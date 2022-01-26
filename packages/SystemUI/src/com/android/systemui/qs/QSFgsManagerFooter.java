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

import static android.provider.DeviceConfig.NAMESPACE_SYSTEMUI;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.TASK_MANAGER_ENABLED;
import static com.android.systemui.qs.dagger.QSFragmentModule.QS_FGS_MANAGER_FOOTER_VIEW;

import android.content.Context;
import android.provider.DeviceConfig;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.fgsmanager.FgsManagerDialogFactory;
import com.android.systemui.statusbar.policy.RunningFgsController;

import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Footer entry point for the foreground service manager
 */
public class QSFgsManagerFooter implements View.OnClickListener {

    private final View mRootView;
    private final TextView mFooterText;
    private final Context mContext;
    private final Executor mMainExecutor;
    private final Executor mExecutor;
    private final RunningFgsController mRunningFgsController;
    private final FgsManagerDialogFactory mFgsManagerDialogFactory;

    private boolean mIsInitialized = false;
    private boolean mIsAvailable = false;

    @Inject
    QSFgsManagerFooter(@Named(QS_FGS_MANAGER_FOOTER_VIEW) View rootView,
            @Main Executor mainExecutor, RunningFgsController runningFgsController,
            @Background Executor executor,
            FgsManagerDialogFactory fgsManagerDialogFactory) {
        mRootView = rootView;
        mFooterText = mRootView.findViewById(R.id.footer_text);
        ImageView icon = mRootView.findViewById(R.id.primary_footer_icon);
        icon.setImageResource(R.drawable.ic_info_outline);
        mContext = rootView.getContext();
        mMainExecutor = mainExecutor;
        mExecutor = executor;
        mRunningFgsController = runningFgsController;
        mFgsManagerDialogFactory = fgsManagerDialogFactory;
    }

    public void init() {
        if (mIsInitialized) {
            return;
        }

        mRootView.setOnClickListener(this);

        mRunningFgsController.addCallback(packages -> refreshState());

        DeviceConfig.addOnPropertiesChangedListener(NAMESPACE_SYSTEMUI, mExecutor,
                (DeviceConfig.OnPropertiesChangedListener) properties -> {
                    mIsAvailable = properties.getBoolean(TASK_MANAGER_ENABLED, mIsAvailable);
                });
        mIsAvailable = DeviceConfig.getBoolean(NAMESPACE_SYSTEMUI, TASK_MANAGER_ENABLED, false);

        mIsInitialized = true;
    }

    @Override
    public void onClick(View view) {
        mFgsManagerDialogFactory.create(mRootView);
    }

    public void refreshState() {
        mExecutor.execute(this::handleRefreshState);
    }

    public View getView() {
        return mRootView;
    }

    private boolean isAvailable() {
        return mIsAvailable;
    }

    public void handleRefreshState() {
        int numPackages = mRunningFgsController.getPackagesWithFgs().size();
        mMainExecutor.execute(() -> {
            mFooterText.setText(mContext.getResources().getQuantityString(
                    R.plurals.fgs_manager_footer_label, numPackages, numPackages));
            mRootView.setVisibility(numPackages > 0 && isAvailable() ? View.VISIBLE : View.GONE);
        });
    }

}
