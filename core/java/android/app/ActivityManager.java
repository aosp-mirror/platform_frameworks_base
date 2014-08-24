/*
 * Copyright (C) 2007 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Point;
import android.os.BatteryStats;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import com.android.internal.app.ProcessStats;
import com.android.internal.os.TransferPipe;
import com.android.internal.util.FastPrintWriter;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Slog;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Interact with the overall activities running in the system.
 */
public class ActivityManager {
    private static String TAG = "ActivityManager";
    private static boolean localLOGV = false;

    private final Context mContext;
    private final Handler mHandler;

    /**
     * <a href="{@docRoot}guide/topics/manifest/meta-data-element.html">{@code
     * &lt;meta-data>}</a> name for a 'home' Activity that declares a package that is to be
     * uninstalled in lieu of the declaring one.  The package named here must be
     * signed with the same certificate as the one declaring the {@code &lt;meta-data>}.
     */
    public static final String META_HOME_ALTERNATE = "android.app.home.alternate";

    /**
     * Result for IActivityManager.startActivity: trying to start an activity under voice
     * control when that activity does not support the VOICE category.
     * @hide
     */
    public static final int START_NOT_VOICE_COMPATIBLE = -7;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * start had to be canceled.
     * @hide
     */
    public static final int START_CANCELED = -6;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * thing being started is not an activity.
     * @hide
     */
    public static final int START_NOT_ACTIVITY = -5;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * caller does not have permission to start the activity.
     * @hide
     */
    public static final int START_PERMISSION_DENIED = -4;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * caller has requested both to forward a result and to receive
     * a result.
     * @hide
     */
    public static final int START_FORWARD_AND_REQUEST_CONFLICT = -3;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * requested class is not found.
     * @hide
     */
    public static final int START_CLASS_NOT_FOUND = -2;

    /**
     * Result for IActivityManager.startActivity: an error where the
     * given Intent could not be resolved to an activity.
     * @hide
     */
    public static final int START_INTENT_NOT_RESOLVED = -1;

    /**
     * Result for IActivityManaqer.startActivity: the activity was started
     * successfully as normal.
     * @hide
     */
    public static final int START_SUCCESS = 0;

    /**
     * Result for IActivityManaqer.startActivity: the caller asked that the Intent not
     * be executed if it is the recipient, and that is indeed the case.
     * @hide
     */
    public static final int START_RETURN_INTENT_TO_CALLER = 1;

    /**
     * Result for IActivityManaqer.startActivity: activity wasn't really started, but
     * a task was simply brought to the foreground.
     * @hide
     */
    public static final int START_TASK_TO_FRONT = 2;

    /**
     * Result for IActivityManaqer.startActivity: activity wasn't really started, but
     * the given Intent was given to the existing top activity.
     * @hide
     */
    public static final int START_DELIVERED_TO_TOP = 3;

    /**
     * Result for IActivityManaqer.startActivity: request was canceled because
     * app switches are temporarily canceled to ensure the user's last request
     * (such as pressing home) is performed.
     * @hide
     */
    public static final int START_SWITCHES_CANCELED = 4;

    /**
     * Result for IActivityManaqer.startActivity: a new activity was attempted to be started
     * while in Lock Task Mode.
     * @hide
     */
    public static final int START_RETURN_LOCK_TASK_MODE_VIOLATION = 5;

    /**
     * Flag for IActivityManaqer.startActivity: do special start mode where
     * a new activity is launched only if it is needed.
     * @hide
     */
    public static final int START_FLAG_ONLY_IF_NEEDED = 1<<0;

    /**
     * Flag for IActivityManaqer.startActivity: launch the app for
     * debugging.
     * @hide
     */
    public static final int START_FLAG_DEBUG = 1<<1;

    /**
     * Flag for IActivityManaqer.startActivity: launch the app for
     * OpenGL tracing.
     * @hide
     */
    public static final int START_FLAG_OPENGL_TRACES = 1<<2;

    /**
     * Flag for IActivityManaqer.startActivity: if the app is being
     * launched for profiling, automatically stop the profiler once done.
     * @hide
     */
    public static final int START_FLAG_AUTO_STOP_PROFILER = 1<<3;

    /**
     * Result for IActivityManaqer.broadcastIntent: success!
     * @hide
     */
    public static final int BROADCAST_SUCCESS = 0;

    /**
     * Result for IActivityManaqer.broadcastIntent: attempt to broadcast
     * a sticky intent without appropriate permission.
     * @hide
     */
    public static final int BROADCAST_STICKY_CANT_HAVE_PERMISSION = -1;

    /**
     * Type for IActivityManaqer.getIntentSender: this PendingIntent is
     * for a sendBroadcast operation.
     * @hide
     */
    public static final int INTENT_SENDER_BROADCAST = 1;

    /**
     * Type for IActivityManaqer.getIntentSender: this PendingIntent is
     * for a startActivity operation.
     * @hide
     */
    public static final int INTENT_SENDER_ACTIVITY = 2;

    /**
     * Type for IActivityManaqer.getIntentSender: this PendingIntent is
     * for an activity result operation.
     * @hide
     */
    public static final int INTENT_SENDER_ACTIVITY_RESULT = 3;

    /**
     * Type for IActivityManaqer.getIntentSender: this PendingIntent is
     * for a startService operation.
     * @hide
     */
    public static final int INTENT_SENDER_SERVICE = 4;

    /** @hide User operation call: success! */
    public static final int USER_OP_SUCCESS = 0;

    /** @hide User operation call: given user id is not known. */
    public static final int USER_OP_UNKNOWN_USER = -1;

    /** @hide User operation call: given user id is the current user, can't be stopped. */
    public static final int USER_OP_IS_CURRENT = -2;

    /** @hide Process is a persistent system process. */
    public static final int PROCESS_STATE_PERSISTENT = 0;

    /** @hide Process is a persistent system process and is doing UI. */
    public static final int PROCESS_STATE_PERSISTENT_UI = 1;

    /** @hide Process is hosting the current top activities.  Note that this covers
     * all activities that are visible to the user. */
    public static final int PROCESS_STATE_TOP = 2;

    /** @hide Process is important to the user, and something they are aware of. */
    public static final int PROCESS_STATE_IMPORTANT_FOREGROUND = 3;

    /** @hide Process is important to the user, but not something they are aware of. */
    public static final int PROCESS_STATE_IMPORTANT_BACKGROUND = 4;

    /** @hide Process is in the background running a backup/restore operation. */
    public static final int PROCESS_STATE_BACKUP = 5;

    /** @hide Process is in the background, but it can't restore its state so we want
     * to try to avoid killing it. */
    public static final int PROCESS_STATE_HEAVY_WEIGHT = 6;

    /** @hide Process is in the background running a service.  Unlike oom_adj, this level
     * is used for both the normal running in background state and the executing
     * operations state. */
    public static final int PROCESS_STATE_SERVICE = 7;

    /** @hide Process is in the background running a receiver.   Note that from the
     * perspective of oom_adj receivers run at a higher foreground level, but for our
     * prioritization here that is not necessary and putting them below services means
     * many fewer changes in some process states as they receive broadcasts. */
    public static final int PROCESS_STATE_RECEIVER = 8;

    /** @hide Process is in the background but hosts the home activity. */
    public static final int PROCESS_STATE_HOME = 9;

    /** @hide Process is in the background but hosts the last shown activity. */
    public static final int PROCESS_STATE_LAST_ACTIVITY = 10;

    /** @hide Process is being cached for later use and contains activities. */
    public static final int PROCESS_STATE_CACHED_ACTIVITY = 11;

    /** @hide Process is being cached for later use and is a client of another cached
     * process that contains activities. */
    public static final int PROCESS_STATE_CACHED_ACTIVITY_CLIENT = 12;

    /** @hide Process is being cached for later use and is empty. */
    public static final int PROCESS_STATE_CACHED_EMPTY = 13;

    Point mAppTaskThumbnailSize;

    /*package*/ ActivityManager(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    /**
     * Screen compatibility mode: the application most always run in
     * compatibility mode.
     * @hide
     */
    public static final int COMPAT_MODE_ALWAYS = -1;

    /**
     * Screen compatibility mode: the application can never run in
     * compatibility mode.
     * @hide
     */
    public static final int COMPAT_MODE_NEVER = -2;

    /**
     * Screen compatibility mode: unknown.
     * @hide
     */
    public static final int COMPAT_MODE_UNKNOWN = -3;

    /**
     * Screen compatibility mode: the application currently has compatibility
     * mode disabled.
     * @hide
     */
    public static final int COMPAT_MODE_DISABLED = 0;

    /**
     * Screen compatibility mode: the application currently has compatibility
     * mode enabled.
     * @hide
     */
    public static final int COMPAT_MODE_ENABLED = 1;

    /**
     * Screen compatibility mode: request to toggle the application's
     * compatibility mode.
     * @hide
     */
    public static final int COMPAT_MODE_TOGGLE = 2;

    /** @hide */
    public int getFrontActivityScreenCompatMode() {
        try {
            return ActivityManagerNative.getDefault().getFrontActivityScreenCompatMode();
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return 0;
        }
    }

    /** @hide */
    public void setFrontActivityScreenCompatMode(int mode) {
        try {
            ActivityManagerNative.getDefault().setFrontActivityScreenCompatMode(mode);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
        }
    }

    /** @hide */
    public int getPackageScreenCompatMode(String packageName) {
        try {
            return ActivityManagerNative.getDefault().getPackageScreenCompatMode(packageName);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return 0;
        }
    }

    /** @hide */
    public void setPackageScreenCompatMode(String packageName, int mode) {
        try {
            ActivityManagerNative.getDefault().setPackageScreenCompatMode(packageName, mode);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
        }
    }

    /** @hide */
    public boolean getPackageAskScreenCompat(String packageName) {
        try {
            return ActivityManagerNative.getDefault().getPackageAskScreenCompat(packageName);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return false;
        }
    }

    /** @hide */
    public void setPackageAskScreenCompat(String packageName, boolean ask) {
        try {
            ActivityManagerNative.getDefault().setPackageAskScreenCompat(packageName, ask);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
        }
    }

    /**
     * Return the approximate per-application memory class of the current
     * device.  This gives you an idea of how hard a memory limit you should
     * impose on your application to let the overall system work best.  The
     * returned value is in megabytes; the baseline Android memory class is
     * 16 (which happens to be the Java heap limit of those devices); some
     * device with more memory may return 24 or even higher numbers.
     */
    public int getMemoryClass() {
        return staticGetMemoryClass();
    }
    
    /** @hide */
    static public int staticGetMemoryClass() {
        // Really brain dead right now -- just take this from the configured
        // vm heap size, and assume it is in megabytes and thus ends with "m".
        String vmHeapSize = SystemProperties.get("dalvik.vm.heapgrowthlimit", "");
        if (vmHeapSize != null && !"".equals(vmHeapSize)) {
            return Integer.parseInt(vmHeapSize.substring(0, vmHeapSize.length()-1));
        }
        return staticGetLargeMemoryClass();
    }
    
    /**
     * Return the approximate per-application memory class of the current
     * device when an application is running with a large heap.  This is the
     * space available for memory-intensive applications; most applications
     * should not need this amount of memory, and should instead stay with the
     * {@link #getMemoryClass()} limit.  The returned value is in megabytes.
     * This may be the same size as {@link #getMemoryClass()} on memory
     * constrained devices, or it may be significantly larger on devices with
     * a large amount of available RAM.
     *
     * <p>The is the size of the application's Dalvik heap if it has
     * specified <code>android:largeHeap="true"</code> in its manifest.
     */
    public int getLargeMemoryClass() {
        return staticGetLargeMemoryClass();
    }
    
    /** @hide */
    static public int staticGetLargeMemoryClass() {
        // Really brain dead right now -- just take this from the configured
        // vm heap size, and assume it is in megabytes and thus ends with "m".
        String vmHeapSize = SystemProperties.get("dalvik.vm.heapsize", "16m");
        return Integer.parseInt(vmHeapSize.substring(0, vmHeapSize.length()-1));
    }

    /**
     * Returns true if this is a low-RAM device.  Exactly whether a device is low-RAM
     * is ultimately up to the device configuration, but currently it generally means
     * something in the class of a 512MB device with about a 800x480 or less screen.
     * This is mostly intended to be used by apps to determine whether they should turn
     * off certain features that require more RAM.
     */
    public boolean isLowRamDevice() {
        return isLowRamDeviceStatic();
    }

    /** @hide */
    public static boolean isLowRamDeviceStatic() {
        return "true".equals(SystemProperties.get("ro.config.low_ram", "false"));
    }

    /**
     * Used by persistent processes to determine if they are running on a
     * higher-end device so should be okay using hardware drawing acceleration
     * (which tends to consume a lot more RAM).
     * @hide
     */
    static public boolean isHighEndGfx() {
        return !isLowRamDeviceStatic() &&
                !Resources.getSystem().getBoolean(com.android.internal.R.bool.config_avoidGfxAccel);
    }

    /**
     * Information you can set and retrieve about the current activity within the recent task list.
     */
    public static class TaskDescription implements Parcelable {
        private String mLabel;
        private Bitmap mIcon;
        private int mColorPrimary;

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this task.
         * @param icon An icon that represents the current state of this task.
         * @param colorPrimary A color to override the theme's primary color.  This color must be opaque.
         */
        public TaskDescription(String label, Bitmap icon, int colorPrimary) {
            if ((colorPrimary != 0) && (Color.alpha(colorPrimary) != 255)) {
                throw new RuntimeException("A TaskDescription's primary color should be opaque");
            }

            mLabel = label;
            mIcon = icon;
            mColorPrimary = colorPrimary;
        }

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this activity.
         * @param icon An icon that represents the current state of this activity.
         */
        public TaskDescription(String label, Bitmap icon) {
            this(label, icon, 0);
        }

        /**
         * Creates the TaskDescription to the specified values.
         *
         * @param label A label and description of the current state of this activity.
         */
        public TaskDescription(String label) {
            this(label, null, 0);
        }

        /**
         * Creates an empty TaskDescription.
         */
        public TaskDescription() {
            this(null, null, 0);
        }

        /**
         * Creates a copy of another TaskDescription.
         */
        public TaskDescription(TaskDescription td) {
            this(td.getLabel(), td.getIcon(), td.getPrimaryColor());
        }

        private TaskDescription(Parcel source) {
            readFromParcel(source);
        }

        /**
         * Sets the label for this task description.
         * @hide
         */
        public void setLabel(String label) {
            mLabel = label;
        }

        /**
         * Sets the primary color for this task description.
         * @hide
         */
        public void setPrimaryColor(int primaryColor) {
            mColorPrimary = primaryColor;
        }

        /**
         * Sets the icon for this task description.
         * @hide
         */
        public void setIcon(Bitmap icon) {
            mIcon = icon;
        }

        /**
         * @return The label and description of the current state of this task.
         */
        public String getLabel() {
            return mLabel;
        }

        /**
         * @return The icon that represents the current state of this task.
         */
        public Bitmap getIcon() {
            return mIcon;
        }

        /**
         * @return The color override on the theme's primary color.
         */
        public int getPrimaryColor() {
            return mColorPrimary;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            if (mLabel == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                dest.writeString(mLabel);
            }
            if (mIcon == null) {
                dest.writeInt(0);
            } else {
                dest.writeInt(1);
                mIcon.writeToParcel(dest, 0);
            }
            dest.writeInt(mColorPrimary);
        }

        public void readFromParcel(Parcel source) {
            mLabel = source.readInt() > 0 ? source.readString() : null;
            mIcon = source.readInt() > 0 ? Bitmap.CREATOR.createFromParcel(source) : null;
            mColorPrimary = source.readInt();
        }

        public static final Creator<TaskDescription> CREATOR
                = new Creator<TaskDescription>() {
            public TaskDescription createFromParcel(Parcel source) {
                return new TaskDescription(source);
            }
            public TaskDescription[] newArray(int size) {
                return new TaskDescription[size];
            }
        };

        @Override
        public String toString() {
            return "TaskDescription Label: " + mLabel + " Icon: " + mIcon +
                    " colorPrimary: " + mColorPrimary;
        }
    }

    /**
     * Information you can retrieve about tasks that the user has most recently
     * started or visited.
     */
    public static class RecentTaskInfo implements Parcelable {
        /**
         * If this task is currently running, this is the identifier for it.
         * If it is not running, this will be -1.
         */
        public int id;

        /**
         * The true identifier of this task, valid even if it is not running.
         */
        public int persistentId;
        
        /**
         * The original Intent used to launch the task.  You can use this
         * Intent to re-launch the task (if it is no longer running) or bring
         * the current task to the front.
         */
        public Intent baseIntent;

        /**
         * If this task was started from an alias, this is the actual
         * activity component that was initially started; the component of
         * the baseIntent in this case is the name of the actual activity
         * implementation that the alias referred to.  Otherwise, this is null.
         */
        public ComponentName origActivity;

        /**
         * Description of the task's last state.
         */
        public CharSequence description;

        /**
         * The id of the ActivityStack this Task was on most recently.
         * @hide
         */
        public int stackId;

        /**
         * The id of the user the task was running as.
         * @hide
         */
        public int userId;

        /**
         * The first time this task was active.
         * @hide
         */
        public long firstActiveTime;

        /**
         * The last time this task was active.
         * @hide
         */
        public long lastActiveTime;

        /**
         * The recent activity values for the highest activity in the stack to have set the values.
         * {@link Activity#setTaskDescription(android.app.ActivityManager.TaskDescription)}.
         */
        public TaskDescription taskDescription;

        /**
         * Task affiliation for grouping with other tasks.
         */
        public int affiliatedTaskId;

        /**
         * Task affiliation color of the source task with the affiliated task id.
         *
         * @hide
         */
        public int affiliatedTaskColor;

        public RecentTaskInfo() {
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            dest.writeInt(persistentId);
            if (baseIntent != null) {
                dest.writeInt(1);
                baseIntent.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
            ComponentName.writeToParcel(origActivity, dest);
            TextUtils.writeToParcel(description, dest,
                    Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            if (taskDescription != null) {
                dest.writeInt(1);
                taskDescription.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
            dest.writeInt(stackId);
            dest.writeInt(userId);
            dest.writeLong(firstActiveTime);
            dest.writeLong(lastActiveTime);
            dest.writeInt(affiliatedTaskId);
            dest.writeInt(affiliatedTaskColor);
        }

        public void readFromParcel(Parcel source) {
            id = source.readInt();
            persistentId = source.readInt();
            baseIntent = source.readInt() > 0 ? Intent.CREATOR.createFromParcel(source) : null;
            origActivity = ComponentName.readFromParcel(source);
            description = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            taskDescription = source.readInt() > 0 ?
                    TaskDescription.CREATOR.createFromParcel(source) : null;
            stackId = source.readInt();
            userId = source.readInt();
            firstActiveTime = source.readLong();
            lastActiveTime = source.readLong();
            affiliatedTaskId = source.readInt();
            affiliatedTaskColor = source.readInt();
        }

        public static final Creator<RecentTaskInfo> CREATOR
                = new Creator<RecentTaskInfo>() {
            public RecentTaskInfo createFromParcel(Parcel source) {
                return new RecentTaskInfo(source);
            }
            public RecentTaskInfo[] newArray(int size) {
                return new RecentTaskInfo[size];
            }
        };

        private RecentTaskInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    /**
     * Flag for use with {@link #getRecentTasks}: return all tasks, even those
     * that have set their
     * {@link android.content.Intent#FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS} flag.
     */
    public static final int RECENT_WITH_EXCLUDED = 0x0001;

    /**
     * Provides a list that does not contain any
     * recent tasks that currently are not available to the user.
     */
    public static final int RECENT_IGNORE_UNAVAILABLE = 0x0002;

    /**
     * Provides a list that contains recent tasks for all
     * profiles of a user.
     * @hide
     */
    public static final int RECENT_INCLUDE_PROFILES = 0x0004;

    /**
     * <p></p>Return a list of the tasks that the user has recently launched, with
     * the most recent being first and older ones after in order.
     *
     * <p><b>Note: this method is only intended for debugging and presenting
     * task management user interfaces</b>.  This should never be used for
     * core logic in an application, such as deciding between different
     * behaviors based on the information found here.  Such uses are
     * <em>not</em> supported, and will likely break in the future.  For
     * example, if multiple applications can be actively running at the
     * same time, assumptions made about the meaning of the data here for
     * purposes of control flow will be incorrect.</p>
     *
     * @deprecated As of {@link android.os.Build.VERSION_CODES#L}, this method is
     * no longer available to third party applications: as the introduction of
     * document-centric recents means
     * it can leak personal information to the caller.  For backwards compatibility,
     * it will still return a small subset of its data: at least the caller's
     * own tasks (though see {@link #getAppTasks()} for the correct supported
     * way to retrieve that information), and possibly some other tasks
     * such as home that are known to not be sensitive.
     *
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many tasks the
     * user has started and the maximum number the system can remember.
     * @param flags Information about what to return.  May be any combination
     * of {@link #RECENT_WITH_EXCLUDED} and {@link #RECENT_IGNORE_UNAVAILABLE}.
     * 
     * @return Returns a list of RecentTaskInfo records describing each of
     * the recent tasks.
     * 
     * @throws SecurityException Throws SecurityException if the caller does
     * not hold the {@link android.Manifest.permission#GET_TASKS} permission.
     */
    @Deprecated
    public List<RecentTaskInfo> getRecentTasks(int maxNum, int flags)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().getRecentTasks(maxNum,
                    flags, UserHandle.myUserId());
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }

    /**
     * Same as {@link #getRecentTasks(int, int)} but returns the recent tasks for a
     * specific user. It requires holding
     * the {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} permission.
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many tasks the
     * user has started and the maximum number the system can remember.
     * @param flags Information about what to return.  May be any combination
     * of {@link #RECENT_WITH_EXCLUDED} and {@link #RECENT_IGNORE_UNAVAILABLE}.
     *
     * @return Returns a list of RecentTaskInfo records describing each of
     * the recent tasks.
     *
     * @throws SecurityException Throws SecurityException if the caller does
     * not hold the {@link android.Manifest.permission#GET_TASKS} or the
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} permissions.
     * @hide
     */
    public List<RecentTaskInfo> getRecentTasksForUser(int maxNum, int flags, int userId)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().getRecentTasks(maxNum,
                    flags, userId);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }

    /**
     * Information you can retrieve about a particular task that is currently
     * "running" in the system.  Note that a running task does not mean the
     * given task actually has a process it is actively running in; it simply
     * means that the user has gone to it and never closed it, but currently
     * the system may have killed its process and is only holding on to its
     * last state in order to restart it when the user returns.
     */
    public static class RunningTaskInfo implements Parcelable {
        /**
         * A unique identifier for this task.
         */
        public int id;

        /**
         * The component launched as the first activity in the task.  This can
         * be considered the "application" of this task.
         */
        public ComponentName baseActivity;

        /**
         * The activity component at the top of the history stack of the task.
         * This is what the user is currently doing.
         */
        public ComponentName topActivity;

        /**
         * Thumbnail representation of the task's current state.  Currently
         * always null.
         */
        public Bitmap thumbnail;

        /**
         * Description of the task's current state.
         */
        public CharSequence description;

        /**
         * Number of activities in this task.
         */
        public int numActivities;

        /**
         * Number of activities that are currently running (not stopped
         * and persisted) in this task.
         */
        public int numRunning;

        /**
         * Last time task was run. For sorting.
         * @hide
         */
        public long lastActiveTime;

        public RunningTaskInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            ComponentName.writeToParcel(baseActivity, dest);
            ComponentName.writeToParcel(topActivity, dest);
            if (thumbnail != null) {
                dest.writeInt(1);
                thumbnail.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
            TextUtils.writeToParcel(description, dest,
                    Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
            dest.writeInt(numActivities);
            dest.writeInt(numRunning);
        }

        public void readFromParcel(Parcel source) {
            id = source.readInt();
            baseActivity = ComponentName.readFromParcel(source);
            topActivity = ComponentName.readFromParcel(source);
            if (source.readInt() != 0) {
                thumbnail = Bitmap.CREATOR.createFromParcel(source);
            } else {
                thumbnail = null;
            }
            description = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
            numActivities = source.readInt();
            numRunning = source.readInt();
        }
        
        public static final Creator<RunningTaskInfo> CREATOR = new Creator<RunningTaskInfo>() {
            public RunningTaskInfo createFromParcel(Parcel source) {
                return new RunningTaskInfo(source);
            }
            public RunningTaskInfo[] newArray(int size) {
                return new RunningTaskInfo[size];
            }
        };

        private RunningTaskInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    /**
     * Get the list of tasks associated with the calling application.
     *
     * @return The list of tasks associated with the application making this call.
     * @throws SecurityException
     */
    public List<ActivityManager.AppTask> getAppTasks() {
        ArrayList<AppTask> tasks = new ArrayList<AppTask>();
        List<IAppTask> appTasks;
        try {
            appTasks = ActivityManagerNative.getDefault().getAppTasks(mContext.getPackageName());
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
        int numAppTasks = appTasks.size();
        for (int i = 0; i < numAppTasks; i++) {
            tasks.add(new AppTask(appTasks.get(i)));
        }
        return tasks;
    }

    /**
     * Return the current design width for {@link AppTask} thumbnails, for use
     * with {@link #addAppTask}.
     */
    public int getAppTaskThumbnailWidth() {
        synchronized (this) {
            ensureAppTaskThumbnailSizeLocked();
            return mAppTaskThumbnailSize.x;
        }
    }

    /**
     * Return the current design height for {@link AppTask} thumbnails, for use
     * with {@link #addAppTask}.
     */
    public int getAppTaskThumbnailHeight() {
        synchronized (this) {
            ensureAppTaskThumbnailSizeLocked();
            return mAppTaskThumbnailSize.y;
        }
    }

    private void ensureAppTaskThumbnailSizeLocked() {
        if (mAppTaskThumbnailSize == null) {
            try {
                mAppTaskThumbnailSize = ActivityManagerNative.getDefault().getAppTaskThumbnailSize();
            } catch (RemoteException e) {
                throw new IllegalStateException("System dead?", e);
            }
        }
    }

    /**
     * Add a new {@link AppTask} for the calling application.  This will create a new
     * recents entry that is added to the <b>end</b> of all existing recents.
     *
     * @param activity The activity that is adding the entry.   This is used to help determine
     * the context that the new recents entry will be in.
     * @param intent The Intent that describes the recents entry.  This is the same Intent that
     * you would have used to launch the activity for it.  In generally you will want to set
     * both {@link Intent#FLAG_ACTIVITY_NEW_DOCUMENT} and
     * {@link Intent#FLAG_ACTIVITY_RETAIN_IN_RECENTS}; the latter is required since this recents
     * entry will exist without an activity, so it doesn't make sense to not retain it when
     * its activity disappears.  The given Intent here also must have an explicit ComponentName
     * set on it.
     * @param description Optional additional description information.
     * @param thumbnail Thumbnail to use for the recents entry.  Should be the size given by
     * {@link #getAppTaskThumbnailWidth()} and {@link #getAppTaskThumbnailHeight()}.  If the
     * bitmap is not that exact size, it will be recreated in your process, probably in a way
     * you don't like, before the recents entry is added.
     *
     * @return Returns the task id of the newly added app task, or -1 if the add failed.  The
     * most likely cause of failure is that there is no more room for more tasks for your app.
     */
    public int addAppTask(@NonNull Activity activity, @NonNull Intent intent,
            @Nullable TaskDescription description, @NonNull Bitmap thumbnail) {
        Point size;
        synchronized (this) {
            ensureAppTaskThumbnailSizeLocked();
            size = mAppTaskThumbnailSize;
        }
        final int tw = thumbnail.getWidth();
        final int th = thumbnail.getHeight();
        if (tw != size.x || th != size.y) {
            Bitmap bm = Bitmap.createBitmap(size.x, size.y, thumbnail.getConfig());

            // Use ScaleType.CENTER_CROP, except we leave the top edge at the top.
            float scale;
            float dx = 0, dy = 0;
            if (tw * size.x > size.y * th) {
                scale = (float) size.x / (float) th;
                dx = (size.y - tw * scale) * 0.5f;
            } else {
                scale = (float) size.y / (float) tw;
                dy = (size.x - th * scale) * 0.5f;
            }
            Matrix matrix = new Matrix();
            matrix.setScale(scale, scale);
            matrix.postTranslate((int) (dx + 0.5f), 0);

            Canvas canvas = new Canvas(bm);
            canvas.drawBitmap(thumbnail, matrix, null);
            canvas.setBitmap(null);

            thumbnail = bm;
        }
        if (description == null) {
            description = new TaskDescription();
        }
        try {
            return ActivityManagerNative.getDefault().addAppTask(activity.getActivityToken(),
                    intent, description, thumbnail);
        } catch (RemoteException e) {
            throw new IllegalStateException("System dead?", e);
        }
    }

    /**
     * Return a list of the tasks that are currently running, with
     * the most recent being first and older ones after in order.  Note that
     * "running" does not mean any of the task's code is currently loaded or
     * activity -- the task may have been frozen by the system, so that it
     * can be restarted in its previous state when next brought to the
     * foreground.
     *
     * <p><b>Note: this method is only intended for debugging and presenting
     * task management user interfaces</b>.  This should never be used for
     * core logic in an application, such as deciding between different
     * behaviors based on the information found here.  Such uses are
     * <em>not</em> supported, and will likely break in the future.  For
     * example, if multiple applications can be actively running at the
     * same time, assumptions made about the meaning of the data here for
     * purposes of control flow will be incorrect.</p>
     *
     * @deprecated As of {@link android.os.Build.VERSION_CODES#L}, this method
     * is no longer available to third party
     * applications: the introduction of document-centric recents means
     * it can leak person information to the caller.  For backwards compatibility,
     * it will still retu rn a small subset of its data: at least the caller's
     * own tasks, and possibly some other tasks
     * such as home that are known to not be sensitive.
     *
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many tasks the
     * user has started.
     *
     * @return Returns a list of RunningTaskInfo records describing each of
     * the running tasks.
     *
     * @throws SecurityException Throws SecurityException if the caller does
     * not hold the {@link android.Manifest.permission#GET_TASKS} permission.
     */
    @Deprecated
    public List<RunningTaskInfo> getRunningTasks(int maxNum)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().getTasks(maxNum, 0);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }

    /**
     * If set, the process of the root activity of the task will be killed
     * as part of removing the task.
     * @hide
     */
    public static final int REMOVE_TASK_KILL_PROCESS = 0x0001;

    /**
     * Completely remove the given task.
     *
     * @param taskId Identifier of the task to be removed.
     * @param flags Additional operational flags.  May be 0 or
     * {@link #REMOVE_TASK_KILL_PROCESS}.
     * @return Returns true if the given task was found and removed.
     *
     * @hide
     */
    public boolean removeTask(int taskId, int flags)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().removeTask(taskId, flags);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return false;
        }
    }

    /** @hide */
    public static class TaskThumbnail implements Parcelable {
        public Bitmap mainThumbnail;
        public ParcelFileDescriptor thumbnailFileDescriptor;

        public TaskThumbnail() {
        }

        public int describeContents() {
            if (thumbnailFileDescriptor != null) {
                return thumbnailFileDescriptor.describeContents();
            }
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            if (mainThumbnail != null) {
                dest.writeInt(1);
                mainThumbnail.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
            if (thumbnailFileDescriptor != null) {
                dest.writeInt(1);
                thumbnailFileDescriptor.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
        }

        public void readFromParcel(Parcel source) {
            if (source.readInt() != 0) {
                mainThumbnail = Bitmap.CREATOR.createFromParcel(source);
            } else {
                mainThumbnail = null;
            }
            if (source.readInt() != 0) {
                thumbnailFileDescriptor = ParcelFileDescriptor.CREATOR.createFromParcel(source);
            } else {
                thumbnailFileDescriptor = null;
            }
        }

        public static final Creator<TaskThumbnail> CREATOR = new Creator<TaskThumbnail>() {
            public TaskThumbnail createFromParcel(Parcel source) {
                return new TaskThumbnail(source);
            }
            public TaskThumbnail[] newArray(int size) {
                return new TaskThumbnail[size];
            }
        };

        private TaskThumbnail(Parcel source) {
            readFromParcel(source);
        }
    }

    /** @hide */
    public TaskThumbnail getTaskThumbnail(int id) throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().getTaskThumbnail(id);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }

    /** @hide */
    public boolean isInHomeStack(int taskId) {
        try {
            return ActivityManagerNative.getDefault().isInHomeStack(taskId);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return false;
        }
    }

    /**
     * Flag for {@link #moveTaskToFront(int, int)}: also move the "home"
     * activity along with the task, so it is positioned immediately behind
     * the task.
     */
    public static final int MOVE_TASK_WITH_HOME = 0x00000001;

    /**
     * Flag for {@link #moveTaskToFront(int, int)}: don't count this as a
     * user-instigated action, so the current activity will not receive a
     * hint that the user is leaving.
     */
    public static final int MOVE_TASK_NO_USER_ACTION = 0x00000002;

    /**
     * Equivalent to calling {@link #moveTaskToFront(int, int, Bundle)}
     * with a null options argument.
     *
     * @param taskId The identifier of the task to be moved, as found in
     * {@link RunningTaskInfo} or {@link RecentTaskInfo}.
     * @param flags Additional operational flags, 0 or more of
     * {@link #MOVE_TASK_WITH_HOME}, {@link #MOVE_TASK_NO_USER_ACTION}.
     */
    public void moveTaskToFront(int taskId, int flags) {
        moveTaskToFront(taskId, flags, null);
    }

    /**
     * Ask that the task associated with a given task ID be moved to the
     * front of the stack, so it is now visible to the user.  Requires that
     * the caller hold permission {@link android.Manifest.permission#REORDER_TASKS}
     * or a SecurityException will be thrown.
     *
     * @param taskId The identifier of the task to be moved, as found in
     * {@link RunningTaskInfo} or {@link RecentTaskInfo}.
     * @param flags Additional operational flags, 0 or more of
     * {@link #MOVE_TASK_WITH_HOME}, {@link #MOVE_TASK_NO_USER_ACTION}.
     * @param options Additional options for the operation, either null or
     * as per {@link Context#startActivity(Intent, android.os.Bundle)
     * Context.startActivity(Intent, Bundle)}.
     */
    public void moveTaskToFront(int taskId, int flags, Bundle options) {
        try {
            ActivityManagerNative.getDefault().moveTaskToFront(taskId, flags, options);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
        }
    }

    /**
     * Information you can retrieve about a particular Service that is
     * currently running in the system.
     */
    public static class RunningServiceInfo implements Parcelable {
        /**
         * The service component.
         */
        public ComponentName service;

        /**
         * If non-zero, this is the process the service is running in.
         */
        public int pid;
        
        /**
         * The UID that owns this service.
         */
        public int uid;
        
        /**
         * The name of the process this service runs in.
         */
        public String process;
        
        /**
         * Set to true if the service has asked to run as a foreground process.
         */
        public boolean foreground;
        
        /**
         * The time when the service was first made active, either by someone
         * starting or binding to it.  This
         * is in units of {@link android.os.SystemClock#elapsedRealtime()}.
         */
        public long activeSince;
        
        /**
         * Set to true if this service has been explicitly started.
         */
        public boolean started;
        
        /**
         * Number of clients connected to the service.
         */
        public int clientCount;
        
        /**
         * Number of times the service's process has crashed while the service
         * is running.
         */
        public int crashCount;
        
        /**
         * The time when there was last activity in the service (either
         * explicit requests to start it or clients binding to it).  This
         * is in units of {@link android.os.SystemClock#uptimeMillis()}.
         */
        public long lastActivityTime;
        
        /**
         * If non-zero, this service is not currently running, but scheduled to
         * restart at the given time.
         */
        public long restarting;
        
        /**
         * Bit for {@link #flags}: set if this service has been
         * explicitly started.
         */
        public static final int FLAG_STARTED = 1<<0;
        
        /**
         * Bit for {@link #flags}: set if the service has asked to
         * run as a foreground process.
         */
        public static final int FLAG_FOREGROUND = 1<<1;
        
        /**
         * Bit for {@link #flags): set if the service is running in a
         * core system process.
         */
        public static final int FLAG_SYSTEM_PROCESS = 1<<2;
        
        /**
         * Bit for {@link #flags): set if the service is running in a
         * persistent process.
         */
        public static final int FLAG_PERSISTENT_PROCESS = 1<<3;
        
        /**
         * Running flags.
         */
        public int flags;
        
        /**
         * For special services that are bound to by system code, this is
         * the package that holds the binding.
         */
        public String clientPackage;
        
        /**
         * For special services that are bound to by system code, this is
         * a string resource providing a user-visible label for who the
         * client is.
         */
        public int clientLabel;
        
        public RunningServiceInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            ComponentName.writeToParcel(service, dest);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeString(process);
            dest.writeInt(foreground ? 1 : 0);
            dest.writeLong(activeSince);
            dest.writeInt(started ? 1 : 0);
            dest.writeInt(clientCount);
            dest.writeInt(crashCount);
            dest.writeLong(lastActivityTime);
            dest.writeLong(restarting);
            dest.writeInt(this.flags);
            dest.writeString(clientPackage);
            dest.writeInt(clientLabel);
        }

        public void readFromParcel(Parcel source) {
            service = ComponentName.readFromParcel(source);
            pid = source.readInt();
            uid = source.readInt();
            process = source.readString();
            foreground = source.readInt() != 0;
            activeSince = source.readLong();
            started = source.readInt() != 0;
            clientCount = source.readInt();
            crashCount = source.readInt();
            lastActivityTime = source.readLong();
            restarting = source.readLong();
            flags = source.readInt();
            clientPackage = source.readString();
            clientLabel = source.readInt();
        }
        
        public static final Creator<RunningServiceInfo> CREATOR = new Creator<RunningServiceInfo>() {
            public RunningServiceInfo createFromParcel(Parcel source) {
                return new RunningServiceInfo(source);
            }
            public RunningServiceInfo[] newArray(int size) {
                return new RunningServiceInfo[size];
            }
        };

        private RunningServiceInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    /**
     * Return a list of the services that are currently running.
     *
     * <p><b>Note: this method is only intended for debugging or implementing
     * service management type user interfaces.</b></p>
     *
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many services
     * are running.
     * 
     * @return Returns a list of RunningServiceInfo records describing each of
     * the running tasks.
     */
    public List<RunningServiceInfo> getRunningServices(int maxNum)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault()
                    .getServices(maxNum, 0);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }
    
    /**
     * Returns a PendingIntent you can start to show a control panel for the
     * given running service.  If the service does not have a control panel,
     * null is returned.
     */
    public PendingIntent getRunningServiceControlPanel(ComponentName service)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault()
                    .getRunningServiceControlPanel(service);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }
    
    /**
     * Information you can retrieve about the available memory through
     * {@link ActivityManager#getMemoryInfo}.
     */
    public static class MemoryInfo implements Parcelable {
        /**
         * The available memory on the system.  This number should not
         * be considered absolute: due to the nature of the kernel, a significant
         * portion of this memory is actually in use and needed for the overall
         * system to run well.
         */
        public long availMem;

        /**
         * The total memory accessible by the kernel.  This is basically the
         * RAM size of the device, not including below-kernel fixed allocations
         * like DMA buffers, RAM for the baseband CPU, etc.
         */
        public long totalMem;

        /**
         * The threshold of {@link #availMem} at which we consider memory to be
         * low and start killing background services and other non-extraneous
         * processes.
         */
        public long threshold;
        
        /**
         * Set to true if the system considers itself to currently be in a low
         * memory situation.
         */
        public boolean lowMemory;

        /** @hide */
        public long hiddenAppThreshold;
        /** @hide */
        public long secondaryServerThreshold;
        /** @hide */
        public long visibleAppThreshold;
        /** @hide */
        public long foregroundAppThreshold;

        public MemoryInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(availMem);
            dest.writeLong(totalMem);
            dest.writeLong(threshold);
            dest.writeInt(lowMemory ? 1 : 0);
            dest.writeLong(hiddenAppThreshold);
            dest.writeLong(secondaryServerThreshold);
            dest.writeLong(visibleAppThreshold);
            dest.writeLong(foregroundAppThreshold);
        }
        
        public void readFromParcel(Parcel source) {
            availMem = source.readLong();
            totalMem = source.readLong();
            threshold = source.readLong();
            lowMemory = source.readInt() != 0;
            hiddenAppThreshold = source.readLong();
            secondaryServerThreshold = source.readLong();
            visibleAppThreshold = source.readLong();
            foregroundAppThreshold = source.readLong();
        }

        public static final Creator<MemoryInfo> CREATOR
                = new Creator<MemoryInfo>() {
            public MemoryInfo createFromParcel(Parcel source) {
                return new MemoryInfo(source);
            }
            public MemoryInfo[] newArray(int size) {
                return new MemoryInfo[size];
            }
        };

        private MemoryInfo(Parcel source) {
            readFromParcel(source);
        }
    }

    /**
     * Return general information about the memory state of the system.  This
     * can be used to help decide how to manage your own memory, though note
     * that polling is not recommended and
     * {@link android.content.ComponentCallbacks2#onTrimMemory(int)
     * ComponentCallbacks2.onTrimMemory(int)} is the preferred way to do this.
     * Also see {@link #getMyMemoryState} for how to retrieve the current trim
     * level of your process as needed, which gives a better hint for how to
     * manage its memory.
     */
    public void getMemoryInfo(MemoryInfo outInfo) {
        try {
            ActivityManagerNative.getDefault().getMemoryInfo(outInfo);
        } catch (RemoteException e) {
        }
    }

    /**
     * Information you can retrieve about an ActivityStack in the system.
     * @hide
     */
    public static class StackInfo implements Parcelable {
        public int stackId;
        public Rect bounds = new Rect();
        public int[] taskIds;
        public String[] taskNames;
        public int displayId;

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(stackId);
            dest.writeInt(bounds.left);
            dest.writeInt(bounds.top);
            dest.writeInt(bounds.right);
            dest.writeInt(bounds.bottom);
            dest.writeIntArray(taskIds);
            dest.writeStringArray(taskNames);
            dest.writeInt(displayId);
        }

        public void readFromParcel(Parcel source) {
            stackId = source.readInt();
            bounds = new Rect(
                    source.readInt(), source.readInt(), source.readInt(), source.readInt());
            taskIds = source.createIntArray();
            taskNames = source.createStringArray();
            displayId = source.readInt();
        }

        public static final Creator<StackInfo> CREATOR = new Creator<StackInfo>() {
            @Override
            public StackInfo createFromParcel(Parcel source) {
                return new StackInfo(source);
            }
            @Override
            public StackInfo[] newArray(int size) {
                return new StackInfo[size];
            }
        };

        public StackInfo() {
        }

        private StackInfo(Parcel source) {
            readFromParcel(source);
        }

        public String toString(String prefix) {
            StringBuilder sb = new StringBuilder(256);
            sb.append(prefix); sb.append("Stack id="); sb.append(stackId);
                    sb.append(" bounds="); sb.append(bounds.toShortString());
                    sb.append(" displayId="); sb.append(displayId);
                    sb.append("\n");
            prefix = prefix + "  ";
            for (int i = 0; i < taskIds.length; ++i) {
                sb.append(prefix); sb.append("taskId="); sb.append(taskIds[i]);
                        sb.append(": "); sb.append(taskNames[i]); sb.append("\n");
            }
            return sb.toString();
        }

        @Override
        public String toString() {
            return toString("");
        }
    }

    /**
     * @hide
     */
    public boolean clearApplicationUserData(String packageName, IPackageDataObserver observer) {
        try {
            return ActivityManagerNative.getDefault().clearApplicationUserData(packageName, 
                    observer, UserHandle.myUserId());
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Permits an application to erase its own data from disk.  This is equivalent to
     * the user choosing to clear the app's data from within the device settings UI.  It
     * erases all dynamic data associated with the app -- its private data and data in its
     * private area on external storage -- but does not remove the installed application
     * itself, nor any OBB files.
     *
     * @return {@code true} if the application successfully requested that the application's
     *     data be erased; {@code false} otherwise.
     */
    public boolean clearApplicationUserData() {
        return clearApplicationUserData(mContext.getPackageName(), null);
    }

    /**
     * Information you can retrieve about any processes that are in an error condition.
     */
    public static class ProcessErrorStateInfo implements Parcelable {
        /**
         * Condition codes
         */
        public static final int NO_ERROR = 0;
        public static final int CRASHED = 1;
        public static final int NOT_RESPONDING = 2;

        /**
         * The condition that the process is in.
         */
        public int condition;

        /**
         * The process name in which the crash or error occurred.
         */
        public String processName;
        
        /**
         * The pid of this process; 0 if none
         */
        public int pid;

        /**
         * The kernel user-ID that has been assigned to this process;
         * currently this is not a unique ID (multiple applications can have
         * the same uid).
         */
        public int uid;
        
        /**
         * The activity name associated with the error, if known.  May be null.
         */
        public String tag;

        /**
         * A short message describing the error condition.
         */
        public String shortMsg;

        /**
         * A long message describing the error condition.
         */
        public String longMsg;

        /**
         * The stack trace where the error originated.  May be null.
         */
        public String stackTrace;

        /**
         * to be deprecated: This value will always be null.
         */
        public byte[] crashData = null;

        public ProcessErrorStateInfo() {
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(condition);
            dest.writeString(processName);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeString(tag);
            dest.writeString(shortMsg);
            dest.writeString(longMsg);
            dest.writeString(stackTrace);
        }

        public void readFromParcel(Parcel source) {
            condition = source.readInt();
            processName = source.readString();
            pid = source.readInt();
            uid = source.readInt();
            tag = source.readString();
            shortMsg = source.readString();
            longMsg = source.readString();
            stackTrace = source.readString();
        }
        
        public static final Creator<ProcessErrorStateInfo> CREATOR = 
                new Creator<ProcessErrorStateInfo>() {
            public ProcessErrorStateInfo createFromParcel(Parcel source) {
                return new ProcessErrorStateInfo(source);
            }
            public ProcessErrorStateInfo[] newArray(int size) {
                return new ProcessErrorStateInfo[size];
            }
        };

        private ProcessErrorStateInfo(Parcel source) {
            readFromParcel(source);
        }
    }
    
    /**
     * Returns a list of any processes that are currently in an error condition.  The result 
     * will be null if all processes are running properly at this time.
     * 
     * @return Returns a list of ProcessErrorStateInfo records, or null if there are no
     * current error conditions (it will not return an empty list).  This list ordering is not
     * specified.
     */
    public List<ProcessErrorStateInfo> getProcessesInErrorState() {
        try {
            return ActivityManagerNative.getDefault().getProcessesInErrorState();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Information you can retrieve about a running process.
     */
    public static class RunningAppProcessInfo implements Parcelable {        
        /**
         * The name of the process that this object is associated with
         */
        public String processName;

        /**
         * The pid of this process; 0 if none
         */
        public int pid;
        
        /**
         * The user id of this process.
         */
        public int uid;
        
        /**
         * All packages that have been loaded into the process.
         */
        public String pkgList[];
        
        /**
         * Constant for {@link #flags}: this is an app that is unable to
         * correctly save its state when going to the background,
         * so it can not be killed while in the background.
         * @hide
         */
        public static final int FLAG_CANT_SAVE_STATE = 1<<0;
        
        /**
         * Constant for {@link #flags}: this process is associated with a
         * persistent system app.
         * @hide
         */
        public static final int FLAG_PERSISTENT = 1<<1;

        /**
         * Constant for {@link #flags}: this process is associated with a
         * persistent system app.
         * @hide
         */
        public static final int FLAG_HAS_ACTIVITIES = 1<<2;

        /**
         * Flags of information.  May be any of
         * {@link #FLAG_CANT_SAVE_STATE}.
         * @hide
         */
        public int flags;

        /**
         * Last memory trim level reported to the process: corresponds to
         * the values supplied to {@link android.content.ComponentCallbacks2#onTrimMemory(int)
         * ComponentCallbacks2.onTrimMemory(int)}.
         */
        public int lastTrimLevel;

        /**
         * Constant for {@link #importance}: this process is running the
         * foreground UI.
         */
        public static final int IMPORTANCE_FOREGROUND = 100;
        
        /**
         * Constant for {@link #importance}: this process is running something
         * that is actively visible to the user, though not in the immediate
         * foreground.
         */
        public static final int IMPORTANCE_VISIBLE = 200;
        
        /**
         * Constant for {@link #importance}: this process is running something
         * that is considered to be actively perceptible to the user.  An
         * example would be an application performing background music playback.
         */
        public static final int IMPORTANCE_PERCEPTIBLE = 130;
        
        /**
         * Constant for {@link #importance}: this process is running an
         * application that can not save its state, and thus can't be killed
         * while in the background.
         * @hide
         */
        public static final int IMPORTANCE_CANT_SAVE_STATE = 170;
        
        /**
         * Constant for {@link #importance}: this process is contains services
         * that should remain running.
         */
        public static final int IMPORTANCE_SERVICE = 300;
        
        /**
         * Constant for {@link #importance}: this process process contains
         * background code that is expendable.
         */
        public static final int IMPORTANCE_BACKGROUND = 400;
        
        /**
         * Constant for {@link #importance}: this process is empty of any
         * actively running code.
         */
        public static final int IMPORTANCE_EMPTY = 500;

        /**
         * Constant for {@link #importance}: this process does not exist.
         */
        public static final int IMPORTANCE_GONE = 1000;

        /** @hide */
        public static int procStateToImportance(int procState) {
            if (procState >= ActivityManager.PROCESS_STATE_HOME) {
                return ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
            } else if (procState >= ActivityManager.PROCESS_STATE_SERVICE) {
                return ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE;
            } else if (procState > ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
                return ActivityManager.RunningAppProcessInfo.IMPORTANCE_CANT_SAVE_STATE;
            } else if (procState >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND) {
                return ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE;
            } else if (procState >= ActivityManager.PROCESS_STATE_IMPORTANT_FOREGROUND) {
                return ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
            } else {
                return ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            }
        }

        /**
         * The relative importance level that the system places on this
         * process.  May be one of {@link #IMPORTANCE_FOREGROUND},
         * {@link #IMPORTANCE_VISIBLE}, {@link #IMPORTANCE_SERVICE},
         * {@link #IMPORTANCE_BACKGROUND}, or {@link #IMPORTANCE_EMPTY}.  These
         * constants are numbered so that "more important" values are always
         * smaller than "less important" values.
         */
        public int importance;
        
        /**
         * An additional ordering within a particular {@link #importance}
         * category, providing finer-grained information about the relative
         * utility of processes within a category.  This number means nothing
         * except that a smaller values are more recently used (and thus
         * more important).  Currently an LRU value is only maintained for
         * the {@link #IMPORTANCE_BACKGROUND} category, though others may
         * be maintained in the future.
         */
        public int lru;

        /**
         * Constant for {@link #importanceReasonCode}: nothing special has
         * been specified for the reason for this level.
         */
        public static final int REASON_UNKNOWN = 0;
        
        /**
         * Constant for {@link #importanceReasonCode}: one of the application's
         * content providers is being used by another process.  The pid of
         * the client process is in {@link #importanceReasonPid} and the
         * target provider in this process is in
         * {@link #importanceReasonComponent}.
         */
        public static final int REASON_PROVIDER_IN_USE = 1;
        
        /**
         * Constant for {@link #importanceReasonCode}: one of the application's
         * content providers is being used by another process.  The pid of
         * the client process is in {@link #importanceReasonPid} and the
         * target provider in this process is in
         * {@link #importanceReasonComponent}.
         */
        public static final int REASON_SERVICE_IN_USE = 2;
        
        /**
         * The reason for {@link #importance}, if any.
         */
        public int importanceReasonCode;
        
        /**
         * For the specified values of {@link #importanceReasonCode}, this
         * is the process ID of the other process that is a client of this
         * process.  This will be 0 if no other process is using this one.
         */
        public int importanceReasonPid;
        
        /**
         * For the specified values of {@link #importanceReasonCode}, this
         * is the name of the component that is being used in this process.
         */
        public ComponentName importanceReasonComponent;
        
        /**
         * When {@link #importanceReasonPid} is non-0, this is the importance
         * of the other pid. @hide
         */
        public int importanceReasonImportance;

        /**
         * Current process state, as per PROCESS_STATE_* constants.
         * @hide
         */
        public int processState;

        public RunningAppProcessInfo() {
            importance = IMPORTANCE_FOREGROUND;
            importanceReasonCode = REASON_UNKNOWN;
            processState = PROCESS_STATE_IMPORTANT_FOREGROUND;
        }
        
        public RunningAppProcessInfo(String pProcessName, int pPid, String pArr[]) {
            processName = pProcessName;
            pid = pPid;
            pkgList = pArr;
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(processName);
            dest.writeInt(pid);
            dest.writeInt(uid);
            dest.writeStringArray(pkgList);
            dest.writeInt(this.flags);
            dest.writeInt(lastTrimLevel);
            dest.writeInt(importance);
            dest.writeInt(lru);
            dest.writeInt(importanceReasonCode);
            dest.writeInt(importanceReasonPid);
            ComponentName.writeToParcel(importanceReasonComponent, dest);
            dest.writeInt(importanceReasonImportance);
            dest.writeInt(processState);
        }

        public void readFromParcel(Parcel source) {
            processName = source.readString();
            pid = source.readInt();
            uid = source.readInt();
            pkgList = source.readStringArray();
            flags = source.readInt();
            lastTrimLevel = source.readInt();
            importance = source.readInt();
            lru = source.readInt();
            importanceReasonCode = source.readInt();
            importanceReasonPid = source.readInt();
            importanceReasonComponent = ComponentName.readFromParcel(source);
            importanceReasonImportance = source.readInt();
            processState = source.readInt();
        }

        public static final Creator<RunningAppProcessInfo> CREATOR = 
            new Creator<RunningAppProcessInfo>() {
            public RunningAppProcessInfo createFromParcel(Parcel source) {
                return new RunningAppProcessInfo(source);
            }
            public RunningAppProcessInfo[] newArray(int size) {
                return new RunningAppProcessInfo[size];
            }
        };

        private RunningAppProcessInfo(Parcel source) {
            readFromParcel(source);
        }
    }
    
    /**
     * Returns a list of application processes installed on external media
     * that are running on the device.
     *
     * <p><b>Note: this method is only intended for debugging or building
     * a user-facing process management UI.</b></p>
     *
     * @return Returns a list of ApplicationInfo records, or null if none
     * This list ordering is not specified.
     * @hide
     */
    public List<ApplicationInfo> getRunningExternalApplications() {
        try {
            return ActivityManagerNative.getDefault().getRunningExternalApplications();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Returns a list of application processes that are running on the device.
     *
     * <p><b>Note: this method is only intended for debugging or building
     * a user-facing process management UI.</b></p>
     *
     * @return Returns a list of RunningAppProcessInfo records, or null if there are no
     * running processes (it will not return an empty list).  This list ordering is not
     * specified.
     */
    public List<RunningAppProcessInfo> getRunningAppProcesses() {
        try {
            return ActivityManagerNative.getDefault().getRunningAppProcesses();
        } catch (RemoteException e) {
            return null;
        }
    }

    /**
     * Return global memory state information for the calling process.  This
     * does not fill in all fields of the {@link RunningAppProcessInfo}.  The
     * only fields that will be filled in are
     * {@link RunningAppProcessInfo#pid},
     * {@link RunningAppProcessInfo#uid},
     * {@link RunningAppProcessInfo#lastTrimLevel},
     * {@link RunningAppProcessInfo#importance},
     * {@link RunningAppProcessInfo#lru}, and
     * {@link RunningAppProcessInfo#importanceReasonCode}.
     */
    static public void getMyMemoryState(RunningAppProcessInfo outState) {
        try {
            ActivityManagerNative.getDefault().getMyMemoryState(outState);
        } catch (RemoteException e) {
        }
    }

    /**
     * Return information about the memory usage of one or more processes.
     *
     * <p><b>Note: this method is only intended for debugging or building
     * a user-facing process management UI.</b></p>
     *
     * @param pids The pids of the processes whose memory usage is to be
     * retrieved.
     * @return Returns an array of memory information, one for each
     * requested pid.
     */
    public Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids) {
        try {
            return ActivityManagerNative.getDefault().getProcessMemoryInfo(pids);
        } catch (RemoteException e) {
            return null;
        }
    }
    
    /**
     * @deprecated This is now just a wrapper for
     * {@link #killBackgroundProcesses(String)}; the previous behavior here
     * is no longer available to applications because it allows them to
     * break other applications by removing their alarms, stopping their
     * services, etc.
     */
    @Deprecated
    public void restartPackage(String packageName) {
        killBackgroundProcesses(packageName);
    }
    
    /**
     * Have the system immediately kill all background processes associated
     * with the given package.  This is the same as the kernel killing those
     * processes to reclaim memory; the system will take care of restarting
     * these processes in the future as needed.
     * 
     * <p>You must hold the permission
     * {@link android.Manifest.permission#KILL_BACKGROUND_PROCESSES} to be able to
     * call this method.
     * 
     * @param packageName The name of the package whose processes are to
     * be killed.
     */
    public void killBackgroundProcesses(String packageName) {
        try {
            ActivityManagerNative.getDefault().killBackgroundProcesses(packageName,
                    UserHandle.myUserId());
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Have the system perform a force stop of everything associated with
     * the given application package.  All processes that share its uid
     * will be killed, all services it has running stopped, all activities
     * removed, etc.  In addition, a {@link Intent#ACTION_PACKAGE_RESTARTED}
     * broadcast will be sent, so that any of its registered alarms can
     * be stopped, notifications removed, etc.
     * 
     * <p>You must hold the permission
     * {@link android.Manifest.permission#FORCE_STOP_PACKAGES} to be able to
     * call this method.
     * 
     * @param packageName The name of the package to be stopped.
     * 
     * @hide This is not available to third party applications due to
     * it allowing them to break other applications by stopping their
     * services, removing their alarms, etc.
     */
    public void forceStopPackage(String packageName) {
        try {
            ActivityManagerNative.getDefault().forceStopPackage(packageName,
                    UserHandle.myUserId());
        } catch (RemoteException e) {
        }
    }
    
    /**
     * Get the device configuration attributes.
     */
    public ConfigurationInfo getDeviceConfigurationInfo() {
        try {
            return ActivityManagerNative.getDefault().getDeviceConfigurationInfo();
        } catch (RemoteException e) {
        }
        return null;
    }

    /**
     * Get the preferred density of icons for the launcher. This is used when
     * custom drawables are created (e.g., for shortcuts).
     *
     * @return density in terms of DPI
     */
    public int getLauncherLargeIconDensity() {
        final Resources res = mContext.getResources();
        final int density = res.getDisplayMetrics().densityDpi;
        final int sw = res.getConfiguration().smallestScreenWidthDp;

        if (sw < 600) {
            // Smaller than approx 7" tablets, use the regular icon size.
            return density;
        }

        switch (density) {
            case DisplayMetrics.DENSITY_LOW:
                return DisplayMetrics.DENSITY_MEDIUM;
            case DisplayMetrics.DENSITY_MEDIUM:
                return DisplayMetrics.DENSITY_HIGH;
            case DisplayMetrics.DENSITY_TV:
                return DisplayMetrics.DENSITY_XHIGH;
            case DisplayMetrics.DENSITY_HIGH:
                return DisplayMetrics.DENSITY_XHIGH;
            case DisplayMetrics.DENSITY_XHIGH:
                return DisplayMetrics.DENSITY_XXHIGH;
            case DisplayMetrics.DENSITY_XXHIGH:
                return DisplayMetrics.DENSITY_XHIGH * 2;
            default:
                // The density is some abnormal value.  Return some other
                // abnormal value that is a reasonable scaling of it.
                return (int)((density*1.5f)+.5f);
        }
    }

    /**
     * Get the preferred launcher icon size. This is used when custom drawables
     * are created (e.g., for shortcuts).
     *
     * @return dimensions of square icons in terms of pixels
     */
    public int getLauncherLargeIconSize() {
        return getLauncherLargeIconSizeInner(mContext);
    }

    static int getLauncherLargeIconSizeInner(Context context) {
        final Resources res = context.getResources();
        final int size = res.getDimensionPixelSize(android.R.dimen.app_icon_size);
        final int sw = res.getConfiguration().smallestScreenWidthDp;

        if (sw < 600) {
            // Smaller than approx 7" tablets, use the regular icon size.
            return size;
        }

        final int density = res.getDisplayMetrics().densityDpi;

        switch (density) {
            case DisplayMetrics.DENSITY_LOW:
                return (size * DisplayMetrics.DENSITY_MEDIUM) / DisplayMetrics.DENSITY_LOW;
            case DisplayMetrics.DENSITY_MEDIUM:
                return (size * DisplayMetrics.DENSITY_HIGH) / DisplayMetrics.DENSITY_MEDIUM;
            case DisplayMetrics.DENSITY_TV:
                return (size * DisplayMetrics.DENSITY_XHIGH) / DisplayMetrics.DENSITY_HIGH;
            case DisplayMetrics.DENSITY_HIGH:
                return (size * DisplayMetrics.DENSITY_XHIGH) / DisplayMetrics.DENSITY_HIGH;
            case DisplayMetrics.DENSITY_XHIGH:
                return (size * DisplayMetrics.DENSITY_XXHIGH) / DisplayMetrics.DENSITY_XHIGH;
            case DisplayMetrics.DENSITY_XXHIGH:
                return (size * DisplayMetrics.DENSITY_XHIGH*2) / DisplayMetrics.DENSITY_XXHIGH;
            default:
                // The density is some abnormal value.  Return some other
                // abnormal value that is a reasonable scaling of it.
                return (int)((size*1.5f) + .5f);
        }
    }

    /**
     * Returns "true" if the user interface is currently being messed with
     * by a monkey.
     */
    public static boolean isUserAMonkey() {
        try {
            return ActivityManagerNative.getDefault().isUserAMonkey();
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Returns "true" if device is running in a test harness.
     */
    public static boolean isRunningInTestHarness() {
        return SystemProperties.getBoolean("ro.test_harness", false);
    }

    /**
     * Returns the launch count of each installed package.
     *
     * @hide
     */
    /*public Map<String, Integer> getAllPackageLaunchCounts() {
        try {
            IUsageStats usageStatsService = IUsageStats.Stub.asInterface(
                    ServiceManager.getService("usagestats"));
            if (usageStatsService == null) {
                return new HashMap<String, Integer>();
            }

            UsageStats.PackageStats[] allPkgUsageStats = usageStatsService.getAllPkgUsageStats(
                    ActivityThread.currentPackageName());
            if (allPkgUsageStats == null) {
                return new HashMap<String, Integer>();
            }

            Map<String, Integer> launchCounts = new HashMap<String, Integer>();
            for (UsageStats.PackageStats pkgUsageStats : allPkgUsageStats) {
                launchCounts.put(pkgUsageStats.getPackageName(), pkgUsageStats.getLaunchCount());
            }

            return launchCounts;
        } catch (RemoteException e) {
            Log.w(TAG, "Could not query launch counts", e);
            return new HashMap<String, Integer>();
        }
    }*/

    /** @hide */
    public static int checkComponentPermission(String permission, int uid,
            int owningUid, boolean exported) {
        // Root, system server get to do everything.
        if (uid == 0 || uid == Process.SYSTEM_UID) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // Isolated processes don't get any permissions.
        if (UserHandle.isIsolated(uid)) {
            return PackageManager.PERMISSION_DENIED;
        }
        // If there is a uid that owns whatever is being accessed, it has
        // blanket access to it regardless of the permissions it requires.
        if (owningUid >= 0 && UserHandle.isSameApp(uid, owningUid)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // If the target is not exported, then nobody else can get to it.
        if (!exported) {
            /*
            RuntimeException here = new RuntimeException("here");
            here.fillInStackTrace();
            Slog.w(TAG, "Permission denied: checkComponentPermission() owningUid=" + owningUid,
                    here);
            */
            return PackageManager.PERMISSION_DENIED;
        }
        if (permission == null) {
            return PackageManager.PERMISSION_GRANTED;
        }
        try {
            return AppGlobals.getPackageManager()
                    .checkUidPermission(permission, uid);
        } catch (RemoteException e) {
            // Should never happen, but if it does... deny!
            Slog.e(TAG, "PackageManager is dead?!?", e);
        }
        return PackageManager.PERMISSION_DENIED;
    }

    /** @hide */
    public static int checkUidPermission(String permission, int uid) {
        try {
            return AppGlobals.getPackageManager()
                    .checkUidPermission(permission, uid);
        } catch (RemoteException e) {
            // Should never happen, but if it does... deny!
            Slog.e(TAG, "PackageManager is dead?!?", e);
        }
        return PackageManager.PERMISSION_DENIED;
    }

    /**
     * @hide
     * Helper for dealing with incoming user arguments to system service calls.
     * Takes care of checking permissions and converting USER_CURRENT to the
     * actual current user.
     *
     * @param callingPid The pid of the incoming call, as per Binder.getCallingPid().
     * @param callingUid The uid of the incoming call, as per Binder.getCallingUid().
     * @param userId The user id argument supplied by the caller -- this is the user
     * they want to run as.
     * @param allowAll If true, we will allow USER_ALL.  This means you must be prepared
     * to get a USER_ALL returned and deal with it correctly.  If false,
     * an exception will be thrown if USER_ALL is supplied.
     * @param requireFull If true, the caller must hold
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL} to be able to run as a
     * different user than their current process; otherwise they must hold
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS}.
     * @param name Optional textual name of the incoming call; only for generating error messages.
     * @param callerPackage Optional package name of caller; only for error messages.
     *
     * @return Returns the user ID that the call should run as.  Will always be a concrete
     * user number, unless <var>allowAll</var> is true in which case it could also be
     * USER_ALL.
     */
    public static int handleIncomingUser(int callingPid, int callingUid, int userId,
            boolean allowAll, boolean requireFull, String name, String callerPackage) {
        if (UserHandle.getUserId(callingUid) == userId) {
            return userId;
        }
        try {
            return ActivityManagerNative.getDefault().handleIncomingUser(callingPid,
                    callingUid, userId, allowAll, requireFull, name, callerPackage);
        } catch (RemoteException e) {
            throw new SecurityException("Failed calling activity manager", e);
        }
    }

    /** @hide */
    public static int getCurrentUser() {
        UserInfo ui;
        try {
            ui = ActivityManagerNative.getDefault().getCurrentUser();
            return ui != null ? ui.id : 0;
        } catch (RemoteException e) {
            return 0;
        }
    }

    /**
     * @param userid the user's id. Zero indicates the default user 
     * @hide
     */
    public boolean switchUser(int userid) {
        try {
            return ActivityManagerNative.getDefault().switchUser(userid);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Return whether the given user is actively running.  This means that
     * the user is in the "started" state, not "stopped" -- it is currently
     * allowed to run code through scheduled alarms, receiving broadcasts,
     * etc.  A started user may be either the current foreground user or a
     * background user; the result here does not distinguish between the two.
     * @param userid the user's id. Zero indicates the default user.
     * @hide
     */
    public boolean isUserRunning(int userid) {
        try {
            return ActivityManagerNative.getDefault().isUserRunning(userid, false);
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * Perform a system dump of various state associated with the given application
     * package name.  This call blocks while the dump is being performed, so should
     * not be done on a UI thread.  The data will be written to the given file
     * descriptor as text.  An application must hold the
     * {@link android.Manifest.permission#DUMP} permission to make this call.
     * @param fd The file descriptor that the dump should be written to.  The file
     * descriptor is <em>not</em> closed by this function; the caller continues to
     * own it.
     * @param packageName The name of the package that is to be dumped.
     */
    public void dumpPackageState(FileDescriptor fd, String packageName) {
        dumpPackageStateStatic(fd, packageName);
    }

    /**
     * @hide
     */
    public static void dumpPackageStateStatic(FileDescriptor fd, String packageName) {
        FileOutputStream fout = new FileOutputStream(fd);
        PrintWriter pw = new FastPrintWriter(fout);
        dumpService(pw, fd, Context.ACTIVITY_SERVICE, new String[] {
                "-a", "package", packageName });
        pw.println();
        dumpService(pw, fd, "meminfo", new String[] { "--local", packageName });
        pw.println();
        dumpService(pw, fd, ProcessStats.SERVICE_NAME, new String[] { "-a", packageName });
        pw.println();
        dumpService(pw, fd, "usagestats", new String[] { "--packages", packageName });
        pw.println();
        dumpService(pw, fd, "package", new String[] { packageName });
        pw.println();
        dumpService(pw, fd, BatteryStats.SERVICE_NAME, new String[] { packageName });
        pw.flush();
    }

    private static void dumpService(PrintWriter pw, FileDescriptor fd, String name, String[] args) {
        pw.print("DUMP OF SERVICE "); pw.print(name); pw.println(":");
        IBinder service = ServiceManager.checkService(name);
        if (service == null) {
            pw.println("  (Service not found)");
            return;
        }
        TransferPipe tp = null;
        try {
            pw.flush();
            tp = new TransferPipe();
            tp.setBufferPrefix("  ");
            service.dumpAsync(tp.getWriteFd().getFileDescriptor(), args);
            tp.go(fd);
        } catch (Throwable e) {
            if (tp != null) {
                tp.kill();
            }
            pw.println("Failure dumping service:");
            e.printStackTrace(pw);
        }
    }

    /**
     * @hide
     */
    public void startLockTaskMode(int taskId) {
        try {
            ActivityManagerNative.getDefault().startLockTaskMode(taskId);
        } catch (RemoteException e) {
        }
    }

    /**
     * @hide
     */
    public void stopLockTaskMode() {
        try {
            ActivityManagerNative.getDefault().stopLockTaskMode();
        } catch (RemoteException e) {
        }
    }

    /**
     * Return whether currently in lock task mode.  When in this mode
     * no new tasks can be created or switched to.
     *
     * @see Activity#startLockTask()
     */
    public boolean isInLockTaskMode() {
        try {
            return ActivityManagerNative.getDefault().isInLockTaskMode();
        } catch (RemoteException e) {
            return false;
        }
    }

    /**
     * The AppTask allows you to manage your own application's tasks.
     * See {@link android.app.ActivityManager#getAppTasks()}
     */
    public static class AppTask {
        private IAppTask mAppTaskImpl;

        /** @hide */
        public AppTask(IAppTask task) {
            mAppTaskImpl = task;
        }

        /**
         * Finishes all activities in this task and removes it from the recent tasks list.
         */
        public void finishAndRemoveTask() {
            try {
                mAppTaskImpl.finishAndRemoveTask();
            } catch (RemoteException e) {
                Slog.e(TAG, "Invalid AppTask", e);
            }
        }

        /**
         * Get the RecentTaskInfo associated with this task.
         *
         * @return The RecentTaskInfo for this task, or null if the task no longer exists.
         */
        public RecentTaskInfo getTaskInfo() {
            try {
                return mAppTaskImpl.getTaskInfo();
            } catch (RemoteException e) {
                Slog.e(TAG, "Invalid AppTask", e);
                return null;
            }
        }

        /**
         * Bring this task to the foreground.  If it contains activities, they will be
         * brought to the foreground with it and their instances re-created if needed.
         * If it doesn't contain activities, the root activity of the task will be
         * re-launched.
         */
        public void moveToFront() {
            try {
                mAppTaskImpl.moveToFront();
            } catch (RemoteException e) {
                Slog.e(TAG, "Invalid AppTask", e);
            }
        }

        /**
         * Start an activity in this task.  Brings the task to the foreground.  If this task
         * is not currently active (that is, its id < 0), then the activity being started
         * needs to be started as a new task and the Intent's ComponentName must match the
         * base ComponenentName of the recent task entry.  Otherwise, the activity being
         * started must <b>not</b> be launched as a new task -- not through explicit intent
         * flags nor implicitly as the singleTask or singleInstance launch modes.
         *
         * <p>See {@link Activity#startActivity(android.content.Intent, android.os.Bundle)
         * Activity.startActivity} for more information.</p>
         *
         * @param intent The Intent describing the new activity to be launched on the task.
         * @param options Optional launch options.
         */
        public void startActivity(Context context, Intent intent, Bundle options) {
            ActivityThread thread = ActivityThread.currentActivityThread();
            thread.getInstrumentation().execStartActivityFromAppTask(context,
                    thread.getApplicationThread(), mAppTaskImpl, intent, options);
        }

        /**
         * Modify the {@link Intent#FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS} flag in the root
         * Intent of this AppTask.
         *
         * @param exclude If true, {@link Intent#FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS} will
         * be set; otherwise, it will be cleared.
         */
        public void setExcludeFromRecents(boolean exclude) {
            try {
                mAppTaskImpl.setExcludeFromRecents(exclude);
            } catch (RemoteException e) {
                Slog.e(TAG, "Invalid AppTask", e);
            }
        }
    }
}
