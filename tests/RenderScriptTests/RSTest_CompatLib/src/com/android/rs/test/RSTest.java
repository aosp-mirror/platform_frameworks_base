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

package com.android.rs.test_compat;

import android.support.v8.renderscript.RenderScript;

import android.app.ListActivity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings.System;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ArrayAdapter;

import java.lang.Runtime;

public class RSTest extends ListActivity {

    private static final String LOG_TAG = "RSTest_Compat";
    private static final boolean DEBUG  = false;
    private static final boolean LOG_ENABLED = false;

    private RenderScript mRS;
    private RSTestCore RSTC;

    String mTestNames[];

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mRS = RenderScript.create(this);

        RSTC = new RSTestCore(this);
        RSTC.init(mRS, getResources());




    }

    static void log(String message) {
        if (LOG_ENABLED) {
            Log.v(LOG_TAG, message);
        }
    }


}
