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
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.UserInfoController;

/**
 * The view to manage the header area in the expanded status bar.
 */
public class StatusBarHeaderView extends RelativeLayout {

    private boolean mExpanded;
    private View mBackground;
    private ViewGroup mSystemIconsContainer;
    private View mDateTime;
    private View mKeyguardCarrierText;
    private MultiUserSwitch mMultiUserSwitch;
    private View mDate;

    private int mCollapsedHeight;
    private int mExpandedHeight;
    private int mKeyguardHeight;

    private int mKeyguardWidth = ViewGroup.LayoutParams.MATCH_PARENT;
    private int mNormalWidth;

    private boolean mKeyguardShowing;

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
        loadDimens();
    }

    private void loadDimens() {
        mCollapsedHeight = getResources().getDimensionPixelSize(R.dimen.status_bar_header_height);
        mExpandedHeight = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_expanded);
        mKeyguardHeight = getResources().getDimensionPixelSize(
                R.dimen.status_bar_header_height_keyguard);
        mNormalWidth = getLayoutParams().width;
    }

    public int getCollapsedHeight() {
        return mKeyguardShowing ? mKeyguardHeight : mCollapsedHeight;
    }

    public int getExpandedHeight() {
        return mExpandedHeight;
    }

    public void setExpanded(boolean expanded) {
        mExpanded = expanded;
        updateHeights();
        updateVisibilities();
    }

    private void updateHeights() {
        int height;
        if (mExpanded) {
            height = mExpandedHeight;
        } else if (mKeyguardShowing) {
            height = mKeyguardHeight;
        } else {
            height = mCollapsedHeight;
        }
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            setLayoutParams(lp);
        }
        int systemIconsContainerHeight = mKeyguardShowing ? mKeyguardHeight : mCollapsedHeight;
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
        mBackground.setVisibility(mKeyguardShowing ? View.INVISIBLE : View.VISIBLE);
        mDateTime.setVisibility(mKeyguardShowing ? View.INVISIBLE : View.VISIBLE);
        mKeyguardCarrierText.setVisibility(mKeyguardShowing ? View.VISIBLE : View.GONE);
        mDate.setVisibility(mExpanded ? View.VISIBLE : View.GONE);
    }

    public void setExpansion(float height) {
        if (height < mCollapsedHeight) {
            height = mCollapsedHeight;
        }
        if (height > mExpandedHeight) {
            height = mExpandedHeight;
        }
        if (mExpanded) {
            mBackground.setTranslationY(-(mExpandedHeight - height));
        } else {
            mBackground.setTranslationY(0);
        }
    }

    public View getBackgroundView() {
        return mBackground;
    }

    public void attachSystemIcons(LinearLayout systemIcons) {
        mSystemIconsContainer.addView(systemIcons);
    }

    public void setKeyguardShowing(boolean keyguardShowing) {
        mKeyguardShowing = keyguardShowing;
        if (keyguardShowing) {
            setZ(0);
        } else {
            setTranslationZ(0);
        }
        updateHeights();
        updateWidth();
        updateVisibilities();
    }

    public void setUserInfoController(UserInfoController userInfoController) {
        mMultiUserSwitch.setUserInfoController(userInfoController);
    }

    public void setOverlayParent(ViewGroup parent) {
        mMultiUserSwitch.setOverlayParent(parent);
    }
}
