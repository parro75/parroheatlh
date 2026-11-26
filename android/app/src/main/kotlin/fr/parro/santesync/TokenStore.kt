package fr.parro.santesync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Jetons OAuth de lecture, chiffrés (AES-256-GCM) par une clé du Keystore Android, non exportable.
 * Une copie des préférences ne suffit donc pas à les réutiliser ailleurs.
 */
class TokenStore(context: Context) {
    private val sp = context.getSharedPreferences("parro_tokens", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String? = try {
        val raw = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
    } catch (e: Exception) {
        null // clé perdue (réinstallation, restauration) : il faudra se reconnecter
    }

    var accessToken: String?
        get() = sp.getString("access", null)?.let(::decrypt)
        set(v) = sp.edit { if (v == null) remove("access") else putString("access", encrypt(v)) }

    var refreshToken: String?
        get() = sp.getString("refresh", null)?.let(::decrypt)
        set(v) = sp.edit { if (v == null) remove("refresh") else putString("refresh", encrypt(v)) }

    /** Expiration du jeton d'accès (epoch ms). */
    var accessExpiresAt: Long
        get() = sp.getLong("access_exp", 0L)
        set(v) = sp.edit { putLong("access_exp", v) }

    val isLoggedIn: Boolean get() = sp.contains("refresh")

    /** Jeton d'ingestion (écriture vers le serveur), chiffré comme les jetons OAuth (26/11/2026). */
    var ingestToken: String?
        get() = sp.getString("ingest", null)?.let(::decrypt)
        set(v) = sp.edit { if (v.isNullOrEmpty()) remove("ingest") else putString("ingest", encrypt(v)) }

    /** Déconnexion de la LECTURE seulement : le jeton d'ingestion (synchro) est gardé. */
    fun clear() = sp.edit { remove("access"); remove("refresh"); remove("access_exp") }

    companion object {
        private const val ALIAS = "parro_health_tokens"
    }
}
