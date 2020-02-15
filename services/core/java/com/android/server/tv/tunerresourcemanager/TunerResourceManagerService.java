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
import android.content.Context;
import android.media.tv.TvInputManager;
import android.media.tv.tunerresourcemanager.CasSessionRequest;
import android.media.tv.tunerresourcemanager.IResourcesReclaimListener;
import android.media.tv.tunerresourcemanager.ITunerResourceManager;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerFrontendInfo;
import android.media.tv.tunerresourcemanager.TunerFrontendRequest;
import android.media.tv.tunerresourcemanager.TunerLnbRequest;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides a system service that manages the TV tuner resources.
 *
 * @hide
 */
public class TunerResourceManagerService extends SystemService {
    private static final String TAG = "TunerResourceManagerService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Array of the registered client profiles
    @VisibleForTesting private SparseArray<ClientProfile> mClientProfiles = new SparseArray<>();
    private int mNextUnusedClientId = 0;
    private List<Integer> mReleasedClientId = new ArrayList<Integer>();

    // Array of the current available frontend resources
    @VisibleForTesting
    private SparseArray<FrontendResource> mFrontendResources = new SparseArray<>();
    @VisibleForTesting private SparseArray<Integer[]> mFrontendTypeMap = new SparseArray<>();
    // Array of the current available frontend ids
    private List<Integer> mAvailableFrontendIds = new ArrayList<Integer>();

    private SparseArray<IResourcesReclaimListener> mListeners = new SparseArray<>();

    private TvInputManager mManager;

    // Used to synchronize the access to the service.
    private final Object mLock = new Object();

    public TunerResourceManagerService(@Nullable Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.TV_TUNER_RESOURCE_MGR_SERVICE, new BinderService());
        mManager = (TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
    }

    private final class BinderService extends ITunerResourceManager.Stub {
        @Override
        public void registerClientProfile(@NonNull ResourceClientProfile profile,
                @NonNull IResourcesReclaimListener listener, @NonNull int[] clientId) {
            if (DEBUG) {
                Slog.d(TAG, "registerClientProfile(clientProfile=" + profile + ")");
            }

            // TODO tell if the client already exists
            if (mReleasedClientId.isEmpty()) {
                clientId[0] = mNextUnusedClientId++;
            } else {
                clientId[0] = mReleasedClientId.get(0);
                mReleasedClientId.remove(0);
            }

            if (mManager == null) {
                Slog.e(TAG, "TvInputManager is null. Can't register client profile.");
                return;
            }

            int callingPid = mManager.getClientPid(profile.getTvInputSessionId());

            ClientProfile clientProfile = new ClientProfile.Builder(clientId[0])
                                                  .tvInputSessionId(profile.getTvInputSessionId())
                                                  .useCase(profile.getUseCase())
                                                  .processId(callingPid)
                                                  .build();
            mClientProfiles.append(clientId[0], clientProfile);
            mListeners.append(clientId[0], listener);
        }

        @Override
        public void unregisterClientProfile(int clientId) {
            if (DEBUG) {
                Slog.d(TAG, "unregisterClientProfile(clientId=" + clientId + ")");
            }

            mClientProfiles.remove(clientId);
            mListeners.remove(clientId);
            mReleasedClientId.add(clientId);
        }

        @Override
        public boolean updateClientPriority(int clientId, int priority, int niceValue) {
            if (DEBUG) {
                Slog.d(TAG,
                        "updateClientPriority(clientId=" + clientId + ", priority=" + priority
                                + ", niceValue=" + niceValue + ")");
            }

            ClientProfile profile = mClientProfiles.get(clientId);
            if (profile == null) {
                Slog.e(TAG,
                        "Can not find client profile with id " + clientId
                                + " when trying to update the client priority.");
                return false;
            }

            profile.setPriority(priority);
            profile.setNiceValue(niceValue);

            return true;
        }

        @Override
        public void setFrontendInfoList(@NonNull TunerFrontendInfo[] infos) throws RemoteException {
            enforceAccessPermission();
            if (infos == null) {
                throw new RemoteException("TunerFrontendInfo can't be null");
            }
            synchronized (mLock) {
                setFrontendInfoListInternal(infos);
            }
        }

        @Override
        public void updateCasInfo(int casSystemId, int maxSessionNum) {
            if (DEBUG) {
                Slog.d(TAG,
                        "updateCasInfo(casSystemId=" + casSystemId
                                + ", maxSessionNum=" + maxSessionNum + ")");
            }
        }

        @Override
        public void setLnbInfoList(int[] lnbIds) {
            if (DEBUG) {
                for (int i = 0; i < lnbIds.length; i++) {
                    Slog.d(TAG, "updateLnbInfo(lnbId=" + lnbIds[i] + ")");
                }
            }
        }

        @Override
        public boolean requestFrontend(@NonNull TunerFrontendRequest request,
                @NonNull int[] frontendId) throws RemoteException {
            if (DEBUG) {
                Slog.d(TAG, "requestFrontend(request=" + request + ")");
            }

            frontendId[0] = TunerResourceManager.INVALID_FRONTEND_ID;

            if (mClientProfiles.get(request.getClientId()) == null) {
                Slog.e(TAG, "Request from unregistered client. Id: " + request.getClientId());
                return false;
            }

            String sessionId = mClientProfiles.get(request.getClientId()).getTvInputSessionId();

            if (DEBUG) {
                Slog.d(TAG, "session Id:" + sessionId + ")");
            }

            if (DEBUG) {
                Slog.d(TAG, "No available Frontend found.");
            }

            return false;
        }

        @Override
        public void shareFrontend(int selfClientId, int targetClientId) {
            if (DEBUG) {
                Slog.d(TAG, "shareFrontend from " + selfClientId + " with " + targetClientId);
            }
        }

        @Override
        public boolean requestCasSession(
                @NonNull CasSessionRequest request, @NonNull int[] sessionResourceId) {
            if (DEBUG) {
                Slog.d(TAG, "requestCasSession(request=" + request + ")");
            }

            return true;
        }

        @Override
        public boolean requestLnb(@NonNull TunerLnbRequest request, @NonNull int[] lnbId) {
            if (DEBUG) {
                Slog.d(TAG, "requestLnb(request=" + request + ")");
            }
            return true;
        }

        @Override
        public void releaseFrontend(int frontendId) {
            if (DEBUG) {
                Slog.d(TAG, "releaseFrontend(id=" + frontendId + ")");
            }
        }

        @Override
        public void releaseCasSession(int sessionResourceId) {
            if (DEBUG) {
                Slog.d(TAG, "releaseCasSession(sessionResourceId=" + sessionResourceId + ")");
            }
        }

        @Override
        public void releaseLnb(int lnbId) {
            if (DEBUG) {
                Slog.d(TAG, "releaseLnb(lnbId=" + lnbId + ")");
            }
        }

        @Override
        public boolean isHigherPriority(
                ResourceClientProfile challengerProfile, ResourceClientProfile holderProfile) {
            if (DEBUG) {
                Slog.d(TAG,
                        "isHigherPriority(challengerProfile=" + challengerProfile
                                + ", holderProfile=" + challengerProfile + ")");
            }
            return true;
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

        // An arrayList to record the frontends pending on updating. Ids will be removed
        // from this list once its updating finished. Any frontend left in this list when all
        // the updates are done will be removed from mAvailableFrontendIds and
        // mFrontendResources.
        List<Integer> updatingFrontendIds = new ArrayList<>(mAvailableFrontendIds);

        // Update frontendResources sparse array and other mappings accordingly
        for (int i = 0; i < infos.length; i++) {
            if (mFrontendResources.get(infos[i].getId()) != null) {
                if (DEBUG) {
                    Slog.d(TAG, "Frontend id=" + infos[i].getId() + "exists.");
                }
                updatingFrontendIds.remove(new Integer(infos[i].getId()));
            } else {
                // Add a new fe resource
                FrontendResource newFe = new FrontendResource.Builder(infos[i].getId())
                                                 .type(infos[i].getFrontendType())
                                                 .exclusiveGroupId(infos[i].getExclusiveGroupId())
                                                 .build();
                // Update the exclusive group member list in all the existing Frontend resource
                for (Integer feId : mAvailableFrontendIds) {
                    FrontendResource fe = mFrontendResources.get(feId.intValue());
                    if (fe.getExclusiveGroupId() == newFe.getExclusiveGroupId()) {
                        newFe.addExclusiveGroupMemberFeId(fe.getId());
                        newFe.addExclusiveGroupMemberFeId(fe.getExclusiveGroupMemberFeIds());
                        for (Integer excGroupmemberFeId : fe.getExclusiveGroupMemberFeIds()) {
                            mFrontendResources.get(excGroupmemberFeId.intValue())
                                    .addExclusiveGroupMemberFeId(newFe.getId());
                        }
                        fe.addExclusiveGroupMemberFeId(newFe.getId());
                        break;
                    }
                }
                // Update resource list and available id list
                mFrontendResources.append(newFe.getId(), newFe);
                mAvailableFrontendIds.add(newFe.getId());
            }
        }

        // TODO check if the removing resource is in use or not. Handle the conflict.
        for (Integer removingId : updatingFrontendIds) {
            // update the exclusive group id memver list
            FrontendResource fe = mFrontendResources.get(removingId.intValue());
            fe.removeExclusiveGroupMemberFeId(new Integer(fe.getId()));
            for (Integer excGroupmemberFeId : fe.getExclusiveGroupMemberFeIds()) {
                mFrontendResources.get(excGroupmemberFeId.intValue())
                        .removeExclusiveGroupMemberFeId(new Integer(fe.getId()));
            }
            mFrontendResources.remove(removingId.intValue());
            mAvailableFrontendIds.remove(removingId);
        }
        for (int i = 0; i < mFrontendResources.size(); i++) {
            int key = mFrontendResources.keyAt(i);
            // get the object by the key.
            FrontendResource r = mFrontendResources.get(key);
        }
    }

    @VisibleForTesting
    protected SparseArray<FrontendResource> getFrontendResources() {
        return mFrontendResources;
    }

    private void enforceAccessPermission() {
        getContext().enforceCallingOrSelfPermission(
                "android.permission.TUNER_RESOURCE_ACCESS", TAG);
    }
}
