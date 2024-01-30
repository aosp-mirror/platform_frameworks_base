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
import android.widget.ListScenario;
import android.view.View;
import android.view.ViewGroup;

/**
 * A list where each item is tall with buttons that are farther apart than the screen
 * size.  We don't want to jump over content off screen to the next button, we need to
 * pan across the intermediate part.
 */
public class ListItemFocusablesFarApart extends ListScenario  {


    @Override
    protected void init(Params params) {
        params.setItemsFocusable(true)
                .setNumItems(2)
                .setItemScreenSizeFactor(2);
    }

    @Override
    protected View createView(int position, ViewGroup parent, int desiredHeight) {
        return ListItemFactory.twoButtonsSeparatedByFiller(
                position, parent.getContext(), desiredHeight);
    }
}
