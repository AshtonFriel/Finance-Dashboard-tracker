# Finance Dashboard Tracker — Android App Specification

An Android app for tracking personal finances, quantifying inflation's impact on purchasing power, and providing interactive visualizations for debt payoff and investment growth projections.

The app is designed to work with imported financial data (Monarch-style Balances + Transactions CSV exports) for any user. No financial data is bundled with the app or this repository; all figures shown in the UI are computed at runtime from the user's own imported files and stored only on-device.

---

## 1. App Overview

**Name:** Finance Dashboard Tracker
**Platform:** Android (minSdk 26, targetSdk 35), Kotlin + Jetpack Compose
**Charts:** Vico (Compose-native) or MPAndroidChart for pinch/zoom interactivity
**Storage:** Room (local, encrypted with SQLCipher); CSV import via Storage Access Framework
**Architecture:** MVVM, single-activity Compose navigation

**Purpose:** Give the user one place to (1) see net worth and cash flow, (2) understand how inflation erodes their income's purchasing power, (3) interactively model debt payoff strategies, and (4) project investment growth in both nominal and inflation-adjusted terms.

---

## 2. Data Model & Import

- **Balances CSV:** `Date, Balance, Account` — daily balance snapshots per account.
- **Transactions CSV:** `Date, Merchant, Category, Account, Original Statement, Notes, Amount, Tags, Owner, Reviewed`.
- Import pipeline dedupes on (date, account, amount, statement hash) so re-imports are idempotent.
- Accounts are auto-classified as **debt** (persistently negative balances), **investment** (retirement/brokerage/crypto naming heuristics), **cash** (checking/savings), or **asset** (manually tracked property), with a manual override per account.
- Income is derived from paycheck-category transactions, aggregated by calendar year. Users may optionally enter gross annual earnings by year (e.g., from an SSA statement) for a more accurate inflation analysis, since net deposits are confounded by withholding and retirement-contribution changes.

---

## 3. Main Features

1. **CSV Import & Sync** — Import/re-import Balances and Transactions exports; automatic account classification; idempotent merge.
2. **Net Worth Dashboard** — Assets vs. liabilities over time, monthly cash flow, spending by category.
3. **Inflation Impact Tracker** — Real vs. nominal income by year, cumulative purchasing power lost since a user-chosen baseline year, and the "raise needed" to restore baseline purchasing power. Ships with a bundled CPI-U table (user-editable, optionally refreshable).
4. **Debt Payoff Planner** — Interactive projections per debt and combined: minimum payment vs. extra payment vs. avalanche/snowball; full amortization tables; payoff-date and interest-saved deltas.
5. **Investment Projector** — Compound-growth projections for retirement and brokerage/crypto accounts with contribution sliders; nominal vs. real (inflation-adjusted) toggle; optimistic/expected/pessimistic bands.
6. **What-If Engine** — Shared scenario state across screens: e.g., "move $X/mo from investing to a loan" instantly updates both the debt and investment charts.
7. **Notifications** — Monthly CPI-release recompute, payoff milestones (25/50/75/100%), net-worth highs.

---

## 4. Screen-by-Screen Breakdown

### 4.1 Dashboard (home)
- Net worth headline with sparkline and month-over-month delta.
- Three stat tiles: Total Debt, Total Investments, Purchasing Power Lost YTD.
- Stacked area chart: assets vs. debts, trailing 24 months.
- Quick links to the four feature screens.

### 4.2 Inflation Impact
- **Hero stat:** "Inflation has cost you **$N** since [baseline year]." — computed from the user's income data and CPI.
- **Dual-line chart** (interactive, scrub-to-inspect): actual income vs. inflation-adjusted baseline income by year, with the gap region shaded when actual falls below what's needed to keep pace.
- **Purchasing power line:** value of $1.00 of baseline-year income over time.
- **Table:** year, actual income, income needed to match baseline purchasing power, nominal gap, income in baseline-year dollars, real gap; totals row.
- **Controls:** baseline-year picker; per-year CPI override; toggle between gross (user-entered) and net (paycheck-derived) income source.

### 4.3 Debt Payoff Planner
- Debt list cards (balance, APR, payment) → tap for detail.
- **Combined payoff chart:** stacked area of all debt balances declining to $0 over time, with scenario lines overlaid (current payments, +$X/mo variants).
- **Interactive controls:** extra-payment slider, strategy toggle (avalanche / snowball / pro-rata), per-debt APR editors (CSV exports don't include rates, so defaults are suggested by debt type and always user-editable).
- **Delta banner:** live-computed "Extra $X/mo → debt-free N months sooner, saves $Y interest."
- **Amortization table** (per debt, monthly rows): payment #, date, payment, principal, interest, remaining balance. Sticky header, CSV export.

### 4.4 Investment Projector
- Current holdings cards per investment account.
- **Projection chart:** line chart to a user-set horizon (5/10/20/30 yr) with three bands (e.g., pessimistic 3%, expected 7%, optimistic 10% for equities; wider, user-set bands for crypto). Shaded confidence region between bands.
- **Nominal vs. Real toggle:** real mode deflates by an assumed-inflation slider.
- **Contribution sliders:** retirement $/paycheck, recurring-buy $/mo; employer-match field.
- **Projection table:** yearly rows — year, contributions, growth, nominal balance, real (today's-dollars) balance.
- Milestone markers on the chart (e.g., $250k, $500k, $1M crossings with projected dates).

### 4.5 Accounts & Transactions
- Account list grouped by type with latest balance and 90-day sparkline.
- Transaction search/filter (category, merchant, date range); category spending donut + monthly bar chart.

### 4.6 Settings
- CSV import/re-import, CPI table editor, rate assumptions, biometric lock, data export, notification preferences, and a "delete all data" action.

---

## 5. Chart Specifications

| Chart | Type | Data | Interactivity |
|-------|------|------|---------------|
| Net worth trend | Stacked area (assets/debts) + net line | Daily balance snapshots, aggregated monthly | Pinch-zoom, scrub tooltip |
| Income vs. inflation | Dual line + shaded gap region | Annual income, CPI-adjusted baseline | Tap year → detail card; baseline picker animates re-base |
| Purchasing power decay | Single line ($1.00 → $0.xx) | Cumulative CPI | Scrub |
| Debt payoff | Multi-line / stacked area to zero | Amortization engine output per scenario | Slider re-renders <16ms; long-press compares two scenarios |
| Investment projection | Line with confidence band | Compound growth engine, 3 return scenarios | Horizon chips, nominal/real toggle crossfades, milestone pins |
| Spending breakdown | Donut + monthly grouped bars | Transaction categories | Tap slice → filtered transaction list |

Rendering rules: one accent color per semantic series (debt = warm/red family, investment = green/teal, inflation baseline = neutral gray dashed), consistent across light/dark themes; all charts support haptic scrubbing and value tooltips.

---

## 6. Table Specifications

1. **Inflation table** — year, actual income, needed income, nominal gap, real income, real gap; totals row; color-coded gap column.
2. **Amortization tables** — per-debt monthly schedule (payment #, date, payment, principal, interest, balance); collapsible by year; totals footer (total paid, total interest). Regenerates on any slider change.
3. **Investment projection table** — yearly: age (optional), contributions, employer match, growth, ending nominal balance, ending real balance. Scenario column selector.
4. **Payoff comparison table** — strategy × (payoff date, months saved, interest paid, interest saved).

All tables: sticky headers, horizontal scroll on narrow screens, CSV export via share sheet.

---

## 7. Calculation Engines

- **Inflation:** `real = nominal × CPI(base)/CPI(year)`; cumulative gap = Σ(actualᵧ − baseline×Π(1+CPI rateᵧ)). CPI-U annual averages bundled as defaults, user-editable, optionally refreshable from the BLS API.
- **Amortization:** standard monthly `interest = balance × APR/12`; supports extra payments and avalanche (highest APR first) / snowball (lowest balance first) rollover of freed payments.
- **Investment:** monthly compounding `FV = P(1+r/12)ⁿ + PMT×[((1+r/12)ⁿ−1)/(r/12)]`; real values deflated by assumed inflation; volatile assets (crypto) modeled with user-set expected return and explicit wide banding rather than false-precision forecasts.
- All projections recompute reactively (Kotlin Flow) so sliders feel instant.

---

## 8. Privacy & Non-Functional Requirements

- **No financial data in the repository, binary, or any remote service.** All user data stays on-device in an encrypted database; the only optional network call is a CPI refresh behind explicit user opt-in.
- Biometric/PIN app lock; encrypted database (SQLCipher); "delete all data" wipes the DB and imported file cache.
- Performance target: tens of thousands of transaction/balance rows with <1s import-to-dashboard.
- Accessibility: TalkBack labels on all chart data points via view-model-provided summaries (e.g., "2025: income $X, needed $Y, gap −$Z").
