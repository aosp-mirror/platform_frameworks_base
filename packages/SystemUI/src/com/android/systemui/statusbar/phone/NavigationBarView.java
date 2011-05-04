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

package com.android.systemui.statusbar.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.content.res.Configuration;

import com.android.systemui.R;

public class NavigationBarView extends LinearLayout {
    final Display mDisplay;
    View[] mRotatedViews = new View[4];

    public NavigationBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDisplay = ((WindowManager)context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
    }

    public void onFinishInflate() {
        mRotatedViews[Surface.ROTATION_0] = 
        mRotatedViews[Surface.ROTATION_180] = findViewById(R.id.rot0);

        mRotatedViews[Surface.ROTATION_90] = findViewById(R.id.rot90);
        
        mRotatedViews[Surface.ROTATION_270] = findViewById(R.id.rot270);
    }

    public void reorient() {
        final int rot = mDisplay.getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mRotatedViews[rot].setVisibility(View.VISIBLE);

        android.util.Log.d("NavigationBarView", "reorient(): rot=" + mDisplay.getRotation());
    }
}
