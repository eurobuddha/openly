package com.eurobuddha.openly;

import android.widget.ScrollView;
import android.widget.TextView;
import android.view.Gravity;

/**
 * Markets tab — placeholder shell (Phase 1). Real content arrives in Phase 2.
 * Programmatic view (family pattern: no per-screen layout XML for list screens).
 */
public class MarketsView extends BaseView {

    private final TextView body;

    public MarketsView(MainActivity a) {
        super(a, makeRoot(a));
        body = (TextView) root;
    }

    private static int makeRoot(MainActivity a) {
        return android.R.layout.simple_list_item_1; // temporary host; replaced in Phase 2
    }

    @Override public void refresh() {
        if (body == null) return;
        body.setGravity(Gravity.CENTER);
        body.setText("Markets\n\nOpenly ready\nblock #" + act.block()
                + "\nbalance " + act.balance
                + "\ncontract " + shortAddr(act.contractAddr));
    }

    private static String shortAddr(String a) {
        if (a == null || a.length() < 14) return String.valueOf(a);
        return a.substring(0, 10) + "…" + a.substring(a.length() - 4);
    }
}
