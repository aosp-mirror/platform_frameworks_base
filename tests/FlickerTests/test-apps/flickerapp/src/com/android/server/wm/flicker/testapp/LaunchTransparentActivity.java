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

package com.android.server.wm.flicker.testapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class LaunchTransparentActivity extends Activity {

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.activity_transparent_launch);
        findViewById(R.id.button_launch_transparent)
                .setOnClickListener(v -> launchTransparentActivity());
    }

    private void launchTransparentActivity() {
        startActivity(new Intent(this, TransparentActivity.class));
    }
}
