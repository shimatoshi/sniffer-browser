package net.localhost.sniffer;

import android.content.Context;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 実機デバッグ用ファイルログ。Termuxから ~/storage/downloads/sniffer-debug.log で監視する。
 * （Android/data配下はTermuxから読めない端末があるためDownloadへ直書き。
 * 　targetSdk28+requestLegacyExternalStorageで直書き可能）
 */
public class Dbg {
    private static volatile File file;

    public static void log(Context ctx, String msg) {
        try {
            File f = file;
            if (f == null) {
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (dir == null || (!dir.exists() && !dir.mkdirs()))
                    dir = ctx.getExternalFilesDir(null);
                if (dir == null) dir = ctx.getFilesDir();
                file = f = new File(dir, "sniffer-debug.log");
            }
            synchronized (Dbg.class) {
                try (PrintWriter pw = new PrintWriter(new FileWriter(f, true))) {
                    pw.println(new SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
                            .format(new Date()) + " " + msg);
                }
            }
        } catch (Throwable e) {
            Log.e("Dbg", "fileLog error: " + e);
        }
    }
}
