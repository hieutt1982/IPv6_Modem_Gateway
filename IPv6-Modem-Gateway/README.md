# IPv6 Modem Gateway 0.6.1 – HYBRID HTTPS PROXY

## Mục tiêu
Android A dùng đồng thời Cellular IPv6 để nhận kết nối từ xa và Wi-Fi để kết nối modem nội bộ. Android B chỉ truy cập URL IPv6 của Android A; không bị điều hướng trực tiếp sang IP LAN của modem.

## Kiến trúc
- Android B -> Android A: HTTPS trên Cellular IPv6, cổng 8080.
- Android A kết thúc TLS tại Gateway, xử lý HTTP request/response.
- Android A -> modem: HTTPS qua Wi-Fi Network, IP modem lấy từ textbox đã lưu, cổng 443.
- Gateway sửa header `Location` trỏ tới IP modem thành đường dẫn tương đối, để trình duyệt tiếp tục ở host IPv6 của Gateway.
- Gateway sửa các URL tuyệt đối trong nội dung HTML chứa IP modem thành URL của Gateway.

## Cài đặt / kiểm thử
1. Build APK từ source này bằng GitHub Actions.
2. Cài lên Android A; nhập IP modem chính xác trong textbox (ví dụ `192.168.10.1`) và bấm LƯU.
3. Bật Gateway khi Android A có Cellular IPv6 và Wi-Fi kết nối modem.
4. Android B tắt Wi-Fi, dùng 4G/5G truy cập URL HTTPS IPv6 do ứng dụng hiển thị.
5. Nếu trình duyệt báo chứng chỉ tự ký, xác nhận cảnh báo nếu Chrome cho phép.

## Quan trọng
- Đây KHÔNG phải TCP Relay thuần. Đây là HTTPS Reverse Proxy/Hybrid Gateway vì cần giải mã HTTP để sửa redirect. TLS pass-through TCP Relay không thể sửa `Location` bên trong TLS.
- IP modem chỉ dùng ở kết nối upstream từ Android A qua Wi-Fi; Android B không được điều hướng sang IP modem.
- Chỉ dùng trong môi trường tin cậy. Gateway xử lý giao diện quản trị modem; giữ token truy cập và không chia sẻ URL/token công khai.
- Chưa xác nhận build APK hoặc kiểm thử thực tế trên thiết bị trong môi trường tạo source này.
