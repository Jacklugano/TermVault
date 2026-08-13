package com.jacklugano.termvault.ssh

import com.jacklugano.termvault.data.db.KnownHostDao
import com.jacklugano.termvault.data.db.KnownHostEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Base64

private class FakeKnownHostDao : KnownHostDao {
    val entries = mutableListOf<KnownHostEntity>()
    private var nextId = 1L

    override fun observeAll(): Flow<List<KnownHostEntity>> = MutableStateFlow(entries.toList())

    override suspend fun findFor(hostname: String, port: Int): List<KnownHostEntity> =
        entries.filter { it.hostname == hostname && it.port == port }

    override suspend fun upsert(entry: KnownHostEntity): Long {
        val id = if (entry.id == 0L) nextId++ else entry.id
        entries.removeAll { it.id == id }
        entries.add(entry.copy(id = id))
        return id
    }

    override suspend fun delete(entry: KnownHostEntity) {
        entries.removeAll { it.id == entry.id }
    }

    override suspend fun deleteAllFor(hostname: String, port: Int) {
        entries.removeAll { it.hostname == hostname && it.port == port }
    }
}

class HostKeyVerifierTest {

    private fun rsaKey(): PublicKey =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public

    @Test
    fun `prima connessione - chiave sconosciuta solleva UnknownHostKeyException`() {
        val dao = FakeKnownHostDao()
        val verifier = DatabaseHostKeyVerifier(dao)
        val key = rsaKey()

        val e = assertThrows(UnknownHostKeyException::class.java) {
            verifier.verify("server.example", 22, key)
        }
        assertEquals("server.example", e.info.hostname)
        assertEquals(22, e.info.port)
        assertTrue(e.info.fingerprint.startsWith("SHA256:"))
    }

    @Test
    fun `chiave salvata identica - verify ritorna true`() {
        val dao = FakeKnownHostDao()
        val verifier = DatabaseHostKeyVerifier(dao)
        val key = rsaKey()

        val info = assertThrows(UnknownHostKeyException::class.java) {
            verifier.verify("host", 22, key)
        }.info
        dao.entries.add(info.toEntity().copy(id = 1))

        assertTrue(verifier.verify("host", 22, key))
    }

    @Test
    fun `stessa porta e tipo ma chiave diversa - HostKeyMismatchException con vecchio fingerprint`() {
        val dao = FakeKnownHostDao()
        val verifier = DatabaseHostKeyVerifier(dao)
        val storedKey = rsaKey()
        val presentedKey = rsaKey()

        val stored = assertThrows(UnknownHostKeyException::class.java) {
            verifier.verify("host", 22, storedKey)
        }.info
        dao.entries.add(stored.toEntity().copy(id = 1))

        val e = assertThrows(HostKeyMismatchException::class.java) {
            verifier.verify("host", 22, presentedKey)
        }
        assertEquals(stored.fingerprint, e.storedFingerprint)
        assertTrue(e.info.fingerprint != stored.fingerprint)
    }

    @Test
    fun `porte diverse sono identita' separate`() {
        val dao = FakeKnownHostDao()
        val verifier = DatabaseHostKeyVerifier(dao)
        val key = rsaKey()

        val info = assertThrows(UnknownHostKeyException::class.java) {
            verifier.verify("host", 22, key)
        }.info
        dao.entries.add(info.toEntity().copy(id = 1))

        // Stessa chiave ma porta diversa: non è nota.
        assertThrows(UnknownHostKeyException::class.java) {
            verifier.verify("host", 2222, key)
        }
    }

    @Test
    fun `findExistingAlgorithms riporta i tipi di chiave salvati`() {
        val dao = FakeKnownHostDao()
        val verifier = DatabaseHostKeyVerifier(dao)
        val key = rsaKey()
        val info = assertThrows(UnknownHostKeyException::class.java) {
            verifier.verify("host", 22, key)
        }.info
        dao.entries.add(info.toEntity().copy(id = 1))

        assertEquals(listOf(info.keyType), verifier.findExistingAlgorithms("host", 22))
    }

    @Test
    fun `fingerprint SHA256 compatibile con ssh-keygen -lf`() {
        // Chiave pubblica generata con ssh-keygen -t ed25519; fingerprint atteso
        // ricavato con: ssh-keygen -lf test_ed25519.pub
        val blobBase64 = "AAAAC3NzaC1lZDI1NTE5AAAAIHz+w3sA4R+sUOA9f0WRiw1Rd6enrL7EQoxqe7rebv9j"
        val expected = "SHA256:O2/f/Jv24OhieP3R8mdwnFtTg90mcKXx2DGI+HddoE4"

        val blob = Base64.getDecoder().decode(blobBase64)
        assertEquals(expected, HostKeys.fingerprintSha256OfBlob(blob))
    }
}
