/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.sensorprivacy;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.app.AppOpsManagerInternal;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Environment;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.LocalServices;
import com.android.server.SensorPrivacyService;
import com.android.server.SystemService;
import com.android.server.pm.UserManagerInternal;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;

@RunWith(AndroidTestingRunner.class)
public class SensorPrivacyServiceMockingTest {

    private static final String PERSISTENCE_FILE_PATHS_TEMPLATE =
            "SensorPrivacyServiceMockingTest/persisted_file%d.xml";
    public static final String PERSISTENCE_FILE1 =
            String.format(PERSISTENCE_FILE_PATHS_TEMPLATE, 1);
    public static final String PERSISTENCE_FILE2 =
            String.format(PERSISTENCE_FILE_PATHS_TEMPLATE, 2);
    public static final String PERSISTENCE_FILE3 =
            String.format(PERSISTENCE_FILE_PATHS_TEMPLATE, 3);
    public static final String PERSISTENCE_FILE4 =
            String.format(PERSISTENCE_FILE_PATHS_TEMPLATE, 4);
    public static final String PERSISTENCE_FILE5 =
            String.format(PERSISTENCE_FILE_PATHS_TEMPLATE, 5);
    public static final String PERSISTENCE_FILE6 =
            String.format(PERSISTENCE_FILE_PATHS_TEMPLATE, 6);

    public static final String PERSISTENCE_FILE_MIC_MUTE_CAM_MUTE =
            "SensorPrivacyServiceMockingTest/persisted_file_micMute_camMute.xml";
    public static final String PERSISTENCE_FILE_MIC_MUTE_CAM_UNMUTE =
            "SensorPrivacyServiceMockingTest/persisted_file_micMute_camUnmute.xml";
    public static final String PERSISTENCE_FILE_MIC_UNMUTE_CAM_MUTE =
            "SensorPrivacyServiceMockingTest/persisted_file_micUnmute_camMute.xml";
    public static final String PERSISTENCE_FILE_MIC_UNMUTE_CAM_UNMUTE =
            "SensorPrivacyServiceMockingTest/persisted_file_micUnmute_camUnmute.xml";

    private Context mContext;
    @Mock
    private AppOpsManager mMockedAppOpsManager;
    @Mock
    private AppOpsManagerInternal mMockedAppOpsManagerInternal;
    @Mock
    private UserManagerInternal mMockedUserManagerInternal;
    @Mock
    private ActivityManager mMockedActivityManager;
    @Mock
    private ActivityTaskManager mMockedActivityTaskManager;
    @Mock
    private TelephonyManager mMockedTelephonyManager;

    @Test
    public void testServiceInit() throws IOException {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .spyStatic(LocalServices.class)
                .spyStatic(Environment.class)
                .startMocking();

        try {
            mContext = InstrumentationRegistry.getInstrumentation().getContext();
            spyOn(mContext);

            doReturn(mMockedAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);
            doReturn(mMockedUserManagerInternal)
                    .when(() -> LocalServices.getService(UserManagerInternal.class));
            doReturn(mMockedActivityManager).when(mContext).getSystemService(ActivityManager.class);
            doReturn(mMockedActivityTaskManager)
                    .when(mContext).getSystemService(ActivityTaskManager.class);
            doReturn(mMockedTelephonyManager).when(mContext).getSystemService(
                    TelephonyManager.class);

            String dataDir = mContext.getApplicationInfo().dataDir;
            doReturn(new File(dataDir)).when(() -> Environment.getDataSystemDirectory());

            File onDeviceFile = new File(dataDir, "sensor_privacy.xml");
            onDeviceFile.delete();

            // Try all files with one known user
            doReturn(new int[]{0}).when(mMockedUserManagerInternal).getUserIds();
            doReturn(ExtendedMockito.mock(UserInfo.class)).when(mMockedUserManagerInternal)
                    .getUserInfo(0);
            initServiceWithPersistenceFile(onDeviceFile, null);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE1);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE2);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE3);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE4);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE5);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE6);

            // Try all files with two known users
            doReturn(new int[]{0, 10}).when(mMockedUserManagerInternal).getUserIds();
            doReturn(ExtendedMockito.mock(UserInfo.class)).when(mMockedUserManagerInternal)
                    .getUserInfo(0);
            doReturn(ExtendedMockito.mock(UserInfo.class)).when(mMockedUserManagerInternal)
                    .getUserInfo(10);
            initServiceWithPersistenceFile(onDeviceFile, null);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE1);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE2);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE3);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE4);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE5);
            initServiceWithPersistenceFile(onDeviceFile, PERSISTENCE_FILE6);

        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void testServiceInit_AppOpsRestricted_micMute_camMute() throws IOException {
        testServiceInit_AppOpsRestricted(PERSISTENCE_FILE_MIC_MUTE_CAM_MUTE, true, true);
    }

    @Test
    public void testServiceInit_AppOpsRestricted_micMute_camUnmute() throws IOException {
        testServiceInit_AppOpsRestricted(PERSISTENCE_FILE_MIC_MUTE_CAM_UNMUTE, true, false);
    }

    @Test
    public void testServiceInit_AppOpsRestricted_micUnmute_camMute() throws IOException {
        testServiceInit_AppOpsRestricted(PERSISTENCE_FILE_MIC_UNMUTE_CAM_MUTE, false, true);
    }

    @Test
    public void testServiceInit_AppOpsRestricted_micUnmute_camUnmute() throws IOException {
        testServiceInit_AppOpsRestricted(PERSISTENCE_FILE_MIC_UNMUTE_CAM_UNMUTE, false, false);
    }

    private void testServiceInit_AppOpsRestricted(String persistenceFileMicMuteCamMute,
            boolean expectedMicState, boolean expectedCamState)
            throws IOException {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .spyStatic(LocalServices.class)
                .spyStatic(Environment.class)
                .startMocking();

        try {
            mContext = InstrumentationRegistry.getInstrumentation().getContext();
            spyOn(mContext);

            doReturn(mMockedAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);
            doReturn(mMockedAppOpsManagerInternal)
                    .when(() -> LocalServices.getService(AppOpsManagerInternal.class));
            doReturn(mMockedUserManagerInternal)
                    .when(() -> LocalServices.getService(UserManagerInternal.class));
            doReturn(mMockedActivityManager).when(mContext).getSystemService(ActivityManager.class);
            doReturn(mMockedActivityTaskManager)
                    .when(mContext).getSystemService(ActivityTaskManager.class);
            doReturn(mMockedTelephonyManager).when(mContext).getSystemService(
                    TelephonyManager.class);

            String dataDir = mContext.getApplicationInfo().dataDir;
            doReturn(new File(dataDir)).when(() -> Environment.getDataSystemDirectory());

            File onDeviceFile = new File(dataDir, "sensor_privacy.xml");
            onDeviceFile.delete();

            doReturn(new int[]{0}).when(mMockedUserManagerInternal).getUserIds();
            doReturn(ExtendedMockito.mock(UserInfo.class)).when(mMockedUserManagerInternal)
                    .getUserInfo(0);

            CompletableFuture<Boolean> micState = new CompletableFuture<>();
            CompletableFuture<Boolean> camState = new CompletableFuture<>();
            doAnswer(invocation -> {
                int code = invocation.getArgument(0);
                boolean restricted = invocation.getArgument(1);
                if (code == AppOpsManager.OP_RECORD_AUDIO) {
                    micState.complete(restricted);
                } else if (code == AppOpsManager.OP_CAMERA) {
                    camState.complete(restricted);
                }
                return null;
            }).when(mMockedAppOpsManagerInternal).setGlobalRestriction(anyInt(), anyBoolean(),
                    any());

            initServiceWithPersistenceFile(onDeviceFile, persistenceFileMicMuteCamMute, 0);

            Assert.assertTrue(micState.join() == expectedMicState);
            Assert.assertTrue(camState.join() == expectedCamState);

        } finally {
            mockitoSession.finishMocking();
        }
    }

    private void initServiceWithPersistenceFile(File onDeviceFile,
            String persistenceFilePath) throws IOException {
        initServiceWithPersistenceFile(onDeviceFile, persistenceFilePath, -1);
    }

    private void initServiceWithPersistenceFile(File onDeviceFile,
            String persistenceFilePath, int startingUserId) throws IOException {
        if (persistenceFilePath != null) {
            Files.copy(mContext.getAssets().open(persistenceFilePath),
                    onDeviceFile.toPath());
        }
        SensorPrivacyService service = new SensorPrivacyService(mContext);
        if (startingUserId != -1) {
            SystemService.TargetUser mockedTargetUser =
                    ExtendedMockito.mock(SystemService.TargetUser.class);
            doReturn(startingUserId).when(mockedTargetUser).getUserIdentifier();
            service.onUserStarting(mockedTargetUser);
        }
        onDeviceFile.delete();
    }
}
