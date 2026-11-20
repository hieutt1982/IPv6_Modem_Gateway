package vn.gateway.ipv6test;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.LinkProperties;
import android.net.LinkAddress;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class MainActivity extends Activity {
    private static final int PORT = 8080;
    private static final int MODEM_HTTPS_PORT = 443;
    private static final String PREFS_NAME = "gateway_settings";
    private static final String PREF_MODEM_IP = "modem_ip";
    private static final String PREF_ACCESS_TOKEN = "access_token_v1";
    private volatile String modemHost = "192.168.1.1";
    private android.widget.EditText modemIpInput;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cellularCallback;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private Network cellularNetwork;
    private Network wifiNetwork;
    private Inet6Address cellularIpv6;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private TextView status;
    private TextView details;
    private TextView logView;
    private File logFile;
    private final Object logLock = new Object();
    private Button startButton;
    private Button stopButton;
    private String accessToken;
    private volatile boolean running = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initLogging();
        log("APP", "onCreate version=0.5.4-ipv6-to-ipv4-gateway");
        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logException("UNCAUGHT", throwable);
            if (oldHandler != null) oldHandler.uncaughtException(thread, throwable);
        });
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        modemHost = prefs.getString(PREF_MODEM_IP, "192.168.1.1");
        // Bounded worker pool: prevent unbounded thread creation from inbound clients.
        // CallerRunsPolicy applies back-pressure to the accept loop when the queue is full.
        executor = new ThreadPoolExecutor(
                2, 16, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64),
                r -> {
                    Thread t = new Thread(r, "Gateway-Worker");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());

        // One persistent token is shared by the screen, copied token, URL and proxy auth.
        // Do not generate a new token on every Activity recreation/app launch.
        String savedToken = prefs.getString(PREF_ACCESS_TOKEN, null);
        if (savedToken == null || !savedToken.matches("[0-9a-f]{32}")) {
            savedToken = makeToken();
            prefs.edit().putString(PREF_ACCESS_TOKEN, savedToken).commit();
        }
        accessToken = savedToken;
        buildUi();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView makeText(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        final int ink = 0xFF183153;
        final int muted = 0xFF667085;
        final int blue = 0xFF245B8F;
        final int pale = 0xFFEEF2F6;
        final int green = 0xFF16804A;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF1F4F7);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(10), dp(12), dp(16));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(15), dp(13), dp(15), dp(13));
        header.setBackgroundColor(ink);
        TextView title = makeText("IPv6 MODEM GATEWAY", 20, 0xFFFFFFFF);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);
        TextView subtitle = makeText("REVERSE PROXY • PHIÊN BẢN 0.5.0", 11, 0xFFD5E3F2);
        subtitle.setPadding(0, dp(5), 0, 0);
        header.addView(subtitle);
        root.addView(header);

        TextView statusLabel = makeText("TRẠNG THÁI", 11, muted);
        statusLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        statusLabel.setPadding(dp(2), dp(16), 0, dp(6));
        root.addView(statusLabel);
        status = makeText("Chưa khởi động gateway", 15, ink);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setPadding(dp(11), dp(10), dp(11), dp(10));
        status.setBackgroundColor(0xFFE8EEF5);
        root.addView(status);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(12), 0, dp(4));
        startButton = new Button(this);
        startButton.setText("KHỞI ĐỘNG GATEWAY");
        startButton.setTextSize(12);
        startButton.setTextColor(0xFFFFFFFF);
        startButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(blue));
        startButton.setOnClickListener(v -> startNetworks());
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        stopButton = new Button(this);
        stopButton.setText("DỪNG");
        stopButton.setTextSize(12);
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopAll());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(dp(104), dp(50));
        stopLp.leftMargin = dp(8);
        actions.addView(stopButton, stopLp);
        root.addView(actions);

        TextView modemHeading = makeText("ĐỊA CHỈ MODEM ĐÍCH", 12, ink);
        modemHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        modemHeading.setPadding(dp(2), dp(14), 0, dp(7));
        root.addView(modemHeading);
        LinearLayout modemCard = new LinearLayout(this);
        modemCard.setOrientation(LinearLayout.VERTICAL);
        modemCard.setPadding(dp(12), dp(10), dp(12), dp(10));
        modemCard.setBackgroundColor(0xFFFFFFFF);
        TextView modemHint = makeText("Nhập IPv4 nội bộ của modem đang kết nối Wi-Fi. Cổng HTTPS mặc định: 443.", 12, muted);
        modemCard.addView(modemHint);
        modemIpInput = new android.widget.EditText(this);
        modemIpInput.setSingleLine(true);
        modemIpInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        modemIpInput.setHint("Ví dụ: 192.168.1.1");
        modemIpInput.setText(modemHost);
        modemIpInput.setSelectAllOnFocus(true);
        modemIpInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams ipLp = new LinearLayout.LayoutParams(-1, dp(50));
        ipLp.topMargin = dp(8);
        modemCard.addView(modemIpInput, ipLp);
        TextView modemPort = makeText("Giao thức: HTTPS     Cổng: 443", 12, blue);
        modemPort.setPadding(0, dp(7), 0, 0);
        modemCard.addView(modemPort);
        Button saveModemIpButton = new Button(this);
        saveModemIpButton.setText("LƯU ĐỊA CHỈ MODEM");
        saveModemIpButton.setTextSize(12);
        saveModemIpButton.setOnClickListener(v -> {
            if (applyModemIpFromInput()) {
                android.widget.Toast.makeText(this, "Đã lưu IP modem: " + modemHost, android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams saveIpLp = new LinearLayout.LayoutParams(-1, dp(44));
        saveIpLp.topMargin = dp(6);
        modemCard.addView(saveModemIpButton, saveIpLp);
        root.addView(modemCard);

        TextView networkHeading = makeText("THÔNG TIN KẾT NỐI", 12, ink);
        networkHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        networkHeading.setPadding(dp(2), dp(14), 0, dp(7));
        root.addView(networkHeading);
        details = makeText("Đang đọc thông tin mạng...", 13, ink);
        details.setTextIsSelectable(true);
        details.setPadding(dp(12), dp(12), dp(12), dp(12));
        details.setBackgroundColor(pale);
        root.addView(details);

        TextView testHeading = makeText("ĐỊA CHỈ TRUY CẬP MODEM TỪ ANDROID B", 12, ink);
        testHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        testHeading.setPadding(dp(2), dp(14), 0, dp(7));
        root.addView(testHeading);
        TextView instructions = makeText(
                "Android B mở link Gateway để xem trực tiếp giao diện modem. " +
                "Gateway chuyển tiếp yêu cầu từ IPv6 Cellular sang modem IPv4 qua Wi-Fi.", 12, muted);
        instructions.setTextIsSelectable(true);
        instructions.setPadding(dp(12), dp(12), dp(12), dp(12));
        instructions.setBackgroundColor(0xFFFFFFFF);
        root.addView(instructions);

        Button copyIpv6Button = new Button(this);
        copyIpv6Button.setText("SAO CHÉP LINK TRUY CẬP MODEM");
        copyIpv6Button.setTextSize(12);
        copyIpv6Button.setOnClickListener(v -> copyGatewayLink(false));
        root.addView(copyIpv6Button);

        Button copyModemButton = new Button(this);
        copyModemButton.setText("SAO CHÉP LINK KIỂM TRA KẾT NỐI MODEM");
        copyModemButton.setTextSize(12);
        copyModemButton.setOnClickListener(v -> copyGatewayLink(true));
        root.addView(copyModemButton);

        Button copyButton = new Button(this);
        copyButton.setText("SAO CHÉP TOKEN RIÊNG");
        copyButton.setTextSize(12);
        copyButton.setOnClickListener(v -> copyAccessToken());
        root.addView(copyButton);

        Button shareLogButton = new Button(this);
        shareLogButton.setText("CHIA SẺ FILE LOG LỖI");
        shareLogButton.setTextSize(12);
        shareLogButton.setOnClickListener(v -> shareLogFile());
        root.addView(shareLogButton);

        Button copyLogButton = new Button(this);
        copyLogButton.setText("SAO CHÉP LOG");
        copyLogButton.setTextSize(12);
        copyLogButton.setOnClickListener(v -> {
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Gateway log", readLog()));
            android.widget.Toast.makeText(this, "Đã sao chép log", android.widget.Toast.LENGTH_SHORT).show();
        });
        root.addView(copyLogButton);

        TextView logHeading = makeText("NHẬT KÝ HOẠT ĐỘNG", 12, ink);
        logHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        logHeading.setPadding(dp(2), dp(14), 0, dp(7));
        root.addView(logHeading);
        logView = makeText("Log sẽ xuất hiện tại đây.", 10, muted);
        logView.setTextIsSelectable(true);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackgroundColor(0xFFFFFFFF);
        root.addView(logView, new LinearLayout.LayoutParams(-1, dp(190)));
        refreshLogView();

        TextView warning = makeText(
                "Gateway nhận kết nối HTTP qua IPv6; đường truyền từ Android A tới modem dùng HTTPS. " +
                "Đường truyền từ B đến A chưa mã hóa, chỉ dùng trên mạng tin cậy.", 11, 0xFF9A5B00);
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        warning.setBackgroundColor(0xFFFFF4DF);
        LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(-1, -2);
        warningLp.topMargin = dp(12);
        root.addView(warning, warningLp);

        setContentView(scroll);
        refreshDetails();
    }

    private boolean applyModemIpFromInput() {
        String value = modemIpInput == null ? modemHost : modemIpInput.getText().toString().trim();
        if (!isPrivateIpv4(value)) {
            if (modemIpInput != null) modemIpInput.setError("Nhập IPv4 nội bộ hợp lệ, ví dụ 192.168.1.1");
            android.widget.Toast.makeText(this,
                    "IP modem không hợp lệ. Chỉ chấp nhận IPv4 mạng nội bộ (10.x, 172.16-31.x, 192.168.x hoặc 100.64-127.x).",
                    android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        modemHost = value;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(PREF_MODEM_IP, modemHost).apply();
        if (modemIpInput != null) modemIpInput.setError(null);
        refreshDetails();
        log("MODEM_CONFIG", "Đã lưu IP modem " + modemHost + ":" + MODEM_HTTPS_PORT);
        return true;
    }

    private boolean isPrivateIpv4(String value) {
        if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) return false;
            }
        } catch (NumberFormatException e) { return false; }
        if (octets[0] == 10) return true;
        if (octets[0] == 192 && octets[1] == 168) return true;
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) return true;
        if (octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127) return true;
        return false;
    }

    private void startNetworks() {
        if (!applyModemIpFromInput()) return;
        log("START", "Người dùng nhấn KHỞI ĐỘNG GATEWAY; modem=" + modemHost + ":" + MODEM_HTTPS_PORT);

        try {
            Intent serviceIntent = new Intent(this, GatewayService.class);
            androidx.core.content.ContextCompat.startForegroundService(this, serviceIntent);
        } catch (Exception e) {
            logException("FOREGROUND_SERVICE", e);
            postStatus("Không thể bật dịch vụ chạy nền: " + e.getMessage());
            startButton.setEnabled(true);
            return;
        }
        if (cellularCallback != null || wifiCallback != null) {
            log("START", "Callback đã đăng ký, bỏ qua lần nhấn lặp");
            return;
        }
        startButton.setEnabled(false);
        status.setText("Đang yêu cầu 4G và theo dõi Wi-Fi...");
        NetworkRequest cellRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        NetworkRequest wifiRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        cellularCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                log("CELLULAR", "onAvailable: " + network);
                cellularNetwork = network;
                updateCellularAddress();
                maybeStartServer();
            }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                log("CELLULAR", "onLinkPropertiesChanged");
                if (network.equals(cellularNetwork)) {
                    updateCellularAddress();
                    maybeStartServer();
                }
            }
            @Override public void onLost(Network network) {
                log("CELLULAR", "onLost: " + network);
                if (network.equals(cellularNetwork)) {
                    cellularNetwork = null;
                    cellularIpv6 = null;
                    stopServerOnly();
                    postStatus("Mạng 4G bị mất.");
                    refreshDetails();
                }
            }
        };
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                log("WIFI", "onAvailable: " + network);
                wifiNetwork = network;
                postStatus("Đã phát hiện Wi-Fi. Đang kiểm tra hai mạng...");
                refreshDetails();
            }
            @Override public void onLost(Network network) {
                log("WIFI", "onLost: " + network);
                if (network.equals(wifiNetwork)) {
                    wifiNetwork = null;
                    postStatus("Wi-Fi bị mất.");
                    refreshDetails();
                }
            }
        };

        try {
            log("NETWORK", "Đăng ký callback Cellular");
            cm.requestNetwork(cellRequest, cellularCallback);
            log("NETWORK", "Đăng ký callback Wi-Fi");

            cm.requestNetwork(wifiRequest, wifiCallback);
        } catch (Exception e) {
            logException("REQUEST_NETWORK", e);
            postStatus("Không thể yêu cầu mạng: " + e.getMessage());
            startButton.setEnabled(true);
        }
    }

    private synchronized void updateCellularAddress() {
        log("IPV6", "Đang đọc LinkProperties Cellular");
        if (cellularNetwork == null) return;
        LinkProperties lp = cm.getLinkProperties(cellularNetwork);
        Inet6Address found = null;
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                InetAddress addr = la.getAddress();
                if (addr instanceof Inet6Address && isGlobal((Inet6Address) addr)) {
                    found = (Inet6Address) addr;
                    break;
                }
            }
        }
        Inet6Address previous = cellularIpv6;
        boolean changed = previous == null ? found != null : !previous.equals(found);
        if (changed) {
            log("IPV6", "Địa chỉ Cellular thay đổi: " +
                    (previous == null ? "(none)" : previous.getHostAddress()) + " -> " +
                    (found == null ? "(none)" : found.getHostAddress()));
            // Close the old listener so the next callback can bind the new global IPv6.
            stopServerOnly();
        }
        cellularIpv6 = found;
        if (found != null) log("IPV6", "IPv6 global hiện tại: " + found.getHostAddress());
        refreshDetails();
    }

    private synchronized void maybeStartServer() {
        log("SERVER", "maybeStartServer running=" + running + ", cellular=" + (cellularNetwork != null) + ", ipv6=" + (cellularIpv6 != null));
        if (running || cellularNetwork == null || cellularIpv6 == null) return;
        final Inet6Address bindAddress = cellularIpv6;
        executor.execute(() -> {
            try {
                log("SERVER", "Đang bind listener vào " + bindAddress.getHostAddress() + ":" + PORT);
                // Bind listener directly to the Cellular IPv6 address.
                // Do NOT bind the whole process to Cellular: modem requests must use Wi-Fi.
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(bindAddress, PORT), 20);
                serverSocket = ss;
                running = true;
                log("SERVER", "Listener đã mở thành công cổng " + PORT);
                postStatus("TCP Server đang nghe trên IPv6 Cellular cổng " + PORT);
                runOnUiThread(() -> {
                    stopButton.setEnabled(true);
                    refreshDetails();
                });
                while (running) {
                    Socket client = ss.accept();
                    log("HTTP", "Client inbound: " + client.getInetAddress());
                    executor.execute(() -> handleClient(client));
                }
            } catch (Exception e) {
                logException("SERVER_BIND_OR_ACCEPT", e);
                postStatus("Không mở được listener IPv6: " + e.getClass().getSimpleName()
                        + " - " + e.getMessage());
                stopServerOnly();
                runOnUiThread(() -> startButton.setEnabled(true));
            }
        });
    }

    private void handleClient(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(25000);
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(s.getInputStream());
            OutputStream clientOut = s.getOutputStream();
            String requestLine = readHttpLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] requestParts = requestLine.split(" ", 3);
            if (requestParts.length < 3) {
                sendHttp(clientOut, "400 Bad Request", "Bad request\n");
                return;
            }
            String method = requestParts[0].toUpperCase(Locale.US);
            String target = requestParts[1];
            java.util.List<String> headers = new java.util.ArrayList<>();
            String line;
            while ((line = readHttpLine(in)) != null && !line.isEmpty()) headers.add(line);

            String tokenPrefix = "/" + accessToken;
            if (target.equals(tokenPrefix) || target.equals(tokenPrefix + "/")) {
                String host = requestHeader(headers, "Host");
                String cookie = "GW_AUTH=" + accessToken + "; Path=/; HttpOnly; SameSite=Lax";
                String location = "/";
                String response = "HTTP/1.1 302 Found\r\nLocation: " + location +
                        "\r\nSet-Cookie: " + cookie +
                        "\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                clientOut.write(response.getBytes(StandardCharsets.US_ASCII));
                clientOut.flush();
                log("AUTH", "Đã cấp cookie truy cập Gateway cho client " + s.getInetAddress());
                return;
            }

            if (target.equals(tokenPrefix + "/modem-test")) {
                String result = testModemOverWifi();
                sendHttp(clientOut, result.startsWith("OK") ? "200 OK" : "502 Bad Gateway", result);
                return;
            }
            if (!hasValidGatewayCookie(headers)) {
                sendHttp(clientOut, "403 Forbidden", "Gateway token missing or invalid. Open the private Gateway link first.\n");
                log("AUTH", "Từ chối request không có token hợp lệ: " + safePath(target));
                return;
            }
            if (target.startsWith("http://") || target.startsWith("https://")) {
                java.net.URI absolute = java.net.URI.create(target);
                target = absolute.getRawPath();
                if (target == null || target.isEmpty()) target = "/";
                if (absolute.getRawQuery() != null) target += "?" + absolute.getRawQuery();
            }
            if (!target.startsWith("/")) target = "/" + target;
            if (target.startsWith("//")) {
                sendHttp(clientOut, "400 Bad Request", "Invalid path\n");
                return;
            }

            Network wifi = wifiNetwork;
            if (wifi == null) {
                sendHttp(clientOut, "503 Service Unavailable", "Wi-Fi modem is not connected.\n");
                return;
            }
            log("PROXY", method + " " + safePath(target) + " -> https://" + modemHost);

            Socket raw = wifi.getSocketFactory().createSocket();
            raw.connect(new InetSocketAddress(modemHost, MODEM_HTTPS_PORT), 8000);
            SSLSocket upstream = (SSLSocket) createModemOnlySslSocketFactory()
                    .createSocket(raw, modemHost, MODEM_HTTPS_PORT, true);
            upstream.setSoTimeout(25000);
            upstream.startHandshake();

            OutputStream upstreamOut = upstream.getOutputStream();
            java.io.BufferedOutputStream bufferedUpOut = new java.io.BufferedOutputStream(upstreamOut);
            String hostHeader = "Host: " + modemHost;
            bufferedUpOut.write((method + " " + target + " HTTP/1.1\r\n").getBytes(StandardCharsets.US_ASCII));
            boolean hasHost = false;
            boolean hasConnection = false;
            boolean hasEncoding = false;
            boolean chunkedRequest = false;
            long contentLength = -1;
            for (String h : headers) {
                int colon = h.indexOf(':');
                if (colon <= 0) continue;
                String name = h.substring(0, colon).trim();
                String lower = name.toLowerCase(Locale.US);
                String value = h.substring(colon + 1).trim();
                if (lower.equals("host")) { hasHost = true; continue; }
                if (lower.equals("connection") || lower.equals("proxy-connection") || lower.equals("keep-alive")) continue;
                if (lower.equals("accept-encoding")) { hasEncoding = true; continue; }
                if (lower.equals("expect")) continue;
                if (lower.equals("origin")) value = "https://" + modemHost;
                if (lower.equals("referer")) {
                    try {
                        java.net.URI ref = java.net.URI.create(value);
                        String rp = ref.getRawPath() == null ? "/" : ref.getRawPath();
                        if (ref.getRawQuery() != null) rp += "?" + ref.getRawQuery();
                        value = "https://" + modemHost + rp;
                    } catch (Exception ignored) { value = "https://" + modemHost + "/"; }
                }
                if (lower.equals("cookie")) {
                    StringBuilder filtered = new StringBuilder();
                    for (String c : value.split(";")) {
                        String part = c.trim();
                        if (part.regionMatches(true, 0, "GW_AUTH=", 0, 8)) continue;
                        if (filtered.length() > 0) filtered.append("; ");
                        filtered.append(part);
                    }
                    if (filtered.length() == 0) continue;
                    value = filtered.toString();
                }
                if (lower.equals("transfer-encoding") && value.toLowerCase(Locale.US).contains("chunked")) chunkedRequest = true;
                if (lower.equals("content-length")) {
                    try { contentLength = Long.parseLong(value); } catch (Exception ignored) { }
                }
                bufferedUpOut.write((name + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            }
            // Reject ambiguous HTTP message framing instead of forwarding a request
            // containing both Transfer-Encoding and Content-Length.
            if (chunkedRequest && contentLength >= 0) {
                sendHttp(clientOut, "400 Bad Request", "Ambiguous request body framing.\n");
                upstream.close();
                return;
            }
            bufferedUpOut.write((hostHeader + "\r\nConnection: close\r\nAccept-Encoding: identity\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            bufferedUpOut.flush();
            if (chunkedRequest) copyChunkedBody(in, bufferedUpOut);
            else if (contentLength > 0) copyExactly(in, bufferedUpOut, contentLength);
            bufferedUpOut.flush();

            java.io.BufferedInputStream upstreamIn = new java.io.BufferedInputStream(upstream.getInputStream());
            String statusLine = readHttpLine(upstreamIn);
            if (statusLine == null) throw new java.io.IOException("Modem returned empty response");
            java.util.List<String> responseHeaders = new java.util.ArrayList<>();
            boolean chunkedResponse = false;
            long responseLength = -1;
            String responseType = "";
            String browserHost = requestHeader(headers, "Host");
            String statusCode = statusLine.length() >= 12 ? statusLine.substring(9, 12) : "200";
            while ((line = readHttpLine(upstreamIn)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase(Locale.US);
                    String value = line.substring(colon + 1).trim();
                    if (name.equals("transfer-encoding") && value.toLowerCase(Locale.US).contains("chunked")) chunkedResponse = true;
                    if (name.equals("content-length")) {
                        try { responseLength = Long.parseLong(value); } catch (Exception ignored) { }
                    }
                    if (name.equals("content-type")) responseType = value.toLowerCase(Locale.US);
                    if (name.equals("connection") || name.equals("keep-alive") || name.equals("proxy-connection")) continue;
                    if (name.equals("transfer-encoding") && !chunkedResponse) continue;
                    if (name.equals("location")) value = rewriteLocation(value);
                    if (name.equals("set-cookie")) value = rewriteCookie(value);
                    if (name.equals("content-length") && !method.equals("HEAD") && !statusCode.equals("204") && !statusCode.equals("304")) continue;
                    responseHeaders.add(line.substring(0, colon + 1) + " " + value);
                } else responseHeaders.add(line);
            }

            boolean noBody = method.equals("HEAD") || statusCode.equals("204") || statusCode.equals("304") || statusCode.startsWith("1");
            byte[] bufferedBody = null;
            boolean textual = responseType.startsWith("text/") || responseType.contains("javascript") ||
                    responseType.contains("json") || responseType.contains("xml") || responseType.contains("svg");
            if (!noBody && textual && responseLength >= 0 && responseLength <= 8 * 1024 * 1024) {
                bufferedBody = readBodyBytes(upstreamIn, responseLength);
                bufferedBody = rewriteModemAbsoluteUrls(bufferedBody, browserHost);
            }

            StringBuilder responseHead = new StringBuilder(statusLine).append("\r\n");
            for (String h : responseHeaders) responseHead.append(h).append("\r\n");
            if (bufferedBody != null) responseHead.append("Content-Length: ").append(bufferedBody.length).append("\r\n");
            else if (!noBody && responseLength >= 0 && !chunkedResponse) responseHead.append("Content-Length: ").append(responseLength).append("\r\n");
            responseHead.append("Connection: close\r\n\r\n");
            clientOut.write(responseHead.toString().getBytes(StandardCharsets.ISO_8859_1));
            clientOut.flush();

            if (!noBody) {
                if (bufferedBody != null) clientOut.write(bufferedBody);
                else if (chunkedResponse) copyChunkedBody(upstreamIn, clientOut);
                else if (responseLength >= 0) copyExactly(upstreamIn, clientOut, responseLength);
                else copyUntilEof(upstreamIn, clientOut);
            }
            clientOut.flush();
            log("PROXY", "Hoàn tất " + method + " " + safePath(target) + " -> HTTP " + statusCode);
            upstream.close();
        } catch (Exception e) {
            logException("REVERSE_PROXY", e);
            try {
                if (!client.isClosed()) sendHttp(client.getOutputStream(), "502 Bad Gateway",
                        "Gateway cannot reach modem: " + e.getClass().getSimpleName() + "\n");
            } catch (Exception ignored) { }
        }
    }

    private byte[] readBodyBytes(java.io.InputStream in, long length) throws Exception {
        if (length > Integer.MAX_VALUE) throw new java.io.IOException("Response body too large");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream((int) length);
        copyExactly(in, out, length);
        return out.toByteArray();
    }

    private byte[] rewriteModemAbsoluteUrls(byte[] body, String browserHost) {
        if (browserHost == null || browserHost.isEmpty()) return body;
        try {
            String text = new String(body, StandardCharsets.UTF_8);
            String gateway = "http://" + browserHost;
            text = text.replace("https://" + modemHost, gateway)
                    .replace("http://" + modemHost, gateway)
                    .replace("//" + modemHost, "//" + browserHost);
            return text.getBytes(StandardCharsets.UTF_8);
        } catch (Exception ignored) { return body; }
    }

    private String readHttpLine(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        int prev = -1, c;
        while ((c = in.read()) != -1) {
            if (prev == '\r' && c == '\n') {
                byte[] bytes = b.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.ISO_8859_1);
            }
            b.write(c);
            prev = c;
            if (b.size() > 16384) throw new java.io.IOException("HTTP line too long");
        }
        return b.size() == 0 ? null : b.toString("ISO-8859-1");
    }

    private String requestHeader(java.util.List<String> headers, String wanted) {
        for (String h : headers) {
            int p = h.indexOf(':');
            if (p > 0 && h.substring(0, p).trim().equalsIgnoreCase(wanted)) return h.substring(p + 1).trim();
        }
        return "";
    }

    private boolean hasValidGatewayCookie(java.util.List<String> headers) {
        String cookies = requestHeader(headers, "Cookie");
        for (String part : cookies.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && pair[0].equals("GW_AUTH") && pair[1].equals(accessToken)) return true;
        }
        return false;
    }

    private String rewriteLocation(String value) {
        try {
            java.net.URI u = java.net.URI.create(value);
            if (u.isAbsolute() && u.getHost() != null &&
                    u.getHost().equalsIgnoreCase(modemHost)) {
                String path = u.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                return path + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery()) +
                        (u.getRawFragment() == null ? "" : "#" + u.getRawFragment());
            }
        } catch (Exception ignored) { }
        return value;
    }

    private String rewriteCookie(String value) {
        String[] parts = value.split(";");
        StringBuilder out = new StringBuilder(parts[0].trim());
        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim();
            String lower = attr.toLowerCase(Locale.US);
            if (lower.startsWith("domain=")) continue;
            if (lower.equals("secure")) continue; // B connects to this Gateway over HTTP; modem-side TLS remains enabled.
            out.append("; ").append(attr);
        }
        return out.toString();
    }

    private String safePath(String path) {
        if (path == null) return "/";
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }

    private void copyExactly(java.io.InputStream in, OutputStream out, long count) throws Exception {
        byte[] buf = new byte[8192];
        long left = count;
        while (left > 0) {
            int n = in.read(buf, 0, (int)Math.min(buf.length, left));
            if (n < 0) throw new java.io.EOFException("Unexpected end of HTTP body");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    private void copyUntilEof(java.io.InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    private void copyChunkedBody(java.io.InputStream in, OutputStream out) throws Exception {
        while (true) {
            String sizeLine = readHttpLine(in);
            if (sizeLine == null) throw new java.io.EOFException("Missing chunk size");
            out.write((sizeLine + "\r\n").getBytes(StandardCharsets.US_ASCII));
            String sizeText = sizeLine.split(";", 2)[0].trim();
            long size = Long.parseLong(sizeText, 16);
            if (size > 0) {
                copyExactly(in, out, size);
                readRequiredCrlf(in, out);
            } else {
                String trailer;
                while ((trailer = readHttpLine(in)) != null && !trailer.isEmpty())
                    out.write((trailer + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                break;
            }
        }
    }

    private void readRequiredCrlf(java.io.InputStream in, OutputStream out) throws Exception {
        int a = in.read(), b = in.read();
        if (a != '\r' || b != '\n') throw new java.io.IOException("Invalid chunk framing");
        out.write('\r'); out.write('\n');
    }

    private String testModemOverWifi() {
        log("MODEM", "Bắt đầu thử kết nối HTTPS tới " + modemHost + ":" + MODEM_HTTPS_PORT);
        Network wifi = wifiNetwork;
        if (wifi == null) return "ERROR: Chưa phát hiện Wi-Fi. Hãy kết nối Wi-Fi modem.\n";
        Socket raw = null;
        SSLSocket tls = null;
        try {
            // SocketFactory from this Network ensures this outbound socket uses Wi-Fi,
            // independently of the phone's default network.
            raw = wifi.getSocketFactory().createSocket();
            raw.connect(new InetSocketAddress(modemHost, MODEM_HTTPS_PORT), 5000);
            // Chỉ dùng SSL bỏ qua chứng chỉ cho kết nối modem nội bộ này.
            // Socket TCP ban đầu vẫn được tạo từ wifi.getSocketFactory(), vì vậy
            // lưu lượng đi qua Wi-Fi; không thay đổi cấu hình mạng toàn ứng dụng.
            tls = (SSLSocket) createModemOnlySslSocketFactory()
                    .createSocket(raw, modemHost, MODEM_HTTPS_PORT, true);
            tls.setSoTimeout(5000);
            log("MODEM", "TCP tới modem thành công; bắt đầu TLS handshake (bỏ qua chứng chỉ riêng modem)");
            tls.startHandshake();
            log("MODEM", "TLS handshake thành công với modem (certificate verification skipped for modem only)");
            OutputStream out = tls.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + modemHost +
                    "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    tls.getInputStream(), StandardCharsets.UTF_8));
            String first = reader.readLine();
            return "OK: Wi-Fi đã kết nối được HTTPS modem.\n" +
                    "Modem response: " + first + "\n";
        } catch (javax.net.ssl.SSLHandshakeException e) {
            logException("MODEM_TLS", e);
            return "ERROR: Không hoàn tất được TLS với modem: " + e.getMessage() + "\n";
        } catch (Exception e) {
            logException("MODEM_TEST", e);
            return "ERROR: Không kết nối được modem qua Wi-Fi: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage() + "\n";
        } finally {
            try { if (tls != null) tls.close(); else if (raw != null) raw.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Trust manager riêng cho HTTPS modem nội bộ.
     * Không dùng factory này cho API Internet hoặc host khác.
     */
    private SSLSocketFactory createModemOnlySslSocketFactory() throws Exception {
        if (!isPrivateIpv4(modemHost) || MODEM_HTTPS_PORT != 443) {
            throw new IllegalStateException("Chỉ cho phép bỏ qua chứng chỉ với IP modem IPv4 nội bộ hợp lệ");
        }
        X509TrustManager modemOnlyTrustManager = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{modemOnlyTrustManager}, new SecureRandom());
        return context.getSocketFactory();
    }

    private void sendHttp(OutputStream out, String code, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + code + "\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    private void copyAccessToken() {
        // Snapshot the exact same immutable token shown in refreshDetails() and used by the proxy.
        final String token = accessToken;
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || token == null || token.isEmpty()) {
            android.widget.Toast.makeText(this, "Không thể sao chép token", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Gateway token", token));
        android.widget.Toast.makeText(this, "Đã sao chép token hiện đang hiển thị", android.widget.Toast.LENGTH_SHORT).show();
        log("COPY_TOKEN", "Đã sao chép token hiện tại");
    }

    private void copyGatewayLink(boolean modemTest) {
        if (modemTest && !applyModemIpFromInput()) return;
        Inet6Address ip = cellularIpv6;
        if (!running || ip == null) {
            android.widget.Toast.makeText(this, "Chưa có IPv6 4G hoặc Gateway chưa chạy.", android.widget.Toast.LENGTH_LONG).show();
            log("COPY_LINK", "Không thể copy: listener hoặc IPv6 chưa sẵn sàng");
            return;
        }
        String host = ip.getHostAddress();
        int zone = host.indexOf('%');
        if (zone >= 0) host = host.substring(0, zone);
        final String token = accessToken;
        String url = "http://[" + host + "]:" + PORT + "/" + token + (modemTest ? "/modem-test" : "/");
        android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cb.setPrimaryClip(android.content.ClipData.newPlainText("Gateway URL", url));
        log("COPY_LINK", "Đã sao chép " + (modemTest ? "modem-test" : "IPv6 test") + " URL");
        android.widget.Toast.makeText(this, "Đã sao chép toàn bộ link", android.widget.Toast.LENGTH_SHORT).show();
    }

    private void refreshDetails() {
        runOnUiThread(() -> {
            if (details == null) return;
            StringBuilder b = new StringBuilder();
            b.append("4G Cellular: ").append(cellularNetwork == null ? "Chưa phát hiện" : "Đã phát hiện").append("\n");
            b.append("IPv6 Cellular: ").append(cellularIpv6 == null ? "Chưa tìm thấy IPv6 global" : cellularIpv6.getHostAddress()).append("\n");
            b.append("Wi-Fi: ").append(wifiNetwork == null ? "Chưa phát hiện" : "Đã phát hiện").append("\n");
            b.append("Modem đích: https://").append(modemHost).append("\n");
            b.append("TCP Listener: ").append(running ? "Đang chạy" : "Chưa chạy").append(" :").append(PORT).append("\n");
            b.append("Token: ").append(accessToken);
            details.setText(b.toString());
        });
    }

    private boolean isGlobal(Inet6Address a) {
        return !a.isAnyLocalAddress() && !a.isLoopbackAddress() &&
                !a.isLinkLocalAddress() && !a.isSiteLocalAddress() && !a.isMulticastAddress();
    }

    private String makeToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder s = new StringBuilder();
        for (byte v : bytes) s.append(String.format(Locale.US, "%02x", v));
        return s.toString();
    }

    private void postStatus(String msg) {
        runOnUiThread(() -> {
            status.setText("Trạng thái: " + msg);
            refreshDetails();
        });
    }

    private void stopServerOnly() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
        runOnUiThread(() -> stopButton.setEnabled(false));
    }

    private void stopAll() {
        log("STOP", "Dừng Gateway và hủy callback mạng");
        stopServerOnly();
        if (cellularCallback != null) {
            try { cm.unregisterNetworkCallback(cellularCallback); } catch (Exception ignored) {}
            cellularCallback = null;
        }
        if (wifiCallback != null) {
            try { cm.unregisterNetworkCallback(wifiCallback); } catch (Exception ignored) {}
            wifiCallback = null;
        }
        cellularNetwork = null;
        wifiNetwork = null;
        cellularIpv6 = null;
        stopService(new Intent(this, GatewayService.class));
        startButton.setEnabled(true);
        status.setText("Đã dừng.");
        refreshDetails();
    }

    @Override protected void onDestroy() {
        // Gateway được giữ bởi Foreground Service. Không dừng listener khi Activity
        // bị đóng hoặc màn hình bị xoay/tái tạo.
        super.onDestroy();
    }

    private void initLogging() {
        try {
            File dir = new File(getFilesDir(), "logs");
            if (!dir.exists() && !dir.mkdirs()) Log.e("IPv6Gateway", "Cannot create log dir");
            logFile = new File(dir, "gateway-log.txt");
            log("LOG", "Log file: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            Log.e("IPv6Gateway", "initLogging failed", e);
        }
    }

    private void log(String tag, String message) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date()) + " [" + tag + "] " + message;
        Log.i("IPv6Gateway", line);
        synchronized (logLock) {
            try {
                if (logFile != null) {
                    FileOutputStream fos = new FileOutputStream(logFile, true);
                    fos.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                    fos.close();
                }
            } catch (Exception e) {
                Log.e("IPv6Gateway", "Cannot write log", e);
            }
        }
        refreshLogView();
    }

    private void logException(String tag, Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        log(tag, sw.toString());
    }

    private String readLog() {
        synchronized (logLock) {
            try {
                if (logFile == null || !logFile.exists()) return "Chưa có log.";
                byte[] bytes = java.nio.file.Files.readAllBytes(logFile.toPath());
                return new String(bytes, StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "Không đọc được log: " + e;
            }
        }
    }

    private void refreshLogView() {
        if (logView == null) return;
        runOnUiThread(() -> {
            String all = readLog();
            if (all.length() > 12000) all = all.substring(all.length() - 12000);
            logView.setText(all);
        });
    }

    private void shareLogFile() {
        try {
            String contents = readLog();
            File export = new File(getCacheDir(), "gateway-log.txt");
            FileOutputStream fos = new FileOutputStream(export, false);
            fos.write(contents.getBytes(StandardCharsets.UTF_8));
            fos.close();
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", export);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "IPv6 Modem Gateway - log lỗi");
            send.putExtra(Intent.EXTRA_TEXT, "File log lỗi Gateway được đính kèm.");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Gửi file log"));
            log("LOG", "Người dùng mở chức năng chia sẻ log");
        } catch (Exception e) {
            logException("SHARE_LOG", e);
            android.widget.Toast.makeText(this, "Không thể chia sẻ log: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

}
