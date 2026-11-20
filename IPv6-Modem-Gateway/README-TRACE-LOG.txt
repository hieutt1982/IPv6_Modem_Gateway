IPv6 MODEM GATEWAY 0.5.5 - TRACE LOG

Bản này được tạo từ source 0.5.4. Mục tiêu chính: bổ sung log theo từng HTTP request để xác định công đoạn gây chậm khi Android B mở modem.

CÁC NHÓM LOG:
01-INBOUND           Nhận kết nối IPv6 từ Android B
02-READ-REQUEST      Đọc request line HTTP từ trình duyệt
03-READ-HEADERS      Đọc headers của request
04-GATEWAY-AUTH      Kiểm tra cookie/token Gateway
05-MODEM-TCP         Kết nối TCP từ Android A sang modem qua Wi-Fi
06-MODEM-TLS         Bắt tay HTTPS/TLS với modem
07-MODEM-REQUEST     Gửi request (GET/POST...) và body sang modem
08-MODEM-FIRST-BYTE  Chờ và nhận response đầu tiên từ modem
09-MODEM-HEADERS     Đọc HTTP status và response headers
10-MODEM-BODY        Đọc/xử lý body modem hoặc chuyển tiếp body
11-RETURN-TO-BROWSER Trả kết quả về Android B
ERROR                Lỗi xảy ra ở công đoạn tương ứng

Mỗi dòng TRACE có mã [REQ-n] và thời gian [t+...ms] tính từ lúc nhận kết nối. Nhiều request tải song song sẽ có mã khác nhau. POST đăng nhập sẽ hiện rõ method và đường dẫn; không ghi nội dung mật khẩu/body.

CÁCH GỬI LOG:
1. Cài/chạy bản 0.5.5 TRACE LOG.
2. Trên Android B mở link Gateway, chờ đến khi trang login hiện ra (hoặc chờ lỗi).
3. Trên Android A bấm "SAO CHÉP LOG 100 DÒNG GẦN NHẤT".
4. Dán nguyên văn vào ChatGPT. Không cần chọn từng dòng.

Log đầy đủ vẫn được nối tiếp vào file nội bộ của ứng dụng. Nút mới chỉ sao chép 100 dòng cuối để tránh phải gửi toàn bộ lịch sử quá dài.

LƯU Ý:
- Đây là bản bổ sung chẩn đoán log dựa trên 0.5.4; không chủ ý thay đổi thuật toán định tuyến, xác thực, TLS trust, hoặc luồng proxy.
- Chưa được build bằng Gradle và chưa thử trên thiết bị trong môi trường tạo file này. Cần biên dịch/cài thử trước khi dùng thực tế.


VERSION 0.5.6: Added a visible CLEAR ALL LOG button. It asks for confirmation, truncates the persistent log file, refreshes the log panel, and starts a fresh log session marker. Clearing logs does not stop Gateway or change network/proxy settings.
