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

package com.android.systemui.navigationbar;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.view.Display;
import android.view.View;
import android.view.WindowInsetsController;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.shared.rotation.RotationButton;
import com.android.systemui.shared.rotation.RotationButtonController;
import com.android.systemui.statusbar.policy.RotationLockController;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.util.function.Supplier;

/** atest NavigationBarRotationContextTest */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class NavigationBarRotationContextTest extends SysuiTestCase {
    static final int DEFAULT_ROTATE = 0;

    @Rule
    public final SysuiTestableContext mContext = new SysuiTestableContext(
            InstrumentationRegistry.getContext(), getLeakCheck());
    private RotationButtonController mRotationButtonController;
    private RotationButton mRotationButton;
    private int mWindowRotation = DEFAULT_ROTATE;
    private Supplier<Integer> mWindowRotationSupplier = () -> mWindowRotation;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(RotationLockController.class);

        final View view = new View(mContext);
        mRotationButton = mock(RotationButton.class);
        mRotationButtonController = new RotationButtonController(mContext,
                /* lightIconColor */ 0,
                /* darkIconColor */ 0,
                /* iconCcwStart0 */ 0,
                /* iconCcwStart90 */ 0,
                /* iconCwStart0 */ 0,
                /* iconCwStart90 */ 0,
                mWindowRotationSupplier
        );
        mRotationButtonController.setRotationButton(mRotationButton,
                new RotationButton.RotationButtonUpdatesCallback() {
                    @Override
                    public void onVisibilityChanged(boolean isVisible) {
                    }

                    @Override
                    public void onPositionChanged() {
                    }
                });
        // Due to a mockito issue, only spy the object after setting the initial state
        mRotationButtonController = spy(mRotationButtonController);
        doReturn(view).when(mRotationButton).getCurrentView();
        doReturn(true).when(mRotationButton).acceptRotationProposal();
    }

    @Test
    public void testOnInvalidRotationProposal() {
        mWindowRotation = DEFAULT_ROTATE + 1;
        mRotationButtonController.onRotationProposal(DEFAULT_ROTATE, false /* isValid */);
        verify(mRotationButtonController, times(1)).setRotateSuggestionButtonState(
                false /* visible */);
    }

    @Test
    public void testOnSameRotationProposal() {
        mWindowRotation = DEFAULT_ROTATE;
        mRotationButtonController.onRotationProposal(DEFAULT_ROTATE, true /* isValid */);
        verify(mRotationButtonController, times(1)).setRotateSuggestionButtonState(
                false /* visible */);
    }

    @Test
    public void testOnRotationProposalShowButtonShowNav() {
        // No navigation bar should not call to set visibility state
        mRotationButtonController.onBehaviorChanged(Display.DEFAULT_DISPLAY,
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        mRotationButtonController.onNavigationBarWindowVisibilityChange(false /* showing */);
        verify(mRotationButtonController, times(0)).setRotateSuggestionButtonState(
                false /* visible */);
        verify(mRotationButtonController, times(0)).setRotateSuggestionButtonState(
                true /* visible */);
        mWindowRotation = DEFAULT_ROTATE + 1;

        // No navigation bar with rotation change should not call to set visibility state
        mRotationButtonController.onRotationProposal(DEFAULT_ROTATE, true /* isValid */);
        verify(mRotationButtonController, times(0)).setRotateSuggestionButtonState(
                false /* visible */);
        verify(mRotationButtonController, times(0)).setRotateSuggestionButtonState(
                true /* visible */);

        // Since rotation has changed rotation should be pending, show mButton when showing nav bar
        mRotationButtonController.onNavigationBarWindowVisibilityChange(true /* showing */);
        verify(mRotationButtonController, times(1)).setRotateSuggestionButtonState(
                true /* visible */);
    }

    @Test
    public void testOnRotationProposalShowButton() {
        // Navigation bar being visible should not call to set visibility state
        mRotationButtonController.onNavigationBarWindowVisibilityChange(true /* showing */);
        verify(mRotationButtonController, times(0)).setRotateSuggestionButtonState(
                false /* visible */);
        verify(mRotationButtonController, times(0)).setRotateSuggestionButtonState(
                true /* visible */);
        mWindowRotation = DEFAULT_ROTATE + 1;

        // Navigation bar is visible and rotation requested
        mRotationButtonController.onRotationProposal(DEFAULT_ROTATE, true /* isValid */);
        verify(mRotationButtonController, times(1)).setRotateSuggestionButtonState(
                true /* visible */);
    }
}
