package net.localhost.sniffer;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import java.util.ArrayList;
import java.util.List;

/** 記事ウィジェットのリスト供給。feedテーブル(永続化済み)を行に変換する。 */
public class ArticleWidgetService extends RemoteViewsService {
    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new ArticleFactory(getApplicationContext());
    }

    private static class ArticleFactory implements RemoteViewsFactory {
        private final Context ctx;
        private List<BrowserDb.FeedItem> items = new ArrayList<>();

        ArticleFactory(Context ctx) { this.ctx = ctx; }

        @Override public void onCreate() { reload(); }
        @Override public void onDataSetChanged() { reload(); }
        @Override public void onDestroy() { items.clear(); }

        private void reload() {
            try {
                items = new BrowserDb(ctx).listFeed();
            } catch (Throwable e) {
                items = new ArrayList<>();
            }
        }

        @Override public int getCount() { return items.size(); }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean hasStableIds() { return true; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public RemoteViews getLoadingView() { return null; }

        @Override
        public RemoteViews getViewAt(int i) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_article_item);
            if (i < 0 || i >= items.size()) return rv;
            BrowserDb.FeedItem it = items.get(i);
            rv.setTextViewText(R.id.itemTitle, it.title);
            rv.setTextViewText(R.id.itemSite, it.site == null ? "" : it.site);
            // タップ時にURLをテンプレートへ差し込む
            Intent fill = new Intent();
            fill.setData(Uri.parse(it.url));
            rv.setOnClickFillInIntent(R.id.itemRoot, fill);
            return rv;
        }
    }
}
