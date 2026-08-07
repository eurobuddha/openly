package com.eurobuddha.openly;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A tiny deterministic identicon (design spec §6.10): a 5×5 horizontally-mirrored cell grid seeded
 * from an identity string (pubkey/address), coloured from a fixed brand-hue ramp. Same identity →
 * same badge, so a poster/counterparty is recognisable at a glance. Pure Canvas, no assets.
 */
public class IdentityBadge extends View {

    private final int[] on = new int[25];   // 5×5, row-major
    private final Paint cell = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final int px;

    public IdentityBadge(Context c, String seed, int sizeDp) {
        super(c);
        px = (int) (sizeDp * c.getResources().getDisplayMetrics().density);
        long h = fnv(seed == null ? "" : seed);
        int[] ramp = { Design.ACCENT(), Design.TRUE_C(), Design.FALSE_C(), Design.GOLD(), Design.WARN(), Design.GRAD_START() };
        cell.setColor(ramp[(int) Math.floorMod(h, ramp.length)]);
        bg.setColor(Design.SURFACE2());
        long bits = h;
        for (int y = 0; y < 5; y++) {
            for (int x = 0; x < 3; x++) {              // left three columns…
                boolean set = (bits & 1L) == 1L; bits >>= 1;
                on[y * 5 + x] = set ? 1 : 0;
                on[y * 5 + (4 - x)] = set ? 1 : 0;     // …mirrored to the right two
            }
        }
    }

    @Override protected void onMeasure(int w, int h) { setMeasuredDimension(px, px); }

    @Override protected void onDraw(Canvas cv) {
        float rad = px * 0.22f;
        r.set(0, 0, px, px);
        cv.drawRoundRect(r, rad, rad, bg);
        float pad = px * 0.14f, grid = px - pad * 2f, s = grid / 5f;
        for (int y = 0; y < 5; y++)
            for (int x = 0; x < 5; x++)
                if (on[y * 5 + x] == 1)
                    cv.drawRect(pad + x * s, pad + y * s, pad + (x + 1) * s, pad + (y + 1) * s, cell);
    }

    /** FNV-1a 64-bit — deterministic, good spread for short hex ids. */
    private static long fnv(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) { h ^= s.charAt(i); h *= 0x100000001b3L; }
        return h;
    }
}
