/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.server.wm.WindowManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.WindowManagerDebugConfig.TAG_WM;

import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.view.animation.Transformation;

// TODO: Move all remaining fields to AppWindowToken and remove this class.
public class AppWindowAnimator {
    static final String TAG = TAG_WITH_CLASS_NAME ? "AppWindowAnimator" : TAG_WM;

    static final int PROLONG_ANIMATION_AT_END = 1;
    static final int PROLONG_ANIMATION_AT_START = 2;

    // Have we been asked to have this token keep the screen frozen?
    // Protect with mAnimator.
    boolean freezingScreen;

    /**
     * How long we last kept the screen frozen.
     */
    int lastFreezeDuration;

    // Offset to the window of all layers in the token, for use by
    // AppWindowToken animations.
    int animLayerAdjustment;

    // Propagated from AppWindowToken.allDrawn, to determine when
    // the state changes.
    boolean allDrawn;

    // Special surface for thumbnail animation.  If deferThumbnailDestruction is enabled, then we
    // will make sure that the thumbnail is destroyed after the other surface is completed.  This
    // requires that the duration of the two animations are the same.
    SurfaceControl thumbnail;
    int thumbnailTransactionSeq;

    Animation thumbnailAnimation;

    // This flag indicates that the destruction of the thumbnail surface is synchronized with
    // another animation, so defer the destruction of this thumbnail surface for a single frame
    // after the secondary animation completes.
    boolean deferThumbnailDestruction;
}
