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
 * limitations under the License.
 */

package android.app;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.ApplicationErrorReport;
import android.app.ContentProviderHolder;
import android.app.GrantedUriPermission;
import android.app.IApplicationThread;
import android.app.IActivityClientController;
import android.app.IActivityController;
import android.app.IAppTask;
import android.app.IAssistDataReceiver;
import android.app.IInstrumentationWatcher;
import android.app.IProcessObserver;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.ITaskStackListener;
import android.app.IUiAutomationConnection;
import android.app.IUidObserver;
import android.app.IUserSwitchObserver;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.PictureInPictureUiState;
import android.app.ProfilerInfo;
import android.app.WaitResult;
import android.app.assist.AssistContent;
import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.UserInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.GraphicBuffer;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.IProgressListener;
import android.os.ParcelFileDescriptor;
import android.os.StrictMode;
import android.os.WorkSource;
import android.service.voice.IVoiceInteractionSession;
import android.view.IRecentsAnimationRunner;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationAdapter;
import android.window.IWindowOrganizerController;
import android.window.BackNavigationInfo;
import android.window.SplashScreenView;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.IResultReceiver;

import java.util.List;

/**
 * System private API for talking with the activity task manager that handles how activities are
 * managed on screen.
 *
 * {@hide}
 */
// TODO(b/174040395): Make this interface private to ActivityTaskManager.java and have external
// caller go through that call instead. This would help us better separate and control the API
// surface exposed.
// TODO(b/174041603): Create a builder interface for things like startActivityXXX(...) to reduce
// interface duplication.
interface IActivityTaskManager {
    int startActivity(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent intent, in String resolvedType,
            in IBinder resultTo, in String resultWho, int requestCode,
            int flags, in ProfilerInfo profilerInfo, in Bundle options);
    int startActivities(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent[] intents, in String[] resolvedTypes,
            in IBinder resultTo, in Bundle options, int userId);
    int startActivityAsUser(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent intent, in String resolvedType,
            in IBinder resultTo, in String resultWho, int requestCode, int flags,
            in ProfilerInfo profilerInfo, in Bundle options, int userId);
    boolean startNextMatchingActivity(in IBinder callingActivity,
            in Intent intent, in Bundle options);

    /**
    *  The DreamActivity has to be started in a special way that does not involve the PackageParser.
    *  The DreamActivity is a framework component inserted in the dream application process. Hence,
    *  it is not declared in the application's manifest and cannot be parsed. startDreamActivity
    *  creates the activity and starts it without reaching out to the PackageParser.
    */
    boolean startDreamActivity(in Intent intent);
    int startActivityIntentSender(in IApplicationThread caller,
            in IIntentSender target, in IBinder whitelistToken, in Intent fillInIntent,
            in String resolvedType, in IBinder resultTo, in String resultWho, int requestCode,
            int flagsMask, int flagsValues, in Bundle options);
    WaitResult startActivityAndWait(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent intent, in String resolvedType,
            in IBinder resultTo, in String resultWho, int requestCode, int flags,
            in ProfilerInfo profilerInfo, in Bundle options, int userId);
    int startActivityWithConfig(in IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, in Intent intent, in String resolvedType,
            in IBinder resultTo, in String resultWho, int requestCode, int startFlags,
            in Configuration newConfig, in Bundle options, int userId);
    int startVoiceActivity(in String callingPackage, in String callingFeatureId, int callingPid,
            int callingUid, in Intent intent, in String resolvedType,
            in IVoiceInteractionSession session, in IVoiceInteractor interactor, int flags,
            in ProfilerInfo profilerInfo, in Bundle options, int userId);
    String getVoiceInteractorPackageName(in IBinder callingVoiceInteractor);
    int startAssistantActivity(in String callingPackage, in String callingFeatureId, int callingPid,
            int callingUid, in Intent intent, in String resolvedType, in Bundle options, int userId);
    @JavaPassthrough(annotation="@android.annotation.RequiresPermission(android.Manifest.permission.MANAGE_GAME_ACTIVITY)")
    int startActivityFromGameSession(IApplicationThread caller, in String callingPackage,
            in String callingFeatureId, int callingPid, int callingUid, in Intent intent,
            int taskId, int userId);
    void startRecentsActivity(in Intent intent, in long eventTime,
            in IRecentsAnimationRunner recentsAnimationRunner);
    int startActivityFromRecents(int taskId, in Bundle options);
    int startActivityAsCaller(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int flags, in ProfilerInfo profilerInfo, in Bundle options,
            boolean ignoreTargetSecurity, int userId);

    boolean isActivityStartAllowedOnDisplay(int displayId, in Intent intent, in String resolvedType,
            int userId);

    void unhandledBack();

    /** Returns an interface to control the activity related operations. */
    IActivityClientController getActivityClientController();

    int getFrontActivityScreenCompatMode();
    void setFrontActivityScreenCompatMode(int mode);
    void setFocusedTask(int taskId);
    boolean removeTask(int taskId);
    void removeAllVisibleRecentTasks();
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum, boolean filterOnlyVisibleRecents,
            boolean keepIntentExtra);
    void moveTaskToFront(in IApplicationThread app, in String callingPackage, int task,
            int flags, in Bundle options);
    ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags,
            int userId);
    boolean isTopActivityImmersive();
    ActivityManager.TaskDescription getTaskDescription(int taskId);
    void reportAssistContextExtras(in IBinder assistToken, in Bundle extras,
            in AssistStructure structure, in AssistContent content, in Uri referrer);

    void setFocusedRootTask(int taskId);
    ActivityTaskManager.RootTaskInfo getFocusedRootTaskInfo();
    Rect getTaskBounds(int taskId);

    void cancelRecentsAnimation(boolean restoreHomeRootTaskPosition);
    void updateLockTaskPackages(int userId, in String[] packages);
    boolean isInLockTaskMode();
    int getLockTaskModeState();
    List<IBinder> getAppTasks(in String callingPackage);
    void startSystemLockTaskMode(int taskId);
    void stopSystemLockTaskMode();
    void finishVoiceTask(in IVoiceInteractionSession session);
    int addAppTask(in IBinder activityToken, in Intent intent,
            in ActivityManager.TaskDescription description, in Bitmap thumbnail);
    Point getAppTaskThumbnailSize();

    oneway void releaseSomeActivities(in IApplicationThread app);
    Bitmap getTaskDescriptionIcon(in String filename, int userId);
    void registerTaskStackListener(in ITaskStackListener listener);
    void unregisterTaskStackListener(in ITaskStackListener listener);
    void setTaskResizeable(int taskId, int resizeableMode);

    /**
     * Resize the task with given bounds
     *
     * @param taskId The id of the task to set the bounds for.
     * @param bounds The new bounds.
     * @param resizeMode Resize mode defined as {@code ActivityTaskManager#RESIZE_MODE_*} constants.
     * @return Return true on success. Otherwise false.
     */
    boolean resizeTask(int taskId, in Rect bounds, int resizeMode);
    void moveRootTaskToDisplay(int taskId, int displayId);

    void moveTaskToRootTask(int taskId, int rootTaskId, boolean toTop);

    /**
     * Removes root tasks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeRootTasksInWindowingModes(in int[] windowingModes);
    /** Removes root tasks of the activity types from the system. */
    void removeRootTasksWithActivityTypes(in int[] activityTypes);

    List<ActivityTaskManager.RootTaskInfo> getAllRootTaskInfos();
    ActivityTaskManager.RootTaskInfo getRootTaskInfo(int windowingMode, int activityType);
    List<ActivityTaskManager.RootTaskInfo> getAllRootTaskInfosOnDisplay(int displayId);
    ActivityTaskManager.RootTaskInfo getRootTaskInfoOnDisplay(int windowingMode, int activityType, int displayId);

    /**
     * Informs ActivityTaskManagerService that the keyguard is showing.
     *
     * @param showingKeyguard True if the keyguard is showing, false otherwise.
     * @param showingAod True if AOD is showing, false otherwise.
     */
    void setLockScreenShown(boolean showingKeyguard, boolean showingAod);
    Bundle getAssistContextExtras(int requestType);
    boolean requestAssistContextExtras(int requestType, in IAssistDataReceiver receiver,
            in Bundle receiverExtras, in IBinder activityToken,
            boolean focused, boolean newSessionId);
    boolean requestAutofillData(in IAssistDataReceiver receiver, in Bundle receiverExtras,
            in IBinder activityToken, int flags);
    boolean isAssistDataAllowedOnCurrentActivity();
    boolean requestAssistDataForTask(in IAssistDataReceiver receiver, int taskId,
            in String callingPackageName);

    /**
     * Notify the system that the keyguard is going away.
     *
     * @param flags See
     *              {@link android.view.WindowManagerPolicyConstants#KEYGUARD_GOING_AWAY_FLAG_TO_SHADE}
     *              etc.
     */
    void keyguardGoingAway(int flags);

    void suppressResizeConfigChanges(boolean suppress);

    /** Returns an interface enabling the management of window organizers. */
    IWindowOrganizerController getWindowOrganizerController();

    /**
     * Sets whether we are currently in an interactive split screen resize operation where we
     * are changing the docked stack size.
     */
    void setSplitScreenResizing(boolean resizing);
    boolean supportsLocalVoiceInteraction();

    // Get device configuration
    ConfigurationInfo getDeviceConfigurationInfo();

    /** Cancels the window transitions for the given task. */
    void cancelTaskWindowTransition(int taskId);

    /**
     * @param taskId the id of the task to retrieve the sAutoapshots for
     * @param isLowResolution if set, if the snapshot needs to be loaded from disk, this will load
     *                          a reduced resolution of it, which is much faster
     * @return a graphic buffer representing a screenshot of a task
     */
    android.window.TaskSnapshot getTaskSnapshot(int taskId, boolean isLowResolution);

    /**
     * @param taskId the id of the task to take a snapshot of
     * @return a graphic buffer representing a screenshot of a task
     */
    android.window.TaskSnapshot takeTaskSnapshot(int taskId);

    /**
     * Return the user id of last resumed activity.
     */
    int getLastResumedActivityUserId();

    /**
     * Updates global configuration and applies changes to the entire system.
     * @param values Update values for global configuration. If null is passed it will request the
     *               Window Manager to compute new config for the default display.
     * @throws RemoteException
     * @return Returns true if the configuration was updated.
     */
    boolean updateConfiguration(in Configuration values);
    void updateLockTaskFeatures(int userId, int flags);

    /**
     * Registers a remote animation to be run for all activity starts from a certain package during
     * a short predefined amount of time.
     */
    void registerRemoteAnimationForNextActivityStart(in String packageName,
            in RemoteAnimationAdapter adapter, in IBinder launchCookie);

    /**
     * Registers remote animations for a display.
     */
    void registerRemoteAnimationsForDisplay(int displayId, in RemoteAnimationDefinition definition);

    /** @see android.app.ActivityManager#alwaysShowUnsupportedCompileSdkWarning */
    void alwaysShowUnsupportedCompileSdkWarning(in ComponentName activity);

    void setVrThread(int tid);
    void setPersistentVrThread(int tid);
    void stopAppSwitches();
    void resumeAppSwitches();
    void setActivityController(in IActivityController watcher, boolean imAMonkey);
    void setVoiceKeepAwake(in IVoiceInteractionSession session, boolean keepAwake);

    int getPackageScreenCompatMode(in String packageName);
    void setPackageScreenCompatMode(in String packageName, int mode);
    boolean getPackageAskScreenCompat(in String packageName);
    void setPackageAskScreenCompat(in String packageName, boolean ask);

    /**
     * Clears launch params for given packages.
     */
    void clearLaunchParamsForPackages(in List<String> packageNames);

    /**
     * A splash screen view has copied.
     */
    void onSplashScreenViewCopyFinished(int taskId,
            in SplashScreenView.SplashScreenViewParcelable material);

    /**
     * When the Picture-in-picture state has changed.
     */
    void onPictureInPictureStateChanged(in PictureInPictureUiState pipState);

    /**
     * Re-attach navbar to the display during a recents transition.
     * TODO(188595497): Remove this once navbar attachment is in shell.
     */
    void detachNavigationBarFromApp(in IBinder transition);

    /**
     * Marks a process as a delegate for the currently playing remote transition animation. This
     * must be called from a process that is already a remote transition player or delegate. Any
     * marked delegates are cleaned-up automatically at the end of the transition.
     * @param caller is the IApplicationThread representing the calling process.
     */
    void setRunningRemoteTransitionDelegate(in IApplicationThread caller);

    /**
     * Prepare the back navigation in the server. This setups the leashed for sysui to animate
     * the back gesture and returns the data needed for the animation.
     * @param requestAnimation true if the caller wishes to animate the back navigation
     */
    android.window.BackNavigationInfo startBackNavigation(in boolean requestAnimation);
}
