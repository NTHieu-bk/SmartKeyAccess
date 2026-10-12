# Mobile App Refactor Audit

> Audit date: 2026-10-12  
> Review specification: `MOBILE_APP_REFACTOR_REVIEW_SPEC.md`  
> Audited project: `ĐACN/SmartKeyAccess`  
> Scope: read-only source review, build, unit-test and lint verification. No application source code was modified.

> Snapshot note: Sections A-K below record the pre-hardening audit. The implementation was subsequently updated from this audit. See **Post-change review (2026-10-12)** at the end for the current status and remaining blockers.

The repository does not contain a literal `mobile_app/` directory. This review uses `ĐACN/SmartKeyAccess`, whose README identifies it as the Android `mobile_app` subsystem.

## A. Executive summary

```text
Architecture: PARTIAL
UI:           NEEDS CLEANUP
BLE:          PARTIAL
Nonce:        PARTIAL
NFC Reader:   PARTIAL
NFC HCE:      PARTIAL
UWB:          TEST-ONLY
Crypto:       PARTIAL
Comments:     MISLEADING
README:       STALE
Build:        FAIL
```

### Main conclusions

1. The package split is already close to the target architecture. `MainActivity` is no longer a behavioral God Class, although its 723-line file still contains all Compose sections.
2. BLE scan/connect, RX write, TX notification, MTU callback, JSON parsing, nonce UUID dispatch, and serialized descriptor writes exist.
3. BLE never validates characteristic properties. NONCE and CCCDs can be missing while the app still enters `DATA_READY`.
4. The nonce path is contractually wrong/incomplete: it signs the raw notification and writes the signature to `...0004`; there is no HMAC stage, nonce-length validation, or proven signing transcript.
5. NFC Reader Mode and HCE are separated correctly, with no NDEF path. However, Reader Mode invents sector/page mapping, default authentication, and page addresses that the specification explicitly says are unknown.
6. HCE preserves the existing AID/APDU shape, but silently falls back to a mock MSK and accepts several malformed or undersized payloads.
7. UWB is not an integration trigger. It waits 600 ms, generates a random local distance, and labels it successful UWB ranging. No UWB API, BLE control exchange, POLL/RESPONSE/FINAL, 200 ms delay, AES key, or IV exists.
8. The UI exposes the first eight bytes of the MSK and presents simulated UWB results as real distance/unlock decisions.
9. APK assembly and lint pass, but unit-test execution fails before assertions because both test classes cannot be loaded.

## B. Actual source tree

```text
SmartKeyAccess/
├── README.md
├── BLE_SCAN_FLOW.md
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/example/smartkeyaccess/
│       │   │   ├── MainActivity.kt
│       │   │   ├── ble/
│       │   │   │   ├── BleManager.kt
│       │   │   │   └── SmartKeyProtocol.kt
│       │   │   ├── crypto/
│       │   │   │   └── CryptoManager.kt
│       │   │   ├── mock/
│       │   │   │   └── MockDataProvider.kt
│       │   │   ├── nfc/
│       │   │   │   ├── MasterCardParser.kt
│       │   │   │   ├── NfcReaderManager.kt
│       │   │   │   └── SmartKeyApduService.kt
│       │   │   ├── ui/theme/
│       │   │   │   ├── Color.kt
│       │   │   │   ├── Theme.kt
│       │   │   │   └── Type.kt
│       │   │   └── uwb/
│       │   │       └── UwbManager.kt
│       │   ├── res/xml/apduservice.xml
│       │   └── res/...
│       ├── test/.../
│       │   ├── ExampleUnitTest.kt
│       │   └── SmartKeyProtocolTest.kt
│       └── androidTest/.../ExampleInstrumentedTest.kt
└── gradle/...
```

### Comparison with the target structure

- `ble`, `nfc`, `crypto`, `uwb`, and `mock` boundaries already exist.
- NFC Reader and HCE are separate classes.
- The missing structural piece is a real `ui/` component layer; all application UI remains in `MainActivity.kt` from line 230 onward.
- Moving Compose components is optional cleanup, not a prerequisite for correcting protocol behavior.

`MainActivity` itself mainly creates managers, handles permissions/lifecycle, and connects callbacks. It is not currently a behavioral God Class. The file is large because it also owns every Compose component.

## C. Requirement-to-code matrix

| Requirement | Expected | Actual file/function | Status | Evidence / problem | Minimal change |
|---|---|---|---|---|---|
| Package separation | UI, BLE, NFC, Crypto, Protocol, UWB separated | Existing packages | PASS | Major subsystem classes exist | Keep boundaries |
| MainActivity orchestration | UI and high-level calls | `onCreate`, `startBleScan`, `toggleNfcReader` | PARTIAL | Activity behavior is reasonable; UI components make file 723 lines | Optionally move only Compose sections |
| BLE UUID/directions | RX `...0002`, TX `...0003`, nonce `...0004` | `BleManager.kt:47-53` | PASS | UUIDs and directions are correct | Preserve |
| Characteristic properties | Validate RX WRITE, TX NOTIFY, nonce NOTIFY+WRITE | `BleManager.onServicesDiscovered()` | FAIL | Only null checks are performed | Validate property bit flags before continuing |
| Required nonce characteristic | Discover and require `...0004` | `BleManager.kt:323-329` | FAIL | `nonceChar` is optional; missing nonce does not fail | Make it required under the current contract |
| Notification sequencing | Serialize TX then nonce CCCD | `descriptorWriteQueue` | PARTIAL | Queue serializes descriptor callbacks correctly | Also validate CCCD presence, local-enable result, invocation result, and timeout |
| MTU | Request 512, use callback result | `requestMtu` / `onMtuChanged` | PASS | Actual MTU comes from callback; failure falls back to 23 | Make failure/fallback status explicit |
| TX dispatch | Dispatch by source UUID | `onCharacteristicChanged` | PASS | TX and NONCE are distinguished | Preserve |
| JSON telemetry | Decode UTF-8 and parse `phone`/`esp32` | `handleTxMessage` | PASS | Existing working-shaped path should not be rewritten | Add malformed-JSON status only |
| PING/PONG | Optional diagnostic, not ranging | `sendPing`, UI label | PASS | It is manual and labeled RTT | Keep in a Debug/Test section |
| Binary protocol validation | Validate frame fields and lengths | `SmartKeyProtocol.parseHeader` | PARTIAL | Only size and magic are checked; version and `payloadLen` are trusted | Add strict decoder validation |
| Nonce response | HMAC, ECDSA, write same `...0004` | `handleNonceMessage` | FAIL | Raw nonce is signed directly; no HMAC | Block response until transcript is frozen |
| NFC Reader/HCE separation | Independent flows | `NfcReaderManager`, `SmartKeyApduService` | PASS | Correct class separation; no NDEF path | Preserve |
| Raw Master Card contract | Raw read with confirmed mapping | `NfcReaderManager.readTagData` | BLOCKED | Sector 1/default key/pages 4–15 are invented assumptions | Replace with explicit pending-contract configuration |
| Parser | Enforce VID 8B/MSK 32B | `MasterCardParser` | PARTIAL | Length validation works, but extraction layout is assumed | Keep validation; postpone mapping |
| Reader lifecycle | End Reader Mode before HCE | Activity/reader manager | FAIL | Successful read leaves Reader Mode enabled | Stop Reader Mode after successful provisioning |
| HCE APDU behavior | Preserve AID and request/response paths | `processCommandApdu` | PARTIAL | AID and branches exist; input validation and state sequencing are weak | Harden around existing wire behavior |
| UWB trigger | Manual integration trigger only | `testUwbManual` | FAIL | Generates random distance locally and claims ranging success | Relabel as mock or replace with real integration trigger |
| AndroidX UWB | `prepareSession`/`RangingResult` if implemented | Dependencies/source | TODO | No AndroidX UWB dependency or API usage | Do not claim ranging |
| Crypto helpers | SHA/HMAC/ECDSA/ECDH/HKDF; AES later | `CryptoManager` | PARTIAL | Helpers exist; AES-GCM absent as documented | Preserve helpers; add crypto tests |
| Sensitive UI | Do not display MSK | `NfcCardSection` | FAIL | Displays first eight MSK bytes | Show only `Loaded / Valid` |
| Modern BLE API | Byte-array write/callback APIs | `BleManager` | PASS | Modern characteristic and descriptor overloads are used | Preserve |

## D. BLE trace

### Actual flow

```text
Scan button
→ MainActivity checks adapter/enabled state
→ BleManager.startScan()
→ unfiltered scan; inspect advertised service UUID in each result
→ stopScan()
→ connectGatt()
→ onConnectionStateChange(CONNECTED)
→ discoverServices()
→ find service
→ obtain RX, TX, NONCE references
→ require only RX and TX to be non-null
→ requestMtu(512)
→ onMtuChanged(actual MTU), or immediate fallback
→ setCharacteristicNotification(TX, true)
→ enqueue TX CCCD if present
→ setCharacteristicNotification(NONCE, true) if nonce exists
→ enqueue NONCE CCCD if present
→ write queued descriptors sequentially
→ DATA_READY when queue becomes empty
→ RX writes / TX and NONCE callbacks
```

### Contract deviations

- No property validation for RX, TX, or NONCE.
- NONCE is not required.
- A missing CCCD is silently skipped.
- `setCharacteristicNotification()` return values are ignored.
- The return status from modern `writeDescriptor()` is ignored.
- If both descriptors are missing, the empty queue immediately produces `DATA_READY`.
- Status always says “TX + Nonce” even if only one or neither was subscribed.
- There is no descriptor-operation timeout.
- Characteristic writes are not queued, so a nonce response can collide with a user-triggered PING/AUTH write.
- Enqueue success is reported as “sent” before `onCharacteristicWrite` confirms success.
- Malformed JSON and malformed binary frames become raw debug payloads rather than explicit parse errors.
- An unsolicited binary frame calculates RTT from a potentially stale or zero timestamp.

## E. NFC trace

### Reader Mode

```text
User enables reader
→ NfcReaderManager.startReader()
→ enableReaderMode()
→ onTagDiscovered()
→ coroutine / Dispatchers.IO
→ try MifareClassic
   → authenticate sector 1 with default Key A
   → read three blocks
→ else try MifareUltralight
   → read pages 4, 8, 12
→ else raw NfcA READ commands for pages 4, 8, 12
→ MasterCardParser extracts first 8B VID + next 32B MSK
→ store result in NfcHceBridge
→ Reader Mode remains enabled
```

This is raw block/page reading, not NDEF. The separation and background I/O are good. The mapping is not valid evidence of the real contract: sector 1, default Key A, and pages 4–15 are unconfirmed firmware assumptions and should currently be `BLOCKED`.

Exceptions are swallowed, so authentication failure, tag removal, and transport failure all collapse into a generic message.

### HCE Mode

```text
PN532 selects F0534D4152544B4559
→ processCommandApdu()
→ reset cached session values
→ return 90 00

INS 0x10 / P1 0x01
→ accept payload
→ optionally copy first 8B into UI VID
→ use provisioned MSK, otherwise silently use mock MSK
→ HMAC over entire payload
→ cache HMAC

INS 0x10 / P1 0x02
→ cache ESP32 public-key bytes, but never consume them

INS 0x10 / P1 0x03
→ sign raw payload with identity private key
→ cache signature

INS 0x20
→ return cached HMAC, phone public key, or signature
```

### HCE risks

- The mock-MSK fallback is fail-open behavior.
- `P1=0x01` can HMAC an empty or undersized payload.
- VID is not compared with the provisioned card’s VID.
- APDU phase/order validation is minimal.
- Public-key bytes are stored without parsing or verification.
- `receivedEspKey` is never consumed.
- Reader Mode is not automatically stopped before the HCE phase.
- `requireDeviceUnlock="false"` should be an explicit prototype security decision.

## F. Nonce trace

### Actual BLE nonce behavior

```text
ESP32 NOTIFY on ...0004
→ onCharacteristicChanged()
→ handleNonceMessage(value)
→ display full nonce as hex
→ load phone identity private key
→ ECDSA-SHA256 sign raw value
→ produce P1363 R||S, normally 64B
→ enqueue WRITE of signature to ...0004
→ immediately report “sent” if enqueue succeeded
```

### Required but unproven contract

```text
HMAC key            = UNKNOWN
HMAC input          = UNKNOWN
ECDSA signing input = UNKNOWN
Response wire format = UNKNOWN
```

The cryptographic portion is therefore:

```text
BLOCKED BY FIRMWARE CONTRACT
```

The current raw-nonce signature must not be treated as the agreed implementation. There is also no expected nonce-length check or response acknowledgement.

## G. UWB trace

### Actual button behavior

```text
Test UWB (Manual)
→ UwbManager.testUwbManual()
→ set state RANGING
→ delay 600 ms
→ generate random distance in approximately 0.8–1.5 m
→ compare with 1.6 m
→ display success and an unlock-range judgement
```

### Audit answers

| Question | Actual result |
|---|---|
| Actual UWB ranging operations | No |
| POLL sender | None |
| RESPONSE handler | None |
| FINAL sender | None |
| Transport | None |
| AndroidX `prepareSession` / `RangingResult` | Not used |
| BLE UWB configuration/control | Only a parsed `UWB_CONFIG_RESP` status; no session connection |
| BLE timing used as UWB ToF | No |
| 200 ms reply delay | Not used |
| AES test key/IV | Not present |
| Distance calculator | Phone generates a random mock value |
| ESP32 distance calculation | Not integrated |

The random generator never exceeds 1.5 m, so the normal UI call cannot reach the `>1.6 m` rejection branch.

This should be described as a local UI mock, not UWB integration or ranging.

## H. UI review

| Section | State/data | Secret exposure | Accuracy |
|---|---|---|---|
| NFC Card & HCE | Reader enabled flag, reader status, parsed card, HCE bridge state | Displays first 8 bytes of MSK | Reader/HCE labels are clear, but invented mapping and mock fallback are hidden |
| BLE Central Pipeline | BLE state, status, MTU, RTT, session ID | None | Mostly accurate; no separate TX/NONCE subscription result |
| Telemetry & Nonce | JSON, delays, raw TX, nonce status | Displays raw nonce; it belongs in Debug | “Signed and sent” can be false or contractually invalid |
| UWB Ranging | Mock state and random distance | None | Misleading: presented as real ranging and unlock-range evaluation |
| Debug/Test controls | Mock card, PING, AUTH_REQ are mixed into normal subsystem cards | Mock MSK is partially displayed | Should be grouped and clearly marked `MOCK / TEST ONLY` |

The app should show `MSK: Loaded / Valid`, not a prefix of the key.

## I. Comment and README audit

| Location | Current claim | Problem | Suggested replacement |
|---|---|---|---|
| `README.md:37` | `UwbManager` manages distance measurement | It generates a random distance | “Local UWB UI mock; no ranging session is integrated” |
| `README.md:49` | RX WRITE/TX NOTIFY properties are checked | No property checks exist | Mark as TODO |
| `README.md:119` | Nonce response is ECDSA signature | Latest requirement includes HMAC and an unfrozen transcript | Mark `BLOCKED BY FIRMWARE CONTRACT` |
| `README.md:131` | `0x2902` enables TX Notify | Code also attempts nonce Notify | Say TX and NONCE, while noting validation gaps |
| `README.md:157` | `DATA_READY` proves RX/TX and TX Notify | Actual code can reach it without nonce or CCCDs | Document current bug; target requires both subscriptions |
| `README.md:191` | `writeBleFrame` is in MainActivity | It is in `BleManager` | Correct class name |
| `README.md:236` | `handleBleMessage()` | Function is `handleTxMessage()` | Use actual name and mention nonce dispatch |
| `README.md:325` | MainActivity implements binary protocol support | `BleManager` invokes `SmartKeyProtocol` | Correct ownership |
| `README.md:514-520` | MSK is mock data | Code prefers provisioned data, then silently falls back | Document both paths and fail-open risk |
| `UwbManager.kt:33` | “verify ranging workflow” | No ranging occurs | “Local UI simulation only” |
| `UwbManager.kt:42` | “DW3000 pulse round-trip delay” | It is a coroutine delay | “Simulated delay; no DW3000 traffic” |
| `CryptoManager.kt:183` | Fallback is for JVM tests | It catches all AndroidKeyStore failures in production | Restrict fallback to injected/test implementation; fail closed in app |
| `BLE_SCAN_FLOW.md:3` | Scan is in MainActivity | It is in `BleManager` | Correct ownership |
| `BLE_SCAN_FLOW.md:28` | Timeout becomes `NOT_FOUND` | Actual state is `ERROR` | Use actual state |
| `BLE_SCAN_FLOW.md:30` | Connection is future work | App immediately calls `connectGatt` | Update flow |

No obsolete NDEF implementation was found. CCCD is correctly identified as `0x2902`; `0x2901` is correctly described as CUD.

## J. Dead, duplicate, and obsolete code

Report before deletion:

- `receivedEspKey` is assigned and cleared but never used.
- `cachedChallengeNonce` is assigned and cleared but never used.
- `SmartKeyProtocol.UwbConfigResponse` is declared but not parsed or used.
- `generateIdentityKeyPair()` is only an alias for `getOrCreateIdentityKeyPair()` and obscures persistence semantics in tests.
- `SAMPLE_MSK_16B` and `createMockMasterCard(false)` conflict with the current 32-byte Master Card contract.
- The normal UWB rejection branch is unreachable because generated distances remain below 1.6 m.
- Seven template color resources are unused.
- README describes several old class/function locations.
- No duplicate HMAC implementation, old NDEF path, old Status characteristic, or blocking PING/PONG flow was found.
- No deprecated BLE write/callback API was reported by lint.

## K. Minimal safe patch plan

1. Correct README and comments first:
   - Mark UWB as a local mock.
   - Mark nonce crypto as firmware-blocked.
   - Correct class/function ownership and subscription claims.
2. Make the current UI truthful:
   - Hide MSK bytes.
   - Relabel or remove random distance/unlock output.
   - Move PING, mock-card loading, AUTH_REQ, and raw payloads under Debug/Test.
3. Harden BLE without restructuring working scan/JSON code:
   - Validate RX/TX/NONCE properties.
   - Require NONCE under the current contract.
   - Fail if either CCCD is missing.
   - Check notification/descriptor invocation results.
   - Track the exact subscription count and add a timeout.
   - Report write completion only from `onCharacteristicWrite`.
4. Freeze nonce behavior pending firmware:
   - Validate expected nonce length once confirmed.
   - Define HMAC key/input, ECDSA input, and response encoding.
   - Until then, do not silently sign raw bytes as if authentication were complete.
5. Make physical NFC reading explicitly blocked/configurable:
   - Remove production claims around sector 1/default key/pages 4–15.
   - Inject confirmed technology/authentication/layout later.
   - Preserve parser length validation.
   - Stop Reader Mode after a successful card read.
6. Harden HCE around the existing APDU wire behavior:
   - Reject invalid `Lc`, payload length, order, and missing credentials.
   - Remove silent mock-MSK fallback from normal execution.
   - Parse or explicitly defer the received ESP32 public key.
7. Keep `CryptoManager` helpers, but make AndroidKeyStore failure fail closed in the app and use an injected test key provider for JVM tests.
8. Fix the unit-test classpath/execution issue, then add focused tests for frame length/version validation, nonce contract encoding, parser rejection, and HCE APDU errors.
9. Only after those corrections, replace the UWB mock with an isolated manual integration manager based on the actual selected Android/firmware transport. Do not invent POLL/FINAL APIs.

## Verification results

```text
assembleDebug:      PASS
lintDebug:          PASS — 0 errors, 20 warnings
testDebugUnitTest:  FAIL
```

Unit-test failure details:

- `ExampleUnitTest`: `ClassNotFoundException`
- `SmartKeyProtocolTest`: `ClassNotFoundException`
- The tests did not reach their assertions.

The lint warnings comprise dependency/SDK update notices, two obsolete SDK checks, one redundant manifest label, and seven unused color resources. No deprecated BLE write/callback usage was reported.

Generated verification reports:

- `app/build/reports/tests/testDebugUnitTest/index.html`
- `app/build/reports/lint-results-debug.html`

## Final assessment

The project does not need a full rewrite. The existing manager boundaries and working-shaped BLE/JSON/HCE paths should be preserved. The immediate priority is to make status claims truthful and fail closed around incomplete contracts: remove misleading UWB behavior, stop exposing MSK material, enforce the BLE GATT contract, and mark nonce and physical-card layouts as blocked until firmware supplies exact byte-level definitions.

## Post-change review (2026-10-12)

The low-risk truthfulness changes and BLE contract hardening recommended above have now been applied. This section supersedes the corresponding pre-change findings without erasing the original audit evidence.

### Resolved in code

- BLE now requires RX, TX, and NONCE characteristics.
- RX must expose `PROPERTY_WRITE`; TX must expose `PROPERTY_NOTIFY`; NONCE must expose both `PROPERTY_NOTIFY` and `PROPERTY_WRITE`.
- TX and NONCE must each expose CCCD `0x2902`.
- Local notification routing results and descriptor-write request results are checked.
- Descriptor writes remain serialized; `DATA_READY` is reached only after both `onDescriptorWrite()` callbacks succeed.
- Characteristic writes are kept one-at-a-time. A write is only described as confirmed after `onCharacteristicWrite()` succeeds.
- Repeated PING/AUTH requests are rejected while another request is awaiting response or timeout.
- Raw nonce notifications are dispatched separately, but the app no longer signs raw nonce bytes or claims authentication success.
- Full/partial MSK bytes are no longer displayed in the UI.
- Normal HCE no longer falls back to `MockDataProvider`; missing provisioned MSK returns `SW_CONDITIONS_NOT_SATISFIED`.
- Mock credentials remain available only through the explicit `Load Mock Card` integration-test action.
- The hardcoded Mifare sector/default-key/page reads were removed from active behavior. Reader Mode detects the tag and reports the card-layout contract as blocked.
- Reader Mode has a defined success path that disables Reader Mode before HCE. Explicit mock provisioning also stops Reader Mode first.
- UWB no longer waits and generates a fake distance. The button now records a test request and clearly says that it is waiting for the real firmware/UWB session contract.
- README, BLE scan flow, UI labels, previews, and important code comments were updated to match the behavior.

### Still blocked or intentionally incomplete

```text
Nonce expected length     = BLOCKED BY FIRMWARE CONTRACT
Nonce HMAC key/input      = BLOCKED BY FIRMWARE CONTRACT
Nonce ECDSA input         = BLOCKED BY FIRMWARE CONTRACT
Nonce response encoding   = BLOCKED BY FIRMWARE CONTRACT

Master Card technology    = BLOCKED BY CARD CONTRACT
Card authentication       = BLOCKED BY CARD CONTRACT
VID/MSK block-page layout = BLOCKED BY CARD CONTRACT

UWB peer/session params   = BLOCKED BY UWB/FIRMWARE CONTRACT
POLL/RESPONSE/FINAL path  = NOT IMPLEMENTED
200 ms reply delay        = NOT INTEGRATED
AES test key/IV           = NOT INTEGRATED
Real distance             = NOT PRODUCED BY MOBILE
```

### Current status after the patch

```text
Architecture: PARTIAL (reasonable subsystem split; Compose still in MainActivity)
UI:           PARTIAL (truthful; debug/test controls are not yet fully regrouped)
BLE:          GOOD AT CODE LEVEL / NEEDS HARDWARE VERIFICATION
Nonce:        TRANSPORT READY / CRYPTO BLOCKED
NFC Reader:   INFRASTRUCTURE READY / CARD LAYOUT BLOCKED
NFC HCE:      PARTIAL / FAIL-CLOSED MSK HANDLING
UWB:          MANUAL TRIGGER ONLY / REAL INTEGRATION MISSING
Crypto:       PARTIAL / AES-GCM NOT IMPLEMENTED
Comments:     UPDATED FOR CHANGED PATHS
README:       UPDATED FOR CHANGED PATHS
```

### Post-change verification

```text
compileDebugKotlin: PASS
assembleDebug:      PASS
lintDebug:          PASS — 0 errors, 20 warnings

testDebugUnitTest from repository Unicode path:
    FAIL before assertions — Gradle worker ClassNotFoundException

testDebugUnitTest from an identical temporary ASCII path:
    PASS — all unit tests executed successfully
```

Both unit-test `.class` files are generated in the original repository, but the Gradle test worker cannot load them from the current path containing Vietnamese characters. Running the same source from `C:\tmp\SmartKeyAccess-audit-20261012` succeeds. This verifies a Windows/Gradle Unicode-path classpath problem rather than a test assertion or application logic failure. The temporary verification copy was removed after the test.
