/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.FLAG_CANCELED;
import static android.view.KeyEvent.FLAG_LONG_PRESS;
import static android.view.KeyEvent.KEYCODE_0;
import static android.view.KeyEvent.KEYCODE_APP_SWITCH;
import static android.view.KeyEvent.KEYCODE_BACK;
import static android.view.KeyEvent.KEYCODE_HOME;

import static com.android.systemui.statusbar.policy.KeyButtonView.NavBarButtonEvent.NAVBAR_BACK_BUTTON_LONGPRESS;
import static com.android.systemui.statusbar.policy.KeyButtonView.NavBarButtonEvent.NAVBAR_BACK_BUTTON_TAP;
import static com.android.systemui.statusbar.policy.KeyButtonView.NavBarButtonEvent.NAVBAR_HOME_BUTTON_LONGPRESS;
import static com.android.systemui.statusbar.policy.KeyButtonView.NavBarButtonEvent.NAVBAR_HOME_BUTTON_TAP;
import static com.android.systemui.statusbar.policy.KeyButtonView.NavBarButtonEvent.NAVBAR_OVERVIEW_BUTTON_LONGPRESS;
import static com.android.systemui.statusbar.policy.KeyButtonView.NavBarButtonEvent.NAVBAR_OVERVIEW_BUTTON_TAP;

import static junit.framework.Assert.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.input.InputManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.KeyEvent;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.recents.OverviewProxyService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class KeyButtonViewTest extends SysuiTestCase {

    private KeyButtonView mKeyButtonView;
    private MetricsLogger mMetricsLogger;
    private BubbleController mBubbleController;
    private UiEventLogger mUiEventLogger;
    private InputManager mInputManager = mock(InputManager.class);
    @Captor
    private ArgumentCaptor<KeyEvent> mInputEventCaptor;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        mMetricsLogger = mDependency.injectMockDependency(MetricsLogger.class);
        mBubbleController = mDependency.injectMockDependency(BubbleController.class);
        mDependency.injectMockDependency(OverviewProxyService.class);
        mUiEventLogger = mDependency.injectMockDependency(UiEventLogger.class);

        TestableLooper.get(this).runWithLooper(() -> {
            mKeyButtonView = new KeyButtonView(mContext, null, 0, mInputManager, mUiEventLogger);
        });
    }

    @Test
    public void testLogBackPress() {
        checkmetrics(KEYCODE_BACK, ACTION_UP, 0, NAVBAR_BACK_BUTTON_TAP);
    }

    @Test
        public void testLogOverviewPress() {
        checkmetrics(KEYCODE_APP_SWITCH, ACTION_UP, 0, NAVBAR_OVERVIEW_BUTTON_TAP);
    }

    @Test
    public void testLogHomePress() {
        checkmetrics(KEYCODE_HOME, ACTION_UP, 0, NAVBAR_HOME_BUTTON_TAP);
    }

    @Test
    public void testLogBackLongPressLog() {
        checkmetrics(KEYCODE_BACK, ACTION_DOWN, FLAG_LONG_PRESS, NAVBAR_BACK_BUTTON_LONGPRESS);
    }

    @Test
    public void testLogOverviewLongPress() {
        checkmetrics(KEYCODE_APP_SWITCH, ACTION_DOWN, FLAG_LONG_PRESS,
                NAVBAR_OVERVIEW_BUTTON_LONGPRESS);
    }

    @Test
    public void testLogHomeLongPress() {
        checkmetrics(KEYCODE_HOME, ACTION_DOWN, FLAG_LONG_PRESS, NAVBAR_HOME_BUTTON_LONGPRESS);
    }

    @Test
    public void testNoLogKeyDown() {
        checkmetrics(KEYCODE_BACK, ACTION_DOWN, 0, null);
    }

    @Test
    public void testNoLogTapAfterLong() {
        mKeyButtonView.mLongClicked = true;
        checkmetrics(KEYCODE_BACK, ACTION_UP, 0, null);
    }

    @Test
    public void testNoLogCanceled() {
        checkmetrics(KEYCODE_HOME, ACTION_UP, FLAG_CANCELED, null);
    }

    @Test
    public void testNoLogArbitraryKeys() {
        checkmetrics(KEYCODE_0, ACTION_UP, 0, null);
    }

    private void checkmetrics(int code, int action, int flag,
            KeyButtonView.NavBarButtonEvent expected) {
        mKeyButtonView.setCode(code);
        mKeyButtonView.sendEvent(action, flag);
        if (expected == null) {
            verify(mUiEventLogger, never()).log(any(KeyButtonView.NavBarButtonEvent.class));
        } else {
            verify(mUiEventLogger, times(1)).log(expected);
        }
    }

    @Test
    public void testBubbleEvents_bubbleExpanded() {
        when(mBubbleController.getExpandedDisplayId(mContext)).thenReturn(3);

        int action = KeyEvent.ACTION_DOWN;
        int flags = 0;
        int code = KeyEvent.KEYCODE_BACK;
        mKeyButtonView.setCode(code);
        mKeyButtonView.sendEvent(action, flags);

        verify(mInputManager, times(1)).injectInputEvent(mInputEventCaptor.capture(),
                anyInt());
        assertEquals(3, mInputEventCaptor.getValue().getDisplayId());
    }
}