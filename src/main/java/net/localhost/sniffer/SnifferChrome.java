package net.localhost.sniffer;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * MainActivity/PwaActivity共通のWebChromeClient。
 * - onShowCustomView: 動画の全画面再生（decorViewに重ねてイマーシブ化）
 * - onShowFileChooser: <input type=file> → システムのファイルピッカー
 * - onCreateWindow: target=_blank / window.open をメインWebViewで開く（非ジェスチャは広告ポップアップとして遮断）
 * 各Activityは onActivityResult → onFileResult、BACKキー → exitFullscreen を中継すること。
 */
public class SnifferChrome extends WebChromeClient {

    public static final int REQ_FILE = 71;

    private final Activity act;
    private final WebView mainWeb;

    private View customView;
    private CustomViewCallback customCb;
    private int savedUiVisibility;
    private ValueCallback<Uri[]> fileCb;

    public SnifferChrome(Activity act, WebView mainWeb) {
        this.act = act;
        this.mainWeb = mainWeb;
    }

    // ---- 全画面再生 ----

    @Override
    public void onShowCustomView(View view, CustomViewCallback callback) {
        if (customView != null) { callback.onCustomViewHidden(); return; }
        customView = view;
        customCb = callback;
        FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
        decor.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        savedUiVisibility = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
    }

    @Override
    public void onHideCustomView() {
        exitFullscreen();
    }

    /** 全画面動画中か（PiP判定に使う） */
    public boolean isInFullscreen() { return customView != null; }

    /** BACKキーから呼ぶ。全画面中だったらtrue（処理済み） */
    public boolean exitFullscreen() {
        if (customView == null) return false;
        FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
        decor.removeView(customView);
        decor.setSystemUiVisibility(savedUiVisibility);
        act.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        customView = null;
        if (customCb != null) { try { customCb.onCustomViewHidden(); } catch (Throwable ignore) {} }
        customCb = null;
        return true;
    }

    // ---- <input type=file> ----

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                     FileChooserParams params) {
        if (fileCb != null) fileCb.onReceiveValue(null);
        fileCb = callback;
        try {
            act.startActivityForResult(params.createIntent(), REQ_FILE);
            return true;
        } catch (Throwable e) {
            fileCb = null;
            Toast.makeText(act, "ファイルピッカーを開けない: " + e, Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    /** Activity#onActivityResult から中継する */
    public void onFileResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_FILE || fileCb == null) return;
        fileCb.onReceiveValue(FileChooserParams.parseResult(resultCode, data));
        fileCb = null;
    }

    // ---- window.open / target=_blank ----

    @Override
    public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
        // 非ジェスチャのポップアップ＝広告は無視（設定でオフにできる）
        if (!isUserGesture && AdBlocker.get(act).popupBlockOn()) return false;
        // 仮WebViewでURLだけ受け取り、メインWebViewで開く（シングルウィンドウ運用）
        WebView temp = new WebView(view.getContext());
        temp.setWebViewClient(new WebViewClient() {
            // 注意: 未アタッチViewのpost()は実行されない(API24+)。Handlerで確実に破棄する
            private void consume(WebView v, String url) {
                openUrl(url);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(v::destroy);
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                consume(v, req.getUrl().toString());
                return true;
            }
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                consume(v, url);
                return true;
            }
        });
        WebView.WebViewTransport t = (WebView.WebViewTransport) resultMsg.obj;
        t.setWebView(temp);
        resultMsg.sendToTarget();
        return true;
    }

    /** 新規窓のURLの開き方。PwaActivityはスコープ外を既定ブラウザへ逃がすためoverride可 */
    protected void openUrl(String url) {
        mainWeb.loadUrl(url);
    }

    // ---- UA ----

    /**
     * WebView識別子(「; wv」と「Version/4.0 」)を落としたChrome相当UAを設定する。
     * GoogleはWebView UAを見るとOAuth/GISスクリプトにHTMLエラーを返し
     * ERR_BLOCKED_BY_ORBで「Googleでログイン」が無反応になるため必須。
     */
    public static void applyChromeUa(android.webkit.WebSettings s) {
        String ua = s.getUserAgentString();
        if (ua == null) return;
        s.setUserAgentString(ua.replace("; wv", "").replace("Version/4.0 ", ""));
    }

    // ---- 通常ダウンロード（静的ヘルパ） ----

    /** PDF/zip/画像長押し等の通常DLをDownloadManagerへ。Cookie/UA/Referer引き継ぎ */
    public static void enableDownloads(Activity act, WebView web) {
        web.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) ->
                downloadUrl(act, web, url, contentDisposition, mimetype));
    }

    /** http(s)/data: URLをDownloadフォルダへ保存。enableDownloadsと画像長押しの共通経路 */
    static void downloadUrl(Activity act, WebView web, String url,
                            String contentDisposition, String mimetype) {
        if (url.startsWith("data:")) { saveDataUrl(act, url); return; }
        if (url.startsWith("blob:")) {
            Toast.makeText(act, "blob URLのDLは未対応", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url));
            r.setMimeType(mimetype);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) r.addRequestHeader("Cookie", cookie);
            String ua = web.getSettings().getUserAgentString();
            if (ua != null) r.addRequestHeader("User-Agent", ua);
            String ref = web.getUrl();
            if (ref != null) r.addRequestHeader("Referer", ref);
            String fn = URLUtil.guessFileName(url, contentDisposition, mimetype);
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fn);
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            ((DownloadManager) act.getSystemService(Context.DOWNLOAD_SERVICE)).enqueue(r);
            Toast.makeText(act, "DL開始: " + fn, Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(act, "DL失敗: " + e, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- 画像長押し保存 ----

    /** 画像長押し → 保存/URLコピーのメニュー。IMAGE_TYPEとリンク付き画像の両方を拾う */
    public static void enableImageSave(Activity act, WebView web) {
        web.setOnLongClickListener(v -> {
            // ホーム(gobie://)では横取りせずページのcontextmenu(PWA長押しメニュー)に渡す
            String cur = ((WebView) v).getUrl();
            if (cur != null && cur.startsWith("gobie://")) return false;
            WebView.HitTestResult hit = ((WebView) v).getHitTestResult();
            int type = hit.getType();
            if (type != WebView.HitTestResult.IMAGE_TYPE
                    && type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) return false;
            final String url = hit.getExtra();
            if (url == null || url.isEmpty()) return false;
            String label = url.startsWith("data:")
                    ? "data:画像" : (url.length() > 120 ? url.substring(0, 120) + "…" : url);
            new android.app.AlertDialog.Builder(act)
                    .setTitle(label)
                    .setItems(new String[]{"画像を保存", "画像URLをコピー"}, (d, w) -> {
                        if (w == 0) {
                            downloadUrl(act, web, url, null, guessImageMime(url));
                        } else {
                            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                    act.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("url", url));
                            Toast.makeText(act, "コピーした", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .show();
            return true;
        });
    }

    private static String guessImageMime(String url) {
        String u = url.toLowerCase();
        if (u.startsWith("data:")) {
            int c = u.indexOf(';'); int s = u.indexOf(':');
            if (c > s) return u.substring(s + 1, c);
        }
        if (u.contains(".png")) return "image/png";
        if (u.contains(".gif")) return "image/gif";
        if (u.contains(".webp")) return "image/webp";
        if (u.contains(".svg")) return "image/svg+xml";
        return "image/jpeg";
    }

    /** data:image/...;base64,xxx をデコードしてDownloadへ直書き（targetSdk28なので直書き可） */
    private static void saveDataUrl(Activity act, String url) {
        try {
            int comma = url.indexOf(',');
            if (comma < 0) throw new IllegalArgumentException("data:URLが不正");
            String meta = url.substring(5, comma); // image/png;base64
            byte[] bytes = meta.endsWith(";base64")
                    ? android.util.Base64.decode(url.substring(comma + 1), android.util.Base64.DEFAULT)
                    : java.net.URLDecoder.decode(url.substring(comma + 1), "UTF-8").getBytes("UTF-8");
            String mime = meta.contains(";") ? meta.substring(0, meta.indexOf(';')) : meta;
            String ext = mime.contains("png") ? "png" : mime.contains("gif") ? "gif"
                    : mime.contains("webp") ? "webp" : mime.contains("svg") ? "svg" : "jpg";
            java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            dir.mkdirs();
            java.io.File out = new java.io.File(dir, "img_" + System.currentTimeMillis() + "." + ext);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(bytes);
            }
            // ギャラリー等に反映させる
            act.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(out)));
            Toast.makeText(act, "保存した: Download/" + out.getName(), Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            Toast.makeText(act, "保存失敗: " + e, Toast.LENGTH_SHORT).show();
        }
    }
}
