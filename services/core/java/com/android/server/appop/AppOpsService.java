/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.appop;

import static android.app.AppOpsManager.MAX_PRIORITY_UID_STATE;
import static android.app.AppOpsManager.MIN_PRIORITY_UID_STATE;
import static android.app.AppOpsManager.OP_FLAGS_ALL;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.OP_PLAY_AUDIO;
import static android.app.AppOpsManager.UID_STATE_BACKGROUND;
import static android.app.AppOpsManager.UID_STATE_CACHED;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND_SERVICE;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND_SERVICE_LOCATION;
import static android.app.AppOpsManager.UID_STATE_MAX_LAST_NON_RESTRICTED;
import static android.app.AppOpsManager.UID_STATE_PERSISTENT;
import static android.app.AppOpsManager.UID_STATE_TOP;
import static android.app.AppOpsManager.modeToName;
import static android.app.AppOpsManager.opToName;
import static android.app.AppOpsManager.resolveFirstUnrestrictedUidState;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityThread;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManager.HistoricalOps;
import android.app.AppOpsManager.HistoricalOpsRequest;
import android.app.AppOpsManager.Mode;
import android.app.AppOpsManager.OpEntry;
import android.app.AppOpsManager.OpFlags;
import android.app.AppOpsManagerInternal;
import android.app.AppOpsManagerInternal.CheckOpsDelegate;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserInfo;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageManagerInternal;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.KeyValueListParser;
import android.util.LongSparseArray;
import android.util.LongSparseLongArray;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsNotedCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.os.Zygote;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.LockGuard;

import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class AppOpsService extends IAppOpsService.Stub {
    static final String TAG = "AppOps";
    static final boolean DEBUG = false;

    private static final int NO_VERSION = -1;
    /** Increment by one every time and add the corresponding upgrade logic in
     *  {@link #upgradeLocked(int)} below. The first version was 1 */
    private static final int CURRENT_VERSION = 1;

    // Write at most every 30 minutes.
    static final long WRITE_DELAY = DEBUG ? 1000 : 30*60*1000;

    // Constant meaning that any UID should be matched when dispatching callbacks
    private static final int UID_ANY = -2;

    // Map from process states to the uid states we track.
    private static final int[] PROCESS_STATE_TO_UID_STATE = new int[] {
        UID_STATE_PERSISTENT,           // ActivityManager.PROCESS_STATE_PERSISTENT
        UID_STATE_PERSISTENT,           // ActivityManager.PROCESS_STATE_PERSISTENT_UI
        UID_STATE_TOP,                  // ActivityManager.PROCESS_STATE_TOP
        UID_STATE_FOREGROUND_SERVICE_LOCATION,
                                        // ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE_LOCATION
        UID_STATE_FOREGROUND_SERVICE,   // ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
        UID_STATE_FOREGROUND,           // ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
        UID_STATE_FOREGROUND,           // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_BACKUP
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_SERVICE
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_RECEIVER
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_TOP_SLEEPING
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_HEAVY_WEIGHT
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_HOME
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_LAST_ACTIVITY
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_CACHED_RECENT
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_CACHED_EMPTY
        UID_STATE_CACHED,               // ActivityManager.PROCESS_STATE_NONEXISTENT
    };

    Context mContext;
    final AtomicFile mFile;
    final Handler mHandler;

    private final AppOpsManagerInternalImpl mAppOpsManagerInternal
            = new AppOpsManagerInternalImpl();

    boolean mWriteScheduled;
    boolean mFastWriteScheduled;
    final Runnable mWriteRunner = new Runnable() {
        public void run() {
            synchronized (AppOpsService.this) {
                mWriteScheduled = false;
                mFastWriteScheduled = false;
                AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
                    @Override protected Void doInBackground(Void... params) {
                        writeState();
                        return null;
                    }
                };
                task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, (Void[])null);
            }
        }
    };

    @VisibleForTesting
    final SparseArray<UidState> mUidStates = new SparseArray<>();

    private final HistoricalRegistry mHistoricalRegistry = new HistoricalRegistry(this);

    long mLastRealtime;

    /*
     * These are app op restrictions imposed per user from various parties.
     */
    private final ArrayMap<IBinder, ClientRestrictionState> mOpUserRestrictions = new ArrayMap<>();

    SparseIntArray mProfileOwners;

    @GuardedBy("this")
    private CheckOpsDelegate mCheckOpsDelegate;

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the AppOpsService lock.
     */
    private final class Constants extends ContentObserver {
        // Key names stored in the settings value.
        private static final String KEY_TOP_STATE_SETTLE_TIME = "top_state_settle_time";
        private static final String KEY_FG_SERVICE_STATE_SETTLE_TIME
                = "fg_service_state_settle_time";
        private static final String KEY_BG_STATE_SETTLE_TIME = "bg_state_settle_time";

        /**
         * How long we want for a drop in uid state from top to settle before applying it.
         * @see Settings.Global#APP_OPS_CONSTANTS
         * @see #KEY_TOP_STATE_SETTLE_TIME
         */
        public long TOP_STATE_SETTLE_TIME;

        /**
         * How long we want for a drop in uid state from foreground to settle before applying it.
         * @see Settings.Global#APP_OPS_CONSTANTS
         * @see #KEY_FG_SERVICE_STATE_SETTLE_TIME
         */
        public long FG_SERVICE_STATE_SETTLE_TIME;

        /**
         * How long we want for a drop in uid state from background to settle before applying it.
         * @see Settings.Global#APP_OPS_CONSTANTS
         * @see #KEY_BG_STATE_SETTLE_TIME
         */
        public long BG_STATE_SETTLE_TIME;

        private final KeyValueListParser mParser = new KeyValueListParser(',');
        private ContentResolver mResolver;

        public Constants(Handler handler) {
            super(handler);
            updateConstants();
        }

        public void startMonitoring(ContentResolver resolver) {
            mResolver = resolver;
            mResolver.registerContentObserver(
                    Settings.Global.getUriFor(Settings.Global.APP_OPS_CONSTANTS),
                    false, this);
            updateConstants();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateConstants();
        }

        private void updateConstants() {
            String value = mResolver != null ? Settings.Global.getString(mResolver,
                    Settings.Global.APP_OPS_CONSTANTS) : "";

            synchronized (AppOpsService.this) {
                try {
                    mParser.setString(value);
                } catch (IllegalArgumentException e) {
                    // Failed to parse the settings string, log this and move on
                    // with defaults.
                    Slog.e(TAG, "Bad app ops settings", e);
                }
                TOP_STATE_SETTLE_TIME = mParser.getDurationMillis(
                        KEY_TOP_STATE_SETTLE_TIME, 30 * 1000L);
                FG_SERVICE_STATE_SETTLE_TIME = mParser.getDurationMillis(
                        KEY_FG_SERVICE_STATE_SETTLE_TIME, 10 * 1000L);
                BG_STATE_SETTLE_TIME = mParser.getDurationMillis(
                        KEY_BG_STATE_SETTLE_TIME, 1 * 1000L);
            }
        }

        void dump(PrintWriter pw) {
            pw.println("  Settings:");

            pw.print("    "); pw.print(KEY_TOP_STATE_SETTLE_TIME); pw.print("=");
            TimeUtils.formatDuration(TOP_STATE_SETTLE_TIME, pw);
            pw.println();
            pw.print("    "); pw.print(KEY_FG_SERVICE_STATE_SETTLE_TIME); pw.print("=");
            TimeUtils.formatDuration(FG_SERVICE_STATE_SETTLE_TIME, pw);
            pw.println();
            pw.print("    "); pw.print(KEY_BG_STATE_SETTLE_TIME); pw.print("=");
            TimeUtils.formatDuration(BG_STATE_SETTLE_TIME, pw);
            pw.println();
        }
    }

    private final Constants mConstants;

    @VisibleForTesting
    static final class UidState {
        public final int uid;

        public int state = UID_STATE_CACHED;
        public int pendingState = UID_STATE_CACHED;
        public long pendingStateCommitTime;

        public int startNesting;
        public ArrayMap<String, Ops> pkgOps;
        public SparseIntArray opModes;

        // true indicates there is an interested observer, false there isn't but it has such an op
        public SparseBooleanArray foregroundOps;
        public boolean hasForegroundWatchers;

        public UidState(int uid) {
            this.uid = uid;
        }

        public void clear() {
            pkgOps = null;
            opModes = null;
        }

        public boolean isDefault() {
            return (pkgOps == null || pkgOps.isEmpty())
                    && (opModes == null || opModes.size() <= 0)
                    && (state == UID_STATE_CACHED
                    && (pendingState == UID_STATE_CACHED));
        }

        int evalMode(int op, int mode) {
            if (mode == AppOpsManager.MODE_FOREGROUND) {
                return state <= AppOpsManager.resolveLastRestrictedUidState(op)
                        ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_IGNORED;
            }
            return mode;
        }

        private void evalForegroundWatchers(int op, SparseArray<ArraySet<ModeCallback>> watchers,
                SparseBooleanArray which) {
            boolean curValue = which.get(op, false);
            ArraySet<ModeCallback> callbacks = watchers.get(op);
            if (callbacks != null) {
                for (int cbi = callbacks.size() - 1; !curValue && cbi >= 0; cbi--) {
                    if ((callbacks.valueAt(cbi).mFlags
                            & AppOpsManager.WATCH_FOREGROUND_CHANGES) != 0) {
                        hasForegroundWatchers = true;
                        curValue = true;
                    }
                }
            }
            which.put(op, curValue);
        }

        public void evalForegroundOps(SparseArray<ArraySet<ModeCallback>> watchers) {
            SparseBooleanArray which = null;
            hasForegroundWatchers = false;
            if (opModes != null) {
                for (int i = opModes.size() - 1; i >= 0; i--) {
                    if (opModes.valueAt(i) == AppOpsManager.MODE_FOREGROUND) {
                        if (which == null) {
                            which = new SparseBooleanArray();
                        }
                        evalForegroundWatchers(opModes.keyAt(i), watchers, which);
                    }
                }
            }
            if (pkgOps != null) {
                for (int i = pkgOps.size() - 1; i >= 0; i--) {
                    Ops ops = pkgOps.valueAt(i);
                    for (int j = ops.size() - 1; j >= 0; j--) {
                        if (ops.valueAt(j).mode == AppOpsManager.MODE_FOREGROUND) {
                            if (which == null) {
                                which = new SparseBooleanArray();
                            }
                            evalForegroundWatchers(ops.keyAt(j), watchers, which);
                        }
                    }
                }
            }
            foregroundOps = which;
        }
    }

    final static class Ops extends SparseArray<Op> {
        final String packageName;
        final UidState uidState;
        final boolean isPrivileged;

        Ops(String _packageName, UidState _uidState, boolean _isPrivileged) {
            packageName = _packageName;
            uidState = _uidState;
            isPrivileged = _isPrivileged;
        }
    }

    final static class Op {
        int op;
        boolean running;
        final UidState uidState;
        final @NonNull String packageName;

        private @Mode int mode;
        private @Nullable LongSparseLongArray mAccessTimes;
        private @Nullable LongSparseLongArray mRejectTimes;
        private @Nullable LongSparseLongArray mDurations;
        private @Nullable LongSparseLongArray mProxyUids;
        private @Nullable LongSparseArray<String> mProxyPackageNames;

        int startNesting;
        long startRealtime;

        Op(UidState uidState, String packageName, int op) {
            this.op = op;
            this.uidState = uidState;
            this.packageName = packageName;
            this.mode = AppOpsManager.opToDefaultMode(op);
        }

        int getMode() {
            return mode;
        }

        int evalMode() {
            return uidState.evalMode(op, mode);
        }

        /** @hide */
        public void accessed(long time, int proxyUid, @Nullable String proxyPackageName,
            @AppOpsManager.UidState int uidState, @OpFlags int flags) {
            final long key = AppOpsManager.makeKey(uidState, flags);
            if (mAccessTimes == null) {
                mAccessTimes = new LongSparseLongArray();
            }
            mAccessTimes.put(key, time);
            updateProxyState(key, proxyUid, proxyPackageName);
            if (mDurations != null) {
                mDurations.delete(key);
            }
        }

        /** @hide */
        public void rejected(long time, int proxyUid, @Nullable String proxyPackageName,
            @AppOpsManager.UidState int uidState, @OpFlags int flags) {
            final long key = AppOpsManager.makeKey(uidState, flags);
            if (mRejectTimes == null) {
                mRejectTimes = new LongSparseLongArray();
            }
            mRejectTimes.put(key, time);
            updateProxyState(key, proxyUid, proxyPackageName);
            if (mDurations != null) {
                mDurations.delete(key);
            }
        }

        /** @hide */
        public void started(long time, @AppOpsManager.UidState int uidState, @OpFlags int flags) {
            updateAccessTimeAndDuration(time, -1 /*duration*/, uidState, flags);
            running = true;
        }

        /** @hide */
        public void finished(long time, long duration, @AppOpsManager.UidState int uidState,
            @OpFlags int flags) {
            updateAccessTimeAndDuration(time, duration, uidState, flags);
            running = false;
        }

        /** @hide */
        public void running(long time, long duration, @AppOpsManager.UidState int uidState,
            @OpFlags int flags) {
            updateAccessTimeAndDuration(time, duration, uidState, flags);
        }

        /** @hide */
        public void continuing(long duration, @AppOpsManager.UidState int uidState,
            @OpFlags int flags) {
            final long key = AppOpsManager.makeKey(uidState, flags);
            if (mDurations == null) {
                mDurations = new LongSparseLongArray();
            }
            mDurations.put(key, duration);
        }

        private void updateAccessTimeAndDuration(long time, long duration,
            @AppOpsManager.UidState int uidState, @OpFlags int flags) {
            final long key = AppOpsManager.makeKey(uidState, flags);
            if (mAccessTimes == null) {
                mAccessTimes = new LongSparseLongArray();
            }
            mAccessTimes.put(key, time);
            if (mDurations == null) {
                mDurations = new LongSparseLongArray();
            }
            mDurations.put(key, duration);
        }

        private void updateProxyState(long key, int proxyUid,
            @Nullable String proxyPackageName) {
            if (mProxyUids == null) {
                mProxyUids = new LongSparseLongArray();
            }
            mProxyUids.put(key, proxyUid);
            if (mProxyPackageNames == null) {
                mProxyPackageNames = new LongSparseArray<>();
            }
            mProxyPackageNames.put(key, proxyPackageName);
        }

        boolean hasAnyTime() {
            return (mAccessTimes != null && mAccessTimes.size() > 0)
                || (mRejectTimes != null && mRejectTimes.size() > 0);
        }
    }

    final SparseArray<ArraySet<ModeCallback>> mOpModeWatchers = new SparseArray<>();
    final ArrayMap<String, ArraySet<ModeCallback>> mPackageModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, ModeCallback> mModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<ActiveCallback>> mActiveWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<NotedCallback>> mNotedWatchers = new ArrayMap<>();
    final SparseArray<SparseArray<Restriction>> mAudioRestrictions = new SparseArray<>();

    final class ModeCallback implements DeathRecipient {
        final IAppOpsCallback mCallback;
        final int mWatchingUid;
        final int mFlags;
        final int mCallingUid;
        final int mCallingPid;

        ModeCallback(IAppOpsCallback callback, int watchingUid, int flags, int callingUid,
                int callingPid) {
            mCallback = callback;
            mWatchingUid = watchingUid;
            mFlags = flags;
            mCallingUid = callingUid;
            mCallingPid = callingPid;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                /*ignored*/
            }
        }

        public boolean isWatchingUid(int uid) {
            return uid == UID_ANY || mWatchingUid < 0 || mWatchingUid == uid;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ModeCallback{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" watchinguid=");
            UserHandle.formatUid(sb, mWatchingUid);
            sb.append(" flags=0x");
            sb.append(Integer.toHexString(mFlags));
            sb.append(" from uid=");
            UserHandle.formatUid(sb, mCallingUid);
            sb.append(" pid=");
            sb.append(mCallingPid);
            sb.append('}');
            return sb.toString();
        }

        void unlinkToDeath() {
            mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            stopWatchingMode(mCallback);
        }
    }

    final class ActiveCallback implements DeathRecipient {
        final IAppOpsActiveCallback mCallback;
        final int mWatchingUid;
        final int mCallingUid;
        final int mCallingPid;

        ActiveCallback(IAppOpsActiveCallback callback, int watchingUid, int callingUid,
                int callingPid) {
            mCallback = callback;
            mWatchingUid = watchingUid;
            mCallingUid = callingUid;
            mCallingPid = callingPid;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                /*ignored*/
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ActiveCallback{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" watchinguid=");
            UserHandle.formatUid(sb, mWatchingUid);
            sb.append(" from uid=");
            UserHandle.formatUid(sb, mCallingUid);
            sb.append(" pid=");
            sb.append(mCallingPid);
            sb.append('}');
            return sb.toString();
        }

        void destroy() {
            mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            stopWatchingActive(mCallback);
        }
    }

    final class NotedCallback implements DeathRecipient {
        final IAppOpsNotedCallback mCallback;
        final int mWatchingUid;
        final int mCallingUid;
        final int mCallingPid;

        NotedCallback(IAppOpsNotedCallback callback, int watchingUid, int callingUid,
                int callingPid) {
            mCallback = callback;
            mWatchingUid = watchingUid;
            mCallingUid = callingUid;
            mCallingPid = callingPid;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                /*ignored*/
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("NotedCallback{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" watchinguid=");
            UserHandle.formatUid(sb, mWatchingUid);
            sb.append(" from uid=");
            UserHandle.formatUid(sb, mCallingUid);
            sb.append(" pid=");
            sb.append(mCallingPid);
            sb.append('}');
            return sb.toString();
        }

        void destroy() {
            mCallback.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            stopWatchingNoted(mCallback);
        }
    }

    final ArrayMap<IBinder, ClientState> mClients = new ArrayMap<>();

    final class ClientState extends Binder implements DeathRecipient {
        final ArrayList<Op> mStartedOps = new ArrayList<>();
        final IBinder mAppToken;
        final int mPid;

        ClientState(IBinder appToken) {
            mAppToken = appToken;
            mPid = Binder.getCallingPid();
            // Watch only for remote processes dying
            if (!(appToken instanceof Binder)) {
                try {
                    mAppToken.linkToDeath(this, 0);
                } catch (RemoteException e) {
                    /* do nothing */
                }
            }
        }

        @Override
        public String toString() {
            return "ClientState{" +
                    "mAppToken=" + mAppToken +
                    ", " + "pid=" + mPid +
                    '}';
        }

        @Override
        public void binderDied() {
            synchronized (AppOpsService.this) {
                for (int i=mStartedOps.size()-1; i>=0; i--) {
                    finishOperationLocked(mStartedOps.get(i), /*finishNested*/ true);
                }
                mClients.remove(mAppToken);
            }
        }
    }

    public AppOpsService(File storagePath, Handler handler) {
        LockGuard.installLock(this, LockGuard.INDEX_APP_OPS);
        mFile = new AtomicFile(storagePath, "appops");
        mHandler = handler;
        mConstants = new Constants(mHandler);
        readState();
    }

    public void publish(Context context) {
        mContext = context;
        ServiceManager.addService(Context.APP_OPS_SERVICE, asBinder());
        LocalServices.addService(AppOpsManagerInternal.class, mAppOpsManagerInternal);
    }

    public void systemReady() {
        mConstants.startMonitoring(mContext.getContentResolver());
        mHistoricalRegistry.systemReady(mContext.getContentResolver());

        synchronized (this) {
            boolean changed = false;
            for (int i = mUidStates.size() - 1; i >= 0; i--) {
                UidState uidState = mUidStates.valueAt(i);

                String[] packageNames = getPackagesForUid(uidState.uid);
                if (ArrayUtils.isEmpty(packageNames)) {
                    uidState.clear();
                    mUidStates.removeAt(i);
                    changed = true;
                    continue;
                }

                ArrayMap<String, Ops> pkgs = uidState.pkgOps;
                if (pkgs == null) {
                    continue;
                }

                Iterator<Ops> it = pkgs.values().iterator();
                while (it.hasNext()) {
                    Ops ops = it.next();
                    int curUid = -1;
                    try {
                        curUid = AppGlobals.getPackageManager().getPackageUid(ops.packageName,
                                PackageManager.MATCH_UNINSTALLED_PACKAGES,
                                UserHandle.getUserId(ops.uidState.uid));
                    } catch (RemoteException ignored) {
                    }
                    if (curUid != ops.uidState.uid) {
                        Slog.i(TAG, "Pruning old package " + ops.packageName
                                + "/" + ops.uidState + ": new uid=" + curUid);
                        it.remove();
                        changed = true;
                    }
                }

                if (uidState.isDefault()) {
                    mUidStates.removeAt(i);
                }
            }
            if (changed) {
                scheduleFastWriteLocked();
            }
        }

        final IntentFilter packageSuspendFilter = new IntentFilter();
        packageSuspendFilter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        packageSuspendFilter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int[] changedUids = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                final String[] changedPkgs = intent.getStringArrayExtra(
                        Intent.EXTRA_CHANGED_PACKAGE_LIST);
                final ArraySet<ModeCallback> callbacks = mOpModeWatchers.get(OP_PLAY_AUDIO);
                for (int i = 0; i < changedUids.length; i++) {
                    final int changedUid = changedUids[i];
                    final String changedPkg = changedPkgs[i];
                    // We trust packagemanager to insert matching uid and packageNames in the extras
                    mHandler.sendMessage(PooledLambda.obtainMessage(AppOpsService::notifyOpChanged,
                            AppOpsService.this, callbacks, OP_PLAY_AUDIO, changedUid, changedPkg));
                }
            }
        }, packageSuspendFilter);

        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        packageManagerInternal.setExternalSourcesPolicy(
                new PackageManagerInternal.ExternalSourcesPolicy() {
                    @Override
                    public int getPackageTrustedToInstallApps(String packageName, int uid) {
                        int appOpMode = checkOperation(AppOpsManager.OP_REQUEST_INSTALL_PACKAGES,
                                uid, packageName);
                        switch (appOpMode) {
                            case AppOpsManager.MODE_ALLOWED:
                                return PackageManagerInternal.ExternalSourcesPolicy.USER_TRUSTED;
                            case AppOpsManager.MODE_ERRORED:
                                return PackageManagerInternal.ExternalSourcesPolicy.USER_BLOCKED;
                            default:
                                return PackageManagerInternal.ExternalSourcesPolicy.USER_DEFAULT;
                        }
                    }
                });

        if (!StorageManager.hasIsolatedStorage()) {
            StorageManagerInternal storageManagerInternal = LocalServices.getService(
                    StorageManagerInternal.class);
            storageManagerInternal.addExternalStoragePolicy(
                    new StorageManagerInternal.ExternalStorageMountPolicy() {
                        @Override
                        public int getMountMode(int uid, String packageName) {
                            if (Process.isIsolated(uid)) {
                                return Zygote.MOUNT_EXTERNAL_NONE;
                            }
                            if (noteOperation(AppOpsManager.OP_READ_EXTERNAL_STORAGE, uid,
                                    packageName) != AppOpsManager.MODE_ALLOWED) {
                                return Zygote.MOUNT_EXTERNAL_NONE;
                            }
                            if (noteOperation(AppOpsManager.OP_WRITE_EXTERNAL_STORAGE, uid,
                                    packageName) != AppOpsManager.MODE_ALLOWED) {
                                return Zygote.MOUNT_EXTERNAL_READ;
                            }
                            return Zygote.MOUNT_EXTERNAL_WRITE;
                        }

                        @Override
                        public boolean hasExternalStorage(int uid, String packageName) {
                            final int mountMode = getMountMode(uid, packageName);
                            return mountMode == Zygote.MOUNT_EXTERNAL_READ
                                    || mountMode == Zygote.MOUNT_EXTERNAL_WRITE;
                        }
                    });
        }
    }

    public void packageRemoved(int uid, String packageName) {
        synchronized (this) {
            UidState uidState = mUidStates.get(uid);
            if (uidState == null) {
                return;
            }

            Ops ops = null;

            // Remove any package state if such.
            if (uidState.pkgOps != null) {
                ops = uidState.pkgOps.remove(packageName);
            }

            // If we just nuked the last package state check if the UID is valid.
            if (ops != null && uidState.pkgOps.isEmpty()
                    && getPackagesForUid(uid).length <= 0) {
                mUidStates.remove(uid);
            }

            // Finish ops other packages started on behalf of the package.
            final int clientCount = mClients.size();
            for (int i = 0; i < clientCount; i++) {
                final ClientState client = mClients.valueAt(i);
                if (client.mStartedOps == null) {
                    continue;
                }
                final int opCount = client.mStartedOps.size();
                for (int j = opCount - 1; j >= 0; j--) {
                    final Op op = client.mStartedOps.get(j);
                    if (uid == op.uidState.uid && packageName.equals(op.packageName)) {
                        finishOperationLocked(op, /*finishNested*/ true);
                        client.mStartedOps.remove(j);
                        if (op.startNesting <= 0) {
                            scheduleOpActiveChangedIfNeededLocked(op.op,
                                    uid, packageName, false);
                        }
                    }
                }
            }

            if (ops != null) {
                scheduleFastWriteLocked();

                final int opCount = ops.size();
                for (int i = 0; i < opCount; i++) {
                    final Op op = ops.valueAt(i);
                    if (op.running) {
                        scheduleOpActiveChangedIfNeededLocked(
                                op.op, op.uidState.uid, op.packageName, false);
                    }
                }
            }
        }
    }

    public void uidRemoved(int uid) {
        synchronized (this) {
            if (mUidStates.indexOfKey(uid) >= 0) {
                mUidStates.remove(uid);
                scheduleFastWriteLocked();
            }
        }
    }

    public void updateUidProcState(int uid, int procState) {
        synchronized (this) {
            final UidState uidState = getUidStateLocked(uid, true);
            int newState = PROCESS_STATE_TO_UID_STATE[procState];
            if (uidState != null && uidState.pendingState != newState) {
                final int oldPendingState = uidState.pendingState;
                uidState.pendingState = newState;
                if (newState < uidState.state || newState <= UID_STATE_MAX_LAST_NON_RESTRICTED) {
                    // We are moving to a more important state, or the new state is in the
                    // foreground, then always do it immediately.
                    commitUidPendingStateLocked(uidState);
                } else if (uidState.pendingStateCommitTime == 0) {
                    // We are moving to a less important state for the first time,
                    // delay the application for a bit.
                    final long settleTime;
                    if (uidState.state <= UID_STATE_TOP) {
                        settleTime = mConstants.TOP_STATE_SETTLE_TIME;
                    } else if (uidState.state <= UID_STATE_FOREGROUND_SERVICE) {
                        settleTime = mConstants.FG_SERVICE_STATE_SETTLE_TIME;
                    } else {
                        settleTime = mConstants.BG_STATE_SETTLE_TIME;
                    }
                    uidState.pendingStateCommitTime = SystemClock.elapsedRealtime() + settleTime;
                }
                if (uidState.startNesting != 0) {
                    // There is some actively running operation...  need to find it
                    // and appropriately update its state.
                    final long now = System.currentTimeMillis();
                    for (int i = uidState.pkgOps.size() - 1; i >= 0; i--) {
                        final Ops ops = uidState.pkgOps.valueAt(i);
                        for (int j = ops.size() - 1; j >= 0; j--) {
                            final Op op = ops.valueAt(j);
                            if (op.startNesting > 0) {
                                final long duration = SystemClock.elapsedRealtime()
                                        - op.startRealtime;
                                // We don't support proxy long running ops (start/stop)
                                mHistoricalRegistry.increaseOpAccessDuration(op.op,
                                        op.uidState.uid, op.packageName, oldPendingState,
                                        AppOpsManager.OP_FLAG_SELF, duration);
                                // Finish the op in the old state
                                op.finished(now, duration, oldPendingState,
                                        AppOpsManager.OP_FLAG_SELF);
                                // Start the op in the new state
                                op.startRealtime = now;
                                op.started(now, newState, AppOpsManager.OP_FLAG_SELF);
                            }
                        }
                    }
                }
            }
        }
    }

    public void shutdown() {
        Slog.w(TAG, "Writing app ops before shutdown...");
        boolean doWrite = false;
        synchronized (this) {
            if (mWriteScheduled) {
                mWriteScheduled = false;
                doWrite = true;
            }
        }
        if (doWrite) {
            writeState();
        }
    }

    private ArrayList<AppOpsManager.OpEntry> collectOps(Ops pkgOps, int[] ops) {
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        final long elapsedNow = SystemClock.elapsedRealtime();
        if (ops == null) {
            resOps = new ArrayList<>();
            for (int j=0; j<pkgOps.size(); j++) {
                Op curOp = pkgOps.valueAt(j);
                resOps.add(getOpEntryForResult(curOp, elapsedNow));
            }
        } else {
            for (int j=0; j<ops.length; j++) {
                Op curOp = pkgOps.get(ops[j]);
                if (curOp != null) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    resOps.add(getOpEntryForResult(curOp, elapsedNow));
                }
            }
        }
        return resOps;
    }

    private ArrayList<AppOpsManager.OpEntry> collectOps(SparseIntArray uidOps, int[] ops) {
        if (uidOps == null) {
            return null;
        }
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        if (ops == null) {
            resOps = new ArrayList<>();
            for (int j=0; j<uidOps.size(); j++) {
                resOps.add(new OpEntry(uidOps.keyAt(j), uidOps.valueAt(j)));
            }
        } else {
            for (int j=0; j<ops.length; j++) {
                int index = uidOps.indexOfKey(ops[j]);
                if (index >= 0) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    resOps.add(new OpEntry(uidOps.keyAt(j), uidOps.valueAt(j)));
                }
            }
        }
        return resOps;
    }

    private static @NonNull OpEntry getOpEntryForResult(@NonNull Op op, long elapsedNow) {
        if (op.running) {
            op.continuing(elapsedNow - op.startRealtime,
                op.uidState.state, AppOpsManager.OP_FLAG_SELF);
        }
        final OpEntry entry = new OpEntry(op.op, op.running, op.mode,
            op.mAccessTimes != null ? op.mAccessTimes.clone() : null,
            op.mRejectTimes != null ? op.mRejectTimes.clone() : null,
            op.mDurations != null ? op.mDurations.clone() : null,
            op.mProxyUids != null ? op.mProxyUids.clone() : null,
            op.mProxyPackageNames != null ? op.mProxyPackageNames.clone() : null);
        return entry;
    }

    @Override
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        ArrayList<AppOpsManager.PackageOps> res = null;
        synchronized (this) {
            final int uidStateCount = mUidStates.size();
            for (int i = 0; i < uidStateCount; i++) {
                UidState uidState = mUidStates.valueAt(i);
                if (uidState.pkgOps == null || uidState.pkgOps.isEmpty()) {
                    continue;
                }
                ArrayMap<String, Ops> packages = uidState.pkgOps;
                final int packageCount = packages.size();
                for (int j = 0; j < packageCount; j++) {
                    Ops pkgOps = packages.valueAt(j);
                    ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
                    if (resOps != null) {
                        if (res == null) {
                            res = new ArrayList<AppOpsManager.PackageOps>();
                        }
                        AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                                pkgOps.packageName, pkgOps.uidState.uid, resOps);
                        res.add(resPackage);
                    }
                }
            }
        }
        return res;
    }

    @Override
    public List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName,
            int[] ops) {
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return Collections.emptyList();
        }
        synchronized (this) {
            Ops pkgOps = getOpsRawLocked(uid, resolvedPackageName, false /* edit */,
                    false /* uidMismatchExpected */);
            if (pkgOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
            if (resOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.PackageOps> res = new ArrayList<AppOpsManager.PackageOps>();
            AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                    pkgOps.packageName, pkgOps.uidState.uid, resOps);
            res.add(resPackage);
            return res;
        }
    }

    @Override
    public void getHistoricalOps(int uid, @NonNull String packageName,
            @Nullable List<String> opNames, long beginTimeMillis, long endTimeMillis,
            @OpFlags int flags, @NonNull RemoteCallback callback) {
        // Use the builder to validate arguments.
        new HistoricalOpsRequest.Builder(
                beginTimeMillis, endTimeMillis)
                .setUid(uid)
                .setPackageName(packageName)
                .setOpNames(opNames)
                .setFlags(flags)
                .build();
        Preconditions.checkNotNull(callback, "callback cannot be null");

        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), "getHistoricalOps");

        final String[] opNamesArray = (opNames != null)
                ? opNames.toArray(new String[opNames.size()]) : null;

        // Must not hold the appops lock
        mHistoricalRegistry.getHistoricalOps(uid, packageName, opNamesArray,
                beginTimeMillis, endTimeMillis, flags, callback);
    }

    @Override
    public void getHistoricalOpsFromDiskRaw(int uid, @NonNull String packageName,
            @Nullable List<String> opNames, long beginTimeMillis, long endTimeMillis,
            @OpFlags int flags, @NonNull RemoteCallback callback) {
        // Use the builder to validate arguments.
        new HistoricalOpsRequest.Builder(
                beginTimeMillis, endTimeMillis)
                .setUid(uid)
                .setPackageName(packageName)
                .setOpNames(opNames)
                .setFlags(flags)
                .build();
        Preconditions.checkNotNull(callback, "callback cannot be null");

        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), "getHistoricalOps");

        final String[] opNamesArray = (opNames != null)
                ? opNames.toArray(new String[opNames.size()]) : null;

        // Must not hold the appops lock
        mHistoricalRegistry.getHistoricalOpsFromDiskRaw(uid, packageName, opNamesArray,
                beginTimeMillis, endTimeMillis, flags, callback);
    }

    @Override
    public List<AppOpsManager.PackageOps> getUidOps(int uid, int[] ops) {
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState == null) {
                return null;
            }
            ArrayList<AppOpsManager.OpEntry> resOps = collectOps(uidState.opModes, ops);
            if (resOps == null) {
                return null;
            }
            ArrayList<AppOpsManager.PackageOps> res = new ArrayList<AppOpsManager.PackageOps>();
            AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                    null, uidState.uid, resOps);
            res.add(resPackage);
            return res;
        }
    }

    private void pruneOp(Op op, int uid, String packageName) {
        if (!op.hasAnyTime()) {
            Ops ops = getOpsRawLocked(uid, packageName, false /* edit */,
                    false /* uidMismatchExpected */);
            if (ops != null) {
                ops.remove(op.op);
                if (ops.size() <= 0) {
                    UidState uidState = ops.uidState;
                    ArrayMap<String, Ops> pkgOps = uidState.pkgOps;
                    if (pkgOps != null) {
                        pkgOps.remove(ops.packageName);
                        if (pkgOps.isEmpty()) {
                            uidState.pkgOps = null;
                        }
                        if (uidState.isDefault()) {
                            mUidStates.remove(uid);
                        }
                    }
                }
            }
        }
    }

    private void enforceManageAppOpsModes(int callingPid, int callingUid, int targetUid) {
        if (callingPid == Process.myPid()) {
            return;
        }
        final int callingUser = UserHandle.getUserId(callingUid);
        synchronized (this) {
            if (mProfileOwners != null && mProfileOwners.get(callingUser, -1) == callingUid) {
                if (targetUid >= 0 && callingUser == UserHandle.getUserId(targetUid)) {
                    // Profile owners are allowed to change modes but only for apps
                    // within their user.
                    return;
                }
            }
        }
        mContext.enforcePermission(android.Manifest.permission.MANAGE_APP_OPS_MODES,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    @Override
    public void setUidMode(int code, int uid, int mode) {
        if (DEBUG) {
            Slog.i(TAG, "uid " + uid + " OP_" + opToName(code) + " := " + modeToName(mode)
                    + " by uid " + Binder.getCallingUid());
        }

        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), uid);
        verifyIncomingOp(code);
        code = AppOpsManager.opToSwitch(code);

        synchronized (this) {
            final int defaultMode = AppOpsManager.opToDefaultMode(code);

            UidState uidState = getUidStateLocked(uid, false);
            if (uidState == null) {
                if (mode == defaultMode) {
                    return;
                }
                uidState = new UidState(uid);
                uidState.opModes = new SparseIntArray();
                uidState.opModes.put(code, mode);
                mUidStates.put(uid, uidState);
                scheduleWriteLocked();
            } else if (uidState.opModes == null) {
                if (mode != defaultMode) {
                    uidState.opModes = new SparseIntArray();
                    uidState.opModes.put(code, mode);
                    scheduleWriteLocked();
                }
            } else {
                if (uidState.opModes.indexOfKey(code) >= 0 && uidState.opModes.get(code) == mode) {
                    return;
                }
                if (mode == defaultMode) {
                    uidState.opModes.delete(code);
                    if (uidState.opModes.size() <= 0) {
                        uidState.opModes = null;
                    }
                } else {
                    uidState.opModes.put(code, mode);
                }
                scheduleWriteLocked();
            }
        }

        String[] uidPackageNames = getPackagesForUid(uid);
        ArrayMap<ModeCallback, ArraySet<String>> callbackSpecs = null;

        synchronized (this) {
            ArraySet<ModeCallback> callbacks = mOpModeWatchers.get(code);
            if (callbacks != null) {
                final int callbackCount = callbacks.size();
                for (int i = 0; i < callbackCount; i++) {
                    ModeCallback callback = callbacks.valueAt(i);
                    ArraySet<String> changedPackages = new ArraySet<>();
                    Collections.addAll(changedPackages, uidPackageNames);
                    if (callbackSpecs == null) {
                        callbackSpecs = new ArrayMap<>();
                    }
                    callbackSpecs.put(callback, changedPackages);
                }
            }

            for (String uidPackageName : uidPackageNames) {
                callbacks = mPackageModeWatchers.get(uidPackageName);
                if (callbacks != null) {
                    if (callbackSpecs == null) {
                        callbackSpecs = new ArrayMap<>();
                    }
                    final int callbackCount = callbacks.size();
                    for (int i = 0; i < callbackCount; i++) {
                        ModeCallback callback = callbacks.valueAt(i);
                        ArraySet<String> changedPackages = callbackSpecs.get(callback);
                        if (changedPackages == null) {
                            changedPackages = new ArraySet<>();
                            callbackSpecs.put(callback, changedPackages);
                        }
                        changedPackages.add(uidPackageName);
                    }
                }
            }
        }

        if (callbackSpecs == null) {
            return;
        }

        for (int i = 0; i < callbackSpecs.size(); i++) {
            final ModeCallback callback = callbackSpecs.keyAt(i);
            final ArraySet<String> reportedPackageNames = callbackSpecs.valueAt(i);
            if (reportedPackageNames == null) {
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        AppOpsService::notifyOpChanged,
                        this, callback, code, uid, (String) null));

            } else {
                final int reportedPackageCount = reportedPackageNames.size();
                for (int j = 0; j < reportedPackageCount; j++) {
                    final String reportedPackageName = reportedPackageNames.valueAt(j);
                    mHandler.sendMessage(PooledLambda.obtainMessage(
                            AppOpsService::notifyOpChanged,
                            this, callback, code, uid, reportedPackageName));
                }
            }
        }
    }

    /**
     * Set all {@link #setMode (package) modes} for this uid to the default value.
     *
     * @param code The app-op
     * @param uid The uid
     */
    private void setAllPkgModesToDefault(int code, int uid) {
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState == null) {
                return;
            }

            ArrayMap<String, Ops> pkgOps = uidState.pkgOps;
            if (pkgOps == null) {
                return;
            }

            boolean scheduleWrite = false;

            int numPkgs = pkgOps.size();
            for (int pkgNum = 0; pkgNum < numPkgs; pkgNum++) {
                Ops ops = pkgOps.valueAt(pkgNum);

                Op op = ops.get(code);
                if (op == null) {
                    continue;
                }

                int defaultMode = AppOpsManager.opToDefaultMode(code);
                if (op.mode != defaultMode) {
                    op.mode = defaultMode;
                    scheduleWrite = true;
                }
            }

            if (scheduleWrite) {
                scheduleWriteLocked();
            }
        }
    }

    @Override
    public void setMode(int code, int uid, String packageName, int mode) {
        setMode(code, uid, packageName, mode, true, false);
    }

    /**
     * Sets the mode for a certain op and uid.
     *
     * @param code The op code to set
     * @param uid The UID for which to set
     * @param packageName The package for which to set
     * @param mode The new mode to set
     * @param verifyUid Iff {@code true}, check that the package name belongs to the uid
     * @param isPrivileged Whether the package is privileged. (Only used if {@code verifyUid ==
     *                     false})
     */
    private void setMode(int code, int uid, @NonNull String packageName, int mode,
            boolean verifyUid, boolean isPrivileged) {
        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), uid);
        verifyIncomingOp(code);
        ArraySet<ModeCallback> repCbs = null;
        code = AppOpsManager.opToSwitch(code);
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            Op op = getOpLocked(code, uid, packageName, true, verifyUid, isPrivileged);
            if (op != null) {
                if (op.mode != mode) {
                    op.mode = mode;
                    if (uidState != null) {
                        uidState.evalForegroundOps(mOpModeWatchers);
                    }
                    ArraySet<ModeCallback> cbs = mOpModeWatchers.get(code);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArraySet<>();
                        }
                        repCbs.addAll(cbs);
                    }
                    cbs = mPackageModeWatchers.get(packageName);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArraySet<>();
                        }
                        repCbs.addAll(cbs);
                    }
                    if (mode == AppOpsManager.opToDefaultMode(op.op)) {
                        // If going into the default mode, prune this op
                        // if there is nothing else interesting in it.
                        pruneOp(op, uid, packageName);
                    }
                    scheduleFastWriteLocked();
                }
            }
        }
        if (repCbs != null) {
            mHandler.sendMessage(PooledLambda.obtainMessage(
                    AppOpsService::notifyOpChanged,
                    this, repCbs, code, uid, packageName));
        }
    }

    private void notifyOpChanged(ArraySet<ModeCallback> callbacks, int code,
            int uid, String packageName) {
        for (int i = 0; i < callbacks.size(); i++) {
            final ModeCallback callback = callbacks.valueAt(i);
            notifyOpChanged(callback, code, uid, packageName);
        }
    }

    private void notifyOpChanged(ModeCallback callback, int code,
            int uid, String packageName) {
        if (uid != UID_ANY && callback.mWatchingUid >= 0 && callback.mWatchingUid != uid) {
            return;
        }
        // There are components watching for mode changes such as window manager
        // and location manager which are in our process. The callbacks in these
        // components may require permissions our remote caller does not have.
        final long identity = Binder.clearCallingIdentity();
        try {
            callback.mCallback.opChanged(code, uid, packageName);
        } catch (RemoteException e) {
            /* ignore */
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private static HashMap<ModeCallback, ArrayList<ChangeRec>> addCallbacks(
            HashMap<ModeCallback, ArrayList<ChangeRec>> callbacks,
            int op, int uid, String packageName, ArraySet<ModeCallback> cbs) {
        if (cbs == null) {
            return callbacks;
        }
        if (callbacks == null) {
            callbacks = new HashMap<>();
        }
        boolean duplicate = false;
        final int N = cbs.size();
        for (int i=0; i<N; i++) {
            ModeCallback cb = cbs.valueAt(i);
            ArrayList<ChangeRec> reports = callbacks.get(cb);
            if (reports == null) {
                reports = new ArrayList<>();
                callbacks.put(cb, reports);
            } else {
                final int reportCount = reports.size();
                for (int j = 0; j < reportCount; j++) {
                    ChangeRec report = reports.get(j);
                    if (report.op == op && report.pkg.equals(packageName)) {
                        duplicate = true;
                        break;
                    }
                }
            }
            if (!duplicate) {
                reports.add(new ChangeRec(op, uid, packageName));
            }
        }
        return callbacks;
    }

    static final class ChangeRec {
        final int op;
        final int uid;
        final String pkg;

        ChangeRec(int _op, int _uid, String _pkg) {
            op = _op;
            uid = _uid;
            pkg = _pkg;
        }
    }

    @Override
    public void resetAllModes(int reqUserId, String reqPackageName) {
        final int callingPid = Binder.getCallingPid();
        final int callingUid = Binder.getCallingUid();
        reqUserId = ActivityManager.handleIncomingUser(callingPid, callingUid, reqUserId,
                true, true, "resetAllModes", null);

        int reqUid = -1;
        if (reqPackageName != null) {
            try {
                reqUid = AppGlobals.getPackageManager().getPackageUid(
                        reqPackageName, PackageManager.MATCH_UNINSTALLED_PACKAGES, reqUserId);
            } catch (RemoteException e) {
                /* ignore - local call */
            }
        }

        enforceManageAppOpsModes(callingPid, callingUid, reqUid);

        HashMap<ModeCallback, ArrayList<ChangeRec>> callbacks = null;
        synchronized (this) {
            boolean changed = false;
            for (int i = mUidStates.size() - 1; i >= 0; i--) {
                UidState uidState = mUidStates.valueAt(i);

                SparseIntArray opModes = uidState.opModes;
                if (opModes != null && (uidState.uid == reqUid || reqUid == -1)) {
                    final int uidOpCount = opModes.size();
                    for (int j = uidOpCount - 1; j >= 0; j--) {
                        final int code = opModes.keyAt(j);
                        if (AppOpsManager.opAllowsReset(code)) {
                            opModes.removeAt(j);
                            if (opModes.size() <= 0) {
                                uidState.opModes = null;
                            }
                            for (String packageName : getPackagesForUid(uidState.uid)) {
                                callbacks = addCallbacks(callbacks, code, uidState.uid, packageName,
                                        mOpModeWatchers.get(code));
                                callbacks = addCallbacks(callbacks, code, uidState.uid, packageName,
                                        mPackageModeWatchers.get(packageName));
                            }
                        }
                    }
                }

                if (uidState.pkgOps == null) {
                    continue;
                }

                if (reqUserId != UserHandle.USER_ALL
                        && reqUserId != UserHandle.getUserId(uidState.uid)) {
                    // Skip any ops for a different user
                    continue;
                }

                Map<String, Ops> packages = uidState.pkgOps;
                Iterator<Map.Entry<String, Ops>> it = packages.entrySet().iterator();
                boolean uidChanged = false;
                while (it.hasNext()) {
                    Map.Entry<String, Ops> ent = it.next();
                    String packageName = ent.getKey();
                    if (reqPackageName != null && !reqPackageName.equals(packageName)) {
                        // Skip any ops for a different package
                        continue;
                    }
                    Ops pkgOps = ent.getValue();
                    for (int j=pkgOps.size()-1; j>=0; j--) {
                        Op curOp = pkgOps.valueAt(j);
                        if (AppOpsManager.opAllowsReset(curOp.op)
                                && curOp.mode != AppOpsManager.opToDefaultMode(curOp.op)) {
                            curOp.mode = AppOpsManager.opToDefaultMode(curOp.op);
                            changed = true;
                            uidChanged = true;
                            final int uid = curOp.uidState.uid;
                            callbacks = addCallbacks(callbacks, curOp.op, uid, packageName,
                                    mOpModeWatchers.get(curOp.op));
                            callbacks = addCallbacks(callbacks, curOp.op, uid, packageName,
                                    mPackageModeWatchers.get(packageName));
                            if (!curOp.hasAnyTime()) {
                                pkgOps.removeAt(j);
                            }
                        }
                    }
                    if (pkgOps.size() == 0) {
                        it.remove();
                    }
                }
                if (uidState.isDefault()) {
                    mUidStates.remove(uidState.uid);
                }
                if (uidChanged) {
                    uidState.evalForegroundOps(mOpModeWatchers);
                }
            }

            if (changed) {
                scheduleFastWriteLocked();
            }
        }
        if (callbacks != null) {
            for (Map.Entry<ModeCallback, ArrayList<ChangeRec>> ent : callbacks.entrySet()) {
                ModeCallback cb = ent.getKey();
                ArrayList<ChangeRec> reports = ent.getValue();
                for (int i=0; i<reports.size(); i++) {
                    ChangeRec rep = reports.get(i);
                    mHandler.sendMessage(PooledLambda.obtainMessage(
                            AppOpsService::notifyOpChanged,
                            this, cb, rep.op, rep.uid, rep.pkg));
                }
            }
        }
    }

    private void evalAllForegroundOpsLocked() {
        for (int uidi = mUidStates.size() - 1; uidi >= 0; uidi--) {
            final UidState uidState = mUidStates.valueAt(uidi);
            if (uidState.foregroundOps != null) {
                uidState.evalForegroundOps(mOpModeWatchers);
            }
        }
    }

    @Override
    public void startWatchingMode(int op, String packageName, IAppOpsCallback callback) {
        startWatchingModeWithFlags(op, packageName, 0, callback);
    }

    @Override
    public void startWatchingModeWithFlags(int op, String packageName, int flags,
            IAppOpsCallback callback) {
        int watchedUid = -1;
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        // TODO: should have a privileged permission to protect this.
        // Also, if the caller has requested WATCH_FOREGROUND_CHANGES, should we require
        // the USAGE_STATS permission since this can provide information about when an
        // app is in the foreground?
        Preconditions.checkArgumentInRange(op, AppOpsManager.OP_NONE,
                AppOpsManager._NUM_OP - 1, "Invalid op code: " + op);
        if (callback == null) {
            return;
        }
        synchronized (this) {
            op = (op != AppOpsManager.OP_NONE) ? AppOpsManager.opToSwitch(op) : op;
            ModeCallback cb = mModeWatchers.get(callback.asBinder());
            if (cb == null) {
                cb = new ModeCallback(callback, watchedUid, flags, callingUid, callingPid);
                mModeWatchers.put(callback.asBinder(), cb);
            }
            if (op != AppOpsManager.OP_NONE) {
                ArraySet<ModeCallback> cbs = mOpModeWatchers.get(op);
                if (cbs == null) {
                    cbs = new ArraySet<>();
                    mOpModeWatchers.put(op, cbs);
                }
                cbs.add(cb);
            }
            if (packageName != null) {
                ArraySet<ModeCallback> cbs = mPackageModeWatchers.get(packageName);
                if (cbs == null) {
                    cbs = new ArraySet<>();
                    mPackageModeWatchers.put(packageName, cbs);
                }
                cbs.add(cb);
            }
            evalAllForegroundOpsLocked();
        }
    }

    @Override
    public void stopWatchingMode(IAppOpsCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (this) {
            ModeCallback cb = mModeWatchers.remove(callback.asBinder());
            if (cb != null) {
                cb.unlinkToDeath();
                for (int i=mOpModeWatchers.size()-1; i>=0; i--) {
                    ArraySet<ModeCallback> cbs = mOpModeWatchers.valueAt(i);
                    cbs.remove(cb);
                    if (cbs.size() <= 0) {
                        mOpModeWatchers.removeAt(i);
                    }
                }
                for (int i=mPackageModeWatchers.size()-1; i>=0; i--) {
                    ArraySet<ModeCallback> cbs = mPackageModeWatchers.valueAt(i);
                    cbs.remove(cb);
                    if (cbs.size() <= 0) {
                        mPackageModeWatchers.removeAt(i);
                    }
                }
            }
            evalAllForegroundOpsLocked();
        }
    }

    @Override
    public IBinder getToken(IBinder clientToken) {
        synchronized (this) {
            ClientState cs = mClients.get(clientToken);
            if (cs == null) {
                cs = new ClientState(clientToken);
                mClients.put(clientToken, cs);
            }
            return cs;
        }
    }

    public CheckOpsDelegate getAppOpsServiceDelegate() {
        synchronized (this) {
            return mCheckOpsDelegate;
        }
    }

    public void setAppOpsServiceDelegate(CheckOpsDelegate delegate) {
        synchronized (this) {
            mCheckOpsDelegate = delegate;
        }
    }

    @Override
    public int checkOperationRaw(int code, int uid, String packageName) {
        return checkOperationInternal(code, uid, packageName, true /*raw*/);
    }

    @Override
    public int checkOperation(int code, int uid, String packageName) {
        return checkOperationInternal(code, uid, packageName, false /*raw*/);
    }

    private int checkOperationInternal(int code, int uid, String packageName, boolean raw) {
        final CheckOpsDelegate delegate;
        synchronized (this) {
            delegate = mCheckOpsDelegate;
        }
        if (delegate == null) {
            return checkOperationImpl(code, uid, packageName, raw);
        }
        return delegate.checkOperation(code, uid, packageName, raw,
                    AppOpsService.this::checkOperationImpl);
    }

    private int checkOperationImpl(int code, int uid, String packageName,
                boolean raw) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        return checkOperationUnchecked(code, uid, resolvedPackageName, raw);
    }

    /**
     * @see #checkOperationUnchecked(int, int, String, boolean, boolean)
     */
    private @Mode int checkOperationUnchecked(int code, int uid, @NonNull String packageName,
            boolean raw) {
        return checkOperationUnchecked(code, uid, packageName, raw, true);
    }

    /**
     * Get the mode of an app-op.
     *
     * @param code The code of the op
     * @param uid The uid of the package the op belongs to
     * @param packageName The package the op belongs to
     * @param raw If the raw state of eval-ed state should be checked.
     * @param verify If the code should check the package belongs to the uid
     *
     * @return The mode of the op
     */
    private @Mode int checkOperationUnchecked(int code, int uid, @NonNull String packageName,
                boolean raw, boolean verify) {
        synchronized (this) {
            if (verify) {
                checkPackage(uid, packageName);
            }
            if (isOpRestrictedLocked(uid, code, packageName)) {
                return AppOpsManager.MODE_IGNORED;
            }
            code = AppOpsManager.opToSwitch(code);
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState != null && uidState.opModes != null
                    && uidState.opModes.indexOfKey(code) >= 0) {
                final int rawMode = uidState.opModes.get(code);
                return raw ? rawMode : uidState.evalMode(code, rawMode);
            }
            Op op = getOpLocked(code, uid, packageName, false, verify, false);
            if (op == null) {
                return AppOpsManager.opToDefaultMode(code);
            }
            return raw ? op.mode : op.evalMode();
        }
    }

    @Override
    public int checkAudioOperation(int code, int usage, int uid, String packageName) {
        final CheckOpsDelegate delegate;
        synchronized (this) {
            delegate = mCheckOpsDelegate;
        }
        if (delegate == null) {
            return checkAudioOperationImpl(code, usage, uid, packageName);
        }
        return delegate.checkAudioOperation(code, usage, uid, packageName,
                AppOpsService.this::checkAudioOperationImpl);
    }

    private int checkAudioOperationImpl(int code, int usage, int uid, String packageName) {
        boolean suspended;
        try {
            suspended = isPackageSuspendedForUser(packageName, uid);
        } catch (IllegalArgumentException ex) {
            // Package not found.
            suspended = false;
        }

        if (suspended) {
            Slog.i(TAG, "Audio disabled for suspended package=" + packageName
                    + " for uid=" + uid);
            return AppOpsManager.MODE_IGNORED;
        }

        synchronized (this) {
            final int mode = checkRestrictionLocked(code, usage, uid, packageName);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                return mode;
            }
        }
        return checkOperation(code, uid, packageName);
    }

    private boolean isPackageSuspendedForUser(String pkg, int uid) {
        try {
            return AppGlobals.getPackageManager().isPackageSuspendedForUser(
                    pkg, UserHandle.getUserId(uid));
        } catch (RemoteException re) {
            throw new SecurityException("Could not talk to package manager service");
        }
    }

    private int checkRestrictionLocked(int code, int usage, int uid, String packageName) {
        final SparseArray<Restriction> usageRestrictions = mAudioRestrictions.get(code);
        if (usageRestrictions != null) {
            final Restriction r = usageRestrictions.get(usage);
            if (r != null && !r.exceptionPackages.contains(packageName)) {
                return r.mode;
            }
        }
        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void setAudioRestriction(int code, int usage, int uid, int mode,
            String[] exceptionPackages) {
        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), uid);
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        synchronized (this) {
            SparseArray<Restriction> usageRestrictions = mAudioRestrictions.get(code);
            if (usageRestrictions == null) {
                usageRestrictions = new SparseArray<Restriction>();
                mAudioRestrictions.put(code, usageRestrictions);
            }
            usageRestrictions.remove(usage);
            if (mode != AppOpsManager.MODE_ALLOWED) {
                final Restriction r = new Restriction();
                r.mode = mode;
                if (exceptionPackages != null) {
                    final int N = exceptionPackages.length;
                    r.exceptionPackages = new ArraySet<String>(N);
                    for (int i = 0; i < N; i++) {
                        final String pkg = exceptionPackages[i];
                        if (pkg != null) {
                            r.exceptionPackages.add(pkg.trim());
                        }
                    }
                }
                usageRestrictions.put(usage, r);
            }
        }

        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsService::notifyWatchersOfChange, this, code, UID_ANY));
    }

    @Override
    public int checkPackage(int uid, String packageName) {
        Preconditions.checkNotNull(packageName);
        synchronized (this) {
            Ops ops = getOpsRawLocked(uid, packageName, true /* edit */,
                    true /* uidMismatchExpected */);
            if (ops != null) {
                return AppOpsManager.MODE_ALLOWED;
            } else {
                return AppOpsManager.MODE_ERRORED;
            }
        }
    }

    @Override
    public int noteProxyOperation(int code, int proxyUid,
            String proxyPackageName, int proxiedUid, String proxiedPackageName) {
        verifyIncomingUid(proxyUid);
        verifyIncomingOp(code);

        String resolveProxyPackageName = resolvePackageName(proxyUid, proxyPackageName);
        if (resolveProxyPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }

        final boolean isProxyTrusted = mContext.checkPermission(
                Manifest.permission.UPDATE_APP_OPS_STATS, -1, proxyUid)
                == PackageManager.PERMISSION_GRANTED;

        final int proxyFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXY
                : AppOpsManager.OP_FLAG_UNTRUSTED_PROXY;
        final int proxyMode = noteOperationUnchecked(code, proxyUid,
                resolveProxyPackageName, Process.INVALID_UID, null, proxyFlags);
        if (proxyMode != AppOpsManager.MODE_ALLOWED || Binder.getCallingUid() == proxiedUid) {
            return proxyMode;
        }

        String resolveProxiedPackageName = resolvePackageName(proxiedUid, proxiedPackageName);
        if (resolveProxiedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        final int proxiedFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXIED
                : AppOpsManager.OP_FLAG_UNTRUSTED_PROXIED;
        return noteOperationUnchecked(code, proxiedUid, resolveProxiedPackageName,
                proxyUid, resolveProxyPackageName, proxiedFlags);
    }

    @Override
    public int noteOperation(int code, int uid, String packageName) {
        final CheckOpsDelegate delegate;
        synchronized (this) {
            delegate = mCheckOpsDelegate;
        }
        if (delegate == null) {
            return noteOperationImpl(code, uid, packageName);
        }
        return delegate.noteOperation(code, uid, packageName,
                AppOpsService.this::noteOperationImpl);
    }

    private int noteOperationImpl(int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        return noteOperationUnchecked(code, uid, resolvedPackageName, Process.INVALID_UID, null,
                AppOpsManager.OP_FLAG_SELF);
    }

    private int noteOperationUnchecked(int code, int uid, String packageName,
            int proxyUid, String proxyPackageName, @OpFlags int flags) {
        synchronized (this) {
            final Ops ops = getOpsRawLocked(uid, packageName, true /* edit */,
                    false /* uidMismatchExpected */);
            if (ops == null) {
                scheduleOpNotedIfNeededLocked(code, uid, packageName,
                        AppOpsManager.MODE_IGNORED);
                if (DEBUG) Slog.d(TAG, "noteOperation: no op for code " + code + " uid " + uid
                        + " package " + packageName);
                return AppOpsManager.MODE_ERRORED;
            }
            final Op op = getOpLocked(ops, code, true);
            if (isOpRestrictedLocked(uid, code, packageName)) {
                scheduleOpNotedIfNeededLocked(code, uid, packageName,
                        AppOpsManager.MODE_IGNORED);
                return AppOpsManager.MODE_IGNORED;
            }
            final UidState uidState = ops.uidState;
            if (op.running) {
                final OpEntry entry = new OpEntry(op.op, op.running, op.mode, op.mAccessTimes,
                    op.mRejectTimes, op.mDurations, op.mProxyUids, op.mProxyPackageNames);
                Slog.w(TAG, "Noting op not finished: uid " + uid + " pkg " + packageName
                        + " code " + code + " time=" + entry.getLastAccessTime(uidState.state,
                        uidState.state, flags) + " duration=" + entry.getLastDuration(
                                uidState.state, uidState.state, flags));
            }

            final int switchCode = AppOpsManager.opToSwitch(code);
            // If there is a non-default per UID policy (we set UID op mode only if
            // non-default) it takes over, otherwise use the per package policy.
            if (uidState.opModes != null && uidState.opModes.indexOfKey(switchCode) >= 0) {
                final int uidMode = uidState.evalMode(code, uidState.opModes.get(switchCode));
                if (uidMode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Slog.d(TAG, "noteOperation: uid reject #" + uidMode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName);
                    op.rejected(System.currentTimeMillis(), proxyUid, proxyPackageName,
                            uidState.state, flags);
                    mHistoricalRegistry.incrementOpRejected(code, uid, packageName,
                            uidState.state, flags);
                    scheduleOpNotedIfNeededLocked(code, uid, packageName, uidMode);
                    return uidMode;
                }
            } else {
                final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op;
                final int mode = switchOp.evalMode();
                if (switchOp.mode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Slog.d(TAG, "noteOperation: reject #" + mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName);
                    op.rejected(System.currentTimeMillis(), proxyUid, proxyPackageName,
                            uidState.state, flags);
                    mHistoricalRegistry.incrementOpRejected(code, uid, packageName,
                            uidState.state, flags);
                    scheduleOpNotedIfNeededLocked(code, uid, packageName, mode);
                    return mode;
                }
            }
            if (DEBUG) Slog.d(TAG, "noteOperation: allowing code " + code + " uid " + uid
                    + " package " + packageName);
            op.accessed(System.currentTimeMillis(), proxyUid, proxyPackageName,
                    uidState.state, flags);
            mHistoricalRegistry.incrementOpAccessedCount(op.op, uid, packageName,
                    uidState.state, flags);
            scheduleOpNotedIfNeededLocked(code, uid, packageName,
                    AppOpsManager.MODE_ALLOWED);
            return AppOpsManager.MODE_ALLOWED;
        }
    }

    @Override
    public void startWatchingActive(int[] ops, IAppOpsActiveCallback callback) {
        int watchedUid = -1;
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.WATCH_APPOPS)
                != PackageManager.PERMISSION_GRANTED) {
            watchedUid = callingUid;
        }
        if (ops != null) {
            Preconditions.checkArrayElementsInRange(ops, 0,
                    AppOpsManager._NUM_OP - 1, "Invalid op code in: " + Arrays.toString(ops));
        }
        if (callback == null) {
            return;
        }
        synchronized (this) {
            SparseArray<ActiveCallback> callbacks = mActiveWatchers.get(callback.asBinder());
            if (callbacks == null) {
                callbacks = new SparseArray<>();
                mActiveWatchers.put(callback.asBinder(), callbacks);
            }
            final ActiveCallback activeCallback = new ActiveCallback(callback, watchedUid,
                    callingUid, callingPid);
            for (int op : ops) {
                callbacks.put(op, activeCallback);
            }
        }
    }

    @Override
    public void stopWatchingActive(IAppOpsActiveCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (this) {
            final SparseArray<ActiveCallback> activeCallbacks =
                    mActiveWatchers.remove(callback.asBinder());
            if (activeCallbacks == null) {
                return;
            }
            final int callbackCount = activeCallbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                activeCallbacks.valueAt(i).destroy();
            }
        }
    }

    @Override
    public void startWatchingNoted(@NonNull int[] ops, @NonNull IAppOpsNotedCallback callback) {
        int watchedUid = Process.INVALID_UID;
        final int callingUid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.WATCH_APPOPS)
                != PackageManager.PERMISSION_GRANTED) {
            watchedUid = callingUid;
        }
        Preconditions.checkArgument(!ArrayUtils.isEmpty(ops), "Ops cannot be null or empty");
        Preconditions.checkArrayElementsInRange(ops, 0, AppOpsManager._NUM_OP - 1,
                "Invalid op code in: " + Arrays.toString(ops));
        Preconditions.checkNotNull(callback, "Callback cannot be null");
        synchronized (this) {
            SparseArray<NotedCallback> callbacks = mNotedWatchers.get(callback.asBinder());
            if (callbacks == null) {
                callbacks = new SparseArray<>();
                mNotedWatchers.put(callback.asBinder(), callbacks);
            }
            final NotedCallback notedCallback = new NotedCallback(callback, watchedUid,
                    callingUid, callingPid);
            for (int op : ops) {
                callbacks.put(op, notedCallback);
            }
        }
    }

    @Override
    public void stopWatchingNoted(IAppOpsNotedCallback callback) {
        Preconditions.checkNotNull(callback, "Callback cannot be null");
        synchronized (this) {
            final SparseArray<NotedCallback> notedCallbacks =
                    mNotedWatchers.remove(callback.asBinder());
            if (notedCallbacks == null) {
                return;
            }
            final int callbackCount = notedCallbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                notedCallbacks.valueAt(i).destroy();
            }
        }
    }

    @Override
    public int startOperation(IBinder token, int code, int uid, String packageName,
            boolean startIfModeDefault) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return  AppOpsManager.MODE_IGNORED;
        }
        ClientState client = (ClientState)token;
        synchronized (this) {
            final Ops ops = getOpsRawLocked(uid, resolvedPackageName, true /* edit */,
                    false /* uidMismatchExpected */);
            if (ops == null) {
                if (DEBUG) Slog.d(TAG, "startOperation: no op for code " + code + " uid " + uid
                        + " package " + resolvedPackageName);
                return AppOpsManager.MODE_ERRORED;
            }
            final Op op = getOpLocked(ops, code, true);
            if (isOpRestrictedLocked(uid, code, resolvedPackageName)) {
                return AppOpsManager.MODE_IGNORED;
            }
            final int switchCode = AppOpsManager.opToSwitch(code);
            final UidState uidState = ops.uidState;
            // If there is a non-default per UID policy (we set UID op mode only if
            // non-default) it takes over, otherwise use the per package policy.
            final int opCode = op.op;
            if (uidState.opModes != null && uidState.opModes.indexOfKey(switchCode) >= 0) {
                final int uidMode = uidState.evalMode(code, uidState.opModes.get(switchCode));
                if (uidMode != AppOpsManager.MODE_ALLOWED
                        && (!startIfModeDefault || uidMode != AppOpsManager.MODE_DEFAULT)) {
                    if (DEBUG) Slog.d(TAG, "noteOperation: uid reject #" + uidMode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + resolvedPackageName);
                    // We don't support proxy long running ops (start/stop)
                    op.rejected(System.currentTimeMillis(), -1 /*proxyUid*/,
                            null /*proxyPackage*/, uidState.state, AppOpsManager.OP_FLAG_SELF);
                    mHistoricalRegistry.incrementOpRejected(opCode, uid, packageName,
                            uidState.state, AppOpsManager.OP_FLAG_SELF);
                    return uidMode;
                }
            } else {
                final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, true) : op;
                final int mode = switchOp.evalMode();
                if (mode != AppOpsManager.MODE_ALLOWED
                        && (!startIfModeDefault || mode != AppOpsManager.MODE_DEFAULT)) {
                    if (DEBUG) Slog.d(TAG, "startOperation: reject #" + mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + resolvedPackageName);
                    // We don't support proxy long running ops (start/stop)
                    op.rejected(System.currentTimeMillis(), -1 /*proxyUid*/,
                            null /*proxyPackage*/, uidState.state, AppOpsManager.OP_FLAG_SELF);
                    mHistoricalRegistry.incrementOpRejected(opCode, uid, packageName,
                            uidState.state, AppOpsManager.OP_FLAG_SELF);
                    return mode;
                }
            }
            if (DEBUG) Slog.d(TAG, "startOperation: allowing code " + code + " uid " + uid
                    + " package " + resolvedPackageName);
            if (op.startNesting == 0) {
                op.startRealtime = SystemClock.elapsedRealtime();
                // We don't support proxy long running ops (start/stop)
                op.started(System.currentTimeMillis(), uidState.state,
                        AppOpsManager.OP_FLAG_SELF);
                mHistoricalRegistry.incrementOpAccessedCount(opCode, uid, packageName,
                        uidState.state, AppOpsManager.OP_FLAG_SELF);

                scheduleOpActiveChangedIfNeededLocked(code, uid, packageName, true);
            }
            op.startNesting++;
            uidState.startNesting++;
            if (client.mStartedOps != null) {
                client.mStartedOps.add(op);
            }
        }

        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void finishOperation(IBinder token, int code, int uid, String packageName) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return;
        }
        if (!(token instanceof ClientState)) {
            return;
        }
        ClientState client = (ClientState) token;
        synchronized (this) {
            Op op = getOpLocked(code, uid, resolvedPackageName, true, true, false);
            if (op == null) {
                return;
            }
            if (!client.mStartedOps.remove(op)) {
                // We finish ops when packages get removed to guarantee no dangling
                // started ops. However, some part of the system may asynchronously
                // finish ops for an already gone package. Hence, finishing an op
                // for a non existing package is fine and we don't log as a wtf.
                final long identity = Binder.clearCallingIdentity();
                try {
                    if (LocalServices.getService(PackageManagerInternal.class).getPackageUid(
                            resolvedPackageName, 0, UserHandle.getUserId(uid)) < 0) {
                        Slog.i(TAG, "Finishing op=" + AppOpsManager.opToName(code)
                                + " for non-existing package=" + resolvedPackageName
                                + " in uid=" + uid);
                        return;
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
                Slog.wtf(TAG, "Operation not started: uid=" + op.uidState.uid + " pkg="
                        + op.packageName + " op=" + AppOpsManager.opToName(op.op));
                return;
            }
            finishOperationLocked(op, /*finishNested*/ false);
            if (op.startNesting <= 0) {
                scheduleOpActiveChangedIfNeededLocked(code, uid, packageName, false);
            }
        }
    }

    private void scheduleOpActiveChangedIfNeededLocked(int code, int uid, String packageName,
            boolean active) {
        ArraySet<ActiveCallback> dispatchedCallbacks = null;
        final int callbackListCount = mActiveWatchers.size();
        for (int i = 0; i < callbackListCount; i++) {
            final SparseArray<ActiveCallback> callbacks = mActiveWatchers.valueAt(i);
            ActiveCallback callback = callbacks.get(code);
            if (callback != null) {
                if (callback.mWatchingUid >= 0 && callback.mWatchingUid != uid) {
                    continue;
                }
                if (dispatchedCallbacks == null) {
                    dispatchedCallbacks = new ArraySet<>();
                }
                dispatchedCallbacks.add(callback);
            }
        }
        if (dispatchedCallbacks == null) {
            return;
        }
        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsService::notifyOpActiveChanged,
                this, dispatchedCallbacks, code, uid, packageName, active));
    }

    private void notifyOpActiveChanged(ArraySet<ActiveCallback> callbacks,
            int code, int uid, String packageName, boolean active) {
        // There are components watching for mode changes such as window manager
        // and location manager which are in our process. The callbacks in these
        // components may require permissions our remote caller does not have.
        final long identity = Binder.clearCallingIdentity();
        try {
            final int callbackCount = callbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                final ActiveCallback callback = callbacks.valueAt(i);
                try {
                    callback.mCallback.opActiveChanged(code, uid, packageName, active);
                } catch (RemoteException e) {
                    /* do nothing */
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void scheduleOpNotedIfNeededLocked(int code, int uid, String packageName,
            int result) {
        ArraySet<NotedCallback> dispatchedCallbacks = null;
        final int callbackListCount = mNotedWatchers.size();
        for (int i = 0; i < callbackListCount; i++) {
            final SparseArray<NotedCallback> callbacks = mNotedWatchers.valueAt(i);
            final NotedCallback callback = callbacks.get(code);
            if (callback != null) {
                if (callback.mWatchingUid >= 0 && callback.mWatchingUid != uid) {
                    continue;
                }
                if (dispatchedCallbacks == null) {
                    dispatchedCallbacks = new ArraySet<>();
                }
                dispatchedCallbacks.add(callback);
            }
        }
        if (dispatchedCallbacks == null) {
            return;
        }
        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsService::notifyOpChecked,
                this, dispatchedCallbacks, code, uid, packageName, result));
    }

    private void notifyOpChecked(ArraySet<NotedCallback> callbacks,
            int code, int uid, String packageName, int result) {
        // There are components watching for checks in our process. The callbacks in
        // these components may require permissions our remote caller does not have.
        final long identity = Binder.clearCallingIdentity();
        try {
            final int callbackCount = callbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                final NotedCallback callback = callbacks.valueAt(i);
                try {
                    callback.mCallback.opNoted(code, uid, packageName, result);
                } catch (RemoteException e) {
                    /* do nothing */
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public int permissionToOpCode(String permission) {
        if (permission == null) {
            return AppOpsManager.OP_NONE;
        }
        return AppOpsManager.permissionToOpCode(permission);
    }

    void finishOperationLocked(Op op, boolean finishNested) {
        final int opCode = op.op;
        final int uid = op.uidState.uid;
        if (op.startNesting <= 1 || finishNested) {
            if (op.startNesting == 1 || finishNested) {
                // We don't support proxy long running ops (start/stop)
                final long duration = SystemClock.elapsedRealtime() - op.startRealtime;
                op.finished(System.currentTimeMillis(), duration, op.uidState.state,
                        AppOpsManager.OP_FLAG_SELF);
                mHistoricalRegistry.increaseOpAccessDuration(opCode, uid, op.packageName,
                        op.uidState.state, AppOpsManager.OP_FLAG_SELF, duration);
            } else {
                final OpEntry entry = new OpEntry(op.op, op.running, op.mode, op.mAccessTimes,
                    op.mRejectTimes, op.mDurations, op.mProxyUids, op.mProxyPackageNames);
                Slog.w(TAG, "Finishing op nesting under-run: uid " + uid + " pkg "
                        + op.packageName + " code " + opCode + " time="
                        + entry.getLastAccessTime(OP_FLAGS_ALL)
                        + " duration=" + entry.getLastDuration(MAX_PRIORITY_UID_STATE,
                        MIN_PRIORITY_UID_STATE, OP_FLAGS_ALL) + " nesting=" + op.startNesting);
            }
            if (op.startNesting >= 1) {
                op.uidState.startNesting -= op.startNesting;
            }
            op.startNesting = 0;
        } else {
            op.startNesting--;
            op.uidState.startNesting--;
        }
    }

    private void verifyIncomingUid(int uid) {
        if (uid == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
    }

    private void verifyIncomingOp(int op) {
        if (op >= 0 && op < AppOpsManager._NUM_OP) {
            return;
        }
        throw new IllegalArgumentException("Bad operation #" + op);
    }

    private @Nullable UidState getUidStateLocked(int uid, boolean edit) {
        UidState uidState = mUidStates.get(uid);
        if (uidState == null) {
            if (!edit) {
                return null;
            }
            uidState = new UidState(uid);
            mUidStates.put(uid, uidState);
        } else {
            if (uidState.pendingStateCommitTime != 0) {
                if (uidState.pendingStateCommitTime < mLastRealtime) {
                    commitUidPendingStateLocked(uidState);
                } else {
                    mLastRealtime = SystemClock.elapsedRealtime();
                    if (uidState.pendingStateCommitTime < mLastRealtime) {
                        commitUidPendingStateLocked(uidState);
                    }
                }
            }
        }
        return uidState;
    }

    private void commitUidPendingStateLocked(UidState uidState) {
        final boolean lastForeground = uidState.state <= UID_STATE_MAX_LAST_NON_RESTRICTED;
        final boolean nowForeground = uidState.pendingState <= UID_STATE_MAX_LAST_NON_RESTRICTED;
        uidState.state = uidState.pendingState;
        uidState.pendingStateCommitTime = 0;
        if (uidState.hasForegroundWatchers && lastForeground != nowForeground) {
            for (int fgi = uidState.foregroundOps.size() - 1; fgi >= 0; fgi--) {
                if (!uidState.foregroundOps.valueAt(fgi)) {
                    continue;
                }
                final int code = uidState.foregroundOps.keyAt(fgi);
                // For location ops we consider fg state only if the fg service
                // is of location type, for all other ops any fg service will do.
                final long resolvedLastRestrictedUidState = resolveFirstUnrestrictedUidState(code);
                final boolean resolvedLastFg = uidState.state <= resolvedLastRestrictedUidState;
                final boolean resolvedNowBg = uidState.pendingState
                        <= resolvedLastRestrictedUidState;
                if (resolvedLastFg == resolvedNowBg) {
                    continue;
                }
                final ArraySet<ModeCallback> callbacks = mOpModeWatchers.get(code);
                if (callbacks != null) {
                    for (int cbi = callbacks.size() - 1; cbi >= 0; cbi--) {
                        final ModeCallback callback = callbacks.valueAt(cbi);
                        if ((callback.mFlags & AppOpsManager.WATCH_FOREGROUND_CHANGES) == 0
                                || !callback.isWatchingUid(uidState.uid)) {
                            continue;
                        }
                        boolean doAllPackages = uidState.opModes != null
                                && uidState.opModes.indexOfKey(code) >= 0
                                && uidState.opModes.get(code) == AppOpsManager.MODE_FOREGROUND;
                        if (uidState.pkgOps != null) {
                            for (int pkgi = uidState.pkgOps.size() - 1; pkgi >= 0; pkgi--) {
                                final Op op = uidState.pkgOps.valueAt(pkgi).get(code);
                                if (op == null) {
                                    continue;
                                }
                                if (doAllPackages || op.mode == AppOpsManager.MODE_FOREGROUND) {
                                    mHandler.sendMessage(PooledLambda.obtainMessage(
                                            AppOpsService::notifyOpChanged,
                                            this, callback, code, uidState.uid,
                                            uidState.pkgOps.keyAt(pkgi)));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private Ops getOpsRawLocked(int uid, String packageName, boolean edit,
            boolean uidMismatchExpected) {
        UidState uidState = getUidStateLocked(uid, edit);
        if (uidState == null) {
            return null;
        }

        if (uidState.pkgOps == null) {
            if (!edit) {
                return null;
            }
            uidState.pkgOps = new ArrayMap<>();
        }

        Ops ops = uidState.pkgOps.get(packageName);
        if (ops == null) {
            if (!edit) {
                return null;
            }
            boolean isPrivileged = false;
            // This is the first time we have seen this package name under this uid,
            // so let's make sure it is valid.
            if (uid != 0) {
                final long ident = Binder.clearCallingIdentity();
                try {
                    int pkgUid = -1;
                    try {
                        ApplicationInfo appInfo = ActivityThread.getPackageManager()
                                .getApplicationInfo(packageName,
                                        PackageManager.MATCH_DIRECT_BOOT_AWARE
                                                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
                                        UserHandle.getUserId(uid));
                        if (appInfo != null) {
                            pkgUid = appInfo.uid;
                            isPrivileged = (appInfo.privateFlags
                                    & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
                        } else {
                            pkgUid = resolveUid(packageName);
                            if (pkgUid >= 0) {
                                isPrivileged = false;
                            }
                        }
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Could not contact PackageManager", e);
                    }
                    if (pkgUid != uid) {
                        // Oops!  The package name is not valid for the uid they are calling
                        // under.  Abort.
                        if (!uidMismatchExpected) {
                            RuntimeException ex = new RuntimeException("here");
                            ex.fillInStackTrace();
                            Slog.w(TAG, "Bad call: specified package " + packageName
                                    + " under uid " + uid + " but it is really " + pkgUid, ex);
                        }
                        return null;
                    }
                } finally {
                    Binder.restoreCallingIdentity(ident);
                }
            }
            ops = new Ops(packageName, uidState, isPrivileged);
            uidState.pkgOps.put(packageName, ops);
        }
        return ops;
    }

    /**
     * Get the state of all ops for a package, <b>don't verify that package belongs to uid</b>.
     *
     * <p>Usually callers should use {@link #getOpLocked} and not call this directly.
     *
     * @param uid The uid the of the package
     * @param packageName The package name for which to get the state for
     * @param edit Iff {@code true} create the {@link Ops} object if not yet created
     * @param isPrivileged Whether the package is privileged or not
     *
     * @return The {@link Ops state} of all ops for the package
     */
    private @Nullable Ops getOpsRawNoVerifyLocked(int uid, @NonNull String packageName,
            boolean edit, boolean isPrivileged) {
        UidState uidState = getUidStateLocked(uid, edit);
        if (uidState == null) {
            return null;
        }

        if (uidState.pkgOps == null) {
            if (!edit) {
                return null;
            }
            uidState.pkgOps = new ArrayMap<>();
        }

        Ops ops = uidState.pkgOps.get(packageName);
        if (ops == null) {
            if (!edit) {
                return null;
            }
            ops = new Ops(packageName, uidState, isPrivileged);
            uidState.pkgOps.put(packageName, ops);
        }
        return ops;
    }

    private void scheduleWriteLocked() {
        if (!mWriteScheduled) {
            mWriteScheduled = true;
            mHandler.postDelayed(mWriteRunner, WRITE_DELAY);
        }
    }

    private void scheduleFastWriteLocked() {
        if (!mFastWriteScheduled) {
            mWriteScheduled = true;
            mFastWriteScheduled = true;
            mHandler.removeCallbacks(mWriteRunner);
            mHandler.postDelayed(mWriteRunner, 10*1000);
        }
    }

    /**
     * Get the state of an op for a uid.
     *
     * @param code The code of the op
     * @param uid The uid the of the package
     * @param packageName The package name for which to get the state for
     * @param edit Iff {@code true} create the {@link Op} object if not yet created
     * @param verifyUid Iff {@code true} check that the package belongs to the uid
     * @param isPrivileged Whether the package is privileged or not (only used if {@code verifyUid
     *                     == false})
     *
     * @return The {@link Op state} of the op
     */
    private @Nullable Op getOpLocked(int code, int uid, @NonNull String packageName, boolean edit,
            boolean verifyUid, boolean isPrivileged) {
        Ops ops;

        if (verifyUid) {
            ops = getOpsRawLocked(uid, packageName, edit, false /* uidMismatchExpected */);
        }  else {
            ops = getOpsRawNoVerifyLocked(uid, packageName, edit, isPrivileged);
        }

        if (ops == null) {
            return null;
        }
        return getOpLocked(ops, code, edit);
    }

    private Op getOpLocked(Ops ops, int code, boolean edit) {
        Op op = ops.get(code);
        if (op == null) {
            if (!edit) {
                return null;
            }
            op = new Op(ops.uidState, ops.packageName, code);
            ops.put(code, op);
        }
        if (edit) {
            scheduleWriteLocked();
        }
        return op;
    }

    private boolean isOpRestrictedLocked(int uid, int code, String packageName) {
        int userHandle = UserHandle.getUserId(uid);
        final int restrictionSetCount = mOpUserRestrictions.size();

        for (int i = 0; i < restrictionSetCount; i++) {
            // For each client, check that the given op is not restricted, or that the given
            // package is exempt from the restriction.
            ClientRestrictionState restrictionState = mOpUserRestrictions.valueAt(i);
            if (restrictionState.hasRestriction(code, packageName, userHandle)) {
                if (AppOpsManager.opAllowSystemBypassRestriction(code)) {
                    // If we are the system, bypass user restrictions for certain codes
                    synchronized (this) {
                        Ops ops = getOpsRawLocked(uid, packageName, true /* edit */,
                                false /* uidMismatchExpected */);
                        if ((ops != null) && ops.isPrivileged) {
                            return false;
                        }
                    }
                }
                return true;
            }
        }
        return false;
    }

    void readState() {
        int oldVersion = NO_VERSION;
        synchronized (mFile) {
            synchronized (this) {
                FileInputStream stream;
                try {
                    stream = mFile.openRead();
                } catch (FileNotFoundException e) {
                    Slog.i(TAG, "No existing app ops " + mFile.getBaseFile() + "; starting empty");
                    return;
                }
                boolean success = false;
                mUidStates.clear();
                try {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(stream, StandardCharsets.UTF_8.name());
                    int type;
                    while ((type = parser.next()) != XmlPullParser.START_TAG
                            && type != XmlPullParser.END_DOCUMENT) {
                        ;
                    }

                    if (type != XmlPullParser.START_TAG) {
                        throw new IllegalStateException("no start tag found");
                    }

                    final String versionString = parser.getAttributeValue(null, "v");
                    if (versionString != null) {
                        oldVersion = Integer.parseInt(versionString);
                    }

                    int outerDepth = parser.getDepth();
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                            && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                        if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                            continue;
                        }

                        String tagName = parser.getName();
                        if (tagName.equals("pkg")) {
                            readPackage(parser);
                        } else if (tagName.equals("uid")) {
                            readUidOps(parser);
                        } else {
                            Slog.w(TAG, "Unknown element under <app-ops>: "
                                    + parser.getName());
                            XmlUtils.skipCurrentTag(parser);
                        }
                    }
                    success = true;
                } catch (IllegalStateException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (NullPointerException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (NumberFormatException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (XmlPullParserException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (IOException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } catch (IndexOutOfBoundsException e) {
                    Slog.w(TAG, "Failed parsing " + e);
                } finally {
                    if (!success) {
                        mUidStates.clear();
                    }
                    try {
                        stream.close();
                    } catch (IOException e) {
                    }
                }
            }
        }
        synchronized (this) {
            upgradeLocked(oldVersion);
        }
    }

    private void upgradeRunAnyInBackgroundLocked() {
        for (int i = 0; i < mUidStates.size(); i++) {
            final UidState uidState = mUidStates.valueAt(i);
            if (uidState == null) {
                continue;
            }
            if (uidState.opModes != null) {
                final int idx = uidState.opModes.indexOfKey(AppOpsManager.OP_RUN_IN_BACKGROUND);
                if (idx >= 0) {
                    uidState.opModes.put(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND,
                        uidState.opModes.valueAt(idx));
                }
            }
            if (uidState.pkgOps == null) {
                continue;
            }
            boolean changed = false;
            for (int j = 0; j < uidState.pkgOps.size(); j++) {
                Ops ops = uidState.pkgOps.valueAt(j);
                if (ops != null) {
                    final Op op = ops.get(AppOpsManager.OP_RUN_IN_BACKGROUND);
                    if (op != null && op.mode != AppOpsManager.opToDefaultMode(op.op)) {
                        final Op copy = new Op(op.uidState, op.packageName,
                            AppOpsManager.OP_RUN_ANY_IN_BACKGROUND);
                        copy.mode = op.mode;
                        ops.put(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, copy);
                        changed = true;
                    }
                }
            }
            if (changed) {
                uidState.evalForegroundOps(mOpModeWatchers);
            }
        }
    }

    private void upgradeLocked(int oldVersion) {
        if (oldVersion >= CURRENT_VERSION) {
            return;
        }
        Slog.d(TAG, "Upgrading app-ops xml from version " + oldVersion + " to " + CURRENT_VERSION);
        switch (oldVersion) {
            case NO_VERSION:
                upgradeRunAnyInBackgroundLocked();
                // fall through
            case 1:
                // for future upgrades
        }
        scheduleFastWriteLocked();
    }

    private void readUidOps(XmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        final int uid = Integer.parseInt(parser.getAttributeValue(null, "n"));
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                final int code = Integer.parseInt(parser.getAttributeValue(null, "n"));
                final int mode = Integer.parseInt(parser.getAttributeValue(null, "m"));
                UidState uidState = getUidStateLocked(uid, true);
                if (uidState.opModes == null) {
                    uidState.opModes = new SparseIntArray();
                }
                uidState.opModes.put(code, mode);
            } else {
                Slog.w(TAG, "Unknown element under <uid-ops>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readPackage(XmlPullParser parser)
            throws NumberFormatException, XmlPullParserException, IOException {
        String pkgName = parser.getAttributeValue(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("uid")) {
                readUid(parser, pkgName);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readUid(XmlPullParser parser, String pkgName)
            throws NumberFormatException, XmlPullParserException, IOException {
        int uid = Integer.parseInt(parser.getAttributeValue(null, "n"));
        final UidState uidState = getUidStateLocked(uid, true);
        String isPrivilegedString = parser.getAttributeValue(null, "p");
        boolean isPrivileged = false;
        if (isPrivilegedString == null) {
            try {
                IPackageManager packageManager = ActivityThread.getPackageManager();
                if (packageManager != null) {
                    ApplicationInfo appInfo = ActivityThread.getPackageManager()
                            .getApplicationInfo(pkgName, 0, UserHandle.getUserId(uid));
                    if (appInfo != null) {
                        isPrivileged = (appInfo.privateFlags
                                & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED) != 0;
                    }
                } else {
                    // Could not load data, don't add to cache so it will be loaded later.
                    return;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not contact PackageManager", e);
            }
        } else {
            isPrivileged = Boolean.parseBoolean(isPrivilegedString);
        }
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals("op")) {
                readOp(parser, uidState, pkgName, isPrivileged);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
        uidState.evalForegroundOps(mOpModeWatchers);
    }

    private void readOp(XmlPullParser parser, @NonNull UidState uidState,
            @NonNull String pkgName, boolean isPrivileged) throws NumberFormatException,
            XmlPullParserException, IOException {
        Op op = new Op(uidState, pkgName,
                Integer.parseInt(parser.getAttributeValue(null, "n")));

        final int mode = XmlUtils.readIntAttribute(parser, "m",
                AppOpsManager.opToDefaultMode(op.op));
        op.mode = mode;

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals("st")) {
                final long key = XmlUtils.readLongAttribute(parser, "n");

                final int flags = AppOpsManager.extractFlagsFromKey(key);
                final int state = AppOpsManager.extractUidStateFromKey(key);

                final long accessTime = XmlUtils.readLongAttribute(parser, "t", 0);
                final long rejectTime = XmlUtils.readLongAttribute(parser, "r", 0);
                final long accessDuration = XmlUtils.readLongAttribute(parser, "d", 0);
                final String proxyPkg = XmlUtils.readStringAttribute(parser, "pp");
                final int proxyUid = XmlUtils.readIntAttribute(parser, "pu", 0);

                if (accessTime > 0) {
                    op.accessed(accessTime, proxyUid, proxyPkg, state, flags);
                }
                if (rejectTime > 0) {
                    op.rejected(rejectTime, proxyUid, proxyPkg, state, flags);
                }
                if (accessDuration > 0) {
                    op.running(accessTime, accessDuration, state, flags);
                }
            } else {
                Slog.w(TAG, "Unknown element under <op>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }

        if (uidState.pkgOps == null) {
            uidState.pkgOps = new ArrayMap<>();
        }
        Ops ops = uidState.pkgOps.get(pkgName);
        if (ops == null) {
            ops = new Ops(pkgName, uidState, isPrivileged);
            uidState.pkgOps.put(pkgName, ops);
        }
        ops.put(op.op, op);
    }

    void writeState() {
        synchronized (mFile) {
            FileOutputStream stream;
            try {
                stream = mFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state: " + e);
                return;
            }

            List<AppOpsManager.PackageOps> allOps = getPackagesForOps(null);

            try {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, StandardCharsets.UTF_8.name());
                out.startDocument(null, true);
                out.startTag(null, "app-ops");
                out.attribute(null, "v", String.valueOf(CURRENT_VERSION));

                final int uidStateCount = mUidStates.size();
                for (int i = 0; i < uidStateCount; i++) {
                    UidState uidState = mUidStates.valueAt(i);
                    if (uidState.opModes != null && uidState.opModes.size() > 0) {
                        out.startTag(null, "uid");
                        out.attribute(null, "n", Integer.toString(uidState.uid));
                        SparseIntArray uidOpModes = uidState.opModes;
                        final int opCount = uidOpModes.size();
                        for (int j = 0; j < opCount; j++) {
                            final int op = uidOpModes.keyAt(j);
                            final int mode = uidOpModes.valueAt(j);
                            out.startTag(null, "op");
                            out.attribute(null, "n", Integer.toString(op));
                            out.attribute(null, "m", Integer.toString(mode));
                            out.endTag(null, "op");
                        }
                        out.endTag(null, "uid");
                    }
                }

                if (allOps != null) {
                    String lastPkg = null;
                    for (int i=0; i<allOps.size(); i++) {
                        AppOpsManager.PackageOps pkg = allOps.get(i);
                        if (!pkg.getPackageName().equals(lastPkg)) {
                            if (lastPkg != null) {
                                out.endTag(null, "pkg");
                            }
                            lastPkg = pkg.getPackageName();
                            out.startTag(null, "pkg");
                            out.attribute(null, "n", lastPkg);
                        }
                        out.startTag(null, "uid");
                        out.attribute(null, "n", Integer.toString(pkg.getUid()));
                        synchronized (this) {
                            Ops ops = getOpsRawLocked(pkg.getUid(), pkg.getPackageName(),
                                    false /* edit */, false /* uidMismatchExpected */);
                            // Should always be present as the list of PackageOps is generated
                            // from Ops.
                            if (ops != null) {
                                out.attribute(null, "p", Boolean.toString(ops.isPrivileged));
                            } else {
                                out.attribute(null, "p", Boolean.toString(false));
                            }
                        }
                        List<AppOpsManager.OpEntry> ops = pkg.getOps();
                        for (int j=0; j<ops.size(); j++) {
                            AppOpsManager.OpEntry op = ops.get(j);
                            out.startTag(null, "op");
                            out.attribute(null, "n", Integer.toString(op.getOp()));
                            if (op.getMode() != AppOpsManager.opToDefaultMode(op.getOp())) {
                                out.attribute(null, "m", Integer.toString(op.getMode()));
                            }

                            final LongSparseArray keys = op.collectKeys();
                            if (keys == null || keys.size() <= 0) {
                                continue;
                            }

                            final int keyCount = keys.size();
                            for (int k = 0; k < keyCount; k++) {
                                final long key = keys.keyAt(k);

                                final int uidState = AppOpsManager.extractUidStateFromKey(key);
                                final int flags = AppOpsManager.extractFlagsFromKey(key);

                                final long accessTime = op.getLastAccessTime(
                                        uidState, uidState, flags);
                                final long rejectTime = op.getLastRejectTime(
                                        uidState, uidState, flags);
                                final long accessDuration = op.getLastDuration(
                                        uidState, uidState, flags);
                                final String proxyPkg = op.getProxyPackageName(uidState, flags);
                                final int proxyUid = op.getProxyUid(uidState, flags);

                                if (accessTime <= 0 && rejectTime <= 0 && accessDuration <= 0
                                        && proxyPkg == null && proxyUid < 0) {
                                    continue;
                                }

                                out.startTag(null, "st");
                                out.attribute(null, "n", Long.toString(key));
                                if (accessTime > 0) {
                                    out.attribute(null, "t", Long.toString(accessTime));
                                }
                                if (rejectTime > 0) {
                                    out.attribute(null, "r", Long.toString(rejectTime));
                                }
                                if (accessDuration > 0) {
                                    out.attribute(null, "d", Long.toString(accessDuration));
                                }
                                if (proxyPkg != null) {
                                    out.attribute(null, "pp", proxyPkg);
                                }
                                if (proxyUid >= 0) {
                                    out.attribute(null, "pu", Integer.toString(proxyUid));
                                }
                                out.endTag(null, "st");
                            }

                            out.endTag(null, "op");
                        }
                        out.endTag(null, "uid");
                    }
                    if (lastPkg != null) {
                        out.endTag(null, "pkg");
                    }
                }

                out.endTag(null, "app-ops");
                out.endDocument();
                mFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state, restoring backup.", e);
                mFile.failWrite(stream);
            }
        }
    }

    static class Shell extends ShellCommand {
        final IAppOpsService mInterface;
        final AppOpsService mInternal;

        int userId = UserHandle.USER_SYSTEM;
        String packageName;
        String opStr;
        String modeStr;
        int op;
        int mode;
        int packageUid;
        int nonpackageUid;
        final static Binder sBinder = new Binder();
        IBinder mToken;

        Shell(IAppOpsService iface, AppOpsService internal) {
            mInterface = iface;
            mInternal = internal;
            try {
                mToken = mInterface.getToken(sBinder);
            } catch (RemoteException e) {
            }
        }

        @Override
        public int onCommand(String cmd) {
            return onShellCommand(this, cmd);
        }

        @Override
        public void onHelp() {
            PrintWriter pw = getOutPrintWriter();
            dumpCommandHelp(pw);
        }

        static private int strOpToOp(String op, PrintWriter err) {
            try {
                return AppOpsManager.strOpToOp(op);
            } catch (IllegalArgumentException e) {
            }
            try {
                return Integer.parseInt(op);
            } catch (NumberFormatException e) {
            }
            try {
                return AppOpsManager.strDebugOpToOp(op);
            } catch (IllegalArgumentException e) {
                err.println("Error: " + e.getMessage());
                return -1;
            }
        }

        static int strModeToMode(String modeStr, PrintWriter err) {
            for (int i = AppOpsManager.MODE_NAMES.length - 1; i >= 0; i--) {
                if (AppOpsManager.MODE_NAMES[i].equals(modeStr)) {
                    return i;
                }
            }
            try {
                return Integer.parseInt(modeStr);
            } catch (NumberFormatException e) {
            }
            err.println("Error: Mode " + modeStr + " is not valid");
            return -1;
        }

        int parseUserOpMode(int defMode, PrintWriter err) throws RemoteException {
            userId = UserHandle.USER_CURRENT;
            opStr = null;
            modeStr = null;
            for (String argument; (argument = getNextArg()) != null;) {
                if ("--user".equals(argument)) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    if (opStr == null) {
                        opStr = argument;
                    } else if (modeStr == null) {
                        modeStr = argument;
                        break;
                    }
                }
            }
            if (opStr == null) {
                err.println("Error: Operation not specified.");
                return -1;
            }
            op = strOpToOp(opStr, err);
            if (op < 0) {
                return -1;
            }
            if (modeStr != null) {
                if ((mode=strModeToMode(modeStr, err)) < 0) {
                    return -1;
                }
            } else {
                mode = defMode;
            }
            return 0;
        }

        int parseUserPackageOp(boolean reqOp, PrintWriter err) throws RemoteException {
            userId = UserHandle.USER_CURRENT;
            packageName = null;
            opStr = null;
            for (String argument; (argument = getNextArg()) != null;) {
                if ("--user".equals(argument)) {
                    userId = UserHandle.parseUserArg(getNextArgRequired());
                } else {
                    if (packageName == null) {
                        packageName = argument;
                    } else if (opStr == null) {
                        opStr = argument;
                        break;
                    }
                }
            }
            if (packageName == null) {
                err.println("Error: Package name not specified.");
                return -1;
            } else if (opStr == null && reqOp) {
                err.println("Error: Operation not specified.");
                return -1;
            }
            if (opStr != null) {
                op = strOpToOp(opStr, err);
                if (op < 0) {
                    return -1;
                }
            } else {
                op = AppOpsManager.OP_NONE;
            }
            if (userId == UserHandle.USER_CURRENT) {
                userId = ActivityManager.getCurrentUser();
            }
            nonpackageUid = -1;
            try {
                nonpackageUid = Integer.parseInt(packageName);
            } catch (NumberFormatException e) {
            }
            if (nonpackageUid == -1 && packageName.length() > 1 && packageName.charAt(0) == 'u'
                    && packageName.indexOf('.') < 0) {
                int i = 1;
                while (i < packageName.length() && packageName.charAt(i) >= '0'
                        && packageName.charAt(i) <= '9') {
                    i++;
                }
                if (i > 1 && i < packageName.length()) {
                    String userStr = packageName.substring(1, i);
                    try {
                        int user = Integer.parseInt(userStr);
                        char type = packageName.charAt(i);
                        i++;
                        int startTypeVal = i;
                        while (i < packageName.length() && packageName.charAt(i) >= '0'
                                && packageName.charAt(i) <= '9') {
                            i++;
                        }
                        if (i > startTypeVal) {
                            String typeValStr = packageName.substring(startTypeVal, i);
                            try {
                                int typeVal = Integer.parseInt(typeValStr);
                                if (type == 'a') {
                                    nonpackageUid = UserHandle.getUid(user,
                                            typeVal + Process.FIRST_APPLICATION_UID);
                                } else if (type == 's') {
                                    nonpackageUid = UserHandle.getUid(user, typeVal);
                                }
                            } catch (NumberFormatException e) {
                            }
                        }
                    } catch (NumberFormatException e) {
                    }
                }
            }
            if (nonpackageUid != -1) {
                packageName = null;
            } else {
                packageUid = resolveUid(packageName);
                if (packageUid < 0) {
                    packageUid = AppGlobals.getPackageManager().getPackageUid(packageName,
                            PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
                }
                if (packageUid < 0) {
                    err.println("Error: No UID for " + packageName + " in user " + userId);
                    return -1;
                }
            }
            return 0;
        }
    }

    @Override public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new Shell(this, this)).exec(this, in, out, err, args, callback, resultReceiver);
    }

    static void dumpCommandHelp(PrintWriter pw) {
        pw.println("AppOps service (appops) commands:");
        pw.println("  help");
        pw.println("    Print this help text.");
        pw.println("  start [--user <USER_ID>] <PACKAGE | UID> <OP> ");
        pw.println("    Starts a given operation for a particular application.");
        pw.println("  stop [--user <USER_ID>] <PACKAGE | UID> <OP> ");
        pw.println("    Stops a given operation for a particular application.");
        pw.println("  set [--user <USER_ID>] <PACKAGE | UID> <OP> <MODE>");
        pw.println("    Set the mode for a particular application and operation.");
        pw.println("  get [--user <USER_ID>] <PACKAGE | UID> [<OP>]");
        pw.println("    Return the mode for a particular application and optional operation.");
        pw.println("  query-op [--user <USER_ID>] <OP> [<MODE>]");
        pw.println("    Print all packages that currently have the given op in the given mode.");
        pw.println("  reset [--user <USER_ID>] [<PACKAGE>]");
        pw.println("    Reset the given application or all applications to default modes.");
        pw.println("  write-settings");
        pw.println("    Immediately write pending changes to storage.");
        pw.println("  read-settings");
        pw.println("    Read the last written settings, replacing current state in RAM.");
        pw.println("  options:");
        pw.println("    <PACKAGE> an Android package name.");
        pw.println("    <OP>      an AppOps operation.");
        pw.println("    <MODE>    one of allow, ignore, deny, or default");
        pw.println("    <USER_ID> the user id under which the package is installed. If --user is not");
        pw.println("              specified, the current user is assumed.");
    }

    static int onShellCommand(Shell shell, String cmd) {
        if (cmd == null) {
            return shell.handleDefaultCommands(cmd);
        }
        PrintWriter pw = shell.getOutPrintWriter();
        PrintWriter err = shell.getErrPrintWriter();
        try {
            switch (cmd) {
                case "set": {
                    int res = shell.parseUserPackageOp(true, err);
                    if (res < 0) {
                        return res;
                    }
                    String modeStr = shell.getNextArg();
                    if (modeStr == null) {
                        err.println("Error: Mode not specified.");
                        return -1;
                    }

                    final int mode = shell.strModeToMode(modeStr, err);
                    if (mode < 0) {
                        return -1;
                    }

                    if (shell.packageName != null) {
                        shell.mInterface.setMode(shell.op, shell.packageUid, shell.packageName,
                                mode);
                    } else {
                        shell.mInterface.setUidMode(shell.op, shell.nonpackageUid, mode);
                    }
                    return 0;
                }
                case "get": {
                    int res = shell.parseUserPackageOp(false, err);
                    if (res < 0) {
                        return res;
                    }

                    List<AppOpsManager.PackageOps> ops = new ArrayList<>();
                    if (shell.packageName != null) {
                        // Uid mode overrides package mode, so make sure it's also reported
                        List<AppOpsManager.PackageOps> r = shell.mInterface.getUidOps(
                                shell.packageUid,
                                shell.op != AppOpsManager.OP_NONE ? new int[]{shell.op} : null);
                        if (r != null) {
                            ops.addAll(r);
                        }
                        r = shell.mInterface.getOpsForPackage(
                                shell.packageUid, shell.packageName,
                                shell.op != AppOpsManager.OP_NONE ? new int[]{shell.op} : null);
                        if (r != null) {
                            ops.addAll(r);
                        }
                    } else {
                        ops = shell.mInterface.getUidOps(
                                shell.nonpackageUid,
                                shell.op != AppOpsManager.OP_NONE ? new int[]{shell.op} : null);
                    }
                    if (ops == null || ops.size() <= 0) {
                        pw.println("No operations.");
                        if (shell.op > AppOpsManager.OP_NONE && shell.op < AppOpsManager._NUM_OP) {
                            pw.println("Default mode: " + AppOpsManager.modeToName(
                                    AppOpsManager.opToDefaultMode(shell.op)));
                        }
                        return 0;
                    }
                    final long now = System.currentTimeMillis();
                    for (int i=0; i<ops.size(); i++) {
                        AppOpsManager.PackageOps packageOps = ops.get(i);
                        if (packageOps.getPackageName() == null) {
                            pw.print("Uid mode: ");
                        }
                        List<AppOpsManager.OpEntry> entries = packageOps.getOps();
                        for (int j=0; j<entries.size(); j++) {
                            AppOpsManager.OpEntry ent = entries.get(j);
                            pw.print(AppOpsManager.opToName(ent.getOp()));
                            pw.print(": ");
                            pw.print(AppOpsManager.modeToName(ent.getMode()));
                            if (ent.getTime() != 0) {
                                pw.print("; time=");
                                TimeUtils.formatDuration(now - ent.getTime(), pw);
                                pw.print(" ago");
                            }
                            if (ent.getRejectTime() != 0) {
                                pw.print("; rejectTime=");
                                TimeUtils.formatDuration(now - ent.getRejectTime(), pw);
                                pw.print(" ago");
                            }
                            if (ent.getDuration() == -1) {
                                pw.print(" (running)");
                            } else if (ent.getDuration() != 0) {
                                pw.print("; duration=");
                                TimeUtils.formatDuration(ent.getDuration(), pw);
                            }
                            pw.println();
                        }
                    }
                    return 0;
                }
                case "query-op": {
                    int res = shell.parseUserOpMode(AppOpsManager.MODE_IGNORED, err);
                    if (res < 0) {
                        return res;
                    }
                    List<AppOpsManager.PackageOps> ops = shell.mInterface.getPackagesForOps(
                            new int[] {shell.op});
                    if (ops == null || ops.size() <= 0) {
                        pw.println("No operations.");
                        return 0;
                    }
                    for (int i=0; i<ops.size(); i++) {
                        final AppOpsManager.PackageOps pkg = ops.get(i);
                        boolean hasMatch = false;
                        final List<AppOpsManager.OpEntry> entries = ops.get(i).getOps();
                        for (int j=0; j<entries.size(); j++) {
                            AppOpsManager.OpEntry ent = entries.get(j);
                            if (ent.getOp() == shell.op && ent.getMode() == shell.mode) {
                                hasMatch = true;
                                break;
                            }
                        }
                        if (hasMatch) {
                            pw.println(pkg.getPackageName());
                        }
                    }
                    return 0;
                }
                case "reset": {
                    String packageName = null;
                    int userId = UserHandle.USER_CURRENT;
                    for (String argument; (argument = shell.getNextArg()) != null;) {
                        if ("--user".equals(argument)) {
                            String userStr = shell.getNextArgRequired();
                            userId = UserHandle.parseUserArg(userStr);
                        } else {
                            if (packageName == null) {
                                packageName = argument;
                            } else {
                                err.println("Error: Unsupported argument: " + argument);
                                return -1;
                            }
                        }
                    }

                    if (userId == UserHandle.USER_CURRENT) {
                        userId = ActivityManager.getCurrentUser();
                    }

                    shell.mInterface.resetAllModes(userId, packageName);
                    pw.print("Reset all modes for: ");
                    if (userId == UserHandle.USER_ALL) {
                        pw.print("all users");
                    } else {
                        pw.print("user "); pw.print(userId);
                    }
                    pw.print(", ");
                    if (packageName == null) {
                        pw.println("all packages");
                    } else {
                        pw.print("package "); pw.println(packageName);
                    }
                    return 0;
                }
                case "write-settings": {
                    shell.mInternal.enforceManageAppOpsModes(Binder.getCallingPid(),
                            Binder.getCallingUid(), -1);
                    long token = Binder.clearCallingIdentity();
                    try {
                        synchronized (shell.mInternal) {
                            shell.mInternal.mHandler.removeCallbacks(shell.mInternal.mWriteRunner);
                        }
                        shell.mInternal.writeState();
                        pw.println("Current settings written.");
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    return 0;
                }
                case "read-settings": {
                    shell.mInternal.enforceManageAppOpsModes(Binder.getCallingPid(),
                            Binder.getCallingUid(), -1);
                    long token = Binder.clearCallingIdentity();
                    try {
                        shell.mInternal.readState();
                        pw.println("Last settings read.");
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    return 0;
                }
                case "start": {
                    int res = shell.parseUserPackageOp(true, err);
                    if (res < 0) {
                        return res;
                    }

                    if (shell.packageName != null) {
                        shell.mInterface.startOperation(shell.mToken,
                                shell.op, shell.packageUid, shell.packageName, true);
                    } else {
                        return -1;
                    }
                    return 0;
                }
                case "stop": {
                    int res = shell.parseUserPackageOp(true, err);
                    if (res < 0) {
                        return res;
                    }

                    if (shell.packageName != null) {
                        shell.mInterface.finishOperation(shell.mToken,
                                shell.op, shell.packageUid, shell.packageName);
                    } else {
                        return -1;
                    }
                    return 0;
                }
                default:
                    return shell.handleDefaultCommands(cmd);
            }
        } catch (RemoteException e) {
            pw.println("Remote exception: " + e);
        }
        return -1;
    }

    private void dumpHelp(PrintWriter pw) {
        pw.println("AppOps service (appops) dump options:");
        pw.println("  -h");
        pw.println("    Print this help text.");
        pw.println("  --op [OP]");
        pw.println("    Limit output to data associated with the given app op code.");
        pw.println("  --mode [MODE]");
        pw.println("    Limit output to data associated with the given app op mode.");
        pw.println("  --package [PACKAGE]");
        pw.println("    Limit output to data associated with the given package name.");
        pw.println("  --watchers");
        pw.println("    Only output the watcher sections.");
        pw.println("  --history");
        pw.println("    Output the historical data.");
    }

    private void dumpStatesLocked(@NonNull PrintWriter pw, @NonNull Op op,
            long now, @NonNull SimpleDateFormat sdf, @NonNull Date date, @NonNull String prefix) {

        final OpEntry entry = new OpEntry(op.op, op.running, op.mode, op.mAccessTimes,
            op.mRejectTimes, op.mDurations, op.mProxyUids, op.mProxyPackageNames);

        final LongSparseArray keys = entry.collectKeys();
        if (keys == null || keys.size() <= 0) {
            return;
        }

        final int keyCount = keys.size();
        for (int k = 0; k < keyCount; k++) {
            final long key = keys.keyAt(k);

            final int uidState = AppOpsManager.extractUidStateFromKey(key);
            final int flags = AppOpsManager.extractFlagsFromKey(key);

            final long accessTime = entry.getLastAccessTime(
                    uidState, uidState, flags);
            final long rejectTime = entry.getLastRejectTime(
                    uidState, uidState, flags);
            final long accessDuration = entry.getLastDuration(
                    uidState, uidState, flags);
            final String proxyPkg = entry.getProxyPackageName(uidState, flags);
            final int proxyUid = entry.getProxyUid(uidState, flags);

            if (accessTime > 0) {
                pw.print(prefix);
                pw.print("Access: ");
                pw.print(AppOpsManager.keyToString(key));
                pw.print(" ");
                date.setTime(accessTime);
                pw.print(sdf.format(date));
                pw.print(" (");
                TimeUtils.formatDuration(accessTime - now, pw);
                pw.print(")");
                if (accessDuration > 0) {
                    pw.print(" duration=");
                    TimeUtils.formatDuration(accessDuration, pw);
                }
                if (proxyUid >= 0) {
                    pw.print(" proxy[");
                    pw.print("uid=");
                    pw.print(proxyUid);
                    pw.print(", pkg=");
                    pw.print(proxyPkg);
                    pw.print("]");
                }
                pw.println();
            }

            if (rejectTime > 0) {
                pw.print(prefix);
                pw.print("Reject: ");
                pw.print(AppOpsManager.keyToString(key));
                date.setTime(rejectTime);
                pw.print(sdf.format(date));
                pw.print(" (");
                TimeUtils.formatDuration(rejectTime - now, pw);
                pw.print(")");
                if (proxyUid >= 0) {
                    pw.print(" proxy[");
                    pw.print("uid=");
                    pw.print(proxyUid);
                    pw.print(", pkg=");
                    pw.print(proxyPkg);
                    pw.print("]");
                }
                pw.println();
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        int dumpOp = OP_NONE;
        String dumpPackage = null;
        int dumpUid = Process.INVALID_UID;
        int dumpMode = -1;
        boolean dumpWatchers = false;
        boolean dumpHistory = false;

        if (args != null) {
            for (int i=0; i<args.length; i++) {
                String arg = args[i];
                if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    // dump all data
                } else if ("--op".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("No argument for --op option");
                        return;
                    }
                    dumpOp = Shell.strOpToOp(args[i], pw);
                    if (dumpOp < 0) {
                        return;
                    }
                } else if ("--package".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("No argument for --package option");
                        return;
                    }
                    dumpPackage = args[i];
                    try {
                        dumpUid = AppGlobals.getPackageManager().getPackageUid(dumpPackage,
                                PackageManager.MATCH_KNOWN_PACKAGES | PackageManager.MATCH_INSTANT,
                                0);
                    } catch (RemoteException e) {
                    }
                    if (dumpUid < 0) {
                        pw.println("Unknown package: " + dumpPackage);
                        return;
                    }
                    dumpUid = UserHandle.getAppId(dumpUid);
                } else if ("--mode".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("No argument for --mode option");
                        return;
                    }
                    dumpMode = Shell.strModeToMode(args[i], pw);
                    if (dumpMode < 0) {
                        return;
                    }
                } else if ("--watchers".equals(arg)) {
                    dumpWatchers = true;
                } else if ("--history".equals(arg)) {
                    dumpHistory = true;
                } else if (arg.length() > 0 && arg.charAt(0) == '-'){
                    pw.println("Unknown option: " + arg);
                    return;
                } else {
                    pw.println("Unknown command: " + arg);
                    return;
                }
            }
        }

        synchronized (this) {
            pw.println("Current AppOps Service state:");
            if (!dumpHistory && !dumpWatchers) {
                mConstants.dump(pw);
            }
            pw.println();
            final long now = System.currentTimeMillis();
            final long nowElapsed = SystemClock.elapsedRealtime();
            final long nowUptime = SystemClock.uptimeMillis();
            final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            final Date date = new Date();
            boolean needSep = false;
            if (dumpOp < 0 && dumpMode < 0 && dumpPackage == null && mProfileOwners != null
                    && !dumpWatchers && !dumpHistory) {
                pw.println("  Profile owners:");
                for (int poi = 0; poi < mProfileOwners.size(); poi++) {
                    pw.print("    User #");
                    pw.print(mProfileOwners.keyAt(poi));
                    pw.print(": ");
                    UserHandle.formatUid(pw, mProfileOwners.valueAt(poi));
                    pw.println();
                }
                pw.println();
            }
            if (mOpModeWatchers.size() > 0 && !dumpHistory) {
                boolean printedHeader = false;
                for (int i=0; i<mOpModeWatchers.size(); i++) {
                    if (dumpOp >= 0 && dumpOp != mOpModeWatchers.keyAt(i)) {
                        continue;
                    }
                    boolean printedOpHeader = false;
                    ArraySet<ModeCallback> callbacks = mOpModeWatchers.valueAt(i);
                    for (int j=0; j<callbacks.size(); j++) {
                        final ModeCallback cb = callbacks.valueAt(j);
                        if (dumpPackage != null
                                && dumpUid != UserHandle.getAppId(cb.mWatchingUid)) {
                            continue;
                        }
                        needSep = true;
                        if (!printedHeader) {
                            pw.println("  Op mode watchers:");
                            printedHeader = true;
                        }
                        if (!printedOpHeader) {
                            pw.print("    Op ");
                            pw.print(AppOpsManager.opToName(mOpModeWatchers.keyAt(i)));
                            pw.println(":");
                            printedOpHeader = true;
                        }
                        pw.print("      #"); pw.print(j); pw.print(": ");
                        pw.println(cb);
                    }
                }
            }
            if (mPackageModeWatchers.size() > 0 && dumpOp < 0 && !dumpHistory) {
                boolean printedHeader = false;
                for (int i=0; i<mPackageModeWatchers.size(); i++) {
                    if (dumpPackage != null && !dumpPackage.equals(mPackageModeWatchers.keyAt(i))) {
                        continue;
                    }
                    needSep = true;
                    if (!printedHeader) {
                        pw.println("  Package mode watchers:");
                        printedHeader = true;
                    }
                    pw.print("    Pkg "); pw.print(mPackageModeWatchers.keyAt(i));
                    pw.println(":");
                    ArraySet<ModeCallback> callbacks = mPackageModeWatchers.valueAt(i);
                    for (int j=0; j<callbacks.size(); j++) {
                        pw.print("      #"); pw.print(j); pw.print(": ");
                        pw.println(callbacks.valueAt(j));
                    }
                }
            }
            if (mModeWatchers.size() > 0 && dumpOp < 0 && !dumpHistory) {
                boolean printedHeader = false;
                for (int i=0; i<mModeWatchers.size(); i++) {
                    final ModeCallback cb = mModeWatchers.valueAt(i);
                    if (dumpPackage != null
                            && dumpUid != UserHandle.getAppId(cb.mWatchingUid)) {
                        continue;
                    }
                    needSep = true;
                    if (!printedHeader) {
                        pw.println("  All op mode watchers:");
                        printedHeader = true;
                    }
                    pw.print("    ");
                    pw.print(Integer.toHexString(System.identityHashCode(mModeWatchers.keyAt(i))));
                    pw.print(": "); pw.println(cb);
                }
            }
            if (mActiveWatchers.size() > 0 && dumpMode < 0) {
                needSep = true;
                boolean printedHeader = false;
                for (int i = 0; i < mActiveWatchers.size(); i++) {
                    final SparseArray<ActiveCallback> activeWatchers = mActiveWatchers.valueAt(i);
                    if (activeWatchers.size() <= 0) {
                        continue;
                    }
                    final ActiveCallback cb = activeWatchers.valueAt(0);
                    if (dumpOp >= 0 && activeWatchers.indexOfKey(dumpOp) < 0) {
                        continue;
                    }
                    if (dumpPackage != null
                            && dumpUid != UserHandle.getAppId(cb.mWatchingUid)) {
                        continue;
                    }
                    if (!printedHeader) {
                        pw.println("  All op active watchers:");
                        printedHeader = true;
                    }
                    pw.print("    ");
                    pw.print(Integer.toHexString(System.identityHashCode(
                            mActiveWatchers.keyAt(i))));
                    pw.println(" ->");
                    pw.print("        [");
                    final int opCount = activeWatchers.size();
                    for (i = 0; i < opCount; i++) {
                        if (i > 0) {
                            pw.print(' ');
                        }
                        pw.print(AppOpsManager.opToName(activeWatchers.keyAt(i)));
                        if (i < opCount - 1) {
                            pw.print(',');
                        }
                    }
                    pw.println("]");
                    pw.print("        ");
                    pw.println(cb);
                }
            }
            if (mNotedWatchers.size() > 0 && dumpMode < 0) {
                needSep = true;
                boolean printedHeader = false;
                for (int i = 0; i < mNotedWatchers.size(); i++) {
                    final SparseArray<NotedCallback> notedWatchers = mNotedWatchers.valueAt(i);
                    if (notedWatchers.size() <= 0) {
                        continue;
                    }
                    final NotedCallback cb = notedWatchers.valueAt(0);
                    if (dumpOp >= 0 && notedWatchers.indexOfKey(dumpOp) < 0) {
                        continue;
                    }
                    if (dumpPackage != null
                            && dumpUid != UserHandle.getAppId(cb.mWatchingUid)) {
                        continue;
                    }
                    if (!printedHeader) {
                        pw.println("  All op noted watchers:");
                        printedHeader = true;
                    }
                    pw.print("    ");
                    pw.print(Integer.toHexString(System.identityHashCode(
                            mNotedWatchers.keyAt(i))));
                    pw.println(" ->");
                    pw.print("        [");
                    final int opCount = notedWatchers.size();
                    for (i = 0; i < opCount; i++) {
                        if (i > 0) {
                            pw.print(' ');
                        }
                        pw.print(AppOpsManager.opToName(notedWatchers.keyAt(i)));
                        if (i < opCount - 1) {
                            pw.print(',');
                        }
                    }
                    pw.println("]");
                    pw.print("        ");
                    pw.println(cb);
                }
            }
            if (mClients.size() > 0 && dumpMode < 0 && !dumpWatchers && !dumpHistory) {
                needSep = true;
                boolean printedHeader = false;
                for (int i=0; i<mClients.size(); i++) {
                    boolean printedClient = false;
                    ClientState cs = mClients.valueAt(i);
                    if (cs.mStartedOps.size() > 0) {
                        boolean printedStarted = false;
                        for (int j=0; j<cs.mStartedOps.size(); j++) {
                            Op op = cs.mStartedOps.get(j);
                            if (dumpOp >= 0 && op.op != dumpOp) {
                                continue;
                            }
                            if (dumpPackage != null && !dumpPackage.equals(op.packageName)) {
                                continue;
                            }
                            if (!printedHeader) {
                                pw.println("  Clients:");
                                printedHeader = true;
                            }
                            if (!printedClient) {
                                pw.print("    "); pw.print(mClients.keyAt(i)); pw.println(":");
                                pw.print("      "); pw.println(cs);
                                printedClient = true;
                            }
                            if (!printedStarted) {
                                pw.println("      Started ops:");
                                printedStarted = true;
                            }
                            pw.print("        "); pw.print("uid="); pw.print(op.uidState.uid);
                            pw.print(" pkg="); pw.print(op.packageName);
                            pw.print(" op="); pw.println(AppOpsManager.opToName(op.op));
                        }
                    }
                }
            }
            if (mAudioRestrictions.size() > 0 && dumpOp < 0 && dumpPackage != null
                    && dumpMode < 0 && !dumpWatchers && !dumpWatchers) {
                boolean printedHeader = false;
                for (int o=0; o<mAudioRestrictions.size(); o++) {
                    final String op = AppOpsManager.opToName(mAudioRestrictions.keyAt(o));
                    final SparseArray<Restriction> restrictions = mAudioRestrictions.valueAt(o);
                    for (int i=0; i<restrictions.size(); i++) {
                        if (!printedHeader){
                            pw.println("  Audio Restrictions:");
                            printedHeader = true;
                            needSep = true;
                        }
                        final int usage = restrictions.keyAt(i);
                        pw.print("    "); pw.print(op);
                        pw.print(" usage="); pw.print(AudioAttributes.usageToString(usage));
                        Restriction r = restrictions.valueAt(i);
                        pw.print(": mode="); pw.println(AppOpsManager.modeToName(r.mode));
                        if (!r.exceptionPackages.isEmpty()) {
                            pw.println("      Exceptions:");
                            for (int j=0; j<r.exceptionPackages.size(); j++) {
                                pw.print("        "); pw.println(r.exceptionPackages.valueAt(j));
                            }
                        }
                    }
                }
            }
            if (needSep) {
                pw.println();
            }
            for (int i=0; i<mUidStates.size(); i++) {
                UidState uidState = mUidStates.valueAt(i);
                final SparseIntArray opModes = uidState.opModes;
                final ArrayMap<String, Ops> pkgOps = uidState.pkgOps;

                if (dumpWatchers || dumpHistory) {
                    continue;
                }
                if (dumpOp >= 0 || dumpPackage != null || dumpMode >= 0) {
                    boolean hasOp = dumpOp < 0 || (uidState.opModes != null
                            && uidState.opModes.indexOfKey(dumpOp) >= 0);
                    boolean hasPackage = dumpPackage == null;
                    boolean hasMode = dumpMode < 0;
                    if (!hasMode && opModes != null) {
                        for (int opi = 0; !hasMode && opi < opModes.size(); opi++) {
                            if (opModes.valueAt(opi) == dumpMode) {
                                hasMode = true;
                            }
                        }
                    }
                    if (pkgOps != null) {
                        for (int pkgi = 0;
                                 (!hasOp || !hasPackage || !hasMode) && pkgi < pkgOps.size();
                                 pkgi++) {
                            Ops ops = pkgOps.valueAt(pkgi);
                            if (!hasOp && ops != null && ops.indexOfKey(dumpOp) >= 0) {
                                hasOp = true;
                            }
                            if (!hasMode) {
                                for (int opi = 0; !hasMode && opi < ops.size(); opi++) {
                                    if (ops.valueAt(opi).mode == dumpMode) {
                                        hasMode = true;
                                    }
                                }
                            }
                            if (!hasPackage && dumpPackage.equals(ops.packageName)) {
                                hasPackage = true;
                            }
                        }
                    }
                    if (uidState.foregroundOps != null && !hasOp) {
                        if (uidState.foregroundOps.indexOfKey(dumpOp) > 0) {
                            hasOp = true;
                        }
                    }
                    if (!hasOp || !hasPackage || !hasMode) {
                        continue;
                    }
                }

                pw.print("  Uid "); UserHandle.formatUid(pw, uidState.uid); pw.println(":");
                pw.print("    state=");
                pw.println(AppOpsManager.getUidStateName(uidState.state));
                if (uidState.state != uidState.pendingState) {
                    pw.print("    pendingState=");
                    pw.println(AppOpsManager.getUidStateName(uidState.pendingState));
                }
                if (uidState.pendingStateCommitTime != 0) {
                    pw.print("    pendingStateCommitTime=");
                    TimeUtils.formatDuration(uidState.pendingStateCommitTime, nowElapsed, pw);
                    pw.println();
                }
                if (uidState.startNesting != 0) {
                    pw.print("    startNesting=");
                    pw.println(uidState.startNesting);
                }
                if (uidState.foregroundOps != null && (dumpMode < 0
                        || dumpMode == AppOpsManager.MODE_FOREGROUND)) {
                    pw.println("    foregroundOps:");
                    for (int j = 0; j < uidState.foregroundOps.size(); j++) {
                        if (dumpOp >= 0 && dumpOp != uidState.foregroundOps.keyAt(j)) {
                            continue;
                        }
                        pw.print("      ");
                        pw.print(AppOpsManager.opToName(uidState.foregroundOps.keyAt(j)));
                        pw.print(": ");
                        pw.println(uidState.foregroundOps.valueAt(j) ? "WATCHER" : "SILENT");
                    }
                    pw.print("    hasForegroundWatchers=");
                    pw.println(uidState.hasForegroundWatchers);
                }
                needSep = true;

                if (opModes != null) {
                    final int opModeCount = opModes.size();
                    for (int j = 0; j < opModeCount; j++) {
                        final int code = opModes.keyAt(j);
                        final int mode = opModes.valueAt(j);
                        if (dumpOp >= 0 && dumpOp != code) {
                            continue;
                        }
                        if (dumpMode >= 0 && dumpMode != mode) {
                            continue;
                        }
                        pw.print("      "); pw.print(AppOpsManager.opToName(code));
                        pw.print(": mode="); pw.println(AppOpsManager.modeToName(mode));
                    }
                }

                if (pkgOps == null) {
                    continue;
                }

                for (int pkgi = 0; pkgi < pkgOps.size(); pkgi++) {
                    final Ops ops = pkgOps.valueAt(pkgi);
                    if (dumpPackage != null && !dumpPackage.equals(ops.packageName)) {
                        continue;
                    }
                    boolean printedPackage = false;
                    for (int j=0; j<ops.size(); j++) {
                        final Op op = ops.valueAt(j);
                        final int opCode = op.op;
                        if (dumpOp >= 0 && dumpOp != opCode) {
                            continue;
                        }
                        if (dumpMode >= 0 && dumpMode != op.mode) {
                            continue;
                        }
                        if (!printedPackage) {
                            pw.print("    Package "); pw.print(ops.packageName); pw.println(":");
                            printedPackage = true;
                        }
                        pw.print("      "); pw.print(AppOpsManager.opToName(opCode));
                        pw.print(" ("); pw.print(AppOpsManager.modeToName(op.mode));
                        final int switchOp = AppOpsManager.opToSwitch(opCode);
                        if (switchOp != opCode) {
                            pw.print(" / switch ");
                            pw.print(AppOpsManager.opToName(switchOp));
                            final Op switchObj = ops.get(switchOp);
                            int mode = switchObj != null ? switchObj.mode
                                    : AppOpsManager.opToDefaultMode(switchOp);
                            pw.print("="); pw.print(AppOpsManager.modeToName(mode));
                        }
                        pw.println("): ");
                        dumpStatesLocked(pw, op, now, sdf, date, "          ");
                        if (op.running) {
                            pw.print("          Running start at: ");
                            TimeUtils.formatDuration(nowElapsed-op.startRealtime, pw);
                            pw.println();
                        }
                        if (op.startNesting != 0) {
                            pw.print("          startNesting=");
                            pw.println(op.startNesting);
                        }
                    }
                }
            }
            if (needSep) {
                pw.println();
            }

            final int userRestrictionCount = mOpUserRestrictions.size();
            for (int i = 0; i < userRestrictionCount; i++) {
                IBinder token = mOpUserRestrictions.keyAt(i);
                ClientRestrictionState restrictionState = mOpUserRestrictions.valueAt(i);
                boolean printedTokenHeader = false;

                if (dumpMode >= 0 || dumpWatchers || dumpHistory) {
                    continue;
                }

                final int restrictionCount = restrictionState.perUserRestrictions != null
                        ? restrictionState.perUserRestrictions.size() : 0;
                if (restrictionCount > 0 && dumpPackage == null) {
                    boolean printedOpsHeader = false;
                    for (int j = 0; j < restrictionCount; j++) {
                        int userId = restrictionState.perUserRestrictions.keyAt(j);
                        boolean[] restrictedOps = restrictionState.perUserRestrictions.valueAt(j);
                        if (restrictedOps == null) {
                            continue;
                        }
                        if (dumpOp >= 0 && (dumpOp >= restrictedOps.length
                                || !restrictedOps[dumpOp])) {
                            continue;
                        }
                        if (!printedTokenHeader) {
                            pw.println("  User restrictions for token " + token + ":");
                            printedTokenHeader = true;
                        }
                        if (!printedOpsHeader) {
                            pw.println("      Restricted ops:");
                            printedOpsHeader = true;
                        }
                        StringBuilder restrictedOpsValue = new StringBuilder();
                        restrictedOpsValue.append("[");
                        final int restrictedOpCount = restrictedOps.length;
                        for (int k = 0; k < restrictedOpCount; k++) {
                            if (restrictedOps[k]) {
                                if (restrictedOpsValue.length() > 1) {
                                    restrictedOpsValue.append(", ");
                                }
                                restrictedOpsValue.append(AppOpsManager.opToName(k));
                            }
                        }
                        restrictedOpsValue.append("]");
                        pw.print("        "); pw.print("user: "); pw.print(userId);
                                pw.print(" restricted ops: "); pw.println(restrictedOpsValue);
                    }
                }

                final int excludedPackageCount = restrictionState.perUserExcludedPackages != null
                        ? restrictionState.perUserExcludedPackages.size() : 0;
                if (excludedPackageCount > 0 && dumpOp < 0) {
                    boolean printedPackagesHeader = false;
                    for (int j = 0; j < excludedPackageCount; j++) {
                        int userId = restrictionState.perUserExcludedPackages.keyAt(j);
                        String[] packageNames = restrictionState.perUserExcludedPackages.valueAt(j);
                        if (packageNames == null) {
                            continue;
                        }
                        boolean hasPackage;
                        if (dumpPackage != null) {
                            hasPackage = false;
                            for (String pkg : packageNames) {
                                if (dumpPackage.equals(pkg)) {
                                    hasPackage = true;
                                    break;
                                }
                            }
                        } else {
                            hasPackage = true;
                        }
                        if (!hasPackage) {
                            continue;
                        }
                        if (!printedTokenHeader) {
                            pw.println("  User restrictions for token " + token + ":");
                            printedTokenHeader = true;
                        }
                        if (!printedPackagesHeader) {
                            pw.println("      Excluded packages:");
                            printedPackagesHeader = true;
                        }
                        pw.print("        "); pw.print("user: "); pw.print(userId);
                                pw.print(" packages: "); pw.println(Arrays.toString(packageNames));
                    }
                }
            }
        }

        // Must not hold the appops lock
        if (dumpHistory && !dumpWatchers) {
            mHistoricalRegistry.dump("  ", pw, dumpUid, dumpPackage, dumpOp);
        }
    }

    private static final class Restriction {
        private static final ArraySet<String> NO_EXCEPTIONS = new ArraySet<String>();
        int mode;
        ArraySet<String> exceptionPackages = NO_EXCEPTIONS;
    }

    @Override
    public void setUserRestrictions(Bundle restrictions, IBinder token, int userHandle) {
        checkSystemUid("setUserRestrictions");
        Preconditions.checkNotNull(restrictions);
        Preconditions.checkNotNull(token);
        for (int i = 0; i < AppOpsManager._NUM_OP; i++) {
            String restriction = AppOpsManager.opToRestriction(i);
            if (restriction != null) {
                setUserRestrictionNoCheck(i, restrictions.getBoolean(restriction, false), token,
                        userHandle, null);
            }
        }
    }

    @Override
    public void setUserRestriction(int code, boolean restricted, IBinder token, int userHandle,
            String[] exceptionPackages) {
        if (Binder.getCallingPid() != Process.myPid()) {
            mContext.enforcePermission(Manifest.permission.MANAGE_APP_OPS_RESTRICTIONS,
                    Binder.getCallingPid(), Binder.getCallingUid(), null);
        }
        if (userHandle != UserHandle.getCallingUserId()) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission
                    .INTERACT_ACROSS_USERS_FULL) != PackageManager.PERMISSION_GRANTED
                && mContext.checkCallingOrSelfPermission(Manifest.permission
                    .INTERACT_ACROSS_USERS) != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Need INTERACT_ACROSS_USERS_FULL or"
                        + " INTERACT_ACROSS_USERS to interact cross user ");
            }
        }
        verifyIncomingOp(code);
        Preconditions.checkNotNull(token);
        setUserRestrictionNoCheck(code, restricted, token, userHandle, exceptionPackages);
    }

    private void setUserRestrictionNoCheck(int code, boolean restricted, IBinder token,
            int userHandle, String[] exceptionPackages) {
        synchronized (AppOpsService.this) {
            ClientRestrictionState restrictionState = mOpUserRestrictions.get(token);

            if (restrictionState == null) {
                try {
                    restrictionState = new ClientRestrictionState(token);
                } catch (RemoteException e) {
                    return;
                }
                mOpUserRestrictions.put(token, restrictionState);
            }

            if (restrictionState.setRestriction(code, restricted, exceptionPackages, userHandle)) {
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        AppOpsService::notifyWatchersOfChange, this, code, UID_ANY));
            }

            if (restrictionState.isDefault()) {
                mOpUserRestrictions.remove(token);
                restrictionState.destroy();
            }
        }
    }

    private void notifyWatchersOfChange(int code, int uid) {
        final ArraySet<ModeCallback> clonedCallbacks;
        synchronized (this) {
            ArraySet<ModeCallback> callbacks = mOpModeWatchers.get(code);
            if (callbacks == null) {
                return;
            }
            clonedCallbacks = new ArraySet<>(callbacks);
        }

        notifyOpChanged(clonedCallbacks,  code, uid, null);
    }

    @Override
    public void removeUser(int userHandle) throws RemoteException {
        checkSystemUid("removeUser");
        synchronized (AppOpsService.this) {
            final int tokenCount = mOpUserRestrictions.size();
            for (int i = tokenCount - 1; i >= 0; i--) {
                ClientRestrictionState opRestrictions = mOpUserRestrictions.valueAt(i);
                opRestrictions.removeUser(userHandle);
            }
            removeUidsForUserLocked(userHandle);
        }
    }

    @Override
    public boolean isOperationActive(int code, int uid, String packageName) {
        if (Binder.getCallingUid() != uid) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.WATCH_APPOPS)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        verifyIncomingOp(code);
        final String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return false;
        }
        synchronized (AppOpsService.this) {
            for (int i = mClients.size() - 1; i >= 0; i--) {
                final ClientState client = mClients.valueAt(i);
                for (int j = client.mStartedOps.size() - 1; j >= 0; j--) {
                    final Op op = client.mStartedOps.get(j);
                    if (op.op == code && op.uidState.uid == uid) return true;
                }
            }
        }
        return false;
    }

    @Override
    public void setHistoryParameters(@AppOpsManager.HistoricalMode int mode,
            long baseSnapshotInterval, int compressionStep) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_APPOPS,
                "setHistoryParameters");
        // Must not hold the appops lock
        mHistoricalRegistry.setHistoryParameters(mode, baseSnapshotInterval, compressionStep);
    }

    @Override
    public void offsetHistory(long offsetMillis) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_APPOPS,
                "offsetHistory");
        // Must not hold the appops lock
        mHistoricalRegistry.offsetHistory(offsetMillis);
    }

    @Override
    public void addHistoricalOps(HistoricalOps ops) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_APPOPS,
                "addHistoricalOps");
        // Must not hold the appops lock
        mHistoricalRegistry.addHistoricalOps(ops);
    }

    @Override
    public void resetHistoryParameters() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_APPOPS,
                "resetHistoryParameters");
        // Must not hold the appops lock
        mHistoricalRegistry.resetHistoryParameters();
    }

    @Override
    public void clearHistory() {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_APPOPS,
                "clearHistory");
        // Must not hold the appops lock
        mHistoricalRegistry.clearHistory();
    }

    private void removeUidsForUserLocked(int userHandle) {
        for (int i = mUidStates.size() - 1; i >= 0; --i) {
            final int uid = mUidStates.keyAt(i);
            if (UserHandle.getUserId(uid) == userHandle) {
                mUidStates.removeAt(i);
            }
        }
    }

    private void checkSystemUid(String function) {
        int uid = Binder.getCallingUid();
        if (uid != Process.SYSTEM_UID) {
            throw new SecurityException(function + " must by called by the system");
        }
    }

    private static String resolvePackageName(int uid, String packageName)  {
        if (uid == Process.ROOT_UID) {
            return "root";
        } else if (uid == Process.SHELL_UID) {
            return "com.android.shell";
        } else if (uid == Process.MEDIA_UID) {
            return "media";
        } else if (uid == Process.AUDIOSERVER_UID) {
            return "audioserver";
        } else if (uid == Process.CAMERASERVER_UID) {
            return "cameraserver";
        } else if (uid == Process.SYSTEM_UID && packageName == null) {
            return "android";
        }
        return packageName;
    }

    private static int resolveUid(String packageName)  {
        if (packageName == null) {
            return -1;
        }
        switch (packageName) {
            case "root":
                return Process.ROOT_UID;
            case "shell":
                return Process.SHELL_UID;
            case "media":
                return Process.MEDIA_UID;
            case "audioserver":
                return Process.AUDIOSERVER_UID;
            case "cameraserver":
                return Process.CAMERASERVER_UID;
        }
        return -1;
    }

    private static String[] getPackagesForUid(int uid) {
        String[] packageNames = null;

        // Very early during boot the package manager is not yet or not yet fully started. At this
        // time there are no packages yet.
        if (AppGlobals.getPackageManager() != null) {
            try {
                packageNames = AppGlobals.getPackageManager().getPackagesForUid(uid);
            } catch (RemoteException e) {
                /* ignore - local call */
            }
        }
        if (packageNames == null) {
            return EmptyArray.STRING;
        }
        return packageNames;
    }

    private final class ClientRestrictionState implements DeathRecipient {
        private final IBinder token;
        SparseArray<boolean[]> perUserRestrictions;
        SparseArray<String[]> perUserExcludedPackages;

        public ClientRestrictionState(IBinder token)
                throws RemoteException {
            token.linkToDeath(this, 0);
            this.token = token;
        }

        public boolean setRestriction(int code, boolean restricted,
                String[] excludedPackages, int userId) {
            boolean changed = false;

            if (perUserRestrictions == null && restricted) {
                perUserRestrictions = new SparseArray<>();
            }

            int[] users;
            if (userId == UserHandle.USER_ALL) {
                List<UserInfo> liveUsers = UserManager.get(mContext).getUsers(false);

                users = new int[liveUsers.size()];
                for (int i = 0; i < liveUsers.size(); i++) {
                    users[i] = liveUsers.get(i).id;
                }
            } else {
                users = new int[]{userId};
            }

            if (perUserRestrictions != null) {
                int numUsers = users.length;

                for (int i = 0; i < numUsers; i++) {
                    int thisUserId = users[i];

                    boolean[] userRestrictions = perUserRestrictions.get(thisUserId);
                    if (userRestrictions == null && restricted) {
                        userRestrictions = new boolean[AppOpsManager._NUM_OP];
                        perUserRestrictions.put(thisUserId, userRestrictions);
                    }
                    if (userRestrictions != null && userRestrictions[code] != restricted) {
                        userRestrictions[code] = restricted;
                        if (!restricted && isDefault(userRestrictions)) {
                            perUserRestrictions.remove(thisUserId);
                            userRestrictions = null;
                        }
                        changed = true;
                    }

                    if (userRestrictions != null) {
                        final boolean noExcludedPackages = ArrayUtils.isEmpty(excludedPackages);
                        if (perUserExcludedPackages == null && !noExcludedPackages) {
                            perUserExcludedPackages = new SparseArray<>();
                        }
                        if (perUserExcludedPackages != null && !Arrays.equals(excludedPackages,
                                perUserExcludedPackages.get(thisUserId))) {
                            if (noExcludedPackages) {
                                perUserExcludedPackages.remove(thisUserId);
                                if (perUserExcludedPackages.size() <= 0) {
                                    perUserExcludedPackages = null;
                                }
                            } else {
                                perUserExcludedPackages.put(thisUserId, excludedPackages);
                            }
                            changed = true;
                        }
                    }
                }
            }

            return changed;
        }

        public boolean hasRestriction(int restriction, String packageName, int userId) {
            if (perUserRestrictions == null) {
                return false;
            }
            boolean[] restrictions = perUserRestrictions.get(userId);
            if (restrictions == null) {
                return false;
            }
            if (!restrictions[restriction]) {
                return false;
            }
            if (perUserExcludedPackages == null) {
                return true;
            }
            String[] perUserExclusions = perUserExcludedPackages.get(userId);
            if (perUserExclusions == null) {
                return true;
            }
            return !ArrayUtils.contains(perUserExclusions, packageName);
        }

        public void removeUser(int userId) {
            if (perUserExcludedPackages != null) {
                perUserExcludedPackages.remove(userId);
                if (perUserExcludedPackages.size() <= 0) {
                    perUserExcludedPackages = null;
                }
            }
            if (perUserRestrictions != null) {
                perUserRestrictions.remove(userId);
                if (perUserRestrictions.size() <= 0) {
                    perUserRestrictions = null;
                }
            }
        }

        public boolean isDefault() {
            return perUserRestrictions == null || perUserRestrictions.size() <= 0;
        }

        @Override
        public void binderDied() {
            synchronized (AppOpsService.this) {
                mOpUserRestrictions.remove(token);
                if (perUserRestrictions == null) {
                    return;
                }
                final int userCount = perUserRestrictions.size();
                for (int i = 0; i < userCount; i++) {
                    final boolean[] restrictions = perUserRestrictions.valueAt(i);
                    final int restrictionCount = restrictions.length;
                    for (int j = 0; j < restrictionCount; j++) {
                        if (restrictions[j]) {
                            final int changedCode = j;
                            mHandler.post(() -> notifyWatchersOfChange(changedCode, UID_ANY));
                        }
                    }
                }
                destroy();
            }
        }

        public void destroy() {
            token.unlinkToDeath(this, 0);
        }

        private boolean isDefault(boolean[] array) {
            if (ArrayUtils.isEmpty(array)) {
                return true;
            }
            for (boolean value : array) {
                if (value) {
                    return false;
                }
            }
            return true;
        }
    }

    private final class AppOpsManagerInternalImpl extends AppOpsManagerInternal {
        @Override public void setDeviceAndProfileOwners(SparseIntArray owners) {
            synchronized (AppOpsService.this) {
                mProfileOwners = owners;
            }
        }

        @Override
        public void setUidMode(int code, int uid, int mode) {
            AppOpsService.this.setUidMode(code, uid, mode);
        }

        @Override
        public void setAllPkgModesToDefault(int code, int uid) {
            AppOpsService.this.setAllPkgModesToDefault(code, uid);
        }

        @Override
        public @Mode int checkOperationUnchecked(int code, int uid, @NonNull String packageName) {
            return AppOpsService.this.checkOperationUnchecked(code, uid, packageName, true, false);
        }
    }
}
