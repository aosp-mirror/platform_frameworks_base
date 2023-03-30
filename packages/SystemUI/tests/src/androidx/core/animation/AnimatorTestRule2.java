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

package androidx.core.animation;

import android.os.Looper;
import android.os.SystemClock;
import android.util.AndroidRuntimeException;

import androidx.annotation.NonNull;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * NOTE: this is a copy of the {@link androidx.core.animation.AnimatorTestRule} which attempts to
 * circumvent the problems with {@link androidx.core.animation.AnimationHandler} having a static
 * list of callbacks.
 *
 * TODO(b/275602127): remove this and use the original rule once we have the updated androidx code.
 */
public final class AnimatorTestRule2 implements TestRule {

    class TestAnimationHandler extends AnimationHandler {
        TestAnimationHandler() {
            super(new TestProvider());
        }

        List<AnimationFrameCallback> animationCallbacks = new ArrayList<>();

        @Override
        void addAnimationFrameCallback(AnimationFrameCallback callback) {
            animationCallbacks.add(callback);
            callback.doAnimationFrame(getCurrentTime());
        }

        @Override
        public void removeCallback(AnimationFrameCallback callback) {
            int id = animationCallbacks.indexOf(callback);
            if (id >= 0) {
                animationCallbacks.set(id, null);
            }
        }

        void onAnimationFrame(long frameTime) {
            for (int i = 0; i < animationCallbacks.size(); i++) {
                final AnimationFrameCallback callback = animationCallbacks.get(i);
                if (callback == null) {
                    continue;
                }
                callback.doAnimationFrame(frameTime);
            }
        }

        @Override
        void autoCancelBasedOn(ObjectAnimator objectAnimator) {
            for (int i = animationCallbacks.size() - 1; i >= 0; i--) {
                AnimationFrameCallback cb = animationCallbacks.get(i);
                if (cb == null) {
                    continue;
                }
                if (objectAnimator.shouldAutoCancel(cb)) {
                    ((Animator) animationCallbacks.get(i)).cancel();
                }
            }
        }
    }

    final TestAnimationHandler mTestHandler;
    final long mStartTime;
    private long mTotalTimeDelta = 0;
    private final Object mLock = new Object();

    public AnimatorTestRule2() {
        mStartTime = SystemClock.uptimeMillis();
        mTestHandler = new TestAnimationHandler();
    }

    @NonNull
    @Override
    public Statement apply(@NonNull final Statement base, @NonNull Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                AnimationHandler.setTestHandler(mTestHandler);
                try {
                    base.evaluate();
                } finally {
                    AnimationHandler.setTestHandler(null);
                }
            }
        };
    }

    /**
     * Advances the animation clock by the given amount of delta in milliseconds. This call will
     * produce an animation frame to all the ongoing animations. This method needs to be
     * called on the same thread as {@link Animator#start()}.
     *
     * @param timeDelta the amount of milliseconds to advance
     */
    public void advanceTimeBy(long timeDelta) {
        if (Looper.myLooper() == null) {
            // Throw an exception
            throw new AndroidRuntimeException("AnimationTestRule#advanceTimeBy(long) may only be"
                    + "called on Looper threads");
        }
        synchronized (mLock) {
            // Advance time & pulse a frame
            mTotalTimeDelta += timeDelta < 0 ? 0 : timeDelta;
        }
        // produce a frame
        mTestHandler.onAnimationFrame(getCurrentTime());
    }


    /**
     * Returns the current time in milliseconds tracked by AnimationHandler. Note that this is a
     * different time than the time tracked by {@link SystemClock} This method needs to be called on
     * the same thread as {@link Animator#start()}.
     */
    public long getCurrentTime() {
        if (Looper.myLooper() == null) {
            // Throw an exception
            throw new AndroidRuntimeException("AnimationTestRule#getCurrentTime() may only be"
                    + "called on Looper threads");
        }
        synchronized (mLock) {
            return mStartTime + mTotalTimeDelta;
        }
    }


    private class TestProvider implements AnimationHandler.AnimationFrameCallbackProvider {
        TestProvider() {
        }

        @Override
        public void onNewCallbackAdded(AnimationHandler.AnimationFrameCallback callback) {
            callback.doAnimationFrame(getCurrentTime());
        }

        @Override
        public void postFrameCallback() {
        }

        @Override
        public void setFrameDelay(long delay) {
        }

        @Override
        public long getFrameDelay() {
            return 0;
        }
    }
}

