/**
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package android.view.menu;

import android.os.Bundle;
import android.view.Menu;
import android.widget.Button;

public class MenuLayout extends MenuScenario {
    private static final String LONG_TITLE = "Really really really really really really really really really really long title";
    private static final String SHORT_TITLE = "Item";

    private Button mButton;
    
    @Override
    protected void onInitParams(Params params) {
        super.onInitParams(params);
        params
            .setNumItems(2)
            .setItemTitle(0, LONG_TITLE)
            .setItemTitle(1, LONG_TITLE);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {

        /*
         * This activity is meant to try a bunch of different menu layouts. So,
         * we recreate the menu every time it is prepared.
         */ 
        menu.clear();
        onCreateOptionsMenu(menu);
        
        return true;
    }

    public Button getButton() {
        return mButton;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mButton  = new Button(this);
        setContentView(mButton);
    }
    
}
