package com.jacklugano.termvault.crypto

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jacklugano.termvault.ssh.HostKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chiavi ed25519 "locali": generate in-app, mai esportate in chiaro.
 *
 * La chiave privata (PKCS#8) è salvata in EncryptedSharedPreferences, la cui
 * master key AES256-GCM vive nell'Android Keystore (hardware-backed quando il
 * dispositivo lo supporta). Se l'host è configurato con "passphrase da KP2A",
 * il blob è cifrato una seconda volta con AES-GCM derivata dalla passphrase
 * (PBKDF2-HMAC-SHA256): senza KP2A la chiave non è utilizzabile.
 */
@Singleton
class LocalKeyManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "local_ssh_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun exists(alias: String): Boolean = prefs.contains("$alias.private")

    fun isPassphraseProtected(alias: String): Boolean =
        prefs.getBoolean("$alias.protected", false)

    /** Genera una nuova coppia ed25519; se [passphrase] non è null protegge il PKCS#8. */
    fun generate(alias: String, passphrase: CharArray?): String {
        val generator = KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val keyPair = generator.generateKeyPair()

        var privateBlob = keyPair.private.encoded // PKCS#8
        val protectedByPassphrase = passphrase != null && passphrase.isNotEmpty()
        var salt: ByteArray? = null
        var iv: ByteArray? = null
        if (protectedByPassphrase) {
            salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            privateBlob = aesGcm(Cipher.ENCRYPT_MODE, passphrase!!, salt, iv, privateBlob)
        }

        prefs.edit()
            .putString("$alias.private", Base64.encodeToString(privateBlob, Base64.NO_WRAP))
            .putString("$alias.public", Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))
            .putBoolean("$alias.protected", protectedByPassphrase)
            .putString("$alias.salt", salt?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
            .putString("$alias.iv", iv?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
            .apply()

        return publicKeyOpenSsh(alias)
    }

    fun delete(alias: String) {
        prefs.edit()
            .remove("$alias.private").remove("$alias.public")
            .remove("$alias.protected").remove("$alias.salt").remove("$alias.iv")
            .apply()
    }

    /** KeyProvider sshj per la connessione. [passphrase] richiesta solo se protetta. */
    fun keyProvider(alias: String, passphrase: CharArray?): KeyProvider {
        val keyPair = loadKeyPair(alias, passphrase)
        return KeyPairWrapper(keyPair)
    }

    /** Riga OpenSSH ("ssh-ed25519 AAAA... termvault-<alias>") da mettere in authorized_keys. */
    fun publicKeyOpenSsh(alias: String): String {
        val pub = loadPublicKey(alias)
        return "${HostKeys.keyType(pub)} ${HostKeys.encode(pub)} termvault-$alias"
    }

    fun publicKeyFingerprint(alias: String): String =
        HostKeys.fingerprintSha256(loadPublicKey(alias))

    private fun loadPublicKey(alias: String): java.security.PublicKey {
        val encoded = Base64.decode(
            prefs.getString("$alias.public", null)
                ?: error("Chiave locale '$alias' inesistente"),
            Base64.NO_WRAP,
        )
        val factory = KeyFactory.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        return factory.generatePublic(X509EncodedKeySpec(encoded))
    }

    private fun loadKeyPair(alias: String, passphrase: CharArray?): KeyPair {
        var privateBlob = Base64.decode(
            prefs.getString("$alias.private", null)
                ?: error("Chiave locale '$alias' inesistente"),
            Base64.NO_WRAP,
        )
        if (isPassphraseProtected(alias)) {
            require(passphrase != null && passphrase.isNotEmpty()) {
                "La chiave '$alias' richiede una passphrase"
            }
            val salt = Base64.decode(prefs.getString("$alias.salt", null)!!, Base64.NO_WRAP)
            val iv = Base64.decode(prefs.getString("$alias.iv", null)!!, Base64.NO_WRAP)
            privateBlob = aesGcm(Cipher.DECRYPT_MODE, passphrase, salt, iv, privateBlob)
        }
        val factory = KeyFactory.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME)
        val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateBlob))
        privateBlob.fill(0)
        return KeyPair(loadPublicKey(alias), privateKey)
    }

    private fun aesGcm(
        mode: Int,
        passphrase: CharArray,
        salt: ByteArray,
        iv: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val keySpec = PBEKeySpec(passphrase, salt, 120_000, 256)
        val derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(keySpec).encoded
        keySpec.clearPassword()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(mode, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, iv))
        derived.fill(0)
        return cipher.doFinal(data)
    }
}
