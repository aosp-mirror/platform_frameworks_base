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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Environment;
import android.telephony.TelephonyManager;
import android.testing.AndroidTestingRunner;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.LocalServices;
import com.android.server.SensorPrivacyService;
import com.android.server.pm.UserManagerInternal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

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

    private Context mContext;
    @Mock
    private AppOpsManager mMockedAppOpsManager;
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

        } finally {
            mockitoSession.finishMocking();
        }
    }

    private void initServiceWithPersistenceFile(File onDeviceFile,
            String persistenceFilePath) throws IOException {
        if (persistenceFilePath != null) {
            Files.copy(mContext.getAssets().open(persistenceFilePath),
                    onDeviceFile.toPath());
        }
        new SensorPrivacyService(mContext);
        onDeviceFile.delete();
    }
}
