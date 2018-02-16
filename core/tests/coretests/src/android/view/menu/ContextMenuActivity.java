/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.view.menu;

import android.app.Activity;
import android.os.Bundle;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View;

import com.android.frameworks.coretests.R;

public class ContextMenuActivity extends Activity {

    static final String LABEL_ITEM = "Item";
    static final String LABEL_SUBMENU = "Submenu";
    static final String LABEL_SUBITEM = "Subitem";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.context_menu);
        registerForContextMenu(getTargetLtr());
        registerForContextMenu(getTargetRtl());
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        menu.add(LABEL_ITEM);
        menu.addSubMenu(LABEL_SUBMENU).add(LABEL_SUBITEM);
    }

    View getTargetLtr() {
        return findViewById(R.id.context_menu_target_ltr);
    }

    View getTargetRtl() {
        return findViewById(R.id.context_menu_target_rtl);
    }
}
