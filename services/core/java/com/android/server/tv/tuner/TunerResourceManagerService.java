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

package com.android.server.tv.tuner;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.tv.TvInputManager;
import android.media.tv.tuner.CasSessionRequest;
import android.media.tv.tuner.ITunerResourceManager;
import android.media.tv.tuner.ITunerResourceManagerListener;
import android.media.tv.tuner.ResourceClientProfile;
import android.media.tv.tuner.TunerFrontendInfo;
import android.media.tv.tuner.TunerFrontendRequest;
import android.media.tv.tuner.TunerLnbRequest;
import android.media.tv.tuner.TunerResourceManager;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

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

    private SparseArray<ClientProfile> mClientProfiles = new SparseArray<>();
    private SparseArray<ITunerResourceManagerListener> mListeners = new SparseArray<>();
    private int mNextUnusedFrontendId = 0;
    private List<Integer> mReleasedClientId = new ArrayList<Integer>();
    private List<Integer> mAvailableFrontendIds = new ArrayList<Integer>();

    private TvInputManager mManager;

    public TunerResourceManagerService(@Nullable Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.TV_TUNER_RESOURCE_MGR_SERVICE, new BinderService());
        mManager = (TvInputManager) getContext()
                .getSystemService(Context.TV_INPUT_SERVICE);
    }

    private final class BinderService extends ITunerResourceManager.Stub {
        @Override
        public void registerClientProfile(@NonNull ResourceClientProfile profile,
                            @NonNull ITunerResourceManagerListener listener,
                            @NonNull int[] clientId) {
            if (DEBUG) {
                Slog.d(TAG, "registerClientProfile(clientProfile=" + profile + ")");
            }

            // TODO tell if the client already exists
            if (mReleasedClientId.isEmpty()) {
                clientId[0] = mNextUnusedFrontendId++;
            } else {
                clientId[0] = mReleasedClientId.get(0);
                mReleasedClientId.remove(0);
            }

            if (mManager == null) {
                Slog.e(TAG, "TvInputManager is null. Can't register client profile.");
                return;
            }

            int callingPid = mManager.getClientPid(profile.getTvInputSessionId());

            ClientProfile clientProfile = new ClientProfile.ClientProfileBuilder(
                    clientId[0])
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
                Slog.d(TAG, "updateClientPriority(clientId=" + clientId
                        + ", priority=" + priority + ", niceValue=" + niceValue + ")");
            }

            ClientProfile profile = mClientProfiles.get(clientId);
            if (profile == null) {
                Slog.e(TAG, "Can not find client profile with id " + clientId
                        + " when trying to update the client priority.");
                return false;
            }

            profile.setPriority(priority);
            profile.setNiceValue(niceValue);

            return true;
        }

        @Override
        public void setFrontendInfoList(@NonNull TunerFrontendInfo[] infos)
                throws RemoteException {
            if (infos == null || infos.length == 0) {
                Slog.d(TAG, "Can't update with empty frontend info");
                return;
            }

            if (DEBUG) {
                Slog.d(TAG, "updateFrontendInfo:");
                for (int i = 0; i < infos.length; i++) {
                    Slog.d(TAG, infos[i].toString());
                }
            }
        }

        @Override
        public void updateCasInfo(int casSystemId, int maxSessionNum) {
            if (DEBUG) {
                Slog.d(TAG, "updateCasInfo(casSystemId="
                        + casSystemId + ", maxSessionNum=" + maxSessionNum + ")");
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

            if (getContext() == null) {
                Slog.e(TAG, "Can not find context when requesting frontend");
                return false;
            }

            if (mClientProfiles.get(request.getClientId()) == null) {
                Slog.e(TAG, "Request from unregistered client. Id: "
                        + request.getClientId());
                return false;
            }

            String sessionId = mClientProfiles.get(request.getClientId())
                    .getTvInputSessionId();

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
                Slog.d(TAG, "shareFrontend from "
                        + selfClientId + " with " + targetClientId);
            }
        }

        @Override
        public boolean requestCasSession(@NonNull CasSessionRequest request,
                    @NonNull int[] sessionResourceId) {
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
        public boolean isHigherPriority(ResourceClientProfile challengerProfile,
                ResourceClientProfile holderProfile) {
            if (DEBUG) {
                Slog.d(TAG, "isHigherPriority(challengerProfile=" + challengerProfile
                        + ", holderProfile=" + challengerProfile + ")");
            }
            return true;
        }
    }
}
