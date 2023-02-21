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

import android.util.PollingCheck;
import android.view.View;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

@MediumTest
public class AnimatorSetCallsTest {
    @Rule
    public final ActivityScenarioRule<AnimatorSetActivity> mRule =
            new ActivityScenarioRule<>(AnimatorSetActivity.class);

    private AnimatorSetActivity mActivity;
    private AnimatorSet mSet1;
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

            AnimatorSet set2 = new AnimatorSet();
            mListener2 = new CountListener();
            set2.addListener(mListener2);
            set2.addPauseListener(mListener2);

            ObjectAnimator anim = ObjectAnimator.ofFloat(square, "translationX", 0f, 100f);
            mListener3 = new CountListener();
            anim.addListener(mListener3);
            anim.addPauseListener(mListener3);
            anim.setDuration(1);

            set2.play(anim);
            mSet1.play(set2);
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
            assertEquals(0, startNoParam);
            assertEquals(0, endNoParam);
            assertEquals(startForward, this.startForward);
            assertEquals(startReverse, this.startReverse);
            assertEquals(endForward, this.endForward);
            assertEquals(endReverse, this.endReverse);
            assertEquals(cancel, this.cancel);
            assertEquals(repeat, this.repeat);
            assertEquals(pause, this.pause);
            assertEquals(resume, this.resume);
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
