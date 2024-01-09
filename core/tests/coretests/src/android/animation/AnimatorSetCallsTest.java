/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.util.PollingCheck;
import android.view.View;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@MediumTest
public class AnimatorSetCallsTest {
    @Rule
    public final ActivityScenarioRule<AnimatorSetActivity> mRule =
            new ActivityScenarioRule<>(AnimatorSetActivity.class);

    private AnimatorSetActivity mActivity;
    private AnimatorSet mSet1;
    private AnimatorSet mSet2;
    private ObjectAnimator mAnimator;
    private CountListener mListener1;
    private CountListener mListener2;
    private CountListener mListener3;

    @Before
    public void setUp() throws Exception {
        mRule.getScenario().onActivity((activity) -> {
            mActivity = activity;
            View square = mActivity.findViewById(R.id.square1);

            mSet1 = new AnimatorSet();
            mListener1 = new CountListener();
            mSet1.addListener(mListener1);
            mSet1.addPauseListener(mListener1);

            mSet2 = new AnimatorSet();
            mListener2 = new CountListener();
            mSet2.addListener(mListener2);
            mSet2.addPauseListener(mListener2);

            mAnimator = ObjectAnimator.ofFloat(square, "translationX", 0f, 100f);
            mListener3 = new CountListener();
            mAnimator.addListener(mListener3);
            mAnimator.addPauseListener(mListener3);
            mAnimator.setDuration(1);

            mSet2.play(mAnimator);
            mSet1.play(mSet2);
        });
    }

    @Test
    public void startEndCalledOnChildren() {
        mRule.getScenario().onActivity((a) -> mSet1.start());
        waitForOnUiThread(() -> mListener1.endForward > 0);

        // only startForward and endForward should have been called once
        mListener1.assertValues(
                1, 0, 1, 0, 0, 0, 0, 0
        );
        mListener2.assertValues(
                1, 0, 1, 0, 0, 0, 0, 0
        );
        mListener3.assertValues(
                1, 0, 1, 0, 0, 0, 0, 0
        );
    }

    @Test
    public void cancelCalledOnChildren() {
        mRule.getScenario().onActivity((a) -> {
            mSet1.start();
            mSet1.cancel();
        });
        waitForOnUiThread(() -> mListener1.endForward > 0);

        // only startForward and endForward should have been called once
        mListener1.assertValues(
                1, 0, 1, 0, 1, 0, 0, 0
        );
        mListener2.assertValues(
                1, 0, 1, 0, 1, 0, 0, 0
        );
        mListener3.assertValues(
                1, 0, 1, 0, 1, 0, 0, 0
        );
    }

    @Test
    public void startEndReversedCalledOnChildren() {
        mRule.getScenario().onActivity((a) -> mSet1.reverse());
        waitForOnUiThread(() -> mListener1.endReverse > 0);

        // only startForward and endForward should have been called once
        mListener1.assertValues(
                0, 1, 0, 1, 0, 0, 0, 0
        );
        mListener2.assertValues(
                0, 1, 0, 1, 0, 0, 0, 0
        );
        mListener3.assertValues(
                0, 1, 0, 1, 0, 0, 0, 0
        );
    }

    @Test
    public void pauseResumeCalledOnChildren() {
        mRule.getScenario().onActivity((a) -> {
            mSet1.start();
            mSet1.pause();
        });
        waitForOnUiThread(() -> mListener1.pause > 0);

        // only startForward and pause should have been called once
        mListener1.assertValues(
                1, 0, 0, 0, 0, 0, 1, 0
        );
        mListener2.assertValues(
                1, 0, 0, 0, 0, 0, 1, 0
        );
        mListener3.assertValues(
                1, 0, 0, 0, 0, 0, 1, 0
        );

        mRule.getScenario().onActivity((a) -> mSet1.resume());
        waitForOnUiThread(() -> mListener1.endForward > 0);

        // resume and endForward should have been called once
        mListener1.assertValues(
                1, 0, 1, 0, 0, 0, 1, 1
        );
        mListener2.assertValues(
                1, 0, 1, 0, 0, 0, 1, 1
        );
        mListener3.assertValues(
                1, 0, 1, 0, 0, 0, 1, 1
        );
    }

    @Test
    public void updateOnlyWhileChangingValues() {
        ArrayList<Float> updateValues = new ArrayList<>();
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                updateValues.add((Float) animation.getAnimatedValue());
            }
        });

        mSet1.setCurrentPlayTime(0);

        assertEquals(1, updateValues.size());
        assertEquals(0f, updateValues.get(0), 0f);
    }

    @Test
    public void updateOnlyWhileRunning() {
        ArrayList<Float> updateValues = new ArrayList<>();
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                updateValues.add((Float) animation.getAnimatedValue());
            }
        });

        mRule.getScenario().onActivity((a) -> {
            mSet1.start();
        });

        waitForOnUiThread(() -> mListener1.endForward > 0);

        // the duration is only 1ms, so there should only be two values, 0 and 100.
        assertEquals(0f, updateValues.get(0), 0f);
        assertEquals(100f, updateValues.get(updateValues.size() - 1), 0f);

        // now check all the values in the middle, which can never go from 100->0.
        boolean isAtEnd = false;
        for (int i = 1; i < updateValues.size() - 1; i++) {
            float actual = updateValues.get(i);
            if (actual == 100f) {
                isAtEnd = true;
            }
            float expected = isAtEnd ? 100f : 0f;
            assertEquals(expected, actual, 0f);
        }
    }

    @Test
    public void pauseResumeSeekingAnimators() {
        ValueAnimator animator2 = ValueAnimator.ofFloat(0f, 1f);
        mSet2.play(animator2).after(mAnimator);
        mSet2.setStartDelay(100);
        mSet1.setStartDelay(100);
        mAnimator.setDuration(100);

        mActivity.runOnUiThread(() -> {
            mSet1.setCurrentPlayTime(0);
            mSet1.pause();

            // only startForward and pause should have been called once
            mListener1.assertValues(
                    1, 0, 0, 0, 0, 0, 1, 0
            );
            mListener2.assertValues(
                    0, 0, 0, 0, 0, 0, 0, 0
            );
            mListener3.assertValues(
                    0, 0, 0, 0, 0, 0, 0, 0
            );

            mSet1.resume();
            mListener1.assertValues(
                    1, 0, 0, 0, 0, 0, 1, 1
            );
            mListener2.assertValues(
                    0, 0, 0, 0, 0, 0, 0, 0
            );
            mListener3.assertValues(
                    0, 0, 0, 0, 0, 0, 0, 0
            );

            mSet1.setCurrentPlayTime(200);

            // resume and endForward should have been called once
            mListener1.assertValues(
                    1, 0, 0, 0, 0, 0, 1, 1
            );
            mListener2.assertValues(
                    1, 0, 0, 0, 0, 0, 0, 0
            );
            mListener3.assertValues(
                    1, 0, 0, 0, 0, 0, 0, 0
            );

            mSet1.pause();
            mListener1.assertValues(
                    1, 0, 0, 0, 0, 0, 2, 1
            );
            mListener2.assertValues(
                    1, 0, 0, 0, 0, 0, 1, 0
            );
            mListener3.assertValues(
                    1, 0, 0, 0, 0, 0, 1, 0
            );
            mSet1.resume();
            mListener1.assertValues(
                    1, 0, 0, 0, 0, 0, 2, 2
            );
            mListener2.assertValues(
                    1, 0, 0, 0, 0, 0, 1, 1
            );
            mListener3.assertValues(
                    1, 0, 0, 0, 0, 0, 1, 1
            );

            // now go to animator2
            mSet1.setCurrentPlayTime(400);
            mSet1.pause();
            mSet1.resume();
            mListener1.assertValues(
                    1, 0, 0, 0, 0, 0, 3, 3
            );
            mListener2.assertValues(
                    1, 0, 0, 0, 0, 0, 2, 2
            );
            mListener3.assertValues(
                    1, 0, 1, 0, 0, 0, 1, 1
            );

            // now go back to mAnimator
            mSet1.setCurrentPlayTime(250);
            mSet1.pause();
            mSet1.resume();
            mListener1.assertValues(
                    1, 0, 0, 0, 0, 0, 4, 4
            );
            mListener2.assertValues(
                    1, 0, 0, 0, 0, 0, 3, 3
            );
            mListener3.assertValues(
                    1, 1, 1, 0, 0, 0, 2, 2
            );

            // now go back to before mSet2 was being run
            mSet1.setCurrentPlayTime(1);
            mSet1.pause();
            mSet1.resume();
            mListener1.assertValues(
                    1, 0, 0, 0, 0, 0, 5, 5
            );
            mListener2.assertValues(
                    1, 0, 0, 1, 0, 0, 3, 3
            );
            mListener3.assertValues(
                    1, 1, 1, 1, 0, 0, 2, 2
            );
        });
    }

    @Test
    public void endInCancel() throws Throwable {
        AnimatorListenerAdapter listener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) {
                mSet1.end();
            }
        };
        mSet1.addListener(listener);
        mActivity.runOnUiThread(() -> {
            mSet1.start();
            mSet1.cancel();
            // Should go to the end value
            View square = mActivity.findViewById(R.id.square1);
            assertEquals(100f, square.getTranslationX(), 0.001f);
        });
    }

    @Test
    public void childAnimatorCancelsDuringUpdate_animatorSetIsEnded() throws Throwable {
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                animation.cancel();
            }
        });
        mActivity.runOnUiThread(() -> {
            mSet1.start();
            assertFalse(mSet1.isRunning());
        });
    }

    @Test
    public void reentrantStart() throws Throwable {
        CountDownLatch latch = new CountDownLatch(3);
        AnimatorListenerAdapter listener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation, boolean isReverse) {
                mSet1.start();
                latch.countDown();
            }
        };
        mSet1.addListener(listener);
        mSet2.addListener(listener);
        mAnimator.addListener(listener);
        mActivity.runOnUiThread(() -> mSet1.start());
        assertTrue(latch.await(1, TimeUnit.SECONDS));

        // Make sure that the UI thread hasn't been destroyed by a stack overflow...
        mActivity.runOnUiThread(() -> {});
    }

    @Test
    public void reentrantEnd() throws Throwable {
        CountDownLatch latch = new CountDownLatch(3);
        AnimatorListenerAdapter listener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation, boolean isReverse) {
                mSet1.end();
                latch.countDown();
            }
        };
        mSet1.addListener(listener);
        mSet2.addListener(listener);
        mAnimator.addListener(listener);
        mActivity.runOnUiThread(() -> {
            mSet1.start();
            mSet1.end();
        });
        assertTrue(latch.await(1, TimeUnit.SECONDS));

        // Make sure that the UI thread hasn't been destroyed by a stack overflow...
        mActivity.runOnUiThread(() -> {});
    }

    @Test
    public void reentrantPause() throws Throwable {
        CountDownLatch latch = new CountDownLatch(3);
        AnimatorListenerAdapter listener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationPause(Animator animation) {
                mSet1.pause();
                latch.countDown();
            }
        };
        mSet1.addPauseListener(listener);
        mSet2.addPauseListener(listener);
        mAnimator.addPauseListener(listener);
        mActivity.runOnUiThread(() -> {
            mSet1.start();
            mSet1.pause();
        });
        assertTrue(latch.await(1, TimeUnit.SECONDS));

        // Make sure that the UI thread hasn't been destroyed by a stack overflow...
        mActivity.runOnUiThread(() -> {});
    }

    @Test
    public void reentrantResume() throws Throwable {
        CountDownLatch latch = new CountDownLatch(3);
        AnimatorListenerAdapter listener = new AnimatorListenerAdapter() {
            @Override
            public void onAnimationResume(Animator animation) {
                mSet1.resume();
                latch.countDown();
            }
        };
        mSet1.addPauseListener(listener);
        mSet2.addPauseListener(listener);
        mAnimator.addPauseListener(listener);
        mActivity.runOnUiThread(() -> {
            mSet1.start();
            mSet1.pause();
            mSet1.resume();
        });
        assertTrue(latch.await(1, TimeUnit.SECONDS));

        // Make sure that the UI thread hasn't been destroyed by a stack overflow...
        mActivity.runOnUiThread(() -> {});
    }

    private void waitForOnUiThread(PollingCheck.PollingCheckCondition condition) {
        final boolean[] value = new boolean[1];
        PollingCheck.waitFor(() -> {
            mActivity.runOnUiThread(() -> value[0] = condition.canProceed());
            return value[0];
        });
    }

    private static class CountListener implements Animator.AnimatorListener,
            Animator.AnimatorPauseListener {
        public int startNoParam;
        public int endNoParam;
        public int startReverse;
        public int startForward;
        public int endForward;
        public int endReverse;
        public int cancel;
        public int repeat;
        public int pause;
        public int resume;

        public void assertValues(
                int startForward,
                int startReverse,
                int endForward,
                int endReverse,
                int cancel,
                int repeat,
                int pause,
                int resume
        ) {
            assertEquals("onAnimationStart() without direction", 0, startNoParam);
            assertEquals("onAnimationEnd() without direction", 0, endNoParam);
            assertEquals("onAnimationStart(forward)", startForward, this.startForward);
            assertEquals("onAnimationStart(reverse)", startReverse, this.startReverse);
            assertEquals("onAnimationEnd(forward)", endForward, this.endForward);
            assertEquals("onAnimationEnd(reverse)", endReverse, this.endReverse);
            assertEquals("onAnimationCancel()", cancel, this.cancel);
            assertEquals("onAnimationRepeat()", repeat, this.repeat);
            assertEquals("onAnimationPause()", pause, this.pause);
            assertEquals("onAnimationResume()", resume, this.resume);
        }

        @Override
        public void onAnimationStart(Animator animation, boolean isReverse) {
            if (isReverse) {
                startReverse++;
            } else {
                startForward++;
            }
        }

        @Override
        public void onAnimationEnd(Animator animation, boolean isReverse) {
            if (isReverse) {
                endReverse++;
            } else {
                endForward++;
            }
        }

        @Override
        public void onAnimationStart(Animator animation) {
            startNoParam++;
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            endNoParam++;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            cancel++;
        }

        @Override
        public void onAnimationRepeat(Animator animation) {
            repeat++;
        }

        @Override
        public void onAnimationPause(Animator animation) {
            pause++;
        }

        @Override
        public void onAnimationResume(Animator animation) {
            resume++;
        }
    }
}
