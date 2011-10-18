/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.recent;

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.android.systemui.R;

public class RecentsScrollViewPerformanceHelper {
    public static final boolean OPTIMIZE_SW_RENDERED_RECENTS = true;
    public static final boolean USE_DARK_FADE_IN_HW_ACCELERATED_MODE = true;
    private View mScrollView;
    private LinearLayout mLinearLayout;
    private RecentsCallback mCallback;

    private boolean mShowBackground = false;
    private int mFadingEdgeLength;
    private Drawable.ConstantState mBackgroundDrawable;
    private Context mContext;
    private boolean mIsVertical;
    private boolean mFirstTime = true;
    private boolean mSoftwareRendered = false;
    private boolean mAttachedToWindow = false;

    public static RecentsScrollViewPerformanceHelper create(Context context,
            AttributeSet attrs, View scrollView, boolean isVertical) {
        boolean isTablet = context.getResources().
                getBoolean(R.bool.config_recents_interface_for_tablets);
        if (!isTablet && (OPTIMIZE_SW_RENDERED_RECENTS || USE_DARK_FADE_IN_HW_ACCELERATED_MODE)) {
            return new RecentsScrollViewPerformanceHelper(context, attrs, scrollView, isVertical);
        } else {
            return null;
        }
    }

    public RecentsScrollViewPerformanceHelper(Context context,
            AttributeSet attrs, View scrollView, boolean isVertical) {
        mScrollView = scrollView;
        mContext = context;
        TypedArray a = context.obtainStyledAttributes(attrs, com.android.internal.R.styleable.View);
        mFadingEdgeLength = a.getDimensionPixelSize(android.R.styleable.View_fadingEdgeLength,
                ViewConfiguration.get(context).getScaledFadingEdgeLength());
        mIsVertical = isVertical;
    }

    public void onAttachedToWindowCallback(
            RecentsCallback callback, LinearLayout layout, boolean hardwareAccelerated) {
        mSoftwareRendered = !hardwareAccelerated;
        if ((mSoftwareRendered && OPTIMIZE_SW_RENDERED_RECENTS)
                || USE_DARK_FADE_IN_HW_ACCELERATED_MODE) {
            mScrollView.setVerticalFadingEdgeEnabled(false);
            mScrollView.setHorizontalFadingEdgeEnabled(false);
        }
        if (mSoftwareRendered && OPTIMIZE_SW_RENDERED_RECENTS) {
            mCallback = callback;
            mLinearLayout = layout;
            mAttachedToWindow = true;
            mBackgroundDrawable = mContext.getResources()
                .getDrawable(R.drawable.status_bar_recents_background_solid).getConstantState();
            updateShowBackground();
        }

    }

    public void addViewCallback(View newLinearLayoutChild) {
        if (mSoftwareRendered && OPTIMIZE_SW_RENDERED_RECENTS) {
            final View view = newLinearLayoutChild;
            if (mShowBackground) {
                view.setBackgroundDrawable(mBackgroundDrawable.newDrawable());
                view.setDrawingCacheEnabled(true);
                view.buildDrawingCache();
            } else {
                view.setBackgroundDrawable(null);
                view.setDrawingCacheEnabled(false);
                view.destroyDrawingCache();
            }
        }
    }

    public void onLayoutCallback() {
        if (mSoftwareRendered && OPTIMIZE_SW_RENDERED_RECENTS) {
            mScrollView.post(new Runnable() {
                public void run() {
                    updateShowBackground();
                }
            });
        }
    }

    public void drawCallback(Canvas canvas,
            int left, int right, int top, int bottom, int scrollX, int scrollY,
            float topFadingEdgeStrength, float bottomFadingEdgeStrength,
            float leftFadingEdgeStrength, float rightFadingEdgeStrength) {
        if (mSoftwareRendered && OPTIMIZE_SW_RENDERED_RECENTS) {
            if (mIsVertical) {
                if (scrollY < 0) {
                    Drawable d = mBackgroundDrawable.newDrawable().getCurrent();
                    d.setBounds(0, scrollY, mScrollView.getWidth(), 0);
                    d.draw(canvas);
                } else {
                    final int childHeight = mLinearLayout.getHeight();
                    if (scrollY + mScrollView.getHeight() > childHeight) {
                        Drawable d = mBackgroundDrawable.newDrawable().getCurrent();
                        d.setBounds(0, childHeight, mScrollView.getWidth(),
                                scrollY + mScrollView.getHeight());
                        d.draw(canvas);
                    }
                }
            } else {
                if (scrollX < 0) {
                    Drawable d = mBackgroundDrawable.newDrawable().getCurrent();
                    d.setBounds(scrollX, 0, 0, mScrollView.getHeight());
                    d.draw(canvas);
                } else {
                    final int childWidth = mLinearLayout.getWidth();
                    if (scrollX + mScrollView.getWidth() > childWidth) {
                        Drawable d = mBackgroundDrawable.newDrawable().getCurrent();
                        d.setBounds(childWidth, 0,
                                scrollX + mScrollView.getWidth(), mScrollView.getHeight());
                        d.draw(canvas);
                    }
                }
            }
        }

        if ((mSoftwareRendered && OPTIMIZE_SW_RENDERED_RECENTS)
                || USE_DARK_FADE_IN_HW_ACCELERATED_MODE) {
            Paint p = new Paint();
            Matrix matrix = new Matrix();
            // use use a height of 1, and then wack the matrix each time we
            // actually use it.
            Shader fade = new LinearGradient(0, 0, 0, 1, 0xCC000000, 0, Shader.TileMode.CLAMP);
            // PULL OUT THIS CONSTANT

            p.setShader(fade);

            // draw the fade effect
            boolean drawTop = false;
            boolean drawBottom = false;
            boolean drawLeft = false;
            boolean drawRight = false;

            float topFadeStrength = 0.0f;
            float bottomFadeStrength = 0.0f;
            float leftFadeStrength = 0.0f;
            float rightFadeStrength = 0.0f;

            final float fadeHeight = mFadingEdgeLength;
            int length = (int) fadeHeight;

            // clip the fade length if top and bottom fades overlap
            // overlapping fades produce odd-looking artifacts
            if (mIsVertical && (top + length > bottom - length)) {
                length = (bottom - top) / 2;
            }

            // also clip horizontal fades if necessary
            if (!mIsVertical && (left + length > right - length)) {
                length = (right - left) / 2;
            }

            if (mIsVertical) {
                topFadeStrength = Math.max(0.0f, Math.min(1.0f, topFadingEdgeStrength));
                drawTop = topFadeStrength * fadeHeight > 1.0f;
                bottomFadeStrength = Math.max(0.0f, Math.min(1.0f, bottomFadingEdgeStrength));
                drawBottom = bottomFadeStrength * fadeHeight > 1.0f;
            }

            if (!mIsVertical) {
                leftFadeStrength = Math.max(0.0f, Math.min(1.0f, leftFadingEdgeStrength));
                drawLeft = leftFadeStrength * fadeHeight > 1.0f;
                rightFadeStrength = Math.max(0.0f, Math.min(1.0f, rightFadingEdgeStrength));
                drawRight = rightFadeStrength * fadeHeight > 1.0f;
            }

            if (drawTop) {
                matrix.setScale(1, fadeHeight * topFadeStrength);
                matrix.postTranslate(left, top);
                fade.setLocalMatrix(matrix);
                canvas.drawRect(left, top, right, top + length, p);
            }

            if (drawBottom) {
                matrix.setScale(1, fadeHeight * bottomFadeStrength);
                matrix.postRotate(180);
                matrix.postTranslate(left, bottom);
                fade.setLocalMatrix(matrix);
                canvas.drawRect(left, bottom - length, right, bottom, p);
            }

            if (drawLeft) {
                matrix.setScale(1, fadeHeight * leftFadeStrength);
                matrix.postRotate(-90);
                matrix.postTranslate(left, top);
                fade.setLocalMatrix(matrix);
                canvas.drawRect(left, top, left + length, bottom, p);
            }

            if (drawRight) {
                matrix.setScale(1, fadeHeight * rightFadeStrength);
                matrix.postRotate(90);
                matrix.postTranslate(right, top);
                fade.setLocalMatrix(matrix);
                canvas.drawRect(right - length, top, right, bottom, p);
            }
        }
    }

    public int getVerticalFadingEdgeLengthCallback() {
        return mFadingEdgeLength;
    }

    public int getHorizontalFadingEdgeLengthCallback() {
        return mFadingEdgeLength;
    }

    public void setLayoutTransitionCallback(LayoutTransition transition) {
        if (mSoftwareRendered && OPTIMIZE_SW_RENDERED_RECENTS) {
            if (transition != null) {
                transition.addTransitionListener(new LayoutTransition.TransitionListener() {
                    @Override
                    public void startTransition(LayoutTransition transition,
                            ViewGroup container, View view, int transitionType) {
                        updateShowBackground();
                    }

                    @Override
                    public void endTransition(LayoutTransition transition,
                            ViewGroup container, View view, int transitionType) {
                        updateShowBackground();
                    }
                });
            }
        }
    }

    // Turn on/off drawing the background in our ancestor, and turn on/off drawing
    // in the items in LinearLayout contained by this scrollview.
    // Moving the background drawing to our children, and turning on a drawing cache
    // for each of them, gives us a ~20fps gain when Recents is rendered in software
    public void updateShowBackground() {
        if (!mAttachedToWindow) {
            // We haven't been initialized yet-- we'll get called again when we are
            return;
        }
        if (mSoftwareRendered && OPTIMIZE_SW_RENDERED_RECENTS) {
            LayoutTransition transition = mLinearLayout.getLayoutTransition();
            int linearLayoutSize =
                mIsVertical ? mLinearLayout.getHeight() : mLinearLayout.getWidth();
            int scrollViewSize =
                mIsVertical ? mScrollView.getHeight() : mScrollView.getWidth();
            boolean show = !mScrollView.isHardwareAccelerated() &&
                (linearLayoutSize > scrollViewSize) &&
                !(transition != null && transition.isRunning()) &&
                mCallback.isRecentsVisible();

            if (!mFirstTime && show == mShowBackground) return;
            mShowBackground = show;
            mFirstTime = false;

            mCallback.handleShowBackground(!show);
            for (int i = 0; i < mLinearLayout.getChildCount(); i++) {
                View v = mLinearLayout.getChildAt(i);
                if (show) {
                    v.setBackgroundDrawable(mBackgroundDrawable.newDrawable());
                    v.setDrawingCacheEnabled(true);
                    v.buildDrawingCache();
                } else {
                    v.setDrawingCacheEnabled(false);
                    v.destroyDrawingCache();
                    v.setBackgroundDrawable(null);
                }
            }
        }
    }

}
