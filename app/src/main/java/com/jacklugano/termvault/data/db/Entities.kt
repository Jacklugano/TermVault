package com.jacklugano.termvault.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Modalità di autenticazione di un host.
 *
 * - KP2A: username/password/chiave arrivano da un'entry Keepass2Android ([HostEntity.kp2aQuery]).
 * - LOCAL_KEY: chiave ed25519 generata in-app e cifrata nel Keystore; la passphrase
 *   può opzionalmente essere recuperata da KP2A.
 * - PROMPT: password chiesta all'utente alla connessione, mai persistita.
 */
enum class AuthMode { KP2A, LOCAL_KEY, PROMPT }

enum class ForwardType { LOCAL, REMOTE }

@Entity(
    tableName = "hosts",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["jumpHostId"],
            onDelete = ForeignKey.SET_NULL,
        )
    ],
    indices = [Index("jumpHostId")],
)
data class HostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String = "",
    /** Tag separati da virgola, es. "prod,web". */
    val tags: String = "",
    /** Colore ARGB della card/scheda. */
    val color: Int = DEFAULT_COLOR,
    val jumpHostId: Long? = null,
    val authMode: AuthMode = AuthMode.KP2A,
    /** Query per KP2A, es. "ssh://nome-host". */
    val kp2aQuery: String = "",
    /** Alias della chiave locale (solo per authMode = LOCAL_KEY). */
    val localKeyAlias: String? = null,
    /** Se true, con LOCAL_KEY la passphrase viene chiesta a KP2A tramite kp2aQuery. */
    val kp2aForPassphrase: Boolean = false,
    /**
     * Nome del profilo di "OpenVPN for Android" (de.blinkt.openvpn) da attivare
     * prima di connettersi. Vuoto = nessuna VPN.
     */
    val openVpnProfile: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DEFAULT_COLOR: Int = 0xFF00E676.toInt()
    }

    val tagList: List<String>
        get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

@Entity(tableName = "snippets")
data class SnippetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val command: String,
    /** Se true il comando viene eseguito subito (aggiunge \n). */
    val autoRun: Boolean = true,
    val sortOrder: Int = 0,
)

/**
 * Riga di known_hosts: identifica la chiave pubblica presentata da (host, porta, tipo chiave).
 */
@Entity(
    tableName = "known_hosts",
    indices = [Index(value = ["hostname", "port"], unique = false)],
)
data class KnownHostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostname: String,
    val port: Int,
    /** Es. "ssh-ed25519", "rsa-sha2-512". */
    val keyType: String,
    /** Chiave pubblica codificata base64 (formato wire SSH). */
    val publicKeyBase64: String,
    /** Fingerprint SHA-256 in formato OpenSSH ("SHA256:..."), ridondante ma comodo per la UI. */
    val fingerprintSha256: String,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "port_forwards",
    foreignKeys = [
        ForeignKey(
            entity = HostEntity::class,
            parentColumns = ["id"],
            childColumns = ["hostId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("hostId")],
)
data class PortForwardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hostId: Long,
    val type: ForwardType = ForwardType.LOCAL,
    /** Indirizzo di bind lato "sorgente" (locale per LOCAL, remoto per REMOTE). */
    val bindHost: String = "127.0.0.1",
    val bindPort: Int,
    val targetHost: String = "127.0.0.1",
    val targetPort: Int,
    val label: String = "",
)
