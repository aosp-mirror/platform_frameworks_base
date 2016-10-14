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

import com.android.systemui.statusbar.ExpandableView;

/**
 * A state of a view. This can be used to apply a set of view properties to a view with
 * {@link com.android.systemui.statusbar.stack.StackScrollState} or start animations with
 * {@link com.android.systemui.statusbar.stack.StackStateAnimator}.
*/
public class ViewState {

    public float alpha;
    public float xTranslation;
    public float yTranslation;
    public float zTranslation;
    public boolean gone;
    public boolean hidden;
    public float scaleX = 1.0f;
    public float scaleY = 1.0f;

    public void copyFrom(ViewState viewState) {
        alpha = viewState.alpha;
        xTranslation = viewState.xTranslation;
        yTranslation = viewState.yTranslation;
        zTranslation = viewState.zTranslation;
        gone = viewState.gone;
        hidden = viewState.hidden;
        scaleX = viewState.scaleX;
        scaleY = viewState.scaleY;
    }

    public void initFrom(View view) {
        alpha = view.getAlpha();
        xTranslation = view.getTranslationX();
        yTranslation = view.getTranslationY();
        zTranslation = view.getTranslationZ();
        gone = view.getVisibility() == View.GONE;
        hidden = false;
        scaleX = view.getScaleX();
        scaleY = view.getScaleY();
    }

    /**
     * Applies a {@link ViewState} to a normal view.
     */
    public void applyToView(View view) {
        if (this.gone) {
            // don't do anything with it
            return;
        }
        boolean becomesInvisible = this.alpha == 0.0f || this.hidden;
        if (view.getAlpha() != this.alpha) {
            // apply layer type
            boolean becomesFullyVisible = this.alpha == 1.0f;
            boolean newLayerTypeIsHardware = !becomesInvisible && !becomesFullyVisible
                    && view.hasOverlappingRendering();
            int layerType = view.getLayerType();
            int newLayerType = newLayerTypeIsHardware
                    ? View.LAYER_TYPE_HARDWARE
                    : View.LAYER_TYPE_NONE;
            if (layerType != newLayerType) {
                view.setLayerType(newLayerType, null);
            }

            // apply alpha
            view.setAlpha(this.alpha);
        }

        // apply visibility
        int oldVisibility = view.getVisibility();
        int newVisibility = becomesInvisible ? View.INVISIBLE : View.VISIBLE;
        if (newVisibility != oldVisibility) {
            if (!(view instanceof ExpandableView) || !((ExpandableView) view).willBeGone()) {
                // We don't want views to change visibility when they are animating to GONE
                view.setVisibility(newVisibility);
            }
        }

        // apply xTranslation
        if (view.getTranslationX() != this.xTranslation) {
            view.setTranslationX(this.xTranslation);
        }

        // apply yTranslation
        if (view.getTranslationY() != this.yTranslation) {
            view.setTranslationY(this.yTranslation);
        }

        // apply zTranslation
        if (view.getTranslationZ() != this.zTranslation) {
            view.setTranslationZ(this.zTranslation);
        }

        // apply scaleX
        if (view.getScaleX() != this.scaleX) {
            view.setScaleX(this.scaleX);
        }

        // apply scaleY
        if (view.getScaleY() != this.scaleY) {
            view.setScaleY(this.scaleY);
        }
    }
}
