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

package com.android.perftest;

import android.renderscript.RSSurfaceView;
import android.renderscript.RenderScript;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings.System;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.ListView;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.Toast;

import java.lang.Runtime;

public class RsBench extends Activity {
    private final String TAG = "RsBench";
    public RsBenchView mView;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        int iterations = 0;
        Intent intent = getIntent();
        Uri uri = intent.getData();
        if (uri != null) {
            // when lauched from instrumentation
            String scheme = uri.getScheme();
            if ("iterations".equals(scheme)) {
                iterations = Integer.parseInt(uri.getSchemeSpecificPart());
            }
        }
        // Create our Preview view and set it as the content of our
        // Activity
        mView = new RsBenchView(this);
        setContentView(mView);
        mView.setLoops(iterations);
    }

    @Override
    protected void onResume() {
        // Ideally a game should implement onResume() and onPause()
        // to take appropriate action when the activity loses focus
        super.onResume();
        mView.resume();
    }

    @Override
    protected void onPause() {
        // Ideally a game should implement onResume() and onPause()
        // to take appropriate action when the activity loses focus
        super.onPause();
        mView.pause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.loader_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.benchmark_mode:
                mView.setBenchmarkMode();
                return true;
            case R.id.debug_mode:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("Pick a Test");
                builder.setItems(mView.getTestNames(),
                                 new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int item) {
                        Toast.makeText(getApplicationContext(),
                                       "Switching to: " + mView.getTestNames()[item],
                                       Toast.LENGTH_SHORT).show();
                        mView.setDebugMode(item);
                    }
                });
                builder.show();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }
}
