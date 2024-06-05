/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.test.hwui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

public class ColoredShadowsActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.colored_shadows_activity);
        ViewGroup grid = findViewById(R.id.colored_grid);
        for (int i = 0; i < grid.getChildCount(); i++) {
            setShadowColors((ViewGroup) grid.getChildAt(i), i);
        }
    }

    private void setShadowColors(ViewGroup row, int rowIndex) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View view = row.getChildAt(i);
            //view.setBackground(new MyHackyBackground());
            view.setOutlineSpotShadowColor(shadowColorFor(view));
            view.setOutlineAmbientShadowColor(shadowColorFor(view));
            view.setElevation(6.0f * (rowIndex + 1));
        }
    }

    private int shadowColorFor(View view) {
        switch (view.getId()) {
            case R.id.grey: return 0xFF3C4043;
            case R.id.blue: return Color.BLUE;
            case R.id.red: return 0xFFEA4335;
            case R.id.yellow: return 0xFFFBBC04;
            case R.id.green: return 0xFF34A853;
            default: return 0xFF000000;
        }
    }

    private static class MyHackyBackground extends ColorDrawable {
        MyHackyBackground() {
            super(0);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getAlpha() {
            return 254;
        }
    }
}
