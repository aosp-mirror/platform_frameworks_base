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

import com.android.internal.util.DumpUtils;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.dreams.IDreamManager;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import libcore.util.Objects;

/**
 * Service api for managing dreams.
 *
 * @hide
 */
public final class DreamManagerService extends IDreamManager.Stub {
    private static final boolean DEBUG = false;
    private static final String TAG = "DreamManagerService";

    private final Object mLock = new Object();

    private final Context mContext;
    private final DreamHandler mHandler;
    private final DreamController mController;
    private final PowerManager mPowerManager;

    private Binder mCurrentDreamToken;
    private ComponentName mCurrentDreamName;
    private int mCurrentDreamUserId;
    private boolean mCurrentDreamIsTest;

    public DreamManagerService(Context context, Handler mainHandler) {
        mContext = context;
        mHandler = new DreamHandler(mainHandler.getLooper());
        mController = new DreamController(context, mHandler, mControllerListener);

        mPowerManager = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
    }

    public void systemRunning() {
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                synchronized (mLock) {
                    stopDreamLocked();
                }
            }
        }, new IntentFilter(Intent.ACTION_USER_SWITCHED), null, mHandler);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (mContext.checkCallingOrSelfPermission("android.permission.DUMP")
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump DreamManager from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return;
        }

        pw.println("DREAM MANAGER (dumpsys dreams)");
        pw.println();

        pw.println("mCurrentDreamToken=" + mCurrentDreamToken);
        pw.println("mCurrentDreamName=" + mCurrentDreamName);
        pw.println("mCurrentDreamUserId=" + mCurrentDreamUserId);
        pw.println("mCurrentDreamIsTest=" + mCurrentDreamIsTest);
        pw.println();

        DumpUtils.dumpAsync(mHandler, new DumpUtils.Dump() {
            @Override
            public void dump(PrintWriter pw) {
                mController.dump(pw);
            }
        }, pw, 200);
    }

    @Override // Binder call
    public ComponentName[] getDreamComponents() {
        checkPermission(android.Manifest.permission.READ_DREAM_STATE);

        final int userId = UserHandle.getCallingUserId();
        final long ident = Binder.clearCallingIdentity();
        try {
            return getDreamComponentsForUser(userId);
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
            Settings.Secure.putStringForUser(mContext.getContentResolver(),
                    Settings.Secure.SCREENSAVER_COMPONENTS,
                    componentsToString(componentNames),
                    userId);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public ComponentName getDefaultDreamComponent() {
        checkPermission(android.Manifest.permission.READ_DREAM_STATE);

        final int userId = UserHandle.getCallingUserId();
        final long ident = Binder.clearCallingIdentity();
        try {
            String name = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                    Settings.Secure.SCREENSAVER_DEFAULT_COMPONENT,
                    userId);
            return name == null ? null : ComponentName.unflattenFromString(name);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public boolean isDreaming() {
        checkPermission(android.Manifest.permission.READ_DREAM_STATE);

        synchronized (mLock) {
            return mCurrentDreamToken != null && !mCurrentDreamIsTest;
        }
    }

    @Override // Binder call
    public void dream() {
        checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

        final long ident = Binder.clearCallingIdentity();
        try {
            // Ask the power manager to nap.  It will eventually call back into
            // startDream() if/when it is appropriate to start dreaming.
            // Because napping could cause the screen to turn off immediately if the dream
            // cannot be started, we keep one eye open and gently poke user activity.
            long time = SystemClock.uptimeMillis();
            mPowerManager.userActivity(time, true /*noChangeLights*/);
            mPowerManager.nap(time);
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void testDream(ComponentName dream) {
        checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

        if (dream == null) {
            throw new IllegalArgumentException("dream must not be null");
        }

        final int callingUserId = UserHandle.getCallingUserId();
        final int currentUserId = ActivityManager.getCurrentUser();
        if (callingUserId != currentUserId) {
            // This check is inherently prone to races but at least it's something.
            Slog.w(TAG, "Aborted attempt to start a test dream while a different "
                    + " user is active: callingUserId=" + callingUserId
                    + ", currentUserId=" + currentUserId);
            return;
        }
        final long ident = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                startDreamLocked(dream, true /*isTest*/, callingUserId);
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void awaken() {
        checkPermission(android.Manifest.permission.WRITE_DREAM_STATE);

        final long ident = Binder.clearCallingIdentity();
        try {
            // Treat an explicit request to awaken as user activity so that the
            // device doesn't immediately go to sleep if the timeout expired,
            // for example when being undocked.
            long time = SystemClock.uptimeMillis();
            mPowerManager.userActivity(time, false /*noChangeLights*/);
            stopDream();
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    @Override // Binder call
    public void finishSelf(IBinder token) {
        // Requires no permission, called by Dream from an arbitrary process.
        if (token == null) {
            throw new IllegalArgumentException("token must not be null");
        }

        final long ident = Binder.clearCallingIdentity();
        try {
            if (DEBUG) {
                Slog.d(TAG, "Dream finished: " + token);
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
                    stopDreamLocked();
                }
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }

    /**
     * Called by the power manager to start a dream.
     */
    public void startDream() {
        int userId = ActivityManager.getCurrentUser();
        ComponentName dream = chooseDreamForUser(userId);
        if (dream != null) {
            synchronized (mLock) {
                startDreamLocked(dream, false /*isTest*/, userId);
            }
        }
    }

    /**
     * Called by the power manager to stop a dream.
     */
    public void stopDream() {
        synchronized (mLock) {
            stopDreamLocked();
        }
    }

    private ComponentName chooseDreamForUser(int userId) {
        ComponentName[] dreams = getDreamComponentsForUser(userId);
        return dreams != null && dreams.length != 0 ? dreams[0] : null;
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
                if (serviceExists(component)) {
                    validComponents.add(component);
                } else {
                    Slog.w(TAG, "Dream " + component + " does not exist");
                }
            }
        }

        // fallback to the default dream component if necessary
        if (validComponents.isEmpty()) {
            ComponentName defaultDream = getDefaultDreamComponent();
            if (defaultDream != null) {
                Slog.w(TAG, "Falling back to default dream " + defaultDream);
                validComponents.add(defaultDream);
            }
        }
        return validComponents.toArray(new ComponentName[validComponents.size()]);
    }

    private boolean serviceExists(ComponentName name) {
        try {
            return name != null && mContext.getPackageManager().getServiceInfo(name, 0) != null;
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    private void startDreamLocked(final ComponentName name,
            final boolean isTest, final int userId) {
        if (Objects.equal(mCurrentDreamName, name)
                && mCurrentDreamIsTest == isTest
                && mCurrentDreamUserId == userId) {
            return;
        }

        stopDreamLocked();

        if (DEBUG) Slog.i(TAG, "Entering dreamland.");

        final Binder newToken = new Binder();
        mCurrentDreamToken = newToken;
        mCurrentDreamName = name;
        mCurrentDreamIsTest = isTest;
        mCurrentDreamUserId = userId;

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mController.startDream(newToken, name, isTest, userId);
            }
        });
    }

    private void stopDreamLocked() {
        if (mCurrentDreamToken != null) {
            if (DEBUG) Slog.i(TAG, "Leaving dreamland.");

            cleanupDreamLocked();

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mController.stopDream();
                }
            });
        }
    }

    private void cleanupDreamLocked() {
        mCurrentDreamToken = null;
        mCurrentDreamName = null;
        mCurrentDreamIsTest = false;
        mCurrentDreamUserId = 0;
    }

    private void checkPermission(String permission) {
        if (mContext.checkCallingOrSelfPermission(permission)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + permission);
        }
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

    /**
     * Handler for asynchronous operations performed by the dream manager.
     * Ensures operations to {@link DreamController} are single-threaded.
     */
    private final class DreamHandler extends Handler {
        public DreamHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }
    }
}
