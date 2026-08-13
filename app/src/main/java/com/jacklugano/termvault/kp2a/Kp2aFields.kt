package com.jacklugano.termvault.kp2a

import com.jacklugano.termvault.ssh.SshCredentials

/**
 * Mappatura dei campi di un'entry Keepass2Android verso le credenziali SSH.
 *
 * Campi standard KeePass: UserName, Password.
 * Campi custom (stringhe protette multilinea consigliate):
 *  - "SSH-PrivateKey": chiave privata in formato PEM/OpenSSH. L'SDK KP2A scambia
 *    solo stringhe, quindi la chiave DEVE stare in un campo custom, non in un allegato.
 *  - "SSH-Port": porta opzionale che sovrascrive quella dell'host.
 *
 * Se è presente una chiave privata, il campo Password è interpretato come
 * passphrase della chiave; altrimenti come password di login.
 */
object Kp2aFields {
    const val USERNAME = "UserName"
    const val PASSWORD = "Password"
    const val PRIVATE_KEY = "SSH-PrivateKey"
    const val PORT = "SSH-Port"
    const val TITLE = "Title"
    const val URL = "URL"

    /** Prefisso che KP2A usa per i campi custom in alcune risposte. */
    private const val STRING_PREFIX = "STRING_"

    /** Normalizza le chiavi rimuovendo l'eventuale prefisso "STRING_" dei campi custom. */
    fun normalize(fields: Map<String, String>): Map<String, String> =
        fields.mapKeys { (key, _) ->
            if (key.startsWith(STRING_PREFIX)) key.removePrefix(STRING_PREFIX) else key
        }

    /**
     * Converte i campi dell'entry in [SshCredentials]. I valori sensibili sono
     * copiati in char[] (azzerabili); il chiamante NON deve conservare la mappa.
     */
    fun toCredentials(rawFields: Map<String, String>): SshCredentials {
        val fields = normalize(rawFields)
        val username = fields[USERNAME].orEmpty().trim()
        val secret = fields[PASSWORD]?.toCharArray()
        val privateKey = fields[PRIVATE_KEY]
            ?.takeIf { it.isNotBlank() }
            ?.normalizeLineEndings()
            ?.toCharArray()
        val port = fields[PORT]?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

        return if (privateKey != null) {
            SshCredentials(
                username = username,
                privateKey = privateKey,
                passphrase = secret,
                portOverride = port,
            )
        } else {
            SshCredentials(
                username = username,
                password = secret,
                portOverride = port,
            )
        }
    }

    /**
     * Campi per creare una nuova entry in KP2A ([Kp2aControl.getAddEntryIntent]).
     * URL = query dell'host, così la ricerca alla connessione la trova subito.
     * SSH-PrivateKey è incluso vuoto e va marcato protetto (vedi [protectedFields]).
     */
    fun addEntryFields(
        title: String,
        query: String,
        username: String,
        port: Int?,
    ): HashMap<String, String> = hashMapOf(
        TITLE to title,
        URL to query,
        USERNAME to username,
        PASSWORD to "",
        PRIVATE_KEY to "",
    ).also { map ->
        if (port != null && port != 22) map[PORT] = port.toString()
    }

    /** Campi da marcare protetti nell'entry creata. */
    fun protectedFields(): ArrayList<String> = arrayListOf(PASSWORD, PRIVATE_KEY)

    /**
     * I campi multilinea di KP2A possono arrivare con CRLF; i parser PEM
     * vogliono LF. Garantisce anche il newline finale richiesto dal formato
     * OpenSSH.
     */
    private fun String.normalizeLineEndings(): String {
        val lf = replace("\r\n", "\n").replace('\r', '\n').trim()
        return lf + "\n"
    }
}
