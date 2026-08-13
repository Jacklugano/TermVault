package com.jacklugano.termvault.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import dagger.hilt.android.qualifiers.ApplicationContext
import de.blinkt.openvpn.api.IOpenVPNAPIService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Esito del tentativo di avvio profilo. */
sealed interface VpnStartOutcome {
    /** Profilo avviato in background, nessuna UI aperta. */
    data object Started : VpnStartOutcome

    /** L'API remota non è disponibile (app assente o bind fallito). */
    data object NotAvailable : VpnStartOutcome

    data class Error(val message: String) : VpnStartOutcome
}

/**
 * Controlla "OpenVPN for Android" (de.blinkt.openvpn) tramite la sua API AIDL
 * remota: il profilo parte in background senza portare OpenVPN in primo piano.
 *
 * I soli passaggi che possono mostrare UI sono i consensi una-tantum
 * (autorizzazione dell'app + dialogo VPN di sistema), consegnati al chiamante
 * tramite [launchForResult] così restano nel task di TermVault.
 */
@Singleton
class OpenVpnController @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private var api: IOpenVPNAPIService? = null

    companion object {
        private const val OPENVPN_PACKAGE = "de.blinkt.openvpn"
        private const val BIND_TIMEOUT_MS = 5_000L
    }

    suspend fun startProfile(
        profileName: String,
        launchForResult: suspend (Intent) -> Boolean,
    ): VpnStartOutcome {
        val service = try {
            withTimeout(BIND_TIMEOUT_MS) { bind() }
        } catch (_: Exception) {
            return VpnStartOutcome.NotAvailable
        }

        return withContext(Dispatchers.IO) {
            try {
                // 1) Permesso dell'API esterna (una tantum, dialog di OpenVPN).
                val permissionIntent = service.prepare(context.packageName)
                if (permissionIntent != null) {
                    val granted = withContext(Dispatchers.Main) { launchForResult(permissionIntent) }
                    if (!granted) {
                        return@withContext VpnStartOutcome.Error(
                            "Permesso negato: autorizza TermVault in OpenVPN for Android"
                        )
                    }
                }

                // 2) Consenso VPN di sistema (una tantum, dialogo Android).
                val vpnConsentIntent = service.prepareVPNService()
                if (vpnConsentIntent != null) {
                    val granted = withContext(Dispatchers.Main) { launchForResult(vpnConsentIntent) }
                    if (!granted) {
                        return@withContext VpnStartOutcome.Error(
                            "Consenso VPN di sistema negato"
                        )
                    }
                }

                // 3) Trova il profilo per nome e avvialo (in background).
                val profiles = service.profiles
                val match = profiles.firstOrNull {
                    it.mName.trim().equals(profileName.trim(), ignoreCase = true)
                } ?: return@withContext VpnStartOutcome.Error(
                    "Profilo \"$profileName\" non trovato in OpenVPN for Android. " +
                        "Disponibili: " + (profiles.joinToString { it.mName }.ifEmpty { "nessuno" })
                )

                service.startProfile(match.mUUID)
                VpnStartOutcome.Started
            } catch (e: Exception) {
                VpnStartOutcome.Error("Errore API OpenVPN: ${e.message}")
            }
        }
    }

    private suspend fun bind(): IOpenVPNAPIService {
        api?.let { existing ->
            if (existing.asBinder().isBinderAlive) return existing
            api = null
        }
        return suspendCancellableCoroutine { cont ->
            val connection = object : ServiceConnection {
                private var resumed = false

                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val service = IOpenVPNAPIService.Stub.asInterface(binder)
                    api = service
                    if (!resumed) {
                        resumed = true
                        cont.resume(service)
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    api = null
                }
            }
            val intent = Intent("de.blinkt.openvpn.api.IOpenVPNAPIService")
                .setPackage(OPENVPN_PACKAGE)
            val bound = try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (_: SecurityException) {
                false
            }
            if (!bound) {
                cont.resumeWithException(
                    IllegalStateException("OpenVPN for Android non disponibile")
                )
            }
        }
    }
}
