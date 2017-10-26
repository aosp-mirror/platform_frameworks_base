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

package android.app;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.voice.IVoiceInteractionSession;
import android.util.SparseIntArray;

import com.android.internal.app.IVoiceInteractor;

import java.util.List;

/**
 * Activity manager local system service interface.
 *
 * @hide Only for use within the system server.
 */
public abstract class ActivityManagerInternal {

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because we had
     * the surface saved.
     */
    public static final int APP_TRANSITION_SAVED_SURFACE = 0;

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because we drew
     * the splash screen.
     */
    public static final int APP_TRANSITION_SPLASH_SCREEN = 1;

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because we all
     * app windows were drawn
     */
    public static final int APP_TRANSITION_WINDOWS_DRAWN = 2;

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because of a
     * timeout.
     */
    public static final int APP_TRANSITION_TIMEOUT = 3;

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because of a
     * we drew a task snapshot.
     */
    public static final int APP_TRANSITION_SNAPSHOT = 4;

    /**
     * Grant Uri permissions from one app to another. This method only extends
     * permission grants if {@code callingUid} has permission to them.
     */
    public abstract void grantUriPermissionFromIntent(int callingUid, String targetPkg,
            Intent intent, int targetUserId);

    /**
     * Verify that calling app has access to the given provider.
     */
    public abstract String checkContentProviderAccess(String authority, int userId);

    // Called by the power manager.
    public abstract void onWakefulnessChanged(int wakefulness);

    public abstract int startIsolatedProcess(String entryPoint, String[] mainArgs,
            String processName, String abiOverride, int uid, Runnable crashHandler);

    /**
     * Acquires a sleep token for the specified display with the specified tag.
     *
     * @param tag A string identifying the purpose of the token (eg. "Dream").
     * @param displayId The display to apply the sleep token to.
     */
    public abstract SleepToken acquireSleepToken(@NonNull String tag, int displayId);

    /**
     * Sleep tokens cause the activity manager to put the top activity to sleep.
     * They are used by components such as dreams that may hide and block interaction
     * with underlying activities.
     */
    public static abstract class SleepToken {

        /**
         * Releases the sleep token.
         */
        public abstract void release();
    }

    /**
     * Returns home activity for the specified user.
     *
     * @param userId ID of the user or {@link android.os.UserHandle#USER_ALL}
     */
    public abstract ComponentName getHomeActivityForUser(int userId);

    /**
     * Called when a user has been deleted. This can happen during normal device usage
     * or just at startup, when partially removed users are purged. Any state persisted by the
     * ActivityManager should be purged now.
     *
     * @param userId The user being cleaned up.
     */
    public abstract void onUserRemoved(int userId);

    public abstract void onLocalVoiceInteractionStarted(IBinder callingActivity,
            IVoiceInteractionSession mSession,
            IVoiceInteractor mInteractor);

    /**
     * Callback for window manager to let activity manager know that we are finally starting the
     * app transition;
     *
     * @param reasons A map from stack id to a reason integer why the transition was started,, which
     *                must be one of the APP_TRANSITION_* values.
     * @param timestamp The time at which the app transition started in
     *                  {@link SystemClock#uptimeMillis()} timebase.
     */
    public abstract void notifyAppTransitionStarting(SparseIntArray reasons, long timestamp);

    /**
     * Callback for window manager to let activity manager know that the app transition was
     * cancelled.
     */
    public abstract void notifyAppTransitionCancelled();

    /**
     * Callback for window manager to let activity manager know that the app transition is finished.
     */
    public abstract void notifyAppTransitionFinished();

    /**
     * Returns the top activity from each of the currently visible stacks. The first entry will be
     * the focused activity.
     */
    public abstract List<IBinder> getTopVisibleActivities();

    /**
     * Callback for window manager to let activity manager know that docked stack changes its
     * minimized state.
     */
    public abstract void notifyDockedStackMinimizedChanged(boolean minimized);

    /**
     * Kill foreground apps from the specified user.
     */
    public abstract void killForegroundAppsForUser(int userHandle);

    /**
     *  Sets how long a {@link PendingIntent} can be temporarily whitelist to by bypass restrictions
     *  such as Power Save mode.
     */
    public abstract void setPendingIntentWhitelistDuration(IIntentSender target,
            IBinder whitelistToken, long duration);

    /**
     * Allow DeviceIdleController to tell us about what apps are whitelisted.
     */
    public abstract void setDeviceIdleWhitelist(int[] appids);

    /**
     * Update information about which app IDs are on the temp whitelist.
     */
    public abstract void updateDeviceIdleTempWhitelist(int[] appids, int changingAppId,
            boolean adding);

    /**
     * Updates and persists the {@link Configuration} for a given user.
     *
     * @param values the configuration to update
     * @param userId the user to update the configuration for
     */
    public abstract void updatePersistentConfigurationForUser(@NonNull Configuration values,
            int userId);

    /**
     * Start activity {@code intents} as if {@code packageName} on user {@code userId} did it.
     *
     * @return error codes used by {@link IActivityManager#startActivity} and its siblings.
     */
    public abstract int startActivitiesAsPackage(String packageName,
            int userId, Intent[] intents, Bundle bOptions);

    /**
     * Get the procstate for the UID.  The return value will be between
     * {@link ActivityManager#MIN_PROCESS_STATE} and {@link ActivityManager#MAX_PROCESS_STATE}.
     * Note if the UID doesn't exist, it'll return {@link ActivityManager#PROCESS_STATE_NONEXISTENT}
     * (-1).
     */
    public abstract int getUidProcessState(int uid);

    /**
     * Called when Keyguard flags might have changed.
     *
     * @param callback Callback to run after activity visibilities have been reevaluated. This can
     *                 be used from window manager so that when the callback is called, it's
     *                 guaranteed that all apps have their visibility updated accordingly.
     */
    public abstract void notifyKeyguardFlagsChanged(@Nullable Runnable callback);

    /**
     * @return {@code true} if system is ready, {@code false} otherwise.
     */
    public abstract boolean isSystemReady();

    /**
     * Called when the trusted state of Keyguard has changed.
     */
    public abstract void notifyKeyguardTrustedChanged();

    /**
     * Sets if the given pid has an overlay UI or not.
     *
     * @param pid The pid we are setting overlay UI for.
     * @param hasOverlayUi True if the process has overlay UI.
     * @see android.view.WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
     */
    public abstract void setHasOverlayUi(int pid, boolean hasOverlayUi);

    /**
     * Called after the network policy rules are updated by
     * {@link com.android.server.net.NetworkPolicyManagerService} for a specific {@param uid} and
     * {@param procStateSeq}.
     */
    public abstract void notifyNetworkPolicyRulesUpdated(int uid, long procStateSeq);

    /**
     * Called after virtual display Id is updated by
     * {@link com.android.server.vr.Vr2dDisplay} with a specific
     * {@param vr2dDisplayId}.
     */
    public abstract void setVr2dDisplayId(int vr2dDisplayId);

    /**
     * Saves the current activity manager state and includes the saved state in the next dump of
     * activity manager.
     */
    public abstract void saveANRState(String reason);

    /**
     * Clears the previously saved activity manager ANR state.
     */
    public abstract void clearSavedANRState();

    /**
     * Set focus on an activity.
     * @param token The IApplicationToken for the activity
     */
    public abstract void setFocusedActivity(IBinder token);
}
