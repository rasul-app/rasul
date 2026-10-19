# Rasul (رسول)

**Control your Hermes Agent from your Android phone.**

Rasul is a free, open-source Android app for people who run [Hermes Agent](https://github.com/NousResearch/hermes-agent) on their own computer. It connects to the Hermes API server on your PC so you can chat with your agent, follow its work live, answer its approval requests and manage its jobs from anywhere.

Rasul is an **unofficial** client. It is not made by or affiliated with Nous Research.

English | [العربية](#العربية)

| Bots | Sessions | Jobs |
|---|---|---|
| <img src="app/src/test/screenshots/01-bots-en.png" width="240"> | <img src="app/src/test/screenshots/04-sessions-en.png" width="240"> | <img src="app/src/test/screenshots/05-jobs-en.png" width="240"> |

## What it does

- **Chat with your agent.** Send a task and see at once that it arrived. A live card then shows what the agent is doing now, how long it has run and how many steps are done. Press and hold any message to copy it.
- **Photos and text files.** Attach up to 4 photos or one small text file to a message; photos are downscaled and stripped of location metadata before sending.
- **Steer or stop a running task.** A message sent while the agent works is added to the running task. Stop ends it.
- **Approvals.** When the agent asks before running something, approve once or deny from the phone.
- **Answer from the notification.** Approve or deny straight from the alert; the phone must be unlocked first.
- **Sessions.** Browse past conversations and read their transcripts. Pin up to 20 chats to the top. Unsent text is kept per chat, encrypted on the phone.
- **Jobs.** See scheduled jobs, when each last ran and whether it worked; pause, resume or run one now. Turn on Job results in More to get a phone notice when a job finishes or fails.
- **Several PCs and profiles.** Save each computer once and switch between them. Hermes profiles (`/p/<profile>/`) are supported.
- **English and Arabic.** Pick the language in the app. Arabic is fully right-to-left.
- **Files from the agent.** When the agent sends you a file from the computer, it appears as a card in the chat. Tap it to download, then open, save or share it. Pictures show inline in the chat. Web-type files and app installers are never opened inside the app.
- **Share into Rasul.** Share photos or text from another app straight into a chat.
- **Connection check.** See what works on the computer side and what to fix.
- **MCP servers.** Switch servers on or off, test them and add new ones from the catalog.
- **Hermes logs.** With Rasul Bridge, read the latest error, agent and gateway log lines on the phone. Keys and tokens are hidden by the bridge before the lines leave the computer.
- **Messaging channels.** See Telegram and other channels per bot, switch them on or off, test them, and connect a new Telegram bot from the phone. The bot token stays on the computer, and Hermes restarts only when it is idle unless you choose to restart now. With Rasul Bridge you can also set up WhatsApp by scanning a code, and edit a channel’s settings.
- **App updates.** The app checks this repository’s GitHub Releases for new versions and can update itself, verified against the release checksum and signing key. Switch it off in More.
- **Light and dark theme.**
- **App lock.** Protect the app with your phone’s screen lock or a strong biometric.

There is no Rasul server. The app talks to your own computer, plus (optional, can be turned off in More) GitHub Releases of this repo to check for app updates. No accounts, no tracking, no analytics, no ads. The Telegram setup link is opened only when you tap it.

## Status

Early development (version 0.2.9). Pre-release APKs are on the Releases page.

## Requirements

- Hermes Agent **v0.22.0 or newer** on your computer, with a working model provider.
- An Android phone with Android 8.0 (API 26) or newer.
- An authenticated HTTPS endpoint for access from the phone.

## Rasul Bridge (recommended)

Use [Rasul Bridge](bridge/README.md) for the recommended pinned TLS companion protocol. It keeps profile keys on the computer and supports local device pairing and revocation. This package provides the computer side; the app pairs with it by scanning its QR code. Direct Hermes connections remain the advanced option described below.

## Approvals from computer sessions

The optional [phone approval integration](docs/bridge-approvals.md) adds computer-side routes for listing and answering pending approvals while keeping the local approval card active. Setup requires a profile plugin and a separate private bridge listener. The phone shows pending requests as cards under each bridge bot (see the doc for details).

Pairing works over the same Wi-Fi or over Tailscale (bridge `init --lan` / `--tailscale`, in 0.2.6). Hermes can also be updated from the phone (0.2.6).

## Direct Hermes connection (advanced)

1. Turn on the Hermes API server. Add these lines to the Hermes `.env` file on your computer (Windows: the `hermes` folder in your local application data folder, otherwise `~/.hermes`, or the folder named by `HERMES_HOME`):

   ```
   API_SERVER_ENABLED=true
   API_SERVER_KEY=<a long random secret>
   ```

   Create the secret with a password manager or, for example, `openssl rand -hex 32`. Treat it like a password.

2. Publish the local API through an authenticated HTTPS endpoint with a trusted certificate (for example `https://example.com`). Private and overlay network addresses also require HTTPS. Plain HTTP is restricted to the phone’s loopback interface.

3. Restart the Hermes gateway (`hermes gateway`).

See the [Hermes API server documentation](https://github.com/NousResearch/hermes-agent) for all options.

## Connect the app

1. Open Rasul and tap the add button on the **Bots** tab.
2. Enter a name, the address (for example `https://example.com`), the profile if you use one, and the API key.
3. Tap **Test connection**. Rasul checks the server and which features it supports.
4. Save, then tap the bot to open the chat.

To use the bridge instead, run its pairing command on the computer and tap **Pair a bridge** in the app and scan the QR code. Credential-bearing pairing links are no longer accepted.

## Security

**The API key gives full control of your computer.** Anyone with the address and the key can make the agent run terminal commands and change files.

- Never expose the Hermes API server to the public internet. Use Tailscale or your home network.
- Direct connections require `https`, except the phone’s loopback interface. Private and overlay network addresses also require `https`.
- The key is encrypted with a key held in the Android Keystore and is excluded from phone backups.
- The app does not follow redirects, so a server cannot silently send your key elsewhere.

Found a security problem? Please open a private security advisory on this repository instead of a public issue.

## Privacy

Speech input is turned into text by your phone’s speech service. Depending on that service, your voice may be sent to its provider (for example Google). Rasul does not record or store audio.

## Build from source

Requirements: JDK 21 and the Android SDK (API 35).

```
git clone <this repository URL>
cd rasul
echo "sdk.dir=/path/to/Android/sdk" > local.properties
./gradlew :app:assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

### Tests

- Unit tests: `./gradlew :app:testDebugUnitTest`
- Screenshot tests run on the JVM (Roborazzi + Robolectric), with no device. Every screen preview is captured in English and Arabic, plus the Bots list in dark mode, to `app/src/test/screenshots/`.
  - Record new images: `./gradlew :app:recordRoborazziDebug`
  - Check against the committed images: `./gradlew :app:verifyRoborazziDebug`

Built with Kotlin, Jetpack Compose (Material 3), OkHttp, kotlinx.serialization and DataStore.

## Contributing

Issues and pull requests are welcome. Please keep each pull request to one change, add tests for behaviour changes, keep English and Arabic strings in step, and run the unit and screenshot tests before you open it.

## License

[Apache License 2.0](LICENSE).

Hermes Agent is a project of Nous Research. "Hermes" is used here only to name the software this app connects to.

---

<div dir="rtl">

## العربية

# رسول

**تحكّم بوكيل Hermes من هاتف أندرويد.**

رسول تطبيق أندرويد مجاني ومفتوح المصدر لمن يشغّل [Hermes Agent](https://github.com/NousResearch/hermes-agent) على حاسوبه. يتصل بخادم Hermes API على حاسوبك، فتستطيع محادثة الوكيل ومتابعة عمله مباشرة والرد على طلبات الموافقة وإدارة المهام المجدولة من أي مكان.

رسول تطبيق **غير رسمي**، ولا علاقة له بشركة Nous Research.

### ماذا يقدّم

- **المحادثة مع الوكيل:** أرسل مهمة وسترى فورًا أنها وصلت، ثم تعرض بطاقة حيّة ما يفعله الوكيل الآن ومنذ متى يعمل وعدد الخطوات المنجزة. اضغط مطولًا على أي رسالة لنسخها.
- **توجيه المهمة الجارية أو إيقافها:** الرسالة التي ترسلها أثناء عمل الوكيل تُضاف إلى المهمة الجارية، وزر الإيقاف ينهيها.
- **الموافقات:** عندما يطلب الوكيل الإذن قبل تنفيذ أمر، وافق مرة واحدة أو ارفض من الهاتف.
- **الرد من الإشعار:** وافق أو ارفض مباشرة من التنبيه، بعد فتح قفل الهاتف.
- **الجلسات:** تصفّح المحادثات السابقة واقرأ نصوصها. ثبّت حتى 20 محادثة في الأعلى، ويُحفظ النص غير المرسل لكل محادثة مشفّرًا على الهاتف.
- **المهام المجدولة:** اعرض المهام ومتى عملت كل منها آخر مرة وهل نجحت، وأوقفها مؤقتًا أو استأنفها أو شغّلها الآن. فعّل «نتائج المهام» من «المزيد» ليصلك إشعار عند انتهاء أي مهمة أو فشلها.
- **أكثر من حاسوب وملف تعريف:** احفظ كل حاسوب مرة واحدة وبدّل بينها، مع دعم ملفات تعريف Hermes.
- **العربية والإنجليزية:** اختر اللغة من داخل التطبيق، والواجهة العربية من اليمين إلى اليسار بالكامل.
- **سمة فاتحة وداكنة.**
- **ملفات من الوكيل:** عندما يرسل لك الوكيل ملفًا من الحاسوب يظهر كبطاقة في المحادثة. اضغط عليها لتنزيله ثم افتحه أو احفظه أو شاركه. وتظهر الصور داخل المحادثة مباشرة. الملفات من نوع الويب ومثبّتات التطبيقات لا تُفتح داخل التطبيق أبدًا.
- **المشاركة إلى رسول:** شارك الصور أو النصوص من أي تطبيق آخر إلى محادثة مباشرة.
- **فحص الاتصال:** اعرف ما يعمل في جهة الحاسوب وما يجب إصلاحه.
- **خوادم MCP:** شغّل الخوادم أو أوقفها، واختبرها، وأضف خوادم جديدة من الكتالوج.
- **سجلات Hermes:** مع جسر رسول اقرأ آخر أسطر سجلات الأخطاء والوكيل والبوابة على الهاتف، ويُخفي الجسر المفاتيح والرموز قبل أن تغادر الأسطر الحاسوب.
- **قنوات المراسلة:** اعرض تيليغرام وغيره من القنوات لكل بوت، وشغّلها أو أوقفها، واختبرها، واربط بوت تيليغرام جديدًا من الهاتف. يبقى رمز البوت على الحاسوب، ولا يُعاد تشغيل Hermes إلا عندما يكون خاملًا ما لم تختر إعادة التشغيل الآن. ومع جسر رسول يمكنك أيضًا إعداد واتساب بمسح رمز، وتعديل إعدادات القناة.
- **تحديث التطبيق:** يتحقق التطبيق من إصدارات GitHub لهذا المستودع بحثًا عن نسخ جديدة ويستطيع تحديث نفسه، مع التحقق من مجموع التحقق الخاص بالإصدار ومن مفتاح التوقيع. يمكن إيقاف ذلك من «المزيد».

لا يوجد خادم لرسول. التطبيق يتصل بحاسوبك، إضافةً إلى (اختياري ويمكن إيقافه من «المزيد») إصدارات GitHub لهذا المستودع للتحقق من تحديثات التطبيق، بلا حسابات ولا تتبّع ولا تحليلات ولا إعلانات. ولا يُفتح رابط إعداد تيليغرام إلا عندما تضغط عليه.

### الحالة

قيد التطوير المبكر (الإصدار 0.2.9). ملفات APK التجريبية متاحة في صفحة الإصدارات.

### جسر رسول (موصى به)

يوفّر [جسر رسول](bridge/README.md) بروتوكول اتصال مشفّرًا مع تثبيت هوية الحاسوب، وإقران الأجهزة وإلغاء وصولها محليًا، مع بقاء مفاتيح ملفات التعريف على الحاسوب. هذه الحزمة توفّر جانب الحاسوب، ويقترن بها التطبيق بمسح رمز QR الذي تعرضه. يصبح الاتصال المباشر بـ Hermes الخيار المتقدّم الموضّح أدناه.

يعمل الإقران عبر شبكة Wi-Fi نفسها أو عبر Tailscale (أمر الجسر `init --lan` أو `--tailscale`، ضمن الإصدار 0.2.6). ويمكن أيضًا تحديث Hermes من الهاتف (الإصدار 0.2.6).

### الاتصال المباشر بـ Hermes (خيار متقدّم)

1. فعّل خادم Hermes API بإضافة هذين السطرين إلى ملف `.env` الخاص بـ Hermes (في Windows: المجلد `hermes` داخل مجلد بيانات التطبيقات المحلية، وإلا `~/.hermes`، أو المجلد المحدد في `HERMES_HOME`):

   ```
   API_SERVER_ENABLED=true
   API_SERVER_KEY=<سر طويل عشوائي>
   ```

   أنشئ السر بمدير كلمات المرور أو بالأمر `openssl rand -hex 32`، وعامله ككلمة مرور.

2. أتح الوصول إلى واجهة API المحلية عبر عنوان HTTPS بشهادة موثوقة، مثل `https://example.com`. تتطلب عناوين الشبكات الخاصة HTTPS أيضًا. يُسمح باتصال HTTP غير المشفّر فقط مع واجهة الاسترجاع على الهاتف نفسه.

3. أعد تشغيل Hermes بالأمر `hermes gateway`.

### ربط التطبيق

1. افتح رسول واضغط زر الإضافة في تبويب **الروبوتات**.
2. أدخل اسمًا والعنوان (مثل `https://example.com`) وملف التعريف إن وُجد ومفتاح API.
3. اضغط **اختبار الاتصال**، ثم احفظ واضغط على الروبوت لفتح المحادثة.

لاستخدام الجسر، شغّل أمر الإقران على الحاسوب ثم اضغط **إقران جسر** في التطبيق وامسح رمز QR. لم يعد التطبيق يقبل روابط الإقران التي تحتوي على مفتاح API.

### الأمان

**مفتاح API يمنح تحكمًا كاملًا بحاسوبك:** من يملك العنوان والمفتاح يستطيع جعل الوكيل ينفّذ أوامر ويعدّل الملفات.

- لا تعرّض خادم Hermes API للإنترنت العام أبدًا، واستخدم Tailscale أو شبكة المنزل.
- يتطلب الاتصال المباشر HTTPS، باستثناء واجهة الاسترجاع على الهاتف نفسه.
- يُخزَّن المفتاح مشفّرًا بمفتاح من Android Keystore ولا يدخل في النسخ الاحتياطي للهاتف.

### الترخيص

[رخصة Apache 2.0](LICENSE).

</div>
