/**
 * Copyright (c) 2018 The Android Open Source Project
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

package com.google.android.test.activityview;

import static android.view.MotionEvent.ACTION_CANCEL;
import static android.view.MotionEvent.ACTION_DOWN;
import static android.view.MotionEvent.ACTION_MOVE;
import static android.view.MotionEvent.ACTION_UP;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

public class ActivityViewTestActivity extends Activity {
    private static final String TAG = "ActivityViewTestActivity";

    private View mRoot;
    private TextView mTextView;
    private TextView mWidthTextView;
    private TextView mHeightTextView;
    private TextView mTouchStateTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_test_activity);
        mRoot = findViewById(R.id.test_activity_root);
        mTextView = findViewById(R.id.test_activity_title);
        mWidthTextView = findViewById(R.id.test_activity_width_text);
        mHeightTextView = findViewById(R.id.test_activity_height_text);
        mTouchStateTextView = findViewById(R.id.test_activity_touch_state);
        ViewTreeObserver viewTreeObserver = mRoot.getViewTreeObserver();
        if (viewTreeObserver.isAlive()) {
            viewTreeObserver.addOnGlobalLayoutListener(this::updateDimensionTexts);
        }
        updateStateText("CREATED");
    }

    @Override
    protected void onStart() {
        super.onStart();
        updateStateText("STARTED");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStateText("RESUMED");
    }

    @Override
    protected void onPause() {
        super.onPause();
        updateStateText("PAUSED");
    }

    @Override
    protected void onStop() {
        super.onStop();
        updateStateText("STOPPED");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateDimensionTexts();
    }

    private void updateStateText(String state) {
        Log.d(TAG, state);
        mTextView.setText(state);
    }

    private void updateDimensionTexts() {
        mWidthTextView.setText("" + mRoot.getWidth());
        mHeightTextView.setText("" + mRoot.getHeight());
    }

    private void updateTouchState(MotionEvent event) {
        switch (event.getAction()) {
            case ACTION_DOWN:
            case ACTION_MOVE:
                mTouchStateTextView.setText("[" + event.getX() + "," + event.getY() + "]");
                break;
            case ACTION_UP:
            case ACTION_CANCEL:
                mTouchStateTextView.setText("");
                break;
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        updateTouchState(event);
        return super.dispatchTouchEvent(event);
    }
}
