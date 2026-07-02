# Handoff: Fiscal — Debt, Income & Investment Tracker (Android)

## Overview
Fiscal is a personal-finance Android app with three pillars, tied together by a dashboard:
1. **Debt payoff** — track multiple debts, compare snowball vs. avalanche strategies, see payoff date and interest saved, simulate extra payments.
2. **Income vs. inflation** — real vs. nominal income over time, purchasing-power erosion, whether raises kept up with CPI.
3. **Investments** — compound-growth projection to retirement, scenario comparison (conservative/moderate/aggressive), contribution slider, goal tracking, contributions-vs-growth breakdown.

Tone: **bold and motivating, progress-focused**. Big numbers, progress rings/bars everywhere, dark green theme. Currency: USD.

## About the Design Files
The files in this bundle are **design references created in HTML** — an interactive prototype showing intended look and behavior, NOT production code to copy directly. Your task is to **recreate this design in the target codebase's environment**. For a native Android app, that most likely means **Kotlin + Jetpack Compose (Material 3)** using its established patterns — or whatever framework the project already uses (Flutter, React Native, etc.). If no codebase exists yet, Jetpack Compose is the recommended choice.

- `Fiscal.dc.html` — the full prototype (all four screens + logic). Open in a browser to interact. All styles are inline; all business logic is in the `class Component` script at the bottom of the file.
- `android-frame.jsx` — a device-bezel wrapper used only for presentation in the prototype. **Ignore it for implementation**; the real app fills the device screen.

## Fidelity
**High-fidelity.** Colors, typography, spacing, radii, and copy are final intent. Recreate the UI faithfully, translating to Material 3 idioms where sensible (e.g. real ripple effects, system navigation). The math (amortization, FV) is specified below and should be implemented exactly.

## Design Tokens

### Colors
- Background: `#0f1f17` (near-black green)
- Bottom nav background: `#0c1912`
- Card surface: `#16271e`, border `rgba(255,255,255,0.05)`, radius 20px
- Hero card: gradient `linear-gradient(160deg, #173026, #12241b)`, border `rgba(62,207,142,0.14)`, radius 24px, shadow `0 18px 40px rgba(0,0,0,0.35)`
- Primary accent (progress, positive, active states): `#3ecf8e`; darker pair `#2ba876`
- Warning / debt: `#ff8b6b` (coral); tint bg `rgba(255,139,107,0.1)`, tint text `#ffb59e`
- CPI / income: `#f5c451` (amber)
- Contributions / tertiary: `#6bd0ff` (sky)
- Text primary: `#eaf3ee`; secondary: `#8fa89a`; muted labels: `#5f7a6c`
- Track/empty progress: `rgba(255,255,255,0.07–0.09)`
- Text on accent-filled elements: `#0f1f17`

### Typography
- **Space Grotesk** (Google Fonts) — all numbers, screen titles, big stats. Weights 600–700.
- **IBM Plex Sans** — body, labels, buttons. Weights 400–600.
- Scale: screen title 26/700; hero number 30–38/700; card stat 22/700; sub-stat 18–20/700; body 13–14; section eyebrow 12, uppercase, letter-spacing 1px, color `#5f7a6c`; captions 11–12.

### Shape & spacing
- Screen padding: 18px horizontal
- Card padding: 18–22px; gap between cards 12–16px
- Radii: hero 24, card 20, inner stat tiles 14, segmented control 16 (inner buttons 11), pills/bars 99
- Progress bars: 6–10px tall, fully rounded
- All touch targets ≥ 44px

## Screens / Views

### 1. Home (dashboard)
- **Header**: "Good evening," (13px `#8fa89a`) over user name (26/700 Space Grotesk); 42px circular avatar right (gradient `#3ecf8e→#2ba876`, initial letter in dark green).
- **Hero card ("Debt-free by")**: horizontal — left: 92px donut ring showing % of debt paid (stroke 9, `#3ecf8e` on `rgba(255,255,255,0.09)`, rounded cap, % centered 20/700); right: eyebrow "DEBT-FREE BY" in accent, payoff date (30/700), caption "Xy Ym to go · $N saved in interest".
- **Section eyebrow**: "YOUR MONEY, AT A GLANCE".
- **Three navigation cards** (whole card tappable → respective tab). Each: 44px rounded-square icon tile (14px radius, 14% tinted bg, simple geometric glyph in the pillar color), label + big number, right-aligned accent stat with "›".
  - Debt: coral circle icon; "Total debt" / $45,200; "30% paid ›" (green)
  - Income: amber diamond; "Income (real, 2021 $)" / $66,929; "-2 vs CPI ›" (amber)
  - Invest: green triangle; "Investments" / $42,000; "→ $1,059K ›" (green, value follows scenario/contribution state)
- **"This month" card**: three columns divided by 1px hairlines — amount to debt ($970), invested ($500), extra pmt ($200, green). Values derive from state.

### 2. Debt payoff
- **Title** "Debt payoff" + subtitle "<Avalanche|Snowball> strategy".
- **Hero card**: "Total remaining" caption, total 38/700; 8px progress bar (green gradient) = % of original borrowed that's paid; below: "30% paid off" ↔ "$61,000 borrowed"; two inner tiles (radius 14, bg `rgba(62,207,142,0.08)`): "PAYOFF DATE" and "INTEREST SAVED" (saved value in green).
- **Strategy segmented control**: 2 buttons in a card-colored track (padding 6). Active: filled `#3ecf8e`, text `#0f1f17`; inactive: transparent, text `#8fa89a`. Below, a one-line explainer that swaps with selection:
  - Avalanche: "Avalanche targets your highest-interest debt first — the fastest way to minimize total interest paid."
  - Snowball: "Snowball clears your smallest balance first — slower on interest, but the quick wins keep you motivated."
- **Extra payment slider card**: label + live green value (22/700); slider $0–$1,000, step $25, default $200. Thumb: 22px green circle with dark border; track 6px, `rgba(62,207,142,0.18)`.
- **Debt list** ("YOUR DEBTS"): one card per debt — colored 10px dot + name + optional "FOCUS" badge (10/700 uppercase, green pill, dark text) on the debt currently targeted by the strategy; balance right (16/700). 6px progress bar in the debt's color = % of original paid. Footer row: "22.9% APR · min $210" ↔ "30% paid".
- **Monthly breakdown card**: rows Minimum payments ($770) / Extra toward focus (green, = slider) / hairline / Total per month (bold).

### 3. Income vs inflation
- **Title** "Income vs inflation" + subtitle "Are your raises keeping up?".
- **Hero card**: two stats — "NOMINAL 2026" $85,000 (left) vs "REAL (2021 $)" $66,929 in amber (right); line chart below (see Charts); legend: green "Nominal (what you're paid)", amber "Real buying power".
- **Purchasing power card**: "Purchasing power lost since 2021", loss in coral 28/700 ($18,071); explanatory sentence referencing both numbers.
- **Raises vs CPI card**: two labeled horizontal bars — cumulative raises (+25%, green) and cumulative CPI (+27%, amber), widths proportional (scaled to 40% = full width); verdict callout below in a coral-tinted rounded box: "Your raises trailed inflation by 2 points since 2021. In real terms, you earn slightly less than you did then." (Positive-gap variant: "Good news — your raises outpaced inflation by X points since 2021.")

### 4. Investments
- **Title** "Investments" + subtitle "Projected to retirement (30 yrs)".
- **Hero card**: "TODAY" $42,000 vs "IN 30 YEARS" (green, live-updating); area+line chart below; legend: green "Total balance", sky "Contributions".
- **Scenario segmented control**: 3 buttons — Conservative 5% / Moderate 7% / Aggressive 10% (rate as second line, 11px, 70% opacity). Same active/inactive treatment as Debt.
- **Contribution slider card**: $0–$3,000, step $50, default $500. Same styling as Debt slider.
- **Retirement goal card**: "Retirement goal" ↔ "$1.0M"; 10px green-gradient progress bar = min(100, projected/1M); caption "Projected to reach NN% of goal".
- **Breakdown card** ("WHERE THE MONEY COMES FROM"): single 14px stacked bar — sky segment (contributions share) + green (growth); legend rows "You put in $X" and "Compound growth $Y" (green).

### Bottom navigation (all screens)
Fixed bar, bg `#0c1912`, top hairline. Four equal items: geometric glyph (rounded square = Home, circle = Debt, diamond = Income, triangle = Invest) + 11/600 label. Active: `#3ecf8e`; inactive: `#5f7a6c`. In Compose, use a Material 3 NavigationBar with these colors; real icons may replace the geometric glyphs if the project has an icon set.

## Charts
Simple, unlabeled-axis line charts inside hero cards (~320×150, full card width):
- Two faint horizontal gridlines (`rgba(255,255,255,0.06)`) at 50% and 100% height.
- 3px rounded polylines; 4.5px end dot with 2px dark-surface ring on the latest point.
- Income chart: nominal (green) vs real (amber), y-min pinned at $55,000 to amplify the divergence.
- Invest chart: balance line (green) with `rgba(62,207,142,0.12)` area fill under it, contributions line (sky), y-min 0; points every 2 years, 0–30.
Use whatever chart approach fits the codebase (Compose Canvas is sufficient — no library needed).

## Interactions & Behavior
- Bottom nav switches screens; state persists across switches (slider/toggle values are global, and dashboard numbers reflect them).
- Strategy toggle and extra-payment slider **recompute payoff date, months remaining, interest saved, and the FOCUS badge live**.
- Scenario toggle and contribution slider **recompute projection, chart, goal %, and breakdown live**.
- Whole dashboard cards are tappable (navigate). Add ripple feedback per Material.
- Sliders update continuously while dragging.
- No loading/error states in scope; all data local in the prototype. In production, persist user inputs (DataStore/Room).

## State Management
Prototype state (see `renderVals()` in Fiscal.dc.html):
- `tab: home | debt | income | invest`
- `strategy: avalanche | snowball` (default avalanche)
- `extra: 0–1000` extra monthly debt payment (default 200)
- `scenario: conservative | moderate | aggressive` (default moderate)
- `contrib: 0–3000` monthly investment contribution (default 500)

### Core math (implement exactly)
**Debt simulation** (month-by-month, max 720 months):
1. Each month, accrue interest on every open debt: `balance += balance * (apr/100/12)`; sum into totalInterest.
2. Pay each debt its minimum (capped at balance); freed-up minimums from paid-off debts roll into a pool along with the extra payment.
3. Sort open debts — avalanche: highest APR first; snowball: lowest balance first — and pour the pool into them in order.
4. Payoff date = current month + months to zero. **Interest saved** = totalInterest with extra=0 minus totalInterest with current extra (same strategy).

**Investment projection** (monthly compounding): `FV = P·(1+r)^n + PMT·((1+r)^n − 1)/r`, r = annualRate/12/100, n = 360. Contributions total = P + PMT·360; growth = FV − contributions.

**Real income**: `real[i] = nominal[i] / (cpi[i]/100)` with CPI indexed to 2021 = 100.

### Sample data
- Debts: Credit Card $8,400 @ 22.9% APR, min $210, original $12,000 (coral) · Car Loan $14,200 @ 6.4%, min $320, original $21,000 (amber) · Student Loan $22,600 @ 4.5%, min $240, original $28,000 (sky)
- Income (2021–2026): $68k, $71k, $74k, $78k, $82k, $85k; CPI index: 100, 108, 114, 119, 123, 127
- Investments: current value $42,000; goal $1,000,000; horizon 30 years; scenario returns 5/7/10%

## Assets
No image assets. Fonts from Google Fonts (Space Grotesk, IBM Plex Sans — both open license, bundleable in Android). Icons in the prototype are simple geometric shapes; substitute the project's icon set if one exists.

## Files
- `Fiscal.dc.html` — full interactive prototype (markup = intended UI; `class Component` script = all state + math)
- `android-frame.jsx` — presentation-only device bezel; not part of the app
