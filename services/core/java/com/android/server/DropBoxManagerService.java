/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server;

import android.Manifest;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.BundleMerger;
import android.os.Debug;
import android.os.DropBoxManager;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dropbox.DropBoxManagerServiceDumpProto;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.text.format.TimeMigrationUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.proto.ProtoOutputStream;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IDropBoxManagerService;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.ObjectUtils;
import com.android.server.DropBoxManagerInternal.EntrySource;
import com.android.server.feature.flags.Flags;

import libcore.io.IoUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.zip.GZIPOutputStream;

/**
 * Implementation of {@link IDropBoxManagerService} using the filesystem.
 * Clients use {@link DropBoxManager} to access this service.
 */
public final class DropBoxManagerService extends SystemService {
    /**
     * For Android U and earlier versions, apps can continue to use the READ_LOGS permission,
     * but for all subsequent versions, the READ_DROPBOX_DATA permission must be used.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static final long ENFORCE_READ_DROPBOX_DATA = 296060945L;
    private static final String TAG = "DropBoxManagerService";
    private static final int DEFAULT_AGE_SECONDS = 3 * 86400;
    private static final int DEFAULT_MAX_FILES = 1000;
    private static final int DEFAULT_MAX_FILES_LOWRAM = 300;
    private static final int DEFAULT_QUOTA_KB = 10 * 1024;
    private static final int DEFAULT_QUOTA_PERCENT = 10;
    private static final int DEFAULT_RESERVE_PERCENT = 0;
    private static final int QUOTA_RESCAN_MILLIS = 5000;

    private static final boolean PROFILE_DUMP = false;

    // Max number of bytes of a dropbox entry to write into protobuf.
    private static final int PROTO_MAX_DATA_BYTES = 256 * 1024;

    // Size beyond which to force-compress newly added entries.
    private static final long COMPRESS_THRESHOLD_BYTES = 16_384;

    // Tags that we should drop by default.
    private static final List<String> DISABLED_BY_DEFAULT_TAGS =
            List.of("data_app_wtf", "system_app_wtf", "system_server_wtf");
    // TODO: This implementation currently uses one file per entry, which is
    // inefficient for smallish entries -- consider using a single queue file
    // per tag (or even globally) instead.

    // The cached context and derived objects

    private final ContentResolver mContentResolver;
    private final File mDropBoxDir;

    // Accounting of all currently written log files (set in init()).

    private FileList mAllFiles = null;
    private ArrayMap<String, FileList> mFilesByTag = null;

    private long mLowPriorityRateLimitPeriod = 0;
    private ArraySet<String> mLowPriorityTags = null;

    // Various bits of disk information

    private StatFs mStatFs = null;
    private int mBlockSize = 0;
    private int mCachedQuotaBlocks = 0;  // Space we can use: computed from free space, etc.
    private long mCachedQuotaUptimeMillis = 0;

    private volatile boolean mBooted = false;

    // Provide a way to perform sendBroadcast asynchronously to avoid deadlocks.
    private final DropBoxManagerBroadcastHandler mHandler;

    private int mMaxFiles = -1; // -1 means uninitialized.

    /** Receives events that might indicate a need to clean up files. */
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // For ACTION_DEVICE_STORAGE_LOW:
            mCachedQuotaUptimeMillis = 0;  // Force a re-check of quota size

            // Run the initialization in the background (not this main thread).
            // The init() and trimToFit() methods are synchronized, so they still
            // block other users -- but at least the onReceive() call can finish.
            new Thread() {
                public void run() {
                    try {
                        init();
                        trimToFit();
                    } catch (IOException e) {
                        Slog.e(TAG, "Can't init", e);
                    }
                }
            }.start();
        }
    };

    private final IDropBoxManagerService.Stub mStub = new IDropBoxManagerService.Stub() {
        @Override
        public void addData(String tag, byte[] data, int flags) {
            DropBoxManagerService.this.addData(tag, data, flags);
        }

        @Override
        public void addFile(String tag, ParcelFileDescriptor fd, int flags) {
            DropBoxManagerService.this.addFile(tag, fd, flags);
        }

        @Override
        public boolean isTagEnabled(String tag) {
            return DropBoxManagerService.this.isTagEnabled(tag);
        }

        @Override
        public DropBoxManager.Entry getNextEntry(String tag, long millis, String callingPackage) {
            return getNextEntryWithAttribution(tag, millis, callingPackage, null);
        }

        @Override
        public DropBoxManager.Entry getNextEntryWithAttribution(String tag, long millis,
                String callingPackage, String callingAttributionTag) {
            return DropBoxManagerService.this.getNextEntry(tag, millis, callingPackage,
                    callingAttributionTag);
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            DropBoxManagerService.this.dump(fd, pw, args);
        }

        @Override
        public void onShellCommand(FileDescriptor in, FileDescriptor out,
                                   FileDescriptor err, String[] args, ShellCallback callback,
                                   ResultReceiver resultReceiver) {
            (new ShellCmd()).exec(this, in, out, err, args, callback, resultReceiver);
        }
    };

    private class ShellCmd extends ShellCommand {
        @Override
        public int onCommand(String cmd) {
            if (cmd == null) {
                return handleDefaultCommands(cmd);
            }
            final PrintWriter pw = getOutPrintWriter();
            try {
                switch (cmd) {
                    case "set-rate-limit":
                        final long period = Long.parseLong(getNextArgRequired());
                        DropBoxManagerService.this.setLowPriorityRateLimit(period);
                        break;
                    case "add-low-priority":
                        final String addedTag = getNextArgRequired();
                        DropBoxManagerService.this.addLowPriorityTag(addedTag);
                        break;
                    case "remove-low-priority":
                        final String removeTag = getNextArgRequired();
                        DropBoxManagerService.this.removeLowPriorityTag(removeTag);
                        break;
                    case "restore-defaults":
                        DropBoxManagerService.this.restoreDefaults();
                        break;
                    default:
                        return handleDefaultCommands(cmd);
                }
            } catch (Exception e) {
                pw.println(e);
            }
            return 0;
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            pw.println("Dropbox manager service commands:");
            pw.println("  help");
            pw.println("    Print this help text.");
            pw.println("  set-rate-limit PERIOD");
            pw.println("    Sets low priority broadcast rate limit period to PERIOD ms");
            pw.println("  add-low-priority TAG");
            pw.println("    Add TAG to dropbox low priority list");
            pw.println("  remove-low-priority TAG");
            pw.println("    Remove TAG from dropbox low priority list");
            pw.println("  restore-defaults");
            pw.println("    restore dropbox settings to defaults");
        }
    }

    private class DropBoxManagerBroadcastHandler extends Handler {
        private final Object mLock = new Object();

        static final int MSG_SEND_BROADCAST = 1;
        static final int MSG_SEND_DEFERRED_BROADCAST = 2;

        @GuardedBy("mLock")
        private final ArrayMap<String, Intent> mDeferredMap = new ArrayMap();

        DropBoxManagerBroadcastHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SEND_BROADCAST:
                    prepareAndSendBroadcast((Intent) msg.obj, null);
                    break;
                case MSG_SEND_DEFERRED_BROADCAST:
                    Intent deferredIntent;
                    synchronized (mLock) {
                        deferredIntent = mDeferredMap.remove((String) msg.obj);
                    }
                    if (deferredIntent != null) {
                        prepareAndSendBroadcast(deferredIntent,
                                createBroadcastOptions(deferredIntent));
                    }
                    break;
            }
        }

        private void prepareAndSendBroadcast(Intent intent, Bundle options) {
            if (!DropBoxManagerService.this.mBooted) {
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            }
            if (Flags.enableReadDropboxPermission()) {
                BroadcastOptions unbundledOptions = (options == null)
                        ? BroadcastOptions.makeBasic() : BroadcastOptions.fromBundle(options);

                unbundledOptions.setRequireCompatChange(ENFORCE_READ_DROPBOX_DATA, true);
                getContext().sendBroadcastAsUser(intent, UserHandle.ALL,
                        Manifest.permission.READ_DROPBOX_DATA, unbundledOptions.toBundle());

                unbundledOptions.setRequireCompatChange(ENFORCE_READ_DROPBOX_DATA, false);
                getContext().sendBroadcastAsUser(intent, UserHandle.ALL,
                        Manifest.permission.READ_LOGS, unbundledOptions.toBundle());
            } else {
                getContext().sendBroadcastAsUser(intent, UserHandle.ALL,
                        android.Manifest.permission.READ_LOGS, options);
            }
        }

        private Intent createIntent(String tag, long time) {
            final Intent dropboxIntent = new Intent(DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED);
            dropboxIntent.putExtra(DropBoxManager.EXTRA_TAG, tag);
            dropboxIntent.putExtra(DropBoxManager.EXTRA_TIME, time);
            dropboxIntent.putExtra(DropBoxManager.EXTRA_DROPPED_COUNT, 0);
            return dropboxIntent;
        }

        private Bundle createBroadcastOptions(Intent intent) {
            final BundleMerger extrasMerger = new BundleMerger();
            extrasMerger.setDefaultMergeStrategy(BundleMerger.STRATEGY_FIRST);
            extrasMerger.setMergeStrategy(DropBoxManager.EXTRA_TIME,
                    BundleMerger.STRATEGY_COMPARABLE_MAX);
            extrasMerger.setMergeStrategy(DropBoxManager.EXTRA_DROPPED_COUNT,
                    BundleMerger.STRATEGY_NUMBER_INCREMENT_FIRST_AND_ADD);

            return BroadcastOptions.makeBasic()
                    .setDeliveryGroupPolicy(BroadcastOptions.DELIVERY_GROUP_POLICY_MERGED)
                    .setDeliveryGroupMatchingKey(DropBoxManager.ACTION_DROPBOX_ENTRY_ADDED,
                            intent.getStringExtra(DropBoxManager.EXTRA_TAG))
                    .setDeliveryGroupExtrasMerger(extrasMerger)
                    .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_UNTIL_ACTIVE)
                    .toBundle();
        }

        /**
         * Schedule a dropbox broadcast to be sent asynchronously.
         */
        public void sendBroadcast(String tag, long time) {
            sendMessage(obtainMessage(MSG_SEND_BROADCAST, createIntent(tag, time)));
        }

        /**
         * Possibly schedule a delayed dropbox broadcast. The broadcast will only be scheduled if
         * no broadcast is currently scheduled. Otherwise updated the scheduled broadcast with the
         * new intent information, effectively dropping the previous broadcast.
         */
        public void maybeDeferBroadcast(String tag, long time) {
            synchronized (mLock) {
                final Intent intent = mDeferredMap.get(tag);
                if (intent == null) {
                    // Schedule new delayed broadcast.
                    mDeferredMap.put(tag, createIntent(tag, time));
                    sendMessageDelayed(obtainMessage(MSG_SEND_DEFERRED_BROADCAST, tag),
                            mLowPriorityRateLimitPeriod);
                } else {
                    // Broadcast is already scheduled. Update intent with new data.
                    intent.putExtra(DropBoxManager.EXTRA_TIME, time);
                    final int dropped = intent.getIntExtra(DropBoxManager.EXTRA_DROPPED_COUNT, 0);
                    intent.putExtra(DropBoxManager.EXTRA_DROPPED_COUNT, dropped + 1);
                    return;
                }
            }
        }
    }

    /**
     * Creates an instance of managed drop box storage using the default dropbox
     * directory.
     *
     * @param context to use for receiving free space & gservices intents
     */
    public DropBoxManagerService(final Context context) {
        this(context, new File("/data/system/dropbox"), FgThread.get().getLooper());
    }

    /**
     * Creates an instance of managed drop box storage.  Normally there is one of these
     * run by the system, but others can be created for testing and other purposes.
     *
     * @param context to use for receiving free space & gservices intents
     * @param path to store drop box entries in
     */
    @VisibleForTesting
    public DropBoxManagerService(final Context context, File path, Looper looper) {
        super(context);
        mDropBoxDir = path;
        mContentResolver = getContext().getContentResolver();
        mHandler = new DropBoxManagerBroadcastHandler(looper);
        LocalServices.addService(DropBoxManagerInternal.class, new DropBoxManagerInternalImpl());
    }

    @Override
    public void onStart() {
        publishBinderService(Context.DROPBOX_SERVICE, mStub);

        // The real work gets done lazily in init() -- that way service creation always
        // succeeds, and things like disk problems cause individual method failures.
    }

    @Override
    public void onBootPhase(int phase) {
        switch (phase) {
            case PHASE_SYSTEM_SERVICES_READY:
                IntentFilter filter = new IntentFilter();
                filter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
                getContext().registerReceiver(mReceiver, filter);

                mContentResolver.registerContentObserver(
                    Settings.Global.CONTENT_URI, true,
                    new ContentObserver(new Handler()) {
                        @Override
                        public void onChange(boolean selfChange) {
                            mReceiver.onReceive(getContext(), (Intent) null);
                        }
                    });

                getLowPriorityResourceConfigs();
                break;

            case PHASE_BOOT_COMPLETED:
                mBooted = true;
                break;
        }
    }

    /** Retrieves the binder stub -- for test instances */
    public IDropBoxManagerService getServiceStub() {
        return mStub;
    }

    public void addData(String tag, byte[] data, int flags) {
        addEntry(tag, new ByteArrayInputStream(data), data.length, flags);
    }

    public void addFile(String tag, ParcelFileDescriptor fd, int flags) {
        final StructStat stat;
        try {
            stat = Os.fstat(fd.getFileDescriptor());

            // Verify caller isn't playing games with pipes or sockets
            if (!OsConstants.S_ISREG(stat.st_mode)) {
                throw new IllegalArgumentException(tag + " entry must be real file");
            }
        } catch (ErrnoException e) {
            throw new IllegalArgumentException(e);
        }

        addEntry(tag, new ParcelFileDescriptor.AutoCloseInputStream(fd), stat.st_size, flags);
    }

    public void addEntry(String tag, InputStream in, long length, int flags) {
        // If entry being added is large, and if it's not already compressed,
        // then we'll force compress it during write
        boolean forceCompress = false;
        if ((flags & DropBoxManager.IS_GZIPPED) == 0
                && length > COMPRESS_THRESHOLD_BYTES) {
            forceCompress = true;
            flags |= DropBoxManager.IS_GZIPPED;
        }

        addEntry(tag, new SimpleEntrySource(in, length, forceCompress), flags);
    }

    /**
     * Simple entry which contains data ready to be written.
     */
    public static class SimpleEntrySource implements EntrySource {
        private final InputStream in;
        private final long length;
        private final boolean forceCompress;

        public SimpleEntrySource(InputStream in, long length, boolean forceCompress) {
            this.in = in;
            this.length = length;
            this.forceCompress = forceCompress;
        }

        public long length() {
            return length;
        }

        @Override
        public void writeTo(FileDescriptor fd) throws IOException {
            // No need to buffer the output here, since data is either coming
            // from an in-memory buffer, or another file on disk; if we buffered
            // we'd lose out on sendfile() optimizations
            if (forceCompress) {
                final GZIPOutputStream gzipOutputStream =
                        new GZIPOutputStream(new FileOutputStream(fd));
                FileUtils.copy(in, gzipOutputStream);
                gzipOutputStream.close();
            } else {
                FileUtils.copy(in, new FileOutputStream(fd));
            }
        }

        @Override
        public void close() throws IOException {
            FileUtils.closeQuietly(in);
        }
    }

    public void addEntry(String tag, EntrySource entry, int flags) {
        File temp = null;
        try {
            Slog.i(TAG, "add tag=" + tag + " isTagEnabled=" + isTagEnabled(tag)
                    + " flags=0x" + Integer.toHexString(flags));
            if ((flags & DropBoxManager.IS_EMPTY) != 0) throw new IllegalArgumentException();

            init();

            // Bail early if we know tag is disabled
            if (!isTagEnabled(tag)) return;

            // Drop entries which are too large for our quota
            final long length = entry.length();
            final long max = trimToFit();
            if (length > max) {
                // Log and fall through to create empty tombstone below
                Slog.w(TAG, "Dropping: " + tag + " (" + length + " > " + max + " bytes)");
                logDropboxDropped(
                        FrameworkStatsLog.DROPBOX_ENTRY_DROPPED__DROP_REASON__ENTRY_TOO_LARGE,
                        tag,
                        0);
            } else {
                temp = new File(mDropBoxDir, "drop" + Thread.currentThread().getId() + ".tmp");
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    entry.writeTo(out.getFD());
                }
            }

            // Writing above succeeded, so create the finalized entry
            long time = createEntry(temp, tag, flags);
            temp = null;

            // Call sendBroadcast after returning from this call to avoid deadlock. In particular
            // the caller may be holding the WindowManagerService lock but sendBroadcast requires a
            // lock in ActivityManagerService. ActivityManagerService has been caught holding that
            // very lock while waiting for the WindowManagerService lock.
            if (mLowPriorityTags != null && mLowPriorityTags.contains(tag)) {
                // Rate limit low priority Dropbox entries
                mHandler.maybeDeferBroadcast(tag, time);
            } else {
                mHandler.sendBroadcast(tag, time);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Can't write: " + tag, e);
            logDropboxDropped(
                    FrameworkStatsLog.DROPBOX_ENTRY_DROPPED__DROP_REASON__WRITE_FAILURE,
                    tag,
                    0);
        } finally {
            IoUtils.closeQuietly(entry);
            if (temp != null) temp.delete();
        }
    }

    private void logDropboxDropped(int reason, String tag, long entryAge) {
        FrameworkStatsLog.write(FrameworkStatsLog.DROPBOX_ENTRY_DROPPED, reason, tag, entryAge);
    }

    public boolean isTagEnabled(String tag) {
        final long token = Binder.clearCallingIdentity();
        try {
            if (DISABLED_BY_DEFAULT_TAGS.contains(tag)) {
                return "enabled".equals(Settings.Global.getString(
                    mContentResolver, Settings.Global.DROPBOX_TAG_PREFIX + tag));
            } else {
                return !"disabled".equals(Settings.Global.getString(
                    mContentResolver, Settings.Global.DROPBOX_TAG_PREFIX + tag));
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean checkPermission(int callingUid, String callingPackage,
            @Nullable String callingAttributionTag) {
        // If callers have this permission, then we don't need to check
        // USAGE_STATS, because they are part of the system and have agreed to
        // check USAGE_STATS before passing the data along.
        if (getContext().checkCallingPermission(android.Manifest.permission.PEEK_DROPBOX_DATA)
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }


        String permission = Manifest.permission.READ_LOGS;
        if (Flags.enableReadDropboxPermission()
                && CompatChanges.isChangeEnabled(ENFORCE_READ_DROPBOX_DATA, callingUid)) {
            permission = Manifest.permission.READ_DROPBOX_DATA;
        }

        // Callers always need this permission
        getContext().enforceCallingOrSelfPermission(permission, TAG);


        // Callers also need the ability to read usage statistics
        switch (getContext().getSystemService(AppOpsManager.class).noteOp(
                AppOpsManager.OP_GET_USAGE_STATS, callingUid, callingPackage, callingAttributionTag,
                null)) {
            case AppOpsManager.MODE_ALLOWED:
                return true;
            case AppOpsManager.MODE_DEFAULT:
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.PACKAGE_USAGE_STATS, TAG);
                return true;
            default:
                return false;
        }
    }

    public synchronized DropBoxManager.Entry getNextEntry(String tag, long millis,
            String callingPackage, @Nullable String callingAttributionTag) {
        if (!checkPermission(Binder.getCallingUid(), callingPackage, callingAttributionTag)) {
            return null;
        }

        try {
            init();
        } catch (IOException e) {
            Slog.e(TAG, "Can't init", e);
            return null;
        }

        FileList list = tag == null ? mAllFiles : mFilesByTag.get(tag);
        if (list == null) return null;

        for (EntryFile entry : list.contents.tailSet(new EntryFile(millis + 1))) {
            if (entry.tag == null) continue;
            if ((entry.flags & DropBoxManager.IS_EMPTY) != 0) {
                return new DropBoxManager.Entry(entry.tag, entry.timestampMillis);
            }
            final File file = entry.getFile(mDropBoxDir);
            try {
                return new DropBoxManager.Entry(
                        entry.tag, entry.timestampMillis, file, entry.flags);
            } catch (IOException e) {
                Slog.wtf(TAG, "Can't read: " + file, e);
                // Continue to next file
            }
        }

        return null;
    }

    private synchronized void setLowPriorityRateLimit(long period) {
        mLowPriorityRateLimitPeriod = period;
    }

    private synchronized void addLowPriorityTag(String tag) {
        mLowPriorityTags.add(tag);
    }

    private synchronized void removeLowPriorityTag(String tag) {
        mLowPriorityTags.remove(tag);
    }

    private synchronized void restoreDefaults() {
        getLowPriorityResourceConfigs();
    }

    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(getContext(), TAG, pw)) return;

        try {
            init();
        } catch (IOException e) {
            pw.println("Can't initialize: " + e);
            Slog.e(TAG, "Can't init", e);
            return;
        }

        if (PROFILE_DUMP) Debug.startMethodTracing("/data/trace/dropbox.dump");

        StringBuilder out = new StringBuilder();
        boolean doPrint = false, doFile = false;
        boolean dumpProto = false;
        ArrayList<String> searchArgs = new ArrayList<String>();
        for (int i = 0; args != null && i < args.length; i++) {
            if (args[i].equals("-p") || args[i].equals("--print")) {
                doPrint = true;
            } else if (args[i].equals("-f") || args[i].equals("--file")) {
                doFile = true;
            } else if (args[i].equals("--proto")) {
                dumpProto = true;
            } else if (args[i].equals("-h") || args[i].equals("--help")) {
                pw.println("Dropbox (dropbox) dump options:");
                pw.println("  [-h|--help] [-p|--print] [-f|--file] [timestamp]");
                pw.println("    -h|--help: print this help");
                pw.println("    -p|--print: print full contents of each entry");
                pw.println("    -f|--file: print path of each entry's file");
                pw.println("    --proto: dump data to proto");
                pw.println("  [timestamp] optionally filters to only those entries.");
                return;
            } else if (args[i].startsWith("-")) {
                out.append("Unknown argument: ").append(args[i]).append("\n");
            } else {
                searchArgs.add(args[i]);
            }
        }

        if (dumpProto) {
            dumpProtoLocked(fd, searchArgs);
            return;
        }

        out.append("Drop box contents: ").append(mAllFiles.contents.size()).append(" entries\n");
        out.append("Max entries: ").append(mMaxFiles).append("\n");

        out.append("Low priority rate limit period: ");
        out.append(mLowPriorityRateLimitPeriod).append(" ms\n");
        out.append("Low priority tags: ").append(mLowPriorityTags).append("\n");

        if (!searchArgs.isEmpty()) {
            out.append("Searching for:");
            for (String a : searchArgs) out.append(" ").append(a);
            out.append("\n");
        }

        int numFound = 0;
        out.append("\n");
        for (EntryFile entry : mAllFiles.contents) {
            if (!matchEntry(entry, searchArgs)) continue;

            numFound++;
            if (doPrint) out.append("========================================\n");

            String date = TimeMigrationUtils.formatMillisWithFixedFormat(entry.timestampMillis);
            out.append(date).append(" ").append(entry.tag == null ? "(no tag)" : entry.tag);

            final File file = entry.getFile(mDropBoxDir);
            if (file == null) {
                out.append(" (no file)\n");
                continue;
            } else if ((entry.flags & DropBoxManager.IS_EMPTY) != 0) {
                out.append(" (contents lost)\n");
                continue;
            } else {
                out.append(" (");
                if ((entry.flags & DropBoxManager.IS_GZIPPED) != 0) out.append("compressed ");
                out.append((entry.flags & DropBoxManager.IS_TEXT) != 0 ? "text" : "data");
                out.append(", ").append(file.length()).append(" bytes)\n");
            }

            if (doFile || (doPrint && (entry.flags & DropBoxManager.IS_TEXT) == 0)) {
                if (!doPrint) out.append("    ");
                out.append(file.getPath()).append("\n");
            }

            if ((entry.flags & DropBoxManager.IS_TEXT) != 0 && doPrint) {
                DropBoxManager.Entry dbe = null;
                InputStreamReader isr = null;
                try {
                    dbe = new DropBoxManager.Entry(
                             entry.tag, entry.timestampMillis, file, entry.flags);

                    if (doPrint) {
                        isr = new InputStreamReader(dbe.getInputStream());
                        char[] buf = new char[4096];
                        boolean newline = false;
                        for (;;) {
                            int n = isr.read(buf);
                            if (n <= 0) break;
                            out.append(buf, 0, n);
                            newline = (buf[n - 1] == '\n');

                            // Flush periodically when printing to avoid out-of-memory.
                            if (out.length() > 65536) {
                                pw.write(out.toString());
                                out.setLength(0);
                            }
                        }
                        if (!newline) out.append("\n");
                    }
                } catch (IOException e) {
                    out.append("*** ").append(e.toString()).append("\n");
                    Slog.e(TAG, "Can't read: " + file, e);
                } finally {
                    if (dbe != null) dbe.close();
                    if (isr != null) {
                        try {
                            isr.close();
                        } catch (IOException unused) {
                        }
                    }
                }
            }

            if (doPrint) out.append("\n");
        }

        if (numFound == 0) out.append("(No entries found.)\n");

        if (args == null || args.length == 0) {
            if (!doPrint) out.append("\n");
            out.append("Usage: dumpsys dropbox [--print|--file] [YYYY-mm-dd] [HH:MM:SS] [tag]\n");
        }

        pw.write(out.toString());
        if (PROFILE_DUMP) Debug.stopMethodTracing();
    }

    private boolean matchEntry(EntryFile entry, ArrayList<String> searchArgs) {
        String date = TimeMigrationUtils.formatMillisWithFixedFormat(entry.timestampMillis);
        boolean match = true;
        int numArgs = searchArgs.size();
        for (int i = 0; i < numArgs && match; i++) {
            String arg = searchArgs.get(i);
            match = (date.contains(arg) || arg.equals(entry.tag));
        }
        return match;
    }

    private void dumpProtoLocked(FileDescriptor fd, ArrayList<String> searchArgs) {
        final ProtoOutputStream proto = new ProtoOutputStream(fd);

        for (EntryFile entry : mAllFiles.contents) {
            if (!matchEntry(entry, searchArgs)) continue;

            final File file = entry.getFile(mDropBoxDir);
            if ((file == null) || ((entry.flags & DropBoxManager.IS_EMPTY) != 0)) {
                continue;
            }

            final long bToken = proto.start(DropBoxManagerServiceDumpProto.ENTRIES);
            proto.write(DropBoxManagerServiceDumpProto.Entry.TIME_MS, entry.timestampMillis);
            try (
                DropBoxManager.Entry dbe = new DropBoxManager.Entry(
                        entry.tag, entry.timestampMillis, file, entry.flags);
                InputStream is = dbe.getInputStream();
            ) {
                if (is != null) {
                    byte[] buf = new byte[PROTO_MAX_DATA_BYTES];
                    int readBytes = 0;
                    int n = 0;
                    while (n >= 0 && (readBytes += n) < PROTO_MAX_DATA_BYTES) {
                        n = is.read(buf, readBytes, PROTO_MAX_DATA_BYTES - readBytes);
                    }
                    proto.write(DropBoxManagerServiceDumpProto.Entry.DATA,
                            Arrays.copyOf(buf, readBytes));
                }
            } catch (IOException e) {
                Slog.e(TAG, "Can't read: " + file, e);
            }

            proto.end(bToken);
        }

        proto.flush();
    }

    ///////////////////////////////////////////////////////////////////////////

    /** Chronologically sorted list of {@link EntryFile} */
    private static final class FileList implements Comparable<FileList> {
        public int blocks = 0;
        public final TreeSet<EntryFile> contents = new TreeSet<EntryFile>();

        /** Sorts bigger FileList instances before smaller ones. */
        public final int compareTo(FileList o) {
            if (blocks != o.blocks) return o.blocks - blocks;
            if (this == o) return 0;
            if (hashCode() < o.hashCode()) return -1;
            if (hashCode() > o.hashCode()) return 1;
            return 0;
        }
    }

    /**
     * Metadata describing an on-disk log file.
     *
     * Note its instances do no have knowledge on what directory they're stored, just to save
     * 4/8 bytes per instance.  Instead, {@link #getFile} takes a directory so it can build a
     * fullpath.
     */
    @VisibleForTesting
    static final class EntryFile implements Comparable<EntryFile> {
        public final String tag;
        public final long timestampMillis;
        public final int flags;
        public final int blocks;

        /** Sorts earlier EntryFile instances before later ones. */
        public final int compareTo(EntryFile o) {
            int comp = Long.compare(timestampMillis, o.timestampMillis);
            if (comp != 0) return comp;

            comp = ObjectUtils.compare(tag, o.tag);
            if (comp != 0) return comp;

            comp = Integer.compare(flags, o.flags);
            if (comp != 0) return comp;

            return Integer.compare(hashCode(), o.hashCode());
        }

        /**
         * Moves an existing temporary file to a new log filename.
         *
         * @param temp file to rename
         * @param dir to store file in
         * @param tag to use for new log file name
         * @param timestampMillis of log entry
         * @param flags for the entry data
         * @param blockSize to use for space accounting
         * @throws IOException if the file can't be moved
         */
        public EntryFile(File temp, File dir, String tag,long timestampMillis,
                         int flags, int blockSize) throws IOException {
            if ((flags & DropBoxManager.IS_EMPTY) != 0) throw new IllegalArgumentException();

            this.tag = TextUtils.safeIntern(tag);
            this.timestampMillis = timestampMillis;
            this.flags = flags;

            final File file = this.getFile(dir);
            if (!temp.renameTo(file)) {
                throw new IOException("Can't rename " + temp + " to " + file);
            }
            this.blocks = (int) ((file.length() + blockSize - 1) / blockSize);
        }

        /**
         * Creates a zero-length tombstone for a file whose contents were lost.
         *
         * @param dir to store file in
         * @param tag to use for new log file name
         * @param timestampMillis of log entry
         * @throws IOException if the file can't be created.
         */
        public EntryFile(File dir, String tag, long timestampMillis) throws IOException {
            this.tag = TextUtils.safeIntern(tag);
            this.timestampMillis = timestampMillis;
            this.flags = DropBoxManager.IS_EMPTY;
            this.blocks = 0;
            new FileOutputStream(getFile(dir)).close();
        }

        /**
         * Extracts metadata from an existing on-disk log filename.
         *
         * Note when a filename is not recognizable, it will create an instance that
         * {@link #hasFile()} would return false on, and also remove the file.
         *
         * @param file name of existing log file
         * @param blockSize to use for space accounting
         */
        public EntryFile(File file, int blockSize) {

            boolean parseFailure = false;

            String name = file.getName();
            int flags = 0;
            String tag = null;
            long millis = 0;

            final int at = name.lastIndexOf('@');
            if (at < 0) {
                parseFailure = true;
            } else {
                tag = Uri.decode(name.substring(0, at));
                if (name.endsWith(".gz")) {
                    flags |= DropBoxManager.IS_GZIPPED;
                    name = name.substring(0, name.length() - 3);
                }
                if (name.endsWith(".lost")) {
                    flags |= DropBoxManager.IS_EMPTY;
                    name = name.substring(at + 1, name.length() - 5);
                } else if (name.endsWith(".txt")) {
                    flags |= DropBoxManager.IS_TEXT;
                    name = name.substring(at + 1, name.length() - 4);
                } else if (name.endsWith(".dat")) {
                    name = name.substring(at + 1, name.length() - 4);
                } else {
                    parseFailure = true;
                }
                if (!parseFailure) {
                    try {
                        millis = Long.parseLong(name);
                    } catch (NumberFormatException e) {
                        parseFailure = true;
                    }
                }
            }
            if (parseFailure) {
                Slog.wtf(TAG, "Invalid filename: " + file);

                // Remove the file and return an empty instance.
                file.delete();
                this.tag = null;
                this.flags = DropBoxManager.IS_EMPTY;
                this.timestampMillis = 0;
                this.blocks = 0;
                return;
            }

            this.blocks = (int) ((file.length() + blockSize - 1) / blockSize);
            this.tag = TextUtils.safeIntern(tag);
            this.flags = flags;
            this.timestampMillis = millis;
        }

        /**
         * Creates a EntryFile object with only a timestamp for comparison purposes.
         * @param millis to compare with.
         */
        public EntryFile(long millis) {
            this.tag = null;
            this.timestampMillis = millis;
            this.flags = DropBoxManager.IS_EMPTY;
            this.blocks = 0;
        }

        /**
         * @return whether an entry actually has a backing file, or it's an empty "tombstone"
         * entry.
         */
        public boolean hasFile() {
            return tag != null;
        }

        /** @return File extension for the flags. */
        private String getExtension() {
            if ((flags &  DropBoxManager.IS_EMPTY) != 0) {
                return ".lost";
            }
            return ((flags & DropBoxManager.IS_TEXT) != 0 ? ".txt" : ".dat") +
                    ((flags & DropBoxManager.IS_GZIPPED) != 0 ? ".gz" : "");
        }

        /**
         * @return filename for this entry without the pathname.
         */
        public String getFilename() {
            return hasFile() ? Uri.encode(tag) + "@" + timestampMillis + getExtension() : null;
        }

        /**
         * Get a full-path {@link File} representing this entry.
         * @param dir Parent directly.  The caller needs to pass it because {@link EntryFile}s don't
         *            know in which directory they're stored.
         */
        public File getFile(File dir) {
            return hasFile() ? new File(dir, getFilename()) : null;
        }

        /**
         * If an entry has a backing file, remove it.
         */
        public void deleteFile(File dir) {
            if (hasFile()) {
                getFile(dir).delete();
            }
        }
    }

    ///////////////////////////////////////////////////////////////////////////

    /** If never run before, scans disk contents to build in-memory tracking data. */
    private synchronized void init() throws IOException {
        if (mStatFs == null) {
            if (!mDropBoxDir.isDirectory() && !mDropBoxDir.mkdirs()) {
                throw new IOException("Can't mkdir: " + mDropBoxDir);
            }
            try {
                mStatFs = new StatFs(mDropBoxDir.getPath());
                mBlockSize = mStatFs.getBlockSize();
            } catch (IllegalArgumentException e) {  // StatFs throws this on error
                throw new IOException("Can't statfs: " + mDropBoxDir);
            }
        }

        if (mAllFiles == null) {
            File[] files = mDropBoxDir.listFiles();
            if (files == null) throw new IOException("Can't list files: " + mDropBoxDir);

            mAllFiles = new FileList();
            mFilesByTag = new ArrayMap<>();

            // Scan pre-existing files.
            for (File file : files) {
                if (file.getName().endsWith(".tmp")) {
                    Slog.i(TAG, "Cleaning temp file: " + file);
                    file.delete();
                    continue;
                }

                EntryFile entry = new EntryFile(file, mBlockSize);

                if (entry.hasFile()) {
                    // Enroll only when the filename is valid.  Otherwise the above constructor
                    // has removed the file already.
                    enrollEntry(entry);
                }
            }
        }
    }

    /** Adds a disk log file to in-memory tracking for accounting and enumeration. */
    private synchronized void enrollEntry(EntryFile entry) {
        mAllFiles.contents.add(entry);
        mAllFiles.blocks += entry.blocks;

        // mFilesByTag is used for trimming, so don't list empty files.
        // (Zero-length/lost files are trimmed by date from mAllFiles.)

        if (entry.hasFile() && entry.blocks > 0) {
            FileList tagFiles = mFilesByTag.get(entry.tag);
            if (tagFiles == null) {
                tagFiles = new FileList();
                mFilesByTag.put(TextUtils.safeIntern(entry.tag), tagFiles);
            }
            tagFiles.contents.add(entry);
            tagFiles.blocks += entry.blocks;
        }
    }

    /** Moves a temporary file to a final log filename and enrolls it. */
    private synchronized long createEntry(File temp, String tag, int flags) throws IOException {
        long t = System.currentTimeMillis();

        // Require each entry to have a unique timestamp; if there are entries
        // >10sec in the future (due to clock skew), drag them back to avoid
        // keeping them around forever.

        SortedSet<EntryFile> tail = mAllFiles.contents.tailSet(new EntryFile(t + 10000));
        EntryFile[] future = null;
        if (!tail.isEmpty()) {
            future = tail.toArray(new EntryFile[tail.size()]);
            tail.clear();  // Remove from mAllFiles
        }

        if (!mAllFiles.contents.isEmpty()) {
            t = Math.max(t, mAllFiles.contents.last().timestampMillis + 1);
        }

        if (future != null) {
            for (EntryFile late : future) {
                mAllFiles.blocks -= late.blocks;
                FileList tagFiles = mFilesByTag.get(late.tag);
                if (tagFiles != null && tagFiles.contents.remove(late)) {
                    tagFiles.blocks -= late.blocks;
                }
                if ((late.flags & DropBoxManager.IS_EMPTY) == 0) {
                    enrollEntry(new EntryFile(late.getFile(mDropBoxDir), mDropBoxDir,
                            late.tag, t++, late.flags, mBlockSize));
                } else {
                    enrollEntry(new EntryFile(mDropBoxDir, late.tag, t++));
                }
            }
        }

        if (temp == null) {
            enrollEntry(new EntryFile(mDropBoxDir, tag, t));
        } else {
            enrollEntry(new EntryFile(temp, mDropBoxDir, tag, t, flags, mBlockSize));
        }
        return t;
    }

    /**
     * Trims the files on disk to make sure they aren't using too much space.
     * @return the overall quota for storage (in bytes)
     */
    private synchronized long trimToFit() throws IOException {
        // Expunge aged items (including tombstones marking deleted data).

        int ageSeconds = Settings.Global.getInt(mContentResolver,
                Settings.Global.DROPBOX_AGE_SECONDS, DEFAULT_AGE_SECONDS);
        mMaxFiles = Settings.Global.getInt(mContentResolver,
                Settings.Global.DROPBOX_MAX_FILES,
                (ActivityManager.isLowRamDeviceStatic()
                        ?  DEFAULT_MAX_FILES_LOWRAM : DEFAULT_MAX_FILES));
        long curTimeMillis = System.currentTimeMillis();
        long cutoffMillis = curTimeMillis - ageSeconds * 1000;
        while (!mAllFiles.contents.isEmpty()) {
            EntryFile entry = mAllFiles.contents.first();
            if (entry.timestampMillis > cutoffMillis && mAllFiles.contents.size() < mMaxFiles) {
                break;
            }

            logDropboxDropped(
                    FrameworkStatsLog.DROPBOX_ENTRY_DROPPED__DROP_REASON__AGED,
                    entry.tag,
                    curTimeMillis - entry.timestampMillis);

            FileList tag = mFilesByTag.get(entry.tag);
            if (tag != null && tag.contents.remove(entry)) tag.blocks -= entry.blocks;
            if (mAllFiles.contents.remove(entry)) mAllFiles.blocks -= entry.blocks;
            entry.deleteFile(mDropBoxDir);
        }

        // Compute overall quota (a fraction of available free space) in blocks.
        // The quota changes dynamically based on the amount of free space;
        // that way when lots of data is available we can use it, but we'll get
        // out of the way if storage starts getting tight.

        long uptimeMillis = SystemClock.uptimeMillis();
        if (uptimeMillis > mCachedQuotaUptimeMillis + QUOTA_RESCAN_MILLIS) {
            int quotaPercent = Settings.Global.getInt(mContentResolver,
                    Settings.Global.DROPBOX_QUOTA_PERCENT, DEFAULT_QUOTA_PERCENT);
            int reservePercent = Settings.Global.getInt(mContentResolver,
                    Settings.Global.DROPBOX_RESERVE_PERCENT, DEFAULT_RESERVE_PERCENT);
            int quotaKb = Settings.Global.getInt(mContentResolver,
                    Settings.Global.DROPBOX_QUOTA_KB, DEFAULT_QUOTA_KB);

            String dirPath = mDropBoxDir.getPath();
            try {
                mStatFs.restat(dirPath);
            } catch (IllegalArgumentException e) {  // restat throws this on error
                throw new IOException("Can't restat: " + mDropBoxDir);
            }
            long available = mStatFs.getAvailableBlocksLong();
            long nonreserved = available - mStatFs.getBlockCountLong() * reservePercent / 100;
            long maxAvailableLong = nonreserved * quotaPercent / 100;
            int maxAvailable = Math.toIntExact(Math.max(0,
                    Math.min(maxAvailableLong, Integer.MAX_VALUE)));
            int maximum = quotaKb * 1024 / mBlockSize;
            mCachedQuotaBlocks = Math.min(maximum, maxAvailable);
            mCachedQuotaUptimeMillis = uptimeMillis;
        }

        // If we're using too much space, delete old items to make room.
        //
        // We trim each tag independently (this is why we keep per-tag lists).
        // Space is "fairly" shared between tags -- they are all squeezed
        // equally until enough space is reclaimed.
        //
        // A single circular buffer (a la logcat) would be simpler, but this
        // way we can handle fat/bursty data (like 1MB+ bugreports, 300KB+
        // kernel crash dumps, and 100KB+ ANR reports) without swamping small,
        // well-behaved data streams (event statistics, profile data, etc).
        //
        // Deleted files are replaced with zero-length tombstones to mark what
        // was lost.  Tombstones are expunged by age (see above).

        if (mAllFiles.blocks > mCachedQuotaBlocks) {
            // Find a fair share amount of space to limit each tag
            int unsqueezed = mAllFiles.blocks, squeezed = 0;
            TreeSet<FileList> tags = new TreeSet<FileList>(mFilesByTag.values());
            for (FileList tag : tags) {
                if (squeezed > 0 && tag.blocks <= (mCachedQuotaBlocks - unsqueezed) / squeezed) {
                    break;
                }
                unsqueezed -= tag.blocks;
                squeezed++;
            }
            int tagQuota = (mCachedQuotaBlocks - unsqueezed) / squeezed;

            // Remove old items from each tag until it meets the per-tag quota.
            for (FileList tag : tags) {
                if (mAllFiles.blocks < mCachedQuotaBlocks) break;
                while (tag.blocks > tagQuota && !tag.contents.isEmpty()) {
                    EntryFile entry = tag.contents.first();
                    logDropboxDropped(
                            FrameworkStatsLog.DROPBOX_ENTRY_DROPPED__DROP_REASON__CLEARING_DATA,
                            entry.tag,
                            curTimeMillis - entry.timestampMillis);

                    if (tag.contents.remove(entry)) tag.blocks -= entry.blocks;
                    if (mAllFiles.contents.remove(entry)) mAllFiles.blocks -= entry.blocks;

                    try {
                        entry.deleteFile(mDropBoxDir);
                        enrollEntry(new EntryFile(mDropBoxDir, entry.tag, entry.timestampMillis));
                    } catch (IOException e) {
                        Slog.e(TAG, "Can't write tombstone file", e);
                    }
                }
            }
        }

        return mCachedQuotaBlocks * mBlockSize;
    }

    private void getLowPriorityResourceConfigs() {
        mLowPriorityRateLimitPeriod = Resources.getSystem().getInteger(
                R.integer.config_dropboxLowPriorityBroadcastRateLimitPeriod);

        final String[] lowPrioritytags = Resources.getSystem().getStringArray(
                R.array.config_dropboxLowPriorityTags);
        final int size = lowPrioritytags.length;
        if (size == 0) {
            mLowPriorityTags = null;
            return;
        }
        mLowPriorityTags = new ArraySet(size);
        for (int i = 0; i < size; i++) {
            mLowPriorityTags.add(lowPrioritytags[i]);
        }
    }

    private final class DropBoxManagerInternalImpl extends DropBoxManagerInternal {
        @Override
        public void addEntry(String tag, EntrySource entry, int flags) {
            DropBoxManagerService.this.addEntry(tag, entry, flags);
        }
    }
}
