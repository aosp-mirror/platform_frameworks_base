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

package android.widget.listview;

import android.widget.ListScenario;

/**
 * Two short items separated by one that is taller than the screen.
 */
public class ListOfShortTallShort extends ListScenario {


    protected void init(Params params) {
        params.setItemScreenSizeFactor(1.0 / 8)
                .setNumItems(3)
                .setPositionScreenSizeFactorOverride(1, 1.2);
    }
}
