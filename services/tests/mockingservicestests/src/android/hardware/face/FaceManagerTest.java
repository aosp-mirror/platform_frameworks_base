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

package android.hardware.face;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Looper;
import android.os.RemoteException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class FaceManagerTest {
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    Context mContext;
    @Mock
    IFaceService mService;

    @Captor
    ArgumentCaptor<IFaceAuthenticatorsRegisteredCallback> mCaptor;

    List<FaceSensorPropertiesInternal> mProps;
    FaceManager mFaceManager;

    @Before
    public void setUp() throws Exception {
        when(mContext.getMainLooper()).thenReturn(Looper.getMainLooper());
        mFaceManager = new FaceManager(mContext, mService);
        mProps = new ArrayList<>();
        mProps.add(new FaceSensorPropertiesInternal(
                0 /* id */,
                FaceSensorProperties.STRENGTH_STRONG,
                1 /* maxTemplatesAllowed */,
                new ArrayList<>() /* conponentInfo */,
                FaceSensorProperties.TYPE_UNKNOWN,
                true /* supportsFaceDetection */,
                true /* supportsSelfIllumination */,
                false /* resetLockoutRequiresChallenge */));
    }

    @Test
    public void getSensorPropertiesInternal_noBinderCalls() throws RemoteException {
        verify(mService).addAuthenticatorsRegisteredCallback(mCaptor.capture());

        mCaptor.getValue().onAllAuthenticatorsRegistered(mProps);
        List<FaceSensorPropertiesInternal> actual = mFaceManager.getSensorPropertiesInternal();

        assertThat(actual).isEqualTo(mProps);
        verify(mService, never()).getSensorPropertiesInternal(any());
    }
}
