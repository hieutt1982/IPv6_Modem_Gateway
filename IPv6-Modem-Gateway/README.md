# IPv6 Modem Gateway 0.6.0 — TCP Relay (TLS pass-through)

## Nền tảng
Bản này được tạo riêng từ source `0.5.9-HTTPS-TEST-SOURCE` người dùng cung cấp.
Không ghi đè bản 0.5.9.

## Cách hoạt động
1. Android A nhận kết nối TCP tại IPv6 Cellular, cổng 8080.
2. Android A tạo kết nối TCP tới modem `IP_MODEM:443` bằng `wifiNetwork.getSocketFactory()`.
3. Chuyển tiếp byte hai chiều giữa hai socket, không giải mã TLS.

Android B dùng Chrome truy cập:
`https://[IPV6-CELLULAR-ANDROID-A]:8080/`

## Khác biệt quan trọng so với Reverse Proxy 0.5.9
- Không có SSLServerSocket / không kết thúc TLS tại Android A.
- Không đọc HTTP request, không rewrite URL/header/cookie.
- Không xác thực token Gateway.
- Không cấp cookie Gateway.
- Không có endpoint `/modem-test`; cả hai nút sao chép link đều trả cùng URL relay.
- Chứng chỉ TLS của modem được chuyển tiếp nguyên trạng tới Android B. Gateway không dùng `gateway_https.p12`.

## Cảnh báo bắt buộc trước khi thử
1. Vì không có token, bất kỳ thiết bị nào truy cập được IPv6:8080 đều có thể mở kết nối tới cổng HTTPS modem. Chỉ bật khi thực sự cần, dừng ngay sau khi dùng.
2. Chrome sẽ kiểm tra chứng chỉ của modem, không phải chứng chỉ Gateway. Nếu chứng chỉ modem được cấp cho `192.168.1.1` hoặc không có SAN khớp IPv6 công khai của Android A, Chrome có thể báo lỗi tên chứng chỉ hoặc từ chối kết nối. Không có cách sửa lỗi này ở lớp TCP relay mà vẫn giữ TLS pass-through.
3. Relay chỉ hoạt động nếu mạng di động cho phép kết nối IPv6 inbound tới Android A và modem HTTPS thực sự lắng nghe tại cổng 443.
4. Mã nguồn chưa được build APK trong môi trường này. Cần build và thử trên hai điện thoại thực tế.

## Cấu trúc mạng
`Android B / Chrome --TCP/TLS--> Android A:8080 --TCP qua Wi-Fi--> Modem:443`

Android A chỉ chuyển tiếp byte; TLS handshake thực tế diễn ra giữa Android B và modem.
