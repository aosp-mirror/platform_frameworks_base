/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.systemui.qs.dagger.QSFragmentModule.QS_FGS_MANAGER_FOOTER_VIEW;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Footer entry point for the foreground service manager
 */
public class QSFgsManagerFooter implements View.OnClickListener,
        FgsManagerController.OnDialogDismissedListener,
        FgsManagerController.OnNumberOfPackagesChangedListener {

    private final View mRootView;
    private final TextView mFooterText;
    private final Context mContext;
    private final Executor mMainExecutor;
    private final Executor mExecutor;

    private final FgsManagerController mFgsManagerController;

    private boolean mIsInitialized = false;
    private int mNumPackages;

    @Inject
    QSFgsManagerFooter(@Named(QS_FGS_MANAGER_FOOTER_VIEW) View rootView,
            @Main Executor mainExecutor, @Background Executor executor,
            FgsManagerController fgsManagerController) {
        mRootView = rootView;
        mFooterText = mRootView.findViewById(R.id.footer_text);
        ImageView icon = mRootView.findViewById(R.id.primary_footer_icon);
        icon.setImageResource(R.drawable.ic_info_outline);
        mContext = rootView.getContext();
        mMainExecutor = mainExecutor;
        mExecutor = executor;
        mFgsManagerController = fgsManagerController;
    }

    public void init() {
        if (mIsInitialized) {
            return;
        }

        mFgsManagerController.init();

        mRootView.setOnClickListener(this);

        mIsInitialized = true;
    }

    public void setListening(boolean listening) {
        if (listening) {
            mFgsManagerController.addOnDialogDismissedListener(this);
            mFgsManagerController.addOnNumberOfPackagesChangedListener(this);
            mNumPackages = mFgsManagerController.getNumRunningPackages();
            refreshState();
        } else {
            mFgsManagerController.removeOnDialogDismissedListener(this);
            mFgsManagerController.removeOnNumberOfPackagesChangedListener(this);
        }
    }

    @Override
    public void onClick(View view) {
        mFgsManagerController.showDialog(mRootView);
    }

    public void refreshState() {
        mExecutor.execute(this::handleRefreshState);
    }

    public View getView() {
        return mRootView;
    }

    public void handleRefreshState() {
        mMainExecutor.execute(() -> {
            mFooterText.setText(mContext.getResources().getQuantityString(
                    R.plurals.fgs_manager_footer_label, mNumPackages, mNumPackages));
            if (mFgsManagerController.shouldUpdateFooterVisibility()) {
                mRootView.setVisibility(mNumPackages > 0
                        && mFgsManagerController.isAvailable() ? View.VISIBLE : View.GONE);
            }
        });
    }

    @Override
    public void onDialogDismissed() {
        refreshState();
    }

    @Override
    public void onNumberOfPackagesChanged(int numPackages) {
        mNumPackages = numPackages;
        refreshState();
    }
}
