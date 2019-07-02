/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the License);
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an AS IS BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.benchmark.ui;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import android.view.KeyEvent;
import android.widget.EditText;

import com.android.benchmark.R;
import com.android.benchmark.ui.automation.Automator;
import com.android.benchmark.ui.automation.Interaction;

public class EditTextInputActivity extends AppCompatActivity {

    private Automator mAutomator;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final EditText editText = new EditText(this);
        final int runId = getIntent().getIntExtra("com.android.benchmark.RUN_ID", 0);
        final int iteration = getIntent().getIntExtra("com.android.benchmark.ITERATION", -1);

        editText.setWidth(400);
        editText.setHeight(200);
        setContentView(editText);

        String testName = getString(R.string.edit_text_input_name);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle(testName);
        }

        mAutomator = new Automator(testName, runId, iteration, getWindow(),
                new Automator.AutomateCallback() {
            @Override
            public void onPostAutomate() {
                Intent result = new Intent();
                setResult(RESULT_OK, result);
                finish();
            }

            @Override
            public void onAutomate() {

                int[] coordinates = new int[2];
                editText.getLocationOnScreen(coordinates);

                int x = coordinates[0];
                int y = coordinates[1];

                float width = editText.getWidth();
                float height = editText.getHeight();

                float middleX = (x + width) / 2;
                float middleY = (y + height) / 2;

                Interaction tap = Interaction.newTap(middleX, middleY);
                addInteraction(tap);

                int[] alphabet = {
                        KeyEvent.KEYCODE_A,
                        KeyEvent.KEYCODE_B,
                        KeyEvent.KEYCODE_C,
                        KeyEvent.KEYCODE_D,
                        KeyEvent.KEYCODE_E,
                        KeyEvent.KEYCODE_F,
                        KeyEvent.KEYCODE_G,
                        KeyEvent.KEYCODE_H,
                        KeyEvent.KEYCODE_I,
                        KeyEvent.KEYCODE_J,
                        KeyEvent.KEYCODE_K,
                        KeyEvent.KEYCODE_L,
                        KeyEvent.KEYCODE_M,
                        KeyEvent.KEYCODE_N,
                        KeyEvent.KEYCODE_O,
                        KeyEvent.KEYCODE_P,
                        KeyEvent.KEYCODE_Q,
                        KeyEvent.KEYCODE_R,
                        KeyEvent.KEYCODE_S,
                        KeyEvent.KEYCODE_T,
                        KeyEvent.KEYCODE_U,
                        KeyEvent.KEYCODE_V,
                        KeyEvent.KEYCODE_W,
                        KeyEvent.KEYCODE_X,
                        KeyEvent.KEYCODE_Y,
                        KeyEvent.KEYCODE_Z,
                        KeyEvent.KEYCODE_SPACE
                };
                Interaction typeAlphabet = Interaction.newKeyInput(new int[] {
                        KeyEvent.KEYCODE_A,
                        KeyEvent.KEYCODE_B,
                        KeyEvent.KEYCODE_C,
                        KeyEvent.KEYCODE_D,
                        KeyEvent.KEYCODE_E,
                        KeyEvent.KEYCODE_F,
                        KeyEvent.KEYCODE_G,
                        KeyEvent.KEYCODE_H,
                        KeyEvent.KEYCODE_I,
                        KeyEvent.KEYCODE_J,
                        KeyEvent.KEYCODE_K,
                        KeyEvent.KEYCODE_L,
                        KeyEvent.KEYCODE_M,
                        KeyEvent.KEYCODE_N,
                        KeyEvent.KEYCODE_O,
                        KeyEvent.KEYCODE_P,
                        KeyEvent.KEYCODE_Q,
                        KeyEvent.KEYCODE_R,
                        KeyEvent.KEYCODE_S,
                        KeyEvent.KEYCODE_T,
                        KeyEvent.KEYCODE_U,
                        KeyEvent.KEYCODE_V,
                        KeyEvent.KEYCODE_W,
                        KeyEvent.KEYCODE_X,
                        KeyEvent.KEYCODE_Y,
                        KeyEvent.KEYCODE_Z,
                        KeyEvent.KEYCODE_SPACE,
                });

                for (int i = 0; i < 5; i++) {
                    addInteraction(typeAlphabet);
                }
            }
        });
        mAutomator.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mAutomator != null) {
            mAutomator.cancel();
            mAutomator = null;
        }
    }

    private String getRunFilename() {
        StringBuilder builder = new StringBuilder();
        builder.append(getClass().getSimpleName());
        builder.append(System.currentTimeMillis());
        return builder.toString();
    }
}
