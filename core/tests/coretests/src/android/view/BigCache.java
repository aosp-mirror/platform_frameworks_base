/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.view;

import com.android.frameworks.coretests.R;

import android.os.Bundle;
import android.app.Activity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.view.ViewGroup;
import android.view.View;
import android.view.Display;
import android.view.ViewConfiguration;

/**
 * This activity contains two Views, one as big as the screen, one much larger. The large one
 * should not be able to activate its drawing cache.
 */
public class BigCache extends Activity {
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        final LinearLayout testBed = new LinearLayout(this);
        testBed.setOrientation(LinearLayout.VERTICAL);
        testBed.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final int cacheSize = ViewConfiguration.getMaximumDrawingCacheSize();
        final Display display = getWindowManager().getDefaultDisplay();
        final int screenWidth = display.getWidth();
        final int screenHeight = display.getHeight();

        final View tiny = new View(this);
        tiny.setId(R.id.a);
        tiny.setBackgroundColor(0xFFFF0000);
        tiny.setLayoutParams(new LinearLayout.LayoutParams(screenWidth, screenHeight));

        final View large = new View(this);
        large.setId(R.id.b);
        large.setBackgroundColor(0xFF00FF00);
        // Compute the height of the view assuming a cache size based on ARGB8888
        final int height = 2 * (cacheSize / 2) / screenWidth;
        large.setLayoutParams(new LinearLayout.LayoutParams(screenWidth, height));

        final ScrollView scroller = new ScrollView(this);
        scroller.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        testBed.addView(tiny);
        testBed.addView(large);
        scroller.addView(testBed);

        setContentView(scroller);
    }
}
