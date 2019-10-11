/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Rational;
import android.view.WindowManager;
import android.widget.Button;

public class PipActivity extends Activity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowManager.LayoutParams p = getWindow().getAttributes();
        p.layoutInDisplayCutoutMode = WindowManager.LayoutParams
                .LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        getWindow().setAttributes(p);
        setContentView(R.layout.activity_pip);
        Button enterPip = (Button) findViewById(R.id.enter_pip);

        PictureInPictureParams params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(1, 1))
                .setSourceRectHint(new Rect(0, 0, 100, 100))
                .build();

        enterPip.setOnClickListener((v) -> enterPictureInPictureMode(params));
    }
}
