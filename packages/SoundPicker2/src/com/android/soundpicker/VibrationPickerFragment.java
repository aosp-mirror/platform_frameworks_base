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

package com.android.soundpicker;

import android.os.Bundle;
import android.view.View;

import androidx.lifecycle.ViewModelProvider;

import org.jetbrains.annotations.NotNull;

/**
 * A fragment that displays a picker used to select vibration or silent (no vibration).
 */
public class VibrationPickerFragment extends BasePickerFragment {

    @Override
    public void onViewCreated(@NotNull View view, Bundle savedInstanceState) {
        mRingtonePickerViewModel = new ViewModelProvider(requireActivity()).get(
                RingtonePickerViewModel.class);
        super.onViewCreated(view, savedInstanceState);
    }

    @Override
    protected RingtoneListHandler getRingtoneListHandler() {
        return mRingtonePickerViewModel.getVibrationListHandler();
    }

    @Override
    protected void addRingtoneAsync() {
        // no-op
    }

    @Override
    protected void addNewRingtoneItem() {
        // no-op
    }
}
