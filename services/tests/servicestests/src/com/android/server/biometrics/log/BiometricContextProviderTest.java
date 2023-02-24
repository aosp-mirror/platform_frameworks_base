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

package com.android.server.biometrics.log;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.StatusBarManager;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.OperationReason;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.InstanceId;
import com.android.internal.statusbar.ISessionListener;
import com.android.internal.statusbar.IStatusBarService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Presubmit
@SmallTest
public class BiometricContextProviderTest {

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock
    private IStatusBarService mStatusBarService;
    @Mock
    private ISessionListener mSessionListener;
    @Mock
    private AmbientDisplayConfiguration mAmbientDisplayConfiguration;

    private OperationContext mOpContext = new OperationContext();
    private IBiometricContextListener mListener;
    private BiometricContextProvider mProvider;

    @Before
    public void setup() throws RemoteException {
        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(true);
        mProvider = new BiometricContextProvider(mAmbientDisplayConfiguration, mStatusBarService,
                null /* handler */);
        ArgumentCaptor<IBiometricContextListener> captor =
                ArgumentCaptor.forClass(IBiometricContextListener.class);
        verify(mStatusBarService).setBiometicContextListener(captor.capture());
        mListener = captor.getValue();
        ArgumentCaptor<ISessionListener> sessionCaptor =
                ArgumentCaptor.forClass(ISessionListener.class);
        verify(mStatusBarService).registerSessionListener(anyInt(), sessionCaptor.capture());
        mSessionListener = sessionCaptor.getValue();
    }

    @Test
    public void testIsAod() throws RemoteException {
        mListener.onDozeChanged(true /* isDozing */, false /* isAwake */);
        assertThat(mProvider.isAod()).isTrue();
        mListener.onDozeChanged(false /* isDozing */, false /* isAwake */);
        assertThat(mProvider.isAod()).isFalse();

        when(mAmbientDisplayConfiguration.alwaysOnEnabled(anyInt())).thenReturn(false);
        mListener.onDozeChanged(true /* isDozing */, false /* isAwake */);
        assertThat(mProvider.isAod()).isFalse();
        mListener.onDozeChanged(false /* isDozing */, false /* isAwake */);
        assertThat(mProvider.isAod()).isFalse();
    }

    @Test
    public void testIsAwake() throws RemoteException {
        mListener.onDozeChanged(false /* isDozing */, true /* isAwake */);
        assertThat(mProvider.isAwake()).isTrue();
        mListener.onDozeChanged(false /* isDozing */, false /* isAwake */);
        assertThat(mProvider.isAwake()).isFalse();
        mListener.onDozeChanged(true /* isDozing */, true /* isAwake */);
        assertThat(mProvider.isAwake()).isTrue();
        mListener.onDozeChanged(true /* isDozing */, false /* isAwake */);
        assertThat(mProvider.isAwake()).isFalse();
    }

    @Test
    public void testSubscribesToAod() throws RemoteException {
        final List<Boolean> actual = new ArrayList<>();

        mProvider.subscribe(mOpContext, ctx -> {
            assertThat(ctx).isSameInstanceAs(mOpContext);
            assertThat(mProvider.isAod()).isEqualTo(ctx.isAod);
            assertThat(mProvider.isAwake()).isFalse();
            actual.add(ctx.isAod);
        });

        for (boolean v : List.of(true, false, true, true, false, false)) {
            mListener.onDozeChanged(v /* isDozing */, false /* isAwake */);
        }

        assertThat(actual).containsExactly(true, false, true, false).inOrder();
    }

    @Test
    public void testSubscribesToAwake() throws RemoteException {
        final List<Boolean> actual = new ArrayList<>();

        mProvider.subscribe(mOpContext, ctx -> {
            assertThat(ctx).isSameInstanceAs(mOpContext);
            assertThat(ctx.isAod).isFalse();
            assertThat(mProvider.isAod()).isFalse();
            actual.add(mProvider.isAwake());
        });

        for (boolean v : List.of(true, false, true, true, false, false)) {
            mListener.onDozeChanged(false /* isDozing */, v /* isAwake */);
        }

        assertThat(actual).containsExactly(true, false, true, false).inOrder();
    }

    @Test
    public void testUnsubscribes() throws RemoteException {
        final Consumer<OperationContext> emptyConsumer = mock(Consumer.class);
        mProvider.subscribe(mOpContext, emptyConsumer);
        mProvider.unsubscribe(mOpContext);

        mListener.onDozeChanged(true /* isDozing */, false /* isAwake */);

        final Consumer<OperationContext> nonEmptyConsumer = mock(Consumer.class);
        mProvider.subscribe(mOpContext, nonEmptyConsumer);
        mListener.onDozeChanged(false /* isDozing */, false /* isAwake */);
        mProvider.unsubscribe(mOpContext);
        mListener.onDozeChanged(true /* isDozing */, false /* isAwake */);

        verify(emptyConsumer, never()).accept(any());
        verify(nonEmptyConsumer).accept(same(mOpContext));
    }

    @Test
    public void testSessionId() throws RemoteException {
        final int keyguardSessionId = 10;
        final int bpSessionId = 20;

        assertThat(mProvider.getBiometricPromptSessionId()).isNull();
        assertThat(mProvider.getKeyguardEntrySessionId()).isNull();

        mSessionListener.onSessionStarted(StatusBarManager.SESSION_KEYGUARD,
                InstanceId.fakeInstanceId(keyguardSessionId));

        assertThat(mProvider.getBiometricPromptSessionId()).isNull();
        assertThat(mProvider.getKeyguardEntrySessionId()).isEqualTo(keyguardSessionId);

        mSessionListener.onSessionStarted(StatusBarManager.SESSION_BIOMETRIC_PROMPT,
                InstanceId.fakeInstanceId(bpSessionId));

        assertThat(mProvider.getBiometricPromptSessionId()).isEqualTo(bpSessionId);
        assertThat(mProvider.getKeyguardEntrySessionId()).isEqualTo(keyguardSessionId);

        mSessionListener.onSessionEnded(StatusBarManager.SESSION_KEYGUARD,
                InstanceId.fakeInstanceId(keyguardSessionId));

        assertThat(mProvider.getBiometricPromptSessionId()).isEqualTo(bpSessionId);
        assertThat(mProvider.getKeyguardEntrySessionId()).isNull();

        mSessionListener.onSessionEnded(StatusBarManager.SESSION_BIOMETRIC_PROMPT,
                InstanceId.fakeInstanceId(bpSessionId));

        assertThat(mProvider.getBiometricPromptSessionId()).isNull();
        assertThat(mProvider.getKeyguardEntrySessionId()).isNull();
    }

    @Test
    public void testUpdate() throws RemoteException {
        mListener.onDozeChanged(false /* isDozing */, false /* isAwake */);
        OperationContext context = mProvider.updateContext(mOpContext, false /* crypto */);

        // default state when nothing has been set
        assertThat(context).isSameInstanceAs(mOpContext);
        assertThat(mOpContext.id).isEqualTo(0);
        assertThat(mOpContext.reason).isEqualTo(OperationReason.UNKNOWN);
        assertThat(mOpContext.isAod).isEqualTo(false);
        assertThat(mOpContext.isCrypto).isEqualTo(false);

        for (int type : List.of(StatusBarManager.SESSION_BIOMETRIC_PROMPT,
                StatusBarManager.SESSION_KEYGUARD)) {
            final int id = 40 + type;
            final boolean aod = (type & 1) == 0;

            mListener.onDozeChanged(aod /* isDozing */, false /* isAwake */);
            mSessionListener.onSessionStarted(type, InstanceId.fakeInstanceId(id));
            context = mProvider.updateContext(mOpContext, false /* crypto */);
            assertThat(context).isSameInstanceAs(mOpContext);
            assertThat(mOpContext.id).isEqualTo(id);
            assertThat(mOpContext.reason).isEqualTo(reason(type));
            assertThat(mOpContext.isAod).isEqualTo(aod);
            assertThat(mOpContext.isCrypto).isEqualTo(false);

            mSessionListener.onSessionEnded(type, InstanceId.fakeInstanceId(id));
        }

        context = mProvider.updateContext(mOpContext, false /* crypto */);
        assertThat(context).isSameInstanceAs(mOpContext);
        assertThat(mOpContext.id).isEqualTo(0);
        assertThat(mOpContext.reason).isEqualTo(OperationReason.UNKNOWN);
        assertThat(mOpContext.isAod).isEqualTo(false);
        assertThat(mOpContext.isCrypto).isEqualTo(false);
    }

    private static byte reason(int type) {
        if (type == StatusBarManager.SESSION_BIOMETRIC_PROMPT) {
            return OperationReason.BIOMETRIC_PROMPT;
        }
        if (type == StatusBarManager.SESSION_KEYGUARD) {
            return OperationReason.KEYGUARD;
        }
        return OperationReason.UNKNOWN;
    }
}
