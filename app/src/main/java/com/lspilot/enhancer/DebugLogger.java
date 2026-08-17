package com.lspilot.enhancer;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Toggleable, redacted diagnostics for the injected target-app code. */
final class DebugLogger {
    private static final String TAG = "LSPilotEnhancer";
    private static final long MAX_FILE_BYTES = 512L * 1024L;
    private static final Object LOCK = new Object();
    private static final ExecutorService FILE_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "LSPilotDebugLogger");
        thread.setDaemon(true);
        return thread;
    });
    private static final ThreadLocal<SimpleDateFormat> FORMAT =
            new ThreadLocal<SimpleDateFormat>() {
                @Override protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);
                }
            };

    private DebugLogger() {}

    static void d(String message) {
        write(Log.DEBUG, message, null, false);
    }

    static void i(String message) {
        write(Log.INFO, message, null, false);
    }

    static void w(String message) {
        write(Log.WARN, message, null, false);
    }

    static void e(String message, Throwable error) {
        write(Log.ERROR, message, error, true);
    }

    static String redact(String value) {
        if (value == null) return "null";
        String compact = value.replace('\n', ' ').replace('\r', ' ');
        if (compact.length() <= 160) return compact;
        return compact.substring(0, 157) + "...";
    }

    static String id(String value) {
        if (value == null || value.isEmpty()) return "none";
        return Integer.toHexString(value.hashCode());
    }

    private static void write(int priority, String message, Throwable error, boolean force) {
        boolean enabled = force || ModuleSettings.isDebugLogEnabled();
        if (!enabled) {
            if (priority >= Log.ERROR) Log.e(TAG, message, error);
            return;
        }
        String line = message == null ? "" : message;
        Log.println(priority, TAG, line);
        if (error != null) Log.println(priority, TAG, stack(error));
        if (!ModuleSettings.isDebugLogEnabled()) return;
        append(line, error);
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    private static void append(String message, Throwable error) {
        Context context = ModuleSettings.applicationContextForLogging();
        if (context == null) return;
        try {
            Context appContext = context.getApplicationContext();
            FILE_EXECUTOR.execute(() -> appendOnBackground(
                    appContext == null ? context : appContext, message, error));
        } catch (RejectedExecutionException ignored) {
            Log.w(TAG, "Debug log queue rejected write");
        }
    }

    private static void appendOnBackground(Context context, String message, Throwable error) {
        synchronized (LOCK) {
            try {
                File file = new File(context.getFilesDir(), "lspilot-enhancer-debug.log");
                if (file.exists() && file.length() > MAX_FILE_BYTES) {
                    File backup = new File(context.getFilesDir(), "lspilot-enhancer-debug.log.1");
                    if (backup.exists()) backup.delete();
                    file.renameTo(backup);
                }
                try (FileWriter writer = new FileWriter(file, true)) {
                    writer.append(FORMAT.get().format(new Date()))
                            .append(" [").append(Thread.currentThread().getName()).append("] ")
                            .append(message).append('\n');
                    if (error != null) writer.append(stack(error)).append('\n');
                }
            } catch (Throwable ignored) {
                Log.w(TAG, "Unable to append debug log file", ignored);
            }
        }
    }
}