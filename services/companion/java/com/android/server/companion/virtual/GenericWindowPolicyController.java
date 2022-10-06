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

import static android.companion.AssociationRequest.DEVICE_PROFILE_APP_STREAMING;
import static android.companion.AssociationRequest.DEVICE_PROFILE_AUTOMOTIVE_PROJECTION;
import static android.content.pm.ActivityInfo.FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.WindowConfiguration;
import android.app.compat.CompatChanges;
import android.companion.AssociationRequest;
import android.companion.virtual.VirtualDeviceManager.ActivityListener;
import android.companion.virtual.VirtualDeviceParams;
import android.companion.virtual.VirtualDeviceParams.ActivityPolicy;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
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
import com.android.internal.app.BlockedAppStreamingActivity;

import java.util.List;
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
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ArraySet<RunningAppsChangedListener> mRunningAppsChangedListener =
            new ArraySet<>();
    @Nullable
    private final @AssociationRequest.DeviceProfile String mDeviceProfile;
    @Nullable private final SecureWindowCallback mSecureWindowCallback;

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
     * @param deviceProfile The {@link AssociationRequest.DeviceProfile} of this virtual device.
     */
    public GenericWindowPolicyController(int windowFlags, int systemWindowFlags,
            @NonNull ArraySet<UserHandle> allowedUsers,
            @NonNull Set<ComponentName> allowedCrossTaskNavigations,
            @NonNull Set<ComponentName> blockedCrossTaskNavigations,
            @NonNull Set<ComponentName> allowedActivities,
            @NonNull Set<ComponentName> blockedActivities,
            @ActivityPolicy int defaultActivityPolicy,
            @NonNull ActivityListener activityListener,
            @NonNull ActivityBlockedCallback activityBlockedCallback,
            @NonNull SecureWindowCallback secureWindowCallback,
            @AssociationRequest.DeviceProfile String deviceProfile) {
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
        mDeviceProfile = deviceProfile;
        mSecureWindowCallback = secureWindowCallback;
    }

    /**
     * Expected to be called once this object is associated with a newly created display.
     */
    public void setDisplayId(int displayId) {
        mDisplayId = displayId;
    }

    /** Register a listener for running applications changes. */
    public void registerRunningAppsChangedListener(@NonNull RunningAppsChangedListener listener) {
        mRunningAppsChangedListener.add(listener);
    }

    /** Unregister a listener for running applications changes. */
    public void unregisterRunningAppsChangedListener(@NonNull RunningAppsChangedListener listener) {
        mRunningAppsChangedListener.remove(listener);
    }

    @Override
    public boolean canContainActivities(@NonNull List<ActivityInfo> activities,
            @WindowConfiguration.WindowingMode int windowingMode) {
        if (!isWindowingModeSupported(windowingMode)) {
            return false;
        }
        // Can't display all the activities if any of them don't want to be displayed.
        final int activityCount = activities.size();
        for (int i = 0; i < activityCount; i++) {
            final ActivityInfo aInfo = activities.get(i);
            if (!canContainActivity(aInfo, /* windowFlags= */ 0, /* systemWindowFlags= */ 0)) {
                mActivityBlockedCallback.onActivityBlocked(mDisplayId, aInfo);
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean canActivityBeLaunched(ActivityInfo activityInfo,
            @WindowConfiguration.WindowingMode int windowingMode, int launchingFromDisplayId,
            boolean isNewTask) {
        if (!isWindowingModeSupported(windowingMode)) {
            return false;
        }

        final ComponentName activityComponent = activityInfo.getComponentName();
        if (BLOCKED_APP_STREAMING_COMPONENT.equals(activityComponent)) {
            // The error dialog alerting users that streaming is blocked is always allowed.
            return true;
        }

        if (!canContainActivity(activityInfo, /* windowFlags= */  0, /* systemWindowFlags= */ 0)) {
            mActivityBlockedCallback.onActivityBlocked(mDisplayId, activityInfo);
            return false;
        }

        if (launchingFromDisplayId == Display.DEFAULT_DISPLAY) {
            return true;
        }
        if (isNewTask && !mBlockedCrossTaskNavigations.isEmpty()
                && mBlockedCrossTaskNavigations.contains(activityComponent)) {
            Slog.d(TAG, "Virtual device blocking cross task navigation of " + activityComponent);
            mActivityBlockedCallback.onActivityBlocked(mDisplayId, activityInfo);
            return false;
        }
        if (isNewTask && !mAllowedCrossTaskNavigations.isEmpty()
                && !mAllowedCrossTaskNavigations.contains(activityComponent)) {
            Slog.d(TAG, "Virtual device not allowing cross task navigation of "
                    + activityComponent);
            mActivityBlockedCallback.onActivityBlocked(mDisplayId, activityInfo);
            return false;
        }

        return true;
    }


    @Override
    public boolean keepActivityOnWindowFlagsChanged(ActivityInfo activityInfo, int windowFlags,
            int systemWindowFlags) {
        // The callback is fired only when windowFlags are changed. To let VirtualDevice owner
        // aware that the virtual display has a secure window on top.
        if ((windowFlags & FLAG_SECURE) != 0) {
            // Post callback on the main thread, so it doesn't block activity launching.
            mHandler.post(() -> mSecureWindowCallback.onSecureWindowShown(mDisplayId,
                    activityInfo.applicationInfo.uid));
        }

        if (!canContainActivity(activityInfo, windowFlags, systemWindowFlags)) {
            mActivityBlockedCallback.onActivityBlocked(mDisplayId, activityInfo);
            return false;
        }
        return true;
    }

    @Override
    public void onTopActivityChanged(ComponentName topActivity, int uid) {
        // Don't send onTopActivityChanged() callback when topActivity is null because it's defined
        // as @NonNull in ActivityListener interface. Sends onDisplayEmpty() callback instead when
        // there is no activity running on virtual display.
        if (mActivityListener != null && topActivity != null) {
            // Post callback on the main thread so it doesn't block activity launching
            mHandler.post(() ->
                    mActivityListener.onTopActivityChanged(mDisplayId, topActivity));
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
        }
        mHandler.post(() -> {
            for (RunningAppsChangedListener listener : mRunningAppsChangedListener) {
                listener.onRunningAppsChanged(runningUids);
            }
        });
    }

    @Override
    public boolean canShowTasksInRecents() {
        if (mDeviceProfile == null) {
            return true;
        }
       // TODO(b/234075973) : Remove this once proper API is ready.
        switch (mDeviceProfile) {
            case DEVICE_PROFILE_AUTOMOTIVE_PROJECTION:
                return false;
            case DEVICE_PROFILE_APP_STREAMING:
            default:
                return true;
        }
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

    private boolean canContainActivity(ActivityInfo activityInfo, int windowFlags,
            int systemWindowFlags) {
        if ((activityInfo.flags & FLAG_CAN_DISPLAY_ON_REMOTE_DEVICES) == 0) {
            return false;
        }
        ComponentName activityComponent = activityInfo.getComponentName();
        if (BLOCKED_APP_STREAMING_COMPONENT.equals(activityComponent)) {
            // The error dialog alerting users that streaming is blocked is always allowed.
            return true;
        }
        final UserHandle activityUser =
                UserHandle.getUserHandleForUid(activityInfo.applicationInfo.uid);
        if (!mAllowedUsers.contains(activityUser)) {
            Slog.d(TAG, "Virtual device activity not allowed from user " + activityUser);
            return false;
        }
        if (mDefaultActivityPolicy == VirtualDeviceParams.ACTIVITY_POLICY_DEFAULT_ALLOWED
                && mBlockedActivities.contains(activityComponent)) {
            Slog.d(TAG, "Virtual device blocking launch of " + activityComponent);
            return false;
        }
        if (mDefaultActivityPolicy == VirtualDeviceParams.ACTIVITY_POLICY_DEFAULT_BLOCKED
                && !mAllowedActivities.contains(activityComponent)) {
            Slog.d(TAG, activityComponent + " is not in the allowed list.");
            return false;
        }
        if (!CompatChanges.isChangeEnabled(ALLOW_SECURE_ACTIVITY_DISPLAY_ON_REMOTE_DEVICE,
                activityInfo.packageName, activityUser)) {
            // TODO(b/201712607): Add checks for the apps that use SurfaceView#setSecure.
            if ((windowFlags & FLAG_SECURE) != 0) {
                return false;
            }
            if ((systemWindowFlags & SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS) != 0) {
                return false;
            }
        }
        return true;
    }
}
