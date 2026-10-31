package com.innova.diag.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug console's guard: an AT console, not a command prompt.
 *
 * If any of these fail, a release-only rule ("no arbitrary command input") has quietly become a
 * debug-only foot-gun that can write to a vehicle.
 */
class AtCommandGuardTest {

    @Test
    fun `adapter info commands are allowed`() {
        for (command in listOf("ATI", "ATDP", "atrv", " ATZ ", "ATE0")) {
            assertEquals("$command should be allowed", AtCommandGuard.Verdict.Allowed, AtCommandGuard.check(command))
        }
    }

    @Test
    fun `vehicle modes are refused so nothing can be written from a text field`() {
        for (command in listOf("04", "010C", "03", "2E F190 00", "3101FF00", "22F190")) {
            val verdict = AtCommandGuard.check(command)

            assertTrue("$command must be refused, got $verdict", verdict is AtCommandGuard.Verdict.Rejected)
        }
    }

    @Test
    fun `a mode 04 clear is refused`() {
        val verdict = AtCommandGuard.check("04")

        val rejected = verdict as? AtCommandGuard.Verdict.Rejected
        assertTrue("expected a rejection", rejected != null)
        assertTrue(
            "the reason should say only AT commands are allowed, got ${rejected?.reason}",
            rejected!!.reason.contains("AT"),
        )
    }

    @Test
    fun `blank input is refused`() {
        assertTrue(AtCommandGuard.check("") is AtCommandGuard.Verdict.Rejected)
        assertTrue(AtCommandGuard.check("   ") is AtCommandGuard.Verdict.Rejected)
    }

    @Test
    fun `a second command on the same line is refused`() {
        for (command in listOf("ATI\rATE0", "ATI\nATE0", "ATI\r\nATE0")) {
            assertTrue(
                "$command injects a second command",
                AtCommandGuard.check(command) is AtCommandGuard.Verdict.Rejected,
            )
        }
    }

    @Test
    fun `a bare AT prefix is refused`() {
        assertTrue(AtCommandGuard.check("AT") is AtCommandGuard.Verdict.Rejected)
    }
}
