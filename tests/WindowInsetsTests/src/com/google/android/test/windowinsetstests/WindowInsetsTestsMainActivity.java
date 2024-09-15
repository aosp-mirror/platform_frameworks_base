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

package com.google.android.test.windowinsetstests;

import static android.view.WindowInsets.Type.displayCutout;
import static android.view.WindowInsets.Type.systemBars;

import android.content.Intent;
import android.graphics.Insets;
import android.os.Bundle;
import android.view.WindowInsets;

import androidx.appcompat.app.AppCompatActivity;

public class WindowInsetsTestsMainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);
        setSupportActionBar(findViewById(R.id.toolbar));

        findViewById(R.id.root).setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    final Insets i = insets.getInsets(systemBars() | displayCutout());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return WindowInsets.CONSUMED;
                });

        findViewById(R.id.chat_button).setOnClickListener(
                v -> startActivity(new Intent(this, ChatActivity.class)));

        findViewById(R.id.controller_button).setOnClickListener(
                v -> startActivity(new Intent(this, ControllerActivity.class)));
    }
}
