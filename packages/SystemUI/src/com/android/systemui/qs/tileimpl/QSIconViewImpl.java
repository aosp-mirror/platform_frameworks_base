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

import static com.android.systemui.qs.tileimpl.QSTileImpl.getColorForState;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.drawable.Animatable2;
import android.graphics.drawable.Animatable2.AnimationCallback;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;

import com.android.systemui.R;
import com.android.systemui.plugins.qs.QSIconView;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.plugins.qs.QSTile.State;
import com.android.systemui.qs.AlphaControlledSignalTileView.AlphaControlledSlashImageView;

import java.util.Objects;

public class QSIconViewImpl extends QSIconView {

    public static final long QS_ANIM_LENGTH = 350;

    protected final View mIcon;
    protected final int mIconSizePx;
    private boolean mAnimationEnabled = true;
    private int mState = -1;
    private int mTint;
    private QSTile.Icon mLastIcon;

    public QSIconViewImpl(Context context) {
        super(context);

        final Resources res = context.getResources();
        mIconSizePx = res.getDimensionPixelSize(R.dimen.qs_tile_icon_size);

        mIcon = createIcon();
        addView(mIcon);
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
                if (state.isTransient) {
                    a.registerAnimationCallback(new AnimationCallback() {
                        @Override
                        public void onAnimationEnd(Drawable drawable) {
                            a.start();
                        }
                    });
                }
            }
        }
    }

    private boolean shouldAnimate(ImageView iv) {
        return mAnimationEnabled && iv.isShown() && iv.getDrawable() != null;
    }

    protected void setIcon(ImageView iv, QSTile.State state, boolean allowAnimations) {
        if (state.disabledByPolicy) {
            iv.setColorFilter(getContext().getColor(R.color.qs_tile_disabled_color));
        } else {
            iv.clearColorFilter();
        }
        if (state.state != mState) {
            int color = getColor(state.state);
            mState = state.state;
            if (mTint != 0 && allowAnimations && shouldAnimate(iv)) {
                animateGrayScale(mTint, color, iv, () -> updateIcon(iv, state, allowAnimations));
                mTint = color;
            } else {
                if (iv instanceof AlphaControlledSlashImageView) {
                    ((AlphaControlledSlashImageView)iv)
                            .setFinalImageTintList(ColorStateList.valueOf(color));
                } else {
                    setTint(iv, color);
                }
                mTint = color;
                updateIcon(iv, state, allowAnimations);
            }
        } else {
            updateIcon(iv, state, allowAnimations);
        }
    }

    protected int getColor(int state) {
        return getColorForState(getContext(), state);
    }

    private void animateGrayScale(int fromColor, int toColor, ImageView iv,
        final Runnable endRunnable) {
        if (iv instanceof AlphaControlledSlashImageView) {
            ((AlphaControlledSlashImageView)iv)
                    .setFinalImageTintList(ColorStateList.valueOf(toColor));
        }
        if (mAnimationEnabled && ValueAnimator.areAnimatorsEnabled()) {
            final float fromAlpha = Color.alpha(fromColor);
            final float toAlpha = Color.alpha(toColor);
            final float fromChannel = Color.red(fromColor);
            final float toChannel = Color.red(toColor);

            ValueAnimator anim = ValueAnimator.ofFloat(0, 1);
            anim.setDuration(QS_ANIM_LENGTH);
            anim.addUpdateListener(animation -> {
                float fraction = animation.getAnimatedFraction();
                int alpha = (int) (fromAlpha + (toAlpha - fromAlpha) * fraction);
                int channel = (int) (fromChannel + (toChannel - fromChannel) * fraction);

                setTint(iv, Color.argb(alpha, channel, channel, channel));
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    endRunnable.run();
                }
            });
            anim.start();
        } else {
            setTint(iv, toColor);
            endRunnable.run();
        }
    }

    public static void setTint(ImageView iv, int color) {
        iv.setImageTintList(ColorStateList.valueOf(color));
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
}
