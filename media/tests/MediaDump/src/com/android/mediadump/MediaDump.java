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

package com.android.mediadump;

import android.app.TabActivity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.TabHost;

/**
 * A media tool to play a video and dump the screen display
 * into raw RGB files. Check VideoDumpView for tech details.
 */
public class MediaDump extends TabActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // TODO: Read/Write the settings.

        setContentView(R.layout.main);

        TabHost tab = getTabHost();

        // Setup video dumping tab
        TabHost.TabSpec videoDumpTab = tab.newTabSpec("VideoDump");
        videoDumpTab.setIndicator("VideoDump");

        Intent videoDumpIntent = new Intent(this, VideoDumpActivity.class);
        videoDumpTab.setContent(videoDumpIntent);

        tab.addTab(videoDumpTab);

        // Setup rgb player tab
        TabHost.TabSpec rgbPlayerTab = tab.newTabSpec("RgbPlayer");
        rgbPlayerTab.setIndicator("RgbPlayer");

        Intent rgbPlayerIntent = new Intent(this, RgbPlayerActivity.class);
        rgbPlayerTab.setContent(rgbPlayerIntent);

        tab.addTab(rgbPlayerTab);
    }
}

