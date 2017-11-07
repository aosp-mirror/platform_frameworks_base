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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.util.IntProperty;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import com.android.internal.R;

/**
 * A listener that automatically starts animations when the layout bounds change.
 */
public class MessagingPropertyAnimator implements View.OnLayoutChangeListener {
    static final long APPEAR_ANIMATION_LENGTH = 210;
    private static final Interpolator ALPHA_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    public static final Interpolator ALPHA_OUT = new PathInterpolator(0f, 0f, 0.8f, 1f);
    private static final int TAG_LOCAL_TRANSLATION_ANIMATOR = R.id.tag_local_translation_y_animator;
    private static final int TAG_LOCAL_TRANSLATION_Y = R.id.tag_local_translation_y;
    private static final int TAG_LAYOUT_TOP = R.id.tag_layout_top;
    private static final int TAG_ALPHA_ANIMATOR = R.id.tag_alpha_animator;
    private static final ViewClippingUtil.ClippingParameters CLIPPING_PARAMETERS =
            view -> view.getId() == com.android.internal.R.id.notification_messaging;
    private static final IntProperty<View> LOCAL_TRANSLATION_Y =
            new IntProperty<View>("localTranslationY") {
                @Override
                public void setValue(View object, int value) {
                    setLocalTranslationY(object, value);
                }

                @Override
                public Integer get(View object) {
                    return getLocalTranslationY(object);
                }
            };

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        int oldHeight = oldBottom - oldTop;
        Integer layoutTop = (Integer) v.getTag(TAG_LAYOUT_TOP);
        if (layoutTop != null) {
            oldTop = layoutTop;
        }
        int topChange = oldTop - top;
        if (oldHeight == 0 || topChange == 0 || !v.isShown() || isGone(v)) {
            // First layout
            return;
        }
        if (layoutTop != null) {
            v.setTagInternal(TAG_LAYOUT_TOP, top);
        }
        int newHeight = bottom - top;
        int heightDifference = oldHeight - newHeight;
        // Only add the difference if the height changes and it's getting smaller
        heightDifference = Math.max(heightDifference, 0);
        startLocalTranslationFrom(v, topChange + heightDifference + getLocalTranslationY(v));
    }

    private boolean isGone(View view) {
        if (view.getVisibility() == View.GONE) {
            return true;
        }
        final ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof MessagingLinearLayout.LayoutParams
                && ((MessagingLinearLayout.LayoutParams) lp).hide) {
            return true;
        }
        return false;
    }

    public static void startLocalTranslationFrom(View v, int startTranslation) {
        startLocalTranslationFrom(v, startTranslation, MessagingLayout.FAST_OUT_SLOW_IN);
    }

    public static void startLocalTranslationFrom(View v, int startTranslation,
            Interpolator interpolator) {
        startLocalTranslation(v, startTranslation, 0, interpolator);
    }

    public static void startLocalTranslationTo(View v, int endTranslation,
            Interpolator interpolator) {
        startLocalTranslation(v, getLocalTranslationY(v), endTranslation, interpolator);
    }

    public static int getLocalTranslationY(View v) {
        Integer tag = (Integer) v.getTag(TAG_LOCAL_TRANSLATION_Y);
        if (tag == null) {
            return 0;
        }
        return tag;
    }

    private static void setLocalTranslationY(View v, int value) {
        v.setTagInternal(TAG_LOCAL_TRANSLATION_Y, value);
        updateTopAndBottom(v);
    }

    private static void updateTopAndBottom(View v) {
        int layoutTop = (int) v.getTag(TAG_LAYOUT_TOP);
        int localTranslation = getLocalTranslationY(v);
        int height = v.getHeight();
        v.setTop(layoutTop + localTranslation);
        v.setBottom(layoutTop + height + localTranslation);
    }

    private static void startLocalTranslation(final View v, int start, int end,
            Interpolator interpolator) {
        ObjectAnimator existing = (ObjectAnimator) v.getTag(TAG_LOCAL_TRANSLATION_ANIMATOR);
        if (existing != null) {
            existing.cancel();
        }
        ObjectAnimator animator = ObjectAnimator.ofInt(v, LOCAL_TRANSLATION_Y, start, end);
        Integer layoutTop = (Integer) v.getTag(TAG_LAYOUT_TOP);
        if (layoutTop == null) {
            layoutTop = v.getTop();
            v.setTagInternal(TAG_LAYOUT_TOP, layoutTop);
        }
        setLocalTranslationY(v, start);
        animator.setInterpolator(interpolator);
        animator.setDuration(APPEAR_ANIMATION_LENGTH);
        animator.addListener(new AnimatorListenerAdapter() {
            public boolean mCancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                v.setTagInternal(TAG_LOCAL_TRANSLATION_ANIMATOR, null);
                setClippingDeactivated(v, false);
                if (!mCancelled) {
                    setLocalTranslationY(v, 0);
                    v.setTagInternal(TAG_LAYOUT_TOP, null);
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }
        });
        setClippingDeactivated(v, true);
        v.setTagInternal(TAG_LOCAL_TRANSLATION_ANIMATOR, animator);
        animator.start();
    }

    public static void fadeIn(final View v) {
        ObjectAnimator existing = (ObjectAnimator) v.getTag(TAG_ALPHA_ANIMATOR);
        if (existing != null) {
            existing.cancel();
        }
        if (v.getVisibility() == View.INVISIBLE) {
            v.setVisibility(View.VISIBLE);
        }
        ObjectAnimator animator = ObjectAnimator.ofFloat(v, View.ALPHA,
                0.0f, 1.0f);
        v.setAlpha(0.0f);
        animator.setInterpolator(ALPHA_IN);
        animator.setDuration(APPEAR_ANIMATION_LENGTH);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                v.setTagInternal(TAG_ALPHA_ANIMATOR, null);
                updateLayerType(v, false /* animating */);
            }
        });
        updateLayerType(v, true /* animating */);
        v.setTagInternal(TAG_ALPHA_ANIMATOR, animator);
        animator.start();
    }

    private static void updateLayerType(View view, boolean animating) {
        if (view.hasOverlappingRendering() && animating) {
            view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        } else if (view.getLayerType() == View.LAYER_TYPE_HARDWARE) {
            view.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    public static void fadeOut(final View view, Runnable endAction) {
        ObjectAnimator existing = (ObjectAnimator) view.getTag(TAG_ALPHA_ANIMATOR);
        if (existing != null) {
            existing.cancel();
        }
        ObjectAnimator animator = ObjectAnimator.ofFloat(view, View.ALPHA,
                view.getAlpha(), 0.0f);
        animator.setInterpolator(ALPHA_OUT);
        animator.setDuration(APPEAR_ANIMATION_LENGTH);
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setTagInternal(TAG_ALPHA_ANIMATOR, null);
                updateLayerType(view, false /* animating */);
                if (endAction != null) {
                    endAction.run();
                }
            }
        });
        updateLayerType(view, true /* animating */);
        view.setTagInternal(TAG_ALPHA_ANIMATOR, animator);
        animator.start();
    }

    public static void setClippingDeactivated(final View transformedView, boolean deactivated) {
        ViewClippingUtil.setClippingDeactivated(transformedView, deactivated,
                CLIPPING_PARAMETERS);
    }

    public static boolean isAnimatingTranslation(View v) {
        return v.getTag(TAG_LOCAL_TRANSLATION_ANIMATOR) != null;
    }

    public static boolean isAnimatingAlpha(View v) {
        return v.getTag(TAG_ALPHA_ANIMATOR) != null;
    }
}
