/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.stackdivider;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * Translucent activity that gets started on top of a task in multi-window to inform the user that
 * we forced the activity below to be resizable.
 */
public class ForcedResizableInfoActivity extends Activity implements OnTouchListener {

    private static final long DISMISS_DELAY = 2500;

    private final Runnable mFinishRunnable = new Runnable() {
        @Override
        public void run() {
            finish();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.forced_resizable_activity);
        TextView tv = (TextView) findViewById(com.android.internal.R.id.message);
        tv.setText(R.string.dock_forced_resizable);
        getWindow().setTitle(getString(R.string.dock_forced_resizable));
        getWindow().getDecorView().setOnTouchListener(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        getWindow().getDecorView().postDelayed(mFinishRunnable, DISMISS_DELAY);
    }

    @Override
    protected void onStop() {
        super.onStop();
        finish();
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        finish();
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        finish();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.forced_resizable_exit);
    }

    @Override
    public void setTaskDescription(ActivityManager.TaskDescription taskDescription) {
        // Do nothing
    }
}
