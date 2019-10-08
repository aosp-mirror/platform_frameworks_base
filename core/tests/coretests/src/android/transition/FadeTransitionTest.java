/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.transition;

import android.animation.Animator;
import android.animation.AnimatorSetActivity;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.test.ActivityInstrumentationTestCase2;
import android.transition.Transition.TransitionListener;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.test.filters.SmallTest;

import com.android.frameworks.coretests.R;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class FadeTransitionTest extends ActivityInstrumentationTestCase2<AnimatorSetActivity> {
    Activity mActivity;
    public FadeTransitionTest() {
        super(AnimatorSetActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        mActivity = getActivity();
    }

    @SmallTest
    public void testFadeOutAndIn() throws Throwable {
        View square1 = mActivity.findViewById(R.id.square1);
        Fade fadeOut = new Fade(Fade.MODE_OUT);
        TransitionLatch latch = setVisibilityInTransition(fadeOut, R.id.square1, View.INVISIBLE);
        assertTrue(latch.startLatch.await(400, TimeUnit.MILLISECONDS));
        assertEquals(View.VISIBLE, square1.getVisibility());
        waitForAnimation();
        assertFalse(square1.getTransitionAlpha() == 0 || square1.getTransitionAlpha() == 1);
        assertTrue(latch.endLatch.await(800, TimeUnit.MILLISECONDS));
        assertEquals(1.0f, square1.getTransitionAlpha());
        assertEquals(View.INVISIBLE, square1.getVisibility());

        Fade fadeIn = new Fade(Fade.MODE_IN);
        latch = setVisibilityInTransition(fadeIn, R.id.square1, View.VISIBLE);
        assertTrue(latch.startLatch.await(400, TimeUnit.MILLISECONDS));
        assertEquals(View.VISIBLE, square1.getVisibility());
        waitForAnimation();
        final float transitionAlpha = square1.getTransitionAlpha();
        assertTrue("expecting transitionAlpha to be between 0 and 1. Was " + transitionAlpha,
                transitionAlpha > 0 && transitionAlpha < 1);
        assertTrue(latch.endLatch.await(800, TimeUnit.MILLISECONDS));
        assertEquals(1.0f, square1.getTransitionAlpha());
        assertEquals(View.VISIBLE, square1.getVisibility());
    }

    @SmallTest
    public void testFadeOutInterrupt() throws Throwable {
        View square1 = mActivity.findViewById(R.id.square1);
        Fade fadeOut = new Fade(Fade.MODE_OUT);
        FadeValueCheck fadeOutValueCheck = new FadeValueCheck(square1);
        fadeOut.addListener(fadeOutValueCheck);
        TransitionLatch outLatch = setVisibilityInTransition(fadeOut, R.id.square1, View.INVISIBLE);
        assertTrue(outLatch.startLatch.await(400, TimeUnit.MILLISECONDS));
        waitForAnimation();

        Fade fadeIn = new Fade(Fade.MODE_IN);
        FadeValueCheck fadeInValueCheck = new FadeValueCheck(square1);
        fadeIn.addListener(fadeInValueCheck);
        TransitionLatch inLatch = setVisibilityInTransition(fadeIn, R.id.square1, View.VISIBLE);
        assertTrue(inLatch.startLatch.await(400, TimeUnit.MILLISECONDS));

        assertEquals(fadeOutValueCheck.pauseTransitionAlpha, fadeInValueCheck.startTransitionAlpha);
        assertTrue("expecting transitionAlpha to be between 0 and 1. Was " +
                fadeOutValueCheck.pauseTransitionAlpha,
                fadeOutValueCheck.pauseTransitionAlpha > 0 &&
                        fadeOutValueCheck.pauseTransitionAlpha < 1);

        assertTrue(inLatch.endLatch.await(800, TimeUnit.MILLISECONDS));
        assertEquals(1.0f, square1.getTransitionAlpha());
        assertEquals(View.VISIBLE, square1.getVisibility());
    }

    @SmallTest
    public void testFadeInInterrupt() throws Throwable {
        final View square1 = mActivity.findViewById(R.id.square1);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                square1.setVisibility(View.INVISIBLE);
            }
        });
        Fade fadeIn = new Fade(Fade.MODE_IN);
        FadeValueCheck fadeInValueCheck = new FadeValueCheck(square1);
        fadeIn.addListener(fadeInValueCheck);
        TransitionLatch inLatch = setVisibilityInTransition(fadeIn, R.id.square1, View.VISIBLE);
        assertTrue(inLatch.startLatch.await(400, TimeUnit.MILLISECONDS));
        waitForAnimation();

        Fade fadeOut = new Fade(Fade.MODE_OUT);
        FadeValueCheck fadeOutValueCheck = new FadeValueCheck(square1);
        fadeOut.addListener(fadeOutValueCheck);
        TransitionLatch outLatch = setVisibilityInTransition(fadeOut, R.id.square1, View.INVISIBLE);
        assertTrue(outLatch.startLatch.await(400, TimeUnit.MILLISECONDS));

        assertEquals(fadeInValueCheck.pauseTransitionAlpha, fadeOutValueCheck.startTransitionAlpha);
        assertTrue("expecting transitionAlpha to be between 0 and 1. Was " +
                        fadeInValueCheck.pauseTransitionAlpha,
                fadeInValueCheck.pauseTransitionAlpha > 0 &&
                        fadeInValueCheck.pauseTransitionAlpha < 1);

        assertTrue(outLatch.endLatch.await(800, TimeUnit.MILLISECONDS));
        assertEquals(1.0f, square1.getTransitionAlpha());
        assertEquals(View.INVISIBLE, square1.getVisibility());
    }

    @SmallTest
    public void testSnapshotView() throws Throwable {
        final View square1 = mActivity.findViewById(R.id.square1);

        final CountDownLatch disappearCalled = new CountDownLatch(1);
        final Fade fadeOut = new Fade(Fade.MODE_OUT) {
            @Override
            public Animator onDisappear(ViewGroup sceneRoot, View view,
                    TransitionValues startValues,
                    TransitionValues endValues) {
                assertNotSame(square1, view);
                assertTrue(view instanceof ImageView);
                ImageView imageView = (ImageView) view;
                BitmapDrawable background = (BitmapDrawable) imageView.getDrawable();
                Bitmap bitmap = background.getBitmap();
                assertEquals(Bitmap.Config.HARDWARE, bitmap.getConfig());
                Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                assertEquals(0xFFFF0000, copy.getPixel(1, 1));
                disappearCalled.countDown();
                return super.onDisappear(sceneRoot, view, startValues, endValues);
            }
        };

        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                ViewGroup container = mActivity.findViewById(R.id.container);
                TransitionManager.beginDelayedTransition(container, fadeOut);
                container.removeView(square1);
                FrameLayout parent = new FrameLayout(mActivity);
                parent.addView(square1);
            }
        });

        assertTrue(disappearCalled.await(1, TimeUnit.SECONDS));
    }

    public TransitionLatch setVisibilityInTransition(final Transition transition, int viewId,
            final int visibility) throws Throwable {
        final ViewGroup sceneRoot = (ViewGroup) mActivity.findViewById(R.id.container);
        final View view = sceneRoot.findViewById(viewId);
        TransitionLatch latch = new TransitionLatch();
        transition.addListener(latch);
        runTestOnUiThread(new Runnable() {
            @Override
            public void run() {
                TransitionManager.beginDelayedTransition(sceneRoot, transition);
                view.setVisibility(visibility);
            }
        });
        return latch;
    }

    /**
     * Waits for two animation frames to ensure animation values change.
     */
    private void waitForAnimation() throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(2);
        mActivity.getWindow().getDecorView().postOnAnimation(new Runnable() {
            @Override
            public void run() {
                latch.countDown();
                if (latch.getCount() > 0) {
                    mActivity.getWindow().getDecorView().postOnAnimation(this);
                }
            }
        });
        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    public static class TransitionLatch implements TransitionListener {
        public CountDownLatch startLatch = new CountDownLatch(1);
        public CountDownLatch endLatch = new CountDownLatch(1);
        public CountDownLatch cancelLatch = new CountDownLatch(1);
        public CountDownLatch pauseLatch = new CountDownLatch(1);
        public CountDownLatch resumeLatch = new CountDownLatch(1);

        @Override
        public void onTransitionStart(Transition transition) {
            startLatch.countDown();
        }

        @Override
        public void onTransitionEnd(Transition transition) {
            endLatch.countDown();
            transition.removeListener(this);
        }

        @Override
        public void onTransitionCancel(Transition transition) {
            cancelLatch.countDown();
        }

        @Override
        public void onTransitionPause(Transition transition) {
            pauseLatch.countDown();
        }

        @Override
        public void onTransitionResume(Transition transition) {
            resumeLatch.countDown();
        }
    }

    private static class FadeValueCheck extends TransitionListenerAdapter {
        public float startTransitionAlpha;
        public float pauseTransitionAlpha;
        private final View mView;

        public FadeValueCheck(View view) {
            mView = view;
        }
        @Override
        public void onTransitionStart(Transition transition) {
            startTransitionAlpha = mView.getTransitionAlpha();
        }

        @Override
        public void onTransitionPause(Transition transition) {
            pauseTransitionAlpha = mView.getTransitionAlpha();
        }
    }
}
