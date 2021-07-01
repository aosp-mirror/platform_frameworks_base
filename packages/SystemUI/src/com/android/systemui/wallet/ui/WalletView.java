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

import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.settingslib.Utils;
import com.android.systemui.R;

import java.util.List;

/** Layout for the wallet screen. */
public class WalletView extends FrameLayout implements WalletCardCarousel.OnCardScrollListener {

    private static final String TAG = "WalletView";
    private static final int CAROUSEL_IN_ANIMATION_DURATION = 100;
    private static final int CAROUSEL_OUT_ANIMATION_DURATION = 200;

    private final WalletCardCarousel mCardCarousel;
    private final ImageView mIcon;
    private final TextView mCardLabel;
    // Displays at the bottom of the screen, allow user to enter the default wallet app.
    private final Button mAppButton;
    // Displays on the top right of the screen, allow user to enter the default wallet app.
    private final Button mToolbarAppButton;
    // Displays underneath the carousel, allow user to unlock device, verify card, etc.
    private final Button mActionButton;
    private final Interpolator mOutInterpolator;
    private final float mAnimationTranslationX;
    private final ViewGroup mCardCarouselContainer;
    private final TextView mErrorView;
    private final ViewGroup mEmptyStateView;
    private boolean mIsDeviceLocked = false;
    private boolean mIsUdfpsEnabled = false;
    private OnClickListener mDeviceLockedActionOnClickListener;
    private OnClickListener mShowWalletAppOnClickListener;

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
        mAppButton = requireViewById(R.id.wallet_app_button);
        mToolbarAppButton = requireViewById(R.id.wallet_toolbar_app_button);
        mActionButton = requireViewById(R.id.wallet_action_button);
        mErrorView = requireViewById(R.id.error_view);
        mEmptyStateView = requireViewById(R.id.wallet_empty_state);
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
    protected void onConfigurationChanged(Configuration newConfig) {
        updateViewForOrientation(newConfig.orientation);
    }

    private void updateViewForOrientation(@Configuration.Orientation int orientation) {
        if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            renderViewPortrait();
        } else if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            renderViewLandscape();
        }
        ViewGroup.LayoutParams params = mCardCarouselContainer.getLayoutParams();
        if (params instanceof MarginLayoutParams) {
            ((MarginLayoutParams) params).topMargin =
                    getResources().getDimensionPixelSize(
                            R.dimen.wallet_card_carousel_container_top_margin);
        }
    }

    private void renderViewPortrait() {
        mAppButton.setVisibility(VISIBLE);
        mToolbarAppButton.setVisibility(GONE);
        mCardLabel.setVisibility(VISIBLE);
        requireViewById(R.id.dynamic_placeholder).setVisibility(VISIBLE);

        mAppButton.setOnClickListener(mShowWalletAppOnClickListener);
    }

    private void renderViewLandscape() {
        mToolbarAppButton.setVisibility(VISIBLE);
        mAppButton.setVisibility(GONE);
        mCardLabel.setVisibility(GONE);
        requireViewById(R.id.dynamic_placeholder).setVisibility(GONE);

        mToolbarAppButton.setOnClickListener(mShowWalletAppOnClickListener);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Forward touch events to card carousel to allow for swiping outside carousel bounds.
        return mCardCarousel.onTouchEvent(event) || super.onTouchEvent(event);
    }

    @Override
    public void onCardScroll(WalletCardViewInfo centerCard, WalletCardViewInfo nextCard,
            float percentDistanceFromCenter) {
        CharSequence centerCardText = getLabelText(centerCard);
        Drawable centerCardIcon = getHeaderIcon(mContext, centerCard);
        renderActionButton(centerCard, mIsDeviceLocked, mIsUdfpsEnabled);
        if (centerCard.isUiEquivalent(nextCard)) {
            mCardLabel.setAlpha(1f);
            mIcon.setAlpha(1f);
            mActionButton.setAlpha(1f);
        } else {
            mCardLabel.setText(centerCardText);
            mIcon.setImageDrawable(centerCardIcon);
            mCardLabel.setAlpha(percentDistanceFromCenter);
            mIcon.setAlpha(percentDistanceFromCenter);
            mActionButton.setAlpha(percentDistanceFromCenter);
        }
    }

    /**
     * Render and show card carousel view.
     *
     * <p>This is called only when {@param data} is not empty.</p>
     *
     * @param data a list of wallet cards information.
     * @param selectedIndex index of the current selected card
     * @param isDeviceLocked indicates whether the device is locked.
     */
    void showCardCarousel(
            List<WalletCardViewInfo> data,
            int selectedIndex,
            boolean isDeviceLocked,
            boolean isUdfpsEnabled) {
        boolean shouldAnimate =
                mCardCarousel.setData(data, selectedIndex, mIsDeviceLocked != isDeviceLocked);
        mIsDeviceLocked = isDeviceLocked;
        mIsUdfpsEnabled = isUdfpsEnabled;
        mCardCarouselContainer.setVisibility(VISIBLE);
        mCardCarousel.setVisibility(VISIBLE);
        mErrorView.setVisibility(GONE);
        mEmptyStateView.setVisibility(GONE);
        mIcon.setImageDrawable(getHeaderIcon(mContext, data.get(selectedIndex)));
        mCardLabel.setText(getLabelText(data.get(selectedIndex)));
        updateViewForOrientation(getResources().getConfiguration().orientation);
        renderActionButton(data.get(selectedIndex), isDeviceLocked, mIsUdfpsEnabled);
        if (shouldAnimate) {
            animateViewsShown(mIcon, mCardLabel, mActionButton);
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
        mCardCarousel.setVisibility(GONE);
        mIcon.setImageDrawable(logo);
        mIcon.setContentDescription(logoContentDescription);
        mCardLabel.setText(R.string.wallet_empty_state_label);
        ImageView logoView = mEmptyStateView.requireViewById(R.id.empty_state_icon);
        logoView.setImageDrawable(mContext.getDrawable(R.drawable.ic_qs_plus));
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

    void setDeviceLockedActionOnClickListener(OnClickListener onClickListener) {
        mDeviceLockedActionOnClickListener = onClickListener;
    }

    void setShowWalletAppOnClickListener(OnClickListener onClickListener) {
        mShowWalletAppOnClickListener = onClickListener;
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

    Button getActionButton() {
        return mActionButton;
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

    @VisibleForTesting
    TextView getCardLabel() {
        return mCardLabel;
    }

    @Nullable
    private static Drawable getHeaderIcon(Context context, WalletCardViewInfo walletCard) {
        Drawable icon = walletCard.getIcon();
        if (icon != null) {
            icon.setTint(
                    Utils.getColorAttrDefaultColor(
                            context, com.android.internal.R.attr.colorAccentPrimary));
        }
        return icon;
    }

    private void renderActionButton(
            WalletCardViewInfo walletCard, boolean isDeviceLocked, boolean isUdfpsEnabled) {
        CharSequence actionButtonText = getActionButtonText(walletCard);
        if (!isUdfpsEnabled && actionButtonText != null) {
            mActionButton.setVisibility(VISIBLE);
            mActionButton.setText(actionButtonText);
            mActionButton.setOnClickListener(
                    isDeviceLocked
                            ? mDeviceLockedActionOnClickListener
                            : v -> {
                        try {
                            walletCard.getPendingIntent().send();
                        } catch (PendingIntent.CanceledException e) {
                            Log.w(TAG, "Error sending pending intent for wallet card.");
                        }
                    }
            );
        } else {
            mActionButton.setVisibility(GONE);
        }
    }

    private static void animateViewsShown(View... uiElements) {
        for (View view : uiElements) {
            if (view.getVisibility() == VISIBLE) {
                view.setAlpha(0f);
                view.animate().alpha(1f).setDuration(CAROUSEL_IN_ANIMATION_DURATION).start();
            }
        }
    }

    private static CharSequence getLabelText(WalletCardViewInfo card) {
        String[] rawLabel = card.getLabel().toString().split("\\n");
        return rawLabel.length == 2 ? rawLabel[0] : card.getLabel();
    }

    @Nullable
    private static CharSequence getActionButtonText(WalletCardViewInfo card) {
        String[] rawLabel = card.getLabel().toString().split("\\n");
        return rawLabel.length == 2 ? rawLabel[1] : null;
    }
}
