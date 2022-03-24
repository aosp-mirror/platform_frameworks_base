/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.AppOpsManager.OPSTR_CAMERA;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;

import android.app.AppOpsManager;
import android.content.Context;
import android.hardware.lights.Light;
import android.hardware.lights.LightsManager;
import android.hardware.lights.LightsRequest;
import android.permission.PermissionManager;
import android.util.ArraySet;

import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

public class CameraPrivacyLightControllerTest {

    private MockitoSession mMockitoSession;

    @Mock
    private Context mContext;

    @Mock
    private LightsManager mLightsManager;

    @Mock
    private AppOpsManager mAppOpsManager;

    @Mock
    private LightsManager.LightsSession mLightsSession;

    private ArgumentCaptor<AppOpsManager.OnOpActiveChangedListener> mAppOpsListenerCaptor =
            ArgumentCaptor.forClass(AppOpsManager.OnOpActiveChangedListener.class);

    private ArgumentCaptor<LightsRequest> mLightsRequestCaptor =
            ArgumentCaptor.forClass(LightsRequest.class);

    private Set<String> mExemptedPackages = new ArraySet<>();
    private List<Light> mLights = new ArrayList<>();

    private int mNextLightId = 1;

    @Before
    public void setUp() {
        mMockitoSession = ExtendedMockito.mockitoSession()
                .initMocks(this)
                .strictness(Strictness.WARN)
                .spyStatic(PermissionManager.class)
                .startMocking();

        doReturn(mLightsManager).when(mContext).getSystemService(LightsManager.class);
        doReturn(mAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);

        doReturn(mLights).when(mLightsManager).getLights();
        doReturn(mLightsSession).when(mLightsManager).openSession(anyInt());

        doReturn(mExemptedPackages)
                .when(() -> PermissionManager.getIndicatorExemptedPackages(any()));
    }

    @After
    public void tearDown() {
        mExemptedPackages.clear();
        mLights.clear();

        mMockitoSession.finishMocking();
    }

    @Test
    public void testAppsOpsListenerNotRegisteredWithoutCameraLights() {
        mLights.add(getNextLight(false));
        new CameraPrivacyLightController(mContext);

        verify(mAppOpsManager, times(0)).startWatchingActive(any(), any(), any());
    }

    @Test
    public void testAppsOpsListenerRegisteredWithCameraLight() {
        mLights.add(getNextLight(true));

        new CameraPrivacyLightController(mContext);

        verify(mAppOpsManager, times(1)).startWatchingActive(any(), any(), any());
    }

    @Test
    public void testAllCameraLightsAreRequestedOnOpActive() {
        Random r = new Random(0);
        for (int i = 0; i < 30; i++) {
            mLights.add(getNextLight(r.nextBoolean()));
        }

        new CameraPrivacyLightController(mContext);

        // Verify no session has been opened at this point.
        verify(mLightsManager, times(0)).openSession(anyInt());

        // Set camera op as active.
        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());
        mAppOpsListenerCaptor.getValue().onOpActiveChanged(OPSTR_CAMERA, 10101, "pkg1", true);

        // Verify session has been opened exactly once
        verify(mLightsManager, times(1)).openSession(anyInt());

        verify(mLightsSession).requestLights(mLightsRequestCaptor.capture());
        assertEquals("requestLights() not invoked exactly once",
                1, mLightsRequestCaptor.getAllValues().size());

        List<Integer> expectedCameraLightIds = mLights.stream()
                .filter(l -> l.getType() == Light.LIGHT_TYPE_CAMERA)
                .map(l -> l.getId())
                .collect(Collectors.toList());
        List<Integer> lightsRequestLightIds = mLightsRequestCaptor.getValue().getLights();

        // We don't own lights framework, don't assume it will retain order
        lightsRequestLightIds.sort(Integer::compare);
        expectedCameraLightIds.sort(Integer::compare);

        assertEquals(expectedCameraLightIds, lightsRequestLightIds);
    }

    @Test
    public void testWillOnlyOpenOnceWhenTwoPackagesStartOp() {
        mLights.add(getNextLight(true));

        new CameraPrivacyLightController(mContext);

        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());

        AppOpsManager.OnOpActiveChangedListener listener = mAppOpsListenerCaptor.getValue();
        listener.onOpActiveChanged(OPSTR_CAMERA, 10101, "pkg1", true);
        verify(mLightsManager, times(1)).openSession(anyInt());
        listener.onOpActiveChanged(OPSTR_CAMERA, 10102, "pkg2", true);
        verify(mLightsManager, times(1)).openSession(anyInt());
    }

    @Test
    public void testWillCloseOnFinishOp() {
        mLights.add(getNextLight(true));

        new CameraPrivacyLightController(mContext);

        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());

        AppOpsManager.OnOpActiveChangedListener listener = mAppOpsListenerCaptor.getValue();
        listener.onOpActiveChanged(OPSTR_CAMERA, 10101, "pkg1", true);

        verify(mLightsSession, times(0)).close();
        listener.onOpActiveChanged(OPSTR_CAMERA, 10101, "pkg1", false);
        verify(mLightsSession, times(1)).close();
    }

    @Test
    public void testWillCloseOnFinishOpForAllPackages() {
        mLights.add(getNextLight(true));

        new CameraPrivacyLightController(mContext);

        int numUids = 100;
        List<Integer> uids = new ArrayList<>(numUids);
        for (int i = 0; i < numUids; i++) {
            uids.add(10001 + i);
        }

        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());

        AppOpsManager.OnOpActiveChangedListener listener = mAppOpsListenerCaptor.getValue();

        for (int i = 0; i < numUids; i++) {
            listener.onOpActiveChanged(OPSTR_CAMERA, uids.get(i), "pkg" + (int) uids.get(i), true);
        }

        // Change the order which their ops are finished
        Collections.shuffle(uids, new Random(0));

        for (int i = 0; i < numUids - 1; i++) {
            listener.onOpActiveChanged(OPSTR_CAMERA, uids.get(i), "pkg" + (int) uids.get(i), false);
        }

        verify(mLightsSession, times(0)).close();
        int lastUid = uids.get(numUids - 1);
        listener.onOpActiveChanged(OPSTR_CAMERA, lastUid, "pkg" + lastUid, false);
        verify(mLightsSession, times(1)).close();
    }

    @Test
    public void testWontOpenForExemptedPackage() {
        mLights.add(getNextLight(true));
        mExemptedPackages.add("pkg1");

        new CameraPrivacyLightController(mContext);

        verify(mAppOpsManager).startWatchingActive(any(), any(), mAppOpsListenerCaptor.capture());

        AppOpsManager.OnOpActiveChangedListener listener = mAppOpsListenerCaptor.getValue();
        listener.onOpActiveChanged(OPSTR_CAMERA, 10101, "pkg1", true);
        verify(mLightsManager, times(0)).openSession(anyInt());
    }

    private Light getNextLight(boolean cameraType) {
        Light light = ExtendedMockito.mock(Light.class);
        if (cameraType) {
            doReturn(Light.LIGHT_TYPE_CAMERA).when(light).getType();
        } else {
            doReturn(Light.LIGHT_TYPE_MICROPHONE).when(light).getType();
        }
        doReturn(mNextLightId++).when(light).getId();
        return light;
    }
}
