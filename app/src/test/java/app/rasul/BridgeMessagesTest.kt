package app.rasul

import android.content.res.Configuration
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BridgeMessagesTest {
    @Test fun keySpecAuthorizesRawTlsSigningAndOldKeysRequirePairing() {
        val spec = app.rasul.bridge.KeystoreDeviceIdentity.keySpec("device", false)
        assertEquals(setOf(android.security.keystore.KeyProperties.DIGEST_NONE, android.security.keystore.KeyProperties.DIGEST_SHA256), spec.digests.toSet())
        val failure = org.junit.Assert.assertThrows(app.rasul.bridge.BridgeAccessException::class.java) {
            app.rasul.bridge.KeystoreDeviceIdentity.requireCompatible(arrayOf(android.security.keystore.KeyProperties.DIGEST_SHA256))
        }
        assertEquals(app.rasul.data.HermesError.DeviceKeyRepair, app.rasul.bridge.bridgeError(failure))
        assertEquals(app.rasul.bridge.CreateBotError.DeviceKeyRepair, app.rasul.bridge.createTransportError(failure))
        app.rasul.bridge.KeystoreDeviceIdentity.requireCompatible(spec.digests)
    }

    @Test fun bridgeStatesHaveSpecificEnglishAndArabicValues() {
        val context = RuntimeEnvironment.getApplication()
        val en = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) })
        val ar = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale("ar")) })
        listOf(
            Triple(R.string.bridge_key_repair, "This device uses an older pairing key. Remove this PC and pair again.", "يستخدم هذا الجهاز مفتاح اقتران قديمًا. احذف هذا الحاسوب وأعد الاقتران."),
            Triple(R.string.bridge_timeout, "Your PC did not respond in time. Check its status before retrying.", "لم يستجب حاسوبك في الوقت المحدد. تحقق من حالته قبل إعادة المحاولة."),
            Triple(R.string.bridge_unreachable, "Cannot reach this PC. Check that it is online.", "تعذّر الاتصال بهذا الحاسوب. تأكد من أنه متصل."),
            Triple(R.string.bridge_link_dropped, "The connection to your PC dropped. Try again.", "انقطع الاتصال بحاسوبك. حاول مرة أخرى."),
            Triple(R.string.bridge_secure_failed, "The secure link to your PC failed. If it keeps failing, pair the PC again.", "فشل الاتصال الآمن بحاسوبك. إذا تكرر ذلك، أعد إقران الحاسوب."),
            Triple(R.string.bridge_address_unknown, "Can’t find this PC’s address. Check the PC address.", "تعذّر العثور على عنوان هذا الحاسوب. تحقّق من عنوان الحاسوب."),
            Triple(R.string.bridge_refused, "Your PC refused the connection. Check that Rasul is running on it.", "رفض حاسوبك الاتصال. تأكد من أن رسول يعمل عليه."),
            Triple(R.string.bridge_revoked, "This device no longer has access. Remove the PC and pair again.", "لم يعد لهذا الجهاز إذن بالوصول. احذف الحاسوب وأعد الاقتران."),
            Triple(R.string.bridge_profile_not_ready, "This bot is not ready. Complete its setup on the computer.", "هذا المساعد غير جاهز. أكمل إعداده على الحاسوب."),
            Triple(R.string.bridge_response_invalid, "Your PC sent an invalid profile list.", "أرسل حاسوبك قائمة مساعدين غير صالحة."),
            Triple(R.string.bridge_ready, "Ready", "جاهز"),
            Triple(R.string.bridge_not_ready, "Not ready", "غير جاهز"),
            Triple(R.string.bridge_loading, "Loading bots…", "جارٍ تحميل المساعدين…"),
            Triple(R.string.bridge_empty, "No bots on this PC.", "لا يوجد مساعدون على هذا الحاسوب."),
            Triple(R.string.bridge_bots_storage_error, "Saved PCs could not be read.", "تعذّرت قراءة الحواسيب المحفوظة."),
            Triple(R.string.bridge_open_bot, "Open %1\$s", "فتح %1\$s"),
            Triple(R.string.bridge_direct_connections, "Direct connections", "اتصالات مباشرة"),
            Triple(R.string.external_link_unavailable_title, "Can’t open this link", "تعذّر فتح هذا الرابط"),
            Triple(R.string.external_link_unavailable, "No app can open this link. Copy the URL: %1\$s", "لا يوجد تطبيق يمكنه فتح هذا الرابط. انسخ عنوان URL: %1\$s"),
        ).forEach { (id, english, arabic) ->
            assertEquals(english, en.getString(id))
            assertEquals(arabic, ar.getString(id))
        }
        val unavailableContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException()
            }
        }
        var unavailableAddress: String? = null
        app.rasul.ui.launchExternal(unavailableContext, "url") { unavailableAddress = it }
        assertEquals("url", unavailableAddress)
        assertEquals("No app can open this link. Copy the URL: url", en.getString(R.string.external_link_unavailable, unavailableAddress))
        assertEquals("لا يوجد تطبيق يمكنه فتح هذا الرابط. انسخ عنوان URL: url", ar.getString(R.string.external_link_unavailable, unavailableAddress))
    }
}
