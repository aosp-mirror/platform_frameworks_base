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

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.input.InputManagerInternal;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.PowerManagerInternal;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.DreamManagerInternal;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.util.Slog;
import android.view.Display;

import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Service api for managing dreams.
 *
 * @hide
 */
public final class DreamManagerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "DreamManagerService";

    private final Object mLock = new Object();

    private final Context mContext;
    private final DreamHandler mHandler;
    private final DreamController mController;
    private final PowerManager mPowerManager;
    private final PowerManagerInternal mPowerManagerInternal;
    private final PowerManager.WakeLock mDozeWakeLock;
    private final ActivityTaskManagerInternal mAtmInternal;

    private Binder mCurrentDreamToken;
    private ComponentName mCurrentDreamName;
    private int mCurrentDreamUserId;
    private boolean mCurrentDreamIsTest;
    private boolean mCurrentDreamCanDoze;
    private boolean mCurrentDreamIsDozing;
    private boolean mCurrentDreamIsWaking;
    private boolean mForceAmbientDisplayEnabled;
    private int mCurrentDreamDozeScreenState = Display.STATE_UNKNOWN;
    private int mCurrentDreamDozeScreenBrightness = PowerManager.BRIGHTNESS_DEFAULT;

    private AmbientDisplayConfiguration mDozeConfig;

    public DreamManagerService(Context context) {
        super(context);
        mContext = context;
        mHandler = new DreamHandler(FgThread.get().getLooper());
        mController = new DreamController(context, mHandler, mControllerListener);

        mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        mPowerManagerInternal = getLocalService(PowerManagerInternal.class);
        mAtmInternal = getLocalService(ActivityTaskManagerInternal.class);
        mDozeWakeLock = mPowerManager.newWakeLock(PowerManager.DOZE_WAKE_LOCK, TAG);
        mDozeConfig = new AmbientDisplayConfiguration(mContext);
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
            mContext.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    writePulseGestureEnabled();
                    synchronized (mLock) {
                        stopDreamLocked(false /*immediate*/, "user switched");
                    }
                }
            }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(Settings.Secure.DOZE_DOUBLE_TAP_GESTURE), false,
                    mDozeEnabledObserver, UserHandle.USER_ALL);
            writePulseGestureEnabled();
        }
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("DREAM MANAGER (dumpsys dreams)");
        pw.println();
        pw.println("mCurrentDreamToken=" + mCurrentDreamToken);
        pw.println("mCurrentDreamName=" + mCurrentDreamName);
        pw.println("mCurrentDreamUserId=" + mCurrentDreamUserId);
        pw.println("mCurrentDreamIsTest=" + mCurrentDreamIsTest);
        pw.println("mCurrentDreamCanDoze=" + mCurrentDreamCanDoze);
        pw.println("mCurrentDreamIsDozing=" + mCurrentDreamIsDozing);
        pw.println("mCurrentDreamIsWaking=" + mCurrentDreamIsWaking);
        pw.println("mForceAmbientDisplayEnabled=" + mForceAmbientDisplayEnabled);
        pw.println("mCurrentDreamDozeScreenState="
                + Display.stateToString(mCurrentDreamDozeScreenState));
        pw.println("mCurrentDreamDozeScreenBrightness=" + mCurrentDreamDozeScreenBrightness);
        pw.println("getDozeComponent()=" + getDozeComponent());
        pw.println();

        DumpUtils.dumpAsync(mHandler, new DumpUtils.Dump() {
            @Override
            public void dump(PrintWriter pw, String prefix) {
                mController.dump(pw);
            }
        }, pw, "", 200);
    }

    private boolean isDreamingInternal() {
        synchronized (mLock) {
            return mCurrentDreamToken != null && !mCurrentDreamIsTest
                    && !mCurrentDreamIsWaking;
        }
    }

    private void requestDreamInternal() {
        // Ask the power manager to nap.  It will eventually call back into
        // startDream() if/when it is appropriate to start dreaming.
        // Because napping could cause the screen to turn off immediately if the dream
        // cannot be started, we keep one eye open and gently poke user activity.
        long time = SystemClock.uptimeMillis();
        mPowerManager.userActivity(time, true /*noChangeLights*/);
        mPowerManager.nap(time);
    }

    private void requestAwakenInternal() {
        // Treat an explicit request to awaken as user activity so that the
        // device doesn't immediately go to sleep if the timeout expired,
        // for example when being undocked.
        long time = SystemClock.uptimeMillis();
        mPowerManager.userActivity(time, false /*noChangeLights*/);
        stopDreamInternal(false /*immediate*/, "request awaken");
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
            if (mCurrentDreamToken == token) {
                stopDreamLocked(immediate, "finished self");
            }
        }
    }

    private void testDreamInternal(ComponentName dream, int userId) {
        synchronized (mLock) {
            startDreamLocked(dream, true /*isTest*/, false /*canDoze*/, userId);
        }
    }

    private void startDreamInternal(boolean doze) {
        final int userId = ActivityManager.getCurrentUser();
        final ComponentName dream = chooseDreamForUser(doze, userId);
        if (dream != null) {
            synchronized (mLock) {
                startDreamLocked(dream, false /*isTest*/, doze, userId);
            }
        }
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
            if (mCurrentDreamToken == token && mCurrentDreamCanDoze) {
                mCurrentDreamDozeScreenState = screenState;
                mCurrentDreamDozeScreenBrightness = screenBrightness;
                mPowerManagerInternal.setDozeOverrideFromDreamManager(
                        screenState, screenBrightness);
                if (!mCurrentDreamIsDozing) {
                    mCurrentDreamIsDozing = true;
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
            if (mCurrentDreamToken == token && mCurrentDreamIsDozing) {
                mCurrentDreamIsDozing = false;
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

    private ComponentName getActiveDreamComponentInternal(boolean doze) {
        return chooseDreamForUser(doze, ActivityManager.getCurrentUser());
    }

    private ComponentName chooseDreamForUser(boolean doze, int userId) {
        if (doze) {
            ComponentName dozeComponent = getDozeComponent(userId);
            return validateDream(dozeComponent) ? dozeComponent : null;
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
        String names = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.SCREENSAVER_COMPONENTS,
                userId);
        ComponentName[] components = componentsFromString(names);

        // first, ensure components point to valid services
        List<ComponentName> validComponents = new ArrayList<ComponentName>();
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

    private ServiceInfo getServiceInfo(ComponentName name) {
        try {
            return name != null ? mContext.getPackageManager().getServiceInfo(name,
                    PackageManager.MATCH_DEBUG_TRIAGED_MISSING) : null;
        } catch (NameNotFoundException e) {
            return null;
        }
    }

    private void startDreamLocked(final ComponentName name,
            final boolean isTest, final boolean canDoze, final int userId) {
        if (Objects.equals(mCurrentDreamName, name)
                && mCurrentDreamIsTest == isTest
                && mCurrentDreamCanDoze == canDoze
                && mCurrentDreamUserId == userId) {
            Slog.i(TAG, "Already in target dream.");
            return;
        }

        stopDreamLocked(true /*immediate*/, "starting new dream");

        Slog.i(TAG, "Entering dreamland.");

        final Binder newToken = new Binder();
        mCurrentDreamToken = newToken;
        mCurrentDreamName = name;
        mCurrentDreamIsTest = isTest;
        mCurrentDreamCanDoze = canDoze;
        mCurrentDreamUserId = userId;

        PowerManager.WakeLock wakeLock = mPowerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "startDream");
        mHandler.post(wakeLock.wrap(() -> {
            mAtmInternal.notifyDreamStateChanged(true);
            mController.startDream(newToken, name, isTest, canDoze, userId, wakeLock);
        }));
    }

    private void stopDreamLocked(final boolean immediate, String reason) {
        if (mCurrentDreamToken != null) {
            if (immediate) {
                Slog.i(TAG, "Leaving dreamland.");
                cleanupDreamLocked();
            } else if (mCurrentDreamIsWaking) {
                return; // already waking
            } else {
                Slog.i(TAG, "Gently waking up from dream.");
                mCurrentDreamIsWaking = true;
            }

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    Slog.i(TAG, "Performing gentle wake from dream.");
                    mController.stopDream(immediate, reason);
                }
            });
        }
    }

    private void cleanupDreamLocked() {
        mCurrentDreamToken = null;
        mCurrentDreamName = null;
        mCurrentDreamIsTest = false;
        mCurrentDreamCanDoze = false;
        mCurrentDreamUserId = 0;
        mCurrentDreamIsWaking = false;
        if (mCurrentDreamIsDozing) {
            mCurrentDreamIsDozing = false;
            mDozeWakeLock.release();
        }
        mCurrentDreamDozeScreenState = Display.STATE_UNKNOWN;
        mCurrentDreamDozeScreenBrightness = PowerManager.BRIGHTNESS_DEFAULT;
        mAtmInternal.notifyDreamStateChanged(false);
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
        StringBuilder names = new StringBuilder();
        if (componentNames != null) {
            for (ComponentName componentName : componentNames) {
                if (names.length() > 0) {
                    names.append(',');
                }
                names.append(componentName.flattenToString());
            }
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
        public void onDreamStopped(Binder token) {
            synchronized (mLock) {
                if (mCurrentDreamToken == token) {
                    cleanupDreamLocked();
                }
            }
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
    private final class DreamHandler extends Handler {
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
                requestAwakenInternal();
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
    }

    private final class LocalService extends DreamManagerInternal {
        @Override
        public void startDream(boolean doze) {
            startDreamInternal(doze);
        }

        @Override
        public void stopDream(boolean immediate) {
            stopDreamInternal(immediate, "requested stopDream");
        }

        @Override
        public boolean isDreaming() {
            return isDreamingInternal();
        }

        @Override
        public ComponentName getActiveDreamComponent(boolean doze) {
            return getActiveDreamComponentInternal(doze);
        }
    }

    private final Runnable mSystemPropertiesChanged = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Slog.d(TAG, "System properties changed");
            synchronized (mLock) {
                if (mCurrentDreamName != null && mCurrentDreamCanDoze
                        && !mCurrentDreamName.equals(getDozeComponent())) {
                    // May have updated the doze component, wake up
                    mPowerManager.wakeUp(SystemClock.uptimeMillis(),
                            "android.server.dreams:SYSPROP");
                }
            }
        }
    };
}
