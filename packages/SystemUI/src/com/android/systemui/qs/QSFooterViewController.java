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
    private final QuickQSPanelController mQuickQSPanelController;
    private final QSFooterActionsController mQsFooterActionsController;
    private final TextView mBuildText;
    private final PageIndicator mPageIndicator;

    @Inject
    QSFooterViewController(QSFooterView view,
            UserTracker userTracker,
            QSPanelController qsPanelController,
            QuickQSPanelController quickQSPanelController,
            QSFooterActionsController qsFooterActionsController) {
        super(view);
        mUserTracker = userTracker;
        mQsPanelController = qsPanelController;
        mQuickQSPanelController = quickQSPanelController;
        mQsFooterActionsController = qsFooterActionsController;

        mBuildText = mView.findViewById(R.id.build);
        mPageIndicator = mView.findViewById(R.id.footer_page_indicator);
    }

    @Override
    protected void onInit() {
        super.onInit();
        mQsFooterActionsController.init();
    }

    @Override
    protected void onViewAttached() {
        mView.addOnLayoutChangeListener(
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    mView.updateExpansion();
                    mQsFooterActionsController.updateAnimator(right - left,
                            mQuickQSPanelController.getNumQuickTiles());
                }
        );

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
        mQsPanelController.setFooterPageIndicator(mPageIndicator);
        mView.updateEverything();
    }

    @Override
    protected void onViewDetached() {
        setListening(false);
    }

    @Override
    public void setVisibility(int visibility) {
        mView.setVisibility(visibility);
    }

    @Override
    public void setExpanded(boolean expanded) {
        mQsFooterActionsController.setExpanded(expanded);
        mView.setExpanded(expanded);
    }

    @Override
    public void setExpansion(float expansion) {
        mView.setExpansion(expansion);
        mQsFooterActionsController.setExpansion(expansion);
    }

    @Override
    public void setListening(boolean listening) {
        mQsFooterActionsController.setListening(listening);
    }

    @Override
    public void setKeyguardShowing(boolean keyguardShowing) {
        mView.setKeyguardShowing();
        mQsFooterActionsController.setKeyguardShowing();
    }

    /** */
    @Override
    public void setExpandClickListener(View.OnClickListener onClickListener) {
        mView.setExpandClickListener(onClickListener);
    }

    @Override
    public void disable(int state1, int state2, boolean animate) {
        mView.disable(state2);
        mQsFooterActionsController.disable(state2);
    }
}
