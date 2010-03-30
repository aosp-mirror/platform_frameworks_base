/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.test;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class TestActivity extends Activity {
    private final static String TAG = "TestActivity";
    TestView mView;
    boolean mToggle;
    int mCount;
    final static int PAUSE_DELAY = 100;
    Runnable mRunnable = new Runnable() {
        public void run() {
        if (mToggle) {
            Log.w(TAG, "****** step " + mCount + " resume");
            mCount++;
            mView.onResume();
        } else {
            Log.w(TAG, "step " + mCount + " pause");
            mView.onPause();
        }
        mToggle = ! mToggle;
        mView.postDelayed(mRunnable, PAUSE_DELAY);
        }
    };

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mView = new TestView(getApplication());
	    mView.setFocusableInTouchMode(true);
	    setContentView(mView);
        mView.postDelayed(mRunnable, PAUSE_DELAY);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mView.onResume();
    }
}
