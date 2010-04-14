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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.graphics.Bitmap;
import android.os.Debug;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemProperties;
import android.text.TextUtils;
import java.util.List;

/**
 * Interact with the overall activities running in the system.
 */
public class ActivityManager {
    private static String TAG = "ActivityManager";
    private static boolean DEBUG = false;
    private static boolean localLOGV = DEBUG || android.util.Config.LOGV;

    private final Context mContext;
    private final Handler mHandler;

    /*package*/ ActivityManager(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
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
        String vmHeapSize = SystemProperties.get("dalvik.vm.heapsize", "16m");
        return Integer.parseInt(vmHeapSize.substring(0, vmHeapSize.length()-1));
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
        
        public RecentTaskInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(id);
            if (baseIntent != null) {
                dest.writeInt(1);
                baseIntent.writeToParcel(dest, 0);
            } else {
                dest.writeInt(0);
            }
            ComponentName.writeToParcel(origActivity, dest);
        }

        public void readFromParcel(Parcel source) {
            id = source.readInt();
            if (source.readInt() != 0) {
                baseIntent = Intent.CREATOR.createFromParcel(source);
            } else {
                baseIntent = null;
            }
            origActivity = ComponentName.readFromParcel(source);
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
     * @hide
     * TODO: Make this public.  Provides a list that does not contain any
     * recent tasks that currently are not available to the user.
     */
    public static final int RECENT_IGNORE_UNAVAILABLE = 0x0002;
    
    /**
     * Return a list of the tasks that the user has recently launched, with
     * the most recent being first and older ones after in order.
     * 
     * @param maxNum The maximum number of entries to return in the list.  The
     * actual number returned may be smaller, depending on how many tasks the
     * user has started and the maximum number the system can remember.
     * 
     * @return Returns a list of RecentTaskInfo records describing each of
     * the recent tasks.
     * 
     * @throws SecurityException Throws SecurityException if the caller does
     * not hold the {@link android.Manifest.permission#GET_TASKS} permission.
     */
    public List<RecentTaskInfo> getRecentTasks(int maxNum, int flags)
            throws SecurityException {
        try {
            return ActivityManagerNative.getDefault().getRecentTasks(maxNum,
                    flags);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
        }
    }

    /**
     * Information you can retrieve about a particular task that is currently
     * "running" in the system.  Note that a running task does not mean the
     * given task actual has a process it is actively running in; it simply
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
         * Thumbnail representation of the task's current state.
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
     * Return a list of the tasks that are currently running, with
     * the most recent being first and older ones after in order.  Note that
     * "running" does not mean any of the task's code is currently loaded or
     * activity -- the task may have been frozen by the system, so that it
     * can be restarted in its previous state when next brought to the
     * foreground.
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
    public List<RunningTaskInfo> getRunningTasks(int maxNum)
            throws SecurityException {
        try {
            return (List<RunningTaskInfo>)ActivityManagerNative.getDefault()
                    .getTasks(maxNum, 0, null);
        } catch (RemoteException e) {
            // System dead, we will be dead too soon!
            return null;
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
         * starting or binding to it.
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
         * explicit requests to start it or clients binding to it).
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
            return (List<RunningServiceInfo>)ActivityManagerNative.getDefault()
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
         * The total available memory on the system.  This number should not
         * be considered absolute: due to the nature of the kernel, a significant
         * portion of this memory is actually in use and needed for the overall
         * system to run well.
         */
        public long availMem;
        
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

        public MemoryInfo() {
        }

        public int describeContents() {
            return 0;
        }

        public void writeToParcel(Parcel dest, int flags) {
            dest.writeLong(availMem);
            dest.writeLong(threshold);
            dest.writeInt(lowMemory ? 1 : 0);
        }
        
        public void readFromParcel(Parcel source) {
            availMem = source.readLong();
            threshold = source.readLong();
            lowMemory = source.readInt() != 0;
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

    public void getMemoryInfo(MemoryInfo outInfo) {
        try {
            ActivityManagerNative.getDefault().getMemoryInfo(outInfo);
        } catch (RemoteException e) {
        }
    }
    
    /**
     * @hide
     */
    public boolean clearApplicationUserData(String packageName, IPackageDataObserver observer) {
        try {
            return ActivityManagerNative.getDefault().clearApplicationUserData(packageName, 
                    observer);
        } catch (RemoteException e) {
            return false;
        }
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

        public int describeContents() {
            return 0;
        }

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
        
        public String pkgList[];
        
        /**
         * Constant for {@link #importance}: this process is running the
         * foreground UI.
         */
        public static final int IMPORTANCE_FOREGROUND = 100;
        
        /**
         * Constant for {@link #importance}: this process is running something
         * that is considered to be actively visible to the user.
         */
        public static final int IMPORTANCE_VISIBLE = 200;
        
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
        
        public RunningAppProcessInfo() {
            importance = IMPORTANCE_FOREGROUND;
            importanceReasonCode = REASON_UNKNOWN;
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
            dest.writeInt(importance);
            dest.writeInt(lru);
            dest.writeInt(importanceReasonCode);
            dest.writeInt(importanceReasonPid);
            ComponentName.writeToParcel(importanceReasonComponent, dest);
        }

        public void readFromParcel(Parcel source) {
            processName = source.readString();
            pid = source.readInt();
            uid = source.readInt();
            pkgList = source.readStringArray();
            importance = source.readInt();
            lru = source.readInt();
            importanceReasonCode = source.readInt();
            importanceReasonPid = source.readInt();
            importanceReasonComponent = ComponentName.readFromParcel(source);
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
     * Return information about the memory usage of one or more processes.
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
            ActivityManagerNative.getDefault().killBackgroundProcesses(packageName);
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
            ActivityManagerNative.getDefault().forceStopPackage(packageName);
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
}
