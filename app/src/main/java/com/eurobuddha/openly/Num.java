package com.eurobuddha.openly;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Exact money math — bit-for-bit with the KISS VM.
 *
 * Verified from core MiniNumber.java: every on-chain number is a BigDecimal under
 * MathContext(64, RoundingMode.DOWN), max 44 decimal places. The contract's only
 * divisions are /5 and /10, which always terminate in base-10, so the same
 * MathContext here reproduces the VM exactly. float/double are BANNED for money —
 * the V3.1 settlement "brick" was purely a JS Math.floor(x*1e8)/1e8 artifact.
 *
 * lock  = stake * 1.25   (25% honesty escrow on top of the bet)
 * le    = loserLock / 5  (loser recovers 20% of their lock; the rest is the winner's)
 * fee   = pot / 10       (arbiter's cut)
 */
public final class Num {

    public static final MathContext MC = new MathContext(64, RoundingMode.DOWN);
    private static final BigDecimal ESCROW = new BigDecimal("1.25");
    private static final BigDecimal FIVE = new BigDecimal("5");
    private static final BigDecimal TEN = new BigDecimal("10");
    public static final BigDecimal GRAIN = new BigDecimal("0.01");
    public static final BigDecimal MIN_STAKE = new BigDecimal("0.1");

    private Num() {}

    public static BigDecimal of(String s) { return new BigDecimal(s, MC); }
    public static BigDecimal of(BigDecimal b) { return b.round(MC); }

    /** Total locked for a given bet stake. */
    public static BigDecimal lock(BigDecimal stake) { return stake.multiply(ESCROW, MC); }

    /** Displayed stake from a total lock (lock * 0.8, exact). */
    public static BigDecimal stakeOfLock(BigDecimal lock) { return lock.divide(ESCROW, MC); }

    /** Loser escrow returned = loserLock / 5. */
    public static BigDecimal loserEscrow(BigDecimal loserLock) { return loserLock.divide(FIVE, MC); }

    /** Arbiter fee = pot / 10. */
    public static BigDecimal fee(BigDecimal pot) { return pot.divide(TEN, MC); }

    public static BigDecimal add(BigDecimal a, BigDecimal b) { return a.add(b, MC); }
    public static BigDecimal sub(BigDecimal a, BigDecimal b) { return a.subtract(b, MC); }

    /** True if v is a positive multiple of the 0.01 grain and >= the 0.1 minimum. */
    public static boolean validStake(BigDecimal v) {
        if (v == null || v.compareTo(MIN_STAKE) < 0) return false;
        BigDecimal r = v.remainder(GRAIN, MC).stripTrailingZeros();
        return r.compareTo(BigDecimal.ZERO) == 0;
    }

    /** MiniNumber-style string: exact, trailing zeros trimmed, never scientific. */
    public static String plain(BigDecimal v) {
        if (v == null) return "0";
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) s = s.setScale(0);
        return s.toPlainString();
    }

    public static boolean eq(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }
}
