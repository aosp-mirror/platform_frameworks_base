/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.tv.tunerresourcemanager;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.tv.tuner.DemuxFilterMainType;
import android.media.IResourceManagerService;
import android.media.tv.TvInputManager;
import android.media.tv.tunerresourcemanager.CasSessionRequest;
import android.media.tv.tunerresourcemanager.IResourcesReclaimListener;
import android.media.tv.tunerresourcemanager.ITunerResourceManager;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerCiCamRequest;
import android.media.tv.tunerresourcemanager.TunerDemuxInfo;
import android.media.tv.tunerresourcemanager.TunerDemuxRequest;
import android.media.tv.tunerresourcemanager.TunerDescramblerRequest;
import android.media.tv.tunerresourcemanager.TunerFrontendInfo;
import android.media.tv.tunerresourcemanager.TunerFrontendRequest;
import android.media.tv.tunerresourcemanager.TunerLnbRequest;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * This class provides a system service that manages the TV tuner resources.
 *
 * @hide
 */
public class TunerResourceManagerService extends SystemService implements IBinder.DeathRecipient {
    private static final String TAG = "TunerResourceManagerService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    public static final int INVALID_CLIENT_ID = -1;
    private static final int MAX_CLIENT_PRIORITY = 1000;
    private static final long INVALID_THREAD_ID = -1;
    private static final long TRMS_LOCK_TIMEOUT = 500;

    private static final int INVALID_FE_COUNT = -1;

    // Map of the registered client profiles
    private Map<Integer, ClientProfile> mClientProfiles = new HashMap<>();
    private int mNextUnusedClientId = 0;

    // Map of the current available frontend resources
    private Map<Integer, FrontendResource> mFrontendResources = new HashMap<>();
    // SparseIntArray of the max usable number for each frontend resource type
    private SparseIntArray mFrontendMaxUsableNums = new SparseIntArray();
    // SparseIntArray of the currently used number for each frontend resource type
    private SparseIntArray mFrontendUsedNums = new SparseIntArray();
    // SparseIntArray of the existing number for each frontend resource type
    private SparseIntArray mFrontendExistingNums = new SparseIntArray();

    // Backups for the frontend resource maps for enabling testing with custom resource maps
    // such as TunerTest.testHasUnusedFrontend1()
    private Map<Integer, FrontendResource> mFrontendResourcesBackup = new HashMap<>();
    private SparseIntArray mFrontendMaxUsableNumsBackup = new SparseIntArray();
    private SparseIntArray mFrontendUsedNumsBackup = new SparseIntArray();
    private SparseIntArray mFrontendExistingNumsBackup = new SparseIntArray();

    // Map of the current available demux resources
    private Map<Integer, DemuxResource> mDemuxResources = new HashMap<>();
    // Map of the current available lnb resources
    private Map<Integer, LnbResource> mLnbResources = new HashMap<>();
    // Map of the current available Cas resources
    private Map<Integer, CasResource> mCasResources = new HashMap<>();
    // Map of the current available CiCam resources
    private Map<Integer, CiCamResource> mCiCamResources = new HashMap<>();

    @GuardedBy("mLock")
    private Map<Integer, ResourcesReclaimListenerRecord> mListeners = new HashMap<>();

    private TvInputManager mTvInputManager;
    private ActivityManager mActivityManager;
    private IResourceManagerService mMediaResourceManager;
    private UseCasePriorityHints mPriorityCongfig = new UseCasePriorityHints();

    // An internal resource request count to help generate resource handle.
    private int mResourceRequestCount = 0;

    // Used to synchronize the access to the service.
    private final Object mLock = new Object();

    private final ReentrantLock mLockForTRMSLock = new ReentrantLock();
    private final Condition mTunerApiLockReleasedCV = mLockForTRMSLock.newCondition();
    private int mTunerApiLockHolder = INVALID_CLIENT_ID;
    private long mTunerApiLockHolderThreadId = INVALID_THREAD_ID;
    private int mTunerApiLockNestedCount = 0;

    public TunerResourceManagerService(@Nullable Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        onStart(false /*isForTesting*/);
    }

    @VisibleForTesting
    protected void onStart(boolean isForTesting) {
        if (!isForTesting) {
            publishBinderService(Context.TV_TUNER_RESOURCE_MGR_SERVICE, new BinderService());
        }
        mTvInputManager = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
        mActivityManager =
                (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
        mPriorityCongfig.parse();

        // Call SystemProperties.set() in mock app will throw exception because of permission.
        if (!isForTesting) {
            final boolean lazyHal = SystemProperties.getBoolean("ro.tuner.lazyhal", false);
            if (!lazyHal) {
                // The HAL is not a lazy HAL, enable the tuner server.
                SystemProperties.set("tuner.server.enable", "true");
            }
        }

        if (mMediaResourceManager == null) {
            IBinder mediaResourceManagerBinder = getBinderService("media.resource_manager");
            if (mediaResourceManagerBinder == null) {
                Slog.w(TAG, "Resource Manager Service not available.");
                return;
            }
            try {
                mediaResourceManagerBinder.linkToDeath(this, /*flags*/ 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not link to death of native resource manager service.");
                return;
            }
            mMediaResourceManager = IResourceManagerService.Stub.asInterface(
                    mediaResourceManagerBinder);
        }
    }

    private final class BinderService extends ITunerResourceManager.Stub {
        @Override
        public void registerClientProfile(@NonNull ResourceClientProfile profile,
                @NonNull IResourcesReclaimListener listener, @NonNull int[] clientId)
                throws RemoteException {
            enforceTrmAccessPermission("registerClientProfile");
            enforceTunerAccessPermission("registerClientProfile");
            if (profile == null) {
                throw new RemoteException("ResourceClientProfile can't be null");
            }

            if (clientId == null) {
                throw new RemoteException("clientId can't be null!");
            }

            if (listener == null) {
                throw new RemoteException("IResourcesReclaimListener can't be null!");
            }

            if (!mPriorityCongfig.isDefinedUseCase(profile.useCase)) {
                throw new RemoteException("Use undefined client use case:" + profile.useCase);
            }

            synchronized (mLock) {
                registerClientProfileInternal(profile, listener, clientId);
            }
        }

        @Override
        public void unregisterClientProfile(int clientId) throws RemoteException {
            enforceTrmAccessPermission("unregisterClientProfile");
            unregisterClientProfileInternal(clientId);
        }

        @Override
        public boolean updateClientPriority(int clientId, int priority, int niceValue) {
            enforceTrmAccessPermission("updateClientPriority");
            synchronized (mLock) {
                return updateClientPriorityInternal(clientId, priority, niceValue);
            }
        }

        @Override
        public boolean hasUnusedFrontend(int frontendType) {
            enforceTrmAccessPermission("hasUnusedFrontend");
            synchronized (mLock) {
                return hasUnusedFrontendInternal(frontendType);
            }
        }

        @Override
        public boolean isLowestPriority(int clientId, int frontendType)
                throws RemoteException {
            enforceTrmAccessPermission("isLowestPriority");
            synchronized (mLock) {
                if (!checkClientExists(clientId)) {
                    throw new RemoteException("isLowestPriority called from unregistered client: "
                            + clientId);
                }
                return isLowestPriorityInternal(clientId, frontendType);
            }
        }

        @Override
        public void setFrontendInfoList(@NonNull TunerFrontendInfo[] infos) throws RemoteException {
            enforceTrmAccessPermission("setFrontendInfoList");
            if (infos == null) {
                throw new RemoteException("TunerFrontendInfo can't be null");
            }
            synchronized (mLock) {
                setFrontendInfoListInternal(infos);
            }
        }

        @Override
        public void setDemuxInfoList(@NonNull TunerDemuxInfo[] infos) throws RemoteException {
            enforceTrmAccessPermission("setDemuxInfoList");
            if (infos == null) {
                throw new RemoteException("TunerDemuxInfo can't be null");
            }
            synchronized (mLock) {
                setDemuxInfoListInternal(infos);
            }
        }

        @Override
        public void updateCasInfo(int casSystemId, int maxSessionNum) {
            enforceTrmAccessPermission("updateCasInfo");
            synchronized (mLock) {
                updateCasInfoInternal(casSystemId, maxSessionNum);
            }
        }

        @Override
        public void setLnbInfoList(int[] lnbHandles) throws RemoteException {
            enforceTrmAccessPermission("setLnbInfoList");
            if (lnbHandles == null) {
                throw new RemoteException("Lnb handle list can't be null");
            }
            synchronized (mLock) {
                setLnbInfoListInternal(lnbHandles);
            }
        }

        @Override
        public boolean requestFrontend(@NonNull TunerFrontendRequest request,
                @NonNull int[] frontendHandle) {
            enforceTunerAccessPermission("requestFrontend");
            enforceTrmAccessPermission("requestFrontend");
            if (frontendHandle == null) {
                Slog.e(TAG, "frontendHandle can't be null");
                return false;
            }
            return requestFrontendInternal(request, frontendHandle);
        }

        @Override
        public boolean setMaxNumberOfFrontends(int frontendType, int maxUsableNum) {
            enforceTunerAccessPermission("setMaxNumberOfFrontends");
            enforceTrmAccessPermission("setMaxNumberOfFrontends");
            if (maxUsableNum < 0) {
                Slog.w(TAG, "setMaxNumberOfFrontends failed with maxUsableNum:" + maxUsableNum
                        + " frontendType:" + frontendType);
                return false;
            }
            synchronized (mLock) {
                return setMaxNumberOfFrontendsInternal(frontendType, maxUsableNum);
            }
        }

        @Override
        public int getMaxNumberOfFrontends(int frontendType) {
            enforceTunerAccessPermission("getMaxNumberOfFrontends");
            enforceTrmAccessPermission("getMaxNumberOfFrontends");
            synchronized (mLock) {
                return getMaxNumberOfFrontendsInternal(frontendType);
            }
        }

        @Override
        public void shareFrontend(int selfClientId, int targetClientId) throws RemoteException {
            enforceTunerAccessPermission("shareFrontend");
            enforceTrmAccessPermission("shareFrontend");
            synchronized (mLock) {
                if (!checkClientExists(selfClientId)) {
                    throw new RemoteException("Share frontend request from an unregistered client:"
                            + selfClientId);
                }
                if (!checkClientExists(targetClientId)) {
                    throw new RemoteException("Request to share frontend with an unregistered "
                            + "client:" + targetClientId);
                }
                if (getClientProfile(targetClientId).getInUseFrontendHandles().isEmpty()) {
                    throw new RemoteException("Request to share frontend with a client that has no "
                            + "frontend resources. Target client id:" + targetClientId);
                }
                shareFrontendInternal(selfClientId, targetClientId);
            }
        }

        @Override
        public boolean transferOwner(int resourceType, int currentOwnerId, int newOwnerId) {
            enforceTunerAccessPermission("transferOwner");
            enforceTrmAccessPermission("transferOwner");
            synchronized (mLock) {
                if (!checkClientExists(currentOwnerId)) {
                    Slog.e(TAG, "currentOwnerId:" + currentOwnerId + " does not exit");
                    return false;
                }
                if (!checkClientExists(newOwnerId)) {
                    Slog.e(TAG, "newOwnerId:" + newOwnerId + " does not exit");
                    return false;
                }
                return transferOwnerInternal(resourceType, currentOwnerId, newOwnerId);
            }
        }

        @Override
        public boolean requestDemux(@NonNull TunerDemuxRequest request,
                    @NonNull int[] demuxHandle) throws RemoteException {
            enforceTunerAccessPermission("requestDemux");
            enforceTrmAccessPermission("requestDemux");
            if (demuxHandle == null) {
                throw new RemoteException("demuxHandle can't be null");
            }
            return requestDemuxInternal(request, demuxHandle);
        }

        @Override
        public boolean requestDescrambler(@NonNull TunerDescramblerRequest request,
                    @NonNull int[] descramblerHandle) throws RemoteException {
            enforceDescramblerAccessPermission("requestDescrambler");
            enforceTrmAccessPermission("requestDescrambler");
            if (descramblerHandle == null) {
                throw new RemoteException("descramblerHandle can't be null");
            }
            synchronized (mLock) {
                if (!checkClientExists(request.clientId)) {
                    throw new RemoteException("Request descrambler from unregistered client:"
                            + request.clientId);
                }
                return requestDescramblerInternal(request, descramblerHandle);
            }
        }

        @Override
        public boolean requestCasSession(@NonNull CasSessionRequest request,
                @NonNull int[] casSessionHandle) throws RemoteException {
            enforceTrmAccessPermission("requestCasSession");
            if (casSessionHandle == null) {
                throw new RemoteException("casSessionHandle can't be null");
            }
            return requestCasSessionInternal(request, casSessionHandle);
        }

        @Override
        public boolean requestCiCam(@NonNull TunerCiCamRequest request,
                @NonNull int[] ciCamHandle) throws RemoteException {
            enforceTrmAccessPermission("requestCiCam");
            if (ciCamHandle == null) {
                throw new RemoteException("ciCamHandle can't be null");
            }
            return requestCiCamInternal(request, ciCamHandle);
        }

        @Override
        public boolean requestLnb(@NonNull TunerLnbRequest request, @NonNull int[] lnbHandle)
                throws RemoteException {
            enforceTunerAccessPermission("requestLnb");
            enforceTrmAccessPermission("requestLnb");
            if (lnbHandle == null) {
                throw new RemoteException("lnbHandle can't be null");
            }
            return requestLnbInternal(request, lnbHandle);
        }

        @Override
        public void releaseFrontend(int frontendHandle, int clientId) throws RemoteException {
            enforceTunerAccessPermission("releaseFrontend");
            enforceTrmAccessPermission("releaseFrontend");
            releaseFrontendInternal(frontendHandle, clientId);
        }

        @Override
        public void releaseDemux(int demuxHandle, int clientId) throws RemoteException {
            enforceTunerAccessPermission("releaseDemux");
            enforceTrmAccessPermission("releaseDemux");
            if (DEBUG) {
                Slog.e(TAG, "releaseDemux(demuxHandle=" + demuxHandle + ")");
            }

            synchronized (mLock) {
                // For Tuner 2.0 and below or any HW constraint devices that are unable to support
                // ITuner.openDemuxById(), demux resources are not really managed under TRM and
                // mDemuxResources.size() will be zero
                if (mDemuxResources.size() == 0) {
                    return;
                }

                if (!checkClientExists(clientId)) {
                    throw new RemoteException("Release demux for unregistered client:" + clientId);
                }
                DemuxResource demux = getDemuxResource(demuxHandle);
                if (demux == null) {
                    throw new RemoteException("Releasing demux does not exist.");
                }
                if (demux.getOwnerClientId() != clientId) {
                    throw new RemoteException("Client is not the current owner "
                            + "of the releasing demux.");
                }
                releaseDemuxInternal(demux);
            }
        }

        @Override
        public void releaseDescrambler(int descramblerHandle, int clientId) {
            enforceTunerAccessPermission("releaseDescrambler");
            enforceTrmAccessPermission("releaseDescrambler");
            if (DEBUG) {
                Slog.d(TAG, "releaseDescrambler(descramblerHandle=" + descramblerHandle + ")");
            }
        }

        @Override
        public void releaseCasSession(int casSessionHandle, int clientId) throws RemoteException {
            enforceTrmAccessPermission("releaseCasSession");
            if (!validateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_CAS_SESSION, casSessionHandle)) {
                throw new RemoteException("casSessionHandle can't be invalid");
            }
            synchronized (mLock) {
                if (!checkClientExists(clientId)) {
                    throw new RemoteException("Release cas from unregistered client:" + clientId);
                }
                int casSystemId = getClientProfile(clientId).getInUseCasSystemId();
                CasResource cas = getCasResource(casSystemId);
                if (cas == null) {
                    throw new RemoteException("Releasing cas does not exist.");
                }
                if (!cas.getOwnerClientIds().contains(clientId)) {
                    throw new RemoteException(
                            "Client is not the current owner of the releasing cas.");
                }
                releaseCasSessionInternal(cas, clientId);
            }
        }

        @Override
        public void releaseCiCam(int ciCamHandle, int clientId) throws RemoteException {
            enforceTrmAccessPermission("releaseCiCam");
            if (!validateResourceHandle(
                    TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM, ciCamHandle)) {
                throw new RemoteException("ciCamHandle can't be invalid");
            }
            synchronized (mLock) {
                if (!checkClientExists(clientId)) {
                    throw new RemoteException("Release ciCam from unregistered client:" + clientId);
                }
                int ciCamId = getClientProfile(clientId).getInUseCiCamId();
                if (ciCamId != getResourceIdFromHandle(ciCamHandle)) {
                    throw new RemoteException("The client " + clientId + " is not the owner of "
                            + "the releasing ciCam.");
                }
                CiCamResource ciCam = getCiCamResource(ciCamId);
                if (ciCam == null) {
                    throw new RemoteException("Releasing ciCam does not exist.");
                }
                if (!ciCam.getOwnerClientIds().contains(clientId)) {
                    throw new RemoteException(
                            "Client is not the current owner of the releasing ciCam.");
                }
                releaseCiCamInternal(ciCam, clientId);
            }
        }

        @Override
        public void releaseLnb(int lnbHandle, int clientId) throws RemoteException {
            enforceTunerAccessPermission("releaseLnb");
            enforceTrmAccessPermission("releaseLnb");
            if (!validateResourceHandle(TunerResourceManager.TUNER_RESOURCE_TYPE_LNB, lnbHandle)) {
                throw new RemoteException("lnbHandle can't be invalid");
            }
            synchronized (mLock) {
                if (!checkClientExists(clientId)) {
                    throw new RemoteException("Release lnb from unregistered client:" + clientId);
                }
                LnbResource lnb = getLnbResource(lnbHandle);
                if (lnb == null) {
                    throw new RemoteException("Releasing lnb does not exist.");
                }
                if (lnb.getOwnerClientId() != clientId) {
                    throw new RemoteException("Client is not the current owner "
                            + "of the releasing lnb.");
                }
                releaseLnbInternal(lnb);
            }
        }

        @Override
        public boolean isHigherPriority(
                ResourceClientProfile challengerProfile, ResourceClientProfile holderProfile)
                throws RemoteException {
            enforceTrmAccessPermission("isHigherPriority");
            if (challengerProfile == null || holderProfile == null) {
                throw new RemoteException("Client profiles can't be null.");
            }
            synchronized (mLock) {
                return isHigherPriorityInternal(challengerProfile, holderProfile);
            }
        }

        @Override
        public void storeResourceMap(int resourceType) {
            enforceTrmAccessPermission("storeResourceMap");
            synchronized (mLock) {
                storeResourceMapInternal(resourceType);
            }
        }

        @Override
        public void clearResourceMap(int resourceType) {
            enforceTrmAccessPermission("clearResourceMap");
            synchronized (mLock) {
                clearResourceMapInternal(resourceType);
            }
        }

        @Override
        public void restoreResourceMap(int resourceType) {
            enforceTrmAccessPermission("restoreResourceMap");
            synchronized (mLock) {
                restoreResourceMapInternal(resourceType);
            }
        }

        @Override
        public boolean acquireLock(int clientId, long clientThreadId) {
            enforceTrmAccessPermission("acquireLock");
            // this must not be locked with mLock
            return acquireLockInternal(clientId, clientThreadId, TRMS_LOCK_TIMEOUT);
        }

        @Override
        public boolean releaseLock(int clientId) {
            enforceTrmAccessPermission("releaseLock");
            // this must not be locked with mLock
            return releaseLockInternal(clientId, TRMS_LOCK_TIMEOUT, false, false);
        }

        @Override
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

            if (getContext().checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump!");
                return;
            }

            synchronized (mLock) {
                dumpMap(mClientProfiles, "ClientProfiles:", "\n", pw);
                dumpMap(mFrontendResources, "FrontendResources:", "\n", pw);
                dumpSIA(mFrontendExistingNums, "FrontendExistingNums:", ", ", pw);
                dumpSIA(mFrontendUsedNums, "FrontendUsedNums:", ", ", pw);
                dumpSIA(mFrontendMaxUsableNums, "FrontendMaxUsableNums:", ", ", pw);
                dumpMap(mFrontendResourcesBackup, "FrontendResourcesBackUp:", "\n", pw);
                dumpSIA(mFrontendExistingNumsBackup, "FrontendExistingNumsBackup:", ", ", pw);
                dumpSIA(mFrontendUsedNumsBackup, "FrontendUsedNumsBackup:", ", ", pw);
                dumpSIA(mFrontendMaxUsableNumsBackup, "FrontendUsedNumsBackup:", ", ", pw);
                dumpMap(mDemuxResources, "DemuxResource:", "\n", pw);
                dumpMap(mLnbResources, "LnbResource:", "\n", pw);
                dumpMap(mCasResources, "CasResource:", "\n", pw);
                dumpMap(mCiCamResources, "CiCamResource:", "\n", pw);
                dumpMap(mListeners, "Listners:", "\n", pw);
            }
        }

        @Override
        public int getClientPriority(int useCase, int pid) throws RemoteException {
            enforceTrmAccessPermission("getClientPriority");
            synchronized (mLock) {
                return TunerResourceManagerService.this.getClientPriority(
                        useCase, checkIsForeground(pid));
            }
        }
        @Override
        public int getConfigPriority(int useCase, boolean isForeground) throws RemoteException {
            enforceTrmAccessPermission("getConfigPriority");
            synchronized (mLock) {
                return TunerResourceManagerService.this.getClientPriority(useCase, isForeground);
            }
        }
    }

    /**
     * Handle the death of the native resource manager service
     */
    @Override
    public void binderDied() {
        if (DEBUG) {
            Slog.w(TAG, "Native media resource manager service has died");
        }
        synchronized (mLock) {
            mMediaResourceManager = null;
        }
    }

    @VisibleForTesting
    protected void registerClientProfileInternal(ResourceClientProfile profile,
            IResourcesReclaimListener listener, int[] clientId) {
        if (DEBUG) {
            Slog.d(TAG, "registerClientProfile(clientProfile=" + profile + ")");
        }

        clientId[0] = INVALID_CLIENT_ID;
        if (mTvInputManager == null) {
            Slog.e(TAG, "TvInputManager is null. Can't register client profile.");
            return;
        }
        // TODO tell if the client already exists
        clientId[0] = mNextUnusedClientId++;

        int pid = profile.tvInputSessionId == null
                ? Binder.getCallingPid() /*callingPid*/
                : mTvInputManager.getClientPid(profile.tvInputSessionId); /*tvAppId*/

        // Update Media Resource Manager with the tvAppId
        if (profile.tvInputSessionId != null && mMediaResourceManager != null) {
            try {
                mMediaResourceManager.overridePid(Binder.getCallingPid(), pid);
            } catch (RemoteException e) {
                Slog.e(TAG, "Could not overridePid in resourceManagerSercice,"
                        + " remote exception: " + e);
            }
        }

        ClientProfile clientProfile = new ClientProfile.Builder(clientId[0])
                                              .tvInputSessionId(profile.tvInputSessionId)
                                              .useCase(profile.useCase)
                                              .processId(pid)
                                              .build();
        clientProfile.setPriority(
                getClientPriority(profile.useCase, checkIsForeground(pid)));

        addClientProfile(clientId[0], clientProfile, listener);
    }

    @VisibleForTesting
    protected void unregisterClientProfileInternal(int clientId) {
        synchronized (mLock) {
            if (!checkClientExists(clientId)) {
                Slog.e(TAG, "Unregistering non exists client:" + clientId);
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "unregisterClientProfile(clientId=" + clientId + ")");
            }
            removeClientProfile(clientId);
            // Remove the Media Resource Manager callingPid to tvAppId mapping
            if (mMediaResourceManager != null) {
                try {
                    mMediaResourceManager.overridePid(Binder.getCallingPid(), -1);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Could not overridePid in resourceManagerSercice when unregister,"
                            + " remote exception: " + e);
                }
            }
        }
    }

    @VisibleForTesting
    protected boolean updateClientPriorityInternal(int clientId, int priority, int niceValue) {
        if (DEBUG) {
            Slog.d(TAG,
                    "updateClientPriority(clientId=" + clientId + ", priority=" + priority
                            + ", niceValue=" + niceValue + ")");
        }

        ClientProfile profile = getClientProfile(clientId);
        if (profile == null) {
            Slog.e(TAG,
                    "Can not find client profile with id " + clientId
                            + " when trying to update the client priority.");
            return false;
        }

        profile.overwritePriority(priority);
        profile.setNiceValue(niceValue);

        return true;
    }


    protected boolean hasUnusedFrontendInternal(int frontendType) {
        for (FrontendResource fr : getFrontendResources().values()) {
            if (fr.getType() == frontendType && !fr.isInUse()) {
                return true;
            }
        }
        return false;
    }

    protected boolean isLowestPriorityInternal(int clientId, int frontendType)
            throws RemoteException {
        // Update the client priority
        ClientProfile requestClient = getClientProfile(clientId);
        if (requestClient == null) {
            return true;
        }
        clientPriorityUpdateOnRequest(requestClient);
        int clientPriority = requestClient.getPriority();

        // Check if there is another holder with lower priority
        for (FrontendResource fr : getFrontendResources().values()) {
            if (fr.getType() == frontendType && fr.isInUse()) {
                int priority = updateAndGetOwnerClientPriority(fr.getOwnerClientId());
                // Returns false only when the clientPriority is strictly greater
                // because false means that there is another reclaimable resource
                if (clientPriority > priority) {
                    return false;
                }
            }
        }
        return true;
    }

    protected void storeResourceMapInternal(int resourceType) {
        switch (resourceType) {
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND:
                replaceFeResourceMap(mFrontendResources, mFrontendResourcesBackup);
                replaceFeCounts(mFrontendExistingNums, mFrontendExistingNumsBackup);
                replaceFeCounts(mFrontendUsedNums, mFrontendUsedNumsBackup);
                replaceFeCounts(mFrontendMaxUsableNums, mFrontendMaxUsableNumsBackup);
                break;
                // TODO: implement for other resource type when needed
            default:
                break;
        }
    }

    protected void clearResourceMapInternal(int resourceType) {
        switch (resourceType) {
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND:
                replaceFeResourceMap(null, mFrontendResources);
                replaceFeCounts(null, mFrontendExistingNums);
                replaceFeCounts(null, mFrontendUsedNums);
                replaceFeCounts(null, mFrontendMaxUsableNums);
                break;
                // TODO: implement for other resource type when needed
            default:
                break;
        }
    }

    protected void restoreResourceMapInternal(int resourceType) {
        switch (resourceType) {
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND:
                replaceFeResourceMap(mFrontendResourcesBackup, mFrontendResources);
                replaceFeCounts(mFrontendExistingNumsBackup, mFrontendExistingNums);
                replaceFeCounts(mFrontendUsedNumsBackup, mFrontendUsedNums);
                replaceFeCounts(mFrontendMaxUsableNumsBackup, mFrontendMaxUsableNums);
                break;
                // TODO: implement for other resource type when needed
            default:
                break;
        }
    }

    @VisibleForTesting
    protected void setFrontendInfoListInternal(TunerFrontendInfo[] infos) {
        if (DEBUG) {
            Slog.d(TAG, "updateFrontendInfo:");
            for (int i = 0; i < infos.length; i++) {
                Slog.d(TAG, infos[i].toString());
            }
        }

        // A set to record the frontends pending on updating. Ids will be removed
        // from this set once its updating finished. Any frontend left in this set when all
        // the updates are done will be removed from mFrontendResources.
        Set<Integer> updatingFrontendHandles = new HashSet<>(getFrontendResources().keySet());

        // Update frontendResources map and other mappings accordingly
        for (int i = 0; i < infos.length; i++) {
            if (getFrontendResource(infos[i].handle) != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Frontend handle=" + infos[i].handle + "exists.");
                }
                updatingFrontendHandles.remove(infos[i].handle);
            } else {
                // Add a new fe resource
                FrontendResource newFe = new FrontendResource.Builder(infos[i].handle)
                                                 .type(infos[i].type)
                                                 .exclusiveGroupId(infos[i].exclusiveGroupId)
                                                 .build();
                addFrontendResource(newFe);
            }
        }

        for (int removingHandle : updatingFrontendHandles) {
            // update the exclusive group id member list
            removeFrontendResource(removingHandle);
        }
    }

    @VisibleForTesting
    protected void setDemuxInfoListInternal(TunerDemuxInfo[] infos) {
        if (DEBUG) {
            Slog.d(TAG, "updateDemuxInfo:");
            for (int i = 0; i < infos.length; i++) {
                Slog.d(TAG, infos[i].toString());
            }
        }

        // A set to record the demuxes pending on updating. Ids will be removed
        // from this set once its updating finished. Any demux left in this set when all
        // the updates are done will be removed from mDemuxResources.
        Set<Integer> updatingDemuxHandles = new HashSet<>(getDemuxResources().keySet());

        // Update demuxResources map and other mappings accordingly
        for (int i = 0; i < infos.length; i++) {
            if (getDemuxResource(infos[i].handle) != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Demux handle=" + infos[i].handle + "exists.");
                }
                updatingDemuxHandles.remove(infos[i].handle);
            } else {
                // Add a new demux resource
                DemuxResource newDemux = new DemuxResource.Builder(infos[i].handle)
                                                 .filterTypes(infos[i].filterTypes)
                                                 .build();
                addDemuxResource(newDemux);
            }
        }

        for (int removingHandle : updatingDemuxHandles) {
            // update the exclusive group id member list
            removeDemuxResource(removingHandle);
        }
    }
    @VisibleForTesting
    protected void setLnbInfoListInternal(int[] lnbHandles) {
        if (DEBUG) {
            for (int i = 0; i < lnbHandles.length; i++) {
                Slog.d(TAG, "updateLnbInfo(lnbHanle=" + lnbHandles[i] + ")");
            }
        }

        // A set to record the Lnbs pending on updating. Handles will be removed
        // from this set once its updating finished. Any lnb left in this set when all
        // the updates are done will be removed from mLnbResources.
        Set<Integer> updatingLnbHandles = new HashSet<>(getLnbResources().keySet());

        // Update lnbResources map and other mappings accordingly
        for (int i = 0; i < lnbHandles.length; i++) {
            if (getLnbResource(lnbHandles[i]) != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Lnb handle=" + lnbHandles[i] + "exists.");
                }
                updatingLnbHandles.remove(lnbHandles[i]);
            } else {
                // Add a new lnb resource
                LnbResource newLnb = new LnbResource.Builder(lnbHandles[i]).build();
                addLnbResource(newLnb);
            }
        }

        for (int removingHandle : updatingLnbHandles) {
            removeLnbResource(removingHandle);
        }
    }

    @VisibleForTesting
    protected void updateCasInfoInternal(int casSystemId, int maxSessionNum) {
        if (DEBUG) {
            Slog.d(TAG,
                    "updateCasInfo(casSystemId=" + casSystemId
                            + ", maxSessionNum=" + maxSessionNum + ")");
        }
        // If maxSessionNum is 0, removing the Cas Resource.
        if (maxSessionNum == 0) {
            removeCasResource(casSystemId);
            removeCiCamResource(casSystemId);
            return;
        }
        // If the Cas exists, updates the Cas Resource accordingly.
        CasResource cas = getCasResource(casSystemId);
        CiCamResource ciCam = getCiCamResource(casSystemId);
        if (cas != null) {
            if (cas.getUsedSessionNum() > maxSessionNum) {
                // Sort and release the short number of Cas resources.
                int releasingCasResourceNum = cas.getUsedSessionNum() - maxSessionNum;
                // TODO: handle CiCam session update.
            }
            cas.updateMaxSessionNum(maxSessionNum);
            if (ciCam != null) {
                ciCam.updateMaxSessionNum(maxSessionNum);
            }
            return;
        }
        // Add the new Cas Resource.
        int casSessionHandle = generateResourceHandle(
                TunerResourceManager.TUNER_RESOURCE_TYPE_CAS_SESSION, casSystemId);
        cas = new CasResource.Builder(casSessionHandle, casSystemId)
                             .maxSessionNum(maxSessionNum)
                             .build();
        int ciCamHandle = generateResourceHandle(
                TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM, casSystemId);
        ciCam = new CiCamResource.Builder(ciCamHandle, casSystemId)
                             .maxSessionNum(maxSessionNum)
                             .build();
        addCasResource(cas);
        addCiCamResource(ciCam);
    }

    @VisibleForTesting
    protected boolean requestFrontendInternal(TunerFrontendRequest request, int[] frontendHandle) {
        if (DEBUG) {
            Slog.d(TAG, "requestFrontend(request=" + request + ")");
        }
        int[] reclaimOwnerId = new int[1];
        if (!claimFrontend(request, frontendHandle, reclaimOwnerId)) {
            return false;
        }
        if (frontendHandle[0] == TunerResourceManager.INVALID_RESOURCE_HANDLE) {
            return false;
        }
        if (reclaimOwnerId[0] != INVALID_CLIENT_ID) {
            if (!reclaimResource(reclaimOwnerId[0], TunerResourceManager
                    .TUNER_RESOURCE_TYPE_FRONTEND)) {
                return false;
            }
            synchronized (mLock) {
                if (getFrontendResource(frontendHandle[0]).isInUse()) {
                    Slog.e(TAG, "Reclaimed frontend still in use");
                    return false;
                }
                updateFrontendClientMappingOnNewGrant(frontendHandle[0], request.clientId);
            }
        }
        return true;
    }

    protected boolean claimFrontend(
            TunerFrontendRequest request,
            int[] frontendHandle,
            int[] reclaimOwnerId
    ) {
        frontendHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        reclaimOwnerId[0] = INVALID_CLIENT_ID;
        synchronized (mLock) {
            if (!checkClientExists(request.clientId)) {
                Slog.e(TAG, "Request frontend from unregistered client: "
                        + request.clientId);
                return false;
            }
            // If the request client is holding or sharing a frontend, throw an exception.
            if (!getClientProfile(request.clientId).getInUseFrontendHandles().isEmpty()) {
                Slog.e(TAG, "Release frontend before requesting another one. Client id: "
                        + request.clientId);
                return false;
            }
            ClientProfile requestClient = getClientProfile(request.clientId);
            clientPriorityUpdateOnRequest(requestClient);
            FrontendResource grantingFrontend = null;
            FrontendResource inUseLowestPriorityFrontend = null;
            // Priority max value is 1000
            int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
            boolean isRequestFromSameProcess = false;
            // If the desired frontend id was specified, we only need to check the frontend.
            boolean hasDesiredFrontend = request.desiredId != TunerFrontendRequest
                    .DEFAULT_DESIRED_ID;
            for (FrontendResource fr : getFrontendResources().values()) {
                int frontendId = getResourceIdFromHandle(fr.getHandle());
                if (fr.getType() == request.frontendType
                        && (!hasDesiredFrontend || frontendId == request.desiredId)) {
                    if (!fr.isInUse()) {
                        // Unused resource cannot be acquired if the max is already reached, but
                        // TRM still has to look for the reclaim candidate
                        if (isFrontendMaxNumUseReached(request.frontendType)) {
                            continue;
                        }
                        // Grant unused frontend with no exclusive group members first.
                        if (fr.getExclusiveGroupMemberFeHandles().isEmpty()) {
                            grantingFrontend = fr;
                            break;
                        } else if (grantingFrontend == null) {
                            // Grant the unused frontend with lower id first if all the unused
                            // frontends have exclusive group members.
                            grantingFrontend = fr;
                        }
                    } else if (grantingFrontend == null) {
                        // Record the frontend id with the lowest client priority among all the
                        // in use frontends when no available frontend has been found.
                        int priority = getFrontendHighestClientPriority(fr.getOwnerClientId());
                        if (currentLowestPriority > priority) {
                            // we need to check the max used num if the target frontend type is not
                            // currently in primary use (and simply blocked due to exclusive group)
                            ClientProfile targetOwnerProfile =
                                    getClientProfile(fr.getOwnerClientId());
                            int primaryFeId = targetOwnerProfile.getPrimaryFrontend();
                            FrontendResource primaryFe = getFrontendResource(primaryFeId);
                            if (fr.getType() != primaryFe.getType()
                                    && isFrontendMaxNumUseReached(fr.getType())) {
                                continue;
                            }
                            // update the target frontend
                            inUseLowestPriorityFrontend = fr;
                            currentLowestPriority = priority;
                            isRequestFromSameProcess = (requestClient.getProcessId()
                                == (getClientProfile(fr.getOwnerClientId())).getProcessId());
                        }
                    }
                }
            }

            // Grant frontend when there is unused resource.
            if (grantingFrontend != null) {
                updateFrontendClientMappingOnNewGrant(grantingFrontend.getHandle(),
                        request.clientId);
                frontendHandle[0] = grantingFrontend.getHandle();
                return true;
            }

            // When all the resources are occupied, grant the lowest priority resource if the
            // request client has higher priority.
            if (inUseLowestPriorityFrontend != null
                    && ((requestClient.getPriority() > currentLowestPriority)
                    || ((requestClient.getPriority() == currentLowestPriority)
                    && isRequestFromSameProcess))) {
                frontendHandle[0] = inUseLowestPriorityFrontend.getHandle();
                reclaimOwnerId[0] = inUseLowestPriorityFrontend.getOwnerClientId();
                return true;
            }
        }

        return false;
    }

    @VisibleForTesting
    protected void shareFrontendInternal(int selfClientId, int targetClientId) {
        if (DEBUG) {
            Slog.d(TAG, "shareFrontend from " + selfClientId + " with " + targetClientId);
        }
        Integer shareeFeClientId = getClientProfile(selfClientId).getShareeFeClientId();
        if (shareeFeClientId != ClientProfile.INVALID_RESOURCE_ID) {
            getClientProfile(shareeFeClientId).stopSharingFrontend(selfClientId);
            getClientProfile(selfClientId).releaseFrontend();
        }
        for (int feId : getClientProfile(targetClientId).getInUseFrontendHandles()) {
            getClientProfile(selfClientId).useFrontend(feId);
        }
        getClientProfile(selfClientId).setShareeFeClientId(targetClientId);
        getClientProfile(targetClientId).shareFrontend(selfClientId);
    }

    private boolean transferFeOwner(int currentOwnerId, int newOwnerId) {
        ClientProfile currentOwnerProfile = getClientProfile(currentOwnerId);
        ClientProfile newOwnerProfile = getClientProfile(newOwnerId);
        // change the owner of all the inUse frontend
        newOwnerProfile.shareFrontend(currentOwnerId);
        currentOwnerProfile.stopSharingFrontend(newOwnerId);
        newOwnerProfile.setShareeFeClientId(ClientProfile.INVALID_RESOURCE_ID);
        currentOwnerProfile.setShareeFeClientId(newOwnerId);
        for (int inUseHandle : newOwnerProfile.getInUseFrontendHandles()) {
            getFrontendResource(inUseHandle).setOwner(newOwnerId);
        }
        // change the primary frontend
        newOwnerProfile.setPrimaryFrontend(currentOwnerProfile.getPrimaryFrontend());
        currentOwnerProfile.setPrimaryFrontend(TunerResourceManager.INVALID_RESOURCE_HANDLE);
        // double check there is no other resources tied to the previous owner
        for (int inUseHandle : currentOwnerProfile.getInUseFrontendHandles()) {
            int ownerId = getFrontendResource(inUseHandle).getOwnerClientId();
            if (ownerId != newOwnerId) {
                Slog.e(TAG, "something is wrong in transferFeOwner:" + inUseHandle
                        + ", " + ownerId + ", " + newOwnerId);
                return false;
            }
        }
        return true;
    }

    private boolean transferFeCiCamOwner(int currentOwnerId, int newOwnerId) {
        ClientProfile currentOwnerProfile = getClientProfile(currentOwnerId);
        ClientProfile newOwnerProfile = getClientProfile(newOwnerId);

        // link ciCamId to the new profile
        int ciCamId = currentOwnerProfile.getInUseCiCamId();
        newOwnerProfile.useCiCam(ciCamId);

        // set the new owner Id
        CiCamResource ciCam = getCiCamResource(ciCamId);
        ciCam.setOwner(newOwnerId);

        // unlink cicam resource from the original owner profile
        currentOwnerProfile.releaseCiCam();
        return true;
    }

    private boolean transferLnbOwner(int currentOwnerId, int newOwnerId) {
        ClientProfile currentOwnerProfile = getClientProfile(currentOwnerId);
        ClientProfile newOwnerProfile = getClientProfile(newOwnerId);

        Set<Integer> inUseLnbHandles = new HashSet<>();
        for (Integer lnbHandle : currentOwnerProfile.getInUseLnbHandles()) {
            // link lnb handle to the new profile
            newOwnerProfile.useLnb(lnbHandle);

            // set new owner Id
            LnbResource lnb = getLnbResource(lnbHandle);
            lnb.setOwner(newOwnerId);

            inUseLnbHandles.add(lnbHandle);
        }

        // unlink lnb handles from the original owner
        for (Integer lnbHandle : inUseLnbHandles) {
            currentOwnerProfile.releaseLnb(lnbHandle);
        }

        return true;
    }

    @VisibleForTesting
    protected boolean transferOwnerInternal(int resourceType, int currentOwnerId, int newOwnerId) {
        switch (resourceType) {
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND:
                return transferFeOwner(currentOwnerId, newOwnerId);
            case TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM:
                return transferFeCiCamOwner(currentOwnerId, newOwnerId);
            case TunerResourceManager.TUNER_RESOURCE_TYPE_LNB:
                return transferLnbOwner(currentOwnerId, newOwnerId);
            default:
                Slog.e(TAG, "transferOwnerInternal. unsupported resourceType: " + resourceType);
                return false;
        }
    }

    @VisibleForTesting
    protected boolean requestLnbInternal(TunerLnbRequest request, int[] lnbHandle)
            throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, "requestLnb(request=" + request + ")");
        }
        int[] reclaimOwnerId = new int[1];
        if (!claimLnb(request, lnbHandle, reclaimOwnerId)) {
            return false;
        }
        if (lnbHandle[0] == TunerResourceManager.INVALID_RESOURCE_HANDLE) {
            return false;
        }
        if (reclaimOwnerId[0] != INVALID_CLIENT_ID) {
            if (!reclaimResource(reclaimOwnerId[0],
                    TunerResourceManager.TUNER_RESOURCE_TYPE_LNB)) {
                return false;
            }
            synchronized (mLock) {
                if (getLnbResource(lnbHandle[0]).isInUse()) {
                    Slog.e(TAG, "Reclaimed lnb still in use");
                    return false;
                }
                updateLnbClientMappingOnNewGrant(lnbHandle[0], request.clientId);
            }
        }
        return true;
    }

    protected boolean claimLnb(TunerLnbRequest request, int[] lnbHandle, int[] reclaimOwnerId)
            throws RemoteException {
        lnbHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        reclaimOwnerId[0] = INVALID_CLIENT_ID;
        synchronized (mLock) {
            if (!checkClientExists(request.clientId)) {
                throw new RemoteException("Request lnb from unregistered client:"
                        + request.clientId);
            }
            ClientProfile requestClient = getClientProfile(request.clientId);
            clientPriorityUpdateOnRequest(requestClient);
            LnbResource grantingLnb = null;
            LnbResource inUseLowestPriorityLnb = null;
            // Priority max value is 1000
            int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
            boolean isRequestFromSameProcess = false;
            for (LnbResource lnb : getLnbResources().values()) {
                if (!lnb.isInUse()) {
                    // Grant the unused lnb with lower handle first
                    grantingLnb = lnb;
                    break;
                } else {
                    // Record the lnb id with the lowest client priority among all the
                    // in use lnb when no available lnb has been found.
                    int priority = updateAndGetOwnerClientPriority(lnb.getOwnerClientId());
                    if (currentLowestPriority > priority) {
                        inUseLowestPriorityLnb = lnb;
                        currentLowestPriority = priority;
                        isRequestFromSameProcess = (requestClient.getProcessId()
                            == (getClientProfile(lnb.getOwnerClientId())).getProcessId());
                    }
                }
            }

            // Grant Lnb when there is unused resource.
            if (grantingLnb != null) {
                updateLnbClientMappingOnNewGrant(grantingLnb.getHandle(), request.clientId);
                lnbHandle[0] = grantingLnb.getHandle();
                return true;
            }

            // When all the resources are occupied, grant the lowest priority resource if the
            // request client has higher priority.
            if (inUseLowestPriorityLnb != null
                    && ((requestClient.getPriority() > currentLowestPriority) || (
                    (requestClient.getPriority() == currentLowestPriority)
                        && isRequestFromSameProcess))) {
                lnbHandle[0] = inUseLowestPriorityLnb.getHandle();
                reclaimOwnerId[0] = inUseLowestPriorityLnb.getOwnerClientId();
                return true;
            }
        }

        return false;
    }

    @VisibleForTesting
    protected boolean requestCasSessionInternal(CasSessionRequest request, int[] casSessionHandle)
            throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, "requestCasSession(request=" + request + ")");
        }
        int[] reclaimOwnerId = new int[1];
        if (!claimCasSession(request, casSessionHandle, reclaimOwnerId)) {
            return false;
        }
        if (casSessionHandle[0] == TunerResourceManager.INVALID_RESOURCE_HANDLE) {
            return false;
        }
        if (reclaimOwnerId[0] != INVALID_CLIENT_ID) {
            if (!reclaimResource(reclaimOwnerId[0],
                    TunerResourceManager.TUNER_RESOURCE_TYPE_CAS_SESSION)) {
                return false;
            }
            synchronized (mLock) {
                if (getCasResource(request.casSystemId).isFullyUsed()) {
                    Slog.e(TAG, "Reclaimed cas still fully used");
                    return false;
                }
                updateCasClientMappingOnNewGrant(request.casSystemId, request.clientId);
            }
        }
        return true;
    }

    protected boolean claimCasSession(CasSessionRequest request, int[] casSessionHandle,
            int[] reclaimOwnerId) throws RemoteException {
        casSessionHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        reclaimOwnerId[0] = INVALID_CLIENT_ID;
        synchronized (mLock) {
            if (!checkClientExists(request.clientId)) {
                throw new RemoteException("Request cas from unregistered client:"
                        + request.clientId);
            }
            CasResource cas = getCasResource(request.casSystemId);
            // Unregistered Cas System is treated as having unlimited sessions.
            if (cas == null) {
                int resourceHandle = generateResourceHandle(
                        TunerResourceManager.TUNER_RESOURCE_TYPE_CAS_SESSION, request.clientId);
                cas = new CasResource.Builder(resourceHandle, request.casSystemId)
                                    .maxSessionNum(Integer.MAX_VALUE)
                                    .build();
                addCasResource(cas);
            }
            ClientProfile requestClient = getClientProfile(request.clientId);
            clientPriorityUpdateOnRequest(requestClient);
            int lowestPriorityOwnerId = INVALID_CLIENT_ID;
            // Priority max value is 1000
            int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
            boolean isRequestFromSameProcess = false;
            if (!cas.isFullyUsed()) {
                updateCasClientMappingOnNewGrant(request.casSystemId, request.clientId);
                casSessionHandle[0] = cas.getHandle();
                return true;
            }
            for (int ownerId : cas.getOwnerClientIds()) {
                // Record the client id with lowest priority that is using the current Cas system.
                int priority = updateAndGetOwnerClientPriority(ownerId);
                if (currentLowestPriority > priority) {
                    lowestPriorityOwnerId = ownerId;
                    currentLowestPriority = priority;
                    isRequestFromSameProcess = (requestClient.getProcessId()
                        == (getClientProfile(ownerId)).getProcessId());
                }
            }

            // When all the Cas sessions are occupied, reclaim the lowest priority client if the
            // request client has higher priority.
            if (lowestPriorityOwnerId != INVALID_CLIENT_ID
                    && ((requestClient.getPriority() > currentLowestPriority)
                    || ((requestClient.getPriority() == currentLowestPriority)
                    && isRequestFromSameProcess))) {
                casSessionHandle[0] = cas.getHandle();
                reclaimOwnerId[0] = lowestPriorityOwnerId;
                return true;
            }
        }

        return false;
    }

    @VisibleForTesting
    protected boolean requestCiCamInternal(TunerCiCamRequest request, int[] ciCamHandle)
            throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, "requestCiCamInternal(TunerCiCamRequest=" + request + ")");
        }
        int[] reclaimOwnerId = new int[1];
        if (!claimCiCam(request, ciCamHandle, reclaimOwnerId)) {
            return false;
        }
        if (ciCamHandle[0] == TunerResourceManager.INVALID_RESOURCE_HANDLE) {
            return false;
        }
        if (reclaimOwnerId[0] != INVALID_CLIENT_ID) {
            if (!reclaimResource(reclaimOwnerId[0],
                    TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM)) {
                return false;
            }
            synchronized (mLock) {
                if (getCiCamResource(request.ciCamId).isFullyUsed()) {
                    Slog.e(TAG, "Reclaimed ciCam still fully used");
                    return false;
                }
                updateCiCamClientMappingOnNewGrant(request.ciCamId, request.clientId);
            }
        }
        return true;
    }

    protected boolean claimCiCam(TunerCiCamRequest request, int[] ciCamHandle,
            int[] reclaimOwnerId) throws RemoteException {
        ciCamHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        reclaimOwnerId[0] = INVALID_CLIENT_ID;
        synchronized (mLock) {
            if (!checkClientExists(request.clientId)) {
                throw new RemoteException("Request ciCam from unregistered client:"
                        + request.clientId);
            }
            CiCamResource ciCam = getCiCamResource(request.ciCamId);
            // Unregistered CiCam is treated as having unlimited sessions.
            if (ciCam == null) {
                int resourceHandle = generateResourceHandle(
                        TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND_CICAM, request.ciCamId);
                ciCam = new CiCamResource.Builder(resourceHandle, request.ciCamId)
                                    .maxSessionNum(Integer.MAX_VALUE)
                                    .build();
                addCiCamResource(ciCam);
            }
            ClientProfile requestClient = getClientProfile(request.clientId);
            clientPriorityUpdateOnRequest(requestClient);
            int lowestPriorityOwnerId = INVALID_CLIENT_ID;
            // Priority max value is 1000
            int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
            boolean isRequestFromSameProcess = false;
            if (!ciCam.isFullyUsed()) {
                updateCiCamClientMappingOnNewGrant(request.ciCamId, request.clientId);
                ciCamHandle[0] = ciCam.getHandle();
                return true;
            }
            for (int ownerId : ciCam.getOwnerClientIds()) {
                // Record the client id with lowest priority that is using the current CiCam.
                int priority = updateAndGetOwnerClientPriority(ownerId);
                if (currentLowestPriority > priority) {
                    lowestPriorityOwnerId = ownerId;
                    currentLowestPriority = priority;
                    isRequestFromSameProcess = (requestClient.getProcessId()
                        == (getClientProfile(ownerId)).getProcessId());
                }
            }

            // When all the CiCam sessions are occupied, reclaim the lowest priority client if the
            // request client has higher priority.
            if (lowestPriorityOwnerId != INVALID_CLIENT_ID
                    && ((requestClient.getPriority() > currentLowestPriority)
                    || ((requestClient.getPriority() == currentLowestPriority)
                    && isRequestFromSameProcess))) {
                ciCamHandle[0] = ciCam.getHandle();
                reclaimOwnerId[0] = lowestPriorityOwnerId;
                return true;
            }
        }

        return false;
    }

    @VisibleForTesting
    protected boolean isHigherPriorityInternal(ResourceClientProfile challengerProfile,
            ResourceClientProfile holderProfile) {
        if (DEBUG) {
            Slog.d(TAG,
                    "isHigherPriority(challengerProfile=" + challengerProfile
                            + ", holderProfile=" + challengerProfile + ")");
        }
        if (mTvInputManager == null) {
            Slog.e(TAG, "TvInputManager is null. Can't compare the priority.");
            // Allow the client to acquire the hardware interface
            // when the TRM is not able to compare the priority.
            return true;
        }

        int challengerPid = challengerProfile.tvInputSessionId == null
                ? Binder.getCallingPid() /*callingPid*/
                : mTvInputManager.getClientPid(challengerProfile.tvInputSessionId); /*tvAppId*/
        int holderPid = holderProfile.tvInputSessionId == null
                ? Binder.getCallingPid() /*callingPid*/
                : mTvInputManager.getClientPid(holderProfile.tvInputSessionId); /*tvAppId*/

        int challengerPriority = getClientPriority(
                challengerProfile.useCase, checkIsForeground(challengerPid));
        int holderPriority = getClientPriority(holderProfile.useCase, checkIsForeground(holderPid));
        return challengerPriority > holderPriority;
    }

    @VisibleForTesting
    protected void releaseFrontendInternal(int frontendHandle, int clientId)
            throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, "releaseFrontend(id=" + frontendHandle + ", clientId=" + clientId + " )");
        }
        if (!validateResourceHandle(TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND,
                frontendHandle)) {
            throw new RemoteException("frontendHandle can't be invalid");
        }
        Set<Integer> reclaimedResourceOwnerIds = unclaimFrontend(frontendHandle, clientId);
        if (reclaimedResourceOwnerIds != null) {
            for (int shareOwnerId : reclaimedResourceOwnerIds) {
                reclaimResource(shareOwnerId,
                        TunerResourceManager.TUNER_RESOURCE_TYPE_FRONTEND);
            }
        }
        synchronized (mLock) {
            clearFrontendAndClientMapping(getClientProfile(clientId));
        }
    }

    private Set<Integer> unclaimFrontend(int frontendHandle, int clientId) throws RemoteException {
        Set<Integer> reclaimedResourceOwnerIds = null;
        synchronized (mLock) {
            if (!checkClientExists(clientId)) {
                throw new RemoteException("Release frontend from unregistered client:"
                        + clientId);
            }
            FrontendResource fe = getFrontendResource(frontendHandle);
            if (fe == null) {
                throw new RemoteException("Releasing frontend does not exist.");
            }
            int ownerClientId = fe.getOwnerClientId();
            ClientProfile ownerProfile = getClientProfile(ownerClientId);
            if (ownerClientId == clientId) {
                reclaimedResourceOwnerIds = ownerProfile.getShareFeClientIds();
            } else {
                if (!ownerProfile.getShareFeClientIds().contains(clientId)) {
                    throw new RemoteException("Client is not a sharee of the releasing fe.");
                }
            }
        }
        return reclaimedResourceOwnerIds;
    }

    @VisibleForTesting
    protected void releaseDemuxInternal(DemuxResource demux) {
        if (DEBUG) {
            Slog.d(TAG, "releaseDemux(DemuxHandle=" + demux.getHandle() + ")");
        }
        updateDemuxClientMappingOnRelease(demux);
    }

    @VisibleForTesting
    protected void releaseLnbInternal(LnbResource lnb) {
        if (DEBUG) {
            Slog.d(TAG, "releaseLnb(lnbHandle=" + lnb.getHandle() + ")");
        }
        updateLnbClientMappingOnRelease(lnb);
    }

    @VisibleForTesting
    protected void releaseCasSessionInternal(CasResource cas, int ownerClientId) {
        if (DEBUG) {
            Slog.d(TAG, "releaseCasSession(sessionResourceId=" + cas.getSystemId() + ")");
        }
        updateCasClientMappingOnRelease(cas, ownerClientId);
    }

    @VisibleForTesting
    protected void releaseCiCamInternal(CiCamResource ciCam, int ownerClientId) {
        if (DEBUG) {
            Slog.d(TAG, "releaseCiCamInternal(ciCamId=" + ciCam.getCiCamId() + ")");
        }
        updateCiCamClientMappingOnRelease(ciCam, ownerClientId);
    }

    @VisibleForTesting
    public boolean requestDemuxInternal(@NonNull TunerDemuxRequest request,
                @NonNull int[] demuxHandle) throws RemoteException {
        if (DEBUG) {
            Slog.d(TAG, "requestDemux(request=" + request + ")");
        }
        int[] reclaimOwnerId = new int[1];
        if (!claimDemux(request, demuxHandle, reclaimOwnerId)) {
            return false;
        }
        if (demuxHandle[0] == TunerResourceManager.INVALID_RESOURCE_HANDLE) {
            return false;
        }
        if (reclaimOwnerId[0] != INVALID_CLIENT_ID) {
            if (!reclaimResource(reclaimOwnerId[0],
                    TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX)) {
                return false;
            }
            synchronized (mLock) {
                if (getDemuxResource(demuxHandle[0]).isInUse()) {
                    Slog.e(TAG, "Reclaimed demux still in use");
                    return false;
                }
                updateDemuxClientMappingOnNewGrant(demuxHandle[0], request.clientId);
            }
        }
        return true;
    }

    protected boolean claimDemux(TunerDemuxRequest request, int[] demuxHandle, int[] reclaimOwnerId)
            throws RemoteException {
        demuxHandle[0] = TunerResourceManager.INVALID_RESOURCE_HANDLE;
        reclaimOwnerId[0] = INVALID_CLIENT_ID;
        synchronized (mLock) {
            if (!checkClientExists(request.clientId)) {
                throw new RemoteException("Request demux from unregistered client:"
                        + request.clientId);
            }

            // For Tuner 2.0 and below or any HW constraint devices that are unable to support
            // ITuner.openDemuxById(), demux resources are not really managed under TRM and
            // mDemuxResources.size() will be zero
            if (mDemuxResources.size() == 0) {
                // There are enough Demux resources, so we don't manage Demux in R.
                demuxHandle[0] =
                        generateResourceHandle(TunerResourceManager.TUNER_RESOURCE_TYPE_DEMUX, 0);
                return true;
            }

            ClientProfile requestClient = getClientProfile(request.clientId);
            if (requestClient == null) {
                return false;
            }
            clientPriorityUpdateOnRequest(requestClient);
            DemuxResource grantingDemux = null;
            DemuxResource inUseLowestPriorityDemux = null;
            // Priority max value is 1000
            int currentLowestPriority = MAX_CLIENT_PRIORITY + 1;
            boolean isRequestFromSameProcess = false;
            // If the desired demux id was specified, we only need to check the demux.
            boolean hasDesiredDemuxCap = request.desiredFilterTypes
                    != DemuxFilterMainType.UNDEFINED;
            int smallestNumOfSupportedCaps = Integer.SIZE + 1;
            int smallestNumOfSupportedCapsInUse = Integer.SIZE + 1;
            for (DemuxResource dr : getDemuxResources().values()) {
                if (!hasDesiredDemuxCap || dr.hasSufficientCaps(request.desiredFilterTypes)) {
                    if (!dr.isInUse()) {
                        int numOfSupportedCaps = dr.getNumOfCaps();

                        // look for the demux with minimum caps supporting the desired caps
                        if (smallestNumOfSupportedCaps > numOfSupportedCaps) {
                            smallestNumOfSupportedCaps = numOfSupportedCaps;
                            grantingDemux = dr;
                        }
                    } else if (grantingDemux == null) {
                        // Record the demux id with the lowest client priority among all the
                        // in use demuxes when no availabledemux has been found.
                        int priority = updateAndGetOwnerClientPriority(dr.getOwnerClientId());
                        if (currentLowestPriority >= priority) {
                            int numOfSupportedCaps = dr.getNumOfCaps();
                            boolean shouldUpdate = false;
                            // update lowest priority
                            if (currentLowestPriority > priority) {
                                currentLowestPriority = priority;
                                isRequestFromSameProcess = (requestClient.getProcessId()
                                    == (getClientProfile(dr.getOwnerClientId())).getProcessId());

                                // reset the smallest caps when lower priority resource is found
                                smallestNumOfSupportedCapsInUse = numOfSupportedCaps;

                                shouldUpdate = true;
                            } else {
                                // This is the case when the priority is the same as previously
                                // found one. Update smallest caps when priority.
                                if (smallestNumOfSupportedCapsInUse > numOfSupportedCaps) {
                                    smallestNumOfSupportedCapsInUse = numOfSupportedCaps;
                                    shouldUpdate = true;
                                }
                            }
                            if (shouldUpdate) {
                                inUseLowestPriorityDemux = dr;
                            }
                        }
                    }
                }
            }

            // Grant demux when there is unused resource.
            if (grantingDemux != null) {
                updateDemuxClientMappingOnNewGrant(grantingDemux.getHandle(), request.clientId);
                demuxHandle[0] = grantingDemux.getHandle();
                return true;
            }

            // When all the resources are occupied, grant the lowest priority resource if the
            // request client has higher priority.
            if (inUseLowestPriorityDemux != null
                    && ((requestClient.getPriority() > currentLowestPriority) || (
                    (requestClient.getPriority() == currentLowestPriority)
                        && isRequestFromSameProcess))) {
                demuxHandle[0] = inUseLowestPriorityDemux.getHandle();
                reclaimOwnerId[0] = inUseLowestPriorityDemux.getOwnerClientId();
                return true;
            }
        }

        return false;
    }

    @VisibleForTesting
    // This mothod is to sync up the request/holder client's foreground/background status and update
    // the client priority accordingly whenever a new resource request comes in.
    protected void clientPriorityUpdateOnRequest(ClientProfile profile) {
        if (profile.isPriorityOverwritten()) {
            // To avoid overriding the priority set through updateClientPriority API.
            return;
        }
        int pid = profile.getProcessId();
        boolean currentIsForeground = checkIsForeground(pid);
        profile.setPriority(
                getClientPriority(profile.getUseCase(), currentIsForeground));
    }

    @VisibleForTesting
    protected boolean requestDescramblerInternal(
            TunerDescramblerRequest request, int[] descramblerHandle) {
        if (DEBUG) {
            Slog.d(TAG, "requestDescrambler(request=" + request + ")");
        }
        // There are enough Descrambler resources, so we don't manage Descrambler in R.
        descramblerHandle[0] =
                generateResourceHandle(TunerResourceManager.TUNER_RESOURCE_TYPE_DESCRAMBLER, 0);
        return true;
    }

    // Return value is guaranteed to be positive
    private long getElapsedTime(long begin) {
        long now = SystemClock.uptimeMillis();
        long elapsed;
        if (now >= begin) {
            elapsed = now - begin;
        } else {
            elapsed = Long.MAX_VALUE - begin + now;
            if (elapsed < 0) {
                elapsed = Long.MAX_VALUE;
            }
        }
        return elapsed;
    }

    private boolean lockForTunerApiLock(int clientId, long timeoutMS, String callerFunction) {
        try {
            if (mLockForTRMSLock.tryLock(timeoutMS, TimeUnit.MILLISECONDS)) {
                return true;
            } else {
                Slog.e(TAG, "FAILED to lock mLockForTRMSLock in " + callerFunction
                        + ", clientId:" + clientId + ", timeoutMS:" + timeoutMS
                        + ", mTunerApiLockHolder:" + mTunerApiLockHolder);
                return false;
            }
        } catch (InterruptedException ie) {
            Slog.e(TAG, "exception thrown in " + callerFunction + ":" + ie);
            if (mLockForTRMSLock.isHeldByCurrentThread()) {
                mLockForTRMSLock.unlock();
            }
            return false;
        }
    }

    private boolean acquireLockInternal(int clientId, long clientThreadId, long timeoutMS) {
        long begin = SystemClock.uptimeMillis();

        // Grab lock
        if (!lockForTunerApiLock(clientId, timeoutMS, "acquireLockInternal()")) {
            return false;
        }

        try {
            boolean available = mTunerApiLockHolder == INVALID_CLIENT_ID;
            boolean nestedSelf = (clientId == mTunerApiLockHolder)
                    && (clientThreadId == mTunerApiLockHolderThreadId);
            boolean recovery = false;

            // Allow same thread to grab the lock multiple times
            while (!available && !nestedSelf) {
                // calculate how much time is left before timeout
                long leftOverMS = timeoutMS - getElapsedTime(begin);
                if (leftOverMS <= 0) {
                    Slog.e(TAG, "FAILED:acquireLockInternal(" + clientId + ", " + clientThreadId
                            + ", " + timeoutMS + ") - timed out, but will grant the lock to "
                            + "the callee by stealing it from the current holder:"
                            + mTunerApiLockHolder + "(" + mTunerApiLockHolderThreadId + "), "
                            + "who likely failed to call releaseLock(), "
                            + "to prevent this from becoming an unrecoverable error");
                    // This should not normally happen, but there sometimes are cases where
                    // in-flight tuner API execution gets scheduled even after binderDied(),
                    // which can leave the in-flight execution dissappear/stopped in between
                    // acquireLock and releaseLock
                    recovery = true;
                    break;
                }

                // Cond wait for left over time
                mTunerApiLockReleasedCV.await(leftOverMS, TimeUnit.MILLISECONDS);

                // Check the availability for "spurious wakeup"
                // The case that was confirmed is that someone else can acquire this in between
                // signal() and wakup from the above await()
                available = mTunerApiLockHolder == INVALID_CLIENT_ID;

                if (!available) {
                    Slog.w(TAG, "acquireLockInternal(" + clientId + ", " + clientThreadId + ", "
                            + timeoutMS + ") - woken up from cond wait, but " + mTunerApiLockHolder
                            + "(" + mTunerApiLockHolderThreadId + ") is already holding the lock. "
                            + "Going to wait again if timeout hasn't reached yet");
                }
            }

            // Will always grant unless exception is thrown (or lock is already held)
            if (available || recovery) {
                if (DEBUG) {
                    Slog.d(TAG, "SUCCESS:acquireLockInternal(" + clientId + ", " + clientThreadId
                            + ", " + timeoutMS + ")");
                }

                if (mTunerApiLockNestedCount != 0) {
                    Slog.w(TAG, "Something is wrong as nestedCount(" + mTunerApiLockNestedCount
                            + ") is not zero. Will overriding it to 1 anyways");
                }

                // set the caller to be the holder
                mTunerApiLockHolder = clientId;
                mTunerApiLockHolderThreadId = clientThreadId;
                mTunerApiLockNestedCount = 1;
            } else if (nestedSelf) {
                // Increment the nested count so releaseLockInternal won't signal prematuredly
                mTunerApiLockNestedCount++;
                if (DEBUG) {
                    Slog.d(TAG, "acquireLockInternal(" + clientId + ", " + clientThreadId
                            + ", " + timeoutMS + ") - nested count incremented to "
                            + mTunerApiLockNestedCount);
                }
            } else {
                Slog.e(TAG, "acquireLockInternal(" + clientId + ", " + clientThreadId
                        + ", " + timeoutMS + ") - should not reach here");
            }
            // return true in "recovery" so callee knows that the deadlock is possible
            // only when the return value is false
            return (available || nestedSelf || recovery);
        } catch (InterruptedException ie) {
            Slog.e(TAG, "exception thrown in acquireLockInternal(" + clientId + ", "
                    + clientThreadId + ", " + timeoutMS + "):" + ie);
            return false;
        } finally {
            if (mLockForTRMSLock.isHeldByCurrentThread()) {
                mLockForTRMSLock.unlock();
            }
        }
    }

    private boolean releaseLockInternal(int clientId, long timeoutMS,
            boolean ignoreNestedCount, boolean suppressError) {
        // Grab lock first
        if (!lockForTunerApiLock(clientId, timeoutMS, "releaseLockInternal()")) {
            return false;
        }

        try {
            if (mTunerApiLockHolder == clientId) {
                // Should always reach here unless called from binderDied()
                mTunerApiLockNestedCount--;
                if (ignoreNestedCount || mTunerApiLockNestedCount <= 0) {
                    if (DEBUG) {
                        Slog.d(TAG, "SUCCESS:releaseLockInternal(" + clientId + ", " + timeoutMS
                                + ", " + ignoreNestedCount + ", " + suppressError
                                + ") - signaling!");
                    }
                    // Reset the current holder and signal
                    mTunerApiLockHolder = INVALID_CLIENT_ID;
                    mTunerApiLockHolderThreadId = INVALID_THREAD_ID;
                    mTunerApiLockNestedCount = 0;
                    mTunerApiLockReleasedCV.signal();
                } else {
                    if (DEBUG) {
                        Slog.d(TAG, "releaseLockInternal(" + clientId + ", " + timeoutMS
                                + ", " + ignoreNestedCount + ", " + suppressError
                                + ") - NOT signaling because nested count is not zero ("
                                + mTunerApiLockNestedCount + ")");
                    }
                }
                return true;
            } else if (mTunerApiLockHolder == INVALID_CLIENT_ID) {
                if (!suppressError) {
                    Slog.w(TAG, "releaseLockInternal(" + clientId + ", " + timeoutMS
                            + ") - called while there is no current holder");
                }
                // No need to do anything.
                // Shouldn't reach here unless called from binderDied()
                return false;
            } else {
                if (!suppressError) {
                    Slog.e(TAG, "releaseLockInternal(" + clientId + ", " + timeoutMS
                            + ") - called while someone else:" + mTunerApiLockHolder
                            + "is the current holder");
                }
                // Cannot reset the holder Id because it reaches here when called
                // from binderDied()
                return false;
            }
        } finally {
            if (mLockForTRMSLock.isHeldByCurrentThread()) {
                mLockForTRMSLock.unlock();
            }
        }
    }

    @VisibleForTesting
    protected class ResourcesReclaimListenerRecord implements IBinder.DeathRecipient {
        private final IResourcesReclaimListener mListener;
        private final int mClientId;

        public ResourcesReclaimListenerRecord(IResourcesReclaimListener listener, int clientId) {
            mListener = listener;
            mClientId = clientId;
        }

        @Override
        public void binderDied() {
            try {
                synchronized (mLock) {
                    if (checkClientExists(mClientId)) {
                        removeClientProfile(mClientId);
                    }
                }
            } finally {
                // reset the tuner API lock
                releaseLockInternal(mClientId, TRMS_LOCK_TIMEOUT, true, true);
            }
        }

        public int getId() {
            return mClientId;
        }

        public IResourcesReclaimListener getListener() {
            return mListener;
        }
    }

    private void addResourcesReclaimListener(int clientId, IResourcesReclaimListener listener) {
        if (listener == null) {
            if (DEBUG) {
                Slog.w(TAG, "Listener is null when client " + clientId + " registered!");
            }
            return;
        }

        ResourcesReclaimListenerRecord record =
                new ResourcesReclaimListenerRecord(listener, clientId);

        try {
            listener.asBinder().linkToDeath(record, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "Listener already died.");
            return;
        }

        synchronized (mLock) {
            mListeners.put(clientId, record);
        }
    }

    @VisibleForTesting
    protected boolean reclaimResource(int reclaimingClientId,
            @TunerResourceManager.TunerResourceType int resourceType) {

        // Allowing this because:
        // 1) serialization of resource reclaim is required in the current design
        // 2) the outgoing transaction is handled by the system app (with
        //    android.Manifest.permission.TUNER_RESOURCE_ACCESS), which goes through full
        //    Google certification
        Binder.allowBlockingForCurrentThread();

        // Reclaim all the resources of the share owners of the frontend that is used by the current
        // resource reclaimed client.
        Set<Integer> shareFeClientIds;
        synchronized (mLock) {
            ClientProfile profile = getClientProfile(reclaimingClientId);
            if (profile == null) {
                return true;
            }
            shareFeClientIds = profile.getShareFeClientIds();
        }
        ResourcesReclaimListenerRecord listenerRecord = null;
        for (int clientId : shareFeClientIds) {
            synchronized (mLock) {
                listenerRecord = mListeners.get(clientId);
            }
            if (listenerRecord != null) {
                try {
                    listenerRecord.getListener().onReclaimResources();
                } catch (RemoteException e) {
                    Slog.e(TAG, "Failed to reclaim resources on client " + clientId, e);
                }
            }
        }

        if (DEBUG) {
            Slog.d(TAG, "Reclaiming resources because higher priority client request resource type "
                    + resourceType + ", clientId:" + reclaimingClientId);
        }

        synchronized (mLock) {
            listenerRecord = mListeners.get(reclaimingClientId);
        }
        if (listenerRecord != null) {
            try {
                listenerRecord.getListener().onReclaimResources();
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to reclaim resources on client " + reclaimingClientId, e);
            }
        }

        return true;
    }

    @VisibleForTesting
    protected int getClientPriority(int useCase, boolean isForeground) {
        if (DEBUG) {
            Slog.d(TAG, "getClientPriority useCase=" + useCase
                    + ", isForeground=" + isForeground + ")");
        }

        if (isForeground) {
            return mPriorityCongfig.getForegroundPriority(useCase);
        }
        return mPriorityCongfig.getBackgroundPriority(useCase);
    }

    @VisibleForTesting
    protected boolean checkIsForeground(int pid) {
        if (mActivityManager == null) {
            return false;
        }
        List<RunningAppProcessInfo> appProcesses = mActivityManager.getRunningAppProcesses();
        if (appProcesses == null) {
            return false;
        }
        for (RunningAppProcessInfo appProcess : appProcesses) {
            if (appProcess.pid == pid
                    && appProcess.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return true;
            }
        }
        return false;
    }

    private void updateFrontendClientMappingOnNewGrant(int grantingHandle, int ownerClientId) {
        FrontendResource grantingFrontend = getFrontendResource(grantingHandle);
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        grantingFrontend.setOwner(ownerClientId);
        increFrontendNum(mFrontendUsedNums, grantingFrontend.getType());
        ownerProfile.useFrontend(grantingHandle);
        for (int exclusiveGroupMember : grantingFrontend.getExclusiveGroupMemberFeHandles()) {
            getFrontendResource(exclusiveGroupMember).setOwner(ownerClientId);
            ownerProfile.useFrontend(exclusiveGroupMember);
        }
        ownerProfile.setPrimaryFrontend(grantingHandle);
    }

    private void updateDemuxClientMappingOnNewGrant(int grantingHandle, int ownerClientId) {
        DemuxResource grantingDemux = getDemuxResource(grantingHandle);
        if (grantingDemux != null) {
            ClientProfile ownerProfile = getClientProfile(ownerClientId);
            grantingDemux.setOwner(ownerClientId);
            ownerProfile.useDemux(grantingHandle);
        }
    }

    private void updateDemuxClientMappingOnRelease(@NonNull DemuxResource releasingDemux) {
        ClientProfile ownerProfile = getClientProfile(releasingDemux.getOwnerClientId());
        releasingDemux.removeOwner();
        ownerProfile.releaseDemux(releasingDemux.getHandle());
    }

    private void updateLnbClientMappingOnNewGrant(int grantingHandle, int ownerClientId) {
        LnbResource grantingLnb = getLnbResource(grantingHandle);
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        grantingLnb.setOwner(ownerClientId);
        ownerProfile.useLnb(grantingHandle);
    }

    private void updateLnbClientMappingOnRelease(@NonNull LnbResource releasingLnb) {
        ClientProfile ownerProfile = getClientProfile(releasingLnb.getOwnerClientId());
        releasingLnb.removeOwner();
        ownerProfile.releaseLnb(releasingLnb.getHandle());
    }

    private void updateCasClientMappingOnNewGrant(int grantingId, int ownerClientId) {
        CasResource grantingCas = getCasResource(grantingId);
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        grantingCas.setOwner(ownerClientId);
        ownerProfile.useCas(grantingId);
    }

    private void updateCiCamClientMappingOnNewGrant(int grantingId, int ownerClientId) {
        CiCamResource grantingCiCam = getCiCamResource(grantingId);
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        grantingCiCam.setOwner(ownerClientId);
        ownerProfile.useCiCam(grantingId);
    }

    private void updateCasClientMappingOnRelease(@NonNull CasResource cas, int ownerClientId) {
        cas.removeSession(ownerClientId);
        if (!cas.hasOpenSessions(ownerClientId)) {
            ClientProfile ownerProfile = getClientProfile(ownerClientId);
            cas.removeOwner(ownerClientId);
            ownerProfile.releaseCas();
        }
    }

    private void updateCiCamClientMappingOnRelease(
            @NonNull CiCamResource releasingCiCam, int ownerClientId) {
        ClientProfile ownerProfile = getClientProfile(ownerClientId);
        releasingCiCam.removeOwner(ownerClientId);
        ownerProfile.releaseCiCam();
    }

    /**
     * Update and get the owner client's priority.
     *
     * @param clientId the owner client id.
     * @return the priority of the owner client.
     */
    private int updateAndGetOwnerClientPriority(int clientId) {
        ClientProfile profile = getClientProfile(clientId);
        clientPriorityUpdateOnRequest(profile);
        return profile.getPriority();
    }

    /**
     * Update the owner and sharee clients' priority and get the highest priority
     * for frontend resource
     *
     * @param clientId the owner client id.
     * @return the highest priority among all the clients holding the same frontend resource.
     */
    private int getFrontendHighestClientPriority(int clientId) {
        // Check if the owner profile exists
        ClientProfile ownerClient = getClientProfile(clientId);
        if (ownerClient == null) {
            return 0;
        }

        // Update and get the priority of the owner client
        int highestPriority = updateAndGetOwnerClientPriority(clientId);

        // Update and get all the client IDs of frontend resource holders
        for (int shareeId : ownerClient.getShareFeClientIds()) {
            int priority = updateAndGetOwnerClientPriority(shareeId);
            if (priority > highestPriority) {
                highestPriority = priority;
            }
        }
        return highestPriority;
    }

    @VisibleForTesting
    @Nullable
    protected FrontendResource getFrontendResource(int frontendHandle) {
        return mFrontendResources.get(frontendHandle);
    }

    @VisibleForTesting
    protected Map<Integer, FrontendResource> getFrontendResources() {
        return mFrontendResources;
    }

    @VisibleForTesting
    @Nullable
    protected DemuxResource getDemuxResource(int demuxHandle) {
        return mDemuxResources.get(demuxHandle);
    }

    @VisibleForTesting
    protected Map<Integer, DemuxResource> getDemuxResources() {
        return mDemuxResources;
    }

    private boolean setMaxNumberOfFrontendsInternal(int frontendType, int maxUsableNum) {
        int usedNum = mFrontendUsedNums.get(frontendType, INVALID_FE_COUNT);
        if (usedNum == INVALID_FE_COUNT || usedNum <= maxUsableNum) {
            mFrontendMaxUsableNums.put(frontendType, maxUsableNum);
            return true;
        } else {
            Slog.e(TAG, "max number of frontend for frontendType: " + frontendType
                    + " cannot be set to a value lower than the current usage count."
                    + " (requested max num = " + maxUsableNum + ", current usage = " + usedNum);
            return false;
        }
    }

    private int getMaxNumberOfFrontendsInternal(int frontendType) {
        int existingNum = mFrontendExistingNums.get(frontendType, INVALID_FE_COUNT);
        if (existingNum == INVALID_FE_COUNT) {
            Log.e(TAG, "existingNum is -1 for " + frontendType);
            return -1;
        }
        int maxUsableNum = mFrontendMaxUsableNums.get(frontendType, INVALID_FE_COUNT);
        if (maxUsableNum == INVALID_FE_COUNT) {
            return existingNum;
        } else {
            return maxUsableNum;
        }
    }

    private boolean isFrontendMaxNumUseReached(int frontendType) {
        int maxUsableNum = mFrontendMaxUsableNums.get(frontendType, INVALID_FE_COUNT);
        if (maxUsableNum == INVALID_FE_COUNT) {
            return false;
        }
        int useNum = mFrontendUsedNums.get(frontendType, INVALID_FE_COUNT);
        if (useNum == INVALID_FE_COUNT) {
            useNum = 0;
        }
        return useNum >= maxUsableNum;
    }

    private void increFrontendNum(SparseIntArray targetNums, int frontendType) {
        int num = targetNums.get(frontendType, INVALID_FE_COUNT);
        if (num == INVALID_FE_COUNT) {
            targetNums.put(frontendType, 1);
        } else {
            targetNums.put(frontendType, num + 1);
        }
    }

    private void decreFrontendNum(SparseIntArray targetNums, int frontendType) {
        int num = targetNums.get(frontendType, INVALID_FE_COUNT);
        if (num != INVALID_FE_COUNT) {
            targetNums.put(frontendType, num - 1);
        }
    }

    private void replaceFeResourceMap(Map<Integer, FrontendResource> srcMap, Map<Integer,
            FrontendResource> dstMap) {
        if (dstMap != null) {
            dstMap.clear();
            if (srcMap != null && srcMap.size() > 0) {
                dstMap.putAll(srcMap);
            }
        }
    }

    private void replaceFeCounts(SparseIntArray srcCounts, SparseIntArray dstCounts) {
        if (dstCounts != null) {
            dstCounts.clear();
            if (srcCounts != null) {
                for (int i = 0; i < srcCounts.size(); i++) {
                    dstCounts.put(srcCounts.keyAt(i), srcCounts.valueAt(i));
                }
            }
        }
    }
    private void dumpMap(Map<?, ?> targetMap, String headline, String delimiter,
            IndentingPrintWriter pw) {
        if (targetMap != null) {
            pw.println(headline);
            pw.increaseIndent();
            for (Map.Entry<?, ?> entry : targetMap.entrySet()) {
                pw.print(entry.getKey() + " : " + entry.getValue());
                pw.print(delimiter);
            }
            pw.println();
            pw.decreaseIndent();
        }
    }

    private void dumpSIA(SparseIntArray array, String headline, String delimiter,
            IndentingPrintWriter pw) {
        if (array != null) {
            pw.println(headline);
            pw.increaseIndent();
            for (int i = 0; i < array.size(); i++) {
                pw.print(array.keyAt(i) + " : " + array.valueAt(i));
                pw.print(delimiter);
            }
            pw.println();
            pw.decreaseIndent();
        }
    }

    private void addFrontendResource(FrontendResource newFe) {
        // Update the exclusive group member list in all the existing Frontend resource
        for (FrontendResource fe : getFrontendResources().values()) {
            if (fe.getExclusiveGroupId() == newFe.getExclusiveGroupId()) {
                newFe.addExclusiveGroupMemberFeHandle(fe.getHandle());
                newFe.addExclusiveGroupMemberFeHandles(fe.getExclusiveGroupMemberFeHandles());
                for (int excGroupmemberFeHandle : fe.getExclusiveGroupMemberFeHandles()) {
                    getFrontendResource(excGroupmemberFeHandle)
                            .addExclusiveGroupMemberFeHandle(newFe.getHandle());
                }
                fe.addExclusiveGroupMemberFeHandle(newFe.getHandle());
                break;
            }
        }
        // Update resource list and available id list
        mFrontendResources.put(newFe.getHandle(), newFe);
        increFrontendNum(mFrontendExistingNums, newFe.getType());

    }

    private void addDemuxResource(DemuxResource newDemux) {
        mDemuxResources.put(newDemux.getHandle(), newDemux);
    }

    private void removeFrontendResource(int removingHandle) {
        FrontendResource fe = getFrontendResource(removingHandle);
        if (fe == null) {
            return;
        }
        if (fe.isInUse()) {
            ClientProfile ownerClient = getClientProfile(fe.getOwnerClientId());
            for (int shareOwnerId : ownerClient.getShareFeClientIds()) {
                clearFrontendAndClientMapping(getClientProfile(shareOwnerId));
            }
            clearFrontendAndClientMapping(ownerClient);
        }
        for (int excGroupmemberFeHandle : fe.getExclusiveGroupMemberFeHandles()) {
            getFrontendResource(excGroupmemberFeHandle)
                    .removeExclusiveGroupMemberFeId(fe.getHandle());
        }
        decreFrontendNum(mFrontendExistingNums, fe.getType());
        mFrontendResources.remove(removingHandle);
    }

    private void removeDemuxResource(int removingHandle) {
        DemuxResource demux = getDemuxResource(removingHandle);
        if (demux == null) {
            return;
        }
        if (demux.isInUse()) {
            releaseDemuxInternal(demux);
        }
        mDemuxResources.remove(removingHandle);
    }

    @VisibleForTesting
    @Nullable
    protected LnbResource getLnbResource(int lnbHandle) {
        return mLnbResources.get(lnbHandle);
    }

    @VisibleForTesting
    protected Map<Integer, LnbResource> getLnbResources() {
        return mLnbResources;
    }

    private void addLnbResource(LnbResource newLnb) {
        // Update resource list and available id list
        mLnbResources.put(newLnb.getHandle(), newLnb);
    }

    private void removeLnbResource(int removingHandle) {
        LnbResource lnb = getLnbResource(removingHandle);
        if (lnb == null) {
            return;
        }
        if (lnb.isInUse()) {
            releaseLnbInternal(lnb);
        }
        mLnbResources.remove(removingHandle);
    }

    @VisibleForTesting
    @Nullable
    protected CasResource getCasResource(int systemId) {
        return mCasResources.get(systemId);
    }

    @VisibleForTesting
    @Nullable
    protected CiCamResource getCiCamResource(int ciCamId) {
        return mCiCamResources.get(ciCamId);
    }

    @VisibleForTesting
    protected Map<Integer, CasResource> getCasResources() {
        return mCasResources;
    }

    @VisibleForTesting
    protected Map<Integer, CiCamResource> getCiCamResources() {
        return mCiCamResources;
    }

    private void addCasResource(CasResource newCas) {
        // Update resource list and available id list
        mCasResources.put(newCas.getSystemId(), newCas);
    }

    private void addCiCamResource(CiCamResource newCiCam) {
        // Update resource list and available id list
        mCiCamResources.put(newCiCam.getCiCamId(), newCiCam);
    }

    private void removeCasResource(int removingId) {
        CasResource cas = getCasResource(removingId);
        if (cas == null) {
            return;
        }
        for (int ownerId : cas.getOwnerClientIds()) {
            getClientProfile(ownerId).releaseCas();
        }
        mCasResources.remove(removingId);
    }

    private void removeCiCamResource(int removingId) {
        CiCamResource ciCam = getCiCamResource(removingId);
        if (ciCam == null) {
            return;
        }
        for (int ownerId : ciCam.getOwnerClientIds()) {
            getClientProfile(ownerId).releaseCiCam();
        }
        mCiCamResources.remove(removingId);
    }

    private void releaseLowerPriorityClientCasResources(int releasingCasResourceNum) {
        // TODO: Sort with a treemap

        // select the first num client to release
    }

    @VisibleForTesting
    @Nullable
    protected ClientProfile getClientProfile(int clientId) {
        return mClientProfiles.get(clientId);
    }

    private void addClientProfile(int clientId, ClientProfile profile,
            IResourcesReclaimListener listener) {
        mClientProfiles.put(clientId, profile);
        addResourcesReclaimListener(clientId, listener);
    }

    @SuppressWarnings("GuardedBy") // Lock is held on mListeners
    private void removeClientProfile(int clientId) {
        for (int shareOwnerId : getClientProfile(clientId).getShareFeClientIds()) {
            clearFrontendAndClientMapping(getClientProfile(shareOwnerId));
        }
        clearAllResourcesAndClientMapping(getClientProfile(clientId));
        mClientProfiles.remove(clientId);

        ResourcesReclaimListenerRecord record = mListeners.remove(clientId);
        if (record != null) {
            record.getListener().asBinder().unlinkToDeath(record, 0);
        }
    }

    private void clearFrontendAndClientMapping(ClientProfile profile) {
        // TODO: check if this check is really needed
        if (profile == null) {
            return;
        }
        for (Integer feId : profile.getInUseFrontendHandles()) {
            FrontendResource fe = getFrontendResource(feId);
            int ownerClientId = fe.getOwnerClientId();
            if (ownerClientId == profile.getId()) {
                fe.removeOwner();
                continue;
            }
            ClientProfile ownerClientProfile = getClientProfile(ownerClientId);
            if (ownerClientProfile != null) {
                ownerClientProfile.stopSharingFrontend(profile.getId());
            }

        }

        int primaryFeId = profile.getPrimaryFrontend();
        if (primaryFeId != TunerResourceManager.INVALID_RESOURCE_HANDLE) {
            FrontendResource primaryFe = getFrontendResource(primaryFeId);
            if (primaryFe != null) {
                decreFrontendNum(mFrontendUsedNums, primaryFe.getType());
            }
        }

        profile.releaseFrontend();
    }

    @VisibleForTesting
    protected void clearAllResourcesAndClientMapping(ClientProfile profile) {
        // TODO: check if this check is really needed. Maybe needed for reclaimResource path.
        if (profile == null) {
            return;
        }
        // Clear Lnb
        for (Integer lnbHandle : profile.getInUseLnbHandles()) {
            getLnbResource(lnbHandle).removeOwner();
        }
        // Clear Cas
        if (profile.getInUseCasSystemId() != ClientProfile.INVALID_RESOURCE_ID) {
            getCasResource(profile.getInUseCasSystemId()).removeOwner(profile.getId());
        }
        // Clear CiCam
        if (profile.getInUseCiCamId() != ClientProfile.INVALID_RESOURCE_ID) {
            getCiCamResource(profile.getInUseCiCamId()).removeOwner(profile.getId());
        }
        // Clear Demux
        for (Integer demuxHandle : profile.getInUseDemuxHandles()) {
            getDemuxResource(demuxHandle).removeOwner();
        }
        // Clear Frontend
        clearFrontendAndClientMapping(profile);
        profile.reclaimAllResources();
    }

    @VisibleForTesting
    protected boolean checkClientExists(int clientId) {
        return mClientProfiles.keySet().contains(clientId);
    }

    private int generateResourceHandle(
            @TunerResourceManager.TunerResourceType int resourceType, int resourceId) {
        return (resourceType & 0x000000ff) << 24
                | (resourceId << 16)
                | (mResourceRequestCount++ & 0xffff);
    }

    @VisibleForTesting
    protected int getResourceIdFromHandle(int resourceHandle) {
        if (resourceHandle == TunerResourceManager.INVALID_RESOURCE_HANDLE) {
            return resourceHandle;
        }
        return (resourceHandle & 0x00ff0000) >> 16;
    }

    private boolean validateResourceHandle(int resourceType, int resourceHandle) {
        if (resourceHandle == TunerResourceManager.INVALID_RESOURCE_HANDLE
                || ((resourceHandle & 0xff000000) >> 24) != resourceType) {
            return false;
        }
        return true;
    }

    private void enforceTrmAccessPermission(String apiName) {
        getContext().enforceCallingOrSelfPermission("android.permission.TUNER_RESOURCE_ACCESS",
                TAG + ": " + apiName);
    }

    private void enforceTunerAccessPermission(String apiName) {
        getContext().enforceCallingPermission("android.permission.ACCESS_TV_TUNER",
                TAG + ": " + apiName);
    }

    private void enforceDescramblerAccessPermission(String apiName) {
        getContext().enforceCallingPermission("android.permission.ACCESS_TV_DESCRAMBLER",
                TAG + ": " + apiName);
    }
}
