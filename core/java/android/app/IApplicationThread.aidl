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

import android.app.ActivityOptions.SceneTransitionInfo;
import android.app.ContentProviderHolder;
import android.app.IInstrumentationWatcher;
import android.app.IUiAutomationConnection;
import android.app.ProfilerInfo;
import android.app.ReceiverInfo;
import android.app.ResultInfo;
import android.app.servertransaction.ClientTransaction;
import android.content.AutofillOptions;
import android.content.ComponentName;
import android.content.ContentCaptureOptions;
import android.content.IIntentReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ParceledListSlice;
import android.content.pm.ProviderInfo;
import android.content.pm.ProviderInfoList;
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
import android.os.RemoteCallback;
import android.os.SharedMemory;
import android.view.autofill.AutofillId;
import android.view.translation.TranslationSpec;
import android.view.translation.UiTranslationSpec;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentTransaction;

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
@JavaDelegator
oneway interface IApplicationThread {
    void scheduleReceiver(in Intent intent, in ActivityInfo info,
            in CompatibilityInfo compatInfo,
            int resultCode, in String data, in Bundle extras, boolean ordered,
            boolean assumeDelivered, int sendingUser, int processState, int sentFromUid,
            in String sentFromPackage);

    void scheduleReceiverList(in List<ReceiverInfo> info);

    @UnsupportedAppUsage
    void scheduleCreateService(IBinder token, in ServiceInfo info,
            in CompatibilityInfo compatInfo, int processState);
    @UnsupportedAppUsage
    void scheduleStopService(IBinder token);
    void bindApplication(in String packageName, in ApplicationInfo info,
            in String sdkSandboxClientAppVolumeUuid, in String sdkSandboxClientAppPackage,
            in boolean isSdkInSandbox,
            in ProviderInfoList providerList, in ComponentName testName,
            in ProfilerInfo profilerInfo, in Bundle testArguments,
            IInstrumentationWatcher testWatcher, IUiAutomationConnection uiAutomationConnection,
            int debugMode, boolean enableBinderTracking, boolean trackAllocation,
            boolean restrictedBackupMode, boolean persistent, in Configuration config,
            in CompatibilityInfo compatInfo, in Map services,
            in Bundle coreSettings, in String buildSerial, in AutofillOptions autofillOptions,
            in ContentCaptureOptions contentCaptureOptions, in long[] disabledCompatChanges,
            in long[] loggableCompatChanges, in SharedMemory serializedSystemFontMap,
            in FileDescriptor applicationSharedMemoryFd,
            long startRequestedElapsedTime, long startRequestedUptime);
    void runIsolatedEntryPoint(in String entryPoint, in String[] entryPointArgs);
    void scheduleExit();
    void scheduleServiceArgs(IBinder token, in ParceledListSlice args);
    void updateTimeZone();
    void processInBackground();
    @UnsupportedAppUsage
    void scheduleBindService(IBinder token,
            in Intent intent, boolean rebind, int processState, long bindSeq);
    @UnsupportedAppUsage
    void scheduleUnbindService(IBinder token,
            in Intent intent);
    void dumpService(in ParcelFileDescriptor fd, IBinder servicetoken,
            in String[] args);
    void scheduleRegisteredReceiver(IIntentReceiver receiver, in Intent intent,
            int resultCode, in String data, in Bundle extras, boolean ordered,
            boolean sticky, boolean assumeDelivered, int sendingUser, int processState,
            int sentFromUid, in String sentFromPackage);
    void scheduleLowMemory();
    void profilerControl(boolean start, in ProfilerInfo profilerInfo, int profileType);
    void setSchedulingGroup(int group);
    void scheduleCreateBackupAgent(in ApplicationInfo app,
            int backupMode, int userId, int operationType);
    void scheduleDestroyBackupAgent(in ApplicationInfo app, int userId);
    void scheduleOnNewSceneTransitionInfo(IBinder token, in SceneTransitionInfo info);
    void scheduleSuicide();
    void dispatchPackageBroadcast(int cmd, in String[] packages);
    void scheduleCrash(in String msg, int typeId, in Bundle extras);
    void dumpHeap(boolean managed, boolean mallocInfo, boolean runGc,
            in String dumpBitmaps, in String path,
            in ParcelFileDescriptor fd, in RemoteCallback finishCallback);
    void dumpActivity(in ParcelFileDescriptor fd, IBinder servicetoken, in String prefix,
            in String[] args);
    void dumpResources(in ParcelFileDescriptor fd, in RemoteCallback finishCallback);
    void clearDnsCache();
    void updateHttpProxy();
    void setCoreSettings(in Bundle coreSettings);
    void updatePackageCompatibilityInfo(in String pkg, in CompatibilityInfo info);
    @UnsupportedAppUsage
    void scheduleTrimMemory(int level);
    void dumpMemInfo(in ParcelFileDescriptor fd, in Debug.MemoryInfo mem, boolean checkin,
            boolean dumpInfo, boolean dumpDalvik, boolean dumpSummaryOnly, boolean dumpUnreachable,
            boolean dumpAllocatorLogs, in String[] args);
    void dumpMemInfoProto(in ParcelFileDescriptor fd, in Debug.MemoryInfo mem,
            boolean dumpInfo, boolean dumpDalvik, boolean dumpSummaryOnly, boolean dumpUnreachable,
            in String[] args);
    void dumpGfxInfo(in ParcelFileDescriptor fd, in String[] args);
    void dumpCacheInfo(in ParcelFileDescriptor fd, in String[] args);
    void dumpProvider(in ParcelFileDescriptor fd, IBinder servicetoken,
            in String[] args);
    void dumpDbInfo(in ParcelFileDescriptor fd, in String[] args);
    void unstableProviderDied(IBinder provider);
    void requestAssistContextExtras(IBinder activityToken, IBinder requestToken,
            int requestType, int sessionId, int flags);
    void scheduleTranslucentConversionComplete(IBinder token, boolean timeout);
    void setProcessState(int state);
    void scheduleInstallProvider(in ProviderInfo provider);
    void updateTimePrefs(int timeFormatPreference);
    void scheduleEnterAnimationComplete(IBinder token);
    void notifyCleartextNetwork(in byte[] firstPacket);
    void startBinderTracking();
    void stopBinderTrackingAndDump(in ParcelFileDescriptor fd);
    void scheduleLocalVoiceInteractionStarted(IBinder token,
            IVoiceInteractor voiceInteractor);
    void handleTrustStorageUpdate();
    void attachAgent(String path);
    void attachStartupAgents(String dataDir);
    void scheduleApplicationInfoChanged(in ApplicationInfo ai);
    void setNetworkBlockSeq(long procStateSeq);
    void scheduleTransaction(in ClientTransaction transaction);
    void scheduleTaskFragmentTransaction(in ITaskFragmentOrganizer organizer,
            in TaskFragmentTransaction transaction);
    void requestDirectActions(IBinder activityToken, IVoiceInteractor intractor,
            in RemoteCallback cancellationCallback, in RemoteCallback callback);
    void performDirectAction(IBinder activityToken, String actionId,
            in Bundle arguments, in RemoteCallback cancellationCallback,
            in RemoteCallback resultCallback);
    void notifyContentProviderPublishStatus(in ContentProviderHolder holder, String authorities,
            int userId, boolean published);
    void instrumentWithoutRestart(in ComponentName instrumentationName,
            in Bundle instrumentationArgs,
            IInstrumentationWatcher instrumentationWatcher,
            IUiAutomationConnection instrumentationUiConnection,
            in ApplicationInfo targetInfo);
    void updateUiTranslationState(IBinder activityToken, int state, in TranslationSpec sourceSpec,
            in TranslationSpec targetSpec, in List<AutofillId> viewIds,
            in UiTranslationSpec uiTranslationSpec);
    void scheduleTimeoutService(IBinder token, int startId);
    void scheduleTimeoutServiceForType(IBinder token, int startId, int fgsType);
    void schedulePing(in RemoteCallback pong);
}
