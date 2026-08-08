package com.eurobuddha.openly;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Local store. Chain is the source of truth for live coins; this DB owns message history,
 * proposal state, and comms bookmarks. Dedup is enforced by PRIMARY KEY + INSERT_OR_IGNORE
 * (no check-then-insert race). Single writer via SQLiteDatabase's own locking.
 */
public class OpenlyDb extends SQLiteOpenHelper {

    private static final String NAME = "openly.db";
    private static final int VERSION = 4;   // v2: history · v3: path + money · v4: history tokenid (dual token)

    public OpenlyDb(Context c) {
        super(c, NAME, null, VERSION);
        // WAL: the comms router must get a synchronous "is-new" answer from insertMessageIfNew (a
        // false return makes the scanner re-process the coin every pass), so the dedup insert stays
        // on the calling thread — WAL keeps that write cheap and non-blocking of concurrent reads.
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE messages (" +
                "randomid TEXT PRIMARY KEY, nonce TEXT NOT NULL, type TEXT NOT NULL," +
                "fromid TEXT, toid TEXT, coinid TEXT, outcome INTEGER," +
                "seq INTEGER, total INTEGER, sha3 TEXT, body TEXT, date INTEGER, inbound INTEGER)");
        db.execSQL("CREATE INDEX msg_nonce ON messages(nonce)");
        db.execSQL("CREATE TABLE proposals (" +
                "nonce TEXT NOT NULL, direction TEXT NOT NULL, outcome INTEGER NOT NULL," +
                "txnsha3 TEXT, hex TEXT, winneramt TEXT, loseramt TEXT, state TEXT NOT NULL," +
                "created INTEGER, updated INTEGER, PRIMARY KEY (nonce, direction))");
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT)");
        db.execSQL(HISTORY_DDL);
    }

    // result: 1 WON · 0 LOST · 2 VOID · 3 CANCELLED · 4 REFUNDED(timeout). amount = signed net string.
    // path: SELF · ARBITER · TIMEOUT · CANCEL · VOID — how it terminated (drives the rich History row).
    // moneyin / moneyout: the actual coin amounts that moved (plain strings), so a row can read
    // "arbiter ruled FALSE · +22.5 in · 12.5 out" instead of a bare signed net.
    private static final String HISTORY_DDL =
            "CREATE TABLE IF NOT EXISTS history (nonce TEXT PRIMARY KEY, proposition TEXT, result INTEGER," +
            " amount TEXT, side INTEGER, date INTEGER, path TEXT, moneyin TEXT, moneyout TEXT, tokenid TEXT)";

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        if (o < 2) db.execSQL(HISTORY_DDL);        // v1 → v2 adds history (current DDL already has later cols)
        if (o < 3) {                                // v2 → v3: enrich existing history table
            addColumn(db, "history", "path", "TEXT");
            addColumn(db, "history", "moneyin", "TEXT");
            addColumn(db, "history", "moneyout", "TEXT");
        }
        if (o < 4) addColumn(db, "history", "tokenid", "TEXT");   // v3 → v4: dual-token history label
    }

    /** ALTER ADD COLUMN, tolerant of "column already exists" (SQLite has no IF NOT EXISTS for this). */
    private static void addColumn(SQLiteDatabase db, String table, String col, String type) {
        try { db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + col + " " + type); }
        catch (Exception ignored) {}
    }

    // ---- meta (comms bookmarks) ----
    public String getMeta(String k, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM meta WHERE k=?", new String[]{k});
        try { return c.moveToFirst() ? c.getString(0) : def; } finally { c.close(); }
    }
    public void setMeta(String k, String v) {
        ContentValues cv = new ContentValues();
        cv.put("k", k); cv.put("v", v);
        getWritableDatabase().insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    // ---- messages ----
    /** Insert if new (dedup on randomid). Returns true if a NEW row was stored. */
    public boolean insertMessageIfNew(OpenlyMessage m, boolean inbound) {
        ContentValues cv = new ContentValues();
        cv.put("randomid", m.randomid); cv.put("nonce", m.ref); cv.put("type", m.type);
        cv.put("fromid", m.from); cv.put("toid", m.to); cv.put("coinid", m.coinid);
        cv.put("outcome", m.outcome); cv.put("seq", m.seq); cv.put("total", m.total);
        cv.put("sha3", m.txnsha3); cv.put("body", m.raw); cv.put("date", m.date);
        cv.put("inbound", inbound ? 1 : 0);
        long id = getWritableDatabase().insertWithOnConflict("messages", null, cv,
                SQLiteDatabase.CONFLICT_IGNORE);
        return id != -1;
    }

    public List<String> chunksFor(String nonce, String sha3) {
        List<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT body FROM messages WHERE nonce=? AND sha3=? AND type='SETTLE_TXN' ORDER BY seq ASC",
                new String[]{nonce, sha3});
        try { while (c.moveToNext()) out.add(c.getString(0)); } finally { c.close(); }
        return out;
    }

    // ---- proposals ----
    public void upsertProposal(String nonce, String direction, int outcome, String txnsha3,
                               String hex, String winnerAmt, String loserAmt, String state, long now) {
        ContentValues cv = new ContentValues();
        cv.put("nonce", nonce); cv.put("direction", direction); cv.put("outcome", outcome);
        cv.put("txnsha3", txnsha3); cv.put("hex", hex);
        cv.put("winneramt", winnerAmt); cv.put("loseramt", loserAmt);
        cv.put("state", state); cv.put("updated", now);
        // preserve created if present
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT created FROM proposals WHERE nonce=? AND direction=?", new String[]{nonce, direction});
        long created = now;
        try { if (c.moveToFirst()) created = c.getLong(0); } finally { c.close(); }
        cv.put("created", created);
        getWritableDatabase().insertWithOnConflict("proposals", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** The agreed outcome from a non-rejected proposal for this bet (self-settle), or null. Persisted,
     *  so it survives a restart mid-settlement — used to classify a bet that settled while I was away. */
    public Integer proposalOutcome(String nonce) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT outcome FROM proposals WHERE nonce=? AND state!='REJECTED' ORDER BY updated DESC LIMIT 1",
                new String[]{nonce});
        try { return c.moveToFirst() ? c.getInt(0) : null; } finally { c.close(); }
    }

    /** The outcome I declared (an OUT proposal I sent), if any — drives the "DECLARED — awaiting them"
     *  live-card state. Null once REJECTED (I disagreed), ABANDONED (a co-sign already settled it), or
     *  if I never declared — so a settled bet doesn't re-render as still awaiting after a restart. */
    public Integer myDeclaredOutcome(String nonce) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT outcome FROM proposals WHERE nonce=? AND direction='OUT' " +
                "AND state!='REJECTED' AND state!='ABANDONED' LIMIT 1",
                new String[]{nonce});
        try { return c.moveToFirst() ? c.getInt(0) : null; } finally { c.close(); }
    }

    public Proposal inboundProposal(String nonce) {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT outcome,txnsha3,hex,winneramt,loseramt,state FROM proposals " +
                "WHERE nonce=? AND direction='IN' AND state='RECEIVED'", new String[]{nonce});
        try {
            if (!c.moveToFirst()) return null;
            Proposal p = new Proposal();
            p.nonce = nonce; p.outcome = c.getInt(0); p.txnsha3 = c.getString(1);
            p.hex = c.getString(2); p.winnerAmt = c.getString(3); p.loserAmt = c.getString(4);
            p.state = c.getString(5);
            return p;
        } finally { c.close(); }
    }

    /** All chunks reassembled + sha3-verified: store the full hex and mark RECEIVED (Agree can show). */
    public void setProposalHexReceived(String nonce, String hex, long now) {
        ContentValues cv = new ContentValues();
        cv.put("hex", hex); cv.put("state", "RECEIVED"); cv.put("updated", now);
        getWritableDatabase().update("proposals", cv, "nonce=? AND direction='IN'", new String[]{nonce});
    }

    public void setProposalState(String nonce, String direction, String state, long now) {
        ContentValues cv = new ContentValues();
        cv.put("state", state); cv.put("updated", now);
        getWritableDatabase().update("proposals", cv, "nonce=? AND direction=?",
                new String[]{nonce, direction});
    }

    public static class Proposal {
        public String nonce, txnsha3, hex, winnerAmt, loserAmt, state;
        public int outcome;
    }

    // ---- history (terminal bets) ----
    public void addHistory(String nonce, String proposition, int result, String amount, int side,
                           String path, String moneyIn, String moneyOut, String tokenid, long now) {
        ContentValues cv = new ContentValues();
        cv.put("nonce", nonce); cv.put("proposition", proposition); cv.put("result", result);
        cv.put("amount", amount); cv.put("side", side); cv.put("date", now);
        cv.put("path", path); cv.put("moneyin", moneyIn); cv.put("moneyout", moneyOut);
        cv.put("tokenid", tokenid);
        getWritableDatabase().insertWithOnConflict("history", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Persistent settlement dedup — true once a terminal result is recorded for this bet. */
    public boolean hasHistory(String nonce) {
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM history WHERE nonce=? LIMIT 1", new String[]{nonce});
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    public List<HistoryRow> history() {
        List<HistoryRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT nonce,proposition,result,amount,side,date,path,moneyin,moneyout,tokenid " +
                "FROM history ORDER BY date DESC", null);
        try {
            while (c.moveToNext()) {
                HistoryRow h = new HistoryRow();
                h.nonce = c.getString(0); h.proposition = c.getString(1); h.result = c.getInt(2);
                h.amount = c.getString(3); h.side = c.getInt(4); h.date = c.getLong(5);
                h.path = c.getString(6); h.moneyIn = c.getString(7); h.moneyOut = c.getString(8);
                h.tokenid = c.getString(9);
                out.add(h);
            }
        } finally { c.close(); }
        return out;
    }

    public static class HistoryRow {
        public String nonce, proposition, amount, path, moneyIn, moneyOut, tokenid;
        public int result, side;    // result: 1 WON · 0 LOST · 2 VOID · 3 CANCELLED · 4 REFUNDED
        public long date;           // path: SELF · ARBITER · TIMEOUT · CANCEL · VOID
    }

    // ---- chat (per-bet, over the messages table; text lives in the message body JSON) ----
    public List<ChatRow> chatFor(String nonce) {
        List<ChatRow> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT body,inbound,date,fromid FROM messages WHERE nonce=? AND type='CHAT' ORDER BY date ASC",
                new String[]{nonce});
        try {
            while (c.moveToNext()) {
                ChatRow r = new ChatRow();
                r.inbound = c.getInt(1) == 1; r.date = c.getLong(2); r.fromid = c.getString(3);
                OpenlyMessage m = OpenlyMessage.fromWire(
                        c.getString(0).getBytes(java.nio.charset.StandardCharsets.UTF_8), "");
                r.text = (m != null && m.statement != null) ? m.statement : "";
                out.add(r);
            }
        } finally { c.close(); }
        return out;
    }

    public static class ChatRow { public String text, fromid; public boolean inbound; public long date; }
}
