/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import com.android.ide.common.rendering.api.IAnimationListener;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.Result.Status;

import android.animation.AnimationThread;
import android.animation.Animator;

public class PlayAnimationThread extends AnimationThread {

    private final Animator mAnimator;

    public PlayAnimationThread(Animator animator, RenderSessionImpl scene, String animName,
            IAnimationListener listener) {
        super(scene, animName, listener);
        mAnimator = animator;
    }

    @Override
    public Result preAnimation() {
        // start the animation. This will send a message to the handler right away, so
        // the queue is filled when this method returns.
        mAnimator.start();

        return Status.SUCCESS.createResult();
    }

    @Override
    public void postAnimation() {
        // nothing to be done.
    }
}
