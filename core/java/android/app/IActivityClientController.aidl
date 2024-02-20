/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.app.ActivityManager;
import android.app.ICompatCameraControlCallback;
import android.app.IRequestFinishCallback;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.IRemoteCallback;
import android.os.PersistableBundle;
import android.view.RemoteAnimationDefinition;
import android.window.SizeConfigurationBuckets;

import com.android.internal.policy.IKeyguardDismissCallback;

/**
 * Interface for the callback and request from an activity to system.
 *
 * {@hide}
 */
interface IActivityClientController {
    oneway void activityIdle(in IBinder token, in Configuration config, in boolean stopProfiling);
    oneway void activityResumed(in IBinder token, in boolean handleSplashScreenExit);
    oneway void activityRefreshed(in IBinder token);
    /**
     * This call is not one-way because {@link #activityPaused()) is not one-way, or
     * the top-resumed-lost could be reported after activity paused.
     */
    void activityTopResumedStateLost();
    /**
     * Notifies that the activity has completed paused. This call is not one-way because it can make
     * consecutive launch in the same process more coherent. About the order of binder call, it
     * should be fine with other one-way calls because if pause hasn't completed on the server side,
     * there won't be other lifecycle changes.
     */
    void activityPaused(in IBinder token);
    oneway void activityStopped(in IBinder token, in Bundle state,
            in PersistableBundle persistentState, in CharSequence description);
    oneway void activityDestroyed(in IBinder token);
    oneway void activityLocalRelaunch(in IBinder token);
    oneway void activityRelaunched(in IBinder token);

    oneway void reportSizeConfigurations(in IBinder token,
            in SizeConfigurationBuckets sizeConfigurations);
    boolean moveActivityTaskToBack(in IBinder token, boolean nonRoot);
    boolean shouldUpRecreateTask(in IBinder token, in String destAffinity);
    boolean navigateUpTo(in IBinder token, in Intent target, in String resolvedType,
            int resultCode, in Intent resultData);
    boolean releaseActivityInstance(in IBinder token);
    boolean finishActivity(in IBinder token, int code, in Intent data, int finishTask);
    boolean finishActivityAffinity(in IBinder token);
    /** Finish all activities that were started for result from the specified activity. */
    void finishSubActivity(in IBinder token, in String resultWho, int requestCode);
    /**
     * Indicates that when the activity finsihes, the result should be immediately sent to the
     * originating activity. Must only be invoked during MediaProjection setup.
     */
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)")
    void setForceSendResultForMediaProjection(in IBinder token);

    boolean isTopOfTask(in IBinder token);
    boolean willActivityBeVisible(in IBinder token);
    int getDisplayId(in IBinder activityToken);
    int getTaskForActivity(in IBinder token, in boolean onlyRoot);
    /**
     * Returns the {@link Configuration} of the task which hosts the Activity, or {@code null} if
     * the task {@link Configuration} cannot be obtained.
     */
    Configuration getTaskConfiguration(in IBinder activityToken);
    IBinder getActivityTokenBelow(IBinder token);
    ComponentName getCallingActivity(in IBinder token);
    String getCallingPackage(in IBinder token);
    int getLaunchedFromUid(in IBinder token);
    int getActivityCallerUid(in IBinder activityToken, in IBinder callerToken);
    String getLaunchedFromPackage(in IBinder token);
    String getActivityCallerPackage(in IBinder activityToken, in IBinder callerToken);

    int checkActivityCallerContentUriPermission(in IBinder activityToken, in IBinder callerToken,
            in Uri uri, int modeFlags, int userId);

    void setRequestedOrientation(in IBinder token, int requestedOrientation);
    int getRequestedOrientation(in IBinder token);

    boolean convertFromTranslucent(in IBinder token);
    boolean convertToTranslucent(in IBinder token, in Bundle options);

    boolean isImmersive(in IBinder token);
    void setImmersive(in IBinder token, boolean immersive);

    boolean enterPictureInPictureMode(in IBinder token, in PictureInPictureParams params);
    void setPictureInPictureParams(in IBinder token, in PictureInPictureParams params);
    oneway void setShouldDockBigOverlays(in IBinder token, in boolean shouldDockBigOverlays);
    void toggleFreeformWindowingMode(in IBinder token);
    oneway void requestMultiwindowFullscreen(in IBinder token, in int request,
            in IRemoteCallback callback);

    oneway void startLockTaskModeByToken(in IBinder token);
    oneway void stopLockTaskModeByToken(in IBinder token);
    oneway void showLockTaskEscapeMessage(in IBinder token);
    void setTaskDescription(in IBinder token, in ActivityManager.TaskDescription values);

    boolean showAssistFromActivity(in IBinder token, in Bundle args);
    boolean isRootVoiceInteraction(in IBinder token);
    void startLocalVoiceInteraction(in IBinder token, in Bundle options);
    void stopLocalVoiceInteraction(in IBinder token);

    oneway void setShowWhenLocked(in IBinder token, boolean showWhenLocked);
    oneway void setInheritShowWhenLocked(in IBinder token, boolean setInheritShownWhenLocked);
    void setTurnScreenOn(in IBinder token, boolean turnScreenOn);
    oneway void setAllowCrossUidActivitySwitchFromBelow(in IBinder token, boolean allowed);
    oneway void reportActivityFullyDrawn(in IBinder token, boolean restoredFromBundle);
    oneway void overrideActivityTransition(IBinder token, boolean open, int enterAnim, int exitAnim,
            int backgroundColor);
    oneway void clearOverrideActivityTransition(IBinder token, boolean open);
    /**
     * Overrides the animation of activity pending transition. This call is not one-way because
     * the method is usually used after startActivity or Activity#finish. If this is non-blocking,
     * the calling activity may proceed to complete pause and become stopping state, which will
     * cause the request to be ignored. Besides, startActivity and Activity#finish are blocking
     * calls, so this method should be the same as them to keep the invocation order.
     */
    void overridePendingTransition(in IBinder token, in String packageName,
            int enterAnim, int exitAnim, int backgroundColor);
    int setVrMode(in IBinder token, boolean enabled, in ComponentName packageName);

    /** See {@link android.app.Activity#setRecentsScreenshotEnabled}. */
    oneway void setRecentsScreenshotEnabled(in IBinder token, boolean enabled);

    /**
     * It should only be called from home activity to remove its outdated snapshot. The home
     * snapshot is used to speed up entering home from screen off. If the content of home activity
     * is significantly different from before taking the snapshot, then the home activity can use
     * this method to avoid inconsistent transition.
     */
    void invalidateHomeTaskSnapshot(IBinder homeToken);

    void dismissKeyguard(in IBinder token, in IKeyguardDismissCallback callback,
            in CharSequence message);

    /** Registers remote animations for a specific activity. */
    void registerRemoteAnimations(in IBinder token, in RemoteAnimationDefinition definition);

    /** Unregisters all remote animations for a specific activity. */
    void unregisterRemoteAnimations(in IBinder token);

    /**
     * Reports that an Activity received a back key press.
     */
    oneway void onBackPressed(in IBinder activityToken,
            in IRequestFinishCallback callback);

    /** Reports that the splash screen view has attached to activity.  */
    oneway void splashScreenAttached(in IBinder token);

    /**
     * Shows or hides a Camera app compat toggle for stretched issues with the requested state.
     *
     * @param token The token for the window that needs a control.
     * @param showControl Whether the control should be shown or hidden.
     * @param transformationApplied Whether the treatment is already applied.
     * @param callback The callback executed when the user clicks on a control.
     */
    oneway void requestCompatCameraControl(in IBinder token, boolean showControl,
            boolean transformationApplied, in ICompatCameraControlCallback callback);

    /**
     * If set, any activity launch in the same task will be overridden to the locale of activity
     * that started the task.
     */
    void enableTaskLocaleOverride(in IBinder token);

    /**
     * Return {@code true} if the activity was explicitly requested to be launched in the
     * TaskFragment.
     *
     * @param activityToken The token of the Activity.
     * @param taskFragmentToken The token of the TaskFragment.
     */
    boolean isRequestedToLaunchInTaskFragment(in IBinder activityToken,
            in IBinder taskFragmentToken);

    /**
     * Enable or disable ActivityRecordInputSink to block input events.
     *
     * @param token The token for the activity that requests to toggle.
     * @param enabled Whether the input evens are blocked by ActivityRecordInputSink.
     */
    @JavaPassthrough(annotation = "@android.annotation.RequiresPermission(android.Manifest"
            + ".permission.INTERNAL_SYSTEM_WINDOW)")
    oneway void setActivityRecordInputSinkEnabled(in IBinder activityToken, boolean enabled);
}
