package app.rasul

import android.content.res.Configuration
import app.rasul.bridge.InstructionsError
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstructionsMessagesTest {
    @Test fun everyInstructionsMessageHasExactEnglishAndArabicValues() {
        val context = RuntimeEnvironment.getApplication()
        val en = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val ar = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale("ar")) })
        val expected = listOf(
            Triple(R.string.instructions_title, "Instructions", "التعليمات"),
            Triple(R.string.instructions_label, "Bot instructions", "تعليمات المساعد"),
            Triple(R.string.instructions_timing, "Saved instructions apply when you start a new chat. Existing chats may keep their earlier instructions.", "تُطبَّق التعليمات المحفوظة عند بدء محادثة جديدة. قد تحتفظ المحادثات الحالية بتعليماتها السابقة."),
            Triple(R.string.instructions_direct, "Editing instructions needs Rasul Bridge on the PC. Connect this bot through a paired bridge to edit them here.", "لتعديل التعليمات، تحتاج إلى Rasul Bridge على الحاسوب. اربط هذا المساعد بجسر مقترن لتعديلها هنا."),
            Triple(R.string.instructions_limit, "Limit: 20,000 bytes. A smaller limit set on the PC may shorten these instructions.", "الحد الأقصى ٢٠٬٠٠٠ بايت. قد يؤدي ضبط حد أصغر على الحاسوب إلى اختصار هذه التعليمات."),
            Triple(R.string.instructions_template_notice, "If this bot came from a template, updating the template on the PC may replace these instructions.", "إذا أُنشئ هذا المساعد من قالب، فقد يؤدي تحديث القالب على الحاسوب إلى استبدال هذه التعليمات."),
            Triple(R.string.instructions_reload, "Reload", "إعادة التحميل"),
            Triple(R.string.instructions_loading, "Loading instructions…", "جارٍ تحميل التعليمات…"),
            Triple(R.string.instructions_saving, "Saving instructions…", "جارٍ حفظ التعليمات…"),
            Triple(R.string.instructions_saved, "Saved. Start a new chat to use these instructions.", "تم الحفظ. ابدأ محادثة جديدة لاستخدام هذه التعليمات."),
            Triple(R.string.instructions_unsaved_title, "Discard unsaved changes?", "هل تريد تجاهل التغييرات غير المحفوظة؟"),
            Triple(R.string.instructions_unsaved_body, "Your changes have not been saved to the PC.", "لم تُحفَظ تغييراتك على الحاسوب."),
            Triple(R.string.instructions_discard, "Discard changes", "تجاهل التغييرات"),
            Triple(R.string.instructions_keep_editing, "Keep editing", "متابعة التعديل"),
            Triple(R.string.instructions_conflict, "Changed on the PC since you opened it", "تغيّرت التعليمات على الحاسوب منذ أن فتحتها"),
            Triple(R.string.instructions_unsafe_path, "The instructions file or its folder is a link or is unsafe. Check it on the PC.", "ملف التعليمات أو مجلده رابط أو مسار غير آمن. تحقّق منه على الحاسوب."),
            Triple(R.string.instructions_too_large, "These instructions exceed 20,000 bytes. Shorten them before saving.", "تتجاوز هذه التعليمات ٢٠٬٠٠٠ بايت. اختصرها قبل الحفظ."),
            Triple(R.string.instructions_not_utf8, "The instructions file is not valid UTF-8 text. Fix its encoding on the PC.", "ملف التعليمات ليس نصًا صالحًا بترميز UTF-8. صحّح ترميزه على الحاسوب."),
            Triple(R.string.instructions_invalid, "The bridge rejected the instructions request. Check the bridge version.", "رفض الجسر طلب التعليمات. تحقّق من إصدار الجسر."),
            Triple(R.string.instructions_profile_missing, "This bot no longer exists on the PC. Return to the bots list.", "لم يعد هذا المساعد موجودًا على الحاسوب. عُد إلى قائمة المساعدين."),
            Triple(R.string.instructions_rate_limited, "Too many instructions requests. Wait a minute before reloading or saving.", "طلبات التعليمات كثيرة جدًا. انتظر دقيقة قبل إعادة التحميل أو الحفظ."),
            Triple(R.string.instructions_unavailable, "The PC could not read or save the instructions file. Check its permissions and available storage.", "تعذّر على الحاسوب قراءة ملف التعليمات أو حفظه. تحقّق من أذوناته ومساحة التخزين المتاحة."),
            Triple(R.string.instructions_unsupported, "This bridge does not support editing instructions. Update it on the PC.", "هذا الجسر لا يدعم تعديل التعليمات. حدّثه على الحاسوب."),
            Triple(R.string.instructions_invalid_response, "The bridge sent an invalid instructions response. Check the bridge version.", "أرسل الجسر استجابة غير صالحة للتعليمات. تحقّق من إصدار الجسر."),
            Triple(R.string.instructions_bridge_removed, "This saved bridge was removed. Return to the bots list.", "حُذف هذا الجسر المحفوظ. عُد إلى قائمة المساعدين."),
            Triple(R.string.instructions_headers_too_large, "The instructions request headers are too large. Check the bridge version.", "ترويسات طلب التعليمات كبيرة جدًا. تحقّق من إصدار الجسر."),
            Triple(R.string.instructions_invalid_header, "The bridge rejected a header in the instructions request. Check its version.", "رفض الجسر ترويسة في طلب التعليمات. تحقّق من إصداره."),
            Triple(R.string.instructions_duplicate_header, "The bridge received a duplicate instructions header. Check its version.", "تلقى الجسر ترويسة مكرّرة في طلب التعليمات. تحقّق من إصداره."),
            Triple(R.string.instructions_unsupported_framing, "The bridge does not support this instructions request format. Check its version.", "لا يدعم الجسر صيغة طلب التعليمات هذه. تحقّق من إصداره."),
            Triple(R.string.instructions_invalid_length, "The bridge rejected the instructions request length. Check its version.", "رفض الجسر طول طلب التعليمات. تحقّق من إصداره."),
            Triple(R.string.instructions_body_too_large, "The instructions request is too large for the bridge. Shorten the text.", "طلب التعليمات أكبر مما يقبله الجسر. اختصر النص."),
        )
        val shared = setOf(R.string.bridge_revoked, R.string.bridge_key_repair, R.string.bridge_timeout, R.string.bridge_unreachable,
            R.string.bridge_link_dropped, R.string.bridge_secure_failed, R.string.bridge_address_unknown, R.string.bridge_refused)
        assertTrue(expected.map { it.first }.toSet().plus(shared).containsAll(InstructionsError.entries.map { it.message }))
        expected.forEach { (id, english, arabic) ->
            assertEquals(english, en.getString(id))
            assertEquals(arabic, ar.getString(id))
        }
    }
}
