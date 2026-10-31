# MULTI-BRAND SUPPORT MATRIX

## Tier Classification
- **T1 (Full Enhanced):** Verified vehicle packs exist for target
  vehicle families. Read + Active + Coding (where validated).
- **T2 (Partial Enhanced):** Some OEM read-only packs verified.
  No active/coding yet.
- **T3 (Generic Only):** SAE J1979 generic OBD-II works. No OEM
  profiles validated yet.
- **T4 (Unverified):** Brand listed but no physical testing performed.

## Current Status
| Brand | Tier | Generic OBD | OEM Read | Active | Coding | Notes |
|-------|------|-------------|----------|--------|--------|-------|
| Toyota | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | Pilot vehicle: 2007 Innova GCC |
| Honda | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |
| Ford | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |
| GM | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |
| VW/Audi | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |
| BMW | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |
| Mercedes | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |
| Hyundai/Kia | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |
| Nissan | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |
| Mazda | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |
| Subaru | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |
| Mitsubishi | T4 | Universal (L1) | UNKNOWN | DISABLED | DISABLED | |

## Universal Coverage (L1 — All Brands)
SAE J1979 Generic OBD-II is supported for any vehicle that:
- Uses an OBD-II compliant diagnostic connector (SAE J1962)
- Communicates on one of the standard protocols (ISO 15765-4,
  ISO 14230-4, ISO 9141-2, SAE J1850, SAE J2610)
- The vLinker MC+ can establish a link on that protocol

## Promotion Rules
A brand may be promoted to a higher tier ONLY when:
1. Exact vehicle/engine/ECU variant is recorded physically
2. Communication logs are captured and sanitized
3. Vehicle pack definitions have source references
4. Unit tests validate every decoder against real transcript fixtures
5. Bench or vehicle testing confirms expected behavior
