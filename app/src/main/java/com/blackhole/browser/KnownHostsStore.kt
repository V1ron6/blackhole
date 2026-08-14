package com.blackhole.browser

import android.content.Context

/**
 * Stores pinned SSH host key fingerprints locally, keyed by host:port.
 *
 * Fingerprints here are SHA-256 over PublicKey.getEncoded() - consistent
 * across reconnects to the same host for pinning purposes, but NOT the same
 * format `ssh-keygen -l` or a standard known_hosts file would show (those
 * use the SSH wire encoding of the key, not Java's X.509-style encoded()
 * form). This is an internal pinning scheme for this app only, not
 * something you can cross-check against a server's published fingerprint
 * without converting formats first.
 */
class KnownHostsStore(context: Context) {

    private val prefs = context.getSharedPreferences("blackhole_known_hosts", Context.MODE_PRIVATE)

    fun getFingerprint(host: String, port: Int): String? = prefs.getString(key(host, port), null)

    fun saveFingerprint(host: String, port: Int, fingerprint: String) {
        prefs.edit().putString(key(host, port), fingerprint).apply()
    }

    fun forget(host: String, port: Int) {
        prefs.edit().remove(key(host, port)).apply()
    }

    private fun key(host: String, port: Int) = "$host:$port"
}
