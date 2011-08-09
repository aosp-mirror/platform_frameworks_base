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
import android.view.ViewPropertyAnimator;
import android.widget.Button;
import com.android.frameworks.coretests.R;

import java.util.concurrent.TimeUnit;

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
public abstract class ViewPropertyAnimatorTest
        extends ActivityInstrumentationTestCase2<BasicAnimatorActivity> {

    protected static final int ANIM_DURATION = 400;
    protected static final int ANIM_DELAY = 100;
    protected static final int ANIM_MID_DURATION = ANIM_DURATION / 2;
    protected static final int ANIM_MID_DELAY = ANIM_DELAY / 2;
    protected static final int FUTURE_RELEASE_DELAY = 50;

    private boolean mStarted;  // tracks whether we've received the onAnimationStart() callback
    protected boolean mRunning;  // tracks whether we've started the animator
    private boolean mCanceled; // trackes whether we've canceled the animator
    protected Animator.AnimatorListener mFutureListener; // mechanism for delaying the end of the test
    protected FutureWaiter mFuture; // Mechanism for waiting for the UI test to complete
    private Animator.AnimatorListener mListener; // Listener that handles/tests the events

    protected ViewPropertyAnimator mAnimator; // The animator used in the tests. Must be set in subclass
                                  // setup() method prior to calling the superclass setup()

    /**
     * Cancels the given animator. Used to delay cancellation until some later time (after the
     * animator has started playing).
     */
    protected static class Canceler implements Runnable {
        ViewPropertyAnimator mAnim;
        FutureWaiter mFuture;
        public Canceler(ViewPropertyAnimator anim, FutureWaiter future) {
            mAnim = anim;
            mFuture = future;
        }
        @Override
        public void run() {
            try {
                mAnim.cancel();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        }
    };

    /**
     * Timeout length, based on when the animation should reasonably be complete.
     */
    protected long getTimeout() {
        return ANIM_DURATION + ANIM_DELAY + FUTURE_RELEASE_DELAY;
    }

    /**
     * Releases the given Future object when the listener's end() event is called. Specifically,
     * it releases it after some further delay, to give the test time to do other things right
     * after an animation ends.
     */
    protected static class FutureReleaseListener extends AnimatorListenerAdapter {
        FutureWaiter mFuture;

        public FutureReleaseListener(FutureWaiter future) {
            mFuture = future;
        }

        /**
         * Variant constructor that auto-releases the FutureWaiter after the specified timeout.
         * @param future
         * @param timeout
         */
        public FutureReleaseListener(FutureWaiter future, long timeout) {
            mFuture = future;
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mFuture.release();
                }
            }, timeout);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mFuture.release();
                }
            }, FUTURE_RELEASE_DELAY);
        }
    };

    public ViewPropertyAnimatorTest() {
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
        final BasicAnimatorActivity activity = getActivity();
        Button button = (Button) activity.findViewById(R.id.animatingButton);

        mAnimator = button.animate().x(100).y(100);

        super.setUp();

        // mListener is the main testing mechanism of this file. The asserts of each test
        // are embedded in the listener callbacks that it implements.
        mListener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                // This should only be called on an animation that has not yet been started
                assertFalse(mStarted);
                assertTrue(mRunning);
                mStarted = true;
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                // This should only be called on an animation that has been started and not
                // yet canceled or ended
                assertFalse(mCanceled);
                assertTrue(mRunning);
                assertTrue(mStarted);
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                // This should only be called on an animation that has been started and not
                // yet ended
                assertTrue(mRunning);
                assertTrue(mStarted);
                mRunning = false;
                mStarted = false;
                super.onAnimationEnd(animation);
            }
        };

        mAnimator.setListener(mListener);
        mAnimator.setDuration(ANIM_DURATION);

        mFuture = new FutureWaiter();

        mRunning = false;
        mCanceled = false;
        mStarted = false;
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
        mFutureListener = new FutureReleaseListener(mFuture);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    mRunning = true;
                    mAnimator.start();
                    mAnimator.cancel();
                    mFuture.release();
                } catch (junit.framework.AssertionFailedError e) {
                    mFuture.setException(new RuntimeException(e));
                }
            }
        });
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * Same as testStartCancel, but with a startDelayed animator
     */
    @SmallTest
    public void testStartDelayedCancel() throws Exception {
        mFutureListener = new FutureReleaseListener(mFuture);
        mAnimator.setStartDelay(ANIM_DELAY);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    mRunning = true;
                    mAnimator.start();
                    mAnimator.cancel();
                    mFuture.release();
                } catch (junit.framework.AssertionFailedError e) {
                    mFuture.setException(new RuntimeException(e));
                }
            }
        });
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
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
                    mAnimator.setListener(mFutureListener);
                    mRunning = true;
                    mAnimator.start();
                    handler.postDelayed(new Canceler(mAnimator, mFuture), ANIM_MID_DURATION);
                } catch (junit.framework.AssertionFailedError e) {
                    mFuture.setException(new RuntimeException(e));
                }
            }
        });
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
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
                    mAnimator.setListener(mFutureListener);
                    mRunning = true;
                    mAnimator.start();
                    handler.postDelayed(new Canceler(mAnimator, mFuture), ANIM_MID_DURATION);
                } catch (junit.framework.AssertionFailedError e) {
                    mFuture.setException(new RuntimeException(e));
                }
            }
        });
        mFuture.get(getTimeout(),  TimeUnit.MILLISECONDS);
    }

    /**
     * Same as testPlayingDelayedCancel, but cancel during the startDelay period
     */
    @MediumTest
    public void testPlayingDelayedCancelMidDelay() throws Exception {
        mAnimator.setStartDelay(ANIM_DELAY);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Set the listener to automatically timeout after an uncanceled animation
                    // would have finished. This tests to make sure that we're not calling
                    // the listeners with cancel/end callbacks since they won't be called
                    // with the start event.
                    mFutureListener = new FutureReleaseListener(mFuture, getTimeout());
                    Handler handler = new Handler();
                    mRunning = true;
                    mAnimator.start();
                    handler.postDelayed(new Canceler(mAnimator, mFuture), ANIM_MID_DELAY);
                } catch (junit.framework.AssertionFailedError e) {
                    mFuture.setException(new RuntimeException(e));
                }
            }
        });
        mFuture.get(getTimeout() + 100,  TimeUnit.MILLISECONDS);
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
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
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
        mFuture.get(getTimeout(),  TimeUnit.MILLISECONDS);
     }

}
