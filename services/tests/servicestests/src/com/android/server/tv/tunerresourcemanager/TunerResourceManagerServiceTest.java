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

import android.content.Context;
import android.content.ContextWrapper;
import android.media.tv.tuner.frontend.FrontendSettings;
import android.media.tv.tunerresourcemanager.TunerFrontendInfo;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Tests for {@link TunerResourceManagerService} class.
 */
@SmallTest
@RunWith(JUnit4.class)
public class TunerResourceManagerServiceTest {
    private static final String TAG = "TunerResourceManagerServiceTest";
    private Context mContextSpy;
    private TunerResourceManagerService mTunerResourceManagerService;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getTargetContext()));
        mTunerResourceManagerService = new TunerResourceManagerService(mContextSpy) {};
    }

    @Test
    public void setFrontendListTest_addFrontendResources_noExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        SparseArray<FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        assertThat(resources.size()).isEqualTo(infos.length);
        for (int id = 0; id < infos.length; id++) {
            FrontendResource fe = resources.get(infos[id].getId());
            assertThat(fe.getId()).isEqualTo(infos[id].getId());
            assertThat(fe.getType()).isEqualTo(infos[id].getFrontendType());
            assertThat(fe.getExclusiveGroupId()).isEqualTo(infos[id].getExclusiveGroupId());
            assertThat(fe.getExclusiveGroupMemberFeIds().size()).isEqualTo(0);
        }
    }

    @Test
    public void setFrontendListTest_addFrontendResources_underTheSameExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[4];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[2] =
                new TunerFrontendInfo(2 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[3] =
                new TunerFrontendInfo(3 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos);

        SparseArray<FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        assertThat(resources.size()).isEqualTo(infos.length);
        for (int id = 0; id < infos.length; id++) {
            FrontendResource fe = resources.get(infos[id].getId());
            assertThat(fe.getId()).isEqualTo(infos[id].getId());
            assertThat(fe.getType()).isEqualTo(infos[id].getFrontendType());
            assertThat(fe.getExclusiveGroupId()).isEqualTo(infos[id].getExclusiveGroupId());
        }

        assertThat(resources.get(0).getExclusiveGroupMemberFeIds())
                .isEqualTo(new ArrayList<Integer>());
        assertThat(resources.get(1).getExclusiveGroupMemberFeIds())
                .isEqualTo(new ArrayList<Integer>(Arrays.asList(2, 3)));
        assertThat(resources.get(2).getExclusiveGroupMemberFeIds())
                .isEqualTo(new ArrayList<Integer>(Arrays.asList(1, 3)));
        assertThat(resources.get(3).getExclusiveGroupMemberFeIds())
                .isEqualTo(new ArrayList<Integer>(Arrays.asList(1, 2)));
    }

    @Test
    public void setFrontendListTest_updateExistingFrontendResources() {
        // Init frontend resources.
        TunerFrontendInfo[] infos = new TunerFrontendInfo[2];
        infos[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);

        mTunerResourceManagerService.setFrontendInfoListInternal(infos);
        SparseArray<FrontendResource> resources0 =
                mTunerResourceManagerService.getFrontendResources();

        mTunerResourceManagerService.setFrontendInfoListInternal(infos);
        SparseArray<FrontendResource> resources1 =
                mTunerResourceManagerService.getFrontendResources();

        assertThat(resources0).isEqualTo(resources1);
    }

    @Test
    public void setFrontendListTest_removeFrontendResources_noExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos0 = new TunerFrontendInfo[3];
        infos0[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos0[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos0[2] =
                new TunerFrontendInfo(2 /*id*/, FrontendSettings.TYPE_DVBT, 2 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos0);

        TunerFrontendInfo[] infos1 = new TunerFrontendInfo[1];
        infos1[0] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos1);

        SparseArray<FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        assertThat(resources.size()).isEqualTo(infos1.length);
        for (int id = 0; id < infos1.length; id++) {
            FrontendResource fe = resources.get(infos1[id].getId());
            assertThat(fe.getId()).isEqualTo(infos1[id].getId());
            assertThat(fe.getType()).isEqualTo(infos1[id].getFrontendType());
            assertThat(fe.getExclusiveGroupId()).isEqualTo(infos1[id].getExclusiveGroupId());
            assertThat(fe.getExclusiveGroupMemberFeIds().size()).isEqualTo(0);
        }
    }

    @Test
    public void setFrontendListTest_removeFrontendResources_underTheSameExclusiveGroupId() {
        // Init frontend resources.
        TunerFrontendInfo[] infos0 = new TunerFrontendInfo[3];
        infos0[0] =
                new TunerFrontendInfo(0 /*id*/, FrontendSettings.TYPE_DVBT, 0 /*exclusiveGroupId*/);
        infos0[1] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        infos0[2] =
                new TunerFrontendInfo(2 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos0);

        TunerFrontendInfo[] infos1 = new TunerFrontendInfo[1];
        infos1[0] =
                new TunerFrontendInfo(1 /*id*/, FrontendSettings.TYPE_DVBT, 1 /*exclusiveGroupId*/);
        mTunerResourceManagerService.setFrontendInfoListInternal(infos1);

        SparseArray<FrontendResource> resources =
                mTunerResourceManagerService.getFrontendResources();
        assertThat(resources.size()).isEqualTo(infos1.length);
        for (int id = 0; id < infos1.length; id++) {
            FrontendResource fe = resources.get(infos1[id].getId());
            assertThat(fe.getId()).isEqualTo(infos1[id].getId());
            assertThat(fe.getType()).isEqualTo(infos1[id].getFrontendType());
            assertThat(fe.getExclusiveGroupId()).isEqualTo(infos1[id].getExclusiveGroupId());
            assertThat(fe.getExclusiveGroupMemberFeIds().size()).isEqualTo(0);
        }
    }
}
