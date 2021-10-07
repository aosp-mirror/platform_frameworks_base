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

import static android.view.InsetsController.ANIMATION_TYPE_NONE;
import static android.view.InsetsController.ANIMATION_TYPE_USER;
import static android.view.InsetsState.ITYPE_IME;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowInsets.Type.statusBars;

import static junit.framework.Assert.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl.Transaction;
import android.view.WindowManager.BadTokenException;
import android.view.WindowManager.LayoutParams;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link InsetsSourceConsumer}.
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:InsetsSourceConsumerTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class InsetsSourceConsumerTest {

    private InsetsSourceConsumer mConsumer;

    private SurfaceSession mSession = new SurfaceSession();
    private SurfaceControl mLeash;
    @Mock Transaction mMockTransaction;
    private InsetsSource mSpyInsetsSource;
    private boolean mRemoveSurfaceCalled = false;
    private InsetsController mController;
    private InsetsState mState;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mLeash = new SurfaceControl.Builder(mSession)
                .setName("testSurface")
                .build();
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            final Context context = instrumentation.getTargetContext();
            // cannot mock ViewRootImpl since it's final.
            final ViewRootImpl viewRootImpl = new ViewRootImpl(context,
                    context.getDisplayNoVerify());
            try {
                viewRootImpl.setView(new TextView(context), new LayoutParams(), null);
            } catch (BadTokenException e) {
                // activity isn't running, lets ignore BadTokenException.
            }
            mState = new InsetsState();
            mSpyInsetsSource = Mockito.spy(new InsetsSource(ITYPE_STATUS_BAR));
            mState.addSource(mSpyInsetsSource);

            mController = new InsetsController(new ViewRootInsetsControllerHost(viewRootImpl));
            mConsumer = new InsetsSourceConsumer(ITYPE_STATUS_BAR, mState,
                    () -> mMockTransaction, mController) {
                @Override
                public void removeSurface() {
                    super.removeSurface();
                    mRemoveSurfaceCalled = true;
                }
            };
        });
        instrumentation.waitForIdleSync();

        mConsumer.setControl(
                new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point(), Insets.NONE),
                new int[1], new int[1]);
    }

    @Test
    public void testHide() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mConsumer.hide();
            assertFalse("Consumer should not be visible", mConsumer.isRequestedVisible());
            verify(mSpyInsetsSource).setVisible(eq(false));
        });

    }

    @Test
    public void testShow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Insets source starts out visible
            mConsumer.hide();
            mConsumer.show(false /* fromIme */);
            assertTrue("Consumer should be visible", mConsumer.isRequestedVisible());
            verify(mSpyInsetsSource).setVisible(eq(false));
            verify(mSpyInsetsSource).setVisible(eq(true));
        });

    }

    @Test
    public void testPendingStates() {
        InsetsState state = new InsetsState();
        InsetsController controller = mock(InsetsController.class);
        InsetsSourceConsumer consumer = new InsetsSourceConsumer(
                ITYPE_IME, state, null, controller);

        InsetsSource source = new InsetsSource(ITYPE_IME);
        source.setFrame(0, 1, 2, 3);
        consumer.updateSource(new InsetsSource(source), ANIMATION_TYPE_NONE);

        // While we're animating, updates are delayed
        source.setFrame(4, 5, 6, 7);
        consumer.updateSource(new InsetsSource(source), ANIMATION_TYPE_USER);
        assertEquals(new Rect(0, 1, 2, 3), state.peekSource(ITYPE_IME).getFrame());

        // Finish the animation, now the pending frame should be applied
        assertTrue(consumer.notifyAnimationFinished());
        assertEquals(new Rect(4, 5, 6, 7), state.peekSource(ITYPE_IME).getFrame());

        // Animating again, updates are delayed
        source.setFrame(8, 9, 10, 11);
        consumer.updateSource(new InsetsSource(source), ANIMATION_TYPE_USER);
        assertEquals(new Rect(4, 5, 6, 7), state.peekSource(ITYPE_IME).getFrame());

        // Updating with the current frame triggers a different code path, verify this clears
        // the pending 8, 9, 10, 11 frame:
        source.setFrame(4, 5, 6, 7);
        consumer.updateSource(new InsetsSource(source), ANIMATION_TYPE_USER);

        assertFalse(consumer.notifyAnimationFinished());
        assertEquals(new Rect(4, 5, 6, 7), state.peekSource(ITYPE_IME).getFrame());
    }

    @Test
    public void testRestore() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mConsumer.setControl(null, new int[1], new int[1]);
            reset(mMockTransaction);
            mConsumer.hide();
            verifyZeroInteractions(mMockTransaction);
            int[] hideTypes = new int[1];
            mConsumer.setControl(
                    new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point(), Insets.NONE),
                    new int[1], hideTypes);
            assertEquals(statusBars(), hideTypes[0]);
            assertFalse(mRemoveSurfaceCalled);
        });
    }

    @Test
    public void testRestore_noAnimation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mConsumer.hide();
            mController.onStateChanged(mState);
            mConsumer.setControl(null, new int[1], new int[1]);
            reset(mMockTransaction);
            verifyZeroInteractions(mMockTransaction);
            mRemoveSurfaceCalled = false;
            int[] hideTypes = new int[1];
            mConsumer.setControl(
                    new InsetsSourceControl(ITYPE_STATUS_BAR, mLeash, new Point(), Insets.NONE),
                    new int[1], hideTypes);
            assertTrue(mRemoveSurfaceCalled);
            assertEquals(0, hideTypes[0]);
        });

    }
}
