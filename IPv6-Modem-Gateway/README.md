# IPv6 Modem Gateway 0.4.0 – Reverse Proxy tới modem IPv4

## Mục tiêu
Android B dùng trình duyệt qua mạng IPv6 để mở giao diện quản trị modem IPv4 `192.168.1.1`. Android A nhận yêu cầu trên IPv6 Cellular, sau đó chuyển tiếp qua Wi-Fi tới modem bằng HTTPS.

## Luồng mạng
- B → A: HTTP qua địa chỉ IPv6 Cellular của A, cổng 8080.
- A → modem: HTTPS `192.168.1.1:443` qua `wifiNetwork.getSocketFactory()`.
- TLS trust-all chỉ áp dụng cho kết nối modem nội bộ `192.168.1.1:443`; không áp dụng cho Internet/API khác.
- Token được đổi thành cookie Gateway khi mở link riêng lần đầu; các đường dẫn tài nguyên, biểu mẫu và AJAX tiếp theo được proxy qua cùng host.

## Cách dùng
1. Android A kết nối Wi-Fi của modem (địa chỉ modem mặc định trong mã là `192.168.1.1`) và bật dữ liệu di động.
2. Mở app, nhấn **KHỞI ĐỘNG GATEWAY**.
3. Sao chép **LINK TRUY CẬP MODEM** và gửi cho Android B qua kênh riêng.
4. Android B mở link trong trình duyệt. Link sẽ cấp cookie Gateway và chuyển về `/`; trình duyệt hiển thị trang modem được proxy.
5. Đăng nhập và thao tác. Khi dừng Gateway hoặc đổi token/phiên app, cần mở lại link riêng.

## Giới hạn và an toàn
- Kết nối B → A hiện dùng HTTP, không mã hóa. Chỉ dùng qua mạng tin cậy và không chia sẻ link/token công khai. Không dùng trên Wi-Fi công cộng.
- TLS A → modem vẫn là HTTPS; chỉ bỏ xác thực chứng chỉ tự ký cho đúng modem nội bộ đã cấu hình.
- Đây là reverse proxy HTTP/1.1 cơ bản. Nếu firmware modem dùng WebSocket, redirect sang hostname khác, absolute URL nhúng trong JavaScript/CSS, hoặc cơ chế đặc biệt, cần log và tinh chỉnh thêm.
- Không ghi nội dung body, mật khẩu hay cookie modem vào log.
- Địa chỉ IPv6 Cellular có thể thay đổi; khởi động lại Gateway và gửi link mới khi địa chỉ đổi.
