# IPv6 Modem Gateway 0.3.1 – bản ghi log chẩn đoán

## Thay đổi
- Giữ giao diện tiếng Việt, nút khởi động/dừng và các nút sao chép link IPv6, link kiểm tra modem, token.
- Thêm nhật ký hoạt động ghi vào bộ nhớ riêng của ứng dụng: `files/logs/gateway-log.txt`.
- Thêm nút **CHIA SẺ FILE LOG LỖI** để gửi file log qua ứng dụng chia sẻ trên Android.
- Thêm nút **SAO CHÉP LOG** để sao chép nội dung log.
- Ghi lỗi kèm Stack Trace cho lỗi trong luồng ứng dụng, lỗi đăng ký mạng, bind TCP và kiểm tra modem.
- Chuyển cập nhật giao diện thông tin mạng về UI thread để tránh cập nhật TextView từ callback thread.
- Chặn nhấn khởi động lặp khi callback mạng đã đăng ký.

## Cách lấy log
1. Cài APK và mở ứng dụng.
2. Nhấn **KHỞI ĐỘNG GATEWAY** để tái hiện lỗi.
3. Mở lại ứng dụng nếu bị thoát.
4. Nhấn **CHIA SẺ FILE LOG LỖI**.
5. Chọn Gmail/Zalo/ChatGPT hoặc lưu file, sau đó gửi `gateway-log.txt`.

Nếu ứng dụng crash trước khi Activity mở, Android vẫn có thể ghi log hệ thống; phần log ứng dụng sẽ ghi được từ lúc `onCreate` bắt đầu.

## Chức năng mạng
- Theo dõi Cellular và Wi-Fi bằng hai NetworkCallback.
- Listener IPv6 gắn trực tiếp vào địa chỉ IPv6 Cellular.
- Không bind toàn bộ process vào Cellular; kết nối modem thử nghiệm sử dụng SocketFactory của Wi-Fi.
- `/TOKEN/modem-test` thử HTTPS tới `192.168.1.1:443` và giữ xác thực chứng chỉ TLS mặc định.

## Giới hạn
Đây vẫn là bản kiểm tra đường truyền, chưa phải reverse proxy giao diện quản trị modem. Nếu modem dùng chứng chỉ TLS tự ký, kiểm tra HTTPS có thể báo lỗi chứng chỉ.
