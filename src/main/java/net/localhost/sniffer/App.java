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
        if (isMainProcess()) SwBackup.maybeRestore(this);
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
