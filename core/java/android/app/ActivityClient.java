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

import static android.Manifest.permission.INTERNAL_SYSTEM_WINDOW;
import static android.os.UserHandle.getCallingUserId;

import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.Singleton;
import android.view.RemoteAnimationDefinition;
import android.window.SizeConfigurationBuckets;

import com.android.internal.policy.IKeyguardDismissCallback;

/**
 * Provides the activity associated operations that communicate with system.
 *
 * @hide
 */
public class ActivityClient {
    private ActivityClient() {}

    /** Reports the main thread is idle after the activity is resumed. */
    public void activityIdle(IBinder token, Configuration config, boolean stopProfiling) {
        try {
            getActivityClientController().activityIdle(token, config, stopProfiling);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Reports {@link Activity#onResume()} is done. */
    public void activityResumed(IBinder token, boolean handleSplashScreenExit) {
        try {
            getActivityClientController().activityResumed(token, handleSplashScreenExit);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Reports {@link android.app.servertransaction.RefreshCallbackItem} is executed. */
    public void activityRefreshed(IBinder token) {
        try {
            getActivityClientController().activityRefreshed(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports after {@link Activity#onTopResumedActivityChanged(boolean)} is called for losing the
     * top most position.
     */
    public void activityTopResumedStateLost() {
        try {
            getActivityClientController().activityTopResumedStateLost();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Reports {@link Activity#onPause()} is done. */
    public void activityPaused(IBinder token) {
        try {
            getActivityClientController().activityPaused(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Reports {@link Activity#onStop()} is done. */
    public void activityStopped(IBinder token, Bundle state, PersistableBundle persistentState,
            CharSequence description) {
        try {
            getActivityClientController().activityStopped(token, state, persistentState,
                    description);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Reports {@link Activity#onDestroy()} is done. */
    public void activityDestroyed(IBinder token) {
        try {
            getActivityClientController().activityDestroyed(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Reports the activity starts local relaunch. */
    public void activityLocalRelaunch(IBinder token) {
        try {
            getActivityClientController().activityLocalRelaunch(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Reports the activity has completed relaunched. */
    public void activityRelaunched(IBinder token) {
        try {
            getActivityClientController().activityRelaunched(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void reportSizeConfigurations(IBinder token, SizeConfigurationBuckets sizeConfigurations) {
        try {
            getActivityClientController().reportSizeConfigurations(token, sizeConfigurations);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public boolean moveActivityTaskToBack(IBinder token, boolean nonRoot) {
        try {
            return getActivityClientController().moveActivityTaskToBack(token, nonRoot);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    boolean shouldUpRecreateTask(IBinder token, String destAffinity) {
        try {
            return getActivityClientController().shouldUpRecreateTask(token, destAffinity);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    boolean navigateUpTo(IBinder token, Intent destIntent, String resolvedType, int resultCode,
            Intent resultData) {
        try {
            return getActivityClientController().navigateUpTo(token, destIntent, resolvedType,
                    resultCode, resultData);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    boolean releaseActivityInstance(IBinder token) {
        try {
            return getActivityClientController().releaseActivityInstance(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean finishActivity(IBinder token, int resultCode, Intent resultData,
            int finishTask) {
        try {
            return getActivityClientController().finishActivity(token, resultCode, resultData,
                    finishTask);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    boolean finishActivityAffinity(IBinder token) {
        try {
            return getActivityClientController().finishActivityAffinity(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void finishSubActivity(IBinder token, String resultWho, int requestCode) {
        try {
            getActivityClientController().finishSubActivity(token, resultWho, requestCode);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission(android.Manifest.permission.MANAGE_MEDIA_PROJECTION)
    void setForceSendResultForMediaProjection(IBinder token) {
        try {
            getActivityClientController().setForceSendResultForMediaProjection(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public boolean isTopOfTask(IBinder token) {
        try {
            return getActivityClientController().isTopOfTask(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    boolean willActivityBeVisible(IBinder token) {
        try {
            return getActivityClientController().willActivityBeVisible(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public int getDisplayId(IBinder token) {
        try {
            return getActivityClientController().getDisplayId(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        try {
            return getActivityClientController().getTaskForActivity(token, onlyRoot);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the {@link Configuration} of the task which hosts the Activity, or {@code null} if
     * the task {@link Configuration} cannot be obtained.
     */
    @Nullable
    public Configuration getTaskConfiguration(IBinder activityToken) {
        try {
            return getActivityClientController().getTaskConfiguration(activityToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the non-finishing activity token below in the same task if it belongs to the same
     * process.
     */
    @Nullable
    public IBinder getActivityTokenBelow(IBinder activityToken) {
        try {
            return getActivityClientController().getActivityTokenBelow(activityToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    ComponentName getCallingActivity(IBinder token) {
        try {
            return getActivityClientController().getCallingActivity(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    String getCallingPackage(IBinder token) {
        try {
            return getActivityClientController().getCallingPackage(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public int getLaunchedFromUid(IBinder token) {
        try {
            return getActivityClientController().getLaunchedFromUid(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public String getLaunchedFromPackage(IBinder token) {
        try {
            return getActivityClientController().getLaunchedFromPackage(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Returns the uid of the app that launched the activity. */
    public int getActivityCallerUid(IBinder activityToken, IBinder callerToken) {
        try {
            return getActivityClientController().getActivityCallerUid(activityToken,
                    callerToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Returns the package of the app that launched the activity. */
    public String getActivityCallerPackage(IBinder activityToken, IBinder callerToken) {
        try {
            return getActivityClientController().getActivityCallerPackage(activityToken,
                    callerToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** Checks if the app that launched the activity has access to the URI. */
    public int checkActivityCallerContentUriPermission(IBinder activityToken, IBinder callerToken,
            Uri uri, int modeFlags) {
        try {
            return getActivityClientController().checkActivityCallerContentUriPermission(
                    activityToken, callerToken, ContentProvider.getUriWithoutUserId(uri), modeFlags,
                    ContentProvider.getUserIdFromUri(uri, getCallingUserId()));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public void setRequestedOrientation(IBinder token, int requestedOrientation) {
        try {
            getActivityClientController().setRequestedOrientation(token, requestedOrientation);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    int getRequestedOrientation(IBinder token) {
        try {
            return getActivityClientController().getRequestedOrientation(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    boolean convertFromTranslucent(IBinder token) {
        try {
            return getActivityClientController().convertFromTranslucent(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    boolean convertToTranslucent(IBinder token, Bundle options) {
        try {
            return getActivityClientController().convertToTranslucent(token, options);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void reportActivityFullyDrawn(IBinder token, boolean restoredFromBundle) {
        try {
            getActivityClientController().reportActivityFullyDrawn(token, restoredFromBundle);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    boolean isImmersive(IBinder token) {
        try {
            return getActivityClientController().isImmersive(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void setImmersive(IBinder token, boolean immersive) {
        try {
            getActivityClientController().setImmersive(token, immersive);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    boolean enterPictureInPictureMode(IBinder token, PictureInPictureParams params) {
        try {
            return getActivityClientController().enterPictureInPictureMode(token, params);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void setPictureInPictureParams(IBinder token, PictureInPictureParams params) {
        try {
            getActivityClientController().setPictureInPictureParams(token, params);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void setShouldDockBigOverlays(IBinder token, boolean shouldDockBigOverlays) {
        try {
            getActivityClientController().setShouldDockBigOverlays(token, shouldDockBigOverlays);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void toggleFreeformWindowingMode(IBinder token) {
        try {
            getActivityClientController().toggleFreeformWindowingMode(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void requestMultiwindowFullscreen(IBinder token, int request, IRemoteCallback callback) {
        try {
            getActivityClientController().requestMultiwindowFullscreen(token, request, callback);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void startLockTaskModeByToken(IBinder token) {
        try {
            getActivityClientController().startLockTaskModeByToken(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void stopLockTaskModeByToken(IBinder token) {
        try {
            getActivityClientController().stopLockTaskModeByToken(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void showLockTaskEscapeMessage(IBinder token) {
        try {
            getActivityClientController().showLockTaskEscapeMessage(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void setTaskDescription(IBinder token, ActivityManager.TaskDescription td) {
        try {
            getActivityClientController().setTaskDescription(token, td);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    boolean showAssistFromActivity(IBinder token, Bundle args) {
        try {
            return getActivityClientController().showAssistFromActivity(token, args);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    boolean isRootVoiceInteraction(IBinder token) {
        try {
            return getActivityClientController().isRootVoiceInteraction(token);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void startLocalVoiceInteraction(IBinder callingActivity, Bundle options) {
        try {
            getActivityClientController().startLocalVoiceInteraction(callingActivity, options);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void stopLocalVoiceInteraction(IBinder callingActivity) {
        try {
            getActivityClientController().stopLocalVoiceInteraction(callingActivity);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void setShowWhenLocked(IBinder token, boolean showWhenLocked) {
        try {
            getActivityClientController().setShowWhenLocked(token, showWhenLocked);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void setInheritShowWhenLocked(IBinder token, boolean inheritShowWhenLocked) {
        try {
            getActivityClientController().setInheritShowWhenLocked(token, inheritShowWhenLocked);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void setTurnScreenOn(IBinder token, boolean turnScreenOn) {
        try {
            getActivityClientController().setTurnScreenOn(token, turnScreenOn);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void setAllowCrossUidActivitySwitchFromBelow(IBinder token, boolean allowed) {
        try {
            getActivityClientController().setAllowCrossUidActivitySwitchFromBelow(token, allowed);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    int setVrMode(IBinder token, boolean enabled, ComponentName packageName) {
        try {
            return getActivityClientController().setVrMode(token, enabled, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void overrideActivityTransition(IBinder token, boolean open, int enterAnim, int exitAnim,
            int backgroundColor) {
        try {
            getActivityClientController().overrideActivityTransition(
                    token, open, enterAnim, exitAnim, backgroundColor);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void clearOverrideActivityTransition(IBinder token, boolean open) {
        try {
            getActivityClientController().clearOverrideActivityTransition(token, open);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void overridePendingTransition(IBinder token, String packageName, int enterAnim, int exitAnim,
            int backgroundColor) {
        try {
            getActivityClientController().overridePendingTransition(token, packageName,
                    enterAnim, exitAnim, backgroundColor);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void setRecentsScreenshotEnabled(IBinder token, boolean enabled) {
        try {
            getActivityClientController().setRecentsScreenshotEnabled(token, enabled);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes the outdated snapshot of the home task.
     *
     * @param homeToken The token of the home task, or null if you have the
     *                  {@link android.Manifest.permission#MANAGE_ACTIVITY_TASKS} permission and
     *                  want us to find the home task token for you.
     */
    public void invalidateHomeTaskSnapshot(IBinder homeToken) {
        try {
            getActivityClientController().invalidateHomeTaskSnapshot(homeToken);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void dismissKeyguard(IBinder token, IKeyguardDismissCallback callback,
            CharSequence message) {
        try {
            getActivityClientController().dismissKeyguard(token, callback, message);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void registerRemoteAnimations(IBinder token, RemoteAnimationDefinition definition) {
        try {
            getActivityClientController().registerRemoteAnimations(token, definition);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void unregisterRemoteAnimations(IBinder token) {
        try {
            getActivityClientController().unregisterRemoteAnimations(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void onBackPressed(IBinder token, IRequestFinishCallback callback) {
        try {
            getActivityClientController().onBackPressed(token, callback);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Reports the splash screen view has attached to client.
     */
    void reportSplashScreenAttached(IBinder token) {
        try {
            getActivityClientController().splashScreenAttached(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void enableTaskLocaleOverride(IBinder token) {
        try {
            getActivityClientController().enableTaskLocaleOverride(token);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if the activity was explicitly requested to be launched in the
     * TaskFragment.
     *
     * @param activityToken The token of the Activity.
     * @param taskFragmentToken The token of the TaskFragment.
     */
    public boolean isRequestedToLaunchInTaskFragment(IBinder activityToken,
            IBinder taskFragmentToken) {
        try {
            return getActivityClientController().isRequestedToLaunchInTaskFragment(activityToken,
                    taskFragmentToken);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @RequiresPermission(INTERNAL_SYSTEM_WINDOW)
    void setActivityRecordInputSinkEnabled(IBinder activityToken, boolean enabled) {
        try {
            getActivityClientController().setActivityRecordInputSinkEnabled(activityToken, enabled);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Shows or hides a Camera app compat toggle for stretched issues with the requested state.
     *
     * @param token The token for the window that needs a control.
     * @param showControl Whether the control should be shown or hidden.
     * @param transformationApplied Whether the treatment is already applied.
     * @param callback The callback executed when the user clicks on a control.
     */
    void requestCompatCameraControl(Resources res, IBinder token, boolean showControl,
            boolean transformationApplied, ICompatCameraControlCallback callback) {
        if (!res.getBoolean(com.android.internal.R.bool
                .config_isCameraCompatControlForStretchedIssuesEnabled)) {
            return;
        }
        try {
            getActivityClientController().requestCompatCameraControl(
                    token, showControl, transformationApplied, callback);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    public static ActivityClient getInstance() {
        return sInstance.get();
    }

    /**
     * If system server has passed the controller interface, store it so the subsequent access can
     * speed up.
     */
    public static IActivityClientController setActivityClientController(
            IActivityClientController activityClientController) {
        // No lock because it is no harm to encounter race condition. The thread safe Singleton#get
        // will take over that case.
        return INTERFACE_SINGLETON.mKnownInstance = activityClientController;
    }

    private static IActivityClientController getActivityClientController() {
        final IActivityClientController controller = INTERFACE_SINGLETON.mKnownInstance;
        return controller != null ? controller : INTERFACE_SINGLETON.get();
    }

    private static final Singleton<ActivityClient> sInstance = new Singleton<ActivityClient>() {
        @Override
        protected ActivityClient create() {
            return new ActivityClient();
        }
    };

    private static final ActivityClientControllerSingleton INTERFACE_SINGLETON =
            new ActivityClientControllerSingleton();

    private static class ActivityClientControllerSingleton
            extends Singleton<IActivityClientController> {
        /**
         * A quick look up to reduce potential extra binder transactions. E.g. getting activity
         * task manager from service manager and controller from activity task manager.
         */
        IActivityClientController mKnownInstance;

        @Override
        protected IActivityClientController create() {
            try {
                return ActivityTaskManager.getService().getActivityClientController();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }
}
