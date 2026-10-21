package app.rasul

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PairingMessagesTest {
    @Test fun specificErrorsHaveEnglishAndArabicValues() {
        val context = RuntimeEnvironment.getApplication()
        val en = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val ar = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale("ar")) })
        val command = "\u2066rasul-bridge pair\u2069"
        val messages = listOf(
            Triple(R.string.pair_error_key_missing, "This pairing link has no API key. On your PC, run rasul-bridge pair to create a new link.", "رابط الإقران بلا مفتاح API. شغّل الأمر $command على حاسوبك لإنشاء رابط جديد."),
            Triple(R.string.pair_denied, "Pairing was declined or expired. On your PC, run rasul-bridge pair to try again.", "رُفض الإقران أو انتهت صلاحيته. شغّل الأمر $command على حاسوبك للمحاولة مجددًا."),
            Triple(R.string.pair_qr_expired, "This pairing code has expired. On your PC, run rasul-bridge pair and scan its new QR code.", "انتهت صلاحية رمز الإقران. شغّل الأمر $command على حاسوبك وامسح رمز QR الجديد."),
            Triple(R.string.pair_timeout, "Pairing timed out. Check your PC, run rasul-bridge pair, and scan the new QR code.", "انتهت مهلة الإقران. تحقّق من حاسوبك، وشغّل الأمر $command، ثم امسح رمز QR الجديد."),
            Triple(R.string.pair_rate_limit, "Too many pairing attempts. Wait a minute, then run rasul-bridge pair on your PC and scan its QR code.", "كثرت محاولات الإقران. انتظر دقيقة، ثم شغّل الأمر $command على حاسوبك وامسح رمز QR."),
            Triple(R.string.pair_request_invalid, "Your PC rejected the pairing details. On your PC, run rasul-bridge pair and scan the new QR code.", "رفض حاسوبك بيانات الإقران. شغّل الأمر $command على حاسوبك وامسح رمز QR جديدًا."),
            Triple(R.string.pair_connection, "Could not securely connect to your PC. Check the connection and scan again.", "تعذّر الاتصال الآمن بحاسوبك. تحقّق من الاتصال وامسح الرمز مجددًا."),
            Triple(R.string.pair_response_invalid, "Your PC sent an unsupported pairing response. Update Rasul on your PC.", "أرسل حاسوبك استجابة إقران غير مدعومة. حدّث رسول على حاسوبك."),
            Triple(R.string.pair_storage, "Pairing succeeded but could not be saved. Remove this device on your PC, then run rasul-bridge pair to try again.", "نجح الإقران لكن تعذّر حفظه. احذف هذا الجهاز من حاسوبك، ثم شغّل الأمر $command للمحاولة مجددًا."),
            Triple(R.string.pair_identity, "Could not create the protected device key. Unlock your phone and try again.", "تعذّر إنشاء مفتاح الجهاز المحمي. افتح قفل هاتفك وحاول مجددًا."),
            Triple(R.string.pair_unavailable, "Rasul on your PC is temporarily unavailable. Check your PC, run rasul-bridge pair, and scan a new QR code.", "رسول على حاسوبك غير متاح مؤقتًا. تحقّق من حاسوبك، وشغّل الأمر $command، ثم امسح رمز QR جديدًا."),
            Triple(R.string.pair_header_size, "Your PC rejected oversized pairing headers. Update Rasul on your PC.", "رفض حاسوبك ترويسات الإقران لكبر حجمها. حدّث رسول على حاسوبك."),
            Triple(R.string.pair_header, "Your PC rejected a pairing header. Update Rasul on your PC.", "رفض حاسوبك إحدى ترويسات الإقران. حدّث رسول على حاسوبك."),
            Triple(R.string.pair_duplicate_header, "Your PC received a duplicate pairing header. Update Rasul on your PC.", "تلقّى حاسوبك ترويسة إقران مكررة. حدّث رسول على حاسوبك."),
            Triple(R.string.pair_bad_request, "Your PC could not read the pairing request. Update Rasul on your PC.", "تعذّرت على حاسوبك قراءة طلب الإقران. حدّث رسول على حاسوبك."),
            Triple(R.string.pair_framing, "Your PC does not support this pairing request format. Update Rasul on your PC.", "لا يدعم حاسوبك صيغة طلب الإقران هذه. حدّث رسول على حاسوبك."),
            Triple(R.string.pair_length, "Your PC rejected the pairing request length. Update Rasul on your PC.", "رفض حاسوبك طول طلب الإقران. حدّث رسول على حاسوبك."),
            Triple(R.string.pair_body_size, "The pairing request is too large for your PC. Update Rasul on your PC.", "طلب الإقران أكبر مما يسمح به حاسوبك. حدّث رسول على حاسوبك."),
            Triple(R.string.pair_device_required, "Your PC incorrectly requires a registered device for pairing. Update Rasul on your PC.", "يطلب حاسوبك جهازًا مسجّلًا لإجراء الإقران. حدّث رسول على حاسوبك."),
            Triple(R.string.pair_route, "Rasul on this PC has no supported pairing route. Update it on your PC.", "لا يوفّر رسول على هذا الحاسوب مسار إقران مدعومًا. حدّثه على حاسوبك."),
            Triple(R.string.pair_camera_failed, "The camera could not start. Check camera access and try again.", "تعذّر تشغيل الكاميرا. تحقّق من إذن الكاميرا وحاول مجددًا."),
            Triple(R.string.pair_already_saved, "This PC is already saved. Forget it before pairing again.", "هذا الحاسوب محفوظ بالفعل. احذفه قبل إقرانه مجددًا."),
            Triple(R.string.bridge_storage_error, "Could not read or update saved PCs. Close this screen and try again.", "تعذّرت قراءة الحواسيب المحفوظة أو تحديثها. أغلق هذه الشاشة وحاول مجددًا."),
            Triple(R.string.no_connection, "Pair your PC to see your sessions and jobs here.", "أقرن حاسوبك لعرض جلساتك ومهامك هنا."),
            Triple(R.string.pair_your_pc, "Pair your PC", "أقرِن حاسوبك"),
            Triple(R.string.bots_empty_note, "Works over any internet connection. Your data stays on your PC.", "يعمل عبر أي اتصال بالإنترنت. تبقى بياناتك على حاسوبك."),
            Triple(R.string.pair_guide_title, "Pair your PC", "إقران الحاسوب"),
            Triple(R.string.pair_guide_step_1, "On your PC, install Rasul (see the README in the project).", "ثبّت رسول على حاسوبك (راجع ملف README في المشروع)."),
            Triple(R.string.pair_guide_step_2, "Open a terminal on the PC and run rasul-bridge pair. A QR code appears.", "افتح الطرفية على حاسوبك وشغّل الأمر $command. سيظهر رمز QR."),
            Triple(R.string.pair_guide_step_3, "Scan the QR code with this phone.", "امسح رمز QR بهذا الهاتف."),
            Triple(R.string.pair_guide_step_4, "Check that the 6 digits on your phone and the PC match, then type y on the PC.", "تحقّق من تطابق الأرقام الستة على الهاتف والحاسوب، ثم اكتب \u2066y\u2069 على الحاسوب."),
            Triple(R.string.pair_guide_privacy, "Your phone talks only to your PC. No account, no cloud service of ours.", "يتصل هاتفك بحاسوبك فقط. لا حساب ولا خدمة سحابية تابعة لنا."),
            Triple(R.string.pair_guide_scan, "Scan QR code", "امسح رمز QR"),
            Triple(R.string.pair_guide_direct, "Advanced: direct connection", "متقدم: اتصال مباشر"),
        )
        messages.forEach { (id, english, arabic) ->
            assertEquals(english, en.getString(id))
            assertEquals(arabic, ar.getString(id))
        }
        assertEquals("2)", en.getString(R.string.pair_guide_number, 2))
        assertEquals("٢)", ar.getString(R.string.pair_guide_number, 2))
    }
}
