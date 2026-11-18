package com.acodext;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int BG = Color.rgb(16, 18, 22), PANEL = Color.rgb(29, 33, 39), TEXT = Color.rgb(230, 234, 241), MUTED = Color.rgb(161, 170, 184), GREEN = Color.rgb(166, 227, 161);
    static int dp(Activity a, float n) { return Math.round(n * a.getResources().getDisplayMetrics().density); }
    static TextView text(Activity a, String text, int size, int color) {
        TextView v = new TextView(a); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v;
    }
    static GradientDrawable background(int color, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(radius); return d;
    }
    static Button button(Activity a, String title, boolean primary, View.OnClickListener click) {
        Button b = new Button(a); b.setText(title); b.setAllCaps(false); b.setTextColor(primary ? BG : TEXT);
        b.setTextSize(14); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); b.setMinHeight(dp(a, 44));
        b.setBackground(background(primary ? GREEN : PANEL, dp(a, 10))); b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(a, 48)); lp.topMargin = dp(a, 10); b.setLayoutParams(lp); return b;
    }
}
