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
 * limitations under the License
 */

package com.google.android.example.locktasktests;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;

public class LaunchActivity extends Activity {

    EditText mEditText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);
        setBackgroundOnLockTaskMode();
    }

    @Override
    public void onResume() {
        super.onResume();
        setBackgroundOnLockTaskMode();
    }

    public void onTryLock(View view) {
        startLockTask();
        setBackgroundOnLockTaskMode();
    }

    public void onTryUnlock(View view) {
        stopLockTask();
        setBackgroundOnLockTaskMode();
    }

    public void onLaunchMain(View view) {
        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
    }

    private void setBackgroundOnLockTaskMode() {
        ActivityManager activityManager =
                (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        final int color =
                activityManager.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE ?
                        0xFFFFC0C0 : 0xFFFFFFFF;
        findViewById(R.id.root_launch).setBackgroundColor(color);
    }
}
