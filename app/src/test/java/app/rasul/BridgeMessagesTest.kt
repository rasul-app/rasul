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
            Triple(R.string.bridge_key_repair, "This device uses an older pairing key. Remove this bridge and pair again.", "يستخدم هذا الجهاز مفتاح اقتران قديمًا. احذف هذا الجسر وأعد الاقتران."),
            Triple(R.string.bridge_timeout, "The bridge did not respond in time. Check its status before retrying.", "لم يستجب الجسر في الوقت المحدد. تحقق من حالته قبل إعادة المحاولة."),
            Triple(R.string.bridge_unreachable, "Cannot reach this bridge. Check that it is online.", "تعذّر الاتصال بالجسر. تأكد من أنه متصل."),
            Triple(R.string.bridge_revoked, "This device no longer has access. Remove the bridge and pair again.", "لم يعد لهذا الجهاز إذن بالوصول. احذف الجسر وأعد الاقتران."),
            Triple(R.string.bridge_profile_not_ready, "This bot is not ready. Complete its setup on the computer.", "هذا المساعد غير جاهز. أكمل إعداده على الحاسوب."),
            Triple(R.string.bridge_response_invalid, "The bridge sent an invalid profile list.", "أرسل الجسر قائمة مساعدين غير صالحة."),
            Triple(R.string.bridge_ready, "Ready", "جاهز"),
            Triple(R.string.bridge_not_ready, "Not ready", "غير جاهز"),
            Triple(R.string.bridge_loading, "Loading bots…", "جارٍ تحميل المساعدين…"),
            Triple(R.string.bridge_empty, "No bots on this bridge.", "لا يوجد مساعدون على هذا الجسر."),
            Triple(R.string.bridge_bots_storage_error, "Saved bridges could not be read.", "تعذّرت قراءة الجسور المحفوظة."),
            Triple(R.string.bridge_open_bot, "Open %1\$s", "فتح %1\$s"),
            Triple(R.string.bridge_direct_connections, "Direct connections", "اتصالات مباشرة"),
        ).forEach { (id, english, arabic) ->
            assertEquals(english, en.getString(id))
            assertEquals(arabic, ar.getString(id))
        }
    }
}
