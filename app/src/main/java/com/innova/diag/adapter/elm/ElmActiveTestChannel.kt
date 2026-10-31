package com.innova.diag.adapter.elm

import com.innova.diag.domain.active.ActiveTestChannel
import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.ObdCommandPort

/**
 * Binds the domain's [ActiveTestChannel] to a live [ElmCommandChannel].
 *
 * The mapping is identical to [ElmObdCommandPort]'s — a UDS frame is a command like any other, and an
 * adapter timeout must become `Timeout` rather than a crash — so this delegates instead of repeating
 * it. The separate type exists so an active-test dependency can never be satisfied by something that
 * was reviewed as a read-only OBD path.
 */
class ElmActiveTestChannel(channel: ElmCommandChannel) : ActiveTestChannel {

    private val port: ObdCommandPort = ElmObdCommandPort(channel)

    override suspend fun exchange(frame: String): DiagnosticResult<String> = port.execute(frame)
}
