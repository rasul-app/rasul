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
        val messages = listOf(
            Triple(R.string.pair_denied, "Pairing was declined or expired. Create a new code on your computer.", "رُفض الإقران أو انتهت صلاحيته. أنشئ رمزًا جديدًا على حاسوبك."),
            Triple(R.string.pair_qr_expired, "This pairing code has expired. Create a new code on your computer.", "انتهت صلاحية رمز الإقران. أنشئ رمزًا جديدًا على حاسوبك."),
            Triple(R.string.pair_timeout, "Pairing timed out. Check your computer and scan a new code.", "انتهت مهلة الإقران. تحقّق من حاسوبك وامسح رمزًا جديدًا."),
            Triple(R.string.pair_rate_limit, "Too many pairing attempts. Wait a minute before scanning a new code.", "محاولات الإقران كثيرة. انتظر دقيقة قبل مسح رمز جديد."),
            Triple(R.string.pair_request_invalid, "The bridge rejected the pairing details. Scan a new code.", "رفض الجسر بيانات الإقران. امسح رمزًا جديدًا."),
            Triple(R.string.pair_connection, "Could not securely connect to the bridge. Check the connection and scan again.", "تعذّر الاتصال الآمن بالجسر. تحقّق من الاتصال وامسح الرمز مجددًا."),
            Triple(R.string.pair_response_invalid, "The bridge sent an unsupported pairing response. Check its version.", "أرسل الجسر استجابة إقران غير مدعومة. تحقّق من إصداره."),
            Triple(R.string.pair_storage, "Pairing succeeded but could not be saved. Remove this device on your computer and pair again.", "نجح الإقران لكن تعذّر حفظه. احذف هذا الجهاز من حاسوبك ثم أعد الإقران."),
            Triple(R.string.pair_identity, "Could not create the protected device key. Unlock your phone and try again.", "تعذّر إنشاء مفتاح الجهاز المحمي. افتح قفل هاتفك وحاول مجددًا."),
            Triple(R.string.pair_unavailable, "The bridge is temporarily unavailable. Check your computer and scan a new code.", "الجسر غير متاح مؤقتًا. تحقّق من حاسوبك وامسح رمزًا جديدًا."),
            Triple(R.string.pair_header_size, "The bridge rejected oversized pairing headers. Check the bridge version.", "رفض الجسر ترويسات الإقران لكبر حجمها. تحقّق من إصدار الجسر."),
            Triple(R.string.pair_header, "The bridge rejected a pairing header. Check the bridge version.", "رفض الجسر إحدى ترويسات الإقران. تحقّق من إصدار الجسر."),
            Triple(R.string.pair_duplicate_header, "The bridge received a duplicate pairing header. Check the bridge version.", "تلقّى الجسر ترويسة إقران مكررة. تحقّق من إصدار الجسر."),
            Triple(R.string.pair_bad_request, "The bridge could not read the pairing request. Check the bridge version.", "تعذّرت على الجسر قراءة طلب الإقران. تحقّق من إصدار الجسر."),
            Triple(R.string.pair_framing, "The bridge does not support this pairing request format. Check the bridge version.", "لا يدعم الجسر صيغة طلب الإقران هذه. تحقّق من إصدار الجسر."),
            Triple(R.string.pair_length, "The bridge rejected the pairing request length. Check the bridge version.", "رفض الجسر طول طلب الإقران. تحقّق من إصدار الجسر."),
            Triple(R.string.pair_body_size, "The pairing request is too large for the bridge. Check the bridge version.", "طلب الإقران أكبر مما يسمح به الجسر. تحقّق من إصدار الجسر."),
            Triple(R.string.pair_device_required, "The bridge incorrectly requires a registered device for pairing. Check its version.", "يطلب الجسر جهازًا مسجّلًا لإجراء الإقران. تحقّق من إصداره."),
            Triple(R.string.pair_route, "This bridge has no supported pairing route. Check its version.", "لا يوفّر هذا الجسر مسار إقران مدعومًا. تحقّق من إصداره."),
            Triple(R.string.pair_camera_failed, "The camera could not start. Check camera access and try again.", "تعذّر تشغيل الكاميرا. تحقّق من إذن الكاميرا وحاول مجددًا."),
            Triple(R.string.pair_already_saved, "This bridge is already saved. Forget it before pairing again.", "هذا الجسر محفوظ بالفعل. احذفه قبل إقرانه مجددًا."),
            Triple(R.string.bridge_storage_error, "Could not read or update saved bridges. Close this screen and try again.", "تعذّرت قراءة الجسور المحفوظة أو تحديثها. أغلق هذه الشاشة وحاول مجددًا."),
        )
        messages.forEach { (id, english, arabic) ->
            assertEquals(english, en.getString(id))
            assertEquals(arabic, ar.getString(id))
        }
    }
}
