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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PORT = 8080;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback cellularCallback;
    private Network cellularNetwork;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private TextView status;
    private TextView details;
    private Button startButton;
    private Button stopButton;
    private String accessToken;
    private volatile boolean running = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        executor = Executors.newCachedThreadPool();
        accessToken = makeToken();
        buildUi();
        refreshNetworkInfo();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);

        TextView title = new TextView(this);
        title.setText("IPv6 MODEM GATEWAY");
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 8, 0, 8);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Bản thử nghiệm 0.1.0 • Android A");
        subtitle.setTextSize(14);
        subtitle.setGravity(Gravity.CENTER);
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 24, 0, 16);
        status.setText("Trạng thái: Chưa khởi động");
        root.addView(status);

        details = new TextView(this);
        details.setTextSize(14);
        details.setTextIsSelectable(true);
        details.setPadding(0, 8, 0, 20);
        root.addView(details);

        startButton = new Button(this);
        startButton.setText("GIỮ 4G VÀ MỞ TCP SERVER");
        startButton.setOnClickListener(v -> startGatewayTest());
        root.addView(startButton);

        stopButton = new Button(this);
        stopButton.setText("DỪNG THỬ NGHIỆM");
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopGatewayTest());
        root.addView(stopButton);

        TextView note = new TextView(this);
        note.setText("\nAndroid B thử bằng trình duyệt:\nhttp://[IPv6-của-A]:8080/" + accessToken +
                "\n\nChỉ dùng để kiểm tra kết nối. Không nhập mật khẩu modem hoặc dữ liệu nhạy cảm. " +
                "HTTP không mã hóa; hãy dừng server sau khi thử.");
        note.setTextSize(13);
        note.setPadding(0, 18, 0, 0);
        root.addView(note);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void startGatewayTest() {
        if (running) return;
        status.setText("Đang yêu cầu Android duy trì mạng Cellular...");
        startButton.setEnabled(false);

        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();

        cellularCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                cellularNetwork = network;
                runOnUiThread(() -> {
                    status.setText("Đã tìm thấy mạng Cellular. Đang mở TCP Server...");
                    refreshNetworkInfo();
                });
                executor.execute(() -> openServerOnCellular(network));
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties props) {
                if (network.equals(cellularNetwork)) {
                    runOnUiThread(() -> refreshNetworkInfo());
                }
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(cellularNetwork)) {
                    runOnUiThread(() -> status.setText("Mạng Cellular bị mất."));
                    stopServerOnly();
                }
            }
        };

        try {
            cm.requestNetwork(request, cellularCallback);
        } catch (Exception e) {
            startButton.setEnabled(true);
            status.setText("Không yêu cầu được Cellular: " + e.getMessage());
        }
    }

    private void openServerOnCellular(Network network) {
        try {
            // Chỉ bind process của APK thử nghiệm vào Cellular.
            // Không thay đổi mạng mặc định của toàn bộ điện thoại.
            boolean bound = cm.bindProcessToNetwork(network);
            if (!bound) {
                postStatus("Không bind được tiến trình vào Cellular.");
                return;
            }

            ServerSocket ss = new ServerSocket(PORT, 20, InetAddress.getByName("::"));
            serverSocket = ss;
            running = true;

            postStatus("TCP Server đang nghe cổng " + PORT +
                    ". Kiểm tra IPv6 Public từ Android B.");
            runOnUiThread(() -> {
                startButton.setEnabled(false);
                stopButton.setEnabled(true);
                refreshNetworkInfo();
            });

            while (running) {
                Socket client = ss.accept();
                executor.execute(() -> handleClient(client));
            }
        } catch (Exception e) {
            postStatus("Không mở được TCP Server: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage());
            stopServerOnly();
            runOnUiThread(() -> startButton.setEnabled(true));
        }
    }

    private void handleClient(Socket client) {
        try (Socket s = client;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             OutputStream out = s.getOutputStream()) {

            s.setSoTimeout(8000);
            String requestLine = reader.readLine();
            if (requestLine == null) return;

            // Đọc hết HTTP headers.
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) { }

            String expectedPath = "/" + accessToken;
            boolean authorized = requestLine.startsWith("GET " + expectedPath + " ");

            String body;
            String code;
            if (authorized) {
                body = "HELLO FROM ANDROID A\nIPv6 TCP/HTTP test successful.\n";
                code = "200 OK";
                postStatus("Có kết nối từ Android B: " + s.getInetAddress());
            } else {
                body = "Unauthorized\n";
                code = "403 Forbidden";
                postStatus("Có kết nối nhưng token không hợp lệ.");
            }

            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            String headers = "HTTP/1.1 " + code + "\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Content-Length: " + bytes.length + "\r\n" +
                    "Connection: close\r\n\r\n";
            out.write(headers.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            postStatus("Lỗi xử lý kết nối: " + e.getMessage());
        }
    }

    private void stopGatewayTest() {
        stopServerOnly();
        if (cellularCallback != null) {
            try { cm.unregisterNetworkCallback(cellularCallback); } catch (Exception ignored) {}
            cellularCallback = null;
        }
        try { cm.bindProcessToNetwork(null); } catch (Exception ignored) {}
        cellularNetwork = null;
        running = false;
        status.setText("Đã dừng thử nghiệm.");
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
        refreshNetworkInfo();
    }

    private void stopServerOnly() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        serverSocket = null;
        try { cm.bindProcessToNetwork(null); } catch (Exception ignored) {}
        runOnUiThread(() -> {
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        });
    }

    private void refreshNetworkInfo() {
        StringBuilder b = new StringBuilder();
        b.append("IPv6 của Cellular:\n");
        boolean found = false;
        try {
            if (cellularNetwork != null) {
                LinkProperties lp = cm.getLinkProperties(cellularNetwork);
                if (lp != null) {
                    b.append("Interface: ").append(lp.getInterfaceName()).append("\n");
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (a instanceof Inet6Address && isGlobalCandidate((Inet6Address) a)) {
                            b.append(a.getHostAddress()).append("\n");
                            found = true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            b.append("Không đọc được: ").append(e.getMessage()).append("\n");
        }
        if (!found) b.append("Chưa tìm thấy IPv6 Global qua Cellular.\n");

        b.append("\nWi-Fi:\n");
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            b.append("Wi-Fi đang là mạng mặc định.\n");
        } else {
            b.append("Wi-Fi không phải mạng mặc định hoặc chưa kết nối.\n");
        }
        b.append("\nCổng TCP: ").append(PORT);
        b.append("\nToken thử nghiệm: ").append(accessToken);
        details.setText(b.toString());
    }

    private boolean isGlobalCandidate(Inet6Address a) {
        return !a.isAnyLocalAddress() && !a.isLoopbackAddress()
                && !a.isLinkLocalAddress() && !a.isSiteLocalAddress()
                && !a.isMulticastAddress();
    }

    private String makeToken() {
        byte[] data = new byte[16];
        new SecureRandom().nextBytes(data);
        StringBuilder s = new StringBuilder();
        for (byte v : data) s.append(String.format(Locale.US, "%02x", v));
        return s.toString();
    }

    private void postStatus(String message) {
        runOnUiThread(() -> status.setText("Trạng thái: " + message));
    }

    @Override
    protected void onDestroy() {
        stopGatewayTest();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }
}
