package net.localhost.sniffer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

/**
 * ホーム画面の上に重なる軽量検索オーバーレイ。
 * 検索ウィジェットのバーをタップすると即これが開き（ブラウザ本体はまだ開かない）、
 * 入力してエンター/Goで初めてMainActivityが起動して検索が走る。外側タップで閉じる。
 */
public class SearchActivity extends Activity {

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        setContentView(R.layout.activity_search);

        EditText input = findViewById(R.id.searchInput);
        findViewById(R.id.searchScrim).setOnClickListener(v -> finish());

        input.setOnEditorActionListener((v, actionId, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_SEARCH || enter) {
                submit(input.getText().toString());
                return true;
            }
            return false;
        });

        findViewById(R.id.searchMic).setOnClickListener(v -> {
            startActivity(new Intent(this, VoiceSearchActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });

        // 開いた瞬間にキーボードを出してすぐ打てるように
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        input.requestFocus();
    }

    private void submit(String q) {
        q = q == null ? "" : q.trim();
        if (!q.isEmpty()) {
            startActivity(new Intent(this, MainActivity.class)
                    .setAction(MainActivity.ACTION_SEARCH)
                    .putExtra(MainActivity.EXTRA_QUERY, q)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        }
        finish();
    }
}
