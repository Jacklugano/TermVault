package com.jacklugano.termvault.kp2a

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Kp2aFieldsTest {

    @Test
    fun `password login quando manca la chiave privata`() {
        val creds = Kp2aFields.toCredentials(
            mapOf(
                "UserName" to "giacomo",
                "Password" to "s3greta",
            )
        )
        assertEquals("giacomo", creds.username)
        assertTrue(creds.hasPassword)
        assertFalse(creds.hasKey)
        assertArrayEquals("s3greta".toCharArray(), creds.password)
        assertNull(creds.passphrase)
    }

    @Test
    fun `password diventa passphrase quando c'e' la chiave privata`() {
        val creds = Kp2aFields.toCredentials(
            mapOf(
                "UserName" to "root",
                "Password" to "passphrase-chiave",
                "SSH-PrivateKey" to "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----",
            )
        )
        assertTrue(creds.hasKey)
        assertFalse(creds.hasPassword)
        assertNotNull(creds.privateKey)
        assertArrayEquals("passphrase-chiave".toCharArray(), creds.passphrase)
    }

    @Test
    fun `normalizza il prefisso STRING_ dei campi custom`() {
        val creds = Kp2aFields.toCredentials(
            mapOf(
                "UserName" to "admin",
                "STRING_SSH-Port" to "2222",
            )
        )
        assertEquals(2222, creds.portOverride)
    }

    @Test
    fun `porta invalida o fuori range viene ignorata`() {
        assertNull(Kp2aFields.toCredentials(mapOf("SSH-Port" to "abc")).portOverride)
        assertNull(Kp2aFields.toCredentials(mapOf("SSH-Port" to "70000")).portOverride)
        assertNull(Kp2aFields.toCredentials(mapOf("SSH-Port" to "0")).portOverride)
        assertEquals(22, Kp2aFields.toCredentials(mapOf("SSH-Port" to "22")).portOverride)
    }

    @Test
    fun `la chiave privata con CRLF viene normalizzata a LF con newline finale`() {
        val creds = Kp2aFields.toCredentials(
            mapOf("SSH-PrivateKey" to "-----BEGIN X-----\r\nAAAA\r\n-----END X-----\r\n")
        )
        val key = String(creds.privateKey!!)
        assertFalse(key.contains('\r'))
        assertTrue(key.endsWith("-----END X-----\n"))
    }

    @Test
    fun `campo chiave vuoto non attiva la modalita' chiave`() {
        val creds = Kp2aFields.toCredentials(
            mapOf("Password" to "pw", "SSH-PrivateKey" to "  ")
        )
        assertFalse(creds.hasKey)
        assertTrue(creds.hasPassword)
    }

    @Test
    fun `wipe azzera i segreti`() {
        val creds = Kp2aFields.toCredentials(
            mapOf("Password" to "pw", "UserName" to "u")
        )
        creds.wipe()
        assertNull(creds.password)
        assertNull(creds.privateKey)
        assertNull(creds.passphrase)
    }

    @Test
    fun `campi per la nuova entry KP2A includono i campi protetti`() {
        val fields = Kp2aFields.addEntryFields("web01", "ssh://web01", "deploy", 2222)
        assertEquals("web01", fields["Title"])
        assertEquals("ssh://web01", fields["URL"])
        assertEquals("deploy", fields["UserName"])
        assertEquals("2222", fields["SSH-Port"])
        assertTrue(fields.containsKey("SSH-PrivateKey"))
        val protected_ = Kp2aFields.protectedFields()
        assertTrue(protected_.contains("SSH-PrivateKey"))
        assertTrue(protected_.contains("Password"))
    }

    @Test
    fun `porta 22 non viene aggiunta alla nuova entry`() {
        val fields = Kp2aFields.addEntryFields("h", "ssh://h", "u", 22)
        assertFalse(fields.containsKey("SSH-Port"))
    }
}
