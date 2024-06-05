/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.companion.virtual;

import static android.content.pm.ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.WindowConfiguration;
import android.app.compat.CompatChanges;
import android.companion.virtual.VirtualDeviceManager.ActivityListener;
import android.companion.virtual.flags.Flags;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.AttributionSource;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Slog;
import android.view.Display;
import android.window.DisplayWindowPolicyController;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.BlockedAppStreamingActivity;
import com.android.modules.expresslog.Counter;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A controller to control the policies of the windows that can be displayed on the virtual display.
 */
public class GenericWindowPolicyController extends DisplayWindowPolicyController {

    private static final String TAG = "GenericWindowPolicyController";

    /** Interface to listen running applications change on virtual display. */
    public interface RunningAppsChangedListener {
        /**
         * Notifies the running applications change.
         */
        void onRunningAppsChanged(ArraySet<Integer> runningUids);
    }

    /**
     * For communicating when activities are blocked from running on the display by this policy
     * controller.
     */
    public interface ActivityBlockedCallback {
        /** Called when an activity is blocked.*/
        void onActivityBlocked(int displayId, ActivityInfo activityInfo);
    }
    private static final ComponentName BLOCKED_APP_STREAMING_COMPONENT =
            new ComponentName("android", BlockedAppStreamingActivity.class.getName());

    /**
     * For communicating when a secure window shows on the virtual display.
     */
    public interface SecureWindowCallback {
        /** Called when a secure window shows on the virtual display. */
        void onSecureWindowShown(int displayId, int uid);
    }

    /**
     * For communicating when activities are blocked from entering PIP on the display by this
     * policy controller.
     */
    public interface PipBlockedCallback {
        /** Called when an activity is blocked from entering PIP. */
        void onEnteringPipBlocked(int uid);
    }

    /** Interface to listen for interception of intents. */
    public interface IntentListenerCallback {
        /** Returns true when an intent should be intercepted */
        boolean shouldInterceptIntent(Intent intent);
    }

    /**
     * If required, allow the secure activity to display on remote device since
     * {@link android.os.Build.VERSION_CODES#TIRAMISU}.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public static final long ALLOW_SECURE_ACTIVITY_DISPLAY_ON_REMOTE_DEVICE = 201712607L;
    @NonNull
    private final AttributionSource mAttributionSource;
    @NonNull
    private final ArraySet<UserHandle> mAllowedUsers;
    @GuardedBy("mGenericWindowPolicyControllerLock")
    private boolean mActivityLaunchAllowedByDefault;
    @NonNull
    @GuardedBy("mGenericWindowPolicyControllerLock")
    private final Set<ComponentName> mActivityPolicyExemptions;
    private final boolean mCrossTaskNavigationAllowedByDefault;
    @NonNull
    private final ArraySet<ComponentName> mCrossTaskNavigationExemptions;
    @Nullable
    private final ComponentName mPermissionDialogComponent;
    private final Object mGenericWindowPolicyControllerLock = new Object();
    @Nullable private final ActivityBlockedCallback mActivityBlockedCallback;

    // Do not access mDisplayId and mIsMirrorDisplay directly, instead use waitAndGetDisplayId()
    // and waitAndGetIsMirrorDisplay()
    private int mDisplayId = Display.INVALID_DISPLAY;
    private boolean mIsMirrorDisplay = false;
    private final CountDownLatch mDisplayIdSetLatch = new CountDownLatch(1);

    @NonNull
    @GuardedBy("mGenericWindowPolicyControllerLock")
    private final ArraySet<Integer> mRunningUids = new ArraySet<>();
    @Nullable private final ActivityListener mActivityListener;
    @Nullable private final PipBlockedCallback mPipBlockedCallback;
    @Nullable private final IntentListenerCallback mIntentListenerCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    @NonNull
    @GuardedBy("mGenericWindowPolicyControllerLock")
    private final ArraySet<RunningAppsChangedListener> mRunningAppsChangedListeners =
            new ArraySet<>();
    @Nullable private final SecureWindowCallback mSecureWindowCallback;
    @NonNull private final Set<String> mDisplayCategories;

    @GuardedBy("mGenericWindowPolicyControllerLock")
    private boolean mShowTasksInHostDeviceRecents;
    @Nullable private final ComponentName mCustomHomeComponent;

    /**
     * Creates a window policy controller that is generic to the different use cases of virtual
     * device.
     *
     * @param windowFlags The window flags that this controller is interested in.
     * @param systemWindowFlags The system window flags that this controller is interested in.
     * @param attributionSource The AttributionSource of the VirtualDevice owner application.
     * @param allowedUsers The set of users that are allowed to stream in this display.
     * @param activityLaunchAllowedByDefault Whether activities are default allowed to be launched
     *   or blocked.
     * @param activityPolicyExemptions The set of activities explicitly exempt from the default
     *   activity policy.
     * @param crossTaskNavigationAllowedByDefault Whether cross task navigations are allowed by
     *   default or not.
     * @param crossTaskNavigationExemptions The set of components explicitly exempt from the default
     *   navigation policy.
     * @param activityListener Activity listener to listen for activity changes.
     * @param activityBlockedCallback Callback that is called when an activity is blocked from
     *   launching.
     * @param secureWindowCallback Callback that is called when a secure window shows on the
     *   virtual display.
     * @param intentListenerCallback Callback that is called to intercept intents when matching
     *   passed in filters.
     * @param showTasksInHostDeviceRecents whether to show activities in recents on the host device.
     * @param customHomeComponent The component acting as a home activity on the virtual display. If
     *   {@code null}, then the system-default secondary home activity will be used. This is only
     *   applicable to displays that support home activities, i.e. they're created with the relevant
     *   virtual display flag.
     */
    public GenericWindowPolicyController(
            int windowFlags,
            int systemWindowFlags,
            AttributionSource attributionSource,
            @NonNull ArraySet<UserHandle> allowedUsers,
            boolean activityLaunchAllowedByDefault,
            @NonNull Set<ComponentName> activityPolicyExemptions,
            boolean crossTaskNavigationAllowedByDefault,
            @NonNull Set<ComponentName> crossTaskNavigationExemptions,
            @Nullable ComponentName permissionDialogComponent,
            @Nullable ActivityListener activityListener,
            @Nullable PipBlockedCallback pipBlockedCallback,
            @Nullable ActivityBlockedCallback activityBlockedCallback,
            @Nullable SecureWindowCallback secureWindowCallback,
            @Nullable IntentListenerCallback intentListenerCallback,
            @NonNull Set<String> displayCategories,
            boolean showTasksInHostDeviceRecents,
            @Nullable ComponentName customHomeComponent) {
        super();
        mAttributionSource = attributionSource;
        mAllowedUsers = allowedUsers;
        mActivityLaunchAllowedByDefault = activityLaunchAllowedByDefault;
        mActivityPolicyExemptions = activityPolicyExemptions;
        mCrossTaskNavigationAllowedByDefault = crossTaskNavigationAllowedByDefault;
        mCrossTaskNavigationExemptions = new ArraySet<>(crossTaskNavigationExemptions);
        mPermissionDialogComponent = permissionDialogComponent;
        mActivityBlockedCallback = activityBlockedCallback;
        setInterestedWindowFlags(windowFlags, systemWindowFlags);
        mActivityListener = activityListener;
        mPipBlockedCallback = pipBlockedCallback;
        mSecureWindowCallback = secureWindowCallback;
        mIntentListenerCallback = intentListenerCallback;
        mDisplayCategories = displayCategories;
        mShowTasksInHostDeviceRecents = showTasksInHostDeviceRecents;
        mCustomHomeComponent = customHomeComponent;
    }

    /**
     * Expected to be called once this object is associated with a newly created display.
     */
    void setDisplayId(int displayId, boolean isMirrorDisplay) {
        mDisplayId = displayId;
        mIsMirrorDisplay = isMirrorDisplay;
        mDisplayIdSetLatch.countDown();
    }

    private int waitAndGetDisplayId() {
        try {
            if (!mDisplayIdSetLatch.await(10, TimeUnit.SECONDS)) {
                Slog.e(TAG, "Timed out while waiting for GWPC displayId to be set.");
                return INVALID_DISPLAY;
            }
        } catch (InterruptedException e) {
            Slog.e(TAG, "Interrupted while waiting for GWPC displayId to be set.");
            return INVALID_DISPLAY;
        }
        return mDisplayId;
    }

    private boolean waitAndGetIsMirrorDisplay() {
        try {
            if (!mDisplayIdSetLatch.await(10, TimeUnit.SECONDS)) {
                Slog.e(TAG, "Timed out while waiting for GWPC isMirrorDisplay to be set.");
                return false;
            }
        } catch (InterruptedException e) {
            Slog.e(TAG, "Interrupted while waiting for GWPC isMirrorDisplay to be set.");
            return false;
        }
        return mIsMirrorDisplay;
    }

    /**
     * Set whether to show activities in recents on the host device.
     */
    public void setShowInHostDeviceRecents(boolean showInHostDeviceRecents) {
        synchronized (mGenericWindowPolicyControllerLock) {
            mShowTasksInHostDeviceRecents = showInHostDeviceRecents;
        }
    }

    void setActivityLaunchDefaultAllowed(boolean activityLaunchDefaultAllowed) {
        synchronized (mGenericWindowPolicyControllerLock) {
            if (mActivityLaunchAllowedByDefault != activityLaunchDefaultAllowed) {
                mActivityPolicyExemptions.clear();
            }
            mActivityLaunchAllowedByDefault = activityLaunchDefaultAllowed;
        }
    }

    void addActivityPolicyExemption(@NonNull ComponentName componentName) {
        synchronized (mGenericWindowPolicyControllerLock) {
            mActivityPolicyExemptions.add(componentName);
        }
    }

    void removeActivityPolicyExemption(@NonNull ComponentName componentName) {
        synchronized (mGenericWindowPolicyControllerLock) {
            mActivityPolicyExemptions.remove(componentName);
        }
    }

    /** Register a listener for running applications changes. */
    public void registerRunningAppsChangedListener(@NonNull RunningAppsChangedListener listener) {
        synchronized (mGenericWindowPolicyControllerLock) {
            mRunningAppsChangedListeners.add(listener);
        }
    }

    /** Unregister a listener for running applications changes. */
    public void unregisterRunningAppsChangedListener(@NonNull RunningAppsChangedListener listener) {
        synchronized (mGenericWindowPolicyControllerLock) {
            mRunningAppsChangedListeners.remove(listener);
        }
    }

    @Override
    public boolean canActivityBeLaunched(@NonNull ActivityInfo activityInfo,
            @Nullable Intent intent, @WindowConfiguration.WindowingMode int windowingMode,
            int launchingFromDisplayId, boolean isNewTask) {
        if (Flags.interceptIntentsBeforeApplyingPolicy()) {
            if (mIntentListenerCallback != null && intent != null
                    && mIntentListenerCallback.shouldInterceptIntent(intent)) {
                logActivityLaunchBlocked("Virtual device intercepting intent");
                return false;
            }
            if (!canContainActivity(activityInfo, windowingMode, launchingFromDisplayId,
                    isNewTask)) {
                notifyActivityBlocked(activityInfo);
                return false;
            }
        } else {
            if (!canContainActivity(activityInfo, windowingMode, launchingFromDisplayId,
                    isNewTask)) {
                notifyActivityBlocked(activityInfo);
                return false;
            }
            if (mIntentListenerCallback != null && intent != null
                    && mIntentListenerCallback.shouldInterceptIntent(intent)) {
                logActivityLaunchBlocked("Virtual device intercepting intent");
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean canContainActivity(@NonNull ActivityInfo activityInfo,
            @WindowConfiguration.WindowingMode int windowingMode, int launchingFromDisplayId,
            boolean isNewTask) {
        // Mirror displays cannot contain activities.
        if (waitAndGetIsMirrorDisplay()) {
            logActivityLaunchBlocked("Mirror virtual displays cannot contain activities.");
            return false;
        }
        if (!isWindowingModeSupported(windowingMode)) {
            logActivityLaunchBlocked(
                    "Virtual device doesn't support windowing mode " + windowingMode);
            return false;
        }
        if ((activityInfo.flags & FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES) == 0) {
            logActivityLaunchBlocked(
                    "Activity requires android:canDisplayOnRemoteDevices=true");
            return false;
        }
        final UserHandle activityUser =
                UserHandle.getUserHandleForUid(activityInfo.applicationInfo.uid);
        final ComponentName activityComponent = activityInfo.getComponentName();
        if (BLOCKED_APP_STREAMING_COMPONENT.equals(activityComponent) && activityUser.isSystem()) {
            // The error dialog alerting users that streaming is blocked is always allowed.
            return true;
        }
        if (!activityUser.isSystem() && !mAllowedUsers.contains(activityUser)) {
            logActivityLaunchBlocked("Activity launch disallowed from user " + activityUser);
            return false;
        }
        if (!activityMatchesDisplayCategory(activityInfo)) {
            logActivityLaunchBlocked("The activity's required display category '"
                    + activityInfo.requiredDisplayCategory
                    + "' not found on virtual display with the following categories: "
                    + mDisplayCategories);
            return false;
        }
        synchronized (mGenericWindowPolicyControllerLock) {
            if (!isAllowedByPolicy(mActivityLaunchAllowedByDefault, mActivityPolicyExemptions,
                    activityComponent)) {
                logActivityLaunchBlocked("Activity launch disallowed by policy: "
                        + activityComponent);
                return false;
            }
        }
        if (isNewTask && launchingFromDisplayId != DEFAULT_DISPLAY
                && !isAllowedByPolicy(mCrossTaskNavigationAllowedByDefault,
                        mCrossTaskNavigationExemptions, activityComponent)) {
            logActivityLaunchBlocked("Cross task navigation disallowed by policy: "
                    + activityComponent);
            return false;
        }

        // mPermissionDialogComponent being null means we don't want to block permission Dialogs
        // based on FLAG_STREAM_PERMISSIONS
        if (mPermissionDialogComponent != null
                && mPermissionDialogComponent.equals(activityComponent)) {
            logActivityLaunchBlocked("Permission dialog not allowed on virtual device");
            return false;
        }

        return true;
    }

    private void logActivityLaunchBlocked(String reason) {
        Slog.d(TAG, "Virtual device activity launch disallowed on display "
                + waitAndGetDisplayId() + ", reason: " + reason);
    }

    @Override
    @SuppressWarnings("AndroidFrameworkRequiresPermission")
    public boolean keepActivityOnWindowFlagsChanged(ActivityInfo activityInfo, int windowFlags,
            int systemWindowFlags) {
        int displayId = waitAndGetDisplayId();
        // The callback is fired only when windowFlags are changed. To let VirtualDevice owner
        // aware that the virtual display has a secure window on top.
        if ((windowFlags & FLAG_SECURE) != 0 && mSecureWindowCallback != null
                && displayId != INVALID_DISPLAY) {
            // Post callback on the main thread, so it doesn't block activity launching.
            mHandler.post(() -> mSecureWindowCallback.onSecureWindowShown(displayId,
                    activityInfo.applicationInfo.uid));
        }

        if (!CompatChanges.isChangeEnabled(ALLOW_SECURE_ACTIVITY_DISPLAY_ON_REMOTE_DEVICE,
                activityInfo.packageName,
                UserHandle.getUserHandleForUid(activityInfo.applicationInfo.uid))) {
            // TODO(b/201712607): Add checks for the apps that use SurfaceView#setSecure.
            if ((windowFlags & FLAG_SECURE) != 0
                    || (systemWindowFlags & SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0) {
                notifyActivityBlocked(activityInfo);
                return false;
            }
        }

        return true;
    }

    @Override
    public void onTopActivityChanged(ComponentName topActivity, int uid, @UserIdInt int userId) {
        int displayId = waitAndGetDisplayId();
        // Don't send onTopActivityChanged() callback when topActivity is null because it's defined
        // as @NonNull in ActivityListener interface. Sends onDisplayEmpty() callback instead when
        // there is no activity running on virtual display.
        if (mActivityListener != null && topActivity != null && displayId != INVALID_DISPLAY) {
            // Post callback on the main thread so it doesn't block activity launching
            mHandler.post(() ->
                    mActivityListener.onTopActivityChanged(displayId, topActivity, userId));
        }
    }

    @Override
    public void onRunningAppsChanged(ArraySet<Integer> runningUids) {
        synchronized (mGenericWindowPolicyControllerLock) {
            mRunningUids.clear();
            mRunningUids.addAll(runningUids);
            int displayId = waitAndGetDisplayId();
            if (mActivityListener != null && mRunningUids.isEmpty()
                    && displayId != INVALID_DISPLAY) {
                // Post callback on the main thread so it doesn't block activity launching
                mHandler.post(() -> mActivityListener.onDisplayEmpty(displayId));
            }
            if (!mRunningAppsChangedListeners.isEmpty()) {
                final ArraySet<RunningAppsChangedListener> listeners =
                        new ArraySet<>(mRunningAppsChangedListeners);
                mHandler.post(() -> {
                    for (RunningAppsChangedListener listener : listeners) {
                        listener.onRunningAppsChanged(runningUids);
                    }
                });
            }
        }
    }

    @Override
    public boolean canShowTasksInHostDeviceRecents() {
        synchronized (mGenericWindowPolicyControllerLock) {
            return mShowTasksInHostDeviceRecents;
        }
    }

    @Override
    public boolean isEnteringPipAllowed(int uid) {
        if (super.isEnteringPipAllowed(uid)) {
            return true;
        }
        if (mPipBlockedCallback != null) {
            mHandler.post(() -> mPipBlockedCallback.onEnteringPipBlocked(uid));
        }
        return false;
    }

    @Override
    public @Nullable ComponentName getCustomHomeComponent() {
        return mCustomHomeComponent;
    }

    /**
     * Returns true if an app with the given UID has an activity running on the virtual display for
     * this controller.
     */
    boolean containsUid(int uid) {
        synchronized (mGenericWindowPolicyControllerLock) {
            return mRunningUids.contains(uid);
        }
    }

    private boolean activityMatchesDisplayCategory(ActivityInfo activityInfo) {
        if (mDisplayCategories.isEmpty()) {
            return activityInfo.requiredDisplayCategory == null;
        }
        return activityInfo.requiredDisplayCategory != null
                    && mDisplayCategories.contains(activityInfo.requiredDisplayCategory);
    }

    private void notifyActivityBlocked(ActivityInfo activityInfo) {
        int displayId = waitAndGetDisplayId();
        // Don't trigger activity blocked callback for mirror displays, because we can't show
        // any activity or presentation on it anyway.
        if (!waitAndGetIsMirrorDisplay() && mActivityBlockedCallback != null
                && displayId != INVALID_DISPLAY) {
            mActivityBlockedCallback.onActivityBlocked(displayId, activityInfo);
        }
        if (android.companion.virtualdevice.flags.Flags.metricsCollection()) {
            Counter.logIncrementWithUid(
                    "virtual_devices.value_activity_blocked_count",
                    mAttributionSource.getUid());
        }

    }

    private static boolean isAllowedByPolicy(boolean allowedByDefault,
            Set<ComponentName> exemptions, ComponentName component) {
        // Either allowed and the exemptions do not contain the component,
        // or disallowed and the exemptions contain the component.
        return allowedByDefault != exemptions.contains(component);
    }

    @VisibleForTesting
    int getRunningAppsChangedListenersSizeForTesting() {
        synchronized (mGenericWindowPolicyControllerLock) {
            return mRunningAppsChangedListeners.size();
        }
    }
}
