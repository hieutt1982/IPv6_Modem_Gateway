# IPv6 Modem Gateway – Test APK 0.1.0

Ứng dụng Android Java thử nghiệm khả năng:
- Yêu cầu duy trì mạng Cellular khi Wi-Fi đang bật.
- Bind tiến trình thử nghiệm vào Cellular.
- Mở TCP/HTTP listener IPv6 cổng 8080.
- Android B kiểm tra kết nối bằng trình duyệt.

## Lưu ý quan trọng
Đây là APK chẩn đoán, CHƯA phải Reverse Proxy modem. Nó không chuyển tiếp đến `192.168.1.1`.

Android B truy cập:
`http://[IPv6-CELLULAR-CUA-A]:8080/TOKEN`

Token được hiển thị trên màn hình Android A. HTTP không mã hóa; chỉ dùng chuỗi thử nghiệm, không gửi mật khẩu hay dữ liệu nhạy cảm. Dừng server sau khi kiểm tra.

## Biên dịch trên GitHub
1. Tạo repository GitHub mới.
2. Upload toàn bộ nội dung thư mục này lên nhánh `main`.
3. Mở tab Actions.
4. Chọn workflow `Build Android APK`.
5. Nhấn `Run workflow` hoặc chờ build tự chạy sau khi push.
6. Mở lần chạy thành công, tải artifact `ipv6-gateway-test-debug`.

## Biên dịch local
Cần JDK 17, Android SDK Platform 35 và Gradle 8.9+:
`gradle assembleDebug`

APK đầu ra:
`app/build/outputs/apk/debug/app-debug.apk`

## Giới hạn kỹ thuật cần kiểm chứng trên thiết bị thật
- Nhà mạng có thể chặn kết nối TCP chiều vào dù ping IPv6 thành công.
- Android/OEM có thể giới hạn kết nối Cellular đồng thời Wi-Fi.
- Bind process vào Cellular chỉ áp dụng cho tiến trình APK này; không thay đổi định tuyến toàn hệ thống.
- Ứng dụng hiện chạy khi Activity còn sống; chưa có foreground service để chạy nền lâu dài.
- Một số thiết bị có thể không chấp nhận bindProcessToNetwork hoặc không cho nhận kết nối inbound như kỳ vọng.
