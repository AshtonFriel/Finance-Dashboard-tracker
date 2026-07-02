# Finance Dashboard Tracker

An Android app that tracks personal finances from CSV exports, quantifies how much purchasing power inflation has cost you, and provides interactive debt-payoff and investment projections. See [SPEC.md](SPEC.md) for the full specification.

**Privacy:** no financial data lives in this repository or leaves the device. The app makes no network calls; everything is computed from CSVs you import locally.

## Features

- **Dashboard** — net worth, assets vs. debts stacked area chart, headline stat tiles.
- **Inflation impact** — actual vs. CPI-adjusted income with shaded shortfall, purchasing-power decay chart, year-by-year table, baseline-year picker, editable CPI table, gross/net income source toggle.
- **Debt payoff** — avalanche / snowball / pro-rata strategies, extra-payment slider, live "months sooner / interest saved" delta, combined payoff projection with no-extra overlay, per-debt amortization tables, strategy comparison table.
- **Investments** — compound-growth projection with pessimistic/expected/optimistic bands, nominal vs. today's-dollars toggle, contribution/return/inflation sliders, milestone estimates, year-by-year table.
- **Accounts** — auto-classified (cash / investment / debt / asset) with manual override, balance sparklines, 12-month spending donut.
- **Import** — Monarch-style `Balances` (`Date,Balance,Account`) and `Transactions` CSV exports via the system file picker; streaming RFC-4180 parser; re-import replaces prior data.

## Design

The UI implements the **Fiscal** design language from the handoff in [`docs/design-handoff/`](docs/design-handoff/): a dark-green theme (`#0f1f17` background, `#3ecf8e` accent, coral/amber/sky pillar colors), Space Grotesk for numbers and titles with IBM Plex Sans for body text, gradient hero cards with progress rings, custom segmented controls, and geometric pillar glyphs. Fonts are bundled (both OFL-licensed).

## Project layout

- `core/` — pure-Kotlin module: CSV parsers, account classifier, and the three engines (inflation, amortization, investment). Fully unit-tested; no Android dependencies.
- `app/` — Jetpack Compose UI (Material 3), Room persistence, custom Canvas charts with scrub-to-inspect tooltips.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```bash
gradle :core:test          # engine + parser unit tests
gradle :app:assembleDebug  # APK at app/build/outputs/apk/debug/app-debug.apk
```

## Notes & assumptions

- CSV exports don't carry interest rates: the app suggests APRs by debt type and every value is editable per debt.
- CPI-U annual averages (public BLS data) ship as defaults and can be overridden or extended per year.
- Projections are fixed-rate scenario models, not forecasts; volatile assets get explicitly wide bands.

## Roadmap

- Database encryption (SQLCipher) + biometric app lock
- CSV export of amortization/projection tables via the share sheet
- Milestone/net-worth notifications
- Optional opt-in CPI refresh from the BLS API
