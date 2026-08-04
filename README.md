# Openly — native

Peer-to-peer proposition betting on Minima, as a native MinimaCore companion app
(`com.eurobuddha.openly`). Propose anything, bet anyone, trust no one: the contract escrows both
stakes, parties self-settle 2-of-2 for 0% fee, or a chosen arbiter resolves for 10%.

Native rebuild of the `mds/Wager` MiniDapp on a hardened **V4 contract**. Both stakes lock at
bet × 1.25 (the extra 25% is "honesty escrow", returned on an honest declaration).

## Architecture

- **Contract** (`OpenlyContract.java`): pinned V4 script + 3 MAST leaves (refresh / timeout / void),
  root/proofs/address generated and proven on-chain by `mds/Wager/tests/test_v4.py`. Owner-set state
  is ports 0–11, pinned at fill by one `SAMESTATE(0 11)`; stable 32-byte nonce (port 9) is the bet id.
- **Money** (`Num.java`): exact `BigDecimal` `MathContext(64, DOWN)` mirroring the KISS VM. No floats.
- **Chain read** (`BetCoin`, `Bet`, `BetScanner`): board scanned per block; chain is source of truth.
- **Transactions** (`OpenlyTxn`): post / cancel / fill / buildSettle / resolve / timeout / refresh,
  all via `CmdChain` (txndelete on failure) behind `SignGate` (serial signing).
- **Settlement** (`SettleEngine`, `CoSigner`, `TxnInspect`): the security-critical path. A proposer
  signs + exports a payout txn; the acceptor runs a 7-point validation against its own chain view
  before ever signing. No auto-cosign. See `SECURITY-REVIEW.md`.
- **Messaging** (`com.eurobuddha.comms` + `OpenlyComms`): sealed X25519/Ed25519 blobs in state 99 of
  1-nano coins on a shared channel; identity HKDF-derived from the vault seed. Senders authenticated
  against the on-chain pinned commsid.
- **Background** (`AutoProcessor`, `OpenlyService`): refresh matched bets inside the visibility
  horizon; claim timeout on flagged bets. Foreground service defers to the Activity.
- **Design** (`Design.java`): NOIR/DAYBREAK token engine forked from atomix; TRUE=teal, FALSE=magenta,
  gold=arbiter/escrow/win. Inter + JetBrains Mono.

## Build

```
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```
JDK is pinned to the Android Studio JBR in `gradle.properties` (system JDK can't jlink android-36).
IPC is authorised by the MINIMA_ID token, so the debug keystore is used for release.

## Status

Core flow complete and building: markets board, post, counter/fill, self-settle (with CoSigner
validation), arbiter resolve, timeout + refresh, comms, foreground service, onboarding. The V4
contract and the security-critical settlement path (including the tamper backstop) are proven
on-chain; arbiter/void/timeout leaf paths mirror proven structures and are pending a clean
cross-node harness run. Deferred: per-bet chat, history view, the sealed-envelope reveal and
SettleOverlay/Sfx polish from the design spec.
