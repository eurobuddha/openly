# Maxima transport for Openly — proposal & discussion

> **Status: PROPOSAL, not approved for build.** Written 2026-08-16 during the Salon/Maxima work,
> for the Openly agent to weigh later. The owner is **not currently convinced it's worthwhile** —
> this is a design discussion to inform that decision, not a build order. Mail is deliberately
> on-chain forever (it *is* "the other transport"); AtomiX is off-limits (complex/fragile); shops
> are a later conversation. Openly is the candidate we're thinking about first.

## TL;DR
Openly's money and trust are on-chain and stay there. The only thing Maxima would touch is the
**1:1 comms channel between two matched parties** (`OpenlyComms` / `OpenlyMessage`), which today
posts every message as a sealed 1-nano coin scanned per block. Maxima would make that exchange
**seconds instead of block-times** and stop bloating the chain — **without weakening settlement
security**, because the trust boundary is the sealed+signed envelope + on-chain validation, not the
pipe. Recommendation: **dual-path (Maxima + on-chain backstop) for money-adjacent messages,
Maxima-only for the (unbuilt) per-bet chat.** The one real design question is mapping a peer's
on-chain `commsid` → their Maxima `mxaddr`; recommended answer is "carry it in the payload and learn
it on receipt," with no change to the proven contract.

## What Openly does today (only the parts this touches)

**Untouched by any Maxima work — on-chain, security-critical:**
- The V4 escrow contract (`OpenlyContract.java`), the board (`BetScanner` reads bets per block),
  and settlement (`SettleEngine` → `CoSigner` 7-point validation → **no auto-cosign**; see
  `SECURITY-REVIEW.md`). Money and trust live on-chain. **Maxima never goes near this.**

**The comms layer — the candidate (`OpenlyComms.java`, `OpenlyMessage.java`, shared
`com.eurobuddha.comms.CommsTransport`):**
- A **1:1 exchange between the two matched parties**. Each party's comms identity (`commsid` =
  X25519‖Ed25519 box/sign keys, HKDF-derived from the vault seed) is **pinned on-chain in the bet
  state** (ports 10/11/16).
- Every message is a **sealed (`crypto_box_seal`) + Ed25519-signed blob in `state[99]` of a 1-nano
  coin**, posted to either the shared `MAIL_ADDR` (`0x4F50454E4C59` = "OPENLY") or a per-bet
  `settleAddr(nonce)`. The receiver scans that address per block, opens the seal, and **authenticates
  the sender against the pinned commsid** before acting.
- Message types (`OpenlyMessage`): `SETTLE_PROPOSE`, `SETTLE_TXN` (the ~14 KB partially-signed payout
  hex — already ONE coin via `storestate:true`; the old 16-way chunking is gone), `SETTLE_ACCEPT` /
  `SETTLE_REJECT`, `DISPUTE` / `DISPUTE_WITHDRAW`, `ARB_RESULT`, and `CHAT` (deferred / unbuilt).
- Each message already carries a `randomid` used for dedup.

**Key insight:** the trust boundary is **seal + Ed25519 signature + on-chain-pinned commsid auth +
the acceptor's 7-point on-chain validation** — *not the transport*. The pipe only carries a sealed,
self-authenticating envelope. So changing the pipe cannot, by itself, weaken settlement security,
**provided the envelope and the validation stay exactly as they are.**

## Wins (what Maxima buys Openly)
- **Latency.** A self-settle round-trip today is several block-times (propose → 14 KB txn → accept,
  each a coin mined + then scanned). Over Maxima it's **seconds**. Headline win.
- **No chain bloat / no message coins.** Settlement, dispute, and chat stop minting 1-nano coins on
  `MAIL_ADDR` / `settleAddr`. Only the bet and the payout stay on-chain, as they must.
- **Chat becomes basically free.** The deferred per-bet `CHAT` turns into a trivial real-time
  feature.
- **Same envelope, no re-crypto.** The exact `OpenlyMessage.toWire()` sealed+signed blob rides
  Maxima unchanged; ~14 KB fits inline in one Maxima message (262 KB wire ceiling — media chunking
  never even engages).

## Losses / risks (honest)
- **Delivery guarantee weakens if done naively.** On-chain messages are durable: they sit at the
  address until scanned, so they *always* eventually arrive. A Maxima message to an offline peer
  relies on the relay mailbox, and if Maxima is uninstalled/unapproved it doesn't arrive at all. For
  a money-adjacent message that would otherwise stall a settlement, that's unacceptable **as a sole
  path**.
- **New dependency + friction.** Openly has ZERO Maxima dependency today. Adding it means the Maxima
  app must be installed and approved (the same IPC-approval friction the Salon has). Not every user
  will have it.
- **Address discovery.** Routing needs the peer's `mxaddr`, but Openly only pins their `commsid`
  on-chain.
- **It's a fragile, security-critical app.** Even a transport-only change is a change to a sensitive
  path; the bar for "prove settlement is untouched" is high.

## Maxima-only vs both — recommendation: split by message type
- **Money-adjacent (`SETTLE_*`, `DISPUTE_*`, `ARB_RESULT`): BOTH (dual-path).** Maxima-first for
  speed, **on-chain as the guaranteed backstop** so a settlement can NEVER stall because Maxima was
  unavailable. This strictly *adds* speed while preserving today's delivery guarantee. Dedup on the
  existing `randomid` so a message arriving over both pipes lands once (this is exactly the
  stable-id dedup pattern the Salon shipped).
- **`CHAT`: Maxima-only.** Unbuilt and non-critical — if a chat line is lost, no funds are at risk.
  A clean, cheap, real-time greenfield add, and a **low-risk place to prove the integration** before
  trusting it with settlement.

## Proposed approach (reuse the Salon's shipped work)
1. **Transport seam.** `OpenlyComms.send()` / `sendTo()` already isolate "seal → post to address."
   Add a parallel `MaximaLink` path (lift the Salon's `MaximaLink` IPC client + delivery-outbox +
   in-flight guard verbatim from `apks/salon`) and choose per-type: dual-path for settle/dispute,
   Maxima-only for chat. The sealed `OpenlyMessage` blob is identical on either pipe.
2. **commsid → mxaddr mapping (the one real design question).**
   - **(A) Carry mxaddr in the payload + learn it on receipt** — the Salon's model. Stamp the
     sender's `mxaddr` into `OpenlyMessage`; the first hop (which the dual-path sends on-chain
     anyway) teaches the peer the return `mxaddr`, and everything after goes Maxima. **No contract
     change. Recommended.**
   - **(B) Pin `mxaddr` on-chain in bet state** alongside `commsid`. Both sides know it immediately,
     but it's a state/contract-surface change to a *proven* contract (higher risk), and `mxaddr`
     churns as relays change while on-chain state doesn't. **Not recommended.**
3. **Keep the envelope + validation byte-identical.** No change to seal/sign/commsid-auth or the
   `CoSigner` 7-point validation. Maxima is a pipe only.
4. **Order of work.** Ship Maxima-only `CHAT` first, prove it two-device live, *then* layer the
   dual-path onto settle/dispute behind the on-chain backstop.

## Open questions for whoever picks this up
- Is `randomid` dedup robust enough to collapse a dual-path (Maxima + on-chain) duplicate at **every**
  sink, or does any handler assume exactly one delivery?
- Does anything downstream depend on a settlement message being an actual on-chain coin — e.g.
  `OpenlyComms.deepRescan`, or the `AutoProcessor` timeout/refresh horizon — such that a Maxima-only
  hop would break a recovery path?
- Is "Maxima for the *fast* path, on-chain for *correctness* (always works)" the right contract?
- Any objection to option (A) (mxaddr in payload, learned) over a contract-surface change?

## Verification (if it's ever built)
- Two devices: propose → settle over Maxima end-to-end in seconds; then **kill Maxima mid-settle** and
  confirm the on-chain backstop still completes it (no stall, no double-apply — dedup by `randomid`).
  Offline peer → Maxima mailbox drain. Chat live both directions.
- **Diff the settlement/dispute envelopes byte-for-byte against pre-change** to prove the security
  path is untouched; re-run the `SettleEngine` / `CoSigner` validation harness.
- Confirm `MAIL_ADDR` / `settleAddr` coin counts stop growing per message once Maxima carries the fast
  path.

## Reference: the Salon's shipped Maxima integration (the reusable pattern)
`apks/salon` (`com.eurobuddha.salon`) did exactly this migration for social DMs — dual-path
(Maxima-first, on-chain fallback), `mxaddr` two-way learning (stamp in payload, learn on intake),
a delivery **outbox + status** with an in-flight guard, stable-id dedup across transports, and a
per-contact transport-override chip. See `apks/salon/app/src/main/java/com/eurobuddha/salon/`:
`MaximaLink.java`, `MaximaLinkReceiver.java`, `sendDm`/`retryOutbox` in `MainActivity.java`,
`SalonNotifyReceiver.intakeDm`. The Maxima transport + relay swarm live in `maxima/` (app `0.4.7`,
Salon `0.11.9` at time of writing).
