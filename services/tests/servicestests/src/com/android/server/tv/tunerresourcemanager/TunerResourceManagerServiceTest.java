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
import android.media.tv.tuner.frontend.FrontendSettings;
import android.media.tv.tunerresourcemanager.CasSessionRequest;
import android.media.tv.tunerresourcemanager.IResourcesReclaimListener;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerCiCamRequest;
import android.media.tv.tunerresourcemanager.TunerDemuxRequest;
import android.media.tv.tunerresourcemanager.TunerDescramblerRequest;
import android.media.tv.tunerresourcemanager.TunerFrontendInfo;
import android.media.tv.tunerresourcemanager.TunerFrontendRequest;
import android.media.tv.tunerresourcemanager.TunerLnbRequest;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
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
    public void requestFrontendTest_NoFrontendWithGiveTypeAvailable() {
        ResourceClientProfile profile = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profile, null /*listener*/, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[1];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBS, 0 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(clientId[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isFalse();
        assertThat(frontendHandle[0]).isEqualTo(TunerResourceManager.INVALID_RESOURCE_HANDLE);
    }

    @Test
    public void requestFrontendTest_FrontendWithNoExclusiveGroupAvailable() {
        ResourceClientProfile profile = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profile, null /*listener*/, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

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
                tunerFrontendRequest(clientId[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(0);
    }

    @Test
    public void requestFrontendTest_FrontendWithExclusiveGroupAvailable() {
        ResourceClientProfile profile0 = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        ResourceClientProfile profile1 = resourceClientProfile("1" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId0 = new int[1];
        int[] clientId1 = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profile0, null /*listener*/, clientId0);
        mTunerResourceManagerService.registerClientProfileInternal(
                profile1, null /*listener*/, clientId1);
        assertThat(clientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        assertThat(clientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

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
                tunerFrontendRequest(clientId1[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);

        request =
                tunerFrontendRequest(clientId0[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[1].handle);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle).isInUse())
                .isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[2].handle).isInUse())
                .isTrue();
    }

    @Test
    public void requestFrontendTest_NoFrontendAvailable_RequestWithLowerPriority() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[2];
        profiles[0] = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        profiles[1] = resourceClientProfile("1" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientPriorities = {100, 50};
        int[] clientId0 = new int[1];
        int[] clientId1 = new int[1];
        TestResourcesReclaimListener listener = new TestResourcesReclaimListener();

        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[0], listener, clientId0);
        assertThat(clientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.updateClientPriorityInternal(
                clientId0[0], clientPriorities[0], 0/*niceValue*/);
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[1], new TestResourcesReclaimListener(), clientId1);
        assertThat(clientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.updateClientPriorityInternal(
                clientId1[0], clientPriorities[1], 0/*niceValue*/);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(clientId0[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();

        request =
                tunerFrontendRequest(clientId1[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isFalse();
        assertThat(listener.isReclaimed()).isFalse();

        request =
                tunerFrontendRequest(clientId1[0] /*clientId*/, FrontendSettings.TYPE_DVBS);
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isFalse();
        assertThat(listener.isReclaimed()).isFalse();
    }

    @Test
    public void requestFrontendTest_NoFrontendAvailable_RequestWithHigherPriority() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[2];
        profiles[0] = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        profiles[1] = resourceClientProfile("1" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientPriorities = {100, 500};
        int[] clientId0 = new int[1];
        int[] clientId1 = new int[1];
        TestResourcesReclaimListener listener = new TestResourcesReclaimListener();
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[0], listener, clientId0);
        assertThat(clientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.updateClientPriorityInternal(
                clientId0[0], clientPriorities[0], 0/*niceValue*/);
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[1], new TestResourcesReclaimListener(), clientId1);
        assertThat(clientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.updateClientPriorityInternal(
                clientId1[0], clientPriorities[1], 0/*niceValue*/);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(clientId0[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId0[0])
                .getInUseFrontendHandles()).isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle, infos[1].handle)));

        request =
                tunerFrontendRequest(clientId1[0] /*clientId*/, FrontendSettings.TYPE_DVBS);
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[1].handle);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .getOwnerClientId()).isEqualTo(clientId1[0]);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .getOwnerClientId()).isEqualTo(clientId1[0]);
        assertThat(listener.isReclaimed()).isTrue();
    }

    @Test
    public void releaseFrontendTest_UnderTheSameExclusiveGroup() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[1];
        profiles[0] = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        TestResourcesReclaimListener listener = new TestResourcesReclaimListener();
        mTunerResourceManagerService.registerClientProfileInternal(profiles[0], listener, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(clientId[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);
        assertThat(mTunerResourceManagerService
                .getFrontendResource(infos[1].handle).isInUse()).isTrue();

        // Release frontend
        mTunerResourceManagerService.releaseFrontendInternal(mTunerResourceManagerService
                .getFrontendResource(frontendHandle[0]), clientId[0]);
        assertThat(mTunerResourceManagerService
                .getFrontendResource(frontendHandle[0]).isInUse()).isFalse();
        assertThat(mTunerResourceManagerService
                .getFrontendResource(infos[1].handle).isInUse()).isFalse();
        assertThat(mTunerResourceManagerService
                .getClientProfile(clientId[0]).getInUseFrontendHandles().size()).isEqualTo(0);
    }

    @Test
    public void requestCasTest_NoCasAvailable_RequestWithHigherPriority() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[2];
        profiles[0] = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        profiles[1] = resourceClientProfile("1" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientPriorities = {100, 500};
        int[] clientId0 = new int[1];
        int[] clientId1 = new int[1];
        TestResourcesReclaimListener listener = new TestResourcesReclaimListener();
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[0], listener, clientId0);
        assertThat(clientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.updateClientPriorityInternal(
                clientId0[0], clientPriorities[0], 0/*niceValue*/);
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[1], new TestResourcesReclaimListener(), clientId1);
        assertThat(clientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.updateClientPriorityInternal(
                clientId1[0], clientPriorities[1], 0/*niceValue*/);

        // Init cas resources.
        mTunerResourceManagerService.updateCasInfoInternal(1 /*casSystemId*/, 2 /*maxSessionNum*/);

        CasSessionRequest request = casSessionRequest(clientId0[0], 1 /*casSystemId*/);
        int[] casSessionHandle = new int[1];
        // Request for 2 cas sessions.
        assertThat(mTunerResourceManagerService
                .requestCasSessionInternal(request, casSessionHandle)).isTrue();
        assertThat(mTunerResourceManagerService
                .requestCasSessionInternal(request, casSessionHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(casSessionHandle[0]))
                .isEqualTo(1);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId0[0])
                .getInUseCasSystemId()).isEqualTo(1);
        assertThat(mTunerResourceManagerService.getCasResource(1)
                .getOwnerClientIds()).isEqualTo(new HashSet<Integer>(Arrays.asList(clientId0[0])));
        assertThat(mTunerResourceManagerService.getCasResource(1).isFullyUsed()).isTrue();

        request = casSessionRequest(clientId1[0], 1);
        assertThat(mTunerResourceManagerService
                .requestCasSessionInternal(request, casSessionHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(casSessionHandle[0]))
                .isEqualTo(1);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId1[0])
                .getInUseCasSystemId()).isEqualTo(1);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId0[0])
                .getInUseCasSystemId()).isEqualTo(ClientProfile.INVALID_RESOURCE_ID);
        assertThat(mTunerResourceManagerService.getCasResource(1)
                .getOwnerClientIds()).isEqualTo(new HashSet<Integer>(Arrays.asList(clientId1[0])));
        assertThat(mTunerResourceManagerService.getCasResource(1).isFullyUsed()).isFalse();
        assertThat(listener.isReclaimed()).isTrue();
    }

    @Test
    public void requestCiCamTest_NoCiCamAvailable_RequestWithHigherPriority() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[2];
        profiles[0] = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        profiles[1] = resourceClientProfile("1" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientPriorities = {100, 500};
        int[] clientId0 = new int[1];
        int[] clientId1 = new int[1];
        TestResourcesReclaimListener listener = new TestResourcesReclaimListener();
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[0], listener, clientId0);
        assertThat(clientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.updateClientPriorityInternal(
                clientId0[0], clientPriorities[0], 0/*niceValue*/);
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[1], new TestResourcesReclaimListener(), clientId1);
        assertThat(clientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.updateClientPriorityInternal(
                clientId1[0], clientPriorities[1], 0/*niceValue*/);

        // Init cicam/cas resources.
        mTunerResourceManagerService.updateCasInfoInternal(1 /*casSystemId*/, 2 /*maxSessionNum*/);

        TunerCiCamRequest request = tunerCiCamRequest(clientId0[0], 1 /*ciCamId*/);
        int[] ciCamHandle = new int[1];
        // Request for 2 ciCam sessions.
        assertThat(mTunerResourceManagerService
                .requestCiCamInternal(request, ciCamHandle)).isTrue();
        assertThat(mTunerResourceManagerService
                .requestCiCamInternal(request, ciCamHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(ciCamHandle[0]))
                .isEqualTo(1);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId0[0])
                .getInUseCiCamId()).isEqualTo(1);
        assertThat(mTunerResourceManagerService.getCiCamResource(1)
                .getOwnerClientIds()).isEqualTo(new HashSet<Integer>(Arrays.asList(clientId0[0])));
        assertThat(mTunerResourceManagerService.getCiCamResource(1).isFullyUsed()).isTrue();

        request = tunerCiCamRequest(clientId1[0], 1);
        assertThat(mTunerResourceManagerService
                .requestCiCamInternal(request, ciCamHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(ciCamHandle[0]))
                .isEqualTo(1);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId1[0])
                .getInUseCiCamId()).isEqualTo(1);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId0[0])
                .getInUseCiCamId()).isEqualTo(ClientProfile.INVALID_RESOURCE_ID);
        assertThat(mTunerResourceManagerService.getCiCamResource(1)
                .getOwnerClientIds()).isEqualTo(new HashSet<Integer>(Arrays.asList(clientId1[0])));
        assertThat(mTunerResourceManagerService.getCiCamResource(1).isFullyUsed()).isFalse();
        assertThat(listener.isReclaimed()).isTrue();
    }

    @Test
    public void releaseCasTest() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[1];
        profiles[0] = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        TestResourcesReclaimListener listener = new TestResourcesReclaimListener();
        mTunerResourceManagerService.registerClientProfileInternal(profiles[0], listener, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        // Init cas resources.
        mTunerResourceManagerService.updateCasInfoInternal(1 /*casSystemId*/, 2 /*maxSessionNum*/);

        CasSessionRequest request = casSessionRequest(clientId[0], 1 /*casSystemId*/);
        int[] casSessionHandle = new int[1];
        // Request for 1 cas sessions.
        assertThat(mTunerResourceManagerService
                .requestCasSessionInternal(request, casSessionHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(casSessionHandle[0]))
                .isEqualTo(1);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId[0])
                .getInUseCasSystemId()).isEqualTo(1);
        assertThat(mTunerResourceManagerService.getCasResource(1)
                .getOwnerClientIds()).isEqualTo(new HashSet<Integer>(Arrays.asList(clientId[0])));
        assertThat(mTunerResourceManagerService.getCasResource(1).isFullyUsed()).isFalse();

        // Release cas
        mTunerResourceManagerService.releaseCasSessionInternal(mTunerResourceManagerService
                .getCasResource(1), clientId[0]);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId[0])
                .getInUseCasSystemId()).isEqualTo(ClientProfile.INVALID_RESOURCE_ID);
        assertThat(mTunerResourceManagerService.getCasResource(1).isFullyUsed()).isFalse();
        assertThat(mTunerResourceManagerService.getCasResource(1)
                .getOwnerClientIds()).isEmpty();
    }

    @Test
    public void releaseCiCamTest() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[1];
        profiles[0] = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        TestResourcesReclaimListener listener = new TestResourcesReclaimListener();
        mTunerResourceManagerService.registerClientProfileInternal(profiles[0], listener, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        // Init cas resources.
        mTunerResourceManagerService.updateCasInfoInternal(1 /*casSystemId*/, 2 /*maxSessionNum*/);

        TunerCiCamRequest request = tunerCiCamRequest(clientId[0], 1 /*ciCamId*/);
        int[] ciCamHandle = new int[1];
        // Request for 1 ciCam sessions.
        assertThat(mTunerResourceManagerService
                .requestCiCamInternal(request, ciCamHandle)).isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(ciCamHandle[0]))
                .isEqualTo(1);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId[0])
                .getInUseCiCamId()).isEqualTo(1);
        assertThat(mTunerResourceManagerService.getCiCamResource(1)
                .getOwnerClientIds()).isEqualTo(new HashSet<Integer>(Arrays.asList(clientId[0])));
        assertThat(mTunerResourceManagerService.getCiCamResource(1).isFullyUsed()).isFalse();

        // Release ciCam
        mTunerResourceManagerService.releaseCiCamInternal(mTunerResourceManagerService
                .getCiCamResource(1), clientId[0]);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId[0])
                .getInUseCiCamId()).isEqualTo(ClientProfile.INVALID_RESOURCE_ID);
        assertThat(mTunerResourceManagerService.getCiCamResource(1).isFullyUsed()).isFalse();
        assertThat(mTunerResourceManagerService.getCiCamResource(1)
                .getOwnerClientIds()).isEmpty();
    }

    @Test
    public void requestLnbTest_NoLnbAvailable_RequestWithHigherPriority() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[2];
        profiles[0] = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        profiles[1] = resourceClientProfile("1" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientPriorities = {100, 500};
        int[] clientId0 = new int[1];
        int[] clientId1 = new int[1];
        TestResourcesReclaimListener listener = new TestResourcesReclaimListener();
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[0], listener, clientId0);
        assertThat(clientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.updateClientPriorityInternal(
                clientId0[0], clientPriorities[0], 0/*niceValue*/);
        mTunerResourceManagerService.registerClientProfileInternal(
                profiles[1], new TestResourcesReclaimListener(), clientId1);
        assertThat(clientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        mTunerResourceManagerService.updateClientPriorityInternal(
                clientId1[0], clientPriorities[1], 0/*niceValue*/);

        // Init lnb resources.
        int[] lnbHandles = {1};
        mTunerResourceManagerService.setLnbInfoListInternal(lnbHandles);

        TunerLnbRequest request = new TunerLnbRequest();
        request.clientId = clientId0[0];
        int[] lnbHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestLnbInternal(request, lnbHandle)).isTrue();
        assertThat(lnbHandle[0]).isEqualTo(lnbHandles[0]);
        assertThat(mTunerResourceManagerService.getClientProfile(clientId0[0]).getInUseLnbHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(lnbHandles[0])));

        request = new TunerLnbRequest();
        request.clientId = clientId1[0];

        assertThat(mTunerResourceManagerService
                .requestLnbInternal(request, lnbHandle)).isTrue();
        assertThat(lnbHandle[0]).isEqualTo(lnbHandles[0]);
        assertThat(mTunerResourceManagerService.getLnbResource(lnbHandles[0])
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getLnbResource(lnbHandles[0])
                .getOwnerClientId()).isEqualTo(clientId1[0]);
        assertThat(listener.isReclaimed()).isTrue();
        assertThat(mTunerResourceManagerService.getClientProfile(clientId0[0])
                .getInUseLnbHandles().size()).isEqualTo(0);
    }

    @Test
    public void releaseLnbTest() {
        // Register clients
        ResourceClientProfile[] profiles = new ResourceClientProfile[1];
        profiles[0] = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        TestResourcesReclaimListener listener = new TestResourcesReclaimListener();
        mTunerResourceManagerService.registerClientProfileInternal(profiles[0], listener, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        // Init lnb resources.
        int[] lnbHandles = {0};
        mTunerResourceManagerService.setLnbInfoListInternal(lnbHandles);

        TunerLnbRequest request = new TunerLnbRequest();
        request.clientId = clientId[0];
        int[] lnbHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestLnbInternal(request, lnbHandle)).isTrue();
        assertThat(lnbHandle[0]).isEqualTo(lnbHandles[0]);

        // Release lnb
        mTunerResourceManagerService.releaseLnbInternal(mTunerResourceManagerService
                .getLnbResource(lnbHandle[0]));
        assertThat(mTunerResourceManagerService
                .getLnbResource(lnbHandle[0]).isInUse()).isFalse();
        assertThat(mTunerResourceManagerService
                .getClientProfile(clientId[0]).getInUseLnbHandles().size()).isEqualTo(0);
    }

    @Test
    public void unregisterClientTest_usingFrontend() {
        // Register client
        ResourceClientProfile profile = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profile, null /*listener*/, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                tunerFrontendInfo(0 /*handle*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                tunerFrontendInfo(1 /*handle*/, FrontendSettings.TYPE_DVBS, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        TunerFrontendRequest request =
                tunerFrontendRequest(clientId[0] /*clientId*/, FrontendSettings.TYPE_DVBT);
        int[] frontendHandle = new int[1];
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle)).isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isTrue();

        // Unregister client when using frontend
        mTunerResourceManagerService.unregisterClientProfileInternal(clientId[0]);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isFalse();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isFalse();
        assertThat(mTunerResourceManagerService.checkClientExists(clientId[0])).isFalse();

    }

    @Test
    public void requestDemuxTest() {
        // Register client
        ResourceClientProfile profile = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profile, null /*listener*/, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        int[] demuxHandle = new int[1];
        TunerDemuxRequest request = new TunerDemuxRequest();
        request.clientId = clientId[0];
        assertThat(mTunerResourceManagerService.requestDemuxInternal(request, demuxHandle))
                .isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(demuxHandle[0]))
                .isEqualTo(0);
    }

    @Test
    public void requestDescramblerTest() {
        // Register client
        ResourceClientProfile profile = resourceClientProfile("0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_PLAYBACK);
        int[] clientId = new int[1];
        mTunerResourceManagerService.registerClientProfileInternal(
                profile, null /*listener*/, clientId);
        assertThat(clientId[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        int[] desHandle = new int[1];
        TunerDescramblerRequest request = new TunerDescramblerRequest();
        request.clientId = clientId[0];
        assertThat(mTunerResourceManagerService.requestDescramblerInternal(request, desHandle))
                .isTrue();
        assertThat(mTunerResourceManagerService.getResourceIdFromHandle(desHandle[0])).isEqualTo(0);
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
    public void shareFrontendTest_FrontendWithExclusiveGroupReadyToShare() {
        /**** Register Clients and Set Priority ****/

        // Int array to save the returned client ids
        int[] ownerClientId0 = new int[1];
        int[] ownerClientId1 = new int[1];
        int[] shareClientId0 = new int[1];
        int[] shareClientId1 = new int[1];

        // Predefined client profiles
        ResourceClientProfile[] ownerProfiles = new ResourceClientProfile[2];
        ResourceClientProfile[] shareProfiles = new ResourceClientProfile[2];
        ownerProfiles[0] = resourceClientProfile(
                "0" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE);
        ownerProfiles[1] = resourceClientProfile(
                "1" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_LIVE);
        shareProfiles[0] = resourceClientProfile(
                "2" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD);
        shareProfiles[1] = resourceClientProfile(
                "3" /*sessionId*/,
                TvInputService.PRIORITY_HINT_USE_CASE_TYPE_RECORD);

        // Predefined client reclaim listeners
        TestResourcesReclaimListener ownerListener0 = new TestResourcesReclaimListener();
        TestResourcesReclaimListener shareListener0 = new TestResourcesReclaimListener();
        TestResourcesReclaimListener ownerListener1 = new TestResourcesReclaimListener();
        TestResourcesReclaimListener shareListener1 = new TestResourcesReclaimListener();
        // Register clients and validate the returned client ids
        mTunerResourceManagerService
                .registerClientProfileInternal(ownerProfiles[0], ownerListener0, ownerClientId0);
        mTunerResourceManagerService
                .registerClientProfileInternal(shareProfiles[0], shareListener0, shareClientId0);
        mTunerResourceManagerService
                .registerClientProfileInternal(ownerProfiles[1], ownerListener1, ownerClientId1);
        mTunerResourceManagerService
                .registerClientProfileInternal(shareProfiles[1], shareListener1, shareClientId1);
        assertThat(ownerClientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        assertThat(shareClientId0[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        assertThat(ownerClientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);
        assertThat(shareClientId1[0]).isNotEqualTo(TunerResourceManagerService.INVALID_CLIENT_ID);

        mTunerResourceManagerService.updateClientPriorityInternal(
                ownerClientId0[0],
                100/*priority*/,
                0/*niceValue*/);
        mTunerResourceManagerService.updateClientPriorityInternal(
                shareClientId0[0],
                200/*priority*/,
                0/*niceValue*/);
        mTunerResourceManagerService.updateClientPriorityInternal(
                ownerClientId1[0],
                300/*priority*/,
                0/*niceValue*/);
        mTunerResourceManagerService.updateClientPriorityInternal(
                shareClientId1[0],
                400/*priority*/,
                0/*niceValue*/);
        mTunerResourceManagerService.updateClientPriorityInternal(
                shareClientId1[0],
                -1/*invalid priority*/,
                0/*niceValue*/);
        assertThat(mTunerResourceManagerService
                .getClientProfile(shareClientId1[0])
                .getPriority())
                .isEqualTo(400);

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
                ownerClientId0[0] /*clientId*/,
                FrontendSettings.TYPE_DVBT);

        // Request call and validate granted resource and internal mapping
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle))
                .isTrue();
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);
        assertThat(mTunerResourceManagerService
                .getClientProfile(ownerClientId0[0])
                .getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));

        /**** Share Frontend ****/

        // Share frontend call and validate the internal mapping
        mTunerResourceManagerService.shareFrontendInternal(
                shareClientId0[0]/*selfClientId*/,
                ownerClientId0[0]/*targetClientId*/);
        mTunerResourceManagerService.shareFrontendInternal(
                shareClientId1[0]/*selfClientId*/,
                ownerClientId0[0]/*targetClientId*/);
        // Verify fe in use status
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isTrue();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isTrue();
        // Verify fe owner status
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .getOwnerClientId()).isEqualTo(ownerClientId0[0]);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .getOwnerClientId()).isEqualTo(ownerClientId0[0]);
        // Verify share fe client status in the primary owner client
        assertThat(mTunerResourceManagerService.getClientProfile(ownerClientId0[0])
                .getShareFeClientIds())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        shareClientId0[0],
                        shareClientId1[0])));
        // Verify in use frontend list in all the primary owner and share owner clients
        assertThat(mTunerResourceManagerService
                .getClientProfile(ownerClientId0[0])
                .getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));
        assertThat(mTunerResourceManagerService
                .getClientProfile(shareClientId0[0])
                .getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));
        assertThat(mTunerResourceManagerService
                .getClientProfile(shareClientId1[0])
                .getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));

        /**** Remove Frontend Share Owner ****/

        // Unregister the second share fe client
        mTunerResourceManagerService.unregisterClientProfileInternal(shareClientId1[0]);

        // Validate the internal mapping
        assertThat(mTunerResourceManagerService.getClientProfile(ownerClientId0[0])
                .getShareFeClientIds())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        shareClientId0[0])));
        assertThat(mTunerResourceManagerService
                .getClientProfile(ownerClientId0[0])
                .getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));
        assertThat(mTunerResourceManagerService
                .getClientProfile(shareClientId0[0])
                .getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));

        /**** Request Shared Frontend with Higher Priority Client ****/

        // Predefined second frontend request
        request = tunerFrontendRequest(
                ownerClientId1[0] /*clientId*/,
                FrontendSettings.TYPE_DVBT);

        // Second request call
        assertThat(mTunerResourceManagerService
                .requestFrontendInternal(request, frontendHandle))
                .isTrue();

        // Validate granted resource and internal mapping
        assertThat(frontendHandle[0]).isEqualTo(infos[0].handle);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .getOwnerClientId()).isEqualTo(ownerClientId1[0]);
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .getOwnerClientId()).isEqualTo(ownerClientId1[0]);
        assertThat(mTunerResourceManagerService
                .getClientProfile(ownerClientId1[0])
                .getInUseFrontendHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        infos[0].handle,
                        infos[1].handle)));
        assertThat(mTunerResourceManagerService
                .getClientProfile(ownerClientId0[0])
                .getInUseFrontendHandles()
                .isEmpty())
                .isTrue();
        assertThat(mTunerResourceManagerService
                .getClientProfile(shareClientId0[0])
                .getInUseFrontendHandles()
                .isEmpty())
                .isTrue();
        assertThat(mTunerResourceManagerService
                .getClientProfile(ownerClientId0[0])
                .getShareFeClientIds()
                .isEmpty())
                .isTrue();
        assertThat(ownerListener0.isReclaimed()).isTrue();
        assertThat(shareListener0.isReclaimed()).isTrue();

        /**** Release Frontend Resource From Primary Owner ****/

        // Reshare the frontend
        mTunerResourceManagerService.shareFrontendInternal(
                shareClientId0[0]/*selfClientId*/,
                ownerClientId1[0]/*targetClientId*/);

        // Release the frontend resource from the primary owner
        mTunerResourceManagerService.releaseFrontendInternal(mTunerResourceManagerService
                .getFrontendResource(infos[0].handle), ownerClientId1[0]);

        // Validate the internal mapping
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isFalse();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isFalse();
        // Verify client status
        assertThat(mTunerResourceManagerService
                .getClientProfile(ownerClientId1[0])
                .getInUseFrontendHandles()
                .isEmpty())
                .isTrue();
        assertThat(mTunerResourceManagerService
                .getClientProfile(shareClientId0[0])
                .getInUseFrontendHandles()
                .isEmpty())
                .isTrue();
        assertThat(mTunerResourceManagerService
                .getClientProfile(ownerClientId1[0])
                .getShareFeClientIds()
                .isEmpty())
                .isTrue();

        /**** Unregister Primary Owner when the Share owner owns an Lnb ****/

        // Predefined Lnb request and handle array
        TunerLnbRequest requestLnb = new TunerLnbRequest();
        requestLnb.clientId = shareClientId0[0];
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
                shareClientId0[0]/*selfClientId*/,
                ownerClientId1[0]/*targetClientId*/);

        // Unregister the primary owner of the shared frontend
        mTunerResourceManagerService.unregisterClientProfileInternal(ownerClientId1[0]);

        // Validate the internal mapping
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[0].handle)
                .isInUse()).isFalse();
        assertThat(mTunerResourceManagerService.getFrontendResource(infos[1].handle)
                .isInUse()).isFalse();
        // Verify client status
        assertThat(mTunerResourceManagerService
                .getClientProfile(shareClientId0[0])
                .getInUseFrontendHandles()
                .isEmpty())
                .isTrue();
        assertThat(mTunerResourceManagerService
                .getClientProfile(shareClientId0[0])
                .getInUseLnbHandles())
                .isEqualTo(new HashSet<Integer>(Arrays.asList(
                        lnbHandles[0])));
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
}
