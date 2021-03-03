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

import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_CAMERA;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_LOCATION;
import static android.app.ActivityManager.PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;
import static android.app.AppOpsManager.CALL_BACK_ON_SWITCHED_OP;
import static android.app.AppOpsManager.FILTER_BY_ATTRIBUTION_TAG;
import static android.app.AppOpsManager.FILTER_BY_OP_NAMES;
import static android.app.AppOpsManager.FILTER_BY_PACKAGE_NAME;
import static android.app.AppOpsManager.FILTER_BY_UID;
import static android.app.AppOpsManager.HistoricalOpsRequestFilter;
import static android.app.AppOpsManager.KEY_BG_STATE_SETTLE_TIME;
import static android.app.AppOpsManager.KEY_FG_SERVICE_STATE_SETTLE_TIME;
import static android.app.AppOpsManager.KEY_TOP_STATE_SETTLE_TIME;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.NoteOpEvent;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_FLAGS_ALL;
import static android.app.AppOpsManager.OP_FLAG_SELF;
import static android.app.AppOpsManager.OP_FLAG_TRUSTED_PROXIED;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.OP_PLAY_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO_HOTWORD;
import static android.app.AppOpsManager.OpEventProxyInfo;
import static android.app.AppOpsManager.RestrictionBypass;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_BOOT_TIME_SAMPLING;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_RARELY_USED;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_UNIFORM;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_UNIFORM_OPS;
import static android.app.AppOpsManager.SECURITY_EXCEPTION_ON_INVALID_ATTRIBUTION_TAG_CHANGE;
import static android.app.AppOpsManager.UID_STATE_BACKGROUND;
import static android.app.AppOpsManager.UID_STATE_CACHED;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND;
import static android.app.AppOpsManager.UID_STATE_FOREGROUND_SERVICE;
import static android.app.AppOpsManager.UID_STATE_MAX_LAST_NON_RESTRICTED;
import static android.app.AppOpsManager.UID_STATE_PERSISTENT;
import static android.app.AppOpsManager.UID_STATE_TOP;
import static android.app.AppOpsManager.WATCH_FOREGROUND_CHANGES;
import static android.app.AppOpsManager._NUM_OP;
import static android.app.AppOpsManager.extractFlagsFromKey;
import static android.app.AppOpsManager.extractUidStateFromKey;
import static android.app.AppOpsManager.makeKey;
import static android.app.AppOpsManager.modeToName;
import static android.app.AppOpsManager.opAllowSystemBypassRestriction;
import static android.app.AppOpsManager.opToName;
import static android.app.AppOpsManager.opToPublicName;
import static android.app.AppOpsManager.resolveFirstUnrestrictedUidState;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.Intent.EXTRA_REPLACING;
import static android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
import static android.content.pm.PermissionInfo.PROTECTION_FLAG_APPOP;

import static com.android.server.appop.AppOpsService.ModeCallback.ALL_OPS;

import static java.lang.Long.max;

import android.Manifest;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManager.AttributedOpEntry;
import android.app.AppOpsManager.HistoricalOps;
import android.app.AppOpsManager.Mode;
import android.app.AppOpsManager.OpEntry;
import android.app.AppOpsManager.OpFlags;
import android.app.AppOpsManagerInternal;
import android.app.AppOpsManagerInternal.CheckOpsDelegate;
import android.app.AsyncNotedAppOp;
import android.app.RuntimeAppOpAccessMessage;
import android.app.SyncNotedAppOp;
import android.app.admin.DevicePolicyManagerInternal;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PermissionInfo;
import android.content.pm.UserInfo;
import android.content.pm.parsing.component.ParsedAttribution;
import android.database.ContentObserver;
import android.hardware.camera2.CameraDevice.CAMERA_AUDIO_RESTRICTION;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ServiceManager;
import android.os.ShellCallback;
import android.os.ShellCommand;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageManagerInternal;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.KeyValueListParser;
import android.util.LongSparseArray;
import android.util.Pair;
import android.util.Pools;
import android.util.Pools.SimplePool;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.Immutable;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsAsyncNotedCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsNotedCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IAppOpsStartedCallback;
import com.android.internal.app.MessageSamplingConfig;
import com.android.internal.compat.IPlatformCompat;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemServiceManager;
import com.android.server.pm.PackageList;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import libcore.util.EmptyArray;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class AppOpsService extends IAppOpsService.Stub {
    static final String TAG = "AppOps";
    static final boolean DEBUG = false;

    /**
     * Used for data access validation collection, we wish to only log a specific access once
     */
    private final ArraySet<NoteOpTrace> mNoteOpCallerStacktraces = new ArraySet<>();

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
        UID_STATE_FOREGROUND,           // ActivityManager.PROCESS_STATE_BOUND_TOP
        UID_STATE_FOREGROUND_SERVICE,   // ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE
        UID_STATE_FOREGROUND,           // ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE
        UID_STATE_BACKGROUND,           // ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND
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

    private static final int[] OPS_RESTRICTED_ON_SUSPEND = {
            OP_PLAY_AUDIO,
            OP_RECORD_AUDIO,
            OP_CAMERA,
    };

    private static final int MAX_UNFORWARDED_OPS = 10;
    private static final int MAX_UNUSED_POOLED_OBJECTS = 3;
    private static final int RARELY_USED_PACKAGES_INITIALIZATION_DELAY_MILLIS = 300000;

    //TODO: remove this when development is done.
    private static final int DEBUG_FGS_ALLOW_WHILE_IN_USE = 0;
    private static final int DEBUG_FGS_ENFORCE_TYPE = 1;


    final Context mContext;
    final AtomicFile mFile;
    private final @Nullable File mNoteOpCallerStacktracesFile;
    final Handler mHandler;

    /** Pool for {@link OpEventProxyInfoPool} to avoid to constantly reallocate new objects */
    @GuardedBy("this")
    private final OpEventProxyInfoPool mOpEventProxyInfoPool = new OpEventProxyInfoPool();

    /** Pool for {@link InProgressStartOpEventPool} to avoid to constantly reallocate new objects */
    @GuardedBy("this")
    private final InProgressStartOpEventPool mInProgressStartOpEventPool =
            new InProgressStartOpEventPool();

    private final AppOpsManagerInternalImpl mAppOpsManagerInternal
            = new AppOpsManagerInternalImpl();
    @Nullable private final DevicePolicyManagerInternal dpmi =
            LocalServices.getService(DevicePolicyManagerInternal.class);

    private final IPlatformCompat mPlatformCompat = IPlatformCompat.Stub.asInterface(
            ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));

    /**
     * Registered callbacks, called from {@link #collectAsyncNotedOp}.
     *
     * <p>(package name, uid) -> callbacks
     *
     * @see #getAsyncNotedOpsKey(String, int)
     */
    @GuardedBy("this")
    private final ArrayMap<Pair<String, Integer>, RemoteCallbackList<IAppOpsAsyncNotedCallback>>
            mAsyncOpWatchers = new ArrayMap<>();

    /**
     * Async note-ops collected from {@link #collectAsyncNotedOp} that have not been delivered to a
     * callback yet.
     *
     * <p>(package name, uid) -> list&lt;ops&gt;
     *
     * @see #getAsyncNotedOpsKey(String, int)
     */
    @GuardedBy("this")
    private final ArrayMap<Pair<String, Integer>, ArrayList<AsyncNotedAppOp>>
            mUnforwardedAsyncNotedOps = new ArrayMap<>();

    boolean mWriteNoteOpsScheduled;

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

    @GuardedBy("this")
    @VisibleForTesting
    final SparseArray<UidState> mUidStates = new SparseArray<>();

    volatile @NonNull HistoricalRegistry mHistoricalRegistry = new HistoricalRegistry(this);

    long mLastRealtime;

    /*
     * These are app op restrictions imposed per user from various parties.
     */
    private final ArrayMap<IBinder, ClientRestrictionState> mOpUserRestrictions = new ArrayMap<>();

    SparseIntArray mProfileOwners;

    @GuardedBy("this")
    private CheckOpsDelegate mAppOpsPolicy;

    @GuardedBy("this")
    private CheckOpsDelegateDispatcher mCheckOpsDelegateDispatcher;

    /**
      * Reverse lookup for {@link AppOpsManager#opToSwitch(int)}. Initialized once and never
      * changed
      */
    private final SparseArray<int[]> mSwitchedOps = new SparseArray<>();

    private ActivityManagerInternal mActivityManagerInternal;

    /** Package sampled for message collection in the current session */
    @GuardedBy("this")
    private String mSampledPackage = null;

    /** Appop sampled for message collection in the current session */
    @GuardedBy("this")
    private int mSampledAppOpCode = OP_NONE;

    /** Maximum distance for appop to be considered for message collection in the current session */
    @GuardedBy("this")
    private int mAcceptableLeftDistance = 0;

    /** Number of messages collected for sampled package and appop in the current session */
    @GuardedBy("this")
    private float mMessagesCollectedCount;

    /** List of rarely used packages priorities for message collection */
    @GuardedBy("this")
    private ArraySet<String> mRarelyUsedPackages = new ArraySet<>();

    /** Sampling strategy used for current session */
    @GuardedBy("this")
    @AppOpsManager.SamplingStrategy
    private int mSamplingStrategy;

    /** Last runtime permission access message collected and ready for reporting */
    @GuardedBy("this")
    private RuntimeAppOpAccessMessage mCollectedRuntimePermissionMessage;

    /** Package Manager internal. Access via {@link #getPackageManagerInternal()} */
    private @Nullable PackageManagerInternal mPackageManagerInternal;

    /**
     * An unsynchronized pool of {@link OpEventProxyInfo} objects.
     */
    private class OpEventProxyInfoPool extends SimplePool<OpEventProxyInfo> {
        OpEventProxyInfoPool() {
            super(MAX_UNUSED_POOLED_OBJECTS);
        }

        OpEventProxyInfo acquire(@IntRange(from = 0) int uid, @Nullable String packageName,
                @Nullable String attributionTag) {
            OpEventProxyInfo recycled = acquire();
            if (recycled != null) {
                recycled.reinit(uid, packageName, attributionTag);
                return recycled;
            }

            return new OpEventProxyInfo(uid, packageName, attributionTag);
        }
    }

    /**
     * An unsynchronized pool of {@link InProgressStartOpEvent} objects.
     */
    private class InProgressStartOpEventPool extends SimplePool<InProgressStartOpEvent> {
        InProgressStartOpEventPool() {
            super(MAX_UNUSED_POOLED_OBJECTS);
        }

        InProgressStartOpEvent acquire(long startTime, long elapsedTime, @NonNull IBinder clientId,
                @NonNull Runnable onDeath, int proxyUid, @Nullable String proxyPackageName,
                @Nullable String proxyAttributionTag, @AppOpsManager.UidState int uidState,
                @OpFlags int flags) throws RemoteException {

            InProgressStartOpEvent recycled = acquire();

            OpEventProxyInfo proxyInfo = null;
            if (proxyUid != Process.INVALID_UID) {
                proxyInfo = mOpEventProxyInfoPool.acquire(proxyUid, proxyPackageName,
                        proxyAttributionTag);
            }

            if (recycled != null) {
                recycled.reinit(startTime, elapsedTime, clientId, onDeath, uidState, flags,
                        proxyInfo, mOpEventProxyInfoPool);
                return recycled;
            }

            return new InProgressStartOpEvent(startTime, elapsedTime, clientId, onDeath, uidState,
                    proxyInfo, flags);
        }
    }

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the AppOpsService lock.
     */
    @VisibleForTesting
    final class Constants extends ContentObserver {

        /**
         * How long we want for a drop in uid state from top to settle before applying it.
         * @see Settings.Global#APP_OPS_CONSTANTS
         * @see AppOpsManager#KEY_TOP_STATE_SETTLE_TIME
         */
        public long TOP_STATE_SETTLE_TIME;

        /**
         * How long we want for a drop in uid state from foreground to settle before applying it.
         * @see Settings.Global#APP_OPS_CONSTANTS
         * @see AppOpsManager#KEY_FG_SERVICE_STATE_SETTLE_TIME
         */
        public long FG_SERVICE_STATE_SETTLE_TIME;

        /**
         * How long we want for a drop in uid state from background to settle before applying it.
         * @see Settings.Global#APP_OPS_CONSTANTS
         * @see AppOpsManager#KEY_BG_STATE_SETTLE_TIME
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
                        KEY_TOP_STATE_SETTLE_TIME, 5 * 1000L);
                FG_SERVICE_STATE_SETTLE_TIME = mParser.getDurationMillis(
                        KEY_FG_SERVICE_STATE_SETTLE_TIME, 5 * 1000L);
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

    @VisibleForTesting
    final Constants mConstants;

    @VisibleForTesting
    final class UidState {
        public final int uid;

        public int state = UID_STATE_CACHED;
        public int pendingState = UID_STATE_CACHED;
        public long pendingStateCommitTime;
        public int capability;
        public int pendingCapability;
        public boolean appWidgetVisible;
        public boolean pendingAppWidgetVisible;

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
            if (mode == MODE_FOREGROUND) {
                if (appWidgetVisible) {
                    return MODE_ALLOWED;
                } else if (mActivityManagerInternal != null
                        && mActivityManagerInternal.isPendingTopUid(uid)) {
                    return MODE_ALLOWED;
                } else if (state <= UID_STATE_TOP) {
                    // process is in TOP.
                    return MODE_ALLOWED;
                } else if (state <= AppOpsManager.resolveFirstUnrestrictedUidState(op)) {
                    // process is in foreground, check its capability.
                    switch (op) {
                        case AppOpsManager.OP_FINE_LOCATION:
                        case AppOpsManager.OP_COARSE_LOCATION:
                        case AppOpsManager.OP_MONITOR_LOCATION:
                        case AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION:
                            if ((capability & PROCESS_CAPABILITY_FOREGROUND_LOCATION) != 0) {
                                return MODE_ALLOWED;
                            } else {
                                return MODE_IGNORED;
                            }
                        case OP_CAMERA:
                            if ((capability & PROCESS_CAPABILITY_FOREGROUND_CAMERA) != 0) {
                                return MODE_ALLOWED;
                            } else {
                                return MODE_IGNORED;
                            }
                        case OP_RECORD_AUDIO:
                            if ((capability & PROCESS_CAPABILITY_FOREGROUND_MICROPHONE) != 0) {
                                return MODE_ALLOWED;
                            } else {
                                return MODE_IGNORED;
                            }
                        default:
                            return MODE_ALLOWED;
                    }
                } else {
                    // process is not in foreground.
                    return MODE_IGNORED;
                }
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

        /**
         * The restriction properties of the package. If {@code null} it could not have been read
         * yet and has to be refreshed.
         */
        @Nullable RestrictionBypass bypass;

        /** Lazily populated cache of attributionTags of this package */
        final @NonNull ArraySet<String> knownAttributionTags = new ArraySet<>();

        Ops(String _packageName, UidState _uidState) {
            packageName = _packageName;
            uidState = _uidState;
        }
    }

    /** A in progress startOp->finishOp event */
    private static final class InProgressStartOpEvent implements IBinder.DeathRecipient {
        /** Wall clock time of startOp event (not monotonic) */
        private long mStartTime;

        /** Elapsed time since boot of startOp event */
        private long mStartElapsedTime;

        /** Id of the client that started the event */
        private @NonNull IBinder mClientId;

        /** To call when client dies */
        private @NonNull Runnable mOnDeath;

        /** uidstate used when calling startOp */
        private @AppOpsManager.UidState int mUidState;

        /** Proxy information of the startOp event */
        private @Nullable OpEventProxyInfo mProxy;

        /** Proxy flag information */
        private @OpFlags int mFlags;

        /** How many times the op was started but not finished yet */
        int numUnfinishedStarts;

        /**
         * Create a new {@link InProgressStartOpEvent}.
         *
         * @param startTime The time {@link #startOperation} was called
         * @param startElapsedTime The elapsed time when {@link #startOperation} was called
         * @param clientId The client id of the caller of {@link #startOperation}
         * @param onDeath The code to execute on client death
         * @param uidState The uidstate of the app {@link #startOperation} was called for
         * @param proxy The proxy information, if {@link #startProxyOperation} was called
         * @param flags The trusted/nontrusted/self flags.
         *
         * @throws RemoteException If the client is dying
         */
        private InProgressStartOpEvent(long startTime, long startElapsedTime,
                @NonNull IBinder clientId, @NonNull Runnable onDeath,
                @AppOpsManager.UidState int uidState, @Nullable OpEventProxyInfo proxy,
                @OpFlags int flags) throws RemoteException {
            mStartTime = startTime;
            mStartElapsedTime = startElapsedTime;
            mClientId = clientId;
            mOnDeath = onDeath;
            mUidState = uidState;
            mProxy = proxy;
            mFlags = flags;

            clientId.linkToDeath(this, 0);
        }

        /** Clean up event */
        public void finish() {
            mClientId.unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            mOnDeath.run();
        }

        /**
         * Reinit existing object with new state.
         *
         * @param startTime The time {@link #startOperation} was called
         * @param startElapsedTime The elapsed time when {@link #startOperation} was called
         * @param clientId The client id of the caller of {@link #startOperation}
         * @param onDeath The code to execute on client death
         * @param uidState The uidstate of the app {@link #startOperation} was called for
         * @param flags The flags relating to the proxy
         * @param proxy The proxy information, if {@link #startProxyOperation} was called
         * @param proxyPool The pool to release previous {@link OpEventProxyInfo} to
         *
         * @throws RemoteException If the client is dying
         */
        public void reinit(long startTime, long startElapsedTime, @NonNull IBinder clientId,
                @NonNull Runnable onDeath, @AppOpsManager.UidState int uidState, @OpFlags int flags,
                @Nullable OpEventProxyInfo proxy, @NonNull Pools.Pool<OpEventProxyInfo> proxyPool
        ) throws RemoteException {
            mStartTime = startTime;
            mStartElapsedTime = startElapsedTime;
            mClientId = clientId;
            mOnDeath = onDeath;
            mUidState = uidState;
            mFlags = flags;

            if (mProxy != null) {
                proxyPool.release(mProxy);
            }
            mProxy = proxy;

            clientId.linkToDeath(this, 0);
        }

        /** @return Wall clock time of startOp event */
        public long getStartTime() {
            return mStartTime;
        }

        /** @return Elapsed time since boot of startOp event */
        public long getStartElapsedTime() {
            return mStartElapsedTime;
        }

        /** @return Id of the client that started the event */
        public @NonNull IBinder getClientId() {
            return mClientId;
        }

        /** @return uidstate used when calling startOp */
        public @AppOpsManager.UidState int getUidState() {
            return mUidState;
        }

        /** @return proxy info for the access */
        public @Nullable OpEventProxyInfo getProxy() {
            return mProxy;
        }

        /** @return flags used for the access */
        public @OpFlags int getFlags() {
            return mFlags;
        }
    }

    private final class AttributedOp {
        public final @Nullable String tag;
        public final @NonNull Op parent;

        /**
         * Last successful accesses (noteOp + finished startOp) for each uidState/opFlag combination
         *
         * <p>Key is {@link AppOpsManager#makeKey}
         */
        @GuardedBy("AppOpsService.this")
        private @Nullable LongSparseArray<NoteOpEvent> mAccessEvents;

        /**
         * Last rejected accesses for each uidState/opFlag combination
         *
         * <p>Key is {@link AppOpsManager#makeKey}
         */
        @GuardedBy("AppOpsService.this")
        private @Nullable LongSparseArray<NoteOpEvent> mRejectEvents;

        /**
         * Currently in progress startOp events
         *
         * <p>Key is clientId
         */
        @GuardedBy("AppOpsService.this")
        private @Nullable ArrayMap<IBinder, InProgressStartOpEvent> mInProgressEvents;

        AttributedOp(@Nullable String tag, @NonNull Op parent) {
            this.tag = tag;
            this.parent = parent;
        }

        /**
         * Update state when noteOp was rejected or startOp->finishOp event finished
         *
         * @param proxyUid The uid of the proxy
         * @param proxyPackageName The package name of the proxy
         * @param proxyAttributionTag the attributionTag in the proxies package
         * @param uidState UID state of the app noteOp/startOp was called for
         * @param flags OpFlags of the call
         */
        public void accessed(int proxyUid, @Nullable String proxyPackageName,
                @Nullable String proxyAttributionTag, @AppOpsManager.UidState int uidState,
                @OpFlags int flags) {
            long accessTime = System.currentTimeMillis();
            accessed(accessTime, -1, proxyUid, proxyPackageName,
                    proxyAttributionTag, uidState, flags);

            mHistoricalRegistry.incrementOpAccessedCount(parent.op, parent.uid, parent.packageName,
                    tag, uidState, flags);

            mHistoricalRegistry.mDiscreteRegistry.recordDiscreteAccess(parent.uid,
                    parent.packageName, parent.op, tag, flags, uidState, accessTime, -1);
        }

        /**
         * Add an access that was previously collected.
         *
         * @param noteTime The time of the event
         * @param duration The duration of the event
         * @param proxyUid The uid of the proxy
         * @param proxyPackageName The package name of the proxy
         * @param proxyAttributionTag the attributionTag in the proxies package
         * @param uidState UID state of the app noteOp/startOp was called for
         * @param flags OpFlags of the call
         */
        public void accessed(long noteTime, long duration, int proxyUid,
                @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
                @AppOpsManager.UidState int uidState, @OpFlags int flags) {
            long key = makeKey(uidState, flags);

            if (mAccessEvents == null) {
                mAccessEvents = new LongSparseArray<>(1);
            }

            OpEventProxyInfo proxyInfo = null;
            if (proxyUid != Process.INVALID_UID) {
                proxyInfo = mOpEventProxyInfoPool.acquire(proxyUid, proxyPackageName,
                        proxyAttributionTag);
            }

            NoteOpEvent existingEvent = mAccessEvents.get(key);
            if (existingEvent != null) {
                existingEvent.reinit(noteTime, duration, proxyInfo, mOpEventProxyInfoPool);
            } else {
                mAccessEvents.put(key, new NoteOpEvent(noteTime, duration, proxyInfo));
            }
        }

        /**
         * Update state when noteOp/startOp was rejected.
         *
         * @param uidState UID state of the app noteOp is called for
         * @param flags OpFlags of the call
         */
        public void rejected(@AppOpsManager.UidState int uidState, @OpFlags int flags) {
            rejected(System.currentTimeMillis(), uidState, flags);

            mHistoricalRegistry.incrementOpRejected(parent.op, parent.uid, parent.packageName,
                    tag, uidState, flags);
        }

        /**
         * Add an rejection that was previously collected
         *
         * @param noteTime The time of the event
         * @param uidState UID state of the app noteOp/startOp was called for
         * @param flags OpFlags of the call
         */
        public void rejected(long noteTime, @AppOpsManager.UidState int uidState,
                @OpFlags int flags) {
            long key = makeKey(uidState, flags);

            if (mRejectEvents == null) {
                mRejectEvents = new LongSparseArray<>(1);
            }

            // We do not collect proxy information for rejections yet
            NoteOpEvent existingEvent = mRejectEvents.get(key);
            if (existingEvent != null) {
                existingEvent.reinit(noteTime, -1, null, mOpEventProxyInfoPool);
            } else {
                mRejectEvents.put(key, new NoteOpEvent(noteTime, -1, null));
            }
        }

        /**
         * Update state when start was called
         *
         * @param clientId Id of the startOp caller
         * @param proxyUid The UID of the proxy app
         * @param proxyPackageName The package name of the proxy app
         * @param proxyAttributionTag The attribution tag of the proxy app
         * @param uidState UID state of the app startOp is called for
         * @param flags The proxy flags
         */
        public void started(@NonNull IBinder clientId, int proxyUid,
                @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
                @AppOpsManager.UidState int uidState, @OpFlags int flags) throws RemoteException {
            started(clientId, proxyUid, proxyPackageName, proxyAttributionTag, uidState, flags,
                    true);
        }

        private void started(@NonNull IBinder clientId, int proxyUid,
                @Nullable String proxyPackageName, @Nullable String proxyAttributionTag,
                @AppOpsManager.UidState int uidState, @OpFlags int flags,
                boolean triggerCallbackIfNeeded) throws RemoteException {
            if (triggerCallbackIfNeeded && !parent.isRunning()) {
                scheduleOpActiveChangedIfNeededLocked(parent.op, parent.uid,
                        parent.packageName, true);
            }

            if (mInProgressEvents == null) {
                mInProgressEvents = new ArrayMap<>(1);
            }

            InProgressStartOpEvent event = mInProgressEvents.get(clientId);
            if (event == null) {
                event = mInProgressStartOpEventPool.acquire(System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(), clientId,
                        PooledLambda.obtainRunnable(AppOpsService::onClientDeath, this, clientId),
                        proxyUid, proxyPackageName, proxyAttributionTag, uidState, flags);
                mInProgressEvents.put(clientId, event);
            } else {
                if (uidState != event.mUidState) {
                    onUidStateChanged(uidState);
                }
            }

            event.numUnfinishedStarts++;

            mHistoricalRegistry.incrementOpAccessedCount(parent.op, parent.uid, parent.packageName,
                    tag, uidState, flags);
        }

        /**
         * Update state when finishOp was called
         *
         * @param clientId Id of the finishOp caller
         */
        public void finished(@NonNull IBinder clientId) {
            finished(clientId, true);
        }

        private void finished(@NonNull IBinder clientId, boolean triggerCallbackIfNeeded) {
            if (mInProgressEvents == null) {
                Slog.wtf(TAG, "No ops running");
                return;
            }

            int indexOfToken = mInProgressEvents.indexOfKey(clientId);
            if (indexOfToken < 0) {
                Slog.wtf(TAG, "No op running for the client");
                return;
            }

            InProgressStartOpEvent event = mInProgressEvents.valueAt(indexOfToken);
            event.numUnfinishedStarts--;
            if (event.numUnfinishedStarts == 0) {
                event.finish();
                mInProgressEvents.removeAt(indexOfToken);

                if (mAccessEvents == null) {
                    mAccessEvents = new LongSparseArray<>(1);
                }

                OpEventProxyInfo proxyCopy = event.getProxy() != null
                        ? new OpEventProxyInfo(event.getProxy()) : null;

                long accessDurationMillis =
                        SystemClock.elapsedRealtime() - event.getStartElapsedTime();
                NoteOpEvent finishedEvent = new NoteOpEvent(event.getStartTime(),
                        accessDurationMillis, proxyCopy);
                mAccessEvents.put(makeKey(event.getUidState(), event.getFlags()),
                        finishedEvent);

                mHistoricalRegistry.increaseOpAccessDuration(parent.op, parent.uid,
                        parent.packageName, tag, event.getUidState(),
                        event.getFlags(), finishedEvent.getDuration());

                mHistoricalRegistry.mDiscreteRegistry.recordDiscreteAccess(parent.uid,
                        parent.packageName, parent.op, tag, event.getFlags(), event.getUidState(),
                        event.getStartTime(), accessDurationMillis);

                mInProgressStartOpEventPool.release(event);

                if (mInProgressEvents.isEmpty()) {
                    mInProgressEvents = null;

                    // TODO moltmann: Also callback for single attribution tag activity changes
                    if (triggerCallbackIfNeeded && !parent.isRunning()) {
                        scheduleOpActiveChangedIfNeededLocked(parent.op, parent.uid,
                                parent.packageName, false);
                    }
                }
            }
        }

        /**
         * Called in the case the client dies without calling finish first
         *
         * @param clientId The client that died
         */
        void onClientDeath(@NonNull IBinder clientId) {
            synchronized (AppOpsService.this) {
                if (mInProgressEvents == null) {
                    return;
                }

                InProgressStartOpEvent deadEvent = mInProgressEvents.get(clientId);
                if (deadEvent != null) {
                    deadEvent.numUnfinishedStarts = 1;
                }

                finished(clientId);
            }
        }

        /**
         * Notify that the state of the uid changed
         *
         * @param newState The new state
         */
        public void onUidStateChanged(@AppOpsManager.UidState int newState) {
            if (mInProgressEvents == null) {
                return;
            }

            int numInProgressEvents = mInProgressEvents.size();
            List<IBinder> binders = new ArrayList<>(mInProgressEvents.keySet());
            for (int i = 0; i < numInProgressEvents; i++) {
                InProgressStartOpEvent event = mInProgressEvents.get(binders.get(i));

                if (event != null && event.getUidState() != newState) {
                    try {
                        // Remove all but one unfinished start count and then call finished() to
                        // remove start event object
                        int numPreviousUnfinishedStarts = event.numUnfinishedStarts;
                        event.numUnfinishedStarts = 1;
                        OpEventProxyInfo proxy = event.getProxy();

                        finished(event.getClientId(), false);

                        // Call started() to add a new start event object and then add the
                        // previously removed unfinished start counts back
                        if (proxy != null) {
                            started(event.getClientId(), proxy.getUid(), proxy.getPackageName(),
                                    proxy.getAttributionTag(), newState, event.getFlags(), false);
                        } else {
                            started(event.getClientId(), Process.INVALID_UID, null, null, newState,
                                    OP_FLAG_SELF, false);
                        }

                        InProgressStartOpEvent newEvent = mInProgressEvents.get(binders.get(i));
                        if (newEvent != null) {
                            newEvent.numUnfinishedStarts += numPreviousUnfinishedStarts - 1;
                        }
                    } catch (RemoteException e) {
                        if (DEBUG) Slog.e(TAG, "Cannot switch to new uidState " + newState);
                    }
                }
            }
        }

        /**
         * Combine {@code a} and {@code b} and return the result. The result might be {@code a}
         * or {@code b}. If there is an event for the same key in both the later event is retained.
         */
        private @Nullable LongSparseArray<NoteOpEvent> add(@Nullable LongSparseArray<NoteOpEvent> a,
                @Nullable LongSparseArray<NoteOpEvent> b) {
            if (a == null) {
                return b;
            }

            if (b == null) {
                return a;
            }

            int numEventsToAdd = b.size();
            for (int i = 0; i < numEventsToAdd; i++) {
                long keyOfEventToAdd = b.keyAt(i);
                NoteOpEvent bEvent = b.valueAt(i);
                NoteOpEvent aEvent = a.get(keyOfEventToAdd);

                if (aEvent == null || bEvent.getNoteTime() > aEvent.getNoteTime()) {
                    a.put(keyOfEventToAdd, bEvent);
                }
            }

            return a;
        }

        /**
         * Add all data from the {@code opToAdd} to this op.
         *
         * <p>If there is an event for the same key in both the later event is retained.
         * <p>{@code opToAdd} should not be used after this method is called.
         *
         * @param opToAdd The op to add
         */
        public void add(@NonNull AttributedOp opToAdd) {
            if (opToAdd.mInProgressEvents != null) {
                Slog.w(TAG, "Ignoring " + opToAdd.mInProgressEvents.size() + " running app-ops");

                int numInProgressEvents = opToAdd.mInProgressEvents.size();
                for (int i = 0; i < numInProgressEvents; i++) {
                    InProgressStartOpEvent event = opToAdd.mInProgressEvents.valueAt(i);

                    event.finish();
                    mInProgressStartOpEventPool.release(event);
                }
            }

            mAccessEvents = add(mAccessEvents, opToAdd.mAccessEvents);
            mRejectEvents = add(mRejectEvents, opToAdd.mRejectEvents);
        }

        public boolean isRunning() {
            return mInProgressEvents != null;
        }

        boolean hasAnyTime() {
            return (mAccessEvents != null && mAccessEvents.size() > 0)
                    || (mRejectEvents != null && mRejectEvents.size() > 0);
        }

        /**
         * Clone a {@link LongSparseArray} and clone all values.
         */
        private @Nullable LongSparseArray<NoteOpEvent> deepClone(
                @Nullable LongSparseArray<NoteOpEvent> original) {
            if (original == null) {
                return original;
            }

            int size = original.size();
            LongSparseArray<NoteOpEvent> clone = new LongSparseArray<>(size);
            for (int i = 0; i < size; i++) {
                clone.put(original.keyAt(i), new NoteOpEvent(original.valueAt(i)));
            }

            return clone;
        }

        @NonNull AttributedOpEntry createAttributedOpEntryLocked() {
            LongSparseArray<NoteOpEvent> accessEvents = deepClone(mAccessEvents);

            // Add in progress events as access events
            if (mInProgressEvents != null) {
                long now = SystemClock.elapsedRealtime();
                int numInProgressEvents = mInProgressEvents.size();

                if (accessEvents == null) {
                    accessEvents = new LongSparseArray<>(numInProgressEvents);
                }

                for (int i = 0; i < numInProgressEvents; i++) {
                    InProgressStartOpEvent event = mInProgressEvents.valueAt(i);

                    accessEvents.append(makeKey(event.getUidState(), event.getFlags()),
                            new NoteOpEvent(event.getStartTime(), now - event.getStartElapsedTime(),
                                    event.getProxy()));
                }
            }

            LongSparseArray<NoteOpEvent> rejectEvents = deepClone(mRejectEvents);

            return new AttributedOpEntry(parent.op, isRunning(), accessEvents, rejectEvents);
        }
    }

    final class Op {
        int op;
        int uid;
        final UidState uidState;
        final @NonNull String packageName;

        private @Mode int mode;

        /** attributionTag -> AttributedOp */
        final ArrayMap<String, AttributedOp> mAttributions = new ArrayMap<>(1);

        Op(UidState uidState, String packageName, int op, int uid) {
            this.op = op;
            this.uid = uid;
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

        void removeAttributionsWithNoTime() {
            for (int i = mAttributions.size() - 1; i >= 0; i--) {
                if (!mAttributions.valueAt(i).hasAnyTime()) {
                    mAttributions.removeAt(i);
                }
            }
        }

        private @NonNull AttributedOp getOrCreateAttribution(@NonNull Op parent,
                @Nullable String attributionTag) {
            AttributedOp attributedOp;

            attributedOp = mAttributions.get(attributionTag);
            if (attributedOp == null) {
                attributedOp = new AttributedOp(attributionTag, parent);
                mAttributions.put(attributionTag, attributedOp);
            }

            return attributedOp;
        }

        @NonNull OpEntry createEntryLocked() {
            final int numAttributions = mAttributions.size();

            final ArrayMap<String, AppOpsManager.AttributedOpEntry> attributionEntries =
                    new ArrayMap<>(numAttributions);
            for (int i = 0; i < numAttributions; i++) {
                attributionEntries.put(mAttributions.keyAt(i),
                        mAttributions.valueAt(i).createAttributedOpEntryLocked());
            }

            return new OpEntry(op, mode, attributionEntries);
        }

        @NonNull OpEntry createSingleAttributionEntryLocked(@Nullable String attributionTag) {
            final int numAttributions = mAttributions.size();

            final ArrayMap<String, AttributedOpEntry> attributionEntries = new ArrayMap<>(1);
            for (int i = 0; i < numAttributions; i++) {
                if (Objects.equals(mAttributions.keyAt(i), attributionTag)) {
                    attributionEntries.put(mAttributions.keyAt(i),
                            mAttributions.valueAt(i).createAttributedOpEntryLocked());
                    break;
                }
            }

            return new OpEntry(op, mode, attributionEntries);
        }

        boolean isRunning() {
            final int numAttributions = mAttributions.size();
            for (int i = 0; i < numAttributions; i++) {
                if (mAttributions.valueAt(i).isRunning()) {
                    return true;
                }
            }

            return false;
        }
    }

    final SparseArray<ArraySet<ModeCallback>> mOpModeWatchers = new SparseArray<>();
    final ArrayMap<String, ArraySet<ModeCallback>> mPackageModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, ModeCallback> mModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<ActiveCallback>> mActiveWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<StartedCallback>> mStartedWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<NotedCallback>> mNotedWatchers = new ArrayMap<>();
    final AudioRestrictionManager mAudioRestrictionManager = new AudioRestrictionManager();

    final class ModeCallback implements DeathRecipient {
        /** If mWatchedOpCode==ALL_OPS notify for ops affected by the switch-op */
        public static final int ALL_OPS = -2;

        final IAppOpsCallback mCallback;
        final int mWatchingUid;
        final int mFlags;
        final int mWatchedOpCode;
        final int mCallingUid;
        final int mCallingPid;

        ModeCallback(IAppOpsCallback callback, int watchingUid, int flags, int watchedOp,
                int callingUid, int callingPid) {
            mCallback = callback;
            mWatchingUid = watchingUid;
            mFlags = flags;
            mWatchedOpCode = watchedOp;
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
            switch (mWatchedOpCode) {
                case OP_NONE:
                    break;
                case ALL_OPS:
                    sb.append(" op=(all)");
                    break;
                default:
                    sb.append(" op=");
                    sb.append(opToName(mWatchedOpCode));
                    break;
            }
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

    final class StartedCallback implements DeathRecipient {
        final IAppOpsStartedCallback mCallback;
        final int mWatchingUid;
        final int mCallingUid;
        final int mCallingPid;

        StartedCallback(IAppOpsStartedCallback callback, int watchingUid, int callingUid,
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
            sb.append("StartedCallback{");
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
            stopWatchingStarted(mCallback);
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

    /**
     * Call {@link AttributedOp#onClientDeath attributedOp.onClientDeath(clientId)}.
     */
    private static void onClientDeath(@NonNull AttributedOp attributedOp,
            @NonNull IBinder clientId) {
        attributedOp.onClientDeath(clientId);
    }


    /**
     * Loads the OpsValidation file results into a hashmap {@link #mNoteOpCallerStacktraces}
     * so that we do not log the same operation twice between instances
     */
    private void readNoteOpCallerStackTraces() {
        try {
            if (!mNoteOpCallerStacktracesFile.exists()) {
                mNoteOpCallerStacktracesFile.createNewFile();
                return;
            }

            try (Scanner read = new Scanner(mNoteOpCallerStacktracesFile)) {
                read.useDelimiter("\\},");
                while (read.hasNext()) {
                    String jsonOps = read.next();
                    mNoteOpCallerStacktraces.add(NoteOpTrace.fromJson(jsonOps));
                }
            }
        } catch (Exception e) {
            Slog.e(TAG, "Cannot parse traces noteOps", e);
        }
    }

    public AppOpsService(File storagePath, Handler handler, Context context) {
        mContext = context;

        LockGuard.installLock(this, LockGuard.INDEX_APP_OPS);
        mFile = new AtomicFile(storagePath, "appops");
        if (AppOpsManager.NOTE_OP_COLLECTION_ENABLED) {
            mNoteOpCallerStacktracesFile = new File(SystemServiceManager.ensureSystemDir(),
                    "noteOpStackTraces.json");
            readNoteOpCallerStackTraces();
        } else {
            mNoteOpCallerStacktracesFile = null;
        }
        mHandler = handler;
        mConstants = new Constants(mHandler);
        readState();

        for (int switchedCode = 0; switchedCode < _NUM_OP; switchedCode++) {
            int switchCode = AppOpsManager.opToSwitch(switchedCode);
            mSwitchedOps.put(switchCode,
                    ArrayUtils.appendInt(mSwitchedOps.get(switchCode), switchedCode));
        }
    }

    public void publish() {
        ServiceManager.addService(Context.APP_OPS_SERVICE, asBinder());
        LocalServices.addService(AppOpsManagerInternal.class, mAppOpsManagerInternal);
    }

    /** Handler for work when packages are removed or updated */
    private BroadcastReceiver mOnPackageUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String pkgName = intent.getData().getEncodedSchemeSpecificPart();
            int uid = intent.getIntExtra(Intent.EXTRA_UID, Process.INVALID_UID);

            if (action.equals(ACTION_PACKAGE_REMOVED) && !intent.hasExtra(EXTRA_REPLACING)) {
                synchronized (AppOpsService.this) {
                    UidState uidState = mUidStates.get(uid);
                    if (uidState == null || uidState.pkgOps == null) {
                        return;
                    }

                    Ops removedOps = uidState.pkgOps.remove(pkgName);
                    if (removedOps != null) {
                        scheduleFastWriteLocked();
                    }
                }
            } else if (action.equals(Intent.ACTION_PACKAGE_REPLACED)) {
                AndroidPackage pkg = getPackageManagerInternal().getPackage(pkgName);
                if (pkg == null) {
                    return;
                }

                ArrayMap<String, String> dstAttributionTags = new ArrayMap<>();
                ArraySet<String> attributionTags = new ArraySet<>();
                attributionTags.add(null);
                if (pkg.getAttributions() != null) {
                    int numAttributions = pkg.getAttributions().size();
                    for (int attributionNum = 0; attributionNum < numAttributions;
                            attributionNum++) {
                        ParsedAttribution attribution = pkg.getAttributions().get(attributionNum);
                        attributionTags.add(attribution.tag);

                        int numInheritFrom = attribution.inheritFrom.size();
                        for (int inheritFromNum = 0; inheritFromNum < numInheritFrom;
                                inheritFromNum++) {
                            dstAttributionTags.put(attribution.inheritFrom.get(inheritFromNum),
                                    attribution.tag);
                        }
                    }
                }

                synchronized (AppOpsService.this) {
                    UidState uidState = mUidStates.get(uid);
                    if (uidState == null || uidState.pkgOps == null) {
                        return;
                    }

                    Ops ops = uidState.pkgOps.get(pkgName);
                    if (ops == null) {
                        return;
                    }

                    // Reset cached package properties to re-initialize when needed
                    ops.bypass = null;
                    ops.knownAttributionTags.clear();

                    // Merge data collected for removed attributions into their successor
                    // attributions
                    int numOps = ops.size();
                    for (int opNum = 0; opNum < numOps; opNum++) {
                        Op op = ops.valueAt(opNum);

                        int numAttributions = op.mAttributions.size();
                        for (int attributionNum = numAttributions - 1; attributionNum >= 0;
                                attributionNum--) {
                            String attributionTag = op.mAttributions.keyAt(attributionNum);

                            if (attributionTags.contains(attributionTag)) {
                                // attribution still exist after upgrade
                                continue;
                            }

                            String newAttributionTag = dstAttributionTags.get(attributionTag);

                            AttributedOp newAttributedOp = op.getOrCreateAttribution(op,
                                    newAttributionTag);
                            newAttributedOp.add(op.mAttributions.valueAt(attributionNum));
                            op.mAttributions.removeAt(attributionNum);

                            scheduleFastWriteLocked();
                        }
                    }
                }
            }
        }
    };

    public void systemReady() {
        mConstants.startMonitoring(mContext.getContentResolver());
        mHistoricalRegistry.systemReady(mContext.getContentResolver());

        IntentFilter packageUpdateFilter = new IntentFilter();
        packageUpdateFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packageUpdateFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packageUpdateFilter.addDataScheme("package");

        mContext.registerReceiverAsUser(mOnPackageUpdatedReceiver, UserHandle.ALL,
                packageUpdateFilter, null, null);

        synchronized (this) {
            for (int uidNum = mUidStates.size() - 1; uidNum >= 0; uidNum--) {
                int uid = mUidStates.keyAt(uidNum);
                UidState uidState = mUidStates.valueAt(uidNum);

                String[] pkgsInUid = getPackagesForUid(uidState.uid);
                if (ArrayUtils.isEmpty(pkgsInUid)) {
                    uidState.clear();
                    mUidStates.removeAt(uidNum);
                    scheduleFastWriteLocked();
                    continue;
                }

                ArrayMap<String, Ops> pkgs = uidState.pkgOps;
                if (pkgs == null) {
                    continue;
                }

                int numPkgs = pkgs.size();
                for (int pkgNum = 0; pkgNum < numPkgs; pkgNum++) {
                    String pkg = pkgs.keyAt(pkgNum);

                    String action;
                    if (!ArrayUtils.contains(pkgsInUid, pkg)) {
                        action = Intent.ACTION_PACKAGE_REMOVED;
                    } else {
                        action = Intent.ACTION_PACKAGE_REPLACED;
                    }

                    SystemServerInitThreadPool.submit(
                            () -> mOnPackageUpdatedReceiver.onReceive(mContext, new Intent(action)
                                    .setData(Uri.fromParts("package", pkg, null))
                                    .putExtra(Intent.EXTRA_UID, uid)),
                            "Update app-ops uidState in case package " + pkg + " changed");
                }
            }
        }

        final IntentFilter packageSuspendFilter = new IntentFilter();
        packageSuspendFilter.addAction(Intent.ACTION_PACKAGES_UNSUSPENDED);
        packageSuspendFilter.addAction(Intent.ACTION_PACKAGES_SUSPENDED);
        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final int[] changedUids = intent.getIntArrayExtra(Intent.EXTRA_CHANGED_UID_LIST);
                final String[] changedPkgs = intent.getStringArrayExtra(
                        Intent.EXTRA_CHANGED_PACKAGE_LIST);
                for (int code : OPS_RESTRICTED_ON_SUSPEND) {
                    ArraySet<ModeCallback> callbacks;
                    synchronized (AppOpsService.this) {
                        callbacks = mOpModeWatchers.get(code);
                        if (callbacks == null) {
                            continue;
                        }
                        callbacks = new ArraySet<>(callbacks);
                    }
                    for (int i = 0; i < changedUids.length; i++) {
                        final int changedUid = changedUids[i];
                        final String changedPkg = changedPkgs[i];
                        // We trust packagemanager to insert matching uid and packageNames in the
                        // extras
                        notifyOpChanged(callbacks, code, changedUid, changedPkg);
                    }
                }
            }
        }, UserHandle.ALL, packageSuspendFilter, null, null);

        final IntentFilter packageAddedFilter = new IntentFilter();
        packageAddedFilter.addAction(Intent.ACTION_PACKAGE_ADDED);
        packageAddedFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final Uri data = intent.getData();

                final String packageName = data.getSchemeSpecificPart();
                PackageInfo pi = getPackageManagerInternal().getPackageInfo(packageName,
                        PackageManager.GET_PERMISSIONS, Process.myUid(), mContext.getUserId());
                if (isSamplingTarget(pi)) {
                    synchronized (this) {
                        mRarelyUsedPackages.add(packageName);
                    }
                }
            }
        }, packageAddedFilter);

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                List<String> packageNames = getPackageListAndResample();
                initializeRarelyUsedPackagesList(new ArraySet<>(packageNames));
            }
        }, RARELY_USED_PACKAGES_INITIALIZATION_DELAY_MILLIS);

        getPackageManagerInternal().setExternalSourcesPolicy(
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

        mActivityManagerInternal = LocalServices.getService(ActivityManagerInternal.class);
    }

    /**
     * Sets a policy for handling app ops.
     *
     * @param appOpsPolicy The policy.
     */
    public void setAppOpsPolicy(@Nullable CheckOpsDelegate appOpsPolicy) {
        synchronized (AppOpsService.this) {
            mAppOpsPolicy = appOpsPolicy;
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

            if (ops != null) {
                scheduleFastWriteLocked();

                final int numOps = ops.size();
                for (int opNum = 0; opNum < numOps; opNum++) {
                    final Op op = ops.valueAt(opNum);

                    final int numAttributions = op.mAttributions.size();
                    for (int attributionNum = 0; attributionNum < numAttributions;
                            attributionNum++) {
                        AttributedOp attributedOp = op.mAttributions.valueAt(attributionNum);

                        while (attributedOp.mInProgressEvents != null) {
                            attributedOp.finished(attributedOp.mInProgressEvents.keyAt(0));
                        }
                    }
                }
            }
        }

        mHistoricalRegistry.clearHistory(uid, packageName);
    }

    public void uidRemoved(int uid) {
        synchronized (this) {
            if (mUidStates.indexOfKey(uid) >= 0) {
                mUidStates.remove(uid);
                scheduleFastWriteLocked();
            }
        }
    }

    /**
     * Update the pending state for the uid
     *
     * @param currentTime The current elapsed real time
     * @param uid The uid that has a pending state
     */
    private void updatePendingState(long currentTime, int uid) {
        synchronized (this) {
            mLastRealtime = max(currentTime, mLastRealtime);
            updatePendingStateIfNeededLocked(mUidStates.get(uid));
        }
    }

    public void updateUidProcState(int uid, int procState,
            @ActivityManager.ProcessCapability int capability) {
        synchronized (this) {
            final UidState uidState = getUidStateLocked(uid, true);
            final int newState = PROCESS_STATE_TO_UID_STATE[procState];
            if (uidState != null && (uidState.pendingState != newState
                    || uidState.pendingCapability != capability)) {
                final int oldPendingState = uidState.pendingState;
                uidState.pendingState = newState;
                uidState.pendingCapability = capability;
                if (newState < uidState.state
                        || (newState <= UID_STATE_MAX_LAST_NON_RESTRICTED
                                && uidState.state > UID_STATE_MAX_LAST_NON_RESTRICTED)) {
                    // We are moving to a more important state, or the new state may be in the
                    // foreground and the old state is in the background, then always do it
                    // immediately.
                    commitUidPendingStateLocked(uidState);
                } else if (newState == uidState.state && capability != uidState.capability) {
                    // No change on process state, but process capability has changed.
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
                    final long commitTime = SystemClock.elapsedRealtime() + settleTime;
                    uidState.pendingStateCommitTime = commitTime;

                    mHandler.sendMessageDelayed(
                            PooledLambda.obtainMessage(AppOpsService::updatePendingState, this,
                                    commitTime + 1, uid), settleTime + 1);
                }

                if (uidState.pkgOps != null) {
                    int numPkgs = uidState.pkgOps.size();
                    for (int pkgNum = 0; pkgNum < numPkgs; pkgNum++) {
                        Ops ops = uidState.pkgOps.valueAt(pkgNum);

                        int numOps = ops.size();
                        for (int opNum = 0; opNum < numOps; opNum++) {
                            Op op = ops.valueAt(opNum);

                            int numAttributions = op.mAttributions.size();
                            for (int attributionNum = 0; attributionNum < numAttributions;
                                    attributionNum++) {
                                AttributedOp attributedOp = op.mAttributions.valueAt(
                                        attributionNum);

                                attributedOp.onUidStateChanged(newState);
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
                mHandler.removeCallbacks(mWriteRunner);
                doWrite = true;
            }
        }
        if (doWrite) {
            writeState();
        }
        if (AppOpsManager.NOTE_OP_COLLECTION_ENABLED && mWriteNoteOpsScheduled) {
            writeNoteOps();
        }

        mHistoricalRegistry.shutdown();
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

    @Nullable
    private ArrayList<AppOpsManager.OpEntry> collectUidOps(@NonNull UidState uidState,
            @Nullable int[] ops) {
        if (uidState.opModes == null) {
            return null;
        }

        int opModeCount = uidState.opModes.size();
        if (opModeCount == 0) {
            return null;
        }
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        if (ops == null) {
            resOps = new ArrayList<>();
            for (int i = 0; i < opModeCount; i++) {
                int code = uidState.opModes.keyAt(i);
                resOps.add(new OpEntry(code, uidState.opModes.get(code), Collections.emptyMap()));
            }
        } else {
            for (int j=0; j<ops.length; j++) {
                int code = ops[j];
                if (uidState.opModes.indexOfKey(code) >= 0) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    resOps.add(new OpEntry(code, uidState.opModes.get(code),
                            Collections.emptyMap()));
                }
            }
        }
        return resOps;
    }

    private static @NonNull OpEntry getOpEntryForResult(@NonNull Op op, long elapsedNow) {
        return op.createEntryLocked();
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
            Ops pkgOps = getOpsLocked(uid, resolvedPackageName, null, null, false /* edit */);
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

    /**
     * Verify that historical appop request arguments are valid.
     */
    private void ensureHistoricalOpRequestIsValid(int uid, String packageName,
            String attributionTag, List<String> opNames, int filter, long beginTimeMillis,
            long endTimeMillis, int flags) {
        if ((filter & FILTER_BY_UID) != 0) {
            Preconditions.checkArgument(uid != Process.INVALID_UID);
        } else {
            Preconditions.checkArgument(uid == Process.INVALID_UID);
        }

        if ((filter & FILTER_BY_PACKAGE_NAME) != 0) {
            Objects.requireNonNull(packageName);
        } else {
            Preconditions.checkArgument(packageName == null);
        }

        if ((filter & FILTER_BY_ATTRIBUTION_TAG) == 0) {
            Preconditions.checkArgument(attributionTag == null);
        }

        if ((filter & FILTER_BY_OP_NAMES) != 0) {
            Objects.requireNonNull(opNames);
        } else {
            Preconditions.checkArgument(opNames == null);
        }

        Preconditions.checkFlagsArgument(filter,
                FILTER_BY_UID | FILTER_BY_PACKAGE_NAME | FILTER_BY_ATTRIBUTION_TAG
                        | FILTER_BY_OP_NAMES);
        Preconditions.checkArgumentNonnegative(beginTimeMillis);
        Preconditions.checkArgument(endTimeMillis > beginTimeMillis);
        Preconditions.checkFlagsArgument(flags, OP_FLAGS_ALL);
    }

    @Override
    public void getHistoricalOps(int uid, String packageName, String attributionTag,
            List<String> opNames, int dataType, int filter, long beginTimeMillis,
            long endTimeMillis, int flags, RemoteCallback callback) {
        PackageManager pm = mContext.getPackageManager();

        ensureHistoricalOpRequestIsValid(uid, packageName, attributionTag, opNames, filter,
                beginTimeMillis, endTimeMillis, flags);
        Objects.requireNonNull(callback, "callback cannot be null");

        ActivityManagerInternal ami = LocalServices.getService(ActivityManagerInternal.class);
        boolean isCallerInstrumented = ami.isUidCurrentlyInstrumented(Binder.getCallingUid());
        boolean isCallerSystem = Binder.getCallingPid() == Process.myPid();
        boolean isCallerPermissionController;
        try {
            isCallerPermissionController = pm.getPackageUid(
                    mContext.getPackageManager().getPermissionControllerPackageName(), 0)
                    == Binder.getCallingUid();
        } catch (PackageManager.NameNotFoundException doesNotHappen) {
            return;
        }

        if (!isCallerSystem && !isCallerInstrumented && !isCallerPermissionController) {
            mHandler.post(() -> callback.sendResult(new Bundle()));
            return;
        }

        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), "getHistoricalOps");

        final String[] opNamesArray = (opNames != null)
                ? opNames.toArray(new String[opNames.size()]) : null;

        // Must not hold the appops lock
        mHandler.post(PooledLambda.obtainRunnable(HistoricalRegistry::getHistoricalOps,
                mHistoricalRegistry, uid, packageName, attributionTag, opNamesArray, dataType,
                filter, beginTimeMillis, endTimeMillis, flags, callback).recycleOnUse());
    }

    @Override
    public void getHistoricalOpsFromDiskRaw(int uid, String packageName, String attributionTag,
            List<String> opNames, int dataType, int filter, long beginTimeMillis,
            long endTimeMillis, int flags, RemoteCallback callback) {
        ensureHistoricalOpRequestIsValid(uid, packageName, attributionTag, opNames, filter,
                beginTimeMillis, endTimeMillis, flags);
        Objects.requireNonNull(callback, "callback cannot be null");

        mContext.enforcePermission(Manifest.permission.MANAGE_APPOPS,
                Binder.getCallingPid(), Binder.getCallingUid(), "getHistoricalOps");

        final String[] opNamesArray = (opNames != null)
                ? opNames.toArray(new String[opNames.size()]) : null;

        // Must not hold the appops lock
        mHandler.post(PooledLambda.obtainRunnable(HistoricalRegistry::getHistoricalOpsFromDiskRaw,
                mHistoricalRegistry, uid, packageName, attributionTag, opNamesArray, dataType,
                filter, beginTimeMillis, endTimeMillis, flags, callback).recycleOnUse());
    }

    @Override
    public void reloadNonHistoricalState() {
        mContext.enforcePermission(Manifest.permission.MANAGE_APPOPS,
                Binder.getCallingPid(), Binder.getCallingUid(), "reloadNonHistoricalState");
        writeState();
        readState();
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
            ArrayList<AppOpsManager.OpEntry> resOps = collectUidOps(uidState, ops);
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

    private void pruneOpLocked(Op op, int uid, String packageName) {
        op.removeAttributionsWithNoTime();

        if (op.mAttributions.isEmpty()) {
            Ops ops = getOpsLocked(uid, packageName, null, null, false /* edit */);
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
        setUidMode(code, uid, mode, null);
    }

    private void setUidMode(int code, int uid, int mode,
            @Nullable IAppOpsCallback permissionPolicyCallback) {
        if (DEBUG) {
            Slog.i(TAG, "uid " + uid + " OP_" + opToName(code) + " := " + modeToName(mode)
                    + " by uid " + Binder.getCallingUid());
        }

        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), uid);
        verifyIncomingOp(code);
        code = AppOpsManager.opToSwitch(code);

        if (permissionPolicyCallback == null) {
            updatePermissionRevokedCompat(uid, code, mode);
        }

        int previousMode;
        synchronized (this) {
            final int defaultMode = AppOpsManager.opToDefaultMode(code);

            UidState uidState = getUidStateLocked(uid, false);
            if (uidState == null) {
                if (mode == defaultMode) {
                    return;
                }
                previousMode = MODE_DEFAULT;
                uidState = new UidState(uid);
                uidState.opModes = new SparseIntArray();
                uidState.opModes.put(code, mode);
                mUidStates.put(uid, uidState);
                scheduleWriteLocked();
            } else if (uidState.opModes == null) {
                previousMode = MODE_DEFAULT;
                if (mode != defaultMode) {
                    uidState.opModes = new SparseIntArray();
                    uidState.opModes.put(code, mode);
                    scheduleWriteLocked();
                }
            } else {
                if (uidState.opModes.indexOfKey(code) >= 0 && uidState.opModes.get(code) == mode) {
                    return;
                }
                previousMode = uidState.opModes.get(code);
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
            uidState.evalForegroundOps(mOpModeWatchers);
        }

        notifyOpChangedForAllPkgsInUid(code, uid, false, permissionPolicyCallback);
        notifyOpChangedSync(code, uid, null, mode, previousMode);
    }

    /**
     * Notify that an op changed for all packages in an uid.
     *
     * @param code The op that changed
     * @param uid The uid the op was changed for
     * @param onlyForeground Only notify watchers that watch for foreground changes
     */
    private void notifyOpChangedForAllPkgsInUid(int code, int uid, boolean onlyForeground,
            @Nullable IAppOpsCallback callbackToIgnore) {
        String[] uidPackageNames = getPackagesForUid(uid);
        ArrayMap<ModeCallback, ArraySet<String>> callbackSpecs = null;

        synchronized (this) {
            ArraySet<ModeCallback> callbacks = mOpModeWatchers.get(code);
            if (callbacks != null) {
                final int callbackCount = callbacks.size();
                for (int i = 0; i < callbackCount; i++) {
                    ModeCallback callback = callbacks.valueAt(i);
                    if (onlyForeground && (callback.mFlags & WATCH_FOREGROUND_CHANGES) == 0) {
                        continue;
                    }

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
                        if (onlyForeground && (callback.mFlags & WATCH_FOREGROUND_CHANGES) == 0) {
                            continue;
                        }

                        ArraySet<String> changedPackages = callbackSpecs.get(callback);
                        if (changedPackages == null) {
                            changedPackages = new ArraySet<>();
                            callbackSpecs.put(callback, changedPackages);
                        }
                        changedPackages.add(uidPackageName);
                    }
                }
            }

            if (callbackSpecs != null && callbackToIgnore != null) {
                callbackSpecs.remove(mModeWatchers.get(callbackToIgnore.asBinder()));
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

    private void updatePermissionRevokedCompat(int uid, int switchCode, int mode) {
        PackageManager packageManager = mContext.getPackageManager();
        if (packageManager == null) {
            // This can only happen during early boot. At this time the permission state and appop
            // state are in sync
            return;
        }

        String[] packageNames = packageManager.getPackagesForUid(uid);
        if (ArrayUtils.isEmpty(packageNames)) {
            return;
        }
        String packageName = packageNames[0];

        int[] ops = mSwitchedOps.get(switchCode);
        for (int code : ops) {
            String permissionName = AppOpsManager.opToPermission(code);
            if (permissionName == null) {
                continue;
            }

            if (packageManager.checkPermission(permissionName, packageName)
                    != PackageManager.PERMISSION_GRANTED) {
                continue;
            }

            PermissionInfo permissionInfo;
            try {
                permissionInfo = packageManager.getPermissionInfo(permissionName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
                continue;
            }

            if (!permissionInfo.isRuntime()) {
                continue;
            }

            boolean supportsRuntimePermissions = getPackageManagerInternal()
                    .getUidTargetSdkVersion(uid) >= Build.VERSION_CODES.M;

            UserHandle user = UserHandle.getUserHandleForUid(uid);
            boolean isRevokedCompat;
            if (permissionInfo.backgroundPermission != null) {
                if (packageManager.checkPermission(permissionInfo.backgroundPermission, packageName)
                        == PackageManager.PERMISSION_GRANTED) {
                    boolean isBackgroundRevokedCompat = mode != AppOpsManager.MODE_ALLOWED;

                    if (isBackgroundRevokedCompat && supportsRuntimePermissions) {
                        Slog.w(TAG, "setUidMode() called with a mode inconsistent with runtime"
                                + " permission state, this is discouraged and you should revoke the"
                                + " runtime permission instead: uid=" + uid + ", switchCode="
                                + switchCode + ", mode=" + mode + ", permission="
                                + permissionInfo.backgroundPermission);
                    }

                    final long identity = Binder.clearCallingIdentity();
                    try {
                        packageManager.updatePermissionFlags(permissionInfo.backgroundPermission,
                                packageName, PackageManager.FLAG_PERMISSION_REVOKED_COMPAT,
                                isBackgroundRevokedCompat
                                        ? PackageManager.FLAG_PERMISSION_REVOKED_COMPAT : 0, user);
                    } finally {
                        Binder.restoreCallingIdentity(identity);
                    }
                }

                isRevokedCompat = mode != AppOpsManager.MODE_ALLOWED
                        && mode != AppOpsManager.MODE_FOREGROUND;
            } else {
                isRevokedCompat = mode != AppOpsManager.MODE_ALLOWED;
            }

            if (isRevokedCompat && supportsRuntimePermissions) {
                Slog.w(TAG, "setUidMode() called with a mode inconsistent with runtime"
                        + " permission state, this is discouraged and you should revoke the"
                        + " runtime permission instead: uid=" + uid + ", switchCode="
                        + switchCode + ", mode=" + mode + ", permission=" + permissionName);
            }

            final long identity = Binder.clearCallingIdentity();
            try {
                packageManager.updatePermissionFlags(permissionName, packageName,
                        PackageManager.FLAG_PERMISSION_REVOKED_COMPAT, isRevokedCompat
                                ? PackageManager.FLAG_PERMISSION_REVOKED_COMPAT : 0, user);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private void notifyOpChangedSync(int code, int uid, @NonNull String packageName, int mode,
            int previousMode) {
        final StorageManagerInternal storageManagerInternal =
                LocalServices.getService(StorageManagerInternal.class);
        if (storageManagerInternal != null) {
            storageManagerInternal.onAppOpsChanged(code, uid, packageName, mode, previousMode);
        }
    }

    /**
     * Sets the mode for a certain op and uid.
     *
     * @param code The op code to set
     * @param uid The UID for which to set
     * @param packageName The package for which to set
     * @param mode The new mode to set
     */
    @Override
    public void setMode(int code, int uid, @NonNull String packageName, int mode) {
        setMode(code, uid, packageName, mode, null);
    }

    private void setMode(int code, int uid, @NonNull String packageName, int mode,
            @Nullable IAppOpsCallback permissionPolicyCallback) {
        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), uid);
        verifyIncomingOp(code);
        verifyIncomingPackage(packageName, UserHandle.getUserId(uid));

        ArraySet<ModeCallback> repCbs = null;
        code = AppOpsManager.opToSwitch(code);

        RestrictionBypass bypass;
        try {
            bypass = verifyAndGetBypass(uid, packageName, null);
        } catch (SecurityException e) {
            Slog.e(TAG, "Cannot setMode", e);
            return;
        }

        int previousMode = MODE_DEFAULT;
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            Op op = getOpLocked(code, uid, packageName, null, bypass, true);
            if (op != null) {
                if (op.mode != mode) {
                    previousMode = op.mode;
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
                    if (repCbs != null && permissionPolicyCallback != null) {
                        repCbs.remove(mModeWatchers.get(permissionPolicyCallback.asBinder()));
                    }
                    if (mode == AppOpsManager.opToDefaultMode(op.op)) {
                        // If going into the default mode, prune this op
                        // if there is nothing else interesting in it.
                        pruneOpLocked(op, uid, packageName);
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

        notifyOpChangedSync(code, uid, packageName, mode, previousMode);
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

        // See CALL_BACK_ON_CHANGED_LISTENER_WITH_SWITCHED_OP_CHANGE
        int[] switchedCodes;
        if (callback.mWatchedOpCode == ALL_OPS) {
            switchedCodes = mSwitchedOps.get(code);
        } else if (callback.mWatchedOpCode == OP_NONE) {
            switchedCodes = new int[]{code};
        } else {
            switchedCodes = new int[]{callback.mWatchedOpCode};
        }

        for (int switchedCode : switchedCodes) {
            // There are features watching for mode changes such as window manager
            // and location manager which are in our process. The callbacks in these
            // features may require permissions our remote caller does not have.
            final long identity = Binder.clearCallingIdentity();
            try {
                callback.mCallback.opChanged(switchedCode, uid, packageName);
            } catch (RemoteException e) {
                /* ignore */
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    private static ArrayList<ChangeRec> addChange(ArrayList<ChangeRec> reports,
            int op, int uid, String packageName, int previousMode) {
        boolean duplicate = false;
        if (reports == null) {
            reports = new ArrayList<>();
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
            reports.add(new ChangeRec(op, uid, packageName, previousMode));
        }

        return reports;
    }

    private static HashMap<ModeCallback, ArrayList<ChangeRec>> addCallbacks(
            HashMap<ModeCallback, ArrayList<ChangeRec>> callbacks,
            int op, int uid, String packageName, int previousMode, ArraySet<ModeCallback> cbs) {
        if (cbs == null) {
            return callbacks;
        }
        if (callbacks == null) {
            callbacks = new HashMap<>();
        }
        final int N = cbs.size();
        for (int i=0; i<N; i++) {
            ModeCallback cb = cbs.valueAt(i);
            ArrayList<ChangeRec> reports = callbacks.get(cb);
            ArrayList<ChangeRec> changed = addChange(reports, op, uid, packageName, previousMode);
            if (changed != reports) {
                callbacks.put(cb, changed);
            }
        }
        return callbacks;
    }

    static final class ChangeRec {
        final int op;
        final int uid;
        final String pkg;
        final int previous_mode;

        ChangeRec(int _op, int _uid, String _pkg, int _previous_mode) {
            op = _op;
            uid = _uid;
            pkg = _pkg;
            previous_mode = _previous_mode;
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
        ArrayList<ChangeRec> allChanges = new ArrayList<>();
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
                            int previousMode = opModes.valueAt(j);
                            opModes.removeAt(j);
                            if (opModes.size() <= 0) {
                                uidState.opModes = null;
                            }
                            for (String packageName : getPackagesForUid(uidState.uid)) {
                                callbacks = addCallbacks(callbacks, code, uidState.uid, packageName,
                                        previousMode, mOpModeWatchers.get(code));
                                callbacks = addCallbacks(callbacks, code, uidState.uid, packageName,
                                        previousMode, mPackageModeWatchers.get(packageName));

                                allChanges = addChange(allChanges, code, uidState.uid,
                                        packageName, previousMode);
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
                        if (shouldDeferResetOpToDpm(curOp.op)) {
                            deferResetOpToDpm(curOp.op, reqPackageName, reqUserId);
                            continue;
                        }
                        if (AppOpsManager.opAllowsReset(curOp.op)
                                && curOp.mode != AppOpsManager.opToDefaultMode(curOp.op)) {
                            int previousMode = curOp.mode;
                            curOp.mode = AppOpsManager.opToDefaultMode(curOp.op);
                            changed = true;
                            uidChanged = true;
                            final int uid = curOp.uidState.uid;
                            callbacks = addCallbacks(callbacks, curOp.op, uid, packageName,
                                    previousMode, mOpModeWatchers.get(curOp.op));
                            callbacks = addCallbacks(callbacks, curOp.op, uid, packageName,
                                    previousMode, mPackageModeWatchers.get(packageName));

                            allChanges = addChange(allChanges, curOp.op, uid, packageName,
                                    previousMode);
                            curOp.removeAttributionsWithNoTime();
                            if (curOp.mAttributions.isEmpty()) {
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

        int numChanges = allChanges.size();
        for (int i = 0; i < numChanges; i++) {
            ChangeRec change = allChanges.get(i);
            notifyOpChangedSync(change.op, change.uid, change.pkg,
                    AppOpsManager.opToDefaultMode(change.op), change.previous_mode);
        }
    }

    private boolean shouldDeferResetOpToDpm(int op) {
        // TODO(b/174582385): avoid special-casing app-op resets by migrating app-op permission
        //  pre-grants to a role-based mechanism or another general-purpose mechanism.
        return dpmi != null && dpmi.supportsResetOp(op);
    }

    /** Assumes {@link #shouldDeferResetOpToDpm(int)} is true. */
    private void deferResetOpToDpm(int op, String packageName, @UserIdInt int userId) {
        // TODO(b/174582385): avoid special-casing app-op resets by migrating app-op permission
        //  pre-grants to a role-based mechanism or another general-purpose mechanism.
        dpmi.resetOp(op, packageName, userId);
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
        final boolean mayWatchPackageName =
                packageName != null && !filterAppAccessUnlocked(packageName);
        synchronized (this) {
            int switchOp = (op != AppOpsManager.OP_NONE) ? AppOpsManager.opToSwitch(op) : op;

            int notifiedOps;
            if ((flags & CALL_BACK_ON_SWITCHED_OP) == 0) {
                if (op == OP_NONE) {
                    notifiedOps = ALL_OPS;
                } else {
                    notifiedOps = op;
                }
            } else {
                notifiedOps = switchOp;
            }

            ModeCallback cb = mModeWatchers.get(callback.asBinder());
            if (cb == null) {
                cb = new ModeCallback(callback, watchedUid, flags, notifiedOps, callingUid,
                        callingPid);
                mModeWatchers.put(callback.asBinder(), cb);
            }
            if (switchOp != AppOpsManager.OP_NONE) {
                ArraySet<ModeCallback> cbs = mOpModeWatchers.get(switchOp);
                if (cbs == null) {
                    cbs = new ArraySet<>();
                    mOpModeWatchers.put(switchOp, cbs);
                }
                cbs.add(cb);
            }
            if (mayWatchPackageName) {
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

    public CheckOpsDelegate getAppOpsServiceDelegate() {
        synchronized (this) {
            return (mCheckOpsDelegateDispatcher != null)
                    ? mCheckOpsDelegateDispatcher.getCheckOpsDelegate()
                    : null;
        }
    }

    public void setAppOpsServiceDelegate(CheckOpsDelegate delegate) {
        synchronized (this) {
            if (delegate != null) {
                mCheckOpsDelegateDispatcher = new CheckOpsDelegateDispatcher(delegate);
            } else {
                mCheckOpsDelegateDispatcher = null;
            }
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
        final CheckOpsDelegate policy;
        final CheckOpsDelegateDispatcher delegateDispatcher;
        synchronized (AppOpsService.this) {
            policy = mAppOpsPolicy;
            delegateDispatcher = mCheckOpsDelegateDispatcher;
        }
        if (policy != null) {
            if (delegateDispatcher != null) {
                return policy.checkOperation(code, uid, packageName, raw,
                        delegateDispatcher::checkOperationImpl);
            } else {
                return policy.checkOperation(code, uid, packageName, raw,
                        AppOpsService.this::checkOperationImpl);
            }
        } else if (delegateDispatcher != null) {
            delegateDispatcher.getCheckOpsDelegate().checkOperation(code, uid,
                    packageName, raw, AppOpsService.this::checkOperationImpl);
        }
        return checkOperationImpl(code, uid, packageName, raw);
    }

    private int checkOperationImpl(int code, int uid, String packageName, boolean raw) {
        verifyIncomingOp(code);
        verifyIncomingPackage(packageName, UserHandle.getUserId(uid));

        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        return checkOperationUnchecked(code, uid, resolvedPackageName, raw);
    }

    /**
     * Get the mode of an app-op.
     *
     * @param code The code of the op
     * @param uid The uid of the package the op belongs to
     * @param packageName The package the op belongs to
     * @param raw If the raw state of eval-ed state should be checked.
     *
     * @return The mode of the op
     */
    private @Mode int checkOperationUnchecked(int code, int uid, @NonNull String packageName,
                boolean raw) {
        RestrictionBypass bypass;
        try {
            bypass = verifyAndGetBypass(uid, packageName, null);
        } catch (SecurityException e) {
            Slog.e(TAG, "checkOperation", e);
            return AppOpsManager.opToDefaultMode(code);
        }

        if (isOpRestrictedDueToSuspend(code, packageName, uid)) {
            return AppOpsManager.MODE_IGNORED;
        }
        synchronized (this) {
            if (isOpRestrictedLocked(uid, code, packageName, bypass)) {
                return AppOpsManager.MODE_IGNORED;
            }
            code = AppOpsManager.opToSwitch(code);
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState != null && uidState.opModes != null
                    && uidState.opModes.indexOfKey(code) >= 0) {
                final int rawMode = uidState.opModes.get(code);
                return raw ? rawMode : uidState.evalMode(code, rawMode);
            }
            Op op = getOpLocked(code, uid, packageName, null, bypass, false);
            if (op == null) {
                return AppOpsManager.opToDefaultMode(code);
            }
            return raw ? op.mode : op.evalMode();
        }
    }

    @Override
    public int checkAudioOperation(int code, int usage, int uid, String packageName) {
        final CheckOpsDelegate policy;
        final CheckOpsDelegateDispatcher delegateDispatcher;
        synchronized (AppOpsService.this) {
            policy = mAppOpsPolicy;
            delegateDispatcher = mCheckOpsDelegateDispatcher;
        }
        if (policy != null) {
            if (delegateDispatcher != null) {
                return policy.checkAudioOperation(code, usage, uid, packageName,
                        delegateDispatcher::checkAudioOperationImpl);
            } else {
                return policy.checkAudioOperation(code, usage, uid, packageName,
                        AppOpsService.this::checkAudioOperationImpl);
            }
        } else if (delegateDispatcher != null) {
            delegateDispatcher.getCheckOpsDelegate().checkAudioOperation(code, usage,
                    uid, packageName, AppOpsService.this::checkAudioOperationImpl);
        }
        return checkAudioOperationImpl(code, usage, uid, packageName);
    }

    private int checkAudioOperationImpl(int code, int usage, int uid, String packageName) {
        final int mode = mAudioRestrictionManager.checkAudioOperation(
                code, usage, uid, packageName);
        if (mode != AppOpsManager.MODE_ALLOWED) {
            return mode;
        }
        return checkOperation(code, uid, packageName);
    }

    @Override
    public void setAudioRestriction(int code, int usage, int uid, int mode,
            String[] exceptionPackages) {
        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), uid);
        verifyIncomingUid(uid);
        verifyIncomingOp(code);

        mAudioRestrictionManager.setZenModeAudioRestriction(
                code, usage, uid, mode, exceptionPackages);

        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsService::notifyWatchersOfChange, this, code, UID_ANY));
    }


    @Override
    public void setCameraAudioRestriction(@CAMERA_AUDIO_RESTRICTION int mode) {
        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), -1);

        mAudioRestrictionManager.setCameraAudioRestriction(mode);

        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsService::notifyWatchersOfChange, this,
                AppOpsManager.OP_PLAY_AUDIO, UID_ANY));
        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsService::notifyWatchersOfChange, this,
                AppOpsManager.OP_VIBRATE, UID_ANY));
    }

    @Override
    public int checkPackage(int uid, String packageName) {
        Objects.requireNonNull(packageName);
        try {
            verifyAndGetBypass(uid, packageName, null);
            if (filterAppAccessUnlocked(packageName)) {
                return AppOpsManager.MODE_ERRORED;
            }
            return AppOpsManager.MODE_ALLOWED;
        } catch (SecurityException ignored) {
            return AppOpsManager.MODE_ERRORED;
        }
    }

    /**
     * This method will check with PackageManager to determine if the package provided should
     * be visible to the {@link Binder#getCallingUid()}.
     *
     * NOTE: This must not be called while synchronized on {@code this} to avoid dead locks
     */
    private boolean filterAppAccessUnlocked(String packageName) {
        final int callingUid = Binder.getCallingUid();
        return LocalServices.getService(PackageManagerInternal.class)
                .filterAppAccess(packageName, callingUid, UserHandle.getUserId(callingUid));
    }

    @Override
    public int noteProxyOperation(int code, int proxiedUid, String proxiedPackageName,
            String proxiedAttributionTag, int proxyUid, String proxyPackageName,
            String proxyAttributionTag, boolean shouldCollectAsyncNotedOp, String message,
            boolean shouldCollectMessage) {
        verifyIncomingUid(proxyUid);
        verifyIncomingOp(code);
        verifyIncomingPackage(proxiedPackageName, UserHandle.getUserId(proxiedUid));
        verifyIncomingPackage(proxyPackageName, UserHandle.getUserId(proxyUid));

        String resolveProxyPackageName = resolvePackageName(proxyUid, proxyPackageName);
        if (resolveProxyPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }

        // This is a workaround for R QPR, new API change is not allowed. We only allow the current
        // voice recognizer is also the voice interactor to noteproxy op.
        final boolean isTrustVoiceServiceProxy = AppOpsManager.isTrustedVoiceServiceProxy(mContext,
                proxyPackageName, code, UserHandle.getUserId(proxyUid));
        final boolean isSelfBlame = Binder.getCallingUid() == proxiedUid;
        final boolean isProxyTrusted = mContext.checkPermission(
                Manifest.permission.UPDATE_APP_OPS_STATS, -1, proxyUid)
                == PackageManager.PERMISSION_GRANTED || isTrustVoiceServiceProxy || isSelfBlame;

        final int proxyFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXY
                : AppOpsManager.OP_FLAG_UNTRUSTED_PROXY;
        final int proxyMode = noteOperationUnchecked(code, proxyUid, resolveProxyPackageName,
                proxyAttributionTag, Process.INVALID_UID, null, null, proxyFlags,
                !isProxyTrusted, "proxy " + message, shouldCollectMessage);
        if (proxyMode != AppOpsManager.MODE_ALLOWED) {
            return proxyMode;
        }

        String resolveProxiedPackageName = resolvePackageName(proxiedUid, proxiedPackageName);
        if (resolveProxiedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        final int proxiedFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXIED
                : AppOpsManager.OP_FLAG_UNTRUSTED_PROXIED;
        return noteOperationUnchecked(code, proxiedUid, resolveProxiedPackageName,
                proxiedAttributionTag, proxyUid, resolveProxyPackageName, proxyAttributionTag,
                proxiedFlags, shouldCollectAsyncNotedOp, message, shouldCollectMessage);
    }

    @Override
    public int noteOperation(int code, int uid, String packageName, String attributionTag,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage) {
        final CheckOpsDelegate policy;
        final CheckOpsDelegateDispatcher delegateDispatcher;
        synchronized (AppOpsService.this) {
            policy = mAppOpsPolicy;
            delegateDispatcher = mCheckOpsDelegateDispatcher;
        }
        if (policy != null) {
            if (delegateDispatcher != null) {
                return policy.noteOperation(code, uid, packageName, attributionTag,
                        shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                        delegateDispatcher::noteOperationImpl);
            } else {
                return policy.noteOperation(code, uid, packageName, attributionTag,
                        shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                        AppOpsService.this::noteOperationImpl);
            }
        } else if (delegateDispatcher != null) {
            delegateDispatcher.getCheckOpsDelegate().noteOperation(code, uid, packageName,
                    attributionTag, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    AppOpsService.this::noteOperationImpl);
        }
        return noteOperationImpl(code, uid, packageName, attributionTag,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage);
    }

    private int noteOperationImpl(int code, int uid, @Nullable String packageName,
            @Nullable String attributionTag, boolean shouldCollectAsyncNotedOp,
            @Nullable String message, boolean shouldCollectMessage) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        verifyIncomingPackage(packageName, UserHandle.getUserId(uid));

        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        return noteOperationUnchecked(code, uid, resolvedPackageName, attributionTag,
                Process.INVALID_UID, null, null, AppOpsManager.OP_FLAG_SELF,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage);
    }

    private int noteOperationUnchecked(int code, int uid, @NonNull String packageName,
            @Nullable String attributionTag, int proxyUid, String proxyPackageName,
            @Nullable String proxyAttributionTag, @OpFlags int flags,
            boolean shouldCollectAsyncNotedOp, @Nullable String message,
            boolean shouldCollectMessage) {
        RestrictionBypass bypass;
        try {
            bypass = verifyAndGetBypass(uid, packageName, attributionTag);
        } catch (SecurityException e) {
            Slog.e(TAG, "noteOperation", e);
            return AppOpsManager.MODE_ERRORED;
        }

        synchronized (this) {
            final Ops ops = getOpsLocked(uid, packageName, attributionTag, bypass,
                    true /* edit */);
            if (ops == null) {
                scheduleOpNotedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                        AppOpsManager.MODE_IGNORED);
                if (DEBUG) Slog.d(TAG, "noteOperation: no op for code " + code + " uid " + uid
                        + " package " + packageName);
                return AppOpsManager.MODE_ERRORED;
            }
            final Op op = getOpLocked(ops, code, uid, true);
            final AttributedOp attributedOp = op.getOrCreateAttribution(op, attributionTag);
            if (attributedOp.isRunning()) {
                Slog.w(TAG, "Noting op not finished: uid " + uid + " pkg " + packageName + " code "
                        + code + " startTime of in progress event="
                        + attributedOp.mInProgressEvents.valueAt(0).getStartTime());
            }

            final int switchCode = AppOpsManager.opToSwitch(code);
            final UidState uidState = ops.uidState;
            if (isOpRestrictedLocked(uid, code, packageName, bypass)) {
                attributedOp.rejected(uidState.state, flags);
                scheduleOpNotedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                        AppOpsManager.MODE_IGNORED);
                return AppOpsManager.MODE_IGNORED;
            }
            // If there is a non-default per UID policy (we set UID op mode only if
            // non-default) it takes over, otherwise use the per package policy.
            if (uidState.opModes != null && uidState.opModes.indexOfKey(switchCode) >= 0) {
                final int uidMode = uidState.evalMode(code, uidState.opModes.get(switchCode));
                if (uidMode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Slog.d(TAG, "noteOperation: uid reject #" + uidMode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName);
                    attributedOp.rejected(uidState.state, flags);
                    scheduleOpNotedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                            uidMode);
                    return uidMode;
                }
            } else {
                final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, uid, true)
                        : op;
                final int mode = switchOp.evalMode();
                if (mode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Slog.d(TAG, "noteOperation: reject #" + mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName);
                    attributedOp.rejected(uidState.state, flags);
                    scheduleOpNotedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                            mode);
                    return mode;
                }
            }
            if (DEBUG) {
                Slog.d(TAG,
                        "noteOperation: allowing code " + code + " uid " + uid + " package "
                                + packageName + (attributionTag == null ? ""
                                : "." + attributionTag));
            }
            scheduleOpNotedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                    AppOpsManager.MODE_ALLOWED);
            attributedOp.accessed(proxyUid, proxyPackageName, proxyAttributionTag, uidState.state,
                    flags);

            if (shouldCollectAsyncNotedOp) {
                collectAsyncNotedOp(uid, packageName, code, attributionTag, flags, message,
                        shouldCollectMessage);
            }

            return AppOpsManager.MODE_ALLOWED;
        }
    }

    // TODO moltmann: Allow watching for attribution ops
    @Override
    public void startWatchingActive(int[] ops, IAppOpsActiveCallback callback) {
        int watchedUid = Process.INVALID_UID;
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
    public void startWatchingStarted(int[] ops, @NonNull IAppOpsStartedCallback callback) {
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
        Objects.requireNonNull(callback, "Callback cannot be null");

        synchronized (this) {
            SparseArray<StartedCallback> callbacks = mStartedWatchers.get(callback.asBinder());
            if (callbacks == null) {
                callbacks = new SparseArray<>();
                mStartedWatchers.put(callback.asBinder(), callbacks);
            }

            final StartedCallback startedCallback = new StartedCallback(callback, watchedUid,
                    callingUid, callingPid);
            for (int op : ops) {
                callbacks.put(op, startedCallback);
            }
        }
    }

    @Override
    public void stopWatchingStarted(IAppOpsStartedCallback callback) {
        Objects.requireNonNull(callback, "Callback cannot be null");

        synchronized (this) {
            final SparseArray<StartedCallback> startedCallbacks =
                    mStartedWatchers.remove(callback.asBinder());
            if (startedCallbacks == null) {
                return;
            }

            final int callbackCount = startedCallbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                startedCallbacks.valueAt(i).destroy();
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
        Objects.requireNonNull(callback, "Callback cannot be null");
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
        Objects.requireNonNull(callback, "Callback cannot be null");
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

    /**
     * Collect an {@link AsyncNotedAppOp}.
     *
     * @param uid The uid the op was noted for
     * @param packageName The package the op was noted for
     * @param opCode The code of the op noted
     * @param attributionTag attribution tag the op was noted for
     * @param message The message for the op noting
     */
    private void collectAsyncNotedOp(int uid, @NonNull String packageName, int opCode,
            @Nullable String attributionTag, @OpFlags int flags, @NonNull String message,
            boolean shouldCollectMessage) {
        Objects.requireNonNull(message);

        int callingUid = Binder.getCallingUid();

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (this) {
                Pair<String, Integer> key = getAsyncNotedOpsKey(packageName, uid);

                RemoteCallbackList<IAppOpsAsyncNotedCallback> callbacks = mAsyncOpWatchers.get(key);
                AsyncNotedAppOp asyncNotedOp = new AsyncNotedAppOp(opCode, callingUid,
                        attributionTag, message, System.currentTimeMillis());
                final boolean[] wasNoteForwarded = {false};

                if ((flags & (OP_FLAG_SELF | OP_FLAG_TRUSTED_PROXIED)) != 0
                        && shouldCollectMessage) {
                    reportRuntimeAppOpAccessMessageAsyncLocked(uid, packageName, opCode,
                            attributionTag, message);
                }

                if (callbacks != null) {
                    callbacks.broadcast((cb) -> {
                        try {
                            cb.opNoted(asyncNotedOp);
                            wasNoteForwarded[0] = true;
                        } catch (RemoteException e) {
                            Slog.e(TAG,
                                    "Could not forward noteOp of " + opCode + " to " + packageName
                                            + "/" + uid + "(" + attributionTag + ")", e);
                        }
                    });
                }

                if (!wasNoteForwarded[0]) {
                    ArrayList<AsyncNotedAppOp> unforwardedOps = mUnforwardedAsyncNotedOps.get(key);
                    if (unforwardedOps == null) {
                        unforwardedOps = new ArrayList<>(1);
                        mUnforwardedAsyncNotedOps.put(key, unforwardedOps);
                    }

                    unforwardedOps.add(asyncNotedOp);
                    if (unforwardedOps.size() > MAX_UNFORWARDED_OPS) {
                        unforwardedOps.remove(0);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Compute a key to be used in {@link #mAsyncOpWatchers} and {@link #mUnforwardedAsyncNotedOps}
     *
     * @param packageName The package name of the app
     * @param uid The uid of the app
     *
     * @return They key uniquely identifying the app
     */
    private @NonNull Pair<String, Integer> getAsyncNotedOpsKey(@NonNull String packageName,
            int uid) {
        return new Pair<>(packageName, uid);
    }

    @Override
    public void startWatchingAsyncNoted(String packageName, IAppOpsAsyncNotedCallback callback) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callback);

        int uid = Binder.getCallingUid();
        Pair<String, Integer> key = getAsyncNotedOpsKey(packageName, uid);

        verifyAndGetBypass(uid, packageName, null);

        synchronized (this) {
            RemoteCallbackList<IAppOpsAsyncNotedCallback> callbacks = mAsyncOpWatchers.get(key);
            if (callbacks == null) {
                callbacks = new RemoteCallbackList<IAppOpsAsyncNotedCallback>() {
                    @Override
                    public void onCallbackDied(IAppOpsAsyncNotedCallback callback) {
                        synchronized (AppOpsService.this) {
                            if (getRegisteredCallbackCount() == 0) {
                                mAsyncOpWatchers.remove(key);
                            }
                        }
                    }
                };
                mAsyncOpWatchers.put(key, callbacks);
            }

            callbacks.register(callback);
        }
    }

    @Override
    public void stopWatchingAsyncNoted(String packageName, IAppOpsAsyncNotedCallback callback) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(callback);

        int uid = Binder.getCallingUid();
        Pair<String, Integer> key = getAsyncNotedOpsKey(packageName, uid);

        verifyAndGetBypass(uid, packageName, null);

        synchronized (this) {
            RemoteCallbackList<IAppOpsAsyncNotedCallback> callbacks = mAsyncOpWatchers.get(key);
            if (callbacks != null) {
                callbacks.unregister(callback);
                if (callbacks.getRegisteredCallbackCount() == 0) {
                    mAsyncOpWatchers.remove(key);
                }
            }
        }
    }

    @Override
    public List<AsyncNotedAppOp> extractAsyncOps(String packageName) {
        Objects.requireNonNull(packageName);

        int uid = Binder.getCallingUid();

        verifyAndGetBypass(uid, packageName, null);

        synchronized (this) {
            return mUnforwardedAsyncNotedOps.remove(getAsyncNotedOpsKey(packageName, uid));
        }
    }

    @Override
    public int startOperation(IBinder clientId, int code, int uid, String packageName,
            String attributionTag, boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp,
            String message, boolean shouldCollectMessage) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        verifyIncomingPackage(packageName, UserHandle.getUserId(uid));

        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }

        // As a special case for OP_RECORD_AUDIO_HOTWORD, which we use only for attribution
        // purposes and not as a check, also make sure that the caller is allowed to access
        // the data gated by OP_RECORD_AUDIO.
        //
        // TODO: Revert this change before Android 12.
        if (code == OP_RECORD_AUDIO_HOTWORD) {
            int result = checkOperation(OP_RECORD_AUDIO, uid, packageName);
            if (result != AppOpsManager.MODE_ALLOWED) {
                return result;
            }
        }
        return startOperationUnchecked(clientId, code, uid, packageName, attributionTag,
                Process.INVALID_UID, null, null, OP_FLAG_SELF, startIfModeDefault,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage, false);

    }

    @Override
    public int startProxyOperation(IBinder clientId, int code, int proxiedUid,
            String proxiedPackageName, @Nullable String proxiedAttributionTag, int proxyUid,
            String proxyPackageName, @Nullable String proxyAttributionTag,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, String message,
            boolean shouldCollectMessage) {
        verifyIncomingUid(proxyUid);
        verifyIncomingOp(code);
        verifyIncomingPackage(proxyPackageName, UserHandle.getUserId(proxyUid));
        verifyIncomingPackage(proxiedPackageName, UserHandle.getUserId(proxiedUid));

        String resolvedProxyPackageName = resolvePackageName(proxyUid, proxyPackageName);
        if (resolvedProxyPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }

        // This is a workaround for R QPR, new API change is not allowed. We only allow the current
        // voice recognizer is also the voice interactor to noteproxy op.
        final boolean isTrustVoiceServiceProxy = AppOpsManager.isTrustedVoiceServiceProxy(mContext,
                proxyPackageName, code, UserHandle.getUserId(proxyUid));
        final boolean isSelfBlame = Binder.getCallingUid() == proxiedUid;
        final boolean isProxyTrusted = mContext.checkPermission(
                Manifest.permission.UPDATE_APP_OPS_STATS, -1, proxyUid)
                == PackageManager.PERMISSION_GRANTED || isTrustVoiceServiceProxy || isSelfBlame;

        final int proxyFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXY
                : AppOpsManager.OP_FLAG_UNTRUSTED_PROXY;

        String resolvedProxiedPackageName = resolvePackageName(proxiedUid, proxiedPackageName);
        if (resolvedProxiedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        final int proxiedFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXIED
                : AppOpsManager.OP_FLAG_UNTRUSTED_PROXIED;

        // Test if the proxied operation will succeed before starting the proxy operation
        final int testProxiedMode = startOperationUnchecked(clientId, code, proxiedUid,
                resolvedProxiedPackageName, proxiedAttributionTag, proxyUid,
                resolvedProxyPackageName, proxyAttributionTag, proxiedFlags, startIfModeDefault,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage, true);
        if (!shouldStartForMode(testProxiedMode, startIfModeDefault)) {
            return testProxiedMode;
        }

        final int proxyMode = startOperationUnchecked(clientId, code, proxyUid,
                resolvedProxyPackageName, proxyAttributionTag, Process.INVALID_UID, null, null,
                proxyFlags, startIfModeDefault, !isProxyTrusted, "proxy " + message,
                shouldCollectMessage, false);
        if (!shouldStartForMode(proxyMode, startIfModeDefault)) {
            return proxyMode;
        }

        return startOperationUnchecked(clientId, code, proxiedUid, resolvedProxiedPackageName,
                proxiedAttributionTag, proxyUid, resolvedProxyPackageName, proxyAttributionTag,
                proxiedFlags, startIfModeDefault, shouldCollectAsyncNotedOp, message,
                shouldCollectMessage, false);
    }

    private boolean shouldStartForMode(int mode, boolean startIfModeDefault) {
        return (mode == MODE_ALLOWED || (mode == MODE_DEFAULT && startIfModeDefault));
    }

    private int startOperationUnchecked(IBinder clientId, int code, int uid,
            @NonNull String packageName, @Nullable String attributionTag, int proxyUid,
            String proxyPackageName, @Nullable String proxyAttributionTag, @OpFlags int flags,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, @Nullable String message,
            boolean shouldCollectMessage, boolean dryRun) {
        RestrictionBypass bypass;
        try {
            bypass = verifyAndGetBypass(uid, packageName, attributionTag);
        } catch (SecurityException e) {
            Slog.e(TAG, "startOperation", e);
            return AppOpsManager.MODE_ERRORED;
        }

        synchronized (this) {
            final Ops ops = getOpsLocked(uid, packageName, attributionTag, bypass, true /* edit */);
            if (ops == null) {
                if (!dryRun) {
                    scheduleOpStartedIfNeededLocked(code, uid, packageName, attributionTag,
                            flags, AppOpsManager.MODE_IGNORED);
                }
                if (DEBUG) Slog.d(TAG, "startOperation: no op for code " + code + " uid " + uid
                        + " package " + packageName);
                return AppOpsManager.MODE_ERRORED;
            }
            final Op op = getOpLocked(ops, code, uid, true);
            if (isOpRestrictedLocked(uid, code, packageName, bypass)) {
                if (!dryRun) {
                    scheduleOpStartedIfNeededLocked(code, uid, packageName, attributionTag,
                            flags, AppOpsManager.MODE_IGNORED);
                }
                return AppOpsManager.MODE_IGNORED;
            }

            final AttributedOp attributedOp = op.getOrCreateAttribution(op, attributionTag);
            final int switchCode = AppOpsManager.opToSwitch(code);
            final UidState uidState = ops.uidState;
            // If there is a non-default per UID policy (we set UID op mode only if
            // non-default) it takes over, otherwise use the per package policy.
            if (uidState.opModes != null && uidState.opModes.indexOfKey(switchCode) >= 0) {
                final int uidMode = uidState.evalMode(code, uidState.opModes.get(switchCode));
                if (!shouldStartForMode(uidMode, startIfModeDefault)) {
                    if (DEBUG) {
                        Slog.d(TAG, "startOperation: uid reject #" + uidMode + " for code "
                                + switchCode + " (" + code + ") uid " + uid + " package "
                                + packageName);
                    }
                    if (!dryRun) {
                        attributedOp.rejected(uidState.state, flags);
                        scheduleOpStartedIfNeededLocked(code, uid, packageName, attributionTag,
                                flags, uidMode);
                    }
                    return uidMode;
                }
            } else {
                final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, uid, true)
                        : op;
                final int mode = switchOp.evalMode();
                if (mode != AppOpsManager.MODE_ALLOWED
                        && (!startIfModeDefault || mode != MODE_DEFAULT)) {
                    if (DEBUG) Slog.d(TAG, "startOperation: reject #" + mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName);
                    if (!dryRun) {
                        attributedOp.rejected(uidState.state, flags);
                        scheduleOpStartedIfNeededLocked(code, uid, packageName, attributionTag,
                                flags, mode);
                    }
                    return mode;
                }
            }
            if (DEBUG) Slog.d(TAG, "startOperation: allowing code " + code + " uid " + uid
                    + " package " + packageName);
            if (!dryRun) {
                scheduleOpStartedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                        AppOpsManager.MODE_ALLOWED);
                try {
                    attributedOp.started(clientId, proxyUid, proxyPackageName, proxyAttributionTag,
                            uidState.state, flags);
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        if (shouldCollectAsyncNotedOp && !dryRun) {
            collectAsyncNotedOp(uid, packageName, code, attributionTag, AppOpsManager.OP_FLAG_SELF,
                    message, shouldCollectMessage);
        }

        return AppOpsManager.MODE_ALLOWED;
    }

    @Override
    public void finishOperation(IBinder clientId, int code, int uid, String packageName,
            String attributionTag) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        verifyIncomingPackage(packageName, UserHandle.getUserId(uid));

        String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return;
        }

        finishOperationUnchecked(clientId, code, uid, resolvedPackageName, attributionTag);
    }

    @Override
    public void finishProxyOperation(IBinder clientId, int code, int proxiedUid,
            String proxiedPackageName, @Nullable String proxiedAttributionTag, int proxyUid,
            @Nullable String proxyPackageName, @Nullable String proxyAttributionTag) {
        verifyIncomingUid(proxyUid);
        verifyIncomingOp(code);
        verifyIncomingPackage(proxyPackageName, UserHandle.getUserId(proxyUid));
        verifyIncomingPackage(proxiedPackageName, UserHandle.getUserId(proxiedUid));

        String resolvedProxyPackageName = resolvePackageName(proxyUid, proxyPackageName);
        if (resolvedProxyPackageName == null) {
            return;
        }

        finishOperationUnchecked(clientId, code, proxyUid, resolvedProxyPackageName,
                proxyAttributionTag);

        String resolvedProxiedPackageName = resolvePackageName(proxiedUid, proxiedPackageName);
        if (resolvedProxiedPackageName == null) {
            return;
        }

        finishOperationUnchecked(clientId, code, proxiedUid, resolvedProxiedPackageName,
                proxiedAttributionTag);
    }

    private void finishOperationUnchecked(IBinder clientId, int code, int uid, String packageName,
            String attributionTag) {
        RestrictionBypass bypass;
        try {
            bypass = verifyAndGetBypass(uid, packageName, attributionTag);
        } catch (SecurityException e) {
            Slog.e(TAG, "Cannot finishOperation", e);
            return;
        }

        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName, attributionTag, bypass, true);
            if (op == null) {
                Slog.e(TAG, "Operation not found: uid=" + uid + " pkg=" + packageName + "("
                        + attributionTag + ") op=" + AppOpsManager.opToName(code));
                return;
            }
            final AttributedOp attributedOp = op.mAttributions.get(attributionTag);
            if (attributedOp == null) {
                Slog.e(TAG, "Attribution not found: uid=" + uid + " pkg=" + packageName + "("
                        + attributionTag + ") op=" + AppOpsManager.opToName(code));
                return;
            }

            if (attributedOp.isRunning()) {
                attributedOp.finished(clientId);
            } else {
                Slog.e(TAG, "Operation not started: uid=" + uid + " pkg=" + packageName + "("
                        + attributionTag + ") op=" + AppOpsManager.opToName(code));
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
        // There are features watching for mode changes such as window manager
        // and location manager which are in our process. The callbacks in these
        // features may require permissions our remote caller does not have.
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

    private void scheduleOpStartedIfNeededLocked(int code, int uid, String pkgName,
            String attributionTag, @OpFlags int flags, @Mode int result) {
        ArraySet<StartedCallback> dispatchedCallbacks = null;
        final int callbackListCount = mStartedWatchers.size();
        for (int i = 0; i < callbackListCount; i++) {
            final SparseArray<StartedCallback> callbacks = mStartedWatchers.valueAt(i);

            StartedCallback callback = callbacks.get(code);
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
                AppOpsService::notifyOpStarted,
                this, dispatchedCallbacks, code, uid, pkgName, attributionTag, flags,
                result));
    }

    private void notifyOpStarted(ArraySet<StartedCallback> callbacks,
            int code, int uid, String packageName, String attributionTag, @OpFlags int flags,
            @Mode int result) {
        final long identity = Binder.clearCallingIdentity();
        try {
            final int callbackCount = callbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                final StartedCallback callback = callbacks.valueAt(i);
                try {
                    callback.mCallback.opStarted(code, uid, packageName, attributionTag, flags,
                            result);
                } catch (RemoteException e) {
                    /* do nothing */
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void scheduleOpNotedIfNeededLocked(int code, int uid, String packageName,
            String attributionTag, @OpFlags int flags, @Mode int result) {
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
                this, dispatchedCallbacks, code, uid, packageName, attributionTag, flags,
                result));
    }

    private void notifyOpChecked(ArraySet<NotedCallback> callbacks,
            int code, int uid, String packageName, String attributionTag, @OpFlags int flags,
            @Mode int result) {
        // There are features watching for checks in our process. The callbacks in
        // these features may require permissions our remote caller does not have.
        final long identity = Binder.clearCallingIdentity();
        try {
            final int callbackCount = callbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                final NotedCallback callback = callbacks.valueAt(i);
                try {
                    callback.mCallback.opNoted(code, uid, packageName, attributionTag, flags,
                            result);
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

    @Override
    public boolean shouldCollectNotes(int opCode) {
        Preconditions.checkArgumentInRange(opCode, 0, _NUM_OP - 1, "opCode");

        String perm = AppOpsManager.opToPermission(opCode);
        if (perm == null) {
            return false;
        }

        PermissionInfo permInfo;
        try {
            permInfo = mContext.getPackageManager().getPermissionInfo(perm, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }

        return permInfo.getProtection() == PROTECTION_DANGEROUS
                || (permInfo.getProtectionFlags() & PROTECTION_FLAG_APPOP) != 0;
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

    private void verifyIncomingPackage(@Nullable String packageName, @UserIdInt int userId) {
        if (packageName != null && getPackageManagerInternal().filterAppAccess(packageName,
                Binder.getCallingUid(), userId)) {
            throw new IllegalArgumentException(
                    packageName + " not found from " + Binder.getCallingUid());
        }
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
            updatePendingStateIfNeededLocked(uidState);
        }
        return uidState;
    }

    /**
     * Check if the pending state should be updated and do so if needed
     *
     * @param uidState The uidState that might have a pending state
     */
    private void updatePendingStateIfNeededLocked(@NonNull UidState uidState) {
        if (uidState != null) {
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
    }

    private void commitUidPendingStateLocked(UidState uidState) {
        if (uidState.hasForegroundWatchers) {
            for (int fgi = uidState.foregroundOps.size() - 1; fgi >= 0; fgi--) {
                if (!uidState.foregroundOps.valueAt(fgi)) {
                    continue;
                }
                final int code = uidState.foregroundOps.keyAt(fgi);
                // For location ops we consider fg state only if the fg service
                // is of location type, for all other ops any fg service will do.
                final long firstUnrestrictedUidState = resolveFirstUnrestrictedUidState(code);
                final boolean resolvedLastFg = uidState.state <= firstUnrestrictedUidState;
                final boolean resolvedNowFg = uidState.pendingState <= firstUnrestrictedUidState;
                if (resolvedLastFg == resolvedNowFg
                        && uidState.capability == uidState.pendingCapability
                        && uidState.appWidgetVisible == uidState.pendingAppWidgetVisible) {
                    continue;
                }

                if (uidState.opModes != null
                        && uidState.opModes.indexOfKey(code) >= 0
                        && uidState.opModes.get(code) == AppOpsManager.MODE_FOREGROUND) {
                    mHandler.sendMessage(PooledLambda.obtainMessage(
                            AppOpsService::notifyOpChangedForAllPkgsInUid,
                            this, code, uidState.uid, true, null));
                } else if (uidState.pkgOps != null) {
                    final ArraySet<ModeCallback> callbacks = mOpModeWatchers.get(code);
                    if (callbacks != null) {
                        for (int cbi = callbacks.size() - 1; cbi >= 0; cbi--) {
                            final ModeCallback callback = callbacks.valueAt(cbi);
                            if ((callback.mFlags & AppOpsManager.WATCH_FOREGROUND_CHANGES) == 0
                                    || !callback.isWatchingUid(uidState.uid)) {
                                continue;
                            }
                            for (int pkgi = uidState.pkgOps.size() - 1; pkgi >= 0; pkgi--) {
                                final Op op = uidState.pkgOps.valueAt(pkgi).get(code);
                                if (op == null) {
                                    continue;
                                }
                                if (op.mode == AppOpsManager.MODE_FOREGROUND) {
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
        uidState.state = uidState.pendingState;
        uidState.capability = uidState.pendingCapability;
        uidState.appWidgetVisible = uidState.pendingAppWidgetVisible;
        uidState.pendingStateCommitTime = 0;
    }

    private void updateAppWidgetVisibility(SparseArray<String> uidPackageNames, boolean visible) {
        synchronized (this) {
            for (int i = uidPackageNames.size() - 1; i >= 0; i--) {
                final int uid = uidPackageNames.keyAt(i);
                final UidState uidState = getUidStateLocked(uid, true);
                if (uidState != null && (uidState.pendingAppWidgetVisible != visible)) {
                    uidState.pendingAppWidgetVisible = visible;
                    if (uidState.pendingAppWidgetVisible != uidState.appWidgetVisible) {
                        commitUidPendingStateLocked(uidState);
                    }
                }
            }
        }
    }

    /**
     * @return {@link PackageManagerInternal}
     */
    private @NonNull PackageManagerInternal getPackageManagerInternal() {
        if (mPackageManagerInternal == null) {
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        }

        return mPackageManagerInternal;
    }

    /**
     * Create a restriction description matching the properties of the package.
     *
     * @param context A context to use
     * @param pkg The package to create the restriction description for
     *
     * @return The restriction matching the package
     */
    private RestrictionBypass getBypassforPackage(@NonNull AndroidPackage pkg) {
        return new RestrictionBypass(pkg.isPrivileged(), mContext.checkPermission(
                android.Manifest.permission.EXEMPT_FROM_AUDIO_RECORD_RESTRICTIONS, -1, pkg.getUid())
                == PackageManager.PERMISSION_GRANTED);
    }

    /**
     * Verify that package belongs to uid and return the {@link RestrictionBypass bypass
     * description} for the package.
     *
     * @param uid The uid the package belongs to
     * @param packageName The package the might belong to the uid
     * @param attributionTag attribution tag or {@code null} if no need to verify
     *
     * @return {@code true} iff the package is privileged
     */
    private @Nullable RestrictionBypass verifyAndGetBypass(int uid, String packageName,
            @Nullable String attributionTag) {
        if (uid == Process.ROOT_UID) {
            // For backwards compatibility, don't check package name for root UID.
            return null;
        }

        // Do not check if uid/packageName/attributionTag is already known
        synchronized (this) {
            UidState uidState = mUidStates.get(uid);
            if (uidState != null && uidState.pkgOps != null) {
                Ops ops = uidState.pkgOps.get(packageName);

                if (ops != null && (attributionTag == null || ops.knownAttributionTags.contains(
                        attributionTag)) && ops.bypass != null) {
                    return ops.bypass;
                }
            }
        }

        int callingUid = Binder.getCallingUid();
        int userId = UserHandle.getUserId(uid);

        RestrictionBypass bypass = null;
        final long ident = Binder.clearCallingIdentity();
        try {
            int pkgUid;
            AndroidPackage pkg = LocalServices.getService(PackageManagerInternal.class).getPackage(
                    packageName);
            boolean isAttributionTagValid = false;

            if (pkg != null) {
                if (attributionTag == null) {
                    isAttributionTagValid = true;
                } else {
                    if (pkg.getAttributions() != null) {
                        int numAttributions = pkg.getAttributions().size();
                        for (int i = 0; i < numAttributions; i++) {
                            if (pkg.getAttributions().get(i).tag.equals(attributionTag)) {
                                isAttributionTagValid = true;
                            }
                        }
                    }
                }

                pkgUid = UserHandle.getUid(userId, UserHandle.getAppId(pkg.getUid()));
                bypass = getBypassforPackage(pkg);
            } else {
                // Allow any attribution tag for resolvable uids
                isAttributionTagValid = true;

                pkgUid = resolveUid(packageName);
                if (pkgUid >= 0) {
                    bypass = RestrictionBypass.UNRESTRICTED;
                }
            }
            if (pkgUid != uid) {
                throw new SecurityException("Specified package " + packageName + " under uid " + uid
                        + " but it is really " + pkgUid);
            }

            if (!isAttributionTagValid) {
                String msg = "attributionTag " + attributionTag + " not declared in"
                        + " manifest of " + packageName;
                try {
                    if (mPlatformCompat.isChangeEnabledByPackageName(
                            SECURITY_EXCEPTION_ON_INVALID_ATTRIBUTION_TAG_CHANGE, packageName,
                            userId) && mPlatformCompat.isChangeEnabledByUid(
                            SECURITY_EXCEPTION_ON_INVALID_ATTRIBUTION_TAG_CHANGE, callingUid)) {
                        throw new SecurityException(msg);
                    } else {
                        Slog.e(TAG, msg);
                    }
                } catch (RemoteException neverHappens) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        return bypass;
    }

    /**
     * Get (and potentially create) ops.
     *
     * @param uid The uid the package belongs to
     * @param packageName The name of the package
     * @param attributionTag attribution tag
     * @param bypass When to bypass certain op restrictions (can be null if edit == false)
     * @param edit If an ops does not exist, create the ops?

     * @return The ops
     */
    private Ops getOpsLocked(int uid, String packageName, @Nullable String attributionTag,
            @Nullable RestrictionBypass bypass, boolean edit) {
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
            ops = new Ops(packageName, uidState);
            uidState.pkgOps.put(packageName, ops);
        }

        if (edit) {
            if (bypass != null) {
                ops.bypass = bypass;
            }

            if (attributionTag != null) {
                ops.knownAttributionTags.add(attributionTag);
            }
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
     * @param attributionTag The attribution tag
     * @param bypass When to bypass certain op restrictions (can be null if edit == false)
     * @param edit Iff {@code true} create the {@link Op} object if not yet created
     *
     * @return The {@link Op state} of the op
     */
    private @Nullable Op getOpLocked(int code, int uid, @NonNull String packageName,
            @Nullable String attributionTag, @Nullable RestrictionBypass bypass, boolean edit) {
        Ops ops = getOpsLocked(uid, packageName, attributionTag, bypass, edit);
        if (ops == null) {
            return null;
        }
        return getOpLocked(ops, code, uid, edit);
    }

    private Op getOpLocked(Ops ops, int code, int uid, boolean edit) {
        Op op = ops.get(code);
        if (op == null) {
            if (!edit) {
                return null;
            }
            op = new Op(ops.uidState, ops.packageName, code, uid);
            ops.put(code, op);
        }
        if (edit) {
            scheduleWriteLocked();
        }
        return op;
    }

    private boolean isOpRestrictedDueToSuspend(int code, String packageName, int uid) {
        if (!ArrayUtils.contains(OPS_RESTRICTED_ON_SUSPEND, code)) {
            return false;
        }
        final PackageManagerInternal pmi = LocalServices.getService(PackageManagerInternal.class);
        return pmi.isPackageSuspended(packageName, UserHandle.getUserId(uid));
    }

    private boolean isOpRestrictedLocked(int uid, int code, String packageName,
            @Nullable RestrictionBypass appBypass) {
        int userHandle = UserHandle.getUserId(uid);
        final int restrictionSetCount = mOpUserRestrictions.size();

        for (int i = 0; i < restrictionSetCount; i++) {
            // For each client, check that the given op is not restricted, or that the given
            // package is exempt from the restriction.
            ClientRestrictionState restrictionState = mOpUserRestrictions.valueAt(i);
            if (restrictionState.hasRestriction(code, packageName, userHandle)) {
                RestrictionBypass opBypass = opAllowSystemBypassRestriction(code);
                if (opBypass != null) {
                    // If we are the system, bypass user restrictions for certain codes
                    synchronized (this) {
                        if (opBypass.isPrivileged && appBypass != null && appBypass.isPrivileged) {
                            return false;
                        }
                        if (opBypass.isRecordAudioRestrictionExcept && appBypass != null
                                && appBypass.isRecordAudioRestrictionExcept) {
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
                    TypedXmlPullParser parser = Xml.resolvePullParser(stream);
                    int type;
                    while ((type = parser.next()) != XmlPullParser.START_TAG
                            && type != XmlPullParser.END_DOCUMENT) {
                        ;
                    }

                    if (type != XmlPullParser.START_TAG) {
                        throw new IllegalStateException("no start tag found");
                    }

                    oldVersion = parser.getAttributeInt(null, "v", NO_VERSION);

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
                                AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, uidState.uid);
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

    private void readUidOps(TypedXmlPullParser parser) throws NumberFormatException,
            XmlPullParserException, IOException {
        final int uid = parser.getAttributeInt(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                final int code = parser.getAttributeInt(null, "n");
                final int mode = parser.getAttributeInt(null, "m");
                setUidMode(code, uid, mode);
            } else {
                Slog.w(TAG, "Unknown element under <uid-ops>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readPackage(TypedXmlPullParser parser)
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

    private void readUid(TypedXmlPullParser parser, String pkgName)
            throws NumberFormatException, XmlPullParserException, IOException {
        int uid = parser.getAttributeInt(null, "n");
        final UidState uidState = getUidStateLocked(uid, true);
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals("op")) {
                readOp(parser, uidState, pkgName);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
        uidState.evalForegroundOps(mOpModeWatchers);
    }

    private void readAttributionOp(TypedXmlPullParser parser, @NonNull Op parent,
            @Nullable String attribution)
            throws NumberFormatException, IOException, XmlPullParserException {
        final AttributedOp attributedOp = parent.getOrCreateAttribution(parent, attribution);

        final long key = parser.getAttributeLong(null, "n");
        final int uidState = extractUidStateFromKey(key);
        final int opFlags = extractFlagsFromKey(key);

        final long accessTime = parser.getAttributeLong(null, "t", 0);
        final long rejectTime = parser.getAttributeLong(null, "r", 0);
        final long accessDuration = parser.getAttributeLong(null, "d", -1);
        final String proxyPkg = XmlUtils.readStringAttribute(parser, "pp");
        final int proxyUid = parser.getAttributeInt(null, "pu", Process.INVALID_UID);
        final String proxyAttributionTag = XmlUtils.readStringAttribute(parser, "pc");

        if (accessTime > 0) {
            attributedOp.accessed(accessTime, accessDuration, proxyUid, proxyPkg,
                    proxyAttributionTag, uidState, opFlags);
        }
        if (rejectTime > 0) {
            attributedOp.rejected(rejectTime, uidState, opFlags);
        }
    }

    private void readOp(TypedXmlPullParser parser,
            @NonNull UidState uidState, @NonNull String pkgName)
            throws NumberFormatException, XmlPullParserException, IOException {
        int opCode = parser.getAttributeInt(null, "n");
        Op op = new Op(uidState, pkgName, opCode, uidState.uid);

        final int mode = parser.getAttributeInt(null, "m", AppOpsManager.opToDefaultMode(op.op));
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
                readAttributionOp(parser, op, XmlUtils.readStringAttribute(parser, "id"));
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
            ops = new Ops(pkgName, uidState);
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
                TypedXmlSerializer out = Xml.resolveSerializer(stream);
                out.startDocument(null, true);
                out.startTag(null, "app-ops");
                out.attributeInt(null, "v", CURRENT_VERSION);

                SparseArray<SparseIntArray> uidStatesClone;
                synchronized (this) {
                    uidStatesClone = new SparseArray<>(mUidStates.size());

                    final int uidStateCount = mUidStates.size();
                    for (int uidStateNum = 0; uidStateNum < uidStateCount; uidStateNum++) {
                        UidState uidState = mUidStates.valueAt(uidStateNum);
                        int uid = mUidStates.keyAt(uidStateNum);

                        SparseIntArray opModes = uidState.opModes;
                        if (opModes != null && opModes.size() > 0) {
                            uidStatesClone.put(uid, new SparseIntArray(opModes.size()));

                            final int opCount = opModes.size();
                            for (int opCountNum = 0; opCountNum < opCount; opCountNum++) {
                                uidStatesClone.get(uid).put(
                                        opModes.keyAt(opCountNum),
                                        opModes.valueAt(opCountNum));
                            }
                        }
                    }
                }

                final int uidStateCount = uidStatesClone.size();
                for (int uidStateNum = 0; uidStateNum < uidStateCount; uidStateNum++) {
                    SparseIntArray opModes = uidStatesClone.valueAt(uidStateNum);
                    if (opModes != null && opModes.size() > 0) {
                        out.startTag(null, "uid");
                        out.attributeInt(null, "n", uidStatesClone.keyAt(uidStateNum));
                        final int opCount = opModes.size();
                        for (int opCountNum = 0; opCountNum < opCount; opCountNum++) {
                            final int op = opModes.keyAt(opCountNum);
                            final int mode = opModes.valueAt(opCountNum);
                            out.startTag(null, "op");
                            out.attributeInt(null, "n", op);
                            out.attributeInt(null, "m", mode);
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
                        out.attributeInt(null, "n", pkg.getUid());
                        List<AppOpsManager.OpEntry> ops = pkg.getOps();
                        for (int j=0; j<ops.size(); j++) {
                            AppOpsManager.OpEntry op = ops.get(j);
                            out.startTag(null, "op");
                            out.attributeInt(null, "n", op.getOp());
                            if (op.getMode() != AppOpsManager.opToDefaultMode(op.getOp())) {
                                out.attributeInt(null, "m", op.getMode());
                            }

                            for (String attributionTag : op.getAttributedOpEntries().keySet()) {
                                final AttributedOpEntry attribution =
                                        op.getAttributedOpEntries().get(attributionTag);

                                final ArraySet<Long> keys = attribution.collectKeys();

                                final int keyCount = keys.size();
                                for (int k = 0; k < keyCount; k++) {
                                    final long key = keys.valueAt(k);

                                    final int uidState = AppOpsManager.extractUidStateFromKey(key);
                                    final int flags = AppOpsManager.extractFlagsFromKey(key);

                                    final long accessTime = attribution.getLastAccessTime(uidState,
                                            uidState, flags);
                                    final long rejectTime = attribution.getLastRejectTime(uidState,
                                            uidState, flags);
                                    final long accessDuration = attribution.getLastDuration(
                                            uidState, uidState, flags);
                                    // Proxy information for rejections is not backed up
                                    final OpEventProxyInfo proxy = attribution.getLastProxyInfo(
                                            uidState, uidState, flags);

                                    if (accessTime <= 0 && rejectTime <= 0 && accessDuration <= 0
                                            && proxy == null) {
                                        continue;
                                    }

                                    String proxyPkg = null;
                                    String proxyAttributionTag = null;
                                    int proxyUid = Process.INVALID_UID;
                                    if (proxy != null) {
                                        proxyPkg = proxy.getPackageName();
                                        proxyAttributionTag = proxy.getAttributionTag();
                                        proxyUid = proxy.getUid();
                                    }

                                    out.startTag(null, "st");
                                    if (attributionTag != null) {
                                        out.attribute(null, "id", attributionTag);
                                    }
                                    out.attributeLong(null, "n", key);
                                    if (accessTime > 0) {
                                        out.attributeLong(null, "t", accessTime);
                                    }
                                    if (rejectTime > 0) {
                                        out.attributeLong(null, "r", rejectTime);
                                    }
                                    if (accessDuration > 0) {
                                        out.attributeLong(null, "d", accessDuration);
                                    }
                                    if (proxyPkg != null) {
                                        out.attribute(null, "pp", proxyPkg);
                                    }
                                    if (proxyAttributionTag != null) {
                                        out.attribute(null, "pc", proxyAttributionTag);
                                    }
                                    if (proxyUid >= 0) {
                                        out.attributeInt(null, "pu", proxyUid);
                                    }
                                    out.endTag(null, "st");
                                }
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
        mHistoricalRegistry.mDiscreteRegistry.writeAndClearAccessHistory();
    }

    static class Shell extends ShellCommand {
        final IAppOpsService mInterface;
        final AppOpsService mInternal;

        int userId = UserHandle.USER_SYSTEM;
        String packageName;
        String attributionTag;
        String opStr;
        String modeStr;
        int op;
        int mode;
        int packageUid;
        int nonpackageUid;
        final static Binder sBinder = new Binder();
        IBinder mToken;
        boolean targetsUid;

        Shell(IAppOpsService iface, AppOpsService internal) {
            mInterface = iface;
            mInternal = internal;
            mToken = AppOpsManager.getClientId();
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
                } else if ("--uid".equals(argument)) {
                    targetsUid = true;
                } else if ("--attribution".equals(argument)) {
                    attributionTag = getNextArgRequired();
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
        pw.println("  start [--user <USER_ID>] [--attribution <ATTRIBUTION_TAG>] <PACKAGE | UID> "
                + "<OP> ");
        pw.println("    Starts a given operation for a particular application.");
        pw.println("  stop [--user <USER_ID>] [--attribution <ATTRIBUTION_TAG>] <PACKAGE | UID> "
                + "<OP> ");
        pw.println("    Stops a given operation for a particular application.");
        pw.println("  set [--user <USER_ID>] <[--uid] PACKAGE | UID> <OP> <MODE>");
        pw.println("    Set the mode for a particular application and operation.");
        pw.println("  get [--user <USER_ID>] [--attribution <ATTRIBUTION_TAG>] <PACKAGE | UID> "
                + "[<OP>]");
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
        pw.println("    <PACKAGE> an Android package name or its UID if prefixed by --uid");
        pw.println("    <OP>      an AppOps operation.");
        pw.println("    <MODE>    one of allow, ignore, deny, or default");
        pw.println("    <USER_ID> the user id under which the package is installed. If --user is");
        pw.println("              not specified, the current user is assumed.");
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

                    if (!shell.targetsUid && shell.packageName != null) {
                        shell.mInterface.setMode(shell.op, shell.packageUid, shell.packageName,
                                mode);
                    } else if (shell.targetsUid && shell.packageName != null) {
                        try {
                            final int uid = shell.mInternal.mContext.getPackageManager()
                                    .getPackageUidAsUser(shell.packageName, shell.userId);
                            shell.mInterface.setUidMode(shell.op, uid, mode);
                        } catch (PackageManager.NameNotFoundException e) {
                            return -1;
                        }
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
                            if (shell.attributionTag == null) {
                                if (ent.getLastAccessTime(OP_FLAGS_ALL) != -1) {
                                    pw.print("; time=");
                                    TimeUtils.formatDuration(
                                            now - ent.getLastAccessTime(OP_FLAGS_ALL), pw);
                                    pw.print(" ago");
                                }
                                if (ent.getLastRejectTime(OP_FLAGS_ALL) != -1) {
                                    pw.print("; rejectTime=");
                                    TimeUtils.formatDuration(
                                            now - ent.getLastRejectTime(OP_FLAGS_ALL), pw);
                                    pw.print(" ago");
                                }
                                if (ent.isRunning()) {
                                    pw.print(" (running)");
                                } else if (ent.getLastDuration(OP_FLAGS_ALL) != -1) {
                                    pw.print("; duration=");
                                    TimeUtils.formatDuration(ent.getLastDuration(OP_FLAGS_ALL), pw);
                                }
                            } else {
                                final AppOpsManager.AttributedOpEntry attributionEnt =
                                        ent.getAttributedOpEntries().get(shell.attributionTag);
                                if (attributionEnt != null) {
                                    if (attributionEnt.getLastAccessTime(OP_FLAGS_ALL) != -1) {
                                        pw.print("; time=");
                                        TimeUtils.formatDuration(
                                                now - attributionEnt.getLastAccessTime(
                                                        OP_FLAGS_ALL), pw);
                                        pw.print(" ago");
                                    }
                                    if (attributionEnt.getLastRejectTime(OP_FLAGS_ALL) != -1) {
                                        pw.print("; rejectTime=");
                                        TimeUtils.formatDuration(
                                                now - attributionEnt.getLastRejectTime(
                                                        OP_FLAGS_ALL), pw);
                                        pw.print(" ago");
                                    }
                                    if (attributionEnt.isRunning()) {
                                        pw.print(" (running)");
                                    } else if (attributionEnt.getLastDuration(OP_FLAGS_ALL)
                                            != -1) {
                                        pw.print("; duration=");
                                        TimeUtils.formatDuration(
                                                attributionEnt.getLastDuration(OP_FLAGS_ALL), pw);
                                    }
                                }
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
                    final long token = Binder.clearCallingIdentity();
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
                    final long token = Binder.clearCallingIdentity();
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
                        shell.mInterface.startOperation(shell.mToken, shell.op, shell.packageUid,
                                shell.packageName, shell.attributionTag, true, true,
                                "appops start shell command", true);
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
                        shell.mInterface.finishOperation(shell.mToken, shell.op, shell.packageUid,
                                shell.packageName, shell.attributionTag);
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
        pw.println("  --attributionTag [attributionTag]");
        pw.println("    Limit output to data associated with the given attribution tag.");
        pw.println("  --watchers");
        pw.println("    Only output the watcher sections.");
    }

    private void dumpStatesLocked(@NonNull PrintWriter pw, @Nullable String filterAttributionTag,
            @HistoricalOpsRequestFilter int filter, long nowElapsed, @NonNull Op op, long now,
            @NonNull SimpleDateFormat sdf, @NonNull Date date, @NonNull String prefix) {
        final int numAttributions = op.mAttributions.size();
        for (int i = 0; i < numAttributions; i++) {
            if ((filter & FILTER_BY_ATTRIBUTION_TAG) != 0 && !Objects.equals(
                    op.mAttributions.keyAt(i), filterAttributionTag)) {
                continue;
            }

            pw.print(prefix + op.mAttributions.keyAt(i) + "=[\n");
            dumpStatesLocked(pw, nowElapsed, op, op.mAttributions.keyAt(i), now, sdf, date,
                    prefix + "  ");
            pw.print(prefix + "]\n");
        }
    }

    private void dumpStatesLocked(@NonNull PrintWriter pw, long nowElapsed, @NonNull Op op,
            @Nullable String attributionTag, long now, @NonNull SimpleDateFormat sdf,
            @NonNull Date date, @NonNull String prefix) {

        final AttributedOpEntry entry = op.createSingleAttributionEntryLocked(
                attributionTag).getAttributedOpEntries().get(attributionTag);

        final ArraySet<Long> keys = entry.collectKeys();

        final int keyCount = keys.size();
        for (int k = 0; k < keyCount; k++) {
            final long key = keys.valueAt(k);

            final int uidState = AppOpsManager.extractUidStateFromKey(key);
            final int flags = AppOpsManager.extractFlagsFromKey(key);

            final long accessTime = entry.getLastAccessTime(uidState, uidState, flags);
            final long rejectTime = entry.getLastRejectTime(uidState, uidState, flags);
            final long accessDuration = entry.getLastDuration(uidState, uidState, flags);
            final OpEventProxyInfo proxy = entry.getLastProxyInfo(uidState, uidState, flags);

            String proxyPkg = null;
            String proxyAttributionTag = null;
            int proxyUid = Process.INVALID_UID;
            if (proxy != null) {
                proxyPkg = proxy.getPackageName();
                proxyAttributionTag = proxy.getAttributionTag();
                proxyUid = proxy.getUid();
            }

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
                    pw.print(", attributionTag=");
                    pw.print(proxyAttributionTag);
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
                    pw.print(", attributionTag=");
                    pw.print(proxyAttributionTag);
                    pw.print("]");
                }
                pw.println();
            }
        }

        final AttributedOp attributedOp = op.mAttributions.get(attributionTag);
        if (attributedOp.isRunning()) {
            long earliestElapsedTime = Long.MAX_VALUE;
            long maxNumStarts = 0;
            int numInProgressEvents = attributedOp.mInProgressEvents.size();
            for (int i = 0; i < numInProgressEvents; i++) {
                InProgressStartOpEvent event = attributedOp.mInProgressEvents.valueAt(i);

                earliestElapsedTime = Math.min(earliestElapsedTime, event.getStartElapsedTime());
                maxNumStarts = Math.max(maxNumStarts, event.numUnfinishedStarts);
            }

            pw.print(prefix + "Running start at: ");
            TimeUtils.formatDuration(nowElapsed - earliestElapsedTime, pw);
            pw.println();

            if (maxNumStarts > 1) {
                pw.print(prefix + "startNesting=");
                pw.println(maxNumStarts);
            }
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpAndUsageStatsPermission(mContext, TAG, pw)) return;

        int dumpOp = OP_NONE;
        String dumpPackage = null;
        String dumpAttributionTag = null;
        int dumpUid = Process.INVALID_UID;
        int dumpMode = -1;
        boolean dumpWatchers = false;
        // TODO ntmyren: Remove the dumpHistory and dumpFilter
        boolean dumpHistory = false;
        @HistoricalOpsRequestFilter int dumpFilter = 0;

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
                    dumpFilter |= FILTER_BY_OP_NAMES;
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
                    dumpFilter |= FILTER_BY_PACKAGE_NAME;
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
                    dumpFilter |= FILTER_BY_UID;
                } else if ("--attributionTag".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("No argument for --attributionTag option");
                        return;
                    }
                    dumpAttributionTag = args[i];
                    dumpFilter |= FILTER_BY_ATTRIBUTION_TAG;
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
            if (dumpFilter == 0 && dumpMode < 0 && mProfileOwners != null && !dumpWatchers
                    && !dumpHistory) {
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
                for (int watcherNum = 0; watcherNum < mActiveWatchers.size(); watcherNum++) {
                    final SparseArray<ActiveCallback> activeWatchers =
                            mActiveWatchers.valueAt(watcherNum);
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
                            mActiveWatchers.keyAt(watcherNum))));
                    pw.println(" ->");
                    pw.print("        [");
                    final int opCount = activeWatchers.size();
                    for (int opNum = 0; opNum < opCount; opNum++) {
                        if (opNum > 0) {
                            pw.print(' ');
                        }
                        pw.print(AppOpsManager.opToName(activeWatchers.keyAt(opNum)));
                        if (opNum < opCount - 1) {
                            pw.print(',');
                        }
                    }
                    pw.println("]");
                    pw.print("        ");
                    pw.println(cb);
                }
            }
            if (mStartedWatchers.size() > 0 && dumpMode < 0) {
                needSep = true;
                boolean printedHeader = false;

                final int watchersSize = mStartedWatchers.size();
                for (int watcherNum = 0; watcherNum < watchersSize; watcherNum++) {
                    final SparseArray<StartedCallback> startedWatchers =
                            mStartedWatchers.valueAt(watcherNum);
                    if (startedWatchers.size() <= 0) {
                        continue;
                    }

                    final StartedCallback cb = startedWatchers.valueAt(0);
                    if (dumpOp >= 0 && startedWatchers.indexOfKey(dumpOp) < 0) {
                        continue;
                    }

                    if (dumpPackage != null
                            && dumpUid != UserHandle.getAppId(cb.mWatchingUid)) {
                        continue;
                    }

                    if (!printedHeader) {
                        pw.println("  All op started watchers:");
                        printedHeader = true;
                    }

                    pw.print("    ");
                    pw.print(Integer.toHexString(System.identityHashCode(
                            mStartedWatchers.keyAt(watcherNum))));
                    pw.println(" ->");

                    pw.print("        [");
                    final int opCount = startedWatchers.size();
                    for (int opNum = 0; opNum < opCount; opNum++) {
                        if (opNum > 0) {
                            pw.print(' ');
                        }

                        pw.print(AppOpsManager.opToName(startedWatchers.keyAt(opNum)));
                        if (opNum < opCount - 1) {
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
                for (int watcherNum = 0; watcherNum < mNotedWatchers.size(); watcherNum++) {
                    final SparseArray<NotedCallback> notedWatchers =
                            mNotedWatchers.valueAt(watcherNum);
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
                            mNotedWatchers.keyAt(watcherNum))));
                    pw.println(" ->");
                    pw.print("        [");
                    final int opCount = notedWatchers.size();
                    for (int opNum = 0; opNum < opCount; opNum++) {
                        if (opNum > 0) {
                            pw.print(' ');
                        }
                        pw.print(AppOpsManager.opToName(notedWatchers.keyAt(opNum)));
                        if (opNum < opCount - 1) {
                            pw.print(',');
                        }
                    }
                    pw.println("]");
                    pw.print("        ");
                    pw.println(cb);
                }
            }
            if (mAudioRestrictionManager.hasActiveRestrictions() && dumpOp < 0
                    && dumpPackage != null && dumpMode < 0 && !dumpWatchers && !dumpWatchers) {
                needSep = mAudioRestrictionManager.dump(pw) | needSep ;
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
                    boolean hasPackage = dumpPackage == null || dumpUid == mUidStates.keyAt(i);
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
                pw.print("    capability=");
                ActivityManager.printCapabilitiesFull(pw, uidState.capability);
                pw.println();
                if (uidState.capability != uidState.pendingCapability) {
                    pw.print("    pendingCapability=");
                    ActivityManager.printCapabilitiesFull(pw, uidState.pendingCapability);
                    pw.println();
                }
                pw.print("    appWidgetVisible=");
                pw.println(uidState.appWidgetVisible);
                if (uidState.appWidgetVisible != uidState.pendingAppWidgetVisible) {
                    pw.print("    pendingAppWidgetVisible=");
                    pw.println(uidState.pendingAppWidgetVisible);
                }
                if (uidState.pendingStateCommitTime != 0) {
                    pw.print("    pendingStateCommitTime=");
                    TimeUtils.formatDuration(uidState.pendingStateCommitTime, nowElapsed, pw);
                    pw.println();
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
                        dumpStatesLocked(pw, dumpAttributionTag, dumpFilter, nowElapsed, op, now,
                                sdf, date, "        ");
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
            mHistoricalRegistry.dump("  ", pw, dumpUid, dumpPackage, dumpAttributionTag, dumpOp,
                    dumpFilter);
        }
    }

    @Override
    public void setUserRestrictions(Bundle restrictions, IBinder token, int userHandle) {
        checkSystemUid("setUserRestrictions");
        Objects.requireNonNull(restrictions);
        Objects.requireNonNull(token);
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
        Objects.requireNonNull(token);
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
        verifyIncomingPackage(packageName, UserHandle.getUserId(uid));

        final String resolvedPackageName = resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return false;
        }
        // TODO moltmann: Allow to check for attribution op activeness
        synchronized (AppOpsService.this) {
            Ops pkgOps = getOpsLocked(uid, resolvedPackageName, null, null, false);
            if (pkgOps == null) {
                return false;
            }

            Op op = pkgOps.get(code);
            if (op == null) {
                return false;
            }

            return op.isRunning();
        }
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
        mHistoricalRegistry.mDiscreteRegistry.clearHistory();
    }

    @Override
    public void rebootHistory(long offlineDurationMillis) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_APPOPS,
                "rebootHistory");

        Preconditions.checkArgument(offlineDurationMillis >= 0);

        // Must not hold the appops lock
        mHistoricalRegistry.shutdown();

        if (offlineDurationMillis > 0) {
            SystemClock.sleep(offlineDurationMillis);
        }

        mHistoricalRegistry = new HistoricalRegistry(mHistoricalRegistry);
        mHistoricalRegistry.systemReady(mContext.getContentResolver());
        mHistoricalRegistry.persistPendingHistory();
    }

    /**
     * Report runtime access to AppOp together with message (including stack trace)
     *
     * @param packageName The package which reported the op
     * @param notedAppOp contains code of op and attributionTag provided by developer
     * @param message Message describing AppOp access (can be stack trace)
     *
     * @return Config for future sampling to reduce amount of reporting
     */
    @Override
    public MessageSamplingConfig reportRuntimeAppOpAccessMessageAndGetConfig(
            String packageName, SyncNotedAppOp notedAppOp, String message) {
        int uid = Binder.getCallingUid();
        Objects.requireNonNull(packageName);
        synchronized (this) {
            switchPackageIfBootTimeOrRarelyUsedLocked(packageName);
            if (!packageName.equals(mSampledPackage)) {
                return new MessageSamplingConfig(OP_NONE, 0,
                        Instant.now().plus(1, ChronoUnit.HOURS).toEpochMilli());
            }

            Objects.requireNonNull(notedAppOp);
            Objects.requireNonNull(message);

            reportRuntimeAppOpAccessMessageInternalLocked(uid, packageName,
                    AppOpsManager.strOpToOp(notedAppOp.getOp()),
                    notedAppOp.getAttributionTag(), message);

            return new MessageSamplingConfig(mSampledAppOpCode, mAcceptableLeftDistance,
                    Instant.now().plus(1, ChronoUnit.HOURS).toEpochMilli());
        }
    }

    /**
     * Report runtime access to AppOp together with message (entry point for reporting
     * asynchronous access)
     * @param uid Uid of the package which reported the op
     * @param packageName The package which reported the op
     * @param opCode Code of AppOp
     * @param attributionTag FeautreId of AppOp reported
     * @param message Message describing AppOp access (can be stack trace)
     */
    private void reportRuntimeAppOpAccessMessageAsyncLocked(int uid,
            @NonNull String packageName, int opCode, @Nullable String attributionTag,
            @NonNull String message) {
        switchPackageIfBootTimeOrRarelyUsedLocked(packageName);
        if (!Objects.equals(mSampledPackage, packageName)) {
            return;
        }
        reportRuntimeAppOpAccessMessageInternalLocked(uid, packageName, opCode, attributionTag,
                message);
    }

    /**
     * Decides whether reported message is within the range of watched AppOps and picks it for
     * reporting uniformly at random across all received messages.
     */
    private void reportRuntimeAppOpAccessMessageInternalLocked(int uid,
            @NonNull String packageName, int opCode, @Nullable String attributionTag,
            @NonNull String message) {
        int newLeftDistance = AppOpsManager.leftCircularDistance(opCode,
                mSampledAppOpCode, _NUM_OP);

        if (mAcceptableLeftDistance < newLeftDistance
                && mSamplingStrategy != SAMPLING_STRATEGY_UNIFORM_OPS) {
            return;
        }

        if (mAcceptableLeftDistance > newLeftDistance
                && mSamplingStrategy != SAMPLING_STRATEGY_UNIFORM_OPS) {
            mAcceptableLeftDistance = newLeftDistance;
            mMessagesCollectedCount = 0.0f;
        }

        mMessagesCollectedCount += 1.0f;
        if (ThreadLocalRandom.current().nextFloat() <= 1.0f / mMessagesCollectedCount) {
            mCollectedRuntimePermissionMessage = new RuntimeAppOpAccessMessage(uid, opCode,
                    packageName, attributionTag, message, mSamplingStrategy);
        }
        return;
    }

    /** Pulls current AppOps access report and resamples package and app op to watch */
    @Override
    public @Nullable RuntimeAppOpAccessMessage collectRuntimeAppOpAccessMessage() {
        ActivityManagerInternal ami = LocalServices.getService(ActivityManagerInternal.class);
        boolean isCallerInstrumented = ami.isUidCurrentlyInstrumented(Binder.getCallingUid());
        boolean isCallerSystem = Binder.getCallingPid() == Process.myPid();
        if (!isCallerSystem && !isCallerInstrumented) {
            return null;
        }
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
        RuntimeAppOpAccessMessage result;
        synchronized (this) {
            result = mCollectedRuntimePermissionMessage;
            mCollectedRuntimePermissionMessage = null;
        }
        mHandler.sendMessage(PooledLambda.obtainMessage(
                AppOpsService::getPackageListAndResample,
                this));
        return result;
    }

    /**
     * Checks if package is in the list of rarely used package and starts watching the new package
     * to collect incoming message or if collection is happening in first minutes since boot.
     * @param packageName
     */
    private void switchPackageIfBootTimeOrRarelyUsedLocked(@NonNull String packageName) {
        if (mSampledPackage == null) {
            if (ThreadLocalRandom.current().nextFloat() < 0.5f) {
                mSamplingStrategy = SAMPLING_STRATEGY_BOOT_TIME_SAMPLING;
                resampleAppOpForPackageLocked(packageName, true);
            }
        } else if (mRarelyUsedPackages.contains(packageName)) {
            mRarelyUsedPackages.remove(packageName);
            if (ThreadLocalRandom.current().nextFloat() < 0.5f) {
                mSamplingStrategy = SAMPLING_STRATEGY_RARELY_USED;
                resampleAppOpForPackageLocked(packageName, true);
            }
        }
    }

    /** Obtains package list and resamples package and appop to watch. */
    private List<String> getPackageListAndResample() {
        List<String> packageNames = getPackageNamesForSampling();
        synchronized (this) {
            resamplePackageAndAppOpLocked(packageNames);
        }
        return packageNames;
    }

    /** Resamples package and appop to watch from the list provided. */
    private void resamplePackageAndAppOpLocked(@NonNull List<String> packageNames) {
        if (!packageNames.isEmpty()) {
            if (ThreadLocalRandom.current().nextFloat() < 0.5f) {
                mSamplingStrategy = SAMPLING_STRATEGY_UNIFORM;
                resampleAppOpForPackageLocked(packageNames.get(
                        ThreadLocalRandom.current().nextInt(packageNames.size())), true);
            } else {
                mSamplingStrategy = SAMPLING_STRATEGY_UNIFORM_OPS;
                resampleAppOpForPackageLocked(packageNames.get(
                        ThreadLocalRandom.current().nextInt(packageNames.size())), false);
            }
        }
    }

    /** Resamples appop for the chosen package and initializes sampling state */
    private void resampleAppOpForPackageLocked(@NonNull String packageName, boolean pickOp) {
        mMessagesCollectedCount = 0.0f;
        mSampledAppOpCode = pickOp ? ThreadLocalRandom.current().nextInt(_NUM_OP) : OP_NONE;
        mAcceptableLeftDistance = _NUM_OP - 1;
        mSampledPackage = packageName;
    }

    /**
     * Creates list of rarely used packages - packages which were not used over last week or
     * which declared but did not use permissions over last week.
     *  */
    private void initializeRarelyUsedPackagesList(@NonNull ArraySet<String> candidates) {
        AppOpsManager appOps = mContext.getSystemService(AppOpsManager.class);
        List<String> runtimeAppOpsList = getRuntimeAppOpsList();
        AppOpsManager.HistoricalOpsRequest histOpsRequest =
                new AppOpsManager.HistoricalOpsRequest.Builder(
                        Math.max(Instant.now().minus(7, ChronoUnit.DAYS).toEpochMilli(), 0),
                        Long.MAX_VALUE).setOpNames(runtimeAppOpsList).setFlags(
                        OP_FLAG_SELF | OP_FLAG_TRUSTED_PROXIED).build();
        appOps.getHistoricalOps(histOpsRequest, AsyncTask.THREAD_POOL_EXECUTOR,
                new Consumer<HistoricalOps>() {
                    @Override
                    public void accept(HistoricalOps histOps) {
                        int uidCount = histOps.getUidCount();
                        for (int uidIdx = 0; uidIdx < uidCount; uidIdx++) {
                            final AppOpsManager.HistoricalUidOps uidOps = histOps.getUidOpsAt(
                                    uidIdx);
                            int pkgCount = uidOps.getPackageCount();
                            for (int pkgIdx = 0; pkgIdx < pkgCount; pkgIdx++) {
                                String packageName = uidOps.getPackageOpsAt(
                                        pkgIdx).getPackageName();
                                if (!candidates.contains(packageName)) {
                                    continue;
                                }
                                AppOpsManager.HistoricalPackageOps packageOps =
                                        uidOps.getPackageOpsAt(pkgIdx);
                                if (packageOps.getOpCount() != 0) {
                                    candidates.remove(packageName);
                                }
                            }
                        }
                        synchronized (this) {
                            int numPkgs = mRarelyUsedPackages.size();
                            for (int i = 0; i < numPkgs; i++) {
                                candidates.add(mRarelyUsedPackages.valueAt(i));
                            }
                            mRarelyUsedPackages = candidates;
                        }
                    }
                });
    }

    /** List of app ops related to runtime permissions */
    private List<String> getRuntimeAppOpsList() {
        ArrayList<String> result = new ArrayList();
        for (int i = 0; i < _NUM_OP; i++) {
            if (shouldCollectNotes(i)) {
                result.add(opToPublicName(i));
            }
        }
        return result;
    }

    /** Returns list of packages to be used for package sampling */
    private @NonNull List<String> getPackageNamesForSampling() {
        List<String> packageNames = new ArrayList<>();
        PackageManagerInternal packageManagerInternal = LocalServices.getService(
                PackageManagerInternal.class);
        PackageList packages = packageManagerInternal.getPackageList();
        for (String packageName : packages.getPackageNames()) {
            PackageInfo pkg = packageManagerInternal.getPackageInfo(packageName,
                    PackageManager.GET_PERMISSIONS, Process.myUid(), mContext.getUserId());
            if (isSamplingTarget(pkg)) {
                packageNames.add(pkg.packageName);
            }
        }
        return packageNames;
    }

    /** Checks whether package should be included in sampling pool */
    private boolean isSamplingTarget(@Nullable PackageInfo pkg) {
        if (pkg == null) {
            return false;
        }
        String[] requestedPermissions = pkg.requestedPermissions;
        if (requestedPermissions == null) {
            return false;
        }
        for (String permission : requestedPermissions) {
            PermissionInfo permissionInfo;
            try {
                permissionInfo = mContext.getPackageManager().getPermissionInfo(permission, 0);
            } catch (PackageManager.NameNotFoundException ignored) {
                continue;
            }
            if (permissionInfo.getProtection() == PROTECTION_DANGEROUS) {
                return true;
            }
        }
        return false;
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
            case "dumpstate":
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
                // TODO(b/162888972): this call is returning all users, not just live ones - we
                // need to either fix the method called, or rename the variable
                List<UserInfo> liveUsers = UserManager.get(mContext).getUsers();

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
        public void updateAppWidgetVisibility(SparseArray<String> uidPackageNames,
                boolean visible) {
            AppOpsService.this.updateAppWidgetVisibility(uidPackageNames, visible);
        }

        @Override
        public void setUidModeFromPermissionPolicy(int code, int uid, int mode,
                @Nullable IAppOpsCallback callback) {
            setUidMode(code, uid, mode, callback);
        }

        @Override
        public void setModeFromPermissionPolicy(int code, int uid, @NonNull String packageName,
                int mode, @Nullable IAppOpsCallback callback) {
            setMode(code, uid, packageName, mode, callback);
        }
    }

    /**
     * Async task for writing note op stack trace, op code, package name and version to file
     * More specifically, writes all the collected ops from {@link #mNoteOpCallerStacktraces}
     */
    private void writeNoteOps() {
        synchronized (this) {
            mWriteNoteOpsScheduled = false;
        }
        synchronized (mNoteOpCallerStacktracesFile) {
            try (FileWriter writer = new FileWriter(mNoteOpCallerStacktracesFile)) {
                int numTraces = mNoteOpCallerStacktraces.size();
                for (int i = 0; i < numTraces; i++) {
                    // Writing json formatted string into file
                    writer.write(mNoteOpCallerStacktraces.valueAt(i).asJson());
                    // Comma separation, so we can wrap the entire log as a JSON object
                    // when all results are collected
                    writer.write(",");
                }
            } catch (IOException e) {
                Slog.w(TAG, "Failed to load opsValidation file for FileWriter", e);
            }
        }
    }

    /**
     * This class represents a NoteOp Trace object amd contains the necessary fields that will
     * be written to file to use for permissions data validation in JSON format
     */
    @Immutable
    static class NoteOpTrace {
        static final String STACKTRACE = "stackTrace";
        static final String OP = "op";
        static final String PACKAGENAME = "packageName";
        static final String VERSION = "version";

        private final @NonNull String mStackTrace;
        private final int mOp;
        private final @Nullable String mPackageName;
        private final long mVersion;

        /**
         * Initialize a NoteOp object using a JSON object containing the necessary fields
         *
         * @param jsonTrace JSON object represented as a string
         *
         * @return NoteOpTrace object initialized with JSON fields
         */
        static NoteOpTrace fromJson(String jsonTrace) {
            try {
                // Re-add closing bracket which acted as a delimiter by the reader
                JSONObject obj = new JSONObject(jsonTrace.concat("}"));
                return new NoteOpTrace(obj.getString(STACKTRACE), obj.getInt(OP),
                        obj.getString(PACKAGENAME), obj.getLong(VERSION));
            } catch (JSONException e) {
                // Swallow error, only meant for logging ops, should not affect flow of the code
                Slog.e(TAG, "Error constructing NoteOpTrace object "
                        + "JSON trace format incorrect", e);
                return null;
            }
        }

        NoteOpTrace(String stackTrace, int op, String packageName, long version) {
            mStackTrace = stackTrace;
            mOp = op;
            mPackageName = packageName;
            mVersion = version;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NoteOpTrace that = (NoteOpTrace) o;
            return mOp == that.mOp
                    && mVersion == that.mVersion
                    && mStackTrace.equals(that.mStackTrace)
                    && Objects.equals(mPackageName, that.mPackageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mStackTrace, mOp, mPackageName, mVersion);
        }

        /**
         * The object is formatted as a JSON object and returned as a String
         *
         * @return JSON formatted string
         */
        public String asJson() {
            return  "{"
                    + "\"" + STACKTRACE + "\":\"" + mStackTrace.replace("\n", "\\n")
                    + '\"' + ",\"" + OP + "\":" + mOp
                    + ",\"" + PACKAGENAME + "\":\"" + mPackageName + '\"'
                    + ",\"" + VERSION + "\":" + mVersion
                    + '}';
        }
    }

    /**
     * Collects noteOps, noteProxyOps and startOps from AppOpsManager and writes it into a file
     * which will be used for permissions data validation, the given parameters to this method
     * will be logged in json format
     *
     * @param stackTrace stacktrace from the most recent call in AppOpsManager
     * @param op op code
     * @param packageName package making call
     * @param version android version for this call
     */
    @Override
    public void collectNoteOpCallsForValidation(String stackTrace, int op, String packageName,
            long version) {
        if (!AppOpsManager.NOTE_OP_COLLECTION_ENABLED) {
            return;
        }

        Objects.requireNonNull(stackTrace);
        Preconditions.checkArgument(op >= 0);
        Preconditions.checkArgument(op < AppOpsManager._NUM_OP);
        Objects.requireNonNull(version);

        NoteOpTrace noteOpTrace = new NoteOpTrace(stackTrace, op, packageName, version);

        boolean noteOpSetWasChanged;
        synchronized (this) {
            noteOpSetWasChanged = mNoteOpCallerStacktraces.add(noteOpTrace);
            if (noteOpSetWasChanged && !mWriteNoteOpsScheduled) {
                mWriteNoteOpsScheduled = true;
                mHandler.postDelayed(PooledLambda.obtainRunnable((that) -> {
                    AsyncTask.execute(() -> {
                        that.writeNoteOps();
                    });
                }, this), 2500);
            }
        }
    }

    private final class CheckOpsDelegateDispatcher {
        private final @NonNull CheckOpsDelegate mCheckOpsDelegate;

        CheckOpsDelegateDispatcher(@NonNull CheckOpsDelegate checkOpsDelegate) {
            mCheckOpsDelegate = checkOpsDelegate;
        }

        public @NonNull CheckOpsDelegate getCheckOpsDelegate() {
            return mCheckOpsDelegate;
        }

        public int checkOperationImpl(int code, int uid, String packageName, boolean raw) {
            return mCheckOpsDelegate.checkOperation(code, uid, packageName, raw,
                    AppOpsService.this::checkOperationImpl);
        }

        public int checkAudioOperationImpl(int code, int usage, int uid, String packageName) {
            return mCheckOpsDelegate.checkAudioOperation(code, usage, uid, packageName,
                    AppOpsService.this::checkAudioOperationImpl);
        }

        public int noteOperationImpl(int code, int uid, @Nullable String packageName,
                @Nullable String featureId, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage) {
            return mCheckOpsDelegate.noteOperation(code, uid, packageName, featureId,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    AppOpsService.this::noteOperationImpl);
        }
    }
}
