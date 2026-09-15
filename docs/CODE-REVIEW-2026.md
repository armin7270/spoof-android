# گزارش بررسی و تحلیل کامل پروژه — Full Code Review

تاریخ: بازبینی کامل روی commit `c8b6c3c` (branch `master`)
روش: تحلیل استاتیک عمیق + **اجرای واقعی تست‌ها و lint** روی همین ماشین

> این سند نتیجه یک بازبینی کامل است. هر یافته با فایل:خط و شواهد اجرایی مستند شده است.
> موارد تأییدشدهی درست هم ذکر شده تا پوشش مشخص باشد.

---

## ۰) خلاصه‌ی مدیریتی

| موضوع | وضعیت |
|---|---|
| معماری کلی | **خوب** — لایه‌بندی تمیز، ماشین حالت thread-safe، معماری چند-موتوره منسجم |
| تست‌ها | **۲۵۱ تست، ۳ تست شکست‌خورده** (قطعی و بازتولیدپذیر) |
| Lint | **۱۷ خطا (error) + ۳۸ هشدار** — `lintDebug` بیلد را abort می‌کند |
| پشتیبانی Android 7 | **ادعا شده (minSdk 24) اما سه مسیر کد روی API 24-25 کرش می‌کنند** |
| CI | **تست و lint اجرا نمی‌شود** → به همین دلیل خرابی‌ها تا حالا دیده نشده |
| امنیت اسرار | **تمیز** — هیچ `keystore.properties`/کلید در گیت نیست |
| حجم مخزن | `.git` ≈ ۱۹۵MB، jniLibs ≈ ۱۷۷MB ترک‌شده |
| امضای APK | **درست** — کلید اختصاصی `CN=SNI Spoofing Android, O=armin7270` |

**مهم‌ترین نکته:** پروژه به‌طور واقعی بیلد می‌شود ولی **۳ تست و ۱۷ خطای lint خراب‌اند** و هیچ‌کس متوجه نمی‌شود چون CI فقط APK می‌سازد.

---

## ۱) وضعیت پایه‌ی پروژه (اندازه‌گیری‌شده)

- کد: **۱۸۷ فایل Kotlin، ~۳۹٬۲۷۶ خط** + ۲ فایل Java + کد native (C/C++ و CMake)
- ماژول‌ها: `:app` (تک‌ماژول) + `core/aether` (Rust، PoW/WARP) + `core/quiche`
- بزرگ‌ترین فایلها: `RouteSpeedTestController.kt` ۲۵۷۰ · `RouteSpeedTestScreen.kt` ۲۴۸۵ · `ProfileLatencyTester.kt` ۱۸۸۱ · `UacVpnService.kt` ۱۷۶۸
- کتابخانه‌های native از پیش بیلدشده در `app/src/main/jniLibs` (libxray ۳۳MB، libgojni، libgopsi، libtor، libwebtunnel، libaether)
- تست: ۴۹ فایل در `app/src/test` (بدون instrumentation test)

### ابزار و اجرا (تأییدشده)
- JDK 17 (Temurin): `C:\Users\ARMIN7270\jdk17\jdk-17.0.20.1+1`
- Android SDK: در `local.properties` به یک مسیر **temp** اشاره می‌کند → **شکننده و قابل بازتولید نیست**
- `python` لازم است چون دو تسک بیلد AAR را با اسکریپت پایتون patch می‌کنند

---

## ۲) یافته‌های قطعی با شواهد اجرایی

### 🔴 [CRITICAL] سه تست شکست‌خورده — drift بین تست و سورس

```
251 tests completed, 3 failed
PowTunRelayConfigTest > yamlRoutesThroughLocalSocksWithMapDnsAndCappedMtu FAILED
TorExecTest > argvDefaultsToDirectExec FAILED
TorExecTest > argvWithLinkerPrefixesTheSystemLinker FAILED
```

**(الف) تست مقدار قدیمی را می‌خواهد** — `engine/pow/PowTunRelayConfig.kt:44,47` vs `test/.../PowEngineIsolationTest.kt:108,110`
```kotlin
// سورس:  connect-timeout: 5000    tcp-buffer-size: 1048576
// تست:   assertTrue(yaml.contains("connect-timeout: 15000"))
//        assertTrue(yaml.contains("tcp-buffer-size: 524224"))
```
مقادیر تیونینگ در سورس تغییر کرده ولی تست به‌روز نشده. یعنی **رفتار واقعی تیونینگ هیچ پوشش تستی ندارد**.
اصلاح: تست را با مقادیر فعلی sync کنید (یا اگر مقادیر عمدی عوض شده‌اند، تست را با نام ثابت‌ها بنویسید تا دوباره drift نکند).

**(ب) باگ واقعی در `TorExec`** — `engine/tor/TorExec.kt:17`
```kotlin
check(command.all { it.isNotEmpty() && !it.any(Char::isWhitespace) }) {
    "Tor exec path cannot contain spaces: ${command.joinToString(" ")}"
}
```
این `check` روی **هر عنصر آرگومان** حساس است، نه فقط مسیر باینری — پس `extraArgs` مثل `-f <path>` را هم رد می‌کند.
تست با مسیر حاوی فاصله (`...\spoof android\app\webtunnel`) شکست می‌خورد چون مسیر پوشه‌ی خود پروژه فاصله دارد.
نکته‌ی مهم: در اندروید مسیر برنامه فاصله ندارد، پس این باگ در production بی‌اثر است — **ولی تست را می‌شکند و روی میزبان‌های دیگر (و مسیرهای داده‌ی کاربر) واقعاً می‌تواند منفجر شود**.
اصلاح: فقط مسیر باینری را اعتبارسنجی کنید (`binary.absolutePath`) و آرگومان‌های داده را مجاز بدانید.

###  [CRITICAL] ۱۷ خطای lint — نقض سطح API با `minSdk 24`

`lintDebug` با شکست تمام می‌شود:
```
Lint found 17 errors, 38 warnings.
AdaptiveConnection.kt:132  Error: Call requires API level 29 (current min is 24): LinkProperties#getMtu
```

مسیرهای **بدون گارد** (کرش واقعی روی Android 7/8):

| فایل:خط | فراخوانی | API لازم | وضعیت |
|---|---|---|---|
| `vpn/AdaptiveConnection.kt:132` | `links?.mtu` | 29 | ❌ بدون گارد |
| `vpn/AdaptiveConnection.kt:320` | `links?.mtu` | 29 |  بدون گارد |
| `engine/pow/PowConnectionCoordinator.kt:654` | `LinkProperties#getMtu` | 29 | ❌ بدون گارد |
| `engine/tor/TorDaemon.kt:39,173,336,338` | `Process#isAlive` / `destroyForcibly` / `waitFor` | 26 | ❌ بدون گارد |
| `ui/UacQuickSettingsTileService.kt:112` | `startActivityAndCollapse(Intent)` | deprecated | ⚠️ فقط deprecation |

**تأیید گارد‌دار بودن (هشدار کاذب lint):** `ui/AnimatedDottedWave.kt:73-107` درست با `if (Build.VERSION.SDK_INT >= P)` و `@RequiresApi(P)` محافظت شده — اینها را اصلاح نکنید.

اصلاح پیشنهادی: برای `mtu` یک helper سازگار بنویسید (روی `<29` مقدار `0` برگردانید یا از `NetworkCapabilities` استفاده کنید)، و در `TorDaemon` یک wrapper با `SDK_INT >= O` بگذارید.

---

## ۳) یافته‌های بحرانی معماری و همزمانی (تأییدشده در سورس)

### 🔴 [CRITICAL] `runBlocking` روی ترد اصلی در `onDestroy`
`vpn/UacVpnService.kt:186`
```kotlin
serviceScope.cancel()
runBlocking(Dispatchers.IO) { runCatching { cleanupRoute() } }
```
`onDestroy` روی main thread اجرا می‌شود و `cleanupRoute()` تا پایان پروسه‌ی native Xray صبر می‌کند (`MciXrayCore.kt:137-178`). اگر هسته هنگ کند → **ANR**.
اصلاح: پاک‌سازی را در یک scope جدا (`CoroutineScope(Dispatchers.IO)`) یا قبل از `cancel()` انجام دهید.

### 🔴 [CRITICAL] نشت `ParcelFileDescriptor` در `XrayNativeTunEngine`
`vpn/XrayNativeTunEngine.kt:38-40` (تأیید‌شده)
```kotlin
val tun = checkNotNull(establishTun()) { ... }
descriptor = tun
val core = Libv2ray.newCoreController(...)   // ← بیرون از try (که از خط ۵۵ شروع می‌شود)
```
اگر `newCoreController` خطا بدهد، `descriptor` باز می‌ماند.
اصلاح: خطوط ۴۰-۵۴ را داخل `try` موجود ببرید.

### 🔴 [CRITICAL] انتشار APK بدون امضا در CI + نصب بدون بررسی
`app/build.gradle.kts:61-63` + `.github/workflows/build.yml:40-55` + `update/AppUpdateManager.kt:146-151,222-229`
در CI فایل `keystore.properties` وجود ندارد → `signingConfig` می‌شود `null` → APK بدون امضا ساخته و آپلود می‌شود؛ سپس آپدیت‌کننده دارایی را با heuristic نام فایل انتخاب می‌کند و بدون هیچ بررسی digest/امضا نصب می‌کند.
اصلاح: بیلد release بدون امضا را **fail** کنید و SHA-256 دارایی منتشرشده را قبل از نصب بررسی کنید.

### 🟠 [HIGH] `stopSelf()` بدون بررسی generation — می‌تواند connect جدید را بکشد
`vpn/UacVpnService.kt:504-526`
```kotlin
pendingRouteProbe?.cancelAndJoin(); pendingConnect?.cancelAndJoin()  // بدون timeout
...
ConnectionStateStore.markDisconnected()   // خط ۵۲۱ — وضعیت CONNECTING جدید را overwrite می‌کند
stopSelf()                                 // خط ۵۲۵ — بدون startId
```
اگر کاربر «قطع» و بعد سریع «اتصال» بزند، ممکن است connect در میانه کشته شود در حالی که UI می‌گوید قطع شد.
اصلاح: `startId` را نگه دارید، `stopSelfResult(startId)` و قبل از آن `if (token != generation.get()) return@launch`.

###  [HIGH] اقدامات توقف با `startService` فرستاده می‌شوند
`core/VpnController.kt:16,21,26,33,40` + `ui/MainActivity.kt:159-166`
```kotlin
fun stop(context: Context) { context.startService(intent) }   // نه startForegroundService
// MainActivity: catch (_: Throwable) { ConnectionStateStore.markDisconnected() }
```
اگر `startService` رد شود، خطا خورده می‌شود و UI «قطع شد» نشان می‌دهد در حالی که **تونل هنوز بالاست**.
اصلاح: از `ContextCompat.startForegroundService` استفاده کنید و فقط با تأیید سرویس وضعیت را عوض کنید.

###  [HIGH] IPv6 مسدود نمی‌شود وقتی `ipv4Only=false`
`vpn/UacVpnService.kt:1135`
```kotlin
.apply { if (settings.ipv4Only || torRelay) allowFamily(OsConstants.AF_INET) }
```
در حالت IPv6، هیچ آدرس/روت v6 اضافه نمی‌شود؛ برنامه‌هایی که IPv6 را ترجیح می‌دهند **از بیرون تونل** خارج می‌شوند — نشت جدی برای یک کلاینت ضد-DPI.
(مسیر PoW این را درست hard-code کرده: خط ۱۱۶۰.)

###  [HIGH] نشتی/هنگ در مدیریت پروسه‌های native
- `mci/MciXrayCore.kt:137-172` — `check(exited)` بعد از `destroyForcibly` پرتاب می‌کند در حالی که `process` هنوز set است؛ `process` فقط در مسیر موفق null می‌شود (خط ۱۵۳) → **پروسه‌ی زنده پورت SOCKS را نگه می‌دارد** و `start()` بعدی آن را «آماده» می‌بیند.
- `engine/tor/TorDaemon.kt:154-179` — Tor در-JVM روی Thread اجرا می‌شود؛ وقتی `join(4_000)` timeout می‌خورد فقط log می‌کند، بعد `running=false` می‌شود و `start()` بعدی `lock`/ControlSocket زنده را **پاک می‌کند**.
- `app/src/main/cpp/aether_jni.cpp:158-168` — `nativeAttach` دو بار `GetMethodID` صدا می‌زند **بدون `ExceptionCheck()`**؛ اگر signature drift کند، محافظت سوکت بی‌صدا از کار می‌افتد (fd نشت می‌کند).

### 🟠 [HIGH] تزریق JSON در `xhttpExtra`
`mci/MciXrayConfigBuilder.kt:299`
```kotlin
identity.xhttpExtra.takeIf(String::isNotBlank)?.let { add("\"extra\":$it") }
```
رشته‌ی خام بدون escape تزریق می‌شود. `ProfileNetworks.normalizeExtra` (`ProfileNetworks.kt:42-51`) فقط چک می‌کند کاراکتر اول/آخر `{`/`}` است و **هرگز parse نمی‌کند**. پس `extra={},"mux":{"enabled":true}` فیلدهای همسایه تزریق می‌کند یا کل کانفیگ را می‌شکند.
*(توابع `q()` برای اعتبارنامه/SNI/host/path **درست** هستند — `MciXrayConfigBuilder.kt:375-379` با ترتیب صحیح `\\` قبل از `"`.)*

###  [HIGH] حلقه‌های پس‌زمینه‌ی پرهزینه = مصرف باتری
- `vpn/UacVpnService.kt:1430-1535` — در حالت بی‌کاری هر ۳۰ ثانیه سه probe (HTTP+DNS+TUN) اجرا می‌شود؛ یعنی دقیقاً وقتی دستگاه idle است، رادیو بیدار می‌شود و Doze نقض می‌شود.
- `vpn/UacVpnService.kt:1369-1374` — watcher شبکه هر **۳ ثانیه** تا پایان اتصال poll می‌کند.
- `vpn/ExitIpInfoRepository.kt:266` — کلید کش با `hashCode()` ۳۲ بیتی (احتمال برخورد) و بدون eviction.
- `ui/RouteSpeedTestController.kt:2151-2216` — `final_stage_snapshot:<sha>` کل rows با همه‌ی observations را ذخیره می‌کند و `clearPersistedRows` (خط ۲۳۸۶) آن‌ها را **پاک نمی‌کند** → رشد نامحدود SharedPreferences.
- `vpn/AdaptiveConnection.kt:504-539` — `MAX_CANDIDATES = 11` عملاً ~۱۶ کاندیدا برمی‌گرداند.

### 🟠 [HIGH] منطق «مدل تطبیقی» — ایرادهای واقعی
منظور از «مدل» در این پروژه، موتور یادگیرنده‌ی تطبیقی است (`AdaptiveConnection` + `AiRouteController`). ایرادها:
- `ai/AiRouteController.kt:52-53,91-97,125-136` — رأی‌ها (verdict) **هیچ TTL ندارند** با اینکه کامنت خط ۱۳۱ می‌گوید «با شبکه expire می‌شوند»؛ یک شکست گذرا کل پروفایل را **برای همیشه** ban می‌کند و کلیدها هرگز evict نمی‌شوند.
- `vpn/AdaptiveConnection.kt:807-832` — پنجره‌ی streak و cooldown با `System.currentTimeMillis()` کار می‌کنند؛ با عقب رفتن ساعت (NTP/کاربر) `now - previous` منفی می‌شود و **cooldown بی‌صدا باطل می‌شود**. بقیه‌ی کدبیس درست از `SystemClock.elapsedRealtime()` استفاده می‌کند.
- `profiles/ProfileLatencyTester.kt:1740-1747` — `ServerSocket(0).use { it.localPort }` سوکت را **می‌بندد** و فقط پورت را در یک HashSet درون-پروسه‌ای ثبت می‌کند → پورت واقعاً رزرو نشده (۲۵ پورت همزمان در خط ۱۱۳۲).
- `vpn/CloudflareEdgeDiscovery.kt:627-628` — تنوع subnet فقط روی backup اعمال می‌شود؛ هر ۸ لبه‌ی اصلی می‌توانند در یک /24 باشند.
- `vpn/CloudflareEdgeDiscovery.kt:186,203,536` — `CloudflareEdgeHistoryStore` روی `this` قفل می‌کند ولی هر discovery یک instance جدید می‌سازد → read-modify-write بدون انحصار متقابل، تاریخچه گم می‌شود.
- `vpn/RouteMtuProbeCoordinator.kt:86-91` — دو `check` بایت‌به‌بایت یکسان (کد مرده؛ re-capture گم شده).

### 🟠 [HIGH] عملکرد UI
- `ui/RouteSpeedTestScreen.kt:264-265` — `visibleRows()` (مرتب‌سازی تا ۱۰۰۰ سطر) + `sortRouteRows` + fold **در هر recomposition**، بدون `remember`/`derivedStateOf`. با هر تکمیل probe دوباره اجرا میشود.
- `vpn/RouteMtuProbeCoordinator.kt:69,118-120` — join با `NonCancellable` تا ۱۰ ثانیه **داخل** `measurementMutex` انجام می‌شود و همه‌ی probeهای دیگر را بلاک می‌کند.

### 🟡 [MEDIUM] موارد دیگر
- `profiles/PhoneImportServer.kt:167-183` — خواندن هدر بایت‌به‌بایت و **بدون سقف اتصال همزمان**؛ هر همتا در LAN می‌تواند سوکت‌ها را باز نگه دارد. (`token` با `SecureRandom` ۱۶ بایتی درست است، خط ۱۵۲-۱۶۲.)
- `profiles/ProfileUriParser.kt:256` — `require(key !in result) { "Duplicate parameter" }` پارامتر تکراری را رد می‌کند، در حالی که بعضی پنل‌ها `&type=ws&type=ws` می‌فرستند → کانفیگ سالم بی‌دلیل رد می‌شود.
- `engine/tor/WebTunnelHandshake.kt:111-117` — `X509TrustManager` که **هر گواهی را قبول می‌کند** (مسیر probe بریج webtunnel).
- `engine/tor/TorNativeRuntime.kt:43-45` — `setReadable(true, false)` (world-readable) قبل از `chmod 0700`؛ ترتیب باید برعکس باشد.
- `update/AppUpdateManager.kt:88-100` — فایل APK با نام timestamp در حافظه‌ی خارجی، بدون پاک‌سازی و بدون بررسی package/cert.
- `vpn/ConnectionMetricsStore.kt:66-71` — jitter با یک نمونه `0` گزارش می‌شود (باید `null` باشد).
- `vpn/XrayNativeTunEngine.kt:123` — کلید init به‌صورت base64 در سورس commit شده.
- `vpn/AppRoutingPreferences.kt:115-135` — وقتی پکیج‌های انتخابی حذف شده‌اند، بی‌صدا به «همه‌ی برنامه‌ها» سقوط می‌کند (فقط `Log.w`).
- `vpn/ExitIpInfoRepository.kt:135-144` و `vpn/UacVpnService.kt:871-898` — `catch (Throwable)` که `Error` را هم می‌بلعد و به منطق reconnect می‌دهد.

---

## ۴) یافته‌های مربوط به بیلد، امنیت و مخزن

### 🔴 آپدیت‌کننده به مخزن **upstream** اشاره میکند
`update/AppUpdateManager.kt:185,187` و `ui/SupportScreen.kt:123`
```kotlin
REPOSITORY_URL = "https://github.com/Floxu1/UAC-SNI-Spoofer-Android"
LATEST_RELEASE_API = "https://api.github.com/repos/Floxu1/UAC-SNI-Spoofer-Android/releases/latest"
```
این فورک `applicationId = com.armin7270.snispoof` با امضای اختصاصی دارد، اما دکمه‌ی «بررسی آپدیت» به releaseهای **پروژه‌ی اصلی** نگاه می‌کند. نتیجه: یا آپدیت اشتباه پیشنهاد می‌شود، یا نصب با خطای امضا شکست می‌خورد.
اصلاح: به مخزن خودتان تغییر دهید (یا آپدیت را غیرفعال کنید تا مخزن مقصد مشخص شود).

### 🟠 حجم و بسته‌بندی
- `app/build.gradle.kts:61-62` — `isMinifyEnabled = false` و `isShrinkResources = false` در release → `classes.dex` **۳۱MB** و کد بدون obfuscation.
- `app/build.gradle.kts:116-125` — `keepDebugSymbols` برای همه‌ی هسته‌ها + `useLegacyPackaging = true` + `extractNativeLibs = true` در manifest.
- `classes.dex` 31MB + `libxray.so` 33.6MB + `libgojni` 33.7MB + `libgopsi` 29.7MB → APK معماری‌محور ۷۰MB و universal **۱۹۸MB**.
- `assets/geoip.dat` 17.7MB و `assets/tor/geoip6` 15.3MB هم قابل trim هستند.

### 🟠 ضعف CI
`.github/workflows/build.yml` — فقط `assembleDebug` و `assembleRelease`. **هیچوقت `test`، `lint` یا `check` اجرا نمی‌شود** — دقیقاً دلیل اینکه ۳ تست شکسته و ۱۷ خطای lint تا امروز پنهان مانده‌اند.

### 🟠 شکنندگی اسکریپت‌های patch
- `app/build.gradle.kts:206,216` — `commandLine("python", ...)` با نام خام (CI با `ln -sf` دور زده شده).
- `scripts/patch_v2ray_seq.py:119-135` — چک `if LOADER.encode() not in body` عملاً **همیشه true است** (رشته در constant pool افزوده‌شده هست) → drift در AAR بی‌صدا در آفست اشتباه patch می‌شود.

### 🟢 موارد درست (تأییدشده)
- **اسرار:** هیچ `keystore.properties` / `local.properties` / `*.keystore` در گیت نیست؛ `.gitignore` خطوط ۱۴ و ۱۹ درست کار می‌کنند.
- **امضا:** APK با کلید اختصاصی امضا شده (`CN=SNI Spoofing Android, OU=Mobile, O=armin7270, C=IR`, SHA256withRSA, معتبر تا ۲۰۵۶).
- **Manifest:** `allowBackup="false"`, `fullBackupContent="false"`, `usesCleartextTraffic="false"`؛ همه‌ی سرویسها `exported="false"` جز tile؛ `specialUse` + `FOREGROUND_SERVICE_SPECIAL_USE` درست اعلام شده.
- **JNI:** مدیریت null/release/attach/detach رشته‌ها درست؛ هیچ `strcpy`/`sprintf`/`malloc` در `aether_jni.cpp`.
- **Per-app routing:** `require(allowed == null || disallowed == null)`، پکیج خود برنامه همیشه حذف میشود، `NameNotFoundException` کرش نمی‌کند.
- **ماشین حالت:** انتقال‌ها `@Synchronized`؛ `markConnected` از حالت غیر-CONNECTING را رد می‌کند.
- **کامپوز:** هیچ نوشتن state خارج از main thread نیست؛ تعداد workerها واقعاً کراندار است.
- **بدون WakeLock/GlobalScope/Thread.sleep** در لایه‌ی `vpn/`.
- **پوشش تست موجود:** ۲۴۸ تست سالم روی parser، config builder، ALPN، adaptive gate، state machine و ابزارهای VPN.

---

## ۵) برنامه‌ی اصلاح پیشنهادی (اولویت‌دار)

| # | کار | اثر | ریسک |
|---|---|---|---|
| ۱ | افزودن `test` + `lint` به CI | جلوگیری از تکرار drift | خیلی کم |
| ۲ | رفع ۳ تست شکست‌خورده (sync مقادیر + اصلاح `TorExec`) | بیلد سبز | کم |
| ۳ | رفع ۴ مسیر کرش API 24/25 (`getMtu` ×۳، `Process.isAlive` ×۱) | پشتیبانی واقعی Android 7 | کم |
| ۴ | رفع `runBlocking` در `onDestroy` + نشت PFD در `XrayNativeTunEngine` | حذف ANR و نشت fd | کم |
| ۵ | `stopSelfResult` + گارد generation در `requestDisconnect` | حذف connect گم‌شده | متوسط |
| ۶ | `startForegroundService` برای اکشن‌های توقف + وضعیت تأییدشده | UI دیگر دروغ نمی‌گوید | کم |
| ۷ | رفع نشت IPv6 در حالت `ipv4Only=false` | بستن حفره‌ی نشت | کم |
| ۸ | escape کردن `xhttpExtra` با parse واقعی | جلوگیری از تزریق/خرابی کانفیگ | کم |
| ۹ | تصحیح آدرس مخزن در آپدیت‌کننده | آپدیت درست | خیلی کم |
| ۱۰ | شکست‌دادن release بدون امضا + بررسی SHA-256 | امنیت زنجیره‌ی تأمین | متوسط |
| ۱۱ | TTL برای رأی‌های AI + `elapsedRealtime` در cooldown | «مدل» تطبیقی واقعاً یاد می‌گیرد | کم |
| ۱۲ | backoff در health/network watch | باتری | کم |
| ۱۳ | eviction برای `final_stage_snapshot` و کش exit-IP | جلوگیری از رشد prefs | کم |
| ۱۴ | `remember`/`derivedStateOf` در `RouteSpeedTestScreen` | روانی UI | کم |
| ۱۵ | فعال‌سازی R8 + حذف `keepDebugSymbols` از release + trim assets | کاهش چشمگیر حجم | متوسط |
| ۱۶ | مقاوم‌سازی kill پروسه‌های native (process-group) | حذف پورت‌های اشغال‌شده | متوسط |

---

## ۶) پوشش و محدودیت‌های این بازبینی

**پوشش داده‌شده:** کل لایه‌ی `vpn/`, `core/`, `mci/`, `profiles/`, `ai/`, `engine/tor`, `engine/pow`, `update/`, `settings/`, `ui/RouteSpeedTestController.kt`, `ui/RouteSpeedTestService.kt`، JNI/C++، Gradle/CI/scripts، Manifest و مخزن.

**پوشش ناقص (صادقانه):** سه فایل بزرگ UI به‌طور کامل خط‌به‌خط خوانده نشدند: `RouteSpeedTestScreen.kt` (~۲۷۰ از ۲۴۸۵ خط)، `SniMakerScreen.kt`، `MainScreen.kt`، `ConfigsScreen.kt`، `AppDrawer.kt` و انتهای `ProfileLatencyTester.kt`. یافته‌های مربوط به این‌ها فقط از بررسی‌های هدفمند است.

**اجرا نشده:** تست روی دستگاه/شبیه‌ساز واقعی (رفتار واقعی تونل، Tor، PoW و اعتبارسنجی MTU تست نشده است).