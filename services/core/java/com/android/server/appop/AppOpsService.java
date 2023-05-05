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

import static android.app.AppOpsManager.ATTRIBUTION_CHAIN_ID_NONE;
import static android.app.AppOpsManager.ATTRIBUTION_FLAG_TRUSTED;
import static android.app.AppOpsManager.CALL_BACK_ON_SWITCHED_OP;
import static android.app.AppOpsManager.FILTER_BY_ATTRIBUTION_TAG;
import static android.app.AppOpsManager.FILTER_BY_OP_NAMES;
import static android.app.AppOpsManager.FILTER_BY_PACKAGE_NAME;
import static android.app.AppOpsManager.FILTER_BY_UID;
import static android.app.AppOpsManager.HISTORY_FLAG_GET_ATTRIBUTION_CHAINS;
import static android.app.AppOpsManager.HistoricalOpsRequestFilter;
import static android.app.AppOpsManager.KEY_BG_STATE_SETTLE_TIME;
import static android.app.AppOpsManager.KEY_FG_SERVICE_STATE_SETTLE_TIME;
import static android.app.AppOpsManager.KEY_TOP_STATE_SETTLE_TIME;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_DEFAULT;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_CAMERA_SANDBOXED;
import static android.app.AppOpsManager.OP_FLAGS_ALL;
import static android.app.AppOpsManager.OP_FLAG_SELF;
import static android.app.AppOpsManager.OP_FLAG_TRUSTED_PROXIED;
import static android.app.AppOpsManager.OP_NONE;
import static android.app.AppOpsManager.OP_PLAY_AUDIO;
import static android.app.AppOpsManager.OP_RECEIVE_AMBIENT_TRIGGER_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO_HOTWORD;
import static android.app.AppOpsManager.OP_RECORD_AUDIO_SANDBOXED;
import static android.app.AppOpsManager.OP_RUN_ANY_IN_BACKGROUND;
import static android.app.AppOpsManager.OP_VIBRATE;
import static android.app.AppOpsManager.OnOpStartedListener.START_TYPE_FAILED;
import static android.app.AppOpsManager.OnOpStartedListener.START_TYPE_STARTED;
import static android.app.AppOpsManager.OpEventProxyInfo;
import static android.app.AppOpsManager.RestrictionBypass;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_BOOT_TIME_SAMPLING;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_RARELY_USED;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_UNIFORM;
import static android.app.AppOpsManager.SAMPLING_STRATEGY_UNIFORM_OPS;
import static android.app.AppOpsManager.SECURITY_EXCEPTION_ON_INVALID_ATTRIBUTION_TAG_CHANGE;
import static android.app.AppOpsManager._NUM_OP;
import static android.app.AppOpsManager.extractFlagsFromKey;
import static android.app.AppOpsManager.extractUidStateFromKey;
import static android.app.AppOpsManager.modeToName;
import static android.app.AppOpsManager.opAllowSystemBypassRestriction;
import static android.app.AppOpsManager.opRestrictsRead;
import static android.app.AppOpsManager.opToName;
import static android.app.AppOpsManager.opToPublicName;
import static android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
import static android.content.pm.PermissionInfo.PROTECTION_FLAG_APPOP;

import static com.android.server.appop.AppOpsService.ModeCallback.ALL_OPS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.app.AppOpsManager.AttributedOpEntry;
import android.app.AppOpsManager.AttributionFlags;
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
import android.content.AttributionSource;
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
import android.database.ContentObserver;
import android.hardware.camera2.CameraDevice.CAMERA_AUDIO_RESTRICTION;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.IBinder;
import android.os.PackageTagsList;
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
import android.os.storage.StorageManagerInternal;
import android.permission.PermissionManager;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.KeyValueListParser;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import android.util.TimeUtils;
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
import com.android.internal.os.Clock;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.LockGuard;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemServiceManager;
import com.android.server.pm.PackageList;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.component.ParsedAttribution;
import com.android.server.policy.AppOpsPolicy;

import dalvik.annotation.optimization.NeverCompile;

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
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class AppOpsService extends IAppOpsService.Stub {
    static final String TAG = "AppOps";
    static final boolean DEBUG = false;

    /**
     * Used for data access validation collection, we wish to only log a specific access once
     */
    private final ArraySet<NoteOpTrace> mNoteOpCallerStacktraces = new ArraySet<>();

    /**
     * Version of the mRecentAccessesFile.
     * Increment by one every time an upgrade step is added at boot, none currently exists.
     */
    private static final int CURRENT_VERSION = 1;

    // Write at most every 30 minutes.
    static final long WRITE_DELAY = DEBUG ? 1000 : 30*60*1000;

    // Constant meaning that any UID should be matched when dispatching callbacks
    private static final int UID_ANY = -2;

    private static final int[] OPS_RESTRICTED_ON_SUSPEND = {
            OP_PLAY_AUDIO,
            OP_RECORD_AUDIO,
            OP_CAMERA,
            OP_VIBRATE,
    };

    private static final int MAX_UNFORWARDED_OPS = 10;
    private static final int MAX_UNUSED_POOLED_OBJECTS = 3;
    private static final int RARELY_USED_PACKAGES_INITIALIZATION_DELAY_MILLIS = 300000;

    final Context mContext;
    final AtomicFile mStorageFile;
    final AtomicFile mRecentAccessesFile;
    private final @Nullable File mNoteOpCallerStacktracesFile;
    final Handler mHandler;

    /**
     * Pool for {@link AttributedOp.OpEventProxyInfoPool} to avoid to constantly reallocate new
     * objects
     */
    @GuardedBy("this")
    final AttributedOp.OpEventProxyInfoPool mOpEventProxyInfoPool =
            new AttributedOp.OpEventProxyInfoPool(MAX_UNUSED_POOLED_OBJECTS);

    /**
     * Pool for {@link AttributedOp.InProgressStartOpEventPool} to avoid to constantly reallocate
     * new objects
     */
    @GuardedBy("this")
    final AttributedOp.InProgressStartOpEventPool mInProgressStartOpEventPool =
            new AttributedOp.InProgressStartOpEventPool(mOpEventProxyInfoPool,
                    MAX_UNUSED_POOLED_OBJECTS);

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
                        writeRecentAccesses();
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

    /*
     * These are app op restrictions imposed per user from various parties.
     */
    private final ArrayMap<IBinder, ClientUserRestrictionState> mOpUserRestrictions =
            new ArrayMap<>();

    /*
     * These are app op restrictions imposed globally from various parties within the system.
     */
    private final ArrayMap<IBinder, ClientGlobalRestrictionState> mOpGlobalRestrictions =
            new ArrayMap<>();

    SparseIntArray mProfileOwners;

    private volatile CheckOpsDelegateDispatcher mCheckOpsDelegateDispatcher =
            new CheckOpsDelegateDispatcher(/*policy*/ null, /*delegate*/ null);

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

    /** Package Manager local. Access via {@link #getPackageManagerLocal()} */
    private @Nullable PackageManagerLocal mPackageManagerLocal;

    /** User Manager internal. Access via {@link #getUserManagerInternal()} */
    private @Nullable UserManagerInternal mUserManagerInternal;

    /** Interface for app-op modes.*/
    @VisibleForTesting
    AppOpsCheckingServiceInterface mAppOpsCheckingService;

    /** Interface for app-op restrictions.*/
    @VisibleForTesting AppOpsRestrictions mAppOpsRestrictions;

    private AppOpsUidStateTracker mUidStateTracker;

    /** Hands the definition of foreground and uid states */
    @GuardedBy("this")
    public AppOpsUidStateTracker getUidStateTracker() {
        if (mUidStateTracker == null) {
            mUidStateTracker = new AppOpsUidStateTrackerImpl(
                    LocalServices.getService(ActivityManagerInternal.class),
                    mHandler,
                    r -> {
                        synchronized (AppOpsService.this) {
                            r.run();
                        }
                    },
                    Clock.SYSTEM_CLOCK, mConstants);

            mUidStateTracker.addUidStateChangedCallback(new HandlerExecutor(mHandler),
                    this::onUidStateChanged);
        }
        return mUidStateTracker;
    }

    /**
     * All times are in milliseconds. These constants are kept synchronized with the system
     * global Settings. Any access to this class or its fields should be done while
     * holding the AppOpsService lock.
     */
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

        @NonNull
        public final ArrayMap<String, Ops> pkgOps = new ArrayMap<>();

        // true indicates there is an interested observer, false there isn't but it has such an op
        //TODO: Move foregroundOps and hasForegroundWatchers into the AppOpsServiceInterface.
        public SparseBooleanArray foregroundOps;
        public boolean hasForegroundWatchers;

        public UidState(int uid) {
            this.uid = uid;
        }

        public void clear() {
            mAppOpsCheckingService.removeUid(uid);
            for (int i = 0; i < pkgOps.size(); i++) {
                String packageName = pkgOps.keyAt(i);
                mAppOpsCheckingService.removePackage(packageName, UserHandle.getUserId(uid));
            }
        }

        // Functions for uid mode access and manipulation.
        public SparseIntArray getNonDefaultUidModes() {
            return mAppOpsCheckingService.getNonDefaultUidModes(uid);
        }

        public int getUidMode(int op) {
            return mAppOpsCheckingService.getUidMode(uid, op);
        }

        public boolean setUidMode(int op, int mode) {
            return mAppOpsCheckingService.setUidMode(uid, op, mode);
        }

        @SuppressWarnings("GuardedBy")
        int evalMode(int op, int mode) {
            return getUidStateTracker().evalMode(uid, op, mode);
        }

        public void evalForegroundOps() {
            foregroundOps = null;
            foregroundOps = mAppOpsCheckingService.evalForegroundUidOps(uid, foregroundOps);
            for (int i = pkgOps.size() - 1; i >= 0; i--) {
                foregroundOps = mAppOpsCheckingService
                        .evalForegroundPackageOps(pkgOps.valueAt(i).packageName, foregroundOps,
                                UserHandle.getUserId(uid));
            }
            hasForegroundWatchers = false;
            if (foregroundOps != null) {
                for (int i = 0;  i < foregroundOps.size(); i++) {
                    if (foregroundOps.valueAt(i)) {
                        hasForegroundWatchers = true;
                        break;
                    }
                }
            }
        }

        @SuppressWarnings("GuardedBy")
        public int getState() {
            return getUidStateTracker().getUidState(uid);
        }

        @SuppressWarnings("GuardedBy")
        public void dump(PrintWriter pw, long nowElapsed) {
            getUidStateTracker().dumpUidState(pw, uid, nowElapsed);
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

        /**
         * Lazily populated cache of <b>valid</b> attributionTags of this package, a set smaller
         * than or equal to {@link #knownAttributionTags}.
         */
        final @NonNull ArraySet<String> validAttributionTags = new ArraySet<>();

        Ops(String _packageName, UidState _uidState) {
            packageName = _packageName;
            uidState = _uidState;
        }
    }

    /** Returned from {@link #verifyAndGetBypass(int, String, String, String, boolean)}. */
    private static final class PackageVerificationResult {

        final RestrictionBypass bypass;
        final boolean isAttributionTagValid;

        PackageVerificationResult(RestrictionBypass bypass, boolean isAttributionTagValid) {
            this.bypass = bypass;
            this.isAttributionTagValid = isAttributionTagValid;
        }
    }

    final class Op {
        int op;
        int uid;
        final UidState uidState;
        final @NonNull String packageName;

        /** attributionTag -> AttributedOp */
        final ArrayMap<String, AttributedOp> mAttributions = new ArrayMap<>(1);

        Op(UidState uidState, String packageName, int op, int uid) {
            this.op = op;
            this.uid = uid;
            this.uidState = uidState;
            this.packageName = packageName;
        }

        @Mode int getMode() {
            return mAppOpsCheckingService.getPackageMode(packageName, this.op,
                    UserHandle.getUserId(this.uid));
        }
        void setMode(@Mode int mode) {
            mAppOpsCheckingService.setPackageMode(packageName, this.op, mode,
                    UserHandle.getUserId(this.uid));
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
                attributedOp = new AttributedOp(AppOpsService.this, attributionTag, parent);
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

            return new OpEntry(op, getMode(), attributionEntries);
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

            return new OpEntry(op, getMode(), attributionEntries);
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

    final ArrayMap<IBinder, ModeCallback> mModeWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<ActiveCallback>> mActiveWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<StartedCallback>> mStartedWatchers = new ArrayMap<>();
    final ArrayMap<IBinder, SparseArray<NotedCallback>> mNotedWatchers = new ArrayMap<>();
    final AudioRestrictionManager mAudioRestrictionManager = new AudioRestrictionManager();

    final class ModeCallback extends OnOpModeChangedListener implements DeathRecipient  {
        /** If mWatchedOpCode==ALL_OPS notify for ops affected by the switch-op */
        public static final int ALL_OPS = -2;

        // Need to keep this only because stopWatchingMode needs an IAppOpsCallback.
        // Otherwise we can just use the IBinder object.
        private final IAppOpsCallback mCallback;

        ModeCallback(IAppOpsCallback callback, int watchingUid, int flags, int watchedOpCode,
                int callingUid, int callingPid) {
            super(watchingUid, flags, watchedOpCode, callingUid, callingPid);
            this.mCallback = callback;
            try {
                mCallback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                /*ignored*/
            }
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ModeCallback{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(" watchinguid=");
            UserHandle.formatUid(sb, getWatchingUid());
            sb.append(" flags=0x");
            sb.append(Integer.toHexString(getFlags()));
            switch (getWatchedOpCode()) {
                case OP_NONE:
                    break;
                case ALL_OPS:
                    sb.append(" op=(all)");
                    break;
                default:
                    sb.append(" op=");
                    sb.append(opToName(getWatchedOpCode()));
                    break;
            }
            sb.append(" from uid=");
            UserHandle.formatUid(sb, getCallingUid());
            sb.append(" pid=");
            sb.append(getCallingPid());
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

        @Override
        public void onOpModeChanged(int op, int uid, String packageName) throws RemoteException {
            mCallback.opChanged(op, uid, packageName);
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
    static void onClientDeath(@NonNull AttributedOp attributedOp,
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

    @VisibleForTesting
    public AppOpsService(File recentAccessesFile, File storageFile, Handler handler,
            Context context) {
        mContext = context;

        for (int switchedCode = 0; switchedCode < _NUM_OP; switchedCode++) {
            int switchCode = AppOpsManager.opToSwitch(switchedCode);
            mSwitchedOps.put(switchCode,
                    ArrayUtils.appendInt(mSwitchedOps.get(switchCode), switchedCode));
        }
        mAppOpsCheckingService = new AppOpsCheckingServiceTracingDecorator(
                new AppOpsCheckingServiceImpl(
                        storageFile, this, handler, context,  mSwitchedOps));
        //mAppOpsCheckingService = new AppOpsCheckingServiceLoggingDecorator(
        //        LocalServices.getService(AppOpsCheckingServiceInterface.class));
        mAppOpsRestrictions = new AppOpsRestrictionsImpl(context, handler, mAppOpsCheckingService);

        LockGuard.installLock(this, LockGuard.INDEX_APP_OPS);
        mStorageFile = new AtomicFile(storageFile, "appops_legacy");
        mRecentAccessesFile = new AtomicFile(recentAccessesFile, "appops_accesses");

        if (AppOpsManager.NOTE_OP_COLLECTION_ENABLED) {
            mNoteOpCallerStacktracesFile = new File(SystemServiceManager.ensureSystemDir(),
                    "noteOpStackTraces.json");
            readNoteOpCallerStackTraces();
        } else {
            mNoteOpCallerStacktracesFile = null;
        }
        mHandler = handler;
        mConstants = new Constants(mHandler);
        // To migrate storageFile to recentAccessesFile, these reads must be called in this order.
        readRecentAccesses();
        mAppOpsCheckingService.readState();
    }

    public void publish() {
        ServiceManager.addService(Context.APP_OPS_SERVICE, asBinder());
        LocalServices.addService(AppOpsManagerInternal.class, mAppOpsManagerInternal);
        LocalManagerRegistry.addManager(AppOpsManagerLocal.class, new AppOpsManagerLocalImpl());
    }

    /** Handler for work when packages are updated */
    private BroadcastReceiver mOnPackageUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String pkgName = intent.getData().getEncodedSchemeSpecificPart();
            int uid = intent.getIntExtra(Intent.EXTRA_UID, Process.INVALID_UID);

            if (action.equals(Intent.ACTION_PACKAGE_REPLACED)) {
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
                        attributionTags.add(attribution.getTag());

                        int numInheritFrom = attribution.getInheritFrom().size();
                        for (int inheritFromNum = 0; inheritFromNum < numInheritFrom;
                                inheritFromNum++) {
                            dstAttributionTags.put(attribution.getInheritFrom().get(inheritFromNum),
                                    attribution.getTag());
                        }
                    }
                }

                synchronized (AppOpsService.this) {
                    UidState uidState = mUidStates.get(uid);
                    if (uidState == null) {
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
        mAppOpsCheckingService.systemReady();
        initializeUidStates();

        mConstants.startMonitoring(mContext.getContentResolver());
        mHistoricalRegistry.systemReady(mContext.getContentResolver());

        IntentFilter packageUpdateFilter = new IntentFilter();
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

        getUserManagerInternal().addUserLifecycleListener(
                new UserManagerInternal.UserLifecycleListener() {
                    @Override
                    public void onUserCreated(UserInfo user, Object token) {
                        initializeUserUidStates(user.id);
                    }

                    // onUserRemoved handled by #removeUser
                });

        getPackageManagerInternal().getPackageList(
                new PackageManagerInternal.PackageListObserver() {
                    @Override
                    public void onPackageAdded(String packageName, int appId) {
                        PackageInfo pi = getPackageManagerInternal().getPackageInfo(packageName,
                                PackageManager.GET_PERMISSIONS, Process.myUid(),
                                mContext.getUserId());
                        boolean isSamplingTarget = isSamplingTarget(pi);
                        int[] userIds = getUserManagerInternal().getUserIds();
                        synchronized (AppOpsService.this) {
                            if (isSamplingTarget) {
                                mRarelyUsedPackages.add(packageName);
                            }
                            for (int i = 0; i < userIds.length; i++) {
                                int uid = UserHandle.getUid(userIds[i], appId);
                                UidState uidState = getUidStateLocked(uid, true);
                                if (!uidState.pkgOps.containsKey(packageName)) {
                                    uidState.pkgOps.put(packageName,
                                            new Ops(packageName, uidState));
                                }
                            }
                        }
                    }

                    @Override
                    public void onPackageRemoved(String packageName, int appId) {
                        int[] userIds = getUserManagerInternal().getUserIds();
                        synchronized (AppOpsService.this) {
                            for (int i = 0; i < userIds.length; i++) {
                                int uid = UserHandle.getUid(userIds[i], appId);
                                packageRemovedLocked(uid, packageName);
                            }
                        }
                    }
                });

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
                    ArraySet<OnOpModeChangedListener> onModeChangedListeners;
                    synchronized (AppOpsService.this) {
                        onModeChangedListeners =
                                mAppOpsCheckingService.getOpModeChangedListeners(code);
                        if (onModeChangedListeners == null) {
                            continue;
                        }
                    }
                    for (int i = 0; i < changedUids.length; i++) {
                        final int changedUid = changedUids[i];
                        final String changedPkg = changedPkgs[i];
                        // We trust packagemanager to insert matching uid and packageNames in the
                        // extras
                        notifyOpChanged(onModeChangedListeners, code, changedUid, changedPkg);
                    }
                }
            }
        }, UserHandle.ALL, packageSuspendFilter, null, null);

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
     * Initialize uid state objects for state contained in the checking service.
     */
    @VisibleForTesting
    void initializeUidStates() {
        UserManagerInternal umi = getUserManagerInternal();
        synchronized (this) {
            int[] userIds = umi.getUserIds();
            try (PackageManagerLocal.UnfilteredSnapshot snapshot =
                         getPackageManagerLocal().withUnfilteredSnapshot()) {
                Map<String, PackageState> packageStates = snapshot.getPackageStates();
                for (int i = 0; i < userIds.length; i++) {
                    int userId = userIds[i];
                    initializeUserUidStatesLocked(userId, packageStates);
                }
            }
        }
    }

    private void initializeUserUidStates(int userId) {
        synchronized (this) {
            try (PackageManagerLocal.UnfilteredSnapshot snapshot =
                    getPackageManagerLocal().withUnfilteredSnapshot()) {
                initializeUserUidStatesLocked(userId, snapshot.getPackageStates());
            }
        }
    }

    private void initializeUserUidStatesLocked(int userId, Map<String,
            PackageState> packageStates) {
        for (Map.Entry<String, PackageState> entry : packageStates.entrySet()) {
            int appId = entry.getValue().getAppId();
            String packageName = entry.getKey();

            initializePackageUidStateLocked(userId, appId, packageName);
        }
    }

    /*
      Be careful not to clear any existing data; only want to add objects that don't already exist.
     */
    private void initializePackageUidStateLocked(int userId, int appId, String packageName) {
        int uid = UserHandle.getUid(userId, appId);
        UidState uidState = getUidStateLocked(uid, true);
        Ops ops = uidState.pkgOps.get(packageName);
        if (ops == null) {
            ops = new Ops(packageName, uidState);
            uidState.pkgOps.put(packageName, ops);
        }

        SparseIntArray packageModes =
                mAppOpsCheckingService.getNonDefaultPackageModes(packageName, userId);
        for (int k = 0; k < packageModes.size(); k++) {
            int code = packageModes.keyAt(k);

            if (ops.indexOfKey(code) < 0) {
                ops.put(code, new Op(uidState, packageName, code, uid));
            }
        }

        uidState.evalForegroundOps();
    }

    /**
     * Sets a policy for handling app ops.
     *
     * @param policy The policy.
     */
    public void setAppOpsPolicy(@Nullable CheckOpsDelegate policy) {
        final CheckOpsDelegateDispatcher oldDispatcher = mCheckOpsDelegateDispatcher;
        final CheckOpsDelegate delegate = (oldDispatcher != null)
                ? oldDispatcher.mCheckOpsDelegate : null;
        mCheckOpsDelegateDispatcher = new CheckOpsDelegateDispatcher(policy, delegate);
    }

    @VisibleForTesting
    void packageRemoved(int uid, String packageName) {
        synchronized (this) {
            packageRemovedLocked(uid, packageName);
        }
    }

    @GuardedBy("this")
    private void packageRemovedLocked(int uid, String packageName) {
        UidState uidState = mUidStates.get(uid);
        if (uidState == null) {
            return;
        }

        Ops removedOps = null;

        // Remove any package state if such.
        removedOps = uidState.pkgOps.remove(packageName);
        mAppOpsCheckingService.removePackage(packageName, UserHandle.getUserId(uid));

        if (removedOps != null) {
            scheduleFastWriteLocked();

            final int numOps = removedOps.size();
            for (int opNum = 0; opNum < numOps; opNum++) {
                final Op op = removedOps.valueAt(opNum);

                final int numAttributions = op.mAttributions.size();
                for (int attributionNum = 0; attributionNum < numAttributions;
                        attributionNum++) {
                    AttributedOp attributedOp = op.mAttributions.valueAt(attributionNum);

                    while (attributedOp.isRunning()) {
                        attributedOp.finished(attributedOp.mInProgressEvents.keyAt(0));
                    }
                    while (attributedOp.isPaused()) {
                        attributedOp.finished(attributedOp.mPausedInProgressEvents.keyAt(0));
                    }
                }
            }
        }

        mHandler.post(PooledLambda.obtainRunnable(HistoricalRegistry::clearHistory,
                    mHistoricalRegistry, uid, packageName));
    }

    public void uidRemoved(int uid) {
        synchronized (this) {
            if (mUidStates.indexOfKey(uid) >= 0) {
                mUidStates.get(uid).clear();
                mUidStates.remove(uid);
                scheduleFastWriteLocked();
            }
        }
    }

    // The callback method from AppOpsUidStateTracker
    private void onUidStateChanged(int uid, int state, boolean foregroundModeMayChange) {
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, true);

            if (uidState != null && foregroundModeMayChange && uidState.hasForegroundWatchers) {
                for (int fgi = uidState.foregroundOps.size() - 1; fgi >= 0; fgi--) {
                    if (!uidState.foregroundOps.valueAt(fgi)) {
                        continue;
                    }
                    final int code = uidState.foregroundOps.keyAt(fgi);

                    if (uidState.getUidMode(code) != AppOpsManager.opToDefaultMode(code)
                            && uidState.getUidMode(code) == AppOpsManager.MODE_FOREGROUND) {
                        mHandler.sendMessage(PooledLambda.obtainMessage(
                                AppOpsService::notifyOpChangedForAllPkgsInUid,
                                this, code, uidState.uid, true, null));
                    } else if (!uidState.pkgOps.isEmpty()) {
                        final ArraySet<OnOpModeChangedListener> listenerSet =
                                mAppOpsCheckingService.getOpModeChangedListeners(code);
                        if (listenerSet != null) {
                            for (int cbi = listenerSet.size() - 1; cbi >= 0; cbi--) {
                                final OnOpModeChangedListener listener = listenerSet.valueAt(cbi);
                                if ((listener.getFlags()
                                        & AppOpsManager.WATCH_FOREGROUND_CHANGES) == 0
                                        || !listener.isWatchingUid(uidState.uid)) {
                                    continue;
                                }
                                for (int pkgi = uidState.pkgOps.size() - 1; pkgi >= 0; pkgi--) {
                                    final Op op = uidState.pkgOps.valueAt(pkgi).get(code);
                                    if (op == null) {
                                        continue;
                                    }
                                    if (op.getMode() == AppOpsManager.MODE_FOREGROUND) {
                                        mHandler.sendMessage(PooledLambda.obtainMessage(
                                                AppOpsService::notifyOpChanged,
                                                this, listenerSet.valueAt(cbi), code, uidState.uid,
                                                uidState.pkgOps.keyAt(pkgi)));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (uidState != null) {
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

                            attributedOp.onUidStateChanged(state);
                        }
                    }
                }
            }
        }
    }

    /**
     * Notify the proc state or capability has changed for a certain UID.
     */
    public void updateUidProcState(int uid, int procState,
            @ActivityManager.ProcessCapability int capability) {
        synchronized (this) {
            getUidStateTracker().updateUidProcState(uid, procState, capability);
            if (!mUidStates.contains(uid)) {
                UidState uidState = new UidState(uid);
                mUidStates.put(uid, uidState);
                onUidStateChanged(uid,
                        AppOpsUidStateTracker.processStateToUidState(procState), false);
            }
        }
    }

    public void shutdown() {
        Slog.w(TAG, "Writing app ops before shutdown...");
        boolean doWrite = false;
        synchronized (this) {
            if (mWriteScheduled) {
                mWriteScheduled = false;
                mFastWriteScheduled = false;
                mHandler.removeCallbacks(mWriteRunner);
                doWrite = true;
            }
        }
        if (doWrite) {
            writeRecentAccesses();
        }
        mAppOpsCheckingService.shutdown();
        if (AppOpsManager.NOTE_OP_COLLECTION_ENABLED && mWriteNoteOpsScheduled) {
            writeNoteOps();
        }
        mHistoricalRegistry.shutdown();
    }

    private ArrayList<AppOpsManager.OpEntry> collectOps(Ops pkgOps, int[] ops) {
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        if (ops == null) {
            resOps = new ArrayList<>();
            for (int j=0; j<pkgOps.size(); j++) {
                Op curOp = pkgOps.valueAt(j);
                resOps.add(getOpEntryForResult(curOp));
            }
        } else {
            for (int j=0; j<ops.length; j++) {
                Op curOp = pkgOps.get(ops[j]);
                if (curOp != null) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    resOps.add(getOpEntryForResult(curOp));
                }
            }
        }
        return resOps;
    }

    @Nullable
    private ArrayList<AppOpsManager.OpEntry> collectUidOps(@NonNull UidState uidState,
            @Nullable int[] ops) {
        final SparseIntArray opModes = uidState.getNonDefaultUidModes();
        if (opModes == null) {
            return null;
        }

        int opModeCount = opModes.size();
        if (opModeCount == 0) {
            return null;
        }
        ArrayList<AppOpsManager.OpEntry> resOps = null;
        if (ops == null) {
            resOps = new ArrayList<>();
            for (int i = 0; i < opModeCount; i++) {
                int code = opModes.keyAt(i);
                resOps.add(new OpEntry(code, opModes.get(code), Collections.emptyMap()));
            }
        } else {
            for (int j=0; j<ops.length; j++) {
                int code = ops[j];
                if (opModes.indexOfKey(code) >= 0) {
                    if (resOps == null) {
                        resOps = new ArrayList<>();
                    }
                    resOps.add(new OpEntry(code, opModes.get(code), Collections.emptyMap()));
                }
            }
        }
        return resOps;
    }

    private static @NonNull OpEntry getOpEntryForResult(@NonNull Op op) {
        return op.createEntryLocked();
    }

    @Override
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        final int callingUid = Binder.getCallingUid();
        final boolean hasAllPackageAccess = mContext.checkPermission(
                Manifest.permission.GET_APP_OPS_STATS, Binder.getCallingPid(),
                Binder.getCallingUid(), null) == PackageManager.PERMISSION_GRANTED;
        ArrayList<AppOpsManager.PackageOps> res = null;
        synchronized (this) {
            final int uidStateCount = mUidStates.size();
            for (int i = 0; i < uidStateCount; i++) {
                UidState uidState = mUidStates.valueAt(i);
                if (uidState.pkgOps.isEmpty()) {
                    continue;
                }
                ArrayMap<String, Ops> packages = uidState.pkgOps;
                final int packageCount = packages.size();
                for (int j = 0; j < packageCount; j++) {
                    Ops pkgOps = packages.valueAt(j);
                    ArrayList<AppOpsManager.OpEntry> resOps = collectOps(pkgOps, ops);
                    if (resOps != null) {
                        if (res == null) {
                            res = new ArrayList<>();
                        }
                        AppOpsManager.PackageOps resPackage = new AppOpsManager.PackageOps(
                                pkgOps.packageName, pkgOps.uidState.uid, resOps);
                        // Caller can always see their packages and with a permission all.
                        if (hasAllPackageAccess || callingUid == pkgOps.uidState.uid) {
                            res.add(resPackage);
                        }
                    }
                }
            }
        }
        return res;
    }

    @Override
    public List<AppOpsManager.PackageOps> getOpsForPackage(int uid, String packageName,
            int[] ops) {
        enforceGetAppOpsStatsPermissionIfNeeded(uid,packageName);
        String resolvedPackageName = AppOpsManager.resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return Collections.emptyList();
        }
        synchronized (this) {
            Ops pkgOps = getOpsLocked(uid, resolvedPackageName, null, false, null,
                    /* edit */ false);
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

    private void enforceGetAppOpsStatsPermissionIfNeeded(int uid, String packageName) {
        // We get to access everything
        final int callingPid = Binder.getCallingPid();
        if (callingPid == Process.myPid()) {
            return;
        }
        // Apps can access their own data
        final int callingUid = Binder.getCallingUid();
        if (uid == callingUid && packageName != null
                && checkPackage(uid, packageName) == MODE_ALLOWED) {
            return;
        }
        // Otherwise, you need a permission...
        mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS, callingPid,
                callingUid, null);
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
        boolean isSelfRequest = (filter & FILTER_BY_UID) != 0 && uid == Binder.getCallingUid();
        if (!isSelfRequest) {
            boolean isCallerInstrumented =
                    ami.getInstrumentationSourceUid(Binder.getCallingUid()) != Process.INVALID_UID;
            boolean isCallerSystem = Binder.getCallingPid() == Process.myPid();
            boolean isCallerPermissionController;
            try {
                isCallerPermissionController = pm.getPackageUidAsUser(
                        mContext.getPackageManager().getPermissionControllerPackageName(), 0,
                        UserHandle.getUserId(Binder.getCallingUid()))
                        == Binder.getCallingUid();
            } catch (PackageManager.NameNotFoundException doesNotHappen) {
                return;
            }

            boolean doesCallerHavePermission = mContext.checkPermission(
                    android.Manifest.permission.GET_HISTORICAL_APP_OPS_STATS,
                    Binder.getCallingPid(), Binder.getCallingUid())
                    == PackageManager.PERMISSION_GRANTED;

            if (!isCallerSystem && !isCallerInstrumented && !isCallerPermissionController
                    && !doesCallerHavePermission) {
                mHandler.post(() -> callback.sendResult(new Bundle()));
                return;
            }

            mContext.enforcePermission(android.Manifest.permission.GET_APP_OPS_STATS,
                    Binder.getCallingPid(), Binder.getCallingUid(), "getHistoricalOps");
        }

        final String[] opNamesArray = (opNames != null)
                ? opNames.toArray(new String[opNames.size()]) : null;

        Set<String> attributionChainExemptPackages = null;
        if ((dataType & HISTORY_FLAG_GET_ATTRIBUTION_CHAINS) != 0) {
            attributionChainExemptPackages =
                    PermissionManager.getIndicatorExemptedPackages(mContext);
        }

        final String[] chainExemptPkgArray = attributionChainExemptPackages != null
                ? attributionChainExemptPackages.toArray(
                        new String[attributionChainExemptPackages.size()]) : null;

        // Must not hold the appops lock
        mHandler.post(PooledLambda.obtainRunnable(HistoricalRegistry::getHistoricalOps,
                mHistoricalRegistry, uid, packageName, attributionTag, opNamesArray, dataType,
                filter, beginTimeMillis, endTimeMillis, flags, chainExemptPkgArray,
                callback).recycleOnUse());
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

        Set<String> attributionChainExemptPackages = null;
        if ((dataType & HISTORY_FLAG_GET_ATTRIBUTION_CHAINS) != 0) {
            attributionChainExemptPackages =
                    PermissionManager.getIndicatorExemptedPackages(mContext);
        }

        final String[] chainExemptPkgArray = attributionChainExemptPackages != null
                ? attributionChainExemptPackages.toArray(
                new String[attributionChainExemptPackages.size()]) : null;

        // Must not hold the appops lock
        mHandler.post(PooledLambda.obtainRunnable(HistoricalRegistry::getHistoricalOpsFromDiskRaw,
                mHistoricalRegistry, uid, packageName, attributionTag, opNamesArray, dataType,
                filter, beginTimeMillis, endTimeMillis, flags, chainExemptPkgArray,
                callback).recycleOnUse());
    }

    @Override
    public void reloadNonHistoricalState() {
        mContext.enforcePermission(Manifest.permission.MANAGE_APPOPS,
                Binder.getCallingPid(), Binder.getCallingUid(), "reloadNonHistoricalState");
        mAppOpsCheckingService.writeState();
        mAppOpsCheckingService.readState();
    }

    @VisibleForTesting
    void readState() {
        mAppOpsCheckingService.readState();
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
            Ops ops = getOpsLocked(uid, packageName, null, false, null, /* edit */ false);
            if (ops != null) {
                ops.remove(op.op);
                op.setMode(AppOpsManager.opToDefaultMode(op.op));
                if (ops.size() <= 0) {
                    UidState uidState = ops.uidState;
                    ArrayMap<String, Ops> pkgOps = uidState.pkgOps;
                    if (pkgOps != null) {
                        pkgOps.remove(ops.packageName);
                        mAppOpsCheckingService.removePackage(ops.packageName,
                                UserHandle.getUserId(uidState.uid));
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
        if (code == OP_RUN_ANY_IN_BACKGROUND) {
            // TODO (b/280869337): Remove this once we have the required data.
            Slog.wtfStack(TAG, "setUidMode called for RUN_ANY_IN_BACKGROUND by uid: "
                    + UserHandle.formatUid(Binder.getCallingUid()));
        }
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
                uidState = new UidState(uid);
                mUidStates.put(uid, uidState);
            }
            if (uidState.getUidMode(code) != AppOpsManager.opToDefaultMode(code)) {
                previousMode = uidState.getUidMode(code);
            } else {
                // doesn't look right but is legacy behavior.
                previousMode = MODE_DEFAULT;
            }

            if (!uidState.setUidMode(code, mode)) {
                return;
            }
            uidState.evalForegroundOps();
            if (mode != MODE_ERRORED && mode != previousMode) {
                updateStartedOpModeForUidLocked(code, mode == MODE_IGNORED, uid);
            }
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
        ModeCallback listenerToIgnore = callbackToIgnore != null
                ? mModeWatchers.get(callbackToIgnore.asBinder()) : null;
        mAppOpsCheckingService.notifyOpChangedForAllPkgsInUid(code, uid, onlyForeground,
                listenerToIgnore);
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
        final int callingUid = Binder.getCallingUid();
        if (code == OP_RUN_ANY_IN_BACKGROUND && mode != MODE_ALLOWED) {
            // TODO (b/280869337): Remove this once we have the required data.
            final String callingPackage = ArrayUtils.firstOrNull(getPackagesForUid(callingUid));
            Slog.wtfStack(TAG,
                    "RUN_ANY_IN_BACKGROUND for package " + packageName + " changed to mode: "
                            + modeToName(mode) + " via setMode. Calling package: " + callingPackage
                            + ", calling uid: " + UserHandle.formatUid(callingUid)
                            + ", calling pid: " + Binder.getCallingPid()
                            + ", system pid: " + Process.myPid());
        }
    }

    void setMode(int code, int uid, @NonNull String packageName, int mode,
            @Nullable IAppOpsCallback permissionPolicyCallback) {
        enforceManageAppOpsModes(Binder.getCallingPid(), Binder.getCallingUid(), uid);
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(packageName, UserHandle.getUserId(uid))) {
            return;
        }

        ArraySet<OnOpModeChangedListener> repCbs = null;
        code = AppOpsManager.opToSwitch(code);

        PackageVerificationResult pvr;
        try {
            pvr = verifyAndGetBypass(uid, packageName, null);
        } catch (SecurityException e) {
            if (Process.isIsolated(uid)) {
                Slog.e(TAG, "Cannot setMode: isolated process");
            } else {
                Slog.e(TAG, "Cannot setMode", e);
            }
            return;
        }

        int previousMode = MODE_DEFAULT;
        synchronized (this) {
            UidState uidState = getUidStateLocked(uid, false);
            Op op = getOpLocked(code, uid, packageName, null, false, pvr.bypass, /* edit */ true);
            if (op != null) {
                if (op.getMode() != mode) {
                    previousMode = op.getMode();
                    op.setMode(mode);

                    if (uidState != null) {
                        uidState.evalForegroundOps();
                    }
                    ArraySet<OnOpModeChangedListener> cbs =
                            mAppOpsCheckingService.getOpModeChangedListeners(code);
                    if (cbs != null) {
                        if (repCbs == null) {
                            repCbs = new ArraySet<>();
                        }
                        repCbs.addAll(cbs);
                    }
                    cbs = mAppOpsCheckingService.getPackageModeChangedListeners(packageName);
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
                    if (mode != MODE_ERRORED) {
                        updateStartedOpModeForUidLocked(code, mode == MODE_IGNORED, uid);
                    }
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

    private void notifyOpChanged(ArraySet<OnOpModeChangedListener> callbacks, int code,
            int uid, String packageName) {
        for (int i = 0; i < callbacks.size(); i++) {
            final OnOpModeChangedListener callback = callbacks.valueAt(i);
            notifyOpChanged(callback, code, uid, packageName);
        }
    }

    private void notifyOpChanged(OnOpModeChangedListener callback, int code,
            int uid, String packageName) {
        mAppOpsCheckingService.notifyOpChanged(callback, code, uid, packageName);
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

    private static HashMap<OnOpModeChangedListener, ArrayList<ChangeRec>> addCallbacks(
            HashMap<OnOpModeChangedListener, ArrayList<ChangeRec>> callbacks,
            int op, int uid, String packageName, int previousMode,
            ArraySet<OnOpModeChangedListener> cbs) {
        if (cbs == null) {
            return callbacks;
        }
        if (callbacks == null) {
            callbacks = new HashMap<>();
        }
        final int N = cbs.size();
        for (int i=0; i<N; i++) {
            OnOpModeChangedListener cb = cbs.valueAt(i);
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

        HashMap<OnOpModeChangedListener, ArrayList<ChangeRec>> callbacks = null;
        ArrayList<ChangeRec> allChanges = new ArrayList<>();
        synchronized (this) {
            boolean changed = false;
            for (int i = mUidStates.size() - 1; i >= 0; i--) {
                UidState uidState = mUidStates.valueAt(i);

                SparseIntArray opModes = uidState.getNonDefaultUidModes();
                if (opModes != null && (uidState.uid == reqUid || reqUid == -1)) {
                    final int uidOpCount = opModes.size();
                    for (int j = uidOpCount - 1; j >= 0; j--) {
                        final int code = opModes.keyAt(j);
                        if (AppOpsManager.opAllowsReset(code)) {
                            int previousMode = opModes.valueAt(j);
                            int newMode = isUidOpGrantedByRole(uidState.uid, code) ? MODE_ALLOWED :
                                    AppOpsManager.opToDefaultMode(code);
                            uidState.setUidMode(code, newMode);
                            for (String packageName : getPackagesForUid(uidState.uid)) {
                                callbacks = addCallbacks(callbacks, code, uidState.uid, packageName,
                                        previousMode,
                                        mAppOpsCheckingService.getOpModeChangedListeners(code));
                                callbacks = addCallbacks(callbacks, code, uidState.uid, packageName,
                                        previousMode, mAppOpsCheckingService
                                                .getPackageModeChangedListeners(packageName));

                                allChanges = addChange(allChanges, code, uidState.uid,
                                        packageName, previousMode);
                            }
                        }
                    }
                }

                if (uidState.pkgOps.isEmpty()) {
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
                        if (AppOpsManager.opAllowsReset(curOp.op)) {
                            int previousMode = curOp.getMode();
                            int newMode = isPackageOpGrantedByRole(packageName, uidState.uid,
                                    curOp.op) ? MODE_ALLOWED : AppOpsManager.opToDefaultMode(
                                    curOp.op);
                            if (previousMode == newMode) {
                                continue;
                            }
                            curOp.setMode(newMode);
                            changed = true;
                            uidChanged = true;
                            final int uid = curOp.uidState.uid;
                            callbacks = addCallbacks(callbacks, curOp.op, uid, packageName,
                                    previousMode,
                                    mAppOpsCheckingService.getOpModeChangedListeners(curOp.op));
                            callbacks = addCallbacks(callbacks, curOp.op, uid, packageName,
                                    previousMode, mAppOpsCheckingService
                                            .getPackageModeChangedListeners(packageName));

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
                        mAppOpsCheckingService.removePackage(packageName,
                                UserHandle.getUserId(uidState.uid));
                    }
                }
                if (uidChanged) {
                    uidState.evalForegroundOps();
                }
            }

            if (changed) {
                scheduleFastWriteLocked();
            }
        }
        if (callbacks != null) {
            for (Map.Entry<OnOpModeChangedListener, ArrayList<ChangeRec>> ent
                    : callbacks.entrySet()) {
                OnOpModeChangedListener cb = ent.getKey();
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

    private boolean isUidOpGrantedByRole(int uid, int code) {
        if (!AppOpsManager.opIsUidAppOpPermission(code)) {
            return false;
        }
        PackageManager packageManager = mContext.getPackageManager();
        long token = Binder.clearCallingIdentity();
        try {
            // Permissions are managed by UIDs, but unfortunately a package name is required in API.
            String packageName = ArrayUtils.firstOrNull(packageManager.getPackagesForUid(uid));
            if (packageName == null) {
                return false;
            }
            int permissionFlags = packageManager.getPermissionFlags(AppOpsManager.opToPermission(
                    code), packageName, UserHandle.getUserHandleForUid(uid));
            return (permissionFlags & PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE) != 0;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private boolean isPackageOpGrantedByRole(@NonNull String packageName, int uid, int code) {
        if (!AppOpsManager.opIsPackageAppOpPermission(code)) {
            return false;
        }
        PackageManager packageManager = mContext.getPackageManager();
        long token = Binder.clearCallingIdentity();
        try {
            int permissionFlags = packageManager.getPermissionFlags(AppOpsManager.opToPermission(
                    code), packageName, UserHandle.getUserHandleForUid(uid));
            return (permissionFlags & PackageManager.FLAG_PERMISSION_GRANTED_BY_ROLE) != 0;
        } finally {
            Binder.restoreCallingIdentity(token);
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
                uidState.evalForegroundOps();
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
        final boolean mayWatchPackageName = packageName != null
                && !filterAppAccessUnlocked(packageName, UserHandle.getUserId(callingUid));
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
                mAppOpsCheckingService.startWatchingOpModeChanged(cb, switchOp);
            }
            if (mayWatchPackageName) {
                mAppOpsCheckingService.startWatchingPackageModeChanged(cb, packageName);
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
                mAppOpsCheckingService.removeListener(cb);
            }

            evalAllForegroundOpsLocked();
        }
    }

    public CheckOpsDelegate getAppOpsServiceDelegate() {
        synchronized (AppOpsService.this) {
            final CheckOpsDelegateDispatcher dispatcher = mCheckOpsDelegateDispatcher;
            return (dispatcher != null) ? dispatcher.getCheckOpsDelegate() : null;
        }
    }

    public void setAppOpsServiceDelegate(CheckOpsDelegate delegate) {
        synchronized (AppOpsService.this) {
            final CheckOpsDelegateDispatcher oldDispatcher = mCheckOpsDelegateDispatcher;
            final CheckOpsDelegate policy = (oldDispatcher != null) ? oldDispatcher.mPolicy : null;
            mCheckOpsDelegateDispatcher = new CheckOpsDelegateDispatcher(policy, delegate);
        }
    }

    @Override
    public int checkOperationRaw(int code, int uid, String packageName,
            @Nullable String attributionTag) {
        return mCheckOpsDelegateDispatcher.checkOperation(code, uid, packageName, attributionTag,
                true /*raw*/);
    }

    @Override
    public int checkOperation(int code, int uid, String packageName) {
        return mCheckOpsDelegateDispatcher.checkOperation(code, uid, packageName, null,
                false /*raw*/);
    }

    private int checkOperationImpl(int code, int uid, String packageName,
            @Nullable String attributionTag, boolean raw) {
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(packageName, UserHandle.getUserId(uid))) {
            return AppOpsManager.opToDefaultMode(code);
        }

        String resolvedPackageName = AppOpsManager.resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return AppOpsManager.MODE_IGNORED;
        }
        return checkOperationUnchecked(code, uid, resolvedPackageName, attributionTag, raw);
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
            @Nullable String attributionTag, boolean raw) {
        PackageVerificationResult pvr;
        try {
            pvr = verifyAndGetBypass(uid, packageName, null);
        } catch (SecurityException e) {
            if (Process.isIsolated(uid)) {
                Slog.e(TAG, "Cannot checkOperation: isolated process");
            } else {
                Slog.e(TAG, "Cannot checkOperation", e);
            }
            return AppOpsManager.opToDefaultMode(code);
        }

        if (isOpRestrictedDueToSuspend(code, packageName, uid)) {
            return AppOpsManager.MODE_IGNORED;
        }
        synchronized (this) {
            if (isOpRestrictedLocked(uid, code, packageName, attributionTag, pvr.bypass, true)) {
                return AppOpsManager.MODE_IGNORED;
            }
            code = AppOpsManager.opToSwitch(code);
            UidState uidState = getUidStateLocked(uid, false);
            if (uidState != null
                    && uidState.getUidMode(code) != AppOpsManager.opToDefaultMode(code)) {
                final int rawMode = uidState.getUidMode(code);
                return raw ? rawMode : uidState.evalMode(code, rawMode);
            }
            Op op = getOpLocked(code, uid, packageName, null, false, pvr.bypass, /* edit */ false);
            if (op == null) {
                return AppOpsManager.opToDefaultMode(code);
            }
            return raw ? op.getMode() : op.uidState.evalMode(op.op, op.getMode());
        }
    }

    @Override
    public int checkAudioOperation(int code, int usage, int uid, String packageName) {
        return mCheckOpsDelegateDispatcher.checkAudioOperation(code, usage, uid, packageName);
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
            verifyAndGetBypass(uid, packageName, null, null, true);
            // When the caller is the system, it's possible that the packageName is the special
            // one (e.g., "root") which isn't actually existed.
            if (resolveUid(packageName) == uid
                    || (isPackageExisted(packageName)
                            && !filterAppAccessUnlocked(packageName, UserHandle.getUserId(uid)))) {
                return AppOpsManager.MODE_ALLOWED;
            }
            return AppOpsManager.MODE_ERRORED;
        } catch (SecurityException ignored) {
            return AppOpsManager.MODE_ERRORED;
        }
    }

    private boolean isPackageExisted(String packageName) {
        return getPackageManagerInternal().getPackageStateInternal(packageName) != null;
    }

    /**
     * This method will check with PackageManager to determine if the package provided should
     * be visible to the {@link Binder#getCallingUid()}.
     *
     * NOTE: This must not be called while synchronized on {@code this} to avoid dead locks
     */
    private boolean filterAppAccessUnlocked(String packageName, int userId) {
        final int callingUid = Binder.getCallingUid();
        return LocalServices.getService(PackageManagerInternal.class)
                .filterAppAccess(packageName, callingUid, userId);
    }

    @Override
    public SyncNotedAppOp noteProxyOperation(int code, AttributionSource attributionSource,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation) {
        return mCheckOpsDelegateDispatcher.noteProxyOperation(code, attributionSource,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage, skipProxyOperation);
    }

    private SyncNotedAppOp noteProxyOperationImpl(int code, AttributionSource attributionSource,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation) {
        final int proxyUid = attributionSource.getUid();
        final String proxyPackageName = attributionSource.getPackageName();
        final String proxyAttributionTag = attributionSource.getAttributionTag();
        final int proxiedUid = attributionSource.getNextUid();
        final String proxiedPackageName = attributionSource.getNextPackageName();
        final String proxiedAttributionTag = attributionSource.getNextAttributionTag();

        verifyIncomingProxyUid(attributionSource);
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(proxiedPackageName, UserHandle.getUserId(proxiedUid))
                || !isIncomingPackageValid(proxyPackageName, UserHandle.getUserId(proxyUid))) {
            return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, proxiedAttributionTag,
                    proxiedPackageName);
        }

        skipProxyOperation = skipProxyOperation
                && isCallerAndAttributionTrusted(attributionSource);

        String resolveProxyPackageName = AppOpsManager.resolvePackageName(proxyUid,
                proxyPackageName);
        if (resolveProxyPackageName == null) {
            return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code,
                    proxiedAttributionTag, proxiedPackageName);
        }

        final boolean isSelfBlame = Binder.getCallingUid() == proxiedUid;
        final boolean isProxyTrusted = mContext.checkPermission(
                Manifest.permission.UPDATE_APP_OPS_STATS, -1, proxyUid)
                == PackageManager.PERMISSION_GRANTED || isSelfBlame;

        if (!skipProxyOperation) {
            final int proxyFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXY
                    : AppOpsManager.OP_FLAG_UNTRUSTED_PROXY;

            final SyncNotedAppOp proxyReturn = noteOperationUnchecked(code, proxyUid,
                    resolveProxyPackageName, proxyAttributionTag, Process.INVALID_UID, null, null,
                    proxyFlags, !isProxyTrusted, "proxy " + message, shouldCollectMessage);
            if (proxyReturn.getOpMode() != AppOpsManager.MODE_ALLOWED) {
                return new SyncNotedAppOp(proxyReturn.getOpMode(), code, proxiedAttributionTag,
                        proxiedPackageName);
            }
        }

        String resolveProxiedPackageName = AppOpsManager.resolvePackageName(proxiedUid,
                proxiedPackageName);
        if (resolveProxiedPackageName == null) {
            return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code, proxiedAttributionTag,
                    proxiedPackageName);
        }

        final int proxiedFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXIED
                : AppOpsManager.OP_FLAG_UNTRUSTED_PROXIED;
        return noteOperationUnchecked(code, proxiedUid, resolveProxiedPackageName,
                proxiedAttributionTag, proxyUid, resolveProxyPackageName, proxyAttributionTag,
                proxiedFlags, shouldCollectAsyncNotedOp, message, shouldCollectMessage);
    }

    @Override
    public SyncNotedAppOp noteOperation(int code, int uid, String packageName,
            String attributionTag, boolean shouldCollectAsyncNotedOp, String message,
            boolean shouldCollectMessage) {
        return mCheckOpsDelegateDispatcher.noteOperation(code, uid, packageName,
                attributionTag, shouldCollectAsyncNotedOp, message, shouldCollectMessage);
    }

    private SyncNotedAppOp noteOperationImpl(int code, int uid, @Nullable String packageName,
            @Nullable String attributionTag, boolean shouldCollectAsyncNotedOp,
            @Nullable String message, boolean shouldCollectMessage) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(packageName, UserHandle.getUserId(uid))) {
            return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, attributionTag,
                    packageName);
        }

        String resolvedPackageName = AppOpsManager.resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code, attributionTag,
                    packageName);
        }
        return noteOperationUnchecked(code, uid, resolvedPackageName, attributionTag,
                Process.INVALID_UID, null, null, AppOpsManager.OP_FLAG_SELF,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage);
    }

    private SyncNotedAppOp noteOperationUnchecked(int code, int uid, @NonNull String packageName,
            @Nullable String attributionTag, int proxyUid, String proxyPackageName,
            @Nullable String proxyAttributionTag, @OpFlags int flags,
            boolean shouldCollectAsyncNotedOp, @Nullable String message,
            boolean shouldCollectMessage) {
        PackageVerificationResult pvr;
        try {
            pvr = verifyAndGetBypass(uid, packageName, attributionTag, proxyPackageName);
            boolean wasNull = attributionTag == null;
            if (!pvr.isAttributionTagValid) {
                attributionTag = null;
            }
        } catch (SecurityException e) {
            if (Process.isIsolated(uid)) {
                Slog.e(TAG, "Cannot noteOperation: isolated process");
            } else {
                Slog.e(TAG, "Cannot noteOperation", e);
            }
            return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, attributionTag,
                    packageName);
        }

        synchronized (this) {
            final Ops ops = getOpsLocked(uid, packageName, attributionTag,
                    pvr.isAttributionTagValid, pvr.bypass, /* edit */ true);
            if (ops == null) {
                scheduleOpNotedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                        AppOpsManager.MODE_IGNORED);
                if (DEBUG) Slog.d(TAG, "noteOperation: no op for code " + code + " uid " + uid
                        + " package " + packageName + "flags: " +
                        AppOpsManager.flagsToString(flags));
                return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, attributionTag,
                        packageName);
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
            if (isOpRestrictedLocked(uid, code, packageName, attributionTag, pvr.bypass, false)) {
                attributedOp.rejected(uidState.getState(), flags);
                scheduleOpNotedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                        AppOpsManager.MODE_IGNORED);
                return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code, attributionTag,
                        packageName);
            }
            // If there is a non-default per UID policy (we set UID op mode only if
            // non-default) it takes over, otherwise use the per package policy.
            if (uidState.getUidMode(switchCode) != AppOpsManager.opToDefaultMode(switchCode)) {
                final int uidMode = uidState.evalMode(code, uidState.getUidMode(switchCode));
                if (uidMode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Slog.d(TAG, "noteOperation: uid reject #" + uidMode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName + " flags: " + AppOpsManager.flagsToString(flags));
                    attributedOp.rejected(uidState.getState(), flags);
                    scheduleOpNotedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                            uidMode);
                    return new SyncNotedAppOp(uidMode, code, attributionTag, packageName);
                }
            } else {
                final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, uid, true)
                        : op;
                final int mode = switchOp.uidState.evalMode(switchOp.op, switchOp.getMode());
                if (mode != AppOpsManager.MODE_ALLOWED) {
                    if (DEBUG) Slog.d(TAG, "noteOperation: reject #" + mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName + " flags: " + AppOpsManager.flagsToString(flags));
                    attributedOp.rejected(uidState.getState(), flags);
                    scheduleOpNotedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                            mode);
                    return new SyncNotedAppOp(mode, code, attributionTag, packageName);
                }
            }
            if (DEBUG) {
                Slog.d(TAG,
                        "noteOperation: allowing code " + code + " uid " + uid + " package "
                                + packageName + (attributionTag == null ? ""
                                : "." + attributionTag) + " flags: "
                                + AppOpsManager.flagsToString(flags));
            }
            scheduleOpNotedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                    AppOpsManager.MODE_ALLOWED);
            attributedOp.accessed(proxyUid, proxyPackageName, proxyAttributionTag,
                    uidState.getState(),
                    flags);

            if (shouldCollectAsyncNotedOp) {
                collectAsyncNotedOp(uid, packageName, code, attributionTag, flags, message,
                        shouldCollectMessage);
            }

            return new SyncNotedAppOp(AppOpsManager.MODE_ALLOWED, code, attributionTag,
                    packageName);
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
    public SyncNotedAppOp startOperation(IBinder token, int code, int uid,
            @Nullable String packageName, @Nullable String attributionTag,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp,
            String message, boolean shouldCollectMessage, @AttributionFlags int attributionFlags,
            int attributionChainId) {
        return mCheckOpsDelegateDispatcher.startOperation(token, code, uid, packageName,
                attributionTag, startIfModeDefault, shouldCollectAsyncNotedOp, message,
                shouldCollectMessage, attributionFlags, attributionChainId);
    }

    private SyncNotedAppOp startOperationImpl(@NonNull IBinder clientId, int code, int uid,
            @Nullable String packageName, @Nullable String attributionTag,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, @NonNull String message,
            boolean shouldCollectMessage, @AttributionFlags int attributionFlags,
            int attributionChainId) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(packageName, UserHandle.getUserId(uid))) {
            return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, attributionTag,
                    packageName);
        }

        String resolvedPackageName = AppOpsManager.resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code, attributionTag,
                    packageName);
        }

        // As a special case for OP_RECORD_AUDIO_HOTWORD, OP_RECEIVE_AMBIENT_TRIGGER_AUDIO and
        // OP_RECORD_AUDIO_SANDBOXED which we use only for attribution purposes and not as a check,
        // also make sure that the caller is allowed to access the data gated by OP_RECORD_AUDIO.
        //
        // TODO: Revert this change before Android 12.
        int result = MODE_DEFAULT;
        if (code == OP_RECORD_AUDIO_HOTWORD || code == OP_RECEIVE_AMBIENT_TRIGGER_AUDIO
                || code == OP_RECORD_AUDIO_SANDBOXED) {
            result = checkOperation(OP_RECORD_AUDIO, uid, packageName);
            // Check result
            if (result != AppOpsManager.MODE_ALLOWED) {
                return new SyncNotedAppOp(result, code, attributionTag, packageName);
            }
        }
        // As a special case for OP_CAMERA_SANDBOXED.
        if (code == OP_CAMERA_SANDBOXED) {
            result = checkOperation(OP_CAMERA, uid, packageName);
            // Check result
            if (result != AppOpsManager.MODE_ALLOWED) {
                return new SyncNotedAppOp(result, code, attributionTag, packageName);
            }
        }

        return startOperationUnchecked(clientId, code, uid, packageName, attributionTag,
                Process.INVALID_UID, null, null, OP_FLAG_SELF, startIfModeDefault,
                shouldCollectAsyncNotedOp, message, shouldCollectMessage, attributionFlags,
                attributionChainId, /*dryRun*/ false);
    }

    @Override
    public SyncNotedAppOp startProxyOperation(@NonNull IBinder clientId, int code,
            @NonNull AttributionSource attributionSource, boolean startIfModeDefault,
            boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
            boolean skipProxyOperation, @AttributionFlags int proxyAttributionFlags,
            @AttributionFlags int proxiedAttributionFlags, int attributionChainId) {
        return mCheckOpsDelegateDispatcher.startProxyOperation(clientId, code, attributionSource,
                startIfModeDefault, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                skipProxyOperation, proxyAttributionFlags, proxiedAttributionFlags,
                attributionChainId);
    }

    private SyncNotedAppOp startProxyOperationImpl(@NonNull IBinder clientId, int code,
            @NonNull AttributionSource attributionSource,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, String message,
            boolean shouldCollectMessage, boolean skipProxyOperation, @AttributionFlags
            int proxyAttributionFlags, @AttributionFlags int proxiedAttributionFlags,
            int attributionChainId) {
        final int proxyUid = attributionSource.getUid();
        final String proxyPackageName = attributionSource.getPackageName();
        final String proxyAttributionTag = attributionSource.getAttributionTag();
        final int proxiedUid = attributionSource.getNextUid();
        final String proxiedPackageName = attributionSource.getNextPackageName();
        final String proxiedAttributionTag = attributionSource.getNextAttributionTag();

        verifyIncomingProxyUid(attributionSource);
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(proxyPackageName, UserHandle.getUserId(proxyUid))
                || !isIncomingPackageValid(proxiedPackageName, UserHandle.getUserId(proxiedUid))) {
            return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, proxiedAttributionTag,
                    proxiedPackageName);
        }

        boolean isCallerTrusted = isCallerAndAttributionTrusted(attributionSource);
        skipProxyOperation = isCallerTrusted && skipProxyOperation;

        String resolvedProxyPackageName = AppOpsManager.resolvePackageName(proxyUid,
                proxyPackageName);
        if (resolvedProxyPackageName == null) {
            return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code, proxiedAttributionTag,
                    proxiedPackageName);
        }

        final boolean isChainTrusted = isCallerTrusted
                && attributionChainId != ATTRIBUTION_CHAIN_ID_NONE
                && ((proxyAttributionFlags & ATTRIBUTION_FLAG_TRUSTED) != 0
                || (proxiedAttributionFlags & ATTRIBUTION_FLAG_TRUSTED) != 0);
        final boolean isSelfBlame = Binder.getCallingUid() == proxiedUid;
        final boolean isProxyTrusted = mContext.checkPermission(
                Manifest.permission.UPDATE_APP_OPS_STATS, -1, proxyUid)
                == PackageManager.PERMISSION_GRANTED || isSelfBlame
                || isChainTrusted;

        String resolvedProxiedPackageName = AppOpsManager.resolvePackageName(proxiedUid,
                proxiedPackageName);
        if (resolvedProxiedPackageName == null) {
            return new SyncNotedAppOp(AppOpsManager.MODE_IGNORED, code, proxiedAttributionTag,
                    proxiedPackageName);
        }

        final int proxiedFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXIED
                : AppOpsManager.OP_FLAG_UNTRUSTED_PROXIED;

        if (!skipProxyOperation) {
            // Test if the proxied operation will succeed before starting the proxy operation
            final SyncNotedAppOp testProxiedOp = startOperationUnchecked(clientId, code,
                    proxiedUid, resolvedProxiedPackageName, proxiedAttributionTag, proxyUid,
                    resolvedProxyPackageName, proxyAttributionTag, proxiedFlags, startIfModeDefault,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    proxiedAttributionFlags, attributionChainId, /*dryRun*/ true);
            if (!shouldStartForMode(testProxiedOp.getOpMode(), startIfModeDefault)) {
                return testProxiedOp;
            }

            final int proxyFlags = isProxyTrusted ? AppOpsManager.OP_FLAG_TRUSTED_PROXY
                    : AppOpsManager.OP_FLAG_UNTRUSTED_PROXY;

            final SyncNotedAppOp proxyAppOp = startOperationUnchecked(clientId, code, proxyUid,
                    resolvedProxyPackageName, proxyAttributionTag, Process.INVALID_UID, null, null,
                    proxyFlags, startIfModeDefault, !isProxyTrusted, "proxy " + message,
                    shouldCollectMessage, proxyAttributionFlags, attributionChainId,
                    /*dryRun*/ false);
            if (!shouldStartForMode(proxyAppOp.getOpMode(), startIfModeDefault)) {
                return proxyAppOp;
            }
        }

        return startOperationUnchecked(clientId, code, proxiedUid, resolvedProxiedPackageName,
                proxiedAttributionTag, proxyUid, resolvedProxyPackageName, proxyAttributionTag,
                proxiedFlags, startIfModeDefault, shouldCollectAsyncNotedOp, message,
                shouldCollectMessage, proxiedAttributionFlags, attributionChainId,
                /*dryRun*/ false);
    }

    private boolean shouldStartForMode(int mode, boolean startIfModeDefault) {
        return (mode == MODE_ALLOWED || (mode == MODE_DEFAULT && startIfModeDefault));
    }

    private SyncNotedAppOp startOperationUnchecked(IBinder clientId, int code, int uid,
            @NonNull String packageName, @Nullable String attributionTag, int proxyUid,
            String proxyPackageName, @Nullable String proxyAttributionTag, @OpFlags int flags,
            boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, @Nullable String message,
            boolean shouldCollectMessage, @AttributionFlags int attributionFlags,
            int attributionChainId, boolean dryRun) {
        PackageVerificationResult pvr;
        try {
            pvr = verifyAndGetBypass(uid, packageName, attributionTag, proxyPackageName);
            if (!pvr.isAttributionTagValid) {
                attributionTag = null;
            }
        } catch (SecurityException e) {
            if (Process.isIsolated(uid)) {
                Slog.e(TAG, "Cannot startOperation: isolated process");
            } else {
                Slog.e(TAG, "Cannot startOperation", e);
            }
            return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, attributionTag,
                    packageName);
        }

        boolean isRestricted = false;
        int startType = START_TYPE_FAILED;
        synchronized (this) {
            final Ops ops = getOpsLocked(uid, packageName, attributionTag,
                    pvr.isAttributionTagValid, pvr.bypass, /* edit */ true);
            if (ops == null) {
                if (!dryRun) {
                    scheduleOpStartedIfNeededLocked(code, uid, packageName, attributionTag,
                            flags, AppOpsManager.MODE_IGNORED, startType, attributionFlags,
                            attributionChainId);
                }
                if (DEBUG) Slog.d(TAG, "startOperation: no op for code " + code + " uid " + uid
                        + " package " + packageName + " flags: "
                        + AppOpsManager.flagsToString(flags));
                return new SyncNotedAppOp(AppOpsManager.MODE_ERRORED, code, attributionTag,
                        packageName);
            }
            final Op op = getOpLocked(ops, code, uid, true);
            final AttributedOp attributedOp = op.getOrCreateAttribution(op, attributionTag);
            final UidState uidState = ops.uidState;
            isRestricted = isOpRestrictedLocked(uid, code, packageName, attributionTag, pvr.bypass,
                    false);
            final int switchCode = AppOpsManager.opToSwitch(code);
            // If there is a non-default per UID policy (we set UID op mode only if
            // non-default) it takes over, otherwise use the per package policy.
            if (uidState.getUidMode(switchCode) != AppOpsManager.opToDefaultMode(switchCode)) {
                final int uidMode = uidState.evalMode(code, uidState.getUidMode(switchCode));
                if (!shouldStartForMode(uidMode, startIfModeDefault)) {
                    if (DEBUG) {
                        Slog.d(TAG, "startOperation: uid reject #" + uidMode + " for code "
                                + switchCode + " (" + code + ") uid " + uid + " package "
                                + packageName + " flags: " + AppOpsManager.flagsToString(flags));
                    }
                    if (!dryRun) {
                        attributedOp.rejected(uidState.getState(), flags);
                        scheduleOpStartedIfNeededLocked(code, uid, packageName, attributionTag,
                                flags, uidMode, startType, attributionFlags, attributionChainId);
                    }
                    return new SyncNotedAppOp(uidMode, code, attributionTag, packageName);
                }
            } else {
                final Op switchOp = switchCode != code ? getOpLocked(ops, switchCode, uid, true)
                        : op;
                final int mode = switchOp.uidState.evalMode(switchOp.op, switchOp.getMode());
                if (mode != AppOpsManager.MODE_ALLOWED
                        && (!startIfModeDefault || mode != MODE_DEFAULT)) {
                    if (DEBUG) Slog.d(TAG, "startOperation: reject #" + mode + " for code "
                            + switchCode + " (" + code + ") uid " + uid + " package "
                            + packageName + " flags: " + AppOpsManager.flagsToString(flags));
                    if (!dryRun) {
                        attributedOp.rejected(uidState.getState(), flags);
                        scheduleOpStartedIfNeededLocked(code, uid, packageName, attributionTag,
                                flags, mode, startType, attributionFlags, attributionChainId);
                    }
                    return new SyncNotedAppOp(mode, code, attributionTag, packageName);
                }
            }
            if (DEBUG) Slog.d(TAG, "startOperation: allowing code " + code + " uid " + uid
                    + " package " + packageName + " restricted: " + isRestricted
                    + " flags: " + AppOpsManager.flagsToString(flags));
            if (!dryRun) {
                try {
                    if (isRestricted) {
                        attributedOp.createPaused(clientId, proxyUid, proxyPackageName,
                                proxyAttributionTag, uidState.getState(), flags,
                                attributionFlags, attributionChainId);
                    } else {
                        attributedOp.started(clientId, proxyUid, proxyPackageName,
                                proxyAttributionTag, uidState.getState(), flags,
                                attributionFlags, attributionChainId);
                        startType = START_TYPE_STARTED;
                    }
                } catch (RemoteException e) {
                    throw new RuntimeException(e);
                }
                scheduleOpStartedIfNeededLocked(code, uid, packageName, attributionTag, flags,
                        isRestricted ? MODE_IGNORED : MODE_ALLOWED, startType, attributionFlags,
                        attributionChainId);
            }
        }

        if (shouldCollectAsyncNotedOp && !dryRun && !isRestricted) {
            collectAsyncNotedOp(uid, packageName, code, attributionTag, AppOpsManager.OP_FLAG_SELF,
                    message, shouldCollectMessage);
        }

        return new SyncNotedAppOp(isRestricted ? MODE_IGNORED : MODE_ALLOWED, code, attributionTag,
                packageName);
    }

    @Override
    public void finishOperation(IBinder clientId, int code, int uid, String packageName,
            String attributionTag) {
        mCheckOpsDelegateDispatcher.finishOperation(clientId, code, uid, packageName,
                attributionTag);
    }

    private void finishOperationImpl(IBinder clientId, int code, int uid, String packageName,
            String attributionTag) {
        verifyIncomingUid(uid);
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(packageName, UserHandle.getUserId(uid))) {
            return;
        }

        String resolvedPackageName = AppOpsManager.resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return;
        }

        finishOperationUnchecked(clientId, code, uid, resolvedPackageName, attributionTag);
    }

    @Override
    public void finishProxyOperation(@NonNull IBinder clientId, int code,
            @NonNull AttributionSource attributionSource, boolean skipProxyOperation) {
        mCheckOpsDelegateDispatcher.finishProxyOperation(clientId, code, attributionSource,
                skipProxyOperation);
    }

    private Void finishProxyOperationImpl(IBinder clientId, int code,
            @NonNull AttributionSource attributionSource, boolean skipProxyOperation) {
        final int proxyUid = attributionSource.getUid();
        final String proxyPackageName = attributionSource.getPackageName();
        final String proxyAttributionTag = attributionSource.getAttributionTag();
        final int proxiedUid = attributionSource.getNextUid();
        final String proxiedPackageName = attributionSource.getNextPackageName();
        final String proxiedAttributionTag = attributionSource.getNextAttributionTag();

        skipProxyOperation = skipProxyOperation
                && isCallerAndAttributionTrusted(attributionSource);

        verifyIncomingProxyUid(attributionSource);
        verifyIncomingOp(code);
        if (!isIncomingPackageValid(proxyPackageName, UserHandle.getUserId(proxyUid))
                || !isIncomingPackageValid(proxiedPackageName, UserHandle.getUserId(proxiedUid))) {
            return null;
        }

        String resolvedProxyPackageName = AppOpsManager.resolvePackageName(proxyUid,
                proxyPackageName);
        if (resolvedProxyPackageName == null) {
            return null;
        }

        if (!skipProxyOperation) {
            finishOperationUnchecked(clientId, code, proxyUid, resolvedProxyPackageName,
                    proxyAttributionTag);
        }

        String resolvedProxiedPackageName = AppOpsManager.resolvePackageName(proxiedUid,
                proxiedPackageName);
        if (resolvedProxiedPackageName == null) {
            return null;
        }

        finishOperationUnchecked(clientId, code, proxiedUid, resolvedProxiedPackageName,
                proxiedAttributionTag);

        return null;
    }

    private void finishOperationUnchecked(IBinder clientId, int code, int uid, String packageName,
            String attributionTag) {
        PackageVerificationResult pvr;
        try {
            pvr = verifyAndGetBypass(uid, packageName, attributionTag);
            if (!pvr.isAttributionTagValid) {
                attributionTag = null;
            }
        } catch (SecurityException e) {
            if (Process.isIsolated(uid)) {
                Slog.e(TAG, "Cannot finishOperation: isolated process");
            } else {
                Slog.e(TAG, "Cannot finishOperation", e);
            }
            return;
        }

        synchronized (this) {
            Op op = getOpLocked(code, uid, packageName, attributionTag, pvr.isAttributionTagValid,
                    pvr.bypass, /* edit */ true);
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

            if (attributedOp.isRunning() || attributedOp.isPaused()) {
                attributedOp.finished(clientId);
            } else {
                Slog.e(TAG, "Operation not started: uid=" + uid + " pkg=" + packageName + "("
                        + attributionTag + ") op=" + AppOpsManager.opToName(code));
            }
        }
    }

    void scheduleOpActiveChangedIfNeededLocked(int code, int uid, @NonNull
            String packageName, @Nullable String attributionTag, boolean active, @AttributionFlags
            int attributionFlags, int attributionChainId) {
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
                this, dispatchedCallbacks, code, uid, packageName, attributionTag, active,
                attributionFlags, attributionChainId));
    }

    private void notifyOpActiveChanged(ArraySet<ActiveCallback> callbacks,
            int code, int uid, @NonNull String packageName, @Nullable String attributionTag,
            boolean active, @AttributionFlags int attributionFlags, int attributionChainId) {
        // There are features watching for mode changes such as window manager
        // and location manager which are in our process. The callbacks in these
        // features may require permissions our remote caller does not have.
        final long identity = Binder.clearCallingIdentity();
        try {
            final int callbackCount = callbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                final ActiveCallback callback = callbacks.valueAt(i);
                try {
                    if (shouldIgnoreCallback(code, callback.mCallingPid, callback.mCallingUid)) {
                        continue;
                    }
                    callback.mCallback.opActiveChanged(code, uid, packageName, attributionTag,
                            active, attributionFlags, attributionChainId);
                } catch (RemoteException e) {
                    /* do nothing */
                }
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    void scheduleOpStartedIfNeededLocked(int code, int uid, String pkgName,
            String attributionTag, @OpFlags int flags, @Mode int result,
            @AppOpsManager.OnOpStartedListener.StartedType int startedType,
            @AttributionFlags int attributionFlags, int attributionChainId) {
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
                result, startedType, attributionFlags, attributionChainId));
    }

    private void notifyOpStarted(ArraySet<StartedCallback> callbacks,
            int code, int uid, String packageName, String attributionTag, @OpFlags int flags,
            @Mode int result, @AppOpsManager.OnOpStartedListener.StartedType int startedType,
            @AttributionFlags int attributionFlags, int attributionChainId) {
        final long identity = Binder.clearCallingIdentity();
        try {
            final int callbackCount = callbacks.size();
            for (int i = 0; i < callbackCount; i++) {
                final StartedCallback callback = callbacks.valueAt(i);
                try {
                    if (shouldIgnoreCallback(code, callback.mCallingPid, callback.mCallingUid)) {
                        continue;
                    }
                    callback.mCallback.opStarted(code, uid, packageName, attributionTag, flags,
                            result, startedType, attributionFlags, attributionChainId);
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
                    if (shouldIgnoreCallback(code, callback.mCallingPid, callback.mCallingUid)) {
                        continue;
                    }
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

        if (AppOpsManager.shouldForceCollectNoteForOp(opCode)) {
            return true;
        }

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

    private void verifyIncomingProxyUid(@NonNull AttributionSource attributionSource) {
        if (attributionSource.getUid() == Binder.getCallingUid()) {
            return;
        }
        if (Binder.getCallingPid() == Process.myPid()) {
            return;
        }
        if (attributionSource.isTrusted(mContext)) {
            return;
        }
        mContext.enforcePermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null);
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

    private boolean shouldIgnoreCallback(int op, int watcherPid, int watcherUid) {
        // If it's a restricted read op, ignore it if watcher doesn't have manage ops permission,
        // as watcher should not use this to signal if the value is changed.
        return opRestrictsRead(op) && mContext.checkPermission(Manifest.permission.MANAGE_APPOPS,
                watcherPid, watcherUid) != PackageManager.PERMISSION_GRANTED;
    }

    private void verifyIncomingOp(int op) {
        if (op >= 0 && op < AppOpsManager._NUM_OP) {
            // Enforce manage appops permission if it's a restricted read op.
            if (opRestrictsRead(op)) {
                mContext.enforcePermission(Manifest.permission.MANAGE_APPOPS,
                        Binder.getCallingPid(), Binder.getCallingUid(), "verifyIncomingOp");
            }
            return;
        }
        throw new IllegalArgumentException("Bad operation #" + op);
    }

    private boolean isIncomingPackageValid(@Nullable String packageName, @UserIdInt int userId) {
        final int callingUid = Binder.getCallingUid();
        // Handle the special UIDs that don't have actual packages (audioserver, cameraserver, etc).
        if (packageName == null || isSpecialPackage(callingUid, packageName)) {
            return true;
        }

        // If the package doesn't exist, #verifyAndGetBypass would throw a SecurityException in
        // the end. Although that exception would be caught and return, we could make it return
        // early.
        if (!isPackageExisted(packageName)) {
            return false;
        }

        if (getPackageManagerInternal().filterAppAccess(packageName, callingUid, userId)) {
            Slog.w(TAG, packageName + " not found from " + callingUid);
            return false;
        }

        return true;
    }

    private boolean isSpecialPackage(int callingUid, @Nullable String packageName) {
        final String resolvedPackage = AppOpsManager.resolvePackageName(callingUid, packageName);
        return callingUid == Process.SYSTEM_UID
                || resolveUid(resolvedPackage) != Process.INVALID_UID;
    }

    private boolean isCallerAndAttributionTrusted(@NonNull AttributionSource attributionSource) {
        if (attributionSource.getUid() != Binder.getCallingUid()
                && attributionSource.isTrusted(mContext)) {
            return true;
        }
        return mContext.checkPermission(android.Manifest.permission.UPDATE_APP_OPS_STATS,
                Binder.getCallingPid(), Binder.getCallingUid(), null)
                == PackageManager.PERMISSION_GRANTED;
    }

    private @Nullable UidState getUidStateLocked(int uid, boolean edit) {
        UidState uidState = mUidStates.get(uid);
        if (uidState == null) {
            if (!edit) {
                return null;
            }
            uidState = new UidState(uid);
            mUidStates.put(uid, uidState);
        }

        return uidState;
    }

    private void updateAppWidgetVisibility(SparseArray<String> uidPackageNames, boolean visible) {
        synchronized (this) {
            getUidStateTracker().updateAppWidgetVisibility(uidPackageNames, visible);
        }
    }

    /**
     * @return {@link PackageManagerInternal}
     */
    private @NonNull PackageManagerInternal getPackageManagerInternal() {
        if (mPackageManagerInternal == null) {
            mPackageManagerInternal = LocalServices.getService(PackageManagerInternal.class);
        }
        if (mPackageManagerInternal == null) {
            throw new IllegalStateException("PackageManagerInternal not loaded");
        }

        return mPackageManagerInternal;
    }

    /**
     * @return {@link PackageManagerLocal}
     */
    private @NonNull PackageManagerLocal getPackageManagerLocal() {
        if (mPackageManagerLocal == null) {
            mPackageManagerLocal = LocalManagerRegistry.getManager(PackageManagerLocal.class);
        }
        if (mPackageManagerLocal == null) {
            throw new IllegalStateException("PackageManagerLocal not loaded");
        }

        return mPackageManagerLocal;
    }

    /**
     * @return {@link UserManagerInternal}
     */
    private @NonNull UserManagerInternal getUserManagerInternal() {
        if (mUserManagerInternal == null) {
            mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
        }
        if (mUserManagerInternal == null) {
            throw new IllegalStateException("UserManagerInternal not loaded");
        }

        return mUserManagerInternal;
    }

    /**
     * Create a restriction description matching the properties of the package.
     *
     * @param pkg The package to create the restriction description for
     *
     * @return The restriction matching the package
     */
    private RestrictionBypass getBypassforPackage(@NonNull PackageState packageState) {
        return new RestrictionBypass(packageState.getAppId() == Process.SYSTEM_UID,
                packageState.isPrivileged(), mContext.checkPermission(
                android.Manifest.permission.EXEMPT_FROM_AUDIO_RECORD_RESTRICTIONS, -1,
                packageState.getAppId()) == PackageManager.PERMISSION_GRANTED);
    }

    /**
     * @see #verifyAndGetBypass(int, String, String, String, boolean)
     */
    private @NonNull PackageVerificationResult verifyAndGetBypass(int uid, String packageName,
            @Nullable String attributionTag) {
        return verifyAndGetBypass(uid, packageName, attributionTag, null);
    }

    /**
     * @see #verifyAndGetBypass(int, String, String, String, boolean)
     */
    private @NonNull PackageVerificationResult verifyAndGetBypass(int uid, String packageName,
            @Nullable String attributionTag, @Nullable String proxyPackageName) {
        return verifyAndGetBypass(uid, packageName, attributionTag, proxyPackageName, false);
    }

    /**
     * Verify that package belongs to uid and return the {@link RestrictionBypass bypass
     * description} for the package, along with a boolean indicating whether the attribution tag is
     * valid.
     *
     * @param uid The uid the package belongs to
     * @param packageName The package the might belong to the uid
     * @param attributionTag attribution tag or {@code null} if no need to verify
     * @param proxyPackageName The proxy package, from which the attribution tag is to be pulled
     * @param suppressErrorLogs Whether to print to logcat about nonmatching parameters
     *
     * @return PackageVerificationResult containing {@link RestrictionBypass} and whether the
     *         attribution tag is valid
     */
    private @NonNull PackageVerificationResult verifyAndGetBypass(int uid, String packageName,
            @Nullable String attributionTag, @Nullable String proxyPackageName,
            boolean suppressErrorLogs) {
        if (uid == Process.ROOT_UID) {
            // For backwards compatibility, don't check package name for root UID.
            return new PackageVerificationResult(null,
                    /* isAttributionTagValid */ true);
        }
        if (Process.isSdkSandboxUid(uid)) {
            // SDK sandbox processes run in their own UID range, but their associated
            // UID for checks should always be the UID of the package implementing SDK sandbox
            // service.
            // TODO: We will need to modify the callers of this function instead, so
            // modifications and checks against the app ops state are done with the
            // correct UID.
            try {
                final PackageManager pm = mContext.getPackageManager();
                final String supplementalPackageName = pm.getSdkSandboxPackageName();
                if (Objects.equals(packageName, supplementalPackageName)) {
                    uid = pm.getPackageUidAsUser(supplementalPackageName,
                            PackageManager.PackageInfoFlags.of(0), UserHandle.getUserId(uid));
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Shouldn't happen for the supplemental package
                e.printStackTrace();
            }
        }


        // Do not check if uid/packageName/attributionTag is already known.
        synchronized (this) {
            UidState uidState = mUidStates.get(uid);
            if (uidState != null && !uidState.pkgOps.isEmpty()) {
                Ops ops = uidState.pkgOps.get(packageName);

                if (ops != null && (attributionTag == null || ops.knownAttributionTags.contains(
                        attributionTag)) && ops.bypass != null) {
                    return new PackageVerificationResult(ops.bypass,
                            ops.validAttributionTags.contains(attributionTag));
                }
            }
        }

        int callingUid = Binder.getCallingUid();

        // Allow any attribution tag for resolvable uids
        int pkgUid;
        if (Objects.equals(packageName, "com.android.shell")) {
            // Special case for the shell which is a package but should be able
            // to bypass app attribution tag restrictions.
            pkgUid = Process.SHELL_UID;
        } else {
            pkgUid = resolveUid(packageName);
        }
        if (pkgUid != Process.INVALID_UID) {
            if (pkgUid != UserHandle.getAppId(uid)) {
                if (!suppressErrorLogs) {
                    Slog.e(TAG, "Bad call made by uid " + callingUid + ". "
                            + "Package \"" + packageName + "\" does not belong to uid " + uid
                            + ".");
                }
                String otherUidMessage = DEBUG ? " but it is really " + pkgUid : " but it is not";
                throw new SecurityException("Specified package \"" + packageName + "\" under uid "
                        +  UserHandle.getAppId(uid) + otherUidMessage);
            }
            return new PackageVerificationResult(RestrictionBypass.UNRESTRICTED,
                    /* isAttributionTagValid */ true);
        }

        int userId = UserHandle.getUserId(uid);
        RestrictionBypass bypass = null;
        boolean isAttributionTagValid = false;

        final long ident = Binder.clearCallingIdentity();
        try {
            PackageManagerInternal pmInt = LocalServices.getService(PackageManagerInternal.class);
            var pkgState = pmInt.getPackageStateInternal(packageName);
            var pkg = pkgState == null ? null : pkgState.getAndroidPackage();
            if (pkg != null) {
                isAttributionTagValid = isAttributionInPackage(pkg, attributionTag);
                pkgUid = UserHandle.getUid(userId, pkgState.getAppId());
                bypass = getBypassforPackage(pkgState);
            }
            if (!isAttributionTagValid) {
                AndroidPackage proxyPkg = proxyPackageName != null
                        ? pmInt.getPackage(proxyPackageName) : null;
                // Re-check in proxy.
                isAttributionTagValid = isAttributionInPackage(proxyPkg, attributionTag);
                String msg;
                if (pkg != null && isAttributionTagValid) {
                    msg = "attributionTag " + attributionTag + " declared in manifest of the proxy"
                            + " package " + proxyPackageName + ", this is not advised";
                } else if (pkg != null) {
                    msg = "attributionTag " + attributionTag + " not declared in manifest of "
                            + packageName;
                } else {
                    msg = "package " + packageName + " not found, can't check for "
                            + "attributionTag " + attributionTag;
                }

                try {
                    if (!mPlatformCompat.isChangeEnabledByPackageName(
                            SECURITY_EXCEPTION_ON_INVALID_ATTRIBUTION_TAG_CHANGE, packageName,
                            userId) || !mPlatformCompat.isChangeEnabledByUid(
                                    SECURITY_EXCEPTION_ON_INVALID_ATTRIBUTION_TAG_CHANGE,
                            callingUid)) {
                        // Do not override tags if overriding is not enabled for this package
                        isAttributionTagValid = true;
                    }
                    Slog.e(TAG, msg);
                } catch (RemoteException neverHappens) {
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }

        if (pkgUid != uid) {
            if (!suppressErrorLogs) {
                Slog.e(TAG, "Bad call made by uid " + callingUid + ". "
                        + "Package \"" + packageName + "\" does not belong to uid " + uid + ".");
            }
            String otherUidMessage = DEBUG ? " but it is really " + pkgUid : " but it is not";
            throw new SecurityException("Specified package \"" + packageName + "\" under uid " + uid
                    + otherUidMessage);
        }

        return new PackageVerificationResult(bypass, isAttributionTagValid);
    }

    private boolean isAttributionInPackage(@Nullable AndroidPackage pkg,
            @Nullable String attributionTag) {
        if (pkg == null) {
            return false;
        } else if (attributionTag == null) {
            return true;
        }
        if (pkg.getAttributions() != null) {
            int numAttributions = pkg.getAttributions().size();
            for (int i = 0; i < numAttributions; i++) {
                if (pkg.getAttributions().get(i).getTag().equals(attributionTag)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Get (and potentially create) ops.
     *
     * @param uid The uid the package belongs to
     * @param packageName The name of the package
     * @param attributionTag attribution tag
     * @param isAttributionTagValid whether the given attribution tag is valid
     * @param bypass When to bypass certain op restrictions (can be null if edit == false)
     * @param edit If an ops does not exist, create the ops?

     * @return The ops
     */
    private Ops getOpsLocked(int uid, String packageName, @Nullable String attributionTag,
            boolean isAttributionTagValid, @Nullable RestrictionBypass bypass, boolean edit) {
        UidState uidState = getUidStateLocked(uid, edit);
        if (uidState == null) {
            return null;
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
                if (isAttributionTagValid) {
                    ops.validAttributionTags.add(attributionTag);
                } else {
                    ops.validAttributionTags.remove(attributionTag);
                }
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
     * @param isAttributionTagValid Whether the given attribution tag is valid
     * @param bypass When to bypass certain op restrictions (can be null if edit == false)
     * @param edit Iff {@code true} create the {@link Op} object if not yet created
     *
     * @return The {@link Op state} of the op
     */
    private @Nullable Op getOpLocked(int code, int uid, @NonNull String packageName,
            @Nullable String attributionTag, boolean isAttributionTagValid,
            @Nullable RestrictionBypass bypass, boolean edit) {
        Ops ops = getOpsLocked(uid, packageName, attributionTag, isAttributionTagValid, bypass,
                edit);
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
            String attributionTag, @Nullable RestrictionBypass appBypass, boolean isCheckOp) {
        int restrictionSetCount = mOpGlobalRestrictions.size();

        for (int i = 0; i < restrictionSetCount; i++) {
            ClientGlobalRestrictionState restrictionState = mOpGlobalRestrictions.valueAt(i);
            if (restrictionState.hasRestriction(code)) {
                return true;
            }
        }

        int userHandle = UserHandle.getUserId(uid);
        restrictionSetCount = mOpUserRestrictions.size();

        for (int i = 0; i < restrictionSetCount; i++) {
            // For each client, check that the given op is not restricted, or that the given
            // package is exempt from the restriction.
            ClientUserRestrictionState restrictionState = mOpUserRestrictions.valueAt(i);
            if (restrictionState.hasRestriction(code, packageName, attributionTag, userHandle,
                    isCheckOp)) {
                RestrictionBypass opBypass = opAllowSystemBypassRestriction(code);
                if (opBypass != null) {
                    // If we are the system, bypass user restrictions for certain codes
                    synchronized (this) {
                        if (opBypass.isSystemUid && appBypass != null && appBypass.isSystemUid) {
                            return false;
                        }
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

    /**
     * Read recent accesses from persistence (mRecentAccessesFile).
     * If there is no mRecentAccessesFile yet, we'll need migrate from mStorageFile: first read from
     * mStorageFile, then all subsequent reads/writes will use mRecentAccessesFile.
     * If neither file exists, there's nothing to migrate.
     */
    private void readRecentAccesses() {
        if (!mRecentAccessesFile.exists()) {
            readRecentAccesses(mStorageFile);
        } else {
            readRecentAccesses(mRecentAccessesFile);
        }
    }

    private void readRecentAccesses(AtomicFile file) {
        synchronized (file) {
            synchronized (this) {
                FileInputStream stream;
                try {
                    stream = file.openRead();
                } catch (FileNotFoundException e) {
                    Slog.i(TAG, "No existing app ops " + file.getBaseFile() + "; starting empty");
                    return;
                }
                boolean success = false;
                mUidStates.clear();
                mAppOpsCheckingService.clearAllModes();
                try {
                    TypedXmlPullParser parser = Xml.resolvePullParser(stream);
                    int type;
                    while ((type = parser.next()) != XmlPullParser.START_TAG
                            && type != XmlPullParser.END_DOCUMENT) {
                        // Parse next until we reach the start or end
                    }

                    if (type != XmlPullParser.START_TAG) {
                        throw new IllegalStateException("no start tag found");
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
                            // uid tag may be present during migration, don't print warning.
                            XmlUtils.skipCurrentTag(parser);
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
                        mAppOpsCheckingService.clearAllModes();
                    }
                    try {
                        stream.close();
                    } catch (IOException e) {
                    }
                }
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

        Ops ops = uidState.pkgOps.get(pkgName);
        if (ops == null) {
            ops = new Ops(pkgName, uidState);
            uidState.pkgOps.put(pkgName, ops);
        }
        ops.put(op.op, op);
    }

    @VisibleForTesting
    void writeRecentAccesses() {
        synchronized (mRecentAccessesFile) {
            FileOutputStream stream;
            try {
                stream = mRecentAccessesFile.startWrite();
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

                if (allOps != null) {
                    String lastPkg = null;
                    for (int i=0; i<allOps.size(); i++) {
                        AppOpsManager.PackageOps pkg = allOps.get(i);
                        if (!Objects.equals(pkg.getPackageName(), lastPkg)) {
                            if (lastPkg != null) {
                                out.endTag(null, "pkg");
                            }
                            lastPkg = pkg.getPackageName();
                            if (lastPkg != null) {
                                out.startTag(null, "pkg");
                                out.attribute(null, "n", lastPkg);
                            }
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
                mRecentAccessesFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state, restoring backup.", e);
                mRecentAccessesFile.failWrite(stream);
            }
        }
        mHistoricalRegistry.writeAndClearDiscreteHistory();
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
                        shell.mInternal.writeRecentAccesses();
                        shell.mInternal.mAppOpsCheckingService.writeState();
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
                        shell.mInternal.readRecentAccesses();
                        shell.mInternal.mAppOpsCheckingService.readState();
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
                                "appops start shell command", true,
                                AppOpsManager.ATTRIBUTION_FLAG_ACCESSOR, ATTRIBUTION_CHAIN_ID_NONE);
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
        pw.println("  --include-discrete [n]");
        pw.println("    Include discrete ops limited to n per dimension. Use zero for no limit.");
        pw.println("  --watchers");
        pw.println("    Only output the watcher sections.");
        pw.println("  --history");
        pw.println("    Only output history.");
        pw.println("  --uid-state-changes");
        pw.println("    Include logs about uid state changes.");
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
                AttributedOp.InProgressStartOpEvent event =
                        attributedOp.mInProgressEvents.valueAt(i);

                earliestElapsedTime = Math.min(earliestElapsedTime, event.getStartElapsedTime());
                maxNumStarts = Math.max(maxNumStarts, event.mNumUnfinishedStarts);
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

    @NeverCompile // Avoid size overhead of debugging code.
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
        boolean includeDiscreteOps = false;
        boolean dumpUidStateChangeLogs = false;
        int nDiscreteOps = 10;
        @HistoricalOpsRequestFilter int dumpFilter = 0;
        boolean dumpAll = false;

        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("-h".equals(arg)) {
                    dumpHelp(pw);
                    return;
                } else if ("-a".equals(arg)) {
                    // dump all data
                    dumpAll = true;
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
                } else if ("--include-discrete".equals(arg)) {
                    i++;
                    if (i >= args.length) {
                        pw.println("No argument for --include-discrete option");
                        return;
                    }
                    try {
                        nDiscreteOps = Integer.valueOf(args[i]);
                    } catch (NumberFormatException e) {
                        pw.println("Wrong parameter: " + args[i]);
                        return;
                    }
                    includeDiscreteOps = true;
                } else if ("--history".equals(arg)) {
                    dumpHistory = true;
                } else if (arg.length() > 0 && arg.charAt(0) == '-') {
                    pw.println("Unknown option: " + arg);
                    return;
                } else if ("--uid-state-changes".equals(arg)) {
                    dumpUidStateChangeLogs = true;
                } else {
                    pw.println("Unknown command: " + arg);
                    return;
                }
            }
        }

        final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        final Date date = new Date();
        synchronized (this) {
            pw.println("Current AppOps Service state:");
            if (!dumpHistory && !dumpWatchers) {
                mConstants.dump(pw);
            }
            pw.println();
            final long now = System.currentTimeMillis();
            final long nowElapsed = SystemClock.elapsedRealtime();
            final long nowUptime = SystemClock.uptimeMillis();
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

            if (!dumpHistory) {
                needSep |= mAppOpsCheckingService.dumpListeners(dumpOp, dumpUid, dumpPackage, pw);
            }

            if (mModeWatchers.size() > 0 && dumpOp < 0 && !dumpHistory) {
                boolean printedHeader = false;
                for (int i = 0; i < mModeWatchers.size(); i++) {
                    final ModeCallback cb = mModeWatchers.valueAt(i);
                    if (dumpPackage != null
                            && dumpUid != UserHandle.getAppId(cb.getWatchingUid())) {
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
                    && dumpPackage != null && dumpMode < 0 && !dumpWatchers) {
                needSep = mAudioRestrictionManager.dump(pw) || needSep;
            }
            if (needSep) {
                pw.println();
            }
            for (int i=0; i<mUidStates.size(); i++) {
                UidState uidState = mUidStates.valueAt(i);
                final SparseIntArray opModes = uidState.getNonDefaultUidModes();
                final ArrayMap<String, Ops> pkgOps = uidState.pkgOps;

                if (dumpWatchers || dumpHistory) {
                    continue;
                }
                if (dumpOp >= 0 || dumpPackage != null || dumpMode >= 0) {
                    boolean hasOp = dumpOp < 0 || (opModes != null
                            && opModes.indexOfKey(dumpOp) >= 0);
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
                                    if (ops.valueAt(opi).getMode() == dumpMode) {
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
                uidState.dump(pw, nowElapsed);
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
                        if (dumpMode >= 0 && dumpMode != op.getMode()) {
                            continue;
                        }
                        if (!printedPackage) {
                            pw.print("    Package "); pw.print(ops.packageName); pw.println(":");
                            printedPackage = true;
                        }
                        pw.print("      "); pw.print(AppOpsManager.opToName(opCode));
                        pw.print(" ("); pw.print(AppOpsManager.modeToName(op.getMode()));
                        final int switchOp = AppOpsManager.opToSwitch(opCode);
                        if (switchOp != opCode) {
                            pw.print(" / switch ");
                            pw.print(AppOpsManager.opToName(switchOp));
                            final Op switchObj = ops.get(switchOp);
                            int mode = switchObj == null
                                    ? AppOpsManager.opToDefaultMode(switchOp) : switchObj.getMode();
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

            boolean showUserRestrictions = !(dumpMode < 0 && !dumpWatchers && !dumpHistory);
            mAppOpsRestrictions.dumpRestrictions(pw, dumpOp, dumpPackage, showUserRestrictions);

            if (!dumpHistory && !dumpWatchers) {
                pw.println();
                if (mCheckOpsDelegateDispatcher.mPolicy != null
                        && mCheckOpsDelegateDispatcher.mPolicy instanceof AppOpsPolicy) {
                    AppOpsPolicy policy = (AppOpsPolicy) mCheckOpsDelegateDispatcher.mPolicy;
                    policy.dumpTags(pw);
                } else {
                    pw.println("  AppOps policy not set.");
                }
            }

            if (dumpAll || dumpUidStateChangeLogs) {
                pw.println();
                pw.println("Uid State Changes Event Log:");
                getUidStateTracker().dumpEvents(pw);
            }
        }

        // Must not hold the appops lock
        if (dumpHistory && !dumpWatchers) {
            mHistoricalRegistry.dump("  ", pw, dumpUid, dumpPackage, dumpAttributionTag, dumpOp,
                    dumpFilter);
        }
        if (includeDiscreteOps) {
            pw.println("Discrete accesses: ");
            mHistoricalRegistry.dumpDiscreteData(pw, dumpUid, dumpPackage, dumpAttributionTag,
                    dumpFilter, dumpOp, sdf, date, "  ", nDiscreteOps);
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
            PackageTagsList excludedPackageTags) {
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
        setUserRestrictionNoCheck(code, restricted, token, userHandle, excludedPackageTags);
    }

    private void setUserRestrictionNoCheck(int code, boolean restricted, IBinder token,
            int userHandle, PackageTagsList excludedPackageTags) {
        synchronized (AppOpsService.this) {
            ClientUserRestrictionState restrictionState = mOpUserRestrictions.get(token);

            if (restrictionState == null) {
                try {
                    restrictionState = new ClientUserRestrictionState(token);
                } catch (RemoteException e) {
                    return;
                }
                mOpUserRestrictions.put(token, restrictionState);
            }

            if (restrictionState.setRestriction(code, restricted, excludedPackageTags,
                    userHandle)) {
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        AppOpsService::notifyWatchersOfChange, this, code, UID_ANY));
                mHandler.sendMessage(PooledLambda.obtainMessage(
                        AppOpsService::updateStartedOpModeForUser, this, code, restricted,
                        userHandle));
            }

            if (restrictionState.isDefault()) {
                mOpUserRestrictions.remove(token);
                restrictionState.destroy();
            }
        }
    }

    private void updateStartedOpModeForUser(int code, boolean restricted, int userId) {
        synchronized (AppOpsService.this) {
            int numUids = mUidStates.size();
            for (int uidNum = 0; uidNum < numUids; uidNum++) {
                int uid = mUidStates.keyAt(uidNum);
                if (userId != UserHandle.USER_ALL && UserHandle.getUserId(uid) != userId) {
                    continue;
                }
                updateStartedOpModeForUidLocked(code, restricted, uid);
            }
        }
    }

    private void updateStartedOpModeForUidLocked(int code, boolean restricted, int uid) {
        UidState uidState = mUidStates.get(uid);
        if (uidState == null) {
            return;
        }

        int numPkgOps = uidState.pkgOps.size();
        for (int pkgNum = 0; pkgNum < numPkgOps; pkgNum++) {
            Ops ops = uidState.pkgOps.valueAt(pkgNum);
            Op op = ops != null ? ops.get(code) : null;
            if (op == null || (op.getMode() != MODE_ALLOWED && op.getMode() != MODE_FOREGROUND)) {
                continue;
            }
            int numAttrTags = op.mAttributions.size();
            for (int attrNum = 0; attrNum < numAttrTags; attrNum++) {
                AttributedOp attrOp = op.mAttributions.valueAt(attrNum);
                if (restricted && attrOp.isRunning()) {
                    attrOp.pause();
                } else if (attrOp.isPaused()) {
                    attrOp.resume();
                }
            }
        }
    }

    private void notifyWatchersOfChange(int code, int uid) {
        final ArraySet<OnOpModeChangedListener> modeChangedListenerSet;
        synchronized (this) {
            modeChangedListenerSet = mAppOpsCheckingService.getOpModeChangedListeners(code);
            if (modeChangedListenerSet == null) {
                return;
            }
        }

        notifyOpChanged(modeChangedListenerSet,  code, uid, null);
    }

    @Override
    public void removeUser(int userHandle) throws RemoteException {
        checkSystemUid("removeUser");
        synchronized (AppOpsService.this) {
            final int tokenCount = mOpUserRestrictions.size();
            for (int i = tokenCount - 1; i >= 0; i--) {
                ClientUserRestrictionState opRestrictions = mOpUserRestrictions.valueAt(i);
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
        if (!isIncomingPackageValid(packageName, UserHandle.getUserId(uid))) {
            return false;
        }

        final String resolvedPackageName = AppOpsManager.resolvePackageName(uid, packageName);
        if (resolvedPackageName == null) {
            return false;
        }
        // TODO moltmann: Allow to check for attribution op activeness
        synchronized (AppOpsService.this) {
            Ops pkgOps = getOpsLocked(uid, resolvedPackageName, null, false, null, false);
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
    public boolean isProxying(int op, @NonNull String proxyPackageName,
            @NonNull String proxyAttributionTag, int proxiedUid,
            @NonNull String proxiedPackageName) {
        Objects.requireNonNull(proxyPackageName);
        Objects.requireNonNull(proxiedPackageName);
        final long callingUid = Binder.getCallingUid();
        final long identity = Binder.clearCallingIdentity();
        try {
            final List<AppOpsManager.PackageOps> packageOps = getOpsForPackage(proxiedUid,
                    proxiedPackageName, new int[] {op});
            if (packageOps == null || packageOps.isEmpty()) {
                return false;
            }
            final List<OpEntry> opEntries = packageOps.get(0).getOps();
            if (opEntries.isEmpty()) {
                return false;
            }
            final OpEntry opEntry = opEntries.get(0);
            if (!opEntry.isRunning()) {
                return false;
            }
            final OpEventProxyInfo proxyInfo = opEntry.getLastProxyInfo(
                    OP_FLAG_TRUSTED_PROXIED | AppOpsManager.OP_FLAG_UNTRUSTED_PROXIED);
            return proxyInfo != null && callingUid == proxyInfo.getUid()
                    && proxyPackageName.equals(proxyInfo.getPackageName())
                    && Objects.equals(proxyAttributionTag, proxyInfo.getAttributionTag());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void resetPackageOpsNoHistory(@NonNull String packageName) {
        mContext.enforceCallingOrSelfPermission(android.Manifest.permission.MANAGE_APPOPS,
                "resetPackageOpsNoHistory");
        synchronized (AppOpsService.this) {
            final int uid = mPackageManagerInternal.getPackageUid(packageName, 0,
                    UserHandle.getCallingUserId());
            if (uid == Process.INVALID_UID) {
                return;
            }
            UidState uidState = mUidStates.get(uid);
            if (uidState == null) {
                return;
            }
            Ops removedOps = uidState.pkgOps.remove(packageName);
            mAppOpsCheckingService.removePackage(packageName, UserHandle.getUserId(uid));
            if (removedOps != null) {
                scheduleFastWriteLocked();
            }
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
        mHistoricalRegistry.offsetDiscreteHistory(offsetMillis);
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
        mHistoricalRegistry.clearAllHistory();
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
        boolean isCallerInstrumented =
                ami.getInstrumentationSourceUid(Binder.getCallingUid()) != Process.INVALID_UID;
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

    @GuardedBy("this")
    private void removeUidsForUserLocked(int userHandle) {
        for (int i = mUidStates.size() - 1; i >= 0; --i) {
            final int uid = mUidStates.keyAt(i);
            if (UserHandle.getUserId(uid) == userHandle) {
                mUidStates.valueAt(i).clear();
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

    private static int resolveUid(String packageName)  {
        if (packageName == null) {
            return Process.INVALID_UID;
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
        return Process.INVALID_UID;
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

    private final class ClientUserRestrictionState implements DeathRecipient {
        private final IBinder token;

        ClientUserRestrictionState(IBinder token)
                throws RemoteException {
            token.linkToDeath(this, 0);
            this.token = token;
        }

        public boolean setRestriction(int code, boolean restricted,
                PackageTagsList excludedPackageTags, int userId) {
            return mAppOpsRestrictions.setUserRestriction(token, userId, code,
                    restricted, excludedPackageTags);
        }

        public boolean hasRestriction(int code, String packageName, String attributionTag,
                int userId, boolean isCheckOp) {
            return mAppOpsRestrictions.getUserRestriction(token, userId, code, packageName,
                    attributionTag, isCheckOp);
        }

        public void removeUser(int userId) {
            mAppOpsRestrictions.clearUserRestrictions(token, userId);
        }

        public boolean isDefault() {
            return !mAppOpsRestrictions.hasUserRestrictions(token);
        }

        @Override
        public void binderDied() {
            synchronized (AppOpsService.this) {
                mAppOpsRestrictions.clearUserRestrictions(token);
                mOpUserRestrictions.remove(token);
                destroy();
            }
        }

        public void destroy() {
            token.unlinkToDeath(this, 0);
        }
    }

    private final class ClientGlobalRestrictionState implements DeathRecipient {
        final IBinder mToken;

        ClientGlobalRestrictionState(IBinder token)
                throws RemoteException {
            token.linkToDeath(this, 0);
            this.mToken = token;
        }

        boolean setRestriction(int code, boolean restricted) {
            return mAppOpsRestrictions.setGlobalRestriction(mToken, code, restricted);
        }

        boolean hasRestriction(int code) {
            return mAppOpsRestrictions.getGlobalRestriction(mToken, code);
        }

        boolean isDefault() {
            return !mAppOpsRestrictions.hasGlobalRestrictions(mToken);
        }

        @Override
        public void binderDied() {
            mAppOpsRestrictions.clearGlobalRestrictions(mToken);
            mOpGlobalRestrictions.remove(mToken);
            destroy();
        }

        void destroy() {
            mToken.unlinkToDeath(this, 0);
        }
    }

    private final class AppOpsManagerLocalImpl implements AppOpsManagerLocal {
        @Override
        public boolean isUidInForeground(int uid) {
            synchronized (AppOpsService.this) {
                return mUidStateTracker.isUidInForeground(uid);
            }
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


        @Override
        public void setGlobalRestriction(int code, boolean restricted, IBinder token) {
            if (Binder.getCallingPid() != Process.myPid()) {
                // TODO instead of this enforcement put in AppOpsManagerInternal
                throw new SecurityException("Only the system can set global restrictions");
            }

            synchronized (AppOpsService.this) {
                ClientGlobalRestrictionState restrictionState = mOpGlobalRestrictions.get(token);

                if (restrictionState == null) {
                    try {
                        restrictionState = new ClientGlobalRestrictionState(token);
                    } catch (RemoteException  e) {
                        return;
                    }
                    mOpGlobalRestrictions.put(token, restrictionState);
                }

                if (restrictionState.setRestriction(code, restricted)) {
                    mHandler.sendMessage(PooledLambda.obtainMessage(
                            AppOpsService::notifyWatchersOfChange, AppOpsService.this, code,
                            UID_ANY));
                    mHandler.sendMessage(PooledLambda.obtainMessage(
                            AppOpsService::updateStartedOpModeForUser, AppOpsService.this,
                            code, restricted, UserHandle.USER_ALL));
                }

                if (restrictionState.isDefault()) {
                    mOpGlobalRestrictions.remove(token);
                    restrictionState.destroy();
                }
            }
        }

        @Override
        public int getOpRestrictionCount(int code, UserHandle user, String pkg,
                String attributionTag) {
            int number = 0;
            synchronized (AppOpsService.this) {
                int numRestrictions = mOpUserRestrictions.size();
                for (int i = 0; i < numRestrictions; i++) {
                    if (mOpUserRestrictions.valueAt(i)
                            .hasRestriction(code, pkg, attributionTag, user.getIdentifier(),
                                    false)) {
                        number++;
                    }
                }

                numRestrictions = mOpGlobalRestrictions.size();
                for (int i = 0; i < numRestrictions; i++) {
                    if (mOpGlobalRestrictions.valueAt(i).hasRestriction(code)) {
                        number++;
                    }
                }
            }

            return number;
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

    @Immutable
    private final class CheckOpsDelegateDispatcher {
        private final @Nullable CheckOpsDelegate mPolicy;
        private final @Nullable CheckOpsDelegate mCheckOpsDelegate;

        CheckOpsDelegateDispatcher(@Nullable CheckOpsDelegate policy,
                @Nullable CheckOpsDelegate checkOpsDelegate) {
            mPolicy = policy;
            mCheckOpsDelegate = checkOpsDelegate;
        }

        public @NonNull CheckOpsDelegate getCheckOpsDelegate() {
            return mCheckOpsDelegate;
        }

        public int checkOperation(int code, int uid, String packageName,
                @Nullable String attributionTag, boolean raw) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.checkOperation(code, uid, packageName, attributionTag, raw,
                            this::checkDelegateOperationImpl);
                } else {
                    return mPolicy.checkOperation(code, uid, packageName, attributionTag, raw,
                            AppOpsService.this::checkOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return checkDelegateOperationImpl(code, uid, packageName, attributionTag, raw);
            }
            return checkOperationImpl(code, uid, packageName, attributionTag, raw);
        }

        private int checkDelegateOperationImpl(int code, int uid, String packageName,
                @Nullable String attributionTag, boolean raw) {
            return mCheckOpsDelegate.checkOperation(code, uid, packageName, attributionTag, raw,
                    AppOpsService.this::checkOperationImpl);
        }

        public int checkAudioOperation(int code, int usage, int uid, String packageName) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.checkAudioOperation(code, usage, uid, packageName,
                            this::checkDelegateAudioOperationImpl);
                } else {
                    return mPolicy.checkAudioOperation(code, usage, uid, packageName,
                            AppOpsService.this::checkAudioOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return checkDelegateAudioOperationImpl(code, usage, uid, packageName);
            }
            return checkAudioOperationImpl(code, usage, uid, packageName);
        }

        private int checkDelegateAudioOperationImpl(int code, int usage, int uid,
                String packageName) {
            return mCheckOpsDelegate.checkAudioOperation(code, usage, uid, packageName,
                    AppOpsService.this::checkAudioOperationImpl);
        }

        public SyncNotedAppOp noteOperation(int code, int uid, String packageName,
                String attributionTag, boolean shouldCollectAsyncNotedOp, String message,
                boolean shouldCollectMessage) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.noteOperation(code, uid, packageName, attributionTag,
                            shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                            this::noteDelegateOperationImpl);
                } else {
                    return mPolicy.noteOperation(code, uid, packageName, attributionTag,
                            shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                            AppOpsService.this::noteOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return noteDelegateOperationImpl(code, uid, packageName,
                        attributionTag, shouldCollectAsyncNotedOp, message, shouldCollectMessage);
            }
            return noteOperationImpl(code, uid, packageName, attributionTag,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage);
        }

        private SyncNotedAppOp noteDelegateOperationImpl(int code, int uid,
                @Nullable String packageName, @Nullable String featureId,
                boolean shouldCollectAsyncNotedOp, @Nullable String message,
                boolean shouldCollectMessage) {
            return mCheckOpsDelegate.noteOperation(code, uid, packageName, featureId,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    AppOpsService.this::noteOperationImpl);
        }

        public SyncNotedAppOp noteProxyOperation(int code, AttributionSource attributionSource,
                boolean shouldCollectAsyncNotedOp, @Nullable String message,
                boolean shouldCollectMessage, boolean skipProxyOperation) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.noteProxyOperation(code, attributionSource,
                            shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                            skipProxyOperation, this::noteDelegateProxyOperationImpl);
                } else {
                    return mPolicy.noteProxyOperation(code, attributionSource,
                            shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                            skipProxyOperation, AppOpsService.this::noteProxyOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return noteDelegateProxyOperationImpl(code,
                        attributionSource, shouldCollectAsyncNotedOp, message,
                        shouldCollectMessage, skipProxyOperation);
            }
            return noteProxyOperationImpl(code, attributionSource, shouldCollectAsyncNotedOp,
                    message, shouldCollectMessage,skipProxyOperation);
        }

        private SyncNotedAppOp noteDelegateProxyOperationImpl(int code,
                @NonNull AttributionSource attributionSource, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage,
                boolean skipProxyOperation) {
            return mCheckOpsDelegate.noteProxyOperation(code, attributionSource,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage, skipProxyOperation,
                    AppOpsService.this::noteProxyOperationImpl);
        }

        public SyncNotedAppOp startOperation(IBinder token, int code, int uid,
                @Nullable String packageName, @NonNull String attributionTag,
                boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp,
                @Nullable String message, boolean shouldCollectMessage,
                @AttributionFlags int attributionFlags, int attributionChainId) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.startOperation(token, code, uid, packageName,
                            attributionTag, startIfModeDefault, shouldCollectAsyncNotedOp, message,
                            shouldCollectMessage, attributionFlags, attributionChainId,
                            this::startDelegateOperationImpl);
                } else {
                    return mPolicy.startOperation(token, code, uid, packageName, attributionTag,
                            startIfModeDefault, shouldCollectAsyncNotedOp, message,
                            shouldCollectMessage, attributionFlags, attributionChainId,
                            AppOpsService.this::startOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return startDelegateOperationImpl(token, code, uid, packageName, attributionTag,
                        startIfModeDefault, shouldCollectAsyncNotedOp, message,
                        shouldCollectMessage, attributionFlags, attributionChainId);
            }
            return startOperationImpl(token, code, uid, packageName, attributionTag,
                    startIfModeDefault, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    attributionFlags, attributionChainId);
        }

        private SyncNotedAppOp startDelegateOperationImpl(IBinder token, int code, int uid,
                @Nullable String packageName, @Nullable String attributionTag,
                boolean startIfModeDefault, boolean shouldCollectAsyncNotedOp, String message,
                boolean shouldCollectMessage, @AttributionFlags int attributionFlags,
                int attributionChainId) {
            return mCheckOpsDelegate.startOperation(token, code, uid, packageName, attributionTag,
                    startIfModeDefault, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    attributionFlags, attributionChainId, AppOpsService.this::startOperationImpl);
        }

        public SyncNotedAppOp startProxyOperation(@NonNull IBinder clientId, int code,
                @NonNull AttributionSource attributionSource, boolean startIfModeDefault,
                boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
                boolean skipProxyOperation, @AttributionFlags int proxyAttributionFlags,
                @AttributionFlags int proxiedAttributionFlags, int attributionChainId) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    return mPolicy.startProxyOperation(clientId, code, attributionSource,
                            startIfModeDefault, shouldCollectAsyncNotedOp, message,
                            shouldCollectMessage, skipProxyOperation, proxyAttributionFlags,
                            proxiedAttributionFlags, attributionChainId,
                            this::startDelegateProxyOperationImpl);
                } else {
                    return mPolicy.startProxyOperation(clientId, code, attributionSource,
                            startIfModeDefault, shouldCollectAsyncNotedOp, message,
                            shouldCollectMessage, skipProxyOperation, proxyAttributionFlags,
                            proxiedAttributionFlags, attributionChainId,
                            AppOpsService.this::startProxyOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                return startDelegateProxyOperationImpl(clientId, code, attributionSource,
                        startIfModeDefault, shouldCollectAsyncNotedOp, message,
                        shouldCollectMessage, skipProxyOperation, proxyAttributionFlags,
                        proxiedAttributionFlags, attributionChainId);
            }
            return startProxyOperationImpl(clientId, code, attributionSource, startIfModeDefault,
                    shouldCollectAsyncNotedOp, message, shouldCollectMessage, skipProxyOperation,
                    proxyAttributionFlags, proxiedAttributionFlags, attributionChainId);
        }

        private SyncNotedAppOp startDelegateProxyOperationImpl(@NonNull IBinder clientId, int code,
                @NonNull AttributionSource attributionSource, boolean startIfModeDefault,
                boolean shouldCollectAsyncNotedOp, String message, boolean shouldCollectMessage,
                boolean skipProxyOperation, @AttributionFlags int proxyAttributionFlags,
                @AttributionFlags int proxiedAttributionFlsgs, int attributionChainId) {
            return mCheckOpsDelegate.startProxyOperation(clientId, code, attributionSource,
                    startIfModeDefault, shouldCollectAsyncNotedOp, message, shouldCollectMessage,
                    skipProxyOperation, proxyAttributionFlags, proxiedAttributionFlsgs,
                    attributionChainId, AppOpsService.this::startProxyOperationImpl);
        }

        public void finishOperation(IBinder clientId, int code, int uid, String packageName,
                String attributionTag) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    mPolicy.finishOperation(clientId, code, uid, packageName, attributionTag,
                            this::finishDelegateOperationImpl);
                } else {
                    mPolicy.finishOperation(clientId, code, uid, packageName, attributionTag,
                            AppOpsService.this::finishOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                finishDelegateOperationImpl(clientId, code, uid, packageName, attributionTag);
            } else {
                finishOperationImpl(clientId, code, uid, packageName, attributionTag);
            }
        }

        private void finishDelegateOperationImpl(IBinder clientId, int code, int uid,
                String packageName, String attributionTag) {
            mCheckOpsDelegate.finishOperation(clientId, code, uid, packageName, attributionTag,
                    AppOpsService.this::finishOperationImpl);
        }

        public void finishProxyOperation(@NonNull IBinder clientId, int code,
                @NonNull AttributionSource attributionSource, boolean skipProxyOperation) {
            if (mPolicy != null) {
                if (mCheckOpsDelegate != null) {
                    mPolicy.finishProxyOperation(clientId, code, attributionSource,
                            skipProxyOperation, this::finishDelegateProxyOperationImpl);
                } else {
                    mPolicy.finishProxyOperation(clientId, code, attributionSource,
                            skipProxyOperation, AppOpsService.this::finishProxyOperationImpl);
                }
            } else if (mCheckOpsDelegate != null) {
                finishDelegateProxyOperationImpl(clientId, code, attributionSource,
                        skipProxyOperation);
            } else {
                finishProxyOperationImpl(clientId, code, attributionSource, skipProxyOperation);
            }
        }

        private Void finishDelegateProxyOperationImpl(@NonNull IBinder clientId, int code,
                @NonNull AttributionSource attributionSource, boolean skipProxyOperation) {
            mCheckOpsDelegate.finishProxyOperation(clientId, code, attributionSource,
                    skipProxyOperation, AppOpsService.this::finishProxyOperationImpl);
            return null;
        }
    }
}
