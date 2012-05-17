/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.example.android.rs.fountainfbo;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

public class FountainFbo extends Activity {
    private static final String LOG_TAG = "libRS_jni";
    private static final boolean DEBUG  = false;
    private static final boolean LOG_ENABLED = false;

    private FountainFboView mView;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        /* Create our Preview view and set it as the content of our Activity */
        mView = new FountainFboView(this);
        setContentView(mView);
    }

    @Override
    protected void onResume() {
        Log.e("rs", "onResume");

        /* Ideally a game should implement onResume() and onPause()
         to take appropriate action when the activity loses focus */
        super.onResume();
        mView.resume();
    }

    @Override
    protected void onPause() {
        Log.e("rs", "onPause");

        /* Ideally a game should implement onResume() and onPause()
        to take appropriate action when the activity loses focus */
        super.onPause();
        mView.pause();
    }

    static void log(String message) {
        if (LOG_ENABLED) {
            Log.v(LOG_TAG, message);
        }
    }
}

