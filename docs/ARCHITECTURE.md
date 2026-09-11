# تحلیل معماری — Architecture Analysis (UAC-SNI-Spoofer-Android 2.0.5)

> مستندات فنی پروژه پذیرفتهشده در این ریپو. تحلیل لایههای غیر-UI بهصورت کامل از مطالعه سورس؛ لایه UI خلاصه از مطالعه مستقیم. برای مجوزها `THIRD_PARTY_NOTICES.md` را ببینید.

## بخش A — معماری کلی

- **State core**: `ConnectionState` (DISCONNECTED/CONNECTING/CONNECTED/DISCONNECTING/ERROR) در `core/ConnectionState.kt`؛ ماشین حالت thread-safe در `core/ConnectionStateMachine.kt`؛ استور سینگلتون `ConnectionStateStore` (StateFlow state + routeProgress) در `core/ConnectionStateStore.kt`؛ `ConnectRouteProgress(current,total)`.
- **کنترل اتصال**: `VpnController` فقط Intent میفرستد (ACTION_CONNECT/DISCONNECT/CLOSE/SWITCH_PROFILE/APPLY_TOR_EXIT/APPLY_POW_EXIT) به `UacVpnService` (`core/VpnController.kt`).
- **انتخاب موتور**: `EngineMode { XRAY_CF, TOR_WEBTUNNEL, UAC_POW }` (`engine/EngineMode.kt`)؛ تغییر فقط در DISCONNECTED/ERROR؛ ذخیره در prefs «connection_engine_v1».
- **سرویسها** (`app/src/main/AndroidManifest.xml`): `UacVpnService` (specialUse FGS)، `PowPsiphonService` (پروسه جدا `:uacpow`)، `UacQuickSettingsTileService`، `RouteSpeedTestService` (dataSync FGS).
- **Threading در UacVpnService**: `SupervisorJob + Dispatchers.IO`، `lifecycleMutex`، شمارنده `generation` برای ابطال jobهای stale؛ هشت job موازی: connect/health/stats/latency/adaptiveLearning/networkWatch/routeProbe/aiRoute (`vpn/UacVpnService.kt`).
- **IPC پروسه PoW**: Messenger با MSG_START/STOP/CONNECTED/SOCKS_PORT/… (`engine/pow/PowPsiphonIpc.kt`)؛ protect(fd) از طریق MSG_PROTECT.
- **لاگ مرکزی**: `AppLogRepository` — حلقه حافظه ۲۰۰۰تایی، سطوح DEBUG…ERROR، منابع APP/SERVICE/ADAPT/XRAY/PROXY/TUN/TOR/POW (فقط in-memory).

## بخش B — VpnService و TUN

- **establishTun**: MTU قابل تنظیم، آدرس 198.18.0.1/32، روت از `TunRouteParser` (پیشفرض 0.0.0.0/0)، DNS 1.1.1.1 (Tor: 198.18.0.2)؛ `allowFamily(AF_INET)` برای ipv4Only/Tor (بلاک IPv6)؛ سپس per-app (ALL_APPS / BYPASS_SELECTED / VPN_ONLY_SELECTED) در `vpn/AppRoutingPreferences.kt`.
- **مسیر ترافیک به تفکیک موتور**:
  - **XRAY_CF (tunnel)**: AAR «libv2ray-native-tun» — `Libv2ray.newCoreController(...).startLoop(config, tun.fd)`؛ خود هسته TUN را میخورد (`vpn/XrayNativeTunEngine.kt`)؛ آمار از `core.queryStats("proxy"|"probe-proxy")`.
  - **TOR_WEBTUNNEL**: `libtor.so` با SOCKS 19050/Control 19051؛ TUN با `libhev-socks5-tunnel` (`HevSocks5Tunnel.TProxyStartService`) (`engine/tor/TorTunRelay.kt`).
  - **UAC_POW (tunnel)**: زنجیره TUN → hev (`PowTun2Socks.kt`) → SOCKS فقط-CONNECT روی 1818 (`PowSocksConnectOnly.kt`) → Psiphon SOCKS 1819 → SOCKS زنجیره Aether 1820 (UpstreamProxyURL در `engine/pow/PowPsiphonProtocols.kt`) → اینترنت (WARP/MASQUE/gool).
  - **XRAY_CF (proxy)**: باینری `libxray.so` با `run -config` و SOCKS لوکال (`mci/MciXrayCore.kt`)؛ batch تا ۳۲ روت همزمان برای screening.
- **Socket protect**: `vpn/SocketProtector.kt` — VpnService.protect + bindSocket به شبکه بدون TRANSPORT_VPN؛ keepalive tuning (idle 11s/intvl 15s/cnt 3).

## بخش C — تولید کانفیگ Xray

- **پارس لینک** (`profiles/ProfileUriParser.kt`): trojan/vless URI، vmess Base64-JSON؛ فقط security `tls|none` (Reality رد میشود)؛ uTLS whitelist با fallback chrome؛ ترنسپورتهای مجاز: `ws, tcp, httpupgrade, grpc, xhttp`.
- **مدل**: `ProxyProfile` (protocol/credential/server/Port/network/security/sni/host/path/alpn/fingerprint/flow/encryption/alterId/serviceName/xhttpMode/…) + دو پروفایل builtin محافظتشده (`builtin:mci`, `builtin:mci2`) + `LocalForwardProfile` برای loopback→SNI:443 + `DirectCompatProfileParser` برای اتصال مستقیم.
- **ساخت JSON** (`mci/MciXrayConfigBuilder.kt`): outbounds proxy/probe-proxy/ai-out/dns-out/block؛ رولها: 53→dns-out، dns-query→proxy، بلاک IPv6 در ipv4Only، بلاک QUIC (udp/443)، دامنههای AI→ai-out؛ inbounds socks-in (sniffing) + tun-in؛ DNS: bootstrap + DoH (Cloudflare/Google/Quad9/AdGuard/OpenDNS) با UseIPv4 + serveStale.
- **streamSettings**: serverName=SNI، allowInsecure، ALPN منطقی (`profiles/TlsAlpnResolver.kt`: grpc/xhttp→h2، ws/httpupgrade→http/1.1)، fingerprint uTLS؛ ws/httpupgrade/grpc/xhttp/http-obfs کامل.
- **Fragment/FinalMask**: `finalmask` بهجای `fragment` استاندارد (هسته Xray داخل libxray.so پچشده) — `{"finalmask":{"tcp":[{type:"fragment",settings:{packets,length,delay,maxSplit}}]}}`.
- **ثابتهای builtin** (`mci/MciConfig.kt`): لبههای Cloudflare، SNI/Host=www.ignitelimit.com، path=/assignment، bridge 127.0.0.1:40443، SOCKS 10808، TUN MTU 1280.

## بخش D — اتصال تطبیقی (Adaptive Connection)

- **Fingerprint** (`vpn/AdaptiveConnection.kt`): transport، carrier، carrierClass (mci/irancell/…)، ASN/provider (HTTPS-identity، کش ۲ دقیقه)، metered/roaming/captive، MTU، IPv4/6، سرعت؛ `learningKey = sha256(transport|asn|class)`.
- **کاندیداها**: `AdaptiveCandidatePlanner.connectPlan` — برنامه متفاوت برای mci/irancell/سایر + `uac-direct-compat` + champion/backup ذخیرهشده + جداسازی cooling-down؛ حداکثر ۱۱ کاندیدا.
- **استخر لبه**: `vpn/ConnectEdgePool.kt` — تا ۱۰ لبه بهازای (learningKey|profileId)، TTL ۳۰ روز.
- **Winner/backup**: `AdaptiveProfileStore` (prefs «adaptive_connection_profiles_v1»)؛ WINNER_TTL ۳۰ روز، FAILURE_COOLDOWN ۲ دقیقه، streak آستانه ۲ در پنجره ۱۰ دقیقه.
- **گیت پذیرش** (`vpn/AdaptiveGatePolicy.kt`): strongHttp+dns+tun → score≥65؛ http+dns → ≥45؛ اسکور از تعداد هدفها/DNS/TUN/latency/bytes.
- **Reconnect**: health هر 30s؛ ۳ شکست متوالی → `scheduleRuntimeRecovery` با backoff؛ network watch هر 3s با ۲ تأیید؛ learning پس از پنجره پایداری 60s.
- **Connect rescue**: کشف لبههای Cloudflare تازه (`profiles/ProfileLatencyTester.kt`, `vpn/CloudflareEdgeDiscovery.kt` — تا ۶۰ کاندیدا، ۲۸ worker، CIDR رسمی CF باندلشده) و retry؛ فازها در `vpn/ConnectRescueStore.kt`.

## بخش E — Route Speed Test (تورنمنت مسیرها)

- **۷ مرحله آمادهسازی** (`profiles/ProfileLatencyTester.kt`): PROFILE_SNAPSHOT → NETWORK_DETECTION → EDGE_POOL → TCP_TLS_PREFLIGHT → XRAY_SCREENING → CONNECTIVITY_VALIDATION → ROUTE_MATRIX.
- **ماتریس**: لبهها(≤10) × ۵ resolver × ۵ tuning × ۴ MTU (تا ۱۰۰۰ ترکیب؛ `vpn/RouteTestArchitecture.kt`). Tuningها: control-off/fast-split/stable-split/deep-split/upload-compat. MTUها: فعلی/1280/1360/1400.
- **سنجش**: batch نمونه Xray روی SOCKS پورت اختصاصی؛ نتایج latency/DNS/payload/throughput/jitter/upload/download/tx-rxDelta/mtuValidated؛ `profiles/RouteTransferProbe.kt` سه فاز LATENCY/UPLOAD(64KB)/DOWNLOAD(تا 50MB)؛ پروب MTU نیتیو با هسته واقعی (`vpn/RouteMtuProbeCoordinator.kt`).
- **ذخیره برنده**: champion + backup بهازای exactStorageKey با score/ping/jitter/upload/download/confidence/mtuValidated.

## بخش F — Config Maker (SNI Maker)

- منبع: SUBSCRIPTION (سقف 24MB) یا CLIPBOARD؛ پارس بازگشتی Base64 (عمق≤۳، تا ۱۰k پروفایل) + dedupe با canonicalUri (`profiles/SubscriptionConfigParser.kt`).
- **Deep Adaptive Test**: screening یکباره لبهها + استخر worker ۱–۴ با timeout ردیف 3–30s؛ پروب HTTP 4s/DNS 2s؛ سطرهای HEALTHY/FAILED؛ ذخیره سالمها در ProfileStore.

## بخش G — اشتراک و پروفایل

- Import: متن/کلیپبورد (`profiles/ProfileStore.kt`)، وبفرم LAN: `PhoneImportServer` پورت 18890–18909 با توکن، QR با zxing.
- ذخیره: prefs «uac_proxy_profiles_v2» + مهاجرت legacy؛ کش تأخیر «profile_real_delay_cache_v1»؛ کشور پروفایل: metadata→cache→geoip.

## بخش H — PoW/WARP + Tor + Psiphon

- **شروع PoW** (`engine/pow/PowConnectionCoordinator.kt`): AetherNative.attach → نردبان خارجی `wireguard → masque → gool` (بودجه 45/35/50s) با `aether_prepare_json`/`aether_start_json` → Psiphon داخلی با UpstreamProxy=socks5://127.0.0.1:1820 (نردبان TLS-OSSH/OSSH/SSH/SS یا FRONTED-MEEK؛ ذخیره برنده نردبان) → پس از اتصال، پل TUN (hev).
- **کیفیت**: baseline ۵ نمونه، تخریب ≥1.6×baseline+180ms، retune cooldown 30s، حداکثر ۳ retune؛ probe median سه هدف (`engine/pow/PowQualityPolicy.kt`).
- **JNI** (`app/src/main/cpp/aether_jni.cpp`): prepare/lastResult/startProxy/stop/isRunning/lastError/attach(protectSocket+onEvent) — با `System.loadLibrary("aether")` + `"aether_jni"`؛ توابع اضافی بدون مصرفکننده: `aether_start_json_with_tun`، `aether_zt_*`.
- **پچهای AAR (زیرساخت حیاتی)**:
  - `scripts/isolate_psiphon_aar.py`: حذف `go/**` از classes.jar Psiphon و تغییرنام `libgojni.so`→`libgopsi.so` (همزیستی دو runtime gomobile).
  - `scripts/patch_v2ray_seq.py`: جراحی بایتکد `go/Seq.<clinit>` در AAR libv2ray — جایگزینی loadLibrary("gojni") با `GoJniLoader.load()` که بسته به پروسه (`:uacpow`) `libgopsi.so` یا `gojni` را لود میکند (`engine/pow/GoJniLoader.kt`).
- **Tor** (`engine/tor/`): torrc با SocksPort 19050، webtunnel bridges (merge lastGood + user + bundled)، رتبهبندی لایو بریجها با پروب WebSocket، bootstrap-watch، تغییر کشور خروجی live از control port (SETCONF ExitNodes + NEWNYM).

## بخش I — پایش و ذخیرهسازی

- آمار ترافیک هر 5s (Xray: queryStats؛ Tor/PoW: TProxyGetStats) — `vpn/TrafficStatsStore.kt`؛ تأخیر زنده median/min/max/jitter (`vpn/ConnectionMetricsStore.kt`).
- کشور/IP خروجی: از SOCKS همان موتور به ipwho.is / ipapi.co، کش ۱۰ دقیقه (`vpn/ExitIpInfoRepository.kt`).
- **AI clean hop** (`ai/AiRouteController.kt`): پروفایل دوم فقط برای دامنههای AI (routine `ai-out`) با پروب chatgpt.com/cdn-cgi/trace.
- آپدیت: GitHub Releases API + DownloadManager (`update/AppUpdateManager.kt`).

## بخش J — خلاصه لایه UI (مطالعه مستقیم)

- ۴۲ فایل؛ شاخصها: `RouteSpeedTestController.kt` (۲۵۷۰ خط) + `RouteSpeedTestScreen.kt` (۲۴۸۵) + `SniMakerScreen.kt` (۱۴۱۲) + `HomeMetricDialogs.kt` (۱۲۵۴) + `ConfigsScreen.kt` (۱۱۷۰) + `MainScreen.kt` (۱۰۳۰).
- ناوبری کشویی: Home (بسته به موتور: Configs/TorCountry/PowCountry) · SNI Maker · Route Speed Test · Live Logs · App Bypass · Settings · Advanced Settings · PoW Settings · Tor Settings · Support.
- سیستم طراحی: Compose Material3 + فونت Vazirmatn با ارقام فارسی، موج نقطهای انیمیشنی، دوزبانه fa/en با pref «language»، حالت TV (TV_MODE buildConfig، `TvFocus.kt`، `WideShell.kt`)، Rescue Overlay هنگام اتصال، راهنمای گامبهگام Home.
