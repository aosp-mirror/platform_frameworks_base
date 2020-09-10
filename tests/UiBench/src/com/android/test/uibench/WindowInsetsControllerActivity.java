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

package com.android.test.uibench;

import static android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP;

import android.os.Bundle;
import android.os.PersistableBundle;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

public class WindowInsetsControllerActivity extends AppCompatActivity {

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EditText text = new EditText(this);
        text.setText("WindowInsetsController");
        setContentView(text);
        getWindow().setDecorFitsSystemWindows(false);

        text.setWindowInsetsAnimationCallback(
                new WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
            @NonNull
            @Override
            public WindowInsets onProgress(@NonNull WindowInsets insets,
                    @NonNull List<WindowInsetsAnimation> runningAnimations) {
                return WindowInsets.CONSUMED;
            }
        });
    }
}
