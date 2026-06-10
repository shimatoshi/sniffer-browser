package net.localhost.sniffer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

import java.util.List;

/**
 * ホーム画面の新着記事ウィジェット（コレクション）。
 * 全固定サイト(pins)の新着を1リストにまとめてスクロール表示。↻で手動更新（自動更新なし）。
 */
public class ArticleWidget extends AppWidgetProvider {

    static final String ACTION_REFRESH = "net.localhost.sniffer.ARTICLE_REFRESH";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(ctx, mgr, id, false);
    }

    private void render(Context ctx, AppWidgetManager mgr, int id, boolean refreshing) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_articles);
        rv.setTextViewText(R.id.articleStatus, refreshing ? "更新中…" : "");

        // リスト本体をRemoteViewsServiceに接続
        Intent svc = new Intent(ctx, ArticleWidgetService.class);
        svc.setData(Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME)));
        rv.setRemoteAdapter(R.id.articleList, svc);
        rv.setEmptyView(R.id.articleList, R.id.articleEmpty);

        // 各記事タップ → MainActivityでURLを開く（fill-inでURLを差し込む）
        Intent open = new Intent(ctx, MainActivity.class);
        open.setAction(Intent.ACTION_VIEW);
        PendingIntent tmpl = PendingIntent.getActivity(
                ctx, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        rv.setPendingIntentTemplate(R.id.articleList, tmpl);

        // ↻更新ボタン
        Intent refresh = new Intent(ctx, ArticleWidget.class);
        refresh.setAction(ACTION_REFRESH);
        rv.setOnClickPendingIntent(R.id.articleRefresh, PendingIntent.getBroadcast(
                ctx, 0, refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        // タイトルタップ → Gobieホームを開く
        Intent home = new Intent(ctx, MainActivity.class);
        home.setData(Uri.parse(HomePage.URL_HOME));
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        rv.setOnClickPendingIntent(R.id.articleTitle, PendingIntent.getActivity(
                ctx, 3, home, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        mgr.updateAppWidget(id, rv);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        if (!ACTION_REFRESH.equals(intent.getAction())) return;

        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, ArticleWidget.class));
        for (int id : ids) render(ctx, mgr, id, true);

        // ネットワーク取得は別スレッド。受信完了まで生かす。
        final PendingResult pr = goAsync();
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                BrowserDb db = new BrowserDb(app);
                List<BrowserDb.Entry> pins = db.listPins();
                String ua = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120 Mobile";
                List<BrowserDb.FeedItem> items = HomePage.collectAll(pins, ua);
                db.replaceFeed(items);
            } catch (Throwable ignore) {
            } finally {
                AppWidgetManager m = AppWidgetManager.getInstance(app);
                int[] live = m.getAppWidgetIds(new ComponentName(app, ArticleWidget.class));
                m.notifyAppWidgetViewDataChanged(live, R.id.articleList);
                for (int id : live) render(app, m, id, false);
                pr.finish();
            }
        }).start();
    }
}
