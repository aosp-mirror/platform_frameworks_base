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

package com.android.internal.widget;

import android.util.ArraySet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.android.internal.R;

/**
 * A utility class that allows to clip views and their parents to allow for better transitions
 */
public class ViewClippingUtil {
    private static final int CLIP_CLIPPING_SET = R.id.clip_children_set_tag;
    private static final int CLIP_CHILDREN_TAG = R.id.clip_children_tag;
    private static final int CLIP_TO_PADDING = R.id.clip_to_padding_tag;

    public static void setClippingDeactivated(final View transformedView, boolean deactivated,
        ClippingParameters clippingParameters) {
        if (!deactivated && !clippingParameters.isClippingEnablingAllowed(transformedView)) {
            return;
        }
        if (!(transformedView.getParent() instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) transformedView.getParent();
        while (true) {
            if (!deactivated && !clippingParameters.isClippingEnablingAllowed(transformedView)) {
                return;
            }
            ArraySet<View> clipSet = (ArraySet<View>) parent.getTag(CLIP_CLIPPING_SET);
            if (clipSet == null) {
                clipSet = new ArraySet<>();
                parent.setTagInternal(CLIP_CLIPPING_SET, clipSet);
            }
            Boolean clipChildren = (Boolean) parent.getTag(CLIP_CHILDREN_TAG);
            if (clipChildren == null) {
                clipChildren = parent.getClipChildren();
                parent.setTagInternal(CLIP_CHILDREN_TAG, clipChildren);
            }
            Boolean clipToPadding = (Boolean) parent.getTag(CLIP_TO_PADDING);
            if (clipToPadding == null) {
                clipToPadding = parent.getClipToPadding();
                parent.setTagInternal(CLIP_TO_PADDING, clipToPadding);
            }
            if (!deactivated) {
                clipSet.remove(transformedView);
                if (clipSet.isEmpty()) {
                    parent.setClipChildren(clipChildren);
                    parent.setClipToPadding(clipToPadding);
                    parent.setTagInternal(CLIP_CLIPPING_SET, null);
                    clippingParameters.onClippingStateChanged(parent, true);
                }
            } else {
                clipSet.add(transformedView);
                parent.setClipChildren(false);
                parent.setClipToPadding(false);
                clippingParameters.onClippingStateChanged(parent, false);
            }
            if (clippingParameters.shouldFinish(parent)) {
                return;
            }
            final ViewParent viewParent = parent.getParent();
            if (viewParent instanceof ViewGroup) {
                parent = (ViewGroup) viewParent;
            } else {
                return;
            }
        }
    }

    public interface ClippingParameters {
        /**
         * Should we stop clipping at this view? If true is returned, {@param view} is the last view
         * where clipping is activated / deactivated.
         */
        boolean shouldFinish(View view);

        /**
         * Is it allowed to enable clipping on this view.
         */
        default boolean isClippingEnablingAllowed(View view) {
            return !MessagingPropertyAnimator.isAnimatingTranslation(view);
        }

        /**
         * A method that is called whenever the view starts clipping again / stops clipping to the
         * children and padding.
         */
        default void onClippingStateChanged(View view, boolean isClipping) {};
    }
}
