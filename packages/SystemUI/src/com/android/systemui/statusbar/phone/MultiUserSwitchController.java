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

import android.content.Intent;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.FooterActionsView;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.qs.user.UserSwitchDialogController;
import com.android.systemui.statusbar.policy.UserSwitcherController;
import com.android.systemui.user.UserSwitcherActivity;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/** View Controller for {@link MultiUserSwitch}. */
public class MultiUserSwitchController extends ViewController<MultiUserSwitch> {
    private final UserManager mUserManager;
    private final UserSwitcherController mUserSwitcherController;
    private final FalsingManager mFalsingManager;
    private final UserSwitchDialogController mUserSwitchDialogController;
    private final ActivityStarter mActivityStarter;
    private final FeatureFlags mFeatureFlags;

    private UserSwitcherController.BaseUserAdapter mUserListener;

    private final View.OnClickListener mOnClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return;
            }

            if (mFeatureFlags.isEnabled(Flags.FULL_SCREEN_USER_SWITCHER)) {
                Intent intent = new Intent(v.getContext(), UserSwitcherActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

                mActivityStarter.startActivity(intent, true /* dismissShade */,
                        ActivityLaunchAnimator.Controller.fromView(v, null),
                        true /* showOverlockscreenwhenlocked */, UserHandle.SYSTEM);
            } else {
                mUserSwitchDialogController.showDialog(v);
            }
        }
    };

    @QSScope
    public static class Factory {
        private final UserManager mUserManager;
        private final UserSwitcherController mUserSwitcherController;
        private final FalsingManager mFalsingManager;
        private final UserSwitchDialogController mUserSwitchDialogController;
        private final ActivityStarter mActivityStarter;
        private final FeatureFlags mFeatureFlags;

        @Inject
        public Factory(UserManager userManager, UserSwitcherController userSwitcherController,
                FalsingManager falsingManager,
                UserSwitchDialogController userSwitchDialogController, FeatureFlags featureFlags,
                ActivityStarter activityStarter) {
            mUserManager = userManager;
            mUserSwitcherController = userSwitcherController;
            mFalsingManager = falsingManager;
            mUserSwitchDialogController = userSwitchDialogController;
            mActivityStarter = activityStarter;
            mFeatureFlags = featureFlags;
        }

        public MultiUserSwitchController create(FooterActionsView view) {
            return new MultiUserSwitchController(view.findViewById(R.id.multi_user_switch),
                    mUserManager, mUserSwitcherController,
                    mFalsingManager, mUserSwitchDialogController, mFeatureFlags,
                    mActivityStarter);
        }
    }

    private MultiUserSwitchController(MultiUserSwitch view, UserManager userManager,
            UserSwitcherController userSwitcherController,
            FalsingManager falsingManager, UserSwitchDialogController userSwitchDialogController,
            FeatureFlags featureFlags, ActivityStarter activityStarter) {
        super(view);
        mUserManager = userManager;
        mUserSwitcherController = userSwitcherController;
        mFalsingManager = falsingManager;
        mUserSwitchDialogController = userSwitchDialogController;
        mFeatureFlags = featureFlags;
        mActivityStarter = activityStarter;
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
