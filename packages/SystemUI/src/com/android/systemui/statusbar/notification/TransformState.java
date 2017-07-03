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
import android.util.Pools;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;

/**
 * A transform state of a view.
*/
public class TransformState {

    public static final int TRANSFORM_X = 0x1;
    public static final int TRANSFORM_Y = 0x10;
    public static final int TRANSFORM_ALL = TRANSFORM_X | TRANSFORM_Y;

    private static final float UNDEFINED = -1f;
    private static final int CLIP_CLIPPING_SET = R.id.clip_children_set_tag;
    private static final int CLIP_CHILDREN_TAG = R.id.clip_children_tag;
    private static final int CLIP_TO_PADDING = R.id.clip_to_padding_tag;
    private static final int TRANSFORMATION_START_X = R.id.transformation_start_x_tag;
    private static final int TRANSFORMATION_START_Y = R.id.transformation_start_y_tag;
    private static final int TRANSFORMATION_START_SCLALE_X = R.id.transformation_start_scale_x_tag;
    private static final int TRANSFORMATION_START_SCLALE_Y = R.id.transformation_start_scale_y_tag;
    private static Pools.SimplePool<TransformState> sInstancePool = new Pools.SimplePool<>(40);

    protected View mTransformedView;
    private int[] mOwnPosition = new int[2];
    private boolean mSameAsAny;
    private float mTransformationEndY = UNDEFINED;
    private float mTransformationEndX = UNDEFINED;

    public void initFrom(View view) {
        mTransformedView = view;
    }

    /**
     * Transforms the {@link #mTransformedView} from the given transformviewstate
     * @param otherState the state to transform from
     * @param transformationAmount how much to transform
     */
    public void transformViewFrom(TransformState otherState, float transformationAmount) {
        mTransformedView.animate().cancel();
        if (sameAs(otherState)) {
            if (mTransformedView.getVisibility() == View.INVISIBLE
                    || mTransformedView.getAlpha() != 1.0f) {
                // We have the same content, lets show ourselves
                mTransformedView.setAlpha(1.0f);
                mTransformedView.setVisibility(View.VISIBLE);
            }
        } else {
            CrossFadeHelper.fadeIn(mTransformedView, transformationAmount);
        }
        transformViewFullyFrom(otherState, transformationAmount);
    }

    public void transformViewFullyFrom(TransformState otherState, float transformationAmount) {
        transformViewFrom(otherState, TRANSFORM_ALL, null, transformationAmount);
    }

    public void transformViewFullyFrom(TransformState otherState,
            ViewTransformationHelper.CustomTransformation customTransformation,
            float transformationAmount) {
        transformViewFrom(otherState, TRANSFORM_ALL, customTransformation, transformationAmount);
    }

    public void transformViewVerticalFrom(TransformState otherState,
            ViewTransformationHelper.CustomTransformation customTransformation,
            float transformationAmount) {
        transformViewFrom(otherState, TRANSFORM_Y, customTransformation, transformationAmount);
    }

    public void transformViewVerticalFrom(TransformState otherState, float transformationAmount) {
        transformViewFrom(otherState, TRANSFORM_Y, null, transformationAmount);
    }

    private void transformViewFrom(TransformState otherState, int transformationFlags,
            ViewTransformationHelper.CustomTransformation customTransformation,
            float transformationAmount) {
        final View transformedView = mTransformedView;
        boolean transformX = (transformationFlags & TRANSFORM_X) != 0;
        boolean transformY = (transformationFlags & TRANSFORM_Y) != 0;
        boolean transformScale = transformScale(otherState);
        // lets animate the positions correctly
        if (transformationAmount == 0.0f
                || transformX && getTransformationStartX() == UNDEFINED
                || transformY && getTransformationStartY() == UNDEFINED
                || transformScale && getTransformationStartScaleX() == UNDEFINED
                || transformScale && getTransformationStartScaleY() == UNDEFINED) {
            int[] otherPosition;
            if (transformationAmount != 0.0f) {
                otherPosition = otherState.getLaidOutLocationOnScreen();
            } else {
                otherPosition = otherState.getLocationOnScreen();
            }
            int[] ownStablePosition = getLaidOutLocationOnScreen();
            if (customTransformation == null
                    || !customTransformation.initTransformation(this, otherState)) {
                if (transformX) {
                    setTransformationStartX(otherPosition[0] - ownStablePosition[0]);
                }
                if (transformY) {
                    setTransformationStartY(otherPosition[1] - ownStablePosition[1]);
                }
                // we also want to animate the scale if we're the same
                View otherView = otherState.getTransformedView();
                if (transformScale && otherState.getViewWidth() != getViewWidth()) {
                    setTransformationStartScaleX(otherState.getViewWidth() * otherView.getScaleX()
                            / (float) getViewWidth());
                    transformedView.setPivotX(0);
                } else {
                    setTransformationStartScaleX(UNDEFINED);
                }
                if (transformScale && otherState.getViewHeight() != getViewHeight()) {
                    setTransformationStartScaleY(otherState.getViewHeight() * otherView.getScaleY()
                            / (float) getViewHeight());
                    transformedView.setPivotY(0);
                } else {
                    setTransformationStartScaleY(UNDEFINED);
                }
            }
            if (!transformX) {
                setTransformationStartX(UNDEFINED);
            }
            if (!transformY) {
                setTransformationStartY(UNDEFINED);
            }
            if (!transformScale) {
                setTransformationStartScaleX(UNDEFINED);
                setTransformationStartScaleY(UNDEFINED);
            }
            setClippingDeactivated(transformedView, true);
        }
        float interpolatedValue = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                transformationAmount);
        if (transformX) {
            float interpolation = interpolatedValue;
            if (customTransformation != null) {
                Interpolator customInterpolator =
                        customTransformation.getCustomInterpolator(TRANSFORM_X, true /* isFrom */);
                if (customInterpolator != null) {
                    interpolation = customInterpolator.getInterpolation(transformationAmount);
                }
            }
            transformedView.setTranslationX(NotificationUtils.interpolate(getTransformationStartX(),
                    0.0f,
                    interpolation));
        }
        if (transformY) {
            float interpolation = interpolatedValue;
            if (customTransformation != null) {
                Interpolator customInterpolator =
                        customTransformation.getCustomInterpolator(TRANSFORM_Y, true /* isFrom */);
                if (customInterpolator != null) {
                    interpolation = customInterpolator.getInterpolation(transformationAmount);
                }
            }
            transformedView.setTranslationY(NotificationUtils.interpolate(getTransformationStartY(),
                    0.0f,
                    interpolation));
        }
        if (transformScale) {
            float transformationStartScaleX = getTransformationStartScaleX();
            if (transformationStartScaleX != UNDEFINED) {
                transformedView.setScaleX(
                        NotificationUtils.interpolate(transformationStartScaleX,
                                1.0f,
                                interpolatedValue));
            }
            float transformationStartScaleY = getTransformationStartScaleY();
            if (transformationStartScaleY != UNDEFINED) {
                transformedView.setScaleY(
                        NotificationUtils.interpolate(transformationStartScaleY,
                                1.0f,
                                interpolatedValue));
            }
        }
    }

    protected int getViewWidth() {
        return mTransformedView.getWidth();
    }

    protected int getViewHeight() {
        return mTransformedView.getHeight();
    }

    protected boolean transformScale(TransformState otherState) {
        return false;
    }

    /**
     * Transforms the {@link #mTransformedView} to the given transformviewstate
     * @param otherState the state to transform from
     * @param transformationAmount how much to transform
     * @return whether an animation was started
     */
    public boolean transformViewTo(TransformState otherState, float transformationAmount) {
        mTransformedView.animate().cancel();
        if (sameAs(otherState)) {
            // We have the same text, lets show ourselfs
            if (mTransformedView.getVisibility() == View.VISIBLE) {
                mTransformedView.setAlpha(0.0f);
                mTransformedView.setVisibility(View.INVISIBLE);
            }
            return false;
        } else {
            CrossFadeHelper.fadeOut(mTransformedView, transformationAmount);
        }
        transformViewFullyTo(otherState, transformationAmount);
        return true;
    }

    public void transformViewFullyTo(TransformState otherState, float transformationAmount) {
        transformViewTo(otherState, TRANSFORM_ALL, null, transformationAmount);
    }

    public void transformViewFullyTo(TransformState otherState,
            ViewTransformationHelper.CustomTransformation customTransformation,
            float transformationAmount) {
        transformViewTo(otherState, TRANSFORM_ALL, customTransformation, transformationAmount);
    }

    public void transformViewVerticalTo(TransformState otherState,
            ViewTransformationHelper.CustomTransformation customTransformation,
            float transformationAmount) {
        transformViewTo(otherState, TRANSFORM_Y, customTransformation, transformationAmount);
    }

    public void transformViewVerticalTo(TransformState otherState, float transformationAmount) {
        transformViewTo(otherState, TRANSFORM_Y, null, transformationAmount);
    }

    private void transformViewTo(TransformState otherState, int transformationFlags,
            ViewTransformationHelper.CustomTransformation customTransformation,
            float transformationAmount) {
        // lets animate the positions correctly

        final View transformedView = mTransformedView;
        boolean transformX = (transformationFlags & TRANSFORM_X) != 0;
        boolean transformY = (transformationFlags & TRANSFORM_Y) != 0;
        boolean transformScale = transformScale(otherState);
        // lets animate the positions correctly
        if (transformationAmount == 0.0f) {
            if (transformX) {
                float transformationStartX = getTransformationStartX();
                float start = transformationStartX != UNDEFINED ? transformationStartX
                        : transformedView.getTranslationX();
                setTransformationStartX(start);
            }
            if (transformY) {
                float transformationStartY = getTransformationStartY();
                float start = transformationStartY != UNDEFINED ? transformationStartY
                        : transformedView.getTranslationY();
                setTransformationStartY(start);
            }
            View otherView = otherState.getTransformedView();
            if (transformScale && otherState.getViewWidth() != getViewWidth()) {
                setTransformationStartScaleX(transformedView.getScaleX());
                transformedView.setPivotX(0);
            } else {
                setTransformationStartScaleX(UNDEFINED);
            }
            if (transformScale && otherState.getViewHeight() != getViewHeight()) {
                setTransformationStartScaleY(transformedView.getScaleY());
                transformedView.setPivotY(0);
            } else {
                setTransformationStartScaleY(UNDEFINED);
            }
            setClippingDeactivated(transformedView, true);
        }
        float interpolatedValue = Interpolators.FAST_OUT_SLOW_IN.getInterpolation(
                transformationAmount);
        int[] otherStablePosition = otherState.getLaidOutLocationOnScreen();
        int[] ownPosition = getLaidOutLocationOnScreen();
        if (transformX) {
            float endX = otherStablePosition[0] - ownPosition[0];
            float interpolation = interpolatedValue;
            if (customTransformation != null) {
                if (customTransformation.customTransformTarget(this, otherState)) {
                    endX = mTransformationEndX;
                }
                Interpolator customInterpolator =
                        customTransformation.getCustomInterpolator(TRANSFORM_X, false /* isFrom */);
                if (customInterpolator != null) {
                    interpolation = customInterpolator.getInterpolation(transformationAmount);
                }
            }
            transformedView.setTranslationX(NotificationUtils.interpolate(getTransformationStartX(),
                    endX,
                    interpolation));
        }
        if (transformY) {
            float endY = otherStablePosition[1] - ownPosition[1];
            float interpolation = interpolatedValue;
            if (customTransformation != null) {
                if (customTransformation.customTransformTarget(this, otherState)) {
                    endY = mTransformationEndY;
                }
                Interpolator customInterpolator =
                        customTransformation.getCustomInterpolator(TRANSFORM_Y, false /* isFrom */);
                if (customInterpolator != null) {
                    interpolation = customInterpolator.getInterpolation(transformationAmount);
                }
            }
            transformedView.setTranslationY(NotificationUtils.interpolate(getTransformationStartY(),
                    endY,
                    interpolation));
        }
        if (transformScale) {
            View otherView = otherState.getTransformedView();
            float transformationStartScaleX = getTransformationStartScaleX();
            if (transformationStartScaleX != UNDEFINED) {
                transformedView.setScaleX(
                        NotificationUtils.interpolate(transformationStartScaleX,
                                (otherState.getViewWidth() / (float) getViewWidth()),
                                interpolatedValue));
            }
            float transformationStartScaleY = getTransformationStartScaleY();
            if (transformationStartScaleY != UNDEFINED) {
                transformedView.setScaleY(
                        NotificationUtils.interpolate(transformationStartScaleY,
                                (otherState.getViewHeight() / (float) getViewHeight()),
                                interpolatedValue));
            }
        }
    }

    public static void setClippingDeactivated(final View transformedView, boolean deactivated) {
        if (!(transformedView.getParent() instanceof ViewGroup)) {
            return;
        }
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

    public int[] getLaidOutLocationOnScreen() {
        int[] location = getLocationOnScreen();
        // remove translation
        location[0] -= mTransformedView.getTranslationX();
        location[1] -= mTransformedView.getTranslationY();
        return location;
    }

    public int[] getLocationOnScreen() {
        mTransformedView.getLocationOnScreen(mOwnPosition);

        // remove scale
        mOwnPosition[0] -= (1.0f - mTransformedView.getScaleX()) * mTransformedView.getPivotX();
        mOwnPosition[1] -= (1.0f - mTransformedView.getScaleY()) * mTransformedView.getPivotY();
        return mOwnPosition;
    }

    protected boolean sameAs(TransformState otherState) {
        return mSameAsAny;
    }

    public void appear(float transformationAmount, TransformableView otherView) {
        // There's no other view, lets fade us in
        // Certain views need to prepare the fade in and make sure its children are
        // completely visible. An example is the notification header.
        if (transformationAmount == 0.0f) {
            prepareFadeIn();
        }
        CrossFadeHelper.fadeIn(mTransformedView, transformationAmount);
    }

    public void disappear(float transformationAmount, TransformableView otherView) {
        CrossFadeHelper.fadeOut(mTransformedView, transformationAmount);
    }

    public static TransformState createFrom(View view) {
        if (view instanceof TextView) {
            TextViewTransformState result = TextViewTransformState.obtain();
            result.initFrom(view);
            return result;
        }
        if (view.getId() == com.android.internal.R.id.actions_container) {
            ActionListTransformState result = ActionListTransformState.obtain();
            result.initFrom(view);
            return result;
        }
        if (view instanceof ImageView) {
            ImageTransformState result = ImageTransformState.obtain();
            result.initFrom(view);
            if (view.getId() == com.android.internal.R.id.reply_icon_action) {
                ((TransformState) result).setIsSameAsAnyView(true);
            }
            return result;
        }
        if (view instanceof ProgressBar) {
            ProgressTransformState result = ProgressTransformState.obtain();
            result.initFrom(view);
            return result;
        }
        TransformState result = obtain();
        result.initFrom(view);
        return result;
    }

    private void setIsSameAsAnyView(boolean sameAsAny) {
        mSameAsAny = sameAsAny;
    }

    public void recycle() {
        reset();
        if (getClass() == TransformState.class) {
            sInstancePool.release(this);
        }
    }

    public void setTransformationEndY(float transformationEndY) {
        mTransformationEndY = transformationEndY;
    }

    public void setTransformationEndX(float transformationEndX) {
        mTransformationEndX = transformationEndX;
    }

    public float getTransformationStartX() {
        Object tag = mTransformedView.getTag(TRANSFORMATION_START_X);
        return tag == null ? UNDEFINED : (float) tag;
    }

    public float getTransformationStartY() {
        Object tag = mTransformedView.getTag(TRANSFORMATION_START_Y);
        return tag == null ? UNDEFINED : (float) tag;
    }

    public float getTransformationStartScaleX() {
        Object tag = mTransformedView.getTag(TRANSFORMATION_START_SCLALE_X);
        return tag == null ? UNDEFINED : (float) tag;
    }

    public float getTransformationStartScaleY() {
        Object tag = mTransformedView.getTag(TRANSFORMATION_START_SCLALE_Y);
        return tag == null ? UNDEFINED : (float) tag;
    }

    public void setTransformationStartX(float transformationStartX) {
        mTransformedView.setTag(TRANSFORMATION_START_X, transformationStartX);
    }

    public void setTransformationStartY(float transformationStartY) {
        mTransformedView.setTag(TRANSFORMATION_START_Y, transformationStartY);
    }

    private void setTransformationStartScaleX(float startScaleX) {
        mTransformedView.setTag(TRANSFORMATION_START_SCLALE_X, startScaleX);
    }

    private void setTransformationStartScaleY(float startScaleY) {
        mTransformedView.setTag(TRANSFORMATION_START_SCLALE_Y, startScaleY);
    }

    protected void reset() {
        mTransformedView = null;
        mSameAsAny = false;
        mTransformationEndX = UNDEFINED;
        mTransformationEndY = UNDEFINED;
    }

    public void setVisible(boolean visible, boolean force) {
        if (!force && mTransformedView.getVisibility() == View.GONE) {
            return;
        }
        if (mTransformedView.getVisibility() != View.GONE) {
            mTransformedView.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        }
        mTransformedView.animate().cancel();
        mTransformedView.setAlpha(visible ? 1.0f : 0.0f);
        resetTransformedView();
    }

    public void prepareFadeIn() {
        resetTransformedView();
    }

    protected void resetTransformedView() {
        mTransformedView.setTranslationX(0);
        mTransformedView.setTranslationY(0);
        mTransformedView.setScaleX(1.0f);
        mTransformedView.setScaleY(1.0f);
        setClippingDeactivated(mTransformedView, false);
        abortTransformation();
    }

    public void abortTransformation() {
        mTransformedView.setTag(TRANSFORMATION_START_X, UNDEFINED);
        mTransformedView.setTag(TRANSFORMATION_START_Y, UNDEFINED);
        mTransformedView.setTag(TRANSFORMATION_START_SCLALE_X, UNDEFINED);
        mTransformedView.setTag(TRANSFORMATION_START_SCLALE_Y, UNDEFINED);
    }

    public static TransformState obtain() {
        TransformState instance = sInstancePool.acquire();
        if (instance != null) {
            return instance;
        }
        return new TransformState();
    }

    public View getTransformedView() {
        return mTransformedView;
    }
}
