/**
 * Copyright (c) 2018 The Android Open Source Project
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

package com.google.android.test.activityview;

import android.app.Activity;
import android.app.ActivityView;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

public class ActivityViewScrollActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_scroll_activity);

        final ActivityView activityView = findViewById(R.id.activity_view);
        final Button launchButton = findViewById(R.id.activity_launch_button);
        launchButton.setOnClickListener(v -> {
            final Intent intent = new Intent(this, ActivityViewTestActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            activityView.startActivity(intent);
        });
        findViewById(R.id.activity_view_host_scroll_view).setOnScrollChangeListener(
                (View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY)
                        -> activityView.onLocationChanged());
    }
}
