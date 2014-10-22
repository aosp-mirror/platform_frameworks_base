/*
 * Copyright 2014 The Android Open Source Project
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

package com.example.wallpapertest;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    WallpaperManager mWallpaperManager;
    WindowManager mWindowManager;

    TextView mDimenWidthView;
    TextView mDimenHeightView;

    TextView mWallOffXView;
    TextView mWallOffYView;

    TextView mPaddingLeftView;
    TextView mPaddingRightView;
    TextView mPaddingTopView;
    TextView mPaddingBottomView;

    TextView mDispOffXView;
    TextView mDispOffYView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mWallpaperManager = (WallpaperManager)getSystemService(Context.WALLPAPER_SERVICE);
        mWindowManager = (WindowManager)getSystemService(Context.WINDOW_SERVICE);

        mDimenWidthView = (TextView) findViewById(R.id.dimen_width);
        mDimenWidthView.addTextChangedListener(mTextWatcher);
        mDimenHeightView = (TextView) findViewById(R.id.dimen_height);
        mDimenHeightView.addTextChangedListener(mTextWatcher);

        mWallOffXView = (TextView) findViewById(R.id.walloff_x);
        mWallOffXView.addTextChangedListener(mTextWatcher);
        mWallOffYView = (TextView) findViewById(R.id.walloff_y);
        mWallOffYView.addTextChangedListener(mTextWatcher);

        mPaddingLeftView = (TextView) findViewById(R.id.padding_left);
        mPaddingLeftView.addTextChangedListener(mTextWatcher);
        mPaddingRightView = (TextView) findViewById(R.id.padding_right);
        mPaddingRightView.addTextChangedListener(mTextWatcher);
        mPaddingTopView = (TextView) findViewById(R.id.padding_top);
        mPaddingTopView.addTextChangedListener(mTextWatcher);
        mPaddingBottomView = (TextView) findViewById(R.id.padding_bottom);
        mPaddingBottomView.addTextChangedListener(mTextWatcher);

        mDispOffXView = (TextView) findViewById(R.id.dispoff_x);
        mDispOffXView.addTextChangedListener(mTextWatcher);
        mDispOffYView = (TextView) findViewById(R.id.dispoff_y);
        mDispOffYView.addTextChangedListener(mTextWatcher);

        updateDimens();
        updateWallOff();
        updatePadding();
        updateDispOff();
    }

    private int loadPropIntText(TextView view, int baseVal) {
        String str = view.getText().toString();
        if (str != null && !TextUtils.isEmpty(str)) {
            try {
                float fval = Float.parseFloat(str);
                return (int)(fval*baseVal);
            } catch (NumberFormatException e) {
                Log.i(TAG, "Bad number: " + str, e);
            }
        }
        return baseVal;
    }

    private float loadFloatText(TextView view) {
        String str = view.getText().toString();
        if (str != null && !TextUtils.isEmpty(str)) {
            try {
                return Float.parseFloat(str);
            } catch (NumberFormatException e) {
                Log.i(TAG, "Bad number: " + str, e);
            }
        }
        return 0;
    }

    private int loadIntText(TextView view) {
        String str = view.getText().toString();
        if (str != null && !TextUtils.isEmpty(str)) {
            try {
                return Integer.parseInt(str);
            } catch (NumberFormatException e) {
                Log.i(TAG, "Bad number: " + str, e);
            }
        }
        return 0;
    }

    public void updateDimens() {
        Point minDims = new Point();
        Point maxDims = new Point();
        mWindowManager.getDefaultDisplay().getCurrentSizeRange(minDims, maxDims);
        mWallpaperManager.suggestDesiredDimensions(
                loadPropIntText(mDimenWidthView, maxDims.x),
                loadPropIntText(mDimenHeightView, maxDims.y));
    }

    public void updateWallOff() {
        IBinder token = getWindow().getDecorView().getWindowToken();
        if (token != null) {
            mWallpaperManager.setWallpaperOffsets(token, loadFloatText(mWallOffXView),
                    loadFloatText(mWallOffYView));
        }
    }

    public void updatePadding() {
        Rect padding = new Rect();
        padding.left = loadIntText(mPaddingLeftView);
        padding.top = loadIntText(mPaddingTopView);
        padding.right = loadIntText(mPaddingRightView);
        padding.bottom = loadIntText(mPaddingBottomView);
        mWallpaperManager.setDisplayPadding(padding);
    }

    public void updateDispOff() {
        IBinder token = getWindow().getDecorView().getWindowToken();
        if (token != null) {
            mWallpaperManager.setDisplayOffset(token, loadIntText(mDispOffXView),
                    loadIntText(mDispOffYView));
        }
    }

    final TextWatcher mTextWatcher = new TextWatcher() {
        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            updateDimens();
            updateWallOff();
            updatePadding();
            updateDispOff();
        }

        @Override public void afterTextChanged(Editable s) {
        }
    };
}
