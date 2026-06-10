package net.localhost.sniffer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 検索ウィジェットのマイクから起動する音声検索。
 * 端末の音声認識UIを呼び出し、聞き取った文字列をMainActivityに渡してgo()させる（透明・即終了）。
 */
public class VoiceSearchActivity extends Activity {
    private static final int REQ_VOICE = 1;

    @Override
    protected void onCreate(Bundle s) {
        super.onCreate(s);
        Intent it = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        it.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        it.putExtra(RecognizerIntent.EXTRA_PROMPT, "話しかけてください");
        try {
            startActivityForResult(it, REQ_VOICE);
        } catch (Throwable e) {
            Toast.makeText(this, "音声認識を利用できません", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOICE && res == RESULT_OK && data != null) {
            ArrayList<String> hits =
                    data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (hits != null && !hits.isEmpty()) {
                Intent open = new Intent(this, MainActivity.class);
                open.setAction(MainActivity.ACTION_SEARCH);
                open.putExtra(MainActivity.EXTRA_QUERY, hits.get(0));
                open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(open);
            }
        }
        finish();
    }
}
