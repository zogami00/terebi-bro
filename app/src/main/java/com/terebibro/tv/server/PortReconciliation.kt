package com.terebibro.tv.server

/**
 * Pure rule keeping the persisted controller port equal to the port the server
 * actually bound.
 *
 * A fallback bind (configured port busy, restore of the previous listener) can
 * leave the listener on a port other than [com.terebibro.tv.config.ConfigStore.controllerPort].
 * Because the Host allowlist, the mDNS SRV record and the URL shown on the TV
 * all read that persisted value, a mismatch makes the server answer `403 host`
 * to the address it advertises. The bound port is the single source of truth;
 * this decides when it must be written back.
 */
object PortReconciliation {

    /**
     * @return the port to persist when [boundPort] is a real port that differs
     * from [configuredPort]; null when no write-back is needed.
     */
    fun sync(configuredPort: Int, boundPort: Int): Int? =
        if (boundPort in 1..65535 && boundPort != configuredPort) boundPort else null
}
