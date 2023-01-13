/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs.tileimpl;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.Animatable2.AnimationCallback;
import android.graphics.drawable.Drawable;
import android.service.quicksettings.Tile;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.AlphaControlledSignalTileView.AlphaControlledSlashImageView;

import java.util.Objects;

public class QSIconViewImpl extends QSIconView {

    public static final long QS_ANIM_LENGTH = 350;

    protected final View mIcon;
    protected int mIconSizePx;
    private boolean mAnimationEnabled = true;
    private int mState = -1;
    private boolean mDisabledByPolicy = false;
    private int mTint;
    @Nullable
    private QSTile.Icon mLastIcon;

    private ValueAnimator mColorAnimator = new ValueAnimator();

    public QSIconViewImpl(Context context) {
        super(context);

        final Resources res = context.getResources();
        mIconSizePx = res.getDimensionPixelSize(R.dimen.qs_icon_size);

        mIcon = createIcon();
        addView(mIcon);
        mColorAnimator.setDuration(QS_ANIM_LENGTH);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mIconSizePx = getContext().getResources().getDimensionPixelSize(R.dimen.qs_icon_size);
    }

    public void disableAnimation() {
        mAnimationEnabled = false;
    }

    public View getIconView() {
        return mIcon;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int w = MeasureSpec.getSize(widthMeasureSpec);
        final int iconSpec = exactly(mIconSizePx);
        mIcon.measure(MeasureSpec.makeMeasureSpec(w, getIconMeasureMode()), iconSpec);
        setMeasuredDimension(w, mIcon.getMeasuredHeight());
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(getClass().getSimpleName()).append('[');
        sb.append("state=" + mState);
        sb.append(", tint=" + mTint);
        if (mLastIcon != null) sb.append(", lastIcon=" + mLastIcon.toString());
        sb.append("]");
        return sb.toString();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        final int w = getMeasuredWidth();
        int top = 0;
        final int iconLeft = (w - mIcon.getMeasuredWidth()) / 2;
        layout(mIcon, iconLeft, top);
    }

    public void setIcon(State state, boolean allowAnimations) {
        setIcon((ImageView) mIcon, state, allowAnimations);
    }

    protected void updateIcon(ImageView iv, State state, boolean allowAnimations) {
        final QSTile.Icon icon = state.iconSupplier != null ? state.iconSupplier.get() : state.icon;
        if (!Objects.equals(icon, iv.getTag(R.id.qs_icon_tag))
                || !Objects.equals(state.slash, iv.getTag(R.id.qs_slash_tag))) {
            boolean shouldAnimate = allowAnimations && shouldAnimate(iv);
            mLastIcon = icon;
            Drawable d = icon != null
                    ? shouldAnimate ? icon.getDrawable(mContext)
                    : icon.getInvisibleDrawable(mContext) : null;
            int padding = icon != null ? icon.getPadding() : 0;
            if (d != null) {
                if (d.getConstantState() != null) {
                    d = d.getConstantState().newDrawable();
                }
                d.setAutoMirrored(false);
                d.setLayoutDirection(getLayoutDirection());
            }

            if (iv instanceof SlashImageView) {
                ((SlashImageView) iv).setAnimationEnabled(shouldAnimate);
                ((SlashImageView) iv).setState(null, d);
            } else {
                iv.setImageDrawable(d);
            }

            iv.setTag(R.id.qs_icon_tag, icon);
            iv.setTag(R.id.qs_slash_tag, state.slash);
            iv.setPadding(0, padding, 0, padding);
            if (d instanceof Animatable2) {
                Animatable2 a = (Animatable2) d;
                a.start();
                if (shouldAnimate) {
                    if (state.isTransient) {
                        a.registerAnimationCallback(new AnimationCallback() {
                            @Override
                            public void onAnimationEnd(Drawable drawable) {
                                a.start();
                            }
                        });
                    }
                } else {
                    // Sends animator to end of animation. Needs to be called after calling start.
                    a.stop();
                }
            }
        }
    }

    private boolean shouldAnimate(ImageView iv) {
        return mAnimationEnabled && iv.isShown() && iv.getDrawable() != null;
    }

    protected void setIcon(ImageView iv, QSTile.State state, boolean allowAnimations) {
        if (state.state != mState || state.disabledByPolicy != mDisabledByPolicy) {
            int color = getColor(state);
            mState = state.state;
            mDisabledByPolicy = state.disabledByPolicy;
            if (mTint != 0 && allowAnimations && shouldAnimate(iv)) {
                animateGrayScale(mTint, color, iv, () -> updateIcon(iv, state, allowAnimations));
            } else {
                if (iv instanceof AlphaControlledSlashImageView) {
                    ((AlphaControlledSlashImageView)iv)
                            .setFinalImageTintList(ColorStateList.valueOf(color));
                } else {
                    setTint(iv, color);
                }
                updateIcon(iv, state, allowAnimations);
            }
        } else {
            updateIcon(iv, state, allowAnimations);
        }
    }

    protected int getColor(QSTile.State state) {
        return getIconColorForState(getContext(), state);
    }

    private void animateGrayScale(int fromColor, int toColor, ImageView iv,
        final Runnable endRunnable) {
        if (iv instanceof AlphaControlledSlashImageView) {
            ((AlphaControlledSlashImageView)iv)
                    .setFinalImageTintList(ColorStateList.valueOf(toColor));
        }
        mColorAnimator.cancel();
        if (mAnimationEnabled && ValueAnimator.areAnimatorsEnabled()) {
            PropertyValuesHolder values = PropertyValuesHolder.ofInt("color", fromColor, toColor);
            values.setEvaluator(ArgbEvaluator.getInstance());
            mColorAnimator.setValues(values);
            mColorAnimator.removeAllListeners();
            mColorAnimator.addUpdateListener(animation -> {
                setTint(iv, (int) animation.getAnimatedValue());
            });
            mColorAnimator.addListener(new EndRunnableAnimatorListener(endRunnable));

            mColorAnimator.start();
        } else {

            setTint(iv, toColor);
            endRunnable.run();
        }
    }

    public void setTint(ImageView iv, int color) {
        iv.setImageTintList(ColorStateList.valueOf(color));
        mTint = color;
    }

    protected int getIconMeasureMode() {
        return MeasureSpec.EXACTLY;
    }

    protected View createIcon() {
        final ImageView icon = new SlashImageView(mContext);
        icon.setId(android.R.id.icon);
        icon.setScaleType(ScaleType.FIT_CENTER);
        return icon;
    }

    protected final int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }

    protected final void layout(View child, int left, int top) {
        child.layout(left, top, left + child.getMeasuredWidth(), top + child.getMeasuredHeight());
    }

    /**
     * Color to tint the tile icon based on state
     */
    private static int getIconColorForState(Context context, QSTile.State state) {
        if (state.disabledByPolicy || state.state == Tile.STATE_UNAVAILABLE) {
            return Utils.getColorAttrDefaultColor(
                    context, com.android.internal.R.attr.textColorTertiary);
        } else if (state.state == Tile.STATE_INACTIVE) {
            return Utils.getColorAttrDefaultColor(context, android.R.attr.textColorPrimary);
        } else if (state.state == Tile.STATE_ACTIVE) {
            return Utils.getColorAttrDefaultColor(context,
                    com.android.internal.R.attr.textColorOnAccent);
        } else {
            Log.e("QSIconView", "Invalid state " + state);
            return 0;
        }
    }

    private static class EndRunnableAnimatorListener extends AnimatorListenerAdapter {
        private Runnable mRunnable;

        EndRunnableAnimatorListener(Runnable endRunnable) {
            super();
            mRunnable = endRunnable;
        }

        @Override
        public void onAnimationCancel(Animator animation) {
            super.onAnimationCancel(animation);
            mRunnable.run();
        }

        @Override
        public void onAnimationEnd(Animator animation) {
            super.onAnimationEnd(animation);
            mRunnable.run();
        }
    }
}
