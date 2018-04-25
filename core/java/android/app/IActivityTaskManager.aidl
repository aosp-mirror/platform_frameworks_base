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
    List<ActivityManager.RunningTaskInfo> getTasks(int maxNum);
    List<ActivityManager.RunningTaskInfo> getFilteredTasks(int maxNum, int ignoreActivityType,
            int ignoreWindowingMode);
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
     * Resizes the input stack id to the given bounds.
     *
     * @param stackId Id of the stack to resize.
     * @param bounds Bounds to resize the stack to or {@code null} for fullscreen.
     * @param allowResizeInDockedMode True if the resize should be allowed when the docked stack is
     *                                active.
     * @param preserveWindows True if the windows of activities contained in the stack should be
     *                        preserved.
     * @param animate True if the stack resize should be animated.
     * @param animationDuration The duration of the resize animation in milliseconds or -1 if the
     *                          default animation duration should be used.
     * @throws RemoteException
     */
    void resizeStack(int stackId, in Rect bounds, boolean allowResizeInDockedMode,
            boolean preserveWindows, boolean animate, int animationDuration);
    boolean setTaskWindowingModeSplitScreenPrimary(int taskId, int createMode, boolean toTop,
            boolean animate, in Rect initialBounds, boolean showRecents);


    /**
     * Removes stacks in the input windowing modes from the system if they are of activity type
     * ACTIVITY_TYPE_STANDARD or ACTIVITY_TYPE_UNDEFINED
     */
    void removeStacksInWindowingModes(in int[] windowingModes);
    /** Removes stack of the activity types from the system. */
    void removeStacksWithActivityTypes(in int[] activityTypes);
}
