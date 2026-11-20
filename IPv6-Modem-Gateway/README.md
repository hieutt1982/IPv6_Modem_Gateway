# IPv6 Modem Gateway – 0.3.1

## Thay đổi giao diện
- Giao diện tiếng Việt gọn, chia rõ trạng thái, điều khiển, thông tin kết nối và địa chỉ kiểm tra.
- Nút khởi động/dừng rõ ràng; có nút sao chép token.
- Tăng khả năng đọc trên màn hình điện thoại và cho phép chọn sao chép thông tin.

## Chức năng mạng được giữ từ 0.2.0
- Theo dõi mạng Cellular và Wi-Fi đồng thời bằng hai NetworkCallback.
- Mở IPv6 TCP listener gắn trực tiếp vào địa chỉ IPv6 Cellular.
- Không bind toàn bộ tiến trình vào 4G; kết nối modem thử nghiệm dùng SocketFactory của Wi-Fi.
- Endpoint `/TOKEN/modem-test` thử HTTPS tới `192.168.1.1:443` và vẫn xác thực chứng chỉ TLS theo mặc định Android.

## Cách thử
1. Cài APK trên Android A.
2. Bật 4G và kết nối Wi-Fi tới modem.
3. Mở app, nhấn **KHỞI ĐỘNG GATEWAY**.
4. Trên Android B, mở URL IPv6 kiểm tra hiển thị trong ứng dụng.
5. Mở URL có đuôi `/modem-test` để kiểm tra kết nối HTTPS từ A tới modem.

## Giới hạn an toàn
Bản 0.3.1 CHƯA phải reverse proxy giao diện quản trị. Nó chỉ kiểm tra riêng đường truyền B → A qua IPv6 và A → modem qua Wi-Fi/HTTPS. Không nhập hoặc gửi mật khẩu modem qua endpoint HTTP. Nếu modem dùng chứng chỉ tự ký, phép thử TLS có thể báo chứng chỉ không được Android tin cậy.


Version 0.3.1: automatically displays the detected Cellular global IPv6 and complete tokenized test URLs; provides copy buttons for the IPv6 test URL and modem HTTPS diagnostic URL.
