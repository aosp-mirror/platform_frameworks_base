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
        spec = tabHost.newTabSpec("basic").setIndicator("Basic").
            setContent(intent);
        tabHost.addTab(spec);

        // Do the same for the other tabs
        intent = new Intent().setClass(this, BiDiTestCanvasActivity.class);
        spec = tabHost.newTabSpec("canvas").setIndicator("Canvas").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestLinearLayoutLtrActivity.class);
        spec = tabHost.newTabSpec("linear-layout-ltr").setIndicator("Linear LTR").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestLinearLayoutRtlActivity.class);
        spec = tabHost.newTabSpec("linear-layout-rtl").setIndicator("Linear RTL").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestLinearLayoutLocaleActivity.class);
        spec = tabHost.newTabSpec("linear-layout-locale").setIndicator("Linear LOC").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestFrameLayoutLtrActivity.class);
        spec = tabHost.newTabSpec("frame-layout-ltr").setIndicator("Frame LTR").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestFrameLayoutRtlActivity.class);
        spec = tabHost.newTabSpec("frame-layout-rtl").setIndicator("Frame RTL").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestFrameLayoutLocaleActivity.class);
        spec = tabHost.newTabSpec("frame-layout-locale").setIndicator("Frame LOC").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestRelativeLayoutLtrActivity.class);
        spec = tabHost.newTabSpec("relative-layout-ltr").setIndicator("Relative LTR").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestRelativeLayoutRtlActivity.class);
        spec = tabHost.newTabSpec("relative-layout-rtl").setIndicator("Relative RTL").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestRelativeLayoutLtrActivity2.class);
        spec = tabHost.newTabSpec("relative-layout-ltr-2").setIndicator("Relative2 LTR").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestRelativeLayoutRtlActivity2.class);
        spec = tabHost.newTabSpec("relative-layout-rtl-2").setIndicator("Relative2 RTL").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestRelativeLayoutLocaleActivity2.class);
        spec = tabHost.newTabSpec("relative-layout-locale-2").setIndicator("Relative2 LOC").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestTableLayoutLtrActivity.class);
        spec = tabHost.newTabSpec("table-layout-ltr").setIndicator("Table LTR").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestTableLayoutRtlActivity.class);
        spec = tabHost.newTabSpec("table-layout-rtl").setIndicator("Table RTL").
            setContent(intent);
        tabHost.addTab(spec);

        intent = new Intent().setClass(this, BiDiTestTableLayoutLocaleActivity.class);
        spec = tabHost.newTabSpec("table-layout-locale").setIndicator("Table LOC").
            setContent(intent);
        tabHost.addTab(spec);

        tabHost.setCurrentTab(0);
    }
}