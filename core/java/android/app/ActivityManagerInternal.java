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
import android.content.ComponentName;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
import android.service.voice.IVoiceInteractionSession;

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
     * the starting window.
     */
    public static final int APP_TRANSITION_STARTING_WINDOW = 1;

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

    // Called by the power manager.
    public abstract void onWakefulnessChanged(int wakefulness);

    public abstract int startIsolatedProcess(String entryPoint, String[] mainArgs,
            String processName, String abiOverride, int uid, Runnable crashHandler);

    /**
     * Acquires a sleep token with the specified tag.
     *
     * @param tag A string identifying the purpose of the token (eg. "Dream").
     */
    public abstract SleepToken acquireSleepToken(@NonNull String tag);

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
     * Callback for window manager to let activity manager know that the starting window has been
     * drawn
     */
    public abstract void notifyStartingWindowDrawn();

    /**
     * Callback for window manager to let activity manager know that we are finally starting the
     * app transition;
     *
     * @param reason The reason why the app transition started. Must be one of the APP_TRANSITION_*
     *               values.
     */
    public abstract void notifyAppTransitionStarting(int reason);

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
    public abstract void setPendingIntentWhitelistDuration(IIntentSender target, long duration);

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
}
