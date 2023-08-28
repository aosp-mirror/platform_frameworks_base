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

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.slider.RangeSlider;
import com.google.android.material.slider.RangeSlider.OnChangeListener;

public class TouchLatencyActivity extends AppCompatActivity {
    private static final int REFRESH_RATE_SLIDER_MIN = 20;
    private static final int REFRESH_RATE_SLIDER_STEP = 1;

    private Menu mMenu;
    private Mode[] mDisplayModes;
    private int mCurrentModeIndex;
    private float mSliderPreferredRefreshRate;
    private DisplayManager mDisplayManager;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int i) {
            updateOptionsMenu();
        }

        @Override
        public void onDisplayRemoved(int i) {
            updateOptionsMenu();
        }

        @Override
        public void onDisplayChanged(int i) {
            updateOptionsMenu();
        }
    };

    private final RangeSlider.OnChangeListener mRefreshRateSliderListener = new OnChangeListener() {
        @Override
        public void onValueChange(@NonNull RangeSlider slider, float value, boolean fromUser) {
            if (value == mSliderPreferredRefreshRate) return;

            mSliderPreferredRefreshRate = value;
            WindowManager.LayoutParams w = getWindow().getAttributes();
            w.preferredRefreshRate = mSliderPreferredRefreshRate;
            getWindow().setAttributes(w);
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

    public void updateOptionsMenu() {
        if (mDisplayModes.length > 1) {
            MenuItem menuItem = mMenu.findItem(R.id.display_mode);
            Mode currentMode = getWindowManager().getDefaultDisplay().getMode();
            updateDisplayMode(menuItem, currentMode);
        }
        updateRefreshRateMenu(mMenu.findItem(R.id.frame_rate));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Trace.beginSection("TouchLatencyActivity onCreateOptionsMenu");
        mMenu = menu;
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_touch_latency, mMenu);
        updateOptionsMenu();
        Trace.endSection();
        return true;
    }

    private void updateDisplayMode(MenuItem menuItem, Mode displayMode) {
        int fps = (int) displayMode.getRefreshRate();
        menuItem.setTitle(fps + "hz");
        menuItem.setVisible(true);
    }

    private float getHighestRefreshRate() {
        float maxRefreshRate = 0;
        for (Display.Mode mode : getDisplay().getSupportedModes()) {
            if (sameSizeMode(mode) && mode.getRefreshRate() > maxRefreshRate) {
                maxRefreshRate = mode.getRefreshRate();
            }
        }
        return maxRefreshRate;
    }

    private void updateRefreshRateMenu(MenuItem item) {
        item.setActionView(R.layout.refresh_rate_layout);
        RangeSlider slider = item.getActionView().findViewById(R.id.slider_from_layout);
        slider.addOnChangeListener(mRefreshRateSliderListener);

        float highestRefreshRate = getHighestRefreshRate();
        slider.setValueFrom(REFRESH_RATE_SLIDER_MIN);
        slider.setValueTo(highestRefreshRate);
        slider.setStepSize(REFRESH_RATE_SLIDER_STEP);
        if (mSliderPreferredRefreshRate < REFRESH_RATE_SLIDER_MIN
                || mSliderPreferredRefreshRate > highestRefreshRate) {
            mSliderPreferredRefreshRate = highestRefreshRate;
        }
        slider.setValues(mSliderPreferredRefreshRate);
    }

    private void updateMultiDisplayMenu(MenuItem item) {
        item.setVisible(mDisplayManager.getDisplays().length > 1);
    }

    private void configureDisplayListener() {
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener, new Handler());
    }

    private boolean sameSizeMode(Display.Mode mode) {
        Mode currentMode = mDisplayModes[mCurrentModeIndex];
        return currentMode.getPhysicalHeight() == mode.getPhysicalHeight()
            && currentMode.getPhysicalWidth() == mode.getPhysicalWidth();
    }

    public void changeDisplayMode(MenuItem item) {
        Window w = getWindow();
        WindowManager.LayoutParams params = w.getAttributes();

        int modeIndex = (mCurrentModeIndex + 1) % mDisplayModes.length;
        while (modeIndex != mCurrentModeIndex) {
            // skip modes with different resolutions
            if (sameSizeMode(mDisplayModes[modeIndex])) {
                break;
            }
            modeIndex = (modeIndex + 1) % mDisplayModes.length;
        }

        params.preferredDisplayModeId = mDisplayModes[modeIndex].getModeId();
        w.setAttributes(params);

        updateDisplayMode(item, mDisplayModes[modeIndex]);
        mCurrentModeIndex = modeIndex;
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
