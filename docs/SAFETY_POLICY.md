# SAFETY_POLICY.md — Transaction State Machine & Unknown-Result Handling

**Status:** Normative. Binding on all code.
**Scope:** every operation that can change state — Level 3 active tests and Level 4 coding
(simulator only), plus any future operation that emits more than a read request.

This policy exists because the dangerous failure mode in vehicle diagnostics is **not** a crash —
it is *a plausible-looking success that was never confirmed*. The machine below is designed so
that the system can always answer: **did the vehicle change, yes / no / unknown?** — and never
guesses "no".

---

## 1. Fundamental Invariants

| # | Invariant |
|---|---|
| I1 | **Deny by default.** Any missing, unparsed, or unverified precondition ⇒ the transaction does not leave `CREATED`. |
| I2 | **No bytes before confirmation.** Nothing is emitted on the transport until `USER_CONFIRMED` is reached in order. |
| I3 | **Ordering is strict.** States may not be skipped, merged, or re-entered. `TRANSMITTING` is reachable only through the full chain. |
| I4 | **Pre-transmission failure is free.** If the transaction ends before `TRANSMITTING`, the vehicle is untouched, and that fact may be reported as a definite "no change". |
| I5 | **Post-first-byte certainty is lost.** Once any byte has been written to the transport, the system may no longer claim "no change" on failure. |
| I6 | **Unknown is a legal outcome.** `RESULT_UNKNOWN` is a first-class terminal state, not an error path to be retried away. |
| I7 | **No silent retry.** Automatic retransmission after `TRANSMITTING` began is forbidden. |
| I8 | **One at a time.** At most one transaction may be in a non-terminal state; no nesting, no queues, no parallel ECUs. |
| I9 | **Confirmation is per-transaction.** Approvals do not carry over, and expire (§3.4). |
| I10 | **No LLM-composed payloads.** Command bytes are statically defined and typed. The state machine transports them; it never generates them. |

---

## 2. States

```
                      ┌─────────┐
                      │ CREATED │
                      └────┬────┘
                           │ preconditions verified
                           ▼
              ┌────────────────────────┐
              │ PRECONDITIONS_CHECKED  │
              └───────────┬────────────┘
                          │ backup captured + read-back verified
                          ▼
                   ┌──────────────┐
                   │ BACKUP_TAKEN │
                   └──────┬───────┘
                          │ explicit user approval (fresh, in-window)
                          ▼
                  ┌────────────────┐
                  │ USER_CONFIRMED │
                  └───────┬────────┘
                          │ first byte written to transport
                          ▼
                   ┌──────────────┐
                   │ TRANSMITTING │
                   └──────┬───────┘
                          │ positive response, transport healthy
                          ▼
                    ┌───────────┐
                    │ VERIFYING │
                    └─────┬─────┘
                          │ read-back matches intent
                          ▼
                   ┌───────────┐
                   │ COMPLETED │
                   └───────────┘
```

| State | Meaning | Entry guard (all must hold, else deny) |
|---|---|---|
| `CREATED` | Intent recorded, nothing inspected, nothing emitted. | Operation type is on the level allow-list; user target selected. |
| `PRECONDITIONS_CHECKED` | Every precondition resolved **positively** from verified data. | Vehicle matrix entries for ECU / protocol / address / header / init sequence are `VERIFIED`; required DIDs and routines allow-listed; adapter link established and stable; battery voltage and ignition state within the operation's declared window (or explicitly not required by the operation definition); simulator confirmed present for L3/L4. Any unknown ⇒ stay in `CREATED`, report the *specific* unmet precondition. |
| `BACKUP_TAKEN` | A restorable snapshot of the target's current state exists and has been **verified**. | Backup captured, persisted atomically, read back, and checksum-matched. A backup that was not read back is not a backup. |
| `USER_CONFIRMED` | A human approved this exact operation. | Explicit, deliberate confirmation showing target ECU, operation, exact payload (hex), and backup reference; not a default-focused button; not a "don't ask again". |
| `TRANSMITTING` | Bytes are on the wire. | Guard is only reached from `USER_CONFIRMED`; transport healthy at the instant of first write. |
| `VERIFYING` | Reading back the affected state to prove the outcome. | Positive/expected response received for the transmitted request; transport still healthy. |
| `COMPLETED` | Read-back matches intended result. | Verification predicate passes and its evidence is stored. |

---

## 3. Transition Rules

### 3.1 CREATED → PRECONDITIONS_CHECKED

Only on a fully positive evaluation. The precondition checker returns
`SATISFIED` or `UNSATISFIED(reason)`; there is no `PROBABLY` and no `PARTIAL`. On
`UNSATISFIED`, the transaction terminates in `ABORTED_PRE_TRANSMISSION` and the reason string is
surfaced verbatim. Unresolvable conditions are never coerced to satisfied.

### 3.2 PRECONDITIONS_CHECKED → BACKUP_TAKEN

The backup must describe the *current* state of the *exact* target that is about to be written.
Requirement: capture → persist → read back → compare. Failure at any step ⇒
`ABORTED_PRE_TRANSMISSION`. A stale backup from a previous session is invalid (§3.4).

### 3.3 BACKUP_TAKEN → USER_CONFIRMED

Requires an affirmative human action bound to a **confirmation token** that carries:

- operation identifier and target ECU / DID / routine
- the exact byte payload to be sent
- backup identifier and checksum
- a nonce and an expiry timestamp

The token is single-use and is invalidated by: expiry, any change to payload/target, any change to
preconditions, any loss of link, app backgrounding, or a new transaction.

### 3.4 Confirmation window

- Approval must be **fresh**: issued after `BACKUP_TAKEN`.
- Approval expires (default 60 s; per-operation override allowed but must be statically defined).
- An expired or invalidated token returns the transaction to `BACKUP_TAKEN` — **not** to
  `USER_CONFIRMED`. Re-approval is required with a newly issued token.
- Preconditions are **re-evaluated** immediately before `TRANSMITTING`; a regression
  (e.g. link drop, voltage change) returns to `PRECONDITIONS_CHECKED` and invalidates the backup
  if the state changed.

### 3.5 USER_CONFIRMED → TRANSMITTING

The only place a write may physically occur. The transition is declared at the moment the first
byte is handed to the transport, and from that instant I5 applies. Write attempts outside this
transition are a defect and must fail the build/review.

### 3.6 TRANSMITTING → VERIFYING

Allowed only on a positive, well-formed, in-scope response for the transmitted request.

- `MALFORMED_DATA` ⇒ `VERIFY_FAILED` (never `COMPLETED`).
- Negative response (`0x7F` with NRC) after full transmission ⇒ `TRANSMISSION_REJECTED`, carrying
  the NRC. The vehicle *may* have partially accepted, so this is a post-transmission terminal
  state and does **not** assert "no change".
- Silence, timeout, `NO_RESPONSE`, link loss, adapter reboot, RFCOMM drop, app kill, or partial
  write ⇒ **`RESULT_UNKNOWN`** (§4).

### 3.7 VERIFYING → COMPLETED

Verification is an independent **read** of the affected state (a read-back, not an echo of the
write response). `COMPLETED` requires the read-back to equal the intended value under a
statically defined comparison — byte-exact for coding, within declared tolerance only where an
operation definition explicitly allows it. Mismatch ⇒ `VERIFY_FAILED`.

---

## 4. Communication Loss During Transmission ⇒ `RESULT_UNKNOWN`

**Rule (mandatory):** if communication is lost, corrupted, or interrupted at any point after the
first byte of a transmission has been emitted, the transaction **must** terminate in
`RESULT_UNKNOWN`. No other outcome is permitted.

`RESULT_UNKNOWN` means: *a change to the vehicle may or may not have occurred; the system is
unable to determine which.* It is the honest answer and the only safe one.

### 4.1 Triggers

Any of the following, at any time in `TRANSMITTING` or after, forces `RESULT_UNKNOWN`:

- transport read timeout, `NO_RESPONSE`, or silence past the declared response window
- RFCOMM/Bluetooth disconnect, adapter power loss, adapter reboot or reset
- partial/multi-frame response truncated mid-message
- checksum/format failure such that the response cannot be trusted as the reply to *this* request
- app process death, OS kill, device reboot, or user navigation away mid-transaction
- an unexpected or unsolicited frame that cannot be reconciled with the request

### 4.2 Mandatory handling

When `RESULT_UNKNOWN` is entered:

1. **Do not** assume success. Never auto-mark `COMPLETED`.
2. **Do not** assume failure. Never auto-mark `FAILED` or "unchanged".
3. **Do not** automatically retry, resend, or "try again" the operation (I7). Retrying a
   half-applied write is how a module ends up in an inconsistent state.
4. **Do not** automatically roll back from a backup. A rollback is itself a write and is itself
   unverified until it completes; blind rollback compounds uncertainty.
5. **Persist** an immutable transaction record: transaction id, operation, target, payload hex,
   last confirmed state reached, backup id/checksum, timestamp, and the triggering condition.
6. **Surface** it as a distinct, unmistakable UI state — `RESULT_UNKNOWN` is its own presentation,
   never rendered with the success or failure styling, never as a red error alone and never as
   green.
7. **Instruct** the user with plain, honest copy: the state of the module is not known, do not
   assume the change applied, re-read status before deciding anything, and escalate to a
   qualified technician if the vehicle misbehaves.
8. **Block** further write transactions against that target until an explicit operator
   acknowledgement plus a successful **read-only re-scan** establishes the module's actual state.
   Only the new read result may be used to decide next steps.
9. **Log** at diagnostic severity without including any secret material; the record must survive
   process restart.

### 4.3 What `RESULT_UNKNOWN` is not

`RESULT_UNKNOWN` is a **transaction outcome**. It is distinct from the data-model values
mandated by AGENTS.md Rule 4 (`TIMEOUT`, `NO_RESPONSE`, `UNSUPPORTED`, `MALFORMED_DATA`, and a
genuine numeric `0`), which describe *a reading*. A read that times out yields the data value
`TIMEOUT`; a write interrupted after transmission yields the transaction outcome
`RESULT_UNKNOWN`. The two must never be collapsed into one enum, one string, or one UI path, and
neither may ever be rendered as `0`.

### 4.4 Ordering of truth

For any operation, the system's reportable knowledge is ranked:

1. **Verified read-back** (strongest — an observed post-state).
2. **Pre-transmission abort** (vehicle provably untouched — I4).
3. **`RESULT_UNKNOWN`** (indeterminate).
4. Never: "probably worked", "assumed applied", "reverted", "likely fine".

---

## 5. Terminal States

| Terminal state | Vehicle state assertion allowed | Notes |
|---|---|---|
| `ABORTED_PRE_TRANSMISSION` | "No change — transmission never began." | Reachable from any pre-`TRANSMITTING` state. Safe and fully reportable. |
| `TRANSMISSION_REJECTED` | "Requested operation was refused by the module (NRC …); partial acceptance cannot be excluded." | Post-transmission. Requires re-read before any follow-up. |
| `VERIFY_FAILED` | "Operation did not achieve the verified result; current state is the read-back value." | Read-back evidence must be retained. |
| `RESULT_UNKNOWN` | **No assertion permitted.** | §4. Requires re-scan + acknowledgement before further writes. |
| `COMPLETED` | "Read-back confirms the intended state." | Requires verification evidence stored. |
| `ROLLED_BACK` | "Restore was transmitted and verified by read-back." | Only reachable via an explicit, confirmed restore transaction that itself runs the full state machine. |

Every terminal state is absorbing: a transaction never resumes. A new attempt is a **new
transaction** starting at `CREATED` with a fresh precondition check, a fresh backup, and a fresh
confirmation.

---

## 6. Enforcement Requirements (implementation obligations)

Because this policy must be unbypassable, the following are required when implementation occurs:

1. The state machine is a single pure-Kotlin domain component; states are a sealed type, not
   strings or ints; illegal transitions are impossible to express or throw at runtime.
2. Emission of bytes is possible only inside the `USER_CONFIRMED → TRANSMITTING` transition.
   There is no other caller of the write API.
3. The adapter/transport layer exposes **no** general-purpose "send raw bytes" API. Level 3/4
   commands are typed values.
4. The physical transport cannot be constructed for Level 3/4 use cases (see
   [PRODUCT_SCOPE.md](PRODUCT_SCOPE.md) §4.1).
5. Every state transition, precondition result, and terminal outcome is persisted and auditable;
   every parser and decoder is tested against raw HEX transcript fixtures, including
   interrupted-transmission fixtures that must yield `RESULT_UNKNOWN`.
6. Debug/raw console, if present, is behind `BuildConfig.DEBUG`, disabled by default, and must not
   be able to create a transaction that skips states.

---

## 7. Traceability

| AGENTS.md rule | Enforced here by |
|---|---|
| 1 — Fail-closed architecture | I1; §2 entry guards; §3.1 |
| 2 — No physical writes | §3.5; §6.2–6.4; PRODUCT_SCOPE §7 invariant |
| 3 — Raw console forbidden in release | §6.6; PRODUCT_SCOPE §6.1 |
| 4 — Honest data modeling | §4.3 |
| 5 — No invented packets | I10; §6.3 |
