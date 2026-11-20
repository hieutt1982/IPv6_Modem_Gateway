# IPv6 Modem Gateway 0.5.2 – Configurable Modem IPv4

## Mục tiêu
Android B truy cập giao diện modem thông qua IPv6 Cellular của Android A. Android A nhận HTTP request trên IPv6, sau đó kết nối tới modem qua Wi-Fi bằng socket thuộc riêng `wifiNetwork`.

## Những phần đã có trong mã nguồn
- Listener IPv6 Cellular, cổng TCP 8080.
- Kết nối upstream qua Wi-Fi riêng, không đổi default network của toàn ứng dụng.
- Reverse proxy HTTP/1.1 cơ bản: method, path/query, header, body theo Content-Length/chunked; trả status, header và body modem.
- HTTPS upstream tới IP modem IPv4 do người dùng nhập, cổng cố định `443`; bỏ xác thực chứng chỉ tự ký chỉ trên socket modem nội bộ.
- Token truy cập được tạo một lần và lưu trong SharedPreferences; màn hình, nút sao chép token, URL truy cập và xác thực proxy dùng chung đúng một giá trị. Token không tự đổi khi mở lại ứng dụng.
- Token truy cập riêng đổi thành cookie Gateway; không ghi password/cookie vào log.
- Chuyển hướng Location và cookie Domain/Secure cơ bản.
- Foreground Service và thông báo thường trực; giữ CPU/Wi-Fi lock khi người dùng bật Gateway để hỗ trợ hoạt động khi tắt màn hình/đóng Activity.
- Giao diện classic gọn, nền sáng, tiêu đề xanh đậm, vùng trạng thái/kết nối/log rõ ràng.

## Cách dùng
1. Android A kết nối Wi-Fi modem và bật dữ liệu di động.
2. Mở ứng dụng, nhập IPv4 nội bộ của modem (ví dụ `192.168.1.1`, `192.168.0.1`, `192.168.100.1`) rồi nhấn **LƯU ĐỊA CHỈ MODEM**.
3. Cổng upstream HTTPS hiện cố định là `443`.
4. Nhấn **KHỞI ĐỘNG GATEWAY** và chờ phát hiện IPv6 global của 4G và Wi-Fi.
5. Nhấn **SAO CHÉP LINK TRUY CẬP MODEM**, gửi link riêng cho Android B.
6. Android B mở link, đăng nhập modem và thao tác.
7. Muốn dừng hẳn, mở ứng dụng và nhấn **DỪNG**. Thông báo thường trực sẽ biến mất.

IP được lưu trong ứng dụng và tự điền ở lần mở sau. Có thể đổi IP khi Gateway đang chạy; nút Lưu cập nhật đích proxy cho các request tiếp theo.

## Chạy nền và giới hạn hệ điều hành
- Đóng Activity hoặc tắt màn hình: Foreground Service được thiết kế để giữ Gateway hoạt động.
- Vuốt ứng dụng khỏi màn hình đa nhiệm thường không đồng nghĩa Force Stop; hành vi cụ thể còn tùy hãng/ROM và chế độ tiết kiệm pin.
- Force Stop, khởi động lại điện thoại, tắt Wi-Fi/4G, hoặc hệ điều hành chặn dịch vụ vẫn làm Gateway dừng. Phiên bản này chưa tự khôi phục listener sau khi Android kill process/reboot.
- CPU/Wi-Fi lock làm tăng tiêu thụ pin khi Gateway chạy. Chỉ bật trong thời gian cần truy cập modem.

## Bảo mật quan trọng
- Android B → A hiện là HTTP, không mã hóa. Không dùng trên Wi-Fi công cộng và không gửi link/token công khai. Tài khoản/mật khẩu modem đi qua đoạn này có thể bị nghe lén trên mạng trung gian.
- A → modem dùng HTTPS. Trust-all chỉ dùng cho kết nối upstream tới IPv4 mạng nội bộ đã được kiểm tra; không áp dụng cho kết nối Internet khác.
- Token là lớp kiểm soát truy cập cơ bản, không thay thế HTTPS đầu cuối.

## Các giới hạn proxy cần kiểm thử theo firmware modem
- IP modem có thể nhập thủ công và lưu lại. Chỉ hỗ trợ IPv4 thuộc các dải nội bộ 10/8, 172.16/12, 192.168/16 và CGNAT 100.64/10. Không hỗ trợ IPv6 modem, hostname, HTTP upstream hoặc cổng tùy chỉnh; upstream vẫn là HTTPS:443.
- Reverse proxy hiện là HTTP/1.1 cơ bản. WebSocket, URL tuyệt đối nhúng trong JavaScript/CSS, API gọi hostname/IP cố định, redirect sang host khác hoặc cơ chế xác thực đặc biệt có thể cần rewrite riêng.
- Chưa có kiểm thử thực tế với giao diện modem từ Android B trong gói này. Cần thử đăng nhập, đổi trang, lưu cấu hình, refresh, timeout và logout trên đúng model/firmware.
- Không mở cổng 8080 ra Internet công cộng nếu chưa có HTTPS và xác thực mạnh.

## Build
Mã nguồn Android Gradle project. Môi trường tạo gói này không có Android SDK/Gradle wrapper để biên dịch APK; ZIP là source project, chưa phải APK đã build.




## Version 0.5.3 – IPv6[B] → IPv6[A] → IPv4[Modem]

- Updates the listener when the Cellular global IPv6 address changes.
- Uses a bounded worker pool to avoid unlimited thread creation under load.
- Rejects ambiguous inbound HTTP framing that contains both `Transfer-Encoding` and `Content-Length`.
- Keeps the existing reverse-proxy design: inbound listener bound to the Cellular IPv6 address; upstream socket explicitly created on the Wi-Fi `Network`.
- No modem API, JSON, authentication API, CSRF, or vendor-specific request-body logic is added.

The proxy still performs limited HTTP proxy compatibility handling (upstream Host/Origin/Referer and browser-facing redirect/cookie scope) so a modem web interface can be displayed through a different public origin. Request and response bodies are streamed without JSON parsing or application-level modification.
