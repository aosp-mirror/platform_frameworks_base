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

import android.util.ListItemFactory;
import android.util.ListScenario;
import android.view.View;
import android.view.ViewGroup;

/**
 * A list where the items may befocusable, but the second item isn't actually focusabe.
 */
public class ListItemFocusableAboveUnfocusable extends ListScenario {


    protected void init(Params params) {
        params.setNumItems(2)
                .setItemsFocusable(true)
                .setItemScreenSizeFactor(0.2)
                .setMustFillScreen(false);
    }

    @Override
    protected View createView(int position, ViewGroup parent, int desiredHeight) {
        if (position == 0) {
            return ListItemFactory.button(
                    position, parent.getContext(), getValueAtPosition(position), desiredHeight);
        } else {
            return super.createView(position, parent, desiredHeight);
        }
    }
}
