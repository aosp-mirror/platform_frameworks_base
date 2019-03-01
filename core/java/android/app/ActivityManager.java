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

import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;

import android.Manifest;
import android.annotation.DrawableRes;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.GraphicBuffer;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.BatteryStats;
import android.os.Binder;
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.IBinder;
import android.os.LocaleList;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.WorkSource;
import android.util.ArrayMap;
import android.util.DisplayMetrics;
import android.util.Singleton;
import android.util.Size;

import com.android.internal.app.LocalePicker;
import com.android.internal.app.procstats.ProcessStats;
import com.android.internal.os.RoSystemProperties;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.MemInfoReader;
import com.android.server.LocalServices;

import org.xmlpull.v1.XmlSerializer;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

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
     * Mount full external storage for the newly started instrumentation.
     * @hide
     */
    public static final int INSTR_FLAG_MOUNT_EXTERNAL_STORAGE_FULL = 1 << 1;

    static final class UidObserver extends IUidObserver.Stub {
        final OnUidImportanceListener mListener;
        final Context mContext;

        UidObserver(OnUidImportanceListener listener, Context clientContext) {
            mListener = listener;
            mContext = clientContext;
        }

        @Override
        public void onUidStateChanged(int uid, int procState, long procStateSeq) {
            mListener.onUidImportance(uid, RunningAppProcessInfo.procStateToImportanceForClient(
                    procState, mContext));
        }

        @Override
        public void onUidGone(int uid, boolean disabled) {
            mListener.onUidImportance(uid, RunningAppProcessInfo.IMPORTANCE_GONE);
        }

        @Override
        public void onUidActive(int uid) {
        }

        @Override
        public void onUidIdle(int uid, boolean disabled) {
        }

        @Override public void onUidCachedChanged(int uid, boolean cached) {
        }
    }

    final ArrayMap<OnUidImportanceListener, UidObserver> mImportanceListeners = new ArrayMap<>();

    /**
     * Defines acceptable types of bugreports.
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "BUGREPORT_OPTION_" }, value = {
            BUGREPORT_OPTION_FULL,
            BUGREPORT_OPTION_INTERACTIVE,
            BUGREPORT_OPTION_REMOTE,
            BUGREPORT_OPTION_WEAR,
            BUGREPORT_OPTION_TELEPHONY,
            BUGREPORT_OPTION_WIFI
    })
    public @interface BugreportMode {}
    /**
     * Takes a bugreport without user interference (and hence causing less
     * interference to the system), but includes all sections.
     * @hide
     */
    public static final int BUGREPORT_OPTION_FULL = 0;
    /**
     * Allows user to monitor progress and enter additional data; might not include all
     * sections.
     * @hide
     */
    public static final int BUGREPORT_OPTION_INTERACTIVE = 1;
    /**
     * Takes a bugreport requested remotely by administrator of the Device Owner app,
     * not the device's user.
     * @hide
     */
    public static final int BUGREPORT_OPTION_REMOTE = 2;
    /**
     * Takes a bugreport on a wearable device.
     * @hide
     */
    public static final int BUGREPORT_OPTION_WEAR = 3;

    /**
     * Takes a lightweight version of bugreport that only includes a few, urgent sections
     * used to report telephony bugs.
     * @hide
     */
    public static final int BUGREPORT_OPTION_TELEPHONY = 4;

    /**
     * Takes a lightweight bugreport that only includes a few sections related to Wifi.
     * @hide
     */
    public static final int BUGREPORT_OPTION_WIFI = 5;

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
     * Result for IActivityManaqer.startActivity: activity wasn't really started, but
     * a task was simply brought to the foreground.
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
     * @hide
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
     */

    /** @hide Not a real process state. */
    public static final int PROCESS_STATE_UNKNOWN = -1;

    /** @hide Process is a persistent system process. */
    public static final int PROCESS_STATE_PERSISTENT = 0;

    /** @hide Process is a persistent system process and is doing UI. */
    public static final int PROCESS_STATE_PERSISTENT_UI = 1;

    /** @hide Process is hosting the current top activities.  Note that this covers
     * all activities that are visible to the user. */
    @UnsupportedAppUsage
    public static final int PROCESS_STATE_TOP = 2;

    /** @hide Process is hosting a foreground service with location type. */
    public static final int PROCESS_STATE_FOREGROUND_SERVICE_LOCATION = 3;

    /** @hide Process is hosting a foreground service. */
    @UnsupportedAppUsage
    public static final int PROCESS_STATE_FOREGROUND_SERVICE = 4;

    /** @hide Process is hosting a foreground service due to a system binding. */
    @UnsupportedAppUsage
    public static final int PROCESS_STATE_BOUND_FOREGROUND_SERVICE = 5;

    /** @hide Process is important to the user, and something they are aware of. */
    public static final int PROCESS_STATE_IMPORTANT_FOREGROUND = 6;

    /** @hide Process is important to the user, but not something they are aware of. */
    @UnsupportedAppUsage
    public static final int PROCESS_STATE_IMPORTANT_BACKGROUND = 7;

    /** @hide Process is in the background transient so we will try to keep running. */
    public static final int PROCESS_STATE_TRANSIENT_BACKGROUND = 8;

    /** @hide Process is in the background running a backup/restore operation. */
    public static final int PROCESS_STATE_BACKUP = 9;

    /** @hide Process is in the background running a service.  Unlike oom_adj, this level
     * is used for both the normal running in background state and the executing
     * operations state. */
    @UnsupportedAppUsage
    public static final int PROCESS_STATE_SERVICE = 10;

    /** @hide Process is in the background running a receiver.   Note that from the
     * perspective of oom_adj, receivers run at a higher foreground level, but for our
     * prioritization here that is not necessary and putting them below services means
     * many fewer changes in some process states as they receive broadcasts. */
    @UnsupportedAppUsage
    public static final int PROCESS_STATE_RECEIVER = 11;

    /** @hide Same as {@link #PROCESS_STATE_TOP} but while device is sleeping. */
    public static final int PROCESS_STATE_TOP_SLEEPING = 12;

    /** @hide Process is in the background, but it can't restore its state so we want
     * to try to avoid killing it. */
    public static final int PROCESS_STATE_HEAVY_WEIGHT = 13;

    /** @hide Process is in the background but hosts the home activity. */
    @UnsupportedAppUsage
    public static final int PROCESS_STATE_HOME = 14;

    /** @hide Process is in the background but hosts the last shown activity. */
    public static final int PROCESS_STATE_LAST_ACTIVITY = 15;

    /** @hide Process is being cached for later use and contains activities. */
    @UnsupportedAppUsage
    public static final int PROCESS_STATE_CACHED_ACTIVITY = 16;

    /** @hide Process is being cached for later use and is a client of another cached
     * process that contains activities. */
    public static final int PROCESS_STATE_CACHED_ACTIVITY_CLIENT = 17;

    /** @hide Process is being cached for later use and has an activity that corresponds
     * to an existing recent task. */
    public static final int PROCESS_STATE_CACHED_RECENT = 18;

    /** @hide Process is being cached for later use and is empty. */
    public static final int PROCESS_STATE_CACHED_EMPTY = 19;

    /** @hide Process does not exist. */
    public static final int PROCESS_STATE_NONEXISTENT = 20;

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
            case PROCESS_STATE_FOREGROUND_SERVICE_LOCATION:
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
    public static final boolean isProcStateBackground(int procState) {
        return procState >= PROCESS_STATE_TRANSIENT_BACKGROUND;
    }

    /** @hide Is this a foreground service type? */
    public static boolean isForegroundService(int procState) {
        return procState == PROCESS_STATE_FOREGROUND_SERVICE_LOCATION
                || procState == PROCESS_STATE_FOREGROUND_SERVICE;
    }

    /** @hide requestType for assist context: only basic information. */
    public static final int ASSIST_CONTEXT_BASIC = 0;

    /** @hide requestType for assist context: generate full AssistStructure. */
    public static final int ASSIST_CONTEXT_FULL = 1;

    /** @hide requestType for assist context: generate full AssistStructure for autofill. */
    public static final int ASSIST_CONTEXT_AUTOFILL = 2;

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

    /**
     * Returns whether the launch was successful.
     * @hide
     */
    public static final boolean isStartResultSuccessful(int result) {
        return FIRST_START_SUCCESS_CODE <= result && result <= LAST_START_SUCCESS_CODE;
    }

    /**
     * Returns whether the launch result was a fatal error.
     * @hide
     */
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
    @UnsupportedAppUsage
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

    /** @removed */
    @Deprecated
    public static int getMaxNumPictureInPictureActions() {
        return 3;
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
                ATTR_TASKDESCRIPTION_PREFIX + "colorBackground";
        private static final String ATTR_TASKDESCRIPTIONICON_FILENAME =
                ATTR_TASKDESCRIPTION_PREFIX + "icon_filename";
        private static final String ATTR_TASKDESCRIPTIONICON_RESOURCE =
                ATTR_TASKDESCRIPTION_PREFIX + "icon_resource";

        private String mLabel;
        private Bitmap mIcon;
        private int mIconRes;
        private String mIconFilename;
        private int mColorPrimary;
        private int mColorBackground;
        private int mStatusBarColor;
        private int mNavigationBarColor;

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this task.
         * @param icon An icon that represents the current state of this task.
         * @param colorPrimary A color to override the theme's primary color.  This color must be
         *                     opaque.
         * @deprecated use TaskDescription constructor with icon resource instead
         */
        @Deprecated
        public TaskDescription(String label, Bitmap icon, int colorPrimary) {
            this(label, icon, 0, null, colorPrimary, 0, 0, 0);
            if ((colorPrimary != 0) && (Color.alpha(colorPrimary) != 255)) {
                throw new RuntimeException("A TaskDescription's primary color should be opaque");
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
         */
        public TaskDescription(String label, @DrawableRes int iconRes, int colorPrimary) {
            this(label, null, iconRes, null, colorPrimary, 0, 0, 0);
            if ((colorPrimary != 0) && (Color.alpha(colorPrimary) != 255)) {
                throw new RuntimeException("A TaskDescription's primary color should be opaque");
            }
        }

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this activity.
         * @param icon An icon that represents the current state of this activity.
         * @deprecated use TaskDescription constructor with icon resource instead
         */
        @Deprecated
        public TaskDescription(String label, Bitmap icon) {
            this(label, icon, 0, null, 0, 0, 0, 0);
        }

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this activity.
         * @param iconRes A drawable resource of an icon that represents the current state of this
         *                activity.
         */
        public TaskDescription(String label, @DrawableRes int iconRes) {
            this(label, null, iconRes, null, 0, 0, 0, 0);
        }

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this activity.
         */
        public TaskDescription(String label) {
            this(label, null, 0, null, 0, 0, 0, 0);
        }

        /**
         * Creates an empty TaskDescription.
         */
        public TaskDescription() {
            this(null, null, 0, null, 0, 0, 0, 0);
        }

        /** @hide */
        public TaskDescription(String label, Bitmap bitmap, int iconRes, String iconFilename,
                int colorPrimary, int colorBackground, int statusBarColor, int navigationBarColor) {
            mLabel = label;
            mIcon = bitmap;
            mIconRes = iconRes;
            mIconFilename = iconFilename;
            mColorPrimary = colorPrimary;
            mColorBackground = colorBackground;
            mStatusBarColor = statusBarColor;
            mNavigationBarColor = navigationBarColor;
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
            mIconRes = other.mIconRes;
            mIconFilename = other.mIconFilename;
            mColorPrimary = other.mColorPrimary;
            mColorBackground = other.mColorBackground;
            mStatusBarColor = other.mStatusBarColor;
            mNavigationBarColor = other.mNavigationBarColor;
        }

        /**
         * Copies this the values from another TaskDescription, but preserves the hidden fields
         * if they weren't set on {@code other}
         * @hide
         */
        public void copyFromPreserveHiddenFields(TaskDescription other) {
            mLabel = other.mLabel;
            mIcon = other.mIcon;
            mIconRes = other.mIconRes;
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
         * Sets the icon for this task description.
         * @hide
         */
        @UnsupportedAppUsage
        public void setIcon(Bitmap icon) {
            mIcon = icon;
        }

        /**
         * Sets the icon resource for this task description.
         * @hide
         */
        public void setIcon(int iconRes) {
            mIconRes = iconRes;
        }

        /**
         * Moves the icon bitmap reference from an actual Bitmap to a file containing the
         * bitmap.
         * @hide
         */
        public void setIconFilename(String iconFilename) {
            mIconFilename = iconFilename;
            mIcon = null;
        }

        /**
         * @return The label and description of the current state of this task.
         */
        public String getLabel() {
            return mLabel;
        }

        /**
         * @return The icon that represents the current state of this task.
         */
        public Bitmap getIcon() {
            if (mIcon != null) {
                return mIcon;
            }
            return loadTaskDescriptionIcon(mIconFilename, UserHandle.myUserId());
        }

        /** @hide */
        @TestApi
        public int getIconResource() {
            return mIconRes;
        }

        /** @hide */
        @TestApi
        public String getIconFilename() {
            return mIconFilename;
        }

        /** @hide */
        @UnsupportedAppUsage
        public Bitmap getInMemoryIcon() {
            return mIcon;
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
        public int getPrimaryColor() {
            return mColorPrimary;
        }

        /**
         * @return The background color.
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
        public int getBackgroundColor() {
            return mColorBackground;
        }

        /**
         * @hide
         */
        public int getStatusBarColor() {
            return mStatusBarColor;
        }

        /**
         * @hide
         */
        public int getNavigationBarColor() {
            return mNavigationBarColor;
        }

        /** @hide */
        public void saveToXml(XmlSerializer out) throws IOException {
            if (mLabel != null) {
                out.attribute(null, ATTR_TASKDESCRIPTIONLABEL, mLabel);
            }
            if (mColorPrimary != 0) {
                out.attribute(null, ATTR_TASKDESCRIPTIONCOLOR_PRIMARY,
                        Integer.toHexString(mColorPrimary));
            }
            if (mColorBackground != 0) {
                out.attribute(null, ATTR_TASKDESCRIPTIONCOLOR_BACKGROUND,
                        Integer.toHexString(mColorBackground));
            }
            if (mIconFilename != null) {
                out.attribute(null, ATTR_TASKDESCRIPTIONICON_FILENAME, mIconFilename);
            }
            if (mIconRes != 0) {
                out.attribute(null, ATTR_TASKDESCRIPTIONICON_RESOURCE, Integer.toString(mIconRes));
            }
        }

        /** @hide */
        public void restoreFromXml(String attrName, String attrValue) {
            if (ATTR_TASKDESCRIPTIONLABEL.equals(attrName)) {
                setLabel(attrValue);
            } else if (ATTR_TASKDESCRIPTIONCOLOR_PRIMARY.equals(attrName)) {
                setPrimaryColor((int) Long.parseLong(attrValue, 16));
            } else if (ATTR_TASKDESCRIPTIONCOLOR_BACKGROUND.equals(attrName)) {
                setBackgroundColor((int) Long.parseLong(attrValue, 16));
            } else if (ATTR_TASKDESCRIPTIONICON_FILENAME.equals(attrName)) {
                setIconFilename(attrValue);
            } else if (ATTR_TASKDESCRIPTIONICON_RESOURCE.equals(attrName)) {
                setIcon(Integer.parseInt(attrValue, 10));
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
            if (mIcon == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                mIcon.writeToParcel(dest, 0);
            }
            dest.writeInt(mIconRes);
            dest.writeInt(mColorPrimary);
            dest.writeInt(mColorBackground);
            dest.writeInt(mStatusBarColor);
            dest.writeInt(mNavigationBarColor);
            if (mIconFilename == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                dest.writeString(mIconFilename);
            }
        }

        public void readFromParcel(Parcel source) {
            mLabel = source.readInt() > 0 ? source.readString() : null;
            mIcon = source.readInt() > 0 ? Bitmap.CREATOR.createFromParcel(source) : null;
            mIconRes = source.readInt();
            mColorPrimary = source.readInt();
            mColorBackground = source.readInt();
            mStatusBarColor = source.readInt();
            mNavigationBarColor = source.readInt();
            mIconFilename = source.readInt() > 0 ? source.readString() : null;
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
            return "TaskDescription Label: " + mLabel + " Icon: " + mIcon +
                    " IconRes: " + mIconRes + " IconFilename: " + mIconFilename +
                    " colorPrimary: " + mColorPrimary + " colorBackground: " + mColorBackground +
                    " statusBarColor: " + mColorBackground +
                    " navigationBarColor: " + mNavigationBarColor;
        }
    }

    /**
     * Information you can retrieve about tasks that the user has most recently
     * started or visited.
     */
    public static class RecentTaskInfo extends TaskInfo implements Parcelable {
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
            super.readFromParcel(source);
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            dest.writeInt(persistentId);
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
            final String activityType = WindowConfiguration.activityTypeToString(
                    configuration.windowConfiguration.getActivityType());
            final String windowingMode = WindowConfiguration.activityTypeToString(
                    configuration.windowConfiguration.getActivityType());

            pw.println(); pw.print("   ");
            pw.print(" id=" + persistentId);
            pw.print(" stackId=" + stackId);
            pw.print(" userId=" + userId);
            pw.print(" hasTask=" + (id != -1));
            pw.print(" lastActiveTime=" + lastActiveTime);
            pw.println(); pw.print("   ");
            pw.print(" baseIntent=" + baseIntent);
            pw.println(); pw.print("   ");
            pw.print(" isExcluded="
                    + ((baseIntent.getFlags() & FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) != 0));
            pw.print(" activityType=" + activityType);
            pw.print(" windowingMode=" + windowingMode);
            pw.print(" supportsSplitScreenMultiWindow=" + supportsSplitScreenMultiWindow);
            if (taskDescription != null) {
                pw.println(); pw.print("   ");
                final ActivityManager.TaskDescription td = taskDescription;
                pw.print(" taskDescription {");
                pw.print(" colorBackground=#" + Integer.toHexString(td.getBackgroundColor()));
                pw.print(" colorPrimary=#" + Integer.toHexString(td.getPrimaryColor()));
                pw.print(" iconRes=" + (td.getIconResource() != 0));
                pw.print(" iconBitmap=" + (td.getIconFilename() != null
                        || td.getInMemoryIcon() != null));
                pw.println(" }");
            }
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
    public List<RecentTaskInfo> getRecentTasks(int maxNum, int flags)
            throws SecurityException {
        try {
            if (maxNum < 0) {
                throw new IllegalArgumentException("The requested number of tasks should be >= 0");
            }
            return getTaskService().getRecentTasks(maxNum, flags, mContext.getUserId()).getList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
            appTasks = getTaskService().getAppTasks(mContext.getPackageName());
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
        try {
            return getTaskService().getTasks(maxNum);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Represents a task snapshot.
     * @hide
     */
    public static class TaskSnapshot implements Parcelable {

        // Top activity in task when snapshot was taken
        private final ComponentName mTopActivityComponent;
        private final GraphicBuffer mSnapshot;
        private final int mOrientation;
        private final Rect mContentInsets;
        // Whether this snapshot is a down-sampled version of the full resolution, used mainly for
        // low-ram devices
        private final boolean mReducedResolution;
        // Whether or not the snapshot is a real snapshot or an app-theme generated snapshot due to
        // the task having a secure window or having previews disabled
        private final boolean mIsRealSnapshot;
        private final int mWindowingMode;
        private final float mScale;
        private final int mSystemUiVisibility;
        private final boolean mIsTranslucent;

        // TODO(b/116112787) TaskSnapshot must also book keep the color space from hardware bitmap
        // when created.
        private final ColorSpace mColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);

        public TaskSnapshot(@NonNull ComponentName topActivityComponent, GraphicBuffer snapshot,
                int orientation, Rect contentInsets, boolean reducedResolution, float scale,
                boolean isRealSnapshot, int windowingMode, int systemUiVisibility,
                boolean isTranslucent) {
            mTopActivityComponent = topActivityComponent;
            mSnapshot = snapshot;
            mOrientation = orientation;
            mContentInsets = new Rect(contentInsets);
            mReducedResolution = reducedResolution;
            mScale = scale;
            mIsRealSnapshot = isRealSnapshot;
            mWindowingMode = windowingMode;
            mSystemUiVisibility = systemUiVisibility;
            mIsTranslucent = isTranslucent;
        }

        private TaskSnapshot(Parcel source) {
            mTopActivityComponent = ComponentName.readFromParcel(source);
            mSnapshot = source.readParcelable(null /* classLoader */);
            mOrientation = source.readInt();
            mContentInsets = source.readParcelable(null /* classLoader */);
            mReducedResolution = source.readBoolean();
            mScale = source.readFloat();
            mIsRealSnapshot = source.readBoolean();
            mWindowingMode = source.readInt();
            mSystemUiVisibility = source.readInt();
            mIsTranslucent = source.readBoolean();
        }

        /**
         * @return The top activity component for the task at the point this snapshot was taken.
         */
        public ComponentName getTopActivityComponent() {
            return mTopActivityComponent;
        }

        /**
         * @return The graphic buffer representing the screenshot.
         */
        @UnsupportedAppUsage
        public GraphicBuffer getSnapshot() {
            return mSnapshot;
        }

        /**
         * @return The color space of graphic buffer representing the screenshot.
         */
        public ColorSpace getColorSpace() {
            return mColorSpace;
        }

        /**
         * @return The screen orientation the screenshot was taken in.
         */
        @UnsupportedAppUsage
        public int getOrientation() {
            return mOrientation;
        }

        /**
         * @return The system/content insets on the snapshot. These can be clipped off in order to
         *         remove any areas behind system bars in the snapshot.
         */
        @UnsupportedAppUsage
        public Rect getContentInsets() {
            return mContentInsets;
        }

        /**
         * @return Whether this snapshot is a down-sampled version of the full resolution.
         */
        @UnsupportedAppUsage
        public boolean isReducedResolution() {
            return mReducedResolution;
        }

        /**
         * @return Whether or not the snapshot is a real snapshot or an app-theme generated snapshot
         * due to the task having a secure window or having previews disabled.
         */
        @UnsupportedAppUsage
        public boolean isRealSnapshot() {
            return mIsRealSnapshot;
        }

        /**
         * @return Whether or not the snapshot is of a translucent app window (non-fullscreen or has
         * a non-opaque pixel format).
         */
        public boolean isTranslucent() {
            return mIsTranslucent;
        }

        /**
         * @return The windowing mode of the task when this snapshot was taken.
         */
        public int getWindowingMode() {
            return mWindowingMode;
        }

        /**
         * @return The system ui visibility flags for the top most visible fullscreen window at the
         *         time that the snapshot was taken.
         */
        public int getSystemUiVisibility() {
            return mSystemUiVisibility;
        }

        /**
         * @return The scale this snapshot was taken in.
         */
        @UnsupportedAppUsage
        public float getScale() {
            return mScale;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            ComponentName.writeToParcel(mTopActivityComponent, dest);
            dest.writeParcelable(mSnapshot, 0);
            dest.writeInt(mOrientation);
            dest.writeParcelable(mContentInsets, 0);
            dest.writeBoolean(mReducedResolution);
            dest.writeFloat(mScale);
            dest.writeBoolean(mIsRealSnapshot);
            dest.writeInt(mWindowingMode);
            dest.writeInt(mSystemUiVisibility);
            dest.writeBoolean(mIsTranslucent);
        }

        @Override
        public String toString() {
            final int width = mSnapshot != null ? mSnapshot.getWidth() : 0;
            final int height = mSnapshot != null ? mSnapshot.getHeight() : 0;
            return "TaskSnapshot{"
                    + " mTopActivityComponent=" + mTopActivityComponent.flattenToShortString()
                    + " mSnapshot=" + mSnapshot + " (" + width + "x" + height + ")"
                    + " mOrientation=" + mOrientation
                    + " mContentInsets=" + mContentInsets.toShortString()
                    + " mReducedResolution=" + mReducedResolution + " mScale=" + mScale
                    + " mIsRealSnapshot=" + mIsRealSnapshot + " mWindowingMode=" + mWindowingMode
                    + " mSystemUiVisibility=" + mSystemUiVisibility
                    + " mIsTranslucent=" + mIsTranslucent;
        }

        public static final @android.annotation.NonNull Creator<TaskSnapshot> CREATOR = new Creator<TaskSnapshot>() {
            public TaskSnapshot createFromParcel(Parcel source) {
                return new TaskSnapshot(source);
            }
            public TaskSnapshot[] newArray(int size) {
                return new TaskSnapshot[size];
            }
        };
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
            getTaskService().moveTaskToFront(taskId, flags, options);
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
     * @see android.view.Display.FLAG_PRIVATE
     * @see android.view.Display.TYPE_VIRTUAL
     *
     * @param context Source context, from which an activity will be started.
     * @param displayId Target display id.
     * @param intent Intent used to launch an activity.
     * @return {@code true} if a call to start an activity on the target display is allowed for the
     * provided context and no {@link SecurityException} will be thrown, {@code false} otherwise.
     */
    public boolean isActivityStartAllowedOnDisplay(Context context, int displayId, Intent intent) {
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
        @UnsupportedAppUsage
        public long hiddenAppThreshold;
        /** @hide */
        @UnsupportedAppUsage
        public long secondaryServerThreshold;
        /** @hide */
        @UnsupportedAppUsage
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
            availMem = source.readLong();
            totalMem = source.readLong();
            threshold = source.readLong();
            lowMemory = source.readInt() != 0;
            hiddenAppThreshold = source.readLong();
            secondaryServerThreshold = source.readLong();
            visibleAppThreshold = source.readLong();
            foregroundAppThreshold = source.readLong();
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
        try {
            getService().getMemoryInfo(outInfo);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Information you can retrieve about an ActivityStack in the system.
     * @hide
     */
    public static class StackInfo implements Parcelable {
        @UnsupportedAppUsage
        public int stackId;
        @UnsupportedAppUsage
        public Rect bounds = new Rect();
        @UnsupportedAppUsage
        public int[] taskIds;
        @UnsupportedAppUsage
        public String[] taskNames;
        @UnsupportedAppUsage
        public Rect[] taskBounds;
        @UnsupportedAppUsage
        public int[] taskUserIds;
        @UnsupportedAppUsage
        public ComponentName topActivity;
        @UnsupportedAppUsage
        public int displayId;
        @UnsupportedAppUsage
        public int userId;
        @UnsupportedAppUsage
        public boolean visible;
        // Index of the stack in the display's stack list, can be used for comparison of stack order
        @UnsupportedAppUsage
        public int position;
        /**
         * The full configuration the stack is currently running in.
         * @hide
         */
        final public Configuration configuration = new Configuration();

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(stackId);
            dest.writeInt(bounds.left);
            dest.writeInt(bounds.top);
            dest.writeInt(bounds.right);
            dest.writeInt(bounds.bottom);
            dest.writeIntArray(taskIds);
            dest.writeStringArray(taskNames);
            final int boundsCount = taskBounds == null ? 0 : taskBounds.length;
            dest.writeInt(boundsCount);
            for (int i = 0; i < boundsCount; i++) {
                dest.writeInt(taskBounds[i].left);
                dest.writeInt(taskBounds[i].top);
                dest.writeInt(taskBounds[i].right);
                dest.writeInt(taskBounds[i].bottom);
            }
            dest.writeIntArray(taskUserIds);
            dest.writeInt(displayId);
            dest.writeInt(userId);
            dest.writeInt(visible ? 1 : 0);
            dest.writeInt(position);
            if (topActivity != null) {
                dest.writeInt(1);
                topActivity.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
            configuration.writeToParcel(dest, flags);
        }

        public void readFromParcel(Parcel source) {
            stackId = source.readInt();
            bounds = new Rect(
                    source.readInt(), source.readInt(), source.readInt(), source.readInt());
            taskIds = source.createIntArray();
            taskNames = source.createStringArray();
            final int boundsCount = source.readInt();
            if (boundsCount > 0) {
                taskBounds = new Rect[boundsCount];
                for (int i = 0; i < boundsCount; i++) {
                    taskBounds[i] = new Rect();
                    taskBounds[i].set(
                            source.readInt(), source.readInt(), source.readInt(), source.readInt());
                }
            } else {
                taskBounds = null;
            }
            taskUserIds = source.createIntArray();
            displayId = source.readInt();
            userId = source.readInt();
            visible = source.readInt() > 0;
            position = source.readInt();
            if (source.readInt() > 0) {
                topActivity = ComponentName.readFromParcel(source);
            }
            configuration.readFromParcel(source);
        }

        public static final @android.annotation.NonNull Creator<StackInfo> CREATOR = new Creator<StackInfo>() {
            @Override
            public StackInfo createFromParcel(Parcel source) {
                return new StackInfo(source);
            }
            @Override
            public StackInfo[] newArray(int size) {
                return new StackInfo[size];
            }
        };

        public StackInfo() {
        }

        private StackInfo(Parcel source) {
            readFromParcel(source);
        }

        @UnsupportedAppUsage
        public String toString(String prefix) {
            StringBuilder sb = new StringBuilder(256);
            sb.append(prefix); sb.append("Stack id="); sb.append(stackId);
                    sb.append(" bounds="); sb.append(bounds.toShortString());
                    sb.append(" displayId="); sb.append(displayId);
                    sb.append(" userId="); sb.append(userId);
                    sb.append("\n");
                    sb.append(" configuration="); sb.append(configuration);
                    sb.append("\n");
            prefix = prefix + "  ";
            for (int i = 0; i < taskIds.length; ++i) {
                sb.append(prefix); sb.append("taskId="); sb.append(taskIds[i]);
                        sb.append(": "); sb.append(taskNames[i]);
                        if (taskBounds != null) {
                            sb.append(" bounds="); sb.append(taskBounds[i].toShortString());
                        }
                        sb.append(" userId=").append(taskUserIds[i]);
                        sb.append(" visible=").append(visible);
                        if (topActivity != null) {
                            sb.append(" topActivity=").append(topActivity);
                        }
                        sb.append("\n");
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return toString("");
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
     * @return Returns a list of ProcessErrorStateInfo records, or null if there are no
     * current error conditions (it will not return an empty list).  This list ordering is not
     * specified.
     */
    public List<ProcessErrorStateInfo> getProcessesInErrorState() {
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
        public String pkgList[];

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
        @UnsupportedAppUsage
        public static final int FLAG_PERSISTENT = 1<<1;

        /**
         * Constant for {@link #flags}: this process is associated with a
         * persistent system app.
         * @hide
         */
        @UnsupportedAppUsage
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
         * other services under the system's control that it inconsiders important.
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
        @TestApi
        public static final int IMPORTANCE_CANT_SAVE_STATE_PRE_26 = 170;

        /**
         * Constant for {@link #importance}: This process is contains services
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
         * Constant for {@link #importance}: This process process contains
         * cached code that is expendable, not actively running any app components
         * we care about.
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
        @UnsupportedAppUsage
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
            } else if (procState >= PROCESS_STATE_FOREGROUND_SERVICE_LOCATION) {
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
        try {
            return getService().getRunningAppProcesses();
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
    @SystemApi @TestApi
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
    @SystemApi @TestApi
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
     * Callback to get reports about changes to the importance of a uid.  Use with
     * {@link #addOnUidImportanceListener}.
     * @hide
     */
    @SystemApi @TestApi
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
     * Start monitoring changes to the imoportance of uids running in the system.
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
     * @throws IllegalArgumentException If the listener is already registered.
     * @throws SecurityException If the caller does not hold
     * {@link android.Manifest.permission#PACKAGE_USAGE_STATS}.
     * @hide
     */
    @SystemApi @TestApi
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    public void addOnUidImportanceListener(OnUidImportanceListener listener,
            @RunningAppProcessInfo.Importance int importanceCutpoint) {
        synchronized (this) {
            if (mImportanceListeners.containsKey(listener)) {
                throw new IllegalArgumentException("Listener already registered: " + listener);
            }
            // TODO: implement the cut point in the system process to avoid IPCs.
            UidObserver observer = new UidObserver(listener, mContext);
            try {
                getService().registerUidObserver(observer,
                        UID_OBSERVER_PROCSTATE | UID_OBSERVER_GONE,
                        RunningAppProcessInfo.importanceToProcState(importanceCutpoint),
                        mContext.getOpPackageName());
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
    @SystemApi @TestApi
    @RequiresPermission(Manifest.permission.PACKAGE_USAGE_STATS)
    public void removeOnUidImportanceListener(OnUidImportanceListener listener) {
        synchronized (this) {
            UidObserver observer = mImportanceListeners.remove(listener);
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
    static public void getMyMemoryState(RunningAppProcessInfo outState) {
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
    @SystemApi @TestApi
    @RequiresPermission(Manifest.permission.FORCE_STOP_PACKAGES)
    public void forceStopPackage(String packageName) {
        forceStopPackageAsUser(packageName, mContext.getUserId());
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
    public static boolean isUserAMonkey() {
        try {
            return getService().isUserAMonkey();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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

    /** @hide */
    @UnsupportedAppUsage
    public static int checkComponentPermission(String permission, int uid,
            int owningUid, boolean exported) {
        // Root, system server get to do everything.
        final int appId = UserHandle.getAppId(uid);
        if (appId == Process.ROOT_UID || appId == Process.SYSTEM_UID) {
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
            return AppGlobals.getPackageManager()
                    .checkUidPermission(permission, uid);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public static int checkUidPermission(String permission, int uid) {
        try {
            return AppGlobals.getPackageManager()
                    .checkUidPermission(permission, uid);
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
    public static int getCurrentUser() {
        UserInfo ui;
        try {
            ui = getService().getCurrentUser();
            return ui != null ? ui.id : 0;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @param userid the user's id. Zero indicates the default user.
     * @hide
     */
    @UnsupportedAppUsage
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
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean switchUser(@NonNull UserHandle user) {
        if (user == null) {
            throw new IllegalArgumentException("UserHandle cannot be null.");
        }
        return switchUser(user.getIdentifier());
    }

    /**
     * Logs out current current foreground user by switching to the system user and stopping the
     * user being switched from.
     * @hide
     */
    public static void logoutCurrentUser() {
        int currentUser = ActivityManager.getCurrentUser();
        if (currentUser != UserHandle.USER_SYSTEM) {
            try {
                getService().switchUser(UserHandle.USER_SYSTEM);
                getService().stopUser(currentUser, /* force= */ false, null);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
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

    /**
     * @hide
     */
    public static void broadcastStickyIntent(Intent intent, int userId) {
        broadcastStickyIntent(intent, AppOpsManager.OP_NONE, userId);
    }

    /**
     * Convenience for sending a sticky broadcast.  For internal use only.
     *
     * @hide
     */
    public static void broadcastStickyIntent(Intent intent, int appOp, int userId) {
        try {
            getService().broadcastIntent(
                    null, intent, null, null, Activity.RESULT_OK, null, null,
                    null /*permission*/, appOp, null, false, true, userId);
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
     * <p>This API only work if the calling process has been marked as
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
     * match the activty must support this action and a MIME type of "*&#47;*".
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
                mAppTaskImpl.moveToFront();
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
}
