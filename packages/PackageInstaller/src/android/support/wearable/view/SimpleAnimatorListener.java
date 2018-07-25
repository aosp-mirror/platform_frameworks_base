/*
 * Copyright (C) 2015 The Android Open Source Project
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

package androidx.wear.ble.view;

import android.animation.Animator;
import android.annotation.TargetApi;
import android.os.Build;

/**
 * Convenience class for listening for Animator events that implements the AnimatorListener
 * interface and allows extending only methods that are necessary.
 */
@TargetApi(Build.VERSION_CODES.KITKAT_WATCH)
public class SimpleAnimatorListener implements Animator.AnimatorListener {

    private boolean mWasCanceled;

    @Override
    public void onAnimationCancel(Animator animator) {
        mWasCanceled = true;
    }

    @Override
    public void onAnimationEnd(Animator animator) {
        if (!mWasCanceled) {
            onAnimationComplete(animator);
        }
    }

    @Override
    public void onAnimationRepeat(Animator animator) {
    }

    @Override
    public void onAnimationStart(Animator animator) {
        mWasCanceled = false;
    }

    /**
     * Called when the animation finishes. Not called if the animation was canceled.
     */
    public void onAnimationComplete(Animator animator) {
    }

    /**
     * Provides information if the animation was cancelled.
     * @return True if animation was cancelled.
     */
    public boolean wasCanceled() {
        return mWasCanceled;
    }

}
