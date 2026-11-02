package top.niunaijun.blackbox.utils;

import java.io.File;
import java.io.FileWriter;

import top.niunaijun.blackbox.BlackBoxCore;

/**
 * DevxyzClone diagnostics (rounds 3-5).
 *
 * Single sink for clone diagnostic lines. The primary target is the HOST
 * app's INTERNAL files dir: it is always writable by the host-uid process
 * (no storage permission, no scoped-storage rules) and is read back by the
 * in-app Diagnostic Log viewer (DiagLogActivity), so evidence can no longer
 * get lost the way the public-Download copy did on Android 16 (0-byte file).
 * External-files and public-Download copies are kept as best-effort extras.
 */
public final class DiagLog {
    public static final String FILE_NAME = "devxyzclone-pmdiag.log";

    private static final Object LOCK = new Object();

    private DiagLog() {
    }

    public static File internalFile() {
        try {
            android.content.Context ctx = BlackBoxCore.getContext();
            if (ctx != null) {
                File dir = ctx.getFilesDir();
                if (dir != null) {
                    return new File(dir, FILE_NAME);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static void log(String msg) {
        try {
            String line = System.currentTimeMillis() + " [" + android.os.Process.myPid() + "] " + msg + "\n";
            synchronized (LOCK) {
                write(internalFile(), line);
                try {
                    android.content.Context ctx = BlackBoxCore.getContext();
                    if (ctx != null) {
                        File ext = ctx.getExternalFilesDir(null);
                        if (ext != null) {
                            write(new File(ext, FILE_NAME), line);
                        }
                    }
                } catch (Throwable ignored) {
                }
                try {
                    File dl = new File(
                            android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS),
                            FILE_NAME);
                    write(dl, line);
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void write(File f, String line) {
        if (f == null) {
            return;
        }
        try {
            FileWriter w = new FileWriter(f, true);
            w.write(line);
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
