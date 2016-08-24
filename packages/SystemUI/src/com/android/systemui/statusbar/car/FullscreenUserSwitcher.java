/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.car;

import android.view.View;
import android.view.ViewStub;

import com.android.systemui.R;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.policy.UserSwitcherController;

/**
 * Manages the fullscreen user switcher.
 */
public class FullscreenUserSwitcher {

    private View mContainer;
    private UserGridView mUserGridView;
    private UserSwitcherController mUserSwitcherController;

    public FullscreenUserSwitcher(PhoneStatusBar statusBar,
            UserSwitcherController userSwitcherController,
            ViewStub containerStub) {
        mUserSwitcherController = userSwitcherController;
        mContainer = containerStub.inflate();
        mUserGridView = (UserGridView) mContainer.findViewById(R.id.user_grid);
        mUserGridView.init(statusBar, mUserSwitcherController);
    }

    public void onUserSwitched(int newUserId) {
        mUserGridView.onUserSwitched(newUserId);
    }

    public void show() {
        mContainer.setVisibility(View.VISIBLE);
    }

    public void hide() {
        mContainer.setVisibility(View.GONE);
    }
}

