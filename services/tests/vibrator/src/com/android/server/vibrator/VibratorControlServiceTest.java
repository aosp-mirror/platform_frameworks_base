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

package com.android.server.vibrator;

import static android.os.VibrationAttributes.USAGE_ALARM;
import static android.os.VibrationAttributes.USAGE_COMMUNICATION_REQUEST;
import static android.os.VibrationAttributes.USAGE_NOTIFICATION;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.frameworks.vibrator.ScaleParam;
import android.frameworks.vibrator.VibrationParam;
import android.os.RemoteException;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

public class VibratorControlServiceTest {

    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    @Mock
    private VibrationScaler mMockVibrationScaler;
    @Captor
    private ArgumentCaptor<SparseArray<Float>> mVibrationScalesCaptor;

    private VibratorControlService mVibratorControlService;
    private final Object mLock = new Object();

    @Before
    public void setUp() throws Exception {
        mVibratorControlService = new VibratorControlService(new VibratorControllerHolder(),
                mMockVibrationScaler, mLock);
    }

    @Test
    public void testRegisterVibratorController() throws RemoteException {
        FakeVibratorController fakeController = new FakeVibratorController();
        mVibratorControlService.registerVibratorController(fakeController);

        assertThat(fakeController.isLinkedToDeath).isTrue();
    }

    @Test
    public void testUnregisterVibratorController_providingTheRegisteredController_performsRequest()
            throws RemoteException {
        FakeVibratorController fakeController = new FakeVibratorController();
        mVibratorControlService.registerVibratorController(fakeController);
        mVibratorControlService.unregisterVibratorController(fakeController);

        verify(mMockVibrationScaler).updateAdaptiveHapticsScales(null);
        assertThat(fakeController.isLinkedToDeath).isFalse();
    }

    @Test
    public void testUnregisterVibratorController_providingAnInvalidController_ignoresRequest()
            throws RemoteException {
        FakeVibratorController fakeController1 = new FakeVibratorController();
        FakeVibratorController fakeController2 = new FakeVibratorController();
        mVibratorControlService.registerVibratorController(fakeController1);
        mVibratorControlService.unregisterVibratorController(fakeController2);

        verifyZeroInteractions(mMockVibrationScaler);
        assertThat(fakeController1.isLinkedToDeath).isTrue();
    }

    @Test
    public void testSetVibrationParams_cachesAdaptiveHapticsScalesCorrectly()
            throws RemoteException {
        FakeVibratorController fakeController = new FakeVibratorController();
        mVibratorControlService.registerVibratorController(fakeController);
        SparseArray<Float> vibrationScales = new SparseArray<>();
        vibrationScales.put(ScaleParam.TYPE_ALARM, 0.7f);
        vibrationScales.put(ScaleParam.TYPE_NOTIFICATION, 0.4f);

        mVibratorControlService.setVibrationParams(generateVibrationParams(vibrationScales),
                fakeController);

        verify(mMockVibrationScaler).updateAdaptiveHapticsScales(mVibrationScalesCaptor.capture());
        SparseArray<Float> cachedVibrationScales = mVibrationScalesCaptor.getValue();
        assertThat(cachedVibrationScales.size()).isEqualTo(3);
        assertThat(cachedVibrationScales.keyAt(0)).isEqualTo(USAGE_ALARM);
        assertThat(cachedVibrationScales.valueAt(0)).isEqualTo(0.7f);
        assertThat(cachedVibrationScales.keyAt(1)).isEqualTo(USAGE_NOTIFICATION);
        assertThat(cachedVibrationScales.valueAt(1)).isEqualTo(0.4f);
        // Setting ScaleParam.TYPE_NOTIFICATION will update vibration scaling for both
        // notification and communication request usages.
        assertThat(cachedVibrationScales.keyAt(2)).isEqualTo(USAGE_COMMUNICATION_REQUEST);
        assertThat(cachedVibrationScales.valueAt(2)).isEqualTo(0.4f);
    }

    @Test
    public void testSetVibrationParams_withUnregisteredController_ignoresRequest()
            throws RemoteException {
        FakeVibratorController fakeController = new FakeVibratorController();

        SparseArray<Float> vibrationScales = new SparseArray<>();
        vibrationScales.put(ScaleParam.TYPE_ALARM, 0.7f);
        vibrationScales.put(ScaleParam.TYPE_NOTIFICATION, 0.4f);

        mVibratorControlService.setVibrationParams(generateVibrationParams(vibrationScales),
                fakeController);

        verifyZeroInteractions(mMockVibrationScaler);
    }

    @Test
    public void testClearVibrationParams_clearsCachedAdaptiveHapticsScales()
            throws RemoteException {
        FakeVibratorController fakeController = new FakeVibratorController();
        mVibratorControlService.registerVibratorController(fakeController);
        mVibratorControlService.clearVibrationParams(ScaleParam.TYPE_ALARM, fakeController);

        verify(mMockVibrationScaler).updateAdaptiveHapticsScales(null);
    }

    @Test
    public void testClearVibrationParams_withUnregisteredController_ignoresRequest()
            throws RemoteException {
        FakeVibratorController fakeController = new FakeVibratorController();

        mVibratorControlService.clearVibrationParams(ScaleParam.TYPE_ALARM, fakeController);

        verifyZeroInteractions(mMockVibrationScaler);
    }

    private VibrationParam[] generateVibrationParams(SparseArray<Float> vibrationScales) {
        List<VibrationParam> vibrationParamList = new ArrayList<>();
        for (int i = 0; i < vibrationScales.size(); i++) {
            int type = vibrationScales.keyAt(i);
            float scale = vibrationScales.valueAt(i);

            vibrationParamList.add(generateVibrationParam(type, scale));
        }

        return vibrationParamList.toArray(new VibrationParam[0]);
    }

    private VibrationParam generateVibrationParam(int type, float scale) {
        ScaleParam scaleParam = new ScaleParam();
        scaleParam.typesMask = type;
        scaleParam.scale = scale;
        VibrationParam vibrationParam = new VibrationParam();
        vibrationParam.setScale(scaleParam);

        return vibrationParam;
    }
}
