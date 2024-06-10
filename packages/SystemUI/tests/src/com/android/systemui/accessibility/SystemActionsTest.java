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

package com.android.systemui.accessibility;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.input.InputManager;
import android.os.RemoteException;
import android.telecom.TelecomManager;
import android.telephony.TelephonyManager;
import android.testing.TestableLooper;
import android.view.KeyEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.recents.Recents;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.domain.interactor.PanelExpansionInteractor;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@TestableLooper.RunWithLooper
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SystemActionsTest extends SysuiTestCase {
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private NotificationShadeWindowController mNotificationShadeController;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private PanelExpansionInteractor mPanelExpansionInteractor;
    @Mock
    private Optional<Recents> mRecentsOptional;
    @Mock
    private TelecomManager mTelecomManager;
    @Mock
    private InputManager mInputManager;
    private final FakeDisplayTracker mDisplayTracker = new FakeDisplayTracker(mContext);

    private SystemActions mSystemActions;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(TelecomManager.class, mTelecomManager);
        mContext.addMockSystemService(InputManager.class, mInputManager);
        mSystemActions = new SystemActions(
                mContext,
                mUserTracker,
                mNotificationShadeController,
                mKeyguardStateController,
                mShadeController,
                () -> mPanelExpansionInteractor,
                mRecentsOptional,
                mDisplayTracker);
    }

    @Test
    public void handleHeadsetHook_callStateIdle_injectsKeyEvents() {
        when(mTelecomManager.getCallState()).thenReturn(TelephonyManager.CALL_STATE_IDLE);
        // Use a custom doAnswer captor that copies the KeyEvent before storing it, because the
        // method under test modifies the event object after injecting it which prevents
        // reliably asserting on the event properties.
        final List<KeyEvent> keyEvents = new ArrayList<>();
        doAnswer(invocation -> {
            keyEvents.add(new KeyEvent(invocation.getArgument(0)));
            return null;
        }).when(mInputManager).injectInputEvent(any(), anyInt());

        mSystemActions.handleHeadsetHook();

        assertThat(keyEvents.size()).isEqualTo(2);
        assertThat(keyEvents.get(0).getKeyCode()).isEqualTo(KeyEvent.KEYCODE_HEADSETHOOK);
        assertThat(keyEvents.get(0).getAction()).isEqualTo(KeyEvent.ACTION_DOWN);
        assertThat(keyEvents.get(1).getKeyCode()).isEqualTo(KeyEvent.KEYCODE_HEADSETHOOK);
        assertThat(keyEvents.get(1).getAction()).isEqualTo(KeyEvent.ACTION_UP);
    }

    @Test
    public void handleHeadsetHook_callStateRinging_answersCall() {
        when(mTelecomManager.getCallState()).thenReturn(TelephonyManager.CALL_STATE_RINGING);

        mSystemActions.handleHeadsetHook();

        verify(mTelecomManager).acceptRingingCall();
    }

    @Test
    public void handleHeadsetHook_callStateOffhook_endsCall() {
        when(mTelecomManager.getCallState()).thenReturn(TelephonyManager.CALL_STATE_OFFHOOK);

        mSystemActions.handleHeadsetHook();

        verify(mTelecomManager).endCall();
    }
}
