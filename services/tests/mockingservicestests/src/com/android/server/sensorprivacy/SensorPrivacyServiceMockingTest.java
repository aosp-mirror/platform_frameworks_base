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

import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;
import static android.hardware.SensorPrivacyManager.Sensors.MICROPHONE;
import static android.hardware.SensorPrivacyManager.TOGGLE_TYPE_HARDWARE;
import static android.hardware.SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;

import android.content.Context;
import android.hardware.SensorPrivacyManager;
import android.os.Environment;
import android.os.Handler;
import android.testing.AndroidTestingRunner;

import androidx.test.platform.app.InstrumentationRegistry;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

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
    public static final String PERSISTENCE_FILE7 =
            String.format(PERSISTENCE_FILE_PATHS_TEMPLATE, 7);
    public static final String PERSISTENCE_FILE8 =
            String.format(PERSISTENCE_FILE_PATHS_TEMPLATE, 8);

    public static final String PERSISTENCE_FILE_MIC_MUTE_CAM_MUTE =
            "SensorPrivacyServiceMockingTest/persisted_file_micMute_camMute.xml";
    public static final String PERSISTENCE_FILE_MIC_MUTE_CAM_UNMUTE =
            "SensorPrivacyServiceMockingTest/persisted_file_micMute_camUnmute.xml";
    public static final String PERSISTENCE_FILE_MIC_UNMUTE_CAM_MUTE =
            "SensorPrivacyServiceMockingTest/persisted_file_micUnmute_camMute.xml";
    public static final String PERSISTENCE_FILE_MIC_UNMUTE_CAM_UNMUTE =
            "SensorPrivacyServiceMockingTest/persisted_file_micUnmute_camUnmute.xml";

    Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    String mDataDir = mContext.getApplicationInfo().dataDir;

    @Before
    public void setUp() {
        new File(mDataDir, "sensor_privacy.xml").delete();
        new File(mDataDir, "sensor_privacy_impl.xml").delete();
    }

    @Test
    public void testMigration1() throws IOException {
        PersistedState ps = migrateFromFile(PERSISTENCE_FILE1);

        assertTrue(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE).isEnabled());
        assertTrue(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, CAMERA).isEnabled());

        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, CAMERA));

        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, CAMERA));
    }

    @Test
    public void testMigration2() throws IOException {
        PersistedState ps = migrateFromFile(PERSISTENCE_FILE2);

        assertTrue(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE).isEnabled());
        assertTrue(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, CAMERA).isEnabled());

        assertTrue(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, MICROPHONE).isEnabled());
        assertFalse(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, CAMERA).isEnabled());

        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 11, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 11, CAMERA));

        assertTrue(ps.getState(TOGGLE_TYPE_SOFTWARE, 12, MICROPHONE).isEnabled());
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 12, CAMERA));

        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 11, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 11, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 12, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 12, CAMERA));
    }

    @Test
    public void testMigration3() throws IOException {
        PersistedState ps = migrateFromFile(PERSISTENCE_FILE3);

        assertFalse(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE).isEnabled());
        assertFalse(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, CAMERA).isEnabled());

        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, CAMERA));

        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, CAMERA));
    }

    @Test
    public void testMigration4() throws IOException {
        PersistedState ps = migrateFromFile(PERSISTENCE_FILE4);

        assertTrue(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE).isEnabled());
        assertFalse(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, CAMERA).isEnabled());

        assertFalse(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, MICROPHONE).isEnabled());
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, CAMERA));

        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, CAMERA));
    }

    @Test
    public void testMigration5() throws IOException {
        PersistedState ps = migrateFromFile(PERSISTENCE_FILE5);

        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE));
        assertFalse(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, CAMERA).isEnabled());

        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, MICROPHONE));
        assertFalse(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, CAMERA).isEnabled());

        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, CAMERA));
    }

    @Test
    public void testMigration6() throws IOException {
        PersistedState ps = migrateFromFile(PERSISTENCE_FILE6);

        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, CAMERA));

        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, CAMERA));

        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, CAMERA));
    }

    private PersistedState migrateFromFile(String fileName) throws IOException {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .spyStatic(LocalServices.class)
                .spyStatic(Environment.class)
                .startMocking();
        try {
            doReturn(new File(mDataDir)).when(() -> Environment.getDataSystemDirectory());

            UserManagerInternal umi = mock(UserManagerInternal.class);
            doReturn(umi).when(() -> LocalServices.getService(UserManagerInternal.class));
            doReturn(new int[] {0}).when(umi).getUserIds();

            Files.copy(
                    mContext.getAssets().open(fileName),
                    new File(mDataDir, "sensor_privacy.xml").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);

            return PersistedState.fromFile("sensor_privacy_impl.xml");
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void testPersistence1Version2() throws IOException {
        PersistedState ps = getPersistedStateV2(PERSISTENCE_FILE7);

        assertEquals(1, ps.getState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE).getState());
        assertEquals(123L, ps.getState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE).getLastChange());
        assertEquals(2, ps.getState(TOGGLE_TYPE_SOFTWARE, 0, CAMERA).getState());
        assertEquals(123L, ps.getState(TOGGLE_TYPE_SOFTWARE, 0, CAMERA).getLastChange());

        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 0, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, CAMERA));
    }

    @Test
    public void testPersistence2Version2() throws IOException {
        PersistedState ps = getPersistedStateV2(PERSISTENCE_FILE8);

        assertEquals(1, ps.getState(TOGGLE_TYPE_HARDWARE, 0, MICROPHONE).getState());
        assertEquals(1234L, ps.getState(TOGGLE_TYPE_HARDWARE, 0, MICROPHONE).getLastChange());
        assertEquals(2, ps.getState(TOGGLE_TYPE_HARDWARE, 0, CAMERA).getState());
        assertEquals(1234L, ps.getState(TOGGLE_TYPE_HARDWARE, 0, CAMERA).getLastChange());

        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 0, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_SOFTWARE, 10, CAMERA));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, MICROPHONE));
        assertNull(ps.getState(TOGGLE_TYPE_HARDWARE, 10, CAMERA));
    }

    private PersistedState getPersistedStateV2(String version2FilePath) throws IOException {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .spyStatic(LocalServices.class)
                .spyStatic(Environment.class)
                .startMocking();
        try {
            doReturn(new File(mDataDir)).when(() -> Environment.getDataSystemDirectory());
            Files.copy(
                    mContext.getAssets().open(version2FilePath),
                    new File(mDataDir, "sensor_privacy_impl.xml").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);

            return PersistedState.fromFile("sensor_privacy_impl.xml");
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void testGetDefaultState() {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .spyStatic(PersistedState.class)
                .startMocking();
        try {
            PersistedState persistedState = mock(PersistedState.class);
            doReturn(persistedState).when(() -> PersistedState.fromFile(any()));
            doReturn(null).when(persistedState).getState(anyInt(), anyInt(), anyInt());

            SensorPrivacyStateController sensorPrivacyStateController =
                    getSensorPrivacyStateControllerImpl();

            SensorState micState = sensorPrivacyStateController.getState(TOGGLE_TYPE_SOFTWARE, 0,
                    MICROPHONE);
            SensorState camState = sensorPrivacyStateController.getState(TOGGLE_TYPE_SOFTWARE, 0,
                    CAMERA);

            assertEquals(SensorPrivacyManager.StateTypes.DISABLED, micState.getState());
            assertEquals(SensorPrivacyManager.StateTypes.DISABLED, camState.getState());
            verify(persistedState, times(1)).getState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE);
            verify(persistedState, times(1)).getState(TOGGLE_TYPE_SOFTWARE, 0, CAMERA);
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void testGetSetState() {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .spyStatic(PersistedState.class)
                .startMocking();
        try {
            PersistedState persistedState = mock(PersistedState.class);
            SensorState sensorState = mock(SensorState.class);
            doReturn(persistedState).when(() -> PersistedState.fromFile(any()));
            doReturn(sensorState).when(persistedState).getState(TOGGLE_TYPE_SOFTWARE, 0,
                    MICROPHONE);
            doReturn(SensorPrivacyManager.StateTypes.ENABLED).when(sensorState).getState();
            doReturn(0L).when(sensorState).getLastChange();

            SensorPrivacyStateController sensorPrivacyStateController =
                    getSensorPrivacyStateControllerImpl();

            SensorState micState = sensorPrivacyStateController.getState(TOGGLE_TYPE_SOFTWARE, 0,
                    MICROPHONE);

            assertEquals(SensorPrivacyManager.StateTypes.ENABLED, micState.getState());
            assertEquals(0L, micState.getLastChange());
        } finally {
            mockitoSession.finishMocking();
        }
    }

    @Test
    public void testSetState() {
        MockitoSession mockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .spyStatic(PersistedState.class)
                .startMocking();
        try {
            PersistedState persistedState = mock(PersistedState.class);
            doReturn(persistedState).when(() -> PersistedState.fromFile(any()));

            SensorPrivacyStateController sensorPrivacyStateController =
                    getSensorPrivacyStateControllerImpl();

            sensorPrivacyStateController.setState(TOGGLE_TYPE_SOFTWARE, 0, MICROPHONE, true,
                    mock(Handler.class), changed -> {});

            ArgumentCaptor<SensorState> captor = ArgumentCaptor.forClass(SensorState.class);

            verify(persistedState, times(1)).setState(eq(TOGGLE_TYPE_SOFTWARE), eq(0),
                    eq(MICROPHONE), captor.capture());
            assertEquals(SensorPrivacyManager.StateTypes.ENABLED, captor.getValue().getState());
        } finally {
            mockitoSession.finishMocking();
        }
    }

    private SensorPrivacyStateController getSensorPrivacyStateControllerImpl() {
        SensorPrivacyStateControllerImpl.getInstance().resetForTestingImpl();
        return SensorPrivacyStateControllerImpl.getInstance();
    }
}
