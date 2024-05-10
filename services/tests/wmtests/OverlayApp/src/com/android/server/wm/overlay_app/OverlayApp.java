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

package com.android.server.wm.overlay_app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;

/**
 * Test app that is translucent not touchable modal.
 * If launched with "disableInputSink" extra boolean value, this activity disables
 * ActivityRecordInputSinkEnabled as long as the permission is granted.
 */
public class OverlayApp extends Activity {
    private static final String KEY_DISABLE_INPUT_SINK = "disableInputSink";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout tv = new LinearLayout(this);
        tv.setBackgroundColor(Color.GREEN);
        tv.setPadding(50, 50, 50, 50);
        tv.setGravity(Gravity.CENTER);
        setContentView(tv);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

        if (getIntent().getBooleanExtra(KEY_DISABLE_INPUT_SINK, false)) {
            setActivityRecordInputSinkEnabled(false);
        }
    }
}
