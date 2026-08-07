# Openly — Bet-matching forensics

_Git forensic analysis, commissioned to answer: did a recent build ("granularity one/half → more
resolution") break bet matching, turning `post → counter → accept → ONE live bet` into a flow that
spawns dangling parallel bets?_

Commits examined (`git log --oneline -15`):

```
e2c1bb7 Settlement works two-device on mainnet: MMR proof + one-coin transport + payoff   (HEAD)
827d794 Chunk settlement hex across SETTLE_TXN messages
3b68808 Cap comms mail-scan depth to 8
9622a7d Fix TransactionTooLargeException crash loop — bound all coins queries
351044a Settlement receive: case-insensitive comms-id match + gentler deep rescan
14d1f9a Settlement receive fix, git-auto versioning, live-test UI batch
e8d6d9c Fix UI-thread ANR, market card, and launcher icon
b00d924 Fix all code-review findings + 16 KB dialog + arbiter dispute redesign
dffd5ce Build quality: coherent NOIR chrome, theme toggle
de9546a Phase 9: onboarding, security review, docs
1b7c262 Phase 8: timeout + foreground service
d378507 Phase 7: arbiter
909f118 Phase 6: self-settle
5eee385 Phase 5: comms online
41d8ec0 Phase 4: fill + the Counter/Take slider sheet
1a0945d Phase 3: post + cancel
2f02a6c Phase 2: design system + read-only board
a6e66f9 Phase 1: scaffold
```

The working tree has uncommitted changes to 9 files (`git status`); those are treated as the current
build. CounterSheet.java touching commits: **41d8ec0, b00d924, 14d1f9a, e2c1bb7** + working tree.

---

## Honest summary

**The "recent build" the author blames did NOT change bet-matching logic.** The commit that changed
slider granularity to "1 / 0.5" steps is **e2c1bb7**, and the *working-tree* change to "finer
resolution" (0.5 / 0.25 / 0.1) is the only uncommitted CounterSheet edit. **Both touched ONLY the
notch-size table (`grid()`). Zero accept-vs-counter, post-vs-fill, ownership, or matching code changed
in either.** The rule "slide below the ask → post a NEW opposite-side bet coin (a counter)" has been
present since the CounterSheet was first written (**41d8ec0, Phase 4**) and was **never** a fill-only
design. Countering has therefore *always* created a second coin.

The only matching-adjacent behavioural change in recent history is the **addition** of
`autoCancelSuperseded()` in **e2c1bb7** — brand-new code that *cleans up* leftover open bets. That
moves the app toward fewer dangling bets, not more. So the evidence does **not** support "a recent
build removed cleanup and started spawning dangling bets." If anything, cleanup that never existed
before now exists. The most likely real cause of a "dangling bet" the author still sees is **timing /
coin visibility** (auto-cancel only fires once *both* the leftover open coin and the matched coin are
visible to the scanner, and all coins queries are now bounded to avoid the IPC crash), not a
regression in the match/counter logic itself.

---

## Q1 — COUNTER SEMANTICS: does "below the ask" post a new coin or fill?

**Finding: below-ask → `act.txn.post(...)` (a NEW opposite-side coin) since the very first version,
41d8ec0. It was never fill-only.**

`git log --oneline --follow -- CounterSheet.java` → only 4 commits: 41d8ec0, b00d924, 14d1f9a,
e2c1bb7.

**41d8ec0 (Phase 4, file created)** — `git show 41d8ec0:.../CounterSheet.java`, `submit()`:

```java
if (full) {
    act.txn.fill(bet, ...);                 // at full ask → FILL
} else {
    act.txn.post(bet.proposition, mySide, theirBet, myWant, ...);   // below ask → POST a new bet
}
```
Class doc, same commit: *"At the full ask → TAKE: fill the bet directly. Below the ask → COUNTER: post
a new bet on the opposite side."*

**b00d924** — `git diff 41d8ec0 b00d924` on this file: a **one-line** change dropping the
`bet.arbcommsid` argument from the `post(...)` call. No semantic change.

**14d1f9a** — still `accept → fill`, `else → post`. (See Q3 for what this commit *did* change.)

**e2c1bb7 (HEAD)** and **working tree** — unchanged branch structure: `if (accept) act.txn.fill(...)`
else `act.txn.post(bet.proposition, mySide, stake, theirStake, ...)`.

**Verdict:** "below-ask posts a new bet" is by-design and longstanding (first appears 41d8ec0). Not a
regression.

---

## Q2 — ACCEPT vs COUNTER boundary

**Finding: the boundary is "is the stake within 0.01 of the full ask?" and it has never moved. The
default slider position has always started pinned at the full ask (= Accept).**

- 41d8ec0 / b00d924: `isFullAsk(v)` = `v.subtract(theirAsk).abs() < 0.01`; slider
  `setProgress(STEPS)` (full ask) at start.
- 14d1f9a → HEAD → working tree: renamed to `isAccept(stake)` = `stake.subtract(fullAsk).abs() <
  0.01`; identical 0.01 tolerance. Start position still the full ask (`setProgress(STEPS)`, and
  e2c1bb7 added `applyStake(fullAsk, false)` in `onCreate` — still Accept).

The threshold value (0.01) and the default (full ask = Accept) are **constant across every commit**.
No gesture that used to FILL now POSTs because of a boundary/default move. **Not a regression.**

---

## Q3 — SLIDER GRANULARITY (the commit the author suspects)

**Finding: the "1 / 0.5" granularity was introduced in e2c1bb7 (HEAD). The working tree then refined
it to "0.5 / 0.25 / 0.1". BOTH changes are notch-size-only — no matching logic changed in either.**

There was **no coarse grid at all** before e2c1bb7. In 41d8ec0…14d1f9a the slider snapped only to the
0.01 grain (`Num.GRAIN`) via `sliderValue()` / `myStake()`.

**e2c1bb7** introduced `grid()` — `git diff 14d1f9a e2c1bb7 -- CounterSheet.java`:

```java
+    private BigDecimal grid() {
+        if (fullAsk.compareTo(new BigDecimal("20")) >= 0) return new BigDecimal("1");
+        if (fullAsk.compareTo(new BigDecimal("5"))  >= 0) return new BigDecimal("0.5");
+        if (fullAsk.compareTo(new BigDecimal("1"))  >= 0) return new BigDecimal("0.1");
+        return Num.GRAIN;
+    }
```
This is the **"granularity one/half"** the author remembers (≥20→1, ≥5→0.5). In the *same* commit the
`if (accept) fill else post` branch is untouched and `isAccept` is untouched (that same diff shows the
accept branch changed only by adding `act.scanner.markFilled(bet.nonce)` inside the fill success
callback — a UI-hide optimisation, not a match-vs-counter decision).

**Working tree** — the only uncommitted CounterSheet edit, `git diff -- CounterSheet.java` in full:

```diff
-     *  ≥1→0.1, else 0.01. ...
+     *  (≈quarter steps): ≥40→0.5, ≥10→0.25, ≥2→0.1, else 0.01. ...
     private BigDecimal grid() {
-        if (fullAsk.compareTo(new BigDecimal("20")) >= 0) return new BigDecimal("1");
-        if (fullAsk.compareTo(new BigDecimal("5"))  >= 0) return new BigDecimal("0.5");
-        if (fullAsk.compareTo(new BigDecimal("1"))  >= 0) return new BigDecimal("0.1");
+        if (fullAsk.compareTo(new BigDecimal("40")) >= 0) return new BigDecimal("0.5");
+        if (fullAsk.compareTo(new BigDecimal("10")) >= 0) return new BigDecimal("0.25");
+        if (fullAsk.compareTo(new BigDecimal("2"))  >= 0) return new BigDecimal("0.1");
         return Num.GRAIN;
     }
```

That is the **entire** working-tree CounterSheet diff — nine lines, all inside `grid()` and its
comment. **No accept/counter/post/fill logic changed.**

**Verdict:** the commit the author suspects (e2c1bb7 + the working-tree refinement) changed **only the
slider notch sizes**. It cannot, by itself, have changed which bets match or how many coins a counter
creates.

---

### Aside (real change in 14d1f9a, not the suspected one)

The one genuine *semantics* change to what the slider means happened earlier, in **14d1f9a**
(`git diff b00d924 14d1f9a -- CounterSheet.java`), and it is worth flagging honestly:

- **Before (41d8ec0):** slider = my **want**; my stake was fixed at the poster's stake →
  `post(prop, mySide, theirBet, myWant)`.
- **After (14d1f9a → now):** slider = my **stake** (floor…fullAsk); my want fixed at the poster's
  stake → `post(prop, mySide, stake, theirStake)`.

This flips what the number under the slider represents when countering, but **it still posts a new
opposite-side coin** — the coin count and the accept/counter split are unchanged. This is *not* the
"granularity" commit the author named, and it predates it.

---

## Q4 — OWNERSHIP ON MATCH (state port 0 = ownerpk, port 13 = counterpk)

**Finding: the POSTER of a coin is always the owner (port 0); the FILLER is always the counter
(port 13). So when device X counters (posts a new coin) and device Y accepts (fills it), X owns the
matched coin. This is consistent and unchanged.**

`OpenlyTxn.post()` writes the poster's own identity into the owner region:

```java
st.put("0", id.pubkey);     // owner pubkey  = ME (the poster)
st.put("1", id.hexaddr);
...
st.put("5", String.valueOf(side));
```

`OpenlyTxn.fill()` pins the owner region and writes the filler into the counter region + phase→1:

```java
cmds.add(st(txid, 0, bet.ownerpk));      // owner preserved
cmds.add(st(txid, 1, bet.owneraddr));
// port 12 phase → 1, my identity into 13/14/16 (counterpk/counteraddr/countercommsid)
```

`Bet.from(...)` classifies:

```java
b.ownerpk   = c.at(0);
b.counterpk = c.at(13);
b.isMine      = myKeys.contains(b.ownerpk);     // I posted this coin
b.isMyCounter = myKeys.contains(b.counterpk);   // I filled this coin
```

So in `X counters, Y accepts`: X's counter coin has port 0 = X → `X.isMine = true` (X = owner);
Y's fill writes port 13 = Y → `Y.isMyCounter = true`. **X is the owner of the matched coin.** The
`st.put("0", id.pubkey)` in `post()` dates to the post/fill implementation (1a0945d/41d8ec0) and is
unchanged since. **Not a regression.**

---

## Q5 — AUTO-CANCEL OF LEFTOVER OPEN BETS (prime suspect)

**Finding: `autoCancelSuperseded()` is BRAND NEW in e2c1bb7 (HEAD). It did not exist in any earlier
commit. It DOES clean up the leftover for the party who is the COUNTER of a matched bet — because its
"live proposition" set includes matched bets where `isMyCounter`, and it cancels any open coin the
user OWNS on that proposition. The working tree changed only its toast text, not its logic.**

`git log --oneline -S "autoCancelSuperseded" -- MainActivity.java` → **e2c1bb7 only** (introduced,
never modified in a later commit). Before e2c1bb7 there was **no leftover-cleanup code at all**.

Current logic (`MainActivity.autoCancelSuperseded`, identical in e2c1bb7 and working tree bar the
toast string):

```java
java.util.Set<String> liveProps = new java.util.HashSet<>();
for (Bet b : scanner.matched)
    if ((b.isMine || b.isMyCounter) && b.proposition != null && !b.proposition.isEmpty())
        liveProps.add(b.proposition);                 // <-- includes bets I COUNTERED
if (liveProps.isEmpty()) return;
for (final Bet o : scanner.open) {
    if (!o.isMine || o.proposition == null || !liveProps.contains(o.proposition)) continue;
    if (!autoCancelled.add(o.coinid)) continue;       // once per coin
    scanner.markFilled(o.nonce);                       // hide from board immediately
    txn.cancel(o, ...);                                // owner-signed cancel of MY open coin
}
```

**Trace of the exact scenario in the brief** — device X posts original TRUE (owns open coin `O`);
counterparty Y posts a FALSE counter; X *fills* Y's counter → matched coin `M` (owner Y, counter X):

1. `M` is in `scanner.matched` with `M.isMyCounter == true` for X → **X's `liveProps` contains the
   proposition.** ✅
2. `O` is in `scanner.open` with `O.isMine == true` and the same proposition → the guard
   `!o.isMine || !liveProps.contains(...)` is **false**, so it is **not** skipped → **`O` is
   auto-cancelled.** ✅

So the current code **does** clean the counter-party's leftover. It matches strictly by proposition
string (empty propositions are skipped), fires once per coinid, and requires the leftover to be an
open coin the user can owner-sign (`o.isMine`).

**Did it EVER work differently?** There is no earlier version to regress *from* — the feature is new
in HEAD. The working-tree diff to this method is **cosmetic only**:

```diff
-            toast("Superseded open bet cancelled — " + Num.plain(o.ownerBet()) + " returning");
+            toast("Cancelled your other open bet on this proposition · " + Num.plain(o.ownerBet()) + " M returning");
```

**Verdict:** This is *not* a regression that removed cleanup. Cleanup was **added** in HEAD, and it
covers the counter party. The realistic failure mode is **timing / visibility**: `autoCancelSuperseded`
returns early unless *both* `scanner.matched` and `scanner.open` are non-empty and the matched coin is
actually visible to the scan. Because all `coins` queries are now bounded (9622a7d, to stop the
`TransactionTooLargeException` IPC crash) and matched coins can take ~1–2 min to confirm, there can be
a window where the leftover open coin is still shown and the cancel has not yet fired. That is a
latency/paging characteristic, not a change in match/counter logic. (Uncertain: whether the bounded
scan ever *permanently* misses a coin on a busy wallet — not provable from git alone; worth a runtime
test.)

---

## Q6 — MATCHED-OFF-MARKETS: when did MarketsView stop showing matched bets?

**Finding: MarketsView has only ever rendered OPEN (phase-0) bets. The board iterates
`act.scanner.open`, and the scanner splits coins by phase (`open` = phase 0, `matched` = phase 1). The
explicit "phase-0 ONLY" contract was documented/reinforced in 14d1f9a; the open/matched split itself
dates to Phase 2 (2f02a6c).**

`git log --oneline -S "scanner.open" -- MarketsView.java` → **2f02a6c** (board created), **14d1f9a**.
`git log --oneline -S "phase-0" -- MarketsView.java` → **14d1f9a** (the clarifying comment).

`BetScanner` (comment + split):

```java
// splits into OPEN (phase 0) and MATCHED (phase 1).
if (b.phase == 0) o.add(b);
else if (b.phase == 1) m.add(b);
```

`MarketsView` (current, 14d1f9a onward):

```java
// Markets shows OPEN (phase-0) bets ONLY. A taken bet becomes phase 1 → it leaves
// scanner.open and disappears from here; it lives in My Bets.
for (Bet b : act.scanner.open) { ... }
```

Current MarketsView contains no reference to `scanner.matched`. **Verdict:** matched bets have been
off-market since the board existed; 14d1f9a made the rule explicit. Not a recent regression.

---

## Q7 — CANCEL PATH

**Finding: there IS a Cancel button on every open (phase-0) bet the user owns. It is wired to
`act.txn.cancel(...)` and is present in HEAD and the working tree. It was added in Phase 3 (1a0945d)
and has been there ever since.**

`git log --oneline -S "txn.cancel" -- MyBetsView.java` → **1a0945d (Phase 3: post + cancel)**.

`MyBetsView.render()` lists the user's open coins and gives each an `openCard`:

```java
for (Bet b : act.scanner.open) if (b.isMine) mineOpen.add(b);
...
for (Bet b : mineOpen) list.addView(openCard(b));
```

`openCard(b)` (current working tree):

```java
TextView cancel = Ui.button(act, "Cancel", Design.SURFACE2(), Design.DIM(), false);
cancel.setOnClickListener(v -> {
    cancel.setEnabled(false);
    act.txn.cancel(b, new OpenlyTxn.Done() {
        public void ok()   { act.toast("Cancelled — funds returning"); act.refreshCurrent(); }
        public void fail(String m) { act.toast("Cancel failed: " + m); cancel.setEnabled(true); }
    });
});
card.addView(cancel);
```

`OpenlyTxn.cancel(...)` inputs the contract coin, pays out to `bet.owneraddr`, and owner-signs with
`bet.ownerpk`. The working-tree diff to MyBetsView adds a **chat "Send"** panel (around line 188), not
anything to do with cancel.

**Verdict:** the cancel path exists and is wired. The author's report "there's no path to cancel a
bet" is **not** supported by the code — with the caveat that the Cancel button lives on the **My Bets**
tab (under the "Open" section), **not** on the Markets board. A user looking on Markets would not see
it. That is a UX-placement issue, not a missing feature or a regression.

---

## VERDICT

### What actually changed recently
- **e2c1bb7 (HEAD):** slider granularity gained a coarse `grid()` (≥20→1, ≥5→0.5, ≥1→0.1). Notch
  sizes only.
- **Working tree (uncommitted):** `grid()` refined to (≥40→0.5, ≥10→0.25, ≥2→0.1). The **entire**
  CounterSheet working-tree diff is those nine lines. Notch sizes only.
- **e2c1bb7 (HEAD):** `autoCancelSuperseded()` **added** — new leftover-cleanup that *does* cover the
  counter party. Working tree only reworded its toast.
- **e2c1bb7 (HEAD):** `scanner.markFilled(bet.nonce)` added to the Accept success path (optimistic
  board hide). Not a match decision.

### What has been this way since the start
- **Counter = post a NEW opposite-side coin below the ask** — since 41d8ec0 (Phase 4). Never a fill.
- **Accept boundary = within 0.01 of the full ask; slider starts at the full ask (Accept)** — every
  commit.
- **Poster = owner (port 0); filler = counter (port 13)** — since post/fill was written.
- **Markets shows phase-0 only; matched bets live in My Bets** — since the board (2f02a6c), explicit
  in 14d1f9a.
- **Cancel button on owned open bets → `txn.cancel`** — since 1a0945d (Phase 3).

### Did autoCancelSuperseded / leftover-cleanup regress?
**No.** It is *new* in HEAD and it *does* clean up the leftover for the counter party (`liveProps`
includes `isMyCounter` matched props; it cancels any open coin the user owns on that proposition). The
code has moved toward **fewer** dangling bets, not more. The evidence contradicts "a recent build
removed cleanup and started spawning dangling bets."

### Honest caveats (not provable from git alone — need a runtime test)
1. **Timing/visibility:** `autoCancelSuperseded` fires only once *both* the matched coin and the
   leftover open coin are visible to the scanner, and all `coins` queries are now bounded (9622a7d) to
   avoid the IPC crash. On a busy wallet or during the ~1–2 min confirm window, the leftover can still
   be shown before cleanup fires — this is the most plausible source of a "dangling bet" the author
   observes, and it is a latency/paging property, not a match-logic regression.
2. **Cancel discoverability:** Cancel exists but only on the My Bets tab, not on Markets — a user
   looking in the wrong place would conclude "there's no way to cancel."
3. **Proposition-exact matching:** `autoCancelSuperseded` and the market grouping key on the
   proposition **string**; any divergence (whitespace/case) would prevent supersede-cleanup. Not
   observed in code, but worth checking against real on-chain data.
