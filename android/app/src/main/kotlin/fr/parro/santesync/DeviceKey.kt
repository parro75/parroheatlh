package fr.parro.santesync

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Base64
import org.json.JSONObject
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Clé d'appareil (26/11/2026) : chaque écriture vers le serveur est SIGNÉE par une clé ECDSA P-256 générée
 * dans le Keystore matériel du téléphone (StrongBox si disponible), qui ne peut pas en sortir. Le jeton
 * d'ingestion seul ne suffit donc plus à écrire : il faut aussi ce téléphone.
 *
 * Appairage : la clé publique est envoyée une fois au serveur avec le jeton OAuth (mot de passe + TOTP).
 * Signature : METHODE \n CHEMIN \n TIMESTAMP \n sha256hex(CORPS) (voir server/shc/devices.py).
 */
object DeviceKey {
    private const val ALIAS = "parro_device_sign"

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }
            .generateKeyPair()
    }

    @Synchronized
    private fun publicKey(): PublicKey {
        keyStore().getCertificate(ALIAS)?.publicKey?.let { return it }
        try {
            generate(strongBox = true)
        } catch (_: StrongBoxUnavailableException) {
            generate(strongBox = false) // pas de puce StrongBox : TEE (toujours non exportable)
        }
        return keyStore().getCertificate(ALIAS).publicKey
    }

    private fun privateKey(): PrivateKey {
        publicKey()
        return keyStore().getKey(ALIAS, null) as PrivateKey
    }

    private fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun id(): String = hex(sha256(publicKey().encoded)).take(32)

    /** En-têtes de signature d'une écriture. [url] : l'URL complète appelée (son chemin est signé). */
    fun headers(method: String, url: URL, body: ByteArray): Map<String, String> {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val msg = "${method.uppercase()}\n${url.path}\n$ts\n${hex(sha256(body))}".toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(privateKey()); update(msg) }.sign()
        return mapOf("X-Parro-Device" to id(), "X-Parro-Timestamp" to ts,
            "X-Parro-Signature" to Base64.encodeToString(sig, Base64.NO_WRAP))
    }

    /**
     * Appaire ce téléphone si ce n'est pas déjà fait (jeton OAuth requis). Renvoie true si appairé.
     * Sans connexion de lecture, on réessaiera à la synchro suivante.
     */
    suspend fun ensurePaired(context: Context): Boolean {
        val prefs = Prefs(context)
        if (prefs.deviceId == id()) return true
        return try {
            val res = Api.post(context, "device", JSONObject()
                .put("public_key", Base64.encodeToString(publicKey().encoded, Base64.NO_WRAP))
                .put("label", "${Build.MANUFACTURER} ${Build.MODEL}".take(80)))
            if (res.optString("device_id") == id()) { prefs.deviceId = id(); true } else false
        } catch (_: Exception) {
            false
        }
    }

    /** Le serveur ne reconnaît plus cet appareil (révoqué) : refaire l'appairage à la prochaine synchro. */
    fun forget(context: Context) {
        Prefs(context).deviceId = ""
    }
}
