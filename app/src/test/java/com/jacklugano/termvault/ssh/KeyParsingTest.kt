package com.jacklugano.termvault.ssh

import com.jacklugano.termvault.kp2a.Kp2aFields
import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.StringReader

/**
 * Verifica che le chiavi in formato OpenSSH (come arriverebbero dal campo
 * custom "SSH-PrivateKey" di KP2A) siano parsabili da sshj, incluso il caso
 * con passphrase e la normalizzazione CRLF fatta da Kp2aFields.
 *
 * Fixture generate con ssh-keygen -t ed25519 (vedi commenti).
 */
class KeyParsingTest {

    // ssh-keygen -t ed25519 -N "" -C termvault-test
    private val plainKey = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW
        QyNTUxOQAAACB8/sN7AOEfrFDgPX9FkYsNUXenp6y+xEKManu63m7/YwAAAJhV/aNdVf2j
        XQAAAAtzc2gtZWQyNTUxOQAAACB8/sN7AOEfrFDgPX9FkYsNUXenp6y+xEKManu63m7/Yw
        AAAEBm/tS4XsQmRGR/wmTogyS8MluRj63sCc/AWolknaM4+nz+w3sA4R+sUOA9f0WRiw1R
        d6enrL7EQoxqe7rebv9jAAAADnRlcm12YXVsdC10ZXN0AQIDBAUGBw==
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent() + "\n"

    // ssh-keygen -t ed25519 -N "segreta123" -C termvault-test-pp
    private val passphraseKey = """
        -----BEGIN OPENSSH PRIVATE KEY-----
        b3BlbnNzaC1rZXktdjEAAAAACmFlczI1Ni1jdHIAAAAGYmNyeXB0AAAAGAAAABATYcYjf2
        d2L8Ai2Rs6Mx6NAAAAGAAAAAEAAAAzAAAAC3NzaC1lZDI1NTE5AAAAIPfVmoouaUORvbq0
        Dxu3HS/q65FTjdCiCAtXXVjqjkiGAAAAoCvP3dHd7wxHmEo/ds7EGk46V2yWk+tayalI2Y
        30JVUmozfI3EGkN2ulS9EbNe6s+EB9h5a3cqzCwXOFA85wMC5z+uZqjK7Ptaty7Tadr6HB
        9GztsZkrJ0JqcKM11Rsd75TyK8C+Tp1rGOiVXHWYjrH/N5VfuAberSZVSTlWSM//p3sIPz
        gDCGbmga3PwsEAjSSiEgexUBGXya/qF7zunwY=
        -----END OPENSSH PRIVATE KEY-----
    """.trimIndent() + "\n"

    @Test
    fun `parsa una chiave ed25519 senza passphrase`() {
        val provider = OpenSSHKeyV1KeyFile()
        provider.init(StringReader(plainKey))
        assertNotNull(provider.private)
        assertEquals("ssh-ed25519", HostKeys.keyType(provider.public))
        assertEquals(
            "SHA256:O2/f/Jv24OhieP3R8mdwnFtTg90mcKXx2DGI+HddoE4",
            HostKeys.fingerprintSha256(provider.public),
        )
    }

    @Test
    fun `parsa una chiave ed25519 protetta da passphrase`() {
        val provider = OpenSSHKeyV1KeyFile()
        provider.init(
            StringReader(passphraseKey),
            PasswordUtils.createOneOff("segreta123".toCharArray()),
        )
        assertNotNull(provider.private)
        assertEquals(
            "SHA256:4UqKejhBcByJyvRbgCuxsI/DTCnWhTZgp52hKSTgeuE",
            HostKeys.fingerprintSha256(provider.public),
        )
    }

    @Test
    fun `passphrase sbagliata - eccezione`() {
        val provider = OpenSSHKeyV1KeyFile()
        provider.init(
            StringReader(passphraseKey),
            PasswordUtils.createOneOff("sbagliata".toCharArray()),
        )
        assertThrows(IOException::class.java) { provider.private }
    }

    @Test
    fun `la chiave normalizzata da Kp2aFields (CRLF) resta parsabile`() {
        // Simula KP2A che restituisce il campo con CRLF e senza newline finale.
        val crlfKey = plainKey.trim().replace("\n", "\r\n")
        val creds = Kp2aFields.toCredentials(mapOf("SSH-PrivateKey" to crlfKey))

        val provider = OpenSSHKeyV1KeyFile()
        provider.init(StringReader(String(creds.privateKey!!)))
        assertNotNull(provider.private)
        assertEquals(
            "SHA256:O2/f/Jv24OhieP3R8mdwnFtTg90mcKXx2DGI+HddoE4",
            HostKeys.fingerprintSha256(provider.public),
        )
    }
}
