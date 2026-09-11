# SNI Spoofing — Android

دورزدن DPI با دستکاری TLS ClientHello — پورت هسته [patterniha/SNI-Spoofing](https://github.com/patterniha/SNI-Spoofing) (ویندوز/WinDivert) به اندروید، با UI الهام‌گرفته از [UAC-SNI-Spoofer-Android](https://github.com/armin7270/UAC-SNI-Spoofer-Android).

Bypass DPI with TLS ClientHello manipulation — the Windows patterniha core ported to Android user-space, with a UAC-inspired Compose UI.

[فارسی](#فارسی) · [English](#english)

---

## فارسی

### معرفی
اپلیکیشن VPN متن‌باز اندروید که بدون نیاز به روت، تکنیک‌های دورزدن DPI هسته patterniha را روی همه ترافیک TCP سیستم اعمال می‌کند. چون اندروید (برخلاف ویندوز) تزریق پکت خام نمی‌دهد، استک TCP/IP کامل userspace روی TUN پیاده شده و تکنیک‌ها روی سگمنت‌های خروجی اعمال می‌شوند.

### امکانات
- تونل سراسری با `VpnService` + استک TCP کاربر-فضا (SYN/ACK، retransmit، out-of-order، window، کنترل جریان)
- **X25519 جاسازی‌شده** — تونل کانفیگ (VLESS/Trojan) روی اندروید ۷ به بالا کار می‌کند، نه فقط اندروید ۱۳
- **حالت‌های دورزدن DPI:**
  - `split_n` — برش ClientHello از بایت N
  - `split_sni` — برش داخل خود SNI (پیش‌فرض: ۲ بایت داخل hostname)
  - `multi_frag` — قطعه‌بندی چندتکه
  - `delayed` — برش + تاخیر
  - `sni_replace` — بازنویسی SNI با دامنه جعلی (اصلاح همه length های رکورد/هندشیک/اکستنشن)؛ با ECH/ESNI خودکار رد می‌شود
  - `combined` — ترکیب جایگزینی + قطعه‌بندی
  - `wrong_seq` — تزریق وفادار پترنی‌ها (`seq = syn_seq + 1 - len(fake)`) با هلپر روت (اختیاری)
- ساخت ClientHello جعلی — پورت `ClientHelloMaker` پترنی‌ها
- DNS-over-HTTPS با ساقط‌کردن AAAA (اجبار IPv4 داخل تونل) + کش
- پروفایل‌ها با import مستقیم `config.json` پترنی‌ها (CONNECT_IP / CONNECT_PORT / FAKE_SNI)
- مسیریابی per-app (لیست سفید/سیاه)
- DNS: Cloudflare / Google / AdGuard / Quad9 / OpenDNS / سفارشی
- بلاک QUIC (بازگشت اپ‌ها به TCP)، MTU قابل تنظیم، اتصال خودکار بعد از بوت
- UI کامپوز: دکمه اتصال انیمیشنی، آمار زنده (بسته‌ها، قطعات، ترافیک، uptime)، لاگ زنده
- Quick Settings tile + نوتیفیکیشن با دکمه قطع

> **توجه:** لینک‌های `vmess://` در حال حاضر پشتیبانی نمی‌شوند؛ هنگام import صریحاً اعلام می‌شوند. از VLESS یا Trojan استفاده کنید.

### ساخت از سورس
JDK 17 لازم است (SDK خودکار دانلود می‌شود).

```bash
git clone https://github.com/armin7270/sni-spoofing-android.git
cd sni-spoofing-android
./gradlew :app:assembleDebug
# خروجی: app/build/outputs/apk/debug/app-debug.apk
```

نسخه release با R8 فشرده/مبهم می‌شود:

```bash
./gradlew :app:assembleRelease
# خروجی: app/build/outputs/apk/release/app-release-unsigned.apk
```

APK آماده را می‌توانید از تب [Actions](https://github.com/armin7270/sni-spoofing-android/actions) همین ریپو (artifact) دانلود کنید.

### هلپر روت (اختیاری — wrong_seq)
فقط برای حالت `wrong_seq` که دقیقاً رفتار ویندوزی پترنی‌ها را تکرار می‌کند. هلپر داخل APK نیست و باید دستی روی دستگاه روت‌شده نصب شود:

```bash
# لینوکس/مک
export ANDROID_NDK_HOME=/path/to/ndk
./tools/build-helper.sh

# ویندوز
set ANDROID_NDK_HOME=C:\path\to\ndk
tools\build-helper.cmd
```

این اسکریپت برای `arm64` و `armv7` می‌سازد، با `adb push` به `/data/local/tmp/spoofhelper` می‌برد و دسترسی اجرا می‌دهد. سپس در برنامه «حالت روت» را روشن کنید. بدون هلپر، `wrong_seq` بی‌صدا به `split` برمی‌گردد (این در لاگ برنامه اعلام می‌شود).

### اعتبار
- هسته اصلی: [@patterniha](https://t.me/patterniha) — [SNI-Spoofing](https://github.com/patterniha/SNI-Spoofing)
- ساختار/UI: [UAC-SNI-Spoofer-Android](https://github.com/Floxu1/UAC-SNI-Spoofer-Android)

---

## English

### What it is
Open-source Android VPN app that applies the patterniha DPI-evasion techniques (Windows/WinDivert) to all device TCP traffic — no root required. Android has no raw-packet injection, so a full userspace TCP/IP stack runs on the TUN device and the evasion is applied to outgoing segments.

X25519 is bundled (RFC 7748, pure Kotlin), so the VLESS/Trojan config tunnel works on Android 7+ — not only Android 13, which is where the JDK's `XDH` provider first appears.

### Evasion modes
| Mode | Description |
|---|---|
| `split_n` | Cut the ClientHello after N bytes |
| `split_sni` | Cut inside the SNI hostname (default: 2 bytes in) |
| `multi_frag` | Split into many fragments |
| `delayed` | Split + micro-delay |
| `sni_replace` | Rewrite SNI to a fake host, fixing every enclosing length field; skipped automatically under ECH/ESNI |
| `combined` | SNI replace + fragmentation |
| `wrong_seq` | Faithful patterniha injection (`seq = syn_seq + 1 - len(fake)`) via optional root helper |

> **Note:** `vmess://` links are not supported yet; they are rejected with a clear message at import time. Use VLESS or Trojan.

### Build
```bash
./gradlew :app:assembleDebug
```
JDK 17 only — the Android SDK is provisioned automatically. CI builds the debug APK and runs the unit tests on every push (see Actions tab).

Release builds are shrunk and obfuscated with R8 and signed automatically when signing is configured:

```bash
./gradlew :app:assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

#### Signing

`build.gradle.kts` picks up credentials from the **first** of these two sources:

1. **`keystore.properties` in the repo root** (local machine — never commit it):

   ```properties
   storeFile=keystore/snispoof-release.keystore
   storePassword=your-password
   keyAlias=snispoof
   keyPassword=your-password
   ```

   Create the keystore with the same JDK the build already needs:

   ```bash
   keytool -genkeypair -v -keystore keystore/snispoof-release.keystore \
     -alias snispoof -keyalg RSA -keysize 2048 -validity 10950 \
     -storepass your-password -keypass your-password \
     -dname "CN=SNI Spoofing Android, OU=Mobile, O=yourname, C=IR"
   ```

2. **CI secrets** — `ANDROID_STORE_FILE`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.

With neither source present, `assembleRelease` still succeeds and produces `app-release-unsigned.apk`.

> **Back up `keystore/` and `keystore.properties` somewhere safe — and never commit them; `.gitignore` already excludes both.** Android refuses to install an update unless it is signed with the *same* key, so a lost keystore forces users to uninstall and reinstall, losing their settings.

### Root helper (optional — wrong_seq)
Only needed for `wrong_seq`, which reproduces the Windows patterniha behaviour exactly. The helper is not packaged in the APK: build it with the NDK and install it on a rooted device.

```bash
export ANDROID_NDK_HOME=/path/to/ndk
./tools/build-helper.sh          # Windows: tools\build-helper.cmd
```

The script builds for arm64 and armv7, pushes the binary to `/data/local/tmp/spoofhelper` and makes it executable. Then enable "Root mode" in the app. Without the helper, `wrong_seq` falls back to `split` — this is reported in the app log.

### Credits
- Core concept: [patterniha/SNI-Spoofing](https://github.com/patterniha/SNI-Spoofing)
- UI/UX reference: [UAC-SNI-Spoofer-Android](https://github.com/Floxu1/UAC-SNI-Spoofer-Android)
