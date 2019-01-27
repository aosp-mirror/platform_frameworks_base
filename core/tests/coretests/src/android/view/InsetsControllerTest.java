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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import android.content.Context;
import android.graphics.Insets;
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
                    rect, rect);
        });
    }

    @Test
    public void testControlsChanged() {
        InsetsSourceControl control = new InsetsSourceControl(TYPE_TOP_BAR, mLeash);
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        assertEquals(mLeash,
                mController.getSourceConsumer(TYPE_TOP_BAR).getControl().getLeash());
    }

    @Test
    public void testControlsRevoked() {
        InsetsSourceControl control = new InsetsSourceControl(TYPE_TOP_BAR, mLeash);
        mController.onControlsChanged(new InsetsSourceControl[] { control });
        mController.onControlsChanged(new InsetsSourceControl[0]);
        assertNull(mController.getSourceConsumer(TYPE_TOP_BAR).getControl());
    }

    @Test
    public void testAnimationEndState() {
        final InsetsSourceControl navBar = new InsetsSourceControl(TYPE_NAVIGATION_BAR, mLeash);
        final InsetsSourceControl topBar = new InsetsSourceControl(TYPE_TOP_BAR, mLeash);
        final InsetsSourceControl ime = new InsetsSourceControl(TYPE_IME, mLeash);

        InsetsSourceControl[] controls = new InsetsSourceControl[3];
        controls[0] = navBar;
        controls[1] = topBar;
        controls[2] = ime;
        mController.onControlsChanged(controls);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.show(Type.all());
            // quickly jump to final state by cancelling it.
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertTrue(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.hide(Type.all());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(navBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(topBar.getType()).isVisible());
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.show(Type.ime());
            mController.cancelExistingAnimation();
            assertTrue(mController.getSourceConsumer(ime.getType()).isVisible());

            mController.hide(Type.ime());
            mController.cancelExistingAnimation();
            assertFalse(mController.getSourceConsumer(ime.getType()).isVisible());
        });
    }
}
