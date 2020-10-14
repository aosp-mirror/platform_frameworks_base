/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs;

import android.graphics.drawable.Drawable;
import android.os.UserManager;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.statusbar.policy.UserInfoController;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Controller for {@link QSFooterImpl}.
 */
@QSScope
public class QSFooterImplController extends ViewController<QSFooterImpl> implements QSFooter {

    private final UserManager mUserManager;
    private final UserInfoController mUserInfoController;

    private final UserInfoController.OnUserInfoChangedListener mOnUserInfoChangedListener =
            new UserInfoController.OnUserInfoChangedListener() {
        @Override
        public void onUserInfoChanged(String name, Drawable picture, String userAccount) {
            boolean isGuestUser = mUserManager.isGuestUser(KeyguardUpdateMonitor.getCurrentUser());
            mView.onUserInfoChanged(picture, isGuestUser);
        }
    };

    private boolean mListening;

    @Inject
    QSFooterImplController(QSFooterImpl view, UserManager userManager,
            UserInfoController userInfoController) {
        super(view);
        mUserManager = userManager;
        mUserInfoController = userInfoController;
    }


    @Override
    protected void onViewAttached() {
    }

    @Override
    protected void onViewDetached() {
        setListening(false);
    }


    @Override
    public void setQSPanel(@Nullable QSPanel panel) {
        mView.setQSPanel(panel);
    }

    @Override
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
    }

    @Override
    public void setExpanded(boolean expanded) {
        mView.setExpanded(expanded);
    }

    @Override
    public int getHeight() {
        return mView.getHeight();
    }

    @Override
    public void setExpansion(float expansion) {
        mView.setExpansion(expansion);
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) {
            return;
        }

        mListening = listening;
        if (mListening) {
            mUserInfoController.addCallback(mOnUserInfoChangedListener);
        } else {
            mUserInfoController.removeCallback(mOnUserInfoChangedListener);
        }
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        mView.setKeyguardShowing(keyguardShowing);
    }

    @Override
    public void setExpandClickListener(View.OnClickListener onClickListener) {
        mView.setExpandClickListener(onClickListener);
    }
    @Override
    public void setQQSPanel(@Nullable QuickQSPanel panel) {
        mView.setQQSPanel(panel);
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        mView.disable(state1, state2, animate);
    }

    /**
     * Factory for {@link QSFooterImplController}.
     *
     * TODO(b/168904199): Delete this once QSFooterImpl is no longer marked as injectable.
     */
    @QSScope
    public static class Factory {
        private final UserManager mUserManager;
        private final UserInfoController mUserInfoController;

        @Inject
        Factory(UserManager userManager, UserInfoController userInfoController) {
            mUserManager = userManager;
            mUserInfoController = userInfoController;
        }

        QSFooterImplController create(QSFooterImpl view) {
            return new QSFooterImplController(view, mUserManager, mUserInfoController);
        }
    }
}
