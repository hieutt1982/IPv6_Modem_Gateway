# IPv6 Modem Gateway 0.6.0 – TCP Tunnel

## Mục tiêu
Android B truy cập modem qua IPv6 Cellular của Android A. Android A chỉ chuyển tiếp TCP nguyên bản từ kết nối Cellular sang modem qua Wi-Fi. APK không giải mã TLS, không đọc/sửa HTTP, không sửa Header, Cookie, Location hoặc nội dung HTML.

## Luồng kết nối
1. Android B mở `https://[IPv6-Cellular-cua-Android-A]:<PORT-TCP-TUNNEL>/`.
2. Listener trên Android A nhận TCP tại địa chỉ IPv6 Cellular.
3. APK tạo socket đích bằng `wifiNetwork.getSocketFactory()` tới IP modem và cổng đã cấu hình.
4. Hai luồng byte được chuyển tiếp song song hai chiều cho đến khi kết nối đóng.

## Phần được giữ nguyên
- Giao diện classic, cấu trúc các khu vực, nút bấm, cấu hình và lưu SharedPreferences.
- Cơ chế yêu cầu và theo dõi riêng Cellular và Wi-Fi bằng NetworkCallback.
- Tự tìm IPv6 global của Cellular và tự mở lại listener khi địa chỉ IPv6 thay đổi.
- Cổng Gateway có thể cấu hình; mặc định vẫn là `8080`.
- IP modem và cổng modem có thể cấu hình; mặc định `192.168.1.1:443`.
- Foreground Service, log, sao chép log, chia sẻ log và xóa log.

## Thay đổi duy nhất về đường truyền
- Bỏ HTTP Reverse Proxy và toàn bộ xử lý request/response, rewrite URL, Cookie, Header, HTML.
- Thay bằng TCP Tunnel hai chiều theo mô hình socket-to-socket: một socket nhận từ Cellular và một socket đích được tạo trên Wi-Fi.
- TLS/HTTPS đi xuyên suốt từ trình duyệt Android B tới modem; Android A không thực hiện TLS handshake cho phiên Tunnel.
- Khi dừng Gateway hoặc mất/đổi IPv6 Cellular, listener và các phiên Tunnel đang mở được đóng.

## Cách dùng
1. Android A kết nối Wi-Fi của modem và bật dữ liệu di động.
2. Mở APK, nhập IP modem và cổng HTTPS modem (thường `443`); cổng TCP Tunnel mặc định `8080`.
3. Nhấn **LƯU CẤU HÌNH**, sau đó **KHỞI ĐỘNG GATEWAY**.
4. Chờ ứng dụng phát hiện IPv6 global Cellular và Wi-Fi.
5. Nhấn **SAO CHÉP LINK TRUY CẬP**, gửi link `https://[IPv6]:8080/` cho Android B.
6. Mở link trên Android B. Vì chứng chỉ modem thường là chứng chỉ tự ký hoặc cấp cho IP LAN `192.168.1.1`, trình duyệt có thể cảnh báo chứng chỉ không đáng tin cậy/không khớp IPv6. Đây là hệ quả của TLS end-to-end, không phải lỗi HTTP Proxy.
7. Nhấn **DỪNG** để đóng listener và các phiên TCP Tunnel.

## Lưu ý quan trọng về chứng chỉ và tương thích
- APK không còn bỏ qua chứng chỉ TLS cho phiên truy cập từ Android B. Trình duyệt B trực tiếp kiểm tra chứng chỉ modem. Nếu trình duyệt không cho phép tiếp tục sau cảnh báo chứng chỉ, cần dùng chứng chỉ hợp lệ phù hợp với tên/IP truy cập hoặc cơ chế tin cậy chứng chỉ trên thiết bị B.
- Vì không sửa HTTP/HTML, các redirect, cookie, URL tuyệt đối hoặc hostname/IP cố định do modem trả về được giữ nguyên. Một số giao diện modem có thể yêu cầu truy cập trực tiếp qua IP LAN hoặc hostname riêng và không hoạt động hoàn toàn qua IPv6 Tunnel.
- Cổng Gateway không có Token; bất kỳ thiết bị nào kết nối được tới IPv6 và cổng này đều có thể tạo TCP Tunnel tới modem. Chỉ bật trong môi trường tin cậy; nên giới hạn truy cập bằng firewall/chính sách mạng phù hợp.
- Chỉ hỗ trợ đích modem IPv4 nội bộ theo kiểm tra cấu hình hiện có; không đổi cơ chế lựa chọn mạng mặc định của Android.

## Build
Mở thư mục `IPv6_Modem_Gateway` bằng Android Studio hoặc chạy workflow GitHub Actions hiện có để build `assembleDebug`.
