# Openly — Security Review (native rebuild)

Threat model: a malicious counterparty who can post bets, fill bets, and send arbitrary sealed
messages to my comms identity. The five findings from the original MiniDapp review, and how the
native rebuild closes each.

## 1 — Blind co-signing of an attacker-supplied transaction — CLOSED (CRITICAL)

The original app imported a counterparty's transaction hex and signed it with zero inspection,
so any input the attacker included got authorised by my key. In the rebuild:

- `txnimport` appears in exactly **one** place: `CoSigner.validateAndPost` (`CoSigner.java:58`).
  Grepped: no other caller.
- No path reaches `txnsign` without `inspect()` returning null first (`CoSigner.java:60→signGatedPost`).
  `inspect()` enforces: exactly one input; that input's coinid == my current chain-scanned coin for
  the nonce (from my scanner, never the message); input at `OpenlyContract.ADDR`, tokenid 0x00;
  exactly two outputs equal to payouts **recomputed locally from my coin** (address + amount exact,
  storestate false, zero burn); txn state port 20 == the outcome the UI displayed.
- Signing uses my **explicit** bet key (`publickey:<myBetPk>`), never `auto` — so even a crafted
  txn cannot conscript unrelated wallet keys.
- `txncheck` gate (incl. `allsignaturesvalid`) before `txnpost`; `txndelete` on every path.
- **No auto-cosign** — accepting a settlement is always an explicit human tap.
- Backstop proven on-chain: a wrong-split settlement that steals the escrow is **rejected by the
  contract** even if co-signed (`tests/RESULTS-v4.md`). Two independent layers.

## 2 — Float arithmetic bricking settlement — CLOSED (HIGH)

`Num.java` does all money math in `BigDecimal` under `MathContext(64, RoundingMode.DOWN)`, mirroring
the KISS VM's `MiniNumber`. Grep confirms no `float`/`double`/`parseFloat` in `Num`, `Bet`, `CoSigner`,
`SettleEngine`, `OpenlyTxn`, `TxnInspect`. Exactness proven on-chain (pot 25 → 22.5 / 2.5). The
UI slider uses a double only to pick a position, then snaps to the 0.01 `BigDecimal` grain before
anything is committed.

## 3 — Proposition text used as a spoofable key — CLOSED (HIGH)

Everything is keyed on the 32-byte **nonce** (state port 9), pinned on-chain and immutable across
fill and refresh. The inbound sink (`MainActivity.onCommsMessage`) resolves the bet by nonce from my
own chain view, then requires the sender's publicId to equal the **on-chain pinned** commsid of a
party (`ownercommsid`/`countercommsid`/`arbcommsid`, ports 10/16/11) before storing or acting.
Proposition text is never a key. Dedup is a PRIMARY KEY + `INSERT OR IGNORE` (no check-then-insert
race).

## 4 — Filler can rewrite the proposition on fill — CLOSED (HIGH)

The V4 contract pins the entire owner-set region with `ASSERT SAMESTATE(0 11)` at fill; proven
on-chain that a fill rewriting the proposition (or forcing refreshcount, or under-funding the pot)
is rejected (`tests/RESULTS-v4.md`, group D). The refresh leaf pins `SAMESTATE(0 16)`.

## 5 — Single-slot pending + dedup races — CLOSED (MEDIUM)

No global pending slot: the proposer exports and immediately `txndelete`s (no lingering node txn);
proposal state lives in the `proposals` table keyed `(nonce, direction)`. Messages dedup on the
`randomid` PRIMARY KEY. Every txn builder routes through `CmdChain` (txndelete on any failure) and
`SignGate` (serial signing — prevents Winternitz one-time-key reuse).

## Residual notes

- The SETTLE_PROPOSE `txnsha3` is transport-integrity only (a malicious proposer controls both hex
  and hash); the real protection against a malicious proposer is checks 1/3/4, which recompute the
  expected outputs independently. This is by design and documented in `CoSigner`.
- `gatePass` fails closed if `txncheck`'s JSON field names differ from expectation.
- Arbiter trust is social, not cryptographic (the contract enforces `arb ≠ owner`, `arb ≠ counter`,
  but cannot stop a sock-puppet arbiter). The fill screen surfaces the full arbiter identity.

Verdict: the five findings are closed in code and, where they touch the chain, proven on-chain.
