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

package com.android.server.dreams;

import static android.Manifest.permission.BIND_DREAM_SERVICE;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;

import static com.android.server.wm.ActivityInterceptorCallback.DREAM_MANAGER_ORDERED_ID;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.IAppTask;
import android.app.TaskInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.hardware.display.AmbientDisplayConfiguration;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.util.Slog;
import android.view.Display;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.UiEventLoggerImpl;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.input.InputManagerInternal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.wm.ActivityInterceptorCallback;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Service api for managing dreams.
 *
 * @hide
 */
public final class DreamManagerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "DreamManagerService";

    private static final String DOZE_WAKE_LOCK_TAG = "dream:doze";
    private static final String DREAM_WAKE_LOCK_TAG = "dream:dream";

    /** Constants for the when to activate dreams. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({DREAM_ON_DOCK, DREAM_ON_CHARGE, DREAM_ON_DOCK_OR_CHARGE})
    public @interface WhenToDream {}
    private static final int DREAM_DISABLED = 0x0;
    private static final int DREAM_ON_DOCK = 0x1;
    private static final int DREAM_ON_CHARGE = 0x2;
    private static final int DREAM_ON_DOCK_OR_CHARGE = 0x3;

    private final Object mLock = new Object();

    private final Context mContext;
    private final Handler mHandler;
    private final DreamController mController;
    private final PowerManager mPowerManager;
    private final PowerManagerInternal mPowerManagerInternal;
    private final PowerManager.WakeLock mDozeWakeLock;
    private final ActivityTaskManagerInternal mAtmInternal;
    private final PackageManagerInternal mPmInternal;
    private final UserManager mUserManager;
    private final UiEventLogger mUiEventLogger;
    private final DreamUiEventLogger mDreamUiEventLogger;
    private final ComponentName mAmbientDisplayComponent;
    private final boolean mDismissDreamOnActivityStart;
    private final boolean mDreamsOnlyEnabledForDockUser;
    private final boolean mDreamsEnabledByDefaultConfig;
    private final boolean mDreamsActivatedOnChargeByDefault;
    private final boolean mDreamsActivatedOnDockByDefault;
    private final boolean mKeepDreamingWhenUnpluggingDefault;
    private final boolean mDreamsDisabledByAmbientModeSuppressionConfig;

    private final CopyOnWriteArrayList<DreamManagerInternal.DreamManagerStateListener>
            mDreamManagerStateListeners = new CopyOnWriteArrayList<>();

    @GuardedBy("mLock")
    private DreamRecord mCurrentDream;

    private boolean mForceAmbientDisplayEnabled;
    private SettingsObserver mSettingsObserver;
    private boolean mDreamsEnabledSetting;
    @WhenToDream private int mWhenToDream;
    private boolean mIsDocked;
    private boolean mIsCharging;

    // A temporary dream component that, when present, takes precedence over user configured dream
    // component.
    private ComponentName mSystemDreamComponent;

    private ComponentName mDreamOverlayServiceName;

    private final AmbientDisplayConfiguration mDozeConfig;
    private final ActivityInterceptorCallback mActivityInterceptorCallback =
            new ActivityInterceptorCallback() {
                @Nullable
                @Override
                public ActivityInterceptResult onInterceptActivityLaunch(@NonNull
                        ActivityInterceptorInfo info) {
                    return null;
                }

                @Override
                public void onActivityLaunched(TaskInfo taskInfo, ActivityInfo activityInfo,
                        ActivityInterceptorInfo info) {
                    final int activityType = taskInfo.getActivityType();
                    final boolean activityAllowed = activityType == ACTIVITY_TYPE_HOME
                            || activityType == ACTIVITY_TYPE_DREAM
                            || activityType == ACTIVITY_TYPE_ASSISTANT;

                    boolean shouldRequestAwaken;
                    synchronized (mLock) {
                        shouldRequestAwaken = mCurrentDream != null && !mCurrentDream.isWaking
                                && !mCurrentDream.isDozing && !activityAllowed;
                    }

                    if (shouldRequestAwaken) {
                        requestAwakenInternal(
                                "stopping dream due to activity start: " + activityInfo.name);
                    }
                }
            };

    private final BroadcastReceiver mChargingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mIsCharging = (BatteryManager.ACTION_CHARGING.equals(intent.getAction()));
        }
    };

    private final BroadcastReceiver mDockStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_DOCK_EVENT.equals(intent.getAction())) {
                int dockState = intent.getIntExtra(Intent.EXTRA_DOCK_STATE,
                        Intent.EXTRA_DOCK_STATE_UNDOCKED);
                mIsDocked = dockState != Intent.EXTRA_DOCK_STATE_UNDOCKED;
            }
        }
    };

    private final class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateWhenToDreamSettings();
        }
    }

    public DreamManagerService(Context context) {
        this(context, new DreamHandler(FgThread.get().getLooper()));
    }

    @VisibleForTesting
    DreamManagerService(Context context, Handler handler) {
        super(context);
        mContext = context;
        mHandler = handler;
        mController = new DreamController(context, mHandler, mControllerListener);

        mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mPowerManagerInternal = getLocalService(PowerManagerInternal.class);
        mAtmInternal = getLocalService(ActivityTaskManagerInternal.class);
        mPmInternal = getLocalService(PackageManagerInternal.class);
        mUserManager = context.getSystemService(UserManager.class);
        mDozeWakeLock = mPowerManager.newWakeLock(PowerManager.DOZE_WAKE_LOCK, DOZE_WAKE_LOCK_TAG);
        mDozeConfig = new AmbientDisplayConfiguration(mContext);
        mUiEventLogger = new UiEventLoggerImpl();
        mDreamUiEventLogger = new DreamUiEventLoggerImpl(
                mContext.getResources().getStringArray(R.array.config_loggable_dream_prefixes));
        AmbientDisplayConfiguration adc = new AmbientDisplayConfiguration(mContext);
        mAmbientDisplayComponent = ComponentName.unflattenFromString(adc.ambientDisplayComponent());
        mDreamsOnlyEnabledForDockUser =
                mContext.getResources().getBoolean(R.bool.config_dreamsOnlyEnabledForDockUser);
        mDismissDreamOnActivityStart = mContext.getResources().getBoolean(
                R.bool.config_dismissDreamOnActivityStart);

        mDreamsEnabledByDefaultConfig = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsEnabledByDefault);
        mDreamsActivatedOnChargeByDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnSleepByDefault);
        mDreamsActivatedOnDockByDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsActivatedOnDockByDefault);
        mSettingsObserver = new SettingsObserver(mHandler);
        mKeepDreamingWhenUnpluggingDefault = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_keepDreamingWhenUnplugging);
        mDreamsDisabledByAmbientModeSuppressionConfig = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_dreamsDisabledByAmbientModeSuppressionConfig);
    }

    @Override
    public void onStart() {
        publishBinderService(DreamService.DREAM_SERVICE, new BinderService());
        publishLocalService(DreamManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_THIRD_PARTY_APPS_CAN_START) {
            if (Build.IS_DEBUGGABLE) {
                SystemProperties.addChangeCallback(mSystemPropertiesChanged);
            }

            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DOZE_DOUBLE_TAP_GESTURE), false,
                    mDozeEnabledObserver, UserHandle.USER_ALL);
            writePulseGestureEnabled();

            if (mDismissDreamOnActivityStart) {
                mAtmInternal.registerActivityStartInterceptor(
                        DREAM_MANAGER_ORDERED_ID,
                        mActivityInterceptorCallback);
            }

            mContext.registerReceiver(
                    mDockStateReceiver, new IntentFilter(Intent.ACTION_DOCK_EVENT));
            IntentFilter chargingIntentFilter = new IntentFilter();
            chargingIntentFilter.addAction(BatteryManager.ACTION_CHARGING);
            chargingIntentFilter.addAction(BatteryManager.ACTION_DISCHARGING);
            mContext.registerReceiver(mChargingReceiver, chargingIntentFilter);

            mSettingsObserver = new SettingsObserver(mHandler);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK),
                    false, mSettingsObserver, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(
                            Settings.Secure.SCREENSAVER_ENABLED),
                    false, mSettingsObserver, UserHandle.USER_ALL);

            // We don't get an initial broadcast for the batter state, so we have to initialize
            // directly from BatteryManager.
            mIsCharging = mContext.getSystemService(BatteryManager.class).isCharging();

            updateWhenToDreamSettings();
        }
    }

    @Override
    public void onUserSwitching(@Nullable TargetUser from, @NonNull TargetUser to) {
        updateWhenToDreamSettings();

        mHandler.post(() -> {
            writePulseGestureEnabled();
            synchronized (mLock) {
                stopDreamLocked(false /*immediate*/, "user switched");
            }
        });
    }

    private void dumpInternal(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("DREAM MANAGER (dumpsys dreams)");
            pw.println();
            pw.println("mCurrentDream=" + mCurrentDream);
            pw.println("mForceAmbientDisplayEnabled=" + mForceAmbientDisplayEnabled);
            pw.println("mDreamsOnlyEnabledForDockUser=" + mDreamsOnlyEnabledForDockUser);
            pw.println("mDreamsEnabledSetting=" + mDreamsEnabledSetting);
            pw.println("mDreamsActivatedOnDockByDefault=" + mDreamsActivatedOnDockByDefault);
            pw.println("mDreamsActivatedOnChargeByDefault=" + mDreamsActivatedOnChargeByDefault);
            pw.println("mIsDocked=" + mIsDocked);
            pw.println("mIsCharging=" + mIsCharging);
            pw.println("mWhenToDream=" + mWhenToDream);
            pw.println("mKeepDreamingWhenUnpluggingDefault=" + mKeepDreamingWhenUnpluggingDefault);
            pw.println("getDozeComponent()=" + getDozeComponent());
            pw.println("mDreamOverlayServiceName="
                    + ComponentName.flattenToShortString(mDreamOverlayServiceName));
            pw.println();

            DumpUtils.dumpAsync(mHandler, (pw1, prefix) -> mController.dump(pw1), pw, "", 200);
        }
    }

    private void updateWhenToDreamSettings() {
        synchronized (mLock) {
            final ContentResolver resolver = mContext.getContentResolver();

            final int activateWhenCharging = (Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_SLEEP,
                    mDreamsActivatedOnChargeByDefault ? 1 : 0,
                    UserHandle.USER_CURRENT) != 0) ? DREAM_ON_CHARGE : DREAM_DISABLED;
            final int activateWhenDocked = (Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.SCREENSAVER_ACTIVATE_ON_DOCK,
                    mDreamsActivatedOnDockByDefault ? 1 : 0,
                    UserHandle.USER_CURRENT) != 0) ? DREAM_ON_DOCK : DREAM_DISABLED;
            mWhenToDream = activateWhenCharging + activateWhenDocked;

            mDreamsEnabledSetting = (Settings.Secure.getIntForUser(resolver,
                    Settings.Secure.SCREENSAVER_ENABLED,
                    mDreamsEnabledByDefaultConfig ? 1 : 0,
                    UserHandle.USER_CURRENT) != 0);
        }
    }

    private void reportKeepDreamingWhenUnpluggingChanged(boolean keepDreaming) {
        notifyDreamStateListeners(
                listener -> listener.onKeepDreamingWhenUnpluggingChanged(keepDreaming));
    }

    private void reportDreamingStarted() {
        notifyDreamStateListeners(listener -> listener.onDreamingStarted());
    }

    private void reportDreamingStopped() {
        notifyDreamStateListeners(listener -> listener.onDreamingStopped());
    }

    private void notifyDreamStateListeners(
            Consumer<DreamManagerInternal.DreamManagerStateListener> notifier) {
        mHandler.post(() -> {
            for (DreamManagerInternal.DreamManagerStateListener listener
                    : mDreamManagerStateListeners) {
                notifier.accept(listener);
            }
        });
    }

    /** Whether a real dream is occurring. */
    private boolean isDreamingInternal() {
        synchronized (mLock) {
            return mCurrentDream != null && !mCurrentDream.isPreview
                    && !mCurrentDream.isWaking;
        }
    }

    /** Whether a doze is occurring. */
    private boolean isDozingInternal() {
        synchronized (mLock) {
            return mCurrentDream != null && mCurrentDream.isDozing;
        }
    }

    /** Whether a real dream, or a dream preview is occurring. */
    private boolean isDreamingOrInPreviewInternal() {
        synchronized (mLock) {
            return mCurrentDream != null && !mCurrentDream.isWaking;
        }
    }

    /** Whether dreaming can start given user settings and the current dock/charge state. */
    private boolean canStartDreamingInternal(boolean isScreenOn) {
        synchronized (mLock) {
            // Can't start dreaming if we are already dreaming.
            if (isScreenOn && isDreamingInternal()) {
                return false;
            }

            if (!mDreamsEnabledSetting) {
                return false;
            }

            if (!dreamsEnabledForUser(ActivityManager.getCurrentUser())) {
                return false;
            }

            if (!mUserManager.isUserUnlocked()) {
                return false;
            }

            if (mDreamsDisabledByAmbientModeSuppressionConfig
                    && mPowerManagerInternal.isAmbientDisplaySuppressed()) {
                // Don't dream if Bedtime (or something else) is suppressing ambient.
                Slog.i(TAG, "Can't start dreaming because ambient is suppressed.");
                return false;
            }

            if ((mWhenToDream & DREAM_ON_CHARGE) == DREAM_ON_CHARGE) {
                return mIsCharging;
            }

            if ((mWhenToDream & DREAM_ON_DOCK) == DREAM_ON_DOCK) {
                return mIsDocked;
            }

            return false;
        }
    }

    protected void requestStartDreamFromShell() {
        requestDreamInternal();
    }

    private void requestDreamInternal() {
        // Ask the power manager to nap.  It will eventually call back into
        // startDream() if/when it is appropriate to start dreaming.
        // Because napping could cause the screen to turn off immediately if the dream
        // cannot be started, we keep one eye open and gently poke user activity.
        long time = SystemClock.uptimeMillis();
        mPowerManager.userActivity(time, /* noChangeLights= */ true);
        mPowerManagerInternal.nap(time, /* allowWake= */ true);
    }

    private void requestAwakenInternal(String reason) {
        // Treat an explicit request to awaken as user activity so that the
        // device doesn't immediately go to sleep if the timeout expired,
        // for example when being undocked.
        long time = SystemClock.uptimeMillis();
        mPowerManager.userActivity(time, false /*noChangeLights*/);
        stopDreamInternal(false /*immediate*/, reason);
    }

    private void finishSelfInternal(IBinder token, boolean immediate) {
        if (DEBUG) {
            Slog.d(TAG, "Dream finished: " + token + ", immediate=" + immediate);
        }

        // Note that a dream finishing and self-terminating is not
        // itself considered user activity.  If the dream is ending because
        // the user interacted with the device then user activity will already
        // have been poked so the device will stay awake a bit longer.
        // If the dream is ending on its own for other reasons and no wake
        // locks are held and the user activity timeout has expired then the
        // device may simply go to sleep.
        synchronized (mLock) {
            if (mCurrentDream != null && mCurrentDream.token == token) {
                stopDreamLocked(immediate, "finished self");
            }
        }
    }

    private void testDreamInternal(ComponentName dream, int userId) {
        synchronized (mLock) {
            startDreamLocked(dream, true /*isPreviewMode*/, false /*canDoze*/, userId,
                    "test dream" /*reason*/);
        }
    }

    private void startDreamInternal(boolean doze, String reason) {
        final int userId = ActivityManager.getCurrentUser();
        final ComponentName dream = chooseDreamForUser(doze, userId);
        if (dream != null) {
            synchronized (mLock) {
                startDreamLocked(dream, false /*isPreviewMode*/, doze, userId, reason);
            }
        }
    }

    protected void requestStopDreamFromShell() {
        stopDreamInternal(false, "stopping dream from shell");
    }

    private void stopDreamInternal(boolean immediate, String reason) {
        synchronized (mLock) {
            stopDreamLocked(immediate, reason);
        }
    }

    private void startDozingInternal(IBinder token, int screenState,
            int screenBrightness) {
        if (DEBUG) {
            Slog.d(TAG, "Dream requested to start dozing: " + token
                    + ", screenState=" + screenState
                    + ", screenBrightness=" + screenBrightness);
        }

        synchronized (mLock) {
            if (mCurrentDream != null && mCurrentDream.token == token && mCurrentDream.canDoze) {
                mCurrentDream.dozeScreenState = screenState;
                mCurrentDream.dozeScreenBrightness = screenBrightness;
                mPowerManagerInternal.setDozeOverrideFromDreamManager(
                        screenState, screenBrightness);
                if (!mCurrentDream.isDozing) {
                    mCurrentDream.isDozing = true;
                    mDozeWakeLock.acquire();
                }
            }
        }
    }

    private void stopDozingInternal(IBinder token) {
        if (DEBUG) {
            Slog.d(TAG, "Dream requested to stop dozing: " + token);
        }

        synchronized (mLock) {
            if (mCurrentDream != null && mCurrentDream.token == token && mCurrentDream.isDozing) {
                mCurrentDream.isDozing = false;
                mDozeWakeLock.release();
                mPowerManagerInternal.setDozeOverrideFromDreamManager(
                        Display.STATE_UNKNOWN, PowerManager.BRIGHTNESS_DEFAULT);
            }
        }
    }

    private void forceAmbientDisplayEnabledInternal(boolean enabled) {
        if (DEBUG) {
            Slog.d(TAG, "Force ambient display enabled: " + enabled);
        }

        synchronized (mLock) {
            mForceAmbientDisplayEnabled = enabled;
        }
    }

    /**
     * If doze is true, returns the doze component for the user.
     * Otherwise, returns the system dream component, if present.
     * Otherwise, returns the first valid user configured dream component.
     */
    private ComponentName chooseDreamForUser(boolean doze, int userId) {
        if (doze) {
            ComponentName dozeComponent = getDozeComponent(userId);
            return validateDream(dozeComponent) ? dozeComponent : null;
        }

        if (mSystemDreamComponent != null) {
            return mSystemDreamComponent;
        }

        ComponentName[] dreams = getDreamComponentsForUser(userId);
        return dreams != null && dreams.length != 0 ? dreams[0] : null;
    }

    private boolean validateDream(ComponentName component) {
        if (component == null) return false;
        final ServiceInfo serviceInfo = getServiceInfo(component);
        if (serviceInfo == null) {
            Slog.w(TAG, "Dream " + component + " does not exist");
            return false;
        } else if (serviceInfo.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.LOLLIPOP
                && !BIND_DREAM_SERVICE.equals(serviceInfo.permission)) {
            Slog.w(TAG, "Dream " + component
                    + " is not available because its manifest is missing the " + BIND_DREAM_SERVICE
                    + " permission on the dream service declaration.");
            return false;
        }
        return true;
    }

    private ComponentName[] getDreamComponentsForUser(int userId) {
        if (!dreamsEnabledForUser(userId)) {
            // Don't return any dream components if the user is not allowed to dream.
            return null;
        }

        String names = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_COMPONENTS,
                userId);
        ComponentName[] components = componentsFromString(names);

        // first, ensure components point to valid services
        List<ComponentName> validComponents = new ArrayList<>();
        if (components != null) {
            for (ComponentName component : components) {
                if (validateDream(component)) {
                    validComponents.add(component);
                }
            }
        }

        // fallback to the default dream component if necessary
        if (validComponents.isEmpty()) {
            ComponentName defaultDream = getDefaultDreamComponentForUser(userId);
            if (defaultDream != null) {
                Slog.w(TAG, "Falling back to default dream " + defaultDream);
                validComponents.add(defaultDream);
            }
        }
        return validComponents.toArray(new ComponentName[validComponents.size()]);
    }

    private void setDreamComponentsForUser(int userId, ComponentName[] componentNames) {
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_COMPONENTS,
                componentsToString(componentNames),
                userId);
    }

    private void setSystemDreamComponentInternal(ComponentName componentName) {
        synchronized (mLock) {
            if (Objects.equals(mSystemDreamComponent, componentName)) {
                return;
            }

            mSystemDreamComponent = componentName;
            reportKeepDreamingWhenUnpluggingChanged(shouldKeepDreamingWhenUnplugging());
            // Switch dream if currently dreaming and not dozing.
            if (isDreamingInternal() && !isDozingInternal()) {
                startDreamInternal(false /*doze*/, (mSystemDreamComponent == null ? "clear" : "set")
                        + " system dream component" /*reason*/);
            }
        }
    }

    private boolean shouldKeepDreamingWhenUnplugging() {
        return mKeepDreamingWhenUnpluggingDefault && mSystemDreamComponent == null;
    }

    private ComponentName getDefaultDreamComponentForUser(int userId) {
        String name = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT,
                userId);
        return name == null ? null : ComponentName.unflattenFromString(name);
    }

    private ComponentName getDozeComponent() {
        return getDozeComponent(ActivityManager.getCurrentUser());
    }

    private ComponentName getDozeComponent(int userId) {
        if (mForceAmbientDisplayEnabled || mDozeConfig.enabled(userId)) {
            return ComponentName.unflattenFromString(mDozeConfig.ambientDisplayComponent());
        } else {
            return null;
        }

    }

    private boolean dreamsEnabledForUser(int userId) {
        if (!mDreamsOnlyEnabledForDockUser) return true;
        if (userId < 0) return false;
        final int mainUserId = LocalServices.getService(UserManagerInternal.class).getMainUserId();
        return userId == mainUserId;
    }

    private ServiceInfo getServiceInfo(ComponentName name) {
        try {
            return name != null ? mContext.getPackageManager().getServiceInfo(name,
                    PackageManager.MATCH_DEBUG_TRIAGED_MISSING) : null;
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    @GuardedBy("mLock")
    private void startDreamLocked(final ComponentName name,
            final boolean isPreviewMode, final boolean canDoze, final int userId,
            final String reason) {
        if (mCurrentDream != null
                && !mCurrentDream.isWaking
                && Objects.equals(mCurrentDream.name, name)
                && mCurrentDream.isPreview == isPreviewMode
                && mCurrentDream.canDoze == canDoze
                && mCurrentDream.userId == userId) {
            Slog.i(TAG, "Already in target dream.");
            return;
        }

        Slog.i(TAG, "Entering dreamland.");

        if (mCurrentDream != null && mCurrentDream.isDozing) {
            stopDozingInternal(mCurrentDream.token);
        }

        mCurrentDream = new DreamRecord(name, userId, isPreviewMode, canDoze);

        if (!mCurrentDream.name.equals(mAmbientDisplayComponent)) {
            // TODO(b/213906448): Remove when metrics based on new atom are fully rolled out.
            mUiEventLogger.log(DreamUiEventLogger.DreamUiEventEnum.DREAM_START);
            mDreamUiEventLogger.log(DreamUiEventLogger.DreamUiEventEnum.DREAM_START,
                    mCurrentDream.name.flattenToString());
        }

        PowerManager.WakeLock wakeLock = mPowerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, DREAM_WAKE_LOCK_TAG);
        final Binder dreamToken = mCurrentDream.token;
        mHandler.post(wakeLock.wrap(() -> {
            mAtmInternal.notifyActiveDreamChanged(name);
            mController.startDream(dreamToken, name, isPreviewMode, canDoze, userId, wakeLock,
                    mDreamOverlayServiceName, reason);
        }));
    }

    @GuardedBy("mLock")
    private void stopDreamLocked(final boolean immediate, String reason) {
        if (mCurrentDream != null) {
            if (immediate) {
                Slog.i(TAG, "Leaving dreamland.");
                cleanupDreamLocked();
            } else if (mCurrentDream.isWaking) {
                return; // already waking
            } else {
                Slog.i(TAG, "Gently waking up from dream.");
                mCurrentDream.isWaking = true;
            }

            mHandler.post(() -> mController.stopDream(immediate, reason));
        }
    }

    @GuardedBy("mLock")
    private void cleanupDreamLocked() {
        mHandler.post(() -> mAtmInternal.notifyActiveDreamChanged(null));

        if (mCurrentDream == null) {
            return;
        }

        if (!mCurrentDream.name.equals(mAmbientDisplayComponent)) {
            // TODO(b/213906448): Remove when metrics based on new atom are fully rolled out.
            mUiEventLogger.log(DreamUiEventLogger.DreamUiEventEnum.DREAM_STOP);
            mDreamUiEventLogger.log(DreamUiEventLogger.DreamUiEventEnum.DREAM_STOP,
                    mCurrentDream.name.flattenToString());
        }
        if (mCurrentDream.isDozing) {
            mDozeWakeLock.release();
        }
        mCurrentDream = null;
    }

    private void checkPermission(String permission) {
        if (mContext.checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
    }

    private void writePulseGestureEnabled() {
        ComponentName name = getDozeComponent();
        boolean dozeEnabled = validateDream(name);
        LocalServices.getService(InputManagerInternal.class).setPulseGestureEnabled(dozeEnabled);
    }

    private static String componentsToString(ComponentName[] componentNames) {
        if (componentNames == null) {
            return null;
        }
        StringBuilder names = new StringBuilder();
        for (ComponentName componentName : componentNames) {
            if (names.length() > 0) {
                names.append(',');
            }
            names.append(componentName.flattenToString());
        }
        return names.toString();
    }

    private static ComponentName[] componentsFromString(String names) {
        if (names == null) {
            return null;
        }
        String[] namesArray = names.split(",");
        ComponentName[] componentNames = new ComponentName[namesArray.length];
        for (int i = 0; i < namesArray.length; i++) {
            componentNames[i] = ComponentName.unflattenFromString(namesArray[i]);
        }
        return componentNames;
    }

    private final DreamController.Listener mControllerListener = new DreamController.Listener() {
        @Override
        public void onDreamStarted(Binder token) {
            // Note that this event is distinct from DreamManagerService#startDreamLocked as it
            // tracks the DreamService attach point from DreamController, closest to the broadcast
            // of ACTION_DREAMING_STARTED.

            reportDreamingStarted();
        }

        @Override
        public void onDreamStopped(Binder token) {
            synchronized (mLock) {
                if (mCurrentDream != null && mCurrentDream.token == token) {
                    cleanupDreamLocked();
                }
            }

            reportDreamingStopped();
        }
    };

    private final ContentObserver mDozeEnabledObserver = new ContentObserver(null) {
        @Override
        public void onChange(boolean selfChange) {
            writePulseGestureEnabled();
        }
    };

    /**
     * Handler for asynchronous operations performed by the dream manager.
     * Ensures operations to {@link DreamController} are single-threaded.
     */
    private static final class DreamHandler extends Handler {
        public DreamHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }
    }

    private final class BinderService extends IDreamManager.Stub {
        @Override // Binder call
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
            final long ident = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        public void onShellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                @Nullable FileDescriptor err,
                @NonNull String[] args, @Nullable ShellCallback callback,
                @NonNull ResultReceiver resultReceiver) throws RemoteException {
            new DreamShellCommand(DreamManagerService.this)
                    .exec(this, in, out, err, args, callback, resultReceiver);
        }

        @Override // Binder call
        public ComponentName[] getDreamComponents() {
            return getDreamComponentsForUser(UserHandle.getCallingUserId());
        }

        @Override // Binder call
        public ComponentName[] getDreamComponentsForUser(int userId) {
            checkPermission(android.Manifest.permission.READ_DREAM_STATE);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "getDreamComponents", null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return DreamManagerService.this.getDreamComponentsForUser(userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void setDreamComponents(ComponentName[] componentNames) {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            final int userId = UserHandle.getCallingUserId();
            final long ident = Binder.clearCallingIdentity();
            try {
                setDreamComponentsForUser(userId, componentNames);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void setDreamComponentsForUser(int userId, ComponentName[] componentNames) {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "setDreamComponents", null);

            final long ident = Binder.clearCallingIdentity();
            try {
                DreamManagerService.this.setDreamComponentsForUser(userId, componentNames);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void setSystemDreamComponent(ComponentName componentName) {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                DreamManagerService.this.setSystemDreamComponentInternal(componentName);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void registerDreamOverlayService(ComponentName overlayComponent) {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            // Store the overlay service component so that it can be passed to the dream when it is
            // invoked.
            mDreamOverlayServiceName = overlayComponent;
        }

        @Override // Binder call
        public ComponentName getDefaultDreamComponentForUser(int userId) {
            checkPermission(android.Manifest.permission.READ_DREAM_STATE);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "getDefaultDreamComponent", null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return DreamManagerService.this.getDefaultDreamComponentForUser(userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isDreaming() {
            checkPermission(android.Manifest.permission.READ_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                return isDreamingInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public boolean isDreamingOrInPreview() {
            checkPermission(android.Manifest.permission.READ_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                return isDreamingOrInPreviewInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }


        @Override // Binder call
        public void dream() {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                requestDreamInternal();
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void testDream(int userId, ComponentName dream) {
            if (dream == null) {
                throw new IllegalArgumentException("dream must not be null");
            }
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);
            userId = ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, true, "testDream", null);

            final int currentUserId = ActivityManager.getCurrentUser();
            if (userId != currentUserId) {
                // This check is inherently prone to races but at least it's something.
                Slog.w(TAG, "Aborted attempt to start a test dream while a different "
                        + " user is active: userId=" + userId
                        + ", currentUserId=" + currentUserId);
                return;
            }
            final long ident = Binder.clearCallingIdentity();
            try {
                testDreamInternal(dream, userId);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void awaken() {
            checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

            final long ident = Binder.clearCallingIdentity();
            try {
                requestAwakenInternal("request awaken");
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void finishSelf(IBinder token, boolean immediate) {
            // Requires no permission, called by Dream from an arbitrary process.
            if (token == null) {
                throw new IllegalArgumentException("token must not be null");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                finishSelfInternal(token, immediate);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void startDozing(IBinder token, int screenState, int screenBrightness) {
            // Requires no permission, called by Dream from an arbitrary process.
            if (token == null) {
                throw new IllegalArgumentException("token must not be null");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                startDozingInternal(token, screenState, screenBrightness);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void stopDozing(IBinder token) {
            // Requires no permission, called by Dream from an arbitrary process.
            if (token == null) {
                throw new IllegalArgumentException("token must not be null");
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                stopDozingInternal(token);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void forceAmbientDisplayEnabled(boolean enabled) {
            checkPermission(android.Manifest.permission.DEVICE_POWER);

            final long ident = Binder.clearCallingIdentity();
            try {
                forceAmbientDisplayEnabledInternal(enabled);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        @Override // Binder call
        public void startDreamActivity(@NonNull Intent intent) {
            final int callingUid = Binder.getCallingUid();
            final int callingPid = Binder.getCallingPid();
            // We post here, because startDreamActivity and setDreamAppTask have to run
            // synchronously and DreamController#setDreamAppTask has to run on mHandler.
            mHandler.post(() -> {
                final Binder dreamToken;
                final String dreamPackageName;
                synchronized (mLock) {
                    if (mCurrentDream == null) {
                        Slog.e(TAG, "Attempt to start DreamActivity, but the device is not "
                                + "dreaming. Aborting without starting the DreamActivity.");
                        return;
                    }
                    dreamToken = mCurrentDream.token;
                    dreamPackageName = mCurrentDream.name.getPackageName();
                }

                if (!canLaunchDreamActivity(dreamPackageName, intent.getPackage(),
                            callingUid)) {
                    Slog.e(TAG, "The dream activity can be started only when the device is dreaming"
                            + " and only by the active dream package.");
                    return;
                }

                final IAppTask appTask = mAtmInternal.startDreamActivity(intent, callingUid,
                        callingPid);
                if (appTask == null) {
                    Slog.e(TAG, "Could not start dream activity.");
                    stopDreamInternal(true, "DreamActivity not started");
                    return;
                }
                mController.setDreamAppTask(dreamToken, appTask);
            });
        }

        boolean canLaunchDreamActivity(String dreamPackageName, String packageName,
                int callingUid) {
            if (dreamPackageName == null || packageName == null) {
                Slog.e(TAG, "Cannot launch dream activity due to invalid state. dream component= "
                        + dreamPackageName + ", packageName=" + packageName);
                return false;
            }
            if (!mPmInternal.isSameApp(packageName, callingUid, UserHandle.getUserId(callingUid))) {
                Slog.e(TAG, "Cannot launch dream activity because package="
                        + packageName + " does not match callingUid=" + callingUid);
                return false;
            }
            if (packageName.equals(dreamPackageName)) {
                return true;
            }
            Slog.e(TAG, "Dream packageName does not match active dream. Package " + packageName
                    + " does not match " + dreamPackageName);
            return false;
        }

    }

    private final class LocalService extends DreamManagerInternal {
        @Override
        public void startDream(boolean doze, String reason) {
            startDreamInternal(doze, reason);
        }

        @Override
        public void stopDream(boolean immediate, String reason) {
            stopDreamInternal(immediate, reason);
        }

        @Override
        public boolean isDreaming() {
            return isDreamingInternal();
        }

        @Override
        public boolean canStartDreaming(boolean isScreenOn) {
            return canStartDreamingInternal(isScreenOn);
        }

        @Override
        public void requestDream() {
            requestDreamInternal();
        }

        @Override
        public void registerDreamManagerStateListener(DreamManagerStateListener listener) {
            mDreamManagerStateListeners.add(listener);
            // Initialize the listener's state.
            listener.onKeepDreamingWhenUnpluggingChanged(shouldKeepDreamingWhenUnplugging());
        }

        @Override
        public void unregisterDreamManagerStateListener(DreamManagerStateListener listener) {
            mDreamManagerStateListeners.remove(listener);
        }
    }

    private static final class DreamRecord {
        public final Binder token = new Binder();
        public final ComponentName name;
        public final int userId;
        public final boolean isPreview;
        public final boolean canDoze;
        public boolean isDozing = false;
        public boolean isWaking = false;
        public int dozeScreenState = Display.STATE_UNKNOWN;
        public int dozeScreenBrightness = PowerManager.BRIGHTNESS_DEFAULT;

        DreamRecord(ComponentName name, int userId, boolean isPreview, boolean canDoze) {
            this.name = name;
            this.userId = userId;
            this.isPreview = isPreview;
            this.canDoze = canDoze;
        }

        @Override
        public String toString() {
            return "DreamRecord{"
                    + "token=" + token
                    + ", name=" + name
                    + ", userId=" + userId
                    + ", isPreview=" + isPreview
                    + ", canDoze=" + canDoze
                    + ", isDozing=" + isDozing
                    + ", isWaking=" + isWaking
                    + ", dozeScreenState=" + dozeScreenState
                    + ", dozeScreenBrightness=" + dozeScreenBrightness
                    + '}';
        }
    }

    private final Runnable mSystemPropertiesChanged = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "System properties changed");
            synchronized (mLock) {
                if (mCurrentDream != null &&  mCurrentDream.name != null && mCurrentDream.canDoze
                        && !mCurrentDream.name.equals(getDozeComponent())) {
                    // May have updated the doze component, wake up
                    mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                            "android.server.dreams:SYSPROP");
                }
            }
        }
    };
}
