/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.ActivityManager;
import android.app.UiModeManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.EventLog;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.EventLogConstants;
import com.android.systemui.EventLogTags;
import com.android.systemui.R;
import com.android.systemui.RecentsComponent;
import com.android.systemui.SystemUI;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.activity.ConfigurationChangedEvent;
import com.android.systemui.recents.events.activity.DockedTopTaskEvent;
import com.android.systemui.recents.events.activity.RecentsActivityStartingEvent;
import com.android.systemui.recents.events.component.RecentsVisibilityChangedEvent;
import com.android.systemui.recents.events.component.ScreenPinningRequestEvent;
import com.android.systemui.recents.events.component.ShowUserToastEvent;
import com.android.systemui.recents.events.ui.RecentsDrawnEvent;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.tv.RecentsTvImpl;
import com.android.systemui.stackdivider.Divider;

import java.util.ArrayList;


/**
 * An implementation of the SystemUI recents component, which supports both system and secondary
 * users.
 */
public class Recents extends SystemUI
        implements RecentsComponent {

    private final static String TAG = "Recents";
    private final static boolean DEBUG = false;

    public final static int EVENT_BUS_PRIORITY = 1;
    public final static int BIND_TO_SYSTEM_USER_RETRY_DELAY = 5000;
    public final static int RECENTS_GROW_TARGET_INVALID = -1;

    // Purely for experimentation
    private final static String RECENTS_OVERRIDE_SYSPROP_KEY = "persist.recents_override_pkg";
    private final static String ACTION_SHOW_RECENTS = "com.android.systemui.recents.ACTION_SHOW";
    private final static String ACTION_HIDE_RECENTS = "com.android.systemui.recents.ACTION_HIDE";
    private final static String ACTION_TOGGLE_RECENTS = "com.android.systemui.recents.ACTION_TOGGLE";

    private static final String COUNTER_WINDOW_SUPPORTED = "window_enter_supported";
    private static final String COUNTER_WINDOW_UNSUPPORTED = "window_enter_unsupported";
    private static final String COUNTER_WINDOW_INCOMPATIBLE = "window_enter_incompatible";

    private static SystemServicesProxy sSystemServicesProxy;
    private static RecentsDebugFlags sDebugFlags;
    private static RecentsTaskLoader sTaskLoader;
    private static RecentsConfiguration sConfiguration;

    // For experiments only, allows another package to handle recents if it is defined in the system
    // properties.  This is limited to show/toggle/hide, and does not tie into the ActivityManager,
    // and does not reside in the home stack.
    private String mOverrideRecentsPackageName;

    private Handler mHandler;
    private RecentsImpl mImpl;
    private int mDraggingInRecentsCurrentUser;

    // Only For system user, this is the callbacks instance we return to each secondary user
    private RecentsSystemUser mSystemToUserCallbacks;

    // Only for secondary users, this is the callbacks instance provided by the system user to make
    // calls back
    private IRecentsSystemUserCallbacks mUserToSystemCallbacks;

    // The set of runnables to run after binding to the system user's service.
    private final ArrayList<Runnable> mOnConnectRunnables = new ArrayList<>();

    // Only for secondary users, this is the death handler for the binder from the system user
    private final IBinder.DeathRecipient mUserToSystemCallbacksDeathRcpt = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            mUserToSystemCallbacks = null;
            EventLog.writeEvent(EventLogTags.SYSUI_RECENTS_CONNECTION,
                    EventLogConstants.SYSUI_RECENTS_CONNECTION_USER_SYSTEM_UNBOUND,
                    sSystemServicesProxy.getProcessUser());

            // Retry after a fixed duration
            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    registerWithSystemUser();
                }
            }, BIND_TO_SYSTEM_USER_RETRY_DELAY);
        }
    };

    // Only for secondary users, this is the service connection we use to connect to the system user
    private final ServiceConnection mUserToSystemServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (service != null) {
                mUserToSystemCallbacks = IRecentsSystemUserCallbacks.Stub.asInterface(
                        service);
                EventLog.writeEvent(EventLogTags.SYSUI_RECENTS_CONNECTION,
                        EventLogConstants.SYSUI_RECENTS_CONNECTION_USER_SYSTEM_BOUND,
                        sSystemServicesProxy.getProcessUser());

                // Listen for system user's death, so that we can reconnect later
                try {
                    service.linkToDeath(mUserToSystemCallbacksDeathRcpt, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "Lost connection to (System) SystemUI", e);
                }

                // Run each of the queued runnables
                runAndFlushOnConnectRunnables();
            }

            // Unbind ourselves now that we've registered our callbacks.  The
            // binder to the system user are still valid at this point.
            mContext.unbindService(this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Do nothing
        }
    };

    /**
     * Returns the callbacks interface that non-system users can call.
     */
    public IBinder getSystemUserCallbacks() {
        return mSystemToUserCallbacks;
    }

    public static RecentsTaskLoader getTaskLoader() {
        return sTaskLoader;
    }

    public static SystemServicesProxy getSystemServices() {
        return sSystemServicesProxy;
    }

    public static RecentsConfiguration getConfiguration() {
        return sConfiguration;
    }

    public static RecentsDebugFlags getDebugFlags() {
        return sDebugFlags;
    }

    @Override
    public void start() {
        sDebugFlags = new RecentsDebugFlags(mContext);
        sSystemServicesProxy = SystemServicesProxy.getInstance(mContext);
        sTaskLoader = new RecentsTaskLoader(mContext);
        sConfiguration = new RecentsConfiguration(mContext);
        mHandler = new Handler();
        UiModeManager uiModeManager = (UiModeManager) mContext.
                getSystemService(Context.UI_MODE_SERVICE);
        if (uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            mImpl = new RecentsTvImpl(mContext);
        } else {
            mImpl = new RecentsImpl(mContext);
        }

        // Check if there is a recents override package
        if ("userdebug".equals(Build.TYPE) || "eng".equals(Build.TYPE)) {
            String cnStr = SystemProperties.get(RECENTS_OVERRIDE_SYSPROP_KEY);
            if (!cnStr.isEmpty()) {
                mOverrideRecentsPackageName = cnStr;
            }
        }

        // Register with the event bus
        EventBus.getDefault().register(this, EVENT_BUS_PRIORITY);
        EventBus.getDefault().register(sSystemServicesProxy, EVENT_BUS_PRIORITY);
        EventBus.getDefault().register(sTaskLoader, EVENT_BUS_PRIORITY);

        // Due to the fact that RecentsActivity is per-user, we need to establish and interface for
        // the system user's Recents component to pass events (like show/hide/toggleRecents) to the
        // secondary user, and vice versa (like visibility change, screen pinning).
        final int processUser = sSystemServicesProxy.getProcessUser();
        if (sSystemServicesProxy.isSystemUser(processUser)) {
            // For the system user, initialize an instance of the interface that we can pass to the
            // secondary user
            mSystemToUserCallbacks = new RecentsSystemUser(mContext, mImpl);
        } else {
            // For the secondary user, bind to the primary user's service to get a persistent
            // interface to register its implementation and to later update its state
            registerWithSystemUser();
        }
        putComponent(Recents.class, this);
    }

    @Override
    public void onBootCompleted() {
        mImpl.onBootCompleted();
    }

    /**
     * Shows the Recents.
     */
    @Override
    public void showRecents(boolean triggeredFromAltTab, boolean fromHome) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        if (proxyToOverridePackage(ACTION_SHOW_RECENTS)) {
            return;
        }

        int recentsGrowTarget = getComponent(Divider.class).getView().growsRecents();

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.showRecents(triggeredFromAltTab, false /* draggingInRecents */,
                    true /* animate */, false /* reloadTasks */, fromHome, recentsGrowTarget);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.showRecents(triggeredFromAltTab, false /* draggingInRecents */,
                                true /* animate */, false /* reloadTasks */, fromHome,
                                recentsGrowTarget);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Hides the Recents.
     */
    @Override
    public void hideRecents(boolean triggeredFromAltTab, boolean triggeredFromHomeKey) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        if (proxyToOverridePackage(ACTION_HIDE_RECENTS)) {
            return;
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.hideRecents(triggeredFromAltTab, triggeredFromHomeKey);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.hideRecents(triggeredFromAltTab, triggeredFromHomeKey);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Toggles the Recents activity.
     */
    @Override
    public void toggleRecents(Display display) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        if (proxyToOverridePackage(ACTION_TOGGLE_RECENTS)) {
            return;
        }

        int growTarget = getComponent(Divider.class).getView().growsRecents();

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.toggleRecents(growTarget);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.toggleRecents(growTarget);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Preloads info for the Recents activity.
     */
    @Override
    public void preloadRecents() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.preloadRecents();
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.preloadRecents();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    @Override
    public void cancelPreloadingRecents() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.cancelPreloadingRecents();
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.cancelPreloadingRecents();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    @Override
    public boolean dockTopTask(int dragMode, int stackCreateMode, Rect initialBounds,
            int metricsDockAction) {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return false;
        }

        Point realSize = new Point();
        if (initialBounds == null) {
            mContext.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY)
                    .getRealSize(realSize);
            initialBounds = new Rect(0, 0, realSize.x, realSize.y);
        }

        int currentUser = sSystemServicesProxy.getCurrentUser();
        SystemServicesProxy ssp = Recents.getSystemServices();
        ActivityManager.RunningTaskInfo runningTask = ssp.getRunningTask();
        boolean screenPinningActive = ssp.isScreenPinningActive();
        boolean isRunningTaskInHomeStack = runningTask != null &&
                SystemServicesProxy.isHomeStack(runningTask.stackId);
        if (runningTask != null && !isRunningTaskInHomeStack && !screenPinningActive) {
            logDockAttempt(mContext, runningTask.topActivity, runningTask.resizeMode);
            if (runningTask.isDockable) {
                if (metricsDockAction != -1) {
                    MetricsLogger.action(mContext, metricsDockAction,
                            runningTask.topActivity.flattenToShortString());
                }
                if (sSystemServicesProxy.isSystemUser(currentUser)) {
                    mImpl.dockTopTask(runningTask.id, dragMode, stackCreateMode, initialBounds);
                } else {
                    if (mSystemToUserCallbacks != null) {
                        IRecentsNonSystemUserCallbacks callbacks =
                                mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                        if (callbacks != null) {
                            try {
                                callbacks.dockTopTask(runningTask.id, dragMode, stackCreateMode,
                                        initialBounds);
                            } catch (RemoteException e) {
                                Log.e(TAG, "Callback failed", e);
                            }
                        } else {
                            Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                        }
                    }
                }
                mDraggingInRecentsCurrentUser = currentUser;
                return true;
            } else {
                EventBus.getDefault().send(new ShowUserToastEvent(
                        R.string.recents_incompatible_app_message, Toast.LENGTH_SHORT));
                return false;
            }
        } else {
            return false;
        }
    }

    public static void logDockAttempt(Context ctx, ComponentName activity, int resizeMode) {
        if (resizeMode == ActivityInfo.RESIZE_MODE_UNRESIZEABLE) {
            MetricsLogger.action(ctx, MetricsEvent.ACTION_WINDOW_DOCK_UNRESIZABLE,
                    activity.flattenToShortString());
        }
        MetricsLogger.count(ctx, getMetricsCounterForResizeMode(resizeMode), 1);
    }

    private static String getMetricsCounterForResizeMode(int resizeMode) {
        switch (resizeMode) {
            case ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE:
                return COUNTER_WINDOW_UNSUPPORTED;
            case ActivityInfo.RESIZE_MODE_RESIZEABLE:
            case ActivityInfo.RESIZE_MODE_RESIZEABLE_AND_PIPABLE:
                return COUNTER_WINDOW_SUPPORTED;
            default:
                return COUNTER_WINDOW_INCOMPATIBLE;
        }
    }

    @Override
    public void onDraggingInRecents(float distanceFromTop) {
        if (sSystemServicesProxy.isSystemUser(mDraggingInRecentsCurrentUser)) {
            mImpl.onDraggingInRecents(distanceFromTop);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(
                                mDraggingInRecentsCurrentUser);
                if (callbacks != null) {
                    try {
                        callbacks.onDraggingInRecents(distanceFromTop);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: "
                            + mDraggingInRecentsCurrentUser);
                }
            }
        }
    }

    @Override
    public void onDraggingInRecentsEnded(float velocity) {
        if (sSystemServicesProxy.isSystemUser(mDraggingInRecentsCurrentUser)) {
            mImpl.onDraggingInRecentsEnded(velocity);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(
                                mDraggingInRecentsCurrentUser);
                if (callbacks != null) {
                    try {
                        callbacks.onDraggingInRecentsEnded(velocity);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: "
                            + mDraggingInRecentsCurrentUser);
                }
            }
        }
    }

    @Override
    public void showNextAffiliatedTask() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        mImpl.showNextAffiliatedTask();
    }

    @Override
    public void showPrevAffiliatedTask() {
        // Ensure the device has been provisioned before allowing the user to interact with
        // recents
        if (!isUserSetup()) {
            return;
        }

        mImpl.showPrevAffiliatedTask();
    }

    /**
     * Updates on configuration change.
     */
    public void onConfigurationChanged(Configuration newConfig) {
        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.onConfigurationChanged();
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.onConfigurationChanged();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Handle Recents activity visibility changed.
     */
    public final void onBusEvent(final RecentsVisibilityChangedEvent event) {
        SystemServicesProxy ssp = Recents.getSystemServices();
        int processUser = ssp.getProcessUser();
        if (ssp.isSystemUser(processUser)) {
            mImpl.onVisibilityChanged(event.applicationContext, event.visible);
        } else {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.updateRecentsVisibility(event.visible);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    /**
     * Handle screen pinning request.
     */
    public final void onBusEvent(final ScreenPinningRequestEvent event) {
        int processUser = sSystemServicesProxy.getProcessUser();
        if (sSystemServicesProxy.isSystemUser(processUser)) {
            mImpl.onStartScreenPinning(event.applicationContext, event.taskId);
        } else {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.startScreenPinning(event.taskId);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    public final void onBusEvent(final RecentsDrawnEvent event) {
        int processUser = sSystemServicesProxy.getProcessUser();
        if (!sSystemServicesProxy.isSystemUser(processUser)) {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.sendRecentsDrawnEvent();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    public final void onBusEvent(final DockedTopTaskEvent event) {
        int processUser = sSystemServicesProxy.getProcessUser();
        if (!sSystemServicesProxy.isSystemUser(processUser)) {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.sendDockingTopTaskEvent(event.dragMode,
                                event.initialRect);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    public final void onBusEvent(final RecentsActivityStartingEvent event) {
        int processUser = sSystemServicesProxy.getProcessUser();
        if (!sSystemServicesProxy.isSystemUser(processUser)) {
            postToSystemUser(new Runnable() {
                @Override
                public void run() {
                    try {
                        mUserToSystemCallbacks.sendLaunchRecentsEvent();
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                }
            });
        }
    }

    public final void onBusEvent(ConfigurationChangedEvent event) {
        // Update the configuration for the Recents component when the activity configuration
        // changes as well
        mImpl.onConfigurationChanged();
    }

    public final void onBusEvent(ShowUserToastEvent event) {
        int currentUser = sSystemServicesProxy.getCurrentUser();
        if (sSystemServicesProxy.isSystemUser(currentUser)) {
            mImpl.onShowCurrentUserToast(event.msgResId, event.msgLength);
        } else {
            if (mSystemToUserCallbacks != null) {
                IRecentsNonSystemUserCallbacks callbacks =
                        mSystemToUserCallbacks.getNonSystemUserRecentsForUser(currentUser);
                if (callbacks != null) {
                    try {
                        callbacks.showCurrentUserToast(event.msgResId, event.msgLength);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Callback failed", e);
                    }
                } else {
                    Log.e(TAG, "No SystemUI callbacks found for user: " + currentUser);
                }
            }
        }
    }

    /**
     * Attempts to register with the system user.
     */
    private void registerWithSystemUser() {
        final int processUser = sSystemServicesProxy.getProcessUser();
        postToSystemUser(new Runnable() {
            @Override
            public void run() {
                try {
                    mUserToSystemCallbacks.registerNonSystemUserCallbacks(
                            new RecentsImplProxy(mImpl), processUser);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to register", e);
                }
            }
        });
    }

    /**
     * Runs the runnable in the system user's Recents context, connecting to the service if
     * necessary.
     */
    private void postToSystemUser(final Runnable onConnectRunnable) {
        mOnConnectRunnables.add(onConnectRunnable);
        if (mUserToSystemCallbacks == null) {
            Intent systemUserServiceIntent = new Intent();
            systemUserServiceIntent.setClass(mContext, RecentsSystemUserService.class);
            boolean bound = mContext.bindServiceAsUser(systemUserServiceIntent,
                    mUserToSystemServiceConnection, Context.BIND_AUTO_CREATE, UserHandle.SYSTEM);
            EventLog.writeEvent(EventLogTags.SYSUI_RECENTS_CONNECTION,
                    EventLogConstants.SYSUI_RECENTS_CONNECTION_USER_BIND_SERVICE,
                    sSystemServicesProxy.getProcessUser());
            if (!bound) {
                // Retry after a fixed duration
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        registerWithSystemUser();
                    }
                }, BIND_TO_SYSTEM_USER_RETRY_DELAY);
            }
        } else {
            runAndFlushOnConnectRunnables();
        }
    }

    /**
     * Runs all the queued runnables after a service connection is made.
     */
    private void runAndFlushOnConnectRunnables() {
        for (Runnable r : mOnConnectRunnables) {
            r.run();
        }
        mOnConnectRunnables.clear();
    }

    /**
     * @return whether this device is provisioned and the current user is set up.
     */
    private boolean isUserSetup() {
        ContentResolver cr = mContext.getContentResolver();
        return (Settings.Global.getInt(cr, Settings.Global.DEVICE_PROVISIONED, 0) != 0) &&
                (Settings.Secure.getInt(cr, Settings.Secure.USER_SETUP_COMPLETE, 0) != 0);
    }

    /**
     * Attempts to proxy the following action to the override recents package.
     * @return whether the proxying was successful
     */
    private boolean proxyToOverridePackage(String action) {
        if (mOverrideRecentsPackageName != null) {
            Intent intent = new Intent(action);
            intent.setPackage(mOverrideRecentsPackageName);
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            mContext.sendBroadcast(intent);
            return true;
        }
        return false;
    }
}
