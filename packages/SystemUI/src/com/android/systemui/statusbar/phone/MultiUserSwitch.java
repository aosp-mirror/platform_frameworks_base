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
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.ContactsContract;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import com.android.systemui.R;
import com.android.systemui.settings.UserSwitcherHostView;
import com.android.systemui.statusbar.policy.UserInfoController;

/**
 * Container for image of the multi user switcher (tappable).
 */
public class MultiUserSwitch extends FrameLayout implements View.OnClickListener {

    private ViewGroup mOverlayParent;

    public MultiUserSwitch(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        setOnClickListener(this);
    }

    public void setOverlayParent(ViewGroup parent) {
        mOverlayParent = parent;
    }

    @Override
    public void onClick(View v) {
        final UserManager um = UserManager.get(getContext());
        if (um.isUserSwitcherEnabled()) {
            final UserSwitcherHostView switcher =
                    (UserSwitcherHostView) LayoutInflater.from(getContext()).inflate(
                            R.layout.user_switcher_host, mOverlayParent, false);
            switcher.setFinishRunnable(new Runnable() {
                @Override
                public void run() {
                    mOverlayParent.removeView(switcher);
                }
            });
            switcher.refreshUsers();
            mOverlayParent.addView(switcher);
        } else {
            Intent intent = ContactsContract.QuickContact.composeQuickContactsIntent(
                    getContext(), v, ContactsContract.Profile.CONTENT_URI,
                    ContactsContract.QuickContact.MODE_LARGE, null);
            getContext().startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        }
    }
}
