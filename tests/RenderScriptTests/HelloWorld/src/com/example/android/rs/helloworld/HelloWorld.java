/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.example.android.rs.helloworld;

import android.app.Activity;
import android.os.Bundle;

// Renderscript activity
public class HelloWorld extends Activity {

    // Custom view to use with RenderScript
    private HelloWorldView mView;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Create our view and set it as the content of our Activity
        mView = new HelloWorldView(this);
        setContentView(mView);
    }

    @Override
    protected void onResume() {
        // Ideally an app should implement onResume() and onPause()
        // to take appropriate action when the activity loses focus
        super.onResume();
        mView.resume();
    }

    @Override
    protected void onPause() {
        // Ideally an app should implement onResume() and onPause()
        // to take appropriate action when the activity loses focus
        super.onPause();
        mView.pause();
    }

}

