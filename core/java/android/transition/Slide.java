/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.transition;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.TimeInterpolator;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * This transition captures the visibility of target objects before and
 * after a scene change and animates any changes by sliding the target
 * objects into or out of place.
 *
 * @hide
 */
public class Slide extends Visibility {

    // TODO: Add parameter for sliding factor - it's hard-coded below

    private static final TimeInterpolator sAccelerator = new AccelerateInterpolator();
    private static final TimeInterpolator sDecelerator = new DecelerateInterpolator();

    @Override
    public Animator onAppear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        View endView = (endValues != null) ? endValues.view : null;
        endView.setTranslationY(-2 * endView.getHeight());
        ObjectAnimator anim = ObjectAnimator.ofFloat(endView, View.TRANSLATION_Y,
                -2 * endView.getHeight(), 0);
        anim.setInterpolator(sDecelerator);
        return anim;
    }

    @Override
    public Animator onDisappear(ViewGroup sceneRoot,
            TransitionValues startValues, int startVisibility,
            TransitionValues endValues, int endVisibility) {
        View startView = (startValues != null) ? startValues.view : null;
        startView.setTranslationY(0);
        ObjectAnimator anim = ObjectAnimator.ofFloat(startView, View.TRANSLATION_Y, 0,
                -2 * startView.getHeight());
        anim.setInterpolator(sAccelerator);
        return anim;
    }

}
