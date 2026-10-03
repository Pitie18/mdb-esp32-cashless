# mdb-slave-esp32s3-sim7080g — pin mapping & firmware compatibility

Custom PCB variant of `mdb-slave-esp32s3` adding an onboard SIM7080G
(GPS/LTE-M/NB-IoT) modem — see `kicad/README.md` for the board photo.

## Confirmed pin mapping (traced from `mdb-slave-esp32s3-sim7080g.kicad_pcb` netlist)

| GPIO | Net | Function |
|---|---|---|
| 4 | `/io4` | MDB bus (bridged to `/mdb_communications_common` — same role as the base board's `PIN_MDB_RX`) |
| 5 | `/io5` | MDB bus (bridged to `/mdb_communications_common` — same role as `PIN_MDB_TX`) |
| 14 | `/io14` → R (base) → Q2 | SIM7080G PWRKEY, via an inverting NPN driver (Q2: base from GPIO14 through a resistor, emitter to GND, collector to `/pwrkey`) — GPIO high → Q2 conducts → PWRKEY pulled low ("press"), same inverted-polarity convention as `MODEM_PWRKEY_INVERTED` in `modem.c` |
| 17 | `/io17` → Q1 → `/sim_uart1_rx` | ESP TX → SIM7080G UART1_RXD, via level-shift transistor Q1 |
| 18 | `/io18` → Q3 → `/sim_uart1_tx` | SIM7080G UART1_TXD → ESP RX, via level-shift transistor Q3 |

No AXP2101 (or any other PMU) is present on this board — modem power is
gated purely by the PWRKEY drive above. SIM7080G UART1 RTS/CTS/DCD/DTR/RI
are all left unconnected (2-wire UART only). This matches exactly the
`PIN_SIM7080G_RX`/`PIN_SIM7080G_TX`/`PIN_SIM7080G_PWR` (GPIO18/17/14)
defines already present in `mdb-slave-esp32s3/main/mdb-slave-esp32s3.c`.

## Current firmware status: **not compatible, not plug-and-play**

Those `PIN_SIM7080G_*` defines are dead code — nothing in the firmware
reads them. The actual modem driver (`main/modem.c`) hardcodes a
different, incompatible pinout, per its own comment (`modem.c` line 30+):
it targets the **LilyGo T-SIM7080G-S3 devkit**, which is what cellular
bring-up was actually verified against, not this PCB:

| Signal | `modem.c` uses (LilyGo devkit) | This PCB is actually wired to | Conflict |
|---|---|---|---|
| Modem UART RX/TX | GPIO4/5 (`MODEM_PIN_RX`/`MODEM_PIN_TX`) | GPIO4/5 = **MDB bus** on this PCB | Real electrical conflict — `modem.c` would claim the MDB bus's UART2 pins for a second, unrelated UART |
| PWRKEY | GPIO41 (`MODEM_PIN_PWR`) | GPIO41 not connected to anything modem-related on this PCB | Modem never powers on — the pin that's actually wired to PWRKEY (GPIO14) is never driven |
| Modem PMU | I2C on GPIO15/7 talking to an AXP2101 (`modem_enable_pmu_rails()`) | No PMU chip on this board at all | `modem_enable_pmu_rails()` calls out to a bus with nothing listening — the LilyGo-specific rail sequencing this function does simply doesn't apply here |

Flashing today's firmware onto this board would not bring up the modem:
it initializes the wrong UART pins (colliding with the MDB bus instead of
reaching the SIM7080G), never toggles the pin that's actually wired to
PWRKEY, and attempts PMU setup against hardware that isn't there.

## What plug-and-play support would need

- Make `MODEM_PIN_RX`/`MODEM_PIN_TX`/`MODEM_PIN_PWR` in `modem.c`
  configurable per board (Kconfig, or a runtime board-detection gate)
  instead of hardcoded to the LilyGo values — this board would use
  GPIO18/17/14, matching the already-present (currently dead)
  `PIN_SIM7080G_*` defines.
- Make `modem_enable_pmu_rails()` conditional on boards that actually
  have an AXP2101, skipping it here.
- The `MODEM_PWRKEY_INVERTED` polarity convention already matches this
  board's Q2 transistor topology (active-high GPIO → active-low PWRKEY
  pulse) — only the GPIO number needs to change, not the drive logic.

Until that lands, this PCB and the current firmware are **not
interchangeable**: don't expect cellular connectivity (or a clean boot,
given the MDB/modem UART pin collision) by simply flashing the current
`mdb-slave-esp32s3` build onto this board.
