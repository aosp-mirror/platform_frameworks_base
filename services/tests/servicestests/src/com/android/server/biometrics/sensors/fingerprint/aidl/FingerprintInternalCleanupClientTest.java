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

package com.android.server.biometrics.sensors.fingerprint.aidl;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.biometrics.fingerprint.ISession;
import android.hardware.fingerprint.Fingerprint;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.testing.TestableContext;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.server.biometrics.Flags;
import com.android.server.biometrics.log.BiometricContext;
import com.android.server.biometrics.log.BiometricLogger;
import com.android.server.biometrics.sensors.ClientMonitorCallback;
import com.android.server.biometrics.sensors.fingerprint.FingerprintUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Presubmit
@SmallTest
public class FingerprintInternalCleanupClientTest {

    private static final int SENSOR_ID = 22;

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Rule
    public final TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getTargetContext(), null);

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Mock
    ISession mSession;
    @Mock
    private AidlSession mAidlSession;
    @Mock
    private BiometricLogger mLogger;
    @Mock
    private BiometricContext mBiometricContext;
    @Mock
    private FingerprintUtils mFingerprintUtils;
    @Mock
    private ClientMonitorCallback mCallback;

    private FingerprintInternalCleanupClient mClient;
    private List<Integer> mAddedIds;

    @Before
    public void setup() {
        mAddedIds = new ArrayList<>();

        when(mAidlSession.getSession()).thenReturn(mSession);
    }

    @Test
    public void removesUnknownTemplate() throws Exception {
        mClient = createClient();

        final ArgumentCaptor<int[]> captor = ArgumentCaptor.forClass(int[].class);
        final List<Fingerprint> templates = List.of(
                new Fingerprint("one", 1, 1),
                new Fingerprint("two", 2, 1)
        );
        mClient.start(mCallback);
        for (int i = templates.size() - 1; i >= 0; i--) {
            mClient.getCurrentEnumerateClient().onEnumerationResult(templates.get(i), i);
        }
        for (int i = templates.size() - 1; i >= 0; i--) {
            mClient.getCurrentRemoveClient().onRemoved(templates.get(i), 0);
        }

        verify(mSession).enumerateEnrollments();
        assertThat(mAddedIds).isEmpty();
        verify(mSession, times(2)).removeEnrollments(captor.capture());
        assertThat(captor.getAllValues().stream()
                .flatMap(x -> Arrays.stream(x).boxed())
                .collect(Collectors.toList()))
                .containsExactly(1, 2);
        verify(mCallback).onClientFinished(eq(mClient), eq(true));
    }

    @Test
    public void addsUnknownTemplateWhenVirtualIsEnabled() throws Exception {
        mClient = createClient();
        mClient.setFavorHalEnrollments();

        final List<Fingerprint> templates = List.of(
                new Fingerprint("one", 1, 1),
                new Fingerprint("two", 2, 1)
        );
        mClient.start(mCallback);
        for (int i = templates.size() - 1; i >= 0; i--) {
            mClient.getCurrentEnumerateClient().onEnumerationResult(templates.get(i), i);
        }

        verify(mSession).enumerateEnrollments();
        assertThat(mAddedIds).containsExactly(1, 2);
        verify(mSession, never()).removeEnrollments(any());
        verify(mCallback).onClientFinished(eq(mClient), eq(true));
    }

    @Test
    public void cleanupUnknownHalTemplatesAfterEnumerationWhenVirtualIsDisabled()
            throws RemoteException {
        mClient = createClient();

        final List<Fingerprint> templates = List.of(
                new Fingerprint("one", 1, 1),
                new Fingerprint("two", 2, 1),
                new Fingerprint("three", 3, 1)
        );
        mClient.start(mCallback);
        for (int i = templates.size() - 1; i >= 0; i--) {
            mClient.getCurrentEnumerateClient().onEnumerationResult(templates.get(i), i);
        }

        verify(mSession).enumerateEnrollments();
        // The first template is removed after enumeration
        assertThat(mClient.getUnknownHALTemplates().size()).isEqualTo(2);

        // Simulate finishing the removal of the first template.
        // |remaining| is 0 because one FingerprintRemovalClient is associated with only one
        // biometrics ID.
        mClient.getCurrentRemoveClient().onRemoved(templates.get(0), 0);
        assertThat(mClient.getUnknownHALTemplates().size()).isEqualTo(1);
        // Simulate finishing the removal of the second template.
        mClient.getCurrentRemoveClient().onRemoved(templates.get(1), 0);
        assertThat(mClient.getUnknownHALTemplates()).isEmpty();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_NOTIFY_FINGERPRINTS_LOE)
    public void invalidBiometricUserState() throws Exception {
        mClient =  createClient();

        final List<Fingerprint> templates = List.of(
                new Fingerprint("one", 1, 1),
                new Fingerprint("two", 2, 1),
                new Fingerprint("three", 3, 1)
        );

        final List<Fingerprint> list = new ArrayList<>();
        doReturn(true).when(mFingerprintUtils)
                .hasValidBiometricUserState(mContext, 2);
        doReturn(list).when(mFingerprintUtils).getBiometricsForUser(mContext, 2);

        mClient.start(mCallback);
        for (int i = templates.size() - 1; i >= 0; i--) {
            mClient.getCurrentEnumerateClient().onEnumerationResult(templates.get(i), i);
        }
        verify(mLogger).logFingerprintsLoe();
        verify(mFingerprintUtils).deleteStateForUser(2);
    }

    protected FingerprintInternalCleanupClient createClient() {
        final Map<Integer, Long> authenticatorIds = new HashMap<>();
        return new FingerprintInternalCleanupClient(mContext, () -> mAidlSession, 2 /* userId */,
                "the.test.owner", SENSOR_ID, mLogger, mBiometricContext,
                mFingerprintUtils, authenticatorIds) {
            @Override
            protected void onAddUnknownTemplate(int userId,
                    @NonNull BiometricAuthenticator.Identifier identifier) {
                mAddedIds.add(identifier.getBiometricId());
            }
        };
    }
}
