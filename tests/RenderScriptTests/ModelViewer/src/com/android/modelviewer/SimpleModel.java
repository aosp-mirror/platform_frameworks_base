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

package com.android.modelviewer;

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

public class SimpleModel extends Activity {

    private SimpleModelView mView;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Create our Preview view and set it as the content of our
        // Activity
        mView = new SimpleModelView(this);
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
        case R.id.load_model:
            loadModel();
            return true;
        case R.id.display_options:
            return true;
        case R.id.sensor:
            mView.toggleSensor();
            return true;
        default:
            return super.onOptionsItemSelected(item);
        }
    }

    private static final int FIND_A3D_MODEL = 10;
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK) {
            if (requestCode == FIND_A3D_MODEL) {
                Uri selectedImageUri = data.getData();
                Log.e("Selected Path: ", selectedImageUri.getPath());
                mView.loadA3DFile(selectedImageUri.getPath());
            }
        }
    }

    public void loadModel() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_PICK);
        intent.setClassName("com.android.modelviewer",
                            "com.android.modelviewer.A3DSelector");
        startActivityForResult(intent, FIND_A3D_MODEL);
    }

}

