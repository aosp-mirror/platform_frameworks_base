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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.tv.ITvInputManager;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.tuner.filter.Filter;
import android.media.tv.tuner.frontend.FrontendSettings;
import android.media.tv.tunerresourcemanager.CasSessionRequest;
import android.media.tv.tunerresourcemanager.IResourcesReclaimListener;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerCiCamRequest;
import android.media.tv.tunerresourcemanager.TunerDemuxInfo;
import android.media.tv.tunerresourcemanager.TunerDemuxRequest;
import android.media.tv.tunerresourcemanager.TunerDescramblerRequest;
import android.media.tv.tunerresourcemanager.TunerFrontendInfo;
import android.media.tv.tunerresourcemanager.TunerFrontendRequest;
import android.media.tv.tunerresourcemanager.TunerLnbRequest;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.google.common.truth.Correspondence;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;

/**
 * Tests for {@link TunerResourceManagerService} class.
 */
@SmallTest
@Presubmit
@RunWith(JUnit4.class)
public class TunerResourceManagerServiceTest {
    private static final String TAG = "TunerResourceManagerServiceTest";
    private Context mContextSpy;
    @Mock private ITvInputManager mITvInputManagerMock;
    private TunerResourceManagerService mTunerResourceManagerService;
    private boolean mIsForeground;

    private final class TunerClient extends IResourcesReclaimListener.Stub {
        int[] mClientId;
        ClientProfile mProfile;
        boolean mReclaimed;

        TunerClient() {
            mClientId = new int[1];
            mClientId[0] = TunerResourceManagerService.INVALID_CLIENT_ID;
        }

        public void register(String sessionId, int useCase) {
            ResourceClientProfile profile = new ResourceClientProfile();
            profile.tvInputSessionId = sessionId;
            profile.useCase = useCase;
            mTunerResourceManagerService.registerClientProfileInternal(
                    profile, this, mClientId);
            assertThat(mClientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
            mProfile = mTunerResourceManagerService.getClientProfile(mClientId[0]);
        }

        public void register(String sessionId, int useCase, int priority, int niceValue) {
            register(sessionId, useCase);
            mTunerResourceManagerService.updateClientPriorityInternal(
                    mClientId[0], priority, niceValue);
        }

        public void register(String sessionId, int useCase, int priority) {
            register(sessionId, useCase, priority, 0);
        }

        public void unregister() {
            mTunerResourceManagerService.unregisterClientProfileInternal(mClientId[0]);
            mClientId[0] = TunerResourceManagerService.INVALID_CLIENT_ID;
            mReclaimed = false;
        }

        public int getId() {
            return mClientId[0];
        }

        public ClientProfile getProfile() {
            return mProfile;
        }

        @Override
        public void onReclaimResources() {
            mTunerResourceManagerService.clearAllResourcesAndClientMapping(mProfile);
            mReclaimed = true;
        }

        public boolean isReclaimed() {
            return mReclaimed;
        }
    }

    private static final class TestResourcesReclaimListener extends IResourcesReclaimListener.Stub {
        boolean mReclaimed;

        @Override
        public void onReclaimResources() {
            mReclaimed = true;
        }

        public boolean isReclaimed() {
            return mReclaimed;
        }
    }

    // A correspondence to compare a FrontendResource and a TunerFrontendInfo.
    private static final Correspondence<FrontendResource, TunerFrontendInfo> FR_TFI_COMPARE =
            Correspondence.from((FrontendResource actual, TunerFrontendInfo expected) -> {
                if (actual == null || expected == null) {
                    return (actual == null) && (expected == null);
                }

                return actual.getHandle() == expected.handle
                        && actual.getType() == expected.type
                        && actual.getExclusiveGroupId() == expected.exclusiveGroupId;
            },  "is correctly configured from ");

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        TvInputManager tvInputManager = new TvInputManager(mITvInputManagerMock, 0);
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        when(mContextSpy.getSystemService(Context.TV_INPUT_SERVICE)).thenReturn(tvInputManager);
        mTunerResourceManagerService = new TunerResourceManagerService(mContextSpy) {
            @Override
            protected boolean checkIsForeground(int pid) {
                return mIsForeground;
            }
        };
        mTunerResourceManagerService.onStart(true /*isForTesting*/);
    }

    @Test
    public void setFrontendListTest_addFrontendResources_noExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        Map<Integer, FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        for (int id = 0; id < infos.length; id++) {
            assertThat(resources.get(infos[id].handle)
                    .getExclusiveGroupMemberFeHandles().size()).isEqualTo(0);
        }
        for (int id = 0; id < infos.length; id++) {
            assertThat(resources.get(infos[id].handle)
                    .getExclusiveGroupMemberFeHandles().size()).isEqualTo(0);
        }
        assertThat(resources.values()).comparingElementsUsing(FR_TFI_COMPARE)
                .containsExactlyElementsIn(Arrays.asList(infos));
    }

    @Test
    public void setFrontendListTest_addFrontendResources_underTheSameExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[4];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[2] =
                tunerFrontendInfo(2 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        infos[3] =
                tunerFrontendInfo(3 /*handle*/, FrontendSettings.TYPE_ATSC, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        Map<Integer, FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        assertThat(resources.values()).comparingElementsUsing(FR_TFI_COMPARE)
                .containsExactlyElementsIn(Arrays.asList(infos));

        assertThat(resources.get(0).getExclusiveGroupMemberFeHandles()).isEmpty();
        assertThat(resources.get(1).getExclusiveGroupMemberFeHandles()).containsExactly(2, 3);
        assertThat(resources.get(2).getExclusiveGroupMemberFeHandles()).containsExactly(1, 3);
        assertThat(resources.get(3).getExclusiveGroupMemberFeHandles()).containsExactly(1, 2);
    }

    @Test
    public void setFrontendListTest_updateExistingFrontendResources() {
        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);

        mTunerResourceManagerService.setFrontendInfoListInternal(infos);
        Map<Integer, FrontendResource> resources0 =
                mTunerResourceManagerService.getFrontendResources();

        mTunerResourceManagerService.setFrontendInfoListInternal(infos);
        Map<Integer, FrontendResource> resources1 =
                mTunerResourceManagerService.getFrontendResources();

        assertThat(resources0).isEqualTo(resources1);
    }

    @Test
    public void setFrontendListTest_removeFrontendResources_noExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos0 = new TunerFrontendInfo[3];
        infos0[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos0[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos0[2] =
                tunerFrontendInfo(2 /*handle*/, FrontendSettings.TYPE_DVBS, 2 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos0);

        TunerFrontendInfo[] infos1 = new TunerFrontendInfo[1];
        infos1[0] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos1);

        Map<Integer, FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        for (int id = 0; id < infos1.length; id++) {
            assertThat(resources.get(infos1[id].handle)
                    .getExclusiveGroupMemberFeHandles().size()).isEqualTo(0);
        }
        assertThat(resources.values()).comparingElementsUsing(FR_TFI_COMPARE)
                .containsExactlyElementsIn(Arrays.asList(infos1));
    }

    @Test
    public void setFrontendListTest_removeFrontendResources_underTheSameExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos0 = new TunerFrontendInfo[3];
        infos0[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos0[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos0[2] =
                tunerFrontendInfo(2 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos0);

        TunerFrontendInfo[] infos1 = new TunerFrontendInfo[1];
        infos1[0] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos1);

        Map<Integer, FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        for (int id = 0; id < infos1.length; id++) {
            assertThat(resources.get(infos1[id].handle)
                    .getExclusiveGroupMemberFeHandles().size()).isEqualTo(0);
        }
        assertThat(resources.values()).comparingElementsUsing(FR_TFI_COMPARE)
                .containsExactlyElementsIn(Arrays.asList(infos1));
    }

    @Test
    public void requestFrontendTest_ClientNotRegistered() {
        TunerFrontendInfo[] infos0 = new TunerFrontendInfo[1];
        infos0[0] =
                tunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos0);
        TunerFrontendRequest request =
                tunerFrontendRequest(0 /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isFalse();
        assertThat(frontendHandle[0]).isEqualTo(TunerResourceManager.INVALID_RESOURCE_HANDLE);
    }

    @Test
    public void requestFrontendTest_NoFrontendWithGiveTypeAvailable() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[1];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBS, 0 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(client0.getId() /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isFalse();
        assertThat(frontendHandle[0]).isEqualTo(TunerResourceManager.INVALID_RESOURCE_HANDLE);
        client0.unregister();
    }

    @Test
    public void requestFrontendTest_FrontendWithNoExclusiveGroupAvailable() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[3];
        infos[0] = tunerFrontendInfo(
                0 /*handle*/,
                FrontendSettings.TYPE_DVBT,
                0 /*exclusiveGroupId*/);
        infos[1] = tunerFrontendInfo(
                1 /*handle*/,
                FrontendSettings.TYPE_DVBT,
                1 /*exclusiveGroupId*/);
        infos[2] = tunerFrontendInfo(
                2 /*handle*/,
                FrontendSettings.TYPE_DVBS,
                1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(client0.getId() /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(0);
        client0.unregister();
    }

    @Test
    public void requestFrontendTest_FrontendWithExclusiveGroupAvailable() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        TunerClient client1 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        client1.register("1" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[3];
        infos[0] = tunerFrontendInfo(
                0 /*handle*/,
                FrontendSettings.TYPE_DVBT,
                0 /*exclusiveGroupId*/);
        infos[1] = tunerFrontendInfo(
                1 /*handle*/,
                FrontendSettings.TYPE_DVBT,
                1 /*exclusiveGroupId*/);
        infos[2] = tunerFrontendInfo(
                2 /*handle*/,
                FrontendSettings.TYPE_DVBS,
                1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        int[] frontendHandle = new int[1];
        TunerFrontendRequest request =
                tunerFrontendRequest(client1.getId() /*clientId*/, FrontendSettings.TYPE_DVBT);
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);

        request =
                tunerFrontendRequest(client0.getId() /*clientId*/, FrontendSettings.TYPE_DVBT);
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[1].handle);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle).isInUse())
                .isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[2].handle).isInUse())
                .isTrue();
        client0.unregister();
        client1.unregister();
    }

    @Test
    public void requestFrontendTest_NoFrontendAvailable_RequestWithLowerPriority()
            throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        TunerClient client1 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 100);
        client1.register("1" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 50);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(client0.getId() /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();

        request =
                tunerFrontendRequest(client1.getId() /*clientId*/, FrontendSettings.TYPE_DVBT);
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isFalse();
        assertThat(client0.isReclaimed()).isFalse();

        request =
                tunerFrontendRequest(client1.getId() /*clientId*/, FrontendSettings.TYPE_DVBS);
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isFalse();
        assertThat(client0.isReclaimed()).isFalse();
        client0.unregister();
        client1.unregister();
    }

    @Test
    public void requestFrontendTest_NoFrontendAvailable_RequestWithHigherPriority()
            throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        TunerClient client1 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 100);
        client1.register("1" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 500);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(client0.getId() /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);
        assertThat(client0.getProfile().getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(infos[0].handle, infos[1].handle)));

        request =
                tunerFrontendRequest(client1.getId() /*clientId*/, FrontendSettings.TYPE_DVBS);
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[1].handle);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .getOwnerClientId()).isEqualTo(client1.getId());
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .getOwnerClientId()).isEqualTo(client1.getId());
        assertThat(client0.isReclaimed()).isTrue();
        client0.unregister();
        client1.unregister();
    }

    @Test
    public void releaseFrontendTest_UnderTheSameExclusiveGroup() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(client0.getId() /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);
        assertThat(mTunerResourceManagerService
                .getFrontendResource(infos[1].handle).isInUse()).isTrue();

        // Release frontend
        mTunerResourceManagerService.releaseFrontendInternal(frontendHandle[0], client0.getId());
        assertThat(mTunerResourceManagerService
                .getFrontendResource(frontendHandle[0]).isInUse()).isFalse();
        assertThat(mTunerResourceManagerService
                .getFrontendResource(infos[1].handle).isInUse()).isFalse();
        assertThat(client0.getProfile().getInUseFrontendHandles().size()).isEqualTo(0);
        client0.unregister();
    }

    @Test
    public void requestCasTest_NoCasAvailable_RequestWithHigherPriority() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        TunerClient client1 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 100);
        client1.register("1" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 500);

        // Init cas resources.
        mTunerResourceManagerService.updateCasInfoInternal(1 /*casSystemId*/, 2 /*maxSessionNum*/);

        CasSessionRequest request = casSessionRequest(client0.getId(), 1 /*casSystemId*/);
        int[] casSessionHandle = new int[1];
        // Request for 2 cas sessions.
        assertThat(mTunerResourceManagerService
                .requestCasSessionInternal(request, casSessionHandle)).isTrue();
        assertThat(mTunerResourceManagerService
                .requestCasSessionInternal(request, casSessionHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(casSessionHandle[0]))
                .isEqualTo(1);
        assertThat(client0.getProfile().getInUseCasSystemId())
                .isEqualTo(1);
        assertThat(mTunerResourceManagerService.getCasResource(1)
                .getOwnerClientIds()).isEqualTo(
                        new HashSet<Integer>(Arrays.asList(client0.getId())));
        assertThat(mTunerResourceManagerService.getCasResource(1).isFullyUsed()).isTrue();

        request = casSessionRequest(client1.getId(), 1);
        assertThat(mTunerResourceManagerService
                .requestCasSessionInternal(request, casSessionHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(casSessionHandle[0]))
                .isEqualTo(1);
        assertThat(client1.getProfile().getInUseCasSystemId()).isEqualTo(1);
        assertThat(client0.getProfile().getInUseCasSystemId())
                .isEqualTo(ClientProfile.INVALID_RESOURCE_ID);
        assertThat(mTunerResourceManagerService.getCasResource(1)
                .getOwnerClientIds()).isEqualTo(
                        new HashSet<Integer>(Arrays.asList(client1.getId())));
        assertThat(mTunerResourceManagerService.getCasResource(1).isFullyUsed()).isFalse();
        assertThat(client0.isReclaimed()).isTrue();
        client0.unregister();
        client1.unregister();
    }

    @Test
    public void requestCiCamTest_NoCiCamAvailable_RequestWithHigherPriority()
            throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        TunerClient client1 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 100);
        client1.register("1" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 500);

        // Init cicam/cas resources.
        mTunerResourceManagerService.updateCasInfoInternal(1 /*casSystemId*/, 2 /*maxSessionNum*/);

        TunerCiCamRequest request = tunerCiCamRequest(client0.getId(), 1 /*ciCamId*/);
        int[] ciCamHandle = new int[1];
        // Request for 2 ciCam sessions.
        assertThat(mTunerResourceManagerService
                .requestCiCamInternal(request, ciCamHandle)).isTrue();
        assertThat(mTunerResourceManagerService
                .requestCiCamInternal(request, ciCamHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(ciCamHandle[0]))
                .isEqualTo(1);
        assertThat(client0.getProfile().getInUseCiCamId()).isEqualTo(1);
        assertThat(mTunerResourceManagerService.getCiCamResource(1)
                .getOwnerClientIds()).isEqualTo(
                        new HashSet<Integer>(Arrays.asList(client0.getId())));
        assertThat(mTunerResourceManagerService.getCiCamResource(1).isFullyUsed()).isTrue();

        request = tunerCiCamRequest(client1.getId(), 1);
        assertThat(mTunerResourceManagerService
                .requestCiCamInternal(request, ciCamHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(ciCamHandle[0]))
                .isEqualTo(1);
        assertThat(client1.getProfile().getInUseCiCamId()).isEqualTo(1);
        assertThat(client0.getProfile().getInUseCiCamId())
                .isEqualTo(ClientProfile.INVALID_RESOURCE_ID);
        assertThat(mTunerResourceManagerService.getCiCamResource(1)
                .getOwnerClientIds()).isEqualTo(
                        new HashSet<Integer>(Arrays.asList(client1.getId())));
        assertThat(mTunerResourceManagerService
                .getCiCamResource(1).isFullyUsed()).isFalse();
        assertThat(client0.isReclaimed()).isTrue();
        client0.unregister();
        client1.unregister();
    }

    @Test
    public void releaseCasTest() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        // Init cas resources.
        mTunerResourceManagerService.updateCasInfoInternal(1 /*casSystemId*/, 2 /*maxSessionNum*/);

        CasSessionRequest request = casSessionRequest(client0.getId(), 1 /*casSystemId*/);
        int[] casSessionHandle = new int[1];
        // Request for 1 cas sessions.
        assertThat(mTunerResourceManagerService
                .requestCasSessionInternal(request, casSessionHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(casSessionHandle[0]))
                .isEqualTo(1);
        assertThat(client0.getProfile().getInUseCasSystemId()).isEqualTo(1);
        assertThat(mTunerResourceManagerService.getCasResource(1)
                .getOwnerClientIds()).isEqualTo(
                        new HashSet<Integer>(Arrays.asList(client0.getId())));
        assertThat(mTunerResourceManagerService.getCasResource(1).isFullyUsed()).isFalse();

        // Release cas
        mTunerResourceManagerService.releaseCasSessionInternal(mTunerResourceManagerService
                .getCasResource(1), client0.getId());
        assertThat(client0.getProfile().getInUseCasSystemId())
                .isEqualTo(ClientProfile.INVALID_RESOURCE_ID);
        assertThat(mTunerResourceManagerService.getCasResource(1).isFullyUsed()).isFalse();
        assertThat(mTunerResourceManagerService.getCasResource(1)
                .getOwnerClientIds()).isEmpty();
        client0.unregister();
    }

    @Test
    public void releaseCiCamTest() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        // Init cas resources.
        mTunerResourceManagerService.updateCasInfoInternal(1 /*casSystemId*/, 2 /*maxSessionNum*/);

        TunerCiCamRequest request = tunerCiCamRequest(client0.getId(), 1 /*ciCamId*/);
        int[] ciCamHandle = new int[1];
        // Request for 1 ciCam sessions.
        assertThat(mTunerResourceManagerService
                .requestCiCamInternal(request, ciCamHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(ciCamHandle[0]))
                .isEqualTo(1);
        assertThat(client0.getProfile().getInUseCiCamId()).isEqualTo(1);
        assertThat(mTunerResourceManagerService.getCiCamResource(1)
                .getOwnerClientIds()).isEqualTo(
                        new HashSet<Integer>(Arrays.asList(client0.getId())));
        assertThat(mTunerResourceManagerService
                .getCiCamResource(1).isFullyUsed()).isFalse();

        // Release ciCam
        mTunerResourceManagerService.releaseCiCamInternal(mTunerResourceManagerService
                .getCiCamResource(1), client0.getId());
        assertThat(client0.getProfile().getInUseCiCamId())
                .isEqualTo(ClientProfile.INVALID_RESOURCE_ID);
        assertThat(mTunerResourceManagerService
                .getCiCamResource(1).isFullyUsed()).isFalse();
        assertThat(mTunerResourceManagerService.getCiCamResource(1)
                .getOwnerClientIds()).isEmpty();
        client0.unregister();
    }

    @Test
    public void requestLnbTest_NoLnbAvailable_RequestWithHigherPriority() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        TunerClient client1 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 100);
        client1.register("1" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, 500);

        // Init lnb resources.
        int[] lnbHandles = {1};
        mTunerResourceManagerService.setLnbInfoListInternal(lnbHandles);

        TunerLnbRequest request = new TunerLnbRequest();
        request.clientId = client0.getId();
        int[] lnbHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestLnbInternal(request, lnbHandle)).isTrue();
        assertThat(lnbHandle[0]).isEqualTo(lnbHandles[0]);
        assertThat(client0.getProfile().getInUseLnbHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(lnbHandles[0])));

        request = new TunerLnbRequest();
        request.clientId = client1.getId();

        assertThat(mTunerResourceManagerService
                .requestLnbInternal(request, lnbHandle)).isTrue();
        assertThat(lnbHandle[0]).isEqualTo(lnbHandles[0]);
        assertThat(mTunerResourceManagerService.getLnbResource(lnbHandles[0])
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getLnbResource(lnbHandles[0])
                .getOwnerClientId()).isEqualTo(client1.getId());
        assertThat(client0.isReclaimed()).isTrue();
        assertThat(client0.getProfile().getInUseLnbHandles().size()).isEqualTo(0);
        client0.unregister();
        client1.unregister();
    }

    @Test
    public void releaseLnbTest() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        // Init lnb resources.
        int[] lnbHandles = {0};
        mTunerResourceManagerService.setLnbInfoListInternal(lnbHandles);

        TunerLnbRequest request = new TunerLnbRequest();
        request.clientId = client0.getId();
        int[] lnbHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestLnbInternal(request, lnbHandle)).isTrue();
        assertThat(lnbHandle[0]).isEqualTo(lnbHandles[0]);

        // Release lnb
        mTunerResourceManagerService.releaseLnbInternal(mTunerResourceManagerService
                .getLnbResource(lnbHandle[0]));
        assertThat(mTunerResourceManagerService
                .getLnbResource(lnbHandle[0]).isInUse()).isFalse();
        assertThat(client0.getProfile().getInUseLnbHandles().size()).isEqualTo(0);
        client0.unregister();
    }

    @Test
    public void unregisterClientTest_usingFrontend() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(client0.getId() /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isTrue();

        // Unregister client when using frontend
        client0.unregister();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isFalse();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isFalse();
        assertThat(mTunerResourceManagerService.checkClientExists(client0.getId())).isFalse();
    }

    @Test
    public void requestDemuxTest() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        TunerDemuxInfo[] infos = new TunerDemuxInfo[3];
        infos[0] = tunerDemuxInfo(0 /* handle */, Filter.TYPE_TS | Filter.TYPE_IP);
        infos[1] = tunerDemuxInfo(1 /* handle */, Filter.TYPE_TLV);
        infos[2] = tunerDemuxInfo(2 /* handle */, Filter.TYPE_TS);
        mTunerResourceManagerService.setDemuxInfoListInternal(infos);

        int[] demuxHandle0 = new int[1];
        // first with undefined type (should be the first one with least # of caps)
        TunerDemuxRequest request = tunerDemuxRequest(client0.getId(), Filter.TYPE_UNDEFINED);
        assertThat(mTunerResourceManagerService.requestDemuxInternal(request, demuxHandle0))
                .isTrue();
        assertThat(demuxHandle0[0]).isEqualTo(1);
        DemuxResource dr = mTunerResourceManagerService.getDemuxResource(demuxHandle0[0]);
        mTunerResourceManagerService.releaseDemuxInternal(dr);

        // now with non-supported type (ALP)
        request.desiredFilterTypes = Filter.TYPE_ALP;
        demuxHandle0[0] = -1;
        assertThat(mTunerResourceManagerService.requestDemuxInternal(request, demuxHandle0))
                .isFalse();
        assertThat(demuxHandle0[0]).isEqualTo(-1);

        // now with TS (should be the one with least # of caps that supports TS)
        request.desiredFilterTypes = Filter.TYPE_TS;
        assertThat(mTunerResourceManagerService.requestDemuxInternal(request, demuxHandle0))
                .isTrue();
        assertThat(demuxHandle0[0]).isEqualTo(2);

        // request for another TS
        TunerClient client1 = new TunerClient();
        client1.register("1" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        int[] demuxHandle1 = new int[1];
        TunerDemuxRequest request1 = tunerDemuxRequest(client1.getId(), Filter.TYPE_TS);
        assertThat(mTunerResourceManagerService.requestDemuxInternal(request1, demuxHandle1))
                .isTrue();
        assertThat(demuxHandle1[0]).isEqualTo(0);
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(client1.getId()))
                .isEqualTo(0);

        // release demuxes
        dr = mTunerResourceManagerService.getDemuxResource(demuxHandle0[0]);
        mTunerResourceManagerService.releaseDemuxInternal(dr);
        dr = mTunerResourceManagerService.getDemuxResource(demuxHandle1[0]);
        mTunerResourceManagerService.releaseDemuxInternal(dr);

        client0.unregister();
        client1.unregister();
    }

    @Test
    public void requestDemuxTest_ResourceReclaim() throws RemoteException {
        // Register clients
        TunerClient client0 = new TunerClient();
        TunerClient client1 = new TunerClient();
        TunerClient client2 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        client1.register("1" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_SCAN);
        client2.register("2" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_SCAN);

        // Init demux resources.
        TunerDemuxInfo[] infos = new TunerDemuxInfo[2];
        infos[0] = tunerDemuxInfo(0 /*handle*/, Filter.TYPE_TS | Filter.TYPE_IP);
        infos[1] = tunerDemuxInfo(1 /*handle*/, Filter.TYPE_TS);
        mTunerResourceManagerService.setDemuxInfoListInternal(infos);

        // let client0(prio:100) request for IP - should succeed
        TunerDemuxRequest request0 = tunerDemuxRequest(client0.getId(), Filter.TYPE_IP);
        int[] demuxHandle0 = new int[1];
        assertThat(mTunerResourceManagerService
                .requestDemuxInternal(request0, demuxHandle0)).isTrue();
        assertThat(demuxHandle0[0]).isEqualTo(0);

        // let client1(prio:50) request for IP - should fail
        TunerDemuxRequest request1 = tunerDemuxRequest(client1.getId(), Filter.TYPE_IP);
        int[] demuxHandle1 = new int[1];
        demuxHandle1[0] = -1;
        assertThat(mTunerResourceManagerService
                .requestDemuxInternal(request1, demuxHandle1)).isFalse();
        assertThat(client0.isReclaimed()).isFalse();
        assertThat(demuxHandle1[0]).isEqualTo(-1);

        // let client1(prio:50) request for TS - should succeed
        request1.desiredFilterTypes = Filter.TYPE_TS;
        assertThat(mTunerResourceManagerService
                .requestDemuxInternal(request1, demuxHandle1)).isTrue();
        assertThat(demuxHandle1[0]).isEqualTo(1);
        assertThat(client0.isReclaimed()).isFalse();

        // now release demux for the client0 (higher priority) and request demux
        DemuxResource dr = mTunerResourceManagerService.getDemuxResource(demuxHandle0[0]);
        mTunerResourceManagerService.releaseDemuxInternal(dr);

        // let client2(prio:50) request for TS - should succeed
        TunerDemuxRequest request2 = tunerDemuxRequest(client2.getId(), Filter.TYPE_TS);
        int[] demuxHandle2 = new int[1];
        assertThat(mTunerResourceManagerService
                .requestDemuxInternal(request2, demuxHandle2)).isTrue();
        assertThat(demuxHandle2[0]).isEqualTo(0);
        assertThat(client1.isReclaimed()).isFalse();

        // let client0(prio:100) request for TS - should reclaim from client1
        // , who has the smaller caps
        request0.desiredFilterTypes = Filter.TYPE_TS;
        assertThat(mTunerResourceManagerService
                .requestDemuxInternal(request0, demuxHandle0)).isTrue();
        assertThat(client1.isReclaimed()).isTrue();
        assertThat(client2.isReclaimed()).isFalse();
        client0.unregister();
        client1.unregister();
        client2.unregister();
    }

    @Test
    public void requestDescramblerTest() {
        // Register clients
        TunerClient client0 = new TunerClient();
        client0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);

        int[] desHandle = new int[1];
        TunerDescramblerRequest request = new TunerDescramblerRequest();
        request.clientId = client0.getId();
        assertThat(mTunerResourceManagerService.requestDescramblerInternal(request, desHandle))
                .isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(desHandle[0])).isEqualTo(0);
        client0.unregister();
    }

    @Test
    public void isHigherPriorityTest() {
        mIsForeground = false;
        ResourceClientProfile backgroundPlaybackProfile =
                resourceClientProfile(null /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        ResourceClientProfile backgroundRecordProfile =
                resourceClientProfile(null /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD);
        int backgroundPlaybackPriority = mTunerResourceManagerService.getClientPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK, mIsForeground);
        int backgroundRecordPriority = mTunerResourceManagerService.getClientPriority(
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD, mIsForeground);
        assertThat(mTunerResourceManagerService.isHigherPriorityInternal(backgroundPlaybackProfile,
                backgroundRecordProfile)).isEqualTo(
                        (backgroundPlaybackPriority > backgroundRecordPriority));
    }

    @Test
    public void shareFrontendTest_FrontendWithExclusiveGroupReadyToShare() throws RemoteException {
        /**** Register Clients and Set Priority ****/
        TunerClient ownerClient0 = new TunerClient();
        TunerClient ownerClient1 = new TunerClient();
        TunerClient shareClient0 = new TunerClient();
        TunerClient shareClient1 = new TunerClient();
        ownerClient0.register("0" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE, 100);
        ownerClient1.register("1" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE, 300);
        shareClient0.register("2" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD, 200);
        shareClient1.register("3" /*sessionId*/,
                        TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD, 400);

        mTunerResourceManagerService.updateClientPriorityInternal(
                shareClient1.getId(),
                -1/*invalid priority*/,
                0/*niceValue*/);
        assertThat(shareClient1.getProfile().getPriority()).isEqualTo(400);

        /**** Init Frontend Resources ****/

        // Predefined frontend info
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] = tunerFrontendInfo(
                0 /*handle*/,
                FrontendSettings.TYPE_DVBT,
                1 /*exclusiveGroupId*/);
        infos[1] = tunerFrontendInfo(
                1 /*handle*/,
                FrontendSettings.TYPE_DVBS,
                1 /*exclusiveGroupId*/);

        /**** Init Lnb Resources ****/
        int[] lnbHandles = {1};
        mTunerResourceManagerService.setLnbInfoListInternal(lnbHandles);

        // Update frontend list in TRM
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        /**** Request Frontend ****/

        // Predefined frontend request and array to save returned frontend handle
        int[] frontendHandle = new int[1];
        TunerFrontendRequest request = tunerFrontendRequest(
                ownerClient0.getId() /*clientId*/,
                FrontendSettings.TYPE_DVBT);

        // Request call and validate granted resource and internal mapping
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle))
                .isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);
        assertThat(ownerClient0.getProfile().getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));

        /**** Share Frontend ****/

        // Share frontend call and validate the internal mapping
        mTunerResourceManagerService.shareFrontendInternal(
                shareClient0.getId()/*selfClientId*/,
                ownerClient0.getId()/*targetClientId*/);
        mTunerResourceManagerService.shareFrontendInternal(
                shareClient1.getId()/*selfClientId*/,
                ownerClient0.getId()/*targetClientId*/);
        // Verify fe in use status
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isTrue();
        // Verify fe owner status
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .getOwnerClientId()).isEqualTo(ownerClient0.getId());
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .getOwnerClientId()).isEqualTo(ownerClient0.getId());
        // Verify share fe client status in the primary owner client
        assertThat(ownerClient0.getProfile().getShareFeClientIds())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        shareClient0.getId(),
                        shareClient1.getId())));
        // Verify in use frontend list in all the primary owner and share owner clients
        assertThat(ownerClient0.getProfile().getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));
        assertThat(shareClient0.getProfile().getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));
        assertThat(shareClient1.getProfile().getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));

        /**** Remove Frontend Share Owner ****/

        // Unregister the second share fe client
        shareClient1.unregister();

        // Validate the internal mapping
        assertThat(ownerClient0.getProfile().getShareFeClientIds())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        shareClient0.getId())));
        assertThat(ownerClient0.getProfile().getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));
        assertThat(shareClient0.getProfile()
                .getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));

        /**** Request Shared Frontend with Higher Priority Client ****/

        // Predefined second frontend request
        request = tunerFrontendRequest(
                ownerClient1.getId() /*clientId*/,
                FrontendSettings.TYPE_DVBT);

        // Second request call
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle))
                .isTrue();

        // Validate granted resource and internal mapping
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .getOwnerClientId()).isEqualTo(ownerClient1.getId());
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .getOwnerClientId()).isEqualTo(ownerClient1.getId());
        assertThat(ownerClient1.getProfile().getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));
        assertThat(ownerClient0.getProfile().getInUseFrontendHandles()
                .isEmpty())
                .isTrue();
        assertThat(shareClient0.getProfile().getInUseFrontendHandles()
                .isEmpty())
                .isTrue();
        assertThat(ownerClient0.getProfile().getShareFeClientIds()
                .isEmpty())
                .isTrue();
        assertThat(ownerClient0.isReclaimed()).isTrue();
        assertThat(shareClient0.isReclaimed()).isTrue();

        /**** Release Frontend Resource From Primary Owner ****/

        // Reshare the frontend
        mTunerResourceManagerService.shareFrontendInternal(
                shareClient0.getId()/*selfClientId*/,
                ownerClient1.getId()/*targetClientId*/);

        // Release the frontend resource from the primary owner
        mTunerResourceManagerService.releaseFrontendInternal(infos[0].handle,
                ownerClient1.getId());

        // Validate the internal mapping
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isFalse();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isFalse();
        // Verify client status
        assertThat(ownerClient1.getProfile().getInUseFrontendHandles()
                .isEmpty())
                .isTrue();
        assertThat(shareClient0.getProfile().getInUseFrontendHandles()
                .isEmpty())
                .isTrue();
        assertThat(ownerClient1.getProfile().getShareFeClientIds()
                .isEmpty())
                .isTrue();

        /**** Unregister Primary Owner when the Share owner owns an Lnb ****/

        // Predefined Lnb request and handle array
        TunerLnbRequest requestLnb = new TunerLnbRequest();
        requestLnb.clientId = shareClient0.getId();
        int[] lnbHandle = new int[1];

        // Request for an Lnb
        assertThat(mTunerResourceManagerService
                .requestLnbInternal(requestLnb, lnbHandle))
                .isTrue();

        // Request and share the frontend resource again
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle))
                .isTrue();
        mTunerResourceManagerService.shareFrontendInternal(
                shareClient0.getId()/*selfClientId*/,
                ownerClient1.getId()/*targetClientId*/);

        // Unregister the primary owner of the shared frontend
        ownerClient1.unregister();

        // Validate the internal mapping
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isFalse();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isFalse();
        // Verify client status
        assertThat(shareClient0.getProfile().getInUseFrontendHandles()
                .isEmpty())
                .isTrue();
        assertThat(shareClient0.getProfile().getInUseLnbHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        lnbHandles[0])));

        ownerClient0.unregister();
        shareClient0.unregister();
    }

    private TunerFrontendInfo tunerFrontendInfo(
            int handle, int frontendType, int exclusiveGroupId) {
        TunerFrontendInfo info = new TunerFrontendInfo();
        info.handle = handle;
        info.type = frontendType;
        info.exclusiveGroupId = exclusiveGroupId;
        return info;
    }

    private TunerFrontendRequest tunerFrontendRequest(int clientId, int frontendType) {
        TunerFrontendRequest request = new TunerFrontendRequest();
        request.clientId = clientId;
        request.frontendType = frontendType;
        return request;
    }

    private ResourceClientProfile resourceClientProfile(String sessionId, int useCase) {
        ResourceClientProfile profile = new ResourceClientProfile();
        profile.tvInputSessionId = sessionId;
        profile.useCase = useCase;
        return profile;
    }

    private CasSessionRequest casSessionRequest(int clientId, int casSystemId) {
        CasSessionRequest request = new CasSessionRequest();
        request.clientId = clientId;
        request.casSystemId = casSystemId;
        return request;
    }

    private TunerCiCamRequest tunerCiCamRequest(int clientId, int ciCamId) {
        TunerCiCamRequest request = new TunerCiCamRequest();
        request.clientId = clientId;
        request.ciCamId = ciCamId;
        return request;
    }

    private TunerDemuxInfo tunerDemuxInfo(int handle, int supportedFilterTypes) {
        TunerDemuxInfo info = new TunerDemuxInfo();
        info.handle = handle;
        info.filterTypes = supportedFilterTypes;
        return info;
    }

    private TunerDemuxRequest tunerDemuxRequest(int clientId, int desiredFilterTypes) {
        TunerDemuxRequest request = new TunerDemuxRequest();
        request.clientId = clientId;
        request.desiredFilterTypes = desiredFilterTypes;
        return request;
    }
}
