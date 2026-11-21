IPv6 MODEM GATEWAY 0.5.9-HTTPS-TEST

Muc dich:
- Android B truy cap Gateway bang HTTPS tren IPv6 Cellular cua Android A.
- Android A dung chung chi tu ky (self-signed), khong can cai CA tin cay.
- Android A -> modem van HTTPS qua Wi-Fi nhu ban 0.5.8.1.
- Giu token va luong reverse proxy hien co.

Cach thu:
1. Cai/build app tren Android A tu ma nguon nay.
2. Tren Android A, bat Wi-Fi ket noi modem va bat du lieu di dong.
3. Mo app, nhan Khoi dong Gateway.
4. Dung nut Sao chep link Gateway trong app; link duoc tao voi https://[IPv6]:8080/...
5. Gui link sang Android B, mo bang Chrome.
6. Chrome co the hien canh bao chung chi tu ky. Neu co nut Nang cao/Tiep tuc, nguoi dung tu chap nhan tren Android B.
7. Kiem tra trang modem va thu dang nhap/cau hinh.

Luu y chung chi:
- Chua ky boi CA cong cong; Chrome se bao loi tin cay.
- SAN IP duoc tao theo IPv6 cu da dung trong lan test truoc: 2001:ee0:1b1:7f11:125d:f352:45d8:1f8e.
- Neu IPv6 Cellular cua Android A da thay doi, Chrome co the bao them loi ten/IP khong khop. Ban test nay dung de xac nhan luong HTTPS; neu dia chi thay doi, can tao lai SAN theo IPv6 moi hoac chuyen sang ten mien co DNS AAAA.
- Khong co co che tu dong tat kiem tra chung chi trong Chrome. Viec chap nhan canh bao do Android B/nguoi dung thuc hien.

Thay doi trong ban test:
- ServerSocket -> SSLServerSocket, TLS 1.2.
- Them app/src/main/assets/gateway_https.p12 (chung chi tu ky).
- Link sao chep chuyen tu http:// sang https://.
- Cookie Gateway co Secure; giu thuoc tinh Secure cua cookie modem.
- URL tuyet doi cua modem duoc rewrite ve https:// Gateway.
- Khong doi Network binding cua upstream: van dung wifiNetwork.getSocketFactory().

Ban nay la SOURCE TEST. Chua dong goi APK trong moi truong hien tai.
