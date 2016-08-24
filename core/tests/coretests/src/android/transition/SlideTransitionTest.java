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
 * limitations under the License
 */

package android.transition;

import android.animation.AnimatorSetActivity;
import android.app.Activity;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.android.frameworks.coretests.R;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


public class SlideTransitionTest extends ActivityInstrumentationTestCase2<AnimatorSetActivity> {

    Activity mActivity;

    public SlideTransitionTest() {
        super(AnimatorSetActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        mActivity = getActivity();
    }

    @SmallTest
    public void testShortSlide() throws Throwable {
        final float slideFraction = 0.5f;
        final View square1 = mActivity.findViewById(R.id.square1);
        final View sceneRoot = mActivity.findViewById(R.id.container);
        final SlideTranslationValueRatchet ratchet = new SlideTranslationValueRatchet(square1);
        square1.getViewTreeObserver().addOnPreDrawListener(ratchet);

        final Slide slideOut = new Slide(Gravity.BOTTOM);
        final float finalOffsetOut = sceneRoot.getHeight() * slideFraction;
        slideOut.setSlideFraction(slideFraction);
        TransitionLatch latch = setVisibilityInTransition(slideOut, R.id.square1, View.INVISIBLE);
        assertTrue(latch.startLatch.await(200, TimeUnit.MILLISECONDS));
        assertEquals(0f, square1.getTranslationY(), 0.1f);
        assertEquals(View.VISIBLE, square1.getVisibility());
        Thread.sleep(100);
        assertFalse(square1.getTranslationY() < 0.1
                || square1.getTranslationY() > finalOffsetOut - 0.1);
        assertTrue(latch.endLatch.await(400, TimeUnit.MILLISECONDS));
        // Give this 20% slop in case some frames get dropped.
        assertTrue(finalOffsetOut * 0.8 < ratchet.maxY);
        assertTrue(finalOffsetOut + 0.1 > ratchet.maxY);
        assertEquals(View.INVISIBLE, square1.getVisibility());

        ratchet.reset();
        final Slide slideIn = new Slide(Gravity.BOTTOM);
        final float initialOffsetIn = sceneRoot.getHeight() * slideFraction;
        slideIn.setSlideFraction(slideFraction);
        latch = setVisibilityInTransition(slideIn, R.id.square1, View.VISIBLE);
        assertTrue(latch.startLatch.await(200, TimeUnit.MILLISECONDS));
        assertEquals(initialOffsetIn, square1.getTranslationY(), 0.1f);
        assertEquals(View.VISIBLE, square1.getVisibility());
        Thread.sleep(100);
        assertFalse(square1.getTranslationY() < 0.1
                || square1.getTranslationY() > initialOffsetIn - 0.1);
        assertTrue(latch.endLatch.await(400, TimeUnit.MILLISECONDS));
        assertEquals(0f, ratchet.minY, 0.1);
        assertEquals(0f, square1.getTranslationY(), 0.1);
        assertEquals(View.VISIBLE, square1.getVisibility());

        square1.getViewTreeObserver().removeOnPreDrawListener(ratchet);
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

    public static class TransitionLatch implements Transition.TransitionListener {
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

    private static class SlideTranslationValueRatchet
            implements ViewTreeObserver.OnPreDrawListener {

        private final View mView;
        private boolean mInitialized;
        public float minX = Float.NaN;
        public float minY = Float.NaN;
        public float maxX = Float.NaN;
        public float maxY = Float.NaN;

        public SlideTranslationValueRatchet(View view) {
            mView = view;
        }

        public void reset() {
            minX = minY = maxX = maxY = Float.NaN;
            mInitialized = false;
        }

        @Override
        public boolean onPreDraw() {
            if (!mInitialized) {
                minX = maxX = mView.getTranslationX();
                minY = maxY = mView.getTranslationY();
                mInitialized = true;
            } else {
                minX = Math.min(minX, mView.getTranslationX());
                minY = Math.min(minY, mView.getTranslationY());
                maxX = Math.max(maxX, mView.getTranslationX());
                maxY = Math.max(maxY, mView.getTranslationY());
            }
            return true;
        }
    }
}
