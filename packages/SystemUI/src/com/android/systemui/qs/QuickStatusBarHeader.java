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

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static com.android.systemui.battery.BatteryMeterView.BATTERY_STYLE_CIRCLE;

import android.content.Context;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.Space;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.graphics.ColorUtils;
import com.android.internal.policy.SystemBarUtils;
import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.battery.BatteryMeterView;
import com.android.systemui.Dependency;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.phone.StatusBarContentInsetsProvider;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager;
import com.android.systemui.statusbar.phone.StatusIconContainer;
import com.android.systemui.statusbar.policy.Clock;
import com.android.systemui.statusbar.policy.VariableDateView;
import com.android.systemui.util.LargeScreenUtils;
import com.android.systemui.tuner.TunerService;

import com.android.internal.util.xtended.XImageUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * View that contains the top-most bits of the QS panel (primarily the status bar with date, time,
 * battery, carrier info and privacy icons) and also contains the {@link QuickQSPanel}.
 */
public class QuickStatusBarHeader extends FrameLayout implements TunerService.Tunable {

    public static final String STATUS_BAR_BATTERY_STYLE =
            "system:" + Settings.System.STATUS_BAR_BATTERY_STYLE;
    public static final String QS_BATTERY_STYLE =
            "system:" + Settings.System.QS_BATTERY_STYLE;
    public static final String QS_BATTERY_LOCATION =
            "system:" + Settings.System.QS_BATTERY_LOCATION;
    private static final String QS_SHOW_BATTERY_PERCENT =
            "system:" + Settings.System.QS_SHOW_BATTERY_PERCENT;
    private static final String QS_SHOW_BATTERY_ESTIMATE =
            "system:" + Settings.System.QS_SHOW_BATTERY_ESTIMATE;
    private static final String QS_HEADER_IMAGE =
            "system:" + Settings.System.QS_HEADER_IMAGE;

    private boolean mExpanded;
    private boolean mQsDisabled;

    @Nullable
    private TouchAnimator mAlphaAnimator;
    @Nullable
    private TouchAnimator mTranslationAnimator;
    @Nullable
    private TouchAnimator mIconsAlphaAnimator;
    private TouchAnimator mIconsAlphaAnimatorFixed;

    private final ActivityStarter mActivityStarter;

    protected QuickQSPanel mHeaderQsPanel;
    private View mDatePrivacyView;
    private View mDateView;
    // DateView next to clock. Visible on QQS
    private VariableDateView mClockDateView;
    private View mStatusIconsView;
    private View mContainer;

    private View mQSCarriers;
    private ViewGroup mClockContainer;
    private Clock mClockView;
    private Space mDatePrivacySeparator;
    private View mClockIconsSeparator;
    private boolean mShowClockIconsSeparator;
    private View mRightLayout;
    private View mDateContainer;
    private View mPrivacyContainer;

    private BatteryMeterView mBatteryRemainingIcon;
    private BatteryMeterView mBatteryIcon;
    private StatusIconContainer mIconContainer;
    private View mPrivacyChip;

    // QS Header
    private int mCurrentColor;
    private ImageView mCustomQsHeaderImage;
    private ImageView mQsHeaderImageView;
    private View mQsHeaderLayout;
    private boolean mHeaderImageEnabled;
    private int mHeaderImageValue;
    private boolean mQsHeaderBackGroundType;

    @Nullable
    private TintedIconManager mTintedIconManager;
    @Nullable
    private QSExpansionPathInterpolator mQSExpansionPathInterpolator;
    private StatusBarContentInsetsProvider mInsetsProvider;

    private int mRoundedCornerPadding = 0;
    private int mWaterfallTopInset;
    private int mCutOutPaddingLeft;
    private int mCutOutPaddingRight;
    private float mKeyguardExpansionFraction;
    private int mTextColorPrimary = Color.TRANSPARENT;
    private int mTopViewMeasureHeight;

    @NonNull
    private List<String> mRssiIgnoredSlots = List.of();
    private boolean mIsSingleCarrier;

    private boolean mUseCombinedQSHeader;

    private boolean mConfigShowBatteryEstimate;

    private int mStatusBarBatteryStyle, mQSBatteryStyle, mQSBatteryLocation;

    private int mCount = 0;

    private static final String QS_HEADER_FILE_IMAGE = "custom_header_image";

    public QuickStatusBarHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
        mActivityStarter = Dependency.get(ActivityStarter.class);
        Handler mHandler = new Handler();
        SettingsObserver mSettingsObserver = new SettingsObserver(mHandler);
        mSettingsObserver.observe();
    }

    /**
     * How much the view containing the clock and QQS will translate down when QS is fully expanded.
     *
     * This matches the measured height of the view containing the date and privacy icons.
     */
    public int getOffsetTranslation() {
        return mTopViewMeasureHeight;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mHeaderQsPanel = findViewById(R.id.quick_qs_panel);
        mDatePrivacyView = findViewById(R.id.quick_status_bar_date_privacy);
        mStatusIconsView = findViewById(R.id.quick_qs_status_icons);
        mQSCarriers = findViewById(R.id.carrier_group);
        mContainer = findViewById(R.id.qs_container);
        mIconContainer = findViewById(R.id.statusIcons);
        mPrivacyChip = findViewById(R.id.privacy_chip);
        mDateView = findViewById(R.id.date);
        mClockDateView = findViewById(R.id.date_clock);
        mClockDateView.setVisibility(View.GONE);
        mClockIconsSeparator = findViewById(R.id.separator);
        mRightLayout = findViewById(R.id.rightLayout);
        mDateContainer = findViewById(R.id.date_container);
        mPrivacyContainer = findViewById(R.id.privacy_container);

        mClockContainer = findViewById(R.id.clock_container);
        mClockView = findViewById(R.id.clock);
        mClockView.setQsHeader();
        mClockView.setOnClickListener(
                v -> mActivityStarter.postStartActivityDismissingKeyguard(
                        new Intent(AlarmClock.ACTION_SHOW_ALARMS), 0));
        mDatePrivacySeparator = findViewById(R.id.space);
        // Tint for the battery icons are handled in setupHost()
        mBatteryRemainingIcon = findViewById(R.id.batteryRemainingIcon);

        mBatteryIcon = findViewById(R.id.batteryIcon);

        mQsHeaderLayout = findViewById(R.id.layout_header);
        mQsHeaderImageView = findViewById(R.id.qs_header_image_view);
        mCustomQsHeaderImage = findViewById(R.id.custom_qs_header_image);

        updateResources();
        updateSettings();

        Configuration config = mContext.getResources().getConfiguration();
        setDatePrivacyContainersWidth(config.orientation == Configuration.ORIENTATION_LANDSCAPE);

        mIconsAlphaAnimatorFixed = new TouchAnimator.Builder()
                .addFloat(mIconContainer, "alpha", 0, 1)
                .addFloat(mBatteryRemainingIcon, "alpha", 0, 1)
                .build();

        Dependency.get(TunerService.class).addTunable(this,
                StatusBarIconController.ICON_HIDE_LIST,
                STATUS_BAR_BATTERY_STYLE,
                QS_BATTERY_STYLE,
                QS_BATTERY_LOCATION,
                QS_SHOW_BATTERY_PERCENT,
                QS_SHOW_BATTERY_ESTIMATE,
                QS_HEADER_IMAGE);
    }

    void onAttach(TintedIconManager iconManager,
            QSExpansionPathInterpolator qsExpansionPathInterpolator,
            List<String> rssiIgnoredSlots,
            StatusBarContentInsetsProvider insetsProvider,
            boolean useCombinedQSHeader) {
        mUseCombinedQSHeader = useCombinedQSHeader;
        mTintedIconManager = iconManager;
        mRssiIgnoredSlots = rssiIgnoredSlots;
        mInsetsProvider = insetsProvider;
        int fillColor = Utils.getColorAttrDefaultColor(getContext(),
                android.R.attr.textColorPrimary);

        // Set the correct tint for the status icons so they contrast
        iconManager.setTint(fillColor);

        mQSExpansionPathInterpolator = qsExpansionPathInterpolator;
        updateAnimators();
    }

    void setIsSingleCarrier(boolean isSingleCarrier) {
        mIsSingleCarrier = isSingleCarrier;
        if (mIsSingleCarrier) {
            mIconContainer.removeIgnoredSlots(mRssiIgnoredSlots);
        }
        updateAlphaAnimator();
    }

    public QuickQSPanel getHeaderQsPanel() {
        return mHeaderQsPanel;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        if (mDatePrivacyView.getMeasuredHeight() != mTopViewMeasureHeight) {
            mTopViewMeasureHeight = mDatePrivacyView.getMeasuredHeight();
            updateAnimators();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
        setDatePrivacyContainersWidth(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }
        void observe() {
            ContentResolver resolver = getContext().getContentResolver();
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_HEADER_TYPE_BACKGROUND), false,
                    this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System
                    .getUriFor(Settings.System.QS_HEADER_CUSTOM_IMAGE), false,
                    this, UserHandle.USER_ALL);
        }

        @Override
         public void onChange(boolean selfChange, Uri uri) {
            if (uri.equals(Settings.System.getUriFor(Settings.System.QS_HEADER_CUSTOM_IMAGE)) ||
                uri.equals(Settings.System.getUriFor(Settings.System.QS_HEADER_TYPE_BACKGROUND))) {
                mCount = 0;
                updateSettings();
            }
        }
    }

    private void updateSettings() {
        ContentResolver resolver = getContext().getContentResolver();
        String headerImageUri = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.QS_HEADER_CUSTOM_IMAGE, UserHandle.USER_CURRENT);
        post(new Runnable() {
            public void run() {
                updateQSHeaderImage();
            }
        });
        if (headerImageUri != null) {
            saveCustomFileFromString(Uri.parse(headerImageUri), QS_HEADER_FILE_IMAGE);
        }
        updateResources();
    }

    private void setDatePrivacyContainersWidth(boolean landscape) {
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mDateContainer.getLayoutParams();
        lp.width = landscape ? WRAP_CONTENT : 0;
        lp.weight = landscape ? 0f : 1f;
        mDateContainer.setLayoutParams(lp);

        lp = (LinearLayout.LayoutParams) mPrivacyContainer.getLayoutParams();
        lp.width = landscape ? WRAP_CONTENT : 0;
        lp.weight = landscape ? 0f : 1f;
        mPrivacyContainer.setLayoutParams(lp);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // If using combined headers, only react to touches inside QuickQSPanel
        if (!mUseCombinedQSHeader || event.getY() > mHeaderQsPanel.getTop()) {
            return super.onTouchEvent(event);
        } else {
            return false;
        }
    }

    private void updateBatteryMode() {
        if (mConfigShowBatteryEstimate) {
            mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_ESTIMATE);
        } else {
            mBatteryRemainingIcon.setPercentShowMode(BatteryMeterView.MODE_ON);
        }
    }

    public void saveCustomFileFromString(Uri fileUri, String fileName) {
        try {
            final InputStream fileStream = getContext().getContentResolver().openInputStream(fileUri);
            File file = new File(getContext().getFilesDir(), fileName);
            if (file.exists()) {
                file.delete();
            }
            FileOutputStream output = new FileOutputStream(file);
            byte[] buffer = new byte[8 * 1024];
            int read;
            while ((read = fileStream.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.flush();
        } catch (Exception e) {
            if (mCount > 2) return;
            if (mQsHeaderBackGroundType) {
                Toast toast = Toast.makeText(mContext, R.string.photos_not_allowed, Toast.LENGTH_SHORT);
                toast.show();
            }
            mCount++;
        }
    }

    public BitmapDrawable getCustomImageFromString(String fileName) {
        BitmapDrawable mImage = null;
        File file = new File(getContext().getFilesDir(), fileName);
        if (file.exists()) {
            final Bitmap image = BitmapFactory.decodeFile(file.getAbsolutePath());
            mImage = new BitmapDrawable(getContext().getResources(), image);
        }
        return mImage;
    }

    void updateResources() {
        Resources resources = mContext.getResources();
        boolean largeScreenHeaderActive =
                LargeScreenUtils.shouldUseLargeScreenShadeHeader(resources);

        boolean gone = largeScreenHeaderActive || mUseCombinedQSHeader || mQsDisabled;
        mStatusIconsView.setVisibility(gone ? View.GONE : View.VISIBLE);
        mDatePrivacyView.setVisibility(gone ? View.GONE : View.VISIBLE);

        mRoundedCornerPadding = resources.getDimensionPixelSize(
                R.dimen.rounded_corner_content_padding);

        int statusBarHeight = SystemBarUtils.getStatusBarHeight(mContext);

        mDatePrivacyView.getLayoutParams().height = statusBarHeight;
        mDatePrivacyView.setLayoutParams(mDatePrivacyView.getLayoutParams());

        mStatusIconsView.getLayoutParams().height = statusBarHeight;
        mStatusIconsView.setLayoutParams(mStatusIconsView.getLayoutParams());

        ViewGroup.LayoutParams lp = getLayoutParams();
        if (mQsDisabled) {
            lp.height = mStatusIconsView.getLayoutParams().height - mWaterfallTopInset;
        } else {
            lp.height = WRAP_CONTENT;
        }
        setLayoutParams(lp);

        int textColor = Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorPrimary);
        if (textColor != mTextColorPrimary) {
            int isCircleBattery = mBatteryIcon.getBatteryStyle();
            int textColorSecondary = Utils.getColorAttrDefaultColor(mContext,
                    (isCircleBattery == 1 || isCircleBattery == 2 || isCircleBattery == 3)
                    ? android.R.attr.textColorHint : android.R.attr.textColorSecondary);
            mTextColorPrimary = textColor;
            mClockView.setTextColor(textColor);
            if (mTintedIconManager != null) {
                mTintedIconManager.setTint(textColor);
            }
            if (mBatteryRemainingIcon.getBatteryStyle() == BATTERY_STYLE_CIRCLE) {
                textColorSecondary = Utils.getColorAttrDefaultColor(mContext,
                        android.R.attr.textColorHint);
            }
            mBatteryRemainingIcon.updateColors(mTextColorPrimary, textColorSecondary,
                    mTextColorPrimary);
            mBatteryIcon.updateColors(mTextColorPrimary, textColorSecondary,
                    mTextColorPrimary);
        }

        MarginLayoutParams qqsLP = (MarginLayoutParams) mHeaderQsPanel.getLayoutParams();
                qqsLP.topMargin = largeScreenHeaderActive || !mUseCombinedQSHeader
                ? mContext.getResources().getDimensionPixelSize(R.dimen.qqs_layout_margin_top)
                : SystemBarUtils.getQuickQsOffsetHeight(mContext);
        mHeaderQsPanel.setLayoutParams(qqsLP);

        post(new Runnable() {
            public void run() {
                updateQSHeaderImage();
            }
        });
        updateHeadersPadding();
        updateAnimators();

        updateClockDatePadding();
    }

    private void updateQSHeaderImage() {
        mQsHeaderBackGroundType = Settings.System.getIntForUser(getContext().getContentResolver(),
                    Settings.System.QS_HEADER_TYPE_BACKGROUND, 0, UserHandle.USER_CURRENT) == 1;

        if (!mHeaderImageEnabled && !mQsHeaderBackGroundType) {
            mQsHeaderLayout.setVisibility(View.GONE);
            return;
        }

        Configuration config = mContext.getResources().getConfiguration();
        if (config.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            boolean mIsNightMode = (mContext.getResources().getConfiguration().uiMode &
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int fadeFilter = ColorUtils.blendARGB(Color.TRANSPARENT, mIsNightMode ?
                Color.BLACK : Color.WHITE, 30 / 100f);
            int newFadeFilter = ColorUtils.blendARGB(Color.TRANSPARENT, mIsNightMode ?
                Color.BLACK : Color.WHITE, 50 / 100f);
            BitmapDrawable currentHeaderImage = null;
            mCurrentColor = Color.WHITE;
            if (mQsHeaderBackGroundType) {
                currentHeaderImage = getCustomImageFromString(QS_HEADER_FILE_IMAGE);
            }
            if ((currentHeaderImage != null && mQsHeaderBackGroundType) && !mHeaderImageEnabled) {
                mQsHeaderImageView.setVisibility(View.GONE);
                int width = mQsHeaderLayout.getWidth();
                int height = mQsHeaderLayout.getHeight();
                int corner = getContext().getResources().getDimensionPixelSize(com.android.internal.R.dimen.rounded_corner_radius);
                Bitmap bitmapHeader = currentHeaderImage.getBitmap();
                Bitmap toCenter = XImageUtils.scaleCenterCrop(bitmapHeader, width, height);
                BitmapDrawable bHeaderDrawable = new BitmapDrawable(mContext.getResources(),
                            XImageUtils.getRoundedCornerBitmap(toCenter, corner, width, height, newFadeFilter));
                Drawable customQsHeaderImage = new InsetDrawable(bHeaderDrawable, 0, 0, 0, 0);
                mCustomQsHeaderImage.setImageDrawable(customQsHeaderImage);
         	customQsHeaderImage.setColorFilter(newFadeFilter, PorterDuff.Mode.SRC_ATOP);
                mCustomQsHeaderImage.setClipToOutline(false);
                mCustomQsHeaderImage.setVisibility(View.VISIBLE);
                mQsHeaderLayout.setVisibility(View.VISIBLE);
	    } else if (mHeaderImageEnabled && !mQsHeaderBackGroundType) {
                int resId = getResources().getIdentifier("qs_header_image_" +
                    String.valueOf(mHeaderImageValue), "drawable", "com.android.systemui");
                mCustomQsHeaderImage.setVisibility(View.GONE);
                mQsHeaderImageView.setImageResource(resId);
         	mQsHeaderImageView.setColorFilter(fadeFilter, PorterDuff.Mode.SRC_ATOP);
                mQsHeaderImageView.setClipToOutline(true);
                mQsHeaderImageView.setVisibility(View.VISIBLE);
                mQsHeaderLayout.setVisibility(View.VISIBLE);
	    }
        } else {
            mQsHeaderLayout.setVisibility(View.GONE);
        }
    }

    private void updateClockDatePadding() {
        int startPadding = mContext.getResources()
                .getDimensionPixelSize(R.dimen.status_bar_left_clock_starting_padding);
        int endPadding = mContext.getResources()
                .getDimensionPixelSize(R.dimen.status_bar_left_clock_end_padding);
        mClockView.setPaddingRelative(
                startPadding,
                mClockView.getPaddingTop(),
                endPadding,
                mClockView.getPaddingBottom()
        );

//        MarginLayoutParams lp = (MarginLayoutParams) mClockDateView.getLayoutParams();
//        lp.setMarginStart(endPadding);
//        mClockDateView.setLayoutParams(lp);
    }

    private void updateAnimators() {
        if (mUseCombinedQSHeader) {
            mTranslationAnimator = null;
            return;
        }
        updateAlphaAnimator();
        int offset = mTopViewMeasureHeight;

        mTranslationAnimator = new TouchAnimator.Builder()
                .addFloat(mContainer, "translationY", 0, offset)
                .setInterpolator(mQSExpansionPathInterpolator != null
                        ? mQSExpansionPathInterpolator.getYInterpolator()
                        : null)
                .build();
    }

    private void updateAlphaAnimator() {
        if (mUseCombinedQSHeader) {
            mAlphaAnimator = null;
            return;
        }
        TouchAnimator.Builder builder = new TouchAnimator.Builder()
                // These views appear on expanding down
//                .addFloat(mDateView, "alpha", 0, 0, 1)
//                .addFloat(mClockDateView, "alpha", 1, 0, 0)
                .addFloat(mClockView, "alpha", 0, 1)
                .addFloat(mQSCarriers, "alpha", 0, 1)
                .setListener(new TouchAnimator.ListenerAdapter() {
                    @Override
                    public void onAnimationAtEnd() {
                        super.onAnimationAtEnd();
                        if (!mIsSingleCarrier) {
                            mIconContainer.addIgnoredSlots(mRssiIgnoredSlots);
                        }
                        // Make it gone so there's enough room for carrier names
                        mClockDateView.setVisibility(View.GONE);
                    }

                    @Override
                    public void onAnimationStarted() {
//                        mClockDateView.setVisibility(View.VISIBLE);
//                        mClockDateView.setFreezeSwitching(true);
                        setSeparatorVisibility(false);
                        if (!mIsSingleCarrier) {
                            mIconContainer.addIgnoredSlots(mRssiIgnoredSlots);
                        }
                    }

                    @Override
                    public void onAnimationAtStart() {
                        super.onAnimationAtStart();
//                        mClockDateView.setFreezeSwitching(false);
//                        mClockDateView.setVisibility(View.VISIBLE);
                        setSeparatorVisibility(mShowClockIconsSeparator);
                        // In QQS we never ignore RSSI.
                        mIconContainer.removeIgnoredSlots(mRssiIgnoredSlots);
                    }
                });
        mAlphaAnimator = builder.build();
    }

    void setChipVisibility(boolean visibility) {
        boolean showBattery = mQSBatteryLocation == 1
                && (mBatteryIcon.getBatteryStyle() != 5
                || mBatteryIcon.getBatteryEstimate() != 0);
        if (showBattery) {
            mBatteryIcon.setVisibility(visibility ? View.GONE : View.VISIBLE);
        }
        if (visibility || showBattery) {
            // Animates the icons and battery indicator from alpha 0 to 1, when the chip is visible
            mIconsAlphaAnimator = mIconsAlphaAnimatorFixed;
            mIconsAlphaAnimator.setPosition(mKeyguardExpansionFraction);
            setBatteryRemainingOnClick(false);
        } else {
            mIconsAlphaAnimator = null;
            mIconContainer.setAlpha(1);
            mBatteryRemainingIcon.setAlpha(1);
            setBatteryRemainingOnClick(true);
        }
    }

    /** */
    public void setExpanded(boolean expanded, QuickQSPanelController quickQSPanelController) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        quickQSPanelController.setExpanded(expanded);
	mDateView.setVisibility(mClockView.isClockDateEnabled() ? View.INVISIBLE : View.VISIBLE);
        updateEverything();
    }

    /**
     * Animates the inner contents based on the given expansion details.
     *
     * @param forceExpanded whether we should show the state expanded forcibly
     * @param expansionFraction how much the QS panel is expanded/pulled out (up to 1f)
     * @param panelTranslationY how much the panel has physically moved down vertically (required
     *                          for keyguard animations only)
     */
    public void setExpansion(boolean forceExpanded, float expansionFraction,
                             float panelTranslationY) {
        final float keyguardExpansionFraction = forceExpanded ? 1f : expansionFraction;

        if (mAlphaAnimator != null) {
            mAlphaAnimator.setPosition(keyguardExpansionFraction);
        }
        if (mTranslationAnimator != null) {
            mTranslationAnimator.setPosition(keyguardExpansionFraction);
        }
        if (mIconsAlphaAnimator != null) {
            mIconsAlphaAnimator.setPosition(keyguardExpansionFraction);
        }
        // If forceExpanded (we are opening QS from lockscreen), the animators have been set to
        // position = 1f.
        if (forceExpanded) {
            setAlpha(expansionFraction);
        } else {
            setAlpha(1);
        }

        mKeyguardExpansionFraction = keyguardExpansionFraction;
    }

    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        mHeaderQsPanel.setDisabledByPolicy(disabled);
        mStatusIconsView.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        updateResources();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        // Handle padding of the views
        DisplayCutout cutout = insets.getDisplayCutout();

        Pair<Integer, Integer> sbInsets = mInsetsProvider
                .getStatusBarContentInsetsForCurrentRotation();
        boolean hasCornerCutout = mInsetsProvider.currentRotationHasCornerCutout();

        mDatePrivacyView.setPadding(
                sbInsets.first,
                mDatePrivacyView.getPaddingTop(),
                sbInsets.second,
                mDatePrivacyView.getPaddingBottom());
        mStatusIconsView.setPadding(
                sbInsets.first,
                mStatusIconsView.getPaddingTop(),
                sbInsets.second,
                mStatusIconsView.getPaddingBottom());
        LinearLayout.LayoutParams datePrivacySeparatorLayoutParams =
                (LinearLayout.LayoutParams) mDatePrivacySeparator.getLayoutParams();
        LinearLayout.LayoutParams mClockIconsSeparatorLayoutParams =
                (LinearLayout.LayoutParams) mClockIconsSeparator.getLayoutParams();
        if (cutout != null) {
            Rect topCutout = cutout.getBoundingRectTop();
            if (topCutout.isEmpty() || hasCornerCutout) {
                datePrivacySeparatorLayoutParams.width = 0;
                mDatePrivacySeparator.setVisibility(View.GONE);
                mClockIconsSeparatorLayoutParams.width = 0;
                setSeparatorVisibility(false);
                mShowClockIconsSeparator = false;
            } else {
                datePrivacySeparatorLayoutParams.width = topCutout.width();
                mDatePrivacySeparator.setVisibility(View.VISIBLE);
                mClockIconsSeparatorLayoutParams.width = topCutout.width();
                mShowClockIconsSeparator = true;
                setSeparatorVisibility(mKeyguardExpansionFraction == 0f);
            }
        }
        mDatePrivacySeparator.setLayoutParams(datePrivacySeparatorLayoutParams);
        mClockIconsSeparator.setLayoutParams(mClockIconsSeparatorLayoutParams);
        mCutOutPaddingLeft = sbInsets.first;
        mCutOutPaddingRight = sbInsets.second;
        mWaterfallTopInset = cutout == null ? 0 : cutout.getWaterfallInsets().top;

        updateHeadersPadding();
        return super.onApplyWindowInsets(insets);
    }

    /**
     * Sets the visibility of the separator between clock and icons.
     *
     * This separator is "visible" when there is a center cutout, to block that space. In that
     * case, the clock and the layout on the right (containing the icons and the battery meter) are
     * set to weight 1 to take the available space.
     * @param visible whether the separator between clock and icons should be visible.
     */
    private void setSeparatorVisibility(boolean visible) {
        int newVisibility = visible ? View.VISIBLE : View.GONE;
        if (mClockIconsSeparator.getVisibility() == newVisibility) return;

        mClockIconsSeparator.setVisibility(visible ? View.VISIBLE : View.GONE);
        mQSCarriers.setVisibility(visible ? View.GONE : View.VISIBLE);

        LinearLayout.LayoutParams lp =
                (LinearLayout.LayoutParams) mClockContainer.getLayoutParams();
        lp.width = visible ? 0 : WRAP_CONTENT;
        lp.weight = visible ? 1f : 0f;
        mClockContainer.setLayoutParams(lp);

        lp = (LinearLayout.LayoutParams) mRightLayout.getLayoutParams();
        lp.width = visible ? 0 : WRAP_CONTENT;
        lp.weight = visible ? 1f : 0f;
        mRightLayout.setLayoutParams(lp);
    }

    private void updateHeadersPadding() {
        setContentMargins(mDatePrivacyView, 0, 0);
        setContentMargins(mStatusIconsView, 0, 0);
        int paddingLeft = 0;
        int paddingRight = 0;

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        int leftMargin = lp.leftMargin;
        int rightMargin = lp.rightMargin;

        int statusBarPaddingTop = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_top);
        int statusBarPaddingStart = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_start);
        int statusBarPaddingEnd = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_end);

        // The clock might collide with cutouts, let's shift it out of the way.
        // We only do that if the inset is bigger than our own padding, since it's nicer to
        // align with
        if (mCutOutPaddingLeft > 0) {
            // if there's a cutout, let's use at least the rounded corner inset
            int cutoutPadding = Math.max(mCutOutPaddingLeft, mRoundedCornerPadding);
            paddingLeft = Math.max(cutoutPadding - leftMargin, statusBarPaddingStart);
        }
        if (mCutOutPaddingRight > 0) {
            // if there's a cutout, let's use at least the rounded corner inset
            int cutoutPadding = Math.max(mCutOutPaddingRight, mRoundedCornerPadding);
            paddingRight = Math.max(cutoutPadding - rightMargin, statusBarPaddingEnd);
        }

        mDatePrivacyView.setPadding(paddingLeft,
                statusBarPaddingTop,
                paddingRight,
                0);
        mStatusIconsView.setPadding(paddingLeft,
                statusBarPaddingTop,
                paddingRight,
                0);
    }

    public void updateEverything() {
        post(() -> setClickable(!mExpanded));
        if (mExpanded) {
            setBatteryRemainingOnClick(true);
        }
    }

    private void setContentMargins(View view, int marginStart, int marginEnd) {
        MarginLayoutParams lp = (MarginLayoutParams) view.getLayoutParams();
        lp.setMarginStart(marginStart);
        lp.setMarginEnd(marginEnd);
        view.setLayoutParams(lp);
    }

    /**
     * Scroll the headers away.
     *
     * @param scrollY the scroll of the QSPanel container
     */
    public void setExpandedScrollAmount(int scrollY) {
        mStatusIconsView.setScrollY(scrollY);
        mDatePrivacyView.setScrollY(scrollY);
    }

    private void updateBatteryStyle() {
        int style;
        if (mQSBatteryStyle == -1) {
            style = mStatusBarBatteryStyle;
        } else {
            style = mQSBatteryStyle;
        }
        mBatteryRemainingIcon.setBatteryStyle(style);
        mBatteryIcon.setBatteryStyle(style);
        setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);
    }

    public BatteryMeterView getBatteryMeterView() {
        if (mQSBatteryLocation == 0) {
            return mBatteryRemainingIcon;
        }
        return mBatteryIcon;
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        switch (key) {
            case QS_BATTERY_STYLE:
                mQSBatteryStyle =
                        TunerService.parseInteger(newValue, -1);
                updateBatteryStyle();
                break;
            case STATUS_BAR_BATTERY_STYLE:
                mStatusBarBatteryStyle =
                        TunerService.parseInteger(newValue, 0);
                updateBatteryStyle();
                break;
            case QS_BATTERY_LOCATION:
                mQSBatteryLocation =
                        TunerService.parseInteger(newValue, 0);
                if (mQSBatteryLocation == 0) {
                    mBatteryIcon.setVisibility(View.GONE);
                    mBatteryRemainingIcon.setVisibility(View.VISIBLE);
                } else {
                    mBatteryRemainingIcon.setVisibility(View.GONE);
                    mBatteryIcon.setVisibility(View.VISIBLE);
                }
                setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);
                break;
            case QS_SHOW_BATTERY_PERCENT:
                mBatteryRemainingIcon.setBatteryPercent(
                        TunerService.parseInteger(newValue, 2));
                mBatteryIcon.setBatteryPercent(
                        TunerService.parseInteger(newValue, 2));
                break;
            case QS_SHOW_BATTERY_ESTIMATE:
                mBatteryRemainingIcon.setBatteryEstimate(
                        TunerService.parseInteger(newValue, 0));
                mBatteryIcon.setBatteryEstimate(
                        TunerService.parseInteger(newValue, 0));
                setChipVisibility(mPrivacyChip.getVisibility() == View.VISIBLE);
                break;
            case QS_HEADER_IMAGE:
                mHeaderImageValue =
                       TunerService.parseInteger(newValue, 0);
                mHeaderImageEnabled = mHeaderImageValue != 0;
                updateResources();
                break;
            default:
                break;
        }
    }

    private void setBatteryRemainingOnClick(boolean enable) {
        if (enable) {
            mBatteryRemainingIcon.setOnClickListener(
                    v -> mActivityStarter.postStartActivityDismissingKeyguard(
                            new Intent(Intent.ACTION_POWER_USAGE_SUMMARY), 0));
            mBatteryRemainingIcon.setClickable(true);
        } else {
            mBatteryRemainingIcon.setOnClickListener(null);
            mBatteryRemainingIcon.setClickable(false);
        }
    }
}
