package net.localhost.sniffer;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

/**
 * SwipeRefreshLayout相当を素のFrameLayoutで自前実装。
 * AndroidX非依存ビルドのため。子に積まれたWebViewのうち
 * VISIBLEなものを対象とし、最上部からの下方向ドラッグで更新コールバックを発火する。
 */
public class PullRefreshContainer extends FrameLayout {

    public interface OnRefreshListener { void onRefresh(); }

    private OnRefreshListener listener;
    private final ProgressBar spinner;
    private final int touchSlop;
    private final float triggerPx;   // この距離まで引いたら発火
    private final float maxPullPx;   // ドラッグ追従の上限
    private final int spinnerSize;

    private float startY;
    private boolean dragging;
    private boolean refreshing;

    public PullRefreshContainer(Context c) { this(c, null); }
    public PullRefreshContainer(Context c, AttributeSet a) {
        super(c, a);
        float d = c.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
        triggerPx = 64 * d;
        maxPullPx = 96 * d;
        spinnerSize = (int) (36 * d);

        // 円形背景つきの小さなスピナーをオーバーレイとして配置
        spinner = new ProgressBar(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.parseColor("#333333"));
        spinner.setBackground(bg);
        int pad = (int) (6 * d);
        spinner.setPadding(pad, pad, pad, pad);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(spinnerSize, spinnerSize);
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        spinner.setLayoutParams(lp);
        spinner.setVisibility(GONE);
        addView(spinner);
        Log.i("PullRefresh", "PullRefreshContainer constructed");
    }

    public void setOnRefreshListener(OnRefreshListener l) { listener = l; }

    /** ロード完了時にActivityから呼ぶ。スピナーを畳む。 */
    public void setRefreshing(boolean on) {
        if (!on && refreshing) {
            refreshing = false;
            spinner.animate().translationY(-spinnerSize).setDuration(200)
                    .withEndAction(() -> spinner.setVisibility(GONE)).start();
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallow) {
        // WebViewはタッチ開始時に親の横取りを無効化しようとする。
        // 対象が最上部のときはこの要求を無視し、自前の引っ張り検出を生かす
        // （AndroidX SwipeRefreshLayoutと同じ挙動）。
        View t = target();
        if (t != null && !t.canScrollVertically(-1)) return;
        super.requestDisallowInterceptTouchEvent(disallow);
    }

    /** 対象＝spinner以外でVISIBLEな子（＝現在表示中のWebView） */
    private View target() {
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            if (ch != spinner && ch.getVisibility() == VISIBLE) return ch;
        }
        return null;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        Log.i("PullRefresh", "dispatch action=" + e.getActionMasked() + " y=" + e.getY());
        return super.dispatchTouchEvent(e);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        Log.i("PullRefresh", "onIntercept action=" + e.getActionMasked()
                + " refreshing=" + refreshing + " listener=" + (listener != null));
        if (refreshing || listener == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = e.getY();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                View t = target();
                float dy0 = e.getY() - startY;
                Log.i("PullRefresh", "intercept MOVE dy=" + dy0 + " target=" + t
                        + " canUp=" + (t != null && t.canScrollVertically(-1)));
                // 最上部（これ以上上にスクロールできない）かつ下方向ドラッグのみ捕捉
                if (t != null && !t.canScrollVertically(-1)) {
                    float dy = e.getY() - startY;
                    if (dy > touchSlop) { dragging = true; return true; }
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!dragging) return super.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getY() - startY;
                if (dy < 0) dy = 0;
                float pull = Math.min(dy * 0.5f, maxPullPx); // ゴム感のため減衰
                spinner.setVisibility(VISIBLE);
                spinner.setTranslationY(pull - spinnerSize);
                spinner.setAlpha(Math.min(1f, pull / triggerPx));
                spinner.setRotation(pull * 2f);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float dy = e.getY() - startY;
                float pull = Math.min(dy * 0.5f, maxPullPx);
                dragging = false;
                if (pull >= triggerPx && e.getActionMasked() == MotionEvent.ACTION_UP) {
                    refreshing = true;
                    spinner.setAlpha(1f);
                    spinner.animate().translationY(triggerPx - spinnerSize)
                            .setDuration(150).start();
                    if (listener != null) listener.onRefresh();
                } else {
                    spinner.animate().translationY(-spinnerSize).setDuration(200)
                            .withEndAction(() -> { if (!refreshing) spinner.setVisibility(GONE); })
                            .start();
                }
                return true;
            }
        }
        return super.onTouchEvent(e);
    }
}
