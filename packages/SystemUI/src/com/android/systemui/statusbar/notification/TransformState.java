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

package com.android.systemui.statusbar.notification;

import android.util.ArraySet;
import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;

/**
 * A transform state of a view.
*/
public abstract class TransformState {

    private static final int CLIP_CLIPPING_SET = R.id.clip_children_set_tag;
    private static final int CLIP_CHILDREN_TAG = R.id.clip_children_tag;
    private static final int CLIP_TO_PADDING = R.id.clip_to_padding_tag;
    protected View mTransformedView;
    private int[] mOwnPosition = new int[2];

    public void initFrom(View view) {
        mTransformedView = view;
    }

    /**
     * Transforms the {@link #mTransformedView} from the given transformviewstate
     * @param otherState the state to transform from
     */
    public void transformViewFrom(TransformState otherState) {
        mTransformedView.animate().cancel();
        if (sameAs(otherState)) {
            // We have the same content, lets show ourselves
            mTransformedView.setAlpha(1.0f);
            mTransformedView.setVisibility(View.VISIBLE);
        } else {
            CrossFadeHelper.fadeIn(mTransformedView);
        }
        final View transformedView = mTransformedView;
        // lets animate the positions correctly
        int[] otherPosition = otherState.getLocationOnScreen();
        int[] ownStablePosition = getLaidOutLocationOnScreen();
        mTransformedView.setTranslationX(otherPosition[0] - ownStablePosition[0]);
        mTransformedView.setTranslationY(otherPosition[1] - ownStablePosition[1]);
        mTransformedView.animate()
                .translationX(0)
                .translationY(0)
                .setDuration(CrossFadeHelper.ANIMATION_DURATION_LENGTH)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        setClippingDeactivated(transformedView, false);
                    }
                });
        setClippingDeactivated(mTransformedView, true);
    }

    /**
     * Transforms the {@link #mTransformedView} to the given transformviewstate
     * @param otherState the state to transform from
     * @param endRunnable a runnable to run at the end of the animation
     * @return whether an animation was started
     */
    public boolean transformViewTo(TransformState otherState, final Runnable endRunnable) {
        mTransformedView.animate().cancel();
        if (sameAs(otherState)) {
            // We have the same text, lets show ourselfs
            mTransformedView.setAlpha(0.0f);
            mTransformedView.setVisibility(View.INVISIBLE);
            return false;
        } else {
            CrossFadeHelper.fadeOut(mTransformedView, endRunnable);
        }
        // lets animate the positions correctly
        int[] otherStablePosition = otherState.getLaidOutLocationOnScreen();
        int[] ownPosition = getLaidOutLocationOnScreen();
        final View transformedView = mTransformedView;
        mTransformedView.animate()
                .translationX(otherStablePosition[0] - ownPosition[0])
                .translationY(otherStablePosition[1] - ownPosition[1])
                .setDuration(CrossFadeHelper.ANIMATION_DURATION_LENGTH)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (endRunnable != null) {
                            endRunnable.run();
                        }
                        setClippingDeactivated(transformedView, false);
                    }
                });
        setClippingDeactivated(mTransformedView, true);
        return true;
    }

    private void setClippingDeactivated(final View transformedView, boolean deactivated) {
        ViewGroup view = (ViewGroup) transformedView.getParent();
        while (true) {
            ArraySet<View> clipSet = (ArraySet<View>) view.getTag(CLIP_CLIPPING_SET);
            if (clipSet == null) {
                clipSet = new ArraySet<>();
                view.setTag(CLIP_CLIPPING_SET, clipSet);
            }
            Boolean clipChildren = (Boolean) view.getTag(CLIP_CHILDREN_TAG);
            if (clipChildren == null) {
                clipChildren = view.getClipChildren();
                view.setTag(CLIP_CHILDREN_TAG, clipChildren);
            }
            Boolean clipToPadding = (Boolean) view.getTag(CLIP_TO_PADDING);
            if (clipToPadding == null) {
                clipToPadding = view.getClipToPadding();
                view.setTag(CLIP_TO_PADDING, clipToPadding);
            }
            ExpandableNotificationRow row = view instanceof ExpandableNotificationRow
                    ? (ExpandableNotificationRow) view
                    : null;
            if (!deactivated) {
                clipSet.remove(transformedView);
                if (clipSet.isEmpty()) {
                    view.setClipChildren(clipChildren);
                    view.setClipToPadding(clipToPadding);
                    view.setTag(CLIP_CLIPPING_SET, null);
                    if (row != null) {
                        row.setClipToActualHeight(true);
                    }
                }
            } else {
                clipSet.add(transformedView);
                view.setClipChildren(false);
                view.setClipToPadding(false);
                if (row != null && row.isChildInGroup()) {
                    // We still want to clip to the parent's height
                    row.setClipToActualHeight(false);
                }
            }
            if (row != null && !row.isChildInGroup()) {
                return;
            }
            final ViewParent parent = view.getParent();
            if (parent instanceof ViewGroup) {
                view = (ViewGroup) parent;
            } else {
                return;
            }
        }
    }

    private int[] getLaidOutLocationOnScreen() {
        int[] location = getLocationOnScreen();
        location[0] -= mTransformedView.getTranslationX();
        location[1] -= mTransformedView.getTranslationY();
        return location;
    }

    private int[] getLocationOnScreen() {
        mTransformedView.getLocationOnScreen(mOwnPosition);
        return mOwnPosition;
    }

    protected boolean sameAs(TransformState otherState) {
        return false;
    }

    public static TransformState createFrom(View view) {
        if (view instanceof TextView) {
            TextViewTransformState result = TextViewTransformState.obtain();
            result.initFrom(view);
            return result;
        }
        if (view instanceof NotificationHeaderView) {
            HeaderTransformState result = HeaderTransformState.obtain();
            result.initFrom(view);
            return result;
        }
        if (view instanceof ImageView) {
            ImageTransformState result = ImageTransformState.obtain();
            result.initFrom(view);
            return result;
        }
        if (view instanceof ProgressBar) {
            ProgressTransformState result = ProgressTransformState.obtain();
            result.initFrom(view);
            return result;
        }
        return null;
    }

    public void recycle() {
        reset();
    }

    protected void reset() {
        mTransformedView = null;
    }

    public void setVisible(boolean visible) {
        mTransformedView.animate().cancel();
        mTransformedView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        mTransformedView.setAlpha(visible ? 1.0f : 0.0f);
        if (visible) {
            mTransformedView.setTranslationX(0);
            mTransformedView.setTranslationY(0);
        }
    }

    public void prepareFadeIn() {
    }
}
