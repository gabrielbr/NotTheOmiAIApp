package app.nottheomi.ai;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Design system after gabriellopes.com: light grey page inside a mint frame, near-black ink,
 * Ubuntu Mono throughout, mint "highlighter" behind key words, coral for recording and
 * destructive states, 4dp button corners and 10dp panel corners.
 */
final class Ui {
    static final int BG = 0xFFF2F2F2, SURFACE = 0xFFFFFFFF, INK = 0xFF17161A, MUTED = 0xFF5E5D63,
            LINE = 0xFFE2E2E4, MINT = 0xFF43F3B7, MINT_INK = 0xFF033423, CORAL = 0xFFE9554D,
            CORAL_TEXT = 0xFFB8322A, ON_DARK = 0xFFFFFFFF, ON_DARK_MUTED = 0xFFB9B8BE;
    enum Style { PRIMARY, DARK, QUIET, DANGER, RECORDING }

    private static Typeface regular, bold, boldItalic;

    private Ui() { }

    static int dp(Context c, float value) {
        return Math.round(c.getResources().getDisplayMetrics().density * value);
    }

    static Typeface font(Context c, boolean strong) {
        if (regular == null) {
            try {
                regular = c.getResources().getFont(R.font.ubuntu_mono);
                bold = c.getResources().getFont(R.font.ubuntu_mono_bold);
                boldItalic = c.getResources().getFont(R.font.ubuntu_mono_bold_italic);
            } catch (RuntimeException missing) {
                regular = Typeface.MONOSPACE;
                bold = boldItalic = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            }
        }
        return strong ? bold : regular;
    }

    /** Root page: grey canvas inside the signature mint frame. */
    static LinearLayout page(Activity a) {
        a.getWindow().setStatusBarColor(SURFACE);
        // Light navigation-bar icons need API 27; keep the dark bar on API 26.
        a.getWindow().setNavigationBarColor(android.os.Build.VERSION.SDK_INT >= 27 ? SURFACE : INK);
        LinearLayout page = column(a);
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(BG);
        frame.setStroke(dp(a, 4), MINT);
        page.setBackground(frame);
        page.setPadding(dp(a, 4), 0, dp(a, 4), dp(a, 4));
        return page;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static TextView text(Context c, CharSequence value, float sp, int color, boolean strong) {
        TextView v = new TextView(c);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(font(c, strong));
        v.setIncludeFontPadding(false);
        v.setLineSpacing(0, 1.15f);
        return v;
    }

    /** Large title with one word set on the mint highlighter, as on the site's hero. */
    static TextView title(Context c, String value, String highlighted) {
        SpannableString s = new SpannableString(value);
        int start = highlighted == null ? -1 : value.indexOf(highlighted);
        TextView t = text(c, s, 30, INK, false);
        if (start >= 0) {
            font(c, true);
            s.setSpan(new BackgroundColorSpan(MINT), start, start + highlighted.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new FontSpan(boldItalic), start, start + highlighted.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            t.setText(s);
        }
        t.setLetterSpacing(-0.02f);
        return t;
    }

    static GradientDrawable shape(Context c, int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    static Drawable pressable(Context c, Drawable content, int ripple) {
        return new RippleDrawable(ColorStateList.valueOf(ripple), content, shape(c, Color.WHITE, 4));
    }

    static Button button(Context c, String label, Style style, View.OnClickListener click) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTypeface(font(c, true));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(c, 52));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        style(c, b, style);
        b.setOnClickListener(click);
        return b;
    }

    static void style(Context c, Button b, Style style) {
        int bg, fg;
        switch (style) {
            case PRIMARY: bg = MINT; fg = MINT_INK; break;
            case DARK: bg = INK; fg = Color.WHITE; break;
            case RECORDING: bg = CORAL; fg = Color.WHITE; break;
            case DANGER: bg = Color.TRANSPARENT; fg = CORAL_TEXT; break;
            default: bg = Color.TRANSPARENT; fg = INK; break;
        }
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{(fg & 0x00FFFFFF) | 0x66000000, fg}));
        Drawable base = shape(c, bg, 4);
        b.setBackground(pressable(c, base, bg == Color.TRANSPARENT || bg == INK ? 0x3343F3B7 : 0x33000000));
        if (style == Style.QUIET || style == Style.DANGER) b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        else b.setGravity(Gravity.CENTER);
    }

    static void icon(Context c, Button b, int drawable, int tint) {
        Drawable d = c.getDrawable(drawable);
        if (d == null) return;
        d = d.mutate();
        d.setTint(tint);
        int size = dp(c, 20);
        d.setBounds(0, 0, size, size);
        b.setCompoundDrawablesRelative(d, null, null, null);
        b.setCompoundDrawablePadding(dp(c, 10));
    }

    static ImageButton iconButton(Context c, int drawable, String description, int tint, View.OnClickListener click) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(drawable);
        b.setImageTintList(ColorStateList.valueOf(tint));
        b.setContentDescription(description);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x3343F3B7), null, null));
        b.setOnClickListener(click);
        int pad = dp(c, 12);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }

    /** Status chip, only for real state such as Recording or Refining. */
    static TextView chip(Context c, String value, boolean alert) {
        TextView t = text(c, value, 13, alert ? Color.WHITE : MINT_INK, true);
        t.setBackground(shape(c, alert ? CORAL : MINT, 4));
        t.setPadding(dp(c, 8), dp(c, 3), dp(c, 8), dp(c, 3));
        return t;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, dp(c, 1))));
        return v;
    }

    static void gap(LinearLayout target, int dp) {
        View v = new View(target.getContext());
        target.addView(v, new LinearLayout.LayoutParams(1, dp(target.getContext(), dp)));
    }

    /** App header: black monogram block (like the site's logo) and the app name. */
    static LinearLayout header(Context c, View trailing) {
        LinearLayout bar = row(c);
        bar.setBackgroundColor(SURFACE);
        bar.setPadding(dp(c, 16), dp(c, 10), dp(c, 4), dp(c, 10));
        TextView mark = text(c, "OT_", 14, Color.WHITE, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackgroundColor(INK);
        bar.addView(mark, new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        TextView name = text(c, "Omi Tarefas", 19, INK, true);
        name.setPadding(dp(c, 12), 0, 0, 0);
        bar.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) bar.addView(trailing, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        return bar;
    }

    /** Typeface span that works on API 26 (TypefaceSpan(Typeface) needs API 28). */
    private static final class FontSpan extends android.text.style.MetricAffectingSpan {
        private final Typeface face;
        FontSpan(Typeface face) { this.face = face; }
        @Override public void updateDrawState(android.text.TextPaint p) { p.setTypeface(face); }
        @Override public void updateMeasureState(android.text.TextPaint p) { p.setTypeface(face); }
    }

    /** Back arrow bar for secondary screens. */
    static LinearLayout backBar(Activity a, View trailing) {
        LinearLayout bar = row(a);
        bar.setBackgroundColor(SURFACE);
        bar.setPadding(dp(a, 4), dp(a, 4), dp(a, 4), dp(a, 4));
        bar.addView(iconButton(a, R.drawable.ic_back, "Back", INK, v -> a.onBackPressed()),
                new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        bar.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
        if (trailing != null) bar.addView(trailing, new LinearLayout.LayoutParams(dp(a, 48), dp(a, 48)));
        return bar;
    }
}
