package net.localhost.sniffer;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ユーザースクリプト（Greasemonkey風ミニ実装）。
 *
 * - 保存はBrowserDbのuserscriptsテーブル、ここではメモリキャッシュとマッチ・注入JS生成
 * - // ==UserScript== メタデータ(@name/@match/@include/@run-at)を貼り付け時に自動解釈
 * - マッチ: *ワイルドカードはURL全体マッチ、*無しは部分一致、空=全ページ
 * - 注入: run-at=endはonPageFinished、startはonPageStarted（WebViewの制約上ベストエフォート）
 * - 二重注入はwindowのidガードで防ぐ（onPageFinishedの多重発火対策）
 */
public class UserScripts {

    public static class Script {
        public long id = -1;
        public String name = "", matches = "", code = "", runat = "end";
        public boolean enabled = true;
    }

    private static volatile UserScripts inst;

    public static UserScripts get(Context ctx) {
        if (inst == null) {
            synchronized (UserScripts.class) {
                if (inst == null) inst = new UserScripts(ctx.getApplicationContext());
            }
        }
        return inst;
    }

    private final Context app;
    private volatile List<Script> scripts = new ArrayList<>();

    private UserScripts(Context app) {
        this.app = app;
        reload();
    }

    public void reload() {
        BrowserDb db = new BrowserDb(app);
        try {
            scripts = db.listScripts();
        } finally {
            db.close();
        }
    }

    public List<Script> all() {
        return scripts;
    }

    /** urlにマッチする有効スクリプトの注入用JS（start=trueはrun-at:document-start分） */
    public List<String> forUrl(String url, boolean start) {
        List<String> out = new ArrayList<>();
        if (url == null || url.startsWith("gobie:")) return out;
        for (Script s : scripts) {
            if (!s.enabled) continue;
            if ("start".equals(s.runat) != start) continue;
            if (!matches(s.matches, url)) continue;
            out.add(wrap(s));
        }
        return out;
    }

    /** カンマ/空白区切りの複数パターン。*はワイルドカード（URL全体）、無ければ部分一致、空=全ページ */
    static boolean matches(String patterns, String url) {
        if (patterns == null || patterns.trim().isEmpty()) return true;
        for (String p : patterns.trim().split("[,\\s]+")) {
            if (p.isEmpty()) continue;
            if (p.contains("*")) {
                String re = "\\Q" + p.replace("*", "\\E.*\\Q") + "\\E";
                if (Pattern.compile(re).matcher(url).matches()) return true;
            } else if (url.contains(p)) {
                return true;
            }
        }
        return false;
    }

    private static String wrap(Script s) {
        String guard = "__gobie_us_" + s.id;
        return "(function(){if(window." + guard + ")return;window." + guard + "=1;"
                + "try{\n" + s.code + "\n}catch(e){console.error('userscript#" + s.id + "',e);}})();";
    }

    // ---- ==UserScript== メタデータ解析 ----

    /** コードから @key の値を1つ取り出す（無ければnull） */
    public static String meta(String code, String key) {
        List<String> v = metaAll(code, key);
        return v.isEmpty() ? null : v.get(0);
    }

    /** コードから @key の値を全部取り出す */
    public static List<String> metaAll(String code, String key) {
        List<String> out = new ArrayList<>();
        if (code == null) return out;
        Matcher m = Pattern.compile(
                "^\\s*//\\s*@" + Pattern.quote(key) + "\\s+(.+?)\\s*$",
                Pattern.MULTILINE).matcher(code);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /** 保存前の整形: 名前/マッチ/run-atが空ならメタデータから補完 */
    public static void fillFromMeta(Script s) {
        if (s.name == null || s.name.trim().isEmpty()) {
            String n = meta(s.code, "name");
            s.name = n != null ? n : "(無名)";
        }
        if (s.matches == null || s.matches.trim().isEmpty()) {
            List<String> ms = metaAll(s.code, "match");
            ms.addAll(metaAll(s.code, "include"));
            StringBuilder b = new StringBuilder();
            for (String m : ms) {
                if (b.length() > 0) b.append(' ');
                b.append(m);
            }
            s.matches = b.toString();
        }
        String ra = meta(s.code, "run-at");
        if (ra != null && ra.contains("start")) s.runat = "start";
    }
}
