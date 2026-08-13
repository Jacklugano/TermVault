package com.jacklugano.termvault.ssh

import com.jacklugano.termvault.data.db.KnownHostDao
import com.jacklugano.termvault.data.db.KnownHostEntity
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

// NB: java.util.Base64 (API 26+) e non android.util.Base64, così la logica
// known_hosts è testabile con unit test JVM puri.
object HostKeys {

    /** Codifica wire SSH (RFC 4253) della chiave pubblica, in base64. */
    fun encode(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        return Base64.getEncoder().encodeToString(blob)
    }

    /** Fingerprint in formato OpenSSH: "SHA256:<base64 senza padding>". */
    fun fingerprintSha256(key: PublicKey): String = fingerprintSha256OfBlob(
        Buffer.PlainBuffer().putPublicKey(key).compactData
    )

    fun fingerprintSha256OfBlob(blob: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun keyType(key: PublicKey): String = KeyType.fromKey(key).toString()
}

/** Dettagli della chiave presentata dal server, per il dialog di verifica. */
data class HostKeyInfo(
    val hostname: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
    val fingerprint: String,
)

/** Prima connessione: chiave sconosciuta, serve conferma dell'utente. */
class UnknownHostKeyException(val info: HostKeyInfo) :
    Exception("Chiave host sconosciuta per ${info.hostname}:${info.port} (${info.fingerprint})")

/** ALLARME: la chiave presentata non corrisponde a quella salvata. */
class HostKeyMismatchException(
    val info: HostKeyInfo,
    val storedFingerprint: String,
) : Exception(
    "MISMATCH chiave host per ${info.hostname}:${info.port}: attesa $storedFingerprint, ricevuta ${info.fingerprint}"
)

/**
 * Verifier sshj che consulta la tabella known_hosts locale.
 * - chiave già salvata e identica -> ok
 * - stessa (host, porta, tipo) ma chiave diversa -> [HostKeyMismatchException]
 * - nessuna chiave salvata di quel tipo -> [UnknownHostKeyException]
 * Le eccezioni interrompono la connect() di sshj e vengono intercettate per
 * mostrare il prompt (o l'allarme) all'utente.
 */
class DatabaseHostKeyVerifier(
    private val dao: KnownHostDao,
) : HostKeyVerifier {

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val keyType = HostKeys.keyType(key)
        val encoded = HostKeys.encode(key)
        val fingerprint = HostKeys.fingerprintSha256(key)
        val info = HostKeyInfo(hostname, port, keyType, encoded, fingerprint)

        val entries = runBlocking { dao.findFor(hostname, port) }
        val sameType = entries.filter { it.keyType == keyType }

        return when {
            sameType.any { it.publicKeyBase64 == encoded } -> true
            sameType.isNotEmpty() ->
                throw HostKeyMismatchException(info, sameType.first().fingerprintSha256)
            else -> throw UnknownHostKeyException(info)
        }
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
        runBlocking { dao.findFor(hostname, port) }.map { it.keyType }
}

fun HostKeyInfo.toEntity() = KnownHostEntity(
    hostname = hostname,
    port = port,
    keyType = keyType,
    publicKeyBase64 = publicKeyBase64,
    fingerprintSha256 = fingerprint,
)
