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

package android.widget.gridview;

import android.widget.GridScenario;

/**
 * Basic stacking from bottom scenario, nothing fancy. Items do
 * fill the screen.
 */
public class GridSetSelectionStackFromBottomMany extends GridScenario {
    @Override
    protected void init(Params params) {
        params.setStackFromBottom(true)
                .setStartingSelectionPosition(-1)
                .setMustFillScreen(false)
                .setNumItems(150)
                .setNumColumns(4)
                .setItemScreenSizeFactor(0.12);
    }
}
