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
import static android.view.InsetsSource.ID_IME;
import static android.view.InsetsSourceConsumer.ShowResult.SHOW_IMMEDIATELY;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.statusBars;

import static junit.framework.Assert.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManager.BadTokenException;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.ImeTracker;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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

    private static final int ID_STATUS_BAR = InsetsSource.createId(
            null /* owner */, 0 /* index */, statusBars());

    private InsetsSourceConsumer mConsumer;

    private SurfaceSession mSession = new SurfaceSession();
    private SurfaceControl mLeash;
    private InsetsSource mSpyInsetsSource;
    private boolean mRemoveSurfaceCalled = false;
    private boolean mSurfaceParamsApplied = false;
    private InsetsController mController;
    private InsetsState mState;
    private ViewRootImpl mViewRoot;

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
            mViewRoot = new ViewRootImpl(context, context.getDisplayNoVerify());
            try {
                mViewRoot.setView(new TextView(context), new LayoutParams(), null);
            } catch (BadTokenException e) {
                // activity isn't running, lets ignore BadTokenException.
            }
            mState = new InsetsState();
            mSpyInsetsSource = Mockito.spy(new InsetsSource(ID_STATUS_BAR, statusBars()));
            mState.addSource(mSpyInsetsSource);

            mController = new InsetsController(new ViewRootInsetsControllerHost(mViewRoot)) {
                @Override
                public void applySurfaceParams(
                        final SyncRtSurfaceTransactionApplier.SurfaceParams... params) {
                    mSurfaceParamsApplied = true;
                }
            };
            mConsumer = new InsetsSourceConsumer(ID_STATUS_BAR, statusBars(), mState, mController) {
                @Override
                public void removeSurface() {
                    super.removeSurface();
                    mRemoveSurfaceCalled = true;
                }
            };
        });
        instrumentation.waitForIdleSync();

        mConsumer.setControl(
                new InsetsSourceControl(ID_STATUS_BAR, statusBars(), mLeash,
                        true /* initialVisible */, new Point(), Insets.NONE),
                new int[1], new int[1]);
    }

    @Test
    public void testOnAnimationStateChanged_requestedInvisible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.setRequestedVisibleTypes(0 /* visibleTypes */, mSpyInsetsSource.getType());
            mConsumer.onAnimationStateChanged(false /* running */);
            verify(mSpyInsetsSource).setVisible(eq(false));
        });
    }

    @Test
    public void testOnAnimationStateChanged_requestedVisible() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // Insets source starts out visible
            final int type = mSpyInsetsSource.getType();
            mController.setRequestedVisibleTypes(0 /* visibleTypes */, type);
            mConsumer.onAnimationStateChanged(false /* running */);
            mController.setRequestedVisibleTypes(type, type);
            mConsumer.onAnimationStateChanged(false /* running */);
            verify(mSpyInsetsSource).setVisible(eq(false));
            verify(mSpyInsetsSource).setVisible(eq(true));
        });
    }

    @Test
    public void testPendingStates() {
        InsetsState state = new InsetsState();
        InsetsController controller = new InsetsController(new ViewRootInsetsControllerHost(
                mViewRoot));
        InsetsSourceConsumer consumer = new InsetsSourceConsumer(ID_IME, ime(), state, controller);

        InsetsSource source = new InsetsSource(ID_IME, ime());
        source.setFrame(0, 1, 2, 3);
        consumer.updateSource(new InsetsSource(source), ANIMATION_TYPE_NONE);

        // While we're animating, updates are delayed
        source.setFrame(4, 5, 6, 7);
        consumer.updateSource(new InsetsSource(source), ANIMATION_TYPE_USER);
        assertEquals(new Rect(0, 1, 2, 3), state.peekSource(ID_IME).getFrame());

        // Finish the animation, now the pending frame should be applied
        assertTrue(consumer.onAnimationStateChanged(false /* running */));
        assertEquals(new Rect(4, 5, 6, 7), state.peekSource(ID_IME).getFrame());

        // Animating again, updates are delayed
        source.setFrame(8, 9, 10, 11);
        consumer.updateSource(new InsetsSource(source), ANIMATION_TYPE_USER);
        assertEquals(new Rect(4, 5, 6, 7), state.peekSource(ID_IME).getFrame());

        // Updating with the current frame triggers a different code path, verify this clears
        // the pending 8, 9, 10, 11 frame:
        source.setFrame(4, 5, 6, 7);
        consumer.updateSource(new InsetsSource(source), ANIMATION_TYPE_USER);

        assertFalse(consumer.onAnimationStateChanged(false /* running */));
        assertEquals(new Rect(4, 5, 6, 7), state.peekSource(ID_IME).getFrame());
    }

    @Test
    public void testRestore() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mConsumer.setControl(null, new int[1], new int[1]);
            mSurfaceParamsApplied = false;
            mController.setRequestedVisibleTypes(0 /* visibleTypes */, statusBars());
            assertFalse(mSurfaceParamsApplied);
            int[] hideTypes = new int[1];
            mConsumer.setControl(
                    new InsetsSourceControl(ID_STATUS_BAR, statusBars(), mLeash,
                            true /* initialVisible */, new Point(), Insets.NONE),
                    new int[1], hideTypes);
            assertEquals(statusBars(), hideTypes[0]);
            assertFalse(mRemoveSurfaceCalled);
        });
    }

    @Test
    public void testRestore_noAnimation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            mController.setRequestedVisibleTypes(0 /* visibleTypes */, statusBars());
            mConsumer.setControl(null, new int[1], new int[1]);
            mLeash = new SurfaceControl.Builder(mSession)
                    .setName("testSurface")
                    .build();
            mRemoveSurfaceCalled = false;
            int[] hideTypes = new int[1];
            mConsumer.setControl(
                    new InsetsSourceControl(ID_STATUS_BAR, statusBars(), mLeash,
                            false /* initialVisible */, new Point(), Insets.NONE),
                    new int[1], hideTypes);
            assertTrue(mRemoveSurfaceCalled);
            assertEquals(0, hideTypes[0]);
        });

    }

    @Test
    public void testWontUpdateImeLeashVisibility_whenAnimation() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            InsetsState state = new InsetsState();
            ViewRootInsetsControllerHost host = new ViewRootInsetsControllerHost(mViewRoot);
            InsetsController insetsController = new InsetsController(host, (ic, id, type) -> {
                if (type == ime()) {
                    return new InsetsSourceConsumer(ID_IME, ime(), state, ic) {
                        @Override
                        public int requestShow(boolean fromController,
                                ImeTracker.Token statsToken) {
                            return SHOW_IMMEDIATELY;
                        }
                    };
                }
                return new InsetsSourceConsumer(id, type, ic.getState(), ic);
            }, host.getHandler());
            InsetsSourceConsumer imeConsumer = insetsController.getSourceConsumer(ID_IME, ime());

            // Initial IME insets source control with its leash.
            imeConsumer.setControl(new InsetsSourceControl(ID_IME, ime(), mLeash,
                    false /* initialVisible */, new Point(), Insets.NONE), new int[1], new int[1]);
            mSurfaceParamsApplied = false;

            // Verify when the app requests controlling show IME animation, the IME leash
            // visibility won't be updated when the consumer received the same leash in setControl.
            insetsController.controlWindowInsetsAnimation(ime(), 0L,
                    null /* interpolator */, null /* cancellationSignal */, null /* listener */);
            assertEquals(ANIMATION_TYPE_USER, insetsController.getAnimationType(ime()));
            imeConsumer.setControl(new InsetsSourceControl(ID_IME, ime(), mLeash,
                    true /* initialVisible */, new Point(), Insets.NONE), new int[1], new int[1]);
            assertFalse(mSurfaceParamsApplied);
        });
    }
}
