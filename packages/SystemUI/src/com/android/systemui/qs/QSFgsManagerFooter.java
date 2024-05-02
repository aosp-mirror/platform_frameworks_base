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
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.qs.dagger.QSScope;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Footer entry point for the foreground service manager
 */
@QSScope
public class QSFgsManagerFooter implements View.OnClickListener,
        FgsManagerController.OnDialogDismissedListener,
        FgsManagerController.OnNumberOfPackagesChangedListener,
        VisibilityChangedDispatcher {

    private final View mRootView;
    private final TextView mFooterText;
    private final Context mContext;
    private final Executor mMainExecutor;
    private final Executor mExecutor;
    private final ActivityStarter mActivityStarter;

    private final FgsManagerController mFgsManagerController;

    private boolean mIsInitialized = false;
    private int mNumPackages;

    private final View mTextContainer;
    private final View mNumberContainer;
    private final TextView mNumberView;
    private final ImageView mDotView;
    private final ImageView mCollapsedDotView;

    @Nullable
    private VisibilityChangedDispatcher.OnVisibilityChangedListener mVisibilityChangedListener;

    @Inject
    QSFgsManagerFooter(@Named(QS_FGS_MANAGER_FOOTER_VIEW) View rootView,
            @Main Executor mainExecutor, @Background Executor executor,
            FgsManagerController fgsManagerController,
            ActivityStarter activityStarter) {
        mRootView = rootView;
        mFooterText = mRootView.findViewById(R.id.footer_text);
        mTextContainer = mRootView.findViewById(R.id.fgs_text_container);
        mNumberContainer = mRootView.findViewById(R.id.fgs_number_container);
        mNumberView = mRootView.findViewById(R.id.fgs_number);
        mDotView = mRootView.findViewById(R.id.fgs_new);
        mCollapsedDotView = mRootView.findViewById(R.id.fgs_collapsed_new);
        mContext = rootView.getContext();
        mMainExecutor = mainExecutor;
        mExecutor = executor;
        mFgsManagerController = fgsManagerController;
        mActivityStarter = activityStarter;
    }

    /**
     * Whether to show the footer in collapsed mode (just a number) or not (text).
     * @param collapsed
     */
    public void setCollapsed(boolean collapsed) {
        mTextContainer.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        mNumberContainer.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mRootView.getLayoutParams();
        lp.width = collapsed ? ViewGroup.LayoutParams.WRAP_CONTENT : 0;
        lp.weight = collapsed ? 0f : 1f;
        mRootView.setLayoutParams(lp);
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
    public void setOnVisibilityChangedListener(
            @Nullable OnVisibilityChangedListener onVisibilityChangedListener) {
        mVisibilityChangedListener = onVisibilityChangedListener;
    }

    @Override
    public void onClick(View view) {
        mActivityStarter.dismissKeyguardThenExecute(
            () -> {
                mFgsManagerController.showDialog(mRootView);
                return false /* if the dismiss should be deferred */;
            },
            null /* cancelAction */,
            true /* afterKeyguardGone */
        );
    }

    public void refreshState() {
        mExecutor.execute(this::handleRefreshState);
    }

    public View getView() {
        return mRootView;
    }

    public void handleRefreshState() {
        mMainExecutor.execute(() -> {
            CharSequence text = mContext.getResources().getQuantityString(
                    R.plurals.fgs_manager_footer_label, mNumPackages, mNumPackages);
            mFooterText.setText(text);
            mNumberView.setText(Integer.toString(mNumPackages));
            mNumberView.setContentDescription(text);
            if (mFgsManagerController.shouldUpdateFooterVisibility()) {
                mRootView.setVisibility(mNumPackages > 0
                        && mFgsManagerController.isAvailable() ? View.VISIBLE : View.GONE);
                int dotVis = mFgsManagerController.getShowFooterDot()
                        && mFgsManagerController.getChangesSinceDialog() ? View.VISIBLE : View.GONE;
                mDotView.setVisibility(dotVis);
                mCollapsedDotView.setVisibility(dotVis);
                if (mVisibilityChangedListener != null) {
                    mVisibilityChangedListener.onVisibilityChanged(mRootView.getVisibility());
                }
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
