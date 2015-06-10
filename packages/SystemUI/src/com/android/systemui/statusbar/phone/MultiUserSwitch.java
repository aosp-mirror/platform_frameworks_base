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
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.statusbar.policy.KeyguardUserSwitcher;
import com.android.systemui.statusbar.policy.UserSwitcherController;

/**
 * Container for image of the multi user switcher (tappable).
 */
public class MultiUserSwitch extends FrameLayout implements View.OnClickListener {

    private QSPanel mQsPanel;
    private KeyguardUserSwitcher mKeyguardUserSwitcher;
    private boolean mKeyguardMode;
    private UserSwitcherController.BaseUserAdapter mUserListener;

    final UserManager mUserManager;

    private final int[] mTmpInt2 = new int[2];

    private UserSwitcherController mUserSwitcherController;

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
        setUserSwitcherController(qsPanel.getHost().getUserSwitcherController());
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

    private void registerListener() {
        if (UserSwitcherController.isUserSwitcherAvailable(mUserManager) && mUserListener == null) {

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
        if (UserSwitcherController.isUserSwitcherAvailable(mUserManager)) {
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
                        mUserSwitcherController.userDetailAdapter,
                        mTmpInt2);
            }
        } else {
            Intent intent = ContactsContract.QuickContact.composeQuickContactsIntent(
                    getContext(), v, ContactsContract.Profile.CONTENT_URI,
                    ContactsContract.QuickContact.MODE_LARGE, null);
            getContext().startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        }
    }

    @Override
    public void setClickable(boolean clickable) {
        super.setClickable(clickable);
        refreshContentDescription();
    }

    private void refreshContentDescription() {
        String currentUser = null;
        if (UserSwitcherController.isUserSwitcherAvailable(mUserManager)
                && mUserSwitcherController != null) {
            currentUser = mUserSwitcherController.getCurrentUserName(mContext);
        }

        String text = null;
        if (isClickable()) {
            if (UserSwitcherController.isUserSwitcherAvailable(mUserManager)) {
                if (TextUtils.isEmpty(currentUser)) {
                    text = mContext.getString(R.string.accessibility_multi_user_switch_switcher);
                } else {
                    text = mContext.getString(
                            R.string.accessibility_multi_user_switch_switcher_with_current,
                            currentUser);
                }
            } else {
                text = mContext.getString(R.string.accessibility_multi_user_switch_quick_contact);
            }
        } else {
            if (!TextUtils.isEmpty(currentUser)) {
                text = mContext.getString(
                        R.string.accessibility_multi_user_switch_inactive,
                        currentUser);
            }
        }

        if (!TextUtils.equals(getContentDescription(), text)) {
            setContentDescription(text);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

}
