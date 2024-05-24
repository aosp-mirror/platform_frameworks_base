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

package com.android.server.biometrics.sensors.face.aidl;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.face.ISession;
import android.hardware.face.Face;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.BiometricUtils;
import com.android.server.biometrics.sensors.ClientMonitorCallback;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Presubmit
@SmallTest
public class FaceInternalCleanupClientTest {
    private static final String TAG = "FaceInternalCleanupClientTest";
    private static final int USER_ID = 2;
    private static final int SENSOR_ID = 4;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private AidlSession mAidlSession;
    @Mock
    private ISession mSession;
    @Mock
    private ClientMonitorCallback mCallback;
    @Mock
    Context mContext;
    @Mock
    private BiometricLogger mBiometricLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private BiometricUtils<Face> mBiometricUtils;
    @Mock
    private Map<Integer, Long> mAuthenticatorIds;

    private final List<Face> mEnrolledList = new ArrayList<>();
    private final int mBiometricId = 1;
    private final Face mFace = new Face("face", mBiometricId, 1 /* deviceId */);
    private FaceInternalCleanupClient mClient;
    private List<Integer> mAddedIds;

    @Before
    public void setUp() throws RemoteException {
        when(mAidlSession.getSession()).thenReturn(mSession);

        mEnrolledList.add(mFace);
        mAddedIds = new ArrayList<>();
        mClient = new FaceInternalCleanupClient(mContext, () -> mAidlSession, USER_ID, TAG,
                SENSOR_ID, mBiometricLogger, mBiometricContext, mBiometricUtils,
                mAuthenticatorIds) {
            @Override
            protected void onAddUnknownTemplate(int userId,
                    @NonNull BiometricAuthenticator.Identifier identifier) {
                mAddedIds.add(identifier.getBiometricId());
            }
        };
    }

    @Test
    public void removesUnknownTemplate() throws Exception {
        final List<Face> templates = List.of(
                new Face("one", 1, 1),
                new Face("two", 2, 1)
        );
        mClient.start(mCallback);
        for (int i = templates.size() - 1; i >= 0; i--) {
            mClient.getCurrentEnumerateClient().onEnumerationResult(templates.get(i), i);
        }
        for (int i = templates.size() - 1; i >= 0; i--) {
            mClient.getCurrentRemoveClient().onRemoved(templates.get(i), 0);
        }

        assertThat(mAddedIds).isEmpty();
        final ArgumentCaptor<int[]> captor = ArgumentCaptor.forClass(int[].class);

        verify(mSession, times(2)).removeEnrollments(captor.capture());
        assertThat(captor.getAllValues().stream()
                .flatMap(x -> Arrays.stream(x).boxed())
                .collect(Collectors.toList()))
                .containsExactly(1, 2);
        verify(mCallback).onClientFinished(eq(mClient), eq(true));
    }

    @Test
    public void addsUnknownTemplateWhenVirtualIsEnabled() throws Exception {
        mClient.setFavorHalEnrollments();
        final List<Face> templates = List.of(
                new Face("one", 1, 1),
                new Face("two", 2, 1)
        );
        mClient.start(mCallback);
        for (int i = templates.size() - 1; i >= 0; i--) {
            mClient.getCurrentEnumerateClient().onEnumerationResult(templates.get(i), i);
        }

        assertThat(mAddedIds).containsExactly(1, 2);
        verify(mSession, never()).removeEnrollments(any());
        verify(mCallback).onClientFinished(eq(mClient), eq(true));
    }

    @Test
    public void cleanupUnknownHalTemplatesAfterEnumerationWhenVirtualIsDisabled() {
        final List<Face> templates = List.of(
                new Face("one", 1, 1),
                new Face("two", 2, 1),
                new Face("three", 3, 1)
        );
        mClient.start(mCallback);
        for (int i = templates.size() - 1; i >= 0; i--) {
            mClient.getCurrentEnumerateClient().onEnumerationResult(templates.get(i), i);
        }

        // The first template is removed after enumeration
        assertThat(mClient.getUnknownHALTemplates().size()).isEqualTo(2);

        // Simulate finishing the removal of the first template.
        // |remaining| is 0 because one FaceRemovalClient is associated with only one
        // biometrics ID.
        mClient.getCurrentRemoveClient().onRemoved(templates.get(0), 0);

        assertThat(mClient.getUnknownHALTemplates().size()).isEqualTo(1);

        // Simulate finishing the removal of the second template.
        mClient.getCurrentRemoveClient().onRemoved(templates.get(1), 0);

        assertThat(mClient.getUnknownHALTemplates()).isEmpty();
    }

    @Test
    public void noUnknownTemplates() throws RemoteException {
        mClient.start(mCallback);
        mClient.getCurrentEnumerateClient().onEnumerationResult(null, 0);

        verify(mSession).enumerateEnrollments();
        assertThat(mClient.getUnknownHALTemplates().size()).isEqualTo(0);
        verify(mSession, never()).removeEnrollments(any());
        verify(mCallback).onClientFinished(mClient, true);
    }
}
