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
import android.app.ApplicationErrorReport;
import android.app.ContentProviderHolder;
import android.app.GrantedUriPermission;
import android.app.IApplicationThread;
import android.app.IActivityController;
import android.app.IAppTask;
import android.app.IAssistDataReceiver;
import android.app.IInstrumentationWatcher;
import android.app.IProcessObserver;
import android.app.IRequestFinishCallback;
import android.app.IServiceConnection;
import android.app.IStopUserCallback;
import android.app.ITaskStackListener;
import android.app.IUiAutomationConnection;
import android.app.IUidObserver;
import android.app.IUserSwitchObserver;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
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
import android.os.PersistableBundle;
import android.os.StrictMode;
import android.os.WorkSource;
import android.service.voice.IVoiceInteractionSession;
import android.view.IRecentsAnimationRunner;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationAdapter;
import com.android.internal.app.IVoiceInteractor;
import com.android.internal.os.IResultReceiver;
import com.android.internal.policy.IKeyguardDismissCallback;

import java.util.List;

/**
 * System private API for talking with the activity task manager that handles how activities are
 * managed on screen.
 *
 * {@hide}
 */
interface IActivityTaskManager {
    int startActivity(in IApplicationThread caller, in String callingPackage, in Intent intent,
            in String resolvedType, in IBinder resultTo, in String resultWho, int requestCode,
            int flags, in ProfilerInfo profilerInfo, in Bundle options);
    int startActivities(in IApplicationThread caller, in String callingPackage,
            in Intent[] intents, in String[] resolvedTypes, in IBinder resultTo,
            in Bundle options, int userId);
    int startActivityAsUser(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int flags, in ProfilerInfo profilerInfo,
            in Bundle options, int userId);
    boolean startNextMatchingActivity(in IBinder callingActivity,
            in Intent intent, in Bundle options);
    int startActivityIntentSender(in IApplicationThread caller,
            in IIntentSender target, in IBinder whitelistToken, in Intent fillInIntent,
            in String resolvedType, in IBinder resultTo, in String resultWho, int requestCode,
            int flagsMask, int flagsValues, in Bundle options);
    WaitResult startActivityAndWait(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int flags, in ProfilerInfo profilerInfo, in Bundle options,
            int userId);
    int startActivityWithConfig(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int startFlags, in Configuration newConfig,
            in Bundle options, int userId);
    int startVoiceActivity(in String callingPackage, int callingPid, int callingUid,
            in Intent intent, in String resolvedType, in IVoiceInteractionSession session,
            in IVoiceInteractor interactor, int flags, in ProfilerInfo profilerInfo,
            in Bundle options, int userId);
    int startAssistantActivity(in String callingPackage, int callingPid, int callingUid,
            in Intent intent, in String resolvedType, in Bundle options, int userId);
    void startRecentsActivity(in Intent intent, in IAssistDataReceiver assistDataReceiver,
            in IRecentsAnimationRunner recentsAnimationRunner);
    int startActivityFromRecents(int taskId, in Bundle options);
    int startActivityAsCaller(in IApplicationThread caller, in String callingPackage,
            in Intent intent, in String resolvedType, in IBinder resultTo, in String resultWho,
            int requestCode, int flags, in ProfilerInfo profilerInfo, in Bundle options,
            IBinder permissionToken, boolean ignoreTargetSecurity, int userId);
    boolean isActivityStartAllowedOnDisplay(int displayId, in Intent intent, in String resolvedType,
            int userId);

    void unhandledBack();
    boolean finishActivity(in IBinder token, int code, in Intent data, int finishTask);
    boolean finishActivityAffinity(in IBinder token);

    oneway void activityIdle(in IBinder token, in Configuration config,
            in boolean stopProfiling);
    void activityResumed(in IBinder token);
    void activityTopResumedStateLost();
    void activityPaused(in IBinder token);
    void activityStopped(in IBinder token, in Bundle state,
            in PersistableBundle persistentState, in CharSequence description);
    oneway void activityDestroyed(in IBinder token);
    void activityRelaunched(in IBinder token);
    oneway void activitySlept(in IBinder token);
    int getFrontActivityScreenCompatMode();
    void setFrontActivityScreenCompatMode(int mode);
    String getCallingPackage(in IBinder token);
    ComponentName getCallingActivity(in IBinder token);
    void setFocusedTask(int taskId);
    boolean removeTask(int taskId);
    void removeAllVisibleRecentTasks();
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum);
    List<ActivityManager.RunningTaskInfo> getFilteredTasks(int maxNum, int ignoreActivityType,
            int ignoreWindowingMode);
    boolean shouldUpRecreateTask(in IBinder token, in String destAffinity);
    boolean navigateUpTo(in IBinder token, in Intent target, int resultCode,
            in Intent resultData);
    void moveTaskToFront(in IApplicationThread app, in String callingPackage, int task,
            int flags, in Bundle options);
    int getTaskForActivity(in IBinder token, in boolean onlyRoot);
    /** Finish all activities that were started for result from the specified activity. */
    void finishSubActivity(in IBinder token, in String resultWho, int requestCode);
    ParceledListSlice getRecentTasks(int maxNum, int flags, int userId);
    boolean willActivityBeVisible(in IBinder token);
    void setRequestedOrientation(in IBinder token, int requestedOrientation);
    int getRequestedOrientation(in IBinder token);
    boolean convertFromTranslucent(in IBinder token);
    boolean convertToTranslucent(in IBinder token, in Bundle options);
    void notifyActivityDrawn(in IBinder token);
    void reportActivityFullyDrawn(in IBinder token, boolean restoredFromBundle);
    int getActivityDisplayId(in IBinder activityToken);
    boolean isImmersive(in IBinder token);
    void setImmersive(in IBinder token, boolean immersive);
    boolean isTopActivityImmersive();
    boolean moveActivityTaskToBack(in IBinder token, boolean nonRoot);
    ActivityManager.TaskDescription getTaskDescription(int taskId);
    void overridePendingTransition(in IBinder token, in String packageName,
            int enterAnim, int exitAnim);
    int getLaunchedFromUid(in IBinder activityToken);
    String getLaunchedFromPackage(in IBinder activityToken);
    void reportAssistContextExtras(in IBinder token, in Bundle extras,
            in AssistStructure structure, in AssistContent content, in Uri referrer);

    void setFocusedStack(int stackId);
    ActivityManager.StackInfo getFocusedStackInfo();
    Rect getTaskBounds(int taskId);

    void cancelRecentsAnimation(boolean restoreHomeStackPosition);
    void startLockTaskModeByToken(in IBinder token);
    void stopLockTaskModeByToken(in IBinder token);
    void updateLockTaskPackages(int userId, in String[] packages);
    boolean isInLockTaskMode();
    int getLockTaskModeState();
    void setTaskDescription(in IBinder token, in ActivityManager.TaskDescription values);
    Bundle getActivityOptions(in IBinder token);
    List<IBinder> getAppTasks(in String callingPackage);
    void startSystemLockTaskMode(int taskId);
    void stopSystemLockTaskMode();
    void finishVoiceTask(in IVoiceInteractionSession session);
    boolean isTopOfTask(in IBinder token);
    void notifyLaunchTaskBehindComplete(in IBinder token);
    void notifyEnterAnimationComplete(in IBinder token);
    int addAppTask(in IBinder activityToken, in Intent intent,
            in ActivityManager.TaskDescription description, in Bitmap thumbnail);
    Point getAppTaskThumbnailSize();
    boolean releaseActivityInstance(in IBinder token);
    /**
     * Only callable from the system. This token grants a temporary permission to call
     * #startActivityAsCallerWithToken. The token will time out after
     * START_AS_CALLER_TOKEN_TIMEOUT if it is not used.
     *
     * @param delegatorToken The Binder token referencing the system Activity that wants to delegate
     *        the #startActivityAsCaller to another app. The "caller" will be the caller of this
     *        activity's token, not the delegate's caller (which is probably the delegator itself).
     *
     * @return Returns a token that can be given to a "delegate" app that may call
     *         #startActivityAsCaller
     */
    IBinder requestStartActivityPermissionToken(in IBinder delegatorToken);

    void releaseSomeActivities(in IApplicationThread app);
    Bitmap getTaskDescriptionIcon(in String filename, int userId);
    void startInPlaceAnimationOnFrontMostApplication(in Bundle opts);
    void registerTaskStackListener(in ITaskStackListener listener);
    void unregisterTaskStackListener(in ITaskStackListener listener);
    void setTaskResizeable(int taskId, int resizeableMode);
    void toggleFreeformWindowingMode(in IBinder token);
    void resizeTask(int taskId, in Rect bounds, int resizeMode);
    void moveStackToDisplay(int stackId, int displayId);
    void removeStack(int stackId);

    /**
     * Sets the windowing mode for a specific task. Only works on tasks of type
     * {@link WindowConfiguration#ACTIVITY_TYPE_STANDARD}
     * @param taskId The id of the task to set the windowing mode for.
     * @param windowingMode The windowing mode to set for the task.
     * @param toTop If the task should be moved to the top once the windowing mode changes.
     */
    void setTaskWindowingMode(int taskId, int windowingMode, boolean toTop);
    void moveTaskToStack(int taskId, int stackId, boolean toTop);
    /**
     * Resizes the input pinned stack to the given bounds with animation.
     *
     * @param stackId Id of the pinned stack to resize.
     * @param bounds Bounds to resize the stack to or {@code null} for fullscreen.
     * @param animationDuration The duration of the resize animation in milliseconds or -1 if the
     *                          default animation duration should be used.
     * @throws RemoteException
     */
    void animateResizePinnedStack(int stackId, in Rect bounds, int animationDuration);
    boolean setTaskWindowingModeSplitScreenPrimary(int taskId, int createMode, boolean toTop,
            boolean animate, in Rect initialBounds, boolean showRecents);
    /**
     * Use the offset to adjust the stack boundary with animation.
     *
     * @param stackId Id of the stack to adjust.
     * @param compareBounds Offset is only applied if the current pinned stack bounds is equal to
     *                      the compareBounds.
     * @param xOffset The horizontal offset.
     * @param yOffset The vertical offset.
     * @param animationDuration The duration of the resize animation in milliseconds or -1 if the
     *                          default animation duration should be used.
     * @throws RemoteException
     */
    void offsetPinnedStackBounds(int stackId, in Rect compareBounds, int xOffset, int yOffset,
            int animationDuration);
    /**
     * Removes stacks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeStacksInWindowingModes(in int[] windowingModes);
    /** Removes stack of the activity types from the system. */
    void removeStacksWithActivityTypes(in int[] activityTypes);

    List<ActivityManager.StackInfo> getAllStackInfos();
    ActivityManager.StackInfo getStackInfo(int windowingMode, int activityType);

    /**
     * Informs ActivityTaskManagerService that the keyguard is showing.
     *
     * @param showingKeyguard True if the keyguard is showing, false otherwise.
     * @param showingAod True if AOD is showing, false otherwise.
     */
    void setLockScreenShown(boolean showingKeyguard, boolean showingAod);
    Bundle getAssistContextExtras(int requestType);
    boolean launchAssistIntent(in Intent intent, int requestType, in String hint, int userHandle,
            in Bundle args);
    boolean requestAssistContextExtras(int requestType, in IAssistDataReceiver receiver,
            in Bundle receiverExtras, in IBinder activityToken,
            boolean focused, boolean newSessionId);
    boolean requestAutofillData(in IAssistDataReceiver receiver, in Bundle receiverExtras,
            in IBinder activityToken, int flags);
    boolean isAssistDataAllowedOnCurrentActivity();
    boolean showAssistFromActivity(in IBinder token, in Bundle args);
    boolean isRootVoiceInteraction(in IBinder token);
    oneway void showLockTaskEscapeMessage(in IBinder token);

    /**
     * Notify the system that the keyguard is going away.
     *
     * @param flags See
     *              {@link android.view.WindowManagerPolicyConstants#KEYGUARD_GOING_AWAY_FLAG_TO_SHADE}
     *              etc.
     */
    void keyguardGoingAway(int flags);
    ComponentName getActivityClassForToken(in IBinder token);
    String getPackageForToken(in IBinder token);

    /**
     * Try to place task to provided position. The final position might be different depending on
     * current user and stacks state. The task will be moved to target stack if it's currently in
     * different stack.
     */
    void positionTaskInStack(int taskId, int stackId, int position);
    void reportSizeConfigurations(in IBinder token, in int[] horizontalSizeConfiguration,
            in int[] verticalSizeConfigurations, in int[] smallestWidthConfigurations);
    /**
     * Dismisses split-screen multi-window mode.
     * {@param toTop} If true the current primary split-screen stack will be placed or left on top.
     */
    void dismissSplitScreenMode(boolean toTop);

    /**
     * Dismisses PiP
     * @param animate True if the dismissal should be animated.
     * @param animationDuration The duration of the resize animation in milliseconds or -1 if the
     *                          default animation duration should be used.
     */
    void dismissPip(boolean animate, int animationDuration);
    void suppressResizeConfigChanges(boolean suppress);
    void moveTasksToFullscreenStack(int fromStackId, boolean onTop);
    boolean moveTopActivityToPinnedStack(int stackId, in Rect bounds);
    boolean isInMultiWindowMode(in IBinder token);
    boolean isInPictureInPictureMode(in IBinder token);
    boolean enterPictureInPictureMode(in IBinder token, in PictureInPictureParams params);
    void setPictureInPictureParams(in IBinder token, in PictureInPictureParams params);
    int getMaxNumPictureInPictureActions(in IBinder token);
    IBinder getUriPermissionOwnerForActivity(in IBinder activityToken);

    /**
     * Resizes the docked stack, and all other stacks as the result of the dock stack bounds change.
     *
     * @param dockedBounds The bounds for the docked stack.
     * @param tempDockedTaskBounds The temporary bounds for the tasks in the docked stack, which
     *                             might be different from the stack bounds to allow more
     *                             flexibility while resizing, or {@code null} if they should be the
     *                             same as the stack bounds.
     * @param tempDockedTaskInsetBounds The temporary bounds for the tasks to calculate the insets.
     *                                  When resizing, we usually "freeze" the layout of a task. To
     *                                  achieve that, we also need to "freeze" the insets, which
     *                                  gets achieved by changing task bounds but not bounds used
     *                                  to calculate the insets in this transient state
     * @param tempOtherTaskBounds The temporary bounds for the tasks in all other stacks, or
     *                            {@code null} if they should be the same as the stack bounds.
     * @param tempOtherTaskInsetBounds Like {@code tempDockedTaskInsetBounds}, but for the other
     *                                 stacks.
     * @throws RemoteException
     */
    void resizeDockedStack(in Rect dockedBounds, in Rect tempDockedTaskBounds,
            in Rect tempDockedTaskInsetBounds,
            in Rect tempOtherTaskBounds, in Rect tempOtherTaskInsetBounds);

    /**
     * Sets whether we are currently in an interactive split screen resize operation where we
     * are changing the docked stack size.
     */
    void setSplitScreenResizing(boolean resizing);
    int setVrMode(in IBinder token, boolean enabled, in ComponentName packageName);
    void startLocalVoiceInteraction(in IBinder token, in Bundle options);
    void stopLocalVoiceInteraction(in IBinder token);
    boolean supportsLocalVoiceInteraction();
    void notifyPinnedStackAnimationStarted();
    void notifyPinnedStackAnimationEnded();

    // Get device configuration
    ConfigurationInfo getDeviceConfigurationInfo();

    /**
     * Resizes the pinned stack.
     *
     * @param pinnedBounds The bounds for the pinned stack.
     * @param tempPinnedTaskBounds The temporary bounds for the tasks in the pinned stack, which
     *                             might be different from the stack bounds to allow more
     *                             flexibility while resizing, or {@code null} if they should be the
     *                             same as the stack bounds.
     */
    void resizePinnedStack(in Rect pinnedBounds, in Rect tempPinnedTaskBounds);

    void dismissKeyguard(in IBinder token, in IKeyguardDismissCallback callback,
            in CharSequence message);

    /** Cancels the window transitions for the given task. */
    void cancelTaskWindowTransition(int taskId);

    /**
     * @param taskId the id of the task to retrieve the sAutoapshots for
     * @param reducedResolution if set, if the snapshot needs to be loaded from disk, this will load
     *                          a reduced resolution of it, which is much faster
     * @return a graphic buffer representing a screenshot of a task
     */
    ActivityManager.TaskSnapshot getTaskSnapshot(int taskId, boolean reducedResolution);

    /**
     * See {@link android.app.Activity#setDisablePreviewScreenshots}
     */
    void setDisablePreviewScreenshots(IBinder token, boolean disable);

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

    void setShowWhenLocked(in IBinder token, boolean showWhenLocked);
    void setInheritShowWhenLocked(in IBinder token, boolean setInheritShownWhenLocked);
    void setTurnScreenOn(in IBinder token, boolean turnScreenOn);

    /**
     * Registers remote animations for a specific activity.
     */
    void registerRemoteAnimations(in IBinder token, in RemoteAnimationDefinition definition);

    /**
     * Registers a remote animation to be run for all activity starts from a certain package during
     * a short predefined amount of time.
     */
    void registerRemoteAnimationForNextActivityStart(in String packageName,
           in RemoteAnimationAdapter adapter);

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
     * Makes the display with the given id a single task instance display. I.e the display can only
     * contain one task.
     */
    void setDisplayToSingleTaskInstance(int displayId);

    /**
     * Restarts the activity by killing its process if it is visible. If the activity is not
     * visible, the activity will not be restarted immediately and just keep the activity record in
     * the stack. It also resets the current override configuration so the activity will use the
     * configuration according to the latest state.
     *
     * @param activityToken The token of the target activity to restart.
     */
    void restartActivityProcessIfVisible(in IBinder activityToken);

    /**
     * Reports that an Activity received a back key press when there were no additional activities
     * on the back stack. If the Activity should be finished, the callback will be invoked. A
     * callback is used instead of finishing the activity directly from the server such that the
     * client may perform actions prior to finishing.
     */
    void onBackPressedOnTaskRoot(in IBinder activityToken, in IRequestFinishCallback callback);
}
