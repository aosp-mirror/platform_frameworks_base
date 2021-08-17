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

import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.IBinder;
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

    boolean navigateUpTo(IBinder token, Intent destIntent, int resultCode,
            Intent resultData) {
        try {
            return getActivityClientController().navigateUpTo(token, destIntent, resultCode,
                    resultData);
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

    void toggleFreeformWindowingMode(IBinder token) {
        try {
            getActivityClientController().toggleFreeformWindowingMode(token);
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

    int setVrMode(IBinder token, boolean enabled, ComponentName packageName) {
        try {
            return getActivityClientController().setVrMode(token, enabled, packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    void overridePendingTransition(IBinder token, String packageName,
            int enterAnim, int exitAnim) {
        try {
            getActivityClientController().overridePendingTransition(token, packageName,
                    enterAnim, exitAnim);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    void setDisablePreviewScreenshots(IBinder token, boolean disable) {
        try {
            getActivityClientController().setDisablePreviewScreenshots(token, disable);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /** Removes the snapshot of home task. */
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

    void onBackPressedOnTaskRoot(IBinder token, IRequestFinishCallback callback) {
        try {
            getActivityClientController().onBackPressedOnTaskRoot(token, callback);
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
