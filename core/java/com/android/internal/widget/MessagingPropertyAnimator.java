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
    private static final long APPEAR_ANIMATION_LENGTH = 210;
    private static final Interpolator ALPHA_IN = new PathInterpolator(0.4f, 0f, 1f, 1f);
    public static final Interpolator ALPHA_OUT = new PathInterpolator(0f, 0f, 0.8f, 1f);
    private static final int TAG_TOP_ANIMATOR = R.id.tag_top_animator;
    private static final int TAG_TOP = R.id.tag_top_override;
    private static final int TAG_LAYOUT_TOP = R.id.tag_layout_top;
    private static final int TAG_FIRST_LAYOUT = R.id.tag_is_first_layout;
    private static final int TAG_ALPHA_ANIMATOR = R.id.tag_alpha_animator;
    private static final ViewClippingUtil.ClippingParameters CLIPPING_PARAMETERS =
            view -> view.getId() == com.android.internal.R.id.notification_messaging;
    private static final IntProperty<View> TOP =
            new IntProperty<View>("top") {
                @Override
                public void setValue(View object, int value) {
                    setTop(object, value);
                }

                @Override
                public Integer get(View object) {
                    return getTop(object);
                }
            };

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft,
            int oldTop, int oldRight, int oldBottom) {
        setLayoutTop(v, top);
        if (isFirstLayout(v)) {
            setFirstLayout(v, false /* first */);
            setTop(v, top);
            return;
        }
        startTopAnimation(v, getTop(v), top, MessagingLayout.FAST_OUT_SLOW_IN);
    }

    private static boolean isFirstLayout(View view) {
        Boolean tag = (Boolean) view.getTag(TAG_FIRST_LAYOUT);
        if (tag == null) {
            return true;
        }
        return tag;
    }

    public static void recycle(View view) {
        setFirstLayout(view, true /* first */);
    }

    private static void setFirstLayout(View view, boolean first) {
        view.setTagInternal(TAG_FIRST_LAYOUT, first);
    }

    private static void setLayoutTop(View view, int top) {
        view.setTagInternal(TAG_LAYOUT_TOP, top);
    }

    public static int getLayoutTop(View view) {
        Integer tag = (Integer) view.getTag(TAG_LAYOUT_TOP);
        if (tag == null) {
            return getTop(view);
        }
        return tag;
    }

    /**
     * Start a translation animation from a start offset to the laid out location
     * @param view The view to animate
     * @param startTranslation The starting translation to start from.
     * @param interpolator The interpolator to use.
     */
    public static void startLocalTranslationFrom(View view, int startTranslation,
            Interpolator interpolator) {
        startTopAnimation(view, getTop(view) + startTranslation, getLayoutTop(view), interpolator);
    }

    /**
     * Start a translation animation from a start offset to the laid out location
     * @param view The view to animate
     * @param endTranslation The end translation to go to.
     * @param interpolator The interpolator to use.
     */
    public static void startLocalTranslationTo(View view, int endTranslation,
            Interpolator interpolator) {
        int top = getTop(view);
        startTopAnimation(view, top, top + endTranslation, interpolator);
    }

    public static int getTop(View v) {
        Integer tag = (Integer) v.getTag(TAG_TOP);
        if (tag == null) {
            return v.getTop();
        }
        return tag;
    }

    private static void setTop(View v, int value) {
        v.setTagInternal(TAG_TOP, value);
        updateTopAndBottom(v);
    }

    private static void updateTopAndBottom(View v) {
        int top = getTop(v);
        int height = v.getHeight();
        v.setTop(top);
        v.setBottom(height + top);
    }

    private static void startTopAnimation(final View v, int start, int end,
            Interpolator interpolator) {
        ObjectAnimator existing = (ObjectAnimator) v.getTag(TAG_TOP_ANIMATOR);
        if (existing != null) {
            existing.cancel();
        }
        if (!v.isShown() || start == end
                || (MessagingLinearLayout.isGone(v) && !isHidingAnimated(v))) {
            setTop(v, end);
            return;
        }
        ObjectAnimator animator = ObjectAnimator.ofInt(v, TOP, start, end);
        setTop(v, start);
        animator.setInterpolator(interpolator);
        animator.setDuration(APPEAR_ANIMATION_LENGTH);
        animator.addListener(new AnimatorListenerAdapter() {
            public boolean mCancelled;

            @Override
            public void onAnimationEnd(Animator animation) {
                v.setTagInternal(TAG_TOP_ANIMATOR, null);
                setClippingDeactivated(v, false);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                mCancelled = true;
            }
        });
        setClippingDeactivated(v, true);
        v.setTagInternal(TAG_TOP_ANIMATOR, animator);
        animator.start();
    }

    private static boolean isHidingAnimated(View v) {
        if (v instanceof MessagingLinearLayout.MessagingChild) {
            return ((MessagingLinearLayout.MessagingChild) v).isHidingAnimated();
        }
        return false;
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
        if (!view.isShown() || (MessagingLinearLayout.isGone(view) && !isHidingAnimated(view))) {
            view.setAlpha(0.0f);
            if (endAction != null) {
                endAction.run();
            }
            return;
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
        return v.getTag(TAG_TOP_ANIMATOR) != null;
    }

    public static boolean isAnimatingAlpha(View v) {
        return v.getTag(TAG_ALPHA_ANIMATOR) != null;
    }

    public static void setToLaidOutPosition(View view) {
        setTop(view, getLayoutTop(view));
    }
}
