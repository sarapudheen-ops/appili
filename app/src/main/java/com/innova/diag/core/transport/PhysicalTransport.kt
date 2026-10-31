package com.innova.diag.core.transport

/**
 * Marks a [ByteTransport] that talks to **real vehicle hardware**.
 *
 * The domain has to be able to say "this link reaches a car, so an active test must not run on it"
 * without importing `android.*` or `platform.bluetooth` — that would invert the layering
 * (`docs/ASSUMPTIONS.md` A27) and make `domain.active` untestable on a plain JVM. A marker interface
 * is the smallest thing that answers the question at runtime and cannot be spoofed by a name check.
 *
 * `BluetoothClassicTransport` implements it. In-memory transports — the demo transport, the bench
 * [com.innova.diag.domain.active.MockEcuSimulator] — deliberately do not: they move bytes to a mock,
 * not to a radiator fan relay.
 *
 * This is a **capability declaration, not a permission**. Being marked physical does not make a
 * transport forbidden; it makes the layers above refuse to send it anything that could actuate
 * hardware (`AGENTS.md` Rule 2, `docs/PRODUCT_SCOPE.md` §4.1).
 */
interface PhysicalTransport : ByteTransport
