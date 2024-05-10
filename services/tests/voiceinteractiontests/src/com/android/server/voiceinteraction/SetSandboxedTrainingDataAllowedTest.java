/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.voiceinteraction;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.Manifest;
import android.app.ActivityManagerInternal;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.PermissionEnforcer;
import android.os.Process;
import android.os.test.FakePermissionEnforcer;
import android.platform.test.annotations.Presubmit;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.modules.utils.testing.ExtendedMockitoRule;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.permission.LegacyPermissionManagerInternal;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.quality.Strictness;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class SetSandboxedTrainingDataAllowedTest {

    @Captor private ArgumentCaptor<Integer> mOpIdCaptor, mUidCaptor, mOpModeCaptor;

    @Mock
    private AppOpsManager mAppOpsManager;

    @Mock
    private VoiceInteractionManagerServiceImpl mVoiceInteractionManagerServiceImpl;

    private FakePermissionEnforcer mPermissionEnforcer = new FakePermissionEnforcer();

    private Context mContext;

    private VoiceInteractionManagerService mVoiceInteractionManagerService;
    private VoiceInteractionManagerService.VoiceInteractionManagerServiceStub
            mVoiceInteractionManagerServiceStub;

    private ApplicationInfo mApplicationInfo = new ApplicationInfo();

    @Rule
    public final ExtendedMockitoRule mExtendedMockitoRule =
            new ExtendedMockitoRule.Builder(this)
                    .setStrictness(Strictness.WARN)
                    .mockStatic(LocalServices.class)
                    .mockStatic(PermissionEnforcer.class)
                    .build();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = spy(ApplicationProvider.getApplicationContext());

        doReturn(mPermissionEnforcer).when(() -> PermissionEnforcer.fromContext(any()));
        doReturn(mock(PermissionManagerServiceInternal.class)).when(
                () -> LocalServices.getService(PermissionManagerServiceInternal.class));
        doReturn(mock(ActivityManagerInternal.class)).when(
                () -> LocalServices.getService(ActivityManagerInternal.class));
        doReturn(mock(UserManagerInternal.class)).when(
                () -> LocalServices.getService(UserManagerInternal.class));
        doReturn(mock(ActivityTaskManagerInternal.class)).when(
                () -> LocalServices.getService(ActivityTaskManagerInternal.class));
        doReturn(mock(LegacyPermissionManagerInternal.class)).when(
                () -> LocalServices.getService(LegacyPermissionManagerInternal.class));
        doReturn(mock(RoleManager.class)).when(mContext).getSystemService(RoleManager.class);
        doReturn(mAppOpsManager).when(mContext).getSystemService(Context.APP_OPS_SERVICE);
        doReturn(mApplicationInfo).when(mVoiceInteractionManagerServiceImpl).getApplicationInfo();

        mVoiceInteractionManagerService = new VoiceInteractionManagerService(mContext);
        mVoiceInteractionManagerServiceStub =
                mVoiceInteractionManagerService.new VoiceInteractionManagerServiceStub();
        mVoiceInteractionManagerServiceStub.mImpl = mVoiceInteractionManagerServiceImpl;
        mPermissionEnforcer.grant(Manifest.permission.MANAGE_HOTWORD_DETECTION);
    }

    @Test
    public void setShouldReceiveSandboxedTrainingData_currentAndPreinstalledAssistant_setsOp() {
        // Set application info so current app is the current and preinstalled assistant.
        mApplicationInfo.uid = Process.myUid();
        mApplicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;

        mVoiceInteractionManagerServiceStub.setShouldReceiveSandboxedTrainingData(
                /* allowed= */ true);

        verify(mAppOpsManager).setUidMode(mOpIdCaptor.capture(), mUidCaptor.capture(),
                mOpModeCaptor.capture());
        assertThat(mOpIdCaptor.getValue()).isEqualTo(
                AppOpsManager.OP_RECEIVE_SANDBOXED_DETECTION_TRAINING_DATA);
        assertThat(mOpModeCaptor.getValue()).isEqualTo(AppOpsManager.MODE_ALLOWED);
        assertThat(mUidCaptor.getValue()).isEqualTo(Process.myUid());
    }

    @Test
    public void setShouldReceiveSandboxedTrainingData_missingPermission_doesNotSetOp() {
        // Set application info so current app is the current and preinstalled assistant.
        mApplicationInfo.uid = Process.myUid();
        mApplicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;

        // Simulate missing MANAGE_HOTWORD_DETECTION permission.
        mPermissionEnforcer.revoke(Manifest.permission.MANAGE_HOTWORD_DETECTION);

        assertThrows(SecurityException.class,
                () -> mVoiceInteractionManagerServiceStub.setShouldReceiveSandboxedTrainingData(
                        /* allowed= */ true));

        verify(mAppOpsManager, never()).setUidMode(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void setShouldReceiveSandboxedTrainingData_notPreinstalledAssistant_doesNotSetOp() {
        // Set application info so current app is not preinstalled assistant.
        mApplicationInfo.uid = Process.myUid();
        mApplicationInfo.flags = ApplicationInfo.FLAG_INSTALLED; // Does not contain FLAG_SYSTEM.

        assertThrows(SecurityException.class,
                () -> mVoiceInteractionManagerServiceStub.setShouldReceiveSandboxedTrainingData(
                                /* allowed= */ true));

        verify(mAppOpsManager, never()).setUidMode(anyInt(), anyInt(), anyInt());
    }

    @Test
    public void setShouldReceiveSandboxedTrainingData_notCurrentAssistant_doesNotSetOp() {
        // Set application info so current app is not current assistant.
        mApplicationInfo.uid = Process.SHELL_UID; // Set current assistant uid to shell UID.
        mApplicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;

        assertThrows(SecurityException.class,
                () -> mVoiceInteractionManagerServiceStub.setShouldReceiveSandboxedTrainingData(
                                /* allowed= */ true));

        verify(mAppOpsManager, never()).setUidMode(anyInt(), anyInt(), anyInt());
    }
}
