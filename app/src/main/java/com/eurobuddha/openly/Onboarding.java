package com.eurobuddha.openly;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Three cinematic first-run slides (ported from the dapp's onboarding), shown once. Each is a
 * full-bleed vertical-gradient panel with a glyph, title, body and a capsule CTA. Kept lightweight —
 * the radial-glow drift and 280ms crossfade are Phase-9-plus polish.
 */
public class Onboarding {

    private static final String PREFS = "openly_onboard";
    private static final String KEY = "done";

    private static final int[][] GRAD = {
            {0xFF0D0D1A, 0xFF2D1B69},   // violet
            {0xFF0A1F1C, 0xFF14705C},   // teal
            {0xFF1A0A12, 0xFF6B2D4A},   // magenta
    };
    private static final String[] GLYPH = {"◎", "⚖", "▣"};
    private static final String[] TITLE = {
            "Propose anything.\nBet anyone.\nTrust no one.",
            "Skin in the game.",
            "No house.\nNo middleman.\nNo loophole.",
    };
    private static final String[] BODY = {
            "The smart contract holds everything until reality decides. No operator can freeze your funds. No counterparty can walk away.",
            "Both sides stake 125% of the bet. The extra 25% is honesty insurance — held in escrow to encourage truthful declarations.",
            "Self-settle and keep everything — 0% fee. Disagree? An arbiter you chose decides for 10%. Runs on Minima — the chain on your phone.",
    };
    private static final String[] CTA = {"Next", "Next", "Start betting"};

    public static void showIfNeeded(MainActivity act, FrameLayout host) {
        SharedPreferences p = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean(KEY, false)) return;
        render(act, host, p, 0);
    }

    private static void render(MainActivity act, FrameLayout host, SharedPreferences p, int step) {
        host.removeAllViews();
        if (step >= TITLE.length) {
            p.edit().putBoolean(KEY, true).apply();
            host.setVisibility(View.GONE);
            return;
        }
        host.setVisibility(View.VISIBLE);
        LinearLayout slide = new LinearLayout(act);
        slide.setOrientation(LinearLayout.VERTICAL);
        slide.setGravity(Gravity.CENTER);
        int pad = Ui.dp(act, 32);
        slide.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, GRAD[step]);
        slide.setBackground(bg);

        TextView brand = Ui.text(act, "Openly", 0xFFFFFFFF, 22, true);
        brand.setGravity(Gravity.CENTER);
        slide.addView(brand);

        TextView glyph = Ui.text(act, GLYPH[step], 0xFFFFFFFF, 56, false);
        glyph.setGravity(Gravity.CENTER);
        Ui.topMargin(glyph, Ui.dp(act, 24));
        slide.addView(glyph);

        TextView title = Ui.text(act, TITLE[step], 0xFFFFFFFF, 26, true);
        title.setGravity(Gravity.CENTER);
        Ui.topMargin(title, Ui.dp(act, 20));
        slide.addView(title);

        TextView body = Ui.text(act, BODY[step], 0xCCFFFFFF, 14, false);
        body.setGravity(Gravity.CENTER);
        Ui.topMargin(body, Ui.dp(act, 16));
        slide.addView(body);

        TextView cta = Ui.text(act, CTA[step], 0xFF0B0912, 15, true);
        cta.setGravity(Gravity.CENTER);
        cta.setBackground(Design.roundBg(act, 0xFFFFFFFF, 999));
        cta.setPadding(Ui.dp(act, 40), Ui.dp(act, 14), Ui.dp(act, 40), Ui.dp(act, 14));
        Ui.topMargin(cta, Ui.dp(act, 32));
        Design.pressable(cta);
        cta.setOnClickListener(v -> render(act, host, p, step + 1));
        slide.addView(cta);

        host.addView(slide, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}
