package net.localhost.debugnote;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 全自作アプリ共通デバッグノート。Activity#onCreate の最後（setContentView後）に DebugNote.attach(this, "appname") を呼ぶだけ。 */
public final class DebugNote {
    private DebugNote() {}

    public static void attach(Activity act, String app) {
        float dp = act.getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(act);

        TextView btn = new TextView(act);
        btn.setText("\u270E");
        btn.setTextColor(Color.argb(190, 255, 255, 255));
        btn.setTextSize(14);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(Color.argb(76, 80, 80, 80));
        btn.setBackground(bg);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams((int) (26 * dp), (int) (26 * dp));
        blp.gravity = Gravity.BOTTOM | Gravity.END;
        blp.setMargins(0, 0, (int) (6 * dp), (int) (96 * dp));

        LinearLayout panel = new LinearLayout(act);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(Color.rgb(34, 34, 34));
        int pad = (int) (8 * dp);
        panel.setPadding(pad, pad, pad, pad);
        panel.setVisibility(View.GONE);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.BOTTOM;

        EditText input = new EditText(act);
        input.setHint("こうしたい・気づいたことをここに");
        input.setTextColor(Color.rgb(238, 238, 238));
        input.setHintTextColor(Color.GRAY);
        input.setBackgroundColor(Color.rgb(17, 17, 17));
        input.setMinLines(3);
        panel.addView(input);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView cnt = new TextView(act);
        cnt.setTextColor(Color.GRAY);
        cnt.setTextSize(12);
        cnt.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);

        Button save = new Button(act); save.setText("保存");
        Button pub = new Button(act); pub.setText("発行");
        Button close = new Button(act); close.setText("閉じる");
        row.addView(save); row.addView(pub); row.addView(close);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        row.addView(cnt, clp);
        panel.addView(row);

        DebugNoteRouter r = new DebugNoteRouter(act, app, panel, input, cnt);
        btn.setOnClickListener(r);
        save.setOnClickListener(r);
        pub.setOnClickListener(r);
        close.setOnClickListener(r);
        r.btn = btn; r.save = save; r.pub = pub; r.close = close;

        root.addView(panel, plp);
        root.addView(btn, blp);
        act.addContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}

final class DebugNoteRouter implements View.OnClickListener {
    private final Activity act;
    private final String app;
    private final LinearLayout panel;
    private final EditText input;
    private final TextView cnt;
    private final File store;
    View btn, save, pub, close;

    DebugNoteRouter(Activity act, String app, LinearLayout panel, EditText input, TextView cnt) {
        this.act = act;
        this.app = app;
        this.panel = panel;
        this.input = input;
        this.cnt = cnt;
        this.store = new File(act.getFilesDir(), "debug-notes.jsonl");
    }

    public void onClick(View v) {
        if (v == btn) {
            boolean open = panel.getVisibility() == View.VISIBLE;
            panel.setVisibility(open ? View.GONE : View.VISIBLE);
            if (!open) refresh();
        } else if (v == save) {
            doSave();
        } else if (v == pub) {
            doPublish();
        } else if (v == close) {
            panel.setVisibility(View.GONE);
        }
    }

    private void refresh() {
        cnt.setText(lineCount() + "件");
    }

    private void doSave() {
        String t = input.getText().toString().trim();
        if (t.length() == 0) return;
        try {
            JSONObject o = new JSONObject();
            o.put("ts", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
            o.put("app", app);
            o.put("note", t);
            FileOutputStream fos = new FileOutputStream(store, true);
            try {
                fos.write((o.toString() + "\n").getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            input.setText("");
            refresh();
        } catch (Exception e) {
            Toast.makeText(act, "保存失敗: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private void doPublish() {
        if (!store.exists() || store.length() == 0) {
            Toast.makeText(act, "ノートは空", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String name = "debugnote-" + app + "-"
                    + new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date()) + ".jsonl";
            OutputStream out;
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = act.getContentResolver().insert(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("MediaStore insert失敗");
                out = act.getContentResolver().openOutputStream(uri);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                dir.mkdirs();
                out = new FileOutputStream(new File(dir, name));
            }
            FileInputStream in = new FileInputStream(store);
            try {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            } finally {
                in.close();
                out.close();
            }
            Toast.makeText(act, "Download/" + name + " に発行した", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(act, "発行失敗: " + e, Toast.LENGTH_LONG).show();
        }
    }

    private int lineCount() {
        if (!store.exists()) return 0;
        int n = 0;
        try {
            FileInputStream in = new FileInputStream(store);
            try {
                int c;
                while ((c = in.read()) != -1) if (c == '\n') n++;
            } finally {
                in.close();
            }
        } catch (Exception ignored) {}
        return n;
    }
}
