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

package com.android.systemui.statusbar.stack;

import android.view.View;

/**
 * A state of a view. This can be used to apply a set of view properties to a view with
 * {@link com.android.systemui.statusbar.stack.StackScrollState} or start animations with
 * {@link com.android.systemui.statusbar.stack.StackStateAnimator}.
*/
public class ViewState {

    public float alpha;
    public float yTranslation;
    public float zTranslation;
    public boolean gone;
    public boolean hidden;

    public void copyFrom(ViewState viewState) {
        alpha = viewState.alpha;
        yTranslation = viewState.yTranslation;
        zTranslation = viewState.zTranslation;
        gone = viewState.gone;
        hidden = viewState.hidden;
    }

    public void initFrom(View view) {
        alpha = view.getAlpha();
        yTranslation = view.getTranslationY();
        zTranslation = view.getTranslationZ();
        gone = view.getVisibility() == View.GONE;
        hidden = false;
    }
}
