# BÁO CÁO TIẾN ĐỘ & KẾ HOẠCH ĐỒNG BỘ KỸ THUẬT PHÂN HỆ MOBILE APP (SMART KEY)

> **Người lập:** Ngô Thanh Hiếu (Mobile App Lead)  
> **Người nhận:** Tuấn Kiệt (Firmware/ESP32 Lead), Tiến Đạt (Crypto/UI), Nhóm Đồ án KTMT  
> **Ngày lập:** Thứ Sáu, 23/10/2026  
> **Mục tiêu:** Thẳng thắn đối chiếu mã nguồn thực tế, làm rõ các điểm lệch pha kiến trúc giữa Mobile và Firmware ESP32, chốt kế hoạch bàn giao trước **Chủ nhật, 25/10/2026**.

---

## 1. THỰC TRẠNG MÃ NGUỒN MOBILE HIỆN TẠI (ĐỐI CHIẾU TRUNG THỰC VỚI CODE)

Để nhóm không bị "hiểu nhầm tiến độ", phân hệ Mobile phân định rõ trạng thái của từng tính năng dựa trên mã nguồn đã commit trên repo nhóm:

| Phân hệ / Module | Hiện trạng mã nguồn thực tế | Đánh giá trạng thái |
| :--- | :--- | :---: |
| **NFC HCE (Giả lập thẻ)** | Đã hiện thực `SmartKeyApduService.kt` đầy đủ 7 bước APDU theo hợp đồng với `nfc.cpp` của Kiệt. Nạp thẻ mock và cập nhật VID/MSK lên UI mượt mà. | `[IMPLEMENTED - READY]` |
| **NFC Reader (Đọc thẻ thật)** | Trong `NfcReaderManager.kt`, hàm `readTagData()` đang cố tình trả về `ReadResult.Blocked` vì chưa có thẻ vật lý thực tế và chưa chốt sector bộ nhớ Mifare. | `[BLOCKED - PENDING HW]` |
| **Thư viện Mật mã lõi** | `CryptoManager.kt` và `AesGcm.kt` đã viết xong các hàm toán học: ECDH P-256, HKDF-SHA256, HMAC-SHA256, ECDSA P1363 và AES-128-GCM (hỗ trợ AAD tùy chọn, fail-closed). Unit Test độc lập pass 100%. | `[IMPLEMENTED - UNIT TESTED]` |
| **BLE GATT Pipeline** | Đã quét, kết nối, discover services, đăng ký Notify TX, bóc tách được delay (dưới dạng JSON text thô). **Chưa ghép nối gọi `AesGcm.decrypt()` và `computeSharedSecret()` trong luồng runtime của `BleManager.kt`.** | `[INTEGRATING - IN PROGRESS]` |
| **Nền tảng UWB AndroidX** | Đã khai báo Manifest (`UWB_RANGING`), xây dựng FSM 9 trạng thái `UwbSessionState`, cấu hình `UwbConfig.kt`, chẩn đoán phần cứng `UwbCapabilityReport.kt`. Build APK thành công (`assembleDebug`). | `[SCAFFOLDED - PENDING FIRA]` |
| **Chạy ngầm (Background)** | `MainActivity.onStop()` hiện vẫn chủ động gọi `stopRanging()`, app chưa chạy background service. | `[DEFERRED TO PHASE 3]` |

---

## 2. BỐN VẤN ĐỀ TRỌNG YẾU CẦN KIỆT & NHÓM THỐNG NHẤT KHẨN CẤP

Sau khi phân tích file `Mota.md` và tin nhắn của Kiệt, phân hệ Mobile nhận thấy có **4 điểm nghẽn kỹ thuật** cần làm rõ ngay để tránh việc code xong cắm mạch thật không chạy được:

### 2.1. Vấn đề 1: Luồng UWB trong `Mota.md` chưa tương thích với Hệ điều hành Android
* **Yêu cầu trong `Mota.md` của Kiệt:**
  $$\text{ESP32} \xrightarrow{\text{0x22 + 'ACK'}} \text{Phone} \xrightarrow{\text{0x01 + 'POLL'}} \text{ESP32} \xrightarrow{\text{0x02 + 'RESP'}} \text{Phone} \xrightarrow{\text{0x31 + Timestamp (t4 - t1)}} \text{ESP32}$$
  Kiệt yêu cầu điện thoại tự phát bản tin `POLL (0x01)` và đo hiệu timestamp phần cứng $(t_4 - t_1)$ gửi qua bản tin `FINAL (0x31)`.
* **Rào cản kỹ thuật thực tế trên Android:**
  - Luồng trên là code ví dụ mẫu của Decawave/Qorvo dành riêng cho **2 chip vi điều khiển bare-metal DW3000** điều khiển thanh ghi SPI trực tiếp.
  - Trên điện thoại Android, thư viện chính thức [`androidx.core.uwb`](https://developer.android.com/reference/kotlin/androidx/core/uwb/UwbManager) hoạt động theo tiêu chuẩn **FiRa Consortium**. 
  - **Hệ điều hành Android đóng kín hoàn toàn tầng PHY và MAC.** Android **không cung cấp API công khai** để ứng dụng tự tạo gói tin thô (như byte `0x01 POLL`, `0x31 FINAL`) hay móc vào thanh ghi để lấy timestamp nano giây $(t_4 - t_1)$. AndroidX chỉ trả về kết quả cuối cùng: khoảng cách $x$ mét và góc $y$ độ.
* ❓ **Câu hỏi chốt với Kiệt:**
  1. *Phần cứng UWB của điện thoại lúc demo là máy gì?*
     - Nếu dùng điện thoại có sẵn chip UWB (Pixel Pro / Samsung Ultra): Firmware ESP32 DW3000 của Kiệt **bắt buộc phải hỗ trợ stack FiRa**, không thể chạy gói tin tự chế `0x22, 0x01...`.
     - Nếu điện thoại cắm một module DW3000 rời qua cổng USB-OTG/Type-C: Lúc đó App phải viết driver đọc Serial USB chứ không dùng thư viện AndroidX UWB.
     - Nếu giai đoạn này ESP32 chưa có FiRa stack: Mobile sẽ hiển thị khoảng cách và timestamp dưới dạng **Giả lập (Mock Demo)** để nghiệm thu logic giao diện trước.

---

### 2.2. Vấn đề 2: Thỏa thuận trao đổi MAC ngắn 2 bytes qua BLE còn thiếu Nonce
* **Trong `Mota.md`:** Kiệt yêu cầu trao đổi Tag 16B và Ciphertext 2B qua 2 label `"MAC ESP32"` (TX) và `"MAC PHONE"` (RX) bằng AES-128-GCM tương tự như lúc gửi Delay.
* **Cảnh báo lỗ hổng mật mã (Nonce-Reuse Attack):**
  - Trong chuẩn AES-128-GCM, việc tái sử dụng cùng một bộ `(Key, Nonce)` cho 2 gói tin khác nhau là **lỗ hổng bảo mật chết người (The Forbidden Attack)**, kẻ tấn công có thể khôi phục hoàn toàn khóa xác thực GHASH và giả mạo toàn bộ hệ thống.
  - [`AesGcm.kt`](file:///c:/Users/ngoth/OneDrive/Máy%20tính/ĐACN%20-%20ĐATN/mobile_app/app/src/main/java/com/example/smartkeyaccess/crypto/AesGcm.kt) bắt buộc phải có **Nonce 12 bytes** mới giải mã được.
* ❓ **Đề xuất thống nhất với Kiệt:**
  - Không tái sử dụng Nonce cũ của gói Delay.
  - Khi ESP32 gửi `"MAC ESP32"`, cấu trúc gói tin qua TX phải là: `12B Nonce + 16B Tag + 2B Ciphertext` (tổng 30 bytes).
  - Tương tự, khi App gửi `"MAC PHONE"`, App cũng sẽ sinh Nonce 12B mới và gửi cấu trúc `12B Nonce + 16B Tag + 2B Ciphertext` qua RX.

---

### 2.3. Vấn đề 3: Thống nhất công nghệ UI — Chốt Jetpack Compose, dừng Flutter
* **Điểm bất hợp lý trong `Mota.md`:** Kiệt ghi *"Đạt thiết kế UI và viết code kotlin bằng framework flutter"*.
* **Làm rõ:** Flutter là framework dùng ngôn ngữ **Dart**, không thể viết code Kotlin bằng Flutter.
* **Thực trạng dự án:** Toàn bộ App hiện tại đang chạy trên nền tảng **Native Android 100% bằng Kotlin + Jetpack Compose**, giao diện Material 3 đã hoàn thiện khung kết nối và đang chạy mượt mà.
* ❓ **Chốt phương án:** Đạt clone code Android về và phát triển tiếp giao diện bằng **Jetpack Compose (Kotlin)**. Việc chuyển sang Flutter lúc này sẽ phá vỡ toàn bộ cấu trúc Native BLE/NFC/UWB và chắc chắn vỡ tiến độ.

---

### 2.4. Vấn đề 4: Đính chính mốc thời gian Deadline
* Hôm nay là **Thứ Sáu, ngày 23/10/2026**.
* **Chủ nhật tới đây là ngày 25/10/2026** (bản nháp trước đó ghi nhầm 26/10).
* Thời gian thực tế cho đợt bàn giao này chỉ còn **vỏn vẹn 2 ngày** (hạn chót: 23h59 Chủ nhật, 25/10/2026).

---

## 3. DANH MỤC ƯU TIÊN VÀ KẾ HOẠCH BÀN GIAO TRƯỚC HẠN CHÓT (25/10)

Để đảm bảo có sản phẩm chạy được cho buổi demo mà không bị phân tán nguồn lực, Mobile phân bổ thứ tự ưu tiên như sau:

| Mức độ | Nhiệm vụ kỹ thuật | Chi tiết thực hiện | Bàn giao trước 23h59 Chủ nhật (25/10) |
| :---: | :--- | :--- | :--- |
| **P0** *(Bắt buộc)* | **Chốt Wire Format BLE MAC 2B với Kiệt** | Thống nhất quy cách đóng gói: Nonce (12B) + Tag (16B) + Ciphertext (2B) cho cả 2 chiều TX/RX. | Tài liệu đặc tả byte thống nhất trên Discord. |
| **P0** *(Bắt buộc)* | **Hoàn thiện nhánh nhận/gửi MAC trong `BleManager.kt`** | Hiện thực hóa nhánh nhận `"MAC ESP32"`, giải mã bằng AES-GCM; sinh MAC ngắn của Phone (2B), mã hóa AES-GCM và bắn sang RX (`"MAC PHONE"`). | Logcat hiển thị giải mã thành công MAC 2 bên, gửi nhận trơn tru. |
| **P0** *(Bắt buộc)* | **Chốt phương án UWB với Kiệt** | Xác định rõ demo UWB bằng FiRa stack hay Mock data. | Quyết định kỹ thuật bằng văn bản để không code sai hướng. |
| **P1** *(Cần thiết)* | **Bổ sung UI UWB: Nút Active, Reset & Ô Debug** | Thêm 2 nút bấm Compose: **Active UWB** và **Reset UWB**; thêm khung hiển thị hiệu số $(t_4 - t_1)$ (đơn vị ms/ticks). | Giao diện hiển thị đúng 100% checklist yêu cầu của Kiệt. |
| **P1** *(Cần thiết)* | **Kiểm thử biên dịch & Chạy thực tế** | Chạy `assembleDebug`, test BLE với mạch ESP32 của Kiệt, quay video logcat. | Video/Log demo BLE bắt tay + trao đổi MAC gửi nhóm. |
| **P2** *(Sau deadline)* | **Chạy nền (Background) & Tự động hóa** | Xây dựng Foreground Service cho BLE/UWB sau khi luồng đo cơ bản đã ổn định. | Thực hiện ở Giai đoạn 3. |

---

## 4. KẾT LUẬN & ĐỀ XUẤT HÀNH ĐỘNG HÔM NAY

1. Hiếu sẽ tiến hành code ngay phần **nhận/gửi 2 bytes MAC qua BLE** trong [`BleManager.kt`](file:///c:/Users/ngoth/OneDrive/Máy%20tính/ĐACN%20-%20ĐATN/mobile_app/app/src/main/java/com/example/smartkeyaccess/ble/BleManager.kt) và dựng sẵn **2 nút bấm Active/Reset + khung Debug Timestamp** trên [`MainActivity.kt`](file:///c:/Users/ngoth/OneDrive/Máy%20tính/ĐACN%20-%20ĐATN/mobile_app/app/src/main/java/com/example/smartkeyaccess/MainActivity.kt).
2. Nhờ Kiệt phản hồi gấp 2 câu hỏi kỹ thuật trong Mục **2.1** (Phần cứng/Giao thức UWB) và Mục **2.2** (Quy cách truyền 12B Nonce cho gói MAC) để hai bên ráp code ăn khớp 100%.
