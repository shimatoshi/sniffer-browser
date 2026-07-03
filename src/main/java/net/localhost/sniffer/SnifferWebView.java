package net.localhost.sniffer;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

/**
 * バックグラウンド再生対応WebView。
 *
 * Androidは Activity が裏(onStop)に回ると WebView へ「非表示」を伝え、WebViewは
 * メディアのデコードを止める（前面サービスでプロセスを生かしても、音声まで止まる）。
 * 再生中(keepPlaying)は onWindowVisibilityChanged に常に VISIBLE を渡し、
 * WebViewに「まだ見えている」と思い込ませてデコードを継続させる。
 * 停止中は本来の可視性を渡す（裏で無駄に描画し続けない）。
 */
public class SnifferWebView extends WebView {

    private volatile boolean keepPlaying = false;

    public SnifferWebView(Context context) {
        super(context);
    }

    /** 再生状態に追従して呼ぶ。trueの間は裏に回ってもメディアを止めない。 */
    public void setKeepPlaying(boolean k) {
        keepPlaying = k;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(keepPlaying ? View.VISIBLE : visibility);
    }
}
