/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.packageinstaller.v2.ui;

import static android.os.Process.INVALID_UID;
import static android.view.WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import com.android.packageinstaller.v2.model.UninstallRepository;
import com.android.packageinstaller.v2.model.UninstallRepository.CallerInfo;
import com.android.packageinstaller.v2.model.uninstallstagedata.UninstallStage;
import com.android.packageinstaller.v2.viewmodel.UninstallViewModel;
import com.android.packageinstaller.v2.viewmodel.UninstallViewModelFactory;

public class UninstallLaunch extends FragmentActivity{

    public static final String EXTRA_CALLING_PKG_UID =
        UninstallLaunch.class.getPackageName() + ".callingPkgUid";
    public static final String EXTRA_CALLING_ACTIVITY_NAME =
        UninstallLaunch.class.getPackageName() + ".callingActivityName";
    public static final String TAG = UninstallLaunch.class.getSimpleName();

    private UninstallViewModel mUninstallViewModel;
    private UninstallRepository mUninstallRepository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        getWindow().addSystemFlags(SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        // Never restore any state, esp. never create any fragments. The data in the fragment might
        // be stale, if e.g. the app was uninstalled while the activity was destroyed.
        super.onCreate(null);

        mUninstallRepository = new UninstallRepository(getApplicationContext());
        mUninstallViewModel = new ViewModelProvider(this,
            new UninstallViewModelFactory(this.getApplication(), mUninstallRepository)).get(
            UninstallViewModel.class);

        Intent intent = getIntent();
        CallerInfo callerInfo = new CallerInfo(
            intent.getStringExtra(EXTRA_CALLING_ACTIVITY_NAME),
            intent.getIntExtra(EXTRA_CALLING_PKG_UID, INVALID_UID));
        mUninstallViewModel.preprocessIntent(intent, callerInfo);

        mUninstallViewModel.getCurrentUninstallStage().observe(this,
            this::onUninstallStageChange);
    }

    /**
     * Main controller of the UI. This method shows relevant dialogs / fragments based on the
     * uninstall stage
     */
    private void onUninstallStageChange(UninstallStage uninstallStage) {
    }
}
