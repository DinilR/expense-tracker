# SMS Expense Tracker

An Android app that reads bank SMS (Sampath `SAMPATHTXN` and BOC `BOC` built in, plus any bank you add) and tracks expenses, income and transfers between your own accounts. Everything runs on the phone: the app has no internet permission.

Design: [`../EXPENSE_TRACKER_PLAN.md`](../EXPENSE_TRACKER_PLAN.md)

## Build, test, run (from the VS Code terminal)

```powershell
cd expense-tracker
.\gradlew.bat :engine:test          # parser, classification, linking, insights tests (no emulator needed)
.\gradlew.bat assembleDebug         # builds app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat installDebug          # installs on the running emulator / connected phone
adb shell am start -n lk.expensetracker/.ui.MainActivity
```

Check the rules against your own full SMS export (local only; the export is git-ignored):

```powershell
.\gradlew.bat :engine:test -PsmsExport=D:/Studies/projects/AI_USED/sms-20261107183857.xml
```

Simulate an incoming bank SMS on the emulator:

```powershell
adb emu sms send BOC "POS/ATM Transaction Rs 220.00 From A/C No XXXXXXXXXX999. Balance available Rs 100184.97 - Thank you for banking with BOC"
```

Multi-line Sampath messages can be sent from the emulator's **Extended controls → Phone** panel.

## Project layout

| Module / package | What it holds |
|---|---|
| `engine` (pure Kotlin, JVM) | Everything that can be tested without Android |
| `engine/.../bank` | `BankPack` JSON model, validation, built-in packs (`src/main/resources/banks/*.json`) |
| `engine/.../interpretation` | `MessageInterpreter` interface, `InterpretationPipeline`, `RuleBasedInterpreter` |
| `engine/.../classification` | Categories, `Categorizer`, `LedgerLinker` (reversals, internal-transfer pairs), `LedgerMath` (totals) |
| `engine/.../insights` | Month reports, `StatisticalInsightProvider`, `InsightProvider` interfaces |
| `engine/.../ai` | AI extension points: `LlmClient`, `LlmMessageInterpreter`, `LlmInsightProvider`, `SmsRedactor` (no back-end wired yet) |
| `app/.../data` | Room database, `LedgerRepository` (ingest, linking, user edits, CSV export) |
| `app/.../sms` | Inbox reader, `SmsReceiver`, `ImportWorker`, notifications |
| `app/.../ui` | Compose screens: Overview, Transactions, Detail, Insights, Banks, Bank rule editor, Review, Settings |

## Adding a bank

**In the app (no JSON needed):** Banks → **Add bank** opens the teach-a-bank wizard:
1. Pick the bank's SMS conversation.
2. The app groups the messages by format and highlights what it understood: amount, account, shop, date, balance, and whether money went in or out. Tap any part to correct it, and choose the direction, type or category if needed.
3. Preview the result on all of that bank's messages, then save. Past messages are imported straight away.

When a bank changes its format, the new message appears in **Review**. Tap **Teach the app** there and the new format is added to that bank.

The learner is in `engine/.../learn` (`Tokenizer`, `BankLearner`, `RuleGenerator`). It runs entirely on the device. On the SMS export, learning Sampath and BOC from scratch agrees with the hand-written rules on 100% of transactions (`LocalLearnFromExportTest`).

**Advanced:** tap a bank to edit its rules as JSON, **Test on inbox**, and **Share** them with a friend.

**Built in:** add `engine/src/main/resources/banks/<id>.json`, list it in `BuiltInBanks`, and add anonymised fixtures to `engine/src/test/resources/fixtures/sms-fixtures.json`.

Named regex groups: `amount` (required), `currency`, `direction`, `account`, `channel`, `merchant`, `date`, `balance`, `type`. Classification rules (`field` + `match`, optional `direction`) then set `kind`, `channel` and `category`.

## Status against the plan

| Phase | Status |
|---|---|
| 0 Setup | Done: Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Compose, Room, WorkManager |
| 1 Interpretation engine | Done: Sampath + BOC packs, fixture tests, local export coverage test |
| 2 Classification | Done: kinds, reversal matching, internal-transfer suggestions, categoriser with learn-from-edit |
| 3 Storage + ingestion | Done: Room, inbox import, live SMS receiver, duplicate prevention, review queue |
| 4 Core UI + statistical insights | Done: Day / Week (Monday start) / Month views with swipe, tap-a-bar drill-down, plain-language summaries, category icons and colours, Light / Dark / System theme. Budgets are not built yet. |
| 5 Extensibility UI | Done as a JSON editor with live inbox test and sharing. The highlight-to-build rule builder is not built yet. |
| 6 Polish + sharing | Notifications and CSV export done. Release signing, backup/restore and app lock are still to do. |
| 7 AI layer | Interfaces, redaction and parsing done and unit-tested. No LLM back-end connected yet. |
| 8 Play readiness | Not started |

Differences from the plan: Hilt was replaced by a small manual `AppContainer`, and charts are drawn with Compose `Canvas` instead of Vico. Both avoid extra dependencies at this size.
