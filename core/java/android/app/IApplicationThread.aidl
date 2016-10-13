/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.app.IInstrumentationWatcher;
import android.app.IUiAutomationConnection;
import android.app.ProfilerInfo;
import android.app.ResultInfo;
import android.content.ComponentName;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.os.IInterface;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;

import com.android.internal.app.IVoiceInteractor;
import com.android.internal.content.ReferrerIntent;

import java.util.List;
import java.util.Map;

/**
 * System private API for communicating with the application.  This is given to
 * the activity manager by an application  when it starts up, for the activity
 * manager to tell the application about things it needs to do.
 *
 * {@hide}
 */
oneway interface IApplicationThread {
    /**
     * Don't change the existing transaction Ids as they could be used in the native code.
     * When adding a new method, assign the next available transaction id.
     */
    void schedulePauseActivity(IBinder token, boolean finished, boolean userLeaving,
            int configChanges, boolean dontReport) = 1;
    void scheduleStopActivity(IBinder token, boolean showWindow,
            int configChanges) = 3;
    void scheduleWindowVisibility(IBinder token, boolean showWindow) = 4;
    void scheduleResumeActivity(IBinder token, int procState, boolean isForward,
            in Bundle resumeArgs) = 5;
    void scheduleSendResult(IBinder token, in List<ResultInfo> results) = 6;
    void scheduleLaunchActivity(in Intent intent, IBinder token, int ident,
            in ActivityInfo info, in Configuration curConfig, in Configuration overrideConfig,
            in CompatibilityInfo compatInfo, in String referrer, IVoiceInteractor voiceInteractor,
            int procState, in Bundle state, in PersistableBundle persistentState,
            in List<ResultInfo> pendingResults, in List<ReferrerIntent> pendingNewIntents,
            boolean notResumed, boolean isForward, in ProfilerInfo profilerInfo) = 7;
    void scheduleNewIntent(
            in List<ReferrerIntent> intent, IBinder token, boolean andPause) = 8;
    void scheduleDestroyActivity(IBinder token, boolean finished,
            int configChanges) = 9;
    void scheduleReceiver(in Intent intent, in ActivityInfo info,
            in CompatibilityInfo compatInfo,
            int resultCode, in String data, in Bundle extras, boolean sync,
            int sendingUser, int processState) = 10;
    void scheduleCreateService(IBinder token, in ServiceInfo info,
            in CompatibilityInfo compatInfo, int processState) = 11;
    void scheduleStopService(IBinder token) = 12;
    void bindApplication(in String packageName, in ApplicationInfo info,
            in List<ProviderInfo> providers, in ComponentName testName,
            in ProfilerInfo profilerInfo, in Bundle testArguments,
            IInstrumentationWatcher testWatcher, IUiAutomationConnection uiAutomationConnection,
            int debugMode, boolean enableBinderTracking, boolean trackAllocation,
            boolean restrictedBackupMode, boolean persistent, in Configuration config,
            in CompatibilityInfo compatInfo, in Map services,
            in Bundle coreSettings, in String buildSerial) = 13;
    void scheduleExit() = 14;
    void scheduleConfigurationChanged(in Configuration config) = 16;
    void scheduleServiceArgs(IBinder token, boolean taskRemoved, int startId,
            int flags, in Intent args) = 17;
    void updateTimeZone() = 18;
    void processInBackground() = 19;
    void scheduleBindService(IBinder token,
            in Intent intent, boolean rebind, int processState) = 20;
    void scheduleUnbindService(IBinder token,
            in Intent intent) = 21;
    void dumpService(in ParcelFileDescriptor fd, IBinder servicetoken,
            in String[] args) = 22;
    void scheduleRegisteredReceiver(IIntentReceiver receiver, in Intent intent,
            int resultCode, in String data, in Bundle extras, boolean ordered,
            boolean sticky, int sendingUser, int processState) = 23;
    void scheduleLowMemory() = 24;
    void scheduleActivityConfigurationChanged(IBinder token, in Configuration overrideConfig,
            boolean reportToActivity) = 25;
    void scheduleRelaunchActivity(IBinder token, in List<ResultInfo> pendingResults,
            in List<ReferrerIntent> pendingNewIntents, int configChanges, boolean notResumed,
            in Configuration config, in Configuration overrideConfig, boolean preserveWindow) = 26;
    void scheduleSleeping(IBinder token, boolean sleeping) = 27;
    void profilerControl(boolean start, in ProfilerInfo profilerInfo, int profileType) = 28;
    void setSchedulingGroup(int group) = 29;
    void scheduleCreateBackupAgent(in ApplicationInfo app, in CompatibilityInfo compatInfo,
            int backupMode) = 30;
    void scheduleDestroyBackupAgent(in ApplicationInfo app,
            in CompatibilityInfo compatInfo) = 31;
    void scheduleOnNewActivityOptions(IBinder token, in Bundle options) = 32;
    void scheduleSuicide() = 33;
    void dispatchPackageBroadcast(int cmd, in String[] packages) = 34;
    void scheduleCrash(in String msg) = 35;
    void dumpHeap(boolean managed, in String path, in ParcelFileDescriptor fd) = 36;
    void dumpActivity(in ParcelFileDescriptor fd, IBinder servicetoken, in String prefix,
            in String[] args) = 37;
    void clearDnsCache() = 38;
    void setHttpProxy(in String proxy, in String port, in String exclList,
            in Uri pacFileUrl) = 39;
    void setCoreSettings(in Bundle coreSettings) = 40;
    void updatePackageCompatibilityInfo(in String pkg, in CompatibilityInfo info) = 41;
    void scheduleTrimMemory(int level) = 42;
    void dumpMemInfo(in ParcelFileDescriptor fd, in Debug.MemoryInfo mem, boolean checkin,
            boolean dumpInfo, boolean dumpDalvik, boolean dumpSummaryOnly, boolean dumpUnreachable,
            in String[] args) = 43;
    void dumpGfxInfo(in ParcelFileDescriptor fd, in String[] args) = 44;
    void dumpProvider(in ParcelFileDescriptor fd, IBinder servicetoken,
            in String[] args) = 45;
    void dumpDbInfo(in ParcelFileDescriptor fd, in String[] args) = 46;
    void unstableProviderDied(IBinder provider) = 47;
    void requestAssistContextExtras(IBinder activityToken, IBinder requestToken,
            int requestType, int sessionId) = 48;
    void scheduleTranslucentConversionComplete(IBinder token, boolean timeout) = 49;
    void setProcessState(int state) = 50;
    void scheduleInstallProvider(in ProviderInfo provider) = 51;
    void updateTimePrefs(boolean is24Hour) = 52;
    void scheduleCancelVisibleBehind(IBinder token) = 53;
    void scheduleBackgroundVisibleBehindChanged(IBinder token, boolean enabled) = 54;
    void scheduleEnterAnimationComplete(IBinder token) = 55;
    void notifyCleartextNetwork(in byte[] firstPacket) = 56;
    void startBinderTracking() = 57;
    void stopBinderTrackingAndDump(in ParcelFileDescriptor fd) = 58;
    void scheduleMultiWindowModeChanged(IBinder token, boolean isInMultiWindowMode) = 59;
    void schedulePictureInPictureModeChanged(IBinder token,
            boolean isInPictureInPictureMode) = 60;
    void scheduleLocalVoiceInteractionStarted(IBinder token,
            IVoiceInteractor voiceInteractor) = 61;
    void handleTrustStorageUpdate() = 62;
    /**
     * Don't change the existing transaction Ids as they could be used in the native code.
     * When adding a new method, assign the next available transaction id.
     */
}