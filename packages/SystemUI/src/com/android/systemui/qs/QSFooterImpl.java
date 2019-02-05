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

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.util.InjectionInflationController.VIEW_CONTEXT;

import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.UserManager;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.keyguard.CarrierTextController;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.Utils;
import com.android.settingslib.drawable.UserIconDrawable;
import com.android.settingslib.graph.SignalDrawable;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.R.dimen;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.TouchAnimator.Builder;
import com.android.systemui.statusbar.phone.MultiUserSwitch;
import com.android.systemui.statusbar.phone.SettingsButton;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.NetworkController.EmergencyListener;
import com.android.systemui.statusbar.policy.NetworkController.SignalCallback;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.statusbar.policy.UserInfoController.OnUserInfoChangedListener;
import com.android.systemui.tuner.TunerService;

import javax.inject.Inject;
import javax.inject.Named;

public class QSFooterImpl extends FrameLayout implements QSFooter,
        OnClickListener, OnUserInfoChangedListener, EmergencyListener, SignalCallback,
        CarrierTextController.CarrierTextCallback {

    private static final int SIM_SLOTS = 2;
    private static final String TAG = "QSFooterImpl";

    private final ActivityStarter mActivityStarter;
    private final UserInfoController mUserInfoController;
    private final NetworkController mNetworkController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private SettingsButton mSettingsButton;
    protected View mSettingsContainer;
    private PageIndicator mPageIndicator;

    private boolean mQsDisabled;
    private QSPanel mQsPanel;

    private boolean mExpanded;

    private boolean mListening;

    private boolean mShowEmergencyCallsOnly;
    private View mDivider;
    protected MultiUserSwitch mMultiUserSwitch;
    private ImageView mMultiUserAvatar;

    protected TouchAnimator mFooterAnimator;
    private float mExpansionAmount;

    protected View mEdit;
    private TouchAnimator mSettingsCogAnimator;

    private View mActionsContainer;
    private View mDragHandle;

    private View mCarrierDivider;
    private ViewGroup mMobileFooter;
    private View[] mMobileGroups = new View[SIM_SLOTS];
    private ViewGroup[] mCarrierGroups = new ViewGroup[SIM_SLOTS];
    private TextView[] mCarrierTexts = new TextView[SIM_SLOTS];
    private ImageView[] mMobileSignals = new ImageView[SIM_SLOTS];
    private ImageView[] mMobileRoamings = new ImageView[SIM_SLOTS];
    private final CellSignalState[] mInfos =
            new CellSignalState[]{new CellSignalState(), new CellSignalState()};

    private final int mColorForeground;
    private OnClickListener mExpandClickListener;
    private CarrierTextController mCarrierTextController;

    @Inject
    public QSFooterImpl(@Named(VIEW_CONTEXT) Context context, AttributeSet attrs,
            ActivityStarter activityStarter, UserInfoController userInfoController,
            NetworkController networkController,
            DeviceProvisionedController deviceProvisionedController) {
        super(context, attrs);
        mColorForeground = Utils.getColorAttrDefaultColor(context, android.R.attr.colorForeground);
        mActivityStarter = activityStarter;
        mUserInfoController = userInfoController;
        mNetworkController = networkController;
        mDeviceProvisionedController = deviceProvisionedController;
    }

    @VisibleForTesting
    public QSFooterImpl(Context context, AttributeSet attrs) {
        this(context, attrs,
                Dependency.get(ActivityStarter.class),
                Dependency.get(UserInfoController.class),
                Dependency.get(NetworkController.class),
                Dependency.get(DeviceProvisionedController.class));
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDivider = findViewById(R.id.qs_footer_divider);
        mEdit = findViewById(android.R.id.edit);
        mEdit.setOnClickListener(view ->
                mActivityStarter.postQSRunnableDismissingKeyguard(() ->
                        mQsPanel.showEdit(view)));

        mPageIndicator = findViewById(R.id.footer_page_indicator);

        mSettingsButton = findViewById(R.id.settings_button);
        mSettingsContainer = findViewById(R.id.settings_button_container);
        mSettingsButton.setOnClickListener(this);

        mMobileFooter = findViewById(R.id.qs_mobile);
        mCarrierGroups[0] = findViewById(R.id.carrier1);
        mCarrierGroups[1] = findViewById(R.id.carrier2);

        for (int i = 0; i < SIM_SLOTS; i++) {
            mMobileGroups[i] = mCarrierGroups[i].findViewById(R.id.mobile_combo);
            mMobileSignals[i] = mCarrierGroups[i].findViewById(R.id.mobile_signal);
            mMobileRoamings[i] = mCarrierGroups[i].findViewById(R.id.mobile_roaming);
            mCarrierTexts[i] = mCarrierGroups[i].findViewById(R.id.qs_carrier_text);
        }
        mCarrierDivider = findViewById(R.id.qs_carrier_divider);
        CharSequence separator = mContext.getString(
                com.android.internal.R.string.kg_text_message_separator);
        mCarrierTextController = new CarrierTextController(mContext, separator, false, false);

        mMultiUserSwitch = findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = mMultiUserSwitch.findViewById(R.id.multi_user_avatar);

        mDragHandle = findViewById(R.id.qs_drag_handle_view);
        mActionsContainer = findViewById(R.id.qs_footer_actions_container);

        // RenderThread is doing more harm than good when touching the header (to expand quick
        // settings), so disable it for this view
        ((RippleDrawable) mSettingsButton.getBackground()).setForceSoftware(true);

        updateResources();

        addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight,
                oldBottom) -> updateAnimator(right - left));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        updateEverything();
    }

    private void updateAnimator(int width) {
        int numTiles = QuickQSPanel.getNumQuickTiles(mContext);
        int size = mContext.getResources().getDimensionPixelSize(R.dimen.qs_quick_tile_size)
                - mContext.getResources().getDimensionPixelSize(dimen.qs_quick_tile_padding);
        int remaining = (width - numTiles * size) / (numTiles - 1);
        int defSpace = mContext.getResources().getDimensionPixelOffset(R.dimen.default_gear_space);

        mSettingsCogAnimator = new Builder()
                .addFloat(mSettingsContainer, "translationX",
                        isLayoutRtl() ? (remaining - defSpace) : -(remaining - defSpace), 0)
                .addFloat(mSettingsButton, "rotation", -120, 0)
                .build();

        setExpansion(mExpansionAmount);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();
    }

    @Override
    public void onRtlPropertiesChanged(int layoutDirection) {
        super.onRtlPropertiesChanged(layoutDirection);
        updateResources();
    }

    private void updateResources() {
        updateFooterAnimator();

        // Update the width and weight of the actions container as the page indicator can sometimes
        // show and the layout needs to center it between the carrier text and actions container.
        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) mActionsContainer.getLayoutParams();
        params.width = mContext.getResources().getInteger(R.integer.qs_footer_actions_width);
        params.weight = mContext.getResources().getInteger(R.integer.qs_footer_actions_weight);
        mActionsContainer.setLayoutParams(params);
    }

    private void updateFooterAnimator() {
        mFooterAnimator = createFooterAnimator();
    }

    @Nullable
    private TouchAnimator createFooterAnimator() {
        return new TouchAnimator.Builder()
                .addFloat(mDivider, "alpha", 0, 1)
                .addFloat(mMobileFooter, "alpha", 0, 0, 1)
                .addFloat(mCarrierDivider, "alpha", 0, 1)
                .addFloat(mActionsContainer, "alpha", 0, 1)
                .addFloat(mDragHandle, "alpha", 1, 0, 0)
                .addFloat(mPageIndicator, "alpha", 0, 1)
                .setStartDelay(0.15f)
                .build();
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        setExpansion(mExpansionAmount);
    }

    @Override
    public void setExpandClickListener(OnClickListener onClickListener) {
        mExpandClickListener = onClickListener;
    }

    @Override
    public void setExpanded(boolean expanded) {
        if (mExpanded == expanded) return;
        mExpanded = expanded;
        updateEverything();
    }

    @Override
    public void setExpansion(float headerExpansionFraction) {
        mExpansionAmount = headerExpansionFraction;
        if (mSettingsCogAnimator != null) mSettingsCogAnimator.setPosition(headerExpansionFraction);

        if (mFooterAnimator != null) {
            mFooterAnimator.setPosition(headerExpansionFraction);
        }
    }

    @Override
    @VisibleForTesting
    public void onDetachedFromWindow() {
        setListening(false);
        super.onDetachedFromWindow();
    }

    @Override
    public void setListening(boolean listening) {
        if (listening == mListening) {
            return;
        }
        mListening = listening;
        updateListeners();
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_EXPAND) {
            if (mExpandClickListener != null) {
                mExpandClickListener.onClick(null);
                return true;
            }
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_QUICK_SETTINGS) != 0;
        if (disabled == mQsDisabled) return;
        mQsDisabled = disabled;
        updateEverything();
    }

    public void updateEverything() {
        post(() -> {
            updateVisibilities();
            updateClickabilities();
            setClickable(false);
        });
    }

    private void updateClickabilities() {
        mMultiUserSwitch.setClickable(mMultiUserSwitch.getVisibility() == View.VISIBLE);
        mEdit.setClickable(mEdit.getVisibility() == View.VISIBLE);
        mSettingsButton.setClickable(mSettingsButton.getVisibility() == View.VISIBLE);
    }

    private void updateVisibilities() {
        mSettingsContainer.setVisibility(mQsDisabled ? View.GONE : View.VISIBLE);
        mSettingsContainer.findViewById(R.id.tuner_icon).setVisibility(
                TunerService.isTunerEnabled(mContext) ? View.VISIBLE : View.INVISIBLE);
        final boolean isDemo = UserManager.isDeviceInDemoMode(mContext);
        mMultiUserSwitch.setVisibility(showUserSwitcher(isDemo) ? View.VISIBLE : View.INVISIBLE);
        mEdit.setVisibility(isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE);
        mSettingsButton.setVisibility(isDemo || !mExpanded ? View.INVISIBLE : View.VISIBLE);
    }

    private boolean showUserSwitcher(boolean isDemo) {
        if (!mExpanded || isDemo || !UserManager.supportsMultipleUsers()) {
            return false;
        }
        UserManager userManager = UserManager.get(mContext);
        if (userManager.hasUserRestriction(UserManager.DISALLOW_USER_SWITCH)) {
            return false;
        }
        int switchableUserCount = 0;
        for (UserInfo user : userManager.getUsers(true)) {
            if (user.supportsSwitchToByUser()) {
                ++switchableUserCount;
                if (switchableUserCount > 1) {
                    return true;
                }
            }
        }
        return getResources().getBoolean(R.bool.qs_show_user_switcher_for_single_user);
    }

    private void updateListeners() {
        if (mListening) {
            mUserInfoController.addCallback(this);
            if (mNetworkController.hasVoiceCallingFeature()) {
                mNetworkController.addEmergencyListener(this);
                mNetworkController.addCallback(this);
            }
            mCarrierTextController.setListening(this);
        } else {
            mUserInfoController.removeCallback(this);
            mNetworkController.removeEmergencyListener(this);
            mNetworkController.removeCallback(this);
            mCarrierTextController.setListening(null);
        }
    }

    @Override
    public void setQSPanel(final QSPanel qsPanel) {
        mQsPanel = qsPanel;
        if (mQsPanel != null) {
            mMultiUserSwitch.setQsPanel(qsPanel);
            mQsPanel.setFooterPageIndicator(mPageIndicator);
        }
    }

    @Override
    public void onClick(View v) {
        // Don't do anything until view are unhidden
        if (!mExpanded) {
            return;
        }

        if (v == mSettingsButton) {
            if (!mDeviceProvisionedController.isCurrentUserSetup()) {
                // If user isn't setup just unlock the device and dump them back at SUW.
                mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                });
                return;
            }
            MetricsLogger.action(mContext,
                    mExpanded ? MetricsProto.MetricsEvent.ACTION_QS_EXPANDED_SETTINGS_LAUNCH
                            : MetricsProto.MetricsEvent.ACTION_QS_COLLAPSED_SETTINGS_LAUNCH);
            if (mSettingsButton.isTunerClick()) {
                mActivityStarter.postQSRunnableDismissingKeyguard(() -> {
                    if (TunerService.isTunerEnabled(mContext)) {
                        TunerService.showResetRequest(mContext, () -> {
                            // Relaunch settings so that the tuner disappears.
                            startSettingsActivity();
                        });
                    } else {
                        Toast.makeText(getContext(), R.string.tuner_toast,
                                Toast.LENGTH_LONG).show();
                        TunerService.setTunerEnabled(mContext, true);
                    }
                    startSettingsActivity();

                });
            } else {
                startSettingsActivity();
            }
        }
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                true /* dismissShade */);
    }

    @Override
    public void setEmergencyCallsOnly(boolean show) {
        boolean changed = show != mShowEmergencyCallsOnly;
        if (changed) {
            mShowEmergencyCallsOnly = show;
            if (mExpanded) {
                updateEverything();
            }
        }
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        if (picture != null &&
                UserManager.get(mContext).isGuestUser(KeyguardUpdateMonitor.getCurrentUser()) &&
                !(picture instanceof UserIconDrawable)) {
            picture = picture.getConstantState().newDrawable(mContext.getResources()).mutate();
            picture.setColorFilter(
                    Utils.getColorAttrDefaultColor(mContext, android.R.attr.colorForeground),
                    Mode.SRC_IN);
        }
        mMultiUserAvatar.setImageDrawable(picture);
    }

    private void handleUpdateState() {
        for (int i = 0; i < SIM_SLOTS; i++) {
            mMobileGroups[i].setVisibility(mInfos[i].visible ? View.VISIBLE : View.GONE);
            if (mInfos[i].visible) {
                mMobileRoamings[i].setVisibility(mInfos[i].roaming ? View.VISIBLE : View.GONE);
                mMobileRoamings[i].setImageTintList(ColorStateList.valueOf(mColorForeground));
                SignalDrawable d = new SignalDrawable(mContext);
                d.setDarkIntensity(QuickStatusBarHeader.getColorIntensity(mColorForeground));
                mMobileSignals[i].setImageDrawable(d);
                mMobileSignals[i].setImageLevel(mInfos[i].mobileSignalIconId);

                StringBuilder contentDescription = new StringBuilder();
                if (mInfos[i].contentDescription != null) {
                    contentDescription.append(mInfos[i].contentDescription).append(", ");
                }
                if (mInfos[i].roaming) {
                    contentDescription
                            .append(mContext.getString(R.string.data_connection_roaming))
                            .append(", ");
                }
                // TODO: show mobile data off/no internet text for 5 seconds before carrier text
                if (TextUtils.equals(mInfos[i].typeContentDescription,
                        mContext.getString(R.string.data_connection_no_internet))
                        || TextUtils.equals(mInfos[i].typeContentDescription,
                        mContext.getString(R.string.cell_data_off_content_description))) {
                    contentDescription.append(mInfos[i].typeContentDescription);
                }
                mMobileSignals[i].setContentDescription(contentDescription);
            }
        }
        mCarrierDivider.setVisibility(
                mInfos[0].visible && mInfos[1].visible ? View.VISIBLE : View.GONE);
    }

    @VisibleForTesting
    protected int getSlotIndex(int subscriptionId) {
        return SubscriptionManager.getSlotIndex(subscriptionId);
    }

    @Override
    public void updateCarrierInfo(CarrierTextController.CarrierTextCallbackInfo info) {
        if (info.anySimReady) {
            boolean[] slotSeen = new boolean[SIM_SLOTS];
            if (info.listOfCarriers.length == info.subscriptionIds.length) {
                for (int i = 0; i < SIM_SLOTS && i < info.listOfCarriers.length; i++) {
                    int slot = getSlotIndex(info.subscriptionIds[i]);
                    if (slot >= SIM_SLOTS) {
                        Log.w(TAG, "updateInfoCarrier - slot: " + slot);
                        continue;
                    }
                    if (slot == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
                        Log.e(TAG,
                                "Invalid SIM slot index for subscription: "
                                        + info.subscriptionIds[i]);
                        continue;
                    }
                    mInfos[slot].visible = true;
                    slotSeen[slot] = true;
                    mCarrierTexts[slot].setText(info.listOfCarriers[i].toString().trim());
                    mCarrierGroups[slot].setVisibility(View.VISIBLE);
                }
                for (int i = 0; i < SIM_SLOTS; i++) {
                    if (!slotSeen[i]) {
                        mInfos[i].visible = false;
                        mCarrierGroups[i].setVisibility(View.GONE);
                    }
                }
            } else {
                Log.e(TAG, "Carrier information arrays not of same length");
            }
        } else {
            mInfos[0].visible = false;
            mCarrierTexts[0].setText(info.carrierText);
            mCarrierGroups[0].setVisibility(View.VISIBLE);
            for (int i = 1; i < SIM_SLOTS; i++) {
                mInfos[i].visible = false;
                mCarrierTexts[i].setText("");
                mCarrierGroups[i].setVisibility(View.GONE);
            }
        }
        handleUpdateState();
    }

    @Override
    public void setMobileDataIndicators(NetworkController.IconState statusIcon,
            NetworkController.IconState qsIcon, int statusType,
            int qsType, boolean activityIn, boolean activityOut,
            String typeContentDescription,
            String description, boolean isWide, int subId, boolean roaming) {
        int slotIndex = getSlotIndex(subId);
        if (slotIndex >= SIM_SLOTS) {
            Log.w(TAG, "setMobileDataIndicators - slot: " + slotIndex);
            return;
        }
        if (slotIndex == SubscriptionManager.INVALID_SIM_SLOT_INDEX) {
            Log.e(TAG, "Invalid SIM slot index for subscription: " + subId);
            return;
        }
        mInfos[slotIndex].visible = statusIcon.visible;
        mInfos[slotIndex].mobileSignalIconId = statusIcon.icon;
        mInfos[slotIndex].contentDescription = statusIcon.contentDescription;
        mInfos[slotIndex].typeContentDescription = typeContentDescription;
        mInfos[slotIndex].roaming = roaming;
        handleUpdateState();
    }

    @Override
    public void setNoSims(boolean hasNoSims, boolean simDetected) {
        if (hasNoSims) {
            mInfos[0].visible = false;
            mInfos[1].visible = false;
        }
        handleUpdateState();
    }

    private final class CellSignalState {
        boolean visible;
        int mobileSignalIconId;
        public String contentDescription;
        String typeContentDescription;
        boolean roaming;
    }

    /**
     * TextView that changes its ellipsize value with its visibility.
     */
    public static class QSCarrierText extends TextView {
        public QSCarrierText(Context context) {
            super(context);
        }

        public QSCarrierText(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public QSCarrierText(Context context, AttributeSet attrs, int defStyleAttr) {
            super(context, attrs, defStyleAttr);
        }

        public QSCarrierText(Context context, AttributeSet attrs, int defStyleAttr,
                int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
        }

        @Override
        protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            // Only show marquee when visible
            if (visibility == VISIBLE) {
                setEllipsize(TextUtils.TruncateAt.MARQUEE);
                setSelected(true);
            } else {
                setEllipsize(TextUtils.TruncateAt.END);
                setSelected(false);
            }
        }
    }
}
