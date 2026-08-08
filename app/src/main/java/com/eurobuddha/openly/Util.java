package com.eurobuddha.openly;

import org.json.JSONObject;

import java.math.BigDecimal;

public final class Util {

    public static final String MINIMA_TOKENID = "0x00";
    /** AtomiX's bridged USDT token (8dp) — the second currency an Openly bet can be denominated in. */
    public static final String MXUSDT_TOKENID = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    private Util() {}

    /** Short currency label for a bet's tokenid — "Minima" / "mxUSDT" / a shortened id. */
    public static String tokenLabel(String tokenid) {
        if (isMinima(tokenid)) return "Minima";
        if (MXUSDT_TOKENID.equalsIgnoreCase(tokenid)) return "mxUSDT";
        return shorten(tokenid);
    }

    /** A Minima address: 0x + exactly 64 hex (32-byte script hash), or Mx + 40–118 alnum.
     *  Matches the dapp's validateAddress; a pre-filter before the node's checkaddress. */
    public static boolean isValidAddress(String a) {
        return a != null && a.matches("^(0x[0-9a-fA-F]{64}|Mx[A-Za-z0-9]{40,118})$");
    }

    /** Number of significant decimal places in an amount (0 for integers). */
    public static int decimalPlaces(BigDecimal bd) {
        return Math.max(0, bd.stripTrailingZeros().scale());
    }

    /** Shorten a long hex id/address for display: 0x1234…ABCD */
    public static String shorten(String s) {
        if (s == null) return "";
        if (s.length() <= 16) return s;
        return s.substring(0, 8) + "…" + s.substring(s.length() - 6);
    }

    public static boolean isMinima(String tokenid) {
        return tokenid == null || MINIMA_TOKENID.equals(tokenid);
    }

    /**
     * Minima "token name" can be a plain string, or a JSON object {name:..,url:..},
     * or (for raw coin entries) nested. Pull a human-readable name out of whatever we get.
     */
    public static String tokenName(Object token, String tokenid) {
        if (isMinima(tokenid)) return "Minima";
        if (token instanceof String) return (String) token;
        if (token instanceof JSONObject) {
            JSONObject t = (JSONObject) token;
            Object name = t.opt("name");
            if (name instanceof JSONObject) {
                return ((JSONObject) name).optString("name", "Token");
            }
            if (name instanceof String && !((String) name).isEmpty()) {
                return (String) name;
            }
        }
        return "Token";
    }

    /** Pull a txpowid out of a posted-transaction response, falling back to the given id. */
    public static String extractTxpowid(JSONObject json, String fallback) {
        JSONObject resp = json.optJSONObject("response");
        if (resp != null) {
            String t = resp.optString("txpowid", "");
            if (t.isEmpty()) {
                JSONObject txp = resp.optJSONObject("txpow");
                if (txp != null) t = txp.optString("txpowid", "");
            }
            if (!t.isEmpty()) return t;
        }
        return fallback;
    }

    /** Trim trailing zeros from a decimal amount string for tidy display. */
    public static String tidyAmount(String amt) {
        if (amt == null || amt.isEmpty()) return "0";
        if (!amt.contains(".")) return amt;
        String s = amt.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s.isEmpty() ? "0" : s;
    }

    /** String → 0x-prefixed uppercase hex of its UTF-8 bytes (proposition text, etc.). Lossless for
     *  any Unicode; post and fill both use this, so the on-chain hex round-trips and SAMESTATE holds. */
    public static String strToHex(String s) {
        if (s == null) return "0x";
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder b = new StringBuilder("0x");
        for (byte x : bytes) b.append(String.format("%02X", x & 0xFF));
        return b.toString();
    }

    /** 0x-hex (with or without prefix) → UTF-8 string. Returns "" on malformed input. */
    public static String hexToStr(String hex) {
        if (hex == null || hex.isEmpty()) return "";
        String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if ((h.length() & 1) != 0) return "";
        try {
            byte[] bytes = new byte[h.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
            }
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (NumberFormatException e) {
            return "";
        }
    }
}
