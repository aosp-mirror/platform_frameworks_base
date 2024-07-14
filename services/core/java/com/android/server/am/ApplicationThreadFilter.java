/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.am;


class ApplicationThreadFilter implements android.app.IApplicationThread {
    private final android.app.IApplicationThread mBase;
    public ApplicationThreadFilter(android.app.IApplicationThread base) { mBase = base; }
    android.app.IApplicationThread getBase() { return mBase; }
    public android.os.IBinder asBinder() {
        return mBase.asBinder();
    }

    @Override
    public void scheduleReceiver(android.content.Intent intent,
            android.content.pm.ActivityInfo info,
            android.content.res.CompatibilityInfo compatInfo,
            int resultCode,
            String data,
            android.os.Bundle extras,
            boolean ordered,
            boolean assumeDelivered,
            int sendingUser,
            int processState,
            int sentFromUid,
            String sentFromPackage)
            throws android.os.RemoteException {
        mBase.scheduleReceiver(intent,
                info,
                compatInfo,
                resultCode,
                data,
                extras,
                ordered,
                assumeDelivered,
                sendingUser,
                processState,
                sentFromUid,
                sentFromPackage);
    }
    @Override
    public void scheduleReceiverList(java.util.List<android.app.ReceiverInfo> info)
            throws android.os.RemoteException {
        mBase.scheduleReceiverList(info);
    }
    @Override
    public void scheduleCreateService(android.os.IBinder token,
            android.content.pm.ServiceInfo info,
            android.content.res.CompatibilityInfo compatInfo,
            int processState)
            throws android.os.RemoteException {
        mBase.scheduleCreateService(token,
                info,
                compatInfo,
                processState);
    }
    @Override
    public void scheduleStopService(android.os.IBinder token)
            throws android.os.RemoteException {
        mBase.scheduleStopService(token);
    }
    @Override
    public void bindApplication(String packageName,
            android.content.pm.ApplicationInfo info,
            String sdkSandboxClientAppVolumeUuid,
            String sdkSandboxClientAppPackage,
            boolean isSdkInSandbox,
            android.content.pm.ProviderInfoList providerList,
            android.content.ComponentName testName,
            android.app.ProfilerInfo profilerInfo,
            android.os.Bundle testArguments,
            android.app.IInstrumentationWatcher testWatcher,
            android.app.IUiAutomationConnection uiAutomationConnection,
            int debugMode,
            boolean enableBinderTracking,
            boolean trackAllocation,
            boolean restrictedBackupMode,
            boolean persistent,
            android.content.res.Configuration config,
            android.content.res.CompatibilityInfo compatInfo,
            java.util.Map services,
            android.os.Bundle coreSettings,
            String buildSerial,
            android.content.AutofillOptions autofillOptions,
            android.content.ContentCaptureOptions contentCaptureOptions,
            long[] disabledCompatChanges,
            long[] loggableCompatChanges,
            android.os.SharedMemory serializedSystemFontMap,
            long startRequestedElapsedTime,
            long startRequestedUptime)
            throws android.os.RemoteException {
        mBase.bindApplication(packageName,
                info,
                sdkSandboxClientAppVolumeUuid,
                sdkSandboxClientAppPackage,
                isSdkInSandbox,
                providerList,
                testName,
                profilerInfo,
                testArguments,
                testWatcher,
                uiAutomationConnection,
                debugMode,
                enableBinderTracking,
                trackAllocation,
                restrictedBackupMode,
                persistent,
                config,
                compatInfo,
                services,
                coreSettings,
                buildSerial,
                autofillOptions,
                contentCaptureOptions,
                disabledCompatChanges,
                loggableCompatChanges,
                serializedSystemFontMap,
                startRequestedElapsedTime,
                startRequestedUptime);
    }
    @Override
    public void runIsolatedEntryPoint(String entryPoint,
            String[] entryPointArgs)
            throws android.os.RemoteException {
        mBase.runIsolatedEntryPoint(entryPoint,
                entryPointArgs);
    }
    @Override
    public void scheduleExit()
            throws android.os.RemoteException {
        mBase.scheduleExit();
    }
    @Override
    public void scheduleServiceArgs(android.os.IBinder token,
            android.content.pm.ParceledListSlice args)
            throws android.os.RemoteException {
        mBase.scheduleServiceArgs(token,
                args);
    }
    @Override
    public void updateTimeZone()
            throws android.os.RemoteException {
        mBase.updateTimeZone();
    }
    @Override
    public void processInBackground()
            throws android.os.RemoteException {
        mBase.processInBackground();
    }
    @Override
    public void scheduleBindService(android.os.IBinder token,
            android.content.Intent intent,
            boolean rebind,
            int processState,
            long bindSeq)
            throws android.os.RemoteException {
        mBase.scheduleBindService(token,
                intent,
                rebind,
                processState,
                bindSeq);
    }
    @Override
    public void scheduleUnbindService(android.os.IBinder token,
            android.content.Intent intent)
            throws android.os.RemoteException {
        mBase.scheduleUnbindService(token,
                intent);
    }
    @Override
    public void dumpService(android.os.ParcelFileDescriptor fd,
            android.os.IBinder servicetoken,
            String[] args)
            throws android.os.RemoteException {
        mBase.dumpService(fd,
                servicetoken,
                args);
    }
    @Override
    public void scheduleRegisteredReceiver(android.content.IIntentReceiver receiver,
            android.content.Intent intent,
            int resultCode,
            String data,
            android.os.Bundle extras,
            boolean ordered,
            boolean sticky,
            boolean assumeDelivered,
            int sendingUser,
            int processState,
            int sentFromUid,
            String sentFromPackage)
            throws android.os.RemoteException {
        mBase.scheduleRegisteredReceiver(receiver,
                intent,
                resultCode,
                data,
                extras,
                ordered,
                sticky,
                assumeDelivered,
                sendingUser,
                processState,
                sentFromUid,
                sentFromPackage);
    }
    @Override
    public void scheduleLowMemory()
            throws android.os.RemoteException {
        mBase.scheduleLowMemory();
    }
    @Override
    public void profilerControl(boolean start,
            android.app.ProfilerInfo profilerInfo,
            int profileType)
            throws android.os.RemoteException {
        mBase.profilerControl(start,
                profilerInfo,
                profileType);
    }
    @Override
    public void setSchedulingGroup(int group)
            throws android.os.RemoteException {
        mBase.setSchedulingGroup(group);
    }
    @Override
    public void scheduleCreateBackupAgent(android.content.pm.ApplicationInfo app,
            int backupMode,
            int userId,
            int operationType)
            throws android.os.RemoteException {
        mBase.scheduleCreateBackupAgent(app,
                backupMode,
                userId,
                operationType);
    }
    @Override
    public void scheduleDestroyBackupAgent(android.content.pm.ApplicationInfo app,
            int userId)
            throws android.os.RemoteException {
        mBase.scheduleDestroyBackupAgent(app,
                userId);
    }
    @Override
    public void scheduleOnNewSceneTransitionInfo(android.os.IBinder token,
            android.app.ActivityOptions.SceneTransitionInfo info)
            throws android.os.RemoteException {
        mBase.scheduleOnNewSceneTransitionInfo(token,
                info);
    }
    @Override
    public void scheduleSuicide()
            throws android.os.RemoteException {
        mBase.scheduleSuicide();
    }
    @Override
    public void dispatchPackageBroadcast(int cmd,
            String[] packages)
            throws android.os.RemoteException {
        mBase.dispatchPackageBroadcast(cmd,
                packages);
    }
    @Override
    public void scheduleCrash(String msg,
            int typeId,
            android.os.Bundle extras)
            throws android.os.RemoteException {
        mBase.scheduleCrash(msg,
                typeId,
                extras);
    }
    @Override
    public void dumpHeap(boolean managed,
            boolean mallocInfo,
            boolean runGc,
            String dumpBitmaps,
            String path,
            android.os.ParcelFileDescriptor fd,
            android.os.RemoteCallback finishCallback)
            throws android.os.RemoteException {
        mBase.dumpHeap(managed,
                mallocInfo,
                runGc,
                dumpBitmaps,
                path,
                fd,
                finishCallback);
    }
    @Override
    public void dumpActivity(android.os.ParcelFileDescriptor fd,
            android.os.IBinder servicetoken,
            String prefix,
            String[] args)
            throws android.os.RemoteException {
        mBase.dumpActivity(fd,
                servicetoken,
                prefix,
                args);
    }
    @Override
    public void dumpResources(android.os.ParcelFileDescriptor fd,
            android.os.RemoteCallback finishCallback)
            throws android.os.RemoteException {
        mBase.dumpResources(fd,
                finishCallback);
    }
    @Override
    public void clearDnsCache()
            throws android.os.RemoteException {
        mBase.clearDnsCache();
    }
    @Override
    public void updateHttpProxy()
            throws android.os.RemoteException {
        mBase.updateHttpProxy();
    }
    @Override
    public void setCoreSettings(android.os.Bundle coreSettings)
            throws android.os.RemoteException {
        mBase.setCoreSettings(coreSettings);
    }
    @Override
    public void updatePackageCompatibilityInfo(String pkg,
            android.content.res.CompatibilityInfo info)
            throws android.os.RemoteException {
        mBase.updatePackageCompatibilityInfo(pkg,
                info);
    }
    @Override
    public void scheduleTrimMemory(int level)
            throws android.os.RemoteException {
        mBase.scheduleTrimMemory(level);
    }
    @Override
    public void dumpMemInfo(android.os.ParcelFileDescriptor fd,
            android.os.Debug.MemoryInfo mem,
            boolean checkin,
            boolean dumpInfo,
            boolean dumpDalvik,
            boolean dumpSummaryOnly,
            boolean dumpUnreachable,
            boolean dumpAllocatorLogs,
            String[] args)
            throws android.os.RemoteException {
        mBase.dumpMemInfo(fd,
                mem,
                checkin,
                dumpInfo,
                dumpDalvik,
                dumpSummaryOnly,
                dumpUnreachable,
                dumpAllocatorLogs,
                args);
    }
    @Override
    public void dumpMemInfoProto(android.os.ParcelFileDescriptor fd,
            android.os.Debug.MemoryInfo mem,
            boolean dumpInfo,
            boolean dumpDalvik,
            boolean dumpSummaryOnly,
            boolean dumpUnreachable,
            String[] args)
            throws android.os.RemoteException {
        mBase.dumpMemInfoProto(fd,
                mem,
                dumpInfo,
                dumpDalvik,
                dumpSummaryOnly,
                dumpUnreachable,
                args);
    }
    @Override
    public void dumpGfxInfo(android.os.ParcelFileDescriptor fd,
            String[] args)
            throws android.os.RemoteException {
        mBase.dumpGfxInfo(fd,
                args);
    }
    @Override
    public void dumpCacheInfo(android.os.ParcelFileDescriptor fd,
            String[] args)
            throws android.os.RemoteException {
        mBase.dumpCacheInfo(fd,
                args);
    }
    @Override
    public void dumpProvider(android.os.ParcelFileDescriptor fd,
            android.os.IBinder servicetoken,
            String[] args)
            throws android.os.RemoteException {
        mBase.dumpProvider(fd,
                servicetoken,
                args);
    }
    @Override
    public void dumpDbInfo(android.os.ParcelFileDescriptor fd,
            String[] args)
            throws android.os.RemoteException {
        mBase.dumpDbInfo(fd,
                args);
    }
    @Override
    public void unstableProviderDied(android.os.IBinder provider)
            throws android.os.RemoteException {
        mBase.unstableProviderDied(provider);
    }
    @Override
    public void requestAssistContextExtras(android.os.IBinder activityToken,
            android.os.IBinder requestToken,
            int requestType,
            int sessionId,
            int flags)
            throws android.os.RemoteException {
        mBase.requestAssistContextExtras(activityToken,
                requestToken,
                requestType,
                sessionId,
                flags);
    }
    @Override
    public void scheduleTranslucentConversionComplete(android.os.IBinder token,
            boolean timeout)
            throws android.os.RemoteException {
        mBase.scheduleTranslucentConversionComplete(token,
                timeout);
    }
    @Override
    public void setProcessState(int state)
            throws android.os.RemoteException {
        mBase.setProcessState(state);
    }
    @Override
    public void scheduleInstallProvider(android.content.pm.ProviderInfo provider)
            throws android.os.RemoteException {
        mBase.scheduleInstallProvider(provider);
    }
    @Override
    public void updateTimePrefs(int timeFormatPreference)
            throws android.os.RemoteException {
        mBase.updateTimePrefs(timeFormatPreference);
    }
    @Override
    public void scheduleEnterAnimationComplete(android.os.IBinder token)
            throws android.os.RemoteException {
        mBase.scheduleEnterAnimationComplete(token);
    }
    @Override
    public void notifyCleartextNetwork(byte[] firstPacket)
            throws android.os.RemoteException {
        mBase.notifyCleartextNetwork(firstPacket);
    }
    @Override
    public void startBinderTracking()
            throws android.os.RemoteException {
        mBase.startBinderTracking();
    }
    @Override
    public void stopBinderTrackingAndDump(android.os.ParcelFileDescriptor fd)
            throws android.os.RemoteException {
        mBase.stopBinderTrackingAndDump(fd);
    }
    @Override
    public void scheduleLocalVoiceInteractionStarted(android.os.IBinder token,
            com.android.internal.app.IVoiceInteractor voiceInteractor)
            throws android.os.RemoteException {
        mBase.scheduleLocalVoiceInteractionStarted(token,
                voiceInteractor);
    }
    @Override
    public void handleTrustStorageUpdate()
            throws android.os.RemoteException {
        mBase.handleTrustStorageUpdate();
    }
    @Override
    public void attachAgent(String path)
            throws android.os.RemoteException {
        mBase.attachAgent(path);
    }
    @Override
    public void attachStartupAgents(String dataDir)
            throws android.os.RemoteException {
        mBase.attachStartupAgents(dataDir);
    }
    @Override
    public void scheduleApplicationInfoChanged(android.content.pm.ApplicationInfo ai)
            throws android.os.RemoteException {
        mBase.scheduleApplicationInfoChanged(ai);
    }
    @Override
    public void setNetworkBlockSeq(long procStateSeq)
            throws android.os.RemoteException {
        mBase.setNetworkBlockSeq(procStateSeq);
    }
    @Override
    public void scheduleTransaction(android.app.servertransaction.ClientTransaction transaction)
            throws android.os.RemoteException {
        mBase.scheduleTransaction(transaction);
    }
    @Override
    public void scheduleTaskFragmentTransaction(android.window.ITaskFragmentOrganizer organizer,
            android.window.TaskFragmentTransaction transaction)
            throws android.os.RemoteException {
        mBase.scheduleTaskFragmentTransaction(organizer,
                transaction);
    }
    @Override
    public void requestDirectActions(android.os.IBinder activityToken,
            com.android.internal.app.IVoiceInteractor intractor,
            android.os.RemoteCallback cancellationCallback,
            android.os.RemoteCallback callback)
            throws android.os.RemoteException {
        mBase.requestDirectActions(activityToken,
                intractor,
                cancellationCallback,
                callback);
    }
    @Override
    public void performDirectAction(android.os.IBinder activityToken,
            String actionId,
            android.os.Bundle arguments,
            android.os.RemoteCallback cancellationCallback,
            android.os.RemoteCallback resultCallback)
            throws android.os.RemoteException {
        mBase.performDirectAction(activityToken,
                actionId,
                arguments,
                cancellationCallback,
                resultCallback);
    }
    @Override
    public void notifyContentProviderPublishStatus(android.app.ContentProviderHolder holder,
            String authorities,
            int userId,
            boolean published)
            throws android.os.RemoteException {
        mBase.notifyContentProviderPublishStatus(holder,
                authorities,
                userId,
                published);
    }
    @Override
    public void instrumentWithoutRestart(android.content.ComponentName instrumentationName,
            android.os.Bundle instrumentationArgs,
            android.app.IInstrumentationWatcher instrumentationWatcher,
            android.app.IUiAutomationConnection instrumentationUiConnection,
            android.content.pm.ApplicationInfo targetInfo)
            throws android.os.RemoteException {
        mBase.instrumentWithoutRestart(instrumentationName,
                instrumentationArgs,
                instrumentationWatcher,
                instrumentationUiConnection,
                targetInfo);
    }
    @Override
    public void updateUiTranslationState(android.os.IBinder activityToken,
            int state,
            android.view.translation.TranslationSpec sourceSpec,
            android.view.translation.TranslationSpec targetSpec,
            java.util.List<android.view.autofill.AutofillId> viewIds,
            android.view.translation.UiTranslationSpec uiTranslationSpec)
            throws android.os.RemoteException {
        mBase.updateUiTranslationState(activityToken,
                state,
                sourceSpec,
                targetSpec,
                viewIds,
                uiTranslationSpec);
    }
    @Override
    public void scheduleTimeoutService(android.os.IBinder token,
            int startId)
            throws android.os.RemoteException {
        mBase.scheduleTimeoutService(token,
                startId);
    }
    @Override
    public void scheduleTimeoutServiceForType(android.os.IBinder token,
            int startId,
            int fgsType)
            throws android.os.RemoteException {
        mBase.scheduleTimeoutServiceForType(token,
                startId,
                fgsType);
    }
    @Override
    public void schedulePing(android.os.RemoteCallback pong)
            throws android.os.RemoteException {
        mBase.schedulePing(pong);
    }
}
