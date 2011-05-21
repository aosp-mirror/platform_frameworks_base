/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.bidi;

import android.app.TabActivity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TabHost;

public class BiDiTestActivity extends TabActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.main);

        TabHost tabHost = getTabHost();
        TabHost.TabSpec spec;
        Intent intent;

        // Create an Intent to launch an Activity for the tab (to be reused)
        intent = new Intent().setClass(this, BiDiTestBasicActivity.class);

        // Initialize a TabSpec for each tab and add it to the TabHost
        spec = tabHost.newTabSpec("basic").setIndicator("Basic").setContent(intent);
        tabHost.addTab(spec);

        // Do the same for the other tabs
        intent = new Intent().setClass(this, BiDiTestCanvasActivity.class);
        spec = tabHost.newTabSpec("canvas").setIndicator("Canvas").setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestLinearLayoutLtrActivity.class);
        spec = tabHost.newTabSpec("layout-ltr").setIndicator("LinearLayout LTR").setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestLinearLayoutRtlActivity.class);
        spec = tabHost.newTabSpec("layout-rtl").setIndicator("LinearLayout RTL").setContent(intent);
        tabHost.addTab(spec);

        tabHost.setCurrentTab(0);
    }
}