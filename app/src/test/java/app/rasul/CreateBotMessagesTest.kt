package app.rasul

import android.content.res.Configuration
import app.rasul.bridge.CreateBotError
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CreateBotMessagesTest {
    @Test fun everyCreationErrorHasExactEnglishAndArabicValues() {
        val context = RuntimeEnvironment.getApplication()
        val en = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val ar = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale("ar")) })
        val expected = listOf(
            Triple(R.string.create_bot, "New bot", "مساعد جديد"),
            Triple(R.string.create_on_bridge, "New bot on %1\$s", "مساعد جديد على %1\$s"),
            Triple(R.string.create_name, "Bot name", "اسم المساعد"),
            Triple(R.string.create_invalid_name, "Use 2–32 lowercase letters (a–z), digits or hyphens. The name default is reserved.", "استخدم من حرفين إلى ٣٢ حرفًا إنجليزيًا صغيرًا (a–z) أو أرقامًا أو شرطات. الاسم default محجوز."),
            Triple(R.string.create_template, "Template", "القالب"),
            Triple(R.string.create_selected, "Selected", "محدّد"),
            Triple(R.string.create_no_templates, "No templates are available on this PC. Ask the owner to add one.", "لا تتوفر قوالب على هذا الحاسوب. اطلب من المالك إضافة قالب."),
            Triple(R.string.create_confirm, "Confirm and create", "تأكيد وإنشاء"),
            Triple(R.string.create_retry, "Confirm and retry", "تأكيد وإعادة المحاولة"),
            Triple(R.string.create_edit, "Edit details for a new attempt", "تعديل البيانات لمحاولة جديدة"),
            Triple(R.string.create_reload_templates, "Reload templates", "إعادة تحميل القوالب"),
            Triple(R.string.create_confirming, "Confirm your identity to create this bot.", "أكّد هويتك لإنشاء هذا المساعد."),
            Triple(R.string.create_progress, "Creating bot… Waiting for your PC.", "جارٍ إنشاء المساعد… في انتظار الحاسوب."),
            Triple(R.string.create_waiting, "Bot created. Waiting for the live list…", "تم إنشاء المساعد. في انتظار تحديث القائمة…"),
            Triple(R.string.create_name_taken, "This name is already used or reserved. Choose another name.", "هذا الاسم مستخدم أو محجوز. اختر اسمًا آخر."),
            Triple(R.string.create_template_unknown, "This template is no longer available. Reload the templates.", "لم يعد هذا القالب متاحًا. أعد تحميل القوالب."),
            Triple(R.string.create_template_unavailable, "The source for this template is unavailable. Choose another template.", "مصدر هذا القالب غير متاح. اختر قالبًا آخر."),
            Triple(R.string.create_rate_limited, "Too many requests. Wait a little, then retry this attempt.", "الطلبات كثيرة. انتظر قليلًا ثم أعد هذه المحاولة."),
            Triple(R.string.create_invalid_request, "Your PC could not read the create request. Update Rasul on your PC.", "تعذّر على حاسوبك قراءة طلب الإنشاء. حدّث رسول على حاسوبك."),
            Triple(R.string.create_invalid_request_id, "Your PC rejected the request identifier. Start a new attempt.", "رفض حاسوبك معرّف الطلب. ابدأ محاولة جديدة."),
            Triple(R.string.create_request_conflict, "This request identifier belongs to different details. Start a new attempt.", "معرّف هذا الطلب مرتبط ببيانات مختلفة. ابدأ محاولة جديدة."),
            Triple(R.string.create_operation_unknown, "Your PC no longer has this operation. Check the bots list before starting again.", "لم تعد هذه العملية موجودة على حاسوبك. تحقق من قائمة المساعدين قبل البدء مجددًا."),
            Triple(R.string.create_failed, "Your PC could not create this bot. Ask the owner to inspect it on the computer.", "تعذّر على حاسوبك إنشاء هذا المساعد. اطلب من المالك فحصه على الحاسوب."),
            Triple(R.string.create_uncertain, "The creation outcome is uncertain. Check the bots on the computer before trying again.", "نتيجة الإنشاء غير مؤكدة. تحقق من المساعدين على الحاسوب قبل المحاولة مجددًا."),
            Triple(R.string.create_invalid_response, "Your PC sent an invalid creation response. Update Rasul on your PC.", "أرسل حاسوبك استجابة إنشاء غير صالحة. حدّث رسول على حاسوبك."),
            Triple(R.string.create_route_missing, "Rasul on this PC does not support bot creation. Update it on your PC.", "لا يدعم رسول على هذا الحاسوب إنشاء المساعدين. حدّثه على حاسوبك."),
            Triple(R.string.create_headers_too_large, "The creation headers are too large for your PC. Update Rasul on your PC.", "ترويسات طلب الإنشاء أكبر مما يقبله حاسوبك. حدّث رسول على حاسوبك."),
            Triple(R.string.create_invalid_header, "Your PC rejected a creation header. Update Rasul on your PC.", "رفض حاسوبك إحدى ترويسات طلب الإنشاء. حدّث رسول على حاسوبك."),
            Triple(R.string.create_duplicate_header, "Your PC received a duplicate creation header. Update Rasul on your PC.", "تلقى حاسوبك ترويسة مكرّرة في طلب الإنشاء. حدّث رسول على حاسوبك."),
            Triple(R.string.create_unsupported_framing, "Your PC does not support this creation request format. Update Rasul on your PC.", "لا يدعم حاسوبك صيغة طلب الإنشاء هذه. حدّث رسول على حاسوبك."),
            Triple(R.string.create_invalid_length, "Your PC rejected the creation request length. Update Rasul on your PC.", "رفض حاسوبك طول طلب الإنشاء. حدّث رسول على حاسوبك."),
            Triple(R.string.create_body_too_large, "The creation request is too large for your PC. Update Rasul on your PC.", "طلب الإنشاء أكبر مما يقبله حاسوبك. حدّث رسول على حاسوبك."),
            Triple(R.string.create_auth_unavailable, "Set up a screen lock or strong biometric on this device before creating a bot.", "أعِدّ قفل الشاشة أو وسيلة تحقق حيوية قوية على هذا الجهاز قبل إنشاء مساعد."),
            Triple(R.string.create_bridge_removed, "This saved PC was removed. Return to the bots list.", "حُذف هذا الحاسوب المحفوظ. عُد إلى قائمة المساعدين."),
            Triple(R.string.bridge_profile_not_ready, "This bot is not ready. Complete its setup on the computer.", "هذا المساعد غير جاهز. أكمل إعداده على الحاسوب."),
            Triple(R.string.bridge_revoked, "This device no longer has access. Remove the PC and pair again.", "لم يعد لهذا الجهاز إذن بالوصول. احذف الحاسوب وأعد الاقتران."),
            Triple(R.string.bridge_key_repair, "This device uses an older pairing key. Remove this PC and pair again.", "يستخدم هذا الجهاز مفتاح اقتران قديمًا. احذف هذا الحاسوب وأعد الاقتران."),
            Triple(R.string.bridge_timeout, "Your PC did not respond in time. Check its status before retrying.", "لم يستجب حاسوبك في الوقت المحدد. تحقق من حالته قبل إعادة المحاولة."),
            Triple(R.string.bridge_unreachable, "Cannot reach this PC. Check that it is online.", "تعذّر الاتصال بهذا الحاسوب. تأكد من أنه متصل."),
            Triple(R.string.bridge_link_dropped, "The connection to your PC dropped. Try again.", "انقطع الاتصال بحاسوبك. حاول مرة أخرى."),
            Triple(R.string.bridge_secure_failed, "The secure link to your PC failed. If it keeps failing, pair the PC again.", "فشل الاتصال الآمن بحاسوبك. إذا تكرر ذلك، أعد إقران الحاسوب."),
            Triple(R.string.bridge_address_unknown, "Can’t find this PC’s address. Check the PC address.", "تعذّر العثور على عنوان هذا الحاسوب. تحقّق من عنوان الحاسوب."),
            Triple(R.string.bridge_refused, "Your PC refused the connection. Check that Rasul is running on it.", "رفض حاسوبك الاتصال. تأكد من أن رسول يعمل عليه."),
        )
        assertTrue(expected.map { it.first }.containsAll(CreateBotError.entries.map { it.message }))
        expected.forEach { (id, english, arabic) ->
            assertEquals(english, en.getString(id))
            assertEquals(arabic, ar.getString(id))
        }
    }
}
