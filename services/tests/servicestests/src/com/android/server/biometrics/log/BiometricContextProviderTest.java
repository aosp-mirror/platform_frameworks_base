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

import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.StatusBarManager;
import android.content.Intent;
import android.hardware.biometrics.AuthenticateOptions;
import android.hardware.biometrics.IBiometricContextListener;
import android.hardware.biometrics.IBiometricContextListener.FoldState;
import android.hardware.biometrics.common.OperationContext;
import android.hardware.biometrics.common.OperationReason;
import android.hardware.display.DisplayManagerGlobal;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.testing.TestableContext;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

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
import java.util.Map;
import java.util.function.Consumer;

@Presubmit
@SmallTest
public class BiometricContextProviderTest {

    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();
    @Rule
    public TestableContext mContext = new TestableContext(
            InstrumentationRegistry.getInstrumentation().getContext());

    @Mock
    private IStatusBarService mStatusBarService;
    @Mock
    private ISessionListener mSessionListener;
    @Mock
    private WindowManager mWindowManager;

    private OperationContextExt mOpContext = new OperationContextExt(true);
    private IBiometricContextListener mListener;
    private BiometricContextProvider mProvider;

    @Before
    public void setup() throws RemoteException {
        when(mWindowManager.getDefaultDisplay()).thenReturn(
                new Display(DisplayManagerGlobal.getInstance(), Display.DEFAULT_DISPLAY,
                        new DisplayInfo(), DEFAULT_DISPLAY_ADJUSTMENTS));
        mProvider = new BiometricContextProvider(mContext, mWindowManager,
                mStatusBarService, null /* handler */,
                null /* authSessionCoordinator */);
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
        final Map<Integer, Boolean> expectedAod = Map.of(
                AuthenticateOptions.DISPLAY_STATE_UNKNOWN, false,
                AuthenticateOptions.DISPLAY_STATE_AOD, true,
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN, false,
                AuthenticateOptions.DISPLAY_STATE_NO_UI, false,
                AuthenticateOptions.DISPLAY_STATE_SCREENSAVER, false
        );

        for (Map.Entry<Integer, Boolean> entry : expectedAod.entrySet()) {
            mListener.onDisplayStateChanged(entry.getKey());

            assertThat(mProvider.isAod()).isEqualTo(entry.getValue());
        }
    }

    @Test
    public void testIsAwake() throws RemoteException {
        final Map<Integer, Boolean> expectedAwake = Map.of(
                AuthenticateOptions.DISPLAY_STATE_UNKNOWN, true,
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN, true,
                AuthenticateOptions.DISPLAY_STATE_SCREENSAVER, true,
                AuthenticateOptions.DISPLAY_STATE_NO_UI, false,
                AuthenticateOptions.DISPLAY_STATE_AOD, false
        );

        for (Map.Entry<Integer, Boolean> entry : expectedAwake.entrySet()) {
            mListener.onDisplayStateChanged(entry.getKey());

            assertThat(mProvider.isAwake()).isEqualTo(entry.getValue());
        }
    }

    @Test
    public void testGetDisplayState() throws RemoteException {
        final List<Integer> states = List.of(
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN,
                AuthenticateOptions.DISPLAY_STATE_SCREENSAVER,
                AuthenticateOptions.DISPLAY_STATE_NO_UI,
                AuthenticateOptions.DISPLAY_STATE_AOD,
                AuthenticateOptions.DISPLAY_STATE_UNKNOWN);

        for (int state : states) {
            mListener.onDisplayStateChanged(state);

            assertThat(mProvider.getDisplayState()).isEqualTo(state);
        }
    }

    @Test
    public void testGetIsHardwareIgnoringTouches() throws RemoteException {
        mListener.onHardwareIgnoreTouchesChanged(true);
        assertThat(mProvider.isHardwareIgnoringTouches()).isTrue();

        mListener.onHardwareIgnoreTouchesChanged(false);
        assertThat(mProvider.isHardwareIgnoringTouches()).isFalse();
    }

    @Test
    public void testGetDockedState() {
        final List<Integer> states = List.of(Intent.EXTRA_DOCK_STATE_DESK,
                Intent.EXTRA_DOCK_STATE_CAR, Intent.EXTRA_DOCK_STATE_UNDOCKED);

        for (int state : states) {
            final Intent intent = new Intent();
            intent.putExtra(Intent.EXTRA_DOCK_STATE, state);
            mProvider.mDockStateReceiver.onReceive(mContext, intent);

            assertThat(mProvider.getDockedState()).isEqualTo(state);
        }
    }

    @Test
    public void testGetFoldState() throws RemoteException {
        final List<Integer> states = List.of(FoldState.FULLY_CLOSED, FoldState.FULLY_OPENED,
                FoldState.UNKNOWN, FoldState.HALF_OPENED);

        for (int state : states) {
            mListener.onFoldChanged(state);

            assertThat(mProvider.getFoldState()).isEqualTo(state);
        }
    }

    @Test
    public void testSubscribesToFoldState() throws RemoteException {
        final List<Integer> actual = new ArrayList<>();
        final List<Integer> expected = List.of(FoldState.FULLY_CLOSED, FoldState.FULLY_OPENED,
                FoldState.UNKNOWN, FoldState.HALF_OPENED);
        mProvider.subscribe(mOpContext, ctx -> {
            assertThat(ctx).isSameInstanceAs(mOpContext.toAidlContext());
            assertThat(mProvider.getFoldState()).isEqualTo(ctx.foldState);
            actual.add(ctx.foldState);
        });

        for (int v : expected) {
            mListener.onFoldChanged(v);
        }

        assertThat(actual).containsExactly(
                FoldState.FULLY_CLOSED,
                FoldState.FULLY_OPENED,
                FoldState.UNKNOWN,
                FoldState.HALF_OPENED
        ).inOrder();
    }

    @Test
    public void testSubscribesToDisplayState() throws RemoteException {
        final List<Integer> actual = new ArrayList<>();
        final List<Integer> expected = List.of(AuthenticateOptions.DISPLAY_STATE_AOD,
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN,
                AuthenticateOptions.DISPLAY_STATE_AOD,
                AuthenticateOptions.DISPLAY_STATE_AOD,
                AuthenticateOptions.DISPLAY_STATE_NO_UI,
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN);

        mProvider.subscribe(mOpContext, ctx -> {
            assertThat(ctx).isSameInstanceAs(mOpContext.toAidlContext());
            assertThat(mProvider.getDisplayState()).isEqualTo(ctx.displayState);
            actual.add(ctx.displayState);
        });

        for (int v : expected) {
            mListener.onDisplayStateChanged(v);
        }

        assertThat(actual).containsExactly(
                AuthenticateOptions.DISPLAY_STATE_AOD,
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN,
                AuthenticateOptions.DISPLAY_STATE_AOD,
                AuthenticateOptions.DISPLAY_STATE_NO_UI,
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN).inOrder();
    }

    @Test
    public void testSubscribesToAod() throws RemoteException {
        final List<Boolean> actual = new ArrayList<>();

        mProvider.subscribe(mOpContext, ctx -> {
            assertThat(ctx).isSameInstanceAs(mOpContext.toAidlContext());
            assertThat(mProvider.isAod()).isEqualTo(ctx.isAod);
            actual.add(ctx.isAod);
        });

        for (int v : List.of(
                AuthenticateOptions.DISPLAY_STATE_AOD,
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN,
                AuthenticateOptions.DISPLAY_STATE_AOD,
                AuthenticateOptions.DISPLAY_STATE_AOD,
                AuthenticateOptions.DISPLAY_STATE_NO_UI,
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)) {
            mListener.onDisplayStateChanged(v);
        }

        assertThat(actual).containsExactly(true, false, true, false, false).inOrder();
    }

    @Test
    public void testSubscribesToAwake() throws RemoteException {
        final List<Boolean> actual = new ArrayList<>();

        mProvider.subscribe(mOpContext, ctx -> {
            assertThat(ctx).isSameInstanceAs(mOpContext.toAidlContext());
            actual.add(mProvider.isAwake());
        });

        for (int v : List.of(
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN,
                AuthenticateOptions.DISPLAY_STATE_NO_UI,
                AuthenticateOptions.DISPLAY_STATE_SCREENSAVER,
                AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN,
                AuthenticateOptions.DISPLAY_STATE_AOD,
                AuthenticateOptions.DISPLAY_STATE_NO_UI)) {
            mListener.onDisplayStateChanged(v);
        }

        assertThat(actual).containsExactly(true, false, true, true, false, false).inOrder();
    }

    @Test
    public void testSubscribesWithDifferentState() throws RemoteException {
        final Consumer<OperationContext> nonEmptyConsumer = mock(Consumer.class);
        mListener.onDisplayStateChanged(AuthenticateOptions.DISPLAY_STATE_AOD);
        mProvider.subscribe(mOpContext, nonEmptyConsumer);
        verify(nonEmptyConsumer).accept(same(mOpContext.toAidlContext()));
    }

    @Test
    public void testUnsubscribes() throws RemoteException {
        final Consumer<OperationContext> emptyConsumer = mock(Consumer.class);
        mProvider.subscribe(mOpContext, emptyConsumer);
        mProvider.unsubscribe(mOpContext);

        mListener.onDisplayStateChanged(AuthenticateOptions.DISPLAY_STATE_AOD);

        //reset to unknown to avoid trigger accept when subscribe
        mListener.onDisplayStateChanged(AuthenticateOptions.DISPLAY_STATE_UNKNOWN);

        final Consumer<OperationContext> nonEmptyConsumer = mock(Consumer.class);
        mProvider.subscribe(mOpContext, nonEmptyConsumer);
        mListener.onDisplayStateChanged(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN);
        mProvider.unsubscribe(mOpContext);
        mListener.onDisplayStateChanged(AuthenticateOptions.DISPLAY_STATE_NO_UI);

        verify(emptyConsumer, never()).accept(any());
        verify(nonEmptyConsumer).accept(same(mOpContext.toAidlContext()));
    }

    @Test
    public void testSessionId() throws RemoteException {
        final int keyguardSessionId = 10;
        final int bpSessionId = 20;

        assertThat(mProvider.getBiometricPromptSessionInfo()).isNull();
        assertThat(mProvider.getKeyguardEntrySessionInfo()).isNull();

        mSessionListener.onSessionStarted(StatusBarManager.SESSION_KEYGUARD,
                InstanceId.fakeInstanceId(keyguardSessionId));

        assertThat(mProvider.getBiometricPromptSessionInfo()).isNull();
        assertThat(mProvider.getKeyguardEntrySessionInfo().getId()).isEqualTo(keyguardSessionId);

        mSessionListener.onSessionStarted(StatusBarManager.SESSION_BIOMETRIC_PROMPT,
                InstanceId.fakeInstanceId(bpSessionId));

        assertThat(mProvider.getBiometricPromptSessionInfo().getId()).isEqualTo(bpSessionId);
        assertThat(mProvider.getKeyguardEntrySessionInfo().getId()).isEqualTo(keyguardSessionId);

        mSessionListener.onSessionEnded(StatusBarManager.SESSION_KEYGUARD,
                InstanceId.fakeInstanceId(keyguardSessionId));

        assertThat(mProvider.getBiometricPromptSessionInfo().getId()).isEqualTo(bpSessionId);
        assertThat(mProvider.getKeyguardEntrySessionInfo()).isNull();

        mSessionListener.onSessionEnded(StatusBarManager.SESSION_BIOMETRIC_PROMPT,
                InstanceId.fakeInstanceId(bpSessionId));

        assertThat(mProvider.getBiometricPromptSessionInfo()).isNull();
        assertThat(mProvider.getKeyguardEntrySessionInfo()).isNull();
    }

    @Test
    public void testUpdate() throws RemoteException {
        mListener.onDisplayStateChanged(AuthenticateOptions.DISPLAY_STATE_NO_UI);

        OperationContextExt context = mProvider.updateContext(mOpContext, false /* crypto */);
        OperationContext aidlContext = context.toAidlContext();

        // default state when nothing has been set
        assertThat(context).isSameInstanceAs(mOpContext);
        assertThat(aidlContext.id).isEqualTo(0);
        assertThat(aidlContext.reason).isEqualTo(OperationReason.UNKNOWN);
        assertThat(aidlContext.isAod).isEqualTo(false);
        assertThat(aidlContext.isCrypto).isEqualTo(false);

        context = mProvider.updateContext(mOpContext, true /* crypto */);
        aidlContext = context.toAidlContext();
        assertThat(context).isSameInstanceAs(mOpContext);
        assertThat(aidlContext.id).isEqualTo(0);
        assertThat(aidlContext.reason).isEqualTo(OperationReason.UNKNOWN);
        assertThat(aidlContext.isAod).isEqualTo(false);
        assertThat(aidlContext.isCrypto).isEqualTo(true);
    }

    @Test
    public void testUpdateAllSessionTypes() throws RemoteException {
        OperationContextExt context = mProvider.updateContext(mOpContext, false /* crypto */);
        OperationContext aidlContext = context.toAidlContext();

        for (int type : List.of(StatusBarManager.SESSION_BIOMETRIC_PROMPT,
                StatusBarManager.SESSION_KEYGUARD)) {
            final int id = 40 + type;
            final boolean aod = (type & 1) == 0;

            OperationContextExt opContext =
                    new OperationContextExt(type == StatusBarManager.SESSION_BIOMETRIC_PROMPT);
            mListener.onDisplayStateChanged(aod ? AuthenticateOptions.DISPLAY_STATE_AOD
                    : AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN);
            mSessionListener.onSessionStarted(type, InstanceId.fakeInstanceId(id));
            context = mProvider.updateContext(opContext, false /* crypto */);
            aidlContext = context.toAidlContext();
            assertThat(context).isSameInstanceAs(opContext);
            assertThat(aidlContext.id).isEqualTo(id);
            assertThat(aidlContext.reason).isEqualTo(reason(type));
            assertThat(aidlContext.isAod).isEqualTo(aod);
            assertThat(aidlContext.isCrypto).isEqualTo(false);

            mSessionListener.onSessionEnded(type, InstanceId.fakeInstanceId(id));
        }
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
