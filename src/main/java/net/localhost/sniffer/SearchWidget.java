package net.localhost.sniffer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** ホーム画面の検索バーウィジェット（Google風）。バー本体=アドレス欄フォーカス / マイク=音声検索。 */
public class SearchWidget extends AppWidgetProvider {
    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) {
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_search);

            // バー本体タップ → ホーム上に検索オーバーレイを開く（ブラウザはまだ開かない）
            Intent bar = new Intent(ctx, SearchActivity.class);
            bar.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            rv.setOnClickPendingIntent(R.id.widgetBar, PendingIntent.getActivity(
                    ctx, 1, bar, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

            // マイクタップ → 音声検索
            Intent voice = new Intent(ctx, VoiceSearchActivity.class);
            voice.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            rv.setOnClickPendingIntent(R.id.widgetMic, PendingIntent.getActivity(
                    ctx, 2, voice, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

            mgr.updateAppWidget(id, rv);
        }
    }
}
