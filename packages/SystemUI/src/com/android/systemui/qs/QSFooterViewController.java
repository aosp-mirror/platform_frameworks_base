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

import android.content.ClipData;
import android.content.ClipboardManager;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.FalsingManager;
import com.android.systemui.qs.dagger.QSScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.util.ViewController;

import javax.inject.Inject;

/**
 * Controller for {@link QSFooterView}.
 */
@QSScope
public class QSFooterViewController extends ViewController<QSFooterView> implements QSFooter {

    private final UserTracker mUserTracker;
    private final QSPanelController mQsPanelController;
    private final TextView mBuildText;
    private final PageIndicator mPageIndicator;
    private final View mEditButton;
    private final FalsingManager mFalsingManager;
    private final ActivityStarter mActivityStarter;

    @Inject
    QSFooterViewController(QSFooterView view,
            UserTracker userTracker,
            FalsingManager falsingManager,
            ActivityStarter activityStarter,
            QSPanelController qsPanelController) {
        super(view);
        mUserTracker = userTracker;
        mQsPanelController = qsPanelController;
        mFalsingManager = falsingManager;
        mActivityStarter = activityStarter;

        mBuildText = mView.findViewById(R.id.build);
        mPageIndicator = mView.findViewById(R.id.footer_page_indicator);
        mEditButton = mView.findViewById(android.R.id.edit);
    }

    @Override
    protected void onViewAttached() {
        mBuildText.setOnLongClickListener(view -> {
            CharSequence buildText = mBuildText.getText();
            if (!TextUtils.isEmpty(buildText)) {
                ClipboardManager service =
                        mUserTracker.getUserContext().getSystemService(ClipboardManager.class);
                String label = getResources().getString(R.string.build_number_clip_data_label);
                service.setPrimaryClip(ClipData.newPlainText(label, buildText));
                Toast.makeText(getContext(), R.string.build_number_copy_toast, Toast.LENGTH_SHORT)
                        .show();
                return true;
            }
            return false;
        });

        mEditButton.setOnClickListener(view -> {
            if (mFalsingManager.isFalseTap(FalsingManager.LOW_PENALTY)) {
                return;
            }
            mActivityStarter
                    .postQSRunnableDismissingKeyguard(() -> mQsPanelController.showEdit(view));
        });
        mQsPanelController.setFooterPageIndicator(mPageIndicator);
        mView.updateEverything();
    }

    @Override
    protected void onViewDetached() {}

    @Override
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
        mEditButton.setClickable(visibility == View.VISIBLE);
    }

    @Override
    public void setExpanded(boolean expanded) {
        mView.setExpanded(expanded);
    }

    @Override
    public void setExpansion(float expansion) {
        mView.setExpansion(expansion);
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        mView.setKeyguardShowing();
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        mView.disable(state2);
    }
}
