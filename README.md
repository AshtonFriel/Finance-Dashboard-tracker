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

- Optional opt-in CPI refresh from the BLS API

Also implemented: interest-paid ledger, safe-to-spend number, Sankey cash-flow diagram, Monte Carlo retirement simulation, Roth-vs-traditional modeler, crypto sell-decision (unrealized gain + LTCG-if-sold), one-tap balance-drift reconciliation, scenario diff table, and crypto-vs-market attribution split.

Implemented beyond the original spec: emergency fund tracking with EF-first payoff sequencing, cash-flow view with savings rate, debt-budget-to-investing redirect, lump-sum payments, extra-payment annual growth, custom payoff ordering, per-debt payoff what-ifs, bad-decade stress testing, per-sleeve (crypto vs equities) volatility bands, multiple investment goals, personal spending-weighted inflation rate, forward-looking raise-vs-inflation projector, import preview with replace confirmation, guided onboarding, biometric app lock, SQLCipher database encryption with safe plaintext migration, share-sheet CSV export of tables, recurring-charge (subscription) detection, transaction search browser, month-over-month top-mover spending trends, inferred minimum payments from payment history, interest-rate CSV import, saved debt scenarios with chart comparison, weekly milestone notifications, and full JSON backup/restore.
