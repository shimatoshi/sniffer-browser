package net.localhost.sniffer;

import android.app.Application;
import android.os.Build;

import java.io.FileInputStream;

/**
 * プロセス起動フック。WebView初期化前にSWストレージのevict検知→復元を行う。
 * MainActivity/PwaActivity/HeadlessBrowserServiceのどこから起動しても先に走る。
 */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // WebViewのsandboxed rendererプロセスでも呼ばれるので、メインプロセス限定
        if (isMainProcess()) {
            SwBackup.maybeRestore(this);
            // スナップショット更新をPwaWarmerの完走に依存させない（12hクールダウンはSwBackup側）。
            // 巡回は35PWA×最大35秒と長く、完走前にプロセスが死ぬと更新機会が丸ごと消える。
            // 実際に2026-07-12から15日間バックアップが空き、evict時にその差分を失った。
            // quota DBのpersistent固定でevictを止めた前提なら、起動時点の現状は信頼してよい。
            SwBackup.maybeBackup(this);
            // 再起動を跨いだBOOT_COMPLETEDに加え、プロセス起動のたびにも安全網として読み直す
            // （アプリ更新直後などBOOT_COMPLETEDが飛ばないケースをカバー）
            PwaScheduler.rescheduleAll(this);
        }
    }

    private boolean isMainProcess() {
        String name = null;
        if (Build.VERSION.SDK_INT >= 28) {
            name = Application.getProcessName();
        } else {
            try (FileInputStream in = new FileInputStream("/proc/self/cmdline")) {
                byte[] buf = new byte[256];
                int n = in.read(buf);
                int end = 0;
                while (end < n && buf[end] != 0) end++;
                name = new String(buf, 0, end);
            } catch (Throwable ignore) {}
        }
        return getPackageName().equals(name);
    }
}
