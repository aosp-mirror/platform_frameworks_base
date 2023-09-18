/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.packageinstaller.v2.ui;

import static android.os.Process.INVALID_UID;

import android.content.Intent;
import android.os.Bundle;
import android.view.Window;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import com.android.packageinstaller.v2.model.InstallRepository;
import com.android.packageinstaller.v2.model.InstallRepository.CallerInfo;
import com.android.packageinstaller.v2.model.installstagedata.InstallStage;
import com.android.packageinstaller.v2.viewmodel.InstallViewModel;
import com.android.packageinstaller.v2.viewmodel.InstallViewModelFactory;

public class InstallLaunch extends FragmentActivity {

    public static final String EXTRA_CALLING_PKG_UID =
            InstallLaunch.class.getPackageName() + ".callingPkgUid";
    public static final String EXTRA_CALLING_PKG_NAME =
            InstallLaunch.class.getPackageName() + ".callingPkgName";
    private static final String TAG = InstallLaunch.class.getSimpleName();
    private InstallViewModel mInstallViewModel;
    private InstallRepository mInstallRepository;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        this.requestWindowFeature(Window.FEATURE_NO_TITLE);

        mInstallRepository = new InstallRepository(getApplicationContext());
        mInstallViewModel = new ViewModelProvider(this,
                new InstallViewModelFactory(this.getApplication(), mInstallRepository)).get(
                InstallViewModel.class);

        Intent intent = getIntent();
        CallerInfo info = new CallerInfo(
                intent.getStringExtra(EXTRA_CALLING_PKG_NAME),
                intent.getIntExtra(EXTRA_CALLING_PKG_UID, INVALID_UID));
        mInstallViewModel.preprocessIntent(intent, info);

        mInstallViewModel.getCurrentInstallStage().observe(this, this::onInstallStageChange);
    }

    /**
     * Main controller of the UI. This method shows relevant dialogs based on the install stage
     */
    private void onInstallStageChange(InstallStage installStage) {
    }
}
