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

## Cách 2: "Chớp Hotspot" bằng iOS Shortcut thuần (1 chạm từ Google Maps iPhone, tự tắt Hotspot)

Không cần cài bất kỳ app bên thứ ba nào trên iPhone — chỉ dùng ứng dụng **Phím tắt (Shortcuts)** có sẵn của Apple:

### Thiết lập 1 lần trên iPhone:
1. Mở app **Phím tắt (Shortcuts)** trên iPhone → bấm **`+`** tạo phím tắt mới tên là **"Gửi lên Xe"**.
2. Bật tùy chọn **Hiển thị trong Bảng chia sẻ (Show in Share Sheet)** (nhận loại *URL* và *Bản đồ / Địa điểm*).
3. Thêm lần lượt 5 hành động (Actions) có sẵn trong iOS:
   - **Bước 1:** `Lấy URL từ Đầu vào (Get URLs from Input)` → `Mở rộng URL (Expand URL)` (để giải mã link rút gọn `maps.app.goo.gl/...`).
   - **Bước 2:** `Bật Điểm truy cập cá nhân (Set Personal Hotspot -> On)`.
   - **Bước 3:** `Chờ 6 giây (Wait 6 seconds)` (để đầu xe tự động bắt vào Wi-Fi Hotspot của iPhone).
   - **Bước 4:** `Lấy nội dung của URL (Get Contents of URL)`:
     `http://<IP-Của-Xe-Trong-Hotspot>:48920/nav?q=[URL đã mở rộng]`
     *(Mẹo: Trên mạng Hotspot iPhone, đầu xe luôn được cấp IP cố định dạng `172.20.10.2` hoặc `172.20.10.3`).*
   - **Bước 5:** `Tắt Điểm truy cập cá nhân (Set Personal Hotspot -> Off)`.

---

## Cách 3: Bắn qua Bluetooth BLE GATT (`CarPlayNav`) — Không ngắt nhạc YouTube Music

Mô-đun `addons/nav-bridge` trên xe luôn mở sẵn dịch vụ **BLE GATT Server**:
- **Service UUID:** `0000c910-0000-1000-8000-00805f9b34fb`
- **Characteristic UUID (Write / Write Without Response):** `0000c911-0000-1000-8000-00805f9b34fb`
- **Định dạng chuỗi UTF-8 chấp nhận:**
  - Tọa độ trực tiếp: `10.7769,106.7009`
  - Mã Plus Code: `QMVX+2P`
  - Đường dẫn Google Maps: `https://www.google.com/maps/place/.../@10.7769,106.7009,17z`

Bạn có thể dùng bất kỳ công cụ BLE nào trên iPhone (như *nRF Connect*, *LightBlue*, *Bluefy*, hoặc App iOS riêng) ghi chuỗi tọa độ vào Characteristic `0000c911-0000-1000-8000-00805f9b34fb` là màn hình xe lập tức nhảy sang **Google Maps Live**!
