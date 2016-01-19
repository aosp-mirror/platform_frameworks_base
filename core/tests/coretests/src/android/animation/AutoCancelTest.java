/*
 * Copyright (C) 2013 The Android Open Source Project
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
package android.animation;

import android.os.Handler;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public class AutoCancelTest extends ActivityInstrumentationTestCase2<BasicAnimatorActivity> {

    boolean mAnimX1Canceled = false;
    boolean mAnimXY1Canceled = false;
    boolean mAnimX2Canceled = false;
    boolean mAnimXY2Canceled = false;

    private static final long START_DELAY = 100;
    private static final long DELAYED_START_DURATION = 200;
    private static final long FUTURE_TIMEOUT = 1000;

    HashMap<Animator, Boolean> mCanceledMap = new HashMap<Animator, Boolean>();

    public AutoCancelTest() {
        super(BasicAnimatorActivity.class);
    }

    ObjectAnimator setupAnimator(long startDelay, String... properties) {
        ObjectAnimator returnVal;
        if (properties.length == 1) {
            returnVal = ObjectAnimator.ofFloat(this, properties[0], 0, 1);
        } else {
            PropertyValuesHolder[] pvhArray = new PropertyValuesHolder[properties.length];
            for (int i = 0; i < properties.length; i++) {
                pvhArray[i] = PropertyValuesHolder.ofFloat(properties[i], 0, 1);
            }
            returnVal = ObjectAnimator.ofPropertyValuesHolder(this, pvhArray);
        }
        returnVal.setAutoCancel(true);
        returnVal.setStartDelay(startDelay);
        returnVal.addListener(mCanceledListener);
        return returnVal;
    }

    private void setupAnimators(long startDelay, boolean startLater, final FutureWaiter future)
    throws Exception {
        // Animators to be auto-canceled
        final ObjectAnimator animX1 = setupAnimator(startDelay, "x");
        final ObjectAnimator animY1 = setupAnimator(startDelay, "y");
        final ObjectAnimator animXY1 = setupAnimator(startDelay, "x", "y");
        final ObjectAnimator animXZ1 = setupAnimator(startDelay, "x", "z");

        animX1.start();
        animY1.start();
        animXY1.start();
        animXZ1.start();

        final ObjectAnimator animX2 = setupAnimator(0, "x");
        animX2.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                // We expect only animX1 to be canceled at this point
                if (mCanceledMap.get(animX1) == null ||
                        mCanceledMap.get(animX1) != true ||
                        mCanceledMap.get(animY1) != null ||
                        mCanceledMap.get(animXY1) != null ||
                        mCanceledMap.get(animXZ1) != null) {
                    future.set(false);
                }
            }
        });

        final ObjectAnimator animXY2 = setupAnimator(0, "x", "y");
        animXY2.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                // We expect only animXY1 to be canceled at this point
                if (mCanceledMap.get(animXY1) == null ||
                        mCanceledMap.get(animXY1) != true ||
                        mCanceledMap.get(animY1) != null ||
                        mCanceledMap.get(animXZ1) != null) {
                    future.set(false);
                }

            }

            @Override
            public void onAnimationEnd(Animator animation) {
                // Release future if not done already via failures during start
                future.release();
            }
        });

        if (startLater) {
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    animX2.start();
                    animXY2.start();
                }
            }, DELAYED_START_DURATION);
        } else {
            animX2.start();
            animXY2.start();
        }
    }

    @SmallTest
    public void testAutoCancel() throws Exception {
        final FutureWaiter future = new FutureWaiter();
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    setupAnimators(0, false, future);
                } catch (Exception e) {
                    future.setException(e);
                }
            }
        });
        assertTrue(future.get(FUTURE_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    @SmallTest
    public void testAutoCancelDelayed() throws Exception {
        final FutureWaiter future = new FutureWaiter();
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    setupAnimators(START_DELAY, false, future);
                } catch (Exception e) {
                    future.setException(e);
                }
            }
        });
        assertTrue(future.get(FUTURE_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    @SmallTest
    public void testAutoCancelTestLater() throws Exception {
        final FutureWaiter future = new FutureWaiter();
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    setupAnimators(0, true, future);
                } catch (Exception e) {
                    future.setException(e);
                }
            }
        });
        assertTrue(future.get(FUTURE_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    @SmallTest
    public void testAutoCancelDelayedTestLater() throws Exception {
        final FutureWaiter future = new FutureWaiter();
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    setupAnimators(START_DELAY, true, future);
                } catch (Exception e) {
                    future.setException(e);
                }
            }
        });
        assertTrue(future.get(FUTURE_TIMEOUT, TimeUnit.MILLISECONDS));
    }

    private AnimatorListenerAdapter mCanceledListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationCancel(Animator animation) {
            mCanceledMap.put(animation, true);
        }
    };

    public void setX(float x) {}

    public void setY(float y) {}

    public void setZ(float z) {}
}


