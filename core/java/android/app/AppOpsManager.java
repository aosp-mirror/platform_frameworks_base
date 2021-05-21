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

package android.app;

import static java.lang.Long.max;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.usage.UsageStatsManager;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.database.DatabaseUtils;
import android.media.AudioAttributes.AttributeUsage;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.DeviceConfig;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LongSparseArray;
import android.util.LongSparseLongArray;
import android.util.Pools;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.Immutable;
import com.android.internal.app.IAppOpsActiveCallback;
import com.android.internal.app.IAppOpsAsyncNotedCallback;
import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsNotedCallback;
import com.android.internal.app.IAppOpsService;
import com.android.internal.app.IAppOpsStartedCallback;
import com.android.internal.app.MessageSamplingConfig;
import com.android.internal.os.RuntimeInit;
import com.android.internal.os.ZygoteInit;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.DataClass;
import com.android.internal.util.FrameworkStatsLog;
import com.android.internal.util.Parcelling;
import com.android.internal.util.Preconditions;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * App-ops are used for two purposes: Access control and tracking.
 *
 * <p>App-ops cover a wide variety of functionality from helping with runtime permissions access
 * control and tracking to battery consumption tracking.
 *
 * <h2>Access control</h2>
 *
 * <p>App-ops can either be controlled for each uid or for each package. Which one is used depends
 * on the API provider maintaining this app-op. For any security or privacy related app-op the
 * provider needs to control the app-op for per uid as all security and privacy is based on uid in
 * Android.
 *
 * <p>To control access the app-op can be set to a mode to:
 * <dl>
 *     <dt>{@link #MODE_DEFAULT}
 *     <dd>Default behavior, might differ from app-op or app-op
 *     <dt>{@link #MODE_ALLOWED}
 *     <dd>Allow the access
 *     <dt>{@link #MODE_IGNORED}
 *     <dd>Don't allow the access, i.e. don't perform the requested action or return no or
 *     placeholder data
 *     <dt>{@link #MODE_ERRORED}
 *     <dd>Throw a {@link SecurityException} on access. This can be suppressed by using a
 *     {@code ...noThrow} method to check the mode
 * </dl>
 *
 * <p>API providers need to check the mode returned by {@link #noteOp} if they are are allowing
 * access to operations gated by the app-op. {@link #unsafeCheckOp} should be used to check the
 * mode if no access is granted. E.g. this can be used for displaying app-op state in the UI or
 * when checking the state before later calling {@link #noteOp} anyway.
 *
 * <p>If an operation refers to a time span (e.g. a audio-recording session) the API provider
 * should use {@link #startOp} and {@link #finishOp} instead of {@link #noteOp}.
 *
 * <h3>Runtime permissions and app-ops</h3>
 *
 * <p>Each platform defined runtime permission (beside background modifiers) has an associated app
 * op which is used for tracking but also to allow for silent failures. I.e. if the runtime
 * permission is denied the caller gets a {@link SecurityException}, but if the permission is
 * granted and the app-op is {@link #MODE_IGNORED} then the callers gets placeholder behavior, e.g.
 * location callbacks would not happen.
 *
 * <h3>App-op permissions</h3>
 *
 * <p>App-ops permissions are platform defined permissions that can be overridden. The security
 * check for app-op permissions should by {@link #MODE_DEFAULT default} check the permission grant
 * state. If the app-op state is set to {@link #MODE_ALLOWED} or {@link #MODE_IGNORED} the app-op
 * state should be checked instead of the permission grant state.
 *
 * <p>This functionality allows to grant access by default to apps fulfilling the requirements for
 * a certain permission level. Still the behavior can be overridden when needed.
 *
 * <h2>Tracking</h2>
 *
 * <p>App-ops track many important events, including all accesses to runtime permission protected
 * APIs. This is done by tracking when an app-op was {@link #noteOp noted} or
 * {@link #startOp started}. The tracked data can only be read by system components.
 *
 * <p><b>Only {@link #noteOp}/{@link #startOp} are tracked; {@link #unsafeCheckOp} is not tracked.
 * Hence it is important to eventually call {@link #noteOp} or {@link #startOp} when providing
 * access to protected operations or data.</b>
 *
 * <p>Some apps are forwarding access to other apps. E.g. an app might get the location from the
 * system's location provider and then send the location further to a 3rd app. In this case the
 * app passing on the data needs to call {@link #noteProxyOp} to signal the access proxying. This
 * might also make sense inside of a single app if the access is forwarded between two parts of
 * the tagged with different attribution tags.
 *
 * <p>An app can register an {@link OnOpNotedCallback} to get informed about what accesses the
 * system is tracking for it. As each runtime permission has an associated app-op this API is
 * particularly useful for an app that want to find unexpected private data accesses.
 */
@SystemService(Context.APP_OPS_SERVICE)
public class AppOpsManager {
    /**
     * This is a subtle behavior change to {@link #startWatchingMode}.
     *
     * Before this change the system called back for the switched op. After the change the system
     * will call back for the actually requested op or all switched ops if no op is specified.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    public static final long CALL_BACK_ON_CHANGED_LISTENER_WITH_SWITCHED_OP_CHANGE = 148180766L;

    /**
     * Enforce that all attributionTags send to {@link #noteOp}, {@link #noteProxyOp},
     * and {@link #startOp} are defined in the manifest of the package that is specified as
     * parameter to the methods.
     *
     * <p>To enable this change both the package calling {@link #noteOp} as well as the package
     * specified as parameter to the method need to have this change enable.
     *
     * @hide
     */
    @TestApi
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    public static final long SECURITY_EXCEPTION_ON_INVALID_ATTRIBUTION_TAG_CHANGE = 151105954L;

    private static final String FULL_LOG = "privacy_attribution_tag_full_log_enabled";

    private static final int MAX_UNFORWARDED_OPS = 10;

    private static Boolean sFullLog = null;

    final Context mContext;

    @UnsupportedAppUsage
    final IAppOpsService mService;

    /**
     * Service for the application context, to be used by static methods via
     * {@link #getService()}
     */
    @GuardedBy("sLock")
    static IAppOpsService sService;

    @GuardedBy("mModeWatchers")
    private final ArrayMap<OnOpChangedListener, IAppOpsCallback> mModeWatchers =
            new ArrayMap<>();

    @GuardedBy("mActiveWatchers")
    private final ArrayMap<OnOpActiveChangedListener, IAppOpsActiveCallback> mActiveWatchers =
            new ArrayMap<>();

    @GuardedBy("mStartedWatchers")
    private final ArrayMap<OnOpStartedListener, IAppOpsStartedCallback> mStartedWatchers =
            new ArrayMap<>();

    @GuardedBy("mNotedWatchers")
    private final ArrayMap<OnOpNotedListener, IAppOpsNotedCallback> mNotedWatchers =
            new ArrayMap<>();

    private static final Object sLock = new Object();

    /** Current {@link OnOpNotedCallback}. Change via {@link #setOnOpNotedCallback} */
    @GuardedBy("sLock")
    private static @Nullable OnOpNotedCallback sOnOpNotedCallback;

    /**
     * Sync note-ops collected from {@link #readAndLogNotedAppops(Parcel)} that have not been
     * delivered to a callback yet.
     *
     * Similar to {@link com.android.server.appop.AppOpsService#mUnforwardedAsyncNotedOps} for
     * {@link COLLECT_ASYNC}. Used in situation when AppOpsManager asks to collect stacktrace with
     * {@link #sMessageCollector}, which forces {@link COLLECT_SYNC} mode.
     */
    @GuardedBy("sLock")
    private static ArrayList<AsyncNotedAppOp> sUnforwardedOps = new ArrayList<>();

    /**
     * Additional collector that collect accesses and forwards a few of them them via
     * {@link IAppOpsService#reportRuntimeAppOpAccessMessageAndGetConfig}.
     */
    private static OnOpNotedCallback sMessageCollector =
            new OnOpNotedCallback() {
                @Override
                public void onNoted(@NonNull SyncNotedAppOp op) {
                    reportStackTraceIfNeeded(op);
                }

                @Override
                public void onAsyncNoted(@NonNull AsyncNotedAppOp asyncOp) {
                    // collected directly in AppOpsService
                }

                @Override
                public void onSelfNoted(@NonNull SyncNotedAppOp op) {
                    reportStackTraceIfNeeded(op);
                }

                private void reportStackTraceIfNeeded(@NonNull SyncNotedAppOp op) {
                    if (!isCollectingStackTraces()) {
                        return;
                    }
                    MessageSamplingConfig config = sConfig;
                    if (leftCircularDistance(strOpToOp(op.getOp()), config.getSampledOpCode(),
                            _NUM_OP) <= config.getAcceptableLeftDistance()
                            || config.getExpirationTimeSinceBootMillis()
                            < SystemClock.elapsedRealtime()) {
                        String stackTrace = getFormattedStackTrace();
                        try {
                            String packageName = ActivityThread.currentOpPackageName();
                            sConfig = getService().reportRuntimeAppOpAccessMessageAndGetConfig(
                                    packageName == null ? "" : packageName, op, stackTrace);
                        } catch (RemoteException e) {
                            e.rethrowFromSystemServer();
                        }
                    }
                }
            };

    static IBinder sClientId;

    /**
     * How many seconds we want for a drop in uid state from top to settle before applying it.
     *
     * <>Set a parameter to {@link android.provider.Settings.Global#APP_OPS_CONSTANTS}
     *
     * @hide
     */
    @TestApi
    public static final String KEY_TOP_STATE_SETTLE_TIME = "top_state_settle_time";

    /**
     * How many second we want for a drop in uid state from foreground to settle before applying it.
     *
     * <>Set a parameter to {@link android.provider.Settings.Global#APP_OPS_CONSTANTS}
     *
     * @hide
     */
    @TestApi
    public static final String KEY_FG_SERVICE_STATE_SETTLE_TIME =
            "fg_service_state_settle_time";

    /**
     * How many seconds we want for a drop in uid state from background to settle before applying
     * it.
     *
     * <>Set a parameter to {@link android.provider.Settings.Global#APP_OPS_CONSTANTS}
     *
     * @hide
     */
    @TestApi
    public static final String KEY_BG_STATE_SETTLE_TIME = "bg_state_settle_time";

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "HISTORICAL_MODE_" }, value = {
            HISTORICAL_MODE_DISABLED,
            HISTORICAL_MODE_ENABLED_ACTIVE,
            HISTORICAL_MODE_ENABLED_PASSIVE
    })
    public @interface HistoricalMode {}

    /**
     * Mode in which app op history is completely disabled.
     * @hide
     */
    @TestApi
    public static final int HISTORICAL_MODE_DISABLED = 0;

    /**
     * Mode in which app op history is enabled and app ops performed by apps would
     * be tracked. This is the mode in which the feature is completely enabled.
     * @hide
     */
    @TestApi
    public static final int HISTORICAL_MODE_ENABLED_ACTIVE = 1;

    /**
     * Mode in which app op history is enabled but app ops performed by apps would
     * not be tracked and the only way to add ops to the history is via explicit calls
     * to dedicated APIs. This mode is useful for testing to allow full control of
     * the historical content.
     * @hide
     */
    @TestApi
    public static final int HISTORICAL_MODE_ENABLED_PASSIVE = 2;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "MODE_" }, value = {
            MODE_ALLOWED,
            MODE_IGNORED,
            MODE_ERRORED,
            MODE_DEFAULT,
            MODE_FOREGROUND
    })
    public @interface Mode {}

    /**
     * Result from {@link #checkOp}, {@link #noteOp}, {@link #startOp}: the given caller is
     * allowed to perform the given operation.
     */
    public static final int MODE_ALLOWED = 0;

    /**
     * Result from {@link #checkOp}, {@link #noteOp}, {@link #startOp}: the given caller is
     * not allowed to perform the given operation, and this attempt should
     * <em>silently fail</em> (it should not cause the app to crash).
     */
    public static final int MODE_IGNORED = 1;

    /**
     * Result from {@link #checkOpNoThrow}, {@link #noteOpNoThrow}, {@link #startOpNoThrow}: the
     * given caller is not allowed to perform the given operation, and this attempt should
     * cause it to have a fatal error, typically a {@link SecurityException}.
     */
    public static final int MODE_ERRORED = 2;

    /**
     * Result from {@link #checkOp}, {@link #noteOp}, {@link #startOp}: the given caller should
     * use its default security check.  This mode is not normally used; it should only be used
     * with appop permissions, and callers must explicitly check for it and deal with it.
     */
    public static final int MODE_DEFAULT = 3;

    /**
     * Special mode that means "allow only when app is in foreground."  This is <b>not</b>
     * returned from {@link #unsafeCheckOp}, {@link #noteOp}, {@link #startOp}.  Rather,
     * {@link #unsafeCheckOp} will always return {@link #MODE_ALLOWED} (because it is always
     * possible for it to be ultimately allowed, depending on the app's background state),
     * and {@link #noteOp} and {@link #startOp} will return {@link #MODE_ALLOWED} when the app
     * being checked is currently in the foreground, otherwise {@link #MODE_IGNORED}.
     *
     * <p>The only place you will this normally see this value is through
     * {@link #unsafeCheckOpRaw}, which returns the actual raw mode of the op.  Note that because
     * you can't know the current state of the app being checked (and it can change at any
     * point), you can only treat the result here as an indication that it will vary between
     * {@link #MODE_ALLOWED} and {@link #MODE_IGNORED} depending on changes in the background
     * state of the app.  You thus must always use {@link #noteOp} or {@link #startOp} to do
     * the actual check for access to the op.</p>
     */
    public static final int MODE_FOREGROUND = 4;

    /**
     * Flag for {@link #startWatchingMode(String, String, int, OnOpChangedListener)}:
     * Also get reports if the foreground state of an op's uid changes.  This only works
     * when watching a particular op, not when watching a package.
     */
    public static final int WATCH_FOREGROUND_CHANGES = 1 << 0;

    /**
     * Flag for {@link #startWatchingMode} that causes the callback to happen on the switch-op
     * instead the op the callback was registered. (This simulates pre-R behavior).
     *
     * @hide
     */
    public static final int CALL_BACK_ON_SWITCHED_OP = 1 << 1;

    /**
     * Flag to determine whether we should log noteOp/startOp calls to make sure they
     * are correctly used
     *
     * @hide
     */
    public static final boolean NOTE_OP_COLLECTION_ENABLED = false;

    /**
     * @hide
     */
    public static final String[] MODE_NAMES = new String[] {
            "allow",        // MODE_ALLOWED
            "ignore",       // MODE_IGNORED
            "deny",         // MODE_ERRORED
            "default",      // MODE_DEFAULT
            "foreground",   // MODE_FOREGROUND
    };

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "UID_STATE_" }, value = {
            UID_STATE_PERSISTENT,
            UID_STATE_TOP,
            UID_STATE_FOREGROUND_SERVICE_LOCATION,
            UID_STATE_FOREGROUND_SERVICE,
            UID_STATE_FOREGROUND,
            UID_STATE_BACKGROUND,
            UID_STATE_CACHED
    })
    public @interface UidState {}

    /**
     * Uid state: The UID is a foreground persistent app. The lower the UID
     * state the more important the UID is for the user.
     * @hide
     */
    @SystemApi
    public static final int UID_STATE_PERSISTENT = 100;

    /**
     * Uid state: The UID is top foreground app. The lower the UID
     * state the more important the UID is for the user.
     * @hide
     */
    @SystemApi
    public static final int UID_STATE_TOP = 200;

    /**
     * Uid state: The UID is running a foreground service of location type.
     * The lower the UID state the more important the UID is for the user.
     * This uid state is a counterpart to PROCESS_STATE_FOREGROUND_SERVICE_LOCATION which has been
     * deprecated.
     * @hide
     * @deprecated
     */
    @SystemApi
    @Deprecated
    public static final int UID_STATE_FOREGROUND_SERVICE_LOCATION = 300;

    /**
     * Uid state: The UID is running a foreground service. The lower the UID
     * state the more important the UID is for the user.
     * @hide
     */
    @SystemApi
    public static final int UID_STATE_FOREGROUND_SERVICE = 400;

    /**
     * Uid state: The UID is a foreground app. The lower the UID
     * state the more important the UID is for the user.
     * @hide
     */
    @SystemApi
    public static final int UID_STATE_FOREGROUND = 500;

    /**
     * The max, which is min priority, UID state for which any app op
     * would be considered as performed in the foreground.
     * @hide
     */
    public static final int UID_STATE_MAX_LAST_NON_RESTRICTED = UID_STATE_FOREGROUND;

    /**
     * Uid state: The UID is a background app. The lower the UID
     * state the more important the UID is for the user.
     * @hide
     */
    @SystemApi
    public static final int UID_STATE_BACKGROUND = 600;

    /**
     * Uid state: The UID is a cached app. The lower the UID
     * state the more important the UID is for the user.
     * @hide
     */
    @SystemApi
    public static final int UID_STATE_CACHED = 700;

    /**
     * Uid state: The UID state with the highest priority.
     * @hide
     */
    public static final int MAX_PRIORITY_UID_STATE = UID_STATE_PERSISTENT;

    /**
     * Uid state: The UID state with the lowest priority.
     * @hide
     */
    public static final int MIN_PRIORITY_UID_STATE = UID_STATE_CACHED;

    /**
     * Resolves the first unrestricted state given an app op.
     * @param op The op to resolve.
     * @return The last restricted UID state.
     *
     * @hide
     */
    public static int resolveFirstUnrestrictedUidState(int op) {
        return UID_STATE_FOREGROUND;
    }

    /**
     * Resolves the last restricted state given an app op.
     * @param op The op to resolve.
     * @return The last restricted UID state.
     *
     * @hide
     */
    public static int resolveLastRestrictedUidState(int op) {
        return UID_STATE_BACKGROUND;
    }

    /** @hide Note: Keep these sorted */
    public static final int[] UID_STATES = {
            UID_STATE_PERSISTENT,
            UID_STATE_TOP,
            UID_STATE_FOREGROUND_SERVICE_LOCATION,
            UID_STATE_FOREGROUND_SERVICE,
            UID_STATE_FOREGROUND,
            UID_STATE_BACKGROUND,
            UID_STATE_CACHED
    };

    /** @hide */
    public static String getUidStateName(@UidState int uidState) {
        switch (uidState) {
            case UID_STATE_PERSISTENT:
                return "pers";
            case UID_STATE_TOP:
                return "top";
            case UID_STATE_FOREGROUND_SERVICE_LOCATION:
                return "fgsvcl";
            case UID_STATE_FOREGROUND_SERVICE:
                return "fgsvc";
            case UID_STATE_FOREGROUND:
                return "fg";
            case UID_STATE_BACKGROUND:
                return "bg";
            case UID_STATE_CACHED:
                return "cch";
            default:
                return "unknown";
        }
    }

    /**
     * Flag: non proxy operations. These are operations
     * performed on behalf of the app itself and not on behalf of
     * another one.
     *
     * @hide
     */
    @SystemApi
    public static final int OP_FLAG_SELF = 0x1;

    /**
     * Flag: trusted proxy operations. These are operations
     * performed on behalf of another app by a trusted app.
     * Which is work a trusted app blames on another app.
     *
     * @hide
     */
    @SystemApi
    public static final int OP_FLAG_TRUSTED_PROXY = 0x2;

    /**
     * Flag: untrusted proxy operations. These are operations
     * performed on behalf of another app by an untrusted app.
     * Which is work an untrusted app blames on another app.
     *
     * @hide
     */
    @SystemApi
    public static final int OP_FLAG_UNTRUSTED_PROXY = 0x4;

    /**
     * Flag: trusted proxied operations. These are operations
     * performed by a trusted other app on behalf of an app.
     * Which is work an app was blamed for by a trusted app.
     *
     * @hide
     */
    @SystemApi
    public static final int OP_FLAG_TRUSTED_PROXIED = 0x8;

    /**
     * Flag: untrusted proxied operations. These are operations
     * performed by an untrusted other app on behalf of an app.
     * Which is work an app was blamed for by an untrusted app.
     *
     * @hide
     */
    @SystemApi
    public static final int OP_FLAG_UNTRUSTED_PROXIED = 0x10;

    /**
     * Flags: all operations. These include operations matched
     * by {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXIED},
     * {@link #OP_FLAG_UNTRUSTED_PROXIED}, {@link #OP_FLAG_TRUSTED_PROXIED},
     * {@link #OP_FLAG_UNTRUSTED_PROXIED}.
     *
     * @hide
     */
    @SystemApi
    public static final int OP_FLAGS_ALL =
            OP_FLAG_SELF
                | OP_FLAG_TRUSTED_PROXY
                | OP_FLAG_UNTRUSTED_PROXY
                | OP_FLAG_TRUSTED_PROXIED
                | OP_FLAG_UNTRUSTED_PROXIED;

    /**
     * Flags: all trusted operations which is ones either the app did {@link #OP_FLAG_SELF},
     * or it was blamed for by a trusted app {@link #OP_FLAG_TRUSTED_PROXIED}, or ones the
     * app if untrusted blamed on other apps {@link #OP_FLAG_UNTRUSTED_PROXY}.
     *
     * @hide
     */
    @SystemApi
    public static final int OP_FLAGS_ALL_TRUSTED = AppOpsManager.OP_FLAG_SELF
        | AppOpsManager.OP_FLAG_UNTRUSTED_PROXY
        | AppOpsManager.OP_FLAG_TRUSTED_PROXIED;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            OP_FLAG_SELF,
            OP_FLAG_TRUSTED_PROXY,
            OP_FLAG_UNTRUSTED_PROXY,
            OP_FLAG_TRUSTED_PROXIED,
            OP_FLAG_UNTRUSTED_PROXIED
    })
    public @interface OpFlags {}

    /** @hide */
    public static final String getFlagName(@OpFlags int flag) {
        switch (flag) {
            case OP_FLAG_SELF:
                return "s";
            case OP_FLAG_TRUSTED_PROXY:
                return "tp";
            case OP_FLAG_UNTRUSTED_PROXY:
                return "up";
            case OP_FLAG_TRUSTED_PROXIED:
                return "tpd";
            case OP_FLAG_UNTRUSTED_PROXIED:
                return "upd";
            default:
                return "unknown";
        }
    }

    // These constants are redefined here to work around a metalava limitation/bug where
    // @IntDef is not able to see @hide symbols when they are hidden via package hiding:
    // frameworks/base/core/java/com/android/internal/package.html

    /** @hide */
    public static final int SAMPLING_STRATEGY_DEFAULT =
            FrameworkStatsLog.RUNTIME_APP_OP_ACCESS__SAMPLING_STRATEGY__DEFAULT;

    /** @hide */
    public static final int SAMPLING_STRATEGY_UNIFORM =
            FrameworkStatsLog.RUNTIME_APP_OP_ACCESS__SAMPLING_STRATEGY__UNIFORM;

    /** @hide */
    public static final int SAMPLING_STRATEGY_RARELY_USED =
            FrameworkStatsLog.RUNTIME_APP_OP_ACCESS__SAMPLING_STRATEGY__RARELY_USED;

    /** @hide */
    public static final int SAMPLING_STRATEGY_BOOT_TIME_SAMPLING =
            FrameworkStatsLog.RUNTIME_APP_OP_ACCESS__SAMPLING_STRATEGY__BOOT_TIME_SAMPLING;

    /** @hide */
    public static final int SAMPLING_STRATEGY_UNIFORM_OPS =
            FrameworkStatsLog.RUNTIME_APP_OP_ACCESS__SAMPLING_STRATEGY__UNIFORM_OPS;

    /**
     * Strategies used for message sampling
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SAMPLING_STRATEGY_"}, value = {
            SAMPLING_STRATEGY_DEFAULT,
            SAMPLING_STRATEGY_UNIFORM,
            SAMPLING_STRATEGY_RARELY_USED,
            SAMPLING_STRATEGY_BOOT_TIME_SAMPLING,
            SAMPLING_STRATEGY_UNIFORM_OPS
    })
    public @interface SamplingStrategy {}

    private static final int UID_STATE_OFFSET = 31;
    private static final int FLAGS_MASK = 0xFFFFFFFF;

    /**
     * Key for a data bucket storing app op state. The bucket
     * is composed of the uid state and state flags. This way
     * we can query data for given uid state and a set of flags where
     * the flags control which type of data to get. For example,
     * one can get the ops an app did on behalf of other apps
     * while in the background.
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
    public @interface DataBucketKey {
    }

    /** @hide */
    public static String keyToString(@DataBucketKey long key) {
        final int uidState = extractUidStateFromKey(key);
        final int flags = extractFlagsFromKey(key);
        return "[" + getUidStateName(uidState) + "-" + flagsToString(flags) + "]";
    }

    /** @hide */
    public static @DataBucketKey long makeKey(@UidState int uidState, @OpFlags int flags) {
        return ((long) uidState << UID_STATE_OFFSET) | flags;
    }

    /** @hide */
    public static int extractUidStateFromKey(@DataBucketKey long key) {
        return (int) (key >> UID_STATE_OFFSET);
    }

    /** @hide */
    public static int extractFlagsFromKey(@DataBucketKey long key) {
        return (int) (key & FLAGS_MASK);
    }

    /** @hide */
    public static String flagsToString(@OpFlags int flags) {
        final StringBuilder flagsBuilder = new StringBuilder();
        while (flags != 0) {
            final int flag = 1 << Integer.numberOfTrailingZeros(flags);
            flags &= ~flag;
            if (flagsBuilder.length() > 0) {
                flagsBuilder.append('|');
            }
            flagsBuilder.append(getFlagName(flag));
        }
        return flagsBuilder.toString();
    }

    // when adding one of these:
    //  - increment _NUM_OP
    //  - define an OPSTR_* constant (marked as @SystemApi)
    //  - add rows to sOpToSwitch, sOpToString, sOpNames, sOpPerms, sOpDefaultMode, sOpDisableReset,
    //      sOpRestrictions, sOpAllowSystemRestrictionBypass
    //  - add descriptive strings to Settings/res/values/arrays.xml
    //  - add the op to the appropriate template in AppOpsState.OpsTemplate (settings app)

    /** @hide No operation specified. */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int OP_NONE = AppProtoEnums.APP_OP_NONE;
    /** @hide Access to coarse location information. */
    @UnsupportedAppUsage
    @TestApi
    public static final int OP_COARSE_LOCATION = AppProtoEnums.APP_OP_COARSE_LOCATION;
    /** @hide Access to fine location information. */
    @UnsupportedAppUsage
    public static final int OP_FINE_LOCATION = AppProtoEnums.APP_OP_FINE_LOCATION;
    /** @hide Causing GPS to run. */
    @UnsupportedAppUsage
    public static final int OP_GPS = AppProtoEnums.APP_OP_GPS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_VIBRATE = AppProtoEnums.APP_OP_VIBRATE;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_CONTACTS = AppProtoEnums.APP_OP_READ_CONTACTS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_CONTACTS = AppProtoEnums.APP_OP_WRITE_CONTACTS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_CALL_LOG = AppProtoEnums.APP_OP_READ_CALL_LOG;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_CALL_LOG = AppProtoEnums.APP_OP_WRITE_CALL_LOG;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_CALENDAR = AppProtoEnums.APP_OP_READ_CALENDAR;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_CALENDAR = AppProtoEnums.APP_OP_WRITE_CALENDAR;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WIFI_SCAN = AppProtoEnums.APP_OP_WIFI_SCAN;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_POST_NOTIFICATION = AppProtoEnums.APP_OP_POST_NOTIFICATION;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_NEIGHBORING_CELLS = AppProtoEnums.APP_OP_NEIGHBORING_CELLS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_CALL_PHONE = AppProtoEnums.APP_OP_CALL_PHONE;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_SMS = AppProtoEnums.APP_OP_READ_SMS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_SMS = AppProtoEnums.APP_OP_WRITE_SMS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_RECEIVE_SMS = AppProtoEnums.APP_OP_RECEIVE_SMS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_RECEIVE_EMERGECY_SMS =
            AppProtoEnums.APP_OP_RECEIVE_EMERGENCY_SMS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_RECEIVE_MMS = AppProtoEnums.APP_OP_RECEIVE_MMS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_RECEIVE_WAP_PUSH = AppProtoEnums.APP_OP_RECEIVE_WAP_PUSH;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_SEND_SMS = AppProtoEnums.APP_OP_SEND_SMS;
    /** @hide */
    public static final int OP_MANAGE_ONGOING_CALLS = AppProtoEnums.APP_OP_MANAGE_ONGOING_CALLS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_ICC_SMS = AppProtoEnums.APP_OP_READ_ICC_SMS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_ICC_SMS = AppProtoEnums.APP_OP_WRITE_ICC_SMS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_SETTINGS = AppProtoEnums.APP_OP_WRITE_SETTINGS;
    /** @hide Required to draw on top of other apps. */
    @UnsupportedAppUsage
    @TestApi
    public static final int OP_SYSTEM_ALERT_WINDOW = AppProtoEnums.APP_OP_SYSTEM_ALERT_WINDOW;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_ACCESS_NOTIFICATIONS =
            AppProtoEnums.APP_OP_ACCESS_NOTIFICATIONS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_CAMERA = AppProtoEnums.APP_OP_CAMERA;
    /** @hide */
    @UnsupportedAppUsage
    @TestApi
    public static final int OP_RECORD_AUDIO = AppProtoEnums.APP_OP_RECORD_AUDIO;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_PLAY_AUDIO = AppProtoEnums.APP_OP_PLAY_AUDIO;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_READ_CLIPBOARD = AppProtoEnums.APP_OP_READ_CLIPBOARD;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WRITE_CLIPBOARD = AppProtoEnums.APP_OP_WRITE_CLIPBOARD;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_TAKE_MEDIA_BUTTONS = AppProtoEnums.APP_OP_TAKE_MEDIA_BUTTONS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_TAKE_AUDIO_FOCUS = AppProtoEnums.APP_OP_TAKE_AUDIO_FOCUS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_MASTER_VOLUME = AppProtoEnums.APP_OP_AUDIO_MASTER_VOLUME;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_VOICE_VOLUME = AppProtoEnums.APP_OP_AUDIO_VOICE_VOLUME;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_RING_VOLUME = AppProtoEnums.APP_OP_AUDIO_RING_VOLUME;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_MEDIA_VOLUME = AppProtoEnums.APP_OP_AUDIO_MEDIA_VOLUME;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_ALARM_VOLUME = AppProtoEnums.APP_OP_AUDIO_ALARM_VOLUME;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_NOTIFICATION_VOLUME =
            AppProtoEnums.APP_OP_AUDIO_NOTIFICATION_VOLUME;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_BLUETOOTH_VOLUME =
            AppProtoEnums.APP_OP_AUDIO_BLUETOOTH_VOLUME;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_WAKE_LOCK = AppProtoEnums.APP_OP_WAKE_LOCK;
    /** @hide Continually monitoring location data. */
    @UnsupportedAppUsage
    public static final int OP_MONITOR_LOCATION =
            AppProtoEnums.APP_OP_MONITOR_LOCATION;
    /** @hide Continually monitoring location data with a relatively high power request. */
    @UnsupportedAppUsage
    public static final int OP_MONITOR_HIGH_POWER_LOCATION =
            AppProtoEnums.APP_OP_MONITOR_HIGH_POWER_LOCATION;
    /** @hide Retrieve current usage stats via {@link UsageStatsManager}. */
    @UnsupportedAppUsage
    public static final int OP_GET_USAGE_STATS = AppProtoEnums.APP_OP_GET_USAGE_STATS;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_MUTE_MICROPHONE = AppProtoEnums.APP_OP_MUTE_MICROPHONE;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_TOAST_WINDOW = AppProtoEnums.APP_OP_TOAST_WINDOW;
    /** @hide Capture the device's display contents and/or audio */
    @UnsupportedAppUsage
    public static final int OP_PROJECT_MEDIA = AppProtoEnums.APP_OP_PROJECT_MEDIA;
    /**
     * Start (without additional user intervention) a VPN connection, as used by {@link
     * android.net.VpnService} along with as Platform VPN connections, as used by {@link
     * android.net.VpnManager}
     *
     * <p>This appop is granted to apps that have already been given user consent to start
     * VpnService based VPN connections. As this is a superset of OP_ACTIVATE_PLATFORM_VPN, this
     * appop also allows the starting of Platform VPNs.
     *
     * @hide
     */
    @UnsupportedAppUsage
    public static final int OP_ACTIVATE_VPN = AppProtoEnums.APP_OP_ACTIVATE_VPN;
    /** @hide Access the WallpaperManagerAPI to write wallpapers. */
    @UnsupportedAppUsage
    public static final int OP_WRITE_WALLPAPER = AppProtoEnums.APP_OP_WRITE_WALLPAPER;
    /** @hide Received the assist structure from an app. */
    @UnsupportedAppUsage
    public static final int OP_ASSIST_STRUCTURE = AppProtoEnums.APP_OP_ASSIST_STRUCTURE;
    /** @hide Received a screenshot from assist. */
    @UnsupportedAppUsage
    public static final int OP_ASSIST_SCREENSHOT = AppProtoEnums.APP_OP_ASSIST_SCREENSHOT;
    /** @hide Read the phone state. */
    @UnsupportedAppUsage
    public static final int OP_READ_PHONE_STATE = AppProtoEnums.APP_OP_READ_PHONE_STATE;
    /** @hide Add voicemail messages to the voicemail content provider. */
    @UnsupportedAppUsage
    public static final int OP_ADD_VOICEMAIL = AppProtoEnums.APP_OP_ADD_VOICEMAIL;
    /** @hide Access APIs for SIP calling over VOIP or WiFi. */
    @UnsupportedAppUsage
    public static final int OP_USE_SIP = AppProtoEnums.APP_OP_USE_SIP;
    /** @hide Intercept outgoing calls. */
    @UnsupportedAppUsage
    public static final int OP_PROCESS_OUTGOING_CALLS =
            AppProtoEnums.APP_OP_PROCESS_OUTGOING_CALLS;
    /** @hide User the fingerprint API. */
    @UnsupportedAppUsage
    public static final int OP_USE_FINGERPRINT = AppProtoEnums.APP_OP_USE_FINGERPRINT;
    /** @hide Access to body sensors such as heart rate, etc. */
    @UnsupportedAppUsage
    public static final int OP_BODY_SENSORS = AppProtoEnums.APP_OP_BODY_SENSORS;
    /** @hide Read previously received cell broadcast messages. */
    @UnsupportedAppUsage
    public static final int OP_READ_CELL_BROADCASTS = AppProtoEnums.APP_OP_READ_CELL_BROADCASTS;
    /** @hide Inject mock location into the system. */
    @UnsupportedAppUsage
    public static final int OP_MOCK_LOCATION = AppProtoEnums.APP_OP_MOCK_LOCATION;
    /** @hide Read external storage. */
    @UnsupportedAppUsage
    public static final int OP_READ_EXTERNAL_STORAGE = AppProtoEnums.APP_OP_READ_EXTERNAL_STORAGE;
    /** @hide Write external storage. */
    @UnsupportedAppUsage
    public static final int OP_WRITE_EXTERNAL_STORAGE =
            AppProtoEnums.APP_OP_WRITE_EXTERNAL_STORAGE;
    /** @hide Turned on the screen. */
    @UnsupportedAppUsage
    public static final int OP_TURN_SCREEN_ON = AppProtoEnums.APP_OP_TURN_SCREEN_ON;
    /** @hide Get device accounts. */
    @UnsupportedAppUsage
    public static final int OP_GET_ACCOUNTS = AppProtoEnums.APP_OP_GET_ACCOUNTS;
    /** @hide Control whether an application is allowed to run in the background. */
    @UnsupportedAppUsage
    public static final int OP_RUN_IN_BACKGROUND =
            AppProtoEnums.APP_OP_RUN_IN_BACKGROUND;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_AUDIO_ACCESSIBILITY_VOLUME =
            AppProtoEnums.APP_OP_AUDIO_ACCESSIBILITY_VOLUME;
    /** @hide Read the phone number. */
    @UnsupportedAppUsage
    public static final int OP_READ_PHONE_NUMBERS = AppProtoEnums.APP_OP_READ_PHONE_NUMBERS;
    /** @hide Request package installs through package installer */
    @UnsupportedAppUsage
    public static final int OP_REQUEST_INSTALL_PACKAGES =
            AppProtoEnums.APP_OP_REQUEST_INSTALL_PACKAGES;
    /** @hide Enter picture-in-picture. */
    @UnsupportedAppUsage
    public static final int OP_PICTURE_IN_PICTURE = AppProtoEnums.APP_OP_PICTURE_IN_PICTURE;
    /** @hide Instant app start foreground service. */
    @UnsupportedAppUsage
    public static final int OP_INSTANT_APP_START_FOREGROUND =
            AppProtoEnums.APP_OP_INSTANT_APP_START_FOREGROUND;
    /** @hide Answer incoming phone calls */
    @UnsupportedAppUsage
    public static final int OP_ANSWER_PHONE_CALLS = AppProtoEnums.APP_OP_ANSWER_PHONE_CALLS;
    /** @hide Run jobs when in background */
    @UnsupportedAppUsage
    public static final int OP_RUN_ANY_IN_BACKGROUND = AppProtoEnums.APP_OP_RUN_ANY_IN_BACKGROUND;
    /** @hide Change Wi-Fi connectivity state */
    @UnsupportedAppUsage
    public static final int OP_CHANGE_WIFI_STATE = AppProtoEnums.APP_OP_CHANGE_WIFI_STATE;
    /** @hide Request package deletion through package installer */
    @UnsupportedAppUsage
    public static final int OP_REQUEST_DELETE_PACKAGES =
            AppProtoEnums.APP_OP_REQUEST_DELETE_PACKAGES;
    /** @hide Bind an accessibility service. */
    @UnsupportedAppUsage
    public static final int OP_BIND_ACCESSIBILITY_SERVICE =
            AppProtoEnums.APP_OP_BIND_ACCESSIBILITY_SERVICE;
    /** @hide Continue handover of a call from another app */
    @UnsupportedAppUsage
    public static final int OP_ACCEPT_HANDOVER = AppProtoEnums.APP_OP_ACCEPT_HANDOVER;
    /** @hide Create and Manage IPsec Tunnels */
    @UnsupportedAppUsage
    public static final int OP_MANAGE_IPSEC_TUNNELS = AppProtoEnums.APP_OP_MANAGE_IPSEC_TUNNELS;
    /** @hide Any app start foreground service. */
    @UnsupportedAppUsage
    @TestApi
    public static final int OP_START_FOREGROUND = AppProtoEnums.APP_OP_START_FOREGROUND;
    /** @hide */
    @UnsupportedAppUsage
    public static final int OP_BLUETOOTH_SCAN = AppProtoEnums.APP_OP_BLUETOOTH_SCAN;
    /** @hide */
    public static final int OP_BLUETOOTH_CONNECT = AppProtoEnums.APP_OP_BLUETOOTH_CONNECT;
    /** @hide */
    public static final int OP_BLUETOOTH_ADVERTISE = AppProtoEnums.APP_OP_BLUETOOTH_ADVERTISE;
    /** @hide Use the BiometricPrompt/BiometricManager APIs. */
    public static final int OP_USE_BIOMETRIC = AppProtoEnums.APP_OP_USE_BIOMETRIC;
    /** @hide Physical activity recognition. */
    public static final int OP_ACTIVITY_RECOGNITION = AppProtoEnums.APP_OP_ACTIVITY_RECOGNITION;
    /** @hide Financial app sms read. */
    public static final int OP_SMS_FINANCIAL_TRANSACTIONS =
            AppProtoEnums.APP_OP_SMS_FINANCIAL_TRANSACTIONS;
    /** @hide Read media of audio type. */
    public static final int OP_READ_MEDIA_AUDIO = AppProtoEnums.APP_OP_READ_MEDIA_AUDIO;
    /** @hide Write media of audio type. */
    public static final int OP_WRITE_MEDIA_AUDIO = AppProtoEnums.APP_OP_WRITE_MEDIA_AUDIO;
    /** @hide Read media of video type. */
    public static final int OP_READ_MEDIA_VIDEO = AppProtoEnums.APP_OP_READ_MEDIA_VIDEO;
    /** @hide Write media of video type. */
    public static final int OP_WRITE_MEDIA_VIDEO = AppProtoEnums.APP_OP_WRITE_MEDIA_VIDEO;
    /** @hide Read media of image type. */
    public static final int OP_READ_MEDIA_IMAGES = AppProtoEnums.APP_OP_READ_MEDIA_IMAGES;
    /** @hide Write media of image type. */
    public static final int OP_WRITE_MEDIA_IMAGES = AppProtoEnums.APP_OP_WRITE_MEDIA_IMAGES;
    /** @hide Has a legacy (non-isolated) view of storage. */
    public static final int OP_LEGACY_STORAGE = AppProtoEnums.APP_OP_LEGACY_STORAGE;
    /** @hide Accessing accessibility features */
    public static final int OP_ACCESS_ACCESSIBILITY = AppProtoEnums.APP_OP_ACCESS_ACCESSIBILITY;
    /** @hide Read the device identifiers (IMEI / MEID, IMSI, SIM / Build serial) */
    public static final int OP_READ_DEVICE_IDENTIFIERS =
            AppProtoEnums.APP_OP_READ_DEVICE_IDENTIFIERS;
    /** @hide Read location metadata from media */
    public static final int OP_ACCESS_MEDIA_LOCATION = AppProtoEnums.APP_OP_ACCESS_MEDIA_LOCATION;
    /** @hide Query all apps on device, regardless of declarations in the calling app manifest */
    public static final int OP_QUERY_ALL_PACKAGES = AppProtoEnums.APP_OP_QUERY_ALL_PACKAGES;
    /** @hide Access all external storage */
    public static final int OP_MANAGE_EXTERNAL_STORAGE =
            AppProtoEnums.APP_OP_MANAGE_EXTERNAL_STORAGE;
    /** @hide Communicate cross-profile within the same profile group. */
    public static final int OP_INTERACT_ACROSS_PROFILES =
            AppProtoEnums.APP_OP_INTERACT_ACROSS_PROFILES;
    /**
     * Start (without additional user intervention) a Platform VPN connection, as used by {@link
     * android.net.VpnManager}
     *
     * <p>This appop is granted to apps that have already been given user consent to start Platform
     * VPN connections. This appop is insufficient to start VpnService based VPNs; OP_ACTIVATE_VPN
     * is needed for that.
     *
     * @hide
     */
    public static final int OP_ACTIVATE_PLATFORM_VPN = AppProtoEnums.APP_OP_ACTIVATE_PLATFORM_VPN;
    /** @hide Controls whether or not read logs are available for incremental installations. */
    public static final int OP_LOADER_USAGE_STATS = AppProtoEnums.APP_OP_LOADER_USAGE_STATS;

    // App op deprecated/removed.
    private static final int OP_DEPRECATED_1 = AppProtoEnums.APP_OP_DEPRECATED_1;

    /** @hide Auto-revoke app permissions if app is unused for an extended period */
    public static final int OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED =
            AppProtoEnums.APP_OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED;

    /**
     * Whether {@link #OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED} is allowed to be changed by
     * the installer
     *
     * @hide
     */
    public static final int OP_AUTO_REVOKE_MANAGED_BY_INSTALLER =
            AppProtoEnums.APP_OP_AUTO_REVOKE_MANAGED_BY_INSTALLER;

    /** @hide */
    public static final int OP_NO_ISOLATED_STORAGE = AppProtoEnums.APP_OP_NO_ISOLATED_STORAGE;

    /**
     * Phone call is using microphone
     *
     * @hide
     */
    public static final int OP_PHONE_CALL_MICROPHONE = AppProtoEnums.APP_OP_PHONE_CALL_MICROPHONE;
    /**
     * Phone call is using camera
     *
     * @hide
     */
    public static final int OP_PHONE_CALL_CAMERA = AppProtoEnums.APP_OP_PHONE_CALL_CAMERA;

    /**
     * Audio is being recorded for hotword detection.
     *
     * @hide
     */
    public static final int OP_RECORD_AUDIO_HOTWORD = AppProtoEnums.APP_OP_RECORD_AUDIO_HOTWORD;

    /**
     * Manage credentials in the system KeyChain.
     *
     * @hide
     */
    public static final int OP_MANAGE_CREDENTIALS = AppProtoEnums.APP_OP_MANAGE_CREDENTIALS;

    /** @hide */
    public static final int OP_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER =
            AppProtoEnums.APP_OP_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER;

    /**
     * App output audio is being recorded
     *
     * @hide
     */
    public static final int OP_RECORD_AUDIO_OUTPUT = AppProtoEnums.APP_OP_RECORD_AUDIO_OUTPUT;

    /**
     * App can schedule exact alarm to perform timing based background work
     *
     * @hide
     */
    public static final int OP_SCHEDULE_EXACT_ALARM = AppProtoEnums.APP_OP_SCHEDULE_EXACT_ALARM;

    /**
     * Fine location being accessed by a location source, which is
     * a component that already has location data since it is the one
     * that produces location, which is it is a data source for
     * location data.
     *
     * @hide
     */
    public static final int OP_FINE_LOCATION_SOURCE = AppProtoEnums.APP_OP_FINE_LOCATION_SOURCE;

    /**
     * Coarse location being accessed by a location source, which is
     * a component that already has location data since it is the one
     * that produces location, which is it is a data source for
     * location data.
     *
     * @hide
     */
    public static final int OP_COARSE_LOCATION_SOURCE = AppProtoEnums.APP_OP_COARSE_LOCATION_SOURCE;

    /**
     * Allow apps to create the requests to manage the media files without user confirmation.
     *
     * @see android.Manifest.permission#MANAGE_MEDIA
     * @see android.provider.MediaStore#createDeleteRequest(ContentResolver, Collection)
     * @see android.provider.MediaStore#createTrashRequest(ContentResolver, Collection, boolean)
     * @see android.provider.MediaStore#createWriteRequest(ContentResolver, Collection)
     *
     * @hide
     */
    public static final int OP_MANAGE_MEDIA = AppProtoEnums.APP_OP_MANAGE_MEDIA;

    /** @hide */
    public static final int OP_UWB_RANGING = AppProtoEnums.APP_OP_UWB_RANGING;

    /**
     * Activity recognition being accessed by an activity recognition source, which
     * is a component that already has access since it is the one that detects
     * activity recognition.
     *
     * @hide
     */
    public static final int OP_ACTIVITY_RECOGNITION_SOURCE =
            AppProtoEnums.APP_OP_ACTIVITY_RECOGNITION_SOURCE;

    /**
     * Incoming phone audio is being recorded
     *
     * @hide
     */
    public static final int OP_RECORD_INCOMING_PHONE_AUDIO =
            AppProtoEnums.APP_OP_RECORD_INCOMING_PHONE_AUDIO;

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static final int _NUM_OP = 116;

    /** Access to coarse location information. */
    public static final String OPSTR_COARSE_LOCATION = "android:coarse_location";
    /** Access to fine location information. */
    public static final String OPSTR_FINE_LOCATION =
            "android:fine_location";
    /** Continually monitoring location data. */
    public static final String OPSTR_MONITOR_LOCATION
            = "android:monitor_location";
    /** Continually monitoring location data with a relatively high power request. */
    public static final String OPSTR_MONITOR_HIGH_POWER_LOCATION
            = "android:monitor_location_high_power";
    /** Access to {@link android.app.usage.UsageStatsManager}. */
    public static final String OPSTR_GET_USAGE_STATS
            = "android:get_usage_stats";
    /** Activate a VPN connection without user intervention. @hide */
    @SystemApi
    public static final String OPSTR_ACTIVATE_VPN
            = "android:activate_vpn";
    /** Allows an application to read the user's contacts data. */
    public static final String OPSTR_READ_CONTACTS
            = "android:read_contacts";
    /** Allows an application to write to the user's contacts data. */
    public static final String OPSTR_WRITE_CONTACTS
            = "android:write_contacts";
    /** Allows an application to read the user's call log. */
    public static final String OPSTR_READ_CALL_LOG
            = "android:read_call_log";
    /** Allows an application to write to the user's call log. */
    public static final String OPSTR_WRITE_CALL_LOG
            = "android:write_call_log";
    /** Allows an application to read the user's calendar data. */
    public static final String OPSTR_READ_CALENDAR
            = "android:read_calendar";
    /** Allows an application to write to the user's calendar data. */
    public static final String OPSTR_WRITE_CALENDAR
            = "android:write_calendar";
    /** Allows an application to initiate a phone call. */
    public static final String OPSTR_CALL_PHONE
            = "android:call_phone";
    /** Allows an application to read SMS messages. */
    public static final String OPSTR_READ_SMS
            = "android:read_sms";
    /** Allows an application to receive SMS messages. */
    public static final String OPSTR_RECEIVE_SMS
            = "android:receive_sms";
    /** Allows an application to receive MMS messages. */
    public static final String OPSTR_RECEIVE_MMS
            = "android:receive_mms";
    /** Allows an application to receive WAP push messages. */
    public static final String OPSTR_RECEIVE_WAP_PUSH
            = "android:receive_wap_push";
    /** Allows an application to send SMS messages. */
    public static final String OPSTR_SEND_SMS
            = "android:send_sms";
    /** Required to be able to access the camera device. */
    public static final String OPSTR_CAMERA
            = "android:camera";
    /** Required to be able to access the microphone device. */
    public static final String OPSTR_RECORD_AUDIO
            = "android:record_audio";
    /** Required to access phone state related information. */
    public static final String OPSTR_READ_PHONE_STATE
            = "android:read_phone_state";
    /** Required to access phone state related information. */
    public static final String OPSTR_ADD_VOICEMAIL
            = "android:add_voicemail";
    /** Access APIs for SIP calling over VOIP or WiFi */
    public static final String OPSTR_USE_SIP
            = "android:use_sip";
    /** Access APIs for diverting outgoing calls */
    public static final String OPSTR_PROCESS_OUTGOING_CALLS
            = "android:process_outgoing_calls";
    /** Use the fingerprint API. */
    public static final String OPSTR_USE_FINGERPRINT
            = "android:use_fingerprint";
    /** Access to body sensors such as heart rate, etc. */
    public static final String OPSTR_BODY_SENSORS
            = "android:body_sensors";
    /** Read previously received cell broadcast messages. */
    public static final String OPSTR_READ_CELL_BROADCASTS
            = "android:read_cell_broadcasts";
    /** Inject mock location into the system. */
    public static final String OPSTR_MOCK_LOCATION
            = "android:mock_location";
    /** Read external storage. */
    public static final String OPSTR_READ_EXTERNAL_STORAGE
            = "android:read_external_storage";
    /** Write external storage. */
    public static final String OPSTR_WRITE_EXTERNAL_STORAGE
            = "android:write_external_storage";
    /** Required to draw on top of other apps. */
    public static final String OPSTR_SYSTEM_ALERT_WINDOW
            = "android:system_alert_window";
    /** Required to write/modify/update system settings. */
    public static final String OPSTR_WRITE_SETTINGS
            = "android:write_settings";
    /** @hide Get device accounts. */
    @SystemApi
    public static final String OPSTR_GET_ACCOUNTS
            = "android:get_accounts";
    public static final String OPSTR_READ_PHONE_NUMBERS
            = "android:read_phone_numbers";
    /** Access to picture-in-picture. */
    public static final String OPSTR_PICTURE_IN_PICTURE
            = "android:picture_in_picture";
    /** @hide */
    @SystemApi
    public static final String OPSTR_INSTANT_APP_START_FOREGROUND
            = "android:instant_app_start_foreground";
    /** Answer incoming phone calls */
    public static final String OPSTR_ANSWER_PHONE_CALLS
            = "android:answer_phone_calls";
    /**
     * Accept call handover
     * @hide
     */
    @SystemApi
    public static final String OPSTR_ACCEPT_HANDOVER
            = "android:accept_handover";
    /** @hide */
    @SystemApi
    public static final String OPSTR_GPS = "android:gps";
    /** @hide */
    @SystemApi
    public static final String OPSTR_VIBRATE = "android:vibrate";
    /** @hide */
    @SystemApi
    public static final String OPSTR_WIFI_SCAN = "android:wifi_scan";
    /** @hide */
    @SystemApi
    public static final String OPSTR_POST_NOTIFICATION = "android:post_notification";
    /** @hide */
    @SystemApi
    public static final String OPSTR_NEIGHBORING_CELLS = "android:neighboring_cells";
    /** @hide */
    @SystemApi
    public static final String OPSTR_WRITE_SMS = "android:write_sms";
    /** @hide */
    @SystemApi
    public static final String OPSTR_RECEIVE_EMERGENCY_BROADCAST =
            "android:receive_emergency_broadcast";
    /** @hide */
    @SystemApi
    public static final String OPSTR_READ_ICC_SMS = "android:read_icc_sms";
    /** @hide */
    @SystemApi
    public static final String OPSTR_WRITE_ICC_SMS = "android:write_icc_sms";
    /** @hide */
    @SystemApi
    public static final String OPSTR_ACCESS_NOTIFICATIONS = "android:access_notifications";
    /** @hide */
    @SystemApi
    public static final String OPSTR_PLAY_AUDIO = "android:play_audio";
    /** @hide */
    @SystemApi
    public static final String OPSTR_READ_CLIPBOARD = "android:read_clipboard";
    /** @hide */
    @SystemApi
    public static final String OPSTR_WRITE_CLIPBOARD = "android:write_clipboard";
    /** @hide */
    @SystemApi
    public static final String OPSTR_TAKE_MEDIA_BUTTONS = "android:take_media_buttons";
    /** @hide */
    @SystemApi
    public static final String OPSTR_TAKE_AUDIO_FOCUS = "android:take_audio_focus";
    /** @hide */
    @SystemApi
    public static final String OPSTR_AUDIO_MASTER_VOLUME = "android:audio_master_volume";
    /** @hide */
    @SystemApi
    public static final String OPSTR_AUDIO_VOICE_VOLUME = "android:audio_voice_volume";
    /** @hide */
    @SystemApi
    public static final String OPSTR_AUDIO_RING_VOLUME = "android:audio_ring_volume";
    /** @hide */
    @SystemApi
    public static final String OPSTR_AUDIO_MEDIA_VOLUME = "android:audio_media_volume";
    /** @hide */
    @SystemApi
    public static final String OPSTR_AUDIO_ALARM_VOLUME = "android:audio_alarm_volume";
    /** @hide */
    @SystemApi
    public static final String OPSTR_AUDIO_NOTIFICATION_VOLUME =
            "android:audio_notification_volume";
    /** @hide */
    @SystemApi
    public static final String OPSTR_AUDIO_BLUETOOTH_VOLUME = "android:audio_bluetooth_volume";
    /** @hide */
    @SystemApi
    public static final String OPSTR_WAKE_LOCK = "android:wake_lock";
    /** @hide */
    @SystemApi
    public static final String OPSTR_MUTE_MICROPHONE = "android:mute_microphone";
    /** @hide */
    @SystemApi
    public static final String OPSTR_TOAST_WINDOW = "android:toast_window";
    /** @hide */
    @SystemApi
    public static final String OPSTR_PROJECT_MEDIA = "android:project_media";
    /** @hide */
    @SystemApi
    public static final String OPSTR_WRITE_WALLPAPER = "android:write_wallpaper";
    /** @hide */
    @SystemApi
    public static final String OPSTR_ASSIST_STRUCTURE = "android:assist_structure";
    /** @hide */
    @SystemApi
    public static final String OPSTR_ASSIST_SCREENSHOT = "android:assist_screenshot";
    /** @hide */
    @SystemApi
    public static final String OPSTR_TURN_SCREEN_ON = "android:turn_screen_on";
    /** @hide */
    @SystemApi
    public static final String OPSTR_RUN_IN_BACKGROUND = "android:run_in_background";
    /** @hide */
    @SystemApi
    public static final String OPSTR_AUDIO_ACCESSIBILITY_VOLUME =
            "android:audio_accessibility_volume";
    /** @hide */
    @SystemApi
    public static final String OPSTR_REQUEST_INSTALL_PACKAGES = "android:request_install_packages";
    /** @hide */
    @SystemApi
    public static final String OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background";
    /** @hide */
    @SystemApi
    public static final String OPSTR_CHANGE_WIFI_STATE = "android:change_wifi_state";
    /** @hide */
    @SystemApi
    public static final String OPSTR_REQUEST_DELETE_PACKAGES = "android:request_delete_packages";
    /** @hide */
    @SystemApi
    public static final String OPSTR_BIND_ACCESSIBILITY_SERVICE =
            "android:bind_accessibility_service";
    /** @hide */
    @SystemApi
    public static final String OPSTR_MANAGE_IPSEC_TUNNELS = "android:manage_ipsec_tunnels";
    /** @hide */
    @SystemApi
    public static final String OPSTR_START_FOREGROUND = "android:start_foreground";
    /** @hide */
    public static final String OPSTR_BLUETOOTH_SCAN = "android:bluetooth_scan";
    /** @hide */
    public static final String OPSTR_BLUETOOTH_CONNECT = "android:bluetooth_connect";
    /** @hide */
    public static final String OPSTR_BLUETOOTH_ADVERTISE = "android:bluetooth_advertise";

    /** @hide Use the BiometricPrompt/BiometricManager APIs. */
    public static final String OPSTR_USE_BIOMETRIC = "android:use_biometric";

    /** @hide Recognize physical activity. */
    @TestApi
    public static final String OPSTR_ACTIVITY_RECOGNITION = "android:activity_recognition";

    /** @hide Financial app read sms. */
    public static final String OPSTR_SMS_FINANCIAL_TRANSACTIONS =
            "android:sms_financial_transactions";

    /** @hide Read media of audio type. */
    @SystemApi
    public static final String OPSTR_READ_MEDIA_AUDIO = "android:read_media_audio";
    /** @hide Write media of audio type. */
    @SystemApi
    public static final String OPSTR_WRITE_MEDIA_AUDIO = "android:write_media_audio";
    /** @hide Read media of video type. */
    @SystemApi
    public static final String OPSTR_READ_MEDIA_VIDEO = "android:read_media_video";
    /** @hide Write media of video type. */
    @SystemApi
    public static final String OPSTR_WRITE_MEDIA_VIDEO = "android:write_media_video";
    /** @hide Read media of image type. */
    @SystemApi
    public static final String OPSTR_READ_MEDIA_IMAGES = "android:read_media_images";
    /** @hide Write media of image type. */
    @SystemApi
    public static final String OPSTR_WRITE_MEDIA_IMAGES = "android:write_media_images";
    /** @hide Has a legacy (non-isolated) view of storage. */
    @SystemApi
    public static final String OPSTR_LEGACY_STORAGE = "android:legacy_storage";
    /** @hide Read location metadata from media */
    public static final String OPSTR_ACCESS_MEDIA_LOCATION = "android:access_media_location";

    /** @hide Interact with accessibility. */
    @SystemApi
    public static final String OPSTR_ACCESS_ACCESSIBILITY = "android:access_accessibility";
    /** @hide Read device identifiers */
    public static final String OPSTR_READ_DEVICE_IDENTIFIERS = "android:read_device_identifiers";
    /** @hide Query all packages on device */
    public static final String OPSTR_QUERY_ALL_PACKAGES = "android:query_all_packages";
    /** @hide Access all external storage */
    @SystemApi
    public static final String OPSTR_MANAGE_EXTERNAL_STORAGE =
            "android:manage_external_storage";

    /** @hide Auto-revoke app permissions if app is unused for an extended period */
    @SystemApi
    public static final String OPSTR_AUTO_REVOKE_PERMISSIONS_IF_UNUSED =
            "android:auto_revoke_permissions_if_unused";

    /** @hide Auto-revoke app permissions if app is unused for an extended period */
    @SystemApi
    public static final String OPSTR_AUTO_REVOKE_MANAGED_BY_INSTALLER =
            "android:auto_revoke_managed_by_installer";

    /** @hide Communicate cross-profile within the same profile group. */
    @SystemApi
    public static final String OPSTR_INTERACT_ACROSS_PROFILES = "android:interact_across_profiles";
    /** @hide Start Platform VPN without user intervention */
    @SystemApi
    public static final String OPSTR_ACTIVATE_PLATFORM_VPN = "android:activate_platform_vpn";
    /** @hide */
    @SystemApi
    public static final String OPSTR_LOADER_USAGE_STATS = "android:loader_usage_stats";

    /**
     * Grants an app access to the {@link android.telecom.InCallService} API to see
     * information about ongoing calls and to enable control of calls.
     * @hide
     */
    @SystemApi
    @TestApi
    public static final String OPSTR_MANAGE_ONGOING_CALLS = "android:manage_ongoing_calls";

    /**
     * AppOp granted to apps that we are started via {@code am instrument -e --no-isolated-storage}
     *
     * <p>MediaProvider is the only component (outside of system server) that should care about this
     * app op, hence {@code SystemApi.Client.MODULE_LIBRARIES}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    public static final String OPSTR_NO_ISOLATED_STORAGE = "android:no_isolated_storage";

    /**
     * Phone call is using microphone
     *
     * @hide
     */
    @SystemApi
    public static final String OPSTR_PHONE_CALL_MICROPHONE = "android:phone_call_microphone";
    /**
     * Phone call is using camera
     *
     * @hide
     */
    @SystemApi
    public static final String OPSTR_PHONE_CALL_CAMERA = "android:phone_call_camera";

    /**
     * Audio is being recorded for hotword detection.
     *
     * @hide
     */
    public static final String OPSTR_RECORD_AUDIO_HOTWORD = "android:record_audio_hotword";

    /**
     * Manage credentials in the system KeyChain.
     *
     * @hide
     */
    public static final String OPSTR_MANAGE_CREDENTIALS = "android:manage_credentials";

    /**
     * Allows to read device identifiers and use ICC based authentication like EAP-AKA.
     *
     * @hide
     */
    @TestApi
    public static final String OPSTR_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER =
            "android:use_icc_auth_with_device_identifier";
    /**
     * App output audio is being recorded
     *
     * @hide
     */
    public static final String OPSTR_RECORD_AUDIO_OUTPUT = "android:record_audio_output";

    /**
     * App can schedule exact alarm to perform timing based background work.
     *
     * @hide
     */
    public static final String OPSTR_SCHEDULE_EXACT_ALARM = "android:schedule_exact_alarm";

    /**
     * Fine location being accessed by a location source, which is
     * a component that already has location since it is the one that
     * produces location.
     *
     * @hide
     */
    public static final String OPSTR_FINE_LOCATION_SOURCE = "android:fine_location_source";

    /**
     * Coarse location being accessed by a location source, which is
     * a component that already has location since it is the one that
     * produces location.
     *
     * @hide
     */
    public static final String OPSTR_COARSE_LOCATION_SOURCE = "android:coarse_location_source";

    /**
     * Allow apps to create the requests to manage the media files without user confirmation.
     *
     * @see android.Manifest.permission#MANAGE_MEDIA
     * @see android.provider.MediaStore#createDeleteRequest(ContentResolver, Collection)
     * @see android.provider.MediaStore#createTrashRequest(ContentResolver, Collection, boolean)
     * @see android.provider.MediaStore#createWriteRequest(ContentResolver, Collection)
     *
     * @hide
     */
    public static final String OPSTR_MANAGE_MEDIA = "android:manage_media";
    /** @hide */
    public static final String OPSTR_UWB_RANGING = "android:uwb_ranging";

    /**
     * Activity recognition being accessed by an activity recognition source, which
     * is a component that already has access since it is the one that detects
     * activity recognition.
     *
     * @hide
     */
    @TestApi
    public static final String OPSTR_ACTIVITY_RECOGNITION_SOURCE =
            "android:activity_recognition_source";

    /**
     * @hide
     */
    public static final String OPSTR_RECORD_INCOMING_PHONE_AUDIO =
            "android:record_incoming_phone_audio";

    /** {@link #sAppOpsToNote} not initialized yet for this op */
    private static final byte SHOULD_COLLECT_NOTE_OP_NOT_INITIALIZED = 0;
    /** Should not collect noting of this app-op in {@link #sAppOpsToNote} */
    private static final byte SHOULD_NOT_COLLECT_NOTE_OP = 1;
    /** Should collect noting of this app-op in {@link #sAppOpsToNote} */
    private static final byte SHOULD_COLLECT_NOTE_OP = 2;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "SHOULD_" }, value = {
            SHOULD_COLLECT_NOTE_OP_NOT_INITIALIZED,
            SHOULD_NOT_COLLECT_NOTE_OP,
            SHOULD_COLLECT_NOTE_OP
    })
    private @interface ShouldCollectNoteOp {}

    private static final int[] RUNTIME_AND_APPOP_PERMISSIONS_OPS = {
            // RUNTIME PERMISSIONS
            // Contacts
            OP_READ_CONTACTS,
            OP_WRITE_CONTACTS,
            OP_GET_ACCOUNTS,
            // Calendar
            OP_READ_CALENDAR,
            OP_WRITE_CALENDAR,
            // SMS
            OP_SEND_SMS,
            OP_RECEIVE_SMS,
            OP_READ_SMS,
            OP_RECEIVE_WAP_PUSH,
            OP_RECEIVE_MMS,
            OP_READ_CELL_BROADCASTS,
            // Storage
            OP_READ_EXTERNAL_STORAGE,
            OP_WRITE_EXTERNAL_STORAGE,
            OP_ACCESS_MEDIA_LOCATION,
            // Location
            OP_COARSE_LOCATION,
            OP_FINE_LOCATION,
            // Phone
            OP_READ_PHONE_STATE,
            OP_READ_PHONE_NUMBERS,
            OP_CALL_PHONE,
            OP_READ_CALL_LOG,
            OP_WRITE_CALL_LOG,
            OP_ADD_VOICEMAIL,
            OP_USE_SIP,
            OP_PROCESS_OUTGOING_CALLS,
            OP_ANSWER_PHONE_CALLS,
            OP_ACCEPT_HANDOVER,
            // Microphone
            OP_RECORD_AUDIO,
            // Camera
            OP_CAMERA,
            // Body sensors
            OP_BODY_SENSORS,
            // Activity recognition
            OP_ACTIVITY_RECOGNITION,
            // Aural
            OP_READ_MEDIA_AUDIO,
            OP_WRITE_MEDIA_AUDIO,
            // Visual
            OP_READ_MEDIA_VIDEO,
            OP_WRITE_MEDIA_VIDEO,
            OP_READ_MEDIA_IMAGES,
            OP_WRITE_MEDIA_IMAGES,
            // Nearby devices
            OP_BLUETOOTH_SCAN,
            OP_BLUETOOTH_CONNECT,
            OP_BLUETOOTH_ADVERTISE,
            OP_UWB_RANGING,

            // APPOP PERMISSIONS
            OP_ACCESS_NOTIFICATIONS,
            OP_SYSTEM_ALERT_WINDOW,
            OP_WRITE_SETTINGS,
            OP_REQUEST_INSTALL_PACKAGES,
            OP_START_FOREGROUND,
            OP_SMS_FINANCIAL_TRANSACTIONS,
            OP_MANAGE_IPSEC_TUNNELS,
            OP_INSTANT_APP_START_FOREGROUND,
            OP_MANAGE_EXTERNAL_STORAGE,
            OP_INTERACT_ACROSS_PROFILES,
            OP_LOADER_USAGE_STATS,
            OP_MANAGE_ONGOING_CALLS,
            OP_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER,
            OP_SCHEDULE_EXACT_ALARM,
            OP_MANAGE_MEDIA,
    };

    /**
     * This maps each operation to the operation that serves as the
     * switch to determine whether it is allowed.  Generally this is
     * a 1:1 mapping, but for some things (like location) that have
     * multiple low-level operations being tracked that should be
     * presented to the user as one switch then this can be used to
     * make them all controlled by the same single operation.
     */
    private static int[] sOpToSwitch = new int[] {
            OP_COARSE_LOCATION,                 // COARSE_LOCATION
            OP_FINE_LOCATION,                   // FINE_LOCATION
            OP_COARSE_LOCATION,                 // GPS
            OP_VIBRATE,                         // VIBRATE
            OP_READ_CONTACTS,                   // READ_CONTACTS
            OP_WRITE_CONTACTS,                  // WRITE_CONTACTS
            OP_READ_CALL_LOG,                   // READ_CALL_LOG
            OP_WRITE_CALL_LOG,                  // WRITE_CALL_LOG
            OP_READ_CALENDAR,                   // READ_CALENDAR
            OP_WRITE_CALENDAR,                  // WRITE_CALENDAR
            OP_COARSE_LOCATION,                 // WIFI_SCAN
            OP_POST_NOTIFICATION,               // POST_NOTIFICATION
            OP_COARSE_LOCATION,                 // NEIGHBORING_CELLS
            OP_CALL_PHONE,                      // CALL_PHONE
            OP_READ_SMS,                        // READ_SMS
            OP_WRITE_SMS,                       // WRITE_SMS
            OP_RECEIVE_SMS,                     // RECEIVE_SMS
            OP_RECEIVE_SMS,                     // RECEIVE_EMERGECY_SMS
            OP_RECEIVE_MMS,                     // RECEIVE_MMS
            OP_RECEIVE_WAP_PUSH,                // RECEIVE_WAP_PUSH
            OP_SEND_SMS,                        // SEND_SMS
            OP_READ_SMS,                        // READ_ICC_SMS
            OP_WRITE_SMS,                       // WRITE_ICC_SMS
            OP_WRITE_SETTINGS,                  // WRITE_SETTINGS
            OP_SYSTEM_ALERT_WINDOW,             // SYSTEM_ALERT_WINDOW
            OP_ACCESS_NOTIFICATIONS,            // ACCESS_NOTIFICATIONS
            OP_CAMERA,                          // CAMERA
            OP_RECORD_AUDIO,                    // RECORD_AUDIO
            OP_PLAY_AUDIO,                      // PLAY_AUDIO
            OP_READ_CLIPBOARD,                  // READ_CLIPBOARD
            OP_WRITE_CLIPBOARD,                 // WRITE_CLIPBOARD
            OP_TAKE_MEDIA_BUTTONS,              // TAKE_MEDIA_BUTTONS
            OP_TAKE_AUDIO_FOCUS,                // TAKE_AUDIO_FOCUS
            OP_AUDIO_MASTER_VOLUME,             // AUDIO_MASTER_VOLUME
            OP_AUDIO_VOICE_VOLUME,              // AUDIO_VOICE_VOLUME
            OP_AUDIO_RING_VOLUME,               // AUDIO_RING_VOLUME
            OP_AUDIO_MEDIA_VOLUME,              // AUDIO_MEDIA_VOLUME
            OP_AUDIO_ALARM_VOLUME,              // AUDIO_ALARM_VOLUME
            OP_AUDIO_NOTIFICATION_VOLUME,       // AUDIO_NOTIFICATION_VOLUME
            OP_AUDIO_BLUETOOTH_VOLUME,          // AUDIO_BLUETOOTH_VOLUME
            OP_WAKE_LOCK,                       // WAKE_LOCK
            OP_COARSE_LOCATION,                 // MONITOR_LOCATION
            OP_COARSE_LOCATION,                 // MONITOR_HIGH_POWER_LOCATION
            OP_GET_USAGE_STATS,                 // GET_USAGE_STATS
            OP_MUTE_MICROPHONE,                 // MUTE_MICROPHONE
            OP_TOAST_WINDOW,                    // TOAST_WINDOW
            OP_PROJECT_MEDIA,                   // PROJECT_MEDIA
            OP_ACTIVATE_VPN,                    // ACTIVATE_VPN
            OP_WRITE_WALLPAPER,                 // WRITE_WALLPAPER
            OP_ASSIST_STRUCTURE,                // ASSIST_STRUCTURE
            OP_ASSIST_SCREENSHOT,               // ASSIST_SCREENSHOT
            OP_READ_PHONE_STATE,                // READ_PHONE_STATE
            OP_ADD_VOICEMAIL,                   // ADD_VOICEMAIL
            OP_USE_SIP,                         // USE_SIP
            OP_PROCESS_OUTGOING_CALLS,          // PROCESS_OUTGOING_CALLS
            OP_USE_FINGERPRINT,                 // USE_FINGERPRINT
            OP_BODY_SENSORS,                    // BODY_SENSORS
            OP_READ_CELL_BROADCASTS,            // READ_CELL_BROADCASTS
            OP_MOCK_LOCATION,                   // MOCK_LOCATION
            OP_READ_EXTERNAL_STORAGE,           // READ_EXTERNAL_STORAGE
            OP_WRITE_EXTERNAL_STORAGE,          // WRITE_EXTERNAL_STORAGE
            OP_TURN_SCREEN_ON,                  // TURN_SCREEN_ON
            OP_GET_ACCOUNTS,                    // GET_ACCOUNTS
            OP_RUN_IN_BACKGROUND,               // RUN_IN_BACKGROUND
            OP_AUDIO_ACCESSIBILITY_VOLUME,      // AUDIO_ACCESSIBILITY_VOLUME
            OP_READ_PHONE_NUMBERS,              // READ_PHONE_NUMBERS
            OP_REQUEST_INSTALL_PACKAGES,        // REQUEST_INSTALL_PACKAGES
            OP_PICTURE_IN_PICTURE,              // ENTER_PICTURE_IN_PICTURE_ON_HIDE
            OP_INSTANT_APP_START_FOREGROUND,    // INSTANT_APP_START_FOREGROUND
            OP_ANSWER_PHONE_CALLS,              // ANSWER_PHONE_CALLS
            OP_RUN_ANY_IN_BACKGROUND,           // OP_RUN_ANY_IN_BACKGROUND
            OP_CHANGE_WIFI_STATE,               // OP_CHANGE_WIFI_STATE
            OP_REQUEST_DELETE_PACKAGES,         // OP_REQUEST_DELETE_PACKAGES
            OP_BIND_ACCESSIBILITY_SERVICE,      // OP_BIND_ACCESSIBILITY_SERVICE
            OP_ACCEPT_HANDOVER,                 // ACCEPT_HANDOVER
            OP_MANAGE_IPSEC_TUNNELS,            // MANAGE_IPSEC_HANDOVERS
            OP_START_FOREGROUND,                // START_FOREGROUND
            OP_BLUETOOTH_SCAN,                  // BLUETOOTH_SCAN
            OP_USE_BIOMETRIC,                   // BIOMETRIC
            OP_ACTIVITY_RECOGNITION,            // ACTIVITY_RECOGNITION
            OP_SMS_FINANCIAL_TRANSACTIONS,      // SMS_FINANCIAL_TRANSACTIONS
            OP_READ_MEDIA_AUDIO,                // READ_MEDIA_AUDIO
            OP_WRITE_MEDIA_AUDIO,               // WRITE_MEDIA_AUDIO
            OP_READ_MEDIA_VIDEO,                // READ_MEDIA_VIDEO
            OP_WRITE_MEDIA_VIDEO,               // WRITE_MEDIA_VIDEO
            OP_READ_MEDIA_IMAGES,               // READ_MEDIA_IMAGES
            OP_WRITE_MEDIA_IMAGES,              // WRITE_MEDIA_IMAGES
            OP_LEGACY_STORAGE,                  // LEGACY_STORAGE
            OP_ACCESS_ACCESSIBILITY,            // ACCESS_ACCESSIBILITY
            OP_READ_DEVICE_IDENTIFIERS,         // READ_DEVICE_IDENTIFIERS
            OP_ACCESS_MEDIA_LOCATION,           // ACCESS_MEDIA_LOCATION
            OP_QUERY_ALL_PACKAGES,              // QUERY_ALL_PACKAGES
            OP_MANAGE_EXTERNAL_STORAGE,         // MANAGE_EXTERNAL_STORAGE
            OP_INTERACT_ACROSS_PROFILES,        //INTERACT_ACROSS_PROFILES
            OP_ACTIVATE_PLATFORM_VPN,           // ACTIVATE_PLATFORM_VPN
            OP_LOADER_USAGE_STATS,              // LOADER_USAGE_STATS
            OP_DEPRECATED_1,                    // deprecated
            OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED, //AUTO_REVOKE_PERMISSIONS_IF_UNUSED
            OP_AUTO_REVOKE_MANAGED_BY_INSTALLER, //OP_AUTO_REVOKE_MANAGED_BY_INSTALLER
            OP_NO_ISOLATED_STORAGE,             // NO_ISOLATED_STORAGE
            OP_PHONE_CALL_MICROPHONE,           // OP_PHONE_CALL_MICROPHONE
            OP_PHONE_CALL_CAMERA,               // OP_PHONE_CALL_CAMERA
            OP_RECORD_AUDIO_HOTWORD,            // RECORD_AUDIO_HOTWORD
            OP_MANAGE_ONGOING_CALLS,            // MANAGE_ONGOING_CALLS
            OP_MANAGE_CREDENTIALS,              // MANAGE_CREDENTIALS
            OP_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER, // USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER
            OP_RECORD_AUDIO_OUTPUT,             // RECORD_AUDIO_OUTPUT
            OP_SCHEDULE_EXACT_ALARM,            // SCHEDULE_EXACT_ALARM
            OP_FINE_LOCATION,                   // OP_FINE_LOCATION_SOURCE
            OP_COARSE_LOCATION,                 // OP_COARSE_LOCATION_SOURCE
            OP_MANAGE_MEDIA,                    // MANAGE_MEDIA
            OP_BLUETOOTH_CONNECT,               // OP_BLUETOOTH_CONNECT
            OP_UWB_RANGING,                     // OP_UWB_RANGING
            OP_ACTIVITY_RECOGNITION,            // OP_ACTIVITY_RECOGNITION_SOURCE
            OP_BLUETOOTH_ADVERTISE,             // OP_BLUETOOTH_ADVERTISE
            OP_RECORD_INCOMING_PHONE_AUDIO,     // OP_RECORD_INCOMING_PHONE_AUDIO
    };

    /**
     * This maps each operation to the public string constant for it.
     */
    private static String[] sOpToString = new String[]{
            OPSTR_COARSE_LOCATION,
            OPSTR_FINE_LOCATION,
            OPSTR_GPS,
            OPSTR_VIBRATE,
            OPSTR_READ_CONTACTS,
            OPSTR_WRITE_CONTACTS,
            OPSTR_READ_CALL_LOG,
            OPSTR_WRITE_CALL_LOG,
            OPSTR_READ_CALENDAR,
            OPSTR_WRITE_CALENDAR,
            OPSTR_WIFI_SCAN,
            OPSTR_POST_NOTIFICATION,
            OPSTR_NEIGHBORING_CELLS,
            OPSTR_CALL_PHONE,
            OPSTR_READ_SMS,
            OPSTR_WRITE_SMS,
            OPSTR_RECEIVE_SMS,
            OPSTR_RECEIVE_EMERGENCY_BROADCAST,
            OPSTR_RECEIVE_MMS,
            OPSTR_RECEIVE_WAP_PUSH,
            OPSTR_SEND_SMS,
            OPSTR_READ_ICC_SMS,
            OPSTR_WRITE_ICC_SMS,
            OPSTR_WRITE_SETTINGS,
            OPSTR_SYSTEM_ALERT_WINDOW,
            OPSTR_ACCESS_NOTIFICATIONS,
            OPSTR_CAMERA,
            OPSTR_RECORD_AUDIO,
            OPSTR_PLAY_AUDIO,
            OPSTR_READ_CLIPBOARD,
            OPSTR_WRITE_CLIPBOARD,
            OPSTR_TAKE_MEDIA_BUTTONS,
            OPSTR_TAKE_AUDIO_FOCUS,
            OPSTR_AUDIO_MASTER_VOLUME,
            OPSTR_AUDIO_VOICE_VOLUME,
            OPSTR_AUDIO_RING_VOLUME,
            OPSTR_AUDIO_MEDIA_VOLUME,
            OPSTR_AUDIO_ALARM_VOLUME,
            OPSTR_AUDIO_NOTIFICATION_VOLUME,
            OPSTR_AUDIO_BLUETOOTH_VOLUME,
            OPSTR_WAKE_LOCK,
            OPSTR_MONITOR_LOCATION,
            OPSTR_MONITOR_HIGH_POWER_LOCATION,
            OPSTR_GET_USAGE_STATS,
            OPSTR_MUTE_MICROPHONE,
            OPSTR_TOAST_WINDOW,
            OPSTR_PROJECT_MEDIA,
            OPSTR_ACTIVATE_VPN,
            OPSTR_WRITE_WALLPAPER,
            OPSTR_ASSIST_STRUCTURE,
            OPSTR_ASSIST_SCREENSHOT,
            OPSTR_READ_PHONE_STATE,
            OPSTR_ADD_VOICEMAIL,
            OPSTR_USE_SIP,
            OPSTR_PROCESS_OUTGOING_CALLS,
            OPSTR_USE_FINGERPRINT,
            OPSTR_BODY_SENSORS,
            OPSTR_READ_CELL_BROADCASTS,
            OPSTR_MOCK_LOCATION,
            OPSTR_READ_EXTERNAL_STORAGE,
            OPSTR_WRITE_EXTERNAL_STORAGE,
            OPSTR_TURN_SCREEN_ON,
            OPSTR_GET_ACCOUNTS,
            OPSTR_RUN_IN_BACKGROUND,
            OPSTR_AUDIO_ACCESSIBILITY_VOLUME,
            OPSTR_READ_PHONE_NUMBERS,
            OPSTR_REQUEST_INSTALL_PACKAGES,
            OPSTR_PICTURE_IN_PICTURE,
            OPSTR_INSTANT_APP_START_FOREGROUND,
            OPSTR_ANSWER_PHONE_CALLS,
            OPSTR_RUN_ANY_IN_BACKGROUND,
            OPSTR_CHANGE_WIFI_STATE,
            OPSTR_REQUEST_DELETE_PACKAGES,
            OPSTR_BIND_ACCESSIBILITY_SERVICE,
            OPSTR_ACCEPT_HANDOVER,
            OPSTR_MANAGE_IPSEC_TUNNELS,
            OPSTR_START_FOREGROUND,
            OPSTR_BLUETOOTH_SCAN,
            OPSTR_USE_BIOMETRIC,
            OPSTR_ACTIVITY_RECOGNITION,
            OPSTR_SMS_FINANCIAL_TRANSACTIONS,
            OPSTR_READ_MEDIA_AUDIO,
            OPSTR_WRITE_MEDIA_AUDIO,
            OPSTR_READ_MEDIA_VIDEO,
            OPSTR_WRITE_MEDIA_VIDEO,
            OPSTR_READ_MEDIA_IMAGES,
            OPSTR_WRITE_MEDIA_IMAGES,
            OPSTR_LEGACY_STORAGE,
            OPSTR_ACCESS_ACCESSIBILITY,
            OPSTR_READ_DEVICE_IDENTIFIERS,
            OPSTR_ACCESS_MEDIA_LOCATION,
            OPSTR_QUERY_ALL_PACKAGES,
            OPSTR_MANAGE_EXTERNAL_STORAGE,
            OPSTR_INTERACT_ACROSS_PROFILES,
            OPSTR_ACTIVATE_PLATFORM_VPN,
            OPSTR_LOADER_USAGE_STATS,
            "", // deprecated
            OPSTR_AUTO_REVOKE_PERMISSIONS_IF_UNUSED,
            OPSTR_AUTO_REVOKE_MANAGED_BY_INSTALLER,
            OPSTR_NO_ISOLATED_STORAGE,
            OPSTR_PHONE_CALL_MICROPHONE,
            OPSTR_PHONE_CALL_CAMERA,
            OPSTR_RECORD_AUDIO_HOTWORD,
            OPSTR_MANAGE_ONGOING_CALLS,
            OPSTR_MANAGE_CREDENTIALS,
            OPSTR_USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER,
            OPSTR_RECORD_AUDIO_OUTPUT,
            OPSTR_SCHEDULE_EXACT_ALARM,
            OPSTR_FINE_LOCATION_SOURCE,
            OPSTR_COARSE_LOCATION_SOURCE,
            OPSTR_MANAGE_MEDIA,
            OPSTR_BLUETOOTH_CONNECT,
            OPSTR_UWB_RANGING,
            OPSTR_ACTIVITY_RECOGNITION_SOURCE,
            OPSTR_BLUETOOTH_ADVERTISE,
            OPSTR_RECORD_INCOMING_PHONE_AUDIO,
    };

    /**
     * This provides a simple name for each operation to be used
     * in debug output.
     */
    private static String[] sOpNames = new String[] {
            "COARSE_LOCATION",
            "FINE_LOCATION",
            "GPS",
            "VIBRATE",
            "READ_CONTACTS",
            "WRITE_CONTACTS",
            "READ_CALL_LOG",
            "WRITE_CALL_LOG",
            "READ_CALENDAR",
            "WRITE_CALENDAR",
            "WIFI_SCAN",
            "POST_NOTIFICATION",
            "NEIGHBORING_CELLS",
            "CALL_PHONE",
            "READ_SMS",
            "WRITE_SMS",
            "RECEIVE_SMS",
            "RECEIVE_EMERGECY_SMS",
            "RECEIVE_MMS",
            "RECEIVE_WAP_PUSH",
            "SEND_SMS",
            "READ_ICC_SMS",
            "WRITE_ICC_SMS",
            "WRITE_SETTINGS",
            "SYSTEM_ALERT_WINDOW",
            "ACCESS_NOTIFICATIONS",
            "CAMERA",
            "RECORD_AUDIO",
            "PLAY_AUDIO",
            "READ_CLIPBOARD",
            "WRITE_CLIPBOARD",
            "TAKE_MEDIA_BUTTONS",
            "TAKE_AUDIO_FOCUS",
            "AUDIO_MASTER_VOLUME",
            "AUDIO_VOICE_VOLUME",
            "AUDIO_RING_VOLUME",
            "AUDIO_MEDIA_VOLUME",
            "AUDIO_ALARM_VOLUME",
            "AUDIO_NOTIFICATION_VOLUME",
            "AUDIO_BLUETOOTH_VOLUME",
            "WAKE_LOCK",
            "MONITOR_LOCATION",
            "MONITOR_HIGH_POWER_LOCATION",
            "GET_USAGE_STATS",
            "MUTE_MICROPHONE",
            "TOAST_WINDOW",
            "PROJECT_MEDIA",
            "ACTIVATE_VPN",
            "WRITE_WALLPAPER",
            "ASSIST_STRUCTURE",
            "ASSIST_SCREENSHOT",
            "READ_PHONE_STATE",
            "ADD_VOICEMAIL",
            "USE_SIP",
            "PROCESS_OUTGOING_CALLS",
            "USE_FINGERPRINT",
            "BODY_SENSORS",
            "READ_CELL_BROADCASTS",
            "MOCK_LOCATION",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "TURN_ON_SCREEN",
            "GET_ACCOUNTS",
            "RUN_IN_BACKGROUND",
            "AUDIO_ACCESSIBILITY_VOLUME",
            "READ_PHONE_NUMBERS",
            "REQUEST_INSTALL_PACKAGES",
            "PICTURE_IN_PICTURE",
            "INSTANT_APP_START_FOREGROUND",
            "ANSWER_PHONE_CALLS",
            "RUN_ANY_IN_BACKGROUND",
            "CHANGE_WIFI_STATE",
            "REQUEST_DELETE_PACKAGES",
            "BIND_ACCESSIBILITY_SERVICE",
            "ACCEPT_HANDOVER",
            "MANAGE_IPSEC_TUNNELS",
            "START_FOREGROUND",
            "BLUETOOTH_SCAN",
            "USE_BIOMETRIC",
            "ACTIVITY_RECOGNITION",
            "SMS_FINANCIAL_TRANSACTIONS",
            "READ_MEDIA_AUDIO",
            "WRITE_MEDIA_AUDIO",
            "READ_MEDIA_VIDEO",
            "WRITE_MEDIA_VIDEO",
            "READ_MEDIA_IMAGES",
            "WRITE_MEDIA_IMAGES",
            "LEGACY_STORAGE",
            "ACCESS_ACCESSIBILITY",
            "READ_DEVICE_IDENTIFIERS",
            "ACCESS_MEDIA_LOCATION",
            "QUERY_ALL_PACKAGES",
            "MANAGE_EXTERNAL_STORAGE",
            "INTERACT_ACROSS_PROFILES",
            "ACTIVATE_PLATFORM_VPN",
            "LOADER_USAGE_STATS",
            "deprecated",
            "AUTO_REVOKE_PERMISSIONS_IF_UNUSED",
            "AUTO_REVOKE_MANAGED_BY_INSTALLER",
            "NO_ISOLATED_STORAGE",
            "PHONE_CALL_MICROPHONE",
            "PHONE_CALL_CAMERA",
            "RECORD_AUDIO_HOTWORD",
            "MANAGE_ONGOING_CALLS",
            "MANAGE_CREDENTIALS",
            "USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER",
            "RECORD_AUDIO_OUTPUT",
            "SCHEDULE_EXACT_ALARM",
            "FINE_LOCATION_SOURCE",
            "COARSE_LOCATION_SOURCE",
            "MANAGE_MEDIA",
            "BLUETOOTH_CONNECT",
            "UWB_RANGING",
            "ACTIVITY_RECOGNITION_SOURCE",
            "BLUETOOTH_ADVERTISE",
            "RECORD_INCOMING_PHONE_AUDIO",
    };

    /**
     * This optionally maps a permission to an operation.  If there
     * is no permission associated with an operation, it is null.
     */
    @UnsupportedAppUsage
    private static String[] sOpPerms = new String[] {
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            null,
            android.Manifest.permission.VIBRATE,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.WRITE_CONTACTS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.WRITE_CALL_LOG,
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
            android.Manifest.permission.ACCESS_WIFI_STATE,
            null, // no permission required for notifications
            null, // neighboring cells shares the coarse location perm
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.READ_SMS,
            null, // no permission required for writing sms
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.RECEIVE_EMERGENCY_BROADCAST,
            android.Manifest.permission.RECEIVE_MMS,
            android.Manifest.permission.RECEIVE_WAP_PUSH,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_SMS,
            null, // no permission required for writing icc sms
            android.Manifest.permission.WRITE_SETTINGS,
            android.Manifest.permission.SYSTEM_ALERT_WINDOW,
            android.Manifest.permission.ACCESS_NOTIFICATIONS,
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.RECORD_AUDIO,
            null, // no permission for playing audio
            null, // no permission for reading clipboard
            null, // no permission for writing clipboard
            null, // no permission for taking media buttons
            null, // no permission for taking audio focus
            null, // no permission for changing global volume
            null, // no permission for changing voice volume
            null, // no permission for changing ring volume
            null, // no permission for changing media volume
            null, // no permission for changing alarm volume
            null, // no permission for changing notification volume
            null, // no permission for changing bluetooth volume
            android.Manifest.permission.WAKE_LOCK,
            null, // no permission for generic location monitoring
            null, // no permission for high power location monitoring
            android.Manifest.permission.PACKAGE_USAGE_STATS,
            null, // no permission for muting/unmuting microphone
            null, // no permission for displaying toasts
            null, // no permission for projecting media
            null, // no permission for activating vpn
            null, // no permission for supporting wallpaper
            null, // no permission for receiving assist structure
            null, // no permission for receiving assist screenshot
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ADD_VOICEMAIL,
            Manifest.permission.USE_SIP,
            Manifest.permission.PROCESS_OUTGOING_CALLS,
            Manifest.permission.USE_FINGERPRINT,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.READ_CELL_BROADCASTS,
            null,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            null, // no permission for turning the screen on
            Manifest.permission.GET_ACCOUNTS,
            null, // no permission for running in background
            null, // no permission for changing accessibility volume
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.REQUEST_INSTALL_PACKAGES,
            null, // no permission for entering picture-in-picture on hide
            Manifest.permission.INSTANT_APP_FOREGROUND_SERVICE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            null, // no permission for OP_RUN_ANY_IN_BACKGROUND
            Manifest.permission.CHANGE_WIFI_STATE,
            Manifest.permission.REQUEST_DELETE_PACKAGES,
            Manifest.permission.BIND_ACCESSIBILITY_SERVICE,
            Manifest.permission.ACCEPT_HANDOVER,
            Manifest.permission.MANAGE_IPSEC_TUNNELS,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.USE_BIOMETRIC,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.SMS_FINANCIAL_TRANSACTIONS,
            null,
            null, // no permission for OP_WRITE_MEDIA_AUDIO
            null,
            null, // no permission for OP_WRITE_MEDIA_VIDEO
            null,
            null, // no permission for OP_WRITE_MEDIA_IMAGES
            null, // no permission for OP_LEGACY_STORAGE
            null, // no permission for OP_ACCESS_ACCESSIBILITY
            null, // no direct permission for OP_READ_DEVICE_IDENTIFIERS
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            null, // no permission for OP_QUERY_ALL_PACKAGES
            Manifest.permission.MANAGE_EXTERNAL_STORAGE,
            android.Manifest.permission.INTERACT_ACROSS_PROFILES,
            null, // no permission for OP_ACTIVATE_PLATFORM_VPN
            android.Manifest.permission.LOADER_USAGE_STATS,
            null, // deprecated operation
            null, // no permission for OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED
            null, // no permission for OP_AUTO_REVOKE_MANAGED_BY_INSTALLER
            null, // no permission for OP_NO_ISOLATED_STORAGE
            null, // no permission for OP_PHONE_CALL_MICROPHONE
            null, // no permission for OP_PHONE_CALL_CAMERA
            null, // no permission for OP_RECORD_AUDIO_HOTWORD
            Manifest.permission.MANAGE_ONGOING_CALLS,
            null, // no permission for OP_MANAGE_CREDENTIALS
            Manifest.permission.USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER,
            null, // no permission for OP_RECORD_AUDIO_OUTPUT
            Manifest.permission.SCHEDULE_EXACT_ALARM,
            null, // no permission for OP_ACCESS_FINE_LOCATION_SOURCE,
            null, // no permission for OP_ACCESS_COARSE_LOCATION_SOURCE,
            Manifest.permission.MANAGE_MEDIA,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.UWB_RANGING,
            null, // no permission for OP_ACTIVITY_RECOGNITION_SOURCE,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            null, // no permission for OP_RECORD_INCOMING_PHONE_AUDIO,
    };

    /**
     * Specifies whether an Op should be restricted by a user restriction.
     * Each Op should be filled with a restriction string from UserManager or
     * null to specify it is not affected by any user restriction.
     */
    private static String[] sOpRestrictions = new String[] {
            UserManager.DISALLOW_SHARE_LOCATION, //COARSE_LOCATION
            UserManager.DISALLOW_SHARE_LOCATION, //FINE_LOCATION
            UserManager.DISALLOW_SHARE_LOCATION, //GPS
            null, //VIBRATE
            null, //READ_CONTACTS
            null, //WRITE_CONTACTS
            UserManager.DISALLOW_OUTGOING_CALLS, //READ_CALL_LOG
            UserManager.DISALLOW_OUTGOING_CALLS, //WRITE_CALL_LOG
            null, //READ_CALENDAR
            null, //WRITE_CALENDAR
            UserManager.DISALLOW_SHARE_LOCATION, //WIFI_SCAN
            null, //POST_NOTIFICATION
            null, //NEIGHBORING_CELLS
            null, //CALL_PHONE
            UserManager.DISALLOW_SMS, //READ_SMS
            UserManager.DISALLOW_SMS, //WRITE_SMS
            UserManager.DISALLOW_SMS, //RECEIVE_SMS
            null, //RECEIVE_EMERGENCY_SMS
            UserManager.DISALLOW_SMS, //RECEIVE_MMS
            null, //RECEIVE_WAP_PUSH
            UserManager.DISALLOW_SMS, //SEND_SMS
            UserManager.DISALLOW_SMS, //READ_ICC_SMS
            UserManager.DISALLOW_SMS, //WRITE_ICC_SMS
            null, //WRITE_SETTINGS
            UserManager.DISALLOW_CREATE_WINDOWS, //SYSTEM_ALERT_WINDOW
            null, //ACCESS_NOTIFICATIONS
            UserManager.DISALLOW_CAMERA, //CAMERA
            UserManager.DISALLOW_RECORD_AUDIO, //RECORD_AUDIO
            null, //PLAY_AUDIO
            null, //READ_CLIPBOARD
            null, //WRITE_CLIPBOARD
            null, //TAKE_MEDIA_BUTTONS
            null, //TAKE_AUDIO_FOCUS
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_MASTER_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_VOICE_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_RING_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_MEDIA_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_ALARM_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_NOTIFICATION_VOLUME
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_BLUETOOTH_VOLUME
            null, //WAKE_LOCK
            UserManager.DISALLOW_SHARE_LOCATION, //MONITOR_LOCATION
            UserManager.DISALLOW_SHARE_LOCATION, //MONITOR_HIGH_POWER_LOCATION
            null, //GET_USAGE_STATS
            UserManager.DISALLOW_UNMUTE_MICROPHONE, // MUTE_MICROPHONE
            UserManager.DISALLOW_CREATE_WINDOWS, // TOAST_WINDOW
            null, //PROJECT_MEDIA
            null, // ACTIVATE_VPN
            UserManager.DISALLOW_WALLPAPER, // WRITE_WALLPAPER
            null, // ASSIST_STRUCTURE
            null, // ASSIST_SCREENSHOT
            null, // READ_PHONE_STATE
            null, // ADD_VOICEMAIL
            null, // USE_SIP
            null, // PROCESS_OUTGOING_CALLS
            null, // USE_FINGERPRINT
            null, // BODY_SENSORS
            null, // READ_CELL_BROADCASTS
            null, // MOCK_LOCATION
            null, // READ_EXTERNAL_STORAGE
            null, // WRITE_EXTERNAL_STORAGE
            null, // TURN_ON_SCREEN
            null, // GET_ACCOUNTS
            null, // RUN_IN_BACKGROUND
            UserManager.DISALLOW_ADJUST_VOLUME, //AUDIO_ACCESSIBILITY_VOLUME
            null, // READ_PHONE_NUMBERS
            null, // REQUEST_INSTALL_PACKAGES
            null, // ENTER_PICTURE_IN_PICTURE_ON_HIDE
            null, // INSTANT_APP_START_FOREGROUND
            null, // ANSWER_PHONE_CALLS
            null, // OP_RUN_ANY_IN_BACKGROUND
            null, // OP_CHANGE_WIFI_STATE
            null, // REQUEST_DELETE_PACKAGES
            null, // OP_BIND_ACCESSIBILITY_SERVICE
            null, // ACCEPT_HANDOVER
            null, // MANAGE_IPSEC_TUNNELS
            null, // START_FOREGROUND
            null, // maybe should be UserManager.DISALLOW_SHARE_LOCATION, //BLUETOOTH_SCAN
            null, // USE_BIOMETRIC
            null, // ACTIVITY_RECOGNITION
            UserManager.DISALLOW_SMS, // SMS_FINANCIAL_TRANSACTIONS
            null, // READ_MEDIA_AUDIO
            null, // WRITE_MEDIA_AUDIO
            null, // READ_MEDIA_VIDEO
            null, // WRITE_MEDIA_VIDEO
            null, // READ_MEDIA_IMAGES
            null, // WRITE_MEDIA_IMAGES
            null, // LEGACY_STORAGE
            null, // ACCESS_ACCESSIBILITY
            null, // READ_DEVICE_IDENTIFIERS
            null, // ACCESS_MEDIA_LOCATION
            null, // QUERY_ALL_PACKAGES
            null, // MANAGE_EXTERNAL_STORAGE
            null, // INTERACT_ACROSS_PROFILES
            null, // ACTIVATE_PLATFORM_VPN
            null, // LOADER_USAGE_STATS
            null, // deprecated operation
            null, // AUTO_REVOKE_PERMISSIONS_IF_UNUSED
            null, // AUTO_REVOKE_MANAGED_BY_INSTALLER
            null, // NO_ISOLATED_STORAGE
            null, // PHONE_CALL_MICROPHONE
            null, // PHONE_CALL_MICROPHONE
            null, // RECORD_AUDIO_HOTWORD
            null, // MANAGE_ONGOING_CALLS
            null, // MANAGE_CREDENTIALS
            null, // USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER
            null, // RECORD_AUDIO_OUTPUT
            null, // SCHEDULE_EXACT_ALARM
            null, // ACCESS_FINE_LOCATION_SOURCE
            null, // ACCESS_COARSE_LOCATION_SOURCE
            null, // MANAGE_MEDIA
            null, // BLUETOOTH_CONNECT
            null, // UWB_RANGING
            null, // ACTIVITY_RECOGNITION_SOURCE
            null, // BLUETOOTH_ADVERTISE
            null, // RECORD_INCOMING_PHONE_AUDIO
    };

    /**
     * In which cases should an app be allowed to bypass the {@link #setUserRestriction user
     * restriction} for a certain app-op.
     */
    private static RestrictionBypass[] sOpAllowSystemRestrictionBypass = new RestrictionBypass[] {
            new RestrictionBypass(true, false), //COARSE_LOCATION
            new RestrictionBypass(true, false), //FINE_LOCATION
            null, //GPS
            null, //VIBRATE
            null, //READ_CONTACTS
            null, //WRITE_CONTACTS
            null, //READ_CALL_LOG
            null, //WRITE_CALL_LOG
            null, //READ_CALENDAR
            null, //WRITE_CALENDAR
            new RestrictionBypass(true, false), //WIFI_SCAN
            null, //POST_NOTIFICATION
            null, //NEIGHBORING_CELLS
            null, //CALL_PHONE
            null, //READ_SMS
            null, //WRITE_SMS
            null, //RECEIVE_SMS
            null, //RECEIVE_EMERGECY_SMS
            null, //RECEIVE_MMS
            null, //RECEIVE_WAP_PUSH
            null, //SEND_SMS
            null, //READ_ICC_SMS
            null, //WRITE_ICC_SMS
            null, //WRITE_SETTINGS
            new RestrictionBypass(true, false), //SYSTEM_ALERT_WINDOW
            null, //ACCESS_NOTIFICATIONS
            null, //CAMERA
            new RestrictionBypass(false, true), //RECORD_AUDIO
            null, //PLAY_AUDIO
            null, //READ_CLIPBOARD
            null, //WRITE_CLIPBOARD
            null, //TAKE_MEDIA_BUTTONS
            null, //TAKE_AUDIO_FOCUS
            null, //AUDIO_MASTER_VOLUME
            null, //AUDIO_VOICE_VOLUME
            null, //AUDIO_RING_VOLUME
            null, //AUDIO_MEDIA_VOLUME
            null, //AUDIO_ALARM_VOLUME
            null, //AUDIO_NOTIFICATION_VOLUME
            null, //AUDIO_BLUETOOTH_VOLUME
            null, //WAKE_LOCK
            null, //MONITOR_LOCATION
            null, //MONITOR_HIGH_POWER_LOCATION
            null, //GET_USAGE_STATS
            null, //MUTE_MICROPHONE
            new RestrictionBypass(true, false), //TOAST_WINDOW
            null, //PROJECT_MEDIA
            null, //ACTIVATE_VPN
            null, //WALLPAPER
            null, //ASSIST_STRUCTURE
            null, //ASSIST_SCREENSHOT
            null, //READ_PHONE_STATE
            null, //ADD_VOICEMAIL
            null, // USE_SIP
            null, // PROCESS_OUTGOING_CALLS
            null, // USE_FINGERPRINT
            null, // BODY_SENSORS
            null, // READ_CELL_BROADCASTS
            null, // MOCK_LOCATION
            null, // READ_EXTERNAL_STORAGE
            null, // WRITE_EXTERNAL_STORAGE
            null, // TURN_ON_SCREEN
            null, // GET_ACCOUNTS
            null, // RUN_IN_BACKGROUND
            null, // AUDIO_ACCESSIBILITY_VOLUME
            null, // READ_PHONE_NUMBERS
            null, // REQUEST_INSTALL_PACKAGES
            null, // ENTER_PICTURE_IN_PICTURE_ON_HIDE
            null, // INSTANT_APP_START_FOREGROUND
            null, // ANSWER_PHONE_CALLS
            null, // OP_RUN_ANY_IN_BACKGROUND
            null, // OP_CHANGE_WIFI_STATE
            null, // OP_REQUEST_DELETE_PACKAGES
            null, // OP_BIND_ACCESSIBILITY_SERVICE
            null, // ACCEPT_HANDOVER
            null, // MANAGE_IPSEC_HANDOVERS
            null, // START_FOREGROUND
            new RestrictionBypass(true, false), // BLUETOOTH_SCAN
            null, // USE_BIOMETRIC
            null, // ACTIVITY_RECOGNITION
            null, // SMS_FINANCIAL_TRANSACTIONS
            null, // READ_MEDIA_AUDIO
            null, // WRITE_MEDIA_AUDIO
            null, // READ_MEDIA_VIDEO
            null, // WRITE_MEDIA_VIDEO
            null, // READ_MEDIA_IMAGES
            null, // WRITE_MEDIA_IMAGES
            null, // LEGACY_STORAGE
            null, // ACCESS_ACCESSIBILITY
            null, // READ_DEVICE_IDENTIFIERS
            null, // ACCESS_MEDIA_LOCATION
            null, // QUERY_ALL_PACKAGES
            null, // MANAGE_EXTERNAL_STORAGE
            null, // INTERACT_ACROSS_PROFILES
            null, // ACTIVATE_PLATFORM_VPN
            null, // LOADER_USAGE_STATS
            null, // deprecated operation
            null, // AUTO_REVOKE_PERMISSIONS_IF_UNUSED
            null, // AUTO_REVOKE_MANAGED_BY_INSTALLER
            null, // NO_ISOLATED_STORAGE
            null, // PHONE_CALL_MICROPHONE
            null, // PHONE_CALL_CAMERA
            null, // RECORD_AUDIO_HOTWORD
            null, // MANAGE_ONGOING_CALLS
            null, // MANAGE_CREDENTIALS
            null, // USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER
            null, // RECORD_AUDIO_OUTPUT
            null, // SCHEDULE_EXACT_ALARM
            null, // ACCESS_FINE_LOCATION_SOURCE
            null, // ACCESS_COARSE_LOCATION_SOURCE
            null, // MANAGE_MEDIA
            null, // BLUETOOTH_CONNECT
            null, // UWB_RANGING
            null, // ACTIVITY_RECOGNITION_SOURCE
            null, // BLUETOOTH_ADVERTISE
            null, // RECORD_INCOMING_PHONE_AUDIO
    };

    /**
     * This specifies the default mode for each operation.
     */
    private static int[] sOpDefaultMode = new int[] {
            AppOpsManager.MODE_ALLOWED, // COARSE_LOCATION
            AppOpsManager.MODE_ALLOWED, // FINE_LOCATION
            AppOpsManager.MODE_ALLOWED, // GPS
            AppOpsManager.MODE_ALLOWED, // VIBRATE
            AppOpsManager.MODE_ALLOWED, // READ_CONTACTS
            AppOpsManager.MODE_ALLOWED, // WRITE_CONTACTS
            AppOpsManager.MODE_ALLOWED, // READ_CALL_LOG
            AppOpsManager.MODE_ALLOWED, // WRITE_CALL_LOG
            AppOpsManager.MODE_ALLOWED, // READ_CALENDAR
            AppOpsManager.MODE_ALLOWED, // WRITE_CALENDAR
            AppOpsManager.MODE_ALLOWED, // WIFI_SCAN
            AppOpsManager.MODE_ALLOWED, // POST_NOTIFICATION
            AppOpsManager.MODE_ALLOWED, // NEIGHBORING_CELLS
            AppOpsManager.MODE_ALLOWED, // CALL_PHONE
            AppOpsManager.MODE_ALLOWED, // READ_SMS
            AppOpsManager.MODE_IGNORED, // WRITE_SMS
            AppOpsManager.MODE_ALLOWED, // RECEIVE_SMS
            AppOpsManager.MODE_ALLOWED, // RECEIVE_EMERGENCY_BROADCAST
            AppOpsManager.MODE_ALLOWED, // RECEIVE_MMS
            AppOpsManager.MODE_ALLOWED, // RECEIVE_WAP_PUSH
            AppOpsManager.MODE_ALLOWED, // SEND_SMS
            AppOpsManager.MODE_ALLOWED, // READ_ICC_SMS
            AppOpsManager.MODE_ALLOWED, // WRITE_ICC_SMS
            AppOpsManager.MODE_DEFAULT, // WRITE_SETTINGS
            getSystemAlertWindowDefault(), // SYSTEM_ALERT_WINDOW
            AppOpsManager.MODE_ALLOWED, // ACCESS_NOTIFICATIONS
            AppOpsManager.MODE_ALLOWED, // CAMERA
            AppOpsManager.MODE_ALLOWED, // RECORD_AUDIO
            AppOpsManager.MODE_ALLOWED, // PLAY_AUDIO
            AppOpsManager.MODE_ALLOWED, // READ_CLIPBOARD
            AppOpsManager.MODE_ALLOWED, // WRITE_CLIPBOARD
            AppOpsManager.MODE_ALLOWED, // TAKE_MEDIA_BUTTONS
            AppOpsManager.MODE_ALLOWED, // TAKE_AUDIO_FOCUS
            AppOpsManager.MODE_ALLOWED, // AUDIO_MASTER_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_VOICE_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_RING_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_MEDIA_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_ALARM_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_NOTIFICATION_VOLUME
            AppOpsManager.MODE_ALLOWED, // AUDIO_BLUETOOTH_VOLUME
            AppOpsManager.MODE_ALLOWED, // WAKE_LOCK
            AppOpsManager.MODE_ALLOWED, // MONITOR_LOCATION
            AppOpsManager.MODE_ALLOWED, // MONITOR_HIGH_POWER_LOCATION
            AppOpsManager.MODE_DEFAULT, // GET_USAGE_STATS
            AppOpsManager.MODE_ALLOWED, // MUTE_MICROPHONE
            AppOpsManager.MODE_ALLOWED, // TOAST_WINDOW
            AppOpsManager.MODE_IGNORED, // PROJECT_MEDIA
            AppOpsManager.MODE_IGNORED, // ACTIVATE_VPN
            AppOpsManager.MODE_ALLOWED, // WRITE_WALLPAPER
            AppOpsManager.MODE_ALLOWED, // ASSIST_STRUCTURE
            AppOpsManager.MODE_ALLOWED, // ASSIST_SCREENSHOT
            AppOpsManager.MODE_ALLOWED, // READ_PHONE_STATE
            AppOpsManager.MODE_ALLOWED, // ADD_VOICEMAIL
            AppOpsManager.MODE_ALLOWED, // USE_SIP
            AppOpsManager.MODE_ALLOWED, // PROCESS_OUTGOING_CALLS
            AppOpsManager.MODE_ALLOWED, // USE_FINGERPRINT
            AppOpsManager.MODE_ALLOWED, // BODY_SENSORS
            AppOpsManager.MODE_ALLOWED, // READ_CELL_BROADCASTS
            AppOpsManager.MODE_ERRORED, // MOCK_LOCATION
            AppOpsManager.MODE_ALLOWED, // READ_EXTERNAL_STORAGE
            AppOpsManager.MODE_ALLOWED, // WRITE_EXTERNAL_STORAGE
            AppOpsManager.MODE_ALLOWED, // TURN_SCREEN_ON
            AppOpsManager.MODE_ALLOWED, // GET_ACCOUNTS
            AppOpsManager.MODE_ALLOWED, // RUN_IN_BACKGROUND
            AppOpsManager.MODE_ALLOWED, // AUDIO_ACCESSIBILITY_VOLUME
            AppOpsManager.MODE_ALLOWED, // READ_PHONE_NUMBERS
            AppOpsManager.MODE_DEFAULT, // REQUEST_INSTALL_PACKAGES
            AppOpsManager.MODE_ALLOWED, // PICTURE_IN_PICTURE
            AppOpsManager.MODE_DEFAULT, // INSTANT_APP_START_FOREGROUND
            AppOpsManager.MODE_ALLOWED, // ANSWER_PHONE_CALLS
            AppOpsManager.MODE_ALLOWED, // RUN_ANY_IN_BACKGROUND
            AppOpsManager.MODE_ALLOWED, // CHANGE_WIFI_STATE
            AppOpsManager.MODE_ALLOWED, // REQUEST_DELETE_PACKAGES
            AppOpsManager.MODE_ALLOWED, // BIND_ACCESSIBILITY_SERVICE
            AppOpsManager.MODE_ALLOWED, // ACCEPT_HANDOVER
            AppOpsManager.MODE_ERRORED, // MANAGE_IPSEC_TUNNELS
            AppOpsManager.MODE_ALLOWED, // START_FOREGROUND
            AppOpsManager.MODE_ALLOWED, // BLUETOOTH_SCAN
            AppOpsManager.MODE_ALLOWED, // USE_BIOMETRIC
            AppOpsManager.MODE_ALLOWED, // ACTIVITY_RECOGNITION
            AppOpsManager.MODE_DEFAULT, // SMS_FINANCIAL_TRANSACTIONS
            AppOpsManager.MODE_ALLOWED, // READ_MEDIA_AUDIO
            AppOpsManager.MODE_ERRORED, // WRITE_MEDIA_AUDIO
            AppOpsManager.MODE_ALLOWED, // READ_MEDIA_VIDEO
            AppOpsManager.MODE_ERRORED, // WRITE_MEDIA_VIDEO
            AppOpsManager.MODE_ALLOWED, // READ_MEDIA_IMAGES
            AppOpsManager.MODE_ERRORED, // WRITE_MEDIA_IMAGES
            AppOpsManager.MODE_DEFAULT, // LEGACY_STORAGE
            AppOpsManager.MODE_ALLOWED, // ACCESS_ACCESSIBILITY
            AppOpsManager.MODE_ERRORED, // READ_DEVICE_IDENTIFIERS
            AppOpsManager.MODE_ALLOWED, // ALLOW_MEDIA_LOCATION
            AppOpsManager.MODE_DEFAULT, // QUERY_ALL_PACKAGES
            AppOpsManager.MODE_DEFAULT, // MANAGE_EXTERNAL_STORAGE
            AppOpsManager.MODE_DEFAULT, // INTERACT_ACROSS_PROFILES
            AppOpsManager.MODE_IGNORED, // ACTIVATE_PLATFORM_VPN
            AppOpsManager.MODE_DEFAULT, // LOADER_USAGE_STATS
            AppOpsManager.MODE_IGNORED, // deprecated operation
            AppOpsManager.MODE_DEFAULT, // OP_AUTO_REVOKE_PERMISSIONS_IF_UNUSED
            AppOpsManager.MODE_ALLOWED, // OP_AUTO_REVOKE_MANAGED_BY_INSTALLER
            AppOpsManager.MODE_ERRORED, // OP_NO_ISOLATED_STORAGE
            AppOpsManager.MODE_ALLOWED, // PHONE_CALL_MICROPHONE
            AppOpsManager.MODE_ALLOWED, // PHONE_CALL_CAMERA
            AppOpsManager.MODE_ALLOWED, // OP_RECORD_AUDIO_HOTWORD
            AppOpsManager.MODE_DEFAULT, // MANAGE_ONGOING_CALLS
            AppOpsManager.MODE_DEFAULT, // MANAGE_CREDENTIALS
            AppOpsManager.MODE_DEFAULT, // USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER
            AppOpsManager.MODE_ALLOWED, // RECORD_AUDIO_OUTPUT
            AppOpsManager.MODE_DEFAULT, // SCHEDULE_EXACT_ALARM
            AppOpsManager.MODE_ALLOWED, // ACCESS_FINE_LOCATION_SOURCE
            AppOpsManager.MODE_ALLOWED, // ACCESS_COARSE_LOCATION_SOURCE
            AppOpsManager.MODE_DEFAULT, // MANAGE_MEDIA
            AppOpsManager.MODE_ALLOWED, // BLUETOOTH_CONNECT
            AppOpsManager.MODE_ALLOWED, // UWB_RANGING
            AppOpsManager.MODE_ALLOWED, // ACTIVITY_RECOGNITION_SOURCE
            AppOpsManager.MODE_ALLOWED, // BLUETOOTH_ADVERTISE
            AppOpsManager.MODE_ALLOWED, // RECORD_INCOMING_PHONE_AUDIO
    };

    /**
     * This specifies whether each option is allowed to be reset
     * when resetting all app preferences.  Disable reset for
     * app ops that are under strong control of some part of the
     * system (such as OP_WRITE_SMS, which should be allowed only
     * for whichever app is selected as the current SMS app).
     */
    private static boolean[] sOpDisableReset = new boolean[] {
            false, // COARSE_LOCATION
            false, // FINE_LOCATION
            false, // GPS
            false, // VIBRATE
            false, // READ_CONTACTS
            false, // WRITE_CONTACTS
            false, // READ_CALL_LOG
            false, // WRITE_CALL_LOG
            false, // READ_CALENDAR
            false, // WRITE_CALENDAR
            false, // WIFI_SCAN
            false, // POST_NOTIFICATION
            false, // NEIGHBORING_CELLS
            false, // CALL_PHONE
            true, // READ_SMS
            true, // WRITE_SMS
            true, // RECEIVE_SMS
            false, // RECEIVE_EMERGENCY_BROADCAST
            false, // RECEIVE_MMS
            true, // RECEIVE_WAP_PUSH
            true, // SEND_SMS
            false, // READ_ICC_SMS
            false, // WRITE_ICC_SMS
            false, // WRITE_SETTINGS
            false, // SYSTEM_ALERT_WINDOW
            false, // ACCESS_NOTIFICATIONS
            false, // CAMERA
            false, // RECORD_AUDIO
            false, // PLAY_AUDIO
            false, // READ_CLIPBOARD
            false, // WRITE_CLIPBOARD
            false, // TAKE_MEDIA_BUTTONS
            false, // TAKE_AUDIO_FOCUS
            false, // AUDIO_MASTER_VOLUME
            false, // AUDIO_VOICE_VOLUME
            false, // AUDIO_RING_VOLUME
            false, // AUDIO_MEDIA_VOLUME
            false, // AUDIO_ALARM_VOLUME
            false, // AUDIO_NOTIFICATION_VOLUME
            false, // AUDIO_BLUETOOTH_VOLUME
            false, // WAKE_LOCK
            false, // MONITOR_LOCATION
            false, // MONITOR_HIGH_POWER_LOCATION
            false, // GET_USAGE_STATS
            false, // MUTE_MICROPHONE
            false, // TOAST_WINDOW
            false, // PROJECT_MEDIA
            false, // ACTIVATE_VPN
            false, // WRITE_WALLPAPER
            false, // ASSIST_STRUCTURE
            false, // ASSIST_SCREENSHOT
            false, // READ_PHONE_STATE
            false, // ADD_VOICEMAIL
            false, // USE_SIP
            false, // PROCESS_OUTGOING_CALLS
            false, // USE_FINGERPRINT
            false, // BODY_SENSORS
            true, // READ_CELL_BROADCASTS
            false, // MOCK_LOCATION
            false, // READ_EXTERNAL_STORAGE
            false, // WRITE_EXTERNAL_STORAGE
            false, // TURN_SCREEN_ON
            false, // GET_ACCOUNTS
            false, // RUN_IN_BACKGROUND
            false, // AUDIO_ACCESSIBILITY_VOLUME
            false, // READ_PHONE_NUMBERS
            false, // REQUEST_INSTALL_PACKAGES
            false, // PICTURE_IN_PICTURE
            false, // INSTANT_APP_START_FOREGROUND
            false, // ANSWER_PHONE_CALLS
            false, // RUN_ANY_IN_BACKGROUND
            false, // CHANGE_WIFI_STATE
            false, // REQUEST_DELETE_PACKAGES
            false, // BIND_ACCESSIBILITY_SERVICE
            false, // ACCEPT_HANDOVER
            false, // MANAGE_IPSEC_TUNNELS
            false, // START_FOREGROUND
            false, // BLUETOOTH_SCAN
            false, // USE_BIOMETRIC
            false, // ACTIVITY_RECOGNITION
            false, // SMS_FINANCIAL_TRANSACTIONS
            false, // READ_MEDIA_AUDIO
            false, // WRITE_MEDIA_AUDIO
            false, // READ_MEDIA_VIDEO
            true,  // WRITE_MEDIA_VIDEO
            false, // READ_MEDIA_IMAGES
            true,  // WRITE_MEDIA_IMAGES
            true,  // LEGACY_STORAGE
            false, // ACCESS_ACCESSIBILITY
            false, // READ_DEVICE_IDENTIFIERS
            false, // ACCESS_MEDIA_LOCATION
            false, // QUERY_ALL_PACKAGES
            false, // MANAGE_EXTERNAL_STORAGE
            false, // INTERACT_ACROSS_PROFILES
            false, // ACTIVATE_PLATFORM_VPN
            false, // LOADER_USAGE_STATS
            false, // deprecated operation
            false, // AUTO_REVOKE_PERMISSIONS_IF_UNUSED
            false, // AUTO_REVOKE_MANAGED_BY_INSTALLER
            true, // NO_ISOLATED_STORAGE
            false, // PHONE_CALL_MICROPHONE
            false, // PHONE_CALL_CAMERA
            false, // RECORD_AUDIO_HOTWORD
            true, // MANAGE_ONGOING_CALLS
            false, // MANAGE_CREDENTIALS
            true, // USE_ICC_AUTH_WITH_DEVICE_IDENTIFIER
            false, // RECORD_AUDIO_OUTPUT
            false, // SCHEDULE_EXACT_ALARM
            false, // ACCESS_FINE_LOCATION_SOURCE
            false, // ACCESS_COARSE_LOCATION_SOURCE
            false, // MANAGE_MEDIA
            false, // BLUETOOTH_CONNECT
            false, // UWB_RANGING
            false, // ACTIVITY_RECOGNITION_SOURCE
            false, // BLUETOOTH_ADVERTISE
            false, // RECORD_INCOMING_PHONE_AUDIO
    };

    /**
     * Mapping from an app op name to the app op code.
     */
    private static HashMap<String, Integer> sOpStrToOp = new HashMap<>();

    /**
     * Mapping from a permission to the corresponding app op.
     */
    private static HashMap<String, Integer> sPermToOp = new HashMap<>();

    /**
     * Set to the uid of the caller if this thread is currently executing a two-way binder
     * transaction. Not set if this thread is currently not executing a two way binder transaction.
     *
     * @see #startNotedAppOpsCollection
     * @see #getNotedOpCollectionMode
     */
    private static final ThreadLocal<Integer> sBinderThreadCallingUid = new ThreadLocal<>();

    /**
     * Optimization: we need to propagate to IPCs whether the current thread is collecting
     * app ops but using only the thread local above is too slow as it requires a map lookup
     * on every IPC. We add this static var that is lockless and stores an OR-ed mask of the
     * thread id's currently collecting ops, thus reducing the map lookup to a simple bit
     * operation except the extremely unlikely case when threads with overlapping id bits
     * execute op collecting ops.
     */
    private static volatile long sThreadsListeningForOpNotedInBinderTransaction = 0L;

    /**
     * If a thread is currently executing a two-way binder transaction, this stores the
     * ops that were noted blaming any app (the caller, the caller of the caller, etc).
     *
     * @see #getNotedOpCollectionMode
     * @see #collectNotedOpSync
     */
    private static final ThreadLocal<ArrayMap<String, ArrayMap<String, long[]>>>
            sAppOpsNotedInThisBinderTransaction = new ThreadLocal<>();

    /** Whether noting for an appop should be collected */
    private static final @ShouldCollectNoteOp byte[] sAppOpsToNote = new byte[_NUM_OP];

    static {
        if (sOpToSwitch.length != _NUM_OP) {
            throw new IllegalStateException("sOpToSwitch length " + sOpToSwitch.length
                    + " should be " + _NUM_OP);
        }
        if (sOpToString.length != _NUM_OP) {
            throw new IllegalStateException("sOpToString length " + sOpToString.length
                    + " should be " + _NUM_OP);
        }
        if (sOpNames.length != _NUM_OP) {
            throw new IllegalStateException("sOpNames length " + sOpNames.length
                    + " should be " + _NUM_OP);
        }
        if (sOpPerms.length != _NUM_OP) {
            throw new IllegalStateException("sOpPerms length " + sOpPerms.length
                    + " should be " + _NUM_OP);
        }
        if (sOpDefaultMode.length != _NUM_OP) {
            throw new IllegalStateException("sOpDefaultMode length " + sOpDefaultMode.length
                    + " should be " + _NUM_OP);
        }
        if (sOpDisableReset.length != _NUM_OP) {
            throw new IllegalStateException("sOpDisableReset length " + sOpDisableReset.length
                    + " should be " + _NUM_OP);
        }
        if (sOpRestrictions.length != _NUM_OP) {
            throw new IllegalStateException("sOpRestrictions length " + sOpRestrictions.length
                    + " should be " + _NUM_OP);
        }
        if (sOpAllowSystemRestrictionBypass.length != _NUM_OP) {
            throw new IllegalStateException("sOpAllowSYstemRestrictionsBypass length "
                    + sOpRestrictions.length + " should be " + _NUM_OP);
        }
        for (int i=0; i<_NUM_OP; i++) {
            if (sOpToString[i] != null) {
                sOpStrToOp.put(sOpToString[i], i);
            }
        }
        for (int op : RUNTIME_AND_APPOP_PERMISSIONS_OPS) {
            if (sOpPerms[op] != null) {
                sPermToOp.put(sOpPerms[op], op);
            }
        }

        if ((_NUM_OP + Long.SIZE - 1) / Long.SIZE != 2) {
            // The code currently assumes that the length of sAppOpsNotedInThisBinderTransaction is
            // two longs
            throw new IllegalStateException("notedAppOps collection code assumes < 128 appops");
        }
    }

    /** Config used to control app ops access messages sampling */
    private static MessageSamplingConfig sConfig =
            new MessageSamplingConfig(OP_NONE, 0, 0);

    /** @hide */
    public static final String KEY_HISTORICAL_OPS = "historical_ops";

    /** System properties for debug logging of noteOp call sites */
    private static final String DEBUG_LOGGING_ENABLE_PROP = "appops.logging_enabled";
    private static final String DEBUG_LOGGING_PACKAGES_PROP = "appops.logging_packages";
    private static final String DEBUG_LOGGING_OPS_PROP = "appops.logging_ops";
    private static final String DEBUG_LOGGING_TAG = "AppOpsManager";

    /**
     * Retrieve the op switch that controls the given operation.
     * @hide
     */
    @UnsupportedAppUsage
    public static int opToSwitch(int op) {
        return sOpToSwitch[op];
    }

    /**
     * Retrieve a non-localized name for the operation, for debugging output.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public static String opToName(int op) {
        if (op == OP_NONE) return "NONE";
        return op < sOpNames.length ? sOpNames[op] : ("Unknown(" + op + ")");
    }

    /**
     * Retrieve a non-localized public name for the operation.
     *
     * @hide
     */
    public static @NonNull String opToPublicName(int op) {
        return sOpToString[op];
    }

    /**
     * @hide
     */
    public static int strDebugOpToOp(String op) {
        for (int i=0; i<sOpNames.length; i++) {
            if (sOpNames[i].equals(op)) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unknown operation string: " + op);
    }

    /**
     * Retrieve the permission associated with an operation, or null if there is not one.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @TestApi
    public static String opToPermission(int op) {
        return sOpPerms[op];
    }

    /**
     * Retrieve the permission associated with an operation, or null if there is not one.
     *
     * @param op The operation name.
     *
     * @hide
     */
    @Nullable
    @SystemApi
    public static String opToPermission(@NonNull String op) {
        return opToPermission(strOpToOp(op));
    }

    /**
     * Retrieve the user restriction associated with an operation, or null if there is not one.
     * @hide
     */
    public static String opToRestriction(int op) {
        return sOpRestrictions[op];
    }

    /**
     * Retrieve the app op code for a permission, or null if there is not one.
     * This API is intended to be used for mapping runtime or appop permissions
     * to the corresponding app op.
     * @hide
     */
    @UnsupportedAppUsage
    @TestApi
    public static int permissionToOpCode(String permission) {
        Integer boxedOpCode = sPermToOp.get(permission);
        return boxedOpCode != null ? boxedOpCode : OP_NONE;
    }

    /**
     * Retrieve whether the op allows to bypass the user restriction.
     *
     * @hide
     */
    public static RestrictionBypass opAllowSystemBypassRestriction(int op) {
        return sOpAllowSystemRestrictionBypass[op];
    }

    /**
     * Retrieve the default mode for the operation.
     * @hide
     */
    public static @Mode int opToDefaultMode(int op) {
        return sOpDefaultMode[op];
    }

    /**
     * Retrieve the default mode for the app op.
     *
     * @param appOp The app op name
     *
     * @return the default mode for the app op
     *
     * @hide
     */
    @SystemApi
    public static int opToDefaultMode(@NonNull String appOp) {
        return opToDefaultMode(strOpToOp(appOp));
    }

    /**
     * Retrieve the human readable mode.
     * @hide
     */
    public static String modeToName(@Mode int mode) {
        if (mode >= 0 && mode < MODE_NAMES.length) {
            return MODE_NAMES[mode];
        }
        return "mode=" + mode;
    }

    /**
     * Retrieve whether the op allows itself to be reset.
     * @hide
     */
    public static boolean opAllowsReset(int op) {
        return !sOpDisableReset[op];
    }

    /**
     * Returns a listenerId suitable for use with {@link #noteOp(int, int, String, String, String)}.
     *
     * This is intended for use client side, when the receiver id must be created before the
     * associated call is made to the system server. If using {@link PendingIntent} as the receiver,
     * avoid using this method as it will include a pointless additional x-process call. Instead
     * prefer passing the PendingIntent to the system server, and then invoking
     * {@link #toReceiverId(PendingIntent)}.
     *
     * @param obj the receiver in use
     * @return a string representation of the receiver suitable for app ops use
     * @hide
     */
    // TODO: this should probably be @SystemApi as well
    public static @NonNull String toReceiverId(@Nullable Object obj) {
        if (obj == null) {
            return "null";
        } else if (obj instanceof PendingIntent) {
            return toReceiverId((PendingIntent) obj);
        } else {
            return obj.getClass().getName() + "@" + System.identityHashCode(obj);
        }
    }

    /**
     * Returns a listenerId suitable for use with {@link #noteOp(int, int, String, String, String)}.
     *
     * This is intended for use server side, where ActivityManagerService can be referenced without
     * an additional x-process call.
     *
     * @param pendingIntent the pendingIntent in use
     * @return a string representation of the pending intent suitable for app ops use
     * @see #toReceiverId(Object)
     * @hide
     */
    // TODO: this should probably be @SystemApi as well
    public static @NonNull String toReceiverId(@NonNull PendingIntent pendingIntent) {
        return pendingIntent.getTag("");
    }

    /**
     * When to not enforce {@link #setUserRestriction restrictions}.
     *
     * @hide
     */
    public static class RestrictionBypass {
        /** Does the app need to be privileged to bypass the restriction */
        public boolean isPrivileged;

        /**
         * Does the app need to have the EXEMPT_FROM_AUDIO_RESTRICTIONS permission to bypass the
         * restriction
         */
        public boolean isRecordAudioRestrictionExcept;

        public RestrictionBypass(boolean isPrivileged, boolean isRecordAudioRestrictionExcept) {
            this.isPrivileged = isPrivileged;
            this.isRecordAudioRestrictionExcept = isRecordAudioRestrictionExcept;
        }

        public static RestrictionBypass UNRESTRICTED = new RestrictionBypass(true, true);
    }

    /**
     * Class holding all of the operation information associated with an app.
     * @hide
     */
    @SystemApi
    public static final class PackageOps implements Parcelable {
        private final String mPackageName;
        private final int mUid;
        private final List<OpEntry> mEntries;

        /**
         * @hide
         */
        @UnsupportedAppUsage
        public PackageOps(String packageName, int uid, List<OpEntry> entries) {
            mPackageName = packageName;
            mUid = uid;
            mEntries = entries;
        }

        /**
         * @return The name of the package.
         */
        public @NonNull String getPackageName() {
            return mPackageName;
        }

        /**
         * @return The uid of the package.
         */
        public int getUid() {
            return mUid;
        }

        /**
         * @return The ops of the package.
         */
        public @NonNull List<OpEntry> getOps() {
            return mEntries;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeString(mPackageName);
            dest.writeInt(mUid);
            dest.writeInt(mEntries.size());
            for (int i=0; i<mEntries.size(); i++) {
                mEntries.get(i).writeToParcel(dest, flags);
            }
        }

        PackageOps(Parcel source) {
            mPackageName = source.readString();
            mUid = source.readInt();
            mEntries = new ArrayList<OpEntry>();
            final int N = source.readInt();
            for (int i=0; i<N; i++) {
                mEntries.add(OpEntry.CREATOR.createFromParcel(source));
            }
        }

        public static final @android.annotation.NonNull Creator<PackageOps> CREATOR = new Creator<PackageOps>() {
            @Override public PackageOps createFromParcel(Parcel source) {
                return new PackageOps(source);
            }

            @Override public PackageOps[] newArray(int size) {
                return new PackageOps[size];
            }
        };
    }

    /**
     * Proxy information for a {@link #noteOp} event
     *
     * @hide
     */
    @SystemApi
    // @DataClass(genHiddenConstructor = true, genHiddenCopyConstructor = true)
    // genHiddenCopyConstructor does not work for @hide @SystemApi classes
    public static final class OpEventProxyInfo implements Parcelable {
        /** UID of the proxy app that noted the op */
        private @IntRange(from = 0) int mUid;
        /** Package of the proxy that noted the op */
        private @Nullable String mPackageName;
        /** Attribution tag of the proxy that noted the op */
        private @Nullable String mAttributionTag;

        /**
         * Reinit existing object with new state.
         *
         * @param uid UID of the proxy app that noted the op
         * @param packageName Package of the proxy that noted the op
         * @param attributionTag attribution tag of the proxy that noted the op
         *
         * @hide
         */
        public void reinit(@IntRange(from = 0) int uid, @Nullable String packageName,
                @Nullable String attributionTag) {
            mUid = Preconditions.checkArgumentNonnegative(uid);
            mPackageName = packageName;
            mAttributionTag = attributionTag;
        }



        // Code below generated by codegen v1.0.14.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/app/AppOpsManager.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        /**
         * Creates a new OpEventProxyInfo.
         *
         * @param uid
         *   UID of the proxy app that noted the op
         * @param packageName
         *   Package of the proxy that noted the op
         * @param attributionTag
         *   Attribution tag of the proxy that noted the op
         * @hide
         */
        @DataClass.Generated.Member
        public OpEventProxyInfo(
                @IntRange(from = 0) int uid,
                @Nullable String packageName,
                @Nullable String attributionTag) {
            this.mUid = uid;
            com.android.internal.util.AnnotationValidations.validate(
                    IntRange.class, null, mUid,
                    "from", 0);
            this.mPackageName = packageName;
            this.mAttributionTag = attributionTag;

            // onConstructed(); // You can define this method to get a callback
        }

        /**
         * Copy constructor
         *
         * @hide
         */
        @DataClass.Generated.Member
        public OpEventProxyInfo(@NonNull OpEventProxyInfo orig) {
            mUid = orig.mUid;
            mPackageName = orig.mPackageName;
            mAttributionTag = orig.mAttributionTag;
        }

        /**
         * UID of the proxy app that noted the op
         */
        @DataClass.Generated.Member
        public @IntRange(from = 0) int getUid() {
            return mUid;
        }

        /**
         * Package of the proxy that noted the op
         */
        @DataClass.Generated.Member
        public @Nullable String getPackageName() {
            return mPackageName;
        }

        /**
         * Attribution tag of the proxy that noted the op
         */
        @DataClass.Generated.Member
        public @Nullable String getAttributionTag() {
            return mAttributionTag;
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mPackageName != null) flg |= 0x2;
            if (mAttributionTag != null) flg |= 0x4;
            dest.writeByte(flg);
            dest.writeInt(mUid);
            if (mPackageName != null) dest.writeString(mPackageName);
            if (mAttributionTag != null) dest.writeString(mAttributionTag);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ OpEventProxyInfo(@NonNull Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            int uid = in.readInt();
            String packageName = (flg & 0x2) == 0 ? null : in.readString();
            String attributionTag = (flg & 0x4) == 0 ? null : in.readString();

            this.mUid = uid;
            com.android.internal.util.AnnotationValidations.validate(
                    IntRange.class, null, mUid,
                    "from", 0);
            this.mPackageName = packageName;
            this.mAttributionTag = attributionTag;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @NonNull Parcelable.Creator<OpEventProxyInfo> CREATOR
                = new Parcelable.Creator<OpEventProxyInfo>() {
            @Override
            public OpEventProxyInfo[] newArray(int size) {
                return new OpEventProxyInfo[size];
            }

            @Override
            public OpEventProxyInfo createFromParcel(@NonNull Parcel in) {
                return new OpEventProxyInfo(in);
            }
        };

        /*
        @DataClass.Generated(
                time = 1576814974615L,
                codegenVersion = "1.0.14",
                sourceFile = "frameworks/base/core/java/android/app/AppOpsManager.java",
                inputSignatures = "private @android.annotation.IntRange(from=0L) int mUid\nprivate @android.annotation.Nullable java.lang.String mPackageName\nprivate @android.annotation.Nullable java.lang.String mAttributionTag\npublic  void reinit(int,java.lang.String,java.lang.String)\nclass OpEventProxyInfo extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genHiddenConstructor=true, genHiddenCopyConstructor=true)")
        @Deprecated
        private void __metadata() {}
        */

        //@formatter:on
        // End of generated code

    }

    /**
     * Description of a {@link #noteOp} or {@link #startOp} event
     *
     * @hide
     */
    //@DataClass codegen verifier is broken
    public static final class NoteOpEvent implements Parcelable {
        /** Time of noteOp event */
        private @IntRange(from = 0) long mNoteTime;
        /** The duration of this event (in case this is a startOp event, -1 otherwise). */
        private @IntRange(from = -1) long mDuration;
        /** Proxy information of the noteOp event */
        private @Nullable OpEventProxyInfo mProxy;

        /**
         * Reinit existing object with new state.
         *
         * @param noteTime Time of noteOp event
         * @param duration The duration of this event (in case this is a startOp event,
         *                 -1 otherwise).
         * @param proxy Proxy information of the noteOp event
         * @param proxyPool  The pool to release previous {@link OpEventProxyInfo} to
         */
        public void reinit(@IntRange(from = 0) long noteTime,
                @IntRange(from = -1) long duration,
                @Nullable OpEventProxyInfo proxy,
                @NonNull Pools.Pool<OpEventProxyInfo> proxyPool) {
            mNoteTime = Preconditions.checkArgumentNonnegative(noteTime);
            mDuration = Preconditions.checkArgumentInRange(duration, -1L, Long.MAX_VALUE,
                    "duration");

            if (mProxy != null) {
                proxyPool.release(mProxy);
            }
            mProxy = proxy;
        }

        /**
         * Copy constructor
         *
         * @hide
         */
        public NoteOpEvent(@NonNull NoteOpEvent original) {
            this(original.mNoteTime, original.mDuration,
                    original.mProxy != null ? new OpEventProxyInfo(original.mProxy) : null);
        }



        // Code below generated by codegen v1.0.14.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/app/AppOpsManager.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        /**
         * Creates a new NoteOpEvent.
         *
         * @param noteTime
         *   Time of noteOp event
         * @param duration
         *   The duration of this event (in case this is a startOp event, -1 otherwise).
         * @param proxy
         *   Proxy information of the noteOp event
         */
        @DataClass.Generated.Member
        public NoteOpEvent(
                @IntRange(from = 0) long noteTime,
                @IntRange(from = -1) long duration,
                @Nullable OpEventProxyInfo proxy) {
            this.mNoteTime = noteTime;
            com.android.internal.util.AnnotationValidations.validate(
                    IntRange.class, null, mNoteTime,
                    "from", 0);
            this.mDuration = duration;
            com.android.internal.util.AnnotationValidations.validate(
                    IntRange.class, null, mDuration,
                    "from", -1);
            this.mProxy = proxy;

            // onConstructed(); // You can define this method to get a callback
        }

        /**
         * Time of noteOp event
         */
        @DataClass.Generated.Member
        public @IntRange(from = 0) long getNoteTime() {
            return mNoteTime;
        }

        /**
         * The duration of this event (in case this is a startOp event, -1 otherwise).
         */
        @DataClass.Generated.Member
        public @IntRange(from = -1) long getDuration() {
            return mDuration;
        }

        /**
         * Proxy information of the noteOp event
         */
        @DataClass.Generated.Member
        public @Nullable OpEventProxyInfo getProxy() {
            return mProxy;
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mProxy != null) flg |= 0x4;
            dest.writeByte(flg);
            dest.writeLong(mNoteTime);
            dest.writeLong(mDuration);
            if (mProxy != null) dest.writeTypedObject(mProxy, flags);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ NoteOpEvent(@NonNull Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            long noteTime = in.readLong();
            long duration = in.readLong();
            OpEventProxyInfo proxy = (flg & 0x4) == 0 ? null : (OpEventProxyInfo) in.readTypedObject(OpEventProxyInfo.CREATOR);

            this.mNoteTime = noteTime;
            com.android.internal.util.AnnotationValidations.validate(
                    IntRange.class, null, mNoteTime,
                    "from", 0);
            this.mDuration = duration;
            com.android.internal.util.AnnotationValidations.validate(
                    IntRange.class, null, mDuration,
                    "from", -1);
            this.mProxy = proxy;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @NonNull Parcelable.Creator<NoteOpEvent> CREATOR
                = new Parcelable.Creator<NoteOpEvent>() {
            @Override
            public NoteOpEvent[] newArray(int size) {
                return new NoteOpEvent[size];
            }

            @Override
            public NoteOpEvent createFromParcel(@NonNull Parcel in) {
                return new NoteOpEvent(in);
            }
        };

        /*
        @DataClass.Generated(
                time = 1576811792274L,
                codegenVersion = "1.0.14",
                sourceFile = "frameworks/base/core/java/android/app/AppOpsManager.java",
                inputSignatures = "private @android.annotation.IntRange(from=0L) long mNoteTime\nprivate @android.annotation.IntRange(from=-1) long mDuration\nprivate @android.annotation.Nullable android.app.OpEventProxyInfo mProxy\npublic  void reinit(long,long,android.app.OpEventProxyInfo,android.util.Pools.Pool<android.app.OpEventProxyInfo>)\npublic @java.lang.Override java.lang.Object clone()\nclass NoteOpEvent extends java.lang.Object implements [android.os.Parcelable, java.lang.Cloneable]\n@com.android.internal.util.DataClass")
        @Deprecated
        private void __metadata() {}
         */


        //@formatter:on
        // End of generated code

    }

    /**
     * Last {@link #noteOp} and {@link #startOp} events performed for a single op and a specific
     * {@link Context#createAttributionContext(String) attribution} for all uidModes and opFlags.
     *
     * @hide
     */
    @SystemApi
    @Immutable
    // @DataClass(genHiddenConstructor = true) codegen verifier is broken
    @DataClass.Suppress({"getAccessEvents", "getRejectEvents", "getOp"})
    public static final class AttributedOpEntry implements Parcelable {
        /** The code of the op */
        private final @IntRange(from = 0, to = _NUM_OP - 1) int mOp;
        /** Whether the op is running */
        private final boolean mRunning;
        /** The access events */
        @DataClass.ParcelWith(LongSparseArrayParceling.class)
        private final @Nullable LongSparseArray<NoteOpEvent> mAccessEvents;
        /** The rejection events */
        @DataClass.ParcelWith(LongSparseArrayParceling.class)
        private final @Nullable LongSparseArray<NoteOpEvent> mRejectEvents;

        private AttributedOpEntry(@NonNull AttributedOpEntry other) {
            mOp = other.mOp;
            mRunning = other.mRunning;
            mAccessEvents = other.mAccessEvents == null ? null : other.mAccessEvents.clone();
            mRejectEvents = other.mRejectEvents == null ? null : other.mRejectEvents.clone();
        }

        /**
         * Returns all keys for which we have events.
         *
         * @hide
         */
        public @NonNull ArraySet<Long> collectKeys() {
            ArraySet<Long> keys = new ArraySet<>();

            if (mAccessEvents != null) {
                int numEvents = mAccessEvents.size();
                for (int i = 0; i < numEvents; i++) {
                    keys.add(mAccessEvents.keyAt(i));
                }
            }

            if (mRejectEvents != null) {
                int numEvents = mRejectEvents.size();
                for (int i = 0; i < numEvents; i++) {
                    keys.add(mRejectEvents.keyAt(i));
                }
            }

            return keys;
        }

        /**
         * Return the last access time.
         *
         * @param flags The op flags
         *
         * @return the last access time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no access
         *
         * @see #getLastAccessForegroundTime(int)
         * @see #getLastAccessBackgroundTime(int)
         * @see #getLastAccessTime(int, int, int)
         * @see OpEntry#getLastAccessTime(int)
         */
        public long getLastAccessTime(@OpFlags int flags) {
            return getLastAccessTime(MAX_PRIORITY_UID_STATE, MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Return the last foreground access time.
         *
         * @param flags The op flags
         *
         * @return the last access time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no foreground access
         *
         * @see #getLastAccessTime(int)
         * @see #getLastAccessBackgroundTime(int)
         * @see #getLastAccessTime(int, int, int)
         * @see OpEntry#getLastAccessForegroundTime(int)
         */
        public long getLastAccessForegroundTime(@OpFlags int flags) {
            return getLastAccessTime(MAX_PRIORITY_UID_STATE, resolveFirstUnrestrictedUidState(mOp),
                    flags);
        }

        /**
         * Return the last background access time.
         *
         * @param flags The op flags
         *
         * @return the last access time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no background access
         *
         * @see #getLastAccessTime(int)
         * @see #getLastAccessForegroundTime(int)
         * @see #getLastAccessTime(int, int, int)
         * @see OpEntry#getLastAccessBackgroundTime(int)
         */
        public long getLastAccessBackgroundTime(@OpFlags int flags) {
            return getLastAccessTime(resolveLastRestrictedUidState(mOp), MIN_PRIORITY_UID_STATE,
                    flags);
        }

        /**
         * Return the last access event.
         *
         * @param flags The op flags
         *
         * @return the last access event of {@code null} if there was no access
         */
        private @Nullable NoteOpEvent getLastAccessEvent(@UidState int fromUidState,
                @UidState int toUidState, @OpFlags int flags) {
            return getLastEvent(mAccessEvents, fromUidState, toUidState, flags);
        }

        /**
         * Return the last access time.
         *
         * @param fromUidState The lowest UID state for which to query
         * @param toUidState The highest UID state for which to query (inclusive)
         * @param flags The op flags
         *
         * @return the last access time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no access
         *
         * @see #getLastAccessTime(int)
         * @see #getLastAccessForegroundTime(int)
         * @see #getLastAccessBackgroundTime(int)
         * @see OpEntry#getLastAccessTime(int, int, int)
         */
        public long getLastAccessTime(@UidState int fromUidState, @UidState int toUidState,
                @OpFlags int flags) {
            NoteOpEvent lastEvent = getLastAccessEvent(fromUidState, toUidState, flags);
            if (lastEvent == null) {
                return -1;
            }

            return lastEvent.getNoteTime();
        }

        /**
         * Return the last rejection time.
         *
         * @param flags The op flags
         *
         * @return the last rejection time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no rejection
         *
         * @see #getLastRejectForegroundTime(int)
         * @see #getLastRejectBackgroundTime(int)
         * @see #getLastRejectTime(int, int, int)
         * @see OpEntry#getLastRejectTime(int)
         */
        public long getLastRejectTime(@OpFlags int flags) {
            return getLastRejectTime(MAX_PRIORITY_UID_STATE, MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Return the last foreground rejection time.
         *
         * @param flags The op flags
         *
         * @return the last rejection time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no foreground rejection
         *
         * @see #getLastRejectTime(int)
         * @see #getLastRejectBackgroundTime(int)
         * @see #getLastRejectTime(int, int, int)
         * @see OpEntry#getLastRejectForegroundTime(int)
         */
        public long getLastRejectForegroundTime(@OpFlags int flags) {
            return getLastRejectTime(MAX_PRIORITY_UID_STATE, resolveFirstUnrestrictedUidState(mOp),
                    flags);
        }

        /**
         * Return the last background rejection time.
         *
         * @param flags The op flags
         *
         * @return the last rejection time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no background rejection
         *
         * @see #getLastRejectTime(int)
         * @see #getLastRejectForegroundTime(int)
         * @see #getLastRejectTime(int, int, int)
         * @see OpEntry#getLastRejectBackgroundTime(int)
         */
        public long getLastRejectBackgroundTime(@OpFlags int flags) {
            return getLastRejectTime(resolveLastRestrictedUidState(mOp), MIN_PRIORITY_UID_STATE,
                    flags);
        }

        /**
         * Return the last background rejection event.
         *
         * @param flags The op flags
         *
         * @return the last rejection event of {@code null} if there was no rejection
         *
         * @see #getLastRejectTime(int)
         * @see #getLastRejectForegroundTime(int)
         * @see #getLastRejectBackgroundTime(int)
         * @see OpEntry#getLastRejectTime(int, int, int)
         */
        private @Nullable NoteOpEvent getLastRejectEvent(@UidState int fromUidState,
                @UidState int toUidState, @OpFlags int flags) {
            return getLastEvent(mRejectEvents, fromUidState, toUidState, flags);
        }

        /**
         * Return the last rejection time.
         *
         * @param fromUidState The lowest UID state for which to query
         * @param toUidState The highest UID state for which to query (inclusive)
         * @param flags The op flags
         *
         * @return the last access time (in milliseconds since epoch) or {@code -1} if there was no
         * rejection
         *
         * @see #getLastRejectTime(int)
         * @see #getLastRejectForegroundTime(int)
         * @see #getLastRejectForegroundTime(int)
         * @see #getLastRejectTime(int, int, int)
         * @see OpEntry#getLastRejectTime(int, int, int)
         */
        public long getLastRejectTime(@UidState int fromUidState, @UidState int toUidState,
                @OpFlags int flags) {
            NoteOpEvent lastEvent = getLastRejectEvent(fromUidState, toUidState, flags);
            if (lastEvent == null) {
                return -1;
            }

            return lastEvent.getNoteTime();
        }

        /**
         * Return the duration in milliseconds of the last the access.
         *
         * @param flags The op flags
         *
         * @return the duration in milliseconds or {@code -1} if there was no rejection
         *
         * @see #getLastForegroundDuration(int)
         * @see #getLastBackgroundDuration(int)
         * @see #getLastDuration(int, int, int)
         * @see OpEntry#getLastDuration(int)
         */
        public long getLastDuration(@OpFlags int flags) {
            return getLastDuration(MAX_PRIORITY_UID_STATE, MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Return the duration in milliseconds of the last foreground access.
         *
         * @param flags The op flags
         *
         * @return the duration in milliseconds or {@code -1} if there was no foreground rejection
         *
         * @see #getLastDuration(int)
         * @see #getLastBackgroundDuration(int)
         * @see #getLastDuration(int, int, int)
         * @see OpEntry#getLastForegroundDuration(int)
         */
        public long getLastForegroundDuration(@OpFlags int flags) {
            return getLastDuration(MAX_PRIORITY_UID_STATE, resolveFirstUnrestrictedUidState(mOp),
                    flags);
        }

        /**
         * Return the duration in milliseconds of the last background access.
         *
         * @param flags The op flags
         *
         * @return the duration in milliseconds or {@code -1} if there was no background rejection
         *
         * @see #getLastDuration(int)
         * @see #getLastForegroundDuration(int)
         * @see #getLastDuration(int, int, int)
         * @see OpEntry#getLastBackgroundDuration(int)
         */
        public long getLastBackgroundDuration(@OpFlags int flags) {
            return getLastDuration(resolveLastRestrictedUidState(mOp), MIN_PRIORITY_UID_STATE,
                    flags);
        }

        /**
         * Return the duration in milliseconds of the last access.
         *
         * @param fromUidState The lowest UID state for which to query
         * @param toUidState The highest UID state for which to query (inclusive)
         * @param flags The op flags
         *
         * @return the duration in milliseconds or {@code -1} if there was no rejection
         *
         * @see #getLastDuration(int)
         * @see #getLastForegroundDuration(int)
         * @see #getLastBackgroundDuration(int)
         * @see #getLastDuration(int, int, int)
         * @see OpEntry#getLastDuration(int, int, int)
         */
        public long getLastDuration(@UidState int fromUidState, @UidState int toUidState,
                @OpFlags int flags) {
            NoteOpEvent lastEvent = getLastAccessEvent(fromUidState, toUidState, flags);;
            if (lastEvent == null) {
                return -1;
            }

            return lastEvent.getDuration();
        }

        /**
         * Gets the proxy info of the app that performed the last access on behalf of this
         * attribution and as a result blamed the op on this attribution.
         *
         * @param flags The op flags
         *
         * @return The proxy info or {@code null} if there was no proxy access
         *
         * @see #getLastForegroundProxyInfo(int)
         * @see #getLastBackgroundProxyInfo(int)
         * @see #getLastProxyInfo(int, int, int)
         * @see OpEntry#getLastProxyInfo(int)
         */
        public @Nullable OpEventProxyInfo getLastProxyInfo(@OpFlags int flags) {
            return getLastProxyInfo(MAX_PRIORITY_UID_STATE, MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Gets the proxy info of the app that performed the last foreground access on behalf of
         * this attribution and as a result blamed the op on this attribution.
         *
         * @param flags The op flags
         *
         * @return The proxy info or {@code null} if there was no proxy access
         *
         * @see #getLastProxyInfo(int)
         * @see #getLastBackgroundProxyInfo(int)
         * @see #getLastProxyInfo(int, int, int)
         * @see OpEntry#getLastForegroundProxyInfo(int)
         */
        public @Nullable OpEventProxyInfo getLastForegroundProxyInfo(@OpFlags int flags) {
            return getLastProxyInfo(MAX_PRIORITY_UID_STATE, resolveFirstUnrestrictedUidState(mOp),
                    flags);
        }

        /**
         * Gets the proxy info of the app that performed the last background access on behalf of
         * this attribution and as a result blamed the op on this attribution.
         *
         * @param flags The op flags
         *
         * @return The proxy info or {@code null} if there was no proxy background access
         *
         * @see #getLastProxyInfo(int)
         * @see #getLastForegroundProxyInfo(int)
         * @see #getLastProxyInfo(int, int, int)
         * @see OpEntry#getLastBackgroundProxyInfo(int)
         */
        public @Nullable OpEventProxyInfo getLastBackgroundProxyInfo(@OpFlags int flags) {
            return getLastProxyInfo(resolveLastRestrictedUidState(mOp), MIN_PRIORITY_UID_STATE,
                    flags);
        }

        /**
         * Gets the proxy info of the app that performed the last access on behalf of this
         * attribution and as a result blamed the op on this attribution.
         *
         * @param fromUidState The lowest UID state for which to query
         * @param toUidState The highest UID state for which to query (inclusive)
         * @param flags The op flags
         *
         * @return The proxy info or {@code null} if there was no proxy foreground access
         *
         * @see #getLastProxyInfo(int)
         * @see #getLastForegroundProxyInfo(int)
         * @see #getLastBackgroundProxyInfo(int)
         * @see OpEntry#getLastProxyInfo(int, int, int)
         */
        public @Nullable OpEventProxyInfo getLastProxyInfo(@UidState int fromUidState,
                @UidState int toUidState, @OpFlags int flags) {
            NoteOpEvent lastEvent = getLastAccessEvent(fromUidState, toUidState, flags);
            if (lastEvent == null) {
                return null;
            }

            return lastEvent.getProxy();
        }

        @NonNull
        String getOpName() {
            return AppOpsManager.opToPublicName(mOp);
        }

        int getOp() {
            return mOp;
        }

        private static class LongSparseArrayParceling implements
                Parcelling<LongSparseArray<NoteOpEvent>> {
            @Override
            public void parcel(@Nullable LongSparseArray<NoteOpEvent> array, @NonNull Parcel dest,
                    int parcelFlags) {
                if (array == null) {
                    dest.writeInt(-1);
                    return;
                }

                int numEntries = array.size();
                dest.writeInt(numEntries);

                for (int i = 0; i < numEntries; i++) {
                    dest.writeLong(array.keyAt(i));
                    dest.writeParcelable(array.valueAt(i), parcelFlags);
                }
            }

            @Override
            public @Nullable LongSparseArray<NoteOpEvent> unparcel(@NonNull Parcel source) {
                int numEntries = source.readInt();
                if (numEntries == -1) {
                    return null;
                }

                LongSparseArray<NoteOpEvent> array = new LongSparseArray<>(numEntries);

                for (int i = 0; i < numEntries; i++) {
                    array.put(source.readLong(), source.readParcelable(null));
                }

                return array;
            }
        }



        // Code below generated by codegen v1.0.14.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/app/AppOpsManager.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        /**
         * Creates a new OpAttributionEntry.
         *
         * @param op
         *   The code of the op
         * @param running
         *   Whether the op is running
         * @param accessEvents
         *   The access events
         * @param rejectEvents
         *   The rejection events
         * @hide
         */
        @DataClass.Generated.Member
        public AttributedOpEntry(
                @IntRange(from = 0, to = _NUM_OP - 1) int op,
                boolean running,
                @Nullable LongSparseArray<NoteOpEvent> accessEvents,
                @Nullable LongSparseArray<NoteOpEvent> rejectEvents) {
            this.mOp = op;
            com.android.internal.util.AnnotationValidations.validate(
                    IntRange.class, null, mOp,
                    "from", 0,
                    "to", _NUM_OP - 1);
            this.mRunning = running;
            this.mAccessEvents = accessEvents;
            this.mRejectEvents = rejectEvents;

            // onConstructed(); // You can define this method to get a callback
        }

        /**
         * Whether the op is running
         */
        @DataClass.Generated.Member
        public boolean isRunning() {
            return mRunning;
        }

        @DataClass.Generated.Member
        static Parcelling<LongSparseArray<NoteOpEvent>> sParcellingForAccessEvents =
                Parcelling.Cache.get(
                        LongSparseArrayParceling.class);
        static {
            if (sParcellingForAccessEvents == null) {
                sParcellingForAccessEvents = Parcelling.Cache.put(
                        new LongSparseArrayParceling());
            }
        }

        @DataClass.Generated.Member
        static Parcelling<LongSparseArray<NoteOpEvent>> sParcellingForRejectEvents =
                Parcelling.Cache.get(
                        LongSparseArrayParceling.class);
        static {
            if (sParcellingForRejectEvents == null) {
                sParcellingForRejectEvents = Parcelling.Cache.put(
                        new LongSparseArrayParceling());
            }
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mRunning) flg |= 0x2;
            if (mAccessEvents != null) flg |= 0x4;
            if (mRejectEvents != null) flg |= 0x8;
            dest.writeByte(flg);
            dest.writeInt(mOp);
            sParcellingForAccessEvents.parcel(mAccessEvents, dest, flags);
            sParcellingForRejectEvents.parcel(mRejectEvents, dest, flags);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ AttributedOpEntry(@NonNull Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            boolean running = (flg & 0x2) != 0;
            int op = in.readInt();
            LongSparseArray<NoteOpEvent> accessEvents = sParcellingForAccessEvents.unparcel(in);
            LongSparseArray<NoteOpEvent> rejectEvents = sParcellingForRejectEvents.unparcel(in);

            this.mOp = op;
            com.android.internal.util.AnnotationValidations.validate(
                    IntRange.class, null, mOp,
                    "from", 0,
                    "to", _NUM_OP - 1);
            this.mRunning = running;
            this.mAccessEvents = accessEvents;
            this.mRejectEvents = rejectEvents;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @NonNull Parcelable.Creator<AttributedOpEntry> CREATOR
                = new Parcelable.Creator<AttributedOpEntry>() {
            @Override
            public AttributedOpEntry[] newArray(int size) {
                return new AttributedOpEntry[size];
            }

            @Override
            public AttributedOpEntry createFromParcel(@NonNull Parcel in) {
                return new AttributedOpEntry(in);
            }
        };

        /*
        @DataClass.Generated(
                time = 1574809856239L,
                codegenVersion = "1.0.14",
                sourceFile = "frameworks/base/core/java/android/app/AppOpsManager.java",
                inputSignatures = "private final @android.annotation.IntRange(from=0L, to=_NUM_OP - 1) int mOp\nprivate final  boolean mRunning\nprivate final @com.android.internal.util.DataClass.ParcelWith(android.app.OpAttributionEntry.LongSparseArrayParceling.class) @android.annotation.Nullable android.util.LongSparseArray<android.app.NoteOpEvent> mAccessEvents\nprivate final @com.android.internal.util.DataClass.ParcelWith(android.app.OpAttributionEntry.LongSparseArrayParceling.class) @android.annotation.Nullable android.util.LongSparseArray<android.app.NoteOpEvent> mRejectEvents\npublic @android.annotation.NonNull android.util.ArraySet<java.lang.Long> collectKeys()\npublic @android.app.UidState int getLastAccessUidState(int)\npublic @android.app.UidState int getLastForegroundAccessUidState(int)\npublic @android.app.UidState int getLastBackgroundAccessUidState(int)\npublic @android.app.UidState int getLastRejectUidState(int)\npublic @android.app.UidState int getLastForegroundRejectUidState(int)\npublic @android.app.UidState int getLastBackgroundRejectUidState(int)\npublic  long getAccessTime(int,int)\npublic  long getRejectTime(int,int)\npublic  long getDuration(int,int)\npublic  int getProxyUid(int,int)\npublic @android.annotation.Nullable java.lang.String getProxyPackageName(int,int)\npublic @android.annotation.Nullable java.lang.String getProxyAttributionTag(int,int)\nclass OpAttributionEntry extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genHiddenConstructor=true)")
        @Deprecated
        private void __metadata() {}
         */


        //@formatter:on
        // End of generated code

    }

    /**
     * Last {@link #noteOp} and {@link #startOp} events performed for a single op for all uidModes
     * and opFlags.
     *
     * @hide
     */
    @Immutable
    @SystemApi
    // @DataClass(genHiddenConstructor = true) codegen verifier is broken
    public static final class OpEntry implements Parcelable {
        /** The code of the op */
        private final @IntRange(from = 0, to = _NUM_OP - 1) int mOp;
        /** The mode of the op */
        private final @Mode int mMode;
        /** The attributed entries by attribution tag */
        private final @NonNull Map<String, AttributedOpEntry> mAttributedOpEntries;

        /**
         * @hide
         */
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, publicAlternatives = "{@code "
                + "#getOpStr()}")
        public int getOp() {
            return mOp;
        }

        /**
         * @return This entry's op string name, such as {@link #OPSTR_COARSE_LOCATION}.
         */
        public @NonNull String getOpStr() {
            return sOpToString[mOp];
        }

        /**
         * @hide
         *
         * @deprecated Use {@link #getLastAccessTime(int)} instead
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, publicAlternatives = "{@code "
                + "#getLastAccessTime(int)}")
        public long getTime() {
            return getLastAccessTime(OP_FLAGS_ALL);
        }

        /**
         * Return the last access time.
         *
         * @param flags The op flags
         *
         * @return the last access time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no access
         *
         * @see #getLastAccessForegroundTime(int)
         * @see #getLastAccessBackgroundTime(int)
         * @see #getLastAccessTime(int, int, int)
         * @see AttributedOpEntry#getLastAccessTime(int)
         */
        public long getLastAccessTime(@OpFlags int flags) {
            return getLastAccessTime(MAX_PRIORITY_UID_STATE, MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Return the last foreground access time.
         *
         * @param flags The op flags
         *
         * @return the last access time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no foreground access
         *
         * @see #getLastAccessTime(int)
         * @see #getLastAccessBackgroundTime(int)
         * @see #getLastAccessTime(int, int, int)
         * @see AttributedOpEntry#getLastAccessForegroundTime(int)
         */
        public long getLastAccessForegroundTime(@OpFlags int flags) {
            return getLastAccessTime(MAX_PRIORITY_UID_STATE, resolveFirstUnrestrictedUidState(mOp),
                    flags);
        }

        /**
         * Return the last background access time.
         *
         * @param flags The op flags
         *
         * @return the last access time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no background access
         *
         * @see #getLastAccessTime(int)
         * @see #getLastAccessForegroundTime(int)
         * @see #getLastAccessTime(int, int, int)
         * @see AttributedOpEntry#getLastAccessBackgroundTime(int)
         */
        public long getLastAccessBackgroundTime(@OpFlags int flags) {
            return getLastAccessTime(resolveLastRestrictedUidState(mOp), MIN_PRIORITY_UID_STATE,
                    flags);
        }

        /**
         * Return the last access event.
         *
         * @param flags The op flags
         *
         * @return the last access event of {@code null} if there was no access
         */
        private @Nullable NoteOpEvent getLastAccessEvent(@UidState int fromUidState,
                @UidState int toUidState, @OpFlags int flags) {
            NoteOpEvent lastAccessEvent = null;
            for (AttributedOpEntry attributionEntry : mAttributedOpEntries.values()) {
                NoteOpEvent lastAttributionAccessEvent = attributionEntry.getLastAccessEvent(
                        fromUidState, toUidState, flags);

                if (lastAccessEvent == null || (lastAttributionAccessEvent != null
                        && lastAttributionAccessEvent.getNoteTime()
                        > lastAccessEvent.getNoteTime())) {
                    lastAccessEvent = lastAttributionAccessEvent;
                }
            }

            return lastAccessEvent;
        }

        /**
         * Return the last access time.
         *
         * @param fromUidState the lowest uid state to query
         * @param toUidState the highest uid state to query (inclusive)
         * @param flags The op flags
         *
         * @return the last access time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no access
         *
         * @see #getLastAccessTime(int)
         * @see #getLastAccessForegroundTime(int)
         * @see #getLastAccessBackgroundTime(int)
         * @see AttributedOpEntry#getLastAccessTime(int, int, int)
         */
        public long getLastAccessTime(@UidState int fromUidState, @UidState int toUidState,
                @OpFlags int flags) {
            NoteOpEvent lastEvent = getLastAccessEvent(fromUidState, toUidState, flags);;

            if (lastEvent == null) {
                return -1;
            }

            return lastEvent.getNoteTime();
        }

        /**
         * @hide
         *
         * @deprecated Use {@link #getLastRejectTime(int)} instead
         */
        @Deprecated
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, publicAlternatives = "{@code "
                + "#getLastRejectTime(int)}")
        public long getRejectTime() {
            return getLastRejectTime(OP_FLAGS_ALL);
        }

        /**
         * Return the last rejection time.
         *
         * @param flags The op flags
         *
         * @return the last rejection time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no rejection
         *
         * @see #getLastRejectForegroundTime(int)
         * @see #getLastRejectBackgroundTime(int)
         * @see #getLastRejectTime(int, int, int)
         * @see AttributedOpEntry#getLastRejectTime(int)
         */
        public long getLastRejectTime(@OpFlags int flags) {
            return getLastRejectTime(MAX_PRIORITY_UID_STATE, MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Return the last foreground rejection time.
         *
         * @param flags The op flags
         *
         * @return the last rejection time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no foreground rejection
         *
         * @see #getLastRejectTime(int)
         * @see #getLastRejectBackgroundTime(int)
         * @see #getLastRejectTime(int, int, int)
         * @see AttributedOpEntry#getLastRejectForegroundTime(int)
         */
        public long getLastRejectForegroundTime(@OpFlags int flags) {
            return getLastRejectTime(MAX_PRIORITY_UID_STATE, resolveFirstUnrestrictedUidState(mOp),
                    flags);
        }

        /**
         * Return the last background rejection time.
         *
         * @param flags The op flags
         *
         * @return the last rejection time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no background rejection
         *
         * @see #getLastRejectTime(int)
         * @see #getLastRejectForegroundTime(int)
         * @see #getLastRejectTime(int, int, int)
         * @see AttributedOpEntry#getLastRejectBackgroundTime(int)
         */
        public long getLastRejectBackgroundTime(@OpFlags int flags) {
            return getLastRejectTime(resolveLastRestrictedUidState(mOp), MIN_PRIORITY_UID_STATE,
                    flags);
        }

        /**
         * Return the last rejection event.
         *
         * @param flags The op flags
         *
         * @return the last reject event of {@code null} if there was no rejection
         */
        private @Nullable NoteOpEvent getLastRejectEvent(@UidState int fromUidState,
                @UidState int toUidState, @OpFlags int flags) {
            NoteOpEvent lastAccessEvent = null;
            for (AttributedOpEntry attributionEntry : mAttributedOpEntries.values()) {
                NoteOpEvent lastAttributionAccessEvent = attributionEntry.getLastRejectEvent(
                        fromUidState, toUidState, flags);

                if (lastAccessEvent == null || (lastAttributionAccessEvent != null
                        && lastAttributionAccessEvent.getNoteTime()
                        > lastAccessEvent.getNoteTime())) {
                    lastAccessEvent = lastAttributionAccessEvent;
                }
            }

            return lastAccessEvent;
        }

        /**
         * Return the last rejection time.
         *
         * @param fromUidState the lowest uid state to query
         * @param toUidState the highest uid state to query (inclusive)
         * @param flags The op flags
         *
         * @return the last rejection time (in milliseconds since epoch start (January 1, 1970
         * 00:00:00.000 GMT - Gregorian)) or {@code -1} if there was no rejection
         *
         * @see #getLastRejectTime(int)
         * @see #getLastRejectForegroundTime(int)
         * @see #getLastRejectBackgroundTime(int)
         * @see #getLastRejectTime(int, int, int)
         * @see AttributedOpEntry#getLastRejectTime(int, int, int)
         */
        public long getLastRejectTime(@UidState int fromUidState, @UidState int toUidState,
                @OpFlags int flags) {
            NoteOpEvent lastEvent = getLastRejectEvent(fromUidState, toUidState, flags);
            if (lastEvent == null) {
                return -1;
            }

            return lastEvent.getNoteTime();
        }

        /**
         * @return Whether the operation is running.
         */
        public boolean isRunning() {
            for (AttributedOpEntry opAttributionEntry : mAttributedOpEntries.values()) {
                if (opAttributionEntry.isRunning()) {
                    return true;
                }
            }

            return false;
        }

        /**
         * @deprecated Use {@link #getLastDuration(int)} instead
         */
        @Deprecated
        public long getDuration() {
            return getLastDuration(OP_FLAGS_ALL);
        }

        /**
         * Return the duration in milliseconds of the last the access.
         *
         * @param flags The op flags
         *
         * @return the duration in milliseconds or {@code -1} if there was no access
         *
         * @see #getLastForegroundDuration(int)
         * @see #getLastBackgroundDuration(int)
         * @see #getLastDuration(int, int, int)
         * @see AttributedOpEntry#getLastDuration(int)
         */
        public long getLastDuration(@OpFlags int flags) {
            return getLastDuration(MAX_PRIORITY_UID_STATE, MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Return the duration in milliseconds of the last foreground access.
         *
         * @param flags The op flags
         *
         * @return the duration in milliseconds or {@code -1} if there was no foreground access
         *
         * @see #getLastDuration(int)
         * @see #getLastBackgroundDuration(int)
         * @see #getLastDuration(int, int, int)
         * @see AttributedOpEntry#getLastForegroundDuration(int)
         */
        public long getLastForegroundDuration(@OpFlags int flags) {
            return getLastDuration(MAX_PRIORITY_UID_STATE, resolveFirstUnrestrictedUidState(mOp),
                    flags);
        }

        /**
         * Return the duration in milliseconds of the last background access.
         *
         * @param flags The op flags
         *
         * @return the duration in milliseconds or {@code -1} if there was no background access
         *
         * @see #getLastDuration(int)
         * @see #getLastForegroundDuration(int)
         * @see #getLastDuration(int, int, int)
         * @see AttributedOpEntry#getLastBackgroundDuration(int)
         */
        public long getLastBackgroundDuration(@OpFlags int flags) {
            return getLastDuration(resolveLastRestrictedUidState(mOp), MIN_PRIORITY_UID_STATE,
                    flags);
        }

        /**
         * Return the duration in milliseconds of the last access.
         *
         * @param fromUidState The lowest UID state for which to query
         * @param toUidState The highest UID state for which to query (inclusive)
         * @param flags The op flags
         *
         * @return the duration in milliseconds or {@code -1} if there was no access
         *
         * @see #getLastDuration(int)
         * @see #getLastForegroundDuration(int)
         * @see #getLastBackgroundDuration(int)
         * @see AttributedOpEntry#getLastDuration(int, int, int)
         */
        public long getLastDuration(@UidState int fromUidState, @UidState int toUidState,
                @OpFlags int flags) {
            NoteOpEvent lastEvent = getLastAccessEvent(fromUidState, toUidState, flags);
            if (lastEvent == null) {
                return -1;
            }

            return lastEvent.getDuration();
        }

        /**
         * @deprecated Use {@link #getLastProxyInfo(int)} instead
         */
        @Deprecated
        public int getProxyUid() {
            OpEventProxyInfo proxy = getLastProxyInfo(OP_FLAGS_ALL);
            if (proxy == null) {
                return Process.INVALID_UID;
            }

            return proxy.getUid();
        }

        /**
         * @deprecated Use {@link #getLastProxyInfo(int)} instead
         */
        @Deprecated
        public int getProxyUid(@UidState int uidState, @OpFlags int flags) {
            OpEventProxyInfo proxy = getLastProxyInfo(uidState, uidState, flags);
            if (proxy == null) {
                return Process.INVALID_UID;
            }

            return proxy.getUid();
        }

        /**
         * @deprecated Use {@link #getLastProxyInfo(int)} instead
         */
        @Deprecated
        public @Nullable String getProxyPackageName() {
            OpEventProxyInfo proxy = getLastProxyInfo(OP_FLAGS_ALL);
            if (proxy == null) {
                return null;
            }

            return proxy.getPackageName();
        }

        /**
         * @deprecated Use {@link #getLastProxyInfo(int)} instead
         */
        @Deprecated
        public @Nullable String getProxyPackageName(@UidState int uidState, @OpFlags int flags) {
            OpEventProxyInfo proxy = getLastProxyInfo(uidState, uidState, flags);
            if (proxy == null) {
                return null;
            }

            return proxy.getPackageName();
        }

        /**
         * Gets the proxy info of the app that performed the last access on behalf of this app and
         * as a result blamed the op on this app.
         *
         * @param flags The op flags
         *
         * @return The proxy info or {@code null} if there was no proxy access
         *
         * @see #getLastForegroundProxyInfo(int)
         * @see #getLastBackgroundProxyInfo(int)
         * @see #getLastProxyInfo(int, int, int)
         * @see AttributedOpEntry#getLastProxyInfo(int)
         */
        public @Nullable OpEventProxyInfo getLastProxyInfo(@OpFlags int flags) {
            return getLastProxyInfo(MAX_PRIORITY_UID_STATE, MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Gets the proxy info of the app that performed the last foreground access on behalf of
         * this app and as a result blamed the op on this app.
         *
         * @param flags The op flags
         *
         * @return The proxy info or {@code null} if there was no foreground proxy access
         *
         * @see #getLastProxyInfo(int)
         * @see #getLastBackgroundProxyInfo(int)
         * @see #getLastProxyInfo(int, int, int)
         * @see AttributedOpEntry#getLastForegroundProxyInfo(int)
         */
        public @Nullable OpEventProxyInfo getLastForegroundProxyInfo(@OpFlags int flags) {
            return getLastProxyInfo(MAX_PRIORITY_UID_STATE, resolveFirstUnrestrictedUidState(mOp),
                    flags);
        }

        /**
         * Gets the proxy info of the app that performed the last background access on behalf of
         * this app and as a result blamed the op on this app.
         *
         * @param flags The op flags
         *
         * @return The proxy info or {@code null} if there was no background proxy access
         *
         * @see #getLastProxyInfo(int)
         * @see #getLastForegroundProxyInfo(int)
         * @see #getLastProxyInfo(int, int, int)
         * @see AttributedOpEntry#getLastBackgroundProxyInfo(int)
         */
        public @Nullable OpEventProxyInfo getLastBackgroundProxyInfo(@OpFlags int flags) {
            return getLastProxyInfo(resolveLastRestrictedUidState(mOp), MIN_PRIORITY_UID_STATE,
                    flags);
        }

        /**
         * Gets the proxy info of the app that performed the last access on behalf of this app and
         * as a result blamed the op on this app.
         *
         * @param fromUidState The lowest UID state for which to query
         * @param toUidState The highest UID state for which to query (inclusive)
         * @param flags The op flags
         *
         * @return The proxy info or {@code null} if there was no proxy access
         *
         * @see #getLastProxyInfo(int)
         * @see #getLastForegroundProxyInfo(int)
         * @see #getLastBackgroundProxyInfo(int)
         * @see AttributedOpEntry#getLastProxyInfo(int, int, int)
         */
        public @Nullable OpEventProxyInfo getLastProxyInfo(@UidState int fromUidState,
                @UidState int toUidState, @OpFlags int flags) {
            NoteOpEvent lastEvent = getLastAccessEvent(fromUidState, toUidState, flags);
            if (lastEvent == null) {
                return null;
            }

            return lastEvent.getProxy();
        }



        // Code below generated by codegen v1.0.14.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/app/AppOpsManager.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        /**
         * Creates a new OpEntry.
         *
         * @param op
         *   The code of the op
         * @param mode
         *   The mode of the op
         * @param attributedOpEntries
         *   The attributions that have been used when noting the op
         * @hide
         */
        @DataClass.Generated.Member
        public OpEntry(
                @IntRange(from = 0, to = _NUM_OP - 1) int op,
                @Mode int mode,
                @NonNull Map<String, AttributedOpEntry> attributedOpEntries) {
            this.mOp = op;
            com.android.internal.util.AnnotationValidations.validate(
                    IntRange.class, null, mOp,
                    "from", 0,
                    "to", _NUM_OP - 1);
            this.mMode = mode;
            com.android.internal.util.AnnotationValidations.validate(
                    Mode.class, null, mMode);
            this.mAttributedOpEntries = attributedOpEntries;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mAttributedOpEntries);

            // onConstructed(); // You can define this method to get a callback
        }

        /**
         * The mode of the op
         */
        @DataClass.Generated.Member
        public @Mode int getMode() {
            return mMode;
        }

        /**
         * The attributed entries keyed by attribution tag.
         *
         * @see Context#createAttributionContext(String)
         * @see #noteOp(String, int, String, String, String)
         */
        @DataClass.Generated.Member
        public @NonNull Map<String, AttributedOpEntry> getAttributedOpEntries() {
            return mAttributedOpEntries;
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            dest.writeInt(mOp);
            dest.writeInt(mMode);
            dest.writeMap(mAttributedOpEntries);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ OpEntry(@NonNull Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            int op = in.readInt();
            int mode = in.readInt();
            Map<String, AttributedOpEntry> attributions = new java.util.LinkedHashMap<>();
            in.readMap(attributions, AttributedOpEntry.class.getClassLoader());

            this.mOp = op;
            com.android.internal.util.AnnotationValidations.validate(
                    IntRange.class, null, mOp,
                    "from", 0,
                    "to", _NUM_OP - 1);
            this.mMode = mode;
            com.android.internal.util.AnnotationValidations.validate(
                    Mode.class, null, mMode);
            this.mAttributedOpEntries = attributions;
            com.android.internal.util.AnnotationValidations.validate(
                    NonNull.class, null, mAttributedOpEntries);

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @NonNull Parcelable.Creator<OpEntry> CREATOR
                = new Parcelable.Creator<OpEntry>() {
            @Override
            public OpEntry[] newArray(int size) {
                return new OpEntry[size];
            }

            @Override
            public OpEntry createFromParcel(@NonNull Parcel in) {
                return new OpEntry(in);
            }
        };

        /*
        @DataClass.Generated(
                time = 1574809856259L,
                codegenVersion = "1.0.14",
                sourceFile = "frameworks/base/core/java/android/app/AppOpsManager.java",
                inputSignatures = "private final @android.annotation.IntRange(from=0L, to=_NUM_OP - 1) int mOp\nprivate final @android.app.Mode int mMode\nprivate final @android.annotation.NonNull java.util.Map<java.lang.String,android.app.OpAttributionEntry> mAttributions\npublic @android.annotation.UnsupportedAppUsage(maxTargetSdk=Build.VERSION_CODES.Q, publicAlternatives=\"{@code \" + \"#getOpStr()}\") int getOp()\npublic @android.annotation.NonNull java.lang.String getOpStr()\npublic @java.lang.Deprecated @android.annotation.UnsupportedAppUsage(maxTargetSdk=Build.VERSION_CODES.Q, publicAlternatives=\"{@code \" + \"#getAccessTime(int, int)}\") long getTime()\npublic @java.lang.Deprecated long getLastAccessTime(int)\npublic @java.lang.Deprecated long getLastAccessForegroundTime(int)\npublic @java.lang.Deprecated long getLastAccessBackgroundTime(int)\npublic @java.lang.Deprecated long getLastAccessTime(int,int,int)\npublic @java.lang.Deprecated @android.annotation.UnsupportedAppUsage(maxTargetSdk=Build.VERSION_CODES.Q, publicAlternatives=\"{@code \" + \"#getLastRejectTime(int, int, int)}\") long getRejectTime()\npublic @java.lang.Deprecated long getLastRejectTime(int)\npublic @java.lang.Deprecated long getLastRejectForegroundTime(int)\npublic @java.lang.Deprecated long getLastRejectBackgroundTime(int)\npublic @java.lang.Deprecated long getLastRejectTime(int,int,int)\npublic  long getAccessTime(int,int)\npublic  long getRejectTime(int,int)\npublic  boolean isRunning()\nprivate  android.app.NoteOpEvent getLastAccessEvent(int,int,int)\npublic @java.lang.Deprecated long getDuration()\npublic @java.lang.Deprecated long getLastForegroundDuration(int)\npublic @java.lang.Deprecated long getLastBackgroundDuration(int)\npublic @java.lang.Deprecated long getLastDuration(int,int,int)\npublic @java.lang.Deprecated int getProxyUid()\npublic @java.lang.Deprecated @android.annotation.Nullable java.lang.String getProxyPackageName()\nprivate @android.app.UidState int getLastAccessUidStateForFlagsInStatesOfAllAttributions(int,int,int)\npublic @android.app.UidState int getLastAccessUidState(int)\npublic @android.app.UidState int getLastForegroundAccessUidState(int)\npublic @android.app.UidState int getLastBackgroundAccessUidState(int)\nprivate @android.app.UidState int getLastRejectUidStateForFlagsInStatesOfAllAttributions(int,int,int)\npublic @android.app.UidState int getLastRejectUidState(int)\npublic @android.app.UidState int getLastForegroundRejectUidState(int)\npublic @android.app.UidState int getLastBackgroundRejectUidState(int)\npublic  long getDuration(int,int)\npublic  int getProxyUid(int,int)\nprivate  int getProxyUid(int,int,int)\npublic @android.annotation.Nullable java.lang.String getProxyPackageName(int,int)\nprivate @android.annotation.Nullable java.lang.String getProxyPackageName(int,int,int)\nclass OpEntry extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genHiddenConstructor=true)")
        @Deprecated
        private void __metadata() {}
         */


        //@formatter:on
        // End of generated code

    }

    /** @hide */
    public interface HistoricalOpsVisitor {
        void visitHistoricalOps(@NonNull HistoricalOps ops);
        void visitHistoricalUidOps(@NonNull HistoricalUidOps ops);
        void visitHistoricalPackageOps(@NonNull HistoricalPackageOps ops);
        void visitHistoricalAttributionOps(@NonNull AttributedHistoricalOps ops);
        void visitHistoricalOp(@NonNull HistoricalOp ops);
    }

    /**
     * Flag for querying app op history: get only aggregate information (counts of events) and no
     * discret accesses information - specific accesses with timestamp.
     *
     * @see #getHistoricalOps(HistoricalOpsRequest, Executor, Consumer)
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int HISTORY_FLAG_AGGREGATE = 1 << 0;

    /**
     * Flag for querying app op history: get only discrete access information (only specific
     * accesses with timestamps) and no aggregate information (counts over time).
     *
     * @see #getHistoricalOps(HistoricalOpsRequest, Executor, Consumer)
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int HISTORY_FLAG_DISCRETE = 1 << 1;

    /**
     * Flag for querying app op history: get all types of historical access information.
     *
     * @see #getHistoricalOps(HistoricalOpsRequest, Executor, Consumer)
     *
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int HISTORY_FLAGS_ALL = HISTORY_FLAG_AGGREGATE
            | HISTORY_FLAG_DISCRETE;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "HISTORY_FLAG_" }, value = {
            HISTORY_FLAG_AGGREGATE,
            HISTORY_FLAG_DISCRETE
    })
    public @interface OpHistoryFlags {}

    /**
     * Specifies what parameters to filter historical appop requests for
     *
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "FILTER_BY_" }, value = {
            FILTER_BY_UID,
            FILTER_BY_PACKAGE_NAME,
            FILTER_BY_ATTRIBUTION_TAG,
            FILTER_BY_OP_NAMES
    })
    public @interface HistoricalOpsRequestFilter {}

    /**
     * Filter historical appop request by uid.
     *
     * @hide
     */
    public static final int FILTER_BY_UID = 1<<0;

    /**
     * Filter historical appop request by package name.
     *
     * @hide
     */
    public static final int FILTER_BY_PACKAGE_NAME = 1<<1;

    /**
     * Filter historical appop request by attribution tag.
     *
     * @hide
     */
    public static final int FILTER_BY_ATTRIBUTION_TAG = 1<<2;

    /**
     * Filter historical appop request by op names.
     *
     * @hide
     */
    public static final int FILTER_BY_OP_NAMES = 1<<3;

    /**
     * Request for getting historical app op usage. The request acts
     * as a filtering criteria when querying historical op usage.
     *
     * @hide
     */
    @Immutable
    @SystemApi
    public static final class HistoricalOpsRequest {
        private final int mUid;
        private final @Nullable String mPackageName;
        private final @Nullable String mAttributionTag;
        private final @Nullable List<String> mOpNames;
        private final @OpHistoryFlags int mHistoryFlags;
        private final @HistoricalOpsRequestFilter int mFilter;
        private final long mBeginTimeMillis;
        private final long mEndTimeMillis;
        private final @OpFlags int mFlags;

        private HistoricalOpsRequest(int uid, @Nullable String packageName,
                @Nullable String attributionTag, @Nullable List<String> opNames,
                @OpHistoryFlags int historyFlags, @HistoricalOpsRequestFilter int filter,
                long beginTimeMillis, long endTimeMillis, @OpFlags int flags) {
            mUid = uid;
            mPackageName = packageName;
            mAttributionTag = attributionTag;
            mOpNames = opNames;
            mHistoryFlags = historyFlags;
            mFilter = filter;
            mBeginTimeMillis = beginTimeMillis;
            mEndTimeMillis = endTimeMillis;
            mFlags = flags;
        }

        /**
         * Builder for creating a {@link HistoricalOpsRequest}.
         *
         * @hide
         */
        @SystemApi
        public static final class Builder {
            private int mUid = Process.INVALID_UID;
            private @Nullable String mPackageName;
            private @Nullable String mAttributionTag;
            private @Nullable List<String> mOpNames;
            private @OpHistoryFlags int mHistoryFlags;
            private @HistoricalOpsRequestFilter int mFilter;
            private final long mBeginTimeMillis;
            private final long mEndTimeMillis;
            private @OpFlags int mFlags = OP_FLAGS_ALL;

            /**
             * Creates a new builder.
             *
             * @param beginTimeMillis The beginning of the interval in milliseconds since
             *     epoch start (January 1, 1970 00:00:00.000 GMT - Gregorian). Must be non
             *     negative.
             * @param endTimeMillis The end of the interval in milliseconds since
             *     epoch start (January 1, 1970 00:00:00.000 GMT - Gregorian). Must be after
             *     {@code beginTimeMillis}. Pass {@link Long#MAX_VALUE} to get the most recent
             *     history including ops that happen while this call is in flight.
             */
            public Builder(long beginTimeMillis, long endTimeMillis) {
                Preconditions.checkArgument(beginTimeMillis >= 0 && beginTimeMillis < endTimeMillis,
                        "beginTimeMillis must be non negative and lesser than endTimeMillis");
                mBeginTimeMillis = beginTimeMillis;
                mEndTimeMillis = endTimeMillis;
                mHistoryFlags = HISTORY_FLAG_AGGREGATE;
            }

            /**
             * Sets the UID to query for.
             *
             * @param uid The uid. Pass {@link android.os.Process#INVALID_UID} for any uid.
             * @return This builder.
             */
            public @NonNull Builder setUid(int uid) {
                Preconditions.checkArgument(uid == Process.INVALID_UID || uid >= 0,
                        "uid must be " + Process.INVALID_UID + " or non negative");
                mUid = uid;

                if (uid == Process.INVALID_UID) {
                    mFilter &= ~FILTER_BY_UID;
                } else {
                    mFilter |= FILTER_BY_UID;
                }

                return this;
            }

            /**
             * Sets the package to query for.
             *
             * @param packageName The package name. <code>Null</code> for any package.
             * @return This builder.
             */
            public @NonNull Builder setPackageName(@Nullable String packageName) {
                mPackageName = packageName;

                if (packageName == null) {
                    mFilter &= ~FILTER_BY_PACKAGE_NAME;
                } else {
                    mFilter |= FILTER_BY_PACKAGE_NAME;
                }

                return this;
            }

            /**
             * Sets the attribution tag to query for.
             *
             * @param attributionTag attribution tag
             * @return This builder.
             */
            public @NonNull Builder setAttributionTag(@Nullable String attributionTag) {
                mAttributionTag = attributionTag;
                mFilter |= FILTER_BY_ATTRIBUTION_TAG;

                return this;
            }

            /**
             * Sets the op names to query for.
             *
             * @param opNames The op names. <code>Null</code> for any op.
             * @return This builder.
             */
            public @NonNull Builder setOpNames(@Nullable List<String> opNames) {
                if (opNames != null) {
                    final int opCount = opNames.size();
                    for (int i = 0; i < opCount; i++) {
                        Preconditions.checkArgument(AppOpsManager.strOpToOp(
                                opNames.get(i)) != AppOpsManager.OP_NONE);
                    }
                }
                mOpNames = opNames;

                if (mOpNames == null) {
                    mFilter &= ~FILTER_BY_OP_NAMES;
                } else {
                    mFilter |= FILTER_BY_OP_NAMES;
                }

                return this;
            }

            /**
             * Sets the op flags to query for. The flags specify the type of
             * op data being queried.
             *
             * @param flags The flags which are any combination of
             * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
             * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
             * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
             * for any flag.
             * @return This builder.
             */
            public @NonNull Builder setFlags(@OpFlags int flags) {
                Preconditions.checkFlagsArgument(flags, OP_FLAGS_ALL);
                mFlags = flags;
                return this;
            }

            /**
             * Specifies what type of historical information to query.
             *
             * @param flags Flags for the historical types to fetch which are any
             * combination of {@link #HISTORY_FLAG_AGGREGATE}, {@link #HISTORY_FLAG_DISCRETE},
             * {@link #HISTORY_FLAGS_ALL}. The default is {@link #HISTORY_FLAG_AGGREGATE}.
             * @return This builder.
             */
            public @NonNull Builder setHistoryFlags(@OpHistoryFlags int flags) {
                Preconditions.checkFlagsArgument(flags, HISTORY_FLAGS_ALL);
                mHistoryFlags = flags;
                return this;
            }

            /**
             * @return a new {@link HistoricalOpsRequest}.
             */
            public @NonNull HistoricalOpsRequest build() {
                return new HistoricalOpsRequest(mUid, mPackageName, mAttributionTag, mOpNames,
                        mHistoryFlags, mFilter, mBeginTimeMillis, mEndTimeMillis, mFlags);
            }
        }
    }

    /**
     * This class represents historical app op state of all UIDs for a given time interval.
     *
     * @hide
     */
    @SystemApi
    public static final class HistoricalOps implements Parcelable {
        private long mBeginTimeMillis;
        private long mEndTimeMillis;
        private @Nullable SparseArray<HistoricalUidOps> mHistoricalUidOps;

        /** @hide */
        @TestApi
        public HistoricalOps(long beginTimeMillis, long endTimeMillis) {
            Preconditions.checkState(beginTimeMillis <= endTimeMillis);
            mBeginTimeMillis = beginTimeMillis;
            mEndTimeMillis = endTimeMillis;
        }

        /** @hide */
        public HistoricalOps(@NonNull HistoricalOps other) {
            mBeginTimeMillis = other.mBeginTimeMillis;
            mEndTimeMillis = other.mEndTimeMillis;
            Preconditions.checkState(mBeginTimeMillis <= mEndTimeMillis);
            if (other.mHistoricalUidOps != null) {
                final int opCount = other.getUidCount();
                for (int i = 0; i < opCount; i++) {
                    final HistoricalUidOps origOps = other.getUidOpsAt(i);
                    final HistoricalUidOps clonedOps = new HistoricalUidOps(origOps);
                    if (mHistoricalUidOps == null) {
                        mHistoricalUidOps = new SparseArray<>(opCount);
                    }
                    mHistoricalUidOps.put(clonedOps.getUid(), clonedOps);
                }
            }
        }

        private HistoricalOps(Parcel parcel) {
            mBeginTimeMillis = parcel.readLong();
            mEndTimeMillis = parcel.readLong();
            final int[] uids = parcel.createIntArray();
            if (!ArrayUtils.isEmpty(uids)) {
                final ParceledListSlice<HistoricalUidOps> listSlice = parcel.readParcelable(
                        HistoricalOps.class.getClassLoader());
                final List<HistoricalUidOps> uidOps = (listSlice != null)
                        ? listSlice.getList() : null;
                if (uidOps == null) {
                    return;
                }
                for (int i = 0; i < uids.length; i++) {
                    if (mHistoricalUidOps == null) {
                        mHistoricalUidOps = new SparseArray<>();
                    }
                    mHistoricalUidOps.put(uids[i], uidOps.get(i));
                }
            }
        }

        /**
         * Splice a piece from the beginning of these ops.
         *
         * @param splicePoint The fraction of the data to be spliced off.
         *
         * @hide
         */
        public @NonNull HistoricalOps spliceFromBeginning(double splicePoint) {
            return splice(splicePoint, true);
        }

        /**
         * Splice a piece from the end of these ops.
         *
         * @param fractionToRemove The fraction of the data to be spliced off.
         *
         * @hide
         */
        public @NonNull HistoricalOps spliceFromEnd(double fractionToRemove) {
            return splice(fractionToRemove, false);
        }

        /**
         * Splice a piece from the beginning or end of these ops.
         *
         * @param fractionToRemove The fraction of the data to be spliced off.
         * @param beginning Whether to splice off the beginning or the end.
         *
         * @return The spliced off part.
         *
         * @hide
         */
        private @Nullable HistoricalOps splice(double fractionToRemove, boolean beginning) {
            final long spliceBeginTimeMills;
            final long spliceEndTimeMills;
            if (beginning) {
                spliceBeginTimeMills = mBeginTimeMillis;
                spliceEndTimeMills = (long) (mBeginTimeMillis
                        + getDurationMillis() * fractionToRemove);
                mBeginTimeMillis = spliceEndTimeMills;
            } else {
                spliceBeginTimeMills = (long) (mEndTimeMillis
                        - getDurationMillis() * fractionToRemove);
                spliceEndTimeMills = mEndTimeMillis;
                mEndTimeMillis = spliceBeginTimeMills;
            }

            HistoricalOps splice = null;
            final int uidCount = getUidCount();
            for (int i = 0; i < uidCount; i++) {
                final HistoricalUidOps origOps = getUidOpsAt(i);
                final HistoricalUidOps spliceOps = origOps.splice(fractionToRemove);
                if (spliceOps != null) {
                    if (splice == null) {
                        splice = new HistoricalOps(spliceBeginTimeMills, spliceEndTimeMills);
                    }
                    if (splice.mHistoricalUidOps == null) {
                        splice.mHistoricalUidOps = new SparseArray<>();
                    }
                    splice.mHistoricalUidOps.put(spliceOps.getUid(), spliceOps);
                }
            }
            return splice;
        }

        /**
         * Merge the passed ops into the current ones. The time interval is a
         * union of the current and passed in one and the passed in data is
         * folded into the data of this instance.
         *
         * @hide
         */
        public void merge(@NonNull HistoricalOps other) {
            mBeginTimeMillis = Math.min(mBeginTimeMillis, other.mBeginTimeMillis);
            mEndTimeMillis = Math.max(mEndTimeMillis, other.mEndTimeMillis);
            final int uidCount = other.getUidCount();
            for (int i = 0; i < uidCount; i++) {
                final HistoricalUidOps otherUidOps = other.getUidOpsAt(i);
                final HistoricalUidOps thisUidOps = getUidOps(otherUidOps.getUid());
                if (thisUidOps != null) {
                    thisUidOps.merge(otherUidOps);
                } else {
                    if (mHistoricalUidOps == null) {
                        mHistoricalUidOps = new SparseArray<>();
                    }
                    mHistoricalUidOps.put(otherUidOps.getUid(), otherUidOps);
                }
            }
        }

        /**
         * AppPermissionUsage the ops to leave only the data we filter for.
         *
         * @param uid Uid to filter for.
         * @param packageName Package to filter for.
         * @param attributionTag attribution tag to filter for
         * @param opNames Ops to filter for.
         * @param filter Which parameters to filter on.
         * @param beginTimeMillis The begin time to filter for or {@link Long#MIN_VALUE} for all.
         * @param endTimeMillis The end time to filter for or {@link Long#MAX_VALUE} for all.
         *
         * @hide
         */
        public void filter(int uid, @Nullable String packageName, @Nullable String attributionTag,
                @Nullable String[] opNames, @OpHistoryFlags int historyFilter,
                @HistoricalOpsRequestFilter int filter,
                long beginTimeMillis, long endTimeMillis) {
            final long durationMillis = getDurationMillis();
            mBeginTimeMillis = Math.max(mBeginTimeMillis, beginTimeMillis);
            mEndTimeMillis = Math.min(mEndTimeMillis, endTimeMillis);
            final double scaleFactor = Math.min((double) (endTimeMillis - beginTimeMillis)
                    / (double) durationMillis, 1);
            final int uidCount = getUidCount();
            for (int i = uidCount - 1; i >= 0; i--) {
                final HistoricalUidOps uidOp = mHistoricalUidOps.valueAt(i);
                if ((filter & FILTER_BY_UID) != 0 && uid != uidOp.getUid()) {
                    mHistoricalUidOps.removeAt(i);
                } else {
                    uidOp.filter(packageName, attributionTag, opNames, filter, historyFilter,
                            scaleFactor, mBeginTimeMillis, mEndTimeMillis);
                    if (uidOp.getPackageCount() == 0) {
                        mHistoricalUidOps.removeAt(i);
                    }
                }
            }
        }

        /** @hide */
        public boolean isEmpty() {
            if (getBeginTimeMillis() >= getEndTimeMillis()) {
                return true;
            }
            final int uidCount = getUidCount();
            for (int i = uidCount - 1; i >= 0; i--) {
                final HistoricalUidOps uidOp = mHistoricalUidOps.valueAt(i);
                if (!uidOp.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        /** @hide */
        public long getDurationMillis() {
            return mEndTimeMillis - mBeginTimeMillis;
        }

        /** @hide */
        @TestApi
        public void increaseAccessCount(int opCode, int uid, @NonNull String packageName,
                @Nullable String attributionTag, @UidState int uidState,  @OpFlags int flags,
                long increment) {
            getOrCreateHistoricalUidOps(uid).increaseAccessCount(opCode,
                    packageName, attributionTag, uidState, flags, increment);
        }

        /** @hide */
        @TestApi
        public void increaseRejectCount(int opCode, int uid, @NonNull String packageName,
                @Nullable String attributionTag, @UidState int uidState, @OpFlags int flags,
                long increment) {
            getOrCreateHistoricalUidOps(uid).increaseRejectCount(opCode,
                    packageName, attributionTag, uidState, flags, increment);
        }

        /** @hide */
        @TestApi
        public void increaseAccessDuration(int opCode, int uid, @NonNull String packageName,
                @Nullable String attributionTag, @UidState int uidState, @OpFlags int flags,
                long increment) {
            getOrCreateHistoricalUidOps(uid).increaseAccessDuration(opCode,
                    packageName, attributionTag, uidState, flags, increment);
        }

        /** @hide */
        @TestApi
        public void addDiscreteAccess(int opCode, int uid, @NonNull String packageName,
                @Nullable String attributionTag, @UidState int uidState, @OpFlags int opFlag,
                long discreteAccessTime, long discreteAccessDuration) {
            getOrCreateHistoricalUidOps(uid).addDiscreteAccess(opCode, packageName, attributionTag,
                    uidState, opFlag, discreteAccessTime, discreteAccessDuration);
        };


        /** @hide */
        @TestApi
        public void offsetBeginAndEndTime(long offsetMillis) {
            mBeginTimeMillis += offsetMillis;
            mEndTimeMillis += offsetMillis;
        }

        /** @hide */
        public void setBeginAndEndTime(long beginTimeMillis, long endTimeMillis) {
            mBeginTimeMillis = beginTimeMillis;
            mEndTimeMillis = endTimeMillis;
        }

        /** @hide */
        public void setBeginTime(long beginTimeMillis) {
            mBeginTimeMillis = beginTimeMillis;
        }

        /** @hide */
        public void setEndTime(long endTimeMillis) {
            mEndTimeMillis = endTimeMillis;
        }

        /**
         * @return The beginning of the interval in milliseconds since
         *    epoch start (January 1, 1970 00:00:00.000 GMT - Gregorian).
         */
        public long getBeginTimeMillis() {
            return mBeginTimeMillis;
        }

        /**
         * @return The end of the interval in milliseconds since
         *    epoch start (January 1, 1970 00:00:00.000 GMT - Gregorian).
         */
        public long getEndTimeMillis() {
            return mEndTimeMillis;
        }

        /**
         * Gets number of UIDs with historical ops.
         *
         * @return The number of UIDs with historical ops.
         *
         * @see #getUidOpsAt(int)
         */
        public @IntRange(from = 0) int getUidCount() {
            if (mHistoricalUidOps == null) {
                return 0;
            }
            return mHistoricalUidOps.size();
        }

        /**
         * Gets the historical UID ops at a given index.
         *
         * @param index The index.
         *
         * @return The historical UID ops at the given index.
         *
         * @see #getUidCount()
         */
        public @NonNull HistoricalUidOps getUidOpsAt(@IntRange(from = 0) int index) {
            if (mHistoricalUidOps == null) {
                throw new IndexOutOfBoundsException();
            }
            return mHistoricalUidOps.valueAt(index);
        }

        /**
         * Gets the historical UID ops for a given UID.
         *
         * @param uid The UID.
         *
         * @return The historical ops for the UID.
         */
        public @Nullable HistoricalUidOps getUidOps(int uid) {
            if (mHistoricalUidOps == null) {
                return null;
            }
            return mHistoricalUidOps.get(uid);
        }

        /** @hide */
        public void clearHistory(int uid, @NonNull String packageName) {
            HistoricalUidOps historicalUidOps = getOrCreateHistoricalUidOps(uid);
            historicalUidOps.clearHistory(packageName);
            if (historicalUidOps.isEmpty()) {
                mHistoricalUidOps.remove(uid);
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeLong(mBeginTimeMillis);
            parcel.writeLong(mEndTimeMillis);
            if (mHistoricalUidOps != null) {
                final int uidCount = mHistoricalUidOps.size();
                parcel.writeInt(uidCount);
                for (int i = 0; i < uidCount; i++) {
                    parcel.writeInt(mHistoricalUidOps.keyAt(i));
                }
                final List<HistoricalUidOps> opsList = new ArrayList<>(uidCount);
                for (int i = 0; i < uidCount; i++) {
                    opsList.add(mHistoricalUidOps.valueAt(i));
                }
                parcel.writeParcelable(new ParceledListSlice<>(opsList), flags);
            } else {
                parcel.writeInt(-1);
            }
        }

        /**
         * Accepts a visitor to traverse the ops tree.
         *
         * @param visitor The visitor.
         *
         * @hide
         */
        public void accept(@NonNull HistoricalOpsVisitor visitor) {
            visitor.visitHistoricalOps(this);
            final int uidCount = getUidCount();
            for (int i = 0; i < uidCount; i++) {
                getUidOpsAt(i).accept(visitor);
            }
        }

        private @NonNull HistoricalUidOps getOrCreateHistoricalUidOps(int uid) {
            if (mHistoricalUidOps == null) {
                mHistoricalUidOps = new SparseArray<>();
            }
            HistoricalUidOps historicalUidOp = mHistoricalUidOps.get(uid);
            if (historicalUidOp == null) {
                historicalUidOp = new HistoricalUidOps(uid);
                mHistoricalUidOps.put(uid, historicalUidOp);
            }
            return historicalUidOp;
        }

        /**
         * @return Rounded value up at the 0.5 boundary.
         *
         * @hide
         */
        public static double round(double value) {
            return Math.floor(value + 0.5);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final HistoricalOps other = (HistoricalOps) obj;
            if (mBeginTimeMillis != other.mBeginTimeMillis) {
                return false;
            }
            if (mEndTimeMillis != other.mEndTimeMillis) {
                return false;
            }
            if (mHistoricalUidOps == null) {
                if (other.mHistoricalUidOps != null) {
                    return false;
                }
            } else if (!mHistoricalUidOps.equals(other.mHistoricalUidOps)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = (int) (mBeginTimeMillis ^ (mBeginTimeMillis >>> 32));
            result = 31 * result + mHistoricalUidOps.hashCode();
            return result;
        }

        @NonNull
        @Override
        public String toString() {
            return getClass().getSimpleName() + "[from:"
                    + mBeginTimeMillis + " to:" + mEndTimeMillis + "]";
        }

        public static final @android.annotation.NonNull Creator<HistoricalOps> CREATOR = new Creator<HistoricalOps>() {
            @Override
            public @NonNull HistoricalOps createFromParcel(@NonNull Parcel parcel) {
                return new HistoricalOps(parcel);
            }

            @Override
            public @NonNull HistoricalOps[] newArray(int size) {
                return new HistoricalOps[size];
            }
        };
    }

    /**
     * This class represents historical app op state for a UID.
     *
     * @hide
     */
    @SystemApi
    public static final class HistoricalUidOps implements Parcelable {
        private final int mUid;
        private @Nullable ArrayMap<String, HistoricalPackageOps> mHistoricalPackageOps;

        /** @hide */
        public HistoricalUidOps(int uid) {
            mUid = uid;
        }

        private HistoricalUidOps(@NonNull HistoricalUidOps other) {
            mUid = other.mUid;
            final int opCount = other.getPackageCount();
            for (int i = 0; i < opCount; i++) {
                final HistoricalPackageOps origOps = other.getPackageOpsAt(i);
                final HistoricalPackageOps cloneOps = new HistoricalPackageOps(origOps);
                if (mHistoricalPackageOps == null) {
                    mHistoricalPackageOps = new ArrayMap<>(opCount);
                }
                mHistoricalPackageOps.put(cloneOps.getPackageName(), cloneOps);
            }
        }

        private HistoricalUidOps(@NonNull Parcel parcel) {
            // No arg check since we always read from a trusted source.
            mUid = parcel.readInt();
            mHistoricalPackageOps = parcel.createTypedArrayMap(HistoricalPackageOps.CREATOR);
        }

        private @Nullable HistoricalUidOps splice(double fractionToRemove) {
            HistoricalUidOps splice = null;
            final int packageCount = getPackageCount();
            for (int i = 0; i < packageCount; i++) {
                final HistoricalPackageOps origOps = getPackageOpsAt(i);
                final HistoricalPackageOps spliceOps = origOps.splice(fractionToRemove);
                if (spliceOps != null) {
                    if (splice == null) {
                        splice = new HistoricalUidOps(mUid);
                    }
                    if (splice.mHistoricalPackageOps == null) {
                        splice.mHistoricalPackageOps = new ArrayMap<>();
                    }
                    splice.mHistoricalPackageOps.put(spliceOps.getPackageName(), spliceOps);
                }
            }
            return splice;
        }

        private void merge(@NonNull HistoricalUidOps other) {
            final int packageCount = other.getPackageCount();
            for (int i = 0; i < packageCount; i++) {
                final HistoricalPackageOps otherPackageOps = other.getPackageOpsAt(i);
                final HistoricalPackageOps thisPackageOps = getPackageOps(
                        otherPackageOps.getPackageName());
                if (thisPackageOps != null) {
                    thisPackageOps.merge(otherPackageOps);
                } else {
                    if (mHistoricalPackageOps == null) {
                        mHistoricalPackageOps = new ArrayMap<>();
                    }
                    mHistoricalPackageOps.put(otherPackageOps.getPackageName(), otherPackageOps);
                }
            }
        }

        private void filter(@Nullable String packageName, @Nullable String attributionTag,
                @Nullable String[] opNames, @HistoricalOpsRequestFilter int filter,
                @OpHistoryFlags int historyFilter, double fractionToRemove, long beginTimeMillis,
                long endTimeMillis) {
            final int packageCount = getPackageCount();
            for (int i = packageCount - 1; i >= 0; i--) {
                final HistoricalPackageOps packageOps = getPackageOpsAt(i);
                if ((filter & FILTER_BY_PACKAGE_NAME) != 0 && !packageName.equals(
                        packageOps.getPackageName())) {
                    mHistoricalPackageOps.removeAt(i);
                } else {
                    packageOps.filter(attributionTag, opNames, filter, historyFilter,
                            fractionToRemove, beginTimeMillis, endTimeMillis);
                    if (packageOps.getAttributedOpsCount() == 0) {
                        mHistoricalPackageOps.removeAt(i);
                    }
                }
            }
        }

        private boolean isEmpty() {
            final int packageCount = getPackageCount();
            for (int i = packageCount - 1; i >= 0; i--) {
                final HistoricalPackageOps packageOps = mHistoricalPackageOps.valueAt(i);
                if (!packageOps.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private void increaseAccessCount(int opCode, @NonNull String packageName,
                @Nullable String attributionTag, @UidState int uidState, @OpFlags int flags,
                long increment) {
            getOrCreateHistoricalPackageOps(packageName).increaseAccessCount(
                    opCode, attributionTag, uidState, flags, increment);
        }

        private void increaseRejectCount(int opCode, @NonNull String packageName,
                @Nullable String attributionTag, @UidState int uidState,  @OpFlags int flags,
                long increment) {
            getOrCreateHistoricalPackageOps(packageName).increaseRejectCount(
                    opCode, attributionTag, uidState, flags, increment);
        }

        private void increaseAccessDuration(int opCode, @NonNull String packageName,
                @Nullable String attributionTag, @UidState int uidState, @OpFlags int flags,
                long increment) {
            getOrCreateHistoricalPackageOps(packageName).increaseAccessDuration(
                    opCode, attributionTag, uidState, flags, increment);
        }

        private void addDiscreteAccess(int opCode, @NonNull String packageName,
                @Nullable String attributionTag, @UidState int uidState,
                @OpFlags int flag, long discreteAccessTime, long discreteAccessDuration) {
            getOrCreateHistoricalPackageOps(packageName).addDiscreteAccess(opCode, attributionTag,
                    uidState, flag, discreteAccessTime, discreteAccessDuration);
        };

        /**
         * @return The UID for which the data is related.
         */
        public int getUid() {
            return mUid;
        }

        /**
         * Gets number of packages with historical ops.
         *
         * @return The number of packages with historical ops.
         *
         * @see #getPackageOpsAt(int)
         */
        public @IntRange(from = 0) int getPackageCount() {
            if (mHistoricalPackageOps == null) {
                return 0;
            }
            return mHistoricalPackageOps.size();
        }

        /**
         * Gets the historical package ops at a given index.
         *
         * @param index The index.
         *
         * @return The historical package ops at the given index.
         *
         * @see #getPackageCount()
         */
        public @NonNull HistoricalPackageOps getPackageOpsAt(@IntRange(from = 0) int index) {
            if (mHistoricalPackageOps == null) {
                throw new IndexOutOfBoundsException();
            }
            return mHistoricalPackageOps.valueAt(index);
        }

        /**
         * Gets the historical package ops for a given package.
         *
         * @param packageName The package.
         *
         * @return The historical ops for the package.
         */
        public @Nullable HistoricalPackageOps getPackageOps(@NonNull String packageName) {
            if (mHistoricalPackageOps == null) {
                return null;
            }
            return mHistoricalPackageOps.get(packageName);
        }

        private void clearHistory(@NonNull String packageName) {
            if (mHistoricalPackageOps != null) {
                mHistoricalPackageOps.remove(packageName);
            }
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(mUid);
            parcel.writeTypedArrayMap(mHistoricalPackageOps, flags);
        }

        private void accept(@NonNull HistoricalOpsVisitor visitor) {
            visitor.visitHistoricalUidOps(this);
            final int packageCount = getPackageCount();
            for (int i = 0; i < packageCount; i++) {
                getPackageOpsAt(i).accept(visitor);
            }
        }

        private @NonNull HistoricalPackageOps getOrCreateHistoricalPackageOps(
                @NonNull String packageName) {
            if (mHistoricalPackageOps == null) {
                mHistoricalPackageOps = new ArrayMap<>();
            }
            HistoricalPackageOps historicalPackageOp = mHistoricalPackageOps.get(packageName);
            if (historicalPackageOp == null) {
                historicalPackageOp = new HistoricalPackageOps(packageName);
                mHistoricalPackageOps.put(packageName, historicalPackageOp);
            }
            return historicalPackageOp;
        }


        public static final @android.annotation.NonNull Creator<HistoricalUidOps> CREATOR = new Creator<HistoricalUidOps>() {
            @Override
            public @NonNull HistoricalUidOps createFromParcel(@NonNull Parcel parcel) {
                return new HistoricalUidOps(parcel);
            }

            @Override
            public @NonNull HistoricalUidOps[] newArray(int size) {
                return new HistoricalUidOps[size];
            }
        };

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final HistoricalUidOps other = (HistoricalUidOps) obj;
            if (mUid != other.mUid) {
                return false;
            }
            if (mHistoricalPackageOps == null) {
                if (other.mHistoricalPackageOps != null) {
                    return false;
                }
            } else if (!mHistoricalPackageOps.equals(other.mHistoricalPackageOps)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = mUid;
            result = 31 * result + (mHistoricalPackageOps != null
                    ? mHistoricalPackageOps.hashCode() : 0);
            return result;
        }
    }

    /**
     * This class represents historical app op information about a package.
     *
     * @hide
     */
    @SystemApi
    public static final class HistoricalPackageOps implements Parcelable {
        private final @NonNull String mPackageName;
        private @Nullable ArrayMap<String, AttributedHistoricalOps> mAttributedHistoricalOps;

        /** @hide */
        public HistoricalPackageOps(@NonNull String packageName) {
            mPackageName = packageName;
        }

        private HistoricalPackageOps(@NonNull HistoricalPackageOps other) {
            mPackageName = other.mPackageName;
            final int opCount = other.getAttributedOpsCount();
            for (int i = 0; i < opCount; i++) {
                final AttributedHistoricalOps origOps = other.getAttributedOpsAt(i);
                final AttributedHistoricalOps cloneOps = new AttributedHistoricalOps(origOps);
                if (mAttributedHistoricalOps == null) {
                    mAttributedHistoricalOps = new ArrayMap<>(opCount);
                }
                mAttributedHistoricalOps.put(cloneOps.getTag(), cloneOps);
            }
        }

        private HistoricalPackageOps(@NonNull Parcel parcel) {
            mPackageName = parcel.readString();
            mAttributedHistoricalOps = parcel.createTypedArrayMap(AttributedHistoricalOps.CREATOR);
        }

        private @Nullable HistoricalPackageOps splice(double fractionToRemove) {
            HistoricalPackageOps splice = null;
            final int attributionCount = getAttributedOpsCount();
            for (int i = 0; i < attributionCount; i++) {
                final AttributedHistoricalOps origOps = getAttributedOpsAt(i);
                final AttributedHistoricalOps spliceOps = origOps.splice(fractionToRemove);
                if (spliceOps != null) {
                    if (splice == null) {
                        splice = new HistoricalPackageOps(mPackageName);
                    }
                    if (splice.mAttributedHistoricalOps == null) {
                        splice.mAttributedHistoricalOps = new ArrayMap<>();
                    }
                    splice.mAttributedHistoricalOps.put(spliceOps.getTag(), spliceOps);
                }
            }
            return splice;
        }

        private void merge(@NonNull HistoricalPackageOps other) {
            final int attributionCount = other.getAttributedOpsCount();
            for (int i = 0; i < attributionCount; i++) {
                final AttributedHistoricalOps otherAttributionOps = other.getAttributedOpsAt(i);
                final AttributedHistoricalOps thisAttributionOps = getAttributedOps(
                        otherAttributionOps.getTag());
                if (thisAttributionOps != null) {
                    thisAttributionOps.merge(otherAttributionOps);
                } else {
                    if (mAttributedHistoricalOps == null) {
                        mAttributedHistoricalOps = new ArrayMap<>();
                    }
                    mAttributedHistoricalOps.put(otherAttributionOps.getTag(),
                            otherAttributionOps);
                }
            }
        }

        private void filter(@Nullable String attributionTag, @Nullable String[] opNames,
                @HistoricalOpsRequestFilter int filter, @OpHistoryFlags int historyFilter,
                double fractionToRemove, long beginTimeMillis, long endTimeMillis) {
            final int attributionCount = getAttributedOpsCount();
            for (int i = attributionCount - 1; i >= 0; i--) {
                final AttributedHistoricalOps attributionOps = getAttributedOpsAt(i);
                if ((filter & FILTER_BY_ATTRIBUTION_TAG) != 0 && !Objects.equals(attributionTag,
                        attributionOps.getTag())) {
                    mAttributedHistoricalOps.removeAt(i);
                } else {
                    attributionOps.filter(opNames, filter, historyFilter, fractionToRemove,
                            beginTimeMillis, endTimeMillis);
                    if (attributionOps.getOpCount() == 0) {
                        mAttributedHistoricalOps.removeAt(i);
                    }
                }
            }
        }

        private void accept(@NonNull HistoricalOpsVisitor visitor) {
            visitor.visitHistoricalPackageOps(this);
            final int attributionCount = getAttributedOpsCount();
            for (int i = 0; i < attributionCount; i++) {
                getAttributedOpsAt(i).accept(visitor);
            }
        }

        private boolean isEmpty() {
            final int attributionCount = getAttributedOpsCount();
            for (int i = attributionCount - 1; i >= 0; i--) {
                final AttributedHistoricalOps attributionOps = mAttributedHistoricalOps.valueAt(i);
                if (!attributionOps.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private void increaseAccessCount(int opCode, @Nullable String attributionTag,
                @UidState int uidState, @OpFlags int flags, long increment) {
            getOrCreateAttributedHistoricalOps(attributionTag).increaseAccessCount(
                    opCode, uidState, flags, increment);
        }

        private void increaseRejectCount(int opCode, @Nullable String attributionTag,
                @UidState int uidState, @OpFlags int flags, long increment) {
            getOrCreateAttributedHistoricalOps(attributionTag).increaseRejectCount(
                    opCode, uidState, flags, increment);
        }

        private void increaseAccessDuration(int opCode, @Nullable String attributionTag,
                @UidState int uidState, @OpFlags int flags, long increment) {
            getOrCreateAttributedHistoricalOps(attributionTag).increaseAccessDuration(
                    opCode, uidState, flags, increment);
        }

        private void addDiscreteAccess(int opCode, @Nullable String attributionTag,
                @UidState int uidState, @OpFlags int flag, long discreteAccessTime,
                long discreteAccessDuration) {
            getOrCreateAttributedHistoricalOps(attributionTag).addDiscreteAccess(opCode, uidState,
                    flag, discreteAccessTime, discreteAccessDuration);
        }

        /**
         * Gets the package name which the data represents.
         *
         * @return The package name which the data represents.
         */
        public @NonNull String getPackageName() {
            return mPackageName;
        }

        private @NonNull AttributedHistoricalOps getOrCreateAttributedHistoricalOps(
                @Nullable String attributionTag) {
            if (mAttributedHistoricalOps == null) {
                mAttributedHistoricalOps = new ArrayMap<>();
            }
            AttributedHistoricalOps historicalAttributionOp = mAttributedHistoricalOps.get(
                    attributionTag);
            if (historicalAttributionOp == null) {
                historicalAttributionOp = new AttributedHistoricalOps(attributionTag);
                mAttributedHistoricalOps.put(attributionTag, historicalAttributionOp);
            }
            return historicalAttributionOp;
        }

        /**
         * Gets number historical app ops.
         *
         * @return The number historical app ops.
         * @see #getOpAt(int)
         */
        public @IntRange(from = 0) int getOpCount() {
            int numOps = 0;
            int numAttributions = getAttributedOpsCount();

            for (int code = 0; code < _NUM_OP; code++) {
                String opName = opToPublicName(code);

                for (int attributionNum = 0; attributionNum < numAttributions; attributionNum++) {
                    if (getAttributedOpsAt(attributionNum).getOp(opName) != null) {
                        numOps++;
                        break;
                    }
                }
            }

            return numOps;
        }

        /**
         * Gets the historical op at a given index.
         *
         * <p>This combines the counts from all attributions.
         *
         * @param index The index to lookup.
         * @return The op at the given index.
         * @see #getOpCount()
         */
        public @NonNull HistoricalOp getOpAt(@IntRange(from = 0) int index) {
            int numOpsFound = 0;
            int numAttributions = getAttributedOpsCount();

            for (int code = 0; code < _NUM_OP; code++) {
                String opName = opToPublicName(code);

                for (int attributionNum = 0; attributionNum < numAttributions; attributionNum++) {
                    if (getAttributedOpsAt(attributionNum).getOp(opName) != null) {
                        if (numOpsFound == index) {
                            return getOp(opName);
                        } else {
                            numOpsFound++;
                            break;
                        }
                    }
                }
            }

            throw new IndexOutOfBoundsException();
        }

        /**
         * Gets the historical entry for a given op name.
         *
         * <p>This combines the counts from all attributions.
         *
         * @param opName The op name.
         * @return The historical entry for that op name.
         */
        public @Nullable HistoricalOp getOp(@NonNull String opName) {
            if (mAttributedHistoricalOps == null) {
                return null;
            }

            HistoricalOp combinedOp = null;
            int numAttributions = getAttributedOpsCount();
            for (int i = 0; i < numAttributions; i++) {
                HistoricalOp attributionOp = getAttributedOpsAt(i).getOp(opName);
                if (attributionOp != null) {
                    if (combinedOp == null) {
                        combinedOp = new HistoricalOp(attributionOp);
                    } else {
                        combinedOp.merge(attributionOp);
                    }
                }
            }

            return combinedOp;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel parcel, int flags) {
            parcel.writeString(mPackageName);
            parcel.writeTypedArrayMap(mAttributedHistoricalOps, flags);
        }

        public static final @android.annotation.NonNull Creator<HistoricalPackageOps> CREATOR =
                new Creator<HistoricalPackageOps>() {
            @Override
            public @NonNull HistoricalPackageOps createFromParcel(@NonNull Parcel parcel) {
                return new HistoricalPackageOps(parcel);
            }

            @Override
            public @NonNull HistoricalPackageOps[] newArray(int size) {
                return new HistoricalPackageOps[size];
            }
        };

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final HistoricalPackageOps other = (HistoricalPackageOps) obj;
            if (!mPackageName.equals(other.mPackageName)) {
                return false;
            }
            if (mAttributedHistoricalOps == null) {
                if (other.mAttributedHistoricalOps != null) {
                    return false;
                }
            } else if (!mAttributedHistoricalOps.equals(other.mAttributedHistoricalOps)) {
                return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = mPackageName != null ? mPackageName.hashCode() : 0;
            result = 31 * result + (mAttributedHistoricalOps != null
                    ? mAttributedHistoricalOps.hashCode() : 0);
            return result;
        }

        /**
         * Gets number of attributed historical ops.
         *
         * @return The number of attribution with historical ops.
         *
         * @see #getAttributedOpsAt(int)
         */
        public @IntRange(from = 0) int getAttributedOpsCount() {
            if (mAttributedHistoricalOps == null) {
                return 0;
            }
            return mAttributedHistoricalOps.size();
        }

        /**
         * Gets the attributed historical ops at a given index.
         *
         * @param index The index.
         *
         * @return The historical attribution ops at the given index.
         *
         * @see #getAttributedOpsCount()
         */
        public @NonNull AttributedHistoricalOps getAttributedOpsAt(@IntRange(from = 0) int index) {
            if (mAttributedHistoricalOps == null) {
                throw new IndexOutOfBoundsException();
            }
            return mAttributedHistoricalOps.valueAt(index);
        }

        /**
         * Gets the attributed historical ops for a given attribution tag.
         *
         * @param attributionTag The attribution tag.
         *
         * @return The historical ops for the attribution.
         */
        public @Nullable AttributedHistoricalOps getAttributedOps(@Nullable String attributionTag) {
            if (mAttributedHistoricalOps == null) {
                return null;
            }
            return mAttributedHistoricalOps.get(attributionTag);
        }
    }

    /**
     * This class represents historical app op information about a attribution in a package.
     *
     * @hide
     */
    @SystemApi
    /* codegen verifier cannot deal with nested class parameters
    @DataClass(genHiddenConstructor = true,
            genEqualsHashCode = true, genHiddenCopyConstructor = true) */
    @DataClass.Suppress("getHistoricalOps")
    public static final class AttributedHistoricalOps implements Parcelable {
        /** {@link Context#createAttributionContext attribution} tag */
        private final @Nullable String mTag;

        /** Ops for this attribution */
        private @Nullable ArrayMap<String, HistoricalOp> mHistoricalOps;

        /** @hide */
        public AttributedHistoricalOps(@NonNull String tag) {
            mTag = tag;
        }

        private AttributedHistoricalOps(@NonNull AttributedHistoricalOps other) {
            mTag = other.mTag;
            final int opCount = other.getOpCount();
            for (int i = 0; i < opCount; i++) {
                final HistoricalOp origOp = other.getOpAt(i);
                final HistoricalOp cloneOp = new HistoricalOp(origOp);
                if (mHistoricalOps == null) {
                    mHistoricalOps = new ArrayMap<>(opCount);
                }
                mHistoricalOps.put(cloneOp.getOpName(), cloneOp);
            }
        }

        private @Nullable AttributedHistoricalOps splice(double fractionToRemove) {
            AttributedHistoricalOps splice = null;
            final int opCount = getOpCount();
            for (int i = 0; i < opCount; i++) {
                final HistoricalOp origOps = getOpAt(i);
                final HistoricalOp spliceOps = origOps.splice(fractionToRemove);
                if (spliceOps != null) {
                    if (splice == null) {
                        splice = new AttributedHistoricalOps(mTag, null);
                    }
                    if (splice.mHistoricalOps == null) {
                        splice.mHistoricalOps = new ArrayMap<>();
                    }
                    splice.mHistoricalOps.put(spliceOps.getOpName(), spliceOps);
                }
            }
            return splice;
        }

        private void merge(@NonNull AttributedHistoricalOps other) {
            final int opCount = other.getOpCount();
            for (int i = 0; i < opCount; i++) {
                final HistoricalOp otherOp = other.getOpAt(i);
                final HistoricalOp thisOp = getOp(otherOp.getOpName());
                if (thisOp != null) {
                    thisOp.merge(otherOp);
                } else {
                    if (mHistoricalOps == null) {
                        mHistoricalOps = new ArrayMap<>();
                    }
                    mHistoricalOps.put(otherOp.getOpName(), otherOp);
                }
            }
        }

        private void filter(@Nullable String[] opNames, @HistoricalOpsRequestFilter int filter,
                @OpHistoryFlags int historyFilter, double scaleFactor, long beginTimeMillis,
                long endTimeMillis) {
            final int opCount = getOpCount();
            for (int i = opCount - 1; i >= 0; i--) {
                final HistoricalOp op = mHistoricalOps.valueAt(i);
                if ((filter & FILTER_BY_OP_NAMES) != 0 && !ArrayUtils.contains(opNames,
                        op.getOpName())) {
                    mHistoricalOps.removeAt(i);
                } else {
                    op.filter(historyFilter, scaleFactor, beginTimeMillis, endTimeMillis);
                }
            }
        }

        private boolean isEmpty() {
            final int opCount = getOpCount();
            for (int i = opCount - 1; i >= 0; i--) {
                final HistoricalOp op = mHistoricalOps.valueAt(i);
                if (!op.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        private void increaseAccessCount(int opCode, @UidState int uidState,
                @OpFlags int flags, long increment) {
            getOrCreateHistoricalOp(opCode).increaseAccessCount(uidState, flags, increment);
        }

        private void increaseRejectCount(int opCode, @UidState int uidState,
                @OpFlags int flags, long increment) {
            getOrCreateHistoricalOp(opCode).increaseRejectCount(uidState, flags, increment);
        }

        private void increaseAccessDuration(int opCode, @UidState int uidState,
                @OpFlags int flags, long increment) {
            getOrCreateHistoricalOp(opCode).increaseAccessDuration(uidState, flags, increment);
        }

        private void addDiscreteAccess(int opCode, @UidState int uidState, @OpFlags int flag,
                long discreteAccessTime, long discreteAccessDuration) {
            getOrCreateHistoricalOp(opCode).addDiscreteAccess(uidState,flag, discreteAccessTime,
                    discreteAccessDuration);
        }

        /**
         * Gets number historical app ops.
         *
         * @return The number historical app ops.
         * @see #getOpAt(int)
         */
        public @IntRange(from = 0) int getOpCount() {
            if (mHistoricalOps == null) {
                return 0;
            }
            return mHistoricalOps.size();
        }

        /**
         * Gets the historical op at a given index.
         *
         * @param index The index to lookup.
         * @return The op at the given index.
         * @see #getOpCount()
         */
        public @NonNull HistoricalOp getOpAt(@IntRange(from = 0) int index) {
            if (mHistoricalOps == null) {
                throw new IndexOutOfBoundsException();
            }
            return mHistoricalOps.valueAt(index);
        }

        /**
         * Gets the historical entry for a given op name.
         *
         * @param opName The op name.
         * @return The historical entry for that op name.
         */
        public @Nullable HistoricalOp getOp(@NonNull String opName) {
            if (mHistoricalOps == null) {
                return null;
            }
            return mHistoricalOps.get(opName);
        }

        private void accept(@NonNull HistoricalOpsVisitor visitor) {
            visitor.visitHistoricalAttributionOps(this);
            final int opCount = getOpCount();
            for (int i = 0; i < opCount; i++) {
                getOpAt(i).accept(visitor);
            }
        }

        private @NonNull HistoricalOp getOrCreateHistoricalOp(int opCode) {
            if (mHistoricalOps == null) {
                mHistoricalOps = new ArrayMap<>();
            }
            final String opStr = sOpToString[opCode];
            HistoricalOp op = mHistoricalOps.get(opStr);
            if (op == null) {
                op = new HistoricalOp(opCode);
                mHistoricalOps.put(opStr, op);
            }
            return op;
        }

        // Code below generated by codegen v1.0.14.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/app/AppOpsManager.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        /**
         * Creates a new HistoricalAttributionOps.
         *
         * @param tag
         *   {@link Context#createAttributionContext attribution} tag
         * @param historicalOps
         *   Ops for this attribution
         * @hide
         */
        @DataClass.Generated.Member
        public AttributedHistoricalOps(
                @Nullable String tag,
                @Nullable ArrayMap<String,HistoricalOp> historicalOps) {
            this.mTag = tag;
            this.mHistoricalOps = historicalOps;

            // onConstructed(); // You can define this method to get a callback
        }

        /**
         * {@link Context#createAttributionContext attribution} tag
         */
        @DataClass.Generated.Member
        public @Nullable String getTag() {
            return mTag;
        }

        @Override
        @DataClass.Generated.Member
        public boolean equals(@Nullable Object o) {
            // You can override field equality logic by defining either of the methods like:
            // boolean fieldNameEquals(HistoricalAttributionOps other) { ... }
            // boolean fieldNameEquals(FieldType otherValue) { ... }

            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            @SuppressWarnings("unchecked")
            AttributedHistoricalOps that = (AttributedHistoricalOps) o;
            //noinspection PointlessBooleanExpression
            return true
                    && Objects.equals(mTag, that.mTag)
                    && Objects.equals(mHistoricalOps, that.mHistoricalOps);
        }

        @Override
        @DataClass.Generated.Member
        public int hashCode() {
            // You can override field hashCode logic by defining methods like:
            // int fieldNameHashCode() { ... }

            int _hash = 1;
            _hash = 31 * _hash + Objects.hashCode(mTag);
            _hash = 31 * _hash + Objects.hashCode(mHistoricalOps);
            return _hash;
        }

        @Override
        @DataClass.Generated.Member
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            // You can override field parcelling by defining methods like:
            // void parcelFieldName(Parcel dest, int flags) { ... }

            byte flg = 0;
            if (mTag != null) flg |= 0x1;
            if (mHistoricalOps != null) flg |= 0x2;
            dest.writeByte(flg);
            if (mTag != null) dest.writeString(mTag);
            if (mHistoricalOps != null) dest.writeMap(mHistoricalOps);
        }

        @Override
        @DataClass.Generated.Member
        public int describeContents() { return 0; }

        /** @hide */
        @SuppressWarnings({"unchecked", "RedundantCast"})
        @DataClass.Generated.Member
        /* package-private */ AttributedHistoricalOps(@NonNull Parcel in) {
            // You can override field unparcelling by defining methods like:
            // static FieldType unparcelFieldName(Parcel in) { ... }

            byte flg = in.readByte();
            String attributionTag = (flg & 0x1) == 0 ? null : in.readString();
            ArrayMap<String,HistoricalOp> historicalOps = null;
            if ((flg & 0x2) != 0) {
                historicalOps = new ArrayMap();
                in.readMap(historicalOps, HistoricalOp.class.getClassLoader());
            }

            this.mTag = attributionTag;
            this.mHistoricalOps = historicalOps;

            // onConstructed(); // You can define this method to get a callback
        }

        @DataClass.Generated.Member
        public static final @NonNull Parcelable.Creator<AttributedHistoricalOps> CREATOR
                = new Parcelable.Creator<AttributedHistoricalOps>() {
            @Override
            public AttributedHistoricalOps[] newArray(int size) {
                return new AttributedHistoricalOps[size];
            }

            @Override
            public AttributedHistoricalOps createFromParcel(@NonNull Parcel in) {
                return new AttributedHistoricalOps(in);
            }
        };

        /*
        @DataClass.Generated(
                time = 1578113234821L,
                codegenVersion = "1.0.14",
                sourceFile = "frameworks/base/core/java/android/app/AppOpsManager.java",
                inputSignatures = "private final @android.annotation.Nullable java.lang.String mAttributionTag\nprivate @android.annotation.Nullable android.util.ArrayMap<java.lang.String,android.app.HistoricalOp> mHistoricalOps\nprivate @android.annotation.Nullable android.app.HistoricalAttributionOps splice(double)\nprivate  void merge(android.app.HistoricalAttributionOps)\nprivate  void filter(java.lang.String[],int,double)\nprivate  boolean isEmpty()\nprivate  void increaseAccessCount(int,int,int,long)\nprivate  void increaseRejectCount(int,int,int,long)\nprivate  void increaseAccessDuration(int,int,int,long)\npublic @android.annotation.IntRange(from=0L) int getOpCount()\npublic @android.annotation.NonNull android.app.HistoricalOp getOpAt(int)\npublic @android.annotation.Nullable android.app.HistoricalOp getOp(java.lang.String)\nprivate  void accept(android.app.HistoricalOpsVisitor)\nprivate @android.annotation.NonNull android.app.HistoricalOp getOrCreateHistoricalOp(int)\nclass HistoricalAttributionOps extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genHiddenConstructor=true, genEqualsHashCode=true, genHiddenCopyConstructor=true)")
        @Deprecated
        private void __metadata() {}
        */

        //@formatter:on
        // End of generated code

    }

    /**
     * This class represents historical information about an app op.
     *
     * @hide
     */
    @SystemApi
    public static final class HistoricalOp implements Parcelable {
        private final int mOp;
        private @Nullable LongSparseLongArray mAccessCount;
        private @Nullable LongSparseLongArray mRejectCount;
        private @Nullable LongSparseLongArray mAccessDuration;

        /** Discrete Ops for this Op */
        private @Nullable List<AttributedOpEntry> mDiscreteAccesses;

        /** @hide */
        public HistoricalOp(int op) {
            mOp = op;
        }

        private HistoricalOp(@NonNull HistoricalOp other) {
            mOp = other.mOp;
            if (other.mAccessCount != null) {
                mAccessCount = other.mAccessCount.clone();
            }
            if (other.mRejectCount != null) {
                mRejectCount = other.mRejectCount.clone();
            }
            if (other.mAccessDuration != null) {
                mAccessDuration = other.mAccessDuration.clone();
            }
            final int historicalOpCount = other.getDiscreteAccessCount();
            for (int i = 0; i < historicalOpCount; i++) {
                final AttributedOpEntry origOp = other.getDiscreteAccessAt(i);
                final AttributedOpEntry cloneOp = new AttributedOpEntry(origOp);
                getOrCreateDiscreteAccesses().add(cloneOp);
            }
        }

        private HistoricalOp(@NonNull Parcel parcel) {
            mOp = parcel.readInt();
            mAccessCount = readLongSparseLongArrayFromParcel(parcel);
            mRejectCount = readLongSparseLongArrayFromParcel(parcel);
            mAccessDuration = readLongSparseLongArrayFromParcel(parcel);
            mDiscreteAccesses = readDiscreteAccessArrayFromParcel(parcel);
        }

        private void filter(@OpHistoryFlags int historyFlag, double scaleFactor,
                long beginTimeMillis, long endTimeMillis) {
            if ((historyFlag & HISTORY_FLAG_AGGREGATE) == 0) {
                mAccessCount = null;
                mRejectCount = null;
                mAccessDuration = null;
            } else {
                scale(mAccessCount, scaleFactor);
                scale(mRejectCount, scaleFactor);
                scale(mAccessDuration, scaleFactor);
            }
            if ((historyFlag & HISTORY_FLAG_DISCRETE) == 0) {
                mDiscreteAccesses = null;
                return;
            }
            final int discreteOpCount = getDiscreteAccessCount();
            for (int i = discreteOpCount - 1; i >= 0; i--) {
                final AttributedOpEntry op = mDiscreteAccesses.get(i);
                long opBeginTime = op.getLastAccessTime(OP_FLAGS_ALL);
                long opEndTime = opBeginTime + op.getLastDuration(OP_FLAGS_ALL);
                opEndTime = max(opBeginTime, opEndTime);
                if (opEndTime < beginTimeMillis || opBeginTime > endTimeMillis) {
                    mDiscreteAccesses.remove(i);
                }
            }
        }

        private boolean isEmpty() {
            return !hasData(mAccessCount)
                    && !hasData(mRejectCount)
                    && !hasData(mAccessDuration)
                    && (mDiscreteAccesses == null);
        }

        private boolean hasData(@NonNull LongSparseLongArray array) {
            return array != null && array.size() > 0;
        }

        private @Nullable HistoricalOp splice(double fractionToRemove) {
            final HistoricalOp splice = new HistoricalOp(mOp);
            splice(mAccessCount, splice::getOrCreateAccessCount, fractionToRemove);
            splice(mRejectCount, splice::getOrCreateRejectCount, fractionToRemove);
            splice(mAccessDuration, splice::getOrCreateAccessDuration, fractionToRemove);
            return splice;
        }

        private static void splice(@Nullable LongSparseLongArray sourceContainer,
                @NonNull Supplier<LongSparseLongArray> destContainerProvider,
                    double fractionToRemove) {
            if (sourceContainer != null) {
                final int size = sourceContainer.size();
                for (int i = 0; i < size; i++) {
                    final long key = sourceContainer.keyAt(i);
                    final long value = sourceContainer.valueAt(i);
                    final long removedFraction = Math.round(value * fractionToRemove);
                    if (removedFraction > 0) {
                        destContainerProvider.get().put(key, removedFraction);
                        sourceContainer.put(key, value - removedFraction);
                    }
                }
            }
        }

        private void merge(@NonNull HistoricalOp other) {
            merge(this::getOrCreateAccessCount, other.mAccessCount);
            merge(this::getOrCreateRejectCount, other.mRejectCount);
            merge(this::getOrCreateAccessDuration, other.mAccessDuration);

            if (other.mDiscreteAccesses == null) {
                return;
            }
            if (mDiscreteAccesses == null) {
                mDiscreteAccesses = new ArrayList(other.mDiscreteAccesses);
                return;
            }
            List<AttributedOpEntry> historicalDiscreteAccesses = new ArrayList<>();
            final int otherHistoricalOpCount = other.getDiscreteAccessCount();
            final int historicalOpCount = getDiscreteAccessCount();
            int i = 0;
            int j = 0;
            while (i < otherHistoricalOpCount || j < historicalOpCount) {
                if (i == otherHistoricalOpCount) {
                    historicalDiscreteAccesses.add(mDiscreteAccesses.get(j++));
                } else if (j == historicalOpCount) {
                    historicalDiscreteAccesses.add(other.mDiscreteAccesses.get(i++));
                } else if (mDiscreteAccesses.get(j).getLastAccessTime(OP_FLAGS_ALL)
                        < other.mDiscreteAccesses.get(i).getLastAccessTime(OP_FLAGS_ALL)) {
                    historicalDiscreteAccesses.add(mDiscreteAccesses.get(j++));
                } else {
                    historicalDiscreteAccesses.add(other.mDiscreteAccesses.get(i++));
                }
            }
            mDiscreteAccesses = deduplicateDiscreteEvents(historicalDiscreteAccesses);
        }

        private void increaseAccessCount(@UidState int uidState, @OpFlags int flags,
                long increment) {
            increaseCount(getOrCreateAccessCount(), uidState, flags, increment);
        }

        private void increaseRejectCount(@UidState int uidState, @OpFlags int flags,
                long increment) {
            increaseCount(getOrCreateRejectCount(), uidState, flags, increment);
        }

        private void increaseAccessDuration(@UidState int uidState, @OpFlags int flags,
                long increment) {
            increaseCount(getOrCreateAccessDuration(), uidState, flags, increment);
        }

        private void increaseCount(@NonNull LongSparseLongArray counts,
                @UidState int uidState, @OpFlags int flags, long increment) {
            while (flags != 0) {
                final int flag = 1 << Integer.numberOfTrailingZeros(flags);
                flags &= ~flag;
                final long key = makeKey(uidState, flag);
                counts.put(key, counts.get(key) + increment);
            }
        }

        private void addDiscreteAccess(@UidState int uidState, @OpFlags int flag,
                long discreteAccessTime, long discreteAccessDuration) {
            List<AttributedOpEntry> discreteAccesses = getOrCreateDiscreteAccesses();
            LongSparseArray<NoteOpEvent> accessEvents = new LongSparseArray<>();
            long key = makeKey(uidState, flag);
            NoteOpEvent note = new NoteOpEvent(discreteAccessTime, discreteAccessDuration, null);
            accessEvents.append(key, note);
            AttributedOpEntry access = new AttributedOpEntry(mOp, false, accessEvents, null);
            int insertionPoint = discreteAccesses.size() - 1;
            for (; insertionPoint >= 0; insertionPoint--) {
                if (discreteAccesses.get(insertionPoint).getLastAccessTime(OP_FLAGS_ALL)
                        < discreteAccessTime) {
                    break;
                }
            }
            insertionPoint++;
            if (insertionPoint < discreteAccesses.size() && discreteAccesses.get(
                    insertionPoint).getLastAccessTime(OP_FLAGS_ALL) == discreteAccessTime) {
                discreteAccesses.set(insertionPoint, mergeAttributedOpEntries(
                        Arrays.asList(discreteAccesses.get(insertionPoint), access)));
            } else {
                discreteAccesses.add(insertionPoint, access);
            }
        }

        /**
         * Gets the op name.
         *
         * @return The op name.
         */
        public @NonNull String getOpName() {
            return sOpToString[mOp];
        }

        /** @hide */
        public int getOpCode() {
            return mOp;
        }

        /**
         * Gets number of discrete historical app ops.
         *
         * @return The number historical app ops.
         * @see #getDiscreteAccessAt(int)
         */
        public @IntRange(from = 0) int getDiscreteAccessCount() {
            if (mDiscreteAccesses == null) {
                return 0;
            }
            return mDiscreteAccesses.size();
        }

        /**
         * Gets the historical op at a given index.
         *
         * @param index The index to lookup.
         * @return The op at the given index.
         * @see #getDiscreteAccessCount()
         */
        public @NonNull AttributedOpEntry getDiscreteAccessAt(@IntRange(from = 0) int index) {
            if (mDiscreteAccesses == null) {
                throw new IndexOutOfBoundsException();
            }
            return mDiscreteAccesses.get(index);
        }

        /**
         * Gets the number times the op was accessed (performed) in the foreground.
         *
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         * @return The times the op was accessed in the foreground.
         *
         * @see #getBackgroundAccessCount(int)
         * @see #getAccessCount(int, int, int)
         */
        public long getForegroundAccessCount(@OpFlags int flags) {
            return sumForFlagsInStates(mAccessCount, MAX_PRIORITY_UID_STATE,
                    resolveFirstUnrestrictedUidState(mOp), flags);
        }

        /**
         * Gets the discrete events the op was accessed (performed) in the foreground.
         *
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         * @return The list of discrete ops accessed in the foreground.
         *
         * @see #getBackgroundDiscreteAccesses(int)
         * @see #getDiscreteAccesses(int, int, int)
         */
        @NonNull
        public List<AttributedOpEntry> getForegroundDiscreteAccesses(@OpFlags int flags) {
            return listForFlagsInStates(mDiscreteAccesses, MAX_PRIORITY_UID_STATE,
                    resolveFirstUnrestrictedUidState(mOp), flags);
        }

        /**
         * Gets the number times the op was accessed (performed) in the background.
         *
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         * @return The times the op was accessed in the background.
         *
         * @see #getForegroundAccessCount(int)
         * @see #getAccessCount(int, int, int)
         */
        public long getBackgroundAccessCount(@OpFlags int flags) {
            return sumForFlagsInStates(mAccessCount, resolveLastRestrictedUidState(mOp),
                    MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Gets the discrete events the op was accessed (performed) in the background.
         *
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         * @return The list of discrete ops accessed in the background.
         *
         * @see #getForegroundDiscreteAccesses(int)
         * @see #getDiscreteAccesses(int, int, int)
         */
        @NonNull
        public List<AttributedOpEntry> getBackgroundDiscreteAccesses(@OpFlags int flags) {
            return listForFlagsInStates(mDiscreteAccesses, resolveLastRestrictedUidState(mOp),
                    MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Gets the number times the op was accessed (performed) for a
         * range of uid states.
         *
         * @param fromUidState The UID state from which to query. Could be one of
         * {@link #UID_STATE_PERSISTENT}, {@link #UID_STATE_TOP},
         * {@link #UID_STATE_FOREGROUND_SERVICE}, {@link #UID_STATE_FOREGROUND},
         * {@link #UID_STATE_BACKGROUND}, {@link #UID_STATE_CACHED}.
         * @param toUidState The UID state to which to query.
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         *
         * @return The times the op was accessed for the given UID state.
         *
         * @see #getForegroundAccessCount(int)
         * @see #getBackgroundAccessCount(int)
         */
        public long getAccessCount(@UidState int fromUidState, @UidState int toUidState,
                @OpFlags int flags) {
            return sumForFlagsInStates(mAccessCount, fromUidState, toUidState, flags);
        }

        /**
         * Gets the discrete events the op was accessed (performed) for a
         * range of uid states.
         *
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         * @return The discrete the op was accessed in the background.
         *
         * @see #getBackgroundDiscreteAccesses(int)
         * @see #getForegroundDiscreteAccesses(int)
         */
        @NonNull
        public List<AttributedOpEntry> getDiscreteAccesses(@UidState int fromUidState,
                @UidState int toUidState, @OpFlags int flags) {
            return listForFlagsInStates(mDiscreteAccesses, fromUidState, toUidState, flags);
        }

        /**
         * Gets the number times the op was rejected in the foreground.
         *
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         * @return The times the op was rejected in the foreground.
         *
         * @see #getBackgroundRejectCount(int)
         * @see #getRejectCount(int, int, int)
         */
        public long getForegroundRejectCount(@OpFlags int flags) {
            return sumForFlagsInStates(mRejectCount, MAX_PRIORITY_UID_STATE,
                    resolveFirstUnrestrictedUidState(mOp), flags);
        }

        /**
         * Gets the number times the op was rejected in the background.
         *
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         * @return The times the op was rejected in the background.
         *
         * @see #getForegroundRejectCount(int)
         * @see #getRejectCount(int, int, int)
         */
        public long getBackgroundRejectCount(@OpFlags int flags) {
            return sumForFlagsInStates(mRejectCount, resolveLastRestrictedUidState(mOp),
                    MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Gets the number times the op was rejected for a given range of UID states.
         *
         * @param fromUidState The UID state from which to query. Could be one of
         * {@link #UID_STATE_PERSISTENT}, {@link #UID_STATE_TOP},
         * {@link #UID_STATE_FOREGROUND_SERVICE}, {@link #UID_STATE_FOREGROUND},
         * {@link #UID_STATE_BACKGROUND}, {@link #UID_STATE_CACHED}.
         * @param toUidState The UID state to which to query.
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         *
         * @return The times the op was rejected for the given UID state.
         *
         * @see #getForegroundRejectCount(int)
         * @see #getBackgroundRejectCount(int)
         */
        public long getRejectCount(@UidState int fromUidState, @UidState int toUidState,
                @OpFlags int flags) {
            return sumForFlagsInStates(mRejectCount, fromUidState, toUidState, flags);
        }

        /**
         * Gets the total duration the app op was accessed (performed) in the foreground.
         * The duration is in wall time.
         *
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         * @return The total duration the app op was accessed in the foreground.
         *
         * @see #getBackgroundAccessDuration(int)
         * @see #getAccessDuration(int, int, int)
         */
        public long getForegroundAccessDuration(@OpFlags int flags) {
            return sumForFlagsInStates(mAccessDuration, MAX_PRIORITY_UID_STATE,
                    resolveFirstUnrestrictedUidState(mOp), flags);
        }

        /**
         * Gets the total duration the app op was accessed (performed) in the background.
         * The duration is in wall time.
         *
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         * @return The total duration the app op was accessed in the background.
         *
         * @see #getForegroundAccessDuration(int)
         * @see #getAccessDuration(int, int, int)
         */
        public long getBackgroundAccessDuration(@OpFlags int flags) {
            return sumForFlagsInStates(mAccessDuration, resolveLastRestrictedUidState(mOp),
                    MIN_PRIORITY_UID_STATE, flags);
        }

        /**
         * Gets the total duration the app op was accessed (performed) for a given
         * range of UID states. The duration is in wall time.
         *
         * @param fromUidState The UID state from which to query. Could be one of
         * {@link #UID_STATE_PERSISTENT}, {@link #UID_STATE_TOP},
         * {@link #UID_STATE_FOREGROUND_SERVICE}, {@link #UID_STATE_FOREGROUND},
         * {@link #UID_STATE_BACKGROUND}, {@link #UID_STATE_CACHED}.
         * @param toUidState The UID state from which to query.
         * @param flags The flags which are any combination of
         * {@link #OP_FLAG_SELF}, {@link #OP_FLAG_TRUSTED_PROXY},
         * {@link #OP_FLAG_UNTRUSTED_PROXY}, {@link #OP_FLAG_TRUSTED_PROXIED},
         * {@link #OP_FLAG_UNTRUSTED_PROXIED}. You can use {@link #OP_FLAGS_ALL}
         * for any flag.
         *
         * @return The total duration the app op was accessed for the given UID state.
         *
         * @see #getForegroundAccessDuration(int)
         * @see #getBackgroundAccessDuration(int)
         */
        public long getAccessDuration(@UidState int fromUidState, @UidState int toUidState,
                @OpFlags int flags) {
            return sumForFlagsInStates(mAccessDuration, fromUidState, toUidState, flags);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeInt(mOp);
            writeLongSparseLongArrayToParcel(mAccessCount, parcel);
            writeLongSparseLongArrayToParcel(mRejectCount, parcel);
            writeLongSparseLongArrayToParcel(mAccessDuration, parcel);
            writeDiscreteAccessArrayToParcel(mDiscreteAccesses, parcel, flags);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final HistoricalOp other = (HistoricalOp) obj;
            if (mOp != other.mOp) {
                return false;
            }
            if (!equalsLongSparseLongArray(mAccessCount, other.mAccessCount)) {
                return false;
            }
            if (!equalsLongSparseLongArray(mRejectCount, other.mRejectCount)) {
                return false;
            }
            if (!equalsLongSparseLongArray(mAccessDuration, other.mAccessDuration)) {
                return false;
            }
            return mDiscreteAccesses == null ? (other.mDiscreteAccesses == null ? true
                    : false) : mDiscreteAccesses.equals(other.mDiscreteAccesses);
        }

        @Override
        public int hashCode() {
            int result = mOp;
            result = 31 * result + Objects.hashCode(mAccessCount);
            result = 31 * result + Objects.hashCode(mRejectCount);
            result = 31 * result + Objects.hashCode(mAccessDuration);
            result = 31 * result + Objects.hashCode(mDiscreteAccesses);
            return result;
        }

        private void accept(@NonNull HistoricalOpsVisitor visitor) {
            visitor.visitHistoricalOp(this);
        }

        private @NonNull LongSparseLongArray getOrCreateAccessCount() {
            if (mAccessCount == null) {
                mAccessCount = new LongSparseLongArray();
            }
            return mAccessCount;
        }

        private @NonNull LongSparseLongArray getOrCreateRejectCount() {
            if (mRejectCount == null) {
                mRejectCount = new LongSparseLongArray();
            }
            return mRejectCount;
        }

        private @NonNull LongSparseLongArray getOrCreateAccessDuration() {
            if (mAccessDuration == null) {
                mAccessDuration = new LongSparseLongArray();
            }
            return mAccessDuration;
        }

        private @NonNull List<AttributedOpEntry> getOrCreateDiscreteAccesses() {
            if (mDiscreteAccesses == null) {
                mDiscreteAccesses = new ArrayList<>();
            }
            return mDiscreteAccesses;
        }

        /**
         * Multiplies the entries in the array with the passed in scale factor and
         * rounds the result at up 0.5 boundary.
         *
         * @param data The data to scale.
         * @param scaleFactor The scale factor.
         */
        private static void scale(@NonNull LongSparseLongArray data, double scaleFactor) {
            if (data != null) {
                final int size = data.size();
                for (int i = 0; i < size; i++) {
                    data.put(data.keyAt(i), (long) HistoricalOps.round(
                            (double) data.valueAt(i) * scaleFactor));
                }
            }
        }

        /**
         * Merges two arrays while lazily acquiring the destination.
         *
         * @param thisSupplier The destination supplier.
         * @param other The array to merge in.
         */
        private static void merge(@NonNull Supplier<LongSparseLongArray> thisSupplier,
                @Nullable LongSparseLongArray other) {
            if (other != null) {
                final int otherSize = other.size();
                for (int i = 0; i < otherSize; i++) {
                    final LongSparseLongArray that = thisSupplier.get();
                    final long otherKey = other.keyAt(i);
                    final long otherValue = other.valueAt(i);
                    that.put(otherKey, that.get(otherKey) + otherValue);
                }
            }
        }

        /** @hide */
        public @Nullable LongSparseArray<Object> collectKeys() {
            LongSparseArray<Object> result = AppOpsManager.collectKeys(mAccessCount,
                null /*result*/);
            result = AppOpsManager.collectKeys(mRejectCount, result);
            result = AppOpsManager.collectKeys(mAccessDuration, result);
            return result;
        }

        public static final @android.annotation.NonNull Creator<HistoricalOp> CREATOR =
                new Creator<HistoricalOp>() {
            @Override
            public @NonNull HistoricalOp createFromParcel(@NonNull Parcel source) {
                return new HistoricalOp(source);
            }

            @Override
            public @NonNull HistoricalOp[] newArray(int size) {
                return new HistoricalOp[size];
            }
        };
    }

    /**
     * Computes the sum of the counts for the given flags in between the begin and
     * end UID states.
     *
     * @param counts The data array.
     * @param beginUidState The beginning UID state (inclusive).
     * @param endUidState The end UID state (inclusive).
     * @param flags The UID flags.
     * @return The sum.
     */
    private static long sumForFlagsInStates(@Nullable LongSparseLongArray counts,
            @UidState int beginUidState, @UidState int endUidState, @OpFlags int flags) {
        if (counts == null) {
            return 0;
        }
        long sum = 0;
        while (flags != 0) {
            final int flag = 1 << Integer.numberOfTrailingZeros(flags);
            flags &= ~flag;
            for (int uidState : UID_STATES) {
                if (uidState < beginUidState || uidState > endUidState) {
                    continue;
                }
                final long key = makeKey(uidState, flag);
                sum += counts.get(key);
            }
        }
        return sum;
    }

    /**
     * Returns list of events filtered by UidState and UID flags.
     *
     * @param accesses The events list.
     * @param beginUidState The beginning UID state (inclusive).
     * @param endUidState The end UID state (inclusive).
     * @param flags The UID flags.
     * @return filtered list of events.
     */
    private static List<AttributedOpEntry> listForFlagsInStates(List<AttributedOpEntry> accesses,
            @UidState int beginUidState, @UidState int endUidState, @OpFlags int flags) {
        List<AttributedOpEntry> result = new ArrayList<>();
        if (accesses == null) {
            return result;
        }
        int nAccesses = accesses.size();
        for (int i = 0; i < nAccesses; i++) {
            AttributedOpEntry entry = accesses.get(i);
            if (entry.getLastAccessTime(beginUidState, endUidState, flags) == -1) {
                continue;
            }
            result.add(entry);
        }
        return deduplicateDiscreteEvents(result);
    }

    /**
     * Callback for notification of changes to operation state.
     */
    public interface OnOpChangedListener {
        public void onOpChanged(String op, String packageName);
    }

    /**
     * Callback for notification of changes to operation active state.
     */
    public interface OnOpActiveChangedListener {
        /**
         * Called when the active state of an app-op changes.
         *
         * @param op The operation that changed.
         * @param packageName The package performing the operation.
         * @param active Whether the operation became active or inactive.
         */
        void onOpActiveChanged(@NonNull String op, int uid, @NonNull String packageName,
                boolean active);
    }

    /**
     * Callback for notification of an op being noted.
     *
     * @hide
     */
    public interface OnOpNotedListener {
        /**
         * Called when an op was noted.
         * @param code The op code.
         * @param uid The UID performing the operation.
         * @param packageName The package performing the operation.
         * @param attributionTag The attribution tag performing the operation.
         * @param flags The flags of this op
         * @param result The result of the note.
         */
        void onOpNoted(int code, int uid, String packageName, String attributionTag,
                @OpFlags int flags, @Mode int result);
    }

    /**
     * Callback for notification of changes to operation state.
     * This allows you to see the raw op codes instead of strings.
     * @hide
     */
    public static class OnOpChangedInternalListener implements OnOpChangedListener {
        public void onOpChanged(String op, String packageName) { }
        public void onOpChanged(int op, String packageName) { }
    }

    /**
     * Callback for notification of changes to operation state.
     * This allows you to see the raw op codes instead of strings.
     * @hide
     */
    public interface OnOpActiveChangedInternalListener extends OnOpActiveChangedListener {
        default void onOpActiveChanged(String op, int uid, String packageName, boolean active) { }
        default void onOpActiveChanged(int op, int uid, String packageName, boolean active) { }
    }

    /**
     * Callback for notification of an op being started.
     *
     * @hide
     */
    public interface OnOpStartedListener {
        /**
         * Called when an op was started.
         *
         * Note: This is only for op starts. It is not called when an op is noted or stopped.
         * @param op The op code.
         * @param uid The UID performing the operation.
         * @param packageName The package performing the operation.
         * @param attributionTag The attribution tag performing the operation.
         * @param flags The flags of this op
         * @param result The result of the start.
         */
        void onOpStarted(int op, int uid, String packageName, String attributionTag,
                @OpFlags int flags, @Mode int result);
    }

    AppOpsManager(Context context, IAppOpsService service) {
        mContext = context;
        mService = service;

        if (mContext != null) {
            final PackageManager pm = mContext.getPackageManager();
            try {
                if (pm != null && pm.checkPermission(Manifest.permission.READ_DEVICE_CONFIG,
                        mContext.getPackageName()) == PackageManager.PERMISSION_GRANTED) {
                    DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_PRIVACY,
                            mContext.getMainExecutor(), properties -> {
                                if (properties.getKeyset().contains(FULL_LOG)) {
                                    sFullLog = properties.getBoolean(FULL_LOG, false);
                                }
                            });
                    return;
                }
            } catch (Exception e) {
                // This manager was made before DeviceConfig is ready, so it's a low-level
                // system app. We likely don't care about its logs.
            }
        }
        sFullLog = false;
    }

    /**
     * Retrieve current operation state for all applications.
     *
     * The mode of the ops returned are set for the package but may not reflect their effective
     * state due to UID policy or because it's controlled by a different global op.
     *
     * Use {@link #unsafeCheckOp(String, int, String)}} or
     * {@link #noteOp(String, int, String, String, String)} if the effective mode is needed.
     *
     * @param ops The set of operations you are interested in, or null if you want all of them.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    public @NonNull List<AppOpsManager.PackageOps> getPackagesForOps(@Nullable String[] ops) {
        final int opCount = ops.length;
        final int[] opCodes = new int[opCount];
        for (int i = 0; i < opCount; i++) {
            opCodes[i] = sOpStrToOp.get(ops[i]);
        }
        final List<AppOpsManager.PackageOps> result = getPackagesForOps(opCodes);
        return (result != null) ? result : Collections.emptyList();
    }

    /**
     * Retrieve current operation state for all applications.
     *
     * The mode of the ops returned are set for the package but may not reflect their effective
     * state due to UID policy or because it's controlled by a different global op.
     *
     * Use {@link #unsafeCheckOp(String, int, String)}} or
     * {@link #noteOp(String, int, String, String, String)} if the effective mode is needed.
     *
     * @param ops The set of operations you are interested in, or null if you want all of them.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    @UnsupportedAppUsage
    public List<AppOpsManager.PackageOps> getPackagesForOps(int[] ops) {
        try {
            return mService.getPackagesForOps(ops);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve current operation state for one application.
     *
     * The mode of the ops returned are set for the package but may not reflect their effective
     * state due to UID policy or because it's controlled by a different global op.
     *
     * Use {@link #unsafeCheckOp(String, int, String)}} or
     * {@link #noteOp(String, int, String, String, String)} if the effective mode is needed.
     *
     * @param uid The uid of the application of interest.
     * @param packageName The name of the application of interest.
     * @param ops The set of operations you are interested in, or null if you want all of them.
     *
     * @deprecated The int op codes are not stable and you should use the string based op
     * names which are stable and namespaced. Use
     * {@link #getOpsForPackage(int, String, String...)})}.
     *
     * @hide
     * @removed
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    public @NonNull List<PackageOps> getOpsForPackage(int uid, @NonNull String packageName,
            @Nullable int[] ops) {
        try {
            return mService.getOpsForPackage(uid, packageName, ops);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve current operation state for one application. The UID and the
     * package must match.
     *
     * The mode of the ops returned are set for the package but may not reflect their effective
     * state due to UID policy or because it's controlled by a different global op.
     *
     * Use {@link #unsafeCheckOp(String, int, String)}} or
     * {@link #noteOp(String, int, String, String, String)} if the effective mode is needed.
     *
     * @param uid The uid of the application of interest.
     * @param packageName The name of the application of interest.
     * @param ops The set of operations you are interested in, or null if you want all of them.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    public @NonNull List<AppOpsManager.PackageOps> getOpsForPackage(int uid,
            @NonNull String packageName, @Nullable String... ops) {
        int[] opCodes = null;
        if (ops != null) {
            opCodes = new int[ops.length];
            for (int i = 0; i < ops.length; i++) {
                opCodes[i] = strOpToOp(ops[i]);
            }
        }
        try {
            final List<PackageOps> result = mService.getOpsForPackage(uid, packageName, opCodes);
            if (result == null) {
                return Collections.emptyList();
            }
            return result;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve historical app op stats for a period.
     *
     * @param request A request object describing the data being queried for.
     * @param executor Executor on which to run the callback. If <code>null</code>
     *     the callback is executed on the default executor running on the main thread.
     * @param callback Callback on which to deliver the result.
     *
     * @throws IllegalArgumentException If any of the argument contracts is violated.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.GET_APP_OPS_STATS)
    public void getHistoricalOps(@NonNull HistoricalOpsRequest request,
            @NonNull Executor executor, @NonNull Consumer<HistoricalOps> callback) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        try {
            mService.getHistoricalOps(request.mUid, request.mPackageName, request.mAttributionTag,
                    request.mOpNames, request.mHistoryFlags, request.mFilter,
                    request.mBeginTimeMillis, request.mEndTimeMillis, request.mFlags,
                    new RemoteCallback((result) -> {
                final HistoricalOps ops = result.getParcelable(KEY_HISTORICAL_OPS);
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> callback.accept(ops));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieve historical app op stats for a period.
     *  <p>
     *  This method queries only the on disk state and the returned ops are raw,
     *  which is their times are relative to the history start as opposed to the
     *  epoch start.
     *
     * @param request A request object describing the data being queried for.
     * @param executor Executor on which to run the callback. If <code>null</code>
     *     the callback is executed on the default executor running on the main thread.
     * @param callback Callback on which to deliver the result.
     *
     * @throws IllegalArgumentException If any of the argument contracts is violated.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void getHistoricalOpsFromDiskRaw(@NonNull HistoricalOpsRequest request,
            @Nullable Executor executor, @NonNull Consumer<HistoricalOps> callback) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        try {
            mService.getHistoricalOpsFromDiskRaw(request.mUid, request.mPackageName,
                    request.mAttributionTag, request.mOpNames, request.mHistoryFlags,
                    request.mFilter, request.mBeginTimeMillis, request.mEndTimeMillis,
                    request.mFlags, new RemoteCallback((result) -> {
                final HistoricalOps ops = result.getParcelable(KEY_HISTORICAL_OPS);
                final long identity = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> callback.accept(ops));
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reloads the non historical state to allow testing the read/write path.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void reloadNonHistoricalState() {
        try {
            mService.reloadNonHistoricalState();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets given app op in the specified mode for app ops in the UID.
     * This applies to all apps currently in the UID or installed in
     * this UID in the future.
     *
     * @param code The app op.
     * @param uid The UID for which to set the app.
     * @param mode The app op mode to set.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    public void setUidMode(int code, int uid, @Mode int mode) {
        try {
            mService.setUidMode(code, uid, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets given app op in the specified mode for app ops in the UID.
     * This applies to all apps currently in the UID or installed in
     * this UID in the future.
     *
     * @param appOp The app op.
     * @param uid The UID for which to set the app.
     * @param mode The app op mode to set.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    public void setUidMode(@NonNull String appOp, int uid, @Mode int mode) {
        try {
            mService.setUidMode(AppOpsManager.strOpToOp(appOp), uid, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    public void setUserRestriction(int code, boolean restricted, IBinder token) {
        setUserRestriction(code, restricted, token, (Map<String, String[]>) null);
    }

    /**
     * An empty array of attribution tags means exclude all tags under that package.
     * @hide
     */
    public void setUserRestriction(int code, boolean restricted, IBinder token,
            @Nullable Map<String, String[]> excludedPackageTags) {
        setUserRestrictionForUser(code, restricted, token, excludedPackageTags,
                mContext.getUserId());
    }

    /**
     * An empty array of attribution tags means exclude all tags under that package.
     * @hide
     */
    public void setUserRestrictionForUser(int code, boolean restricted, IBinder token,
            @Nullable Map<String, String[]> excludedPackageTags, int userId) {
        try {
            mService.setUserRestriction(code, restricted, token, userId, excludedPackageTags);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    @TestApi
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    public void setMode(int code, int uid, String packageName, @Mode int mode) {
        try {
            mService.setMode(code, uid, packageName, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Change the operating mode for the given op in the given app package.  You must pass
     * in both the uid and name of the application whose mode is being modified; if these
     * do not match, the modification will not be applied.
     *
     * @param op The operation to modify.  One of the OPSTR_* constants.
     * @param uid The user id of the application whose mode will be changed.
     * @param packageName The name of the application package name whose mode will
     * be changed.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    public void setMode(@NonNull String op, int uid, @Nullable String packageName,
            @Mode int mode) {
        try {
            mService.setMode(strOpToOp(op), uid, packageName, mode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set a non-persisted restriction on an audio operation at a stream-level.
     * Restrictions are temporary additional constraints imposed on top of the persisted rules
     * defined by {@link #setMode}.
     *
     * @param code The operation to restrict.
     * @param usage The {@link android.media.AudioAttributes} usage value.
     * @param mode The restriction mode (MODE_IGNORED,MODE_ERRORED) or MODE_ALLOWED to unrestrict.
     * @param exceptionPackages Optional list of packages to exclude from the restriction.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    @UnsupportedAppUsage
    public void setRestriction(int code, @AttributeUsage int usage, @Mode int mode,
            String[] exceptionPackages) {
        try {
            final int uid = Binder.getCallingUid();
            mService.setAudioRestriction(code, usage, uid, mode, exceptionPackages);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    @RequiresPermission(android.Manifest.permission.MANAGE_APP_OPS_MODES)
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    public void resetAllModes() {
        try {
            mService.resetAllModes(mContext.getUserId(), null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the app-op name associated with a given permission.
     *
     * <p>The app-op name is one of the public constants defined
     * in this class such as {@link #OPSTR_COARSE_LOCATION}.
     * This API is intended to be used for mapping runtime
     * permissions to the corresponding app-op.
     *
     * @param permission The permission.
     * @return The app-op associated with the permission or {@code null}.
     */
    public static @Nullable String permissionToOp(@NonNull String permission) {
        final Integer opCode = sPermToOp.get(permission);
        if (opCode == null) {
            return null;
        }
        return sOpToString[opCode];
    }

    /**
     * Resolves special UID's pakcages such as root, shell, media, etc.
     *
     * @param uid The uid to resolve.
     * @param packageName Optional package. If caller system  and null returns "android"
     * @return The resolved package name.
     *
     * @hide
     */
    public static @Nullable String resolvePackageName(int uid, @Nullable String packageName)  {
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

    /**
     * Monitor for changes to the operating mode for the given op in the given app package.
     * You can watch op changes only for your UID.
     *
     * @param op The operation to monitor, one of OPSTR_*.
     * @param packageName The name of the application to monitor.
     * @param callback Where to report changes.
     */
    public void startWatchingMode(@NonNull String op, @Nullable String packageName,
            @NonNull final OnOpChangedListener callback) {
        startWatchingMode(strOpToOp(op), packageName, callback);
    }

    /**
     * Monitor for changes to the operating mode for the given op in the given app package.
     * You can watch op changes only for your UID.
     *
     * @param op The operation to monitor, one of OPSTR_*.
     * @param packageName The name of the application to monitor.
     * @param flags Option flags: any combination of {@link #WATCH_FOREGROUND_CHANGES} or 0.
     * @param callback Where to report changes.
     */
    public void startWatchingMode(@NonNull String op, @Nullable String packageName, int flags,
            @NonNull final OnOpChangedListener callback) {
        startWatchingMode(strOpToOp(op), packageName, flags, callback);
    }

    /**
     * Monitor for changes to the operating mode for the given op in the given app package.
     *
     * <p> If you don't hold the {@link android.Manifest.permission#WATCH_APPOPS} permission
     * you can watch changes only for your UID.
     *
     * @param op The operation to monitor, one of OP_*.
     * @param packageName The name of the application to monitor.
     * @param callback Where to report changes.
     * @hide
     */
    @RequiresPermission(value=android.Manifest.permission.WATCH_APPOPS, conditional=true)
    public void startWatchingMode(int op, String packageName, final OnOpChangedListener callback) {
        startWatchingMode(op, packageName, 0, callback);
    }

    /**
     * Monitor for changes to the operating mode for the given op in the given app package.
     *
     * <p> If you don't hold the {@link android.Manifest.permission#WATCH_APPOPS} permission
     * you can watch changes only for your UID.
     *
     * @param op The operation to monitor, one of OP_*.
     * @param packageName The name of the application to monitor.
     * @param flags Option flags: any combination of {@link #WATCH_FOREGROUND_CHANGES} or 0.
     * @param callback Where to report changes.
     * @hide
     */
    @RequiresPermission(value=android.Manifest.permission.WATCH_APPOPS, conditional=true)
    public void startWatchingMode(int op, String packageName, int flags,
            final OnOpChangedListener callback) {
        synchronized (mModeWatchers) {
            IAppOpsCallback cb = mModeWatchers.get(callback);
            if (cb == null) {
                cb = new IAppOpsCallback.Stub() {
                    public void opChanged(int op, int uid, String packageName) {
                        if (callback instanceof OnOpChangedInternalListener) {
                            ((OnOpChangedInternalListener)callback).onOpChanged(op, packageName);
                        }
                        if (sOpToString[op] != null) {
                            callback.onOpChanged(sOpToString[op], packageName);
                        }
                    }
                };
                mModeWatchers.put(callback, cb);
            }

            // See CALL_BACK_ON_CHANGED_LISTENER_WITH_SWITCHED_OP_CHANGE
            if (!Compatibility.isChangeEnabled(
                    CALL_BACK_ON_CHANGED_LISTENER_WITH_SWITCHED_OP_CHANGE)) {
                flags |= CALL_BACK_ON_SWITCHED_OP;
            }

            try {
                mService.startWatchingModeWithFlags(op, packageName, flags, cb);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Stop monitoring that was previously started with {@link #startWatchingMode}.  All
     * monitoring associated with this callback will be removed.
     */
    public void stopWatchingMode(@NonNull OnOpChangedListener callback) {
        synchronized (mModeWatchers) {
            IAppOpsCallback cb = mModeWatchers.remove(callback);
            if (cb != null) {
                try {
                    mService.stopWatchingMode(cb);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /** {@hide} */
    @Deprecated
    public void startWatchingActive(@NonNull int[] ops,
            @NonNull OnOpActiveChangedListener callback) {
        final String[] strOps = new String[ops.length];
        for (int i = 0; i < ops.length; i++) {
            strOps[i] = opToPublicName(ops[i]);
        }
        startWatchingActive(strOps, mContext.getMainExecutor(), callback);
    }

    /**
     * Start watching for changes to the active state of app-ops. An app-op may be
     * long running and it has a clear start and stop delimiters. If an op is being
     * started or stopped by any package you will get a callback. To change the
     * watched ops for a registered callback you need to unregister and register it
     * again.
     *
     * <p> If you don't hold the {@code android.Manifest.permission#WATCH_APPOPS} permission
     * you can watch changes only for your UID.
     *
     * @param ops The operations to watch.
     * @param callback Where to report changes.
     *
     * @see #stopWatchingActive
     */
    // TODO: Uncomment below annotation once b/73559440 is fixed
    // @RequiresPermission(value=Manifest.permission.WATCH_APPOPS, conditional=true)
    public void startWatchingActive(@NonNull String[] ops,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull OnOpActiveChangedListener callback) {
        Objects.requireNonNull(ops);
        Objects.requireNonNull(executor);
        Objects.requireNonNull(callback);
        IAppOpsActiveCallback cb;
        synchronized (mActiveWatchers) {
            cb = mActiveWatchers.get(callback);
            if (cb != null) {
                return;
            }
            cb = new IAppOpsActiveCallback.Stub() {
                @Override
                public void opActiveChanged(int op, int uid, String packageName, boolean active) {
                    executor.execute(() -> {
                        if (callback instanceof OnOpActiveChangedInternalListener) {
                            ((OnOpActiveChangedInternalListener) callback).onOpActiveChanged(op,
                                    uid, packageName, active);
                        }
                        if (sOpToString[op] != null) {
                            callback.onOpActiveChanged(sOpToString[op], uid, packageName, active);
                        }
                    });
                }
            };
            mActiveWatchers.put(callback, cb);
        }
        final int[] rawOps = new int[ops.length];
        for (int i = 0; i < ops.length; i++) {
            rawOps[i] = strOpToOp(ops[i]);
        }
        try {
            mService.startWatchingActive(rawOps, cb);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop watching for changes to the active state of an app-op. An app-op may be
     * long running and it has a clear start and stop delimiters. Unregistering a
     * non-registered callback has no effect.
     *
     * @see #startWatchingActive
     */
    public void stopWatchingActive(@NonNull OnOpActiveChangedListener callback) {
        synchronized (mActiveWatchers) {
            final IAppOpsActiveCallback cb = mActiveWatchers.remove(callback);
            if (cb != null) {
                try {
                    mService.stopWatchingActive(cb);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Start watching for started app-ops.
     * An app-op may be long running and it has a clear start delimiter.
     * If an op start is attempted by any package, you will get a callback.
     * To change the watched ops for a registered callback you need to unregister and register it
     * again.
     *
     * <p> If you don't hold the {@code android.Manifest.permission#WATCH_APPOPS} permission
     * you can watch changes only for your UID.
     *
     * @param ops The operations to watch.
     * @param callback Where to report changes.
     *
     * @see #stopWatchingStarted(OnOpStartedListener)
     * @see #startWatchingActive(int[], OnOpActiveChangedListener)
     * @see #startWatchingNoted(int[], OnOpNotedListener)
     * @see #startOp(int, int, String, boolean, String, String)
     * @see #finishOp(int, int, String, String)
     *
     * @hide
     */
     @RequiresPermission(value=Manifest.permission.WATCH_APPOPS, conditional=true)
     public void startWatchingStarted(@NonNull int[] ops, @NonNull OnOpStartedListener callback) {
         IAppOpsStartedCallback cb;
         synchronized (mStartedWatchers) {
             if (mStartedWatchers.containsKey(callback)) {
                 return;
             }
             cb = new IAppOpsStartedCallback.Stub() {
                 @Override
                 public void opStarted(int op, int uid, String packageName, String attributionTag,
                         int flags, int mode) {
                     callback.onOpStarted(op, uid, packageName, attributionTag, flags, mode);
                 }
             };
             mStartedWatchers.put(callback, cb);
         }
         try {
             mService.startWatchingStarted(ops, cb);
         } catch (RemoteException e) {
             throw e.rethrowFromSystemServer();
         }
    }

    /**
     * Stop watching for started app-ops.
     * An app-op may be long running and it has a clear start delimiter.
     * Henceforth, if an op start is attempted by any package, you will not get a callback.
     * Unregistering a non-registered callback has no effect.
     *
     * @see #startWatchingStarted(int[], OnOpStartedListener)
     * @see #startOp(int, int, String, boolean, String, String)
     *
     * @hide
     */
    public void stopWatchingStarted(@NonNull OnOpStartedListener callback) {
        synchronized (mStartedWatchers) {
            final IAppOpsStartedCallback cb = mStartedWatchers.remove(callback);
            if (cb != null) {
                try {
                    mService.stopWatchingStarted(cb);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Start watching for noted app ops. An app op may be immediate or long running.
     * Immediate ops are noted while long running ones are started and stopped. This
     * method allows registering a listener to be notified when an app op is noted. If
     * an op is being noted by any package you will get a callback. To change the
     * watched ops for a registered callback you need to unregister and register it again.
     *
     * <p> If you don't hold the {@link android.Manifest.permission#WATCH_APPOPS} permission
     * you can watch changes only for your UID.
     *
     * @param ops The ops to watch.
     * @param callback Where to report changes.
     *
     * @see #startWatchingActive(int[], OnOpActiveChangedListener)
     * @see #startWatchingStarted(int[], OnOpStartedListener)
     * @see #stopWatchingNoted(OnOpNotedListener)
     * @see #noteOp(String, int, String, String, String)
     *
     * @hide
     */
    @RequiresPermission(value=Manifest.permission.WATCH_APPOPS, conditional=true)
    public void startWatchingNoted(@NonNull int[] ops, @NonNull OnOpNotedListener callback) {
        IAppOpsNotedCallback cb;
        synchronized (mNotedWatchers) {
            cb = mNotedWatchers.get(callback);
            if (cb != null) {
                return;
            }
            cb = new IAppOpsNotedCallback.Stub() {
                @Override
                public void opNoted(int op, int uid, String packageName, String attributionTag,
                        int flags, int mode) {
                    callback.onOpNoted(op, uid, packageName, attributionTag, flags, mode);
                }
            };
            mNotedWatchers.put(callback, cb);
        }
        try {
            mService.startWatchingNoted(ops, cb);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop watching for noted app ops. An app op may be immediate or long running.
     * Unregistering a non-registered callback has no effect.
     *
     * @see #startWatchingNoted(int[], OnOpNotedListener)
     * @see #noteOp(String, int, String, String, String)
     *
     * @hide
     */
    public void stopWatchingNoted(@NonNull OnOpNotedListener callback) {
        synchronized (mNotedWatchers) {
            final IAppOpsNotedCallback cb = mNotedWatchers.remove(callback);
            if (cb != null) {
                try {
                    mService.stopWatchingNoted(cb);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    private String buildSecurityExceptionMsg(int op, int uid, String packageName) {
        return packageName + " from uid " + uid + " not allowed to perform " + sOpNames[op];
    }

    /**
     * {@hide}
     */
    @UnsupportedAppUsage
    @TestApi
    public static int strOpToOp(@NonNull String op) {
        Integer val = sOpStrToOp.get(op);
        if (val == null) {
            throw new IllegalArgumentException("Unknown operation string: " + op);
        }
        return val;
    }

    /**
     * Do a quick check for whether an application might be able to perform an operation.
     * This is <em>not</em> a security check; you must use {@link #noteOp(String, int, String,
     * String, String)} or {@link #startOp(String, int, String, String, String)} for your actual
     * security checks, which also ensure that the given uid and package name are consistent. This
     * function can just be used for a quick check to see if an operation has been disabled for the
     * application, as an early reject of some work.  This does not modify the time stamp or other
     * data about the operation.
     *
     * <p>Important things this will not do (which you need to ultimate use
     * {@link #noteOp(String, int, String, String, String)} or
     * {@link #startOp(String, int, String, String, String)} to cover):</p>
     * <ul>
     *     <li>Verifying the uid and package are consistent, so callers can't spoof
     *     their identity.</li>
     *     <li>Taking into account the current foreground/background state of the
     *     app; apps whose mode varies by this state will always be reported
     *     as {@link #MODE_ALLOWED}.</li>
     * </ul>
     *
     * @param op The operation to check.  One of the OPSTR_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @throws SecurityException If the app has been configured to crash on this op.
     */
    public int unsafeCheckOp(@NonNull String op, int uid, @NonNull String packageName) {
        return checkOp(strOpToOp(op), uid, packageName);
    }

    /**
     * @deprecated Renamed to {@link #unsafeCheckOp(String, int, String)}.
     */
    @Deprecated
    public int checkOp(@NonNull String op, int uid, @NonNull String packageName) {
        return checkOp(strOpToOp(op), uid, packageName);
    }

    /**
     * Like {@link #checkOp} but instead of throwing a {@link SecurityException} it
     * returns {@link #MODE_ERRORED}.
     */
    public int unsafeCheckOpNoThrow(@NonNull String op, int uid, @NonNull String packageName) {
        return checkOpNoThrow(strOpToOp(op), uid, packageName);
    }

    /**
     * @deprecated Renamed to {@link #unsafeCheckOpNoThrow(String, int, String)}.
     */
    @Deprecated
    public int checkOpNoThrow(@NonNull String op, int uid, @NonNull String packageName) {
        return checkOpNoThrow(strOpToOp(op), uid, packageName);
    }

    /**
     * Like {@link #checkOp} but returns the <em>raw</em> mode associated with the op.
     * Does not throw a security exception, does not translate {@link #MODE_FOREGROUND}.
     */
    public int unsafeCheckOpRaw(@NonNull String op, int uid, @NonNull String packageName) {
        return unsafeCheckOpRawNoThrow(op, uid, packageName);
    }

    /**
     * Like {@link #unsafeCheckOpNoThrow(String, int, String)} but returns the <em>raw</em>
     * mode associated with the op. Does not throw a security exception, does not translate
     * {@link #MODE_FOREGROUND}.
     */
    public int unsafeCheckOpRawNoThrow(@NonNull String op, int uid, @NonNull String packageName) {
        return unsafeCheckOpRawNoThrow(strOpToOp(op), uid, packageName);
    }

    /**
     * Returns the <em>raw</em> mode associated with the op.
     * Does not throw a security exception, does not translate {@link #MODE_FOREGROUND}.
     * @hide
     */
    public int unsafeCheckOpRawNoThrow(int op, int uid, @NonNull String packageName) {
        try {
            return mService.checkOperationRaw(op, uid, packageName, null);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated Use {@link #noteOp(String, int, String, String, String)} instead
     */
    @Deprecated
    public int noteOp(@NonNull String op, int uid, @NonNull String packageName) {
        return noteOp(op, uid, packageName, null, null);
    }

    /**
     * @deprecated Use {@link #noteOp(String, int, String, String, String)} instead
     *
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "#noteOp(java.lang.String, int, java.lang.String, java.lang.String, "
            + "java.lang.String)} instead")
    @Deprecated
    public int noteOp(int op) {
        return noteOp(op, Process.myUid(), mContext.getOpPackageName(), null, null);
    }

    /**
     * @deprecated Use {@link #noteOp(String, int, String, String, String)} instead
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "#noteOp(java.lang.String, int, java.lang.String, java.lang.String, "
            + "java.lang.String)} instead")
    public int noteOp(int op, int uid, @Nullable String packageName) {
        return noteOp(op, uid, packageName, null, null);
    }

    /**
     * Make note of an application performing an operation and check if the application is allowed
     * to perform it.
     *
     * <p>If this is a check that is not preceding the protected operation, use
     * {@link #unsafeCheckOp} instead.
     *
     * <p>The identity of the package the app-op is noted for is specified by the
     * {@code uid} and {@code packageName} parameters. If this is noted for a regular app both
     * should be set and the package needs to be part of the uid. In the very rare case that an
     * app-op is noted for an entity that does not have a package name, the package can be
     * {@code null}. As it is possible that a single process contains more than one package the
     * {@code packageName} should be {@link Context#getPackageName() read} from the context of the
     * caller of the API (in the app process) that eventually triggers this check. If this op is
     * not noted for a running process the {@code packageName} cannot be read from the context, but
     * it should be clear which package the note is for.
     *
     * <p>If the  {@code uid} and {@code packageName} do not match this return
     * {@link #MODE_IGNORED}.
     *
     * <p>Beside the access check this method also records the access. While the access check is
     * based on {@code uid} and/or {@code packageName} the access recording is done based on the
     * {@code packageName} and {@code attributionTag}. The {@code attributionTag} should be
     * {@link Context#getAttributionTag() read} from the same context the package name is read from.
     * In the case the check is not related to an API call, the  {@code attributionTag} should be
     * {@code null}. Please note that e.g. registering a callback for later is still an API call and
     * the code should store the attribution tag along the package name for being used in this
     * method later.
     *
     * <p>The {@code message} parameter only needs to be set when this method is <ul>not</ul>
     * called in a two-way binder call from the client. In this case the message is a free form text
     * that is meant help the app developer determine what part of the app's code triggered the
     * note. This message is passed back to the app in the
     * {@link OnOpNotedCallback#onAsyncNoted(AsyncNotedAppOp)} callback. A good example of a useful
     * message is including the {@link System#identityHashCode(Object)} of the listener that will
     * receive data or the name of the manifest-receiver.
     *
     * @param op The operation to note.  One of the OPSTR_* constants.
     * @param uid The uid of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @param attributionTag The {@link Context#createAttributionContext attribution tag} of the
     *                       calling context or {@code null} for default attribution
     * @param message A message describing why the op was noted
     *
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     *
     * @throws SecurityException If the app has been configured to crash on this op.
     */
    // For platform callers of this method, please read the package name parameter from
    // Context#getOpPackageName.
    // When noting a callback, the message can be computed using the #toReceiverId method.
    public int noteOp(@NonNull String op, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message) {
        return noteOp(strOpToOp(op), uid, packageName, attributionTag, message);
    }

    /**
     * @see #noteOp(String, int, String, String, String
     *
     * @hide
     */
    public int noteOp(int op, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message) {
        final int mode = noteOpNoThrow(op, uid, packageName, attributionTag, message);
        if (mode == MODE_ERRORED) {
            throw new SecurityException(buildSecurityExceptionMsg(op, uid, packageName));
        }
        return mode;
    }

    /**
     * @deprecated Use {@link #noteOpNoThrow(String, int, String, String, String)} instead
     */
    @Deprecated
    public int noteOpNoThrow(@NonNull String op, int uid, @NonNull String packageName) {
        return noteOpNoThrow(op, uid, packageName, null, null);
    }

    /**
     * @deprecated Use {@link #noteOpNoThrow(int, int, String, String, String)} instead
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "#noteOpNoThrow(java.lang.String, int, java.lang.String, java.lang.String, "
            + "java.lang.String)} instead")
    public int noteOpNoThrow(int op, int uid, String packageName) {
        return noteOpNoThrow(op, uid, packageName, null, null);
    }

    /**
     * Like {@link #noteOp(String, int, String, String, String)} but instead of throwing a
     * {@link SecurityException} it returns {@link #MODE_ERRORED}.
     *
     * @see #noteOp(String, int, String, String, String)
     */
    public int noteOpNoThrow(@NonNull String op, int uid, @NonNull String packageName,
            @Nullable String attributionTag, @Nullable String message) {
        return noteOpNoThrow(strOpToOp(op), uid, packageName, attributionTag, message);
    }

    /**
     * @see #noteOpNoThrow(String, int, String, String, String)
     *
     * @hide
     */
    public int noteOpNoThrow(int op, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message) {
        try {
            collectNoteOpCallsForValidation(op);
            int collectionMode = getNotedOpCollectionMode(uid, packageName, op);
            boolean shouldCollectMessage = Process.myUid() == Process.SYSTEM_UID;
            if (collectionMode == COLLECT_ASYNC) {
                if (message == null) {
                    // Set stack trace as default message
                    message = getFormattedStackTrace();
                    shouldCollectMessage = true;
                }
            }

            SyncNotedAppOp syncOp = mService.noteOperation(op, uid, packageName, attributionTag,
                    collectionMode == COLLECT_ASYNC, message, shouldCollectMessage);

            if (syncOp.getOpMode() == MODE_ALLOWED) {
                if (collectionMode == COLLECT_SELF) {
                    collectNotedOpForSelf(syncOp);
                } else if (collectionMode == COLLECT_SYNC) {
                    collectNotedOpSync(syncOp);
                }
            }

            return syncOp.getOpMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated Use {@link #noteProxyOp(String, String, int, String, String)} instead
     */
    @Deprecated
    public int noteProxyOp(@NonNull String op, @NonNull String proxiedPackageName) {
        return noteProxyOp(op, proxiedPackageName, Binder.getCallingUid(), null, null);
    }

    /**
     * @deprecated Use {@link #noteProxyOp(String, String, int, String, String)} instead
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Use {@link "
            + "#noteProxyOp(java.lang.String, java.lang.String, int, java.lang.String, "
            + "java.lang.String)} instead")
    public int noteProxyOp(int op, @Nullable String proxiedPackageName) {
        return noteProxyOp(op, proxiedPackageName, Binder.getCallingUid(), null, null);
    }

    /**
     * @see #noteProxyOp(String, String, int, String, String)
     *
     * @hide
     */
    public int noteProxyOp(int op, @Nullable String proxiedPackageName, int proxiedUid,
            @Nullable String proxiedAttributionTag, @Nullable String message) {
        return noteProxyOp(op, new AttributionSource(mContext.getAttributionSource(),
                new AttributionSource(proxiedUid, proxiedPackageName, proxiedAttributionTag)),
                message, /*skipProxyOperation*/ false);
    }

    /**
     * Make note of an application performing an operation on behalf of another application when
     * handling an IPC. This function will verify that the calling uid and proxied package name
     * match, and if not, return {@link #MODE_IGNORED}. If this call succeeds, the last execution
     * time of the operation for the proxied app and your app will be updated to the current time.
     *
     * @param op The operation to note. One of the OPSTR_* constants.
     * @param proxiedPackageName The name of the application calling into the proxy application.
     * @param proxiedUid The uid of the proxied application
     * @param proxiedAttributionTag The proxied {@link Context#createAttributionContext
     * attribution tag} or {@code null} for default attribution
     * @param message A message describing the reason the op was noted
     *
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or {@link #MODE_IGNORED}
     * if it is not allowed and should be silently ignored (without causing the app to crash).
     *
     * @throws SecurityException If the proxy or proxied app has been configured to crash on this
     * op.
     */
    public int noteProxyOp(@NonNull String op, @Nullable String proxiedPackageName, int proxiedUid,
            @Nullable String proxiedAttributionTag, @Nullable String message) {
        return noteProxyOp(strOpToOp(op), proxiedPackageName, proxiedUid, proxiedAttributionTag,
                message);
    }

    /**
     * Make note of an application performing an operation on behalf of another application(s).
     *
     * @param op The operation to note. One of the OPSTR_* constants.
     * @param attributionSource The permission identity for which to note.
     * @param message A message describing the reason the op was noted
     * @param skipProxyOperation Whether to skip the proxy note.
     *
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or {@link #MODE_IGNORED}
     * if it is not allowed and should be silently ignored (without causing the app to crash).
     *
     * @throws SecurityException If the any proxying operations in the permission identityf
     *     chain fails.
     *
     * @hide
     */
    public int noteProxyOp(@NonNull int op, @NonNull AttributionSource attributionSource,
            @Nullable String message, boolean skipProxyOperation) {
        final int mode = noteProxyOpNoThrow(op, attributionSource, message, skipProxyOperation);
        if (mode == MODE_ERRORED) {
            throw new SecurityException("Proxy package "
                    + attributionSource.getPackageName()  + " from uid "
                    + attributionSource.getUid() + " or calling package "
                    + attributionSource.getNextPackageName() + " from uid "
                    + attributionSource.getNextUid() + " not allowed to perform "
                    + sOpNames[op]);
        }
        return mode;
    }

    /**
     * @deprecated Use {@link #noteProxyOpNoThrow(String, String, int, String, String)} instead
     */
    @Deprecated
    public int noteProxyOpNoThrow(@NonNull String op, @NonNull String proxiedPackageName) {
        return noteProxyOpNoThrow(op, proxiedPackageName, Binder.getCallingUid(), null, null);
    }

    /**
     * @deprecated Use {@link #noteProxyOpNoThrow(String, String, int, String, String)} instead
     */
    @Deprecated
    public int noteProxyOpNoThrow(@NonNull String op, @Nullable String proxiedPackageName,
            int proxiedUid) {
        return noteProxyOpNoThrow(op, proxiedPackageName, proxiedUid, null, null);
    }

    /**
     * Like {@link #noteProxyOp(String, String, int, String, String)} but instead
     * of throwing a {@link SecurityException} it returns {@link #MODE_ERRORED}.
     *
     * @see #noteOpNoThrow(String, int, String, String, String)
     */
    public int noteProxyOpNoThrow(@NonNull String op, @Nullable String proxiedPackageName,
            int proxiedUid, @Nullable String proxiedAttributionTag, @Nullable String message) {
        return noteProxyOpNoThrow(strOpToOp(op), new AttributionSource(
                mContext.getAttributionSource(), new AttributionSource(proxiedUid,
                        proxiedPackageName, proxiedAttributionTag)), message,
                        /*skipProxyOperation*/ false);
    }

    /**
     * Make note of an application performing an operation on behalf of another application(s).
     *
     * @param op The operation to note. One of the OPSTR_* constants.
     * @param attributionSource The permission identity for which to note.
     * @param message A message describing the reason the op was noted
     * @param skipProxyOperation Whether to note op for the proxy
     *
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or {@link #MODE_IGNORED}
     * if it is not allowed and should be silently ignored (without causing the app to crash).
     *
     * @hide
     */
    @SuppressWarnings("AndroidFrameworkClientSidePermissionCheck")
    public int noteProxyOpNoThrow(int op, @NonNull AttributionSource attributionSource,
            @Nullable String message, boolean skipProxyOperation) {
        int myUid = Process.myUid();

        try {
            collectNoteOpCallsForValidation(op);
            int collectionMode = getNotedOpCollectionMode(
                    attributionSource.getNextUid(),
                    attributionSource.getNextAttributionTag(), op);
            boolean shouldCollectMessage = (myUid == Process.SYSTEM_UID);
            if (collectionMode == COLLECT_ASYNC) {
                if (message == null) {
                    // Set stack trace as default message
                    message = getFormattedStackTrace();
                    shouldCollectMessage = true;
                }
            }

            SyncNotedAppOp syncOp = mService.noteProxyOperation(op, attributionSource,
                    collectionMode == COLLECT_ASYNC, message,
                    shouldCollectMessage, skipProxyOperation);

            if (syncOp.getOpMode() == MODE_ALLOWED) {
                if (collectionMode == COLLECT_SELF) {
                    collectNotedOpForSelf(syncOp);
                } else if (collectionMode == COLLECT_SYNC
                        // Only collect app-ops when the proxy is trusted
                        && (mContext.checkPermission(Manifest.permission.UPDATE_APP_OPS_STATS, -1,
                        myUid) == PackageManager.PERMISSION_GRANTED ||
                            Binder.getCallingUid() == attributionSource.getNextUid())) {
                    collectNotedOpSync(syncOp);
                }
            }

            return syncOp.getOpMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static String getComponentPackageNameFromString(String from) {
        ComponentName componentName = from != null ? ComponentName.unflattenFromString(from) : null;
        return componentName != null ? componentName.getPackageName() : "";
    }

    private static boolean isPackagePreInstalled(Context context, String packageName, int userId) {
        try {
            final PackageManager pm = context.getPackageManager();
            final ApplicationInfo info =
                    pm.getApplicationInfoAsUser(packageName, 0, userId);
            return ((info.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /**
     * Do a quick check for whether an application might be able to perform an operation.
     * This is <em>not</em> a security check; you must use {@link #noteOp(String, int, String,
     * String, String)} or {@link #startOp(int, int, String, boolean, String, String)} for your
     * actual security checks, which also ensure that the given uid and package name are consistent.
     * This function can just be used for a quick check to see if an operation has been disabled for
     * the application, as an early reject of some work.  This does not modify the time stamp or
     * other data about the operation.
     *
     * <p>Important things this will not do (which you need to ultimate use
     * {@link #noteOp(String, int, String, String, String)} or
     * {@link #startOp(int, int, String, boolean, String, String)} to cover):</p>
     * <ul>
     *     <li>Verifying the uid and package are consistent, so callers can't spoof
     *     their identity.</li>
     *     <li>Taking into account the current foreground/background state of the
     *     app; apps whose mode varies by this state will always be reported
     *     as {@link #MODE_ALLOWED}.</li>
     * </ul>
     *
     * @param op The operation to check.  One of the OP_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     * @throws SecurityException If the app has been configured to crash on this op.
     * @hide
     */
    @UnsupportedAppUsage
    public int checkOp(int op, int uid, String packageName) {
        try {
            int mode = mService.checkOperation(op, uid, packageName);
            if (mode == MODE_ERRORED) {
                throw new SecurityException(buildSecurityExceptionMsg(op, uid, packageName));
            }
            return mode;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Like {@link #checkOp} but instead of throwing a {@link SecurityException} it
     * returns {@link #MODE_ERRORED}.
     *
     * @see #checkOp(int, int, String)
     *
     * @hide
     */
    @UnsupportedAppUsage
    public int checkOpNoThrow(int op, int uid, String packageName) {
        try {
            int mode = mService.checkOperation(op, uid, packageName);
            return mode == AppOpsManager.MODE_FOREGROUND ? AppOpsManager.MODE_ALLOWED : mode;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated Use {@link PackageManager#getPackageUid} instead
     */
    @Deprecated
    public void checkPackage(int uid, @NonNull String packageName) {
        try {
            if (mService.checkPackage(uid, packageName) != MODE_ALLOWED) {
                throw new SecurityException(
                        "Package " + packageName + " does not belong to " + uid);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Like {@link #checkOp} but at a stream-level for audio operations.
     * @hide
     */
    public int checkAudioOp(int op, int stream, int uid, String packageName) {
        try {
            final int mode = mService.checkAudioOperation(op, stream, uid, packageName);
            if (mode == MODE_ERRORED) {
                throw new SecurityException(buildSecurityExceptionMsg(op, uid, packageName));
            }
            return mode;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Like {@link #checkAudioOp} but instead of throwing a {@link SecurityException} it
     * returns {@link #MODE_ERRORED}.
     * @hide
     */
    public int checkAudioOpNoThrow(int op, int stream, int uid, String packageName) {
        try {
            return mService.checkAudioOperation(op, stream, uid, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated Use own local {@link android.os.Binder#Binder()}
     *
     * @hide
     */
    @Deprecated
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.Q, publicAlternatives = "Create own "
            + "local {@link android.os.Binder}")
    public static IBinder getToken(IAppOpsService service) {
        return getClientId();
    }

    /** @hide */
    public static IBinder getClientId() {
        synchronized (AppOpsManager.class) {
            if (sClientId == null) {
                sClientId = new Binder();
            }

            return sClientId;
        }
    }

    /** @hide */
    private static IAppOpsService getService() {
        synchronized (sLock) {
            if (sService == null) {
                sService = IAppOpsService.Stub.asInterface(
                        ServiceManager.getService(Context.APP_OPS_SERVICE));
            }
            return sService;
        }
    }

    /**
     * @deprecated use {@link #startOp(String, int, String, String, String)} instead
     */
    @Deprecated
    public int startOp(@NonNull String op, int uid, @NonNull String packageName) {
        return startOp(op, uid, packageName, null, null);
    }

    /**
     * @deprecated Use {@link #startOp(int, int, String, boolean, String, String)} instead
     *
     * @hide
     */
    @Deprecated
    public int startOp(int op) {
        return startOp(op, Process.myUid(), mContext.getOpPackageName(), false, null, null);
    }

    /**
     * @deprecated Use {@link #startOp(int, int, String, boolean, String, String)} instead
     *
     * @hide
     */
    @Deprecated
    public int startOp(int op, int uid, String packageName) {
        return startOp(op, uid, packageName, false, null, null);
    }

    /**
     * @deprecated Use {@link #startOp(int, int, String, boolean, String, String)} instead
     *
     * @hide
     */
    @Deprecated
    public int startOp(int op, int uid, String packageName, boolean startIfModeDefault) {
        return startOp(op, uid, packageName, startIfModeDefault, null, null);
    }

    /**
     * Report that an application has started executing a long-running operation.
     *
     * <p>For more details how to determine the {@code callingPackageName},
     * {@code callingAttributionTag}, and {@code message}, please check the description in
     * {@link #noteOp(String, int, String, String, String)}
     *
     * @param op The operation to start.  One of the OPSTR_* constants.
     * @param uid The user id of the application attempting to perform the operation.
     * @param packageName The name of the application attempting to perform the operation.
     * @param attributionTag The {@link Context#createAttributionContext attribution tag} or
     * {@code null} for default attribution
     * @param message Description why op was started
     *
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or
     * {@link #MODE_IGNORED} if it is not allowed and should be silently ignored (without
     * causing the app to crash).
     *
     * @throws SecurityException If the app has been configured to crash on this op or
     * the package is not in the passed in UID.
     */
    public int startOp(@NonNull String op, int uid, @Nullable String packageName,
            @Nullable String attributionTag, @Nullable String message) {
        return startOp(strOpToOp(op), uid, packageName, false, attributionTag, message);
    }

    /**
     * @see #startOp(String, int, String, String, String)
     *
     * @hide
     */
    public int startOp(int op, int uid, @Nullable String packageName, boolean startIfModeDefault,
            @Nullable String attributionTag, @Nullable String message) {
        final int mode = startOpNoThrow(op, uid, packageName, startIfModeDefault, attributionTag,
                message);
        if (mode == MODE_ERRORED) {
            throw new SecurityException(buildSecurityExceptionMsg(op, uid, packageName));
        }
        return mode;
    }

    /**
     * @deprecated use {@link #startOpNoThrow(String, int, String, String, String)} instead
     */
    @Deprecated
    public int startOpNoThrow(@NonNull String op, int uid, @NonNull String packageName) {
        return startOpNoThrow(op, uid, packageName, null, null);
    }

    /**
     * @deprecated Use {@link #startOpNoThrow(int, int, String, boolean, String, String} instead
     *
     * @hide
     */
    @Deprecated
    public int startOpNoThrow(int op, int uid, String packageName) {
        return startOpNoThrow(op, uid, packageName, false, null, null);
    }

    /**
     * @deprecated Use {@link #startOpNoThrow(int, int, String, boolean, String, String} instead
     *
     * @hide
     */
    @Deprecated
    public int startOpNoThrow(int op, int uid, String packageName, boolean startIfModeDefault) {
        return startOpNoThrow(op, uid, packageName, startIfModeDefault, null, null);
    }

    /**
     * Like {@link #startOp(String, int, String, String, String)} but instead of throwing a
     * {@link SecurityException} it returns {@link #MODE_ERRORED}.
     *
     * @see #startOp(String, int, String, String, String)
     */
    public int startOpNoThrow(@NonNull String op, int uid, @NonNull String packageName,
            @NonNull String attributionTag, @Nullable String message) {
        return startOpNoThrow(strOpToOp(op), uid, packageName, false, attributionTag, message);
    }

    /**
     * @see #startOpNoThrow(String, int, String, String, String)
     *
     * @hide
     */
    public int startOpNoThrow(int op, int uid, @NonNull String packageName,
            boolean startIfModeDefault, @Nullable String attributionTag, @Nullable String message) {
        try {
            collectNoteOpCallsForValidation(op);
            int collectionMode = getNotedOpCollectionMode(uid, packageName, op);
            boolean shouldCollectMessage = Process.myUid() == Process.SYSTEM_UID;
            if (collectionMode == COLLECT_ASYNC) {
                if (message == null) {
                    // Set stack trace as default message
                    message = getFormattedStackTrace();
                    shouldCollectMessage = true;
                }
            }

            SyncNotedAppOp syncOp = mService.startOperation(getClientId(), op, uid, packageName,
                    attributionTag, startIfModeDefault, collectionMode == COLLECT_ASYNC, message,
                    shouldCollectMessage);

            if (syncOp.getOpMode() == MODE_ALLOWED) {
                if (collectionMode == COLLECT_SELF) {
                    collectNotedOpForSelf(syncOp);
                } else if (collectionMode == COLLECT_SYNC) {
                    collectNotedOpSync(syncOp);
                }
            }

            return syncOp.getOpMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report that an application has started executing a long-running operation on behalf of
     * another application when handling an IPC. This function will verify that the calling uid and
     * proxied package name match, and if not, return {@link #MODE_IGNORED}.
     *
     * @param op The op to note
     * @param proxiedUid The uid to note the op for {@code null}
     * @param proxiedPackageName The package name the uid belongs to
     * @param proxiedAttributionTag The proxied {@link Context#createAttributionContext
     * attribution tag} or {@code null} for default attribution
     * @param message A message describing the reason the op was noted
     *
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or {@link #MODE_IGNORED}
     * if it is not allowed and should be silently ignored (without causing the app to crash).
     *
     * @throws SecurityException If the proxy or proxied app has been configured to crash on this
     * op.
     */
    public int startProxyOp(@NonNull String op, int proxiedUid, @NonNull String proxiedPackageName,
            @Nullable String proxiedAttributionTag, @Nullable String message) {
        return startProxyOp(op, new AttributionSource(mContext.getAttributionSource(),
                new AttributionSource(proxiedUid, proxiedPackageName, proxiedAttributionTag)),
                message, /*skipProxyOperation*/ false);
    }

    /**
     * Report that an application has started executing a long-running operation on behalf of
     * another application for the attribution chain specified by the {@link AttributionSource}}.
     *
     * @param op The op to note
     * @param attributionSource The permission identity for which to check
     * @param message A message describing the reason the op was noted
     * @param skipProxyOperation Whether to skip the proxy start.
     *
     * @return Returns {@link #MODE_ALLOWED} if the operation is allowed, or {@link #MODE_IGNORED}
     * if it is not allowed and should be silently ignored (without causing the app to crash).
     *
     * @throws SecurityException If the any proxying operations in the permission identity
     *     chain fails.
     *
     * @hide
     */
    public int startProxyOp(@NonNull String op, @NonNull AttributionSource attributionSource,
            @Nullable String message, boolean skipProxyOperation) {
        final int mode = startProxyOpNoThrow(AppOpsManager.strOpToOp(op), attributionSource,
                message, skipProxyOperation);
        if (mode == MODE_ERRORED) {
            throw new SecurityException("Proxy package "
                    + attributionSource.getPackageName()  + " from uid "
                    + attributionSource.getUid() + " or calling package "
                    + attributionSource.getNextPackageName() + " from uid "
                    + attributionSource.getNextUid() + " not allowed to perform "
                    + op);
        }
        return mode;
    }

    /**
     * Like {@link #startProxyOp(String, int, String, String, String)} but instead
     * of throwing a {@link SecurityException} it returns {@link #MODE_ERRORED}.
     *
     * @see #startProxyOp(String, int, String, String, String)
     */
    public int startProxyOpNoThrow(@NonNull String op, int proxiedUid,
            @NonNull String proxiedPackageName, @Nullable String proxiedAttributionTag,
            @Nullable String message) {
        return startProxyOpNoThrow(AppOpsManager.strOpToOp(op), new AttributionSource(
                mContext.getAttributionSource(), new AttributionSource(proxiedUid,
                        proxiedPackageName, proxiedAttributionTag)), message,
                /*skipProxyOperation*/ false);
    }

    /**
     * Like {@link #startProxyOp(String, AttributionSource, String)} but instead
     * of throwing a {@link SecurityException} it returns {@link #MODE_ERRORED} and
     * the checks is for the attribution chain specified by the {@link AttributionSource}.
     *
     * @see #startProxyOp(String, AttributionSource, String)
     *
     * @hide
     */
    public int startProxyOpNoThrow(int op, @NonNull AttributionSource attributionSource,
            @Nullable String message, boolean skipProxyOperation) {
        try {
            collectNoteOpCallsForValidation(op);
            int collectionMode = getNotedOpCollectionMode(
                    attributionSource.getNextUid(),
                    attributionSource.getNextPackageName(), op);
            boolean shouldCollectMessage = Process.myUid() == Process.SYSTEM_UID;
            if (collectionMode == COLLECT_ASYNC) {
                if (message == null) {
                    // Set stack trace as default message
                    message = getFormattedStackTrace();
                    shouldCollectMessage = true;
                }
            }

            SyncNotedAppOp syncOp = mService.startProxyOperation(getClientId(), op,
                    attributionSource, false, collectionMode == COLLECT_ASYNC, message,
                    shouldCollectMessage, skipProxyOperation);

            if (syncOp.getOpMode() == MODE_ALLOWED) {
                if (collectionMode == COLLECT_SELF) {
                    collectNotedOpForSelf(syncOp);
                } else if (collectionMode == COLLECT_SYNC
                        // Only collect app-ops when the proxy is trusted
                        && (mContext.checkPermission(Manifest.permission.UPDATE_APP_OPS_STATS, -1,
                        Process.myUid()) == PackageManager.PERMISSION_GRANTED
                        || Binder.getCallingUid() == attributionSource.getNextUid())) {
                    collectNotedOpSync(syncOp);
                }
            }

            return syncOp.getOpMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated Use {@link #finishOp(String, int, String, String)} instead
     *
     * @hide
     */
    @Deprecated
    public void finishOp(int op) {
        finishOp(op, Process.myUid(), mContext.getOpPackageName(), null);
    }

    /**
     * @deprecated Use {@link #finishOp(String, int, String, String)} instead
     */
    public void finishOp(@NonNull String op, int uid, @NonNull String packageName) {
        finishOp(strOpToOp(op), uid, packageName, null);
    }

    /**
     * Report that an application is no longer performing an operation that had previously
     * been started with {@link #startOp(String, int, String, String, String)}.  There is no
     * validation of input or result; the parameters supplied here must be the exact same ones
     * previously passed in when starting the operation.
     */
    public void finishOp(@NonNull String op, int uid, @NonNull String packageName,
            @Nullable String attributionTag) {
        finishOp(strOpToOp(op), uid, packageName, attributionTag);
    }

    /**
     * @deprecated Use {@link #finishOp(int, int, String, String)} instead
     *
     * @hide
     */
    public void finishOp(int op, int uid, @NonNull String packageName) {
        finishOp(op, uid, packageName, null);
    }

    /**
     * @see #finishOp(String, int, String, String)
     *
     * @hide
     */
    public void finishOp(int op, int uid, @NonNull String packageName,
            @Nullable String attributionTag) {
        try {
            mService.finishOperation(getClientId(), op, uid, packageName, attributionTag);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Report that an application is no longer performing an operation that had previously
     * been started with {@link #startProxyOp(String, int, String, String, String)}. There is no
     * validation of input or result; the parameters supplied here must be the exact same ones
     * previously passed in when starting the operation.
     *
     * @param op The operation which was started
     * @param proxiedUid The proxied appp's UID
     * @param proxiedPackageName The proxied appp's package name
     * @param proxiedAttributionTag The proxied appp's attribution tag or
     *     {@code null} for default attribution
     */
    public void finishProxyOp(@NonNull String op, int proxiedUid,
            @NonNull String proxiedPackageName, @Nullable String proxiedAttributionTag) {
        finishProxyOp(op, new AttributionSource(mContext.getAttributionSource(),
                new AttributionSource(proxiedUid, proxiedPackageName, proxiedAttributionTag)));
    }

    /**
     * Report that an application is no longer performing an operation that had previously
     * been started with {@link #startProxyOp(String, AttributionSource, String)}. There is no
     * validation of input or result; the parameters supplied here must be the exact same ones
     * previously passed in when starting the operation.
     *
     * @param op The operation which was started
     * @param attributionSource The permission identity for which to finish
     *
     * @hide
     */
    public void finishProxyOp(@NonNull String op, @NonNull AttributionSource attributionSource) {
        try {
            mService.finishProxyOperation(getClientId(), strOpToOp(op), attributionSource);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks whether the given op for a package is active, i.e. did someone call {@link #startOp}
     * without {@link #finishOp} yet.
     * <p>
     * If you don't hold the {@code android.Manifest.permission#WATCH_APPOPS}
     * permission you can query only for your UID.
     *
     * @see #finishOp(String, int, String, String)
     * @see #startOp(String, int, String, String, String)
     */
    public boolean isOpActive(@NonNull String op, int uid, @NonNull String packageName) {
        return isOperationActive(strOpToOp(op), uid, packageName);
    }

    /**
     * Get whether you are currently proxying to another package. That applies only
     * for long running operations like {@link #OP_RECORD_AUDIO}.
     *
     * @param op The op.
     * @param proxyAttributionTag Your attribution tag to query for.
     * @param proxiedUid The proxied UID to query for.
     * @param proxiedPackageName The proxied package to query for.
     * @return Whether you are currently proxying to this target.
     *
     * @hide
     */
    public boolean isProxying(int op, @NonNull String proxyAttributionTag, int proxiedUid,
            @NonNull String proxiedPackageName) {
        try {
            return mService.isProxying(op, mContext.getOpPackageName(),
                    mContext.getAttributionTag(), proxiedUid, proxiedPackageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clears the op state (last accesses + op modes) for a package but not
     * the historical state.
     *
     * @param packageName The package to reset.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void resetPackageOpsNoHistory(@NonNull String packageName) {
        try {
            mService.resetPackageOpsNoHistory(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start collection of noted appops on this thread.
     *
     * <p>Called at the beginning of a two way binder transaction.
     *
     * @see #finishNotedAppOpsCollection()
     *
     * @hide
     */
    public static void startNotedAppOpsCollection(int callingUid) {
        sThreadsListeningForOpNotedInBinderTransaction |= Thread.currentThread().getId();
        sBinderThreadCallingUid.set(callingUid);
    }

    /**
     * Finish collection of noted appops on this thread.
     *
     * <p>Called at the end of a two way binder transaction.
     *
     * @see #startNotedAppOpsCollection(int)
     *
     * @hide
     */
    public static void finishNotedAppOpsCollection() {
        sBinderThreadCallingUid.remove();
        sThreadsListeningForOpNotedInBinderTransaction &= ~Thread.currentThread().getId();
        sAppOpsNotedInThisBinderTransaction.remove();
    }

    /**
     * Collect a noted op for the current process.
     *
     * @param op The noted op
     * @param attributionTag The attribution tag the op is noted for
     */
    private void collectNotedOpForSelf(SyncNotedAppOp syncOp) {
        synchronized (sLock) {
            if (sOnOpNotedCallback != null) {
                sOnOpNotedCallback.onSelfNoted(syncOp);
            }
        }
        sMessageCollector.onSelfNoted(syncOp);
    }

    /**
     * Collect a noted op when inside of a two-way binder call.
     *
     * <p> Delivered to caller via {@link #prefixParcelWithAppOpsIfNeeded}
     *
     * @param syncOp the op and attribution tag to note for
     *
     * @hide
     */
    @TestApi
    public static void collectNotedOpSync(@NonNull SyncNotedAppOp syncOp) {
        collectNotedOpSync(sOpStrToOp.get(syncOp.getOp()), syncOp.getAttributionTag(),
                syncOp.getPackageName());
    }

    /**
     * Collect a noted op when inside of a two-way binder call.
     *
     * <p> Delivered to caller via {@link #prefixParcelWithAppOpsIfNeeded}
     *
     * @param code the op code to note for
     * @param attributionTag the attribution tag to note for
     * @param packageName the package to note for
     */
    private static void collectNotedOpSync(int code, @Nullable String attributionTag,
            @NonNull String packageName) {
        // If this is inside of a two-way binder call:
        // We are inside of a two-way binder call. Delivered to caller via
        // {@link #prefixParcelWithAppOpsIfNeeded}
        ArrayMap<String, ArrayMap<String, long[]>> appOpsNoted =
                sAppOpsNotedInThisBinderTransaction.get();
        if (appOpsNoted == null) {
            appOpsNoted = new ArrayMap<>(1);
            sAppOpsNotedInThisBinderTransaction.set(appOpsNoted);
        }

        ArrayMap<String, long[]> packageAppOpsNotedForAttribution = appOpsNoted.get(packageName);
        if (packageAppOpsNotedForAttribution == null) {
            packageAppOpsNotedForAttribution = new ArrayMap<>(1);
            appOpsNoted.put(packageName, packageAppOpsNotedForAttribution);
        }

        long[] appOpsNotedForAttribution = packageAppOpsNotedForAttribution.get(attributionTag);
        if (appOpsNotedForAttribution == null) {
            appOpsNotedForAttribution = new long[2];
            packageAppOpsNotedForAttribution.put(attributionTag, appOpsNotedForAttribution);
        }

        if (code < 64) {
            appOpsNotedForAttribution[0] |= 1L << code;
        } else {
            appOpsNotedForAttribution[1] |= 1L << (code - 64);
        }
    }

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(value = {
            DONT_COLLECT,
            COLLECT_SELF,
            COLLECT_SYNC,
            COLLECT_ASYNC
    })
    private @interface NotedOpCollectionMode {}
    private static final int DONT_COLLECT = 0;
    private static final int COLLECT_SELF = 1;
    private static final int COLLECT_SYNC = 2;
    private static final int COLLECT_ASYNC = 3;

    /**
     * Mark an app-op as noted.
     */
    private @NotedOpCollectionMode int getNotedOpCollectionMode(int uid,
            @Nullable String packageName, int op) {
        if (packageName == null) {
            packageName = "android";
        }

        // check if the appops needs to be collected and cache result
        if (sAppOpsToNote[op] == SHOULD_COLLECT_NOTE_OP_NOT_INITIALIZED) {
            boolean shouldCollectNotes;
            try {
                shouldCollectNotes = mService.shouldCollectNotes(op);
            } catch (RemoteException e) {
                return DONT_COLLECT;
            }

            if (shouldCollectNotes) {
                sAppOpsToNote[op] = SHOULD_COLLECT_NOTE_OP;
            } else {
                sAppOpsToNote[op] = SHOULD_NOT_COLLECT_NOTE_OP;
            }
        }

        if (sAppOpsToNote[op] != SHOULD_COLLECT_NOTE_OP) {
            return DONT_COLLECT;
        }

        synchronized (sLock) {
            if (uid == Process.myUid()
                    && packageName.equals(ActivityThread.currentOpPackageName())) {
                return COLLECT_SELF;
            }
        }

        if (isListeningForOpNotedInBinderTransaction()) {
            return COLLECT_SYNC;
        } else {
            return COLLECT_ASYNC;
        }
    }

    /**
     * Append app-ops noted in the current two-way binder transaction to parcel.
     *
     * <p>This is called on the callee side of a two way binder transaction just before the
     * transaction returns.
     *
     * @param p the parcel to append the noted app-ops to
     *
     * @hide
     */
    // TODO (b/186872903) Refactor how sync noted ops are propagated.
    public static void prefixParcelWithAppOpsIfNeeded(@NonNull Parcel p) {
        if (!isListeningForOpNotedInBinderTransaction()) {
            return;
        }
        final ArrayMap<String, ArrayMap<String, long[]>> notedAppOps =
                sAppOpsNotedInThisBinderTransaction.get();
        if (notedAppOps == null) {
            return;
        }

        p.writeInt(Parcel.EX_HAS_NOTED_APPOPS_REPLY_HEADER);

        final int packageCount = notedAppOps.size();
        p.writeInt(packageCount);

        for (int i = 0; i < packageCount; i++) {
            p.writeString(notedAppOps.keyAt(i));

            final ArrayMap<String, long[]> notedTagAppOps = notedAppOps.valueAt(i);
            final int tagCount = notedTagAppOps.size();
            p.writeInt(tagCount);

            for (int j = 0; j < tagCount; j++) {
                p.writeString(notedTagAppOps.keyAt(j));
                p.writeLong(notedTagAppOps.valueAt(j)[0]);
                p.writeLong(notedTagAppOps.valueAt(j)[1]);
            }
        }
    }

    /**
     * Read app-ops noted during a two-way binder transaction from parcel.
     *
     * <p>This is called on the calling side of a two way binder transaction just after the
     * transaction returns.
     *
     * @param p The parcel to read from
     *
     * @hide
     */
    public static void readAndLogNotedAppops(@NonNull Parcel p) {
        final int packageCount = p.readInt();
        if (packageCount <= 0) {
            return;
        }

        final String myPackageName = ActivityThread.currentPackageName();

        synchronized (sLock) {
            for (int i = 0; i < packageCount; i++) {
                final String packageName = p.readString();

                final int tagCount = p.readInt();
                for (int j = 0; j < tagCount; j++) {
                    final String attributionTag = p.readString();
                    final long[] rawNotedAppOps = new long[2];
                    rawNotedAppOps[0] = p.readLong();
                    rawNotedAppOps[1] = p.readLong();

                    if (rawNotedAppOps[0] == 0 && rawNotedAppOps[1] == 0) {
                        continue;
                    }

                    final BitSet notedAppOps = BitSet.valueOf(rawNotedAppOps);
                    for (int code = notedAppOps.nextSetBit(0); code != -1;
                            code = notedAppOps.nextSetBit(code + 1)) {
                        if (Objects.equals(myPackageName, packageName)) {
                            if (sOnOpNotedCallback != null) {
                                sOnOpNotedCallback.onNoted(new SyncNotedAppOp(code,
                                        attributionTag, packageName));
                            } else {
                                String message = getFormattedStackTrace();
                                sUnforwardedOps.add(new AsyncNotedAppOp(code, Process.myUid(),
                                        attributionTag, message, System.currentTimeMillis()));
                                if (sUnforwardedOps.size() > MAX_UNFORWARDED_OPS) {
                                    sUnforwardedOps.remove(0);
                                }
                            }
                        } else if (isListeningForOpNotedInBinderTransaction()) {
                            collectNotedOpSync(code, attributionTag, packageName);
                        }
                    }
                    for (int code = notedAppOps.nextSetBit(0); code != -1;
                            code = notedAppOps.nextSetBit(code + 1)) {
                        if (Objects.equals(myPackageName, packageName)) {
                            sMessageCollector.onNoted(new SyncNotedAppOp(code,
                                    attributionTag, packageName));
                        }
                    }
                }
            }
        }
    }

    /**
     * Set a new {@link OnOpNotedCallback}.
     *
     * <p>There can only ever be one collector per process. If there currently is another callback
     * set, this will fail.
     *
     * @param asyncExecutor executor to execute {@link OnOpNotedCallback#onAsyncNoted} on, {@code
     * null} to unset
     * @param callback listener to set, {@code null} to unset
     *
     * @throws IllegalStateException If another callback is already registered
     */
    public void setOnOpNotedCallback(@Nullable @CallbackExecutor Executor asyncExecutor,
            @Nullable OnOpNotedCallback callback) {
        Preconditions.checkState((callback == null) == (asyncExecutor == null));

        synchronized (sLock) {
            if (callback == null) {
                Preconditions.checkState(sOnOpNotedCallback != null,
                        "No callback is currently registered");

                try {
                    mService.stopWatchingAsyncNoted(mContext.getPackageName(),
                            sOnOpNotedCallback.mAsyncCb);
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }

                sOnOpNotedCallback = null;
            } else {
                Preconditions.checkState(sOnOpNotedCallback == null,
                        "Another callback is already registered");

                callback.mAsyncExecutor = asyncExecutor;
                sOnOpNotedCallback = callback;

                List<AsyncNotedAppOp> missedAsyncOps = null;
                try {
                    mService.startWatchingAsyncNoted(mContext.getPackageName(),
                            sOnOpNotedCallback.mAsyncCb);
                    missedAsyncOps = mService.extractAsyncOps(mContext.getPackageName());
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }

                if (missedAsyncOps != null) {
                    int numMissedAsyncOps = missedAsyncOps.size();
                    for (int i = 0; i < numMissedAsyncOps; i++) {
                        final AsyncNotedAppOp asyncNotedAppOp = missedAsyncOps.get(i);
                        if (sOnOpNotedCallback != null) {
                            sOnOpNotedCallback.getAsyncNotedExecutor().execute(
                                    () -> sOnOpNotedCallback.onAsyncNoted(asyncNotedAppOp));
                        }
                    }
                }
                synchronized (this) {
                    int numMissedSyncOps = sUnforwardedOps.size();
                    for (int i = 0; i < numMissedSyncOps; i++) {
                        final AsyncNotedAppOp syncNotedAppOp = sUnforwardedOps.get(i);
                        if (sOnOpNotedCallback != null) {
                            sOnOpNotedCallback.getAsyncNotedExecutor().execute(
                                    () -> sOnOpNotedCallback.onAsyncNoted(syncNotedAppOp));
                        }
                    }
                    sUnforwardedOps.clear();
                }
            }
        }
    }

    // TODO moltmann: Remove
    /**
     * Will be removed before R ships, leave it just to not break apps immediately.
     *
     * @removed
     *
     * @hide
     */
    @SystemApi
    @Deprecated
    public void setNotedAppOpsCollector(@Nullable AppOpsCollector collector) {
        synchronized (sLock) {
            if (collector != null) {
                if (isListeningForOpNoted()) {
                    setOnOpNotedCallback(null, null);
                }
                setOnOpNotedCallback(new HandlerExecutor(Handler.getMain()), collector);
            } else if (sOnOpNotedCallback != null) {
                setOnOpNotedCallback(null, null);
            }
        }
    }

    /**
     * @return {@code true} iff the process currently is currently collecting noted appops.
     *
     * @see #setOnOpNotedCallback
     *
     * @hide
     */
    public static boolean isListeningForOpNoted() {
        return sOnOpNotedCallback != null || isListeningForOpNotedInBinderTransaction()
                || isCollectingStackTraces();
    }

    /**
     * @return whether we are in a binder transaction and collecting appops.
     */
    private static boolean isListeningForOpNotedInBinderTransaction() {
        return (sThreadsListeningForOpNotedInBinderTransaction
                        & Thread.currentThread().getId()) != 0
                && sBinderThreadCallingUid.get() != null;
    }

    /**
     * @return {@code true} iff the process is currently sampled for stacktrace collection.
     *
     * @see #setOnOpNotedCallback
     *
     * @hide
     */
    private static boolean isCollectingStackTraces() {
        if (sConfig.getSampledOpCode() == OP_NONE && sConfig.getAcceptableLeftDistance() == 0 &&
                sConfig.getExpirationTimeSinceBootMillis() >= SystemClock.elapsedRealtime()) {
            return false;
        }
        return true;
    }

    /**
     * Callback an app can {@link #setOnOpNotedCallback set} to monitor the app-ops the
     * system has tracked for it. I.e. each time any app calls {@link #noteOp} or {@link #startOp}
     * one of a method of this object is called.
     *
     * <p><b>There will be a call for all app-ops related to runtime permissions, but not
     * necessarily for all other app-ops.
     *
     * <pre>
     * setOnOpNotedCallback(getMainExecutor(), new OnOpNotedCallback() {
     *     ArraySet<Pair<String, String>> opsNotedForThisProcess = new ArraySet<>();
     *
     *     private synchronized void addAccess(String op, String accessLocation) {
     *         // Ops are often noted when runtime permission protected APIs were called.
     *         // In this case permissionToOp() allows to resolve the permission<->op
     *         opsNotedForThisProcess.add(new Pair(accessType, accessLocation));
     *     }
     *
     *     public void onNoted(SyncNotedAppOp op) {
     *         // Accesses is currently happening, hence stack trace describes location of access
     *         addAccess(op.getOp(), Arrays.toString(Thread.currentThread().getStackTrace()));
     *     }
     *
     *     public void onSelfNoted(SyncNotedAppOp op) {
     *         onNoted(op);
     *     }
     *
     *     public void onAsyncNoted(AsyncNotedAppOp asyncOp) {
     *         // Stack trace is not useful for async ops as accessed happened on different thread
     *         addAccess(asyncOp.getOp(), asyncOp.getMessage());
     *     }
     * });
     * </pre>
     *
     * @see #setOnOpNotedCallback
     */
    public abstract static class OnOpNotedCallback {
        private @NonNull Executor mAsyncExecutor;

        /** Callback registered with the system. This will receive the async notes ops */
        private final IAppOpsAsyncNotedCallback mAsyncCb = new IAppOpsAsyncNotedCallback.Stub() {
            @Override
            public void opNoted(AsyncNotedAppOp op) {
                Objects.requireNonNull(op);

                final long token = Binder.clearCallingIdentity();
                try {
                    getAsyncNotedExecutor().execute(() -> onAsyncNoted(op));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        };

        // TODO moltmann: Remove
        /**
         * Will be removed before R ships.
         *
         * @return The executor for the system to use when calling {@link #onAsyncNoted}.
         *
         * @hide
         */
        protected @NonNull Executor getAsyncNotedExecutor() {
            return mAsyncExecutor;
        }

        /**
         * Called when an app-op was {@link #noteOp noted} for this package inside of a synchronous
         * API call, i.e. a API call that returned data or waited until the action was performed.
         *
         * <p>Called on the calling thread before the API returns. This allows the app to e.g.
         * collect stack traces to figure out where the access came from.
         *
         * @param op op noted
         */
        public abstract void onNoted(@NonNull SyncNotedAppOp op);

        /**
         * Called when this app noted an app-op for its own package,
         *
         * <p>This is very similar to {@link #onNoted} only that the tracking was not caused by the
         * API provider in a separate process, but by one in the app's own process.
         *
         * @param op op noted
         */
        public abstract void onSelfNoted(@NonNull SyncNotedAppOp op);

        /**
         * Called when an app-op was noted for this package which cannot be delivered via the other
         * two mechanisms.
         *
         * <p>Called as soon as possible after the app-op was noted, but the delivery delay is not
         * guaranteed. Due to how async calls work in Android this might even be delivered slightly
         * before the private data is delivered to the app.
         *
         * <p>If the app is not running or no {@link OnOpNotedCallback} is registered a small amount
         * of noted app-ops are buffered and then delivered as soon as a listener is registered.
         *
         * @param asyncOp op noted
         */
        public abstract void onAsyncNoted(@NonNull AsyncNotedAppOp asyncOp);
    }

    // TODO moltmann: Remove
    /**
     * Will be removed before R ships, leave it just to not break apps immediately.
     *
     * @removed
     *
     * @hide
     */
    @SystemApi
    @Deprecated
    public abstract static class AppOpsCollector extends OnOpNotedCallback {
        public @NonNull Executor getAsyncNotedExecutor() {
            return new HandlerExecutor(Handler.getMain());
        }
    };

    /**
     * Generate a stack trace used for noted app-ops logging.
     *
     * <p>This strips away the first few and last few stack trace elements as they are not
     * interesting to apps.
     */
    private static String getFormattedStackTrace() {
        StackTraceElement[] trace = new Exception().getStackTrace();

        int firstInteresting = 0;
        for (int i = 0; i < trace.length; i++) {
            if (trace[i].getClassName().startsWith(AppOpsManager.class.getName())
                    || trace[i].getClassName().startsWith(Parcel.class.getName())
                    || trace[i].getClassName().contains("$Stub$Proxy")
                    || trace[i].getClassName().startsWith(DatabaseUtils.class.getName())
                    || trace[i].getClassName().startsWith("android.content.ContentProviderProxy")
                    || trace[i].getClassName().startsWith(ContentResolver.class.getName())) {
                firstInteresting = i;
            } else {
                break;
            }
        }

        int lastInteresting = trace.length - 1;
        for (int i = trace.length - 1; i >= 0; i--) {
            if (trace[i].getClassName().startsWith(HandlerThread.class.getName())
                    || trace[i].getClassName().startsWith(Handler.class.getName())
                    || trace[i].getClassName().startsWith(Looper.class.getName())
                    || trace[i].getClassName().startsWith(Binder.class.getName())
                    || trace[i].getClassName().startsWith(RuntimeInit.class.getName())
                    || trace[i].getClassName().startsWith(ZygoteInit.class.getName())
                    || trace[i].getClassName().startsWith(ActivityThread.class.getName())
                    || trace[i].getClassName().startsWith(Method.class.getName())
                    || trace[i].getClassName().startsWith("com.android.server.SystemServer")) {
                lastInteresting = i;
            } else {
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = firstInteresting; i <= lastInteresting; i++) {
            if (sFullLog == null) {
                try {
                    sFullLog = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_PRIVACY,
                            FULL_LOG, false);
                } catch (Exception e) {
                    // This should not happen, but it may, in rare cases
                    sFullLog = false;
                }
            }

            if (i != firstInteresting) {
                sb.append('\n');
            }
            if (!sFullLog && sb.length() + trace[i].toString().length() > 600) {
                break;
            }
            sb.append(trace[i]);
        }

        return sb.toString();
    }

    /**
     * Checks whether the given op for a UID and package is active.
     *
     * <p> If you don't hold the {@link android.Manifest.permission#WATCH_APPOPS} permission
     * you can query only for your UID.
     *
     * @see #startWatchingActive(int[], OnOpActiveChangedListener)
     * @see #stopWatchingMode(OnOpChangedListener)
     * @see #finishOp(int, int, String, String)
     * @see #startOp(int, int, String, boolean, String, String)
     *
     * @hide */
    @TestApi
    // TODO: Uncomment below annotation once b/73559440 is fixed
    // @RequiresPermission(value=Manifest.permission.WATCH_APPOPS, conditional=true)
    public boolean isOperationActive(int code, int uid, String packageName) {
        try {
            return mService.isOperationActive(code, uid, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Configures the app ops persistence for testing.
     *
     * @param mode The mode in which the historical registry operates.
     * @param baseSnapshotInterval The base interval on which we would be persisting a snapshot of
     *   the historical data. The history is recursive where every subsequent step encompasses
     *   {@code compressionStep} longer interval with {@code compressionStep} distance between
     *    snapshots.
     * @param compressionStep The compression step in every iteration.
     *
     * @see #HISTORICAL_MODE_DISABLED
     * @see #HISTORICAL_MODE_ENABLED_ACTIVE
     * @see #HISTORICAL_MODE_ENABLED_PASSIVE
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void setHistoryParameters(@HistoricalMode int mode, long baseSnapshotInterval,
            int compressionStep) {
        try {
            mService.setHistoryParameters(mode, baseSnapshotInterval, compressionStep);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Offsets the history by the given duration.
     *
     * @param offsetMillis The offset duration.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void offsetHistory(long offsetMillis) {
        try {
            mService.offsetHistory(offsetMillis);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds ops to the history directly. This could be useful for testing especially
     * when the historical registry operates in {@link #HISTORICAL_MODE_ENABLED_PASSIVE}
     * mode.
     *
     * @param ops The ops to add to the history.
     *
     * @see #setHistoryParameters(int, long, int)
     * @see #HISTORICAL_MODE_ENABLED_PASSIVE
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void addHistoricalOps(@NonNull HistoricalOps ops) {
        try {
            mService.addHistoricalOps(ops);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Resets the app ops persistence for testing.
     *
     * @see #setHistoryParameters(int, long, int)
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void resetHistoryParameters() {
        try {
            mService.resetHistoryParameters();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Clears all app ops history.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void clearHistory() {
        try {
            mService.clearHistory();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Reboots the ops history.
     *
     * @param offlineDurationMillis The duration to wait between
     * tearing down and initializing the history. Must be greater
     * than or equal to zero.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.MANAGE_APPOPS)
    public void rebootHistory(long offlineDurationMillis) {
        try {
            mService.rebootHistory(offlineDurationMillis);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Pulls current AppOps access report and picks package and op to watch for next access report
     * Returns null if no reports were collected since last call. There is no guarantee of report
     * collection, hence this method should be called periodically even if no report was collected
     * to pick different package and op to watch.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.GET_APP_OPS_STATS)
    public @Nullable RuntimeAppOpAccessMessage collectRuntimeAppOpAccessMessage() {
        try {
            return mService.collectRuntimeAppOpAccessMessage();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all supported operation names.
     * @hide
     */
    @SystemApi
    public static String[] getOpStrs() {
        return Arrays.copyOf(sOpToString, sOpToString.length);
    }


    /**
     * @return number of App ops
     * @hide
     */
    @TestApi
    public static int getNumOps() {
        return _NUM_OP;
    }

    /**
     * Gets the last of the event.
     *
     * @param events The events
     * @param flags The UID flags
     * @param beginUidState The maximum UID state (inclusive)
     * @param endUidState The minimum UID state (inclusive)
     *
     * @return The last event of {@code null}
     */
    private static @Nullable NoteOpEvent getLastEvent(
            @Nullable LongSparseArray<NoteOpEvent> events, @UidState int beginUidState,
            @UidState int endUidState, @OpFlags int flags) {
        if (events == null) {
            return null;
        }

        NoteOpEvent lastEvent = null;
        while (flags != 0) {
            final int flag = 1 << Integer.numberOfTrailingZeros(flags);
            flags &= ~flag;
            for (int uidState : UID_STATES) {
                if (uidState < beginUidState || uidState > endUidState) {
                    continue;
                }
                final long key = makeKey(uidState, flag);

                NoteOpEvent event = events.get(key);
                if (lastEvent == null
                        || event != null && event.getNoteTime() > lastEvent.getNoteTime()) {
                    lastEvent = event;
                }
            }
        }

        return lastEvent;
    }

    private static boolean equalsLongSparseLongArray(@Nullable LongSparseLongArray a,
            @Nullable LongSparseLongArray b) {
        if (a == b) {
            return true;
        }

        if (a == null || b == null) {
            return false;
        }

        if (a.size() != b.size()) {
            return false;
        }

        int numEntries = a.size();
        for (int i = 0; i < numEntries; i++) {
            if (a.keyAt(i) != b.keyAt(i) || a.valueAt(i) != b.valueAt(i)) {
                return false;
            }
        }

        return true;
    }

    private static void writeLongSparseLongArrayToParcel(
            @Nullable LongSparseLongArray array, @NonNull Parcel parcel) {
        if (array != null) {
            final int size = array.size();
            parcel.writeInt(size);
            for (int i = 0; i < size; i++) {
                parcel.writeLong(array.keyAt(i));
                parcel.writeLong(array.valueAt(i));
            }
        } else {
            parcel.writeInt(-1);
        }
    }

    private static @Nullable LongSparseLongArray readLongSparseLongArrayFromParcel(
            @NonNull Parcel parcel) {
        final int size = parcel.readInt();
        if (size < 0) {
            return null;
        }
        final LongSparseLongArray array = new LongSparseLongArray(size);
        for (int i = 0; i < size; i++) {
            array.append(parcel.readLong(), parcel.readLong());
        }
        return array;
    }

    private static void writeDiscreteAccessArrayToParcel(
            @Nullable List<AttributedOpEntry> array, @NonNull Parcel parcel, int flags) {
        ParceledListSlice<AttributedOpEntry> listSlice =
                array == null ? null : new ParceledListSlice<>(array);
        parcel.writeParcelable(listSlice, flags);
    }

    private static @Nullable List<AttributedOpEntry> readDiscreteAccessArrayFromParcel(
            @NonNull Parcel parcel) {
        final ParceledListSlice<AttributedOpEntry> listSlice = parcel.readParcelable(null);
        return listSlice == null ? null : listSlice.getList();
    }

    /**
     * Collects the keys from an array to the result creating the result if needed.
     *
     * @param array The array whose keys to collect.
     * @param result The optional result store collected keys.
     * @return The result collected keys array.
     */
    private static LongSparseArray<Object> collectKeys(@Nullable LongSparseLongArray array,
            @Nullable LongSparseArray<Object> result) {
        if (array != null) {
            if (result == null) {
                result = new LongSparseArray<>();
            }
            final int accessSize = array.size();
            for (int i = 0; i < accessSize; i++) {
                result.put(array.keyAt(i), null);
            }
        }
        return result;
    }

    /** @hide */
    public static String uidStateToString(@UidState int uidState) {
        switch (uidState) {
            case UID_STATE_PERSISTENT: {
                return "UID_STATE_PERSISTENT";
            }
            case UID_STATE_TOP: {
                return "UID_STATE_TOP";
            }
            case UID_STATE_FOREGROUND_SERVICE_LOCATION: {
                return "UID_STATE_FOREGROUND_SERVICE_LOCATION";
            }
            case UID_STATE_FOREGROUND_SERVICE: {
                return "UID_STATE_FOREGROUND_SERVICE";
            }
            case UID_STATE_FOREGROUND: {
                return "UID_STATE_FOREGROUND";
            }
            case UID_STATE_BACKGROUND: {
                return "UID_STATE_BACKGROUND";
            }
            case UID_STATE_CACHED: {
                return "UID_STATE_CACHED";
            }
            default: {
                return "UNKNOWN";
            }
        }
    }

    /** @hide */
    public static int parseHistoricalMode(@NonNull String mode) {
        switch (mode) {
            case "HISTORICAL_MODE_ENABLED_ACTIVE": {
                return HISTORICAL_MODE_ENABLED_ACTIVE;
            }
            case "HISTORICAL_MODE_ENABLED_PASSIVE": {
                return HISTORICAL_MODE_ENABLED_PASSIVE;
            }
            default: {
                return HISTORICAL_MODE_DISABLED;
            }
        }
    }

    /** @hide */
    public static String historicalModeToString(@HistoricalMode int mode) {
        switch (mode) {
            case HISTORICAL_MODE_DISABLED: {
                return "HISTORICAL_MODE_DISABLED";
            }
            case HISTORICAL_MODE_ENABLED_ACTIVE: {
                return "HISTORICAL_MODE_ENABLED_ACTIVE";
            }
            case HISTORICAL_MODE_ENABLED_PASSIVE: {
                return "HISTORICAL_MODE_ENABLED_PASSIVE";
            }
            default: {
                return "UNKNOWN";
            }
        }
    }

    private static int getSystemAlertWindowDefault() {
        final Context context = ActivityThread.currentApplication();
        if (context == null) {
            return AppOpsManager.MODE_DEFAULT;
        }

        // system alert window is disable on low ram phones starting from Q
        final PackageManager pm = context.getPackageManager();
        // TVs are constantly plugged in and has less concern for memory/power
        if (ActivityManager.isLowRamDeviceStatic()
                && !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK, 0)) {
            return AppOpsManager.MODE_IGNORED;
        }

        return AppOpsManager.MODE_DEFAULT;
    }

    /**
     * Calculate left circular distance for two numbers modulo size.
     * @hide
     */
    public static int leftCircularDistance(int from, int to, int size) {
        return (to + size - from) % size;
    }

    /**
     * Helper method for noteOp, startOp and noteProxyOp to call AppOpsService to collect/log
     * stack traces
     *
     * <p> For each call, the stacktrace op code, package name and long version code will be
     * passed along where it will be logged/collected
     *
     * @param op The operation to note
     */
    private void collectNoteOpCallsForValidation(int op) {
        if (NOTE_OP_COLLECTION_ENABLED) {
            try {
                mService.collectNoteOpCallsForValidation(getFormattedStackTrace(),
                        op, mContext.getOpPackageName(), mContext.getApplicationInfo().longVersionCode);
            } catch (RemoteException e) {
                // Swallow error, only meant for logging ops, should not affect flow of the code
            }
        }
    }

    private static List<AttributedOpEntry> deduplicateDiscreteEvents(List<AttributedOpEntry> list) {
        int n = list.size();
        int i = 0;
        for (int j = 0, k = 0; j < n; i++, j = k) {
            long currentAccessTime = list.get(j).getLastAccessTime(OP_FLAGS_ALL);
            k = j + 1;
            while(k < n && list.get(k).getLastAccessTime(OP_FLAGS_ALL) == currentAccessTime) {
                k++;
            }
            list.set(i, mergeAttributedOpEntries(list.subList(j, k)));
        }
        for (; i < n; i++) {
            list.remove(list.size() - 1);
        }
        return list;
    }

    private static AttributedOpEntry mergeAttributedOpEntries(List<AttributedOpEntry> opEntries) {
        if (opEntries.size() == 1) {
            return opEntries.get(0);
        }
        LongSparseArray<AppOpsManager.NoteOpEvent> accessEvents = new LongSparseArray<>();
        LongSparseArray<AppOpsManager.NoteOpEvent> rejectEvents = new LongSparseArray<>();
        int opCount = opEntries.size();
        for (int i = 0; i < opCount; i++) {
            AttributedOpEntry a = opEntries.get(i);
            ArraySet<Long> keys = a.collectKeys();
            final int keyCount = keys.size();
            for (int k = 0; k < keyCount; k++) {
                final long key = keys.valueAt(k);

                final int uidState = extractUidStateFromKey(key);
                final int flags = extractFlagsFromKey(key);

                NoteOpEvent access = a.getLastAccessEvent(uidState, uidState, flags);
                NoteOpEvent reject = a.getLastRejectEvent(uidState, uidState, flags);

                if (access != null) {
                    NoteOpEvent existingAccess = accessEvents.get(key);
                    if (existingAccess == null || existingAccess.getDuration() == -1) {
                        accessEvents.append(key, access);
                    }
                }
                if (reject != null) {
                    rejectEvents.append(key, reject);
                }
            }
        }
        return new AttributedOpEntry(opEntries.get(0).mOp, false, accessEvents, rejectEvents);
    }
}
