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

package com.android.fbotest;

import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScript;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings.System;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.MenuInflater;
import android.view.Window;
import android.widget.Button;
import android.widget.ListView;
import android.net.Uri;

import java.lang.Runtime;

public class FBOTest extends Activity {

    private FBOTestView mView;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Create our Preview view and set it as the content of our
        // Activity
        mView = new FBOTestView(this);
        setContentView(mView);
    }

    @Override
    protected void onResume() {
        // Ideally a game should implement onResume() and onPause()
        // to take appropriate action when the activity looses focus
        super.onResume();
        mView.resume();
    }

    @Override
    protected void onPause() {
        // Ideally a game should implement onResume() and onPause()
        // to take appropriate action when the activity looses focus
        super.onPause();
        mView.pause();
    }
}

