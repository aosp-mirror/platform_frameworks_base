/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm.flicker.testapp;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import android.content.Intent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ToggleButton;

public class ImeActivityAutoFocus extends ImeActivity {
    @Override
    protected void onStart() {
        super.onStart();

        Button startThemedActivityButton = findViewById(R.id.start_dialog_themed_activity_btn);
        startThemedActivityButton.setOnClickListener(
                button -> startActivity(new Intent(this, DialogThemedActivity.class)));

        ToggleButton toggleFixedPortraitButton = findViewById(R.id.toggle_fixed_portrait_btn);
        toggleFixedPortraitButton.setOnCheckedChangeListener(
                (button, isChecked) -> setRequestedOrientation(
                        isChecked ? SCREEN_ORIENTATION_PORTRAIT : SCREEN_ORIENTATION_UNSPECIFIED));

        EditText editTextField = findViewById(R.id.plain_text_input);
        editTextField.requestFocus();
    }
}
