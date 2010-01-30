/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.app.activity;

import android.app.TabActivity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TabHost;

public class LaunchpadTabActivity extends TabActivity {
    public LaunchpadTabActivity() {
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        Intent tabIntent = new Intent(getIntent());
        tabIntent.setComponent((ComponentName)tabIntent.getParcelableExtra("tab"));
        
        TabHost th = getTabHost();
        TabHost.TabSpec ts = th.newTabSpec("1");
        ts.setIndicator("One");
        ts.setContent(tabIntent);
        th.addTab(ts);
    }
}

