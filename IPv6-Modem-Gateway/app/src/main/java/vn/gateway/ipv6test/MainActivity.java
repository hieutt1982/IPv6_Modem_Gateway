package vn.gateway.ipv6test;

import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
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
    private Button startButton;
    private Button stopButton;
    private String accessToken;
    private volatile boolean running = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
                "1. Kiểm tra IPv6: http://[IPv6-4G-của-A]:8080/" + accessToken +
                "\n\n2. Kiểm tra HTTPS modem: http://[IPv6-4G-của-A]:8080/" + accessToken + "/modem-test" +
                "\n\nThay IPv6-4G-của-A bằng địa chỉ IPv6 hiển thị ở phần thông tin kết nối.", 12, muted);
        instructions.setTextIsSelectable(true);
        instructions.setPadding(dp(12), dp(12), dp(12), dp(12));
        instructions.setBackgroundColor(0xFFFFFFFF);
        root.addView(instructions);

        Button copyButton = new Button(this);
        copyButton.setText("SAO CHÉP TOKEN");
        copyButton.setTextSize(12);
        copyButton.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Gateway token", accessToken));
            android.widget.Toast.makeText(this, "Đã sao chép token", android.widget.Toast.LENGTH_SHORT).show();
        });
        root.addView(copyButton);

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
                cellularNetwork = network;
                updateCellularAddress();
                maybeStartServer();
            }
            @Override public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
                if (network.equals(cellularNetwork)) {
                    updateCellularAddress();
                    maybeStartServer();
                }
            }
            @Override public void onLost(Network network) {
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
                wifiNetwork = network;
                postStatus("Đã phát hiện Wi-Fi. Đang kiểm tra hai mạng...");
                refreshDetails();
            }
            @Override public void onLost(Network network) {
                if (network.equals(wifiNetwork)) {
                    wifiNetwork = null;
                    postStatus("Wi-Fi bị mất.");
                    refreshDetails();
                }
            }
        };

        try {
            cm.requestNetwork(cellRequest, cellularCallback);
            cm.requestNetwork(wifiRequest, wifiCallback);
        } catch (Exception e) {
            postStatus("Không thể yêu cầu mạng: " + e.getMessage());
            startButton.setEnabled(true);
        }
    }

    private synchronized void updateCellularAddress() {
        if (cellularNetwork == null) return;
        LinkProperties lp = cm.getLinkProperties(cellularNetwork);
        if (lp == null) return;
        for (LinkAddress la : lp.getLinkAddresses()) {
            InetAddress addr = la.getAddress();
            if (addr instanceof Inet6Address && isGlobal((Inet6Address) addr)) {
                cellularIpv6 = (Inet6Address) addr;
                break;
            }
        }
        refreshDetails();
    }

    private synchronized void maybeStartServer() {
        if (running || cellularNetwork == null || cellularIpv6 == null) return;
        executor.execute(() -> {
            try {
                // Bind listener directly to the Cellular IPv6 address.
                // Do NOT bind the whole process to Cellular: modem requests must use Wi-Fi.
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(cellularIpv6, PORT), 20);
                serverSocket = ss;
                running = true;
                postStatus("TCP Server đang nghe trên IPv6 Cellular cổng " + PORT);
                runOnUiThread(() -> {
                    stopButton.setEnabled(true);
                    refreshDetails();
                });
                while (running) {
                    Socket client = ss.accept();
                    executor.execute(() -> handleClient(client));
                }
            } catch (Exception e) {
                postStatus("Không mở được listener IPv6: " + e.getClass().getSimpleName()
                        + " - " + e.getMessage());
                stopServerOnly();
                runOnUiThread(() -> startButton.setEnabled(true));
            }
        });
    }

    private void handleClient(Socket client) {
        try (Socket s = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(
                     s.getInputStream(), StandardCharsets.US_ASCII));
             OutputStream out = s.getOutputStream()) {
            s.setSoTimeout(7000);
            String request = in.readLine();
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
            postStatus("Lỗi xử lý yêu cầu: " + e.getMessage());
        }
    }

    private String testModemOverWifi() {
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

    private void refreshDetails() {
        if (details == null) return;
        StringBuilder b = new StringBuilder();
        b.append("4G Cellular: ").append(cellularNetwork == null ? "Chưa phát hiện" : "Đã phát hiện").append("\n");
        b.append("IPv6 Cellular: ").append(cellularIpv6 == null ? "Chưa tìm thấy IPv6 global" : cellularIpv6.getHostAddress()).append("\n");
        b.append("Wi-Fi: ").append(wifiNetwork == null ? "Chưa phát hiện" : "Đã phát hiện").append("\n");
        b.append("Modem đích: https://").append(MODEM_HOST).append("\n");
        b.append("TCP Listener: ").append(running ? "Đang chạy" : "Chưa chạy").append(" :").append(PORT).append("\n");
        b.append("Token: ").append(accessToken);
        details.setText(b.toString());
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
}
