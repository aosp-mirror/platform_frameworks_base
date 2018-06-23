/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.wm;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppProtoEnums;
import android.app.IActivityManager;
import android.app.IApplicationThread;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.service.voice.IVoiceInteractionSession;
import android.util.SparseIntArray;
import android.view.RemoteAnimationAdapter;

import com.android.internal.app.IVoiceInteractor;
import com.android.server.am.WindowProcessController;

import java.util.List;

/**
 * Activity Task manager local system service interface.
 * @hide Only for use within system server
 */
public abstract class ActivityTaskManagerInternal {

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because we drew
     * the splash screen.
     */
    public static final int APP_TRANSITION_SPLASH_SCREEN =
              AppProtoEnums.APP_TRANSITION_SPLASH_SCREEN; // 1

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because we all
     * app windows were drawn
     */
    public static final int APP_TRANSITION_WINDOWS_DRAWN =
              AppProtoEnums.APP_TRANSITION_WINDOWS_DRAWN; // 2

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because of a
     * timeout.
     */
    public static final int APP_TRANSITION_TIMEOUT =
              AppProtoEnums.APP_TRANSITION_TIMEOUT; // 3

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because of a
     * we drew a task snapshot.
     */
    public static final int APP_TRANSITION_SNAPSHOT =
              AppProtoEnums.APP_TRANSITION_SNAPSHOT; // 4

    /**
     * Type for {@link #notifyAppTransitionStarting}: The transition was started because it was a
     * recents animation and we only needed to wait on the wallpaper.
     */
    public static final int APP_TRANSITION_RECENTS_ANIM =
            AppProtoEnums.APP_TRANSITION_RECENTS_ANIM; // 5

    /**
     * The bundle key to extract the assist data.
     */
    public static final String ASSIST_KEY_DATA = "data";

    /**
     * The bundle key to extract the assist structure.
     */
    public static final String ASSIST_KEY_STRUCTURE = "structure";

    /**
     * The bundle key to extract the assist content.
     */
    public static final String ASSIST_KEY_CONTENT = "content";

    /**
     * The bundle key to extract the assist receiver extras.
     */
    public static final String ASSIST_KEY_RECEIVER_EXTRAS = "receiverExtras";

    public interface ScreenObserver {
        void onAwakeStateChanged(boolean isAwake);
        void onKeyguardStateChanged(boolean isShowing);
    }

    /**
     * Sleep tokens cause the activity manager to put the top activity to sleep.
     * They are used by components such as dreams that may hide and block interaction
     * with underlying activities.
     */
    public static abstract class SleepToken {

        /** Releases the sleep token. */
        public abstract void release();
    }

    /**
     * Acquires a sleep token for the specified display with the specified tag.
     *
     * @param tag A string identifying the purpose of the token (eg. "Dream").
     * @param displayId The display to apply the sleep token to.
     */
    public abstract SleepToken acquireSleepToken(@NonNull String tag, int displayId);

    /**
     * Returns home activity for the specified user.
     *
     * @param userId ID of the user or {@link android.os.UserHandle#USER_ALL}
     */
    public abstract ComponentName getHomeActivityForUser(int userId);

    public abstract void onLocalVoiceInteractionStarted(IBinder callingActivity,
            IVoiceInteractionSession mSession,
            IVoiceInteractor mInteractor);

    /**
     * Callback for window manager to let activity manager know that we are finally starting the
     * app transition;
     *
     * @param reasons A map from windowing mode to a reason integer why the transition was started,
     *                which must be one of the APP_TRANSITION_* values.
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
     * Start activity {@code intents} as if {@code packageName} on user {@code userId} did it.
     *
     * - DO NOT call it with the calling UID cleared.
     * - All the necessary caller permission checks must be done at callsites.
     *
     * @return error codes used by {@link IActivityManager#startActivity} and its siblings.
     */
    public abstract int startActivitiesAsPackage(String packageName,
            int userId, Intent[] intents, Bundle bOptions);

    /**
     * Start activity {@code intent} without calling user-id check.
     *
     * - DO NOT call it with the calling UID cleared.
     * - The caller must do the calling user ID check.
     *
     * @return error codes used by {@link IActivityManager#startActivity} and its siblings.
     */
    public abstract int startActivityAsUser(IApplicationThread caller, String callingPackage,
            Intent intent, @Nullable Bundle options, int userId);

    /**
     * Called when Keyguard flags might have changed.
     *
     * @param callback Callback to run after activity visibilities have been reevaluated. This can
     *                 be used from window manager so that when the callback is called, it's
     *                 guaranteed that all apps have their visibility updated accordingly.
     */
    public abstract void notifyKeyguardFlagsChanged(@Nullable Runnable callback);

    /**
     * Called when the trusted state of Keyguard has changed.
     */
    public abstract void notifyKeyguardTrustedChanged();

    /**
     * Called after virtual display Id is updated by
     * {@link com.android.server.vr.Vr2dDisplay} with a specific
     * {@param vr2dDisplayId}.
     */
    public abstract void setVr2dDisplayId(int vr2dDisplayId);

    /**
     * Set focus on an activity.
     * @param token The IApplicationToken for the activity
     */
    public abstract void setFocusedActivity(IBinder token);

    public abstract void registerScreenObserver(ScreenObserver observer);

    /**
     * Returns is the caller has the same uid as the Recents component
     */
    public abstract boolean isCallerRecents(int callingUid);

    /**
     * Returns whether the recents component is the home activity for the given user.
     */
    public abstract boolean isRecentsComponentHomeActivity(int userId);

    /**
     * Cancels any currently running recents animation.
     */
    public abstract void cancelRecentsAnimation(boolean restoreHomeStackPosition);

    /**
     * This enforces {@code func} can only be called if either the caller is Recents activity or
     * has {@code permission}.
     */
    public abstract void enforceCallerIsRecentsOrHasPermission(String permission, String func);

    /**
     * Called after the voice interaction service has changed.
     */
    public abstract void notifyActiveVoiceInteractionServiceChanged(ComponentName component);

    /**
     * Set a uid that is allowed to bypass stopped app switches, launching an app
     * whenever it wants.
     *
     * @param type Type of the caller -- unique string the caller supplies to identify itself
     * and disambiguate with other calles.
     * @param uid The uid of the app to be allowed, or -1 to clear the uid for this type.
     * @param userId The user it is allowed for.
     */
    public abstract void setAllowAppSwitches(@NonNull String type, int uid, int userId);

    /**
     * Called when a user has been deleted. This can happen during normal device usage
     * or just at startup, when partially removed users are purged. Any state persisted by the
     * ActivityManager should be purged now.
     *
     * @param userId The user being cleaned up.
     */
    public abstract void onUserStopped(int userId);
    public abstract boolean isGetTasksAllowed(String caller, int callingPid, int callingUid);

    public abstract void onProcessAdded(WindowProcessController proc);
    public abstract void onProcessRemoved(String name, int uid);
    public abstract void onCleanUpApplicationRecord(WindowProcessController proc);
}
