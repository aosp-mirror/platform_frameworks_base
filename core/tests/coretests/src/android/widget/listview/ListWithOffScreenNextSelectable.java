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
 * Pressing down from position 0 requires looking past positions 1, 2 and 3 to
 * an offscreen item to know that it is the next selectable.
 */
public class ListWithOffScreenNextSelectable extends ListScenario {


    protected void init(Params params) {
        params.setItemsFocusable(false)
                .setNumItems(5)
                .setItemScreenSizeFactor(0.25)
                .setPositionUnselectable(1)
                .setPositionUnselectable(2)
                .setPositionUnselectable(3);
    }

}
