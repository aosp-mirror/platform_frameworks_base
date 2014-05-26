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

import android.content.Context;
import android.content.Intent;
import android.graphics.Outline;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.settings.BrightnessController;
import com.android.systemui.settings.ToggleSlider;
import com.android.systemui.statusbar.policy.UserInfoController;

/**
 * The view to manage the header area in the expanded status bar.
 */
public class StatusBarHeaderView extends RelativeLayout implements View.OnClickListener {

    /**
     * How much the header expansion gets rubberbanded while expanding the panel.
     */
    private static final float EXPANSION_RUBBERBAND_FACTOR = 0.35f;

    private boolean mExpanded;
    private boolean mKeyguardShowing;

    private View mBackground;
    private ViewGroup mSystemIconsContainer;
    private View mDateTime;
    private View mKeyguardCarrierText;
    private MultiUserSwitch mMultiUserSwitch;
    private View mDate;
    private View mStatusIcons;
    private View mSignalCluster;
    private View mSettingsButton;
    private View mBrightnessContainer;

    private int mCollapsedHeight;
    private int mExpandedHeight;
    private int mKeyguardHeight;

    private int mKeyguardWidth = ViewGroup.LayoutParams.MATCH_PARENT;
    private int mNormalWidth;

    private ActivityStarter mActivityStarter;
    private BrightnessController mBrightnessController;
    private QSPanel mQSPanel;

    private final Rect mClipBounds = new Rect();
    private final Outline mOutline = new Outline();

    public StatusBarHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mBackground = findViewById(R.id.background);
        mSystemIconsContainer = (ViewGroup) findViewById(R.id.system_icons_container);
        mDateTime = findViewById(R.id.datetime);
        mKeyguardCarrierText = findViewById(R.id.keyguard_carrier_text);
        mMultiUserSwitch = (MultiUserSwitch) findViewById(R.id.multi_user_switch);
        mDate = findViewById(R.id.date);
        mSettingsButton = findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(this);
        mBrightnessContainer = findViewById(R.id.brightness_container);
        mBrightnessController = new BrightnessController(getContext(),
                (ImageView) findViewById(R.id.brightness_icon),
                (ToggleSlider) findViewById(R.id.brightness_slider));
        loadDimens();
        updateVisibilities();
        addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right,
                    int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if ((right - left) != (oldRight - oldLeft)) {
                    // width changed, update clipping
                    setClipping(getHeight());
                }
            }
        });
    }

    private void loadDimens() {
        mCollapsedHeight = getResources().getDimensionPixelSize(R.dimen.status_bar_header_height);
        mExpandedHeight = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_expanded);
        mKeyguardHeight = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_keyguard);
        mNormalWidth = getLayoutParams().width;
    }

    public void setActivityStarter(ActivityStarter activityStarter) {
        mActivityStarter = activityStarter;
    }

    public int getCollapsedHeight() {
        return mKeyguardShowing ? mKeyguardHeight : mCollapsedHeight;
    }

    public int getExpandedHeight() {
        return mExpandedHeight;
    }

    public void setExpanded(boolean expanded) {
        boolean changed = expanded != mExpanded;
        mExpanded = expanded;
        if (changed) {
            updateHeights();
            updateVisibilities();
            updateSystemIconsLayoutParams();
            updateBrightnessControllerState();
            updateZTranslation();
            if (mQSPanel != null) {
                mQSPanel.setExpanded(expanded);
            }
        }
    }

    private void updateHeights() {
        boolean onKeyguardAndCollapsed = mKeyguardShowing && !mExpanded;
        int height;
        if (mExpanded) {
            height = mExpandedHeight;
        } else if (onKeyguardAndCollapsed) {
            height = mKeyguardHeight;
        } else {
            height = mCollapsedHeight;
        }
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            setLayoutParams(lp);
        }
        int systemIconsContainerHeight = onKeyguardAndCollapsed ? mKeyguardHeight : mCollapsedHeight;
        lp = mSystemIconsContainer.getLayoutParams();
        if (lp.height != systemIconsContainerHeight) {
            lp.height = systemIconsContainerHeight;
            mSystemIconsContainer.setLayoutParams(lp);
        }
        lp = mMultiUserSwitch.getLayoutParams();
        if (lp.height != systemIconsContainerHeight) {
            lp.height = systemIconsContainerHeight;
            mMultiUserSwitch.setLayoutParams(lp);
        }
    }

    private void updateWidth() {
        int width = mKeyguardShowing ? mKeyguardWidth : mNormalWidth;
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (width != lp.width) {
            lp.width = width;
            setLayoutParams(lp);
        }
    }

    private void updateVisibilities() {
        boolean onKeyguardAndCollapsed = mKeyguardShowing && !mExpanded;
        mBackground.setVisibility(onKeyguardAndCollapsed ? View.INVISIBLE : View.VISIBLE);
        mDateTime.setVisibility(onKeyguardAndCollapsed ? View.INVISIBLE : View.VISIBLE);
        mKeyguardCarrierText.setVisibility(onKeyguardAndCollapsed ? View.VISIBLE : View.GONE);
        mDate.setVisibility(mExpanded ? View.VISIBLE : View.GONE);
        mSettingsButton.setVisibility(mExpanded ? View.VISIBLE : View.GONE);
        mBrightnessContainer.setVisibility(mExpanded ? View.VISIBLE : View.GONE);
        if (mStatusIcons != null) {
            mStatusIcons.setVisibility(!mExpanded ? View.VISIBLE : View.GONE);
        }
        if (mSignalCluster != null) {
            mSignalCluster.setVisibility(!mExpanded ? View.VISIBLE : View.GONE);
        }
    }

    private void updateSystemIconsLayoutParams() {
        RelativeLayout.LayoutParams lp = (LayoutParams) mSystemIconsContainer.getLayoutParams();
        lp.addRule(RelativeLayout.START_OF, mExpanded
                ? mSettingsButton.getId()
                : mMultiUserSwitch.getId());
    }

    private void updateBrightnessControllerState() {
        if (mExpanded) {
            mBrightnessController.registerCallbacks();
        } else {
            mBrightnessController.unregisterCallbacks();
        }
    }

    private void updateZTranslation() {

        // If we are on the Keyguard, we need to set our z position to zero, so we don't get
        // shadows.
        if (mKeyguardShowing && !mExpanded) {
            setZ(0);
        } else {
            setTranslationZ(0);
        }
    }

    public void setExpansion(float height) {
        height = (height - mCollapsedHeight) * EXPANSION_RUBBERBAND_FACTOR + mCollapsedHeight;
        if (height < mCollapsedHeight) {
            height = mCollapsedHeight;
        }
        if (height > mExpandedHeight) {
            height = mExpandedHeight;
        }
        setClipping(height);
    }

    private void setClipping(float height) {
        mClipBounds.set(getPaddingLeft(), 0, getWidth() - getPaddingRight(), (int) height);
        setClipBounds(mClipBounds);
        mOutline.setRect(mClipBounds);
        setOutline(mOutline);
    }

    public View getBackgroundView() {
        return mBackground;
    }

    public void attachSystemIcons(LinearLayout systemIcons) {
        mSystemIconsContainer.addView(systemIcons);
        mStatusIcons = systemIcons.findViewById(R.id.statusIcons);
        mSignalCluster = systemIcons.findViewById(R.id.signal_cluster);
    }

    public void onSystemIconsDetached() {
        if (mStatusIcons != null) {
            mStatusIcons.setVisibility(View.VISIBLE);
        }
        if (mSignalCluster != null) {
            mSignalCluster.setVisibility(View.VISIBLE);
        }
        mStatusIcons = null;
        mSignalCluster = null;
    }

    public void setKeyguardShowing(boolean keyguardShowing) {
        mKeyguardShowing = keyguardShowing;
        updateHeights();
        updateWidth();
        updateVisibilities();
        updateZTranslation();
    }

    public void setUserInfoController(UserInfoController userInfoController) {
        mMultiUserSwitch.setUserInfoController(userInfoController);
    }

    public void setOverlayParent(ViewGroup parent) {
        mMultiUserSwitch.setOverlayParent(parent);
    }

    @Override
    public void onClick(View v) {
        if (v == mSettingsButton) {
            startSettingsActivity();
        }
    }

    private void startSettingsActivity() {
        mActivityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS));
    }

    public void setQSPanel(QSPanel qsp) {
        mQSPanel = qsp;
    }
}
