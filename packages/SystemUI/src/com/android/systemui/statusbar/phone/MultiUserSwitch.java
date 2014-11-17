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
import android.view.accessibility.AccessibilityEvent;
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
    final UserManager mUserManager;

    public MultiUserSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
        mUserManager = UserManager.get(getContext());
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setOnClickListener(this);
    }

    public void setQsPanel(QSPanel qsPanel) {
        mQsPanel = qsPanel;
    }

    public void setKeyguardUserSwitcher(KeyguardUserSwitcher keyguardUserSwitcher) {
        mKeyguardUserSwitcher = keyguardUserSwitcher;
    }

    public void setKeyguardMode(boolean keyguardShowing) {
        mKeyguardMode = keyguardShowing;
    }

    @Override
    public void onClick(View v) {
        if (UserSwitcherController.isUserSwitcherAvailable(mUserManager)) {
            if (mKeyguardMode) {
                if (mKeyguardUserSwitcher != null) {
                    mKeyguardUserSwitcher.show(true /* animate */);
                }
            } else {
                if (mQsPanel != null) {
                    UserSwitcherController userSwitcherController =
                            mQsPanel.getHost().getUserSwitcherController();
                    if (userSwitcherController != null) {
                        mQsPanel.showDetailAdapter(true, userSwitcherController.userDetailAdapter);
                    }
                }
            }
        } else {
            Intent intent = ContactsContract.QuickContact.composeQuickContactsIntent(
                    getContext(), v, ContactsContract.Profile.CONTENT_URI,
                    ContactsContract.QuickContact.MODE_LARGE, null);
            getContext().startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        }
    }

    @Override
    public void onPopulateAccessibilityEvent(AccessibilityEvent event) {
        super.onPopulateAccessibilityEvent(event);

        if (isClickable()) {
            String text;
            if (UserSwitcherController.isUserSwitcherAvailable(mUserManager)) {
                String currentUser = null;
                if (mQsPanel != null) {
                    UserSwitcherController controller = mQsPanel.getHost()
                            .getUserSwitcherController();
                    if (controller != null) {
                        currentUser = controller.getCurrentUserName(mContext);
                    }
                }
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
            if (!TextUtils.isEmpty(text)) {
                event.getText().add(text);
            }
        }

    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

}
