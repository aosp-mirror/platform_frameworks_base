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

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import android.util.ListScenario;

/**
 * Each list item has two focusables that are close enough together that
 * it shouldn't require panning to move focus.
 */
public class ListOfTouchables extends ListScenario {


    @Override
    protected void init(Params params) {
        params.setItemsFocusable(true)
                .setItemScreenSizeFactor(0.2)
                .setNumItems(100);
    }


    @Override
    protected View createView(int position, ViewGroup parent, int desiredHeight) {
        Button b = new Button(this);
        b.setText("Position " + position);
        b.setId(position);
        return b;
    }
}
