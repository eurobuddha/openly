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
    private static final int VERSION = 1;

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
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) { /* v1 */ }

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
}
