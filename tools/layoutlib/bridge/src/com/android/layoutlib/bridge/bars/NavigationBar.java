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

package com.android.layoutlib.bridge.bars;

import com.android.resources.Density;

import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;

public class NavigationBar extends CustomBar {

    public NavigationBar(Context context, Density density, int orientation) throws XmlPullParserException {
        super(context, density, orientation, "/bars/navigation_bar.xml", "navigation_bar.xml");

        setBackgroundColor(0xFF000000);

        // Cannot access the inside items through id because no R.id values have been
        // created for them.
        // We do know the order though.
        // 0 is a spacer.
        int back = 1;
        int recent = 3;
        if (orientation == LinearLayout.VERTICAL) {
            back = 3;
            recent = 1;
        }

        loadIcon(back,   "ic_sysbar_back.png", density);
        loadIcon(2,      "ic_sysbar_home.png", density);
        loadIcon(recent, "ic_sysbar_recent.png", density);
    }

    @Override
    protected TextView getStyleableTextView() {
        return null;
    }
}
