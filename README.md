# HỆ THỐNG TRUY CẬP SMART KEY — PHÂN HỆ DI ĐỘNG
## Smart Key Mobile Access — Android BLE + NFC HCE + Crypto Support

---

## 1. Tổng quan

`mobile_app` là phân hệ Android của nguyên mẫu **Smart Key Access**. Ứng dụng đóng vai trò là phía điện thoại trong hệ thống, giao tiếp với ESP32 của xe thông qua BLE và NFC, đồng thời cung cấp các hàm mật mã cần thiết cho quá trình xác thực và thiết lập phiên.

Mục tiêu của phân hệ hiện tại không phải là tự quyết định mở khóa xe. Quyền `GRANT / DENY` cuối cùng vẫn thuộc về phía phương tiện/ESP32. Mobile chủ yếu thực hiện bốn nhiệm vụ:

1. Thiết lập và duy trì đường truyền BLE GATT giữa điện thoại và ESP32.
2. Nhận dữ liệu do ESP32 gửi về, đặc biệt là JSON telemetry phục vụ kiểm thử tích hợp.
3. Tham gia NFC HCE provisioning/authentication qua PN532.
4. Cung cấp các primitive mật mã như SHA-256, HMAC-SHA256, ECDSA P-256, ECDH và HKDF.

### Cấu trúc mã nguồn chính

```text
app/src/main/java/com/example/smartkeyaccess/
├── MainActivity.kt
├── ble/
│   ├── BleManager.kt
│   └── SmartKeyProtocol.kt
├── nfc/
│   ├── NfcReaderManager.kt
│   ├── MasterCardParser.kt
│   └── SmartKeyApduService.kt
├── uwb/
│   └── UwbManager.kt
├── crypto/
│   └── CryptoManager.kt
└── mock/
    └── MockDataProvider.kt
```

> Mã nguồn đã được tách theo subsystem: `BleManager` quản lý GATT/MTU/TX/RX/Nonce; `NfcReaderManager` quản lý Reader Mode nhưng chưa đọc credential cho tới khi firmware chốt card layout; `SmartKeyApduService` xử lý HCE với PN532; `UwbManager` hiện chỉ ghi nhận manual integration-test trigger, chưa đo khoảng cách; `CryptoManager` cung cấp các primitive mật mã.

---

## 2. Phạm vi hiện tại

### 2.1 Đã hiện thực hóa trên Mobile

- Kiểm tra BLE hardware và runtime permission.
- Scan ESP32 theo Service UUID.
- Dừng scan ngay khi tìm thấy thiết bị mục tiêu.
- Kết nối GATT và khám phá Service/Characteristic.
- Kiểm tra RX hỗ trợ `WRITE` và TX hỗ trợ `NOTIFY`.
- Bắt buộc Nonce Characteristic `...0004` hỗ trợ đồng thời `NOTIFY + WRITE`.
- Bắt buộc TX và Nonce đều có CCCD `0x2902`, sau đó ghi hai descriptor tuần tự.
- Yêu cầu ATT MTU và tiếp tục hoạt động với MTU mặc định nếu cần.
- Bật TX Notify bằng CCCD `0x2902`.
- Gửi dữ liệu tùy ý từ Phone xuống ESP32 qua RX bằng `writeBleFrame()`.
- Nhận dữ liệu ESP32 gửi lên qua TX Notify bằng `onCharacteristicChanged()`.
- Nhận và hiển thị JSON telemetry từ ESP32.
- Hỗ trợ PING/PONG như một công cụ debug tùy chọn, không còn là điều kiện bắt buộc để vào `DATA_READY`.
- Hỗ trợ một phần binary Smart Key protocol cho `AUTH_REQ`, `AUTH_RESP`, `UWB_CONFIG_RESP`, `ERROR_IND`, ...
- Android NFC HCE với AID Smart Key.
- Nhận APDU từ PN532 và trả response APDU.
- HMAC-SHA256 cho nhánh `VID || Nonce`.
- Phone Identity Key Pair P-256 lưu bằng AndroidKeyStore.
- ECDSA-SHA256 theo IEEE P1363 `R || S` cho challenge nonce.
- ECDH P-256 + HKDF helper cho secure session trong milestone sau.
- Nhận raw nonce riêng qua `...0004`; phần HMAC/ECDSA response đang `BLOCKED BY FIRMWARE CONTRACT` và không tự ký raw nonce.

### 2.2 Chưa hoàn tất / chưa hardware-verified

- Đọc thật `VID` và `MSK` từ Master Card vật lý vẫn chưa hoàn thiện; hiện tại MSK còn lấy từ `MockDataProvider`.
- Public Key wire format 32B/65B/66B vẫn cần chốt cuối cùng với firmware.
- ECDSA đã có code Mobile nhưng vẫn cần ESP32 verify thành công end-to-end.
- ECDH/HKDF đã có helper nhưng chưa chạy full secure-session giữa Mobile và ESP32.
- AES-128-GCM chưa được tích hợp.
- UWB chưa được tích hợp end-to-end.
- JSON telemetry đã có parser/UI phía Mobile nhưng vẫn cần Kiệt chạy ESP32 thật để xác minh.

---

## 3. Quy ước trạng thái bằng chứng

| Trạng thái | Ý nghĩa |
|---|---|
| `DESIGN` | Đã có kiến trúc/ý tưởng nhưng chưa có implementation hoàn chỉnh |
| `MOCKED` | Đang dùng test vector hoặc dữ liệu giả lập |
| `IMPLEMENTED` | Đã có code trong Mobile |
| `HARDWARE-VERIFIED` | Đã chạy và xác minh trên điện thoại + ESP32/PN532 thật |
| `UNKNOWN` | Chưa đủ bằng chứng để kết luận |

> Không nâng một chức năng lên `HARDWARE-VERIFIED` chỉ vì build thành công. Cần log hoặc kết quả thực tế từ phần cứng.

---

# 4. BLE Central / GATT

## 4.1 Vai trò hai phía

```text
Android Phone                     ESP32
-------------                     -----
BLE Central / GATT Client         BLE Peripheral / GATT Server

RX WRITE  ----------------------> RX Characteristic
TX Notify <---------------------- TX Characteristic
```

Tên RX/TX được hiểu theo phía ESP32:

- `RX`: ESP32 nhận dữ liệu do Phone ghi xuống.
- `TX`: ESP32 gửi dữ liệu về Phone bằng Notify.

Mobile không cần gọi `readCharacteristic()` để nhận dữ liệu thường xuyên ở flow hiện tại. ESP32 chủ động `notify()`, Android nhận callback ở `onCharacteristicChanged()`.

---

## 4.2 GATT Contract

| Thành phần | UUID | Property | Chức năng |
|---|---|---|---|
| Service | `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` | Primary | Smart Key service, dùng để scan/filter |
| RX Characteristic | `6E400002-B5A3-F393-E0A9-E50E24DCCA9E` | `WRITE` | Phone → ESP32 |
| TX Characteristic | `6E400003-B5A3-F393-E0A9-E50E24DCCA9E` | `NOTIFY` | ESP32 → Phone |
| Nonce Characteristic | `6E400004-B5A3-F393-E0A9-E50E24DCCA9E` | `NOTIFY + WRITE` | ESP32 notify raw challenge; Mobile response đang bị chặn cho tới khi chốt HMAC/signing transcript |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` | Descriptor | Bật/tắt Notify cho TX và Nonce |

### Lưu ý về Descriptor

Trong tài liệu nhóm có nhắc đến CUD `0x2901` để mô tả characteristic. Cần phân biệt:

```text
0x2901 = Characteristic User Description (CUD)
0x2902 = Client Characteristic Configuration Descriptor (CCCD)
```

Mobile hiện tại **không dựa vào CUD `0x2901` để phân loại từng packet**. UUID của characteristic được dùng để dispatch TX và Nonce; CCCD `0x2902` được dùng để bật Notify cho cả hai characteristic.

---

## 4.3 BLE Runtime State Machine hiện tại

```text
READY
  ↓
SCANNING
  ↓
FOUND
  ↓
CONNECTING
  ↓
DISCOVERING
  ↓
MTU_NEGOTIATING
  ↓
SUBSCRIBING
  ↓
DATA_READY
```

`DATA_READY` trong bản hiện tại có nghĩa là:

> RX/TX/NONCE đã được discover đúng property, MTU đã được xử lý, TX và NONCE Notify đã được bật tuần tự qua hai CCCD, và ứng dụng sẵn sàng nhận/gửi application data.

`DATA_READY` **không có nghĩa**:

- authentication đã hoàn tất;
- ECDH/HKDF đã hoàn tất;
- UWB đã hoạt động;
- xe đã được cấp quyền mở khóa.

### Thay đổi so với baseline cũ

Bản cũ dùng:

```text
SUBSCRIBING
→ PING
→ PONG
→ DATA_READY
```

Bản hiện tại chọn **Option B**:

```text
SUBSCRIBING
→ DATA_READY
→ chờ ESP32 TX Notify / JSON telemetry
```

PING/PONG được giữ lại như một công cụ debug tùy chọn, không còn chặn luồng dữ liệu chính.

---

## 4.4 BLE Transport Layer

`BleManager.kt` có hàm chung:

```text
writeBleFrame(frame: ByteArray)
```

Nhiệm vụ:

```text
Application / Protocol data
        ↓
ByteArray
        ↓
writeBleFrame()
        ↓
gatt.writeCharacteristic(RX, frame, WRITE_TYPE_DEFAULT)
        ↓
ESP32
```

Điều này giúp các message sau dùng chung một cơ chế gửi:

```text
PING
AUTH_REQ
Public Key
Status
UWB configuration request
Binary SmartKey frame
...
```

---

## 4.5 BLE Receive Path

Chiều ESP32 → Phone:

```text
ESP32 TX Characteristic
        ↓ notify()
Android Bluetooth Stack
        ↓
onCharacteristicChanged()
        ↓
handleTxMessage(value)
```

`handleTxMessage()` hiện phân loại dữ liệu TX theo thứ tự; nonce được dispatch riêng sang `handleNonceMessage()` dựa trên UUID `...0004`:

1. `PONG` nếu dùng diagnostic PING/PONG.
2. JSON telemetry.
3. Binary `SmartKeyProtocol` frame.
4. Text/raw payload fallback để debug.

---

## 4.6 JSON Telemetry — yêu cầu test hiện tại của ESP32

Kiệt có thể gửi JSON qua TX Notify, ví dụ:

```json
{
  "phone": 200,
  "esp32": 100
}
```

Mobile sẽ:

```text
TX Notify
→ ByteArray
→ UTF-8 String
→ JSONObject
→ lấy phone delay
→ lấy esp32 delay
→ cập nhật Jetpack Compose UI
```

Ngoài hai key chính `phone` và `esp32`, code hiện chấp nhận thêm một số alias trong giai đoạn contract chưa freeze hoàn toàn:

```text
phone / phoneDelay / phone_delay
esp32 / esp32Delay / esp32_delay
```

UI sẽ hiển thị:

```text
JSON: { ... }
Phone delay: xxx ms
ESP32 delay: xxx ms
```

### Mục tiêu test

Test này dùng để chứng minh:

```text
ESP32 application data
→ BLE TX Notify
→ Android receive callback
→ JSON parser
→ Compose UI
```

Nó **không phải phép đo khoảng cách** và cũng không thay thế UWB ranging.

---

## 4.7 Optional PING/PONG Diagnostic

`sendPing()` vẫn tồn tại để phục vụ debug nếu firmware ESP32 có hỗ trợ.

```text
Phone -- "PING" --> ESP32
Phone <-- "PONG" -- ESP32
```

RTT nếu đo bằng PING/PONG chỉ được xem là:

```text
BLE application/GATT round-trip latency
```

Không được coi là UWB Time-of-Flight và không dùng để tính khoảng cách.

PING/PONG hiện **không tự động chạy sau khi subscribe TX**.

---

## 4.8 Binary Smart Key Protocol

`MainActivity.kt` đã có hỗ trợ một phần binary protocol thông qua `SmartKeyProtocol`.

Các message hiện được code tham chiếu gồm:

```text
AUTH_REQ
AUTH_RESP
PONG
UWB_CONFIG_RESP
ERROR_IND
```

Đây là tầng phía trên BLE transport:

```text
Application
    ↓
SmartKeyProtocol encode/decode
    ↓
BLE RX/TX
```

Phần này đang được giữ để phát triển secure session sau khi contract firmware được chốt hoàn toàn.

---

# 5. NFC Architecture

NFC của dự án có hai use case khác nhau và không nên trộn lẫn.

## 5.1 Phase A — Master Card → Phone

Mục tiêu thiết kế:

```text
Master Card
    ↓
Android Phone Reader Mode
    ↓
VID + MSK
```

Trong bản hiện tại:

- UI đã có nút kiểm tra/bật NFC hardware.
- `VID` có thể được hiển thị trên UI.
- `MSK` vẫn đang dùng test vector từ `MockDataProvider`.
- Phần đọc dữ liệu thật từ Master Card vật lý chưa hoàn chỉnh.

Do đó trạng thái hiện tại:

```text
Physical Master Card provisioning = BLOCKED BY CARD TECHNOLOGY / BLOCK-PAGE CONTRACT
```

---

## 5.2 Phase B — ESP32 + PN532 ↔ Phone HCE

Trong phase này:

```text
ESP32 + PN532                  Android Phone
-------------                  -------------
NFC Reader                     HCE Card
chủ động gửi APDU              processCommandApdu()
                               trả response APDU
```

Phone không chủ động gửi APDU như BLE. PN532 gửi command trước, HCE xử lý rồi trả response.

---

## 5.3 Smart Key AID

```text
F0 53 4D 41 52 54 4B 45 59
```

Tương ứng:

```text
0xF0 + "SMARTKEY"
```

SELECT AID:

```text
00 A4 04 00 09 F0 53 4D 41 52 54 4B 45 59 00
```

Response thành công:

```text
90 00
```

---

## 5.4 APDU Contract hiện tại

### Instruction byte

```text
INS_SELECT     = 0xA4
INS_SEND_DATA  = 0x10    // ESP32 gửi data sang Phone
INS_RECE_DATA  = 0x20    // ESP32 yêu cầu Phone trả data
```

### P1 data tag

```text
0x01 = VID / Nonce / HMAC
0x02 = Public Key
0x03 = Signature Challenge / Signature
```

### Các APDU đang dùng theo firmware hiện tại

```text
SELECT AID
00 A4 04 00 09 F0 53 4D 41 52 54 4B 45 59 00

Gửi VID + nonce
00 10 01 00 24 <payload>

ESP32 gửi Public Key cho Phone
00 10 02 00 32 <payload>

ESP32 gửi challenge nonce
00 10 03 00 24 <payload>

ESP32 yêu cầu HMAC
00 20 01 00 00

ESP32 yêu cầu Phone Public Key
00 20 02 00 32

ESP32 yêu cầu chữ ký số
00 20 03 00 00
```

> Các APDU đã được đánh dấu `OK` ở firmware cũ được giữ tương thích trong giai đoạn hiện tại. Phần Public Key/signature vẫn cần test chéo end-to-end trước khi freeze protocol cuối cùng.

---

# 6. NFC Authentication Flow hiện tại

## 6.1 Bước 1 — SELECT AID

```text
ESP32/PN532
    ↓ SELECT AID
Phone HCE
    ↓
AID match?
    ├─ Yes → 90 00
    └─ No  → error status
```

Khi bắt đầu một HCE session mới, các cache của session trước được reset để tránh dùng nhầm HMAC/challenge/signature cũ.

---

## 6.2 Bước 2 — VID + Nonce → HMAC

ESP32 gửi:

```text
VID || Nonce
```

Mobile thực hiện:

```text
HMAC-SHA256(
    key  = MSK,
    data = VID || Nonce
)
```

Kết quả được cache trong session.

Sau đó ESP32 gửi `INS_RECE_DATA / P1=0x01`, Mobile trả:

```text
HMAC 32B || 90 00
```

MSK chỉ được dùng khi đã provision từ Reader Mode hoặc khi người dùng chủ động bấm `Load Mock Card` cho integration test:

```text
NfcHceBridge.provisionedMsk
```

HCE không còn tự fallback sang mock MSK. Nếu chưa có MSK được provision, APDU authentication bị từ chối theo hướng fail closed.

---

## 6.3 Bước 3 — Public Key Exchange

ESP32 có thể gửi public key cho Phone qua:

```text
INS_SEND_DATA
P1 = 0x02
```

Mobile hiện lưu payload vào `receivedEspKey` để phục vụ secure-session flow sau.

Khi ESP32 yêu cầu Phone Public Key:

```text
INS_RECE_DATA
P1 = 0x02
```

Mobile lấy public key từ **Phone Identity Key Pair**.

### Compatibility behavior hiện tại

Code hiện hỗ trợ hai nhánh:

```text
Le = 32
→ trả 32B affine X để tương thích firmware hiện tại

Le khác 32
→ trả full TLS ECPoint 66B
```

Đây là vùng contract vẫn cần Kiệt và phía Mobile chốt lại trước khi coi là final protocol.

---

# 7. Phone Identity Key và ECDSA

## 7.1 Tách Identity Key khỏi ECDH Session Key

Hai loại key có mục đích khác nhau:

```text
PHONE IDENTITY KEY PAIR
- long-term
- P-256
- private key dùng để ký ECDSA
- public key gửi ESP32 để verify identity
- lưu bằng AndroidKeyStore

ECDH EPHEMERAL KEY PAIR
- temporary/session
- P-256
- dùng ECDH tạo shared secret
- không dùng làm permanent phone identity
```

---

## 7.2 AndroidKeyStore Identity Key

`CryptoManager.getOrCreateIdentityKeyPair()` dùng alias:

```text
smartkey_phone_identity_p256
```

Logic:

```text
AndroidKeyStore có alias?
    ├─ Có  → lấy lại private/public key cũ
    └─ Chưa → sinh P-256 key pair mới và lưu trong AndroidKeyStore
```

Lý do cần persistent identity:

```text
ESP32 enroll ID_PUBLIC A
→ lưu A trong NVS
→ app restart
→ Phone vẫn phải ký bằng private key tương ứng với A
```

Nếu mỗi lần app restart lại sinh identity key mới thì ESP32 sẽ không verify được chữ ký sau này.

---

## 7.3 Signature Challenge Flow

ESP32 gửi challenge nonce:

```text
INS_SEND_DATA
P1 = 0x03
```

Mobile:

```text
challenge nonce
    ↓
cache nonce
    ↓
SHA256withECDSA
    ↓
IEEE P1363 signature
    ↓
cache signature
```

Khi ESP32 gửi:

```text
INS_RECE_DATA
P1 = 0x03
```

Mobile trả chữ ký:

```text
R(32B) || S(32B)
= 64 bytes
```

ESP32 là phía verifier:

```text
ECDSA_VERIFY(
    PHONE_ID_PUBLIC,
    challengeNonce,
    signature
)
```

### Trạng thái hiện tại

```text
Mobile ECDSA sign code          = IMPLEMENTED
P1363 conversion helper        = IMPLEMENTED
ESP32 verify end-to-end        = NOT YET HARDWARE-VERIFIED
```

---

# 8. CryptoManager

`CryptoManager.kt` hiện cung cấp:

```text
SHA-256
HMAC-SHA256
P-256 ECDH ephemeral key generation
P-256 ECPoint 65B/66B decode support
TLS ECPoint 66B encode
ECDH shared secret
HKDF-SHA256 session-key derivation
AndroidKeyStore Phone Identity Key Pair
ECDSA-SHA256 signing
ECDSA P1363 verification helper
DER ↔ P1363 conversion
ByteArray ↔ Hex
```

## 8.1 ECDH / HKDF

ECDH helper được chuẩn bị cho BLE secure session:

```text
Phone ephemeral private key
+ ESP32 ephemeral public key
        ↓
      ECDH
        ↓
  shared secret
        ↓
   HKDF-SHA256
        ↓
 session key material
```

Hiện helper đã có nhưng full exchange chưa được tích hợp end-to-end.

## 8.2 AES-GCM

Hiện tại:

```text
AES-128-GCM = TODO
```

Không nên đánh dấu là `IMPLEMENTED` cho tới khi cả encryption/decryption và test vector chéo Android/ESP32 chạy thành công.

---

# 9. MockDataProvider

Dữ liệu mẫu đang dùng để hai phía Mobile/Firmware có thể test độc lập:

```text
VID / CAR_ID
01 02 03 04 05 06 07 08

MSK
01 02 03 04 05 06 07 08
09 0A 0B 0C 0D 0E 0F 10
11 12 13 14 15 16 17 18
19 1A 1B 1C 1D 1E 1F 20
```

Các giá trị này chỉ là **test vectors**, không phải secret thực tế dùng trong production.

---

# 10. Yêu cầu chức năng và trạng thái

## 10.1 BLE

| ID | Requirement | Trạng thái |
|---|---|---|
| `MOB-BLE-01` | Kiểm tra BLE hardware và runtime permission | `IMPLEMENTED` |
| `MOB-BLE-02` | Scan Smart Key Service với timeout | `IMPLEMENTED` |
| `MOB-BLE-03` | Stop scan trước khi connect | `IMPLEMENTED` |
| `MOB-BLE-04` | Discover Service + validate RX WRITE + TX NOTIFY + NONCE NOTIFY/WRITE | `IMPLEMENTED` |
| `MOB-BLE-05` | Request/record MTU và có fallback | `IMPLEMENTED` |
| `MOB-BLE-06` | Enable TX Notify bằng CCCD `0x2902` | `IMPLEMENTED` |
| `MOB-BLE-06A` | Enable NONCE Notify bằng CCCD `0x2902`, tuần tự sau TX | `IMPLEMENTED` |
| `MOB-BLE-06B` | Nonce HMAC/ECDSA response | `BLOCKED BY FIRMWARE CONTRACT` |
| `MOB-BLE-07` | Sau khi Notify ready, cho phép application traffic mà không bắt buộc PING/PONG | `IMPLEMENTED` |
| `MOB-BLE-08` | Nhận TX Notify và dispatch payload | `IMPLEMENTED` |
| `MOB-BLE-09` | Parse JSON telemetry từ ESP32 | `IMPLEMENTED` |
| `MOB-BLE-10` | Hiển thị raw JSON + phone/ESP32 delay lên UI | `IMPLEMENTED` |
| `MOB-BLE-11` | PING/PONG diagnostic nếu firmware hỗ trợ | `OPTIONAL / IMPLEMENTED MOBILE` |
| `MOB-BLE-12` | Hardware test JSON ESP32 → Mobile | `UNKNOWN / WAITING ESP32 TEST` |

## 10.2 NFC HCE

| ID | Requirement | Trạng thái |
|---|---|---|
| `MOB-NFC-01` | Đăng ký Smart Key AID | `IMPLEMENTED` |
| `MOB-NFC-02` | Xử lý SELECT AID và trả `90 00` | `IMPLEMENTED` |
| `MOB-NFC-03` | Nhận VID + Nonce APDU | `IMPLEMENTED` |
| `MOB-NFC-04` | Tính/trả HMAC-SHA256 | `IMPLEMENTED + MOCKED MSK` |
| `MOB-NFC-05` | Nhận ESP32 Public Key payload | `IMPLEMENTED` |
| `MOB-NFC-06` | Trả Phone Identity Public Key | `IMPLEMENTED / CONTRACT NOT FINAL` |
| `MOB-NFC-07` | Nhận challenge nonce | `IMPLEMENTED` |
| `MOB-NFC-08` | Ký challenge bằng ECDSA P-256 | `IMPLEMENTED` |
| `MOB-NFC-09` | Trả P1363 signature 64B | `IMPLEMENTED` |
| `MOB-NFC-10` | ESP32 verify chữ ký end-to-end | `UNKNOWN / NEED HARDWARE TEST` |
| `MOB-NFC-11` | Đọc VID/MSK thật từ Master Card vật lý | `BLOCKED / WAITING CARD TYPE + BLOCK-PAGE LAYOUT` |

## 10.3 Crypto

| ID | Requirement | Trạng thái |
|---|---|---|
| `MOB-CRYPTO-01` | SHA-256 | `IMPLEMENTED` |
| `MOB-CRYPTO-02` | HMAC-SHA256 | `IMPLEMENTED` |
| `MOB-CRYPTO-03` | Ephemeral ECDH P-256 | `IMPLEMENTED` |
| `MOB-CRYPTO-04` | ECDH shared secret | `IMPLEMENTED` |
| `MOB-CRYPTO-05` | HKDF-SHA256 | `IMPLEMENTED` |
| `MOB-CRYPTO-06` | Persistent Phone Identity Key | `IMPLEMENTED` |
| `MOB-CRYPTO-07` | ECDSA-SHA256 P1363 sign | `IMPLEMENTED` |
| `MOB-CRYPTO-08` | Cross-platform ECDSA verify với ESP32 | `UNKNOWN / NEED TEST` |
| `MOB-CRYPTO-09` | AES-128-GCM | `TODO` |

## 10.4 UWB

| ID | Requirement | Trạng thái |
|---|---|---|
| `MOB-UWB-01` | Nhận UWB configuration từ ESP32 | `DESIGN / PARTIAL PROTOCOL SUPPORT` |
| `MOB-UWB-02` | Tạo UWB session Android | `DESIGN` |
| `MOB-UWB-03` | Hiển thị ranging result | `DESIGN` |

---

# 11. Current Integration Status

| Feature | Mobile | Hardware/ESP32 integration |
|---|---|---|
| BLE scan/connect | ✅ Implemented | ⏳ cần test board thật |
| RX Write | ✅ Implemented | ⏳ |
| TX Notify | ✅ Implemented | ⏳ |
| JSON telemetry parser | ✅ Implemented | ⏳ chờ Kiệt gửi JSON |
| JSON telemetry UI | ✅ Implemented | ⏳ |
| PING/PONG | ✅ optional Mobile helper | ⏳ firmware optional |
| Binary AUTH frame | ✅ partial Mobile | ⏳ |
| NFC SELECT AID | ✅ | ✅/⏳ giữ contract cũ đã test một phần |
| NFC HMAC | ✅ | ✅/⏳ giữ nhánh đã test |
| Phone Identity Key | ✅ AndroidKeyStore | ⏳ |
| ECDSA signature | ✅ | ⏳ ESP32 verify |
| ECDH/HKDF helper | ✅ | ⏳ end-to-end |
| AES-GCM | ❌ | ❌ |
| Physical Master Card read | ❌ | ❌ |
| UWB | Manual trigger only; no distance | Waiting firmware/UWB session contract |

---

# 12. Verification Checklist

## 12.1 BLE

- [ ] Runtime permission được cấp đúng.
- [ ] Bluetooth đang bật.
- [ ] Scan thấy đúng ESP32 Service UUID.
- [ ] Stop scan sau khi tìm thấy ESP32.
- [ ] `connectGatt()` thành công.
- [ ] Discover đúng Smart Key Service.
- [ ] RX có `WRITE`.
- [ ] TX có `NOTIFY`.
- [ ] MTU callback hoặc fallback hoạt động.
- [ ] Ghi CCCD `0x2902` thành công.
- [ ] State chuyển sang `DATA_READY` mà không phụ thuộc PING/PONG.
- [ ] Kiệt gửi TX Notify JSON thành công.
- [ ] Mobile nhận JSON trong `onCharacteristicChanged()`.
- [ ] JSON parse thành công.
- [ ] UI hiển thị raw JSON.
- [ ] UI hiển thị `phone delay`.
- [ ] UI hiển thị `esp32 delay`.
- [ ] Payload text/raw không phải JSON vẫn được hiển thị để debug.
- [ ] Optional: PING/PONG hoạt động nếu firmware hỗ trợ.
- [ ] Disconnect/reset dọn GATT resource đúng.
- [ ] Reconnect thành công.

## 12.2 NFC HCE

- [ ] Thiết bị hỗ trợ NFC/HCE.
- [ ] `HostApduService` được khai báo đúng trong `AndroidManifest.xml`.
- [ ] AID XML khớp `F0534D4152544B4559`.
- [ ] PN532 SELECT AID thành công.
- [ ] Mobile trả `90 00`.
- [ ] ESP32 gửi VID + Nonce thành công.
- [ ] Mobile tính đúng HMAC.
- [ ] ESP32 nhận đúng HMAC 32B.
- [ ] Mobile nhận ESP32 Public Key payload.
- [ ] ESP32 nhận Phone Identity Public Key.
- [ ] ESP32 gửi challenge nonce.
- [ ] Mobile tạo ECDSA signature P1363 64B.
- [ ] ESP32 nhận signature.
- [ ] ESP32 verify signature thành công bằng Phone Identity Public Key.
- [ ] Session cache bị xóa khi HCE deactivated.
- [ ] APDU sai thứ tự bị từ chối/fail-closed phù hợp.

## 12.3 Crypto

- [ ] HMAC Android = HMAC ESP32 trên cùng test vector.
- [ ] Phone Identity Public Key có format firmware parse được.
- [ ] ECDSA signature Android được ESP32 verify đúng.
- [ ] Ephemeral ECDH public-key format được chốt.
- [ ] Shared secret Android = ESP32.
- [ ] HKDF output Android = ESP32.
- [ ] AES-GCM được thêm sau khi key contract ổn định.

---

# 13. Build

Windows PowerShell:

```powershell
cd mobile_app
.\gradlew.bat assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

---

# 14. Các bước tiếp theo

Ưu tiên ngắn hạn:

```text
1. Build lại Mobile sau khi merge 3 file mới.
2. Kiệt chạy ESP32 BLE GATT Server.
3. Mobile connect và subscribe TX.
4. Kiệt notify JSON test.
5. Mobile hiển thị JSON + delay trên UI.
6. Test lại NFC SELECT/HMAC đã có.
7. Test Phone Identity Public Key + ECDSA signature với PN532.
8. Freeze Public Key/Signature wire format.
```

Sau khi baseline trên ổn định:

```text
9. Hoàn thiện Master Card physical read.
10. Tích hợp ECDH/HKDF end-to-end.
11. Thêm AES-GCM.
12. Truyền UWB configuration qua secure BLE session.
13. Tích hợp Android UWB ranging.
14. Thu thập log/measurement và nâng trạng thái lên HARDWARE-VERIFIED.
```

---

# 15. Ghi chú phát triển

- Không hardcode secret production trong source code.
- `MockDataProvider` chỉ dùng cho test/integration.
- Không coi BLE RTT là UWB ranging hay khoảng cách vật lý.
- Không dùng PING/PONG làm điều kiện bảo mật.
- Không coi `DATA_READY` là đã authenticated.
- Identity Key và ECDH Ephemeral Key phải được xem là hai loại key khác nhau.
- Mobile không tự quyết định unlock xe.
- Với mọi thay đổi protocol, cần đồng bộ cả Mobile, ESP32 firmware, README và diagram.

