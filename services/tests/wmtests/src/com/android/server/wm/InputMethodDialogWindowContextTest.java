/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.STATE_ON;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import static java.util.Objects.requireNonNull;

import android.app.ActivityThread;
import android.app.IApplicationThread;
import android.app.WindowConfiguration;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.IWindowManager;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.window.WindowContextInfo;
import android.window.WindowTokenClient;

import androidx.annotation.NonNull;

import com.android.server.inputmethod.InputMethodDialogWindowContext;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

// TODO(b/157888351): Move the test to inputmethod package once we find the way to test the
//  scenario there.
/**
 * Build/Install/Run:
 *  atest WmTests:InputMethodDialogWindowContextTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class InputMethodDialogWindowContextTest extends WindowTestsBase {

    private InputMethodDialogWindowContext mWindowContext;
    private DualDisplayAreaGroupPolicyTest.DualDisplayContent mSecondaryDisplay;

    private IWindowManager mIWindowManager;
    private DisplayManagerGlobal mDisplayManagerGlobal;

    private static final int WAIT_TIMEOUT_MS = 1000;

    @Before
    public void setUp() throws Exception {
        // Let the Display be created with the DualDisplay policy.
        final DisplayAreaPolicy.Provider policyProvider =
                new DualDisplayAreaGroupPolicyTest.DualDisplayTestPolicyProvider(mWm);
        Mockito.doReturn(policyProvider).when(mWm).getDisplayAreaPolicyProvider();

        mWindowContext = new InputMethodDialogWindowContext();
        mSecondaryDisplay = new DualDisplayAreaGroupPolicyTest.DualDisplayContent
                .Builder(mAtm, 1000, 1000).build();
        mSecondaryDisplay.getDisplayInfo().state = STATE_ON;

        // Mock addWindowTokenWithOptions to create a test window token.
        mIWindowManager = WindowManagerGlobal.getWindowManagerService();
        spyOn(mIWindowManager);
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            IApplicationThread appThread = (IApplicationThread) args[0];
            IBinder clientToken = (IBinder) args[1];
            int displayId = (int) args[3];
            DisplayContent dc = mWm.mRoot.getDisplayContent(displayId);
            final WindowProcessController wpc = mAtm.getProcessController(appThread);
            mWm.mWindowContextListenerController.registerWindowContainerListener(wpc, clientToken,
                    dc.getImeContainer(), TYPE_INPUT_METHOD_DIALOG, null /* options */);
            return new WindowContextInfo(dc.getImeContainer().getConfiguration(), displayId);
        }).when(mIWindowManager).attachWindowContextToDisplayArea(any(), any(),
                eq(TYPE_INPUT_METHOD_DIALOG), anyInt(), any());
        mDisplayManagerGlobal = DisplayManagerGlobal.getInstance();
        spyOn(mDisplayManagerGlobal);
        final int displayId = mSecondaryDisplay.getDisplayId();
        final Display display = mSecondaryDisplay.getDisplay();
        doReturn(display).when(mDisplayManagerGlobal).getCompatibleDisplay(eq(displayId),
                (Resources) any());
        Context systemUiContext = ActivityThread.currentActivityThread()
                .getSystemUiContext(displayId);
        spyOn(systemUiContext);
        doReturn(display).when(systemUiContext).getDisplay();
    }

    @After
    public void tearDown() {
        reset(mIWindowManager);
        reset(mDisplayManagerGlobal);
    }

    @Test
    public void testGetSettingsContext() {
        final Context contextOnDefaultDisplay = mWindowContext.get(DEFAULT_DISPLAY);

        assertImeSwitchContextMetricsValidity(contextOnDefaultDisplay, mDefaultDisplay);

        // Obtain the context again and check if the window metrics match the IME container bounds
        // of the secondary display.
        final Context contextOnSecondaryDisplay =
                mWindowContext.get(mSecondaryDisplay.getDisplayId());

        assertImeSwitchContextMetricsValidity(contextOnSecondaryDisplay, mSecondaryDisplay);
    }

    @Test
    public void testGetSettingsContextOnDualDisplayContent() {
        final Context context = mWindowContext.get(mSecondaryDisplay.getDisplayId());
        final MaxBoundsVerifier maxBoundsVerifier = new MaxBoundsVerifier();
        context.registerComponentCallbacks(maxBoundsVerifier);

        final WindowTokenClient tokenClient = (WindowTokenClient) context.getWindowContextToken();
        spyOn(requireNonNull(tokenClient));

        final DisplayArea.Tokens imeContainer = mSecondaryDisplay.getImeContainer();
        spyOn(imeContainer);
        assertThat(imeContainer.getRootDisplayArea()).isEqualTo(mSecondaryDisplay);

        final DisplayAreaGroup firstDaGroup = mSecondaryDisplay.mFirstRoot;
        maxBoundsVerifier.setMaxBounds(firstDaGroup.getMaxBounds());

        // Clear the previous invocation histories in case we may count the previous
        // onConfigurationChanged invocation into the next verification.
        clearInvocations(tokenClient, imeContainer);
        firstDaGroup.placeImeContainer(imeContainer);

        verify(imeContainer, timeout(WAIT_TIMEOUT_MS)).onConfigurationChanged(
                eq(firstDaGroup.getConfiguration()));
        verify(tokenClient, timeout(WAIT_TIMEOUT_MS)).onConfigurationChanged(
                eq(firstDaGroup.getConfiguration()),
                eq(mSecondaryDisplay.mDisplayId));
        assertThat(imeContainer.getRootDisplayArea()).isEqualTo(firstDaGroup);
        maxBoundsVerifier.waitAndAssertMaxMetricsMatches();
        assertImeSwitchContextMetricsValidity(context, mSecondaryDisplay);

        // Clear the previous invocation histories in case we may count the previous
        // onConfigurationChanged invocation into the next verification.
        clearInvocations(tokenClient, imeContainer);
        final DisplayAreaGroup secondDaGroup = mSecondaryDisplay.mSecondRoot;
        maxBoundsVerifier.setMaxBounds(secondDaGroup.getMaxBounds());

        secondDaGroup.placeImeContainer(imeContainer);

        verify(imeContainer, timeout(WAIT_TIMEOUT_MS)).onConfigurationChanged(
                eq(secondDaGroup.getConfiguration()));
        verify(tokenClient, timeout(WAIT_TIMEOUT_MS)).onConfigurationChanged(
                eq(secondDaGroup.getConfiguration()),
                eq(mSecondaryDisplay.mDisplayId));
        assertThat(imeContainer.getRootDisplayArea()).isEqualTo(secondDaGroup);
        maxBoundsVerifier.waitAndAssertMaxMetricsMatches();
        assertImeSwitchContextMetricsValidity(context, mSecondaryDisplay);
    }

    private void assertImeSwitchContextMetricsValidity(Context context, DisplayContent dc) {
        assertThat(context.getDisplayId()).isEqualTo(dc.getDisplayId());

        final Rect imeContainerBounds = dc.getImeContainer().getBounds();
        final Rect contextBounds = context.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics().getBounds();
        assertThat(contextBounds).isEqualTo(imeContainerBounds);
    }

    private static final class MaxBoundsVerifier implements ComponentCallbacks {

        private CountDownLatch mLatch;

        private Rect mMaxBounds;

        /**
         * Sets max bounds to verify whether it matches the
         * {@link WindowConfiguration#getMaxBounds()} reported from
         * {@link #onConfigurationChanged(Configuration)} callback, and also resets the count down
         * latch.
         *
         * @param maxBounds max bounds to verify
         */
        private void setMaxBounds(@NonNull Rect maxBounds) {
            mMaxBounds = maxBounds;
            mLatch = new CountDownLatch(1);
        }

        /**
         * Waits for the {@link #onConfigurationChanged(Configuration)} callback whose the reported
         * {@link WindowConfiguration#getMaxBounds()} matches {@link #mMaxBounds}.
         */
        private void waitAndAssertMaxMetricsMatches() {
            try {
                assertThat(mLatch.await(WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new RuntimeException("Test failed because of " + e);
            }
        }

        @Override
        public void onConfigurationChanged(@NonNull Configuration newConfig) {
            if (newConfig.windowConfiguration.getMaxBounds().equals(mMaxBounds)) {
                mLatch.countDown();
            }
        }

        @Override
        public void onLowMemory() {}
    }
}
