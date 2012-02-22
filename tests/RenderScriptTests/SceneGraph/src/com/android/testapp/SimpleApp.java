/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.testapp;

import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScript;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.Window;
import android.net.Uri;

import java.lang.Runtime;

public class SimpleApp extends Activity {

    private SimpleAppView mView;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Create our Preview view and set it as the content of our
        // Activity
        mView = new SimpleAppView(this);
        setContentView(mView);
    }
}

