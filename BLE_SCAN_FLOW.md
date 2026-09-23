# Flowchart M1 — tìm quảng bá ESP32 qua BLE

Sơ đồ này mô tả phần app **đã có** trong `MainActivity.kt`: tìm quảng bá chứa service UUID tham khảo. `FOUND` chỉ nghĩa là đã thấy quảng bá; chưa kết nối GATT, xác thực hay mở khóa.

```mermaid
flowchart TD
    A[Nhấn Find ESP32] --> B{Đang scan?}
    B -- Có --> Z[Giữ nguyên lượt scan]
    B -- Không --> C{Máy hỗ trợ BLE và có adapter?}
    C -- Không --> C1[Báo lỗi BLE / adapter]
    C -- Có --> D{Đủ quyền SCAN và CONNECT?}
    D -- Không --> D1[Hiện hộp thoại xin quyền]
    D1 --> D2{Người dùng cấp đủ?}
    D2 -- Không --> D3[Báo thiếu quyền; chờ bấm lại]
    D2 -- Có --> C
    D -- Có --> E{Bluetooth đã bật?}
    E -- Không --> E1[Hiện hộp thoại bật Bluetooth]
    E1 --> E2{Người dùng bật?}
    E2 -- Không --> E3[Báo Bluetooth tắt; chờ bấm lại]
    E2 -- Có --> C
    E -- Có --> F{Lấy được BLE scanner?}
    F -- Không --> F1[Báo lỗi scanner]
    F -- Có --> G[Đặt SCANNING; startScan; hẹn giờ 10 giây]
    G --> H{Kết quả nào đến trước?}
    H -- Quảng bá chứa UUID tham khảo --> I[stopScan; hủy giờ; FOUND]
    H -- Thiết bị khác --> H1[Bỏ qua kết quả; tiếp tục chờ]
    H1 --> H
    H -- Hết 10 giây --> J[stopScan; NOT_FOUND]
    H -- Scan báo lỗi --> K[stopScan; ERROR_SCAN]
    I --> L[Chờ bước connectGatt sau này]
    J --> M[Đặt lại SCANNING = false; bấm lại nếu muốn thử]
    K --> M
```

## Đọc từng khối

1. **Điều kiện trước scan:** BLE/adapter, hai quyền và Bluetooth đã bật. Hộp thoại quyền và bật Bluetooth trả kết quả qua callback, rồi kiểm tra lại điều kiện.
2. **Scan:** `startScan()` chỉ bắt đầu quét. `onScanResult()` có thể nhận nhiều thiết bị khác; app tiếp tục quét cho đến khi thấy service UUID tham khảo hoặc có timeout/lỗi.
3. **Dọn dẹp:** `stopScan()` dừng scanner, hủy hẹn giờ, đặt `scanning = false`. Timeout không xóa phiên GATT vì app chưa tạo phiên đó.

**Cần xác minh với Kiệt:** ESP32 thật có đưa UUID `6E400001-B5A3-F393-E0A9-E50E24DCCA9E` vào advertising không. Nếu không, app sẽ báo `NOT_FOUND` dù thiết bị đang phát BLE.
