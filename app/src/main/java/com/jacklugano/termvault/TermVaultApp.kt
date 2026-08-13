package com.jacklugano.termvault

import android.app.Application
import com.jacklugano.termvault.debug.CrashReporter
import dagger.hilt.android.HiltAndroidApp
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

@HiltAndroidApp
class TermVaultApp : Application() {

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Android include una versione ridotta di BouncyCastle registrata come "BC":
        // va sostituita con quella completa portata da sshj, altrimenti ed25519 e
        // altri algoritmi non sono disponibili.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
