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

import static android.view.InsetsState.TYPE_IME;
import static android.view.InsetsState.TYPE_NAVIGATION_BAR;
import static android.view.InsetsState.TYPE_TOP_BAR;

import static android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.WindowInsets.Type;
import android.view.WindowManager.BadTokenException;
import android.view.WindowManager.LayoutParams;
import android.widget.TextView;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@FlakyTest(detail = "Promote once confirmed non-flaky")
@RunWith(AndroidJUnit4.class)
public class InsetsControllerTest {

    private InsetsController mController;
    private SurfaceSession mSession = new SurfaceSession();
    private SurfaceControl mLeash;

    @Before
    public void setup() {
        mLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getTargetContext();
            // cannot mock ViewRootImpl since it's final.
            ViewRootImpl viewRootImpl = new ViewRootImpl(context, context.getDisplay());
            try {
                viewRootImpl.setView(new TextView(context), new LayoutParams(), null);
            } catch (BadTokenException e) {
                // activity isn't running, we will ignore BadTokenException.
            }
            mController = new InsetsController(viewRootImpl);
            final Rect rect = new Rect(5, 5, 5, 5);
            mController.calculateInsets(
                    false,
                    false,
                    new DisplayCutout(
                            Insets.of(10, 10, 10, 10), rect, rect, rect, rect),
                    rect, rect, SOFT_INPUT_ADJUST_RESIZE);
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testControlsChanged() {
        InsetsSourceControl control = new InsetsSourceControl(TYPE_TOP_BAR, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        assertEquals(mLeash,
                mController.getSourceConsumer(TYPE_TOP_BAR).getControl().getLeash());
    }

    @Test
    public void testControlsRevoked() {
        InsetsSourceControl control = new InsetsSourceControl(TYPE_TOP_BAR, mLeash, new Point());
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        mController.onControlsChanged(new InsetsSourceControl[0]);
        assertNull(mController.getSourceConsumer(TYPE_TOP_BAR).getControl());
    }

    @Test
    public void testFrameDoesntMatchDisplay() {
        mController.onFrameChanged(new Rect(0, 0, 100, 100));
        mController.getState().setDisplayFrame(new Rect(0, 0, 200, 200));
        WindowInsetsAnimationControlListener controlListener =
                mock(WindowInsetsAnimationControlListener.class);
        mController.controlWindowInsetsAnimation(0, controlListener);
        verify(controlListener).onCancelled();
        verify(controlListener, never()).onReady(any(), anyInt());
    }

    @Test
    public void testAnimationEndState() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl topBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.show(Type.all());
            // quickly jump to final state by cancelling it.
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(topBar.getType()).isVisible());
            // no focused view, no IME.
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.hide(Type.all());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.show(Type.ime());
            mController.cancelExistingAnimation();
            // no focused view, no IME.
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testApplyImeVisibility() {
        final InsetsSourceControl ime = new InsetsSourceControl(TYPE_IME, mLeash, new Point());

        InsetsSourceControl[] controls = new InsetsSourceControl[3];
        controls[0] = ime;
        mController.onControlsChanged(controls);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.applyImeVisibility(true);
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(ime.getType()).isVisible());
            mController.applyImeVisibility(false);
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testShowHideSelectively() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl topBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int types = Type.sideBars() | Type.systemBars();
            // test show select types.
            mController.show(types);
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            // test hide all
            mController.hide(types);
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testShowHideSingle() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl topBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int types = Type.sideBars() | Type.systemBars();
            // test show select types.
            mController.show(types);
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            // test hide all
            mController.hide(Type.all());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            // test single show
            mController.show(Type.sideBars());
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            // test single hide
            mController.hide(Type.sideBars());
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testShowHideMultiple() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl topBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // start two animations and see if previous is cancelled and final state is reached.
            mController.show(Type.sideBars());
            mController.show(Type.systemBars());
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.hide(Type.sideBars());
            mController.hide(Type.systemBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            int types = Type.sideBars() | Type.systemBars();
            // show two at a time and hide one by one.
            mController.show(types);
            mController.hide(Type.sideBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.hide(Type.systemBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void testShowMultipleHideOneByOne() {
        InsetsSourceControl[] controls = prepareControls();
        InsetsSourceControl navBar = controls[0];
        InsetsSourceControl topBar = controls[1];
        InsetsSourceControl ime = controls[2];

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int types = Type.sideBars() | Type.systemBars();
            // show two at a time and hide one by one.
            mController.show(types);
            mController.hide(Type.sideBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.hide(Type.systemBars());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private InsetsSourceControl[] prepareControls() {
        final InsetsSourceControl navBar = new InsetsSourceControl(TYPE_NAVIGATION_BAR, mLeash,
                new Point());
        final InsetsSourceControl topBar = new InsetsSourceControl(TYPE_TOP_BAR, mLeash,
                new Point());
        final InsetsSourceControl ime = new InsetsSourceControl(TYPE_IME, mLeash, new Point());

        InsetsSourceControl[] controls = new InsetsSourceControl[3];
        controls[0] = navBar;
        controls[1] = topBar;
        controls[2] = ime;
        mController.onControlsChanged(controls);
        return controls;
    }
}
