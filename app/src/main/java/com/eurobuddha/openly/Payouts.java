package com.eurobuddha.openly;

import java.math.BigDecimal;

/**
 * Central payout math for a settled bet — the money that lands at MY address per settlement path.
 * Mirrors the contract exactly (see {@link Num} + the V4 leaves). Reused by {@link SettleResult}
 * (the payoff reveal) and {@link MainActivity} (history rows + classifying a bet that settled
 * without my local action) so the numbers can never diverge.
 *
 * Paths: SELF (2-of-2, 0% fee) · ARBITER (winner gets pot−10%, loser forfeits everything) ·
 * TIMEOUT (both stakes refunded). "Money out" is always my own lock; moneyIn varies by path/outcome.
 */
final class Payouts {

    private Payouts() {}

    static BigDecimal ownerLock(Bet b) { return b.ownerstake.signum() > 0 ? b.ownerstake : b.amount; }

    static BigDecimal myLock(Bet b) {
        BigDecimal ol = ownerLock(b);
        return b.isMine ? ol : Num.sub(b.amount, ol);
    }

    static BigDecimal theirLock(Bet b) {
        BigDecimal ol = ownerLock(b);
        return b.isMine ? Num.sub(b.amount, ol) : ol;
    }

    /** My betting side (1 TRUE / 0 FALSE), owner or counter. */
    static int mySide(Bet b) {
        boolean iAmOwner = b.isMine;
        return (b.side == 1 && iAmOwner) || (b.side == 0 && !iAmOwner) ? 1 : 0;
    }

    static BigDecimal fee(Bet b) { return Num.fee(b.amount); }

    /** Money that comes IN to me for (outcome, path). outcome: 0 FALSE · 1 TRUE · 2 VOID. */
    static BigDecimal moneyIn(Bet b, int outcome, String path) {
        BigDecimal pot = b.amount;
        boolean won = outcome != 2 && outcome == mySide(b);
        if (SettleResult.TIMEOUT.equals(path)) return myLock(b);          // refund
        if (SettleResult.ARBITER.equals(path)) {
            return won ? Num.sub(pot, fee(b)) : BigDecimal.ZERO;          // loser forfeits everything
        }
        // SELF (0% fee)
        if (outcome == 2) return myLock(b);                              // void → my stake back
        if (won) return Num.sub(pot, Num.loserEscrow(theirLock(b)));     // pot minus loser's returned escrow
        return Num.loserEscrow(myLock(b));                              // loser recovers own escrow
    }

    /** Net balance change = moneyIn − myLock. */
    static BigDecimal net(Bet b, int outcome, String path) {
        return Num.sub(moneyIn(b, outcome, path), myLock(b));
    }
}
