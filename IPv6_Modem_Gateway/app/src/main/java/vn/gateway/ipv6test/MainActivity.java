package vn.gateway.ipv6test;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.util.concurrent.LinkedBlockingQueue;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final int UI_INK = 0xFF222222;
    private volatile int gatewayPort = 8080;
    private volatile int modemPort = 443;
    private static final String PREFS_NAME = "gateway_settings";
    private static final String PREF_MODEM_IP = "modem_ip";
    private static final String PREF_MODEM_PORT = "modem_port";
    private static final String PREF_GATEWAY_PORT = "gateway_port";
    private volatile String modemHost = "192.168.1.1";
    private android.widget.EditText modemIpInput;
    private android.widget.EditText modemPortInput;
    private android.widget.EditText cellularIpv6Input;
    private android.widget.EditText gatewayPortInput;

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
    private TextView accessLinkView;
    private ScrollView logScroll;
    private ScrollView pageScroll;
    private File logFile;
    private final Object logLock = new Object();
    private static final String LOG_CLEAR_MARKER = "\u0000__CLEAR_LOG__\u0000";
    private final LinkedBlockingQueue<String> diskLogQueue = new LinkedBlockingQueue<>(16384);
    private volatile boolean logWriterRunning = true;
    private Thread logWriterThread;
    // UI chỉ giữ một cửa sổ log gần nhất; file log vẫn lưu đầy đủ không giới hạn.
    private static final int MAX_VISIBLE_LOG_LINES = 300;
    private static final long LOG_UI_REFRESH_MS = 500L;
    private final Object displayLogLock = new Object();
    private final ArrayDeque<String> displayLogLines = new ArrayDeque<>();
    private final Handler logUiHandler = new Handler(Looper.getMainLooper());
    private boolean logUiRefreshPending = false;
    private final AtomicLong requestSequence = new AtomicLong(0);
    private Button startButton;
    private Button stopButton;
    private volatile boolean running = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initLogging();
        startLogWriter();
        log("APP", "onCreate version=0.5.8-HTTP-SNIFF-TIMEOUT-FIX");
        final Thread.UncaughtExceptionHandler oldHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            logException("UNCAUGHT", throwable);
            if (oldHandler != null) oldHandler.uncaughtException(thread, throwable);
        });
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        modemHost = prefs.getString(PREF_MODEM_IP, "192.168.1.1");
        modemPort = prefs.getInt(PREF_MODEM_PORT, 443);
        gatewayPort = prefs.getInt(PREF_GATEWAY_PORT, 8080);
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

        buildUi();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView makeText(String value, float size, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size);
        t.setTextColor(color);
        return t;
    }

    private void buildUi() {
        final int ink = 0xFF171717;
        final int muted = 0xFF555555;
        final int line = 0xFF777777;
        final int panel = 0xFFEAEAEA;
        final int green = 0xFF187A32;

        ScrollView page = new ScrollView(this);
        pageScroll = page;
        page.setFillViewport(true);
        page.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        page.setFocusableInTouchMode(true);
        page.setBackgroundColor(0xFFF0F0F0);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(12));
        page.addView(root);

        // Header: classic grayscale, compact and clearly bordered.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setGravity(Gravity.CENTER);
        header.setPadding(dp(12), dp(9), dp(12), dp(9));
        header.setBackground(makeBorderBackground(0xFFF7F7F7, ink, 1, 5));
        TextView title = makeText("IPv6 MODEM GATEWAY", 18, ink);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title);
        TextView subtitle = makeText("REVERSE PROXY  •  TRUY CẬP MODEM QUA IPv6", 10, muted);
        subtitle.setGravity(Gravity.CENTER);
        header.addView(subtitle);
        root.addView(header);

        // Gateway status and actions.
        LinearLayout statusCard = panelCard(root, "TRẠNG THÁI GATEWAY", panel, ink);
        status = makeText("●  ĐANG DỪNG", 14, green);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(8), dp(5), dp(8), dp(5));
        statusCard.addView(status);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        startButton = new Button(this);
        startButton.setText("▶  KHỞI ĐỘNG GATEWAY");
        startButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        startButton.setTextColor(ink);
        startButton.setOnClickListener(v -> startNetworks());
        actions.addView(startButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        stopButton = new Button(this);
        stopButton.setText("■  DỪNG");
        stopButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        stopButton.setEnabled(false);
        stopButton.setOnClickListener(v -> stopAll());
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(dp(112), dp(48));
        stopLp.leftMargin = dp(7);
        actions.addView(stopButton, stopLp);
        statusCard.addView(actions);

        // Four requested settings, in the exact agreed order.
        LinearLayout configCard = panelCard(root, "CẤU HÌNH KẾT NỐI", panel, ink);
        modemIpInput = createConfigInput(modemHost, "Ví dụ: 192.168.1.1", android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        modemPortInput = createConfigInput(String.valueOf(modemPort), "443", android.text.InputType.TYPE_CLASS_NUMBER);
        cellularIpv6Input = createConfigInput("Chưa có IPv6", "IPv6 Cellular tự động hiển thị", android.text.InputType.TYPE_CLASS_TEXT);
        cellularIpv6Input.setFocusable(false);
        cellularIpv6Input.setFocusableInTouchMode(false);
        cellularIpv6Input.setClickable(false);
        cellularIpv6Input.setLongClickable(true);
        cellularIpv6Input.setTextIsSelectable(true);
        cellularIpv6Input.setBackground(makeBorderBackground(0xFFEAEAEA, line, 1, 4));
        gatewayPortInput = createConfigInput(String.valueOf(gatewayPort), "8080", android.text.InputType.TYPE_CLASS_NUMBER);
        addConfigRow(configCard, "1. IP MODEM", modemIpInput);
        addConfigRow(configCard, "2. PORT HTTPS MODEM", modemPortInput);
        addConfigRow(configCard, "3. IPv6 ANDROID A", cellularIpv6Input);
        addConfigRow(configCard, "4. PORT HTTP IPv6", gatewayPortInput);
        Button saveButton = new Button(this);
        saveButton.setText("▣  LƯU CẤU HÌNH");
        saveButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        saveButton.setOnClickListener(v -> {
            if (applyConnectionSettingsFromInputs())
                android.widget.Toast.makeText(this, "Đã lưu cấu hình kết nối", android.widget.Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, dp(46));
        saveLp.topMargin = dp(8);
        configCard.addView(saveButton, saveLp);

        // Requested full-width stacked panels: connection info ABOVE access link.
        // This makes both sections wider and easier to read on a phone screen.
        LinearLayout infoCard = panelCard(root, "THÔNG TIN KẾT NỐI", panel, ink);
        details = makeText("Đang đọc thông tin mạng...", 12, ink);
        details.setPadding(dp(12), dp(12), dp(12), dp(12));
        details.setMinHeight(dp(112));
        details.setBackground(makeBorderBackground(0xFFFFFFFF, line, 1, 4));
        details.setTextIsSelectable(true);
        infoCard.addView(details, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout accessCard = panelCard(root, "ĐỊA CHỈ TRUY CẬP MODEM TỪ ANDROID B", panel, ink);
        accessLinkView = makeText("http://[IPv6 ANDROID A]:" + gatewayPort + "/", 14, ink);
        accessLinkView.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
        accessLinkView.setGravity(Gravity.CENTER);
        accessLinkView.setPadding(dp(8), dp(14), dp(8), dp(14));
        accessLinkView.setBackground(makeBorderBackground(0xFFFFFFFF, line, 1, 4));
        accessCard.addView(accessLinkView, new LinearLayout.LayoutParams(-1, -2));
        Button copyLinkButton = new Button(this);
        copyLinkButton.setText("▢  SAO CHÉP LINK TRUY CẬP");
        copyLinkButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        copyLinkButton.setOnClickListener(v -> copyGatewayLink(false));
        LinearLayout.LayoutParams copyLinkLp = new LinearLayout.LayoutParams(-1, dp(46));
        copyLinkLp.topMargin = dp(7);
        accessCard.addView(copyLinkButton, copyLinkLp);
        Button testModemButton = new Button(this);
        testModemButton.setText("↗  KIỂM TRA KẾT NỐI MODEM");
        testModemButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        testModemButton.setOnClickListener(v -> copyGatewayLink(true));
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(-1, dp(46));
        testLp.topMargin = dp(6);
        accessCard.addView(testModemButton, testLp);

        // Large bordered activity log; retains the 200-line rolling history.
        LinearLayout logCard = panelCard(root, "NHẬT KÝ HOẠT ĐỘNG", panel, ink);
        logScroll = new ScrollView(this);
        logScroll.setFillViewport(false);
        logScroll.setVerticalScrollBarEnabled(true);
        logScroll.setHorizontalScrollBarEnabled(true);
        logScroll.setScrollbarFadingEnabled(false);
        // Keep gestures inside the log panel from being stolen by the outer page ScrollView.
        logScroll.setNestedScrollingEnabled(false);
        logScroll.setOnTouchListener((v, event) -> {
            if (pageScroll != null) {
                int action = event.getActionMasked();
                if (action == android.view.MotionEvent.ACTION_DOWN ||
                        action == android.view.MotionEvent.ACTION_MOVE) {
                    pageScroll.requestDisallowInterceptTouchEvent(true);
                } else if (action == android.view.MotionEvent.ACTION_UP ||
                        action == android.view.MotionEvent.ACTION_CANCEL) {
                    pageScroll.requestDisallowInterceptTouchEvent(false);
                }
            }
            return false;
        });
        logScroll.setBackground(makeBorderBackground(0xFFFFFFFF, ink, 1, 4));
        logView = makeText("Log sẽ xuất hiện tại đây.", 10, ink);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        // Log is read-only; copy is handled by the dedicated button. Avoid focus jumps.
        logView.setFocusable(false);
        logView.setFocusableInTouchMode(false);
        logView.setTextIsSelectable(false);
        logView.setPadding(dp(10), dp(10), dp(10), dp(10));
        logView.setBackgroundColor(0xFFFFFFFF);
        logScroll.addView(logView, new ScrollView.LayoutParams(-1, -2));
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(-1, dp(400));
        logLp.topMargin = dp(4);
        logCard.addView(logScroll, logLp);

        LinearLayout logActions = new LinearLayout(this);
        logActions.setOrientation(LinearLayout.HORIZONTAL);
        Button copyLogButton = new Button(this);
        copyLogButton.setText("▢  SAO CHÉP 200 DÒNG GẦN NHẤT");
        copyLogButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10);
        copyLogButton.setOnClickListener(v -> copyRecentLog());
        logActions.addView(copyLogButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button shareLogButton = new Button(this);
        shareLogButton.setText("SHARE FILE LOG");
        shareLogButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10);
        shareLogButton.setOnClickListener(v -> shareLogFile());
        LinearLayout.LayoutParams shareLp = new LinearLayout.LayoutParams(0, dp(46), 1);
        shareLp.leftMargin = dp(6);
        logActions.addView(shareLogButton, shareLp);
        Button clearLogButton = new Button(this);
        clearLogButton.setText("XÓA LOG");
        clearLogButton.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 10);
        clearLogButton.setOnClickListener(v -> confirmClearLogs());
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(0, dp(46), 0.7f);
        clearLp.leftMargin = dp(6);
        logActions.addView(clearLogButton, clearLp);
        LinearLayout.LayoutParams logActionsLp = new LinearLayout.LayoutParams(-1, -2);
        logActionsLp.topMargin = dp(8);
        logCard.addView(logActions, logActionsLp);

        TextView warning = makeText("LƯU Ý: Kết nối Android B → Android A sử dụng HTTP và không có Token. Chỉ dùng trong môi trường thử nghiệm đáng tin cậy.", 11, muted);
        warning.setPadding(dp(10), dp(8), dp(10), dp(8));
        warning.setBackground(makeBorderBackground(0xFFF7F7F7, line, 1, 4));
        LinearLayout.LayoutParams warningLp = new LinearLayout.LayoutParams(-1, -2);
        warningLp.topMargin = dp(8);
        root.addView(warning, warningLp);

        setContentView(page);
        refreshLogView();
        refreshDetails();
    }

    private LinearLayout panelCard(LinearLayout parent, String heading, int fill, int ink) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(8), dp(7), dp(8), dp(8));
        card.setBackground(makeBorderBackground(0xFFFFFFFF, 0xFF555555, 1, 5));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        parent.addView(card, lp);
        TextView title = makeText(heading, 14, ink);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(dp(7), dp(5), dp(7), dp(8));
        title.setBackground(makeBorderBackground(fill, 0xFF888888, 1, 3));
        card.addView(title);
        return card;
    }

    private void addConfigRow(LinearLayout parent, String label, android.widget.EditText input) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = makeText(label, 12, UI_INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        row.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1.05f));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(42), 1.35f);
        inputLp.leftMargin = dp(6);
        row.addView(input, inputLp);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.topMargin = dp(4);
        parent.addView(row, rowLp);
        View divider = new View(this);
        divider.setBackgroundColor(0xFFCCCCCC);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(-1, dp(1));
        dividerLp.topMargin = dp(3);
        parent.addView(divider, dividerLp);
    }

    private android.graphics.drawable.GradientDrawable makeBorderBackground(int fillColor, int strokeColor, int strokeDp, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private android.widget.EditText createConfigInput(String value, String hint, int inputType) {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setInputType(inputType);
        input.setHint(hint);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        input.setPadding(dp(9), dp(5), dp(9), dp(5));
        input.setBackground(makeBorderBackground(0xFFFFFFFF, 0xFF7B8794, 1, 5));
        return input;
    }

    private void addConfigField(LinearLayout parent, String label, android.widget.EditText input) {
        TextView title = makeText(label, 11, UI_INK);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = dp(9);
        parent.addView(title, titleLp);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(-1, dp(42));
        inputLp.topMargin = dp(3);
        parent.addView(input, inputLp);
    }

    private boolean applyConnectionSettingsFromInputs() {
        String ip = modemIpInput == null ? modemHost : modemIpInput.getText().toString().trim();
        int mp, gp;
        try {
            mp = Integer.parseInt(modemPortInput == null ? String.valueOf(modemPort) : modemPortInput.getText().toString().trim());
            gp = Integer.parseInt(gatewayPortInput == null ? String.valueOf(gatewayPort) : gatewayPortInput.getText().toString().trim());
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Cổng phải là số từ 1 đến 65535.", android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        if (!isPrivateIpv4(ip)) {
            if (modemIpInput != null) modemIpInput.setError("Nhập IPv4 nội bộ hợp lệ");
            return false;
        }
        if (mp < 1 || mp > 65535 || gp < 1 || gp > 65535) {
            android.widget.Toast.makeText(this, "Cổng phải nằm trong khoảng 1–65535.", android.widget.Toast.LENGTH_LONG).show();
            return false;
        }
        modemHost = ip;
        modemPort = mp;
        gatewayPort = gp;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putString(PREF_MODEM_IP, modemHost)
                .putInt(PREF_MODEM_PORT, modemPort)
                .putInt(PREF_GATEWAY_PORT, gatewayPort).apply();
        if (modemIpInput != null) modemIpInput.setError(null);
        refreshDetails();
        log("CONFIG", "Đã lưu modem https://" + modemHost + ":" + modemPort + " ; Gateway IPv6 :" + gatewayPort);
        return true;
    }

    private boolean applyModemIpFromInput() {
        if (modemPortInput != null && gatewayPortInput != null) return applyConnectionSettingsFromInputs();
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
        log("MODEM_CONFIG", "Đã lưu IP modem " + modemHost + ":" + modemPort);
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
        log("START", "Người dùng nhấn KHỞI ĐỘNG GATEWAY; modem=" + modemHost + ":" + modemPort);

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
                log("SERVER", "Đang bind listener vào " + bindAddress.getHostAddress() + ":" + gatewayPort);
                // Bind listener directly to the Cellular IPv6 address.
                // Do NOT bind the whole process to Cellular: modem requests must use Wi-Fi.
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(bindAddress, gatewayPort), 20);
                serverSocket = ss;
                running = true;
                log("SERVER", "Listener đã mở thành công cổng " + gatewayPort);
                postStatus("TCP Server đang nghe trên IPv6 Cellular cổng " + gatewayPort);
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

    // Mỗi HTTP request có mã riêng và thời gian từng công đoạn (không thay đổi luồng proxy).
    private final class RequestTrace {
        final long id = requestSequence.incrementAndGet();
        final long started = System.nanoTime();
        void mark(String phase, String message) {
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            log("TRACE", "[REQ-" + id + "] [" + phase + "] [t+" + elapsedMs + "ms] " + message);
        }
        void failed(String phase, Exception e) {
            mark(phase, "THẤT BẠI: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void copyRecentLog() {
        try {
            String all = readLog();
            String[] lines = all.split("\n");
            StringBuilder out = new StringBuilder();
            int start = Math.max(0, lines.length - 200);
            out.append("===== GATEWAY TRACE: 200 DÒNG GẦN NHẤT =====\n");
            for (int i = start; i < lines.length; i++) out.append(lines[i]).append('\n');
            android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(android.content.ClipData.newPlainText("Gateway trace gần nhất", out.toString()));
            android.widget.Toast.makeText(this, "Đã sao chép tối đa 200 dòng log gần nhất.", android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Không sao chép được log: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void handleClient(Socket client) {
        RequestTrace trace = new RequestTrace();
        trace.mark("01-INBOUND", "Nhận kết nối từ " + client.getInetAddress());
        try (Socket s = client) {
            // Listener này chỉ phục vụ HTTP thường. Một số trình duyệt/thành phần
            // thử mở HTTPS vào cùng cổng; nhận diện TLS ClientHello sớm để không
            // giữ worker 25 giây rồi mới timeout như trước.
            s.setSoTimeout(5000);
            trace.mark("02-READ-REQUEST", "Kiểm tra giao thức và chờ HTTP request line (timeout 5s)");
            java.io.BufferedInputStream in = new java.io.BufferedInputStream(s.getInputStream());
            OutputStream clientOut = s.getOutputStream();
            in.mark(1);
            int firstByte = in.read();
            in.reset();
            // TLS handshake record bắt đầu bằng 0x16; HTTP method bắt đầu bằng ASCII.
            if (firstByte == 0x16) {
                trace.mark("02-PROTOCOL", "CLIENT_PROTOCOL_MISMATCH: nhận TLS handshake trên cổng HTTP; đóng kết nối ngay");
                return;
            }
            if (firstByte < 0) {
                trace.mark("02-READ-REQUEST", "Client đóng kết nối trước khi gửi dữ liệu");
                return;
            }
            // Sau khi xác nhận không phải TLS, giữ timeout dài hơn cho request hợp lệ.
            s.setSoTimeout(25000);
            String requestLine = readHttpLine(in);
            if (requestLine == null || requestLine.isEmpty()) {
                trace.mark("02-READ-REQUEST", "Không nhận được request line; đóng kết nối");
                return;
            }
            trace.mark("02-READ-REQUEST", "Đã nhận: " + requestLine);
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
            trace.mark("03-READ-HEADERS", "Đã đọc " + headers.size() + " header");

            // Gateway không dùng token/cookie: truy cập trực tiếp tại / hoặc /modem-test.
            if (target.equals("/modem-test")) {
                String result = testModemOverWifi();
                sendHttp(clientOut, result.startsWith("OK") ? "200 OK" : "502 Bad Gateway", result);
                return;
            }
            trace.mark("04-GATEWAY-AUTH", "Bỏ qua xác thực Token theo cấu hình không Token");
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
            trace.mark("05-MODEM-TCP", "Bắt đầu kết nối TCP tới " + modemHost + ":" + modemPort);
            Socket raw = wifi.getSocketFactory().createSocket();
            raw.connect(new InetSocketAddress(modemHost, modemPort), 8000);
            trace.mark("05-MODEM-TCP", "TCP kết nối thành công tới modem");
            trace.mark("06-MODEM-TLS", "Bắt đầu TLS handshake (HTTPS)");
            SSLSocket upstream = (SSLSocket) createModemOnlySslSocketFactory()
                    .createSocket(raw, modemHost, modemPort, true);
            upstream.setSoTimeout(25000);
            upstream.startHandshake();
            trace.mark("06-MODEM-TLS", "TLS handshake hoàn tất");

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
            trace.mark("07-MODEM-REQUEST", "Đã gửi HTTP request tới modem: " + method + " " + safePath(target));
            if (chunkedRequest) copyChunkedBody(in, bufferedUpOut);
            else if (contentLength > 0) copyExactly(in, bufferedUpOut, contentLength);
            bufferedUpOut.flush();
            trace.mark("07-MODEM-REQUEST", "Hoàn tất gửi request/body; method=" + method + ", bodyLength=" + contentLength + ", chunked=" + chunkedRequest);

            java.io.BufferedInputStream upstreamIn = new java.io.BufferedInputStream(upstream.getInputStream());
            trace.mark("08-MODEM-FIRST-BYTE", "Đang chờ byte phản hồi đầu tiên từ modem");
            String statusLine = readHttpLine(upstreamIn);
            if (statusLine == null) throw new java.io.IOException("Modem returned empty response");
            trace.mark("08-MODEM-FIRST-BYTE", "Modem bắt đầu trả response: " + statusLine);
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

            trace.mark("09-MODEM-HEADERS", "Đã đọc header phản hồi; HTTP " + statusCode + ", Content-Type=" + responseType + ", Content-Length=" + responseLength);
            boolean noBody = method.equals("HEAD") || statusCode.equals("204") || statusCode.equals("304") || statusCode.startsWith("1");
            byte[] bufferedBody = null;
            boolean textual = responseType.startsWith("text/") || responseType.contains("javascript") ||
                    responseType.contains("json") || responseType.contains("xml") || responseType.contains("svg");
            if (!noBody && textual && responseLength >= 0 && responseLength <= 8 * 1024 * 1024) {
                trace.mark("10-MODEM-BODY", "Bắt đầu đọc nội dung text từ modem; bytes=" + responseLength);
                bufferedBody = readBodyBytes(upstreamIn, responseLength);
                bufferedBody = rewriteModemAbsoluteUrls(bufferedBody, browserHost);
                trace.mark("10-MODEM-BODY", "Đọc và xử lý xong nội dung text; bytes=" + bufferedBody.length);
            } else {
                trace.mark("10-MODEM-BODY", "Chuyển tiếp body trực tiếp; noBody=" + noBody + ", chunked=" + chunkedResponse + ", length=" + responseLength);
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
            trace.mark("11-RETURN-TO-BROWSER", "Đã gửi xong response về Android B; HTTP " + statusCode);
            log("PROXY", "Hoàn tất " + method + " " + safePath(target) + " -> HTTP " + statusCode);
            upstream.close();
        } catch (Exception e) {
            trace.failed("ERROR", e);
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
        log("MODEM", "Bắt đầu thử kết nối HTTPS tới " + modemHost + ":" + modemPort);
        Network wifi = wifiNetwork;
        if (wifi == null) return "ERROR: Chưa phát hiện Wi-Fi. Hãy kết nối Wi-Fi modem.\n";
        Socket raw = null;
        SSLSocket tls = null;
        try {
            // SocketFactory from this Network ensures this outbound socket uses Wi-Fi,
            // independently of the phone's default network.
            raw = wifi.getSocketFactory().createSocket();
            raw.connect(new InetSocketAddress(modemHost, modemPort), 5000);
            // Chỉ dùng SSL bỏ qua chứng chỉ cho kết nối modem nội bộ này.
            // Socket TCP ban đầu vẫn được tạo từ wifi.getSocketFactory(), vì vậy
            // lưu lượng đi qua Wi-Fi; không thay đổi cấu hình mạng toàn ứng dụng.
            tls = (SSLSocket) createModemOnlySslSocketFactory()
                    .createSocket(raw, modemHost, modemPort, true);
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
        if (!isPrivateIpv4(modemHost) || modemPort < 1 || modemPort > 65535) {
            throw new IllegalStateException("Chỉ cho phép bỏ qua chứng chỉ với IP modem IPv4 nội bộ và cổng hợp lệ");
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
        String url = "http://[" + host + "]:" + gatewayPort + (modemTest ? "/modem-test" : "/");
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
            String ipv6Text = cellularIpv6 == null ? "Chưa tìm thấy IPv6 global" : cellularIpv6.getHostAddress();
            b.append("IPv6 Cellular: ").append(ipv6Text).append("\n");
            if (cellularIpv6Input != null) cellularIpv6Input.setText(ipv6Text);
            if (accessLinkView != null) accessLinkView.setText("http://[" + (cellularIpv6 == null ? "IPv6 ANDROID A" : cellularIpv6.getHostAddress()) + "]:" + gatewayPort + "/");
            b.append("Wi-Fi: ").append(wifiNetwork == null ? "Chưa phát hiện" : "Đã phát hiện").append("\n");
            b.append("Modem đích: https://").append(modemHost).append("\n");
            b.append("TCP Listener: ").append(running ? "Đang chạy" : "Chưa chạy").append(" :").append(gatewayPort).append("\n");
            b.append("Xác thực: Tắt Token (truy cập trực tiếp)");
            details.setText(b.toString());
        });
    }

    private boolean isGlobal(Inet6Address a) {
        return !a.isAnyLocalAddress() && !a.isLoopbackAddress() &&
                !a.isLinkLocalAddress() && !a.isSiteLocalAddress() && !a.isMulticastAddress();
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

    private void startLogWriter() {
        logWriterThread = new Thread(() -> {
            ArrayList<String> batch = new ArrayList<>(64);
            while (logWriterRunning || !diskLogQueue.isEmpty()) {
                try {
                    String item = diskLogQueue.poll(500, TimeUnit.MILLISECONDS);
                    if (item != null && LOG_CLEAR_MARKER.equals(item)) {
                        appendDiskBatch(batch);
                        batch.clear();
                        truncateDiskLog();
                    } else if (item != null) {
                        batch.add(item);
                    }
                    // Ghi theo lô, không mở/đóng file cho từng dòng log.
                    if (batch.size() >= 32 || (item == null && !batch.isEmpty())) {
                        appendDiskBatch(batch);
                        batch.clear();
                    }
                } catch (InterruptedException ignored) {
                    if (!logWriterRunning) break;
                }
            }
            appendDiskBatch(batch);
        }, "Gateway-LogWriter");
        logWriterThread.setDaemon(true);
        logWriterThread.start();
    }

    private void appendDiskBatch(ArrayList<String> batch) {
        if (batch.isEmpty() || logFile == null) return;
        synchronized (logLock) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(logFile, true), StandardCharsets.UTF_8), 16 * 1024)) {
                for (String line : batch) writer.write(line + "\n");
                writer.flush();
            } catch (Exception e) {
                Log.e("IPv6Gateway", "Cannot append log batch", e);
            }
        }
    }

    private void truncateDiskLog() {
        synchronized (logLock) {
            try (FileOutputStream ignored = new FileOutputStream(logFile, false)) {
                // Opening in non-append mode truncates the log after all earlier queued lines were flushed.
            } catch (Exception e) {
                Log.e("IPv6Gateway", "Cannot clear log file", e);
            }
        }
    }

    private void log(String tag, String message) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date()) + " [" + tag + "] " + message;
        Log.i("IPv6Gateway", line);
        synchronized (displayLogLock) {
            displayLogLines.addLast(line);
            while (displayLogLines.size() > MAX_VISIBLE_LOG_LINES) displayLogLines.removeFirst();
        }
        synchronized (logLock) {
            if (logWriterRunning && !diskLogQueue.offer(line)) {
                Log.e("IPv6Gateway", "Log queue full; one log line could not be queued");
            }
        }
        refreshLogView();
    }

    private void logException(String tag, Throwable throwable) {
        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        log(tag, sw.toString());
    }

    private void confirmClearLogs() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Xóa nhật ký hoạt động?")
                .setMessage("Toàn bộ log đang lưu sẽ bị xóa. Nên dùng trước khi kiểm tra một modem mới để log chỉ chứa lần kiểm tra tiếp theo.")
                .setNegativeButton("HỦY", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("XÓA LOG", (dialog, which) -> clearLogs())
                .show();
    }

    private void clearLogs() {
        synchronized (logLock) {
            diskLogQueue.clear();
            if (!diskLogQueue.offer(LOG_CLEAR_MARKER)) {
                android.widget.Toast.makeText(this, "Hàng đợi log đang bận, vui lòng thử lại.",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
        }
        synchronized (displayLogLock) { displayLogLines.clear(); }
        if (logView != null) logView.setText("Log đã được xóa. Bắt đầu ghi nhận phiên kiểm tra mới.");
        log("LOG", "========== BẮT ĐẦU PHIÊN LOG MỚI ==========");
        android.widget.Toast.makeText(this, "Đã xóa log cũ. Log mới sẽ được ghi từ bây giờ.",
                android.widget.Toast.LENGTH_SHORT).show();
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
        synchronized (displayLogLock) {
            if (logUiRefreshPending) return;
            logUiRefreshPending = true;
        }
        // Gộp nhiều log phát sinh liên tiếp thành một lần cập nhật UI mỗi 500ms.
        logUiHandler.postDelayed(() -> {
            StringBuilder snapshot = new StringBuilder();
            synchronized (displayLogLock) {
                for (String line : displayLogLines) snapshot.append(line).append('\n');
                logUiRefreshPending = false;
            }
            if (logView == null) return;
            String visible = snapshot.length() == 0 ? "Chưa có log." : snapshot.toString();
            android.text.SpannableString styled = new android.text.SpannableString(visible);
            int lineStart = 0;
            while (lineStart < visible.length()) {
                int lineEnd = visible.indexOf('\n', lineStart);
                if (lineEnd < 0) lineEnd = visible.length();
                int stampEnd = Math.min(lineStart + 23, lineEnd);
                if (stampEnd - lineStart == 23 && isLogTimestamp(visible, lineStart)) {
                    styled.setSpan(new android.text.style.ForegroundColorSpan(0xFFD92D20),
                            lineStart, stampEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    styled.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            lineStart, stampEnd, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                lineStart = lineEnd + 1;
            }
            // Preserve the user's position. Auto-follow only when the user was
            // already at the bottom; never force-scroll while they are reading older logs.
            final int previousLogScrollY = logScroll != null ? logScroll.getScrollY() : 0;
            final int previousPageScrollY = pageScroll != null ? pageScroll.getScrollY() : 0;
            boolean wasAtBottom = false;
            if (logScroll != null && logScroll.getChildCount() > 0) {
                View child = logScroll.getChildAt(0);
                int distanceToBottom = child.getBottom() - (logScroll.getScrollY() + logScroll.getHeight());
                wasAtBottom = distanceToBottom <= dp(24);
            }
            logView.setText(styled);
            if (logScroll != null) {
                final boolean keepFollowing = wasAtBottom;
                logScroll.post(() -> {
                    if (keepFollowing) {
                        int maxY = Math.max(0, logView.getHeight() - logScroll.getHeight());
                        logScroll.scrollTo(0, maxY);
                    } else {
                        logScroll.scrollTo(0, previousLogScrollY);
                    }
                    // Updating text must never move the whole screen/page.
                    if (pageScroll != null && pageScroll.getScrollY() != previousPageScrollY) {
                        pageScroll.scrollTo(0, previousPageScrollY);
                    }
                });
            }
        }, LOG_UI_REFRESH_MS);
    }

    private boolean isLogTimestamp(String value, int start) {
        if (start + 23 > value.length()) return false;
        return value.charAt(start + 4) == '-' && value.charAt(start + 7) == '-' &&
                value.charAt(start + 10) == ' ' && value.charAt(start + 13) == ':' &&
                value.charAt(start + 16) == ':' && value.charAt(start + 19) == '.';
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
