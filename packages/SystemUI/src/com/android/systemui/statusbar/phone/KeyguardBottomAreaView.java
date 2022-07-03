/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import static com.android.systemui.controls.dagger.ControlsComponent.Visibility.AVAILABLE;
import static com.android.systemui.doze.util.BurnInHelperKt.getBurnInOffset;
import static com.android.systemui.wallet.controller.QuickAccessWalletController.WalletChangeEvent.DEFAULT_PAYMENT_APP_CHANGE;
import static com.android.systemui.wallet.controller.QuickAccessWalletController.WalletChangeEvent.WALLET_PREFERENCE_CHANGE;

import android.app.ActivityTaskManager;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.RemoteException;
import android.os.UserHandle;
import android.service.quickaccesswallet.GetWalletCardsError;
import android.service.quickaccesswallet.GetWalletCardsResponse;
import android.service.quickaccesswallet.QuickAccessWalletClient;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.controls.dagger.ControlsComponent;
import com.android.systemui.controls.management.ControlsListingController;
import com.android.systemui.controls.ui.ControlsActivity;
import com.android.systemui.controls.ui.ControlsUiController;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qrcodescanner.controller.QRCodeScannerController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.wallet.controller.QuickAccessWalletController;

/**
 * Implementation for the bottom area of the Keyguard, including camera/phone affordance and status
 * text.
 */
public class KeyguardBottomAreaView extends FrameLayout {

    private static final String TAG = "CentralSurfaces/KeyguardBottomAreaView";
    private static final int DOZE_ANIMATION_ELEMENT_DURATION = 250;

    private ImageView mWalletButton;
    private ImageView mQRCodeScannerButton;
    private ImageView mControlsButton;
    private boolean mHasCard = false;
    private final WalletCardRetriever mCardRetriever = new WalletCardRetriever();
    private QuickAccessWalletController mQuickAccessWalletController;
    private QRCodeScannerController mQRCodeScannerController;
    private ControlsComponent mControlsComponent;
    private boolean mControlServicesAvailable = false;

    @Nullable private View mAmbientIndicationArea;
    private ViewGroup mIndicationArea;
    private TextView mIndicationText;
    private TextView mIndicationTextBottom;
    private ViewGroup mOverlayContainer;

    private ActivityStarter mActivityStarter;
    private KeyguardStateController mKeyguardStateController;
    private CentralSurfaces mCentralSurfaces;
    private FalsingManager mFalsingManager;

    private boolean mDozing;
    private int mIndicationBottomMargin;
    private int mIndicationPadding;
    private float mDarkAmount;
    private int mBurnInXOffset;
    private int mBurnInYOffset;

    private final ControlsListingController.ControlsListingCallback mListingCallback =
            serviceInfos -> post(() -> {
                boolean available = !serviceInfos.isEmpty();

                if (available != mControlServicesAvailable) {
                    mControlServicesAvailable = available;
                    updateControlsVisibility();
                    updateAffordanceColors();
                }
            });

    private final KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
        @Override
        public void onKeyguardShowingChanged() {
            if (mKeyguardStateController.isShowing()) {
                if (mQuickAccessWalletController != null) {
                    mQuickAccessWalletController.queryWalletCards(mCardRetriever);
                }
            }
        }
    };

    public KeyguardBottomAreaView(Context context) {
        this(context, null);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public KeyguardBottomAreaView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public void initFrom(KeyguardBottomAreaView oldBottomArea) {
        setCentralSurfaces(oldBottomArea.mCentralSurfaces);

        // if it exists, continue to use the original ambient indication container
        // instead of the newly inflated one
        if (mAmbientIndicationArea != null) {
            // remove old ambient indication from its parent
            View originalAmbientIndicationView =
                    oldBottomArea.findViewById(R.id.ambient_indication_container);
            ((ViewGroup) originalAmbientIndicationView.getParent())
                    .removeView(originalAmbientIndicationView);

            // remove current ambient indication from its parent (discard)
            ViewGroup ambientIndicationParent = (ViewGroup) mAmbientIndicationArea.getParent();
            int ambientIndicationIndex =
                    ambientIndicationParent.indexOfChild(mAmbientIndicationArea);
            ambientIndicationParent.removeView(mAmbientIndicationArea);

            // add the old ambient indication to this view
            ambientIndicationParent.addView(originalAmbientIndicationView, ambientIndicationIndex);
            mAmbientIndicationArea = originalAmbientIndicationView;

            // update burn-in offsets
            dozeTimeTick();
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mOverlayContainer = findViewById(R.id.overlay_container);
        mWalletButton = findViewById(R.id.wallet_button);
        mQRCodeScannerButton = findViewById(R.id.qr_code_scanner_button);
        mControlsButton = findViewById(R.id.controls_button);
        mIndicationArea = findViewById(R.id.keyguard_indication_area);
        mAmbientIndicationArea = findViewById(R.id.ambient_indication_container);
        mIndicationText = findViewById(R.id.keyguard_indication_text);
        mIndicationTextBottom = findViewById(R.id.keyguard_indication_text_bottom);
        mIndicationBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_margin_bottom);
        mBurnInYOffset = getResources().getDimensionPixelSize(
                R.dimen.default_burn_in_prevention_offset);
        mKeyguardStateController = Dependency.get(KeyguardStateController.class);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
        setClipChildren(false);
        setClipToPadding(false);
        mActivityStarter = Dependency.get(ActivityStarter.class);

        mIndicationPadding = getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_area_padding);
        updateWalletVisibility();
        updateQRCodeButtonVisibility();
        updateControlsVisibility();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        final IntentFilter filter = new IntentFilter();
        filter.addAction(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mKeyguardStateController.removeCallback(mKeyguardStateCallback);

        if (mQuickAccessWalletController != null) {
            mQuickAccessWalletController.unregisterWalletChangeObservers(
                    WALLET_PREFERENCE_CHANGE, DEFAULT_PAYMENT_APP_CHANGE);
        }

        if (mQRCodeScannerController != null) {
            mQRCodeScannerController.unregisterQRCodeScannerChangeObservers(
                    QRCodeScannerController.DEFAULT_QR_CODE_SCANNER_CHANGE,
                    QRCodeScannerController.QR_CODE_SCANNER_PREFERENCE_CHANGE);
        }

        if (mControlsComponent != null) {
            mControlsComponent.getControlsListingController().ifPresent(
                    c -> c.removeCallback(mListingCallback));
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        mIndicationBottomMargin = getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_margin_bottom);
        mBurnInYOffset = getResources().getDimensionPixelSize(
                R.dimen.default_burn_in_prevention_offset);
        MarginLayoutParams mlp = (MarginLayoutParams) mIndicationArea.getLayoutParams();
        if (mlp.bottomMargin != mIndicationBottomMargin) {
            mlp.bottomMargin = mIndicationBottomMargin;
            mIndicationArea.setLayoutParams(mlp);
        }

        // Respect font size setting.
        mIndicationTextBottom.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.text_size_small_material));
        mIndicationText.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(
                        com.android.internal.R.dimen.text_size_small_material));

        ViewGroup.LayoutParams lp = mWalletButton.getLayoutParams();
        lp.width = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width);
        lp.height = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height);
        mWalletButton.setLayoutParams(lp);

        lp = mQRCodeScannerButton.getLayoutParams();
        lp.width = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width);
        lp.height = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height);
        mQRCodeScannerButton.setLayoutParams(lp);

        lp = mControlsButton.getLayoutParams();
        lp.width = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_width);
        lp.height = getResources().getDimensionPixelSize(R.dimen.keyguard_affordance_fixed_height);
        mControlsButton.setLayoutParams(lp);

        mIndicationPadding = getResources().getDimensionPixelSize(
                R.dimen.keyguard_indication_area_padding);

        updateWalletVisibility();
        updateQRCodeButtonVisibility();
        updateAffordanceColors();
    }

    public void setCentralSurfaces(CentralSurfaces centralSurfaces) {
        mCentralSurfaces = centralSurfaces;
    }

    private void updateWalletVisibility() {
        if (mDozing
                || mQuickAccessWalletController == null
                || !mQuickAccessWalletController.isWalletEnabled()
                || !mHasCard) {
            mWalletButton.setVisibility(GONE);

            if (mControlsButton.getVisibility() == GONE) {
                mIndicationArea.setPadding(0, 0, 0, 0);
            }
        } else {
            mWalletButton.setVisibility(VISIBLE);
            mWalletButton.setOnClickListener(this::onWalletClick);
            mIndicationArea.setPadding(mIndicationPadding, 0, mIndicationPadding, 0);
        }
    }

    private void updateControlsVisibility() {
        if (mControlsComponent == null) return;

        mControlsButton.setImageResource(mControlsComponent.getTileImageId());
        mControlsButton.setContentDescription(getContext()
                .getString(mControlsComponent.getTileTitleId()));
        updateAffordanceColors();

        boolean hasFavorites = mControlsComponent.getControlsController()
                .map(c -> c.getFavorites().size() > 0)
                .orElse(false);
        if (mDozing
                || !hasFavorites
                || !mControlServicesAvailable
                || mControlsComponent.getVisibility() != AVAILABLE) {
            mControlsButton.setVisibility(GONE);
            if (mWalletButton.getVisibility() == GONE) {
                mIndicationArea.setPadding(0, 0, 0, 0);
            }
        } else {
            mControlsButton.setVisibility(VISIBLE);
            mControlsButton.setOnClickListener(this::onControlsClick);
            mIndicationArea.setPadding(mIndicationPadding, 0, mIndicationPadding, 0);
        }
    }

    public void setDarkAmount(float darkAmount) {
        if (darkAmount == mDarkAmount) {
            return;
        }
        mDarkAmount = darkAmount;
        dozeTimeTick();
    }

    public View getIndicationArea() {
        return mIndicationArea;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    public void startFinishDozeAnimation() {
        long delay = 0;
        if (mWalletButton.getVisibility() == View.VISIBLE) {
            startFinishDozeAnimationElement(mWalletButton, delay);
        }
        if (mQRCodeScannerButton.getVisibility() == View.VISIBLE) {
            startFinishDozeAnimationElement(mQRCodeScannerButton, delay);
        }
        if (mControlsButton.getVisibility() == View.VISIBLE) {
            startFinishDozeAnimationElement(mControlsButton, delay);
        }
    }

    private void startFinishDozeAnimationElement(View element, long delay) {
        element.setAlpha(0f);
        element.setTranslationY(element.getHeight() / 2);
        element.animate()
                .alpha(1f)
                .translationY(0f)
                .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                .setStartDelay(delay)
                .setDuration(DOZE_ANIMATION_ELEMENT_DURATION);
    }

    public void setDozing(boolean dozing, boolean animate) {
        mDozing = dozing;

        updateWalletVisibility();
        updateControlsVisibility();
        updateQRCodeButtonVisibility();

        if (dozing) {
            mOverlayContainer.setVisibility(INVISIBLE);
        } else {
            mOverlayContainer.setVisibility(VISIBLE);
            if (animate) {
                startFinishDozeAnimation();
            }
        }
    }

    public void dozeTimeTick() {
        int burnInYOffset = getBurnInOffset(mBurnInYOffset * 2, false /* xAxis */)
                - mBurnInYOffset;
        mIndicationArea.setTranslationY(burnInYOffset * mDarkAmount);
        if (mAmbientIndicationArea != null) {
            mAmbientIndicationArea.setTranslationY(burnInYOffset * mDarkAmount);
        }
    }

    public void setAntiBurnInOffsetX(int burnInXOffset) {
        if (mBurnInXOffset == burnInXOffset) {
            return;
        }
        mBurnInXOffset = burnInXOffset;
        mIndicationArea.setTranslationX(burnInXOffset);
        if (mAmbientIndicationArea != null) {
            mAmbientIndicationArea.setTranslationX(burnInXOffset);
        }
    }

    /**
     * Sets the alpha of the indication areas and affordances, excluding the lock icon.
     */
    public void setAffordanceAlpha(float alpha) {
        mIndicationArea.setAlpha(alpha);
        mWalletButton.setAlpha(alpha);
        mQRCodeScannerButton.setAlpha(alpha);
        mControlsButton.setAlpha(alpha);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        int bottom = insets.getDisplayCutout() != null
                ? insets.getDisplayCutout().getSafeInsetBottom() : 0;
        if (isPaddingRelative()) {
            setPaddingRelative(getPaddingStart(), getPaddingTop(), getPaddingEnd(), bottom);
        } else {
            setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), bottom);
        }
        return insets;
    }

    /** Set the falsing manager */
    public void setFalsingManager(FalsingManager falsingManager) {
        mFalsingManager = falsingManager;
    }

    /**
     * Initialize the wallet feature, only enabling if the feature is enabled within the platform.
     */
    public void initWallet(
            QuickAccessWalletController controller) {
        mQuickAccessWalletController = controller;
        mQuickAccessWalletController.setupWalletChangeObservers(
                mCardRetriever, WALLET_PREFERENCE_CHANGE, DEFAULT_PAYMENT_APP_CHANGE);
        mQuickAccessWalletController.updateWalletPreference();
        mQuickAccessWalletController.queryWalletCards(mCardRetriever);

        updateWalletVisibility();
        updateAffordanceColors();
    }

    /**
     * Initialize the qr code scanner feature, controlled by QRCodeScannerController.
     */
    public void initQRCodeScanner(QRCodeScannerController qrCodeScannerController) {
        mQRCodeScannerController = qrCodeScannerController;
        mQRCodeScannerController.registerQRCodeScannerChangeObservers(
                QRCodeScannerController.DEFAULT_QR_CODE_SCANNER_CHANGE,
                QRCodeScannerController.QR_CODE_SCANNER_PREFERENCE_CHANGE);
        updateQRCodeButtonVisibility();
        updateAffordanceColors();
    }

    private void updateQRCodeButtonVisibility() {
        if (mQuickAccessWalletController != null
                && mQuickAccessWalletController.isWalletEnabled()) {
            // Don't enable if quick access wallet is enabled
            return;
        }

        if (mQRCodeScannerController != null
                && mQRCodeScannerController.isEnabledForLockScreenButton()) {
            mQRCodeScannerButton.setVisibility(VISIBLE);
            mQRCodeScannerButton.setOnClickListener(this::onQRCodeScannerClicked);
            mIndicationArea.setPadding(mIndicationPadding, 0, mIndicationPadding, 0);
        } else {
            mQRCodeScannerButton.setVisibility(GONE);
            if (mControlsButton.getVisibility() == GONE) {
                mIndicationArea.setPadding(0, 0, 0, 0);
            }
        }
    }

    private void onQRCodeScannerClicked(View view) {
        Intent intent = mQRCodeScannerController.getIntent();
        if (intent != null) {
            try {
                ActivityTaskManager.getService().startActivityAsUser(
                                null, getContext().getBasePackageName(),
                                getContext().getAttributionTag(), intent,
                                intent.resolveTypeIfNeeded(getContext().getContentResolver()),
                                null, null, 0, Intent.FLAG_ACTIVITY_NEW_TASK, null, null,
                                UserHandle.CURRENT.getIdentifier());
            } catch (RemoteException e) {
                // This is unexpected. Nonetheless, just log the error and prevent the UI from
                // crashing
                Log.e(TAG, "Unexpected intent: " + intent
                        + " when the QR code scanner button was clicked");
            }
        }
    }

    private void updateAffordanceColors() {
        int iconColor = Utils.getColorAttrDefaultColor(
                mContext,
                com.android.internal.R.attr.textColorPrimary);
        mWalletButton.getDrawable().setTint(iconColor);
        mControlsButton.getDrawable().setTint(iconColor);
        mQRCodeScannerButton.getDrawable().setTint(iconColor);

        ColorStateList bgColor = Utils.getColorAttr(
                mContext,
                com.android.internal.R.attr.colorSurface);
        mWalletButton.setBackgroundTintList(bgColor);
        mControlsButton.setBackgroundTintList(bgColor);
        mQRCodeScannerButton.setBackgroundTintList(bgColor);
    }

    /**
      * Initialize controls via the ControlsComponent
      */
    public void initControls(ControlsComponent controlsComponent) {
        mControlsComponent = controlsComponent;
        mControlsComponent.getControlsListingController().ifPresent(
                c -> c.addCallback(mListingCallback));

        updateAffordanceColors();
    }

    private void onWalletClick(View v) {
        // More coming here; need to inform the user about how to proceed
        if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return;
        }

        ActivityLaunchAnimator.Controller animationController = createLaunchAnimationController(v);
        mQuickAccessWalletController.startQuickAccessUiIntent(
                mActivityStarter, animationController, mHasCard);
    }

    protected ActivityLaunchAnimator.Controller createLaunchAnimationController(View view) {
        return ActivityLaunchAnimator.Controller.fromView(view, null);
    }

    private void onControlsClick(View v) {
        if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
            return;
        }

        Intent intent = new Intent(mContext, ControlsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(ControlsUiController.EXTRA_ANIMATE, true);

        ActivityLaunchAnimator.Controller controller =
                v != null ? ActivityLaunchAnimator.Controller.fromView(v, null /* cujType */)
                        : null;
        if (mControlsComponent.getVisibility() == AVAILABLE) {
            mActivityStarter.startActivity(intent, true /* dismissShade */, controller,
                    true /* showOverLockscreenWhenLocked */);
        } else {
            mActivityStarter.postStartActivityDismissingKeyguard(intent, 0 /* delay */, controller);
        }
    }

    private class WalletCardRetriever implements
            QuickAccessWalletClient.OnWalletCardsRetrievedCallback {

        @Override
        public void onWalletCardsRetrieved(@NonNull GetWalletCardsResponse response) {
            mHasCard = !response.getWalletCards().isEmpty();
            Drawable tileIcon = mQuickAccessWalletController.getWalletClient().getTileIcon();
            post(() -> {
                if (tileIcon != null) {
                    mWalletButton.setImageDrawable(tileIcon);
                }
                updateWalletVisibility();
                updateAffordanceColors();
            });
        }

        @Override
        public void onWalletCardRetrievalError(@NonNull GetWalletCardsError error) {
            mHasCard = false;
            post(() -> {
                updateWalletVisibility();
                updateAffordanceColors();
            });
        }
    }
}
