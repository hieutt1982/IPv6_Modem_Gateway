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
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class MainActivity extends Activity {
    private static final int PORT = 8080;
    private static final String MODEM_HOST = "192.168.1.1";
    private static final int MODEM_HTTPS_PORT = 443;

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
        log("APP", "onCreate version=0.3.1");
        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logException("UNCAUGHT", throwable);
            if (oldHandler != null) oldHandler.uncaughtException(thread, throwable);
        });
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        executor = Executors.newCachedThreadPool();
        accessToken = makeToken();
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
        final int ink = 0xFF172B4D;
        final int muted = 0xFF667085;
        final int blue = 0xFF1769AA;
        final int pale = 0xFFF3F6FA;
        final int green = 0xFF16804A;

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFFF7F9FC);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(22));
        scroll.addView(root);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(16), dp(16), dp(16), dp(16));
        header.setBackgroundColor(ink);
        TextView title = makeText("IPv6 MODEM GATEWAY", 20, 0xFFFFFFFF);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title);
        TextView subtitle = makeText("CỔNG KẾT NỐI • PHIÊN BẢN 0.3.0", 11, 0xFFD5E3F2);
        subtitle.setPadding(0, dp(5), 0, 0);
        header.addView(subtitle);
        root.addView(header);

        TextView statusLabel = makeText("TRẠNG THÁI", 11, muted);
        statusLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        statusLabel.setPadding(dp(2), dp(16), 0, dp(6));
        root.addView(statusLabel);
        status = makeText("Chưa khởi động gateway", 15, ink);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setPadding(dp(12), dp(12), dp(12), dp(12));
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

        TextView networkHeading = makeText("THÔNG TIN KẾT NỐI", 12, ink);
        networkHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        networkHeading.setPadding(dp(2), dp(14), 0, dp(7));
        root.addView(networkHeading);
        details = makeText("Đang đọc thông tin mạng...", 13, ink);
        details.setTextIsSelectable(true);
        details.setPadding(dp(12), dp(12), dp(12), dp(12));
        details.setBackgroundColor(pale);
        root.addView(details);

        TextView testHeading = makeText("ĐỊA CHỈ KIỂM TRA TỪ ANDROID B", 12, ink);
        testHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        testHeading.setPadding(dp(2), dp(14), 0, dp(7));
        root.addView(testHeading);
        TextView instructions = makeText(
                "IPv6 sẽ tự động được lấy từ mạng 4G sau khi khởi động Gateway. " +
                "Dùng các nút bên dưới để sao chép nguyên đường dẫn, không cần nhập IPv6 thủ công.", 12, muted);
        instructions.setTextIsSelectable(true);
        instructions.setPadding(dp(12), dp(12), dp(12), dp(12));
        instructions.setBackgroundColor(0xFFFFFFFF);
        root.addView(instructions);

        Button copyIpv6Button = new Button(this);
        copyIpv6Button.setText("SAO CHÉP LINK KIỂM TRA IPV6");
        copyIpv6Button.setTextSize(12);
        copyIpv6Button.setOnClickListener(v -> copyGatewayLink(false));
        root.addView(copyIpv6Button);

        Button copyModemButton = new Button(this);
        copyModemButton.setText("SAO CHÉP LINK KIỂM TRA MODEM");
        copyModemButton.setTextSize(12);
        copyModemButton.setOnClickListener(v -> copyGatewayLink(true));
        root.addView(copyModemButton);

        Button copyButton = new Button(this);
        copyButton.setText("SAO CHÉP TOKEN RIÊNG");
        copyButton.setTextSize(12);
        copyButton.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Gateway token", accessToken));
            android.widget.Toast.makeText(this, "Đã sao chép token", android.widget.Toast.LENGTH_SHORT).show();
        });
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
        root.addView(logView);
        refreshLogView();

        TextView warning = makeText(
                "LƯU Ý: Bản 0.3.0 chỉ kiểm tra đường truyền IPv6 và kết nối HTTPS tới modem. " +
                "Chưa chuyển tiếp giao diện quản trị. Không gửi mật khẩu modem qua HTTP.", 11, 0xFF9A5B00);
        warning.setPadding(dp(12), dp(10), dp(12), dp(10));
        warning.setBackgroundColor(0xFFFFF4DF);
        LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(-1, -2);
        warningLp.topMargin = dp(12);
        root.addView(warning, warningLp);

        setContentView(scroll);
        refreshDetails();
    }

    private void startNetworks() {
        log("START", "Người dùng nhấn KHỞI ĐỘNG GATEWAY");
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
        if (lp == null) return;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress addr = la.getAddress();
            if (addr instanceof Inet6Address && isGlobal((Inet6Address) addr)) {
                cellularIpv6 = (Inet6Address) addr;
                log("IPV6", "Tìm thấy IPv6 global: " + cellularIpv6.getHostAddress());
                break;
            }
        }
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
        log("HTTP", "Bắt đầu xử lý client");
        try (Socket s = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     s.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream out = s.getOutputStream()) {
            s.setSoTimeout(7000);
            String request = in.readLine();
            log("HTTP", "Request: " + request);
            if (request == null) return;
            String line;
            while ((line = in.readLine()) != null && !line.isEmpty()) { }

            String prefix = "/" + accessToken;
            if (request.startsWith("GET " + prefix + "/modem-test ")) {
                String result = testModemOverWifi();
                sendHttp(out, result.startsWith("OK") ? "200 OK" : "502 Bad Gateway", result);
            } else if (request.startsWith("GET " + prefix + " ")) {
                sendHttp(out, "200 OK",
                        "ANDROID A REACHED OVER CELLULAR IPv6\n" +
                        "TCP/HTTP inbound: OK\n" +
                        "Cellular IPv6: " + cellularIpv6.getHostAddress() + "\n" +
                        "Wi-Fi network detected: " + (wifiNetwork != null) + "\n" +
                        "Modem HTTPS test: open /" + accessToken + "/modem-test\n");
            } else {
                sendHttp(out, "404 Not Found", "Not found\n");
            }
        } catch (Exception e) {
            logException("HANDLE_CLIENT", e);
            postStatus("Lỗi xử lý yêu cầu: " + e.getMessage());
        }
    }

    private String testModemOverWifi() {
        log("MODEM", "Bắt đầu thử kết nối HTTPS tới " + MODEM_HOST + ":" + MODEM_HTTPS_PORT);
        Network wifi = wifiNetwork;
        if (wifi == null) return "ERROR: Chưa phát hiện Wi-Fi. Hãy kết nối Wi-Fi modem.\n";
        Socket raw = null;
        SSLSocket tls = null;
        try {
            // SocketFactory from this Network ensures this outbound socket uses Wi-Fi,
            // independently of the phone's default network.
            raw = wifi.getSocketFactory().createSocket();
            raw.connect(new InetSocketAddress(MODEM_HOST, MODEM_HTTPS_PORT), 5000);
            tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(raw, MODEM_HOST, MODEM_HTTPS_PORT, true);
            tls.setSoTimeout(5000);
            log("MODEM", "TCP tới modem thành công; bắt đầu TLS handshake");
            tls.startHandshake(); // Standard certificate validation; no trust-all bypass.
            OutputStream out = tls.getOutputStream();
            out.write(("GET / HTTP/1.1\r\nHost: " + MODEM_HOST +
                    "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    tls.getInputStream(), StandardCharsets.UTF_8));
            String first = reader.readLine();
            return "OK: Wi-Fi đã kết nối được HTTPS modem.\n" +
                    "Modem response: " + first + "\n";
        } catch (javax.net.ssl.SSLHandshakeException e) {
            return "ERROR: Đã tới được cổng HTTPS modem nhưng chứng chỉ TLS không được Android tin cậy " +
                    "(có thể modem dùng chứng chỉ tự ký). Chưa bỏ qua kiểm tra chứng chỉ.\n";
        } catch (Exception e) {
            logException("MODEM_TEST", e);
            return "ERROR: Không kết nối được modem qua Wi-Fi: " +
                    e.getClass().getSimpleName() + " - " + e.getMessage() + "\n";
        } finally {
            try { if (tls != null) tls.close(); else if (raw != null) raw.close(); } catch (Exception ignored) {}
        }
    }

    private void sendHttp(OutputStream out, String code, String body) throws Exception {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + code + "\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: " + data.length + "\r\nConnection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.flush();
    }

    private void copyGatewayLink(boolean modemTest) {
        Inet6Address ip = cellularIpv6;
        if (!running || ip == null) {
            android.widget.Toast.makeText(this, "Chưa có IPv6 4G hoặc Gateway chưa chạy.", android.widget.Toast.LENGTH_LONG).show();
            log("COPY_LINK", "Không thể copy: listener hoặc IPv6 chưa sẵn sàng");
            return;
        }
        String host = ip.getHostAddress();
        int zone = host.indexOf('%');
        if (zone >= 0) host = host.substring(0, zone);
        String url = "http://[" + host + "]:" + PORT + "/" + accessToken + (modemTest ? "/modem-test" : "");
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
            b.append("Modem đích: https://").append(MODEM_HOST).append("\n");
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
        startButton.setEnabled(true);
        status.setText("Đã dừng.");
        refreshDetails();
    }

    @Override protected void onDestroy() {
        stopAll();
        if (executor != null) executor.shutdownNow();
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
