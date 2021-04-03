/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.wallet.ui;

import static com.android.systemui.wallet.ui.WalletCardCarousel.CARD_ANIM_ALPHA_DELAY;
import static com.android.systemui.wallet.ui.WalletCardCarousel.CARD_ANIM_ALPHA_DURATION;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

import java.util.List;

/** Layout for the wallet screen. */
public class WalletView extends FrameLayout implements WalletCardCarousel.OnCardScrollListener {

    private static final int CAROUSEL_IN_ANIMATION_DURATION = 300;
    private static final int CAROUSEL_OUT_ANIMATION_DURATION = 200;
    private static final int CARD_LABEL_ANIM_DELAY = 133;
    private static final int CONTACTLESS_ICON_SIZE = 90;

    private final WalletCardCarousel mCardCarousel;
    private final ImageView mIcon;
    private final TextView mCardLabel;
    private final Button mWalletButton;
    private final Interpolator mInInterpolator;
    private final Interpolator mOutInterpolator;
    private final float mAnimationTranslationX;
    private final ViewGroup mCardCarouselContainer;
    private final TextView mErrorView;
    private final ViewGroup mEmptyStateView;
    private CharSequence mCenterCardText;

    public WalletView(Context context) {
        this(context, null);
    }

    public WalletView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inflate(context, R.layout.wallet_fullscreen, this);
        mCardCarouselContainer = requireViewById(R.id.card_carousel_container);
        mCardCarousel = requireViewById(R.id.card_carousel);
        mCardCarousel.setCardScrollListener(this);
        mIcon = requireViewById(R.id.icon);
        mCardLabel = requireViewById(R.id.label);
        mWalletButton = requireViewById(R.id.wallet_button);
        mErrorView = requireViewById(R.id.error_view);
        mEmptyStateView = requireViewById(R.id.wallet_empty_state);
        mInInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.fast_out_slow_in);
        mOutInterpolator =
                AnimationUtils.loadInterpolator(context, android.R.interpolator.accelerate_cubic);
        mAnimationTranslationX = mCardCarousel.getCardWidthPx() / 4f;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mCardCarousel.setExpectedViewWidth(getWidth());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Forward touch events to card carousel to allow for swiping outside carousel bounds.
        return mCardCarousel.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public void onCardScroll(WalletCardViewInfo centerCard, WalletCardViewInfo nextCard,
            float percentDistanceFromCenter) {
        CharSequence centerCardText = centerCard.getLabel();
        Drawable icon = centerCard.getIcon();
        if (icon != null) {
            mIcon.setImageDrawable(resizeDrawable(getResources(), icon));
            mIcon.setVisibility(VISIBLE);
        } else {
            mIcon.setVisibility(INVISIBLE);
        }
        if (!TextUtils.equals(mCenterCardText, centerCardText)) {
            mCenterCardText = centerCardText;
            mCardLabel.setText(centerCardText);
        }
        if (TextUtils.equals(centerCardText, nextCard.getLabel())) {
            mCardLabel.setAlpha(1f);
        } else {
            mCardLabel.setAlpha(percentDistanceFromCenter);
            mIcon.setAlpha(percentDistanceFromCenter);
        }
    }

    void showCardCarousel(
            List<WalletCardViewInfo> data, int selectedIndex, boolean isDeviceLocked) {
        boolean shouldAnimate = mCardCarousel.setData(data, selectedIndex);
        mCardCarouselContainer.setVisibility(VISIBLE);
        mErrorView.setVisibility(GONE);
        if (isDeviceLocked) {
            // TODO(b/182964813): Add click action to prompt device unlock.
            mWalletButton.setText(R.string.wallet_button_label_device_locked);
        } else {
            mWalletButton.setText(R.string.wallet_button_label_device_unlocked);
        }
        if (shouldAnimate) {
            // If the empty state is visible, animate it away and delay the card carousel animation
            int emptyStateAnimDelay = 0;
            if (mEmptyStateView.getVisibility() == VISIBLE) {
                emptyStateAnimDelay = CARD_ANIM_ALPHA_DURATION;
                mEmptyStateView.animate()
                        .alpha(0)
                        .setDuration(emptyStateAnimDelay)
                        .setListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationEnd(Animator animation) {
                                mEmptyStateView.setVisibility(GONE);
                            }
                        })
                        .start();
            }
            mCardLabel.setAlpha(0f);
            mCardLabel.animate().alpha(1f)
                    .setStartDelay(CARD_LABEL_ANIM_DELAY + emptyStateAnimDelay)
                    .setDuration(CARD_ANIM_ALPHA_DURATION)
                    .start();
            mCardCarousel.setExtraAnimationDelay(emptyStateAnimDelay);
            mCardCarousel.setTranslationX(mAnimationTranslationX);
            mCardCarousel.animate().translationX(0)
                    .setInterpolator(mInInterpolator)
                    .setDuration(CAROUSEL_IN_ANIMATION_DURATION)
                    .setStartDelay(emptyStateAnimDelay)
                    .start();
        }
    }

    void animateDismissal() {
        if (mCardCarouselContainer.getVisibility() != VISIBLE) {
            return;
        }
        mCardCarousel.animate().translationX(mAnimationTranslationX)
                .setInterpolator(mOutInterpolator)
                .setDuration(CAROUSEL_OUT_ANIMATION_DURATION)
                .start();
        mCardCarouselContainer.animate()
                .alpha(0f)
                .setDuration(CARD_ANIM_ALPHA_DURATION)
                .setStartDelay(CARD_ANIM_ALPHA_DELAY)
                .start();
    }

    void showEmptyStateView(Drawable logo, CharSequence logoContentDescription, CharSequence label,
            OnClickListener clickListener) {
        mEmptyStateView.setVisibility(VISIBLE);
        mErrorView.setVisibility(GONE);
        mCardCarouselContainer.setVisibility(GONE);
        ImageView logoView = mEmptyStateView.requireViewById(R.id.empty_state_icon);
        logoView.setImageDrawable(logo);
        logoView.setContentDescription(logoContentDescription);
        mEmptyStateView.<TextView>requireViewById(R.id.empty_state_title).setText(label);
        mEmptyStateView.setOnClickListener(clickListener);
    }

    void showErrorMessage(@Nullable CharSequence message) {
        if (TextUtils.isEmpty(message)) {
            message = getResources().getText(R.string.wallet_error_generic);
        }
        mErrorView.setText(message);
        mErrorView.setVisibility(VISIBLE);
        mCardCarouselContainer.setVisibility(GONE);
        mEmptyStateView.setVisibility(GONE);
    }

    void hide() {
        setVisibility(GONE);
    }

    void show() {
        setVisibility(VISIBLE);
    }

    void hideErrorMessage() {
        mErrorView.setVisibility(GONE);
    }

    WalletCardCarousel getCardCarousel() {
        return mCardCarousel;
    }

    Button getWalletButton() {
        return mWalletButton;
    }

    @VisibleForTesting
    TextView getErrorView() {
        return mErrorView;
    }

    @VisibleForTesting
    ViewGroup getEmptyStateView() {
        return mEmptyStateView;
    }

    @VisibleForTesting
    ViewGroup getCardCarouselContainer() {
        return mCardCarouselContainer;
    }

    private static Drawable resizeDrawable(Resources resources, Drawable drawable) {
        Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
        return new BitmapDrawable(resources, Bitmap.createScaledBitmap(
                bitmap, CONTACTLESS_ICON_SIZE, CONTACTLESS_ICON_SIZE, true));
    }
}
