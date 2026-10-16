package app.rasul.bridge

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/** Tests provide an ephemeral software identity; production uses a non-exportable key. */
interface DeviceIdentity {
    val privateKey: PrivateKey
    val certificateChain: Array<X509Certificate>
}

class KeystoreDeviceIdentity(private val alias: String) : DeviceIdentity {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    init {
        require(alias.isNotBlank())
        synchronized(KeystoreDeviceIdentity::class.java) {
            if (!store.containsAlias(alias)) {
                if (Build.VERSION.SDK_INT >= 28) {
                    try {
                        generate(strongBox = true)
                    } catch (_: StrongBoxUnavailableException) {
                        generate(strongBox = false)
                    }
                } else {
                    generate(strongBox = false)
                }
            }
        }
    }

    internal companion object {
        val signingDigests = arrayOf(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256)
        fun keySpec(alias: String, strongBox: Boolean): KeyGenParameterSpec {
            val now = System.currentTimeMillis()
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(*signingDigests)
                .setCertificateSubject(X500Principal("CN=Device"))
                .setCertificateSerialNumber(BigInteger(128, SecureRandom()).add(BigInteger.ONE))
                .setCertificateNotBefore(Date(now - 60_000))
                .setCertificateNotAfter(Date(now + 10L * 365 * 24 * 60 * 60 * 1000))
            if (Build.VERSION.SDK_INT >= 28) spec.setUnlockedDeviceRequired(true).setIsStrongBoxBacked(strongBox)
            return spec.build()
        }
        fun requireCompatible(digests: Array<String>) {
            if (!digests.contains(KeyProperties.DIGEST_NONE)) throw BridgeAccessException(app.rasul.data.HermesError.DeviceKeyRepair)
        }
    }

    private fun generate(strongBox: Boolean) {
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            .apply { initialize(keySpec(alias, strongBox)) }.generateKeyPair()
    }

    override val privateKey: PrivateKey get() = (store.getKey(alias, null) as PrivateKey).also { key ->
        val info = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        requireCompatible(info.digests)
    }
    override val certificateChain: Array<X509Certificate>
        get() = store.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()
}

class DeviceKeyManager(private val identity: DeviceIdentity) : X509ExtendedKeyManager() {
    private val alias = "device"
    private fun supports(types: Array<out String>?) = types?.any { it == "EC" } == true
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) =
        if (keyType == "EC") arrayOf(alias) else null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
        if (supports(keyType)) alias else null
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) =
        if (supports(keyType)) alias else null
    override fun getCertificateChain(alias: String?) = if (alias == this.alias) identity.certificateChain else null
    override fun getPrivateKey(alias: String?) = if (alias == this.alias) identity.privateKey else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}
