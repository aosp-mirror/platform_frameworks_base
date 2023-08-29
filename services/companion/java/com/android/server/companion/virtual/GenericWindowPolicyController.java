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
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.WindowConfiguration;
import android.app.compat.CompatChanges;
import android.companion.virtual.VirtualDeviceManager.ActivityListener;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.VirtualDeviceParams.ActivityPolicy;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
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

import java.util.Set;


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
    private final ArraySet<UserHandle> mAllowedUsers;
    @Nullable
    private final ArraySet<ComponentName> mAllowedCrossTaskNavigations;
    @Nullable
    private final ArraySet<ComponentName> mBlockedCrossTaskNavigations;
    @Nullable
    private final ArraySet<ComponentName> mAllowedActivities;
    @Nullable
    private final ArraySet<ComponentName> mBlockedActivities;
    private final Object mGenericWindowPolicyControllerLock = new Object();
    @ActivityPolicy
    private final int mDefaultActivityPolicy;
    private final ActivityBlockedCallback mActivityBlockedCallback;
    private int mDisplayId = Display.INVALID_DISPLAY;

    @NonNull
    @GuardedBy("mGenericWindowPolicyControllerLock")
    final ArraySet<Integer> mRunningUids = new ArraySet<>();
    @Nullable private final ActivityListener mActivityListener;
    @Nullable private final PipBlockedCallback mPipBlockedCallback;
    @Nullable private final IntentListenerCallback mIntentListenerCallback;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    @NonNull
    @GuardedBy("mGenericWindowPolicyControllerLock")
    private final ArraySet<RunningAppsChangedListener> mRunningAppsChangedListeners =
            new ArraySet<>();
    @Nullable private final SecureWindowCallback mSecureWindowCallback;
    @Nullable private final Set<String> mDisplayCategories;

    private final boolean mShowTasksInHostDeviceRecents;

    /**
     * Creates a window policy controller that is generic to the different use cases of virtual
     * device.
     *
     * @param windowFlags The window flags that this controller is interested in.
     * @param systemWindowFlags The system window flags that this controller is interested in.
     * @param allowedUsers The set of users that are allowed to stream in this display.
     * @param allowedCrossTaskNavigations The set of components explicitly allowed to navigate
     *   across tasks on this device.
     * @param blockedCrossTaskNavigations The set of components explicitly blocked from
     *   navigating across tasks on this device.
     * @param allowedActivities The set of activities explicitly allowed to stream on this device.
     *   Used only if the {@code activityPolicy} is
     *   {@link VirtualDeviceParams#ACTIVITY_POLICY_DEFAULT_BLOCKED}.
     * @param blockedActivities The set of activities explicitly blocked from streaming on this
     *   device. Used only if the {@code activityPolicy} is
     *   {@link VirtualDeviceParams#ACTIVITY_POLICY_DEFAULT_ALLOWED}
     * @param defaultActivityPolicy Whether activities are default allowed to be displayed or
     *   blocked.
     * @param activityListener Activity listener to listen for activity changes.
     * @param activityBlockedCallback Callback that is called when an activity is blocked from
     *   launching.
     * @param secureWindowCallback Callback that is called when a secure window shows on the
     *   virtual display.
     * @param intentListenerCallback Callback that is called to intercept intents when matching
     *   passed in filters.
     * @param showTasksInHostDeviceRecents whether to show activities in recents on the host device.
     */
    public GenericWindowPolicyController(int windowFlags, int systemWindowFlags,
            @NonNull ArraySet<UserHandle> allowedUsers,
            @NonNull Set<ComponentName> allowedCrossTaskNavigations,
            @NonNull Set<ComponentName> blockedCrossTaskNavigations,
            @NonNull Set<ComponentName> allowedActivities,
            @NonNull Set<ComponentName> blockedActivities,
            @ActivityPolicy int defaultActivityPolicy,
            @NonNull ActivityListener activityListener,
            @NonNull PipBlockedCallback pipBlockedCallback,
            @NonNull ActivityBlockedCallback activityBlockedCallback,
            @NonNull SecureWindowCallback secureWindowCallback,
            @NonNull IntentListenerCallback intentListenerCallback,
            @NonNull Set<String> displayCategories,
            boolean showTasksInHostDeviceRecents) {
        super();
        mAllowedUsers = allowedUsers;
        mAllowedCrossTaskNavigations = new ArraySet<>(allowedCrossTaskNavigations);
        mBlockedCrossTaskNavigations = new ArraySet<>(blockedCrossTaskNavigations);
        mAllowedActivities = new ArraySet<>(allowedActivities);
        mBlockedActivities = new ArraySet<>(blockedActivities);
        mDefaultActivityPolicy = defaultActivityPolicy;
        mActivityBlockedCallback = activityBlockedCallback;
        setInterestedWindowFlags(windowFlags, systemWindowFlags);
        mActivityListener = activityListener;
        mPipBlockedCallback = pipBlockedCallback;
        mSecureWindowCallback = secureWindowCallback;
        mIntentListenerCallback = intentListenerCallback;
        mDisplayCategories = displayCategories;
        mShowTasksInHostDeviceRecents = showTasksInHostDeviceRecents;
    }

    /**
     * Expected to be called once this object is associated with a newly created display.
     */
    public void setDisplayId(int displayId) {
        mDisplayId = displayId;
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
        if (!canContainActivity(activityInfo, windowingMode, launchingFromDisplayId, isNewTask)) {
            mActivityBlockedCallback.onActivityBlocked(mDisplayId, activityInfo);
            return false;
        }
        if (mIntentListenerCallback != null && intent != null
                && mIntentListenerCallback.shouldInterceptIntent(intent)) {
            Slog.d(TAG, "Virtual device intercepting intent");
            return false;
        }
        return true;
    }

    @Override
    public boolean canContainActivity(@NonNull ActivityInfo activityInfo,
            @WindowConfiguration.WindowingMode int windowingMode, int launchingFromDisplayId,
            boolean isNewTask) {
        if (!isWindowingModeSupported(windowingMode)) {
            Slog.d(TAG, "Virtual device doesn't support windowing mode " + windowingMode);
            return false;
        }
        if ((activityInfo.flags & FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES) == 0) {
            Slog.d(TAG, "Virtual device requires android:canDisplayOnRemoteDevices=true");
            return false;
        }
        final UserHandle activityUser =
                UserHandle.getUserHandleForUid(activityInfo.applicationInfo.uid);
        if (!mAllowedUsers.contains(activityUser)) {
            Slog.d(TAG, "Virtual device launch disallowed from user " + activityUser);
            return false;
        }

        final ComponentName activityComponent = activityInfo.getComponentName();
        if (BLOCKED_APP_STREAMING_COMPONENT.equals(activityComponent)) {
            // The error dialog alerting users that streaming is blocked is always allowed.
            return true;
        }
        if (!activityMatchesDisplayCategory(activityInfo)) {
            Slog.d(TAG, "The activity's required display category '"
                    + activityInfo.requiredDisplayCategory
                    + "' not found on virtual display with the following categories: "
                    + mDisplayCategories);
            return false;
        }
        if ((mDefaultActivityPolicy == VirtualDeviceParams.ACTIVITY_POLICY_DEFAULT_ALLOWED
                && mBlockedActivities.contains(activityComponent))
                || (mDefaultActivityPolicy == VirtualDeviceParams.ACTIVITY_POLICY_DEFAULT_BLOCKED
                && !mAllowedActivities.contains(activityComponent))) {
            Slog.d(TAG, "Virtual device launch disallowed by policy: " + activityComponent);
            return false;
        }
        if (isNewTask && launchingFromDisplayId != DEFAULT_DISPLAY) {
            if ((!mBlockedCrossTaskNavigations.isEmpty()
                    && mBlockedCrossTaskNavigations.contains(activityComponent))
                    || ((!mAllowedCrossTaskNavigations.isEmpty()
                    && !mAllowedCrossTaskNavigations.contains(activityComponent)))) {
                Slog.d(TAG, "Virtual device cross task navigation disallowed by policy: "
                        + activityComponent);
                return false;
            }
        }

        return true;
    }

    @Override
    @SuppressWarnings("AndroidFrameworkRequiresPermission")
    public boolean keepActivityOnWindowFlagsChanged(ActivityInfo activityInfo, int windowFlags,
            int systemWindowFlags) {
        // The callback is fired only when windowFlags are changed. To let VirtualDevice owner
        // aware that the virtual display has a secure window on top.
        if ((windowFlags & FLAG_SECURE) != 0) {
            // Post callback on the main thread, so it doesn't block activity launching.
            mHandler.post(() -> mSecureWindowCallback.onSecureWindowShown(mDisplayId,
                    activityInfo.applicationInfo.uid));
        }

        if (!CompatChanges.isChangeEnabled(ALLOW_SECURE_ACTIVITY_DISPLAY_ON_REMOTE_DEVICE,
                activityInfo.packageName,
                UserHandle.getUserHandleForUid(activityInfo.applicationInfo.uid))) {
            // TODO(b/201712607): Add checks for the apps that use SurfaceView#setSecure.
            if ((windowFlags & FLAG_SECURE) != 0
                    || (systemWindowFlags & SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0) {
                mActivityBlockedCallback.onActivityBlocked(mDisplayId, activityInfo);
                return false;
            }
        }

        return true;
    }

    @Override
    public void onTopActivityChanged(ComponentName topActivity, int uid, @UserIdInt int userId) {
        // Don't send onTopActivityChanged() callback when topActivity is null because it's defined
        // as @NonNull in ActivityListener interface. Sends onDisplayEmpty() callback instead when
        // there is no activity running on virtual display.
        if (mActivityListener != null && topActivity != null) {
            // Post callback on the main thread so it doesn't block activity launching
            mHandler.post(() ->
                    mActivityListener.onTopActivityChanged(mDisplayId, topActivity, userId));
        }
    }

    @Override
    public void onRunningAppsChanged(ArraySet<Integer> runningUids) {
        synchronized (mGenericWindowPolicyControllerLock) {
            mRunningUids.clear();
            mRunningUids.addAll(runningUids);
            if (mActivityListener != null && mRunningUids.isEmpty()) {
                // Post callback on the main thread so it doesn't block activity launching
                mHandler.post(() -> mActivityListener.onDisplayEmpty(mDisplayId));
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
        return mShowTasksInHostDeviceRecents;
    }

    @Override
    public boolean isEnteringPipAllowed(int uid) {
        if (super.isEnteringPipAllowed(uid)) {
            return true;
        }
        mHandler.post(() -> {
            mPipBlockedCallback.onEnteringPipBlocked(uid);
        });
        return false;
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

    @VisibleForTesting
    int getRunningAppsChangedListenersSizeForTesting() {
        synchronized (mGenericWindowPolicyControllerLock) {
            return mRunningAppsChangedListeners.size();
        }
    }
}
