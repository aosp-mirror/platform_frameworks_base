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
            int configChanges, boolean dontReport) = 0;
    void scheduleStopActivity(IBinder token, boolean showWindow,
            int configChanges) = 2;
    void scheduleWindowVisibility(IBinder token, boolean showWindow) = 3;
    void scheduleResumeActivity(IBinder token, int procState, boolean isForward,
            in Bundle resumeArgs) = 4;
    void scheduleSendResult(IBinder token, in List<ResultInfo> results) = 5;
    void scheduleLaunchActivity(in Intent intent, IBinder token, int ident,
            in ActivityInfo info, in Configuration curConfig, in Configuration overrideConfig,
            in CompatibilityInfo compatInfo, in String referrer, IVoiceInteractor voiceInteractor,
            int procState, in Bundle state, in PersistableBundle persistentState,
            in List<ResultInfo> pendingResults, in List<ReferrerIntent> pendingNewIntents,
            boolean notResumed, boolean isForward, in ProfilerInfo profilerInfo) = 6;
    void scheduleNewIntent(
            in List<ReferrerIntent> intent, IBinder token, boolean andPause) = 7;
    void scheduleDestroyActivity(IBinder token, boolean finished,
            int configChanges) = 8;
    void scheduleReceiver(in Intent intent, in ActivityInfo info,
            in CompatibilityInfo compatInfo,
            int resultCode, in String data, in Bundle extras, boolean sync,
            int sendingUser, int processState) = 9;
    void scheduleCreateService(IBinder token, in ServiceInfo info,
            in CompatibilityInfo compatInfo, int processState) = 10;
    void scheduleStopService(IBinder token) = 11;
    void bindApplication(in String packageName, in ApplicationInfo info,
            in List<ProviderInfo> providers, in ComponentName testName,
            in ProfilerInfo profilerInfo, in Bundle testArguments,
            IInstrumentationWatcher testWatcher, IUiAutomationConnection uiAutomationConnection,
            int debugMode, boolean enableBinderTracking, boolean trackAllocation,
            boolean restrictedBackupMode, boolean persistent, in Configuration config,
            in CompatibilityInfo compatInfo, in Map services,
            in Bundle coreSettings, in String buildSerial) = 12;
    void scheduleExit() = 13;
    void scheduleConfigurationChanged(in Configuration config) = 15;
    void scheduleServiceArgs(IBinder token, boolean taskRemoved, int startId,
            int flags, in Intent args) = 16;
    void updateTimeZone() = 17;
    void processInBackground() = 18;
    void scheduleBindService(IBinder token,
            in Intent intent, boolean rebind, int processState) = 19;
    void scheduleUnbindService(IBinder token,
            in Intent intent) = 20;
    void dumpService(in ParcelFileDescriptor fd, IBinder servicetoken,
            in String[] args) = 21;
    void scheduleRegisteredReceiver(IIntentReceiver receiver, in Intent intent,
            int resultCode, in String data, in Bundle extras, boolean ordered,
            boolean sticky, int sendingUser, int processState) = 22;
    void scheduleLowMemory() = 23;
    void scheduleActivityConfigurationChanged(IBinder token, in Configuration overrideConfig,
            boolean reportToActivity) = 24;
    void scheduleRelaunchActivity(IBinder token, in List<ResultInfo> pendingResults,
            in List<ReferrerIntent> pendingNewIntents, int configChanges, boolean notResumed,
            in Configuration config, in Configuration overrideConfig, boolean preserveWindow) = 25;
    void scheduleSleeping(IBinder token, boolean sleeping) = 26;
    void profilerControl(boolean start, in ProfilerInfo profilerInfo, int profileType) = 27;
    void setSchedulingGroup(int group) = 28;
    void scheduleCreateBackupAgent(in ApplicationInfo app, in CompatibilityInfo compatInfo,
            int backupMode) = 29;
    void scheduleDestroyBackupAgent(in ApplicationInfo app,
            in CompatibilityInfo compatInfo) = 30;
    void scheduleOnNewActivityOptions(IBinder token, in Bundle options) = 31;
    void scheduleSuicide() = 32;
    void dispatchPackageBroadcast(int cmd, in String[] packages) = 33;
    void scheduleCrash(in String msg) = 34;
    void dumpHeap(boolean managed, in String path, in ParcelFileDescriptor fd) = 35;
    void dumpActivity(in ParcelFileDescriptor fd, IBinder servicetoken, in String prefix,
            in String[] args) = 36;
    void clearDnsCache() = 37;
    void setHttpProxy(in String proxy, in String port, in String exclList,
            in Uri pacFileUrl) = 38;
    void setCoreSettings(in Bundle coreSettings) = 39;
    void updatePackageCompatibilityInfo(in String pkg, in CompatibilityInfo info) = 40;
    void scheduleTrimMemory(int level) = 41;
    void dumpMemInfo(in ParcelFileDescriptor fd, in Debug.MemoryInfo mem, boolean checkin,
            boolean dumpInfo, boolean dumpDalvik, boolean dumpSummaryOnly, boolean dumpUnreachable,
            in String[] args) = 42;
    void dumpGfxInfo(in ParcelFileDescriptor fd, in String[] args) = 43;
    void dumpProvider(in ParcelFileDescriptor fd, IBinder servicetoken,
            in String[] args) = 44;
    void dumpDbInfo(in ParcelFileDescriptor fd, in String[] args) = 45;
    void unstableProviderDied(IBinder provider) = 46;
    void requestAssistContextExtras(IBinder activityToken, IBinder requestToken,
            int requestType, int sessionId) = 47;
    void scheduleTranslucentConversionComplete(IBinder token, boolean timeout) = 48;
    void setProcessState(int state) = 49;
    void scheduleInstallProvider(in ProviderInfo provider) = 50;
    void updateTimePrefs(boolean is24Hour) = 51;
    void scheduleCancelVisibleBehind(IBinder token) = 52;
    void scheduleBackgroundVisibleBehindChanged(IBinder token, boolean enabled) = 53;
    void scheduleEnterAnimationComplete(IBinder token) = 54;
    void notifyCleartextNetwork(in byte[] firstPacket) = 55;
    void startBinderTracking() = 56;
    void stopBinderTrackingAndDump(in ParcelFileDescriptor fd) = 57;
    void scheduleMultiWindowModeChanged(IBinder token, boolean isInMultiWindowMode) = 58;
    void schedulePictureInPictureModeChanged(IBinder token,
            boolean isInPictureInPictureMode) = 59;
    void scheduleLocalVoiceInteractionStarted(IBinder token,
            IVoiceInteractor voiceInteractor) = 60;
    void handleTrustStorageUpdate() = 61;
    void attachAgent(String path) = 62;
    /**
     * Don't change the existing transaction Ids as they could be used in the native code.
     * When adding a new method, assign the next available transaction id.
     */
}