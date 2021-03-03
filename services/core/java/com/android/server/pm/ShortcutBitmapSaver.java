/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ShortcutInfo;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.drawable.Icon;
import android.os.StrictMode;
import android.os.StrictMode.ThreadPolicy;
import android.os.SystemClock;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.pm.ShortcutService.FileOutputStreamWithPath;

import libcore.io.IoUtils;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Class to save shortcut bitmaps on a worker thread.
 *
 * The methods with the "Locked" prefix must be called with the service lock held.
 */
public class ShortcutBitmapSaver {
    private static final String TAG = ShortcutService.TAG;
    private static final boolean DEBUG = ShortcutService.DEBUG;

    private static final boolean ADD_DELAY_BEFORE_SAVE_FOR_TEST = false; // DO NOT submit with true.
    private static final long SAVE_DELAY_MS_FOR_TEST = 1000; // DO NOT submit with true.

    /**
     * Before saving shortcuts.xml, and returning icons to the launcher, we wait for all pending
     * saves to finish.  However if it takes more than this long, we just give up and proceed.
     */
    private final long SAVE_WAIT_TIMEOUT_MS = 30 * 1000;

    private final ShortcutService mService;

    /**
     * Bitmaps are saved on this thread.
     *
     * Note: Just before saving shortcuts into the XML, we need to wait on all pending saves to
     * finish, and we need to do it with the service lock held, which would still block incoming
     * binder calls, meaning saving bitmaps *will* still actually block API calls too, which is
     * not ideal but fixing it would be tricky, so this is still a known issue on the current
     * version.
     *
     * In order to reduce the conflict, we use an own thread for this purpose, rather than
     * reusing existing background threads, and also to avoid possible deadlocks.
     */
    private final Executor mExecutor = new ThreadPoolExecutor(0, 1, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>());

    /** Represents a bitmap to save. */
    private static class PendingItem {
        /** Hosting shortcut. */
        public final ShortcutInfo shortcut;

        /** Compressed bitmap data. */
        public final byte[] bytes;

        /** Instantiated time, only for dogfooding. */
        private final long mInstantiatedUptimeMillis; // Only for dumpsys.

        private PendingItem(ShortcutInfo shortcut, byte[] bytes) {
            this.shortcut = shortcut;
            this.bytes = bytes;
            mInstantiatedUptimeMillis = SystemClock.uptimeMillis();
        }

        @Override
        public String toString() {
            return "PendingItem{size=" + bytes.length
                    + " age=" + (SystemClock.uptimeMillis() - mInstantiatedUptimeMillis) + "ms"
                    + " shortcut=" + shortcut.toInsecureString()
                    + "}";
        }
    }

    @GuardedBy("mPendingItems")
    private final Deque<PendingItem> mPendingItems = new LinkedBlockingDeque<>();

    public ShortcutBitmapSaver(ShortcutService service) {
        mService = service;
        // mLock = lock;
    }

    public boolean waitForAllSavesLocked() {
        final CountDownLatch latch = new CountDownLatch(1);

        mExecutor.execute(() -> latch.countDown());

        try {
            if (latch.await(SAVE_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return true;
            }
            mService.wtf("Timed out waiting on saving bitmaps.");
        } catch (InterruptedException e) {
            Slog.w(TAG, "interrupted");
        }
        return false;
    }

    /**
     * Wait for all pending saves to finish, and then return the given shortcut's bitmap path.
     */
    @Nullable
    public String getBitmapPathMayWaitLocked(ShortcutInfo shortcut) {
        final boolean success = waitForAllSavesLocked();
        if (success && shortcut.hasIconFile()) {
            return shortcut.getBitmapPath();
        } else {
            return null;
        }
    }

    public void removeIcon(ShortcutInfo shortcut) {
        // Do not remove the actual bitmap file yet, because if the device crashes before saving
        // the XML we'd lose the icon.  We just remove all dangling files after saving the XML.
        shortcut.setIconResourceId(0);
        shortcut.setIconResName(null);
        shortcut.setBitmapPath(null);
        shortcut.setIconUri(null);
        shortcut.clearFlags(ShortcutInfo.FLAG_HAS_ICON_FILE |
                ShortcutInfo.FLAG_ADAPTIVE_BITMAP | ShortcutInfo.FLAG_HAS_ICON_RES |
                ShortcutInfo.FLAG_ICON_FILE_PENDING_SAVE | ShortcutInfo.FLAG_HAS_ICON_URI);
    }

    public void saveBitmapLocked(ShortcutInfo shortcut,
            int maxDimension, CompressFormat format, int quality) {
        final Icon icon = shortcut.getIcon();
        Objects.requireNonNull(icon);

        final Bitmap original = icon.getBitmap();
        if (original == null) {
            Log.e(TAG, "Missing icon: " + shortcut);
            return;
        }

        // Compress it and enqueue to the requests.
        final byte[] bytes;
        final StrictMode.ThreadPolicy oldPolicy = StrictMode.getThreadPolicy();
        try {
            // compress() triggers a slow call, but in this case it's needed to save RAM and also
            // the target bitmap is of an icon size, so let's just permit it.
            StrictMode.setThreadPolicy(new ThreadPolicy.Builder(oldPolicy)
                    .permitCustomSlowCalls()
                    .build());
            final Bitmap shrunk = mService.shrinkBitmap(original, maxDimension);
            try {
                try (final ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
                    if (!shrunk.compress(format, quality, out)) {
                        Slog.wtf(ShortcutService.TAG, "Unable to compress bitmap");
                    }
                    out.flush();
                    bytes = out.toByteArray();
                    out.close();
                }
            } finally {
                if (shrunk != original) {
                    shrunk.recycle();
                }
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Slog.wtf(ShortcutService.TAG, "Unable to write bitmap to file", e);
            return;
        } finally {
            StrictMode.setThreadPolicy(oldPolicy);
        }

        shortcut.addFlags(
                ShortcutInfo.FLAG_HAS_ICON_FILE | ShortcutInfo.FLAG_ICON_FILE_PENDING_SAVE);

        if (icon.getType() == Icon.TYPE_ADAPTIVE_BITMAP) {
            shortcut.addFlags(ShortcutInfo.FLAG_ADAPTIVE_BITMAP);
        }

        // Enqueue a pending save.
        final PendingItem item = new PendingItem(shortcut, bytes);
        synchronized (mPendingItems) {
            mPendingItems.add(item);
        }

        if (DEBUG) {
            Slog.d(TAG, "Scheduling to save: " + item);
        }

        mExecutor.execute(mRunnable);
    }

    private final Runnable mRunnable = () -> {
        // Process all pending items.
        while (processPendingItems()) {
        }
    };

    /**
     * Takes a {@link PendingItem} from {@link #mPendingItems} and process it.
     *
     * Must be called {@link #mExecutor}.
     *
     * @return true if it processed an item, false if the queue is empty.
     */
    private boolean processPendingItems() {
        if (ADD_DELAY_BEFORE_SAVE_FOR_TEST) {
            Slog.w(TAG, "*** ARTIFICIAL SLEEP ***");
            try {
                Thread.sleep(SAVE_DELAY_MS_FOR_TEST);
            } catch (InterruptedException e) {
            }
        }

        // NOTE:
        // Ideally we should be holding the service lock when accessing shortcut instances,
        // but that could cause a deadlock so we don't do it.
        //
        // Instead, waitForAllSavesLocked() uses a latch to make sure changes made on this
        // thread is visible on the caller thread.

        ShortcutInfo shortcut = null;
        try {
            final PendingItem item;

            synchronized (mPendingItems) {
                if (mPendingItems.size() == 0) {
                    return false;
                }
                item = mPendingItems.pop();
            }

            shortcut = item.shortcut;

            // See if the shortcut is still relevant. (It might have been removed already.)
            if (!shortcut.isIconPendingSave()) {
                return true;
            }

            if (DEBUG) {
                Slog.d(TAG, "Saving bitmap: " + item);
            }

            File file = null;
            try {
                final FileOutputStreamWithPath out = mService.openIconFileForWrite(
                        shortcut.getUserId(), shortcut);
                file = out.getFile();

                try {
                    out.write(item.bytes);
                } finally {
                    IoUtils.closeQuietly(out);
                }

                final String path = file.getAbsolutePath();
                mService.postValue(shortcut, si -> si.setBitmapPath(path));

            } catch (IOException | RuntimeException e) {
                Slog.e(ShortcutService.TAG, "Unable to write bitmap to file", e);

                if (file != null && file.exists()) {
                    file.delete();
                }
                return true;
            }
        } finally {
            if (DEBUG) {
                Slog.d(TAG, "Saved bitmap.");
            }
            if (shortcut != null) {
                mService.postValue(shortcut, si -> {
                    if (si.getBitmapPath() == null) {
                        removeIcon(si);
                    }

                    // Whatever happened, remove this flag.
                    si.clearFlags(ShortcutInfo.FLAG_ICON_FILE_PENDING_SAVE);
                });
            }
        }
        return true;
    }

    public void dumpLocked(@NonNull PrintWriter pw, @NonNull String prefix) {
        synchronized (mPendingItems) {
            final int N = mPendingItems.size();
            pw.print(prefix);
            pw.println("Pending saves: Num=" + N + " Executor=" + mExecutor);

            for (PendingItem item : mPendingItems) {
                pw.print(prefix);
                pw.print("  ");
                pw.println(item);
            }
        }
    }
}
