/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.bidi;

import android.app.Activity;
import android.os.Bundle;
import android.widget.SeekBar;

import static com.android.bidi.BiDiTestConstants.FONT_MAX_SIZE;
import static com.android.bidi.BiDiTestConstants.FONT_MIN_SIZE;

public class BiDiTestCanvasActivity extends Activity {

    static final int INIT_TEXT_SIZE = (FONT_MAX_SIZE - FONT_MIN_SIZE) / 2;

    private BiDiTestView testView;
    private SeekBar textSizeSeekBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.canvas);

        testView = (BiDiTestView) findViewById(R.id.testview);
        testView.setCurrentTextSize(INIT_TEXT_SIZE);

        textSizeSeekBar = (SeekBar) findViewById(R.id.seekbar);
        textSizeSeekBar.setProgress(INIT_TEXT_SIZE);
        textSizeSeekBar.setMax(FONT_MAX_SIZE - FONT_MIN_SIZE);

        textSizeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                testView.setCurrentTextSize(FONT_MIN_SIZE + progress);
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }
}
