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

package com.android.server.biometrics;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static java.util.Collections.emptySet;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.UserHandle;

import com.android.internal.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.util.function.Consumer;

public class AuthenticationStatsBroadcastReceiverTest {

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    private AuthenticationStatsBroadcastReceiver mBroadcastReceiver;
    private static final float FRR_THRESHOLD = 0.2f;
    private static final int USER_ID_1 = 1;

    @Mock
    Context mContext;
    @Mock
    private Resources mResources;
    @Mock
    private SharedPreferences mSharedPreferences;
    @Mock
    private SharedPreferences.Editor mEditor;
    @Mock
    Intent mIntent;
    @Mock
    Consumer<AuthenticationStatsCollector> mCallback;

    @Captor
    private ArgumentCaptor<AuthenticationStatsCollector> mArgumentCaptor;

    @Before
    public void setUp() {

        when(mContext.getResources()).thenReturn(mResources);
        when(mResources.getFraction(eq(R.fraction.config_biometricNotificationFrrThreshold),
                anyInt(), anyInt())).thenReturn(FRR_THRESHOLD);
        when(mContext.getSharedPreferences(any(File.class), anyInt()))
                .thenReturn(mSharedPreferences);
        when(mSharedPreferences.getStringSet(anyString(), anySet())).thenReturn(emptySet());
        when(mSharedPreferences.edit()).thenReturn(mEditor);
        when(mEditor.putFloat(anyString(), anyFloat())).thenReturn(mEditor);
        when(mEditor.putStringSet(anyString(), anySet())).thenReturn(mEditor);

        when(mIntent.getAction()).thenReturn(Intent.ACTION_USER_UNLOCKED);
        when(mIntent.getIntExtra(Intent.EXTRA_USER_HANDLE, UserHandle.USER_NULL))
                .thenReturn(USER_ID_1);

        mBroadcastReceiver = new AuthenticationStatsBroadcastReceiver(mContext,
                BiometricsProtoEnums.MODALITY_FINGERPRINT, mCallback);
    }

    @Test
    public void testRegisterReceiver() {
        verify(mContext).registerReceiver(eq(mBroadcastReceiver), any());
    }

    @Test
    public void testOnReceive_shouldInitializeAuthenticationStatsCollector() {
        mBroadcastReceiver.onReceive(mContext, mIntent);

        // Verify AuthenticationStatsCollector is initialized
        verify(mCallback).accept(mArgumentCaptor.capture());
        assertThat(mArgumentCaptor.getValue()).isNotNull();

        // Verify receiver is unregistered after receiving the broadcast
        verify(mContext).unregisterReceiver(mBroadcastReceiver);
    }
}
