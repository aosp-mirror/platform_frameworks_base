/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.util.wakelock;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.view.animation.Animation;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.util.Assert;

public class KeepAwakeAnimationListener extends AnimatorListenerAdapter
        implements Animation.AnimationListener {
    @VisibleForTesting
    static WakeLock sWakeLock;

    public KeepAwakeAnimationListener(Context context) {
        Assert.isMainThread();
        if (sWakeLock == null) {
            sWakeLock = WakeLock.createPartial(context, "animation");
        }
    }

    @Override
    public void onAnimationStart(Animation animation) {
        onStart();
    }

    @Override
    public void onAnimationEnd(Animation animation) {
        onEnd();
    }

    @Override
    public void onAnimationRepeat(Animation animation) {

    }

    @Override
    public void onAnimationStart(Animator animation) {
        onStart();
    }

    @Override
    public void onAnimationEnd(Animator animation) {
        onEnd();
    }

    private void onStart() {
        Assert.isMainThread();
        sWakeLock.acquire();
    }

    private void onEnd() {
        Assert.isMainThread();
        sWakeLock.release();
    }
}
