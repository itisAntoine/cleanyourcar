# Hướng dẫn 3 Cách Gửi Điểm Đến sang Màn Hình Xe (Offline / Chớp Hotspot / Bluetooth BLE)

`CleanYourCar` và mô-đun đi kèm `addons/nav-bridge` hỗ trợ sẵn **cả 3 phương pháp** để bạn mở chỉ đường trên màn hình xe mà không phải bật Hotspot 5G suốt chuyến đi:

---

## Cách 1: Nhập mã Plus Code 6 ký tự (100% Offline, 0 chạm iPhone cài đặt, 3 giây)

1. Mở **Google Maps** trên iPhone, chạm vào địa điểm muốn đi → kéo xuống mục thông tin sẽ thấy mã **Plus Code** (ví dụ: `QMVX+2P TP. Hồ Chí Minh`).
2. Trên màn hình xe, bấm nút **`[ 🔢 Mã 6 ký tự ]`**.
3. Chọn đúng tỉnh/thành phố ở góc trên bên phải (mặc định là **TP. Hồ Chí Minh**, hoặc nếu xe đã bắt GPS thì tự lấy vị trí GPS của xe).
4. Bấm 6 ký tự (ví dụ `QMVX2P` — dấu `+` tự động điền sau ký tự thứ 4) rồi bấm **`[ 🧭 ĐI NGAY ]`**.
5. Màn hình xe tự động giải mã toán học ra tọa độ GPS chính xác và bật **Google Maps Live** ở giữa khung CarPlay.

---

## Thiết lập chung cho Cách 2 và Cách 3 (bắt buộc, tùy chọn bật)

Mô-đun `addons/nav-bridge` là APK **tùy chọn** và **mặc định tắt**: không tự lắng nghe, không tự khởi động sau khi cập nhật, và **từ chối mọi điểm đến không kèm token**.

1. Trên đầu xe, mở ứng dụng **CarPlay Nav Bridge**.
2. Bật phương thức muốn dùng: **Nhận qua Bluetooth LE** và/hoặc **Nhận qua mạng nội bộ (HTTP)**.
3. Bấm **Token mới** để tạo token ghép đôi (dạng `<16 ký tự hex>.<64 ký tự hex>`). Token được lưu riêng tư trên đầu xe, không sao lưu, không chia sẻ cho ứng dụng khác. Tạo token mới sẽ thu hồi token cũ; bấm **Thu hồi** để vô hiệu hóa ngay.
4. Bấm **Quyền** (nếu Android yêu cầu) rồi **Bắt đầu**. Màn hình hiển thị trạng thái thật của từng phương thức (`đang nhận`, `lỗi`, `không khả dụng`…), không báo thành công khi chưa thực sự lắng nghe.
5. (Tùy chọn) Bật **Tự khởi động sau khi bật máy** nếu muốn bridge tự chạy lại sau khi khởi động đầu xe.

> **Người dùng bản cũ:** sau khi cập nhật, bridge **tạm dừng** và hiện thông báo giải thích một lần. Phím tắt iOS cũ (không có token), gói UDP, và chuỗi BLE thô đều bị từ chối — cần làm lại theo hướng dẫn dưới đây.

Giới hạn: yêu cầu HTTP tối đa 4 KiB, nội dung điểm đến (`q`) tối đa 1 KiB; mỗi yêu cầu hợp lệ mở đúng một lần chỉ đường.

---

## Cách 2: "Chớp Hotspot" bằng iOS Shortcut có xác thực (1 chạm từ Google Maps iPhone)

Không cần cài app bên thứ ba trên iPhone — chỉ dùng ứng dụng **Phím tắt (Shortcuts)** của Apple.

> ⚠️ **Token không phải là mã hóa đường truyền.** Yêu cầu đi bằng HTTP thuần; ai cùng mạng có thể đọc được token và điểm đến. Chỉ dùng trên **mạng tin cậy** (ví dụ Hotspot của chính iPhone bạn), không dùng trên Wi-Fi công cộng.
>
> Trên **Android 14 trở lên**, nhận qua LAN **không khả dụng** (không có loại dịch vụ nền hợp lệ cho phép); bridge sẽ báo `không khả dụng` thay vì chạy lén. Hãy dùng Cách 3.

### Thiết lập 1 lần trên iPhone:
1. Mở **Phím tắt** → bấm **`+`** tạo phím tắt mới tên **"Gửi lên Xe"**, bật **Hiển thị trong Bảng chia sẻ** (nhận *URL* và *Bản đồ / Địa điểm*).
2. Thêm lần lượt các hành động:
   - **Bước 1:** `Lấy URL từ Đầu vào` → `Mở rộng URL` (giải mã link rút gọn `maps.app.goo.gl/...`) → `Mã hóa URL (URL Encode)`.
   - **Bước 2:** `Bật Điểm truy cập cá nhân (Set Personal Hotspot -> On)`.
   - **Bước 3:** `Chờ 6 giây` (để đầu xe kết nối vào Hotspot).
   - **Bước 4:** `Lấy nội dung của URL (Get Contents of URL)`:
     - URL: `http://<địa chỉ hiển thị trong mục "Địa chỉ mạng nội bộ" của bridge>:48920/nav?q=[URL đã mã hóa]`
     - Phương thức: `GET`
     - **Tiêu đề (Headers):** khóa `Authorization`, giá trị `Bearer <token ghép đôi>`
     - *Không* đặt token vào URL — tham số khác ngoài `q` sẽ bị từ chối.
   - **Bước 5:** `Tắt Điểm truy cập cá nhân (Set Personal Hotspot -> Off)`.
3. Địa chỉ của đầu xe **không cố định**: nó do mạng cấp và có thể thay đổi. Mỗi khi đổi mạng hoặc khi phím tắt báo lỗi kết nối, hãy xem lại mục **Địa chỉ mạng nội bộ** trong bridge và cập nhật URL.

Phản hồi: `200` = đã mở chỉ đường; `401` = thiếu/sai token; `400` = yêu cầu sai định dạng; `413` = quá giới hạn kích thước.

---

## Cách 3: Bắn qua Bluetooth BLE GATT (`CarPlayNav`) — Không ngắt nhạc YouTube Music

Khi đã bật **Nhận qua Bluetooth LE** và bấm **Bắt đầu**, đầu xe mở dịch vụ **BLE GATT Server**:
- **Service UUID:** `0000c910-0000-1000-8000-00805f9b34fb`
- **Characteristic UUID (Write / Write Without Response):** `0000c911-0000-1000-8000-00805f9b34fb`
- **Định dạng tin nhắn bắt buộc (UTF-8, 3 dòng, phân tách bằng `\n`):**
  ```
  CYC-NAV/1
  <token ghép đôi>
  <điểm đến>
  ```
- **Điểm đến** chấp nhận (tối đa 1 KiB):
  - Tọa độ trực tiếp: `10.7769,106.7009`
  - Mã Plus Code: `QMVX+2P`
  - Đường dẫn Google Maps: `https://www.google.com/maps/place/.../@10.7769,106.7009,17z`

Chuỗi thô kiểu cũ (chỉ có điểm đến, không có dòng `CYC-NAV/1` và token) sẽ bị từ chối. Dùng công cụ BLE trên iPhone (như *nRF Connect*, *LightBlue*, *Bluefy*, hoặc app iOS riêng) ghi tin nhắn trên vào Characteristic `0000c911-...`; với tin nhắn dài, dùng chế độ ghi dài (Write with response / prepared write).
