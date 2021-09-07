/*
 * Copyright (C) 2021 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import static com.android.systemui.DejankUtils.whitelistIpcs;

import android.os.UserManager;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.plugins.qs.DetailAdapter;
import com.android.systemui.qs.QSDetailDisplayer;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.user.UserSwitchDialogController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** View Controller for {@link MultiUserSwitch}. */
@QSScope
public class MultiUserSwitchController extends ViewController<MultiUserSwitch> {
    private final UserManager mUserManager;
    private final UserSwitcherController mUserSwitcherController;
    private final QSDetailDisplayer mQsDetailDisplayer;
    private final FalsingManager mFalsingManager;
    private final UserSwitchDialogController mUserSwitchDialogController;
    private final FeatureFlags mFeatureFlags;

    private UserSwitcherController.BaseUserAdapter mUserListener;

    private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return;
            }

            if (mFeatureFlags.useNewUserSwitcher()) {
                mUserSwitchDialogController.showDialog(v);
            } else {
                View center = mView.getChildCount() > 0 ? mView.getChildAt(0) : mView;

                int[] tmpInt = new int[2];
                center.getLocationInWindow(tmpInt);
                tmpInt[0] += center.getWidth() / 2;
                tmpInt[1] += center.getHeight() / 2;

                mQsDetailDisplayer.showDetailAdapter(getUserDetailAdapter(), tmpInt[0], tmpInt[1]);
            }
        }
    };

    @Inject
    public MultiUserSwitchController(MultiUserSwitch view, UserManager userManager,
            UserSwitcherController userSwitcherController, QSDetailDisplayer qsDetailDisplayer,
            FalsingManager falsingManager, UserSwitchDialogController userSwitchDialogController,
            FeatureFlags featureFlags) {
        super(view);
        mUserManager = userManager;
        mUserSwitcherController = userSwitcherController;
        mQsDetailDisplayer = qsDetailDisplayer;
        mFalsingManager = falsingManager;
        mUserSwitchDialogController = userSwitchDialogController;
        mFeatureFlags = featureFlags;
    }

    @Override
    protected void onInit() {
        registerListener();
        mView.refreshContentDescription(getCurrentUser());
    }

    @Override
    protected void onViewAttached() {
        mView.setOnClickListener(mOnClickListener);
    }

    @Override
    protected void onViewDetached() {
        mView.setOnClickListener(null);
    }

    protected DetailAdapter getUserDetailAdapter() {
        return mUserSwitcherController.mUserDetailAdapter;
    }

    private void registerListener() {
        if (mUserManager.isUserSwitcherEnabled() && mUserListener == null) {

            final UserSwitcherController controller = mUserSwitcherController;
            if (controller != null) {
                mUserListener = new UserSwitcherController.BaseUserAdapter(controller) {
                    @Override
                    public void notifyDataSetChanged() {
                        mView.refreshContentDescription(getCurrentUser());
                    }

                    @Override
                    public View getView(int position, View convertView, ViewGroup parent) {
                        return null;
                    }
                };
                mView.refreshContentDescription(getCurrentUser());
            }
        }
    }

    private String getCurrentUser() {
        // TODO(b/138661450)
        if (whitelistIpcs(() -> mUserManager.isUserSwitcherEnabled())) {
            return mUserSwitcherController.getCurrentUserName();
        }

        return null;
    }

    /** Returns true if view should be made visible. */
    public boolean isMultiUserEnabled() {
        // TODO(b/138661450) Move IPC calls to background
        return whitelistIpcs(() -> mUserManager.isUserSwitcherEnabled(
                getResources().getBoolean(R.bool.qs_show_user_switcher_for_single_user)));
    }

}
