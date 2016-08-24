/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.statusbar;

import com.android.systemui.statusbar.notification.TransformState;

/**
 * A view that can be transformed to and from.
 */
public interface TransformableView {
    int TRANSFORMING_VIEW_HEADER = 0;
    int TRANSFORMING_VIEW_TITLE = 1;
    int TRANSFORMING_VIEW_TEXT = 2;
    int TRANSFORMING_VIEW_IMAGE = 3;
    int TRANSFORMING_VIEW_PROGRESS = 4;
    int TRANSFORMING_VIEW_ACTIONS = 5;

    /**
     * Get the current state of a view in a transform animation
     *
     * @param fadingView which view we are interested in
     * @return the current transform state of this viewtype
     */
    TransformState getCurrentState(int fadingView);

    /**
     * Transform to the given view
     *
     * @param notification the view to transform to
     */
    void transformTo(TransformableView notification, Runnable endRunnable);

    /**
     * Transform to the given view by a specified amount.
     *
     * @param notification the view to transform to
     * @param transformationAmount how much transformation should be done
     */
    void transformTo(TransformableView notification, float transformationAmount);

    /**
     * Transform to this view from the given view
     *
     * @param notification the view to transform from
     */
    void transformFrom(TransformableView notification);

    /**
     * Transform to this view from the given view by a specified amount.
     *
     * @param notification the view to transform from
     * @param transformationAmount how much transformation should be done
     */
    void transformFrom(TransformableView notification, float transformationAmount);

    /**
     * Set this view to be fully visible or gone
     *
     * @param visible
     */
    void setVisible(boolean visible);
}
