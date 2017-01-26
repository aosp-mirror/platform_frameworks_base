/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.keyguard.KeyguardHostView.OnDismissAction;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class StatusBarTest extends SysuiTestCase {

    StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    StatusBar mStatusBar;

    @Before
    public void setup() {
        mStatusBarKeyguardViewManager = mock(StatusBarKeyguardViewManager.class);
        mStatusBar = new TestableStatusBar(mStatusBarKeyguardViewManager);

        doAnswer(invocation -> {
            OnDismissAction onDismissAction = (OnDismissAction) invocation.getArguments()[0];
            onDismissAction.onDismiss();
            return null;
        }).when(mStatusBarKeyguardViewManager).dismissWithAction(any(), any(), anyBoolean());

        doAnswer(invocation -> {
            Runnable runnable = (Runnable) invocation.getArguments()[0];
            runnable.run();
            return null;
        }).when(mStatusBarKeyguardViewManager).addAfterKeyguardGoneRunnable(any());
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_showingAndOccluded() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(true);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_showing() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(true);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    @Test
    public void executeRunnableDismissingKeyguard_nullRunnable_notShowing() {
        when(mStatusBarKeyguardViewManager.isShowing()).thenReturn(false);
        when(mStatusBarKeyguardViewManager.isOccluded()).thenReturn(false);

        mStatusBar.executeRunnableDismissingKeyguard(null, null, false, false, false);
    }

    static class TestableStatusBar extends StatusBar {
        public TestableStatusBar(StatusBarKeyguardViewManager man) {
            mStatusBarKeyguardViewManager = man;
        }

        @Override
        protected H createHandler() {
            return null;
        }
    }
}