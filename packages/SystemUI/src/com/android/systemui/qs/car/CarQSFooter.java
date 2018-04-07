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
package com.android.systemui.qs.car;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.QSFooter;
import com.android.systemui.qs.QSPanel;
import com.android.systemui.statusbar.phone.MultiUserSwitch;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.UserInfoController;

/**
 * The footer view that displays below the status bar in the auto use-case. This view shows the
 * user switcher and access to settings.
 */
public class CarQSFooter extends RelativeLayout implements QSFooter,
        UserInfoController.OnUserInfoChangedListener {
    private static final String TAG = "CarQSFooter";

    private UserInfoController mUserInfoController;

    private MultiUserSwitch mMultiUserSwitch;
    private TextView mUserName;
    private ImageView mMultiUserAvatar;
    private CarQSFragment.UserSwitchCallback mUserSwitchCallback;

    public CarQSFooter(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mMultiUserSwitch = findViewById(R.id.multi_user_switch);
        mMultiUserAvatar = mMultiUserSwitch.findViewById(R.id.multi_user_avatar);
        mUserName = findViewById(R.id.user_name);

        mUserInfoController = Dependency.get(UserInfoController.class);

        mMultiUserSwitch.setOnClickListener(v -> {
            if (mUserSwitchCallback == null) {
                Log.e(TAG, "CarQSFooter not properly set up; cannot display user switcher.");
                return;
            }

            if (!mUserSwitchCallback.isShowing()) {
                mUserSwitchCallback.show();
            } else {
                mUserSwitchCallback.hide();
            }
        });

        findViewById(R.id.settings_button).setOnClickListener(v -> {
            ActivityStarter activityStarter = Dependency.get(ActivityStarter.class);

            if (!Dependency.get(DeviceProvisionedController.class).isCurrentUserSetup()) {
                // If user isn't setup just unlock the device and dump them back at SUW.
                activityStarter.postQSRunnableDismissingKeyguard(() -> { });
                return;
            }

            activityStarter.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS),
                    true /* dismissShade */);
        });
    }

    @Override
    public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
        mMultiUserAvatar.setImageDrawable(picture);
        mUserName.setText(name);
    }

    @Override
    public void setQSPanel(@Nullable QSPanel panel) {
        if (panel != null) {
            mMultiUserSwitch.setQsPanel(panel);
        }
    }

    public void setUserSwitchCallback(CarQSFragment.UserSwitchCallback callback) {
        mUserSwitchCallback = callback;
    }

    @Override
    public void setListening(boolean listening) {
        if (listening) {
            mUserInfoController.addCallback(this);
        } else {
            mUserInfoController.removeCallback(this);
        }
    }

    @Nullable
    @Override
    public View getExpandView() {
        // No view that should expand/collapse the quick settings.
        return null;
    }

    @Override
    public void setExpanded(boolean expanded) {
        // Do nothing because the quick settings cannot be expanded.
    }

    @Override
    public void setExpansion(float expansion) {
        // Do nothing because the quick settings cannot be expanded.
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        // Do nothing because the footer will not be shown when the keyguard is up.
    }
}
