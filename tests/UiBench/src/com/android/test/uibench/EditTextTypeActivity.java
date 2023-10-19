/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Note: currently incomplete, complexity of input continuously grows, instead of looping
 * over a stable amount of work.
 *
 * Simulates typing continuously into an EditText.
 */
public class EditTextTypeActivity extends AppCompatActivity {

    /**
     * Broadcast action: Used to notify UiBenchEditTextTypingMicrobenchmark test when the
     * test activity was paused.
     */
    private static final String ACTION_CANCEL_TYPING_CALLBACK =
            "com.android.uibench.action.CANCEL_TYPING_CALLBACK";

    private static String sSeedText = "";
    static {
        final int count = 100;
        final String string = "hello ";

        StringBuilder builder = new StringBuilder(count * string.length());
        for (int i = 0; i < count; i++) {
            builder.append(string);
        }
        sSeedText = builder.toString();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        EditText editText = new EditText(this);
        editText.setText(sSeedText);
        setContentView(editText);
    }

    @Override
    protected void onPause() {
        // Cancel the typing when the test activity was paused.
        sendBroadcast(new Intent(ACTION_CANCEL_TYPING_CALLBACK).addFlags(
                Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_RECEIVER_REGISTERED_ONLY));
        super.onPause();
    }
}
