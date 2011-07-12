/*
 * Copyright (C) 2011 The Android Open Source Project
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
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

/**
 * Tests for the various lifecycle events of Animators. This abstract class is subclassed by
 * concrete implementations that provide the actual Animator objects being tested. All of the
 * testing mechanisms are in this class; the subclasses are only responsible for providing
 * the mAnimator object.
 *
 * This test is more complicated than a typical synchronous test because much of the functionality
 * must happen on the UI thread. Some tests do this by using the UiThreadTest annotation to
 * automatically run the whole test on that thread. Other tests must run on the UI thread and also
 * wait for some later event to occur before ending. These tests use a combination of an
 * AbstractFuture mechanism and a delayed action to release that Future later.
 */
public abstract class EventsTest
        extends ActivityInstrumentationTestCase2<BasicAnimatorActivity> {

    private static final int ANIM_DURATION = 400;
    private static final int ANIM_DELAY = 100;
    private static final int ANIM_MID_DURATION = ANIM_DURATION / 2;
    private static final int ANIM_MID_DELAY = ANIM_DELAY / 2;

    private boolean mRunning;  // tracks whether we've started the animator
    private boolean mCanceled; // trackes whether we've canceled the animator
    private Animator.AnimatorListener mFutureListener; // mechanism for delaying the end of the test
    private FutureWaiter mFuture; // Mechanism for waiting for the UI test to complete
    private Animator.AnimatorListener mListener; // Listener that handles/tests the events

    protected Animator mAnimator; // The animator used in the tests. Must be set in subclass
                                  // setup() method prior to calling the superclass setup()

    /**
     * Cancels the given animator. Used to delay cancelation until some later time (after the
     * animator has started playing).
     */
    static class Canceler implements Runnable {
        Animator mAnim;
        public Canceler(Animator anim) {
            mAnim = anim;
        }
        @Override
        public void run() {
            mAnim.cancel();
        }
    };

    /**
     * Releases the given Future object when the listener's end() event is called. Specifically,
     * it releases it after some further delay, to give the test time to do other things right
     * after an animation ends.
     */
    static class FutureReleaseListener extends AnimatorListenerAdapter {
        FutureWaiter mFuture;

        public FutureReleaseListener(FutureWaiter future) {
            mFuture = future;
        }
        @Override
        public void onAnimationEnd(Animator animation) {
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mFuture.release();
                }
            }, ANIM_MID_DURATION);
        }
    };

    public EventsTest() {
        super(BasicAnimatorActivity.class);
    }


    /**
     * Sets up the fields used by each test. Subclasses must override this method to create
     * the protected mAnimator object used in all tests. Overrides must create that animator
     * and then call super.setup(), where further properties are set on that animator.
     * @throws Exception
     */
    @Override
    public void setUp() throws Exception {
        super.setUp();

        // mListener is the main testing mechanism of this file. The asserts of each test
        // are embedded in the listener callbacks that it implements.
        mListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                // This should only be called on an animation that has been started and not
                // yet canceled or ended
                assertFalse(mCanceled);
                assertTrue(mRunning);
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                // This should only be called on an animation that has been started and not
                // yet ended
                assertTrue(mRunning);
                mRunning = false;
                super.onAnimationEnd(animation);
            }
        };

        mAnimator.addListener(mListener);
        mAnimator.setDuration(ANIM_DURATION);

        mFuture = new FutureWaiter();

        mRunning = false;
        mCanceled = false;
    }

    /**
     * Verify that calling cancel on an unstarted animator does nothing.
     */
    @UiThreadTest
    @SmallTest
    public void testCancel() throws Exception {
        mAnimator.cancel();
    }

    /**
     * Verify that calling cancel on a started animator does the right thing.
     */
    @UiThreadTest
    @SmallTest
    public void testStartCancel() throws Exception {
        mRunning = true;
        mAnimator.start();
        mAnimator.cancel();
    }

    /**
     * Same as testStartCancel, but with a startDelayed animator
     */
    @UiThreadTest
    @SmallTest
    public void testStartDelayedCancel() throws Exception {
        mAnimator.setStartDelay(ANIM_DELAY);
        mRunning = true;
        mAnimator.start();
        mAnimator.cancel();
    }

    /**
     * Verify that canceling an animator that is playing does the right thing.
     */
    @MediumTest
    public void testPlayingCancel() throws Exception {
        mFutureListener = new FutureReleaseListener(mFuture);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Handler handler = new Handler();
                    mAnimator.addListener(mFutureListener);
                    mRunning = true;
                    mAnimator.start();
                    handler.postDelayed(new Canceler(mAnimator), ANIM_MID_DURATION);
                } catch (junit.framework.AssertionFailedError e) {
                    mFuture.setException(new RuntimeException(e));
                }
            }
        });
        mFuture.get();
    }

    /**
     * Same as testPlayingCancel, but with a startDelayed animator
     */
    @MediumTest
    public void testPlayingDelayedCancel() throws Exception {
        mAnimator.setStartDelay(ANIM_DELAY);
        mFutureListener = new FutureReleaseListener(mFuture);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Handler handler = new Handler();
                    mAnimator.addListener(mFutureListener);
                    mRunning = true;
                    mAnimator.start();
                    handler.postDelayed(new Canceler(mAnimator), ANIM_MID_DURATION);
                } catch (junit.framework.AssertionFailedError e) {
                    mFuture.setException(new RuntimeException(e));
                }
            }
        });
        mFuture.get();
    }

    /**
     * Verifies that canceling a started animation after it has already been canceled
     * does nothing.
     */
    @MediumTest
    public void testStartDoubleCancel() throws Exception {
        mFutureListener = new FutureReleaseListener(mFuture);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    mRunning = true;
                    mAnimator.start();
                    mAnimator.cancel();
                    mAnimator.cancel();
                    mFuture.release();
                } catch (junit.framework.AssertionFailedError e) {
                    mFuture.setException(new RuntimeException(e));
                }
            }
        });
        mFuture.get();
    }

    /**
     * Same as testStartDoubleCancel, but with a startDelayed animator
     */
    @MediumTest
    public void testStartDelayedDoubleCancel() throws Exception {
        mAnimator.setStartDelay(ANIM_DELAY);
        mFutureListener = new FutureReleaseListener(mFuture);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    mRunning = true;
                    mAnimator.start();
                    mAnimator.cancel();
                    mAnimator.cancel();
                    mFuture.release();
                } catch (junit.framework.AssertionFailedError e) {
                    mFuture.setException(new RuntimeException(e));
                }
            }
        });
        mFuture.get();
    }


}
