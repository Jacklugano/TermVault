package com.jacklugano.termvault.kp2a

import keepass2android.pluginsdk.PluginAccessBroadcastReceiver
import keepass2android.pluginsdk.Strings

/**
 * Receiver richiesto dal protocollo plugin di Keepass2Android: KP2A lo
 * contatta per negoziare gli scope e consegnare/revocare l'access token
 * (gestito da AccessManager dell'SDK in SharedPreferences dedicate).
 */
class Kp2aAccessReceiver : PluginAccessBroadcastReceiver() {

    override fun getScopes(): ArrayList<String> = arrayListOf(
        Strings.SCOPE_QUERY_CREDENTIALS,
        Strings.SCOPE_DATABASE_ACTIONS,
    )
}
