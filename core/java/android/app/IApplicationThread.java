/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.content.IIntentReceiver;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Debug;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.IInterface;

import java.io.FileDescriptor;
import java.util.List;
import java.util.Map;

/**
 * System private API for communicating with the application.  This is given to
 * the activity manager by an application  when it starts up, for the activity
 * manager to tell the application about things it needs to do.
 *
 * {@hide}
 */
public interface IApplicationThread extends IInterface {
    void schedulePauseActivity(IBinder token, boolean finished, boolean userLeaving,
            int configChanges) throws RemoteException;
    void scheduleStopActivity(IBinder token, boolean showWindow,
            int configChanges) throws RemoteException;
    void scheduleWindowVisibility(IBinder token, boolean showWindow) throws RemoteException;
    void scheduleSleeping(IBinder token, boolean sleeping) throws RemoteException;
    void scheduleResumeActivity(IBinder token, boolean isForward) throws RemoteException;
    void scheduleSendResult(IBinder token, List<ResultInfo> results) throws RemoteException;
    void scheduleLaunchActivity(Intent intent, IBinder token, int ident,
            ActivityInfo info, Configuration curConfig, CompatibilityInfo compatInfo,
            Bundle state, List<ResultInfo> pendingResults,
    		List<Intent> pendingNewIntents, boolean notResumed, boolean isForward,
    		String profileName, ParcelFileDescriptor profileFd, boolean autoStopProfiler)
    		throws RemoteException;
    void scheduleRelaunchActivity(IBinder token, List<ResultInfo> pendingResults,
            List<Intent> pendingNewIntents, int configChanges,
            boolean notResumed, Configuration config) throws RemoteException;
    void scheduleNewIntent(List<Intent> intent, IBinder token) throws RemoteException;
    void scheduleDestroyActivity(IBinder token, boolean finished,
            int configChanges) throws RemoteException;
    void scheduleReceiver(Intent intent, ActivityInfo info, CompatibilityInfo compatInfo,
            int resultCode, String data, Bundle extras, boolean sync) throws RemoteException;
    static final int BACKUP_MODE_INCREMENTAL = 0;
    static final int BACKUP_MODE_FULL = 1;
    static final int BACKUP_MODE_RESTORE = 2;
    static final int BACKUP_MODE_RESTORE_FULL = 3;
    void scheduleCreateBackupAgent(ApplicationInfo app, CompatibilityInfo compatInfo,
            int backupMode) throws RemoteException;
    void scheduleDestroyBackupAgent(ApplicationInfo app, CompatibilityInfo compatInfo)
            throws RemoteException;
    void scheduleCreateService(IBinder token, ServiceInfo info,
            CompatibilityInfo compatInfo) throws RemoteException;
    void scheduleBindService(IBinder token,
            Intent intent, boolean rebind) throws RemoteException;
    void scheduleUnbindService(IBinder token,
            Intent intent) throws RemoteException;
    void scheduleServiceArgs(IBinder token, boolean taskRemoved, int startId,
            int flags, Intent args) throws RemoteException;
    void scheduleStopService(IBinder token) throws RemoteException;
    static final int DEBUG_OFF = 0;
    static final int DEBUG_ON = 1;
    static final int DEBUG_WAIT = 2;
    void bindApplication(String packageName, ApplicationInfo info, List<ProviderInfo> providers,
            ComponentName testName, String profileName, ParcelFileDescriptor profileFd,
            boolean autoStopProfiler, Bundle testArguments, IInstrumentationWatcher testWatcher,
            int debugMode, boolean restrictedBackupMode, boolean persistent,
            Configuration config, CompatibilityInfo compatInfo, Map<String, IBinder> services,
            Bundle coreSettings) throws RemoteException;
    void scheduleExit() throws RemoteException;
    void scheduleSuicide() throws RemoteException;
    void requestThumbnail(IBinder token) throws RemoteException;
    void scheduleConfigurationChanged(Configuration config) throws RemoteException;
    void updateTimeZone() throws RemoteException;
    void clearDnsCache() throws RemoteException;
    void setHttpProxy(String proxy, String port, String exclList) throws RemoteException;
    void processInBackground() throws RemoteException;
    void dumpService(FileDescriptor fd, IBinder servicetoken, String[] args)
            throws RemoteException;
    void dumpProvider(FileDescriptor fd, IBinder servicetoken, String[] args)
            throws RemoteException;
    void scheduleRegisteredReceiver(IIntentReceiver receiver, Intent intent,
            int resultCode, String data, Bundle extras, boolean ordered, boolean sticky)
            throws RemoteException;
    void scheduleLowMemory() throws RemoteException;
    void scheduleActivityConfigurationChanged(IBinder token) throws RemoteException;
    void profilerControl(boolean start, String path, ParcelFileDescriptor fd, int profileType)
            throws RemoteException;
    void dumpHeap(boolean managed, String path, ParcelFileDescriptor fd)
            throws RemoteException;
    void setSchedulingGroup(int group) throws RemoteException;
    void getMemoryInfo(Debug.MemoryInfo outInfo) throws RemoteException;
    static final int PACKAGE_REMOVED = 0;
    static final int EXTERNAL_STORAGE_UNAVAILABLE = 1;
    void dispatchPackageBroadcast(int cmd, String[] packages) throws RemoteException;
    void scheduleCrash(String msg) throws RemoteException;
    void dumpActivity(FileDescriptor fd, IBinder servicetoken, String prefix, String[] args)
            throws RemoteException;
    void setCoreSettings(Bundle coreSettings) throws RemoteException;
    void updatePackageCompatibilityInfo(String pkg, CompatibilityInfo info) throws RemoteException;
    void scheduleTrimMemory(int level) throws RemoteException;
    Debug.MemoryInfo dumpMemInfo(FileDescriptor fd, boolean checkin, boolean all,
            String[] args) throws RemoteException;
    void dumpGfxInfo(FileDescriptor fd, String[] args) throws RemoteException;

    String descriptor = "android.app.IApplicationThread";

    int SCHEDULE_PAUSE_ACTIVITY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION;
    int SCHEDULE_STOP_ACTIVITY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+2;
    int SCHEDULE_WINDOW_VISIBILITY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+3;
    int SCHEDULE_RESUME_ACTIVITY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+4;
    int SCHEDULE_SEND_RESULT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+5;
    int SCHEDULE_LAUNCH_ACTIVITY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+6;
    int SCHEDULE_NEW_INTENT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+7;
    int SCHEDULE_FINISH_ACTIVITY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+8;
    int SCHEDULE_RECEIVER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+9;
    int SCHEDULE_CREATE_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+10;
    int SCHEDULE_STOP_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+11;
    int BIND_APPLICATION_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+12;
    int SCHEDULE_EXIT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+13;
    int REQUEST_THUMBNAIL_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+14;
    int SCHEDULE_CONFIGURATION_CHANGED_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+15;
    int SCHEDULE_SERVICE_ARGS_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+16;
    int UPDATE_TIME_ZONE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+17;
    int PROCESS_IN_BACKGROUND_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+18;
    int SCHEDULE_BIND_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+19;
    int SCHEDULE_UNBIND_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+20;
    int DUMP_SERVICE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+21;
    int SCHEDULE_REGISTERED_RECEIVER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+22;
    int SCHEDULE_LOW_MEMORY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+23;
    int SCHEDULE_ACTIVITY_CONFIGURATION_CHANGED_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+24;
    int SCHEDULE_RELAUNCH_ACTIVITY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+25;
    int SCHEDULE_SLEEPING_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+26;
    int PROFILER_CONTROL_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+27;
    int SET_SCHEDULING_GROUP_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+28;
    int SCHEDULE_CREATE_BACKUP_AGENT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+29;
    int SCHEDULE_DESTROY_BACKUP_AGENT_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+30;
    int GET_MEMORY_INFO_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+31;
    int SCHEDULE_SUICIDE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+32;
    int DISPATCH_PACKAGE_BROADCAST_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+33;
    int SCHEDULE_CRASH_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+34;
    int DUMP_HEAP_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+35;
    int DUMP_ACTIVITY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+36;
    int CLEAR_DNS_CACHE_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+37;
    int SET_HTTP_PROXY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+38;
    int SET_CORE_SETTINGS_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+39;
    int UPDATE_PACKAGE_COMPATIBILITY_INFO_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+40;
    int SCHEDULE_TRIM_MEMORY_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+41;
    int DUMP_MEM_INFO_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+42;
    int DUMP_GFX_INFO_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+43;
    int DUMP_PROVIDER_TRANSACTION = IBinder.FIRST_CALL_TRANSACTION+44;
}
