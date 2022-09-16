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
 * limitations under the License.
 */

package com.prefabulated.touchlatency;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Trace;
import android.view.Display;
import android.view.Display.Mode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;

public class TouchLatencyActivity extends Activity {
    private Mode mDisplayModes[];
    private int mCurrentModeIndex;
    private DisplayManager mDisplayManager;
    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int i) {
            invalidateOptionsMenu();
        }

        @Override
        public void onDisplayRemoved(int i) {
            invalidateOptionsMenu();
        }

        @Override
        public void onDisplayChanged(int i) {
            invalidateOptionsMenu();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Trace.beginSection("TouchLatencyActivity onCreate");
        setContentView(R.layout.activity_touch_latency);
        mTouchView = findViewById(R.id.canvasView);

        configureDisplayListener();
        WindowManager wm = getWindowManager();
        Display display = wm.getDefaultDisplay();
        mDisplayModes = display.getSupportedModes();
        Mode currentMode = getWindowManager().getDefaultDisplay().getMode();

        for (int i = 0; i < mDisplayModes.length; i++) {
            if (currentMode.getModeId() == mDisplayModes[i].getModeId()) {
                mCurrentModeIndex = i;
                break;
            }
        }
        Trace.endSection();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Trace.beginSection("TouchLatencyActivity onCreateOptionsMenu");
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_touch_latency, menu);
        if (mDisplayModes.length > 1) {
            MenuItem menuItem = menu.findItem(R.id.display_mode);
            Mode currentMode = getWindowManager().getDefaultDisplay().getMode();
            updateDisplayMode(menuItem, currentMode);
        }
        updateMultiDisplayMenu(menu.findItem(R.id.multi_display));
        Trace.endSection();
        return true;
    }

    private void updateDisplayMode(MenuItem menuItem, Mode displayMode) {
        int fps = (int) displayMode.getRefreshRate();
        menuItem.setTitle(fps + "hz");
        menuItem.setVisible(true);
    }

    private void updateMultiDisplayMenu(MenuItem item) {
        item.setVisible(mDisplayManager.getDisplays().length > 1);
    }

    private void configureDisplayListener() {
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, new Handler());
    }

    public void changeDisplayMode(MenuItem item) {
        Window w = getWindow();
        WindowManager.LayoutParams params = w.getAttributes();

        int modeIndex = (mCurrentModeIndex + 1) % mDisplayModes.length;
        while (modeIndex != mCurrentModeIndex) {
            // skip modes with different resolutions
            Mode currentMode = mDisplayModes[mCurrentModeIndex];
            Mode nextMode = mDisplayModes[modeIndex];
            if (currentMode.getPhysicalHeight() == nextMode.getPhysicalHeight()
                    && currentMode.getPhysicalWidth() == nextMode.getPhysicalWidth()) {
                break;
            }
            modeIndex = (modeIndex + 1) % mDisplayModes.length;
        }

        params.preferredDisplayModeId = mDisplayModes[modeIndex].getModeId();
        w.setAttributes(params);

        updateDisplayMode(item, mDisplayModes[modeIndex]);
        mCurrentModeIndex = modeIndex;
    }

    private void changeMultipleDisplays() {
        Intent intent = new Intent(this, TouchLatencyActivityPresentation.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options = ActivityOptions.makeBasic();
        for (int i = 1; i < mDisplayManager.getDisplays().length; ++i) {
            // We assume the first display is already displaying the TouchLatencyActivity
            int displayId = mDisplayManager.getDisplays()[i].getDisplayId();
            options.setLaunchDisplayId(displayId);
            intent.putExtra(TouchLatencyActivityPresentation.DISPLAY_ID, displayId);
            startActivity(intent, options.toBundle());
        }
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Trace.beginSection("TouchLatencyActivity onOptionsItemSelected");
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        switch (id) {
            case R.id.action_settings: {
                mTouchView.changeMode(item);
                break;
            }
            case R.id.display_mode: {
                changeDisplayMode(item);
                break;
            }
            case R.id.multi_display: {
                changeMultipleDisplays();
                break;
            }
        }

        Trace.endSection();
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
    }

    private TouchLatencyView mTouchView;
}
