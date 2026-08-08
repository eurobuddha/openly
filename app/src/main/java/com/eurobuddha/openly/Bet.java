package com.eurobuddha.openly;

import java.math.BigDecimal;
import java.util.Set;

/**
 * A parsed bet — the canonical domain object the whole app reads from.
 * Built from a {@link BetCoin} (live chain state) via {@link #from}. The V4 port
 * layout (pinned in {@link OpenlyContract}):
 *
 *   0 ownerpk   1 owneraddr  2 arbpk    3 arbaddr   4 timeout  5 side  6 wantstake
 *   7 proposition(hex)  8 settleblock  9 nonce  10 ownercommsid  11 arbcommsid
 *   12 phase  13 counterpk  14 counteraddr  15 ownerstake  16 countercommsid  17 refreshcount
 *
 * Stake convention: coin @AMOUNT = owner's total lock (stake × 1.25); port 6 = counter's
 * total lock. Displayed stake = lock × 0.8 (exact, via {@link Num}).
 */
public class Bet {

    public String coinid;
    public String tokenid = "0x00";  // the currency this bet is denominated in (0x00 Minima / mxUSDT / …)
    public String nonce;             // port 9 — stable id across refresh
    public int phase;                // port 12: 0 open, 1 matched
    public BigDecimal amount;        // @AMOUNT = owner lock (phase 0) or full pot (phase 1)

    public String ownerpk, owneraddr, arbpk, arbaddr;
    public int timeout, side;        // side: 1 TRUE / 0 FALSE (owner's side)
    public BigDecimal wantstake;     // counter's required total lock
    public String proposition;       // decoded UTF-8
    public int settleblock;
    public String ownercommsid, arbcommsid;

    public String counterpk, counteraddr, countercommsid;
    public BigDecimal ownerstake;    // port 15 (phase 1) — owner lock captured at fill
    public int refreshcount;

    // roles (computed against my wallet keys)
    public boolean isMine, isMyCounter, isMyArb;
    public int ageBlocks;

    public static Bet from(BetCoin c, Set<String> myKeys, int tipBlock) {
        Bet b = new Bet();
        b.coinid = c.coinid;
        b.tokenid = c.tokenid;
        b.amount = safe(c.amount);
        b.ownerpk = c.at(0);
        b.owneraddr = c.at(1);
        b.arbpk = c.at(2);
        b.arbaddr = c.at(3);
        b.timeout = intOf(c.at(4), 200);
        b.side = intOf(c.at(5), 1);
        b.wantstake = safe(c.at(6));
        b.proposition = Util.hexToStr(c.at(7));
        b.settleblock = intOf(c.at(8), 0);
        b.nonce = c.at(9);
        b.ownercommsid = c.at(10);
        b.arbcommsid = c.at(11);
        b.phase = intOf(c.at(12), 0);
        b.counterpk = c.at(13);
        b.counteraddr = c.at(14);
        b.ownerstake = safe(c.at(15));
        b.countercommsid = c.at(16);
        b.refreshcount = intOf(c.at(17), 0);
        // myKeys are stored upper-cased (BetScanner.loadKeys); compare upper-cased so ownership holds
        // regardless of the hex case the node reports for on-chain state values.
        b.isMine = owns(myKeys, b.ownerpk);
        b.isMyCounter = owns(myKeys, b.counterpk);
        b.isMyArb = owns(myKeys, b.arbpk);
        b.ageBlocks = c.ageFrom(tipBlock);
        return b;
    }

    /**
     * A real bet always pins an owner pubkey (port 0) and a 32-byte nonce (port 9) at post time.
     * A coin at the contract address with neither is a state-less orphan — e.g. a mis-built cancel
     * output that landed back at the contract with empty state. Such a coin is UNSPENDABLE (the
     * script errors on `PREVSTATE Missing: 0` at instruction 1, so no path can ever authorize it),
     * yet it would otherwise render as a phantom "Untitled / TRUE / bet X / win 0" card that no one
     * can cancel. The scanner drops these so they never masquerade as bets.
     */
    public boolean isWellFormed() {
        return ownerpk != null && !ownerpk.isEmpty()
                && nonce != null && !nonce.isEmpty();
    }

    /** Owner's displayed stake (not lock). */
    public BigDecimal ownerBet() {
        BigDecimal lock = phase == 1 && ownerstake.signum() > 0 ? ownerstake : amount;
        return Num.stakeOfLock(lock);
    }

    /** Counter's displayed stake (not lock). */
    public BigDecimal counterBet() {
        return Num.stakeOfLock(wantstake);
    }

    /** Total pot displayed (both stakes, no escrow). */
    public BigDecimal potStakes() {
        return Num.add(ownerBet(), counterBet());
    }

    private static boolean owns(Set<String> keys, String pk) {
        return keys != null && pk != null && !pk.isEmpty() && keys.contains(pk.toUpperCase());
    }

    private static BigDecimal safe(String s) {
        try { return Num.of(s == null || s.isEmpty() ? "0" : s); }
        catch (Exception e) { return BigDecimal.ZERO; }
    }

    private static int intOf(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; }
    }
}
