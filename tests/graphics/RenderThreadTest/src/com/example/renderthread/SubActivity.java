/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.example.renderthread;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

public class SubActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        long before = SystemClock.currentThreadTimeMillis();
        setContentView(R.layout.activity_sub);
        getActionBar().setTitle("SubActivity");
        // Simulate being a real app!
        while (SystemClock.currentThreadTimeMillis() - before < 100) {
            View v = new View(this, null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ViewGroup container = findViewById(R.id.my_container);
        int dx = getWindowManager().getDefaultDisplay().getWidth();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            int dir = child.getId() == R.id.from_left ? 1 : -1;
            child.setTranslationX(dx * dir);
            child.animate().translationX(0).setDuration(MainActivity.DURATION);
        }
        View bg = findViewById(R.id.bg_container);
        bg.setAlpha(0f);
        bg.animate().alpha(1f).setDuration(MainActivity.DURATION);
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        overridePendingTransition(0, 0);
    }
}
