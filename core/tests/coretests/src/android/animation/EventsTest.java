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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.annotation.CallSuper;
import android.os.Handler;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
public abstract class EventsTest {

    @Rule
    public final ActivityTestRule<BasicAnimatorActivity> mActivityRule =
            new ActivityTestRule<>(BasicAnimatorActivity.class);

    protected static final int ANIM_DURATION = 400;
    protected static final int ANIM_DELAY = 100;
    protected static final int ANIM_MID_DURATION = ANIM_DURATION / 2;
    protected static final int ANIM_MID_DELAY = ANIM_DELAY / 2;
    protected static final int ANIM_PAUSE_DURATION = ANIM_DELAY;
    protected static final int ANIM_PAUSE_DELAY = ANIM_DELAY / 2;
    protected static final int FUTURE_RELEASE_DELAY = 50;
    protected static final int ANIM_FULL_DURATION_SLOP = 100;

    private boolean mStarted;  // tracks whether we've received the onAnimationStart() callback
    protected boolean mRunning;  // tracks whether we've started the animator
    private boolean mCanceled; // tracks whether we've canceled the animator
    protected Animator.AnimatorListener mFutureListener; // mechanism for delaying end of the test
    protected FutureWaiter mFuture; // Mechanism for waiting for the UI test to complete

    protected Animator mAnimator; // The animator used in the tests. Must be set in subclass
                                  // setup() method prior to calling the superclass setup()

    /**
     * Cancels the given animator. Used to delay cancellation until some later time (after the
     * animator has started playing).
     */
    protected static class Canceler implements Runnable {
        Animator mAnim;
        FutureWaiter mFuture;

        public Canceler(Animator anim, FutureWaiter future) {
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
    }

    /**
     * Timeout length, based on when the animation should reasonably be complete.
     */
    protected long getTimeout() {
        return ANIM_DURATION + ANIM_DELAY + FUTURE_RELEASE_DELAY;
    }

    /**
     * Ends the given animator. Used to delay ending until some later time (after the
     * animator has started playing).
     */
    static class Ender implements Runnable {
        Animator mAnim;
        FutureWaiter mFuture;

        public Ender(Animator anim, FutureWaiter future) {
            mAnim = anim;
            mFuture = future;
        }

        @Override
        public void run() {
            try {
                mAnim.end();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        }
    }

    /**
     * Pauses the given animator. Used to delay pausing until some later time (after the
     * animator has started playing).
     */
    static class Pauser implements Runnable {
        Animator mAnim;
        FutureWaiter mFuture;

        public Pauser(Animator anim, FutureWaiter future) {
            mAnim = anim;
            mFuture = future;
        }

        @Override
        public void run() {
            try {
                mAnim.pause();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        }
    }

    /**
     * Resumes the given animator. Used to delay resuming until some later time (after the
     * animator has paused for some duration).
     */
    static class Resumer implements Runnable {
        Animator mAnim;
        FutureWaiter mFuture;

        public Resumer(Animator anim, FutureWaiter future) {
            mAnim = anim;
            mFuture = future;
        }

        @Override
        public void run() {
            try {
                mAnim.resume();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        }
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
            handler.postDelayed(mFuture::release, timeout);
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            Handler handler = new Handler();
            handler.postDelayed(mFuture::release, FUTURE_RELEASE_DELAY);
        }
    }

    /**
     * Sets up the fields used by each test. Subclasses must override this method to create
     * the protected mAnimator object used in all tests. Overrides must create that animator
     * and then call super.setup(), where further properties are set on that animator.
     * @throws Exception
     */
    @CallSuper
    @Before
    public void setUp() throws Exception {
        // mListener is the main testing mechanism of this file. The asserts of each test
        // are embedded in the listener callbacks that it implements.
        final Animator.AnimatorListener listener = new AnimatorListenerAdapter() {
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
                assertTrue(mRunning || mStarted);
                mCanceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                // This should only be called on an animation that has been started and not
                // yet ended
                assertTrue(mRunning || mStarted);
                mRunning = false;
                mStarted = false;
                super.onAnimationEnd(animation);
            }
        };

        mAnimator.addListener(listener);
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
    @Test
    public void testCancel() throws Exception {
        mAnimator.cancel();
    }

    /**
     * Verify that calling end on an unstarted animator starts/ends an animator.
     */
    @UiThreadTest
    @SmallTest
    @Test
    public void testEnd() throws Exception {
        mRunning = true; // end() implicitly starts an unstarted animator
        mAnimator.end();
    }

    /**
     * Verify that calling cancel on a started animator does the right thing.
     */
    @UiThreadTest
    @SmallTest
    @Test
    public void testStartCancel() throws Throwable {
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                mRunning = true;
                mAnimator.start();
                mAnimator.cancel();
                mFuture.release();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * Verify that calling end on a started animator does the right thing.
     */
    @UiThreadTest
    @SmallTest
    @Test
    public void testStartEnd() throws Throwable {
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                mRunning = true;
                mAnimator.start();
                mAnimator.end();
                mFuture.release();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * Same as testStartCancel, but with a startDelayed animator
     */
    @SmallTest
    @Test
    public void testStartDelayedCancel() throws Throwable {
        mFutureListener = new FutureReleaseListener(mFuture);
        mAnimator.setStartDelay(ANIM_DELAY);
        mActivityRule.runOnUiThread(() -> {
            try {
                mRunning = true;
                mAnimator.start();
                mAnimator.cancel();
                mFuture.release();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * Same as testStartEnd, but with a startDelayed animator
     */
    @SmallTest
    @Test
    public void testStartDelayedEnd() throws Throwable {
        mFutureListener = new FutureReleaseListener(mFuture);
        mAnimator.setStartDelay(ANIM_DELAY);
        mActivityRule.runOnUiThread(() -> {
            try {
                mRunning = true;
                mAnimator.start();
                mAnimator.end();
                mFuture.release();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * Verify that canceling an animator that is playing does the right thing.
     */
    @MediumTest
    @Test
    public void testPlayingCancel() throws Throwable {
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                Handler handler = new Handler();
                mAnimator.addListener(mFutureListener);
                mRunning = true;
                mAnimator.start();
                handler.postDelayed(new Canceler(mAnimator, mFuture), ANIM_MID_DURATION);
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * Verify that ending an animator that is playing does the right thing.
     */
    @MediumTest
    @Test
    public void testPlayingEnd() throws Throwable {
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                Handler handler = new Handler();
                mAnimator.addListener(mFutureListener);
                mRunning = true;
                mAnimator.start();
                handler.postDelayed(new Ender(mAnimator, mFuture), ANIM_MID_DURATION);
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
    }

    /**
     * Same as testPlayingCancel, but with a startDelayed animator
     */
    @MediumTest
    @Test
    public void testPlayingDelayedCancel() throws Throwable {
        mAnimator.setStartDelay(ANIM_DELAY);
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                Handler handler = new Handler();
                mAnimator.addListener(mFutureListener);
                mRunning = true;
                mAnimator.start();
                handler.postDelayed(new Canceler(mAnimator, mFuture), ANIM_MID_DURATION);
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(),  TimeUnit.MILLISECONDS);
    }

    /**
     * Same as testPlayingEnd, but with a startDelayed animator
     */
    @MediumTest
    @Test
    public void testPlayingDelayedEnd() throws Throwable {
        mAnimator.setStartDelay(ANIM_DELAY);
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                Handler handler = new Handler();
                mAnimator.addListener(mFutureListener);
                mRunning = true;
                mAnimator.start();
                handler.postDelayed(new Ender(mAnimator, mFuture), ANIM_MID_DURATION);
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(),  TimeUnit.MILLISECONDS);
    }

    /**
     * Same as testPlayingDelayedCancel, but cancel during the startDelay period
     */
    @MediumTest
    @Test
    public void testPlayingDelayedCancelMidDelay() throws Throwable {
        mAnimator.setStartDelay(ANIM_DELAY);
        mActivityRule.runOnUiThread(() -> {
            try {
                // Set the listener to automatically timeout after an uncanceled animation would
                // have finished. This tests to make sure that we're not calling the listeners with
                // cancel/end callbacks since they won't be called with the start event.
                mFutureListener = new FutureReleaseListener(mFuture, getTimeout());
                Handler handler = new Handler();
                mRunning = true;
                mAnimator.start();
                handler.postDelayed(new Canceler(mAnimator, mFuture), ANIM_MID_DELAY);
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout() + 100,  TimeUnit.MILLISECONDS);
    }

    /**
     * Same as testPlayingDelayedEnd, but end during the startDelay period
     */
    @MediumTest
    @Test
    public void testPlayingDelayedEndMidDelay() throws Throwable {
        mAnimator.setStartDelay(ANIM_DELAY);
        mActivityRule.runOnUiThread(() -> {
            try {
                // Set the listener to automatically timeout after an uncanceled animation would
                // have finished. This tests to make sure that we're not calling the listeners with
                // cancel/end callbacks since they won't be called with the start event.
                mFutureListener = new FutureReleaseListener(mFuture, getTimeout());
                Handler handler = new Handler();
                mRunning = true;
                mAnimator.start();
                handler.postDelayed(new Ender(mAnimator, mFuture), ANIM_MID_DELAY);
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout() + 100,  TimeUnit.MILLISECONDS);
    }

    /**
     * Verifies that canceling a started animation after it has already been canceled
     * does nothing.
     */
    @MediumTest
    @Test
    public void testStartDoubleCancel() throws Throwable {
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                mRunning = true;
                mAnimator.start();
                mAnimator.cancel();
                mAnimator.cancel();
                mFuture.release();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(),  TimeUnit.MILLISECONDS);
    }

    /**
     * Verifies that ending a started animation after it has already been ended
     * does nothing.
     */
    @MediumTest
    @Test
    public void testStartDoubleEnd() throws Throwable {
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                mRunning = true;
                mAnimator.start();
                mAnimator.end();
                mRunning = true; // end() implicitly starts an unstarted animator
                mAnimator.end();
                mFuture.release();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(),  TimeUnit.MILLISECONDS);
    }

    /**
     * Same as testStartDoubleCancel, but with a startDelayed animator
     */
    @MediumTest
    @Test
    public void testStartDelayedDoubleCancel() throws Throwable {
        mAnimator.setStartDelay(ANIM_DELAY);
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                mRunning = true;
                mAnimator.start();
                mAnimator.cancel();
                mAnimator.cancel();
                mFuture.release();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(),  TimeUnit.MILLISECONDS);
     }

    /**
     * Same as testStartDoubleEnd, but with a startDelayed animator
     */
    @MediumTest
    @Test
    public void testStartDelayedDoubleEnd() throws Throwable {
        mAnimator.setStartDelay(ANIM_DELAY);
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                mRunning = true;
                mAnimator.start();
                mAnimator.end();
                mRunning = true; // end() implicitly starts an unstarted animator
                mAnimator.end();
                mFuture.release();
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout(),  TimeUnit.MILLISECONDS);
     }

    /**
     * Verify that pausing and resuming an animator ends within
     * the appropriate timeout duration.
     */
    @MediumTest
    @Test
    public void testPauseResume() throws Throwable {
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                Handler handler = new Handler();
                mAnimator.addListener(mFutureListener);
                mRunning = true;
                mAnimator.start();
                handler.postDelayed(new Pauser(mAnimator, mFuture), ANIM_PAUSE_DELAY);
                handler.postDelayed(new Resumer(mAnimator, mFuture),
                        ANIM_PAUSE_DELAY + ANIM_PAUSE_DURATION);
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout() + ANIM_PAUSE_DURATION, TimeUnit.MILLISECONDS);
    }

    /**
     * Verify that pausing and resuming a startDelayed animator ends within
     * the appropriate timeout duration.
     */
    @MediumTest
    @Test
    public void testPauseResumeDelayed() throws Throwable {
        mAnimator.setStartDelay(ANIM_DELAY);
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                Handler handler = new Handler();
                mAnimator.addListener(mFutureListener);
                mRunning = true;
                mAnimator.start();
                handler.postDelayed(new Pauser(mAnimator, mFuture), ANIM_PAUSE_DELAY);
                handler.postDelayed(new Resumer(mAnimator, mFuture),
                        ANIM_PAUSE_DELAY + ANIM_PAUSE_DURATION);
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        mFuture.get(getTimeout() + ANIM_PAUSE_DURATION + ANIM_FULL_DURATION_SLOP,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Verify that pausing an animator without resuming it causes a timeout.
     */
    @MediumTest
    @Test
    public void testPauseTimeout() throws Throwable {
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                Handler handler = new Handler();
                mAnimator.addListener(mFutureListener);
                mRunning = true;
                mAnimator.start();
                handler.postDelayed(new Pauser(mAnimator, mFuture), ANIM_PAUSE_DELAY);
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        try {
            mFuture.get(getTimeout() + ANIM_PAUSE_DURATION + ANIM_FULL_DURATION_SLOP,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Expected behavior, swallow the exception
        }
    }

    /**
     * Verify that pausing a startDelayed animator without resuming it causes a timeout.
     */
    @MediumTest
    @Test
    public void testPauseTimeoutDelayed() throws Throwable {
        mAnimator.setStartDelay(ANIM_DELAY);
        mFutureListener = new FutureReleaseListener(mFuture);
        mActivityRule.runOnUiThread(() -> {
            try {
                Handler handler = new Handler();
                mAnimator.addListener(mFutureListener);
                mRunning = true;
                mAnimator.start();
                handler.postDelayed(new Pauser(mAnimator, mFuture), ANIM_PAUSE_DELAY);
            } catch (junit.framework.AssertionFailedError e) {
                mFuture.setException(new RuntimeException(e));
            }
        });
        try {
            mFuture.get(getTimeout() + ANIM_PAUSE_DURATION + ANIM_FULL_DURATION_SLOP,
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // Expected behavior, swallow the exception
        }
    }
}
