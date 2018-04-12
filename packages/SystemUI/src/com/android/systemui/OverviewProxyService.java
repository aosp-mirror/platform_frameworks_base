/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.SurfaceControl;

import com.android.systemui.OverviewProxyService.OverviewProxyListener;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.DockedFirstAnimationFrameEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.shared.recents.IOverviewProxy;
import com.android.systemui.shared.recents.ISystemUiProxy;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.GraphicBufferCompat;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.CallbackController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.DeviceProvisionedController.DeviceProvisionedListener;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import static android.content.pm.PackageManager.MATCH_DIRECT_BOOT_UNAWARE;
import static com.android.systemui.shared.system.NavigationBarCompat.FLAG_DISABLE_SWIPE_UP;
import static com.android.systemui.shared.system.NavigationBarCompat.InteractionType;

/**
 * Class to send information from overview to launcher with a binder.
 */
public class OverviewProxyService implements CallbackController<OverviewProxyListener>, Dumpable {

    private static final String ACTION_QUICKSTEP = "android.intent.action.QUICKSTEP_SERVICE";

    public static final String TAG_OPS = "OverviewProxyService";
    public static final boolean DEBUG_OVERVIEW_PROXY = false;
    private static final long BACKOFF_MILLIS = 5000;

    private final Context mContext;
    private final Handler mHandler;
    private final Runnable mConnectionRunnable = this::internalConnectToCurrentUser;
    private final ComponentName mRecentsComponentName;
    private final DeviceProvisionedController mDeviceProvisionedController
            = Dependency.get(DeviceProvisionedController.class);
    private final List<OverviewProxyListener> mConnectionCallbacks = new ArrayList<>();
    private final Intent mQuickStepIntent;

    private IOverviewProxy mOverviewProxy;
    private int mConnectionBackoffAttempts;
    private @InteractionType int mInteractionFlags;
    private boolean mIsEnabled;

    private ISystemUiProxy mSysUiProxy = new ISystemUiProxy.Stub() {

        public GraphicBufferCompat screenshot(Rect sourceCrop, int width, int height, int minLayer,
                int maxLayer, boolean useIdentityTransform, int rotation) {
            long token = Binder.clearCallingIdentity();
            try {
                return new GraphicBufferCompat(SurfaceControl.screenshotToBuffer(sourceCrop, width,
                        height, minLayer, maxLayer, useIdentityTransform, rotation));
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void startScreenPinning(int taskId) {
            long token = Binder.clearCallingIdentity();
            try {
                mHandler.post(() -> {
                    StatusBar statusBar = ((SystemUIApplication) mContext).getComponent(
                            StatusBar.class);
                    if (statusBar != null) {
                        statusBar.showScreenPinningRequest(taskId, false /* allowCancel */);
                    }
                });
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void onSplitScreenInvoked() {
            long token = Binder.clearCallingIdentity();
            try {
                EventBus.getDefault().post(new DockedFirstAnimationFrameEvent());
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        public void setInteractionState(@InteractionType int flags) {
            long token = Binder.clearCallingIdentity();
            try {
                if (mInteractionFlags != flags) {
                    mInteractionFlags = flags;
                    mHandler.post(() -> {
                        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
                            mConnectionCallbacks.get(i).onInteractionFlagsChanged(flags);
                        }
                    });
                }
            } finally {
                Prefs.putInt(mContext, Prefs.Key.QUICK_STEP_INTERACTION_FLAGS, mInteractionFlags);
                Binder.restoreCallingIdentity(token);
            }
        }
    };

    private final BroadcastReceiver mLauncherStateChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateEnabledState();

            // When launcher service is disabled, reset interaction flags because it is inactive
            if (!isEnabled()) {
                mInteractionFlags = 0;
                Prefs.remove(mContext, Prefs.Key.QUICK_STEP_INTERACTION_FLAGS);
            }

            // Reconnect immediately, instead of waiting for resume to arrive.
            startConnectionToCurrentUser();
        }
    };

    private final ServiceConnection mOverviewServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service != null) {
                mConnectionBackoffAttempts = 0;
                mOverviewProxy = IOverviewProxy.Stub.asInterface(service);
                // Listen for launcher's death
                try {
                    service.linkToDeath(mOverviewServiceDeathRcpt, 0);
                } catch (RemoteException e) {
                    Log.e(TAG_OPS, "Lost connection to launcher service", e);
                }
                try {
                    mOverviewProxy.onBind(mSysUiProxy);
                } catch (RemoteException e) {
                    Log.e(TAG_OPS, "Failed to call onBind()", e);
                }
                notifyConnectionChanged();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Do nothing
        }
    };

    private final DeviceProvisionedListener mDeviceProvisionedCallback =
                new DeviceProvisionedListener() {
            @Override
            public void onUserSetupChanged() {
                if (mDeviceProvisionedController.isCurrentUserSetup()) {
                    internalConnectToCurrentUser();
                }
            }

            @Override
            public void onUserSwitched() {
                mConnectionBackoffAttempts = 0;
                internalConnectToCurrentUser();
            }
        };

    // This is the death handler for the binder from the launcher service
    private final IBinder.DeathRecipient mOverviewServiceDeathRcpt
            = this::startConnectionToCurrentUser;

    public OverviewProxyService(Context context) {
        mContext = context;
        mHandler = new Handler();
        mConnectionBackoffAttempts = 0;
        mRecentsComponentName = ComponentName.unflattenFromString(context.getString(
                com.android.internal.R.string.config_recentsComponentName));
        mQuickStepIntent = new Intent(ACTION_QUICKSTEP)
                .setPackage(mRecentsComponentName.getPackageName());
        mInteractionFlags = Prefs.getInt(mContext, Prefs.Key.QUICK_STEP_INTERACTION_FLAGS, 0);

        // Listen for the package update changes.
        if (SystemServicesProxy.getInstance(context)
                .isSystemUser(mDeviceProvisionedController.getCurrentUser())) {
            updateEnabledState();
            mDeviceProvisionedController.addCallback(mDeviceProvisionedCallback);
            IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
            filter.addDataScheme("package");
            filter.addDataSchemeSpecificPart(mRecentsComponentName.getPackageName(),
                    PatternMatcher.PATTERN_LITERAL);
            filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
            mContext.registerReceiver(mLauncherStateChangedReceiver, filter);
        }
    }

    public void startConnectionToCurrentUser() {
        if (mHandler.getLooper() != Looper.myLooper()) {
            mHandler.post(mConnectionRunnable);
        } else {
            internalConnectToCurrentUser();
        }
    }

    private void internalConnectToCurrentUser() {
        disconnectFromLauncherService();

        // If user has not setup yet or already connected, do not try to connect
        if (!mDeviceProvisionedController.isCurrentUserSetup() || !isEnabled()) {
            return;
        }
        mHandler.removeCallbacks(mConnectionRunnable);
        Intent launcherServiceIntent = new Intent(ACTION_QUICKSTEP)
                .setPackage(mRecentsComponentName.getPackageName());
        boolean bound = false;
        try {
            bound = mContext.bindServiceAsUser(launcherServiceIntent,
                    mOverviewServiceConnection, Context.BIND_AUTO_CREATE,
                    UserHandle.of(mDeviceProvisionedController.getCurrentUser()));
        } catch (SecurityException e) {
            Log.e(TAG_OPS, "Unable to bind because of security error", e);
        }
        if (!bound) {
            // Retry after exponential backoff timeout
            final long timeoutMs = (long) Math.scalb(BACKOFF_MILLIS, mConnectionBackoffAttempts);
            mHandler.postDelayed(mConnectionRunnable, timeoutMs);
            mConnectionBackoffAttempts++;
        }
    }

    @Override
    public void addCallback(OverviewProxyListener listener) {
        mConnectionCallbacks.add(listener);
        listener.onConnectionChanged(mOverviewProxy != null);
        listener.onInteractionFlagsChanged(mInteractionFlags);
    }

    @Override
    public void removeCallback(OverviewProxyListener listener) {
        mConnectionCallbacks.remove(listener);
    }

    public boolean shouldShowSwipeUpUI() {
        return isEnabled() && ((mInteractionFlags & FLAG_DISABLE_SWIPE_UP) == 0);
    }

    public boolean isEnabled() {
        return mIsEnabled;
    }

    public IOverviewProxy getProxy() {
        return mOverviewProxy;
    }

    public int getInteractionFlags() {
        return mInteractionFlags;
    }

    private void disconnectFromLauncherService() {
        if (mOverviewProxy != null) {
            mOverviewProxy.asBinder().unlinkToDeath(mOverviewServiceDeathRcpt, 0);
            mContext.unbindService(mOverviewServiceConnection);
            mOverviewProxy = null;
            notifyConnectionChanged();
        }
    }

    private void notifyConnectionChanged() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onConnectionChanged(mOverviewProxy != null);
        }
    }

    public void notifyQuickStepStarted() {
        for (int i = mConnectionCallbacks.size() - 1; i >= 0; --i) {
            mConnectionCallbacks.get(i).onQuickStepStarted();
        }
    }

    private void updateEnabledState() {
        mIsEnabled = mContext.getPackageManager().resolveServiceAsUser(mQuickStepIntent,
                MATCH_DIRECT_BOOT_UNAWARE,
                ActivityManagerWrapper.getInstance().getCurrentUserId()) != null;
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(TAG_OPS + " state:");
        pw.print("  mConnectionBackoffAttempts="); pw.println(mConnectionBackoffAttempts);
        pw.print("  isCurrentUserSetup="); pw.println(mDeviceProvisionedController
                .isCurrentUserSetup());
        pw.print("  isConnected="); pw.println(mOverviewProxy != null);
    }

    public interface OverviewProxyListener {
        default void onConnectionChanged(boolean isConnected) {}
        default void onQuickStepStarted() {}
        default void onInteractionFlagsChanged(@InteractionType int flags) {}
    }
}
