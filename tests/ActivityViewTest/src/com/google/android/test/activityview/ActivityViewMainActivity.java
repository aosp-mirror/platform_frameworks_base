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
import android.content.Intent;
import android.os.Bundle;

public class ActivityViewMainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_main_activity);

        findViewById(R.id.activity_view_button).setOnClickListener(
                v -> startActivity(new Intent(this, ActivityViewActivity.class)));

        findViewById(R.id.scroll_activity_view_button).setOnClickListener(
                v -> startActivity(new Intent(this, ActivityViewScrollActivity.class)));

        findViewById(R.id.resize_activity_view_button).setOnClickListener(
                v -> startActivity(new Intent(this, ActivityViewResizeActivity.class)));

        findViewById(R.id.visibility_activity_view_button).setOnClickListener(
                v -> startActivity(new Intent(this, ActivityViewVisibilityActivity.class)));
    }
}
