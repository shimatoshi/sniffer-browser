package net.localhost.sniffer;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Chrome風のメニューパネル（下部バーの上に右寄せで浮く角丸パネル）。
 * 上段にアイコンだけの操作列、下に「アイコン＋ラベル」の行を並べる。
 * 選んだ項目のidをOnPickへ返すだけで、処理自体は呼び出し側に任せる。
 */
final class MenuPanel {

    interface OnPick { void pick(int id); }

    private final Activity act;
    private final OnPick cb;
    private final LinearLayout root;
    private LinearLayout actionRow;
    private PopupWindow pw;

    MenuPanel(Activity act, OnPick cb) {
        this.act = act;
        this.cb = cb;
        root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(4), 0, dp(8));
    }

    /** パネル上端の見出し（サブメニュー用） */
    MenuPanel header(String title) {
        TextView tv = new TextView(act);
        tv.setText(title);
        tv.setTextSize(13);
        tv.setTextColor(act.getColor(R.color.on_surface_muted));
        tv.setPadding(dp(20), dp(12), dp(20), dp(6));
        root.addView(tv);
        return this;
    }

    /** 上段のアイコンボタン。activeならアクセント色（ブックマーク済み等） */
    MenuPanel action(int id, int icon, String desc, boolean active) {
        if (actionRow == null) {
            actionRow = new LinearLayout(act);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setPadding(dp(4), 0, dp(4), dp(4));
            root.addView(actionRow);
            divider();
        }
        ImageButton b = new ImageButton(act);
        b.setImageDrawable(tinted(icon, active ? R.color.accent : R.color.icon));
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackground(attrDrawable(android.R.attr.selectableItemBackgroundBorderless));
        b.setContentDescription(desc);
        b.setOnClickListener(v -> pick(id));
        actionRow.addView(b, new LinearLayout.LayoutParams(0, dp(52), 1f));
        return this;
    }

    MenuPanel item(int id, int icon, String label) { return row(id, icon, label, null, false); }

    /** 右端に補足（現在値など）を出す行 */
    MenuPanel item(int id, int icon, String label, String trailing) { return row(id, icon, label, trailing, false); }

    /** 右端に › を出す行（押すと別パネルを開く想定） */
    MenuPanel sub(int id, int icon, String label) { return row(id, icon, label, null, true); }

    MenuPanel divider() {
        View d = new View(act);
        d.setBackgroundColor(act.getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMargins(0, dp(4), 0, dp(4));
        root.addView(d, lp);
        return this;
    }

    private MenuPanel row(int id, int icon, String label, String trailing, boolean chevron) {
        LinearLayout r = new LinearLayout(act);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(20), 0, dp(16), 0);
        r.setMinimumHeight(dp(48));
        r.setBackground(attrDrawable(android.R.attr.selectableItemBackground));
        r.setOnClickListener(v -> pick(id));

        ImageView iv = new ImageView(act);
        iv.setImageDrawable(tinted(icon, R.color.icon));
        r.addView(iv, new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView tv = new TextView(act);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTextColor(act.getColor(R.color.on_surface));
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(18));
        r.addView(tv, tlp);

        if (trailing != null) {
            TextView tr = new TextView(act);
            tr.setText(trailing);
            tr.setTextSize(13);
            tr.setTextColor(act.getColor(R.color.on_surface_muted));
            r.addView(tr);
        }
        if (chevron) {
            ImageView ch = new ImageView(act);
            ch.setImageDrawable(tinted(R.drawable.ic_m_chevron_right, R.color.on_surface_muted));
            r.addView(ch, new LinearLayout.LayoutParams(dp(20), dp(20)));
        }
        root.addView(r);
        return this;
    }

    /** anchor（下部バー内のボタン）のあるウィンドウの右下、下部バーの直上に出す */
    void show(View anchor) {
        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(root);
        pw = new PopupWindow(sv, dp(264), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        pw.setBackgroundDrawable(act.getDrawable(R.drawable.popup_bg));
        pw.setElevation(dp(8));
        pw.setOutsideTouchable(true);
        pw.setAnimationStyle(android.R.style.Animation_Dialog);
        View bar = (View) anchor.getParent();
        int[] loc = new int[2];
        bar.getLocationInWindow(loc);
        int windowH = anchor.getRootView().getHeight();
        int bottomOffset = windowH - loc[1] + dp(6);
        // 画面が低い（横向き等）ときは下部バーの上の空間に収まるようにする
        pw.setHeight(Math.min(measureHeight(sv), loc[1] - dp(32)));
        pw.showAtLocation(anchor, Gravity.BOTTOM | Gravity.END, dp(8), bottomOffset);
    }

    private int measureHeight(View v) {
        v.measure(View.MeasureSpec.makeMeasureSpec(dp(264), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return v.getMeasuredHeight();
    }

    private void pick(int id) {
        if (pw != null) pw.dismiss();
        cb.pick(id);
    }

    private Drawable tinted(int res, int color) {
        Drawable d = act.getDrawable(res).mutate();
        d.setTint(act.getColor(color));
        return d;
    }

    private Drawable attrDrawable(int attr) {
        TypedValue tv = new TypedValue();
        act.getTheme().resolveAttribute(attr, tv, true);
        return act.getDrawable(tv.resourceId);
    }

    private int dp(int v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + .5f);
    }
}
