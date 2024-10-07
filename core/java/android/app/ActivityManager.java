/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.app;

import static android.app.Instrumentation.DEBUG_FINISH_ACTIVITY;
import static android.app.WindowConfiguration.activityTypeToString;
import static android.app.WindowConfiguration.windowingModeToString;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;

import android.Manifest;
import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.hardware.HardwareBuffer;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.IpcDataCache;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PowerExemptionManager;
import android.os.PowerExemptionManager.ReasonCode;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.WorkSource;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Singleton;
import android.util.Size;
import android.view.WindowInsetsController.Appearance;
import android.window.TaskSnapshot;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.app.LocalePicker;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.MemInfoReader;
import com.android.internal.util.Preconditions;
import com.android.internal.util.RateLimitingCache;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.LocalServices;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * <p>
 * This class gives information about, and interacts
 * with, activities, services, and the containing
 * process.
 * </p>
 *
 * <p>
 * A number of the methods in this class are for
 * debugging or informational purposes and they should
 * not be used to affect any runtime behavior of
 * your app. These methods are called out as such in
 * the method level documentation.
 * </p>
 *
 *<p>
 * Most application developers should not have the need to
 * use this class, most of whose methods are for specialized
 * use cases. However, a few methods are more broadly applicable.
 * For instance, {@link android.app.ActivityManager#isLowRamDevice() isLowRamDevice()}
 * enables your app to detect whether it is running on a low-memory device,
 * and behave accordingly.
 * {@link android.app.ActivityManager#clearApplicationUserData() clearApplicationUserData()}
 * is for apps with reset-data functionality.
 * </p>
 *
 * <p>
 * In some special use cases, where an app interacts with
 * its Task stack, the app may use the
 * {@link android.app.ActivityManager.AppTask} and
 * {@link android.app.ActivityManager.RecentTaskInfo} inner
 * classes. However, in general, the methods in this class should
 * be used for testing and debugging purposes only.
 * </p>
 */
@SystemService(Context.ACTIVITY_SERVICE)
@android.ravenwood.annotation.RavenwoodKeepPartialClass
public class ActivityManager {
    private static String TAG = "ActivityManager";

    @UnsupportedAppUsage
    private final Context mContext;

    private static volatile boolean sSystemReady = false;


    private static final int FIRST_START_FATAL_ERROR_CODE = -100;
    private static final int LAST_START_FATAL_ERROR_CODE = -1;
    private static final int FIRST_START_SUCCESS_CODE = 0;
    private static final int LAST_START_SUCCESS_CODE = 99;
    private static final int FIRST_START_NON_FATAL_ERROR_CODE = 100;
    private static final int LAST_START_NON_FATAL_ERROR_CODE = 199;

    /**
     * Disable hidden API checks for the newly started instrumentation.
     * @hide
     */
    public static final int INSTR_FLAG_DISABLE_HIDDEN_API_CHECKS = 1 << 0;
    /**
     * Grant full access to the external storage for the newly started instrumentation.
     * @hide
     */
    public static final int INSTR_FLAG_DISABLE_ISOLATED_STORAGE = 1 << 1;

    /**
     * Disable test API access for the newly started instrumentation.
     * @hide
     */
    public static final int INSTR_FLAG_DISABLE_TEST_API_CHECKS = 1 << 2;

    /**
     * Do not restart the target process when starting or finishing instrumentation.
     * @hide
     */
    public static final int INSTR_FLAG_NO_RESTART = 1 << 3;
    /**
     * Force the check that instrumentation and the target package are signed with the same
     * certificate even if {@link Build#IS_DEBUGGABLE} is {@code true}.
     * @hide
     */
    public static final int INSTR_FLAG_ALWAYS_CHECK_SIGNATURE = 1 << 4;
    /**
     * Instrument Sdk Sandbox process that corresponds to the target package.
     * @hide
     */
    public static final int INSTR_FLAG_INSTRUMENT_SDK_SANDBOX = 1 << 5;
    /**
     * Instrument an Sdk Sandbox process corresponding to an Sdk running inside the sandbox.
     * @hide
     */
    public static final int INSTR_FLAG_INSTRUMENT_SDK_IN_SANDBOX = 1 << 6;

    static final class MyUidObserver extends UidObserver {
        final OnUidImportanceListener mListener;
        final Context mContext;

        MyUidObserver(OnUidImportanceListener listener, Context clientContext) {
            mListener = listener;
            mContext = clientContext;
        }

        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq, int capability) {
            mListener.onUidImportance(uid, RunningAppProcessInfo.procStateToImportanceForClient(
                    procState, mContext));
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            mListener.onUidImportance(uid, RunningAppProcessInfo.IMPORTANCE_GONE);
        }
    }

    final ArrayMap<OnUidImportanceListener, MyUidObserver> mImportanceListeners = new ArrayMap<>();

    /** Rate-Limiting Cache that allows no more than 400 calls to the service per second. */
    private static final RateLimitingCache<List<RunningAppProcessInfo>> mRunningProcessesCache =
            new RateLimitingCache<>(10, 4);

    /** Rate-Limiting Cache that allows no more than 200 calls to the service per second. */
    private static final RateLimitingCache<List<ProcessErrorStateInfo>> mErrorProcessesCache =
            new RateLimitingCache<>(10, 2);

    /** Rate-Limiting cache that allows no more than 100 calls to the service per second. */
    @GuardedBy("mMemoryInfoCache")
    private static final RateLimitingCache<MemoryInfo> mMemoryInfoCache =
            new RateLimitingCache<>(10);
    /** Used to store cached results for rate-limited calls to getMemoryInfo(). */
    @GuardedBy("mMemoryInfoCache")
    private static final MemoryInfo mRateLimitedMemInfo = new MemoryInfo();

    /** Rate-Limiting cache that allows no more than 200 calls to the service per second. */
    @GuardedBy("mMyMemoryStateCache")
    private static final RateLimitingCache<RunningAppProcessInfo> mMyMemoryStateCache =
            new RateLimitingCache<>(10, 2);
    /** Used to store cached results for rate-limited calls to getMyMemoryState(). */
    @GuardedBy("mMyMemoryStateCache")
    private static final RunningAppProcessInfo mRateLimitedMemState = new RunningAppProcessInfo();

    /**
     * Query handler for mGetCurrentUserIdCache - returns a cached value of the current foreground
     * user id if the backstage_power/android.app.cache_get_current_user_id flag is enabled.
     */
    private static final IpcDataCache.QueryHandler<Void, Integer> mGetCurrentUserIdQuery =
            new IpcDataCache.QueryHandler<>() {
                @Override
                public Integer apply(Void query) {
                    try {
                        return getService().getCurrentUserId();
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }

                @Override
                public boolean shouldBypassCache(Void query) {
                    // If the flag to enable the new caching behavior is off, bypass the cache.
                    return !Flags.cacheGetCurrentUserId();
                }
            };

    /** A cache which maintains the current foreground user id. */
    private static final IpcDataCache<Void, Integer> mGetCurrentUserIdCache =
            new IpcDataCache<>(1, IpcDataCache.MODULE_SYSTEM,
                    /* api= */ "getCurrentUserId", /* cacheName= */ "CurrentUserIdCache",
                    mGetCurrentUserIdQuery);

    /**
     * The current foreground user has changed - invalidate the cache. Currently only called from
     * UserController when a user switch occurs.
     * @hide
     */
    public static void invalidateGetCurrentUserIdCache() {
        IpcDataCache.invalidateCache(
                IpcDataCache.MODULE_SYSTEM, /* api= */ "getCurrentUserId");
    }

    /**
     * Map of callbacks that have registered for {@link UidFrozenStateChanged} events.
     * Will be called when a Uid has become frozen or unfrozen.
     */
    private final ArrayMap<UidFrozenStateChangedCallback, Executor> mFrozenStateChangedCallbacks =
             new ArrayMap<>();

    private final IUidFrozenStateChangedCallback mFrozenStateChangedCallback =
            new IUidFrozenStateChangedCallback.Stub() {
            @Override
            public void onUidFrozenStateChanged(int[] uids, int[] frozenStates) {
                synchronized (mFrozenStateChangedCallbacks) {
                    mFrozenStateChangedCallbacks.forEach((callback, executor) -> {
                        executor.execute(
                                () -> callback.onUidFrozenStateChanged(uids, frozenStates));
                    });
                }
            }
        };

    /**
     * Callback object for {@link #registerUidFrozenStateChangedCallback}
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public interface UidFrozenStateChangedCallback {
        /**
         * Indicates that the UID was frozen.
         *
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @TestApi
        int UID_FROZEN_STATE_FROZEN = 1;

        /**
         * Indicates that the UID was unfrozen.
         *
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @TestApi
        int UID_FROZEN_STATE_UNFROZEN = 2;

        /**
         * @hide
         */
        @Retention(RetentionPolicy.SOURCE)
        @IntDef(flag = false, prefix = {"UID_FROZEN_STATE_"}, value = {
                UID_FROZEN_STATE_FROZEN,
                UID_FROZEN_STATE_UNFROZEN,
        })
        public @interface UidFrozenState {}

        /**
         * Notify the client that the frozen states of an array of UIDs have changed.
         *
         * @param uids The UIDs for which the frozen state has changed
         * @param frozenStates Frozen state for each UID index, Will be set to
         *               {@link UidFrozenStateChangedCallback#UID_FROZEN_STATE_FROZEN}
         *               when the UID is frozen. When the UID is unfrozen,
         *               {@link UidFrozenStateChangedCallback#UID_FROZEN_STATE_UNFROZEN}
         *               will be set.
         *
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        @TestApi
        void onUidFrozenStateChanged(@NonNull int[] uids,
                @NonNull @UidFrozenState int[] frozenStates);
    }

    /**
     * Register a {@link UidFrozenStateChangedCallback} object to receive notification
     * when a UID is frozen or unfrozen. Will throw an exception if the same
     * callback object is registered more than once.
     *
     * @param executor The executor that the callback will be run from.
     * @param callback The callback to be registered. Callbacks for previous frozen/unfrozen
     *                 UID changes will not be delivered. Only changes in state from the point of
     *                 registration onward will be reported.
     * @throws IllegalStateException if the {@code callback} is already registered.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public void registerUidFrozenStateChangedCallback(
            @NonNull Executor executor,
            @NonNull UidFrozenStateChangedCallback callback) {
        Preconditions.checkNotNull(executor, "executor cannot be null");
        Preconditions.checkNotNull(callback, "callback cannot be null");
        synchronized (mFrozenStateChangedCallbacks) {
            if (mFrozenStateChangedCallbacks.containsKey(callback)) {
                throw new IllegalStateException("Callback already registered: " + callback);
            }
            mFrozenStateChangedCallbacks.put(callback, executor);
            if (mFrozenStateChangedCallbacks.size() > 1) {
                /* There's no need to register more than one binder interface */
                return;
            }

            try {
                getService().registerUidFrozenStateChangedCallback(mFrozenStateChangedCallback);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Unregister a {@link UidFrozenStateChangedCallback} callback.
     * @param callback The callback to be unregistered.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public void unregisterUidFrozenStateChangedCallback(
            @NonNull UidFrozenStateChangedCallback callback) {
        Preconditions.checkNotNull(callback, "callback cannot be null");
        synchronized (mFrozenStateChangedCallbacks) {
            mFrozenStateChangedCallbacks.remove(callback);
            if (mFrozenStateChangedCallbacks.isEmpty()) {
                try {
                    getService().unregisterUidFrozenStateChangedCallback(
                            mFrozenStateChangedCallback);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Query the frozen state of a list of UIDs.
     *
     * @param uids the array of UIDs which the client would like to know the frozen state of.
     * @return An array containing the frozen state for each requested UID, by index. Will be set
     *               to {@link UidFrozenStateChangedCallback#UID_FROZEN_STATE_FROZEN}
     *               if the UID is frozen. If the UID is not frozen or not found,
     *               {@link UidFrozenStateChangedCallback#UID_FROZEN_STATE_UNFROZEN}
     *               will be set.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    public @NonNull @UidFrozenStateChangedCallback.UidFrozenState
            int[] getUidFrozenState(@NonNull int[] uids) {
        try {
            return getService().getUidFrozenState(uids);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * <a href="{@docRoot}guide/topics/manifest/meta-data-element.html">{@code
     * <meta-data>}</a> name for a 'home' Activity that declares a package that is to be
     * uninstalled in lieu of the declaring one.  The package named here must be
     * signed with the same certificate as the one declaring the {@code <meta-data>}.
     */
    public static final String META_HOME_ALTERNATE = "android.app.home.alternate";

    // NOTE: Before adding a new start result, please reference the defined ranges to ensure the
    // result is properly categorized.

    /**
     * Result for IActivityManager.startVoiceActivity: active session is currently hidden.
     * @hide
     */
    public static final int START_VOICE_HIDDEN_SESSION = FIRST_START_FATAL_ERROR_CODE;

    /**
     * Result for IActivityManager.startVoiceActivity: active session does not match
     * the requesting token.
     * @hide
     */
    public static final int START_VOICE_NOT_ACTIVE_SESSION = FIRST_START_FATAL_ERROR_CODE + 1;

    /**
     * Result for IActivityManager.startActivity: trying to start a background user
     * activity that shouldn't be displayed for all users.
     * @hide
     */
    public static final int START_NOT_CURRENT_USER_ACTIVITY = FIRST_START_FATAL_ERROR_CODE + 2;

    /**
     * Result for IActivityManager.startActivity: trying to start an activity under voice
     * control when that activity does not support the VOICE category.
     * @hide
     */
    public static final int START_NOT_VOICE_COMPATIBLE = FIRST_START_FATAL_ERROR_CODE + 3;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * start had to be canceled.
     * @hide
     */
    public static final int START_CANCELED = FIRST_START_FATAL_ERROR_CODE + 4;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * thing being started is not an activity.
     * @hide
     */
    public static final int START_NOT_ACTIVITY = FIRST_START_FATAL_ERROR_CODE + 5;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * caller does not have permission to start the activity.
     * @hide
     */
    public static final int START_PERMISSION_DENIED = FIRST_START_FATAL_ERROR_CODE + 6;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * caller has requested both to forward a result and to receive
     * a result.
     * @hide
     */
    public static final int START_FORWARD_AND_REQUEST_CONFLICT = FIRST_START_FATAL_ERROR_CODE + 7;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * requested class is not found.
     * @hide
     */
    public static final int START_CLASS_NOT_FOUND = FIRST_START_FATAL_ERROR_CODE + 8;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * given Intent could not be resolved to an activity.
     * @hide
     */
    public static final int START_INTENT_NOT_RESOLVED = FIRST_START_FATAL_ERROR_CODE + 9;

    /**
     * Result for IActivityManager.startAssistantActivity: active session is currently hidden.
     * @hide
     */
    public static final int START_ASSISTANT_HIDDEN_SESSION = FIRST_START_FATAL_ERROR_CODE + 10;

    /**
     * Result for IActivityManager.startAssistantActivity: active session does not match
     * the requesting token.
     * @hide
     */
    public static final int START_ASSISTANT_NOT_ACTIVE_SESSION = FIRST_START_FATAL_ERROR_CODE + 11;

    /**
     * Result for IActivityManaqer.startActivity: the activity was started
     * successfully as normal.
     * @hide
     */
    public static final int START_SUCCESS = FIRST_START_SUCCESS_CODE;

    /**
     * Result for IActivityManaqer.startActivity: the caller asked that the Intent not
     * be executed if it is the recipient, and that is indeed the case.
     * @hide
     */
    public static final int START_RETURN_INTENT_TO_CALLER = FIRST_START_SUCCESS_CODE + 1;

    /**
     * Result for IActivityManaqer.startActivity: activity was started or brought forward in an
     * existing task which was brought to the foreground.
     * @hide
     */
    public static final int START_TASK_TO_FRONT = FIRST_START_SUCCESS_CODE + 2;

    /**
     * Result for IActivityManaqer.startActivity: activity wasn't really started, but
     * the given Intent was given to the existing top activity.
     * @hide
     */
    public static final int START_DELIVERED_TO_TOP = FIRST_START_SUCCESS_CODE + 3;

    /**
     * Result for IActivityManaqer.startActivity: request was canceled because
     * app switches are temporarily canceled to ensure the user's last request
     * (such as pressing home) is performed.
     * @hide
     */
    public static final int START_SWITCHES_CANCELED = FIRST_START_NON_FATAL_ERROR_CODE;

    /**
     * Result for IActivityManaqer.startActivity: a new activity was attempted to be started
     * while in Lock Task Mode.
     * @hide
     */
    public static final int START_RETURN_LOCK_TASK_MODE_VIOLATION =
            FIRST_START_NON_FATAL_ERROR_CODE + 1;

    /**
     * Result for IActivityManaqer.startActivity: a new activity start was aborted. Never returned
     * externally.
     * @hide
     */
    public static final int START_ABORTED = FIRST_START_NON_FATAL_ERROR_CODE + 2;

    /**
     * Flag for IActivityManaqer.startActivity: do special start mode where
     * a new activity is launched only if it is needed.
     * @hide
     */
    public static final int START_FLAG_ONLY_IF_NEEDED = 1<<0;

    /**
     * Flag for IActivityManaqer.startActivity: launch the app for
     * debugging.
     * @hide
     */
    public static final int START_FLAG_DEBUG = 1<<1;

    /**
     * Flag for IActivityManaqer.startActivity: launch the app for
     * allocation tracking.
     * @hide
     */
    public static final int START_FLAG_TRACK_ALLOCATION = 1<<2;

    /**
     * Flag for IActivityManaqer.startActivity: launch the app with
     * native debugging support.
     * @hide
     */
    public static final int START_FLAG_NATIVE_DEBUGGING = 1<<3;

    /**
     * Flag for IActivityManaqer.startActivity: launch the app for
     * debugging and suspend threads.
     * @hide
     */
    public static final int START_FLAG_DEBUG_SUSPEND = 1 << 4;

    /**
     * Result for IActivityManaqer.broadcastIntent: success!
     * @hide
     */
    public static final int BROADCAST_SUCCESS = 0;

    /**
     * Result for IActivityManaqer.broadcastIntent: attempt to broadcast
     * a sticky intent without appropriate permission.
     * @hide
     */
    public static final int BROADCAST_STICKY_CANT_HAVE_PERMISSION = -1;

    /**
     * Result for IActivityManager.broadcastIntent: trying to send a broadcast
     * to a stopped user. Fail.
     * @hide
     */
    public static final int BROADCAST_FAILED_USER_STOPPED = -2;

    /**
     * Type for IActivityManaqer.getIntentSender: this PendingIntent type is unknown.
     * @hide
     */
    public static final int INTENT_SENDER_UNKNOWN = 0;

    /**
     * Type for IActivityManaqer.getIntentSender: this PendingIntent is
     * for a sendBroadcast operation.
     * @hide
     */
    public static final int INTENT_SENDER_BROADCAST = 1;

    /**
     * Type for IActivityManaqer.getIntentSender: this PendingIntent is
     * for a startActivity operation.
     * @hide
     */
    @UnsupportedAppUsage
    public static final int INTENT_SENDER_ACTIVITY = 2;

    /**
     * Type for IActivityManaqer.getIntentSender: this PendingIntent is
     * for an activity result operation.
     * @hide
     */
    public static final int INTENT_SENDER_ACTIVITY_RESULT = 3;

    /**
     * Type for IActivityManaqer.getIntentSender: this PendingIntent is
     * for a startService operation.
     * @hide
     */
    public static final int INTENT_SENDER_SERVICE = 4;

    /**
     * Type for IActivityManaqer.getIntentSender: this PendingIntent is
     * for a startForegroundService operation.
     * @hide
     */
    public static final int INTENT_SENDER_FOREGROUND_SERVICE = 5;

    /** @hide User operation call: success! */
    public static final int USER_OP_SUCCESS = 0;

    /** @hide User operation call: given user id is not known. */
    public static final int USER_OP_UNKNOWN_USER = -1;

    /** @hide User operation call: given user id is the current user, can't be stopped. */
    public static final int USER_OP_IS_CURRENT = -2;

    /** @hide User operation call: system user can't be stopped. */
    public static final int USER_OP_ERROR_IS_SYSTEM = -3;

    /** @hide User operation call: one of related users cannot be stopped. */
    public static final int USER_OP_ERROR_RELATED_USERS_CANNOT_STOP = -4;

    /**
     * Process states, describing the kind of state a particular process is in.
     * When updating these, make sure to also check all related references to the
     * constant in code, and update these arrays:
     *
     * @see com.android.internal.app.procstats.ProcessState#PROCESS_STATE_TO_STATE
     * @see com.android.server.am.ProcessList#sProcStateToProcMem
     * @see com.android.server.am.ProcessList#sFirstAwakePssTimes
     * @see com.android.server.am.ProcessList#sSameAwakePssTimes
     * @see com.android.server.am.ProcessList#sTestFirstPssTimes
     * @see com.android.server.am.ProcessList#sTestSamePssTimes
     * @hide
     */
    @IntDef(flag = false, prefix = { "PROCESS_STATE_" }, value = {
        PROCESS_STATE_UNKNOWN, // -1
        PROCESS_STATE_PERSISTENT, // 0
        PROCESS_STATE_PERSISTENT_UI,
        PROCESS_STATE_TOP,
        PROCESS_STATE_BOUND_TOP,
        PROCESS_STATE_FOREGROUND_SERVICE,
        PROCESS_STATE_BOUND_FOREGROUND_SERVICE,
        PROCESS_STATE_IMPORTANT_FOREGROUND,
        PROCESS_STATE_IMPORTANT_BACKGROUND,
        PROCESS_STATE_TRANSIENT_BACKGROUND,
        PROCESS_STATE_BACKUP,
        PROCESS_STATE_SERVICE,
        PROCESS_STATE_RECEIVER,
        PROCESS_STATE_TOP_SLEEPING,
        PROCESS_STATE_HEAVY_WEIGHT,
        PROCESS_STATE_HOME,
        PROCESS_STATE_LAST_ACTIVITY,
        PROCESS_STATE_CACHED_ACTIVITY,
        PROCESS_STATE_CACHED_ACTIVITY_CLIENT,
        PROCESS_STATE_CACHED_RECENT,
        PROCESS_STATE_CACHED_EMPTY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProcessState {}

    /*
     * PROCESS_STATE_* must come from frameworks/base/core/java/android/app/ProcessStateEnum.aidl.
     * This is to make sure that Java side uses the same values as native.
     */

    /** @hide Not a real process state. */
    public static final int PROCESS_STATE_UNKNOWN = ProcessStateEnum.UNKNOWN;

    /** @hide Process is a persistent system process. */
    public static final int PROCESS_STATE_PERSISTENT = ProcessStateEnum.PERSISTENT;

    /** @hide Process is a persistent system process and is doing UI. */
    public static final int PROCESS_STATE_PERSISTENT_UI = ProcessStateEnum.PERSISTENT_UI;

    /** @hide Process is hosting the current top activities.  Note that this covers
     * all activities that are visible to the user. */
    @UnsupportedAppUsage
    @TestApi
    public static final int PROCESS_STATE_TOP = ProcessStateEnum.TOP;

    /** @hide Process is bound to a TOP app. */
    public static final int PROCESS_STATE_BOUND_TOP = ProcessStateEnum.BOUND_TOP;

    /** @hide Process is hosting a foreground service. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static final int PROCESS_STATE_FOREGROUND_SERVICE = ProcessStateEnum.FOREGROUND_SERVICE;

    /** @hide Process is hosting a foreground service due to a system binding. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
    public static final int PROCESS_STATE_BOUND_FOREGROUND_SERVICE =
            ProcessStateEnum.BOUND_FOREGROUND_SERVICE;

    /** @hide Process is important to the user, and something they are aware of. */
    public static final int PROCESS_STATE_IMPORTANT_FOREGROUND =
            ProcessStateEnum.IMPORTANT_FOREGROUND;

    /** @hide Process is important to the user, but not something they are aware of. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROCESS_STATE_IMPORTANT_BACKGROUND =
            ProcessStateEnum.IMPORTANT_BACKGROUND;

    /** @hide Process is in the background transient so we will try to keep running. */
    public static final int PROCESS_STATE_TRANSIENT_BACKGROUND =
            ProcessStateEnum.TRANSIENT_BACKGROUND;

    /** @hide Process is in the background running a backup/restore operation. */
    public static final int PROCESS_STATE_BACKUP = ProcessStateEnum.BACKUP;

    /** @hide Process is in the background running a service.  Unlike oom_adj, this level
     * is used for both the normal running in background state and the executing
     * operations state. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROCESS_STATE_SERVICE = ProcessStateEnum.SERVICE;

    /** @hide Process is in the background running a receiver.   Note that from the
     * perspective of oom_adj, receivers run at a higher foreground level, but for our
     * prioritization here that is not necessary and putting them below services means
     * many fewer changes in some process states as they receive broadcasts. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROCESS_STATE_RECEIVER = ProcessStateEnum.RECEIVER;

    /** @hide Same as {@link #PROCESS_STATE_TOP} but while device is sleeping. */
    public static final int PROCESS_STATE_TOP_SLEEPING = ProcessStateEnum.TOP_SLEEPING;

    /** @hide Process is in the background, but it can't restore its state so we want
     * to try to avoid killing it. */
    public static final int PROCESS_STATE_HEAVY_WEIGHT = ProcessStateEnum.HEAVY_WEIGHT;

    /** @hide Process is in the background but hosts the home activity. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROCESS_STATE_HOME = ProcessStateEnum.HOME;

    /** @hide Process is in the background but hosts the last shown activity. */
    public static final int PROCESS_STATE_LAST_ACTIVITY = ProcessStateEnum.LAST_ACTIVITY;

    /** @hide Process is being cached for later use and contains activities. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int PROCESS_STATE_CACHED_ACTIVITY = ProcessStateEnum.CACHED_ACTIVITY;

    /** @hide Process is being cached for later use and is a client of another cached
     * process that contains activities. */
    public static final int PROCESS_STATE_CACHED_ACTIVITY_CLIENT =
            ProcessStateEnum.CACHED_ACTIVITY_CLIENT;

    /** @hide Process is being cached for later use and has an activity that corresponds
     * to an existing recent task. */
    public static final int PROCESS_STATE_CACHED_RECENT = ProcessStateEnum.CACHED_RECENT;

    /** @hide Process is being cached for later use and is empty. */
    public static final int PROCESS_STATE_CACHED_EMPTY = ProcessStateEnum.CACHED_EMPTY;

    /** @hide Process does not exist. */
    public static final int PROCESS_STATE_NONEXISTENT = ProcessStateEnum.NONEXISTENT;

    /**
     * The set of flags for process capability.
     * Keep it in sync with ProcessCapability in atoms.proto.
     * @hide
     */
    @IntDef(flag = true, prefix = { "PROCESS_CAPABILITY_" }, value = {
            PROCESS_CAPABILITY_NONE,
            PROCESS_CAPABILITY_FOREGROUND_LOCATION,
            PROCESS_CAPABILITY_FOREGROUND_CAMERA,
            PROCESS_CAPABILITY_FOREGROUND_MICROPHONE,
            PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK,
            PROCESS_CAPABILITY_BFSL,
            PROCESS_CAPABILITY_USER_RESTRICTED_NETWORK,
            PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProcessCapability {}

    /**
     * Used to log FGS API events from CAMERA API
     * @hide
     */
    @SystemApi
    public static final int FOREGROUND_SERVICE_API_TYPE_CAMERA = 1;

    /**
     * Used to log FGS API events from BLUETOOTH API, used
     * with FGS type of CONNECTED_DEVICE
     * @hide
     */
    @SystemApi
    public static final int FOREGROUND_SERVICE_API_TYPE_BLUETOOTH = 2;
    /**
     * Used to log FGS API events from Location API.
     * @hide
     */
    @SystemApi
    public static final int FOREGROUND_SERVICE_API_TYPE_LOCATION = 3;
    /**
     * Used to log FGS API events from media playback API
     * @hide
     */
    @SystemApi
    public static final int FOREGROUND_SERVICE_API_TYPE_MEDIA_PLAYBACK = 4;
    /**
     * Used to log FGS API events from Audio API
     * @hide
     */
    @SystemApi
    public static final int FOREGROUND_SERVICE_API_TYPE_AUDIO = 5;
    /**
     * Used to log FGS API events from microphone API
     * @hide
     */
    @SystemApi
    public static final int FOREGROUND_SERVICE_API_TYPE_MICROPHONE = 6;
    /**
     * Used to log FGS API events from phone API
     * @hide
     */
    @SystemApi
    public static final int FOREGROUND_SERVICE_API_TYPE_PHONE_CALL = 7;
    /**
     * Used to log FGS API events from USB API
     * @hide
     */
    @SystemApi
    public static final int FOREGROUND_SERVICE_API_TYPE_USB = 8;
    /**
     * Used to log FGS API events from CDM API
     * @hide
     */
    @SystemApi
    public static final int FOREGROUND_SERVICE_API_TYPE_CDM = 9;

    /**
     * Constants used to denote what API type
     * is creating an API event for logging.
     * @hide
     */
    @IntDef(flag = false, prefix = { "FOREGROUND_SERVICE_API_TYPE_" }, value = {
            FOREGROUND_SERVICE_API_TYPE_CAMERA,
            FOREGROUND_SERVICE_API_TYPE_BLUETOOTH,
            FOREGROUND_SERVICE_API_TYPE_LOCATION,
            FOREGROUND_SERVICE_API_TYPE_MEDIA_PLAYBACK,
            FOREGROUND_SERVICE_API_TYPE_AUDIO,
            FOREGROUND_SERVICE_API_TYPE_MICROPHONE,
            FOREGROUND_SERVICE_API_TYPE_PHONE_CALL,
            FOREGROUND_SERVICE_API_TYPE_USB,
            FOREGROUND_SERVICE_API_TYPE_CDM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ForegroundServiceApiType {}

    /**
     * Used to log a start event for an FGS API
     * @hide
     */
    public static final int FOREGROUND_SERVICE_API_EVENT_BEGIN = 1;
    /**
     * Used to log a stop event for an FGS API
     * @hide
     */
    public static final int FOREGROUND_SERVICE_API_EVENT_END = 2;
    /**
     * Constants used to denote API state
     * during an API event for logging.
     * @hide
     */
    @IntDef(flag = false, prefix = { "FOREGROUND_SERVICE_API_EVENT_" }, value = {
            FOREGROUND_SERVICE_API_EVENT_BEGIN,
            FOREGROUND_SERVICE_API_EVENT_END,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ForegroundServiceApiEvent {}

    /** @hide Process does not have any capability */
    @SystemApi
    public static final int PROCESS_CAPABILITY_NONE = 0;

    /** @hide Process can access location while in foreground */
    @SystemApi
    public static final int PROCESS_CAPABILITY_FOREGROUND_LOCATION = 1 << 0;

    /** @hide Process can access camera while in foreground */
    @SystemApi
    public static final int PROCESS_CAPABILITY_FOREGROUND_CAMERA = 1 << 1;

    /** @hide Process can access microphone while in foreground */
    @SystemApi
    public static final int PROCESS_CAPABILITY_FOREGROUND_MICROPHONE = 1 << 2;

    /** @hide Process can access network despite any power saving restrictions */
    @TestApi
    public static final int PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK = 1 << 3;

    /**
     * Flag used to indicate whether an app is allowed to start a foreground service from the
     * background, decided by the procstates. ("BFSL" == "background foreground service launch")
     *
     * - BFSL has a number of exemptions -- e.g. when an app is power-allowlisted, including
     *   temp-allowlist -- but this capability is *not* used to represent such exemptions.
     *   This is set only based on the procstate and the foreground service type.
     * - Basically, procstates <= BFGS (i.e. BFGS, FGS, BTOP, TOP, ...) are BFSL-allowed,
     *   and that's how things worked on Android S/T.
     *   However, Android U added a "SHORT_SERVICE" FGS type, which gets the FGS procstate
     *   *but* can't start another FGS. So now we use this flag to decide whether FGS/BFGS
     *   procstates are BFSL-allowed. (higher procstates, such as BTOP, will still always be
     *   BFSL-allowed.)
     *   We propagate this flag across via service bindings and provider references.
     *
     * @hide
     */
    public static final int PROCESS_CAPABILITY_BFSL = 1 << 4;

    /**
     * @hide
     * Process can access network at a high enough proc state despite any user restrictions.
     */
    @TestApi
    public static final int PROCESS_CAPABILITY_USER_RESTRICTED_NETWORK = 1 << 5;

    /**
     * @hide
     * Process can access volume APIs and can request audio focus with GAIN.
     */
    public static final int PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL = 1 << 6;

    /**
     * @hide all capabilities, the ORing of all flags in {@link ProcessCapability}.
     *
     * Don't expose it as TestApi -- we may add new capabilities any time, which could
     * break CTS tests if they relied on it.
     */
    public static final int PROCESS_CAPABILITY_ALL = PROCESS_CAPABILITY_FOREGROUND_LOCATION
            | PROCESS_CAPABILITY_FOREGROUND_CAMERA
            | PROCESS_CAPABILITY_FOREGROUND_MICROPHONE
            | PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK
            | PROCESS_CAPABILITY_BFSL
            | PROCESS_CAPABILITY_USER_RESTRICTED_NETWORK
            | PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL;

    /**
     * All implicit capabilities. There are capabilities that process automatically have.
     * @hide
     */
    @TestApi
    public static final int PROCESS_CAPABILITY_ALL_IMPLICIT = PROCESS_CAPABILITY_FOREGROUND_CAMERA
            | PROCESS_CAPABILITY_FOREGROUND_MICROPHONE;

    /**
     * Print capability bits in human-readable form.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static void printCapabilitiesSummary(PrintWriter pw, @ProcessCapability int caps) {
        pw.print((caps & PROCESS_CAPABILITY_FOREGROUND_LOCATION) != 0 ? 'L' : '-');
        pw.print((caps & PROCESS_CAPABILITY_FOREGROUND_CAMERA) != 0 ? 'C' : '-');
        pw.print((caps & PROCESS_CAPABILITY_FOREGROUND_MICROPHONE) != 0 ? 'M' : '-');
        pw.print((caps & PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK) != 0 ? 'N' : '-');
        pw.print((caps & PROCESS_CAPABILITY_BFSL) != 0 ? 'F' : '-');
        pw.print((caps & PROCESS_CAPABILITY_USER_RESTRICTED_NETWORK) != 0 ? 'U' : '-');
        pw.print((caps & PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL) != 0 ? 'A' : '-');
    }

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static void printCapabilitiesSummary(StringBuilder sb, @ProcessCapability int caps) {
        sb.append((caps & PROCESS_CAPABILITY_FOREGROUND_LOCATION) != 0 ? 'L' : '-');
        sb.append((caps & PROCESS_CAPABILITY_FOREGROUND_CAMERA) != 0 ? 'C' : '-');
        sb.append((caps & PROCESS_CAPABILITY_FOREGROUND_MICROPHONE) != 0 ? 'M' : '-');
        sb.append((caps & PROCESS_CAPABILITY_POWER_RESTRICTED_NETWORK) != 0 ? 'N' : '-');
        sb.append((caps & PROCESS_CAPABILITY_BFSL) != 0 ? 'F' : '-');
        sb.append((caps & PROCESS_CAPABILITY_USER_RESTRICTED_NETWORK) != 0 ? 'U' : '-');
        sb.append((caps & PROCESS_CAPABILITY_FOREGROUND_AUDIO_CONTROL) != 0 ? 'A' : '-');
    }

    /**
     * Print capability bits in human-readable form.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static void printCapabilitiesFull(PrintWriter pw, @ProcessCapability int caps) {
        printCapabilitiesSummary(pw, caps);
        final int remain = caps & ~PROCESS_CAPABILITY_ALL;
        if (remain != 0) {
            pw.print("+0x");
            pw.print(Integer.toHexString(remain));
        }
    }

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static String getCapabilitiesSummary(@ProcessCapability int caps) {
        final StringBuilder sb = new StringBuilder();
        printCapabilitiesSummary(sb, caps);
        return sb.toString();
    }

    // NOTE: If PROCESS_STATEs are added, then new fields must be added
    // to frameworks/base/core/proto/android/app/enums.proto and the following method must
    // be updated to correctly map between them.
    // However, if the current ActivityManager values are merely modified, no update should be made
    // to enums.proto, to which values can only be added but never modified. Note that the proto
    // versions do NOT have the ordering restrictions of the ActivityManager process state.
    /**
     * Maps ActivityManager.PROCESS_STATE_ values to enums.proto ProcessStateEnum value.
     *
     * @param amInt a process state of the form ActivityManager.PROCESS_STATE_
     * @return the value of the corresponding enums.proto ProcessStateEnum value.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static final int processStateAmToProto(int amInt) {
        switch (amInt) {
            case PROCESS_STATE_UNKNOWN:
                return AppProtoEnums.PROCESS_STATE_UNKNOWN;
            case PROCESS_STATE_PERSISTENT:
                return AppProtoEnums.PROCESS_STATE_PERSISTENT;
            case PROCESS_STATE_PERSISTENT_UI:
                return AppProtoEnums.PROCESS_STATE_PERSISTENT_UI;
            case PROCESS_STATE_TOP:
                return AppProtoEnums.PROCESS_STATE_TOP;
            case PROCESS_STATE_BOUND_TOP:
                return AppProtoEnums.PROCESS_STATE_BOUND_TOP;
            case PROCESS_STATE_FOREGROUND_SERVICE:
                return AppProtoEnums.PROCESS_STATE_FOREGROUND_SERVICE;
            case PROCESS_STATE_BOUND_FOREGROUND_SERVICE:
                return AppProtoEnums.PROCESS_STATE_BOUND_FOREGROUND_SERVICE;
            case PROCESS_STATE_IMPORTANT_FOREGROUND:
                return AppProtoEnums.PROCESS_STATE_IMPORTANT_FOREGROUND;
            case PROCESS_STATE_IMPORTANT_BACKGROUND:
                return AppProtoEnums.PROCESS_STATE_IMPORTANT_BACKGROUND;
            case PROCESS_STATE_TRANSIENT_BACKGROUND:
                return AppProtoEnums.PROCESS_STATE_TRANSIENT_BACKGROUND;
            case PROCESS_STATE_BACKUP:
                return AppProtoEnums.PROCESS_STATE_BACKUP;
            case PROCESS_STATE_SERVICE:
                return AppProtoEnums.PROCESS_STATE_SERVICE;
            case PROCESS_STATE_RECEIVER:
                return AppProtoEnums.PROCESS_STATE_RECEIVER;
            case PROCESS_STATE_TOP_SLEEPING:
                return AppProtoEnums.PROCESS_STATE_TOP_SLEEPING;
            case PROCESS_STATE_HEAVY_WEIGHT:
                return AppProtoEnums.PROCESS_STATE_HEAVY_WEIGHT;
            case PROCESS_STATE_HOME:
                return AppProtoEnums.PROCESS_STATE_HOME;
            case PROCESS_STATE_LAST_ACTIVITY:
                return AppProtoEnums.PROCESS_STATE_LAST_ACTIVITY;
            case PROCESS_STATE_CACHED_ACTIVITY:
                return AppProtoEnums.PROCESS_STATE_CACHED_ACTIVITY;
            case PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                return AppProtoEnums.PROCESS_STATE_CACHED_ACTIVITY_CLIENT;
            case PROCESS_STATE_CACHED_RECENT:
                return AppProtoEnums.PROCESS_STATE_CACHED_RECENT;
            case PROCESS_STATE_CACHED_EMPTY:
                return AppProtoEnums.PROCESS_STATE_CACHED_EMPTY;
            case PROCESS_STATE_NONEXISTENT:
                return AppProtoEnums.PROCESS_STATE_NONEXISTENT;
            default:
                // ActivityManager process state (amInt)
                // could not be mapped to an AppProtoEnums ProcessState state.
                return AppProtoEnums.PROCESS_STATE_UNKNOWN_TO_PROTO;
        }
    }

    /** @hide The lowest process state number */
    public static final int MIN_PROCESS_STATE = PROCESS_STATE_PERSISTENT;

    /** @hide The highest process state number */
    public static final int MAX_PROCESS_STATE = PROCESS_STATE_NONEXISTENT;

    /** @hide Should this process state be considered a background state? */
    @android.ravenwood.annotation.RavenwoodKeep
    public static final boolean isProcStateBackground(int procState) {
        return procState >= PROCESS_STATE_TRANSIENT_BACKGROUND;
    }

    /** @hide Should this process state be considered in the cache? */
    @android.ravenwood.annotation.RavenwoodKeep
    public static final boolean isProcStateCached(int procState) {
        return procState >= PROCESS_STATE_CACHED_ACTIVITY;
    }

    /** @hide Is this a foreground service type? */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isForegroundService(int procState) {
        return procState == PROCESS_STATE_FOREGROUND_SERVICE;
    }

    /** @hide requestType for assist context: only basic information. */
    public static final int ASSIST_CONTEXT_BASIC = 0;

    /** @hide requestType for assist context: generate full AssistStructure. */
    public static final int ASSIST_CONTEXT_FULL = 1;

    /** @hide requestType for assist context: generate full AssistStructure for autofill. */
    public static final int ASSIST_CONTEXT_AUTOFILL = 2;

    /** @hide requestType for assist context: generate AssistContent but not AssistStructure. */
    public static final int ASSIST_CONTEXT_CONTENT = 3;

    /** @hide Flag for registerUidObserver: report changes in process state. */
    public static final int UID_OBSERVER_PROCSTATE = 1<<0;

    /** @hide Flag for registerUidObserver: report uid gone. */
    public static final int UID_OBSERVER_GONE = 1<<1;

    /** @hide Flag for registerUidObserver: report uid has become idle. */
    public static final int UID_OBSERVER_IDLE = 1<<2;

    /** @hide Flag for registerUidObserver: report uid has become active. */
    public static final int UID_OBSERVER_ACTIVE = 1<<3;

    /** @hide Flag for registerUidObserver: report uid cached state has changed. */
    public static final int UID_OBSERVER_CACHED = 1<<4;

    /** @hide Flag for registerUidObserver: report uid capability has changed. */
    public static final int UID_OBSERVER_CAPABILITY = 1<<5;

    /** @hide Flag for registerUidObserver: report pid oom adj has changed. */
    public static final int UID_OBSERVER_PROC_OOM_ADJ = 1 << 6;

    /** @hide Mode for {@link IActivityManager#isAppStartModeDisabled}: normal free-to-run operation. */
    public static final int APP_START_MODE_NORMAL = 0;

    /** @hide Mode for {@link IActivityManager#isAppStartModeDisabled}: delay running until later. */
    public static final int APP_START_MODE_DELAYED = 1;

    /** @hide Mode for {@link IActivityManager#isAppStartModeDisabled}: delay running until later, with
     * rigid errors (throwing exception). */
    public static final int APP_START_MODE_DELAYED_RIGID = 2;

    /** @hide Mode for {@link IActivityManager#isAppStartModeDisabled}: disable/cancel pending
     * launches; this is the mode for ephemeral apps. */
    public static final int APP_START_MODE_DISABLED = 3;

    /**
     * Lock task mode is not active.
     */
    public static final int LOCK_TASK_MODE_NONE = 0;

    /**
     * Full lock task mode is active.
     */
    public static final int LOCK_TASK_MODE_LOCKED = 1;

    /**
     * App pinning mode is active.
     */
    public static final int LOCK_TASK_MODE_PINNED = 2;

    Point mAppTaskThumbnailSize;

    @UnsupportedAppUsage
    /*package*/ ActivityManager(Context context, Handler handler) {
        mContext = context;
    }

    private static volatile int sCurrentUser$ravenwood = UserHandle.USER_NULL;

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static void init$ravenwood(int currentUser) {
        sCurrentUser$ravenwood = currentUser;
    }

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static void reset$ravenwood() {
        sCurrentUser$ravenwood = UserHandle.USER_NULL;
    }

    /**
     * Returns whether the launch was successful.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static final boolean isStartResultSuccessful(int result) {
        return FIRST_START_SUCCESS_CODE <= result && result <= LAST_START_SUCCESS_CODE;
    }

    /**
     * Returns whether the launch result was a fatal error.
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static final boolean isStartResultFatalError(int result) {
        return FIRST_START_FATAL_ERROR_CODE <= result && result <= LAST_START_FATAL_ERROR_CODE;
    }

    /**
     * Screen compatibility mode: the application most always run in
     * compatibility mode.
     * @hide
     */
    public static final int COMPAT_MODE_ALWAYS = -1;

    /**
     * Screen compatibility mode: the application can never run in
     * compatibility mode.
     * @hide
     */
    public static final int COMPAT_MODE_NEVER = -2;

    /**
     * Screen compatibility mode: unknown.
     * @hide
     */
    public static final int COMPAT_MODE_UNKNOWN = -3;

    /**
     * Screen compatibility mode: the application currently has compatibility
     * mode disabled.
     * @hide
     */
    public static final int COMPAT_MODE_DISABLED = 0;

    /**
     * Screen compatibility mode: the application currently has compatibility
     * mode enabled.
     * @hide
     */
    public static final int COMPAT_MODE_ENABLED = 1;

    /**
     * Screen compatibility mode: request to toggle the application's
     * compatibility mode.
     * @hide
     */
    public static final int COMPAT_MODE_TOGGLE = 2;

    private static final boolean DEVELOPMENT_FORCE_LOW_RAM =
            SystemProperties.getBoolean("debug.force_low_ram", false);

    /**
     * Intent {@link Intent#ACTION_CLOSE_SYSTEM_DIALOGS} is too powerful to be unrestricted. We
     * restrict its usage for a few legitimate use-cases only, regardless of targetSdk. For the
     * other use-cases we drop the intent with a log message.
     *
     * Note that this is the lighter version of {@link ActivityManager
     * #LOCK_DOWN_CLOSE_SYSTEM_DIALOGS} which is not gated on targetSdk in order to eliminate the
     * abuse vector.
     *
     * @hide
     */
    @ChangeId
    public static final long DROP_CLOSE_SYSTEM_DIALOGS = 174664120L;

    /**
     * Intent {@link Intent#ACTION_CLOSE_SYSTEM_DIALOGS} is too powerful to be unrestricted. So,
     * apps targeting {@link Build.VERSION_CODES#S} or higher will crash if they try to send such
     * intent and don't have permission {@code android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS}.
     *
     * Note that this is the more restrict version of {@link ActivityManager
     * #DROP_CLOSE_SYSTEM_DIALOGS} that expects the app to stop sending aforementioned intent once
     * it bumps its targetSdk to {@link Build.VERSION_CODES#S} or higher.
     *
     * @hide
     */
    @TestApi
    @ChangeId
    @EnabledSince(targetSdkVersion = VERSION_CODES.S)
    public static final long LOCK_DOWN_CLOSE_SYSTEM_DIALOGS = 174664365L;

    // The background process restriction levels. The definitions here are meant for internal
    // bookkeeping only.

    /**
     * Not a valid restriction level.
     *
     * @hide
     */
    public static final int RESTRICTION_LEVEL_UNKNOWN = 0;

    /**
     * No background restrictions at all, this should NEVER be used
     * for any process other than selected system processes, currently it's reserved.
     *
     * <p>In the future, apps in {@link #RESTRICTION_LEVEL_EXEMPTED} would receive permissive
     * background restrictions to protect the system from buggy behaviors; in other words,
     * the {@link #RESTRICTION_LEVEL_EXEMPTED} would not be the truly "unrestricted" state, while
     * the {@link #RESTRICTION_LEVEL_UNRESTRICTED} here would be the last resort if there is
     * a strong reason to grant such a capability to a system app. </p>
     *
     * @hide
     */
    public static final int RESTRICTION_LEVEL_UNRESTRICTED = 10;

    /**
     * The default background restriction level for the "unrestricted" apps set by the user,
     * where it'll have the {@link android.app.AppOpsManager#OP_RUN_ANY_IN_BACKGROUND} set to
     * ALLOWED, being added into the device idle allow list; however there will be still certain
     * restrictions to apps in this level.
     *
     * @hide
     */
    public static final int RESTRICTION_LEVEL_EXEMPTED = 20;

    /**
     * The default background restriction level for all other apps, they'll be moved between
     * various standby buckets, including
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_ACTIVE},
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_WORKING_SET},
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_FREQUENT},
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RARE}.
     *
     * @hide
     */
    public static final int RESTRICTION_LEVEL_ADAPTIVE_BUCKET = 30;

    /**
     * The background restriction level where the apps will be placed in the restricted bucket
     * {@link android.app.usage.UsageStatsManager#STANDBY_BUCKET_RESTRICTED}.
     *
     * @hide
     */
    public static final int RESTRICTION_LEVEL_RESTRICTED_BUCKET = 40;

    /**
     * The background restricted level, where apps would get more restrictions,
     * such as not allowed to launch foreground services besides on TOP.
     *
     * @hide
     */
    public static final int RESTRICTION_LEVEL_BACKGROUND_RESTRICTED = 50;

    /**
     * The restricted level where the apps are in a force-stopped state.
     *
     * @hide
     */
    public static final int RESTRICTION_LEVEL_FORCE_STOPPED = 60;

    /**
     * The heavily background restricted level, where apps cannot start without an explicit
     * launch by the user.
     *
     * @hide
     */
    public static final int RESTRICTION_LEVEL_USER_LAUNCH_ONLY = 70;

    /**
     * A reserved restriction level that is not well-defined.
     *
     * @hide
     */
    public static final int RESTRICTION_LEVEL_CUSTOM = 90;

    /**
     * Not a valid restriction level, it defines the maximum numerical value of restriction level.
     *
     * @hide
     */
    public static final int RESTRICTION_LEVEL_MAX = 100;

    /** @hide */
    @IntDef(prefix = { "RESTRICTION_LEVEL_" }, value = {
            RESTRICTION_LEVEL_UNKNOWN,
            RESTRICTION_LEVEL_UNRESTRICTED,
            RESTRICTION_LEVEL_EXEMPTED,
            RESTRICTION_LEVEL_ADAPTIVE_BUCKET,
            RESTRICTION_LEVEL_RESTRICTED_BUCKET,
            RESTRICTION_LEVEL_BACKGROUND_RESTRICTED,
            RESTRICTION_LEVEL_FORCE_STOPPED,
            RESTRICTION_LEVEL_USER_LAUNCH_ONLY,
            RESTRICTION_LEVEL_CUSTOM,
            RESTRICTION_LEVEL_MAX,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RestrictionLevel{}

    /**
     * Maximum string length for sub reason for restriction.
     *
     * @hide
     */
    public static final int RESTRICTION_SUBREASON_MAX_LENGTH = 16;

    /**
     * Restriction reason to be used when this is normal behavior for the state.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_REASON_DEFAULT = 1;

    /**
     * Restriction reason is some kind of timeout that moves the app to a more restricted state.
     * The threshold should specify how long the app was dormant, in milliseconds.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_REASON_DORMANT = 2;

    /**
     * Restriction reason to be used when removing a restriction due to direct or indirect usage
     * of the app, especially to undo any automatic restrictions.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_REASON_USAGE = 3;

    /**
     * Restriction reason to be used when the user chooses to manually restrict the app, through
     * UI or command line interface.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_REASON_USER = 4;

    /**
     * Restriction reason to be used when the OS automatically detects that the app is causing
     * system health issues such as performance degradation, battery drain, high memory usage, etc.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_REASON_SYSTEM_HEALTH = 5;

    /**
     * Restriction reason to be used when app is doing something that is against policy, such as
     * spamming the user or being deceptive about its intentions.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_REASON_POLICY = 6;

    /**
     * Restriction reason to be used when some other problem requires restricting the app.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_REASON_OTHER = 7;

    /** @hide */
    @IntDef(prefix = { "RESTRICTION_REASON_" }, value = {
            RESTRICTION_REASON_DEFAULT,
            RESTRICTION_REASON_DORMANT,
            RESTRICTION_REASON_USAGE,
            RESTRICTION_REASON_USER,
            RESTRICTION_REASON_SYSTEM_HEALTH,
            RESTRICTION_REASON_POLICY,
            RESTRICTION_REASON_OTHER
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RestrictionReason{}

    /**
     * The source of restriction is the user manually choosing to do so.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_SOURCE_USER = 1;

    /**
     * The source of restriction is the user, on being prompted by the system for the specified
     * reason.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_SOURCE_USER_NUDGED = 2;

    /**
     * The source of restriction is the system.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_SOURCE_SYSTEM = 3;

    /**
     * The source of restriction is the command line interface through the shell or a test.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_SOURCE_COMMAND_LINE = 4;

    /**
     * The source of restriction is a configuration pushed from a server.
     *
     * @see #noteAppRestrictionEnabled(String, int, int, boolean, int, String, int, long)
     * @hide
     */
    public static final int RESTRICTION_SOURCE_REMOTE_TRIGGER = 5;

    /** @hide */
    @IntDef(prefix = { "RESTRICTION_SOURCE_" }, value = {
            RESTRICTION_SOURCE_USER,
            RESTRICTION_SOURCE_USER_NUDGED,
            RESTRICTION_SOURCE_SYSTEM,
            RESTRICTION_SOURCE_COMMAND_LINE,
            RESTRICTION_SOURCE_REMOTE_TRIGGER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RestrictionSource{}

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static String restrictionLevelToName(@RestrictionLevel int level) {
        switch (level) {
            case RESTRICTION_LEVEL_UNKNOWN:
                return "unknown";
            case RESTRICTION_LEVEL_UNRESTRICTED:
                return "unrestricted";
            case RESTRICTION_LEVEL_EXEMPTED:
                return "exempted";
            case RESTRICTION_LEVEL_ADAPTIVE_BUCKET:
                return "adaptive_bucket";
            case RESTRICTION_LEVEL_RESTRICTED_BUCKET:
                return "restricted_bucket";
            case RESTRICTION_LEVEL_BACKGROUND_RESTRICTED:
                return "background_restricted";
            case RESTRICTION_LEVEL_FORCE_STOPPED:
                return "stopped";
            case RESTRICTION_LEVEL_USER_LAUNCH_ONLY:
                return "user_only";
            case RESTRICTION_LEVEL_CUSTOM:
                return "custom";
            case RESTRICTION_LEVEL_MAX:
                return "max";
            default:
                return String.valueOf(level);
        }
    }

    /** @hide */
    public int getFrontActivityScreenCompatMode() {
        try {
            return getTaskService().getFrontActivityScreenCompatMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setFrontActivityScreenCompatMode(int mode) {
        try {
            getTaskService().setFrontActivityScreenCompatMode(mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public int getPackageScreenCompatMode(String packageName) {
        try {
            return getTaskService().getPackageScreenCompatMode(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setPackageScreenCompatMode(String packageName, int mode) {
        try {
            getTaskService().setPackageScreenCompatMode(packageName, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public boolean getPackageAskScreenCompat(String packageName) {
        try {
            return getTaskService().getPackageAskScreenCompat(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setPackageAskScreenCompat(String packageName, boolean ask) {
        try {
            getTaskService().setPackageAskScreenCompat(packageName, ask);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the approximate per-application memory class of the current
     * device.  This gives you an idea of how hard a memory limit you should
     * impose on your application to let the overall system work best.  The
     * returned value is in megabytes; the baseline Android memory class is
     * 16 (which happens to be the Java heap limit of those devices); some
     * devices with more memory may return 24 or even higher numbers.
     */
    public int getMemoryClass() {
        return staticGetMemoryClass();
    }

    /** @hide */
    @UnsupportedAppUsage
    static public int staticGetMemoryClass() {
        // Really brain dead right now -- just take this from the configured
        // vm heap size, and assume it is in megabytes and thus ends with "m".
        String vmHeapSize = SystemProperties.get("dalvik.vm.heapgrowthlimit", "");
        if (vmHeapSize != null && !"".equals(vmHeapSize)) {
            return Integer.parseInt(vmHeapSize.substring(0, vmHeapSize.length()-1));
        }
        return staticGetLargeMemoryClass();
    }

    /**
     * Return the approximate per-application memory class of the current
     * device when an application is running with a large heap.  This is the
     * space available for memory-intensive applications; most applications
     * should not need this amount of memory, and should instead stay with the
     * {@link #getMemoryClass()} limit.  The returned value is in megabytes.
     * This may be the same size as {@link #getMemoryClass()} on memory
     * constrained devices, or it may be significantly larger on devices with
     * a large amount of available RAM.
     *
     * <p>This is the size of the application's Dalvik heap if it has
     * specified <code>android:largeHeap="true"</code> in its manifest.
     */
    public int getLargeMemoryClass() {
        return staticGetLargeMemoryClass();
    }

    /** @hide */
    static public int staticGetLargeMemoryClass() {
        // Really brain dead right now -- just take this from the configured
        // vm heap size, and assume it is in megabytes and thus ends with "m".
        String vmHeapSize = SystemProperties.get("dalvik.vm.heapsize", "16m");
        return Integer.parseInt(vmHeapSize.substring(0, vmHeapSize.length() - 1));
    }

    /**
     * Returns true if this is a low-RAM device.  Exactly whether a device is low-RAM
     * is ultimately up to the device configuration, but currently it generally means
     * something with 1GB or less of RAM.  This is mostly intended to be used by apps
     * to determine whether they should turn off certain features that require more RAM.
     */
    public boolean isLowRamDevice() {
        return isLowRamDeviceStatic();
    }

    /** @hide */
    @UnsupportedAppUsage
    public static boolean isLowRamDeviceStatic() {
        return RoSystemProperties.CONFIG_LOW_RAM ||
                (Build.IS_DEBUGGABLE && DEVELOPMENT_FORCE_LOW_RAM);
    }

    /**
     * Returns true if this is a small battery device. Exactly whether a device is considered to be
     * small battery is ultimately up to the device configuration, but currently it generally means
     * something in the class of a device with 1000 mAh or less. This is mostly intended to be used
     * to determine whether certain features should be altered to account for a drastically smaller
     * battery.
     * @hide
     */
    public static boolean isSmallBatteryDevice() {
        return RoSystemProperties.CONFIG_SMALL_BATTERY;
    }

    /**
     * Used by persistent processes to determine if they are running on a
     * higher-end device so should be okay using hardware drawing acceleration
     * (which tends to consume a lot more RAM).
     * @hide
     */
    @TestApi
    static public boolean isHighEndGfx() {
        return !isLowRamDeviceStatic()
                && !RoSystemProperties.CONFIG_AVOID_GFX_ACCEL
                && !Resources.getSystem()
                        .getBoolean(com.android.internal.R.bool.config_avoidGfxAccel);
    }

    /**
     * Return the total number of bytes of RAM this device has.
     * @hide
     */
    @TestApi
    public long getTotalRam() {
        MemInfoReader memreader = new MemInfoReader();
        memreader.readMemInfo();
        return memreader.getTotalSize();
    }

    /**
     * TODO(b/80414790): Remove once no longer on hiddenapi-light-greylist.txt
     * @hide
     * @deprecated Use {@link ActivityTaskManager#getMaxRecentTasksStatic()}
     */
    @Deprecated
    @UnsupportedAppUsage
    static public int getMaxRecentTasksStatic() {
        return ActivityTaskManager.getMaxRecentTasksStatic();
    }

    /**
     * Information you can set and retrieve about the current activity within the recent task list.
     */
    public static class TaskDescription implements Parcelable {
        /** @hide */
        public static final String ATTR_TASKDESCRIPTION_PREFIX = "task_description_";
        private static final String ATTR_TASKDESCRIPTIONLABEL =
                ATTR_TASKDESCRIPTION_PREFIX + "label";
        private static final String ATTR_TASKDESCRIPTIONCOLOR_PRIMARY =
                ATTR_TASKDESCRIPTION_PREFIX + "color";
        private static final String ATTR_TASKDESCRIPTIONCOLOR_BACKGROUND =
                ATTR_TASKDESCRIPTION_PREFIX + "color_background";
        private static final String ATTR_TASKDESCRIPTIONICON_FILENAME =
                ATTR_TASKDESCRIPTION_PREFIX + "icon_filename";
        private static final String ATTR_TASKDESCRIPTIONICON_RESOURCE =
                ATTR_TASKDESCRIPTION_PREFIX + "icon_resource";
        private static final String ATTR_TASKDESCRIPTIONICON_RESOURCE_PACKAGE =
                ATTR_TASKDESCRIPTION_PREFIX + "icon_package";
        private static final String ATTR_TASKDESCRIPTIONCOLOR_BACKGROUND_FLOATING =
                ATTR_TASKDESCRIPTION_PREFIX + "color_background_floating";

        private String mLabel;
        @Nullable
        private Icon mIcon;
        private String mIconFilename;
        private int mColorPrimary;
        private int mColorBackground;
        private int mColorBackgroundFloating;
        private int mStatusBarColor;
        private int mNavigationBarColor;
        @Appearance
        private int mSystemBarsAppearance;
        /**
         * Similar to {@link TaskDescription#mSystemBarsAppearance}, but is taken from the topmost
         * fully opaque (i.e. non transparent) activity in the task.
         */
        @Appearance
        private int mTopOpaqueSystemBarsAppearance;
        private boolean mEnsureStatusBarContrastWhenTransparent;
        private boolean mEnsureNavigationBarContrastWhenTransparent;
        private int mResizeMode;
        private int mMinWidth;
        private int mMinHeight;

        /**
         * Provides a convenient way to set the fields of a {@link TaskDescription} when creating a
         * new instance.
         */
        public static final class Builder {
            /**
             * Default values for the TaskDescription
             */
            @Nullable
            private String mLabel = null;
            @DrawableRes
            private int mIconRes = Resources.ID_NULL;
            private int mPrimaryColor = 0;
            private int mBackgroundColor = 0;
            private int mStatusBarColor = 0;
            private int mNavigationBarColor = 0;

            /**
             * Set the label to use in the TaskDescription.
             * @param label A label and description of the current state of this activity.
             * @return The same instance of the builder.
             */
            @NonNull
            public Builder setLabel(@Nullable String label) {
                this.mLabel = label;
                return this;
            }

            /**
             * Set the drawable resource of the icon to use in the TaskDescription.
             * @param iconRes A drawable resource of an icon that represents the current state of
             *                this activity.
             * @return The same instance of the builder.
             */
            @NonNull
            public Builder setIcon(@DrawableRes int iconRes) {
                this.mIconRes = iconRes;
                return this;
            }

            /**
             * Set the primary color to use in the TaskDescription.
             * @param color A color to override the theme's primary color. The color must be opaque.
             * @return The same instance of the builder.
             */
            @NonNull
            public Builder setPrimaryColor(@ColorInt int color) {
                this.mPrimaryColor = color;
                return this;
            }

            /**
             * Set the background color to use in the TaskDescription.
             * @param color A color to override the theme's background color. The color must be
             *              opaque.
             * @return The same instance of the builder.
             */
            @NonNull
            public Builder setBackgroundColor(@ColorInt int color) {
                this.mBackgroundColor = color;
                return this;
            }

            /**
             * Set the status bar color to use in the TaskDescription.
             * @param color A color to override the theme's status bar color.
             * @return The same instance of the builder.
             */
            @NonNull
            public Builder setStatusBarColor(@ColorInt int color) {
                this.mStatusBarColor = color;
                return this;
            }

            /**
             * Set the navigation bar color to use in the TaskDescription.
             * @param color A color to override the theme's navigation bar color.
             * @return The same instance of the builder.
             */
            @NonNull
            public Builder setNavigationBarColor(@ColorInt int color) {
                this.mNavigationBarColor = color;
                return this;
            }

            /**
             * Build the TaskDescription.
             * @return the TaskDescription object.
             */
            @NonNull
            public TaskDescription build() {
                final Icon icon = mIconRes == Resources.ID_NULL ? null :
                        Icon.createWithResource(ActivityThread.currentPackageName(), mIconRes);
                return new TaskDescription(mLabel, icon, mPrimaryColor, mBackgroundColor,
                        mStatusBarColor, mNavigationBarColor, 0, 0, false, false,
                        RESIZE_MODE_RESIZEABLE, -1, -1, 0);
            }
        }

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this task.
         * @param iconRes A drawable resource of an icon that represents the current state of this
         *                activity.
         * @param colorPrimary A color to override the theme's primary color.  This color must be
         *                     opaque.
         *
         * @deprecated Use {@link Builder} instead.
         */
        @Deprecated
        public TaskDescription(String label, @DrawableRes int iconRes, int colorPrimary) {
            this(label, Icon.createWithResource(ActivityThread.currentPackageName(), iconRes),
                    colorPrimary, 0, 0, 0, 0, 0, false, false, RESIZE_MODE_RESIZEABLE, -1, -1, 0);
            if ((colorPrimary != 0) && (Color.alpha(colorPrimary) != 255)) {
                throw new RuntimeException("A TaskDescription's primary color should be opaque");
            }
        }

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this activity.
         * @param iconRes A drawable resource of an icon that represents the current state of this
         *                activity.
         *
         * @deprecated Use {@link Builder} instead.
         */
        @Deprecated
        public TaskDescription(String label, @DrawableRes int iconRes) {
            this(label, Icon.createWithResource(ActivityThread.currentPackageName(), iconRes),
                    0, 0, 0, 0, 0, 0, false, false, RESIZE_MODE_RESIZEABLE, -1, -1, 0);
        }

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this activity.
         *
         * @deprecated Use {@link Builder} instead.
         */
        @Deprecated
        public TaskDescription(String label) {
            this(label, null, 0, 0, 0, 0, 0, 0, false, false, RESIZE_MODE_RESIZEABLE, -1, -1, 0);
        }

        /**
         * Creates an empty TaskDescription.
         *
         * @deprecated Use {@link Builder} instead.
         */
        @Deprecated
        public TaskDescription() {
            this(null, null, 0, 0, 0, 0, 0, 0, false, false, RESIZE_MODE_RESIZEABLE, -1, -1, 0);
        }

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this task.
         * @param icon An icon that represents the current state of this task.
         * @param colorPrimary A color to override the theme's primary color.  This color must be
         *                     opaque.
         *
         * @deprecated Use {@link Builder} instead.
         */
        @Deprecated
        public TaskDescription(String label, Bitmap icon, int colorPrimary) {
            this(label, icon != null ? Icon.createWithBitmap(icon) : null, colorPrimary, 0, 0, 0,
                    0, 0, false, false, RESIZE_MODE_RESIZEABLE, -1, -1, 0);
            if ((colorPrimary != 0) && (Color.alpha(colorPrimary) != 255)) {
                throw new RuntimeException("A TaskDescription's primary color should be opaque");
            }
        }

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this activity.
         * @param icon An icon that represents the current state of this activity.
         *
         * @deprecated Use {@link Builder} instead.
         */
        @Deprecated
        public TaskDescription(String label, Bitmap icon) {
            this(label, icon != null ? Icon.createWithBitmap(icon) : null, 0, 0, 0, 0, 0, 0, false,
                    false, RESIZE_MODE_RESIZEABLE, -1, -1, 0);
        }

        /** @hide */
        public TaskDescription(@Nullable String label, @Nullable Icon icon,
                int colorPrimary, int colorBackground,
                int statusBarColor, int navigationBarColor,
                @Appearance int systemBarsAppearance,
                @Appearance int topOpaqueSystemBarsAppearance,
                boolean ensureStatusBarContrastWhenTransparent,
                boolean ensureNavigationBarContrastWhenTransparent, int resizeMode, int minWidth,
                int minHeight, int colorBackgroundFloating) {
            mLabel = label;
            mIcon = icon;
            mColorPrimary = colorPrimary;
            mColorBackground = colorBackground;
            mStatusBarColor = statusBarColor;
            mNavigationBarColor = navigationBarColor;
            mSystemBarsAppearance = systemBarsAppearance;
            mTopOpaqueSystemBarsAppearance = topOpaqueSystemBarsAppearance;
            mEnsureStatusBarContrastWhenTransparent = ensureStatusBarContrastWhenTransparent;
            mEnsureNavigationBarContrastWhenTransparent =
                    ensureNavigationBarContrastWhenTransparent;
            mResizeMode = resizeMode;
            mMinWidth = minWidth;
            mMinHeight = minHeight;
            mColorBackgroundFloating = colorBackgroundFloating;
        }

        /**
         * Creates a copy of another TaskDescription.
         */
        public TaskDescription(TaskDescription td) {
            copyFrom(td);
        }

        /**
         * Copies this the values from another TaskDescription.
         * @hide
         */
        public void copyFrom(TaskDescription other) {
            mLabel = other.mLabel;
            mIcon = other.mIcon;
            mIconFilename = other.mIconFilename;
            mColorPrimary = other.mColorPrimary;
            mColorBackground = other.mColorBackground;
            mStatusBarColor = other.mStatusBarColor;
            mNavigationBarColor = other.mNavigationBarColor;
            mSystemBarsAppearance = other.mSystemBarsAppearance;
            mTopOpaqueSystemBarsAppearance = other.mTopOpaqueSystemBarsAppearance;
            mEnsureStatusBarContrastWhenTransparent = other.mEnsureStatusBarContrastWhenTransparent;
            mEnsureNavigationBarContrastWhenTransparent =
                    other.mEnsureNavigationBarContrastWhenTransparent;
            mResizeMode = other.mResizeMode;
            mMinWidth = other.mMinWidth;
            mMinHeight = other.mMinHeight;
            mColorBackgroundFloating = other.mColorBackgroundFloating;
        }

        /**
         * Copies values from another TaskDescription, but preserves the hidden fields if they
         * weren't set on {@code other}. Public fields will be overwritten anyway.
         * @hide
         */
        public void copyFromPreserveHiddenFields(TaskDescription other) {
            mLabel = other.mLabel;
            mIcon = other.mIcon;
            mIconFilename = other.mIconFilename;
            mColorPrimary = other.mColorPrimary;

            if (other.mColorBackground != 0) {
                mColorBackground = other.mColorBackground;
            }
            if (other.mStatusBarColor != 0) {
                mStatusBarColor = other.mStatusBarColor;
            }
            if (other.mNavigationBarColor != 0) {
                mNavigationBarColor = other.mNavigationBarColor;
            }
            if (other.mSystemBarsAppearance != 0) {
                mSystemBarsAppearance = other.mSystemBarsAppearance;
            }
            if (other.mTopOpaqueSystemBarsAppearance != 0) {
                mTopOpaqueSystemBarsAppearance = other.mTopOpaqueSystemBarsAppearance;
            }

            mEnsureStatusBarContrastWhenTransparent = other.mEnsureStatusBarContrastWhenTransparent;
            mEnsureNavigationBarContrastWhenTransparent =
                    other.mEnsureNavigationBarContrastWhenTransparent;

            if (other.mResizeMode != RESIZE_MODE_RESIZEABLE) {
                mResizeMode = other.mResizeMode;
            }
            if (other.mMinWidth != -1) {
                mMinWidth = other.mMinWidth;
            }
            if (other.mMinHeight != -1) {
                mMinHeight = other.mMinHeight;
            }
            if (other.mColorBackgroundFloating != 0) {
                mColorBackgroundFloating = other.mColorBackgroundFloating;
            }
        }

        private TaskDescription(Parcel source) {
            readFromParcel(source);
        }

        /**
         * Sets the label for this task description.
         * @hide
         */
        public void setLabel(String label) {
            mLabel = label;
        }

        /**
         * Sets the primary color for this task description.
         * @hide
         */
        public void setPrimaryColor(int primaryColor) {
            // Ensure that the given color is valid
            if ((primaryColor != 0) && (Color.alpha(primaryColor) != 255)) {
                throw new RuntimeException("A TaskDescription's primary color should be opaque");
            }
            mColorPrimary = primaryColor;
        }

        /**
         * Sets the background color for this task description.
         * @hide
         */
        public void setBackgroundColor(int backgroundColor) {
            // Ensure that the given color is valid
            if ((backgroundColor != 0) && (Color.alpha(backgroundColor) != 255)) {
                throw new RuntimeException("A TaskDescription's background color should be opaque");
            }
            mColorBackground = backgroundColor;
        }

        /**
         * Sets the background color floating for this task description.
         * @hide
         */
        public void setBackgroundColorFloating(int backgroundColor) {
            // Ensure that the given color is valid
            if ((backgroundColor != 0) && (Color.alpha(backgroundColor) != 255)) {
                throw new RuntimeException(
                        "A TaskDescription's background color floating should be opaque");
            }
            mColorBackgroundFloating = backgroundColor;
        }

        /**
         * @hide
         */
        public void setStatusBarColor(int statusBarColor) {
            mStatusBarColor = statusBarColor;
        }

        /**
         * @hide
         */
        public void setNavigationBarColor(int navigationBarColor) {
            mNavigationBarColor = navigationBarColor;
        }

        /**
         * Sets the icon resource for this task description.
         * @hide
         */
        public void setIcon(Icon icon) {
            mIcon = icon;
        }

        /**
         * Moves the icon bitmap reference from an actual Bitmap to a file containing the
         * bitmap.
         * @hide
         */
        public void setIconFilename(String iconFilename) {
            mIconFilename = iconFilename;
            if (iconFilename != null) {
                // Only reset the icon if an actual persisted icon filepath was set
                mIcon = null;
            }
        }

        /**
         * Sets the resize mode for this task description. Resize mode as in
         * {@link android.content.pm.ActivityInfo}.
         * @hide
         */
        public void setResizeMode(int resizeMode) {
            mResizeMode = resizeMode;
        }

        /**
         * The minimal width size to show the app content in freeform mode.
         * @param minWidth minimal width, -1 for system default.
         * @hide
         */
        public void setMinWidth(int minWidth) {
            mMinWidth = minWidth;
        }

        /**
         * The minimal height size to show the app content in freeform mode.
         * @param minHeight minimal height, -1 for system default.
         * @hide
         */
        public void setMinHeight(int minHeight) {
            mMinHeight = minHeight;
        }

        /**
         * @return The label and description of the current state of this task.
         */
        public String getLabel() {
            return mLabel;
        }

        /**
         * @return The actual icon that represents the current state of this task if it is in memory
         *         or loads it from disk if available.
         * @hide
         */
        public Icon loadIcon() {
            if (mIcon != null) {
                return mIcon;
            }
            Bitmap loadedIcon = loadTaskDescriptionIcon(mIconFilename, UserHandle.myUserId());
            if (loadedIcon != null) {
                return Icon.createWithBitmap(loadedIcon);
            }
            return null;
        }

        /**
         * @return The in-memory or loaded icon that represents the current state of this task.
         * @deprecated This call is no longer supported. The caller should keep track of any icons
         *             it sets for the task descriptions internally.
         */
        @Deprecated
        public Bitmap getIcon() {
            Bitmap icon = getInMemoryIcon();
            if (icon != null) {
                return icon;
            }
            return loadTaskDescriptionIcon(mIconFilename, UserHandle.myUserId());
        }

        /** @hide */
        @Nullable
        public Icon getRawIcon() {
            return mIcon;
        }

        /** @hide */
        @TestApi
        @Nullable
        public String getIconResourcePackage() {
            if (mIcon != null && mIcon.getType() == Icon.TYPE_RESOURCE) {
                return mIcon.getResPackage();
            }
            return "";
        }

        /** @hide */
        @TestApi
        public int getIconResource() {
            if (mIcon != null && mIcon.getType() == Icon.TYPE_RESOURCE) {
                return mIcon.getResId();
            }
            return 0;
        }

        /** @hide */
        @TestApi
        public String getIconFilename() {
            return mIconFilename;
        }

        /** @hide */
        @UnsupportedAppUsage
        public Bitmap getInMemoryIcon() {
            if (mIcon != null && mIcon.getType() == Icon.TYPE_BITMAP) {
                return mIcon.getBitmap();
            }
            return null;
        }

        /** @hide */
        @UnsupportedAppUsage
        public static Bitmap loadTaskDescriptionIcon(String iconFilename, int userId) {
            if (iconFilename != null) {
                try {
                    return getTaskService().getTaskDescriptionIcon(iconFilename,
                            userId);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
            return null;
        }

        /**
         * @return The color override on the theme's primary color.
         */
        @ColorInt
        public int getPrimaryColor() {
            return mColorPrimary;
        }

        /**
         * @return The color override on the theme's background color.
         */
        @ColorInt
        public int getBackgroundColor() {
            return mColorBackground;
        }

        /**
         * @return The background color floating.
         * @hide
         */
        public int getBackgroundColorFloating() {
            return mColorBackgroundFloating;
        }

        /**
         * @return The color override on the theme's status bar color.
         */
        @ColorInt
        public int getStatusBarColor() {
            return mStatusBarColor;
        }

        /**
         * @return The color override on the theme's navigation bar color.
         */
        @ColorInt
        public int getNavigationBarColor() {
            return mNavigationBarColor;
        }

        /**
         * @hide
         */
        public boolean getEnsureStatusBarContrastWhenTransparent() {
            return mEnsureStatusBarContrastWhenTransparent;
        }

        /**
         * @hide
         */
        @Appearance
        public int getSystemBarsAppearance() {
            return mSystemBarsAppearance;
        }

        /**
         * @hide
         */
        @Appearance
        public int getTopOpaqueSystemBarsAppearance() {
            return mTopOpaqueSystemBarsAppearance;
        }

        /**
         * @hide
         */
        public void setEnsureStatusBarContrastWhenTransparent(
                boolean ensureStatusBarContrastWhenTransparent) {
            mEnsureStatusBarContrastWhenTransparent = ensureStatusBarContrastWhenTransparent;
        }

        /**
         * @hide
         */
        public void setSystemBarsAppearance(@Appearance int systemBarsAppearance) {
            mSystemBarsAppearance = systemBarsAppearance;
        }

        /**
         * @hide
         */
        public void setTopOpaqueSystemBarsAppearance(int topOpaqueSystemBarsAppearance) {
            mTopOpaqueSystemBarsAppearance = topOpaqueSystemBarsAppearance;
        }

        /**
         * @hide
         */
        public boolean getEnsureNavigationBarContrastWhenTransparent() {
            return mEnsureNavigationBarContrastWhenTransparent;
        }

        /**
         * @hide
         */
        public void setEnsureNavigationBarContrastWhenTransparent(
                boolean ensureNavigationBarContrastWhenTransparent) {
            mEnsureNavigationBarContrastWhenTransparent =
                    ensureNavigationBarContrastWhenTransparent;
        }

        /**
         * @hide
         */
        public int getResizeMode() {
            return mResizeMode;
        }

        /**
         * @hide
         */
        public int getMinWidth() {
            return mMinWidth;
        }

        /**
         * @hide
         */
        public int getMinHeight() {
            return mMinHeight;
        }

        /** @hide */
        public void saveToXml(TypedXmlSerializer out) throws IOException {
            if (mLabel != null) {
                out.attribute(null, ATTR_TASKDESCRIPTIONLABEL, mLabel);
            }
            if (mColorPrimary != 0) {
                out.attributeIntHex(null, ATTR_TASKDESCRIPTIONCOLOR_PRIMARY, mColorPrimary);
            }
            if (mColorBackground != 0) {
                out.attributeIntHex(null, ATTR_TASKDESCRIPTIONCOLOR_BACKGROUND, mColorBackground);
            }
            if (mColorBackgroundFloating != 0) {
                out.attributeIntHex(null, ATTR_TASKDESCRIPTIONCOLOR_BACKGROUND_FLOATING,
                        mColorBackgroundFloating);
            }
            if (mIconFilename != null) {
                out.attribute(null, ATTR_TASKDESCRIPTIONICON_FILENAME, mIconFilename);
            }
            if (mIcon != null && mIcon.getType() == Icon.TYPE_RESOURCE) {
                out.attributeInt(null, ATTR_TASKDESCRIPTIONICON_RESOURCE, mIcon.getResId());
                out.attribute(null, ATTR_TASKDESCRIPTIONICON_RESOURCE_PACKAGE,
                        mIcon.getResPackage());
            }
        }

        /** @hide */
        public void restoreFromXml(TypedXmlPullParser in) {
            final String label = in.getAttributeValue(null, ATTR_TASKDESCRIPTIONLABEL);
            if (label != null) {
                setLabel(label);
            }
            final int colorPrimary = in.getAttributeIntHex(null,
                    ATTR_TASKDESCRIPTIONCOLOR_PRIMARY, 0);
            if (colorPrimary != 0) {
                setPrimaryColor(colorPrimary);
            }
            final int colorBackground = in.getAttributeIntHex(null,
                    ATTR_TASKDESCRIPTIONCOLOR_BACKGROUND, 0);
            if (colorBackground != 0) {
                setBackgroundColor(colorBackground);
            }
            final int colorBackgroundFloating = in.getAttributeIntHex(null,
                    ATTR_TASKDESCRIPTIONCOLOR_BACKGROUND_FLOATING, 0);
            if (colorBackgroundFloating != 0) {
                setBackgroundColorFloating(colorBackgroundFloating);
            }
            final String iconFilename = in.getAttributeValue(null,
                    ATTR_TASKDESCRIPTIONICON_FILENAME);
            if (iconFilename != null) {
                setIconFilename(iconFilename);
            }
            final int iconResourceId = in.getAttributeInt(null,
                    ATTR_TASKDESCRIPTIONICON_RESOURCE, Resources.ID_NULL);
            final String iconResourcePackage = in.getAttributeValue(null,
                    ATTR_TASKDESCRIPTIONICON_RESOURCE_PACKAGE);
            if (iconResourceId != Resources.ID_NULL && iconResourcePackage != null) {
                setIcon(Icon.createWithResource(iconResourcePackage,
                        iconResourceId));
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            if (mLabel == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                dest.writeString(mLabel);
            }
            final Bitmap bitmapIcon = getInMemoryIcon();
            if (mIcon == null || (bitmapIcon != null && bitmapIcon.isRecycled())) {
                // If there is no icon, or if the icon is a bitmap that has been recycled, then
                // don't write anything to disk
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                mIcon.writeToParcel(dest, 0);
            }
            dest.writeInt(mColorPrimary);
            dest.writeInt(mColorBackground);
            dest.writeInt(mStatusBarColor);
            dest.writeInt(mNavigationBarColor);
            dest.writeInt(mSystemBarsAppearance);
            dest.writeInt(mTopOpaqueSystemBarsAppearance);
            dest.writeBoolean(mEnsureStatusBarContrastWhenTransparent);
            dest.writeBoolean(mEnsureNavigationBarContrastWhenTransparent);
            dest.writeInt(mResizeMode);
            dest.writeInt(mMinWidth);
            dest.writeInt(mMinHeight);
            if (mIconFilename == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                dest.writeString(mIconFilename);
            }
            dest.writeInt(mColorBackgroundFloating);
        }

        public void readFromParcel(Parcel source) {
            mLabel = source.readInt() > 0 ? source.readString() : null;
            if (source.readInt() > 0) {
                mIcon = Icon.CREATOR.createFromParcel(source);
            }
            mColorPrimary = source.readInt();
            mColorBackground = source.readInt();
            mStatusBarColor = source.readInt();
            mNavigationBarColor = source.readInt();
            mSystemBarsAppearance = source.readInt();
            mTopOpaqueSystemBarsAppearance = source.readInt();
            mEnsureStatusBarContrastWhenTransparent = source.readBoolean();
            mEnsureNavigationBarContrastWhenTransparent = source.readBoolean();
            mResizeMode = source.readInt();
            mMinWidth = source.readInt();
            mMinHeight = source.readInt();
            mIconFilename = source.readInt() > 0 ? source.readString() : null;
            mColorBackgroundFloating = source.readInt();
        }

        public static final @android.annotation.NonNull Creator<TaskDescription> CREATOR
                = new Creator<TaskDescription>() {
            public TaskDescription createFromParcel(Parcel source) {
                return new TaskDescription(source);
            }
            public TaskDescription[] newArray(int size) {
                return new TaskDescription[size];
            }
        };

        @Override
        public String toString() {
            return "TaskDescription Label: " + mLabel + " Icon: " + mIcon
                    + " IconFilename: " + mIconFilename
                    + " colorPrimary: " + mColorPrimary + " colorBackground: " + mColorBackground
                    + " statusBarColor: " + mStatusBarColor
                    + (mEnsureStatusBarContrastWhenTransparent ? " (contrast when transparent)"
                            : "") + " navigationBarColor: " + mNavigationBarColor
                    + (mEnsureNavigationBarContrastWhenTransparent
                            ? " (contrast when transparent)" : "")
                    + " resizeMode: " + ActivityInfo.resizeModeToString(mResizeMode)
                    + " minWidth: " + mMinWidth + " minHeight: " + mMinHeight
                    + " colorBackgrounFloating: " + mColorBackgroundFloating
                    + " systemBarsAppearance: " + mSystemBarsAppearance
                    + " topOpaqueSystemBarsAppearance: " + mTopOpaqueSystemBarsAppearance;
        }

        @Override
        public int hashCode() {
            int result = 17;
            if (mLabel != null) {
                result = result * 31 + mLabel.hashCode();
            }
            if (mIcon != null) {
                result = result * 31 + mIcon.hashCode();
            }
            if (mIconFilename != null) {
                result = result * 31 + mIconFilename.hashCode();
            }
            result = result * 31 + mColorPrimary;
            result = result * 31 + mColorBackground;
            result = result * 31 + mColorBackgroundFloating;
            result = result * 31 + mStatusBarColor;
            result = result * 31 + mNavigationBarColor;
            result = result * 31 + mSystemBarsAppearance;
            result = result * 31 + mTopOpaqueSystemBarsAppearance;
            result = result * 31 + (mEnsureStatusBarContrastWhenTransparent ? 1 : 0);
            result = result * 31 + (mEnsureNavigationBarContrastWhenTransparent ? 1 : 0);
            result = result * 31 + mResizeMode;
            result = result * 31 + mMinWidth;
            result = result * 31 + mMinHeight;
            return result;
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof TaskDescription)) {
                return false;
            }

            TaskDescription other = (TaskDescription) obj;
            return TextUtils.equals(mLabel, other.mLabel)
                    && TextUtils.equals(mIconFilename, other.mIconFilename)
                    && mIcon == other.mIcon
                    && mColorPrimary == other.mColorPrimary
                    && mColorBackground == other.mColorBackground
                    && mStatusBarColor == other.mStatusBarColor
                    && mNavigationBarColor == other.mNavigationBarColor
                    && mSystemBarsAppearance == other.mSystemBarsAppearance
                    && mTopOpaqueSystemBarsAppearance == other.mTopOpaqueSystemBarsAppearance
                    && mEnsureStatusBarContrastWhenTransparent
                            == other.mEnsureStatusBarContrastWhenTransparent
                    && mEnsureNavigationBarContrastWhenTransparent
                            == other.mEnsureNavigationBarContrastWhenTransparent
                    && mResizeMode == other.mResizeMode
                    && mMinWidth == other.mMinWidth
                    && mMinHeight == other.mMinHeight
                    && mColorBackgroundFloating == other.mColorBackgroundFloating;
        }

        /** @hide */
        public static boolean equals(TaskDescription td1, TaskDescription td2) {
            if (td1 == null && td2 == null) {
                return true;
            } else if (td1 != null && td2 != null) {
                return td1.equals(td2);
            }
            return false;
        }
    }

    /**
     * Information you can retrieve about tasks that the user has most recently
     * started or visited.
     */
    public static class RecentTaskInfo extends TaskInfo implements Parcelable {
        /**
         * @hide
         */
        public static class PersistedTaskSnapshotData {
            /**
             * The bounds of the task when the last snapshot was taken, may be null if the task is
             * not yet attached to the hierarchy.
             * @see {@link android.window.TaskSnapshot#mTaskSize}.
             * @hide
             */
            public @Nullable Point taskSize;

            /**
             * The content insets of the task when the task snapshot was taken.
             * @see {@link android.window.TaskSnapshot#mContentInsets}.
             * @hide
             */
            public @Nullable Rect contentInsets;

            /**
             * The size of the last snapshot taken, may be null if there is no associated snapshot.
             * @see {@link android.window.TaskSnapshot#mSnapshot}.
             * @hide
             */
            public @Nullable Point bufferSize;

            /**
             * Sets the data from the other data.
             * @hide
             */
            public void set(PersistedTaskSnapshotData other) {
                taskSize = other.taskSize;
                contentInsets = other.contentInsets;
                bufferSize = other.bufferSize;
            }

            /**
             * Sets the data from the provided {@param snapshot}.
             * @hide
             */
            public void set(TaskSnapshot snapshot) {
                if (snapshot == null) {
                    taskSize = null;
                    contentInsets = null;
                    bufferSize = null;
                    return;
                }
                final HardwareBuffer buffer = snapshot.getHardwareBuffer();
                taskSize = new Point(snapshot.getTaskSize());
                contentInsets = new Rect(snapshot.getContentInsets());
                bufferSize = buffer != null
                        ? new Point(buffer.getWidth(), buffer.getHeight())
                        : null;
            }
        }

        /**
         * If this task is currently running, this is the identifier for it.
         * If it is not running, this will be -1.
         *
         * @deprecated As of {@link android.os.Build.VERSION_CODES#Q}, use
         * {@link RecentTaskInfo#taskId} to get the task id and {@link RecentTaskInfo#isRunning}
         * to determine if it is running.
         */
        @Deprecated
        public int id;

        /**
         * The true identifier of this task, valid even if it is not running.
         *
         * @deprecated As of {@link android.os.Build.VERSION_CODES#Q}, use
         * {@link RecentTaskInfo#taskId}.
         */
        @Deprecated
        public int persistentId;

        /**
         * Description of the task's last state.
         *
         * @deprecated As of {@link android.os.Build.VERSION_CODES#Q}, currently always null.
         */
        @Deprecated
        public CharSequence description;

        /**
         * Task affiliation for grouping with other tasks.
         *
         * @deprecated As of {@link android.os.Build.VERSION_CODES#Q}, currently always 0.
         */
        @Deprecated
        public int affiliatedTaskId;

        /**
         * Information of organized child tasks.
         *
         * @hide
         */
        public ArrayList<RecentTaskInfo> childrenTaskInfos = new ArrayList<>();

        /**
         * Information about the last snapshot taken for this task.
         * @hide
         */
        public PersistedTaskSnapshotData lastSnapshotData = new PersistedTaskSnapshotData();

        public RecentTaskInfo() {
        }

        private RecentTaskInfo(Parcel source) {
            readFromParcel(source);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public void readFromParcel(Parcel source) {
            id = source.readInt();
            persistentId = source.readInt();
            childrenTaskInfos = source.readArrayList(RecentTaskInfo.class.getClassLoader(), android.app.ActivityManager.RecentTaskInfo.class);
            lastSnapshotData.taskSize = source.readTypedObject(Point.CREATOR);
            lastSnapshotData.contentInsets = source.readTypedObject(Rect.CREATOR);
            lastSnapshotData.bufferSize = source.readTypedObject(Point.CREATOR);
            super.readFromParcel(source);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            dest.writeInt(persistentId);
            dest.writeList(childrenTaskInfos);
            dest.writeTypedObject(lastSnapshotData.taskSize, flags);
            dest.writeTypedObject(lastSnapshotData.contentInsets, flags);
            dest.writeTypedObject(lastSnapshotData.bufferSize, flags);
            super.writeToParcel(dest, flags);
        }

        public static final @android.annotation.NonNull Creator<RecentTaskInfo> CREATOR
                = new Creator<RecentTaskInfo>() {
            public RecentTaskInfo createFromParcel(Parcel source) {
                return new RecentTaskInfo(source);
            }
            public RecentTaskInfo[] newArray(int size) {
                return new RecentTaskInfo[size];
            }
        };

        /**
         * @hide
         */
        public void dump(PrintWriter pw, String indent) {
            pw.println(); pw.print("   ");
            pw.print(" id="); pw.print(persistentId);
            pw.print(" userId="); pw.print(userId);
            pw.print(" hasTask="); pw.print((id != -1));
            pw.print(" lastActiveTime="); pw.println(lastActiveTime);
            pw.print("   "); pw.print(" baseIntent="); pw.println(baseIntent);
            if (baseActivity != null) {
                pw.print("   "); pw.print(" baseActivity=");
                pw.println(baseActivity.toShortString());
            }
            if (topActivity != null) {
                pw.print("   "); pw.print(" topActivity="); pw.println(topActivity.toShortString());
            }
            if (origActivity != null) {
                pw.print("   "); pw.print(" origActivity=");
                pw.println(origActivity.toShortString());
            }
            if (realActivity != null) {
                pw.print("   "); pw.print(" realActivity=");
                pw.println(realActivity.toShortString());
            }
            pw.print("   ");
            pw.print(" isExcluded=");
            pw.print(((baseIntent.getFlags() & FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0));
            pw.print(" activityType="); pw.print(activityTypeToString(getActivityType()));
            pw.print(" windowingMode="); pw.print(windowingModeToString(getWindowingMode()));
            pw.print(" supportsMultiWindow=");
            pw.println(supportsMultiWindow);
            if (taskDescription != null) {
                pw.print("   ");
                final ActivityManager.TaskDescription td = taskDescription;
                pw.print(" taskDescription {");
                pw.print(" colorBackground=#");
                pw.print(Integer.toHexString(td.getBackgroundColor()));
                pw.print(" colorPrimary=#");
                pw.print(Integer.toHexString(td.getPrimaryColor()));
                pw.print(" iconRes=");
                pw.print(td.getIconResourcePackage() + "/" + td.getIconResource());
                pw.print(" iconBitmap=");
                pw.print(td.getIconFilename() != null || td.getInMemoryIcon() != null);
                pw.print(" resizeMode=");
                pw.print(ActivityInfo.resizeModeToString(td.getResizeMode()));
                pw.print(" minWidth="); pw.print(td.getMinWidth());
                pw.print(" minHeight="); pw.print(td.getMinHeight());
                pw.print(" colorBackgroundFloating=#");
                pw.print(Integer.toHexString(td.getBackgroundColorFloating()));
                pw.println(" }");
            }
            pw.print("   ");
            pw.print(" lastSnapshotData {");
            pw.print(" taskSize=" + lastSnapshotData.taskSize);
            pw.print(" contentInsets=" + lastSnapshotData.contentInsets);
            pw.print(" bufferSize=" + lastSnapshotData.bufferSize);
            pw.println(" }");
        }
    }

    /**
     * Flag for use with {@link #getRecentTasks}: return all tasks, even those
     * that have set their
     * {@link android.content.Intent#FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS} flag.
     */
    public static final int RECENT_WITH_EXCLUDED = 0x0001;

    /**
     * Provides a list that does not contain any
     * recent tasks that currently are not available to the user.
     */
    public static final int RECENT_IGNORE_UNAVAILABLE = 0x0002;

    /**
     * <p></p>Return a list of the tasks that the user has recently launched, with
     * the most recent being first and older ones after in order.
     *
     * <p><b>Note: this method is only intended for debugging and presenting
     * task management user interfaces</b>.  This should never be used for
     * core logic in an application, such as deciding between different
     * behaviors based on the information found here.  Such uses are
     * <em>not</em> supported, and will likely break in the future.  For
     * example, if multiple applications can be actively running at the
     * same time, assumptions made about the meaning of the data here for
     * purposes of control flow will be incorrect.</p>
     *
     * @deprecated As of {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this method is
     * no longer available to third party applications: the introduction of
     * document-centric recents means
     * it can leak personal information to the caller.  For backwards compatibility,
     * it will still return a small subset of its data: at least the caller's
     * own tasks (though see {@link #getAppTasks()} for the correct supported
     * way to retrieve that information), and possibly some other tasks
     * such as home that are known to not be sensitive.
     *
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many tasks the
     * user has started and the maximum number the system can remember.
     * @param flags Information about what to return.  May be any combination
     * of {@link #RECENT_WITH_EXCLUDED} and {@link #RECENT_IGNORE_UNAVAILABLE}.
     *
     * @return Returns a list of RecentTaskInfo records describing each of
     * the recent tasks.
     */
    @Deprecated
    public List<RecentTaskInfo> getRecentTasks(int maxNum, int flags) throws SecurityException {
        if (maxNum < 0) {
            throw new IllegalArgumentException("The requested number of tasks should be >= 0");
        }
        return ActivityTaskManager.getInstance().getRecentTasks(
                maxNum, flags, mContext.getUserId());
    }

    /**
     * Information you can retrieve about a particular task that is currently
     * "running" in the system.  Note that a running task does not mean the
     * given task actually has a process it is actively running in; it simply
     * means that the user has gone to it and never closed it, but currently
     * the system may have killed its process and is only holding on to its
     * last state in order to restart it when the user returns.
     */
    public static class RunningTaskInfo extends TaskInfo implements Parcelable {

        /**
         * A unique identifier for this task.
         *
         * @deprecated As of {@link android.os.Build.VERSION_CODES#Q}, use
         * {@link RunningTaskInfo#taskId}.
         */
        @Deprecated
        public int id;

        /**
         * Thumbnail representation of the task's current state.
         *
         * @deprecated As of {@link android.os.Build.VERSION_CODES#Q}, currently always null.
         */
        @Deprecated
        public Bitmap thumbnail;

        /**
         * Description of the task's current state.
         *
         * @deprecated As of {@link android.os.Build.VERSION_CODES#Q}, currently always null.
         */
        @Deprecated
        public CharSequence description;

        /**
         * Number of activities that are currently running (not stopped and persisted) in this task.
         *
         * @deprecated As of {@link android.os.Build.VERSION_CODES#Q}, currently always 0.
         */
        @Deprecated
        public int numRunning;

        public RunningTaskInfo() {
        }

        private RunningTaskInfo(Parcel source) {
            readFromParcel(source);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public void readFromParcel(Parcel source) {
            id = source.readInt();
            super.readFromParcel(source);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            super.writeToParcel(dest, flags);
        }

        public static final @android.annotation.NonNull Creator<RunningTaskInfo> CREATOR = new Creator<RunningTaskInfo>() {
            public RunningTaskInfo createFromParcel(Parcel source) {
                return new RunningTaskInfo(source);
            }
            public RunningTaskInfo[] newArray(int size) {
                return new RunningTaskInfo[size];
            }
        };
    }

    /**
     * Get the list of tasks associated with the calling application.
     *
     * @return The list of tasks associated with the application making this call.
     * @throws SecurityException
     */
    public List<ActivityManager.AppTask> getAppTasks() {
        ArrayList<AppTask> tasks = new ArrayList<AppTask>();
        List<IBinder> appTasks;
        try {
            appTasks = getTaskService().getAppTasks(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        int numAppTasks = appTasks.size();
        for (int i = 0; i < numAppTasks; i++) {
            tasks.add(new AppTask(IAppTask.Stub.asInterface(appTasks.get(i))));
        }
        return tasks;
    }

    /**
     * Return the current design dimensions for {@link AppTask} thumbnails, for use
     * with {@link #addAppTask}.
     */
    public Size getAppTaskThumbnailSize() {
        synchronized (this) {
            ensureAppTaskThumbnailSizeLocked();
            return new Size(mAppTaskThumbnailSize.x, mAppTaskThumbnailSize.y);
        }
    }

    private void ensureAppTaskThumbnailSizeLocked() {
        if (mAppTaskThumbnailSize == null) {
            try {
                mAppTaskThumbnailSize = getTaskService().getAppTaskThumbnailSize();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Add a new {@link AppTask} for the calling application.  This will create a new
     * recents entry that is added to the <b>end</b> of all existing recents.
     *
     * @param activity The activity that is adding the entry.   This is used to help determine
     * the context that the new recents entry will be in.
     * @param intent The Intent that describes the recents entry.  This is the same Intent that
     * you would have used to launch the activity for it.  In generally you will want to set
     * both {@link Intent#FLAG_ACTIVITY_NEW_DOCUMENT} and
     * {@link Intent#FLAG_ACTIVITY_RETAIN_IN_RECENTS}; the latter is required since this recents
     * entry will exist without an activity, so it doesn't make sense to not retain it when
     * its activity disappears.  The given Intent here also must have an explicit ComponentName
     * set on it.
     * @param description Optional additional description information.
     * @param thumbnail Thumbnail to use for the recents entry.  Should be the size given by
     * {@link #getAppTaskThumbnailSize()}.  If the bitmap is not that exact size, it will be
     * recreated in your process, probably in a way you don't like, before the recents entry
     * is added.
     *
     * @return Returns the task id of the newly added app task, or -1 if the add failed.  The
     * most likely cause of failure is that there is no more room for more tasks for your app.
     */
    public int addAppTask(@NonNull Activity activity, @NonNull Intent intent,
            @Nullable TaskDescription description, @NonNull Bitmap thumbnail) {
        Point size;
        synchronized (this) {
            ensureAppTaskThumbnailSizeLocked();
            size = mAppTaskThumbnailSize;
        }
        final int tw = thumbnail.getWidth();
        final int th = thumbnail.getHeight();
        if (tw != size.x || th != size.y) {
            Bitmap bm = Bitmap.createBitmap(size.x, size.y, thumbnail.getConfig());

            // Use ScaleType.CENTER_CROP, except we leave the top edge at the top.
            float scale;
            float dx = 0, dy = 0;
            if (tw * size.x > size.y * th) {
                scale = (float) size.x / (float) th;
                dx = (size.y - tw * scale) * 0.5f;
            } else {
                scale = (float) size.y / (float) tw;
                dy = (size.x - th * scale) * 0.5f;
            }
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate((int) (dx + 0.5f), 0);

            Canvas canvas = new Canvas(bm);
            canvas.drawBitmap(thumbnail, matrix, null);
            canvas.setBitmap(null);

            thumbnail = bm;
        }
        if (description == null) {
            description = new TaskDescription();
        }
        try {
            return getTaskService().addAppTask(activity.getActivityToken(),
                    intent, description, thumbnail);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return a list of the tasks that are currently running, with
     * the most recent being first and older ones after in order.  Note that
     * "running" does not mean any of the task's code is currently loaded or
     * activity -- the task may have been frozen by the system, so that it
     * can be restarted in its previous state when next brought to the
     * foreground.
     *
     * <p><b>Note: this method is only intended for debugging and presenting
     * task management user interfaces</b>.  This should never be used for
     * core logic in an application, such as deciding between different
     * behaviors based on the information found here.  Such uses are
     * <em>not</em> supported, and will likely break in the future.  For
     * example, if multiple applications can be actively running at the
     * same time, assumptions made about the meaning of the data here for
     * purposes of control flow will be incorrect.</p>
     *
     * @deprecated As of {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this method
     * is no longer available to third party
     * applications: the introduction of document-centric recents means
     * it can leak person information to the caller.  For backwards compatibility,
     * it will still return a small subset of its data: at least the caller's
     * own tasks, and possibly some other tasks
     * such as home that are known to not be sensitive.
     *
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many tasks the
     * user has started.
     *
     * @return Returns a list of RunningTaskInfo records describing each of
     * the running tasks.
     */
    @Deprecated
    public List<RunningTaskInfo> getRunningTasks(int maxNum)
            throws SecurityException {
        return ActivityTaskManager.getInstance().getTasks(maxNum);
    }

    /** @hide */
    @IntDef(flag = true, prefix = { "MOVE_TASK_" }, value = {
            MOVE_TASK_WITH_HOME,
            MOVE_TASK_NO_USER_ACTION,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MoveTaskFlags {}

    /**
     * Flag for {@link #moveTaskToFront(int, int)}: also move the "home"
     * activity along with the task, so it is positioned immediately behind
     * the task.
     */
    public static final int MOVE_TASK_WITH_HOME = 0x00000001;

    /**
     * Flag for {@link #moveTaskToFront(int, int)}: don't count this as a
     * user-instigated action, so the current activity will not receive a
     * hint that the user is leaving.
     */
    public static final int MOVE_TASK_NO_USER_ACTION = 0x00000002;

    /**
     * Equivalent to calling {@link #moveTaskToFront(int, int, Bundle)}
     * with a null options argument.
     *
     * @param taskId The identifier of the task to be moved, as found in
     * {@link RunningTaskInfo} or {@link RecentTaskInfo}.
     * @param flags Additional operational flags.
     */
    @RequiresPermission(android.Manifest.permission.REORDER_TASKS)
    public void moveTaskToFront(int taskId, @MoveTaskFlags int flags) {
        moveTaskToFront(taskId, flags, null);
    }

    /**
     * Ask that the task associated with a given task ID be moved to the
     * front of the stack, so it is now visible to the user.
     *
     * @param taskId The identifier of the task to be moved, as found in
     * {@link RunningTaskInfo} or {@link RecentTaskInfo}.
     * @param flags Additional operational flags.
     * @param options Additional options for the operation, either null or
     * as per {@link Context#startActivity(Intent, android.os.Bundle)
     * Context.startActivity(Intent, Bundle)}.
     */
    @RequiresPermission(android.Manifest.permission.REORDER_TASKS)
    public void moveTaskToFront(int taskId, @MoveTaskFlags int flags, Bundle options) {
        try {
            ActivityThread thread = ActivityThread.currentActivityThread();
            IApplicationThread appThread = thread.getApplicationThread();
            String packageName = mContext.getOpPackageName();
            getTaskService().moveTaskToFront(appThread, packageName, taskId, flags, options);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Check if the context is allowed to start an activity on specified display. Some launch
     * restrictions may apply to secondary displays that are private, virtual, or owned by the
     * system, in which case an activity start may throw a {@link SecurityException}. Call this
     * method prior to starting an activity on a secondary display to check if the current context
     * has access to it.
     *
     * @see ActivityOptions#setLaunchDisplayId(int)
     * @see android.view.Display#FLAG_PRIVATE
     *
     * @param context Source context, from which an activity will be started.
     * @param displayId Target display id.
     * @param intent Intent used to launch an activity.
     * @return {@code true} if a call to start an activity on the target display is allowed for the
     * provided context and no {@link SecurityException} will be thrown, {@code false} otherwise.
     */
    public boolean isActivityStartAllowedOnDisplay(@NonNull Context context, int displayId,
            @NonNull Intent intent) {
        try {
            return getTaskService().isActivityStartAllowedOnDisplay(displayId, intent,
                    intent.resolveTypeIfNeeded(context.getContentResolver()), context.getUserId());
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return false;
    }

    /**
     * Information you can retrieve about a particular Service that is
     * currently running in the system.
     */
    public static class RunningServiceInfo implements Parcelable {
        /**
         * The service component.
         */
        public ComponentName service;

        /**
         * If non-zero, this is the process the service is running in.
         */
        public int pid;

        /**
         * The UID that owns this service.
         */
        public int uid;

        /**
         * The name of the process this service runs in.
         */
        public String process;

        /**
         * Set to true if the service has asked to run as a foreground process.
         */
        public boolean foreground;

        /**
         * The time when the service was first made active, either by someone
         * starting or binding to it.  This
         * is in units of {@link android.os.SystemClock#elapsedRealtime()}.
         */
        public long activeSince;

        /**
         * Set to true if this service has been explicitly started.
         */
        public boolean started;

        /**
         * Number of clients connected to the service.
         */
        public int clientCount;

        /**
         * Number of times the service's process has crashed while the service
         * is running.
         */
        public int crashCount;

        /**
         * The time when there was last activity in the service (either
         * explicit requests to start it or clients binding to it).  This
         * is in units of {@link android.os.SystemClock#uptimeMillis()}.
         */
        public long lastActivityTime;

        /**
         * If non-zero, this service is not currently running, but scheduled to
         * restart at the given time.
         */
        public long restarting;

        /**
         * Bit for {@link #flags}: set if this service has been
         * explicitly started.
         */
        public static final int FLAG_STARTED = 1<<0;

        /**
         * Bit for {@link #flags}: set if the service has asked to
         * run as a foreground process.
         */
        public static final int FLAG_FOREGROUND = 1<<1;

        /**
         * Bit for {@link #flags}: set if the service is running in a
         * core system process.
         */
        public static final int FLAG_SYSTEM_PROCESS = 1<<2;

        /**
         * Bit for {@link #flags}: set if the service is running in a
         * persistent process.
         */
        public static final int FLAG_PERSISTENT_PROCESS = 1<<3;

        /**
         * Running flags.
         */
        public int flags;

        /**
         * For special services that are bound to by system code, this is
         * the package that holds the binding.
         */
        public String clientPackage;

        /**
         * For special services that are bound to by system code, this is
         * a string resource providing a user-visible label for who the
         * client is.
         */
        public int clientLabel;

        public RunningServiceInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            ComponentName.writeToParcel(service, dest);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeString(process);
            dest.writeInt(foreground ? 1 : 0);
            dest.writeLong(activeSince);
            dest.writeInt(started ? 1 : 0);
            dest.writeInt(clientCount);
            dest.writeInt(crashCount);
            dest.writeLong(lastActivityTime);
            dest.writeLong(restarting);
            dest.writeInt(this.flags);
            dest.writeString(clientPackage);
            dest.writeInt(clientLabel);
        }

        public void readFromParcel(Parcel source) {
            service = ComponentName.readFromParcel(source);
            pid = source.readInt();
            uid = source.readInt();
            process = source.readString();
            foreground = source.readInt() != 0;
            activeSince = source.readLong();
            started = source.readInt() != 0;
            clientCount = source.readInt();
            crashCount = source.readInt();
            lastActivityTime = source.readLong();
            restarting = source.readLong();
            flags = source.readInt();
            clientPackage = source.readString();
            clientLabel = source.readInt();
        }

        public static final @android.annotation.NonNull Creator<RunningServiceInfo> CREATOR = new Creator<RunningServiceInfo>() {
            public RunningServiceInfo createFromParcel(Parcel source) {
                return new RunningServiceInfo(source);
            }
            public RunningServiceInfo[] newArray(int size) {
                return new RunningServiceInfo[size];
            }
        };

        private RunningServiceInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    /**
     * Return a list of the services that are currently running.
     *
     * <p><b>Note: this method is only intended for debugging or implementing
     * service management type user interfaces.</b></p>
     *
     * @deprecated As of {@link android.os.Build.VERSION_CODES#O}, this method
     * is no longer available to third party applications.  For backwards compatibility,
     * it will still return the caller's own services.
     *
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many services
     * are running.
     *
     * @return Returns a list of RunningServiceInfo records describing each of
     * the running tasks.
     */
    @Deprecated
    public List<RunningServiceInfo> getRunningServices(int maxNum)
            throws SecurityException {
        try {
            return getService()
                    .getServices(maxNum, 0);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a PendingIntent you can start to show a control panel for the
     * given running service.  If the service does not have a control panel,
     * null is returned.
     */
    public PendingIntent getRunningServiceControlPanel(ComponentName service)
            throws SecurityException {
        try {
            return getService()
                    .getRunningServiceControlPanel(service);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Information you can retrieve about the available memory through
     * {@link ActivityManager#getMemoryInfo}.
     */
    public static class MemoryInfo implements Parcelable {
        /**
         * The advertised memory of the system, as the end user would encounter in a retail display
         * environment. This value might be different from {@code totalMem}. This could be due to
         * many reasons. For example, the ODM could reserve part of the memory for the Trusted
         * Execution Environment (TEE) which the kernel doesn't have access or knowledge about it.
         */
        @SuppressLint("MutableBareField")
        public long advertisedMem;

        /**
         * The available memory on the system.  This number should not
         * be considered absolute: due to the nature of the kernel, a significant
         * portion of this memory is actually in use and needed for the overall
         * system to run well.
         */
        public long availMem;

        /**
         * The total memory accessible by the kernel.  This is basically the
         * RAM size of the device, not including below-kernel fixed allocations
         * like DMA buffers, RAM for the baseband CPU, etc.
         */
        public long totalMem;

        /**
         * The threshold of {@link #availMem} at which we consider memory to be
         * low and start killing background services and other non-extraneous
         * processes.
         */
        public long threshold;

        /**
         * Set to true if the system considers itself to currently be in a low
         * memory situation.
         */
        public boolean lowMemory;

        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public long hiddenAppThreshold;
        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public long secondaryServerThreshold;
        /** @hide */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public long visibleAppThreshold;
        /** @hide */
        @UnsupportedAppUsage
        public long foregroundAppThreshold;

        public MemoryInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(advertisedMem);
            dest.writeLong(availMem);
            dest.writeLong(totalMem);
            dest.writeLong(threshold);
            dest.writeInt(lowMemory ? 1 : 0);
            dest.writeLong(hiddenAppThreshold);
            dest.writeLong(secondaryServerThreshold);
            dest.writeLong(visibleAppThreshold);
            dest.writeLong(foregroundAppThreshold);
        }

        public void readFromParcel(Parcel source) {
            advertisedMem = source.readLong();
            availMem = source.readLong();
            totalMem = source.readLong();
            threshold = source.readLong();
            lowMemory = source.readInt() != 0;
            hiddenAppThreshold = source.readLong();
            secondaryServerThreshold = source.readLong();
            visibleAppThreshold = source.readLong();
            foregroundAppThreshold = source.readLong();
        }

        /** @hide */
        public void copyTo(MemoryInfo other) {
            other.advertisedMem = advertisedMem;
            other.availMem = availMem;
            other.totalMem = totalMem;
            other.threshold = threshold;
            other.lowMemory = lowMemory;
            other.hiddenAppThreshold = hiddenAppThreshold;
            other.secondaryServerThreshold = secondaryServerThreshold;
            other.visibleAppThreshold = visibleAppThreshold;
            other.foregroundAppThreshold = foregroundAppThreshold;
        }

        public static final @android.annotation.NonNull Creator<MemoryInfo> CREATOR
                = new Creator<MemoryInfo>() {
            public MemoryInfo createFromParcel(Parcel source) {
                return new MemoryInfo(source);
            }
            public MemoryInfo[] newArray(int size) {
                return new MemoryInfo[size];
            }
        };

        private MemoryInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    /**
     * Return general information about the memory state of the system.  This
     * can be used to help decide how to manage your own memory, though note
     * that polling is not recommended and
     * {@link android.content.ComponentCallbacks2#onTrimMemory(int)
     * ComponentCallbacks2.onTrimMemory(int)} is the preferred way to do this.
     * Also see {@link #getMyMemoryState} for how to retrieve the current trim
     * level of your process as needed, which gives a better hint for how to
     * manage its memory.
     */
    public void getMemoryInfo(MemoryInfo outInfo) {
        if (Flags.rateLimitGetMemoryInfo()) {
            synchronized (mMemoryInfoCache) {
                mMemoryInfoCache.get(() -> {
                    getMemoryInfoInternal(mRateLimitedMemInfo);
                    return mRateLimitedMemInfo;
                });
                mRateLimitedMemInfo.copyTo(outInfo);
            }
        } else {
            getMemoryInfoInternal(outInfo);
        }
    }

    private void getMemoryInfoInternal(MemoryInfo outInfo) {
        try {
            getService().getMemoryInfo(outInfo);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     */
    @RequiresPermission(anyOf={Manifest.permission.CLEAR_APP_USER_DATA,
            Manifest.permission.ACCESS_INSTANT_APPS})
    @UnsupportedAppUsage
    public boolean clearApplicationUserData(String packageName, IPackageDataObserver observer) {
        try {
            return getService().clearApplicationUserData(packageName, false,
                    observer, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Permits an application to erase its own data from disk.  This is equivalent to
     * the user choosing to clear the app's data from within the device settings UI.  It
     * erases all dynamic data associated with the app -- its private data and data in its
     * private area on external storage -- but does not remove the installed application
     * itself, nor any OBB files. It also revokes all runtime permissions that the app has acquired,
     * clears all notifications and removes all Uri grants related to this application.
     *
     * @return {@code true} if the application successfully requested that the application's
     *     data be erased; {@code false} otherwise.
     */
    public boolean clearApplicationUserData() {
        return clearApplicationUserData(mContext.getPackageName(), null);
    }

    /**
     * Permits an application to get the persistent URI permissions granted to another.
     *
     * <p>Typically called by Settings or DocumentsUI, requires
     * {@code GET_APP_GRANTED_URI_PERMISSIONS}.
     *
     * @param packageName application to look for the granted permissions, or {@code null} to get
     * granted permissions for all applications
     * @return list of granted URI permissions
     *
     * @hide
     * @deprecated use {@link UriGrantsManager#getGrantedUriPermissions(String)} instead.
     */
    @Deprecated
    public ParceledListSlice<GrantedUriPermission> getGrantedUriPermissions(
            @Nullable String packageName) {
        return ((UriGrantsManager) mContext.getSystemService(Context.URI_GRANTS_SERVICE))
                .getGrantedUriPermissions(packageName);
    }

    /**
     * Permits an application to clear the persistent URI permissions granted to another.
     *
     * <p>Typically called by Settings, requires {@code CLEAR_APP_GRANTED_URI_PERMISSIONS}.
     *
     * @param packageName application to clear its granted permissions
     *
     * @hide
     * @deprecated use {@link UriGrantsManager#clearGrantedUriPermissions(String)} instead.
     */
    @Deprecated
    public void clearGrantedUriPermissions(String packageName) {
        ((UriGrantsManager) mContext.getSystemService(Context.URI_GRANTS_SERVICE))
                .clearGrantedUriPermissions(packageName);
    }

    /**
     * Information you can retrieve about any processes that are in an error condition.
     */
    public static class ProcessErrorStateInfo implements Parcelable {
        /**
         * Condition codes
         */
        public static final int NO_ERROR = 0;
        public static final int CRASHED = 1;
        public static final int NOT_RESPONDING = 2;

        /**
         * The condition that the process is in.
         */
        public int condition;

        /**
         * The process name in which the crash or error occurred.
         */
        public String processName;

        /**
         * The pid of this process; 0 if none
         */
        public int pid;

        /**
         * The kernel user-ID that has been assigned to this process;
         * currently this is not a unique ID (multiple applications can have
         * the same uid).
         */
        public int uid;

        /**
         * The activity name associated with the error, if known.  May be null.
         */
        public String tag;

        /**
         * A short message describing the error condition.
         */
        public String shortMsg;

        /**
         * A long message describing the error condition.
         */
        public String longMsg;

        /**
         * The stack trace where the error originated.  May be null.
         */
        public String stackTrace;

        /**
         * to be deprecated: This value will always be null.
         */
        public byte[] crashData = null;

        public ProcessErrorStateInfo() {
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(condition);
            dest.writeString(processName);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeString(tag);
            dest.writeString(shortMsg);
            dest.writeString(longMsg);
            dest.writeString(stackTrace);
        }

        public void readFromParcel(Parcel source) {
            condition = source.readInt();
            processName = source.readString();
            pid = source.readInt();
            uid = source.readInt();
            tag = source.readString();
            shortMsg = source.readString();
            longMsg = source.readString();
            stackTrace = source.readString();
        }

        public static final @android.annotation.NonNull Creator<ProcessErrorStateInfo> CREATOR =
                new Creator<ProcessErrorStateInfo>() {
            public ProcessErrorStateInfo createFromParcel(Parcel source) {
                return new ProcessErrorStateInfo(source);
            }
            public ProcessErrorStateInfo[] newArray(int size) {
                return new ProcessErrorStateInfo[size];
            }
        };

        private ProcessErrorStateInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    /**
     * Returns a list of any processes that are currently in an error condition.  The result
     * will be null if all processes are running properly at this time.
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#TIRAMISU Android TIRAMISU}, for regular apps
     * this method will only return {@link ProcessErrorStateInfo} records for the processes running
     * as the caller's uid, unless the caller has the permission
     * {@link android.Manifest.permission#DUMP}.
     * </p>
     *
     * @return Returns a list of {@link ProcessErrorStateInfo} records, or null if there are no
     * current error conditions (it will not return an empty list).  This list ordering is not
     * specified.
     */
    public List<ProcessErrorStateInfo> getProcessesInErrorState() {
        if (Flags.rateLimitGetProcessesInErrorState()) {
            return mErrorProcessesCache.get(() -> {
                return getProcessesInErrorStateInternal();
            });
        } else {
            return getProcessesInErrorStateInternal();
        }
    }

    private List<ProcessErrorStateInfo> getProcessesInErrorStateInternal() {
        try {
            return getService().getProcessesInErrorState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Information you can retrieve about a running process.
     */
    public static class RunningAppProcessInfo implements Parcelable {
        /**
         * The name of the process that this object is associated with
         */
        public String processName;

        /**
         * The pid of this process; 0 if none
         */
        public int pid;

        /**
         * The user id of this process.
         */
        public int uid;

        /**
         * All packages that have been loaded into the process.
         */
        public String[] pkgList;

        /**
         * Additional packages loaded into the process as dependency.
         * @hide
         */
        public String[] pkgDeps;

        /**
         * Constant for {@link #flags}: this is an app that is unable to
         * correctly save its state when going to the background,
         * so it can not be killed while in the background.
         * @hide
         */
        public static final int FLAG_CANT_SAVE_STATE = 1<<0;

        /**
         * Constant for {@link #flags}: this process is associated with a
         * persistent system app.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final int FLAG_PERSISTENT = 1<<1;

        /**
         * Constant for {@link #flags}: this process is associated with a
         * persistent system app.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static final int FLAG_HAS_ACTIVITIES = 1<<2;

        /**
         * Flags of information.  May be any of
         * {@link #FLAG_CANT_SAVE_STATE}.
         * @hide
         */
        @UnsupportedAppUsage
        public int flags;

        /**
         * Last memory trim level reported to the process: corresponds to
         * the values supplied to {@link android.content.ComponentCallbacks2#onTrimMemory(int)
         * ComponentCallbacks2.onTrimMemory(int)}.
         */
        public int lastTrimLevel;

        /** @hide */
        @IntDef(prefix = { "IMPORTANCE_" }, value = {
                IMPORTANCE_FOREGROUND,
                IMPORTANCE_FOREGROUND_SERVICE,
                IMPORTANCE_TOP_SLEEPING,
                IMPORTANCE_VISIBLE,
                IMPORTANCE_PERCEPTIBLE,
                IMPORTANCE_CANT_SAVE_STATE,
                IMPORTANCE_SERVICE,
                IMPORTANCE_CACHED,
                IMPORTANCE_GONE,
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Importance {}

        /**
         * Constant for {@link #importance}: This process is running the
         * foreground UI; that is, it is the thing currently at the top of the screen
         * that the user is interacting with.
         */
        public static final int IMPORTANCE_FOREGROUND = 100;

        /**
         * Constant for {@link #importance}: This process is running a foreground
         * service, for example to perform music playback even while the user is
         * not immediately in the app.  This generally indicates that the process
         * is doing something the user actively cares about.
         */
        public static final int IMPORTANCE_FOREGROUND_SERVICE = 125;

        /**
         * @deprecated Pre-{@link android.os.Build.VERSION_CODES#P} version of
         * {@link #IMPORTANCE_TOP_SLEEPING}.  As of Android
         * {@link android.os.Build.VERSION_CODES#P}, this is considered much less
         * important since we want to reduce what apps can do when the screen is off.
         */
        @Deprecated
        public static final int IMPORTANCE_TOP_SLEEPING_PRE_28 = 150;

        /**
         * Constant for {@link #importance}: This process is running something
         * that is actively visible to the user, though not in the immediate
         * foreground.  This may be running a window that is behind the current
         * foreground (so paused and with its state saved, not interacting with
         * the user, but visible to them to some degree); it may also be running
         * other services under the system's control that it considers important.
         */
        public static final int IMPORTANCE_VISIBLE = 200;

        /**
         * Constant for {@link #importance}: {@link #IMPORTANCE_PERCEPTIBLE} had this wrong value
         * before {@link Build.VERSION_CODES#O}.  Since the {@link Build.VERSION_CODES#O} SDK,
         * the value of {@link #IMPORTANCE_PERCEPTIBLE} has been fixed.
         *
         * <p>The system will return this value instead of {@link #IMPORTANCE_PERCEPTIBLE}
         * on Android versions below {@link Build.VERSION_CODES#O}.
         *
         * <p>On Android version {@link Build.VERSION_CODES#O} and later, this value will still be
         * returned for apps with the target API level below {@link Build.VERSION_CODES#O}.
         * For apps targeting version {@link Build.VERSION_CODES#O} and later,
         * the correct value {@link #IMPORTANCE_PERCEPTIBLE} will be returned.
         */
        public static final int IMPORTANCE_PERCEPTIBLE_PRE_26 = 130;

        /**
         * Constant for {@link #importance}: This process is not something the user
         * is directly aware of, but is otherwise perceptible to them to some degree.
         */
        public static final int IMPORTANCE_PERCEPTIBLE = 230;

        /**
         * Constant for {@link #importance}: {@link #IMPORTANCE_CANT_SAVE_STATE} had
         * this wrong value
         * before {@link Build.VERSION_CODES#O}.  Since the {@link Build.VERSION_CODES#O} SDK,
         * the value of {@link #IMPORTANCE_CANT_SAVE_STATE} has been fixed.
         *
         * <p>The system will return this value instead of {@link #IMPORTANCE_CANT_SAVE_STATE}
         * on Android versions below {@link Build.VERSION_CODES#O}.
         *
         * <p>On Android version {@link Build.VERSION_CODES#O} after, this value will still be
         * returned for apps with the target API level below {@link Build.VERSION_CODES#O}.
         * For apps targeting version {@link Build.VERSION_CODES#O} and later,
         * the correct value {@link #IMPORTANCE_CANT_SAVE_STATE} will be returned.
         *
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        @TestApi
        public static final int IMPORTANCE_CANT_SAVE_STATE_PRE_26 = 170;

        /**
         * Constant for {@link #importance}: This process contains services
         * that should remain running.  These are background services apps have
         * started, not something the user is aware of, so they may be killed by
         * the system relatively freely (though it is generally desired that they
         * stay running as long as they want to).
         */
        public static final int IMPORTANCE_SERVICE = 300;

        /**
         * Constant for {@link #importance}: This process is running the foreground
         * UI, but the device is asleep so it is not visible to the user.  Though the
         * system will try hard to keep its process from being killed, in all other
         * ways we consider it a kind of cached process, with the limitations that go
         * along with that state: network access, running background services, etc.
         */
        public static final int IMPORTANCE_TOP_SLEEPING = 325;

        /**
         * Constant for {@link #importance}: This process is running an
         * application that can not save its state, and thus can't be killed
         * while in the background.  This will be used with apps that have
         * {@link android.R.attr#cantSaveState} set on their application tag.
         */
        public static final int IMPORTANCE_CANT_SAVE_STATE = 350;

        /**
         * Constant for {@link #importance}: This process contains cached code
         * that is expendable, not actively running any app components we care
         * about.
         */
        public static final int IMPORTANCE_CACHED = 400;

        /**
         * @deprecated Renamed to {@link #IMPORTANCE_CACHED}.
         */
        public static final int IMPORTANCE_BACKGROUND = IMPORTANCE_CACHED;

        /**
         * Constant for {@link #importance}: This process is empty of any
         * actively running code.
         * @deprecated This value is no longer reported, use {@link #IMPORTANCE_CACHED} instead.
         */
        @Deprecated
        public static final int IMPORTANCE_EMPTY = 500;

        /**
         * Constant for {@link #importance}: This process does not exist.
         */
        public static final int IMPORTANCE_GONE = 1000;

        /**
         * Convert a proc state to the correspondent IMPORTANCE_* constant.  If the return value
         * will be passed to a client, use {@link #procStateToImportanceForClient}.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        public static @Importance int procStateToImportance(int procState) {
            if (procState == PROCESS_STATE_NONEXISTENT) {
                return IMPORTANCE_GONE;
            } else if (procState >= PROCESS_STATE_HOME) {
                return IMPORTANCE_CACHED;
            } else if (procState == PROCESS_STATE_HEAVY_WEIGHT) {
                return IMPORTANCE_CANT_SAVE_STATE;
            } else if (procState >= PROCESS_STATE_TOP_SLEEPING) {
                return IMPORTANCE_TOP_SLEEPING;
            } else if (procState >= PROCESS_STATE_SERVICE) {
                return IMPORTANCE_SERVICE;
            } else if (procState >= PROCESS_STATE_TRANSIENT_BACKGROUND) {
                return IMPORTANCE_PERCEPTIBLE;
            } else if (procState >= PROCESS_STATE_IMPORTANT_FOREGROUND) {
                return IMPORTANCE_VISIBLE;
            } else if (procState >= PROCESS_STATE_FOREGROUND_SERVICE) {
                return IMPORTANCE_FOREGROUND_SERVICE;
            } else {
                return IMPORTANCE_FOREGROUND;
            }
        }

        /**
         * Convert a proc state to the correspondent IMPORTANCE_* constant for a client represented
         * by a given {@link Context}, with converting {@link #IMPORTANCE_PERCEPTIBLE}
         * and {@link #IMPORTANCE_CANT_SAVE_STATE} to the corresponding "wrong" value if the
         * client's target SDK < {@link VERSION_CODES#O}.
         * @hide
         */
        public static @Importance int procStateToImportanceForClient(int procState,
                Context clientContext) {
            return procStateToImportanceForTargetSdk(procState,
                    clientContext.getApplicationInfo().targetSdkVersion);
        }

        /**
         * See {@link #procStateToImportanceForClient}.
         * @hide
         */
        public static @Importance int procStateToImportanceForTargetSdk(int procState,
                int targetSdkVersion) {
            final int importance = procStateToImportance(procState);

            // For pre O apps, convert to the old, wrong values.
            if (targetSdkVersion < VERSION_CODES.O) {
                switch (importance) {
                    case IMPORTANCE_PERCEPTIBLE:
                        return IMPORTANCE_PERCEPTIBLE_PRE_26;
                    case IMPORTANCE_TOP_SLEEPING:
                        return IMPORTANCE_TOP_SLEEPING_PRE_28;
                    case IMPORTANCE_CANT_SAVE_STATE:
                        return IMPORTANCE_CANT_SAVE_STATE_PRE_26;
                }
            }
            return importance;
        }

        /** @hide */
        public static int importanceToProcState(@Importance int importance) {
            if (importance == IMPORTANCE_GONE) {
                return PROCESS_STATE_NONEXISTENT;
            } else if (importance >= IMPORTANCE_CACHED) {
                return PROCESS_STATE_HOME;
            } else if (importance >= IMPORTANCE_CANT_SAVE_STATE) {
                return PROCESS_STATE_HEAVY_WEIGHT;
            } else if (importance >= IMPORTANCE_TOP_SLEEPING) {
                return PROCESS_STATE_TOP_SLEEPING;
            } else if (importance >= IMPORTANCE_SERVICE) {
                return PROCESS_STATE_SERVICE;
            } else if (importance >= IMPORTANCE_PERCEPTIBLE) {
                return PROCESS_STATE_TRANSIENT_BACKGROUND;
            } else if (importance >= IMPORTANCE_VISIBLE) {
                return PROCESS_STATE_IMPORTANT_FOREGROUND;
            } else if (importance >= IMPORTANCE_TOP_SLEEPING_PRE_28) {
                return PROCESS_STATE_IMPORTANT_FOREGROUND;
            } else if (importance >= IMPORTANCE_FOREGROUND_SERVICE) {
                return PROCESS_STATE_FOREGROUND_SERVICE;
                // TODO: Asymmetrical mapping for LOCATION service type. Ok?
            } else {
                return PROCESS_STATE_TOP;
            }
        }

        /**
         * The relative importance level that the system places on this process.
         * These constants are numbered so that "more important" values are
         * always smaller than "less important" values.
         */
        public @Importance int importance;

        /**
         * An additional ordering within a particular {@link #importance}
         * category, providing finer-grained information about the relative
         * utility of processes within a category.  This number means nothing
         * except that a smaller values are more recently used (and thus
         * more important).  Currently an LRU value is only maintained for
         * the {@link #IMPORTANCE_CACHED} category, though others may
         * be maintained in the future.
         */
        public int lru;

        /**
         * Constant for {@link #importanceReasonCode}: nothing special has
         * been specified for the reason for this level.
         */
        public static final int REASON_UNKNOWN = 0;

        /**
         * Constant for {@link #importanceReasonCode}: one of the application's
         * content providers is being used by another process.  The pid of
         * the client process is in {@link #importanceReasonPid} and the
         * target provider in this process is in
         * {@link #importanceReasonComponent}.
         */
        public static final int REASON_PROVIDER_IN_USE = 1;

        /**
         * Constant for {@link #importanceReasonCode}: one of the application's
         * content providers is being used by another process.  The pid of
         * the client process is in {@link #importanceReasonPid} and the
         * target provider in this process is in
         * {@link #importanceReasonComponent}.
         */
        public static final int REASON_SERVICE_IN_USE = 2;

        /**
         * The reason for {@link #importance}, if any.
         */
        public int importanceReasonCode;

        /**
         * For the specified values of {@link #importanceReasonCode}, this
         * is the process ID of the other process that is a client of this
         * process.  This will be 0 if no other process is using this one.
         */
        public int importanceReasonPid;

        /**
         * For the specified values of {@link #importanceReasonCode}, this
         * is the name of the component that is being used in this process.
         */
        public ComponentName importanceReasonComponent;

        /**
         * When {@link #importanceReasonPid} is non-0, this is the importance
         * of the other pid. @hide
         */
        public int importanceReasonImportance;

        /**
         * Current process state, as per PROCESS_STATE_* constants.
         * @hide
         */
        @UnsupportedAppUsage
        public int processState;

        /**
         * Whether the app is focused in multi-window environment.
         * @hide
         */
        public boolean isFocused;

        /**
         * Copy of {@link com.android.server.am.ProcessRecord#lastActivityTime} of the process.
         * @hide
         */
        public long lastActivityTime;

        public RunningAppProcessInfo() {
            importance = IMPORTANCE_FOREGROUND;
            importanceReasonCode = REASON_UNKNOWN;
            processState = PROCESS_STATE_IMPORTANT_FOREGROUND;
            isFocused = false;
            lastActivityTime = 0;
        }

        public RunningAppProcessInfo(String pProcessName, int pPid, String pArr[]) {
            processName = pProcessName;
            pid = pPid;
            pkgList = pArr;
            isFocused = false;
            lastActivityTime = 0;
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(processName);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeStringArray(pkgList);
            dest.writeStringArray(pkgDeps);
            dest.writeInt(this.flags);
            dest.writeInt(lastTrimLevel);
            dest.writeInt(importance);
            dest.writeInt(lru);
            dest.writeInt(importanceReasonCode);
            dest.writeInt(importanceReasonPid);
            ComponentName.writeToParcel(importanceReasonComponent, dest);
            dest.writeInt(importanceReasonImportance);
            dest.writeInt(processState);
            dest.writeInt(isFocused ? 1 : 0);
            dest.writeLong(lastActivityTime);
        }

        public void readFromParcel(Parcel source) {
            processName = source.readString();
            pid = source.readInt();
            uid = source.readInt();
            pkgList = source.readStringArray();
            pkgDeps = source.readStringArray();
            flags = source.readInt();
            lastTrimLevel = source.readInt();
            importance = source.readInt();
            lru = source.readInt();
            importanceReasonCode = source.readInt();
            importanceReasonPid = source.readInt();
            importanceReasonComponent = ComponentName.readFromParcel(source);
            importanceReasonImportance = source.readInt();
            processState = source.readInt();
            isFocused = source.readInt() != 0;
            lastActivityTime = source.readLong();
        }

        /**
         * Note: only fields that are updated in ProcessList.fillInProcMemInfoLOSP() are copied.
         * @hide
         */
        public void copyTo(RunningAppProcessInfo other) {
            other.pid = pid;
            other.uid = uid;
            other.flags = flags;
            other.lastTrimLevel = lastTrimLevel;
            other.importance = importance;
            other.lru = lru;
            other.importanceReasonCode = importanceReasonCode;
            other.processState = processState;
            other.isFocused = isFocused;
            other.lastActivityTime = lastActivityTime;
        }

        public static final @android.annotation.NonNull Creator<RunningAppProcessInfo> CREATOR =
            new Creator<RunningAppProcessInfo>() {
            public RunningAppProcessInfo createFromParcel(Parcel source) {
                return new RunningAppProcessInfo(source);
            }
            public RunningAppProcessInfo[] newArray(int size) {
                return new RunningAppProcessInfo[size];
            }
        };

        private RunningAppProcessInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    /**
     * Returns a list of application processes installed on external media
     * that are running on the device.
     *
     * <p><b>Note: this method is only intended for debugging or building
     * a user-facing process management UI.</b></p>
     *
     * @return Returns a list of ApplicationInfo records, or null if none
     * This list ordering is not specified.
     * @hide
     */
    public List<ApplicationInfo> getRunningExternalApplications() {
        try {
            return getService().getRunningExternalApplications();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Query whether the user has enabled background restrictions for this app.
     *
     * <p> The user may chose to do this, if they see that an app is consuming an unreasonable
     * amount of battery while in the background. </p>
     *
     * <p> If true, any work that the app tries to do will be aggressively restricted while it is in
     * the background. At a minimum, jobs and alarms will not execute and foreground services
     * cannot be started unless an app activity is in the foreground. </p>
     *
     * <p><b> Note that these restrictions stay in effect even when the device is charging.</b></p>
     *
     * @return true if user has enforced background restrictions for this app, false otherwise.
     */
    public boolean isBackgroundRestricted() {
        try {
            return getService().isBackgroundRestricted(mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the memory trim mode for a process and schedules a memory trim operation.
     *
     * <p><b>Note: this method is only intended for testing framework.</b></p>
     *
     * @return Returns true if successful.
     * @hide
     */
    public boolean setProcessMemoryTrimLevel(String process, int userId, int level) {
        try {
            return getService().setProcessMemoryTrimLevel(process, userId,
                    level);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of application processes that are running on the device.
     *
     * <p><b>Note: this method is only intended for debugging or building
     * a user-facing process management UI.</b></p>
     *
     * @return Returns a list of RunningAppProcessInfo records, or null if there are no
     * running processes (it will not return an empty list).  This list ordering is not
     * specified.
     */
    public List<RunningAppProcessInfo> getRunningAppProcesses() {
        if (!Flags.rateLimitGetRunningAppProcesses()) {
            return getRunningAppProcessesInternal();
        } else {
            return mRunningProcessesCache.get(() -> {
                return getRunningAppProcessesInternal();
            });
        }
    }

    private List<RunningAppProcessInfo> getRunningAppProcessesInternal() {
        try {
            return getService().getRunningAppProcesses();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return a list of {@link ApplicationStartInfo} records containing the information about the
     * most recent app startups.
     *
     * Records accessed using this path might include "incomplete" records such as in-progress app
     * starts. Accessing in-progress starts using this method lets you access start information
     * early to better optimize your startup path.
     *
     * <p class="note"> Note: System stores this historical information in a ring buffer and only
     * the most recent records will be returned. </p>
     *
     * @param maxNum      The maximum number of results to be returned; a value of 0
     *                    means to ignore this parameter and return all matching records. If fewer
     *                    records exist, all existing records will be returned.
     *
     * @return a list of {@link ApplicationStartInfo} records matching the criteria, sorted in
     *         the order from most recent to least recent.
     */
    @NonNull
    @FlaggedApi(Flags.FLAG_APP_START_INFO)
    public List<ApplicationStartInfo> getHistoricalProcessStartReasons(
            @IntRange(from = 0) int maxNum) {
        try {
            ParceledListSlice<ApplicationStartInfo> startInfos = getService()
                    .getHistoricalProcessStartReasons(null, maxNum, mContext.getUserId());
            return startInfos == null ? Collections.emptyList() : startInfos.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return a list of {@link ApplicationStartInfo} records containing the information about the
     * most recent app startups.
     *
     * Records accessed using this path might include "incomplete" records such as in-progress app
     * starts.
     *
     * <p class="note"> Note: System stores this historical information in a ring buffer and only
     * the most recent records will be returned. </p>
     *
     * @param packageName Package name for which app startups to receive.
     * @param maxNum      The maximum number of results to be returned; a value of 0
     *                    means to ignore this parameter and return all matching records. If fewer
     *                    records exist, all existing records will be returned.
     *
     * @return a list of {@link ApplicationStartInfo} records matching the criteria, sorted in
     *         the order from most recent to least recent.
     *
     * @hide
     */
    @NonNull
    @SystemApi
    @FlaggedApi(Flags.FLAG_APP_START_INFO)
    @RequiresPermission(Manifest.permission.DUMP)
    public List<ApplicationStartInfo> getExternalHistoricalProcessStartReasons(
            @NonNull String packageName, @IntRange(from = 0) int maxNum) {
        try {
            ParceledListSlice<ApplicationStartInfo> startInfos = getService()
                    .getHistoricalProcessStartReasons(packageName, maxNum, mContext.getUserId());
            return startInfos == null ? Collections.emptyList() : startInfos.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private final ArrayList<AppStartInfoCallbackWrapper> mAppStartInfoCallbacks =
            new ArrayList<>();
    @Nullable
    private IApplicationStartInfoCompleteListener mAppStartInfoCompleteListener = null;

    private static final class AppStartInfoCallbackWrapper {
        @NonNull final Executor mExecutor;
        @NonNull final Consumer<ApplicationStartInfo> mListener;

        AppStartInfoCallbackWrapper(@NonNull final Executor executor,
                @NonNull final Consumer<ApplicationStartInfo> listener) {
            mExecutor = executor;
            mListener = listener;
        }
    }

    /**
     * Adds a callback that is notified when the {@link ApplicationStartInfo} record of this startup
     * is complete. The startup is considered complete when the first frame is drawn.
     *
     * The callback doesn't wait for {@link Activity#reportFullyDrawn} to occur. Retrieve a copy
     * of {@link ApplicationStartInfo} after {@link Activity#reportFullyDrawn} is called (using this
     * callback or {@link getHistoricalProcessStartReasons}) if you need the
     * {@link ApplicationStartInfo.START_TIMESTAMP_FULLY_DRAWN} timestamp.
     *
     * If the current start record has already been completed (that is, the process is not currently
     * starting), the callback will be invoked immediately on the specified executor with the
     * previously completed {@link ApplicationStartInfo} record.
     *
     * Callback will be called at most once and removed automatically after being triggered.
     *
     * <p class="note"> Note: callback is asynchronous and should be made from a background thread.
     * </p>
     *
     * @param executor    The executor on which the listener should be called.
     * @param listener    Callback to be called when collection of {@link ApplicationStartInfo} is
     *                    complete. Will replace existing listener if one is already attached.
     *
     * @throws IllegalArgumentException if executor or listener are null.
     */
    @FlaggedApi(Flags.FLAG_APP_START_INFO)
    public void addApplicationStartInfoCompletionListener(@NonNull final Executor executor,
            @NonNull final Consumer<ApplicationStartInfo> listener) {
        Preconditions.checkNotNull(executor, "executor cannot be null");
        Preconditions.checkNotNull(listener, "listener cannot be null");
        synchronized (mAppStartInfoCallbacks) {
            for (int i = 0; i < mAppStartInfoCallbacks.size(); i++) {
                if (listener.equals(mAppStartInfoCallbacks.get(i).mListener)) {
                    return;
                }
            }
            if (mAppStartInfoCompleteListener == null) {
                mAppStartInfoCompleteListener = new IApplicationStartInfoCompleteListener.Stub() {
                    @Override
                    public void onApplicationStartInfoComplete(
                            ApplicationStartInfo applicationStartInfo) {
                        synchronized (mAppStartInfoCallbacks) {
                            for (int i = 0; i < mAppStartInfoCallbacks.size(); i++) {
                                final AppStartInfoCallbackWrapper callback =
                                        mAppStartInfoCallbacks.get(i);
                                callback.mExecutor.execute(() -> callback.mListener.accept(
                                        applicationStartInfo));
                            }
                            mAppStartInfoCallbacks.clear();
                            mAppStartInfoCompleteListener = null;
                        }
                    }
                };
                boolean succeeded = false;
                try {
                    getService().addApplicationStartInfoCompleteListener(
                            mAppStartInfoCompleteListener, mContext.getUserId());
                    succeeded = true;
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                if (succeeded) {
                    mAppStartInfoCallbacks.add(new AppStartInfoCallbackWrapper(executor, listener));
                } else {
                    mAppStartInfoCompleteListener = null;
                    mAppStartInfoCallbacks.clear();
                }
            } else {
                mAppStartInfoCallbacks.add(new AppStartInfoCallbackWrapper(executor, listener));
            }
        }
    }

    /**
     * Removes the provided callback set by {@link #addApplicationStartInfoCompletionListener}.
     */
    @FlaggedApi(Flags.FLAG_APP_START_INFO)
    public void removeApplicationStartInfoCompletionListener(
            @NonNull final Consumer<ApplicationStartInfo> listener) {
        Preconditions.checkNotNull(listener, "listener cannot be null");
        synchronized (mAppStartInfoCallbacks) {
            for (int i = 0; i < mAppStartInfoCallbacks.size(); i++) {
                final AppStartInfoCallbackWrapper callback = mAppStartInfoCallbacks.get(i);
                if (listener.equals(callback.mListener)) {
                    mAppStartInfoCallbacks.remove(i);
                    break;
                }
            }
            if (mAppStartInfoCompleteListener != null && mAppStartInfoCallbacks.isEmpty()) {
                try {
                    getService().removeApplicationStartInfoCompleteListener(
                            mAppStartInfoCompleteListener, mContext.getUserId());
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                mAppStartInfoCompleteListener = null;
            }
        }
    }

    /**
     * Adds an optional developer supplied timestamp to the calling apps most recent
     * {@link ApplicationStartInfo}. This is in addition to system recorded timestamps.
     *
     * <p class="note"> Note: any timestamps added after {@link Activity#reportFullyDrawn} is called
     * are discarded.</p>
     *
     * <p class="note"> Note: will overwrite existing timestamp if called with same key.</p>
     *
     * @param key         Unique key for timestamp. Must be greater than
     *                    {@link ApplicationStartInfo#START_TIMESTAMP_RESERVED_RANGE_SYSTEM} and
     *                    less than or equal to
     *                    {@link ApplicationStartInfo#START_TIMESTAMP_RESERVED_RANGE_DEVELOPER}.
     *                    Will thow {@link java.lang.IllegalArgumentException} if not in range.
     * @param timestampNs Clock monotonic time in nanoseconds of event to be recorded.
     */
    @FlaggedApi(Flags.FLAG_APP_START_INFO)
    public void addStartInfoTimestamp(@IntRange(
            from = ApplicationStartInfo.START_TIMESTAMP_RESERVED_RANGE_DEVELOPER_START,
            to = ApplicationStartInfo.START_TIMESTAMP_RESERVED_RANGE_DEVELOPER) int key,
            long timestampNs) {
        if (key <= ApplicationStartInfo.START_TIMESTAMP_RESERVED_RANGE_SYSTEM
                || key > ApplicationStartInfo.START_TIMESTAMP_RESERVED_RANGE_DEVELOPER) {
            throw new IllegalArgumentException("Key not in allowed range.");
        }
        try {
            getService().addStartInfoTimestamp(key, timestampNs, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return a list of {@link ApplicationExitInfo} records containing the reasons for the most
     * recent app deaths.
     *
     * <p class="note"> Note: System stores this historical information in a ring buffer and only
     * the most recent records will be returned. </p>
     *
     * <p class="note"> Note: In the case that this application was bound to an external service
     * with flag {@link android.content.Context#BIND_EXTERNAL_SERVICE}, the process of that external
     * service will be included in this package's exit info. </p>
     *
     * @param packageName Optional, a null value means match all packages belonging to the
     *                    caller's UID. If this package belongs to another UID, you must hold
     *                    {@link android.Manifest.permission#DUMP} in order to retrieve it.
     * @param pid         A process ID that used to belong to this package but died later; a value
     *                    of 0 means to ignore this parameter and return all matching records.
     * @param maxNum      The maximum number of results to be returned; a value of 0
     *                    means to ignore this parameter and return all matching records
     *
     * @return a list of {@link ApplicationExitInfo} records matching the criteria, sorted in
     *         the order from most recent to least recent.
     */
    @NonNull
    public List<ApplicationExitInfo> getHistoricalProcessExitReasons(@Nullable String packageName,
            @IntRange(from = 0) int pid, @IntRange(from = 0) int maxNum) {
        try {
            ParceledListSlice<ApplicationExitInfo> r = getService().getHistoricalProcessExitReasons(
                    packageName, pid, maxNum, mContext.getUserId());
            return r == null ? Collections.emptyList() : r.getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set custom state data for this process. It will be included in the record of
     * {@link ApplicationExitInfo} on the death of the current calling process; the new process
     * of the app can retrieve this state data by calling
     * {@link android.app.ApplicationExitInfo#getProcessStateSummary()
     * ApplicationExitInfo.getProcessStateSummary()} on the record returned by
     * {@link #getHistoricalProcessExitReasons}.
     *
     * <p> This would be useful for the calling app to save its stateful data: if it's
     * killed later for any reason, the new process of the app can know what the
     * previous process of the app was doing. For instance, you could use this to encode
     * the current level in a game, or a set of features/experiments that were enabled. Later you
     * could analyze under what circumstances the app tends to crash or use too much memory.
     * However, it's not suggested to rely on this to restore the applications previous UI state
     * or so, it's only meant for analyzing application healthy status.</p>
     *
     * <p> System might decide to throttle the calls to this API; so call this API in a reasonable
     * manner, excessive calls to this API could result a {@link java.lang.RuntimeException}.
     * </p>
     *
     * @param state The state data. To be advised, <b>DO NOT</b> include sensitive information/data
     * (PII, SPII, or other sensitive user data) here. Maximum length is 128 bytes.
     */
    public void setProcessStateSummary(@Nullable byte[] state) {
        try {
            getService().setProcessStateSummary(state);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return Whether or not the low memory kill will be reported in
     * {@link #getHistoricalProcessExitReasons}.
     *
     * @see ApplicationExitInfo#REASON_LOW_MEMORY
     */
    public static boolean isLowMemoryKillReportSupported() {
        return SystemProperties.getBoolean("persist.sys.lmk.reportkills", false);
    }

    /**
     * Returns the process state of this uid.
     *
     * If the caller does not hold {@link Manifest.permission#INTERACT_ACROSS_USERS_FULL}
     * permission, they can only query process state of UIDs running in the same user as the caller.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(allOf = {
            Manifest.permission.PACKAGE_USAGE_STATS,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL
    }, conditional = true)
    public int getUidProcessState(int uid) {
        try {
            return getService().getUidProcessState(uid, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the process capability of this uid.
     *
     * If the caller does not hold {@link Manifest.permission#INTERACT_ACROSS_USERS_FULL}
     * permission, they can only query process capabilities of UIDs running in the same user
     * as the caller.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(allOf = {
            Manifest.permission.PACKAGE_USAGE_STATS,
            Manifest.permission.INTERACT_ACROSS_USERS_FULL
    }, conditional = true)
    public @ProcessCapability int getUidProcessCapabilities(int uid) {
        try {
            return getService().getUidProcessCapabilities(uid, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the importance of a given package name, based on the processes that are
     * currently running.  The return value is one of the importance constants defined
     * in {@link RunningAppProcessInfo}, giving you the highest importance of all the
     * processes that this package has code running inside of.  If there are no processes
     * running its code, {@link RunningAppProcessInfo#IMPORTANCE_GONE} is returned.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    public @RunningAppProcessInfo.Importance int getPackageImportance(String packageName) {
        try {
            int procState = getService().getPackageProcessState(packageName,
                    mContext.getOpPackageName());
            return RunningAppProcessInfo.procStateToImportanceForClient(procState, mContext);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return the importance of a given uid, based on the processes that are
     * currently running.  The return value is one of the importance constants defined
     * in {@link RunningAppProcessInfo}, giving you the highest importance of all the
     * processes that this uid has running.  If there are no processes
     * running its code, {@link RunningAppProcessInfo#IMPORTANCE_GONE} is returned.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    public @RunningAppProcessInfo.Importance int getUidImportance(int uid) {
        try {
            int procState = getService().getUidProcessState(uid,
                    mContext.getOpPackageName());
            return RunningAppProcessInfo.procStateToImportanceForClient(procState, mContext);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Same as {@link #getUidImportance(int)}, but it only works on UIDs that currently
     * have a service binding, or provider reference, to the calling UID, even if the target UID
     * belong to another android user or profile.
     *
     * <p>This will return {@link RunningAppProcessInfo#IMPORTANCE_GONE} on all other UIDs,
     * regardless of if they're valid or not.
     *
     * <p>Privileged system apps may prefer this API to {@link #getUidImportance(int)} to
     * avoid requesting the permission {@link Manifest.permission#PACKAGE_USAGE_STATS}, which
     * would allow access to APIs that return more senstive information.
     *
     * @hide
     */
    @FlaggedApi(Flags.FLAG_GET_BINDING_UID_IMPORTANCE)
    @SystemApi
    @RequiresPermission(Manifest.permission.GET_BINDING_UID_IMPORTANCE)
    public @RunningAppProcessInfo.Importance int getBindingUidImportance(int uid) {
        try {
            int procState = getService().getBindingUidProcessState(uid,
                    mContext.getOpPackageName());
            return RunningAppProcessInfo.procStateToImportanceForClient(procState, mContext);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Callback to get reports about changes to the importance of a uid.  Use with
     * {@link #addOnUidImportanceListener}.
     * @hide
     */
    @SystemApi
    public interface OnUidImportanceListener {
        /**
         * The importance if a given uid has changed.  Will be one of the importance
         * values in {@link RunningAppProcessInfo};
         * {@link RunningAppProcessInfo#IMPORTANCE_GONE IMPORTANCE_GONE} will be reported
         * when the uid is no longer running at all.  This callback will happen on a thread
         * from a thread pool, not the main UI thread.
         * @param uid The uid whose importance has changed.
         * @param importance The new importance value as per {@link RunningAppProcessInfo}.
         */
        void onUidImportance(int uid, @RunningAppProcessInfo.Importance int importance);
    }

    /**
     * Start monitoring changes to the importance of all uids running in the system.
     * @param listener The listener callback that will receive change reports.
     * @param importanceCutpoint The level of importance in which the caller is interested
     * in differences.  For example, if {@link RunningAppProcessInfo#IMPORTANCE_PERCEPTIBLE}
     * is used here, you will receive a call each time a uids importance transitions between
     * being <= {@link RunningAppProcessInfo#IMPORTANCE_PERCEPTIBLE} and
     * > {@link RunningAppProcessInfo#IMPORTANCE_PERCEPTIBLE}.
     *
     * <p>The caller must hold the {@link android.Manifest.permission#PACKAGE_USAGE_STATS}
     * permission to use this feature.</p>
     *
     * <p>Calling this API with the same instance of {@code listener} without
     * unregistering with {@link #removeOnUidImportanceListener} before it will result in
     * an {@link IllegalArgumentException}.</p>
     *
     * @throws IllegalArgumentException If the listener is already registered.
     * @throws SecurityException If the caller does not hold
     * {@link android.Manifest.permission#PACKAGE_USAGE_STATS}.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    public void addOnUidImportanceListener(OnUidImportanceListener listener,
            @RunningAppProcessInfo.Importance int importanceCutpoint) {
        addOnUidImportanceListenerInternal(listener, importanceCutpoint, null /* uids */);
    }

    /**
     * Start monitoring changes to the importance of given uids running in the system.
     *
     * @param listener The listener callback that will receive change reports.
     * @param importanceCutpoint The level of importance in which the caller is interested
     * in differences.  For example, if {@link RunningAppProcessInfo#IMPORTANCE_PERCEPTIBLE}
     * is used here, you will receive a call each time a uids importance transitions between
     * being <= {@link RunningAppProcessInfo#IMPORTANCE_PERCEPTIBLE} and
     * > {@link RunningAppProcessInfo#IMPORTANCE_PERCEPTIBLE}.
     * @param uids The UIDs that this listener is interested with.
     * {@link #addOnUidImportanceListener(OnUidImportanceListener, int)} in this case.
     *
     * <p>Calling this API with the same instance of {@code listener} without
     * unregistering with {@link #removeOnUidImportanceListener} before it will result in
     * an {@link IllegalArgumentException}.</p>
     *
     * @throws IllegalArgumentException If the listener is already registered.
     * @hide
     */
    @FlaggedApi(Flags.FLAG_UID_IMPORTANCE_LISTENER_FOR_UIDS)
    @SystemApi
    @SuppressLint("SamShouldBeLast")
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    public void addOnUidImportanceListener(@NonNull OnUidImportanceListener listener,
            @RunningAppProcessInfo.Importance int importanceCutpoint, @NonNull int[] uids) {
        Objects.requireNonNull(listener);
        Objects.requireNonNull(uids);
        addOnUidImportanceListenerInternal(listener, importanceCutpoint, uids);
    }

    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    private void addOnUidImportanceListenerInternal(@NonNull OnUidImportanceListener listener,
            @RunningAppProcessInfo.Importance int importanceCutpoint, @Nullable int[] uids) {
        synchronized (mImportanceListeners) {
            if (mImportanceListeners.containsKey(listener)) {
                throw new IllegalArgumentException("Listener already registered: " + listener);
            }
            // TODO: implement the cut point in the system process to avoid IPCs.
            MyUidObserver observer = new MyUidObserver(listener, mContext);
            try {
                getService().registerUidObserverForUids(observer,
                        UID_OBSERVER_PROCSTATE | UID_OBSERVER_GONE,
                        RunningAppProcessInfo.importanceToProcState(importanceCutpoint),
                        mContext.getOpPackageName(), uids);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mImportanceListeners.put(listener, observer);
        }
    }

    /**
     * Remove an importance listener that was previously registered with
     * {@link #addOnUidImportanceListener}.
     *
     * @throws IllegalArgumentException If the listener is not registered.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    public void removeOnUidImportanceListener(OnUidImportanceListener listener) {
        synchronized (mImportanceListeners) {
            MyUidObserver observer = mImportanceListeners.remove(listener);
            if (observer == null) {
                throw new IllegalArgumentException("Listener not registered: " + listener);
            }
            try {
                getService().unregisterUidObserver(observer);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Return global memory state information for the calling process.  This
     * does not fill in all fields of the {@link RunningAppProcessInfo}.  The
     * only fields that will be filled in are
     * {@link RunningAppProcessInfo#pid},
     * {@link RunningAppProcessInfo#uid},
     * {@link RunningAppProcessInfo#lastTrimLevel},
     * {@link RunningAppProcessInfo#importance},
     * {@link RunningAppProcessInfo#lru}, and
     * {@link RunningAppProcessInfo#importanceReasonCode}.
     */
    public static void getMyMemoryState(RunningAppProcessInfo outState) {
        if (Flags.rateLimitGetMyMemoryState()) {
            synchronized (mMyMemoryStateCache) {
                mMyMemoryStateCache.get(() -> {
                    getMyMemoryStateInternal(mRateLimitedMemState);
                    return mRateLimitedMemState;
                });
                mRateLimitedMemState.copyTo(outState);
            }
        } else {
            getMyMemoryStateInternal(outState);
        }
    }

    private static void getMyMemoryStateInternal(RunningAppProcessInfo outState) {
        try {
            getService().getMyMemoryState(outState);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return information about the memory usage of one or more processes.
     *
     * <p><b>Note: this method is only intended for debugging or building
     * a user-facing process management UI.</b></p>
     *
     * <p>As of {@link android.os.Build.VERSION_CODES#Q Android Q}, for regular apps this method
     * will only return information about the memory info for the processes running as the
     * caller's uid; no other process memory info is available and will be zero.
     * Also of {@link android.os.Build.VERSION_CODES#Q Android Q} the sample rate allowed
     * by this API is significantly limited, if called faster the limit you will receive the
     * same data as the previous call.</p>
     *
     * @param pids The pids of the processes whose memory usage is to be
     * retrieved.
     * @return Returns an array of memory information, one for each
     * requested pid.
     */
    public Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids) {
        try {
            return getService().getProcessMemoryInfo(pids);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated This is now just a wrapper for
     * {@link #killBackgroundProcesses(String)}; the previous behavior here
     * is no longer available to applications because it allows them to
     * break other applications by removing their alarms, stopping their
     * services, etc.
     */
    @Deprecated
    public void restartPackage(String packageName) {
        killBackgroundProcesses(packageName);
    }

    /**
     * Have the system immediately kill all background processes associated
     * with the given package.  This is the same as the kernel killing those
     * processes to reclaim memory; the system will take care of restarting
     * these processes in the future as needed.
     *
     * <p class="note">On devices that run Android 14 or higher,
     * third party applications can only use this API to kill their own processes.
     * </p>
     *
     * @param packageName The name of the package whose processes are to
     * be killed.
     */
    @RequiresPermission(Manifest.permission.KILL_BACKGROUND_PROCESSES)
    public void killBackgroundProcesses(String packageName) {
        try {
            getService().killBackgroundProcesses(packageName,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Kills the specified UID.
     * @param uid The UID to kill.
     * @param reason The reason for the kill.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.KILL_UID)
    public void killUid(int uid, String reason) {
        try {
            getService().killUid(UserHandle.getAppId(uid),
                    UserHandle.getUserId(uid), reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Have the system perform a force stop of everything associated with
     * the given application package.  All processes that share its uid
     * will be killed, all services it has running stopped, all activities
     * removed, etc.  In addition, a {@link Intent#ACTION_PACKAGE_RESTARTED}
     * broadcast will be sent, so that any of its registered alarms can
     * be stopped, notifications removed, etc.
     *
     * <p>You must hold the permission
     * {@link android.Manifest.permission#FORCE_STOP_PACKAGES} to be able to
     * call this method.
     *
     * @param packageName The name of the package to be stopped.
     * @param userId The user for which the running package is to be stopped.
     *
     * @hide This is not available to third party applications due to
     * it allowing them to break other applications by stopping their
     * services, removing their alarms, etc.
     */
    @UnsupportedAppUsage
    public void forceStopPackageAsUser(String packageName, int userId) {
        try {
            getService().forceStopPackage(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @see #forceStopPackageAsUser(String, int)
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.FORCE_STOP_PACKAGES)
    public void forceStopPackage(String packageName) {
        forceStopPackageAsUser(packageName, mContext.getUserId());
    }

    /**
     * Similar to {@link #forceStopPackageAsUser(String, int)} but will also stop the package even
     * when the user is in the stopping state.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.FORCE_STOP_PACKAGES)
    public void forceStopPackageAsUserEvenWhenStopping(String packageName, @UserIdInt int userId) {
        try {
            getService().forceStopPackageEvenWhenStopping(packageName, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets the current locales of the device. Calling app must have the permission
     * {@code android.permission.CHANGE_CONFIGURATION} and
     * {@code android.permission.WRITE_SETTINGS}.
     *
     * @hide
     */
    @SystemApi
    public void setDeviceLocales(@NonNull LocaleList locales) {
        LocalePicker.updateLocales(locales);
    }

    /**
     * Returns a list of supported locales by this system. It includes all locales that are
     * selectable by the user, potentially including locales that the framework does not have
     * translated resources for. To get locales that the framework has translated resources for, use
     * {@code Resources.getSystem().getAssets().getLocales()} instead.
     *
     * @hide
     */
    @SystemApi
    public @NonNull Collection<Locale> getSupportedLocales() {
        ArrayList<Locale> locales = new ArrayList<>();
        for (String localeTag : LocalePicker.getSupportedLocales(mContext)) {
            locales.add(Locale.forLanguageTag(localeTag));
        }
        return locales;
    }

    /**
     * Get the device configuration attributes.
     */
    public ConfigurationInfo getDeviceConfigurationInfo() {
        try {
            return getTaskService().getDeviceConfigurationInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the preferred density of icons for the launcher. This is used when
     * custom drawables are created (e.g., for shortcuts).
     *
     * @return density in terms of DPI
     */
    public int getLauncherLargeIconDensity() {
        final Resources res = mContext.getResources();
        final int density = res.getDisplayMetrics().densityDpi;
        final int sw = res.getConfiguration().smallestScreenWidthDp;

        if (sw < 600) {
            // Smaller than approx 7" tablets, use the regular icon size.
            return density;
        }

        switch (density) {
            case DisplayMetrics.DENSITY_LOW:
                return DisplayMetrics.DENSITY_MEDIUM;
            case DisplayMetrics.DENSITY_MEDIUM:
                return DisplayMetrics.DENSITY_HIGH;
            case DisplayMetrics.DENSITY_TV:
                return DisplayMetrics.DENSITY_XHIGH;
            case DisplayMetrics.DENSITY_HIGH:
                return DisplayMetrics.DENSITY_XHIGH;
            case DisplayMetrics.DENSITY_XHIGH:
                return DisplayMetrics.DENSITY_XXHIGH;
            case DisplayMetrics.DENSITY_XXHIGH:
                return DisplayMetrics.DENSITY_XHIGH * 2;
            default:
                // The density is some abnormal value.  Return some other
                // abnormal value that is a reasonable scaling of it.
                return (int)((density*1.5f)+.5f);
        }
    }

    /**
     * Get the preferred launcher icon size. This is used when custom drawables
     * are created (e.g., for shortcuts).
     *
     * @return dimensions of square icons in terms of pixels
     */
    public int getLauncherLargeIconSize() {
        return getLauncherLargeIconSizeInner(mContext);
    }

    static int getLauncherLargeIconSizeInner(Context context) {
        final Resources res = context.getResources();
        final int size = res.getDimensionPixelSize(android.R.dimen.app_icon_size);
        final int sw = res.getConfiguration().smallestScreenWidthDp;

        if (sw < 600) {
            // Smaller than approx 7" tablets, use the regular icon size.
            return size;
        }

        final int density = res.getDisplayMetrics().densityDpi;

        switch (density) {
            case DisplayMetrics.DENSITY_LOW:
                return (size * DisplayMetrics.DENSITY_MEDIUM) / DisplayMetrics.DENSITY_LOW;
            case DisplayMetrics.DENSITY_MEDIUM:
                return (size * DisplayMetrics.DENSITY_HIGH) / DisplayMetrics.DENSITY_MEDIUM;
            case DisplayMetrics.DENSITY_TV:
                return (size * DisplayMetrics.DENSITY_XHIGH) / DisplayMetrics.DENSITY_HIGH;
            case DisplayMetrics.DENSITY_HIGH:
                return (size * DisplayMetrics.DENSITY_XHIGH) / DisplayMetrics.DENSITY_HIGH;
            case DisplayMetrics.DENSITY_XHIGH:
                return (size * DisplayMetrics.DENSITY_XXHIGH) / DisplayMetrics.DENSITY_XHIGH;
            case DisplayMetrics.DENSITY_XXHIGH:
                return (size * DisplayMetrics.DENSITY_XHIGH*2) / DisplayMetrics.DENSITY_XXHIGH;
            default:
                // The density is some abnormal value.  Return some other
                // abnormal value that is a reasonable scaling of it.
                return (int)((size*1.5f) + .5f);
        }
    }

    /**
     * Returns "true" if the user interface is currently being messed with
     * by a monkey.
     */
    @android.ravenwood.annotation.RavenwoodReplace
    public static boolean isUserAMonkey() {
        try {
            return getService().isUserAMonkey();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public static boolean isUserAMonkey$ravenwood() {
        // Ravenwood environment is never considered a "monkey"
        return false;
    }

    /**
     * Returns "true" if device is running in a test harness.
     *
     * @deprecated this method is false for all user builds. Users looking to check if their device
     * is running in a device farm should see {@link #isRunningInUserTestHarness()}.
     */
    @Deprecated
    public static boolean isRunningInTestHarness() {
        return SystemProperties.getBoolean("ro.test_harness", false);
    }

    /**
     * Returns "true" if the device is running in Test Harness Mode.
     *
     * <p>Test Harness Mode is a feature that allows devices to run without human interaction in a
     * device farm/testing harness (such as Firebase Test Lab). You should check this method if you
     * want your app to behave differently when running in a test harness to skip setup screens that
     * would impede UI testing. e.g. a keyboard application that has a full screen setup page for
     * the first time it is launched.
     *
     * <p>Note that you should <em>not</em> use this to determine whether or not your app is running
     * an instrumentation test, as it is not set for a standard device running a test.
     */
    public static boolean isRunningInUserTestHarness() {
        return SystemProperties.getBoolean("persist.sys.test_harness", false);
    }

    /**
     * Unsupported compiled sdk warning should always be shown for the intput activity
     * even in cases where the system would normally not show the warning. E.g. when running in a
     * test harness.
     *
     * @param activity The component name of the activity to always show the warning for.
     *
     * @hide
     */
    @TestApi
    public void alwaysShowUnsupportedCompileSdkWarning(ComponentName activity) {
        try {
            getTaskService().alwaysShowUnsupportedCompileSdkWarning(activity);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the launch count of each installed package.
     *
     * @hide
     */
    /*public Map<String, Integer> getAllPackageLaunchCounts() {
        try {
            IUsageStats usageStatsService = IUsageStats.Stub.asInterface(
                    ServiceManager.getService("usagestats"));
            if (usageStatsService == null) {
                return new HashMap<String, Integer>();
            }

            UsageStats.PackageStats[] allPkgUsageStats = usageStatsService.getAllPkgUsageStats(
                    ActivityThread.currentPackageName());
            if (allPkgUsageStats == null) {
                return new HashMap<String, Integer>();
            }

            Map<String, Integer> launchCounts = new HashMap<String, Integer>();
            for (UsageStats.PackageStats pkgUsageStats : allPkgUsageStats) {
                launchCounts.put(pkgUsageStats.getPackageName(), pkgUsageStats.getLaunchCount());
            }

            return launchCounts;
        } catch (RemoteException e) {
            Log.w(TAG, "Could not query launch counts", e);
            return new HashMap<String, Integer>();
        }
    }*/

    /** @hide
     * Determines whether the given UID can access unexported components
     * @param uid the calling UID
     * @return true if the calling UID is ROOT or SYSTEM
     */
    public static boolean canAccessUnexportedComponents(int uid) {
        final int appId = UserHandle.getAppId(uid);
        return (appId == Process.ROOT_UID || appId == Process.SYSTEM_UID);
    }

    /** @hide */
    @UnsupportedAppUsage
    public static int checkComponentPermission(String permission, int uid,
            int owningUid, boolean exported) {
        return checkComponentPermission(permission, uid, Context.DEVICE_ID_DEFAULT,
                owningUid, exported);
    }

    /** @hide */
    public static int checkComponentPermission(String permission, int uid, int deviceId,
            int owningUid, boolean exported) {
        // Root, system server get to do everything.
        if (canAccessUnexportedComponents(uid)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // Isolated processes don't get any permissions.
        if (UserHandle.isIsolated(uid)) {
            return PackageManager.PERMISSION_DENIED;
        }
        // If there is a uid that owns whatever is being accessed, it has
        // blanket access to it regardless of the permissions it requires.
        if (owningUid >= 0 && UserHandle.isSameApp(uid, owningUid)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // If the target is not exported, then nobody else can get to it.
        if (!exported) {
            /*
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.w(TAG, "Permission denied: checkComponentPermission() owningUid=" + owningUid,
                    here);
            */
            return PackageManager.PERMISSION_DENIED;
        }
        if (permission == null) {
            return PackageManager.PERMISSION_GRANTED;
        }
        try {
            return AppGlobals.getPermissionManager().checkUidPermission(uid, permission, deviceId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public static int checkUidPermission(String permission, int uid) {
        try {
            return AppGlobals.getPermissionManager().checkUidPermission(
                    uid, permission, Context.DEVICE_ID_DEFAULT);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @hide
     * Helper for dealing with incoming user arguments to system service calls.
     * Takes care of checking permissions and converting USER_CURRENT to the
     * actual current user.
     *
     * @param callingPid The pid of the incoming call, as per Binder.getCallingPid().
     * @param callingUid The uid of the incoming call, as per Binder.getCallingUid().
     * @param userId The user id argument supplied by the caller -- this is the user
     * they want to run as.
     * @param allowAll If true, we will allow USER_ALL.  This means you must be prepared
     * to get a USER_ALL returned and deal with it correctly.  If false,
     * an exception will be thrown if USER_ALL is supplied.
     * @param requireFull If true, the caller must hold
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} to be able to run as a
     * different user than their current process; otherwise they must hold
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS}.
     * @param name Optional textual name of the incoming call; only for generating error messages.
     * @param callerPackage Optional package name of caller; only for error messages.
     *
     * @return Returns the user ID that the call should run as.  Will always be a concrete
     * user number, unless <var>allowAll</var> is true in which case it could also be
     * USER_ALL.
     */
    public static int handleIncomingUser(int callingPid, int callingUid, int userId,
            boolean allowAll, boolean requireFull, String name, String callerPackage) {
        if (UserHandle.getUserId(callingUid) == userId) {
            return userId;
        }
        try {
            return getService().handleIncomingUser(callingPid,
                    callingUid, userId, allowAll, requireFull, name, callerPackage);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the userId of the current foreground user. Requires system permissions.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {
            "android.permission.INTERACT_ACROSS_USERS",
            "android.permission.INTERACT_ACROSS_USERS_FULL"
    })
    @android.ravenwood.annotation.RavenwoodReplace
    public static int getCurrentUser() {
        return mGetCurrentUserIdCache.query(null);
    }

    /** @hide */
    public static int getCurrentUser$ravenwood() {
        return sCurrentUser$ravenwood;
    }

    /**
     * @param userid the user's id. Zero indicates the default user.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public boolean switchUser(int userid) {
        try {
            return getService().switchUser(userid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether switching to provided user was successful.
     *
     * @param user the user to switch to.
     *
     * @throws IllegalArgumentException if the user is null.
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS})
    public boolean switchUser(@NonNull UserHandle user) {
        Preconditions.checkArgument(user != null, "UserHandle cannot be null.");

        return switchUser(user.getIdentifier());
    }

    /**
     * Starts the given user in background and assign the user to the given display.
     *
     * <p>This method will allow the user to launch activities on that display, and it's typically
     * used only on automotive builds when the vehicle has multiple displays (you can verify if it's
     * supported by calling {@link UserManager#isVisibleBackgroundUsersSupported()}).
     *
     * <p><b>NOTE:</b> differently from {@link #switchUser(int)}, which stops the current foreground
     * user before starting a new one, this method does not stop the previous user running in
     * background in the display, and it will return {@code false} in this case. It's up to the
     * caller to call {@link #stopUser(int)} before starting a new user.
     *
     * @param userId user to be started in the display. It will return {@code false} if the user is
     * a profile, the {@link #getCurrentUser()}, the {@link UserHandle#SYSTEM system user}, or
     * does not exist.
     *
     * @param displayId id of the display.
     *
     * @return whether the operation succeeded. Notice that if the user was already started in such
     * display before, it will return {@code false}.
     *
     * @throws UnsupportedOperationException if the device does not support background users on
     * secondary displays.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS})
    public boolean startUserInBackgroundVisibleOnDisplay(@UserIdInt int userId, int displayId) {
        if (!UserManager.isVisibleBackgroundUsersEnabled()) {
            throw new UnsupportedOperationException(
                    "device does not support users on secondary displays");
        }
        try {
            return getService().startUserInBackgroundVisibleOnDisplay(userId, displayId,
                    /* unlockProgressListener= */ null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the id of displays that can be used by
     * {@link #startUserInBackgroundOnSecondaryDisplay(int, int)}.
     *
     * @hide
     */
    @TestApi
    @Nullable
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS})
    public int[] getDisplayIdsForStartingVisibleBackgroundUsers() {
        try {
            return getService().getDisplayIdsForStartingVisibleBackgroundUsers();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the message that is shown when a user is switched from.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USERS)
    public @Nullable String getSwitchingFromUserMessage() {
        try {
            return getService().getSwitchingFromUserMessage();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the message that is shown when a user is switched to.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.MANAGE_USERS)
    public @Nullable String getSwitchingToUserMessage() {
        try {
            return getService().getSwitchingToUserMessage();
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Uses the value defined by the platform.
     *
     * @hide
     */
    @TestApi
    public static final int STOP_USER_ON_SWITCH_DEFAULT = -1;

    /**
     * Overrides value defined by the platform and stop user on switch.
     *
     * @hide
     */
    @TestApi
    public static final int STOP_USER_ON_SWITCH_TRUE = 1;

    /**
     * Overrides value defined by the platform and don't stop user on switch.
     *
     * @hide
     */
    @TestApi
    public static final int STOP_USER_ON_SWITCH_FALSE = 0;

    /** @hide */
    @IntDef(prefix = { "STOP_USER_ON_SWITCH_" }, value = {
            STOP_USER_ON_SWITCH_DEFAULT,
            STOP_USER_ON_SWITCH_TRUE,
            STOP_USER_ON_SWITCH_FALSE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface StopUserOnSwitch {}

    /**
     * Sets whether the current foreground user (and its profiles) should be stopped after switched
     * out.
     *
     * <p>Should only be used on tests. Doesn't apply to {@link UserHandle#SYSTEM system user}.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS})
    public void setStopUserOnSwitch(@StopUserOnSwitch int value) {
        try {
            getService().setStopUserOnSwitch(value);
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Starts a profile.
     * To be used with non-managed profiles, managed profiles should use
     * {@link UserManager#requestQuietModeEnabled}
     *
     * @param userHandle user handle of the profile.
     * @return true if the profile has been successfully started or if the profile is already
     * running, false if profile failed to start.
     * @throws IllegalArgumentException if {@code userHandle} is not a profile.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public boolean startProfile(@NonNull UserHandle userHandle) {
        try {
            return getService().startProfile(userHandle.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Stops a running profile.
     * To be used with non-managed profiles, managed profiles should use
     * {@link UserManager#requestQuietModeEnabled}
     *
     * @param userHandle user handle of the profile.
     * @return true if the profile has been successfully stopped or is already stopped. Otherwise
     * the exceptions listed below are thrown.
     * @throws IllegalArgumentException if {@code userHandle} is not a profile.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.INTERACT_ACROSS_USERS_FULL})
    public boolean stopProfile(@NonNull UserHandle userHandle) {
        try {
            return getService().stopProfile(userHandle.getIdentifier());
        } catch (RemoteException re) {
            throw re.rethrowFromSystemServer();
        }
    }

    /**
     * Updates the MCC (Mobile Country Code) and MNC (Mobile Network Code) in the
     * system configuration.
     *
     * @param mcc The new MCC.
     * @param mnc The new MNC.
     * @throws RemoteException; IllegalArgumentException if mcc or mnc is null;
     * @return Returns {@code true} if the configuration was updated successfully;
     *         {@code false} otherwise.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @RequiresPermission(android.Manifest.permission.CHANGE_CONFIGURATION)
    public boolean updateMccMncConfiguration(@NonNull String mcc, @NonNull String mnc) {
        if (mcc == null || mnc == null) {
            throw new IllegalArgumentException("mcc or mnc cannot be null.");
        }
        try {
            return getService().updateMccMncConfiguration(mcc, mnc);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stops the given {@code userId}.
     *
     * <p><b>NOTE:</b> on systems that support
     * {@link UserManager#isVisibleBackgroundUsersSupported() background users on secondary
     * displays}, this method will also unassign the user from the display it was started on.
     *
     * @hide
     */
    @SuppressLint("UnflaggedApi") // @TestApi without associated feature.
    @TestApi
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS_FULL)
    public boolean stopUser(@UserIdInt int userId) {
        if (userId == UserHandle.USER_SYSTEM) {
            return false;
        }
        try {
            return USER_OP_SUCCESS == getService().stopUserWithCallback(
                    userId, /* callback= */ null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public static final int FLAG_OR_STOPPED = 1 << 0;
    /** {@hide} */
    public static final int FLAG_AND_LOCKED = 1 << 1;
    /** {@hide} */
    public static final int FLAG_AND_UNLOCKED = 1 << 2;
    /** {@hide} */
    public static final int FLAG_AND_UNLOCKING_OR_UNLOCKED = 1 << 3;

    /**
     * Return whether the given user is actively running.  This means that
     * the user is in the "started" state, not "stopped" -- it is currently
     * allowed to run code through scheduled alarms, receiving broadcasts,
     * etc.  A started user may be either the current foreground user or a
     * background user; the result here does not distinguish between the two.
     * @param userId the user's id. Zero indicates the default user.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isUserRunning(int userId) {
        try {
            return getService().isUserRunning(userId, 0);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** {@hide} */
    public boolean isVrModePackageEnabled(ComponentName component) {
        try {
            return getService().isVrModePackageEnabled(component);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Perform a system dump of various state associated with the given application
     * package name.  This call blocks while the dump is being performed, so should
     * not be done on a UI thread.  The data will be written to the given file
     * descriptor as text.
     * @param fd The file descriptor that the dump should be written to.  The file
     * descriptor is <em>not</em> closed by this function; the caller continues to
     * own it.
     * @param packageName The name of the package that is to be dumped.
     */
    @RequiresPermission(Manifest.permission.DUMP)
    public void dumpPackageState(FileDescriptor fd, String packageName) {
        dumpPackageStateStatic(fd, packageName);
    }

    /**
     * @hide
     */
    public static void dumpPackageStateStatic(FileDescriptor fd, String packageName) {
        FileOutputStream fout = new FileOutputStream(fd);
        PrintWriter pw = new FastPrintWriter(fout);
        dumpService(pw, fd, "package", new String[] { packageName });
        pw.println();
        dumpService(pw, fd, Context.ACTIVITY_SERVICE, new String[] {
                "-a", "package", packageName });
        pw.println();
        dumpService(pw, fd, "meminfo", new String[] { "--local", "--package", packageName });
        pw.println();
        dumpService(pw, fd, ProcessStats.SERVICE_NAME, new String[] { packageName });
        pw.println();
        dumpService(pw, fd, "usagestats", new String[] { packageName });
        pw.println();
        dumpService(pw, fd, BatteryStats.SERVICE_NAME, new String[] { packageName });
        pw.flush();
    }

    /**
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodReplace
    public static boolean isSystemReady() {
        if (!sSystemReady) {
            if (ActivityThread.isSystem()) {
                sSystemReady =
                        LocalServices.getService(ActivityManagerInternal.class).isSystemReady();
            } else {
                // Since this is being called from outside system server, system should be
                // ready by now.
                sSystemReady = true;
            }
        }
        return sSystemReady;
    }

    /** @hide */
    public static boolean isSystemReady$ravenwood() {
        // Ravenwood environment is always considered as booted and ready
        return true;
    }

    /**
     * @hide
     */
    public static void broadcastStickyIntent(Intent intent, int userId) {
        broadcastStickyIntent(intent, AppOpsManager.OP_NONE, null, userId);
    }

    /**
     * Convenience for sending a sticky broadcast.  For internal use only.
     *
     * @hide
     */
    public static void broadcastStickyIntent(Intent intent, int appOp, int userId) {
        broadcastStickyIntent(intent, appOp, null, userId);
    }

    /**
     * Convenience for sending a sticky broadcast.  For internal use only.
     *
     * @hide
     */
    public static void broadcastStickyIntent(Intent intent, int appOp, Bundle options, int userId) {
        broadcastStickyIntent(intent, null, appOp, options, userId);
    }

    /**
     * Convenience for sending a sticky broadcast.  For internal use only.
     *
     * @hide
     */
    public static void broadcastStickyIntent(Intent intent, String[] excludedPackages,
            int appOp, Bundle options, int userId) {
        try {
            getService().broadcastIntentWithFeature(
                    null, null, intent, null, null, Activity.RESULT_OK, null, null,
                    null /*requiredPermissions*/, null /*excludedPermissions*/,
                    excludedPackages, appOp, options, false, true, userId);
        } catch (RemoteException ex) {
        }
    }

    /**
     * @hide
     */
    @TestApi
    public static void resumeAppSwitches() throws RemoteException {
        getService().resumeAppSwitches();
    }

    /**
     * @hide
     */
    public static void noteWakeupAlarm(PendingIntent ps, WorkSource workSource, int sourceUid,
            String sourcePkg, String tag) {
        try {
            getService().noteWakeupAlarm((ps != null) ? ps.getTarget() : null, workSource,
                    sourceUid, sourcePkg, tag);
        } catch (RemoteException ex) {
        }
    }

    /**
     * @hide
     */
    public static void noteAlarmStart(PendingIntent ps, WorkSource workSource, int sourceUid,
            String tag) {
        try {
            getService().noteAlarmStart((ps != null) ? ps.getTarget() : null, workSource,
                    sourceUid, tag);
        } catch (RemoteException ex) {
        }
    }


    /**
     * @hide
     */
    public static void noteAlarmFinish(PendingIntent ps, WorkSource workSource, int sourceUid,
            String tag) {
        try {
            getService().noteAlarmFinish((ps != null) ? ps.getTarget() : null, workSource,
                    sourceUid, tag);
        } catch (RemoteException ex) {
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static IActivityManager getService() {
        return IActivityManagerSingleton.get();
    }

    private static IActivityTaskManager getTaskService() {
        return ActivityTaskManager.getService();
    }

    @UnsupportedAppUsage
    private static final Singleton<IActivityManager> IActivityManagerSingleton =
            new Singleton<IActivityManager>() {
                @Override
                protected IActivityManager create() {
                    final IBinder b = ServiceManager.getService(Context.ACTIVITY_SERVICE);
                    final IActivityManager am = IActivityManager.Stub.asInterface(b);
                    return am;
                }
            };

    private static void dumpService(PrintWriter pw, FileDescriptor fd, String name, String[] args) {
        pw.print("DUMP OF SERVICE "); pw.print(name); pw.println(":");
        IBinder service = ServiceManager.checkService(name);
        if (service == null) {
            pw.println("  (Service not found)");
            pw.flush();
            return;
        }
        pw.flush();
        if (service instanceof Binder) {
            // If this is a local object, it doesn't make sense to do an async dump with it,
            // just directly dump.
            try {
                service.dump(fd, args);
            } catch (Throwable e) {
                pw.println("Failure dumping service:");
                e.printStackTrace(pw);
                pw.flush();
            }
        } else {
            // Otherwise, it is remote, do the dump asynchronously to avoid blocking.
            TransferPipe tp = null;
            try {
                pw.flush();
                tp = new TransferPipe();
                tp.setBufferPrefix("  ");
                service.dumpAsync(tp.getWriteFd().getFileDescriptor(), args);
                tp.go(fd, 10000);
            } catch (Throwable e) {
                if (tp != null) {
                    tp.kill();
                }
                pw.println("Failure dumping service:");
                e.printStackTrace(pw);
            }
        }
    }

    /**
     * Request that the system start watching for the calling process to exceed a pss
     * size as given here.  Once called, the system will look for any occasions where it
     * sees the associated process with a larger pss size and, when this happens, automatically
     * pull a heap dump from it and allow the user to share the data.  Note that this request
     * continues running even if the process is killed and restarted.  To remove the watch,
     * use {@link #clearWatchHeapLimit()}.
     *
     * <p>This API only works if the calling process has been marked as
     * {@link ApplicationInfo#FLAG_DEBUGGABLE} or this is running on a debuggable
     * (userdebug or eng) build.</p>
     *
     * <p>Callers can optionally implement {@link #ACTION_REPORT_HEAP_LIMIT} to directly
     * handle heap limit reports themselves.</p>
     *
     * @param pssSize The size in bytes to set the limit at.
     */
    public void setWatchHeapLimit(long pssSize) {
        try {
            getService().setDumpHeapDebugLimit(null, 0, pssSize,
                    mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Action an app can implement to handle reports from {@link #setWatchHeapLimit(long)}.
     * If your package has an activity handling this action, it will be launched with the
     * heap data provided to it the same way as {@link Intent#ACTION_SEND}.  Note that to
     * match, the activity must support this action and a MIME type of "*&#47;*".
     */
    public static final String ACTION_REPORT_HEAP_LIMIT = "android.app.action.REPORT_HEAP_LIMIT";

    /**
     * Clear a heap watch limit previously set by {@link #setWatchHeapLimit(long)}.
     */
    public void clearWatchHeapLimit() {
        try {
            getService().setDumpHeapDebugLimit(null, 0, 0, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether currently in lock task mode.  When in this mode
     * no new tasks can be created or switched to.
     *
     * @see Activity#startLockTask()
     *
     * @deprecated Use {@link #getLockTaskModeState} instead.
     */
    @Deprecated
    public boolean isInLockTaskMode() {
        return getLockTaskModeState() != LOCK_TASK_MODE_NONE;
    }

    /**
     * Return the current state of task locking. The three possible outcomes
     * are {@link #LOCK_TASK_MODE_NONE}, {@link #LOCK_TASK_MODE_LOCKED}
     * and {@link #LOCK_TASK_MODE_PINNED}.
     *
     * @see Activity#startLockTask()
     */
    public int getLockTaskModeState() {
        try {
            return getTaskService().getLockTaskModeState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enable more aggressive scheduling for latency-sensitive low-runtime VR threads. Only one
     * thread can be a VR thread in a process at a time, and that thread may be subject to
     * restrictions on the amount of time it can run.
     *
     * If persistent VR mode is set, whatever thread has been granted aggressive scheduling via this
     * method will return to normal operation, and calling this method will do nothing while
     * persistent VR mode is enabled.
     *
     * To reset the VR thread for an application, a tid of 0 can be passed.
     *
     * @see android.os.Process#myTid()
     * @param tid tid of the VR thread
     */
    public static void setVrThread(int tid) {
        try {
            getTaskService().setVrThread(tid);
        } catch (RemoteException e) {
            // pass
        }
    }

    /**
     * Enable more aggressive scheduling for latency-sensitive low-runtime VR threads that persist
     * beyond a single process. Only one thread can be a
     * persistent VR thread at a time, and that thread may be subject to restrictions on the amount
     * of time it can run. Calling this method will disable aggressive scheduling for non-persistent
     * VR threads set via {@link #setVrThread}. If persistent VR mode is disabled then the
     * persistent VR thread loses its new scheduling priority; this method must be called again to
     * set the persistent thread.
     *
     * To reset the persistent VR thread, a tid of 0 can be passed.
     *
     * @see android.os.Process#myTid()
     * @param tid tid of the VR thread
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.RESTRICTED_VR_ACCESS)
    public static void setPersistentVrThread(int tid) {
        try {
            getService().setPersistentVrThread(tid);
        } catch (RemoteException e) {
            // pass
        }
    }

    /**
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.CHANGE_CONFIGURATION)
    public void scheduleApplicationInfoChanged(List<String> packages, int userId) {
        try {
            getService().scheduleApplicationInfoChanged(packages, userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether the given user, or its parent (if the user is a profile), is in the
     * foreground.
     * @param userHandle UserHandle to check
     * @return whether the user is the foreground user or, if it is a profile, whether its parent
     *         is the foreground user
     * @hide
     */
    @RequiresPermission(anyOf = {
            android.Manifest.permission.MANAGE_USERS,
            android.Manifest.permission.CREATE_USERS
    })
    public boolean isProfileForeground(@NonNull UserHandle userHandle) {
        UserManager userManager = mContext.getSystemService(UserManager.class);
        if (userManager != null) {
            for (UserInfo userInfo : userManager.getProfiles(getCurrentUser())) {
                if (userInfo.id == userHandle.getIdentifier()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Kill the given PIDs, but the killing will be delayed until the device is idle
     * and the given process is imperceptible.
     *
     * <p>You must hold the permission
     * {@link android.Manifest.permission#FORCE_STOP_PACKAGES} to be able to
     * call this method.
     * </p>
     *
     * @param pids The list of the pids to be killed
     * @pram reason The reason of the kill
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.FORCE_STOP_PACKAGES)
    public void killProcessesWhenImperceptible(@NonNull int[] pids, @NonNull String reason) {
        try {
            getService().killProcessesWhenImperceptible(pids, reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static boolean isProcStateConsideredInteraction(@ProcessState int procState) {
        return (procState <= PROCESS_STATE_TOP || procState == PROCESS_STATE_BOUND_TOP);
    }

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static String procStateToString(int procState) {
        final String procStateStr;
        switch (procState) {
            case ActivityManager.PROCESS_STATE_PERSISTENT:
                procStateStr = "PER ";
                break;
            case ActivityManager.PROCESS_STATE_PERSISTENT_UI:
                procStateStr = "PERU";
                break;
            case ActivityManager.PROCESS_STATE_TOP:
                procStateStr = "TOP ";
                break;
            case ActivityManager.PROCESS_STATE_BOUND_TOP:
                procStateStr = "BTOP";
                break;
            case ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE:
                procStateStr = "FGS ";
                break;
            case ActivityManager.PROCESS_STATE_BOUND_FOREGROUND_SERVICE:
                procStateStr = "BFGS";
                break;
            case ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND:
                procStateStr = "IMPF";
                break;
            case ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND:
                procStateStr = "IMPB";
                break;
            case ActivityManager.PROCESS_STATE_TRANSIENT_BACKGROUND:
                procStateStr = "TRNB";
                break;
            case ActivityManager.PROCESS_STATE_BACKUP:
                procStateStr = "BKUP";
                break;
            case ActivityManager.PROCESS_STATE_SERVICE:
                procStateStr = "SVC ";
                break;
            case ActivityManager.PROCESS_STATE_RECEIVER:
                procStateStr = "RCVR";
                break;
            case ActivityManager.PROCESS_STATE_TOP_SLEEPING:
                procStateStr = "TPSL";
                break;
            case ActivityManager.PROCESS_STATE_HEAVY_WEIGHT:
                procStateStr = "HVY ";
                break;
            case ActivityManager.PROCESS_STATE_HOME:
                procStateStr = "HOME";
                break;
            case ActivityManager.PROCESS_STATE_LAST_ACTIVITY:
                procStateStr = "LAST";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                procStateStr = "CAC ";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                procStateStr = "CACC";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_RECENT:
                procStateStr = "CRE ";
                break;
            case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                procStateStr = "CEM ";
                break;
            case ActivityManager.PROCESS_STATE_NONEXISTENT:
                procStateStr = "NONE";
                break;
            default:
                procStateStr = "??";
                break;
        }
        return procStateStr;
    }

    /**
     * The AppTask allows you to manage your own application's tasks.
     * See {@link android.app.ActivityManager#getAppTasks()}
     */
    public static class AppTask {
        private IAppTask mAppTaskImpl;

        /** @hide */
        public AppTask(IAppTask task) {
            mAppTaskImpl = task;
        }

        /**
         * Finishes all activities in this task and removes it from the recent tasks list.
         */
        public void finishAndRemoveTask() {
            if (DEBUG_FINISH_ACTIVITY) {
                Log.d(Instrumentation.TAG, "AppTask#finishAndRemoveTask: task="
                        + getTaskInfo(), new Throwable());
            }
            try {
                mAppTaskImpl.finishAndRemoveTask();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Get the RecentTaskInfo associated with this task.
         *
         * @return The RecentTaskInfo for this task, or null if the task no longer exists.
         */
        public RecentTaskInfo getTaskInfo() {
            try {
                return mAppTaskImpl.getTaskInfo();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Bring this task to the foreground.  If it contains activities, they will be
         * brought to the foreground with it and their instances re-created if needed.
         * If it doesn't contain activities, the root activity of the task will be
         * re-launched.
         */
        public void moveToFront() {
            try {
                ActivityThread thread = ActivityThread.currentActivityThread();
                IApplicationThread appThread = thread.getApplicationThread();
                String packageName = ActivityThread.currentPackageName();
                mAppTaskImpl.moveToFront(appThread, packageName);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Start an activity in this task.  Brings the task to the foreground.  If this task
         * is not currently active (that is, its id < 0), then a new activity for the given
         * Intent will be launched as the root of the task and the task brought to the
         * foreground.  Otherwise, if this task is currently active and the Intent does not specify
         * an activity to launch in a new task, then a new activity for the given Intent will
         * be launched on top of the task and the task brought to the foreground.  If this
         * task is currently active and the Intent specifies {@link Intent#FLAG_ACTIVITY_NEW_TASK}
         * or would otherwise be launched in to a new task, then the activity not launched but
         * this task be brought to the foreground and a new intent delivered to the top
         * activity if appropriate.
         *
         * <p>In other words, you generally want to use an Intent here that does not specify
         * {@link Intent#FLAG_ACTIVITY_NEW_TASK} or {@link Intent#FLAG_ACTIVITY_NEW_DOCUMENT},
         * and let the system do the right thing.</p>
         *
         * @param intent The Intent describing the new activity to be launched on the task.
         * @param options Optional launch options.
         *
         * @see Activity#startActivity(android.content.Intent, android.os.Bundle)
         */
        public void startActivity(Context context, Intent intent, Bundle options) {
            ActivityThread thread = ActivityThread.currentActivityThread();
            thread.getInstrumentation().execStartActivityFromAppTask(context,
                    thread.getApplicationThread(), mAppTaskImpl, intent, options);
        }

        /**
         * Modify the {@link Intent#FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS} flag in the root
         * Intent of this AppTask.
         *
         * @param exclude If true, {@link Intent#FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS} will
         * be set; otherwise, it will be cleared.
         */
        public void setExcludeFromRecents(boolean exclude) {
            try {
                mAppTaskImpl.setExcludeFromRecents(exclude);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Get packages of bugreport-allowlisted apps to handle a bug report.
     *
     * @return packages of bugreport-allowlisted apps to handle a bug report.
     * @hide
     */
    public List<String> getBugreportWhitelistedPackages() {
        try {
            return getService().getBugreportWhitelistedPackages();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Method for the app to tell system that it's wedged and would like to trigger an ANR.
     *
     * @param reason The description of that what happened
     */
    public void appNotResponding(@NonNull final String reason) {
        try {
            getService().appNotResponding(reason);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register to be notified when the visibility of the home screen changes.
     *
     * @param executor The executor on which the listener should be called.
     * @param listener The listener that is called when home visibility changes.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @RequiresPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
    public void addHomeVisibilityListener(@NonNull Executor executor,
            @NonNull HomeVisibilityListener listener) {
        Preconditions.checkNotNull(listener);
        Preconditions.checkNotNull(executor);
        try {
            listener.init(mContext, executor);
            getService().registerProcessObserver(listener.mObserver);
            // Notify upon first registration.
            executor.execute(() ->
                    listener.onHomeVisibilityChanged(listener.mIsHomeActivityVisible));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a listener that was previously added with {@link #addHomeVisibilityListener}.
     *
     * @param listener The listener that was previously added.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @TestApi
    @RequiresPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
    public void removeHomeVisibilityListener(@NonNull HomeVisibilityListener listener) {
        Preconditions.checkNotNull(listener);
        try {
            getService().unregisterProcessObserver(listener.mObserver);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Used by ThemeOverlayController to notify when color
     * palette is ready.
     *
     * @param userId The ID of the user where ThemeOverlayController is ready.
     *
     * @throws RemoteException
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.SET_THEME_OVERLAY_CONTROLLER_READY)
    public void setThemeOverlayReady(@UserIdInt int userId) {
        try {
            getService().setThemeOverlayReady(userId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resets the state of the {@link com.android.server.am.AppErrors} instance.
     * This is intended for use with CTS only.
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.RESET_APP_ERRORS)
    public void resetAppErrors() {
        try {
            getService().resetAppErrors();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Holds the AM lock for the specified amount of milliseconds.
     * This is intended for use by the tests that need to imitate lock contention.
     * The token should be obtained by
     * {@link android.content.pm.PackageManager#getHoldLockToken()}.
     * @hide
     */
    @TestApi
    public void holdLock(IBinder token, int durationMs) {
        try {
            getService().holdLock(token, durationMs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Blocks until all broadcast queues become idle.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void waitForBroadcastIdle() {
        try {
            getService().waitForBroadcastIdle();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Delays delivering broadcasts to the specified package.
     *
     * <p> When {@code delayedDurationMs} is {@code 0}, it will clears any previously
     * set forced delays.
     *
     * <p><b>Note: This method is only intended for testing and it only
     * works for packages that are already running.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void forceDelayBroadcastDelivery(@NonNull String targetPackage,
            @IntRange(from = 0) long delayedDurationMs) {
        try {
            getService().forceDelayBroadcastDelivery(targetPackage, delayedDurationMs);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if the process represented by the given {@code pid} is frozen.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DUMP)
    public boolean isProcessFrozen(int pid) {
        try {
            return getService().isProcessFrozen(pid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Internal method for logging API starts. Used with
     * FGS metrics logging. Is called by APIs that are
     * used with FGS to log an API event (eg when
     * the camera starts).
     * @hide
     *
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.LOG_FOREGROUND_RESOURCE_USE)
    public void noteForegroundResourceUseBegin(@ForegroundServiceApiType int apiType,
            int uid, int pid) throws SecurityException {
        try {
            getService().logFgsApiBegin(apiType, uid, pid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Internal method for logging API end. Used with
     * FGS metrics logging. Is called by APIs that are
     * used with FGS to log an API event (eg when
     * the camera starts).
     * @hide
     *
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.LOG_FOREGROUND_RESOURCE_USE)
    public void noteForegroundResourceUseEnd(@ForegroundServiceApiType int apiType,
            int uid, int pid) throws SecurityException {
        try {
            getService().logFgsApiEnd(apiType, uid, pid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @return The reason code of whether or not the given UID should be exempted from background
     * restrictions here.
     *
     * <p>
     * Note: Call it with caution as it'll try to acquire locks in other services.
     * </p>
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.DEVICE_POWER)
    @ReasonCode
    public int getBackgroundRestrictionExemptionReason(int uid) {
        try {
            return getService().getBackgroundRestrictionExemptionReason(uid);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
        return PowerExemptionManager.REASON_DENIED;
    }

    /**
     * Requests the system to log the reason for restricting/unrestricting an app. This API
     * should be called before applying any change to the restriction level.
     * <p>
     * The {@code enabled} value determines whether the state is being applied or removed.
     * Not all restrictions are actual restrictions. For example,
     * {@link #RESTRICTION_LEVEL_ADAPTIVE_BUCKET} is a normal state, where there is default lifecycle
     * management applied to the app. Also, {@link #RESTRICTION_LEVEL_EXEMPTED} is used when the
     * app is being put in a power-save allowlist.
     * <p>
     * Example arguments when user force-stops an app from Settings:
     * <pre>
     * noteAppRestrictionEnabled(
     *     "com.example.app",
     *     appUid,
     *     RESTRICTION_LEVEL_FORCE_STOPPED,
     *     true,
     *     RESTRICTION_REASON_USER,
     *     "settings",
     *     RESTRICTION_SOURCE_USER,
     *     0);
     * </pre>
     * Example arguments when app is put in restricted standby bucket for exceeding X hours of jobs:
     * <pre>
     * noteAppRestrictionEnabled(
     *     "com.example.app",
     *     appUid,
     *     RESTRICTION_LEVEL_RESTRICTED_BUCKET,
     *     true,
     *     RESTRICTION_REASON_SYSTEM_HEALTH,
     *     "job_duration",
     *     RESTRICTION_SOURCE_SYSTEM,
     *     X * 3600 * 1000L);
     * </pre>
     *
     * @param packageName the package name of the app
     * @param uid the uid of the app
     * @param restrictionLevel the restriction level specified in {@code RestrictionLevel}
     * @param enabled whether the state is being applied or removed
     * @param reason the reason for the restriction state change, from {@code RestrictionReason}
     * @param subReason a string sub reason limited to 16 characters that specifies additional
     *                  information about the reason for restriction. This string must only contain
     *                  reasons related to excessive system resource usage or in some cases,
     *                  source of the restriction. This string must not contain any details that
     *                  identify user behavior beyond their actions to restrict/unrestrict/launch
     *                  apps in some way.
     *                  Examples of system resource usage: wakelock, wakeups, mobile_data,
     *                  binder_calls, memory, excessive_threads, excessive_cpu, gps_scans, etc.
     *                  Examples of user actions: settings, notification, command_line, launch, etc.
     * @param source the source of the action, from {@code RestrictionSource}
     * @param threshold for reasons that are due to exceeding some threshold, the threshold value
     *                  must be specified. The unit of the threshold depends on the reason and/or
     *                  subReason. For time, use milliseconds. For memory, use KB. For count, use
     *                  the actual count or if rate limited, normalized per-hour. For power,
     *                  use milliwatts. For CPU, use mcycles.
     *
     * @hide
     */
    @RequiresPermission(Manifest.permission.DEVICE_POWER)
    public void noteAppRestrictionEnabled(@NonNull String packageName, int uid,
            @RestrictionLevel int restrictionLevel, boolean enabled,
            @RestrictionReason int reason,
            @Nullable String subReason, @RestrictionSource int source, long threshold) {
        try {
            getService().noteAppRestrictionEnabled(packageName, uid, restrictionLevel, enabled,
                    reason, subReason, source, threshold);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Notifies {@link #getRunningAppProcesses app processes} that the system properties
     * have changed.
     *
     * @see SystemProperties#addChangeCallback
     *
     * @hide
     */
    @TestApi
    public void notifySystemPropertiesChanged() {
        // Note: this cannot use {@link ServiceManager#listServices()} to notify all the services,
        // as that is not available from tests.
        final var binder = ActivityManager.getService().asBinder();
        if (binder != null) {
            var data = Parcel.obtain();
            try {
                binder.transact(IBinder.SYSPROPS_TRANSACTION, data, null /* reply */,
                        0 /* flags */);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            data.recycle();
        }
    }

    /**
     * A subset of immutable pending intent information suitable for caching on the client side.
     *
     * @hide
     */
    public static final class PendingIntentInfo implements Parcelable {

        @Nullable private final String mCreatorPackage;
        private final int mCreatorUid;
        private final boolean mImmutable;
        private final int mIntentSenderType;

        public PendingIntentInfo(@Nullable String creatorPackage, int creatorUid, boolean immutable,
                int intentSenderType) {
            mCreatorPackage = creatorPackage;
            mCreatorUid = creatorUid;
            mImmutable = immutable;
            mIntentSenderType = intentSenderType;
        }

        @Nullable
        public String getCreatorPackage() {
            return mCreatorPackage;
        }

        public int getCreatorUid() {
            return mCreatorUid;
        }

        public boolean isImmutable() {
            return mImmutable;
        }

        public int getIntentSenderType() {
            return mIntentSenderType;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeString(mCreatorPackage);
            parcel.writeInt(mCreatorUid);
            parcel.writeBoolean(mImmutable);
            parcel.writeInt(mIntentSenderType);
        }

        public static final @NonNull Creator<PendingIntentInfo> CREATOR =
                new Creator<PendingIntentInfo>() {
                    @Override
                    public PendingIntentInfo createFromParcel(Parcel in) {
                        return new PendingIntentInfo(
                                /* creatorPackage= */ in.readString(),
                                /* creatorUid= */ in.readInt(),
                                /* immutable= */ in.readBoolean(),
                                /* intentSenderType= */ in.readInt());
                    }

                    @Override
                    public PendingIntentInfo[] newArray(int size) {
                        return new PendingIntentInfo[size];
                    }
                };
    }
}
