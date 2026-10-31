# PRODUCT SCOPE — Universal Diagnostic Platform

## Vision
A universal Android diagnostic application communicating via the
Vgate vLinker MC+, supporting all major global automotive brands for
generic OBD-II diagnostics and, progressively, validated OEM-specific
enhanced diagnostics.

## Supported Brands (Aspirational — Each Requires Evidence)
Toyota / Lexus, Honda / Acura, Ford / Lincoln, GM / Chevrolet,
Volkswagen / Audi / Skoda / SEAT, BMW / MINI, Mercedes-Benz,
Hyundai / Kia, Nissan / Infiniti, Mazda, Subaru, Mitsubishi,
Renault, Peugeot / Citroën, Fiat / Chrysler / Jeep / Dodge.

## Product Levels (Global — Not Brand-Specific)
| Level | Description | Scope |
|-------|-------------|-------|
| L0 | Adapter Connection & Identity | Universal |
| L1 | Generic OBD-II Read-Only (SAE J1979) | Universal — All modern vehicles |
| L2 | Enhanced OEM Read-Only | Per-brand, per-vehicle-pack |
| L3 | Validated Active Tests (Simulator → Bench → Vehicle) | Per-brand, per-operation |
| L4 | Validated Configuration Coding (Simulator → Bench → Vehicle) | Per-brand, per-operation |
| L5 | Firmware Programming | PERMANENTLY OUT OF SCOPE |

## Permanently Excluded
- ECU Firmware Flashing
- Immobilizer / Key Programming
- Odometer Modification
- VIN Rewriting
- Emissions Defeat
- Airbag Crash Data Manipulation
- Security Access Bypass / Brute Force

## Development Priority
1. L0 + L1 universal (works on every car with OBD-II)
2. L2 per brand, starting with Toyota family (Innova as pilot)
3. L2 per additional brand, one at a time
4. L3/L4 only after brand-specific evidence and bench testing

## Pilot Vehicles (Validation, Not Product Scope)
| Pilot | Brand | Why |
|-------|-------|-----|
| 2007 Toyota Innova (GCC) | Toyota | Available physical vehicle for initial validation |
| [Add vehicles as available] | [Brand] | [Reason] |
