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
import android.widget.Button;

import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

import java.util.concurrent.TimeUnit;

/**
 * Listener tests for AnimatorSet.
 */
public class AnimatorSetEventsTest extends EventsTest {

    Button button;
    ObjectAnimator xAnim = ObjectAnimator.ofFloat(this, "translationX", 0, 100);
    ObjectAnimator yAnim = ObjectAnimator.ofFloat(this, "translationY", 0, 100);

    @Override
    public void setUp() throws Exception {
        button = (Button) getActivity().findViewById(R.id.animatingButton);
        mAnimator = new AnimatorSet();
        ((AnimatorSet)mAnimator).playSequentially(xAnim, yAnim);
        super.setUp();
    }

    @Override
    protected long getTimeout() {
        return (2 * mAnimator.getDuration()) + (2 * mAnimator.getStartDelay()) +
                ANIM_DELAY + FUTURE_RELEASE_DELAY;
    }

    /**
     * Tests that an AnimatorSet can be correctly canceled during the delay of one of
     * its children
     */
    @MediumTest
    public void testPlayingCancelDuringChildDelay() throws Exception {
        yAnim.setStartDelay(500);
        final AnimatorSet animSet = new AnimatorSet();
        animSet.playSequentially(xAnim, yAnim);
        mFutureListener = new FutureReleaseListener(mFuture);
        getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    Handler handler = new Handler();
                    animSet.addListener(mFutureListener);
                    mRunning = true;
                    animSet.start();
                    handler.postDelayed(new Canceler(animSet, mFuture), ANIM_DURATION + 250);
                } catch (junit.framework.AssertionFailedError e) {
                    mFuture.setException(new RuntimeException(e));
                }
            }
        });
        mFuture.get(getTimeout(), TimeUnit.MILLISECONDS);
    }

    public void setTranslationX(float value) {
        button.setTranslationX(value);
    }


    public void setTranslationY(float value) {
        button.setTranslationY(value);
    }


}
