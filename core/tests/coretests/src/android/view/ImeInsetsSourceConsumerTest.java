/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package android.view;

import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManager.BadTokenException;
import android.view.WindowManager.LayoutParams;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.FlakyTest;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.Spy;

/**
 * Test {@link InsetsSourceConsumer} with IME type.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:ImeInsetsSourceConsumerTest
 */
@Presubmit
@FlakyTest(detail = "Promote once confirmed non-flaky")
@RunWith(AndroidJUnit4.class)
public class ImeInsetsSourceConsumerTest {

    Context mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    ImeInsetsSourceConsumer mImeConsumer;
    @Spy InsetsController mController;
    SurfaceControl mLeash;

    @Before
    public void setup() {
        mLeash = new SurfaceControl.Builder(new SurfaceSession())
                .setName("testSurface")
                .build();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewRootImpl viewRootImpl = new ViewRootImpl(mContext, mContext.getDisplayNoVerify());
            try {
                viewRootImpl.setView(new TextView(mContext), new LayoutParams(), null);
            } catch (BadTokenException e) {
                // activity isn't running, we will ignore BadTokenException.
            }
            mController = Mockito.spy(new InsetsController(
                    new ViewRootInsetsControllerHost(viewRootImpl)));
            final Rect rect = new Rect(5, 5, 5, 5);
            mController.getState().setDisplayCutout(new DisplayCutout(
                    Insets.of(10, 10, 10, 10), rect, rect, rect, rect));
            mController.calculateInsets(
                    false,
                    false,
                    TYPE_APPLICATION, WINDOWING_MODE_UNDEFINED,
                    SOFT_INPUT_ADJUST_RESIZE, 0, 0);
            mImeConsumer = (ImeInsetsSourceConsumer) mController.getSourceConsumer(ITYPE_IME);
        });
    }

    @Test
    public void testImeVisibility() {
        final InsetsSourceControl ime =
                new InsetsSourceControl(ITYPE_IME, mLeash, false, new Point(), Insets.NONE);
        mController.onControlsChanged(new InsetsSourceControl[] { ime });

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // test if setVisibility can show IME
            mImeConsumer.onWindowFocusGained(true);
            mController.show(WindowInsets.Type.ime(), true /* fromIme */);
            mController.cancelExistingAnimations();
            assertTrue(mController.getSourceConsumer(ime.getType()).isRequestedVisible());

            // test if setVisibility can hide IME
            mController.hide(WindowInsets.Type.ime(), true /* fromIme */);
            mController.cancelExistingAnimations();
            assertFalse(mController.getSourceConsumer(ime.getType()).isRequestedVisible());
        });
    }

    @Test
    public void testImeRequestedVisibleAwaitingControl() {
        // Set null control and then request show.
        mController.onControlsChanged(new InsetsSourceControl[] { null });

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Request IME visible before control is available.
            mImeConsumer.onWindowFocusGained(true);
            mController.show(WindowInsets.Type.ime(), true /* fromIme */);

            // set control and verify visibility is applied.
            InsetsSourceControl control =
                    new InsetsSourceControl(ITYPE_IME, mLeash, false, new Point(), Insets.NONE);
            mController.onControlsChanged(new InsetsSourceControl[] { control });
            // IME show animation should be triggered when control becomes available.
            verify(mController).applyAnimation(
                    eq(WindowInsets.Type.ime()), eq(true) /* show */, eq(true) /* fromIme */);
            verify(mController, never()).applyAnimation(
                    eq(WindowInsets.Type.ime()), eq(false) /* show */, eq(true) /* fromIme */);
        });
    }

    @Test
    public void testImeGetAndClearSkipAnimationOnce_expectSkip() {
        // Expect IME animation will skipped when the IME is visible at first place.
        verifyImeGetAndClearSkipAnimationOnce(true /* hasWindowFocus */, true /* hasViewFocus */,
                true /* expectSkipAnim */);
    }

    @Test
    public void testImeGetAndClearSkipAnimationOnce_expectNoSkip() {
        // Expect IME animation will not skipped if previously no view focused when gained the
        // window focus and requesting the IME visible next time.
        verifyImeGetAndClearSkipAnimationOnce(true /* hasWindowFocus */, false /* hasViewFocus */,
                false /* expectSkipAnim */);
    }

    private void verifyImeGetAndClearSkipAnimationOnce(boolean hasWindowFocus, boolean hasViewFocus,
            boolean expectSkipAnim) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Request IME visible before control is available.
            mImeConsumer.onWindowFocusGained(hasWindowFocus);
            final boolean imeVisible = hasWindowFocus && hasViewFocus;
            if (imeVisible) {
                mController.show(WindowInsets.Type.ime(), true /* fromIme */);
            }

            // set control and verify visibility is applied.
            InsetsSourceControl control = Mockito.spy(
                    new InsetsSourceControl(ITYPE_IME, mLeash, false, new Point(), Insets.NONE));
            // Simulate IME source control set this flag when the target has starting window.
            control.setSkipAnimationOnce(true);

            if (imeVisible) {
                // Verify IME applyAnimation should be triggered when control becomes available,
                // and expect skip animation state after getAndClearSkipAnimationOnce invoked.
                mController.onControlsChanged(new InsetsSourceControl[]{ control });
                verify(control).getAndClearSkipAnimationOnce();
                verify(mController).applyAnimation(eq(WindowInsets.Type.ime()),
                        eq(true) /* show */, eq(false) /* fromIme */,
                        eq(expectSkipAnim) /* skipAnim */);
            }

            // If previously hasViewFocus is false, verify when requesting the IME visible next
            // time will not skip animation.
            if (!hasViewFocus) {
                mController.show(WindowInsets.Type.ime(), true);
                mController.onControlsChanged(new InsetsSourceControl[]{ control });
                // Verify IME show animation should be triggered when control becomes available and
                // the animation will be skipped by getAndClearSkipAnimationOnce invoked.
                verify(control).getAndClearSkipAnimationOnce();
                verify(mController).applyAnimation(eq(WindowInsets.Type.ime()),
                        eq(true) /* show */, eq(true) /* fromIme */,
                        eq(false) /* skipAnim */);
            }
        });
    }
}
