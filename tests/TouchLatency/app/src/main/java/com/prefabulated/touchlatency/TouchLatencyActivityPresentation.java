/*
 * Copyright 2022 The Android Open Source Project
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
import android.os.Bundle;
import android.os.Trace;
import android.view.Display;
import android.view.Display.Mode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;

public class TouchLatencyActivityPresentation extends Activity {
    public static final String DISPLAY_ID = "DISPLAY_ID";
    private Mode[] mDisplayModes;
    private int mCurrentModeIndex;
    private int mDisplayId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getIntent().hasExtra(DISPLAY_ID)) {
            mDisplayId = (int) getIntent().getExtras().get(DISPLAY_ID);
        }
        Trace.beginSection(
                "TouchLatencyActivityPresentation::DisplayId::" + mDisplayId + " onCreate");
        setContentView(R.layout.activity_touch_latency);

        mTouchView = findViewById(R.id.canvasView);

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
    protected void onResume() {
        super.onResume();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Trace.beginSection(
                "TouchLatencyActivityPresentation::DisplayId:: "
                        + mDisplayId + "  onCreateOptionsMenu");
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_touch_latency, menu);
        if (mDisplayModes.length > 1) {
            MenuItem menuItem = menu.findItem(R.id.display_mode);
            Mode currentMode = getWindowManager().getDefaultDisplay().getMode();
            updateDisplayMode(menuItem, currentMode);
        }
        Trace.endSection();
        return true;
    }

    private void updateDisplayMode(MenuItem menuItem, Mode displayMode) {
        int fps = (int) displayMode.getRefreshRate();
        menuItem.setTitle(fps + "hz");
        menuItem.setVisible(true);
    }

    public void changeDisplayMode(MenuItem item) {
        Window w = getWindow();
        WindowManager.LayoutParams params = w.getAttributes();

        int modeIndex = (mCurrentModeIndex + 1) % mDisplayModes.length;
        params.preferredDisplayModeId = mDisplayModes[modeIndex].getModeId();
        w.setAttributes(params);

        updateDisplayMode(item, mDisplayModes[modeIndex]);
        mCurrentModeIndex = modeIndex;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Trace.beginSection(
                "TouchLatencyActivityPresentation::DisplayId::"
                        + mDisplayId + "  onOptionsItemSelected");
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
        }

        Trace.endSection();
        return super.onOptionsItemSelected(item);
    }

    private TouchLatencyView mTouchView;
}
