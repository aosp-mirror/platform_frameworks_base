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

import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.content.Context;
import android.os.UserManager;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;

import com.android.systemui.Dependency;
import com.android.systemui.Prefs;
import com.android.systemui.Prefs.Key;
import com.android.systemui.R;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.policy.UserSwitcherController;

/**
 * Container for image of the multi user switcher (tappable).
 */
public class MultiUserSwitch extends FrameLayout implements View.OnClickListener {

    protected QSPanel mQsPanel;
    private KeyguardUserSwitcher mKeyguardUserSwitcher;
    private boolean mKeyguardMode;
    private UserSwitcherController.BaseUserAdapter mUserListener;

    final UserManager mUserManager;

    private final int[] mTmpInt2 = new int[2];

    protected UserSwitcherController mUserSwitcherController;

    public MultiUserSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        mUserManager = UserManager.get(getContext());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setOnClickListener(this);
        refreshContentDescription();
    }

    public void setQsPanel(QSPanel qsPanel) {
        mQsPanel = qsPanel;
        setUserSwitcherController(Dependency.get(UserSwitcherController.class));
    }

    public boolean hasMultipleUsers() {
        if (mUserListener == null) {
            return false;
        }
        return mUserListener.getUserCount() != 0
                && Prefs.getBoolean(getContext(), Key.SEEN_MULTI_USER, false);
    }

    public void setUserSwitcherController(UserSwitcherController userSwitcherController) {
        mUserSwitcherController = userSwitcherController;
        registerListener();
        refreshContentDescription();
    }

    public void setKeyguardUserSwitcher(KeyguardUserSwitcher keyguardUserSwitcher) {
        mKeyguardUserSwitcher = keyguardUserSwitcher;
    }

    public void setKeyguardMode(boolean keyguardShowing) {
        mKeyguardMode = keyguardShowing;
        registerListener();
    }

    public boolean isMultiUserEnabled() {
        // TODO(b/138661450) Move IPC calls to background
        return whitelistIpcs(() -> mUserManager.isUserSwitcherEnabled(
                mContext.getResources().getBoolean(R.bool.qs_show_user_switcher_for_single_user)));
    }

    private void registerListener() {
        if (mUserManager.isUserSwitcherEnabled() && mUserListener == null) {

            final UserSwitcherController controller = mUserSwitcherController;
            if (controller != null) {
                mUserListener = new UserSwitcherController.BaseUserAdapter(controller) {
                    @Override
                    public void notifyDataSetChanged() {
                        refreshContentDescription();
                    }

                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        return null;
                    }
                };
                refreshContentDescription();
            }
        }
    }

    @Override
    public void onClick(View v) {
        if (mKeyguardMode) {
            if (mKeyguardUserSwitcher != null) {
                mKeyguardUserSwitcher.show(true /* animate */);
            }
        } else if (mQsPanel != null && mUserSwitcherController != null) {
            View center = getChildCount() > 0 ? getChildAt(0) : this;

            center.getLocationInWindow(mTmpInt2);
            mTmpInt2[0] += center.getWidth() / 2;
            mTmpInt2[1] += center.getHeight() / 2;

            mQsPanel.showDetailAdapter(true,
                    getUserDetailAdapter(),
                    mTmpInt2);
        }
    }

    @Override
    public void setClickable(boolean clickable) {
        super.setClickable(clickable);
        refreshContentDescription();
    }

    private void refreshContentDescription() {
        String currentUser = null;
        // TODO(b/138661450)
        if (whitelistIpcs(() -> mUserManager.isUserSwitcherEnabled())
                && mUserSwitcherController != null) {
            currentUser = mUserSwitcherController.getCurrentUserName(mContext);
        }

        String text = null;

        if (!TextUtils.isEmpty(currentUser)) {
            text = mContext.getString(
                    R.string.accessibility_quick_settings_user,
                    currentUser);
        }

        if (!TextUtils.equals(getContentDescription(), text)) {
            setContentDescription(text);
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        event.setClassName(Button.class.getName());
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(Button.class.getName());
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    protected DetailAdapter getUserDetailAdapter() {
        return mUserSwitcherController.userDetailAdapter;
    }
}
