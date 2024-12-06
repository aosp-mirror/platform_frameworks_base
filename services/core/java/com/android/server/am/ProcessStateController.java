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

import static com.android.server.am.ActivityManagerDebugConfig.DEBUG_OOM_ADJ;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.PowerManagerInternal;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.ServiceThread;

/**
 * ProcessStateController is responsible for maintaining state that can affect the OomAdjuster
 * computations of a process. Any state that can affect a process's importance must be set by
 * only ProcessStateController.
 */
public class ProcessStateController {
    public static String TAG = "ProcessStateController";

    private final OomAdjuster mOomAdjuster;

    private final GlobalState mGlobalState = new GlobalState();

    private ProcessStateController(ActivityManagerService ams, ProcessList processList,
            ActiveUids activeUids, ServiceThread handlerThread,
            CachedAppOptimizer cachedAppOptimizer, OomAdjuster.Injector oomAdjInjector,
            boolean useOomAdjusterModernImpl) {
        mOomAdjuster = useOomAdjusterModernImpl
                ? new OomAdjusterModernImpl(ams, processList, activeUids, handlerThread,
                mGlobalState, cachedAppOptimizer, oomAdjInjector)
                : new OomAdjuster(ams, processList, activeUids, handlerThread, mGlobalState,
                        cachedAppOptimizer, oomAdjInjector);
    }

    /**
     * Get the instance of OomAdjuster that ProcessStateController is using.
     * Must only be interacted with while holding the ActivityManagerService lock.
     */
    public OomAdjuster getOomAdjuster() {
        return mOomAdjuster;
    }

    /**
     * Add a process to evaluated the next time an update is run.
     */
    public void enqueueUpdateTarget(@NonNull ProcessRecord proc) {
        mOomAdjuster.enqueueOomAdjTargetLocked(proc);
    }

    /**
     * Remove a process that was added by {@link #enqueueUpdateTarget}.
     */
    public void removeUpdateTarget(@NonNull ProcessRecord proc, boolean procDied) {
        mOomAdjuster.removeOomAdjTargetLocked(proc, procDied);
    }

    /**
     * Trigger an update on a single process (and any processes that have been enqueued with
     * {@link #enqueueUpdateTarget}).
     */
    public boolean runUpdate(@NonNull ProcessRecord proc,
            @ActivityManagerInternal.OomAdjReason int oomAdjReason) {
        return mOomAdjuster.updateOomAdjLocked(proc, oomAdjReason);
    }

    /**
     * Trigger an update on all processes that have been enqueued with {@link #enqueueUpdateTarget}.
     */
    public void runPendingUpdate(@ActivityManagerInternal.OomAdjReason int oomAdjReason) {
        mOomAdjuster.updateOomAdjPendingTargetsLocked(oomAdjReason);
    }

    /**
     * Trigger an update on all processes.
     */
    public void runFullUpdate(@ActivityManagerInternal.OomAdjReason int oomAdjReason) {
        mOomAdjuster.updateOomAdjLocked(oomAdjReason);
    }

    /**
     * Trigger an update on any processes that have been marked for follow up during a previous
     * update.
     */
    public void runFollowUpUpdate() {
        mOomAdjuster.updateOomAdjFollowUpTargetsLocked();
    }

    private static class GlobalState implements OomAdjuster.GlobalState {
        public boolean isAwake = true;
        // TODO(b/369300367): Maintaining global state for backup processes is a bit convoluted.
        //  ideally the state gets migrated to ProcessStateRecord.
        public final SparseArray<ProcessRecord> backupTargets = new SparseArray<>();
        public boolean isLastMemoryLevelNormal = true;

        public boolean isAwake() {
            return isAwake;
        }

        public ProcessRecord getBackupTarget(@UserIdInt int userId) {
            return backupTargets.get(userId);
        }

        public boolean isLastMemoryLevelNormal() {
            return isLastMemoryLevelNormal;
        }
    }

    /*************************** Global State Events ***************************/
    /**
     * Set which process state Top processes should get.
     */
    public void setTopProcessState(@ActivityManager.ProcessState int procState) {
        // TODO(b/302575389): Migrate state pulled from ATMS to a pushed model
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Set whether to give Top processes the Top sched group.
     */
    public void setUseTopSchedGroupForTopProcess(boolean useTopSchedGroup) {
        // TODO(b/302575389): Migrate state pulled from ATMS to a pushed model
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Set the Top process.
     */
    public void setTopApp(@Nullable ProcessRecord proc) {
        // TODO(b/302575389): Migrate state pulled from ATMS to a pushed model
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Set which process is considered the Home process, if any.
     */
    public void setHomeProcess(@Nullable ProcessRecord proc) {
        // TODO(b/302575389): Migrate state pulled from ATMS to a pushed model
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Set which process is considered the Heavy Weight process, if any.
     */
    public void setHeavyWeightProcess(@Nullable ProcessRecord proc) {
        // TODO(b/302575389): Migrate state pulled from ATMS to a pushed model
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Set which process is showing UI while the screen is off, if any.
     */
    public void setVisibleDozeUiProcess(@Nullable ProcessRecord proc) {
        // TODO(b/302575389): Migrate state pulled from ATMS to a pushed model
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Set which process is considered the Previous process, if any.
     */
    public void setPreviousProcess(@Nullable ProcessRecord proc) {
        // TODO(b/302575389): Migrate state pulled from ATMS to a pushed model
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Set what wakefulness state the screen is in.
     */
    public void setWakefulness(int wakefulness) {
        mGlobalState.isAwake = (wakefulness == PowerManagerInternal.WAKEFULNESS_AWAKE);
        mOomAdjuster.onWakefulnessChanged(wakefulness);
    }

    /**
     * Set for a given user what process is currently running a backup, if any.
     */
    public void setBackupTarget(@NonNull ProcessRecord proc, @UserIdInt int userId) {
        mGlobalState.backupTargets.put(userId, proc);
    }

    /**
     * No longer consider any process running a backup for a given user.
     */
    public void stopBackupTarget(@UserIdInt int userId) {
        mGlobalState.backupTargets.delete(userId);
    }

    /**
     * Set whether the last known memory level is normal.
     */
    public void setIsLastMemoryLevelNormal(boolean isMemoryNormal) {
        mGlobalState.isLastMemoryLevelNormal = isMemoryNormal;
    }

    /***************************** UID State Events ****************************/
    /**
     * Set a UID as temp allowlisted.
     */
    public void setUidTempAllowlistStateLSP(int uid, boolean allowList) {
        mOomAdjuster.setUidTempAllowlistStateLSP(uid, allowList);
    }

    /*********************** Process Miscellaneous Events **********************/
    /**
     * Set the maximum adj score a process can be assigned.
     */
    public void setMaxAdj(@NonNull ProcessRecord proc, int adj) {
        proc.mState.setMaxAdj(adj);
    }

    /**
     * Initialize a process that is being attached.
     */
    @GuardedBy({"mService", "mProcLock"})
    public void setAttachingProcessStatesLSP(@NonNull ProcessRecord proc) {
        mOomAdjuster.setAttachingProcessStatesLSP(proc);
    }

    /**
     * Note whether a process is pending attach or not.
     */
    public void setPendingFinishAttach(@NonNull ProcessRecord proc, boolean pendingFinishAttach) {
        proc.setPendingFinishAttach(pendingFinishAttach);
    }

    /**
     * Set what sched group to grant a process due to running a broadcast.
     * {@link ProcessList.SCHED_GROUP_UNDEFINED} means the process is not running a broadcast.
     */
    public void setBroadcastSchedGroup(@NonNull ProcessRecord proc, int schedGroup) {
        // TODO(b/302575389): Migrate state pulled from BroadcastQueue to a pushed model
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /********************* Process Visibility State Events *********************/
    /**
     * Note whether a process has Top UI or not.
     *
     * @return true if the state changed, otherwise returns false.
     */
    public boolean setHasTopUi(@NonNull ProcessRecord proc, boolean hasTopUi) {
        if (proc.mState.hasTopUi() == hasTopUi) return false;
        if (DEBUG_OOM_ADJ) {
            Slog.d(TAG, "Setting hasTopUi=" + hasTopUi + " for pid=" + proc.getPid());
        }
        proc.mState.setHasTopUi(hasTopUi);
        return true;
    }

    /**
     * Note whether a process is displaying Overlay UI or not.
     *
     * @return true if the state changed, otherwise returns false.
     */
    public boolean setHasOverlayUi(@NonNull ProcessRecord proc, boolean hasOverlayUi) {
        if (proc.mState.hasOverlayUi() == hasOverlayUi) return false;
        proc.mState.setHasOverlayUi(hasOverlayUi);
        return true;
    }


    /**
     * Note whether a process is running a remote animation.
     *
     * @return true if the state changed, otherwise returns false.
     */
    public boolean setRunningRemoteAnimation(@NonNull ProcessRecord proc,
            boolean runningRemoteAnimation) {
        if (proc.mState.isRunningRemoteAnimation() == runningRemoteAnimation) return false;
        if (DEBUG_OOM_ADJ) {
            Slog.i(TAG, "Setting runningRemoteAnimation=" + runningRemoteAnimation
                    + " for pid=" + proc.getPid());
        }
        proc.mState.setRunningRemoteAnimation(runningRemoteAnimation);
        return true;
    }

    /**
     * Note that the process is showing a toast.
     */
    public void setForcingToImportant(@NonNull ProcessRecord proc,
            @Nullable Object forcingToImportant) {
        if (proc.mState.getForcingToImportant() == forcingToImportant) return;
        proc.mState.setForcingToImportant(forcingToImportant);
    }

    /**
     * Note that the process has shown UI at some point in its life.
     */
    public void setHasShownUi(@NonNull ProcessRecord proc, boolean hasShownUi) {
        // This arguably should be turned into an internal state of OomAdjuster.
        if (proc.mState.hasShownUi() == hasShownUi) return;
        proc.mState.setHasShownUi(hasShownUi);
    }

    /**
     * Note whether the process has an activity or not.
     */
    public void setHasActivity(@NonNull ProcessRecord proc, boolean hasActivity) {
        // TODO(b/302575389): Migrate state pulled from ATMS to a pushed model
        // Possibly not needed, maybe can use ActivityStateFlags.
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Note whether the process has a visibly activity or not.
     */
    public void setHasVisibleActivity(@NonNull ProcessRecord proc, boolean hasVisibleActivity) {
        // TODO(b/302575389): Migrate state pulled from ATMS to a pushed model
        // maybe used ActivityStateFlags instead.
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /**
     * Set the Activity State Flags for a process.
     */
    public void setActivityStateFlags(@NonNull ProcessRecord proc, int flags) {
        // TODO(b/302575389): Migrate state pulled from ATMS to a pushed model
        throw new UnsupportedOperationException("Not implemented yet");
    }

    /********************** Content Provider State Events **********************/
    /**
     * Note that a process is hosting a content provider.
     */
    public boolean addPublishedProvider(@NonNull ProcessRecord proc, String name,
            ContentProviderRecord cpr) {
        final ProcessProviderRecord providers = proc.mProviders;
        if (providers.hasProvider(name)) return false;
        providers.installProvider(name, cpr);
        return true;
    }

    /**
     * Remove a published content provider from a process.
     */
    public void removePublishedProvider(@NonNull ProcessRecord proc, String name) {
        final ProcessProviderRecord providers = proc.mProviders;
        providers.removeProvider(name);
    }

    /**
     * Note that a content provider has an external client.
     */
    public void addExternalProviderClient(@NonNull ContentProviderRecord cpr,
            IBinder externalProcessToken, int callingUid, String callingTag) {
        cpr.addExternalProcessHandleLocked(externalProcessToken, callingUid, callingTag);
    }

    /**
     * Remove an external client from a conetnt provider.
     */
    public boolean removeExternalProviderClient(@NonNull ContentProviderRecord cpr,
            IBinder externalProcessToken) {
        return cpr.removeExternalProcessHandleLocked(externalProcessToken);
    }

    /**
     * Note the time a process is no longer hosting any content providers.
     */
    public void setLastProviderTime(@NonNull ProcessRecord proc, long uptimeMs) {
        proc.mProviders.setLastProviderTime(uptimeMs);
    }

    /**
     * Note that a process has connected to a content provider.
     */
    public void addProviderConnection(@NonNull ProcessRecord client,
            ContentProviderConnection cpc) {
        client.mProviders.addProviderConnection(cpc);
    }

    /**
     * Note that a process is no longer connected to a content provider.
     */
    public void removeProviderConnection(@NonNull ProcessRecord client,
            ContentProviderConnection cpc) {
        client.mProviders.removeProviderConnection(cpc);
    }

    /*************************** Service State Events **************************/
    /**
     * Note that a process has started hosting a service.
     */
    public boolean startService(@NonNull ProcessServiceRecord psr, ServiceRecord sr) {
        return psr.startService(sr);
    }

    /**
     * Note that a process has stopped hosting a service.
     */
    public boolean stopService(@NonNull ProcessServiceRecord psr, ServiceRecord sr) {
        return psr.stopService(sr);
    }

    /**
     * Remove all services that the process is hosting.
     */
    public void stopAllServices(@NonNull ProcessServiceRecord psr) {
        psr.stopAllServices();
    }

    /**
     * Note that a process's service has started executing.
     */
    public void startExecutingService(@NonNull ProcessServiceRecord psr, ServiceRecord sr) {
        psr.startExecutingService(sr);
    }

    /**
     * Note that a process's service has stopped executing.
     */
    public void stopExecutingService(@NonNull ProcessServiceRecord psr, ServiceRecord sr) {
        psr.stopExecutingService(sr);
    }

    /**
     * Note all executing services a process has has stopped.
     */
    public void stopAllExecutingServices(@NonNull ProcessServiceRecord psr) {
        psr.stopAllExecutingServices();
    }

    /**
     * Note that process has bound to a service.
     */
    public void addConnection(@NonNull ProcessServiceRecord psr, ConnectionRecord cr) {
        psr.addConnection(cr);
    }

    /**
     * Note that process has unbound from a service.
     */
    public void removeConnection(@NonNull ProcessServiceRecord psr, ConnectionRecord cr) {
        psr.removeConnection(cr);
    }

    /**
     * Remove all bindings a process has to services.
     */
    public void removeAllConnections(@NonNull ProcessServiceRecord psr) {
        psr.removeAllConnections();
        psr.removeAllSdkSandboxConnections();
    }

    /**
     * Note whether an executing service should be considered in the foreground or not.
     */
    public void setExecServicesFg(@NonNull ProcessServiceRecord psr, boolean execServicesFg) {
        psr.setExecServicesFg(execServicesFg);
    }

    /**
     * Note whether a service is in the foreground or not and what type of FGS, if so.
     */
    public void setHasForegroundServices(@NonNull ProcessServiceRecord psr,
            boolean hasForegroundServices,
            int fgServiceTypes, boolean hasTypeNoneFgs) {
        psr.setHasForegroundServices(hasForegroundServices, fgServiceTypes, hasTypeNoneFgs);
    }

    /**
     * Note whether a service has a client activity or not.
     */
    public void setHasClientActivities(@NonNull ProcessServiceRecord psr,
            boolean hasClientActivities) {
        psr.setHasClientActivities(hasClientActivities);
    }

    /**
     * Note whether a service should be treated like an activity or not.
     */
    public void setTreatLikeActivity(@NonNull ProcessServiceRecord psr, boolean treatLikeActivity) {
        psr.setTreatLikeActivity(treatLikeActivity);
    }

    /**
     * Note whether a process has bound to a service with
     * {@link android.content.Context.BIND_ABOVE_CLIENT} or not.
     */
    public void setHasAboveClient(@NonNull ProcessServiceRecord psr, boolean hasAboveClient) {
        psr.setHasAboveClient(hasAboveClient);
    }

    /**
     * Recompute whether a process has bound to a service with
     * {@link android.content.Context.BIND_ABOVE_CLIENT} or not.
     */
    public void updateHasAboveClientLocked(@NonNull ProcessServiceRecord psr) {
        psr.updateHasAboveClientLocked();
    }

    /**
     * Cleanup a process's state.
     */
    public void onCleanupApplicationRecord(@NonNull ProcessServiceRecord psr) {
        psr.onCleanupApplicationRecordLocked();
    }

    /**
     * Set which process is hosting a service.
     */
    public void setHostProcess(@NonNull ServiceRecord sr, @Nullable ProcessRecord host) {
        sr.app = host;
    }

    /**
     * Note whether a service is a Foreground Service or not
     */
    public void setIsForegroundService(@NonNull ServiceRecord sr, boolean isFgs) {
        sr.isForeground = isFgs;
    }

    /**
     * Note the Foreground Service type of a service.
     */
    public void setForegroundServiceType(@NonNull ServiceRecord sr,
            @ServiceInfo.ForegroundServiceType int fgsType) {
        sr.foregroundServiceType = fgsType;
    }

    /**
     * Note the start time of a short foreground service.
     */
    public void setShortFgsInfo(@NonNull ServiceRecord sr, long uptimeNow) {
        sr.setShortFgsInfo(uptimeNow);
    }

    /**
     * Note that a short foreground service has stopped.
     */
    public void clearShortFgsInfo(@NonNull ServiceRecord sr) {
        sr.clearShortFgsInfo();
    }

    /**
     * Note the last time a service was active.
     */
    public void setServiceLastActivityTime(@NonNull ServiceRecord sr, long lastActivityUpdateMs) {
        sr.lastActivity = lastActivityUpdateMs;
    }

    /**
     * Note that a service start was requested.
     */
    public void setStartRequested(@NonNull ServiceRecord sr, boolean startRequested) {
        sr.startRequested = startRequested;
    }

    /**
     * Note the last time the service was bound by a Top process with
     * {@link android.content.Context.BIND_ALMOST_PERCEPTIBLE}
     */
    public void setLastTopAlmostPerceptibleBindRequest(@NonNull ServiceRecord sr,
            long lastTopAlmostPerceptibleBindRequestUptimeMs) {
        sr.lastTopAlmostPerceptibleBindRequestUptimeMs =
                lastTopAlmostPerceptibleBindRequestUptimeMs;
    }

    /**
     * Recompute whether a process has bound to a service with
     * {@link android.content.Context.BIND_ALMOST_PERCEPTIBLE} or not.
     */
    public void updateHasTopStartedAlmostPerceptibleServices(@NonNull ProcessServiceRecord psr) {
        psr.updateHasTopStartedAlmostPerceptibleServices();
    }

    /**
     * Builder for ProcessStateController.
     */
    public static class Builder {
        private final ActivityManagerService mAms;
        private final ProcessList mProcessList;
        private final ActiveUids mActiveUids;

        private ServiceThread mHandlerThread = null;
        private CachedAppOptimizer mCachedAppOptimizer = null;
        private OomAdjuster.Injector mOomAdjInjector = null;
        private boolean mUseOomAdjusterModernImpl = false;

        public Builder(ActivityManagerService ams, ProcessList processList, ActiveUids activeUids) {
            mAms = ams;
            mProcessList = processList;
            mActiveUids = activeUids;
        }

        /**
         * Build the ProcessStateController object.
         */
        public ProcessStateController build() {
            if (mHandlerThread == null) {
                mHandlerThread = OomAdjuster.createAdjusterThread();
            }
            if (mCachedAppOptimizer == null) {
                mCachedAppOptimizer = new CachedAppOptimizer(mAms);
            }
            if (mOomAdjInjector == null) {
                mOomAdjInjector = new OomAdjuster.Injector();
            }
            return new ProcessStateController(mAms, mProcessList, mActiveUids, mHandlerThread,
                    mCachedAppOptimizer, mOomAdjInjector, mUseOomAdjusterModernImpl);
        }

        /**
         * For Testing Purposes. Set what thread OomAdjuster will offload tasks on to.
         */
        @VisibleForTesting
        public Builder setHandlerThread(ServiceThread handlerThread) {
            mHandlerThread = handlerThread;
            return this;
        }

        /**
         * For Testing Purposes. Set the CachedAppOptimzer used by OomAdjuster.
         */
        @VisibleForTesting
        public Builder setCachedAppOptimizer(CachedAppOptimizer cachedAppOptimizer) {
            mCachedAppOptimizer = cachedAppOptimizer;
            return this;
        }

        /**
         * For Testing Purposes. Set an injector for OomAdjuster.
         */
        @VisibleForTesting
        public Builder setOomAdjusterInjector(OomAdjuster.Injector injector) {
            mOomAdjInjector = injector;
            return this;
        }

        /**
         * Set which implementation of OomAdjuster to use.
         */
        public Builder useModernOomAdjuster(boolean use) {
            mUseOomAdjusterModernImpl = use;
            return this;
        }
    }
}
