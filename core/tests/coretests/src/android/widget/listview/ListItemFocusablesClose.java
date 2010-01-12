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

import android.util.ListScenario;
import android.util.ListItemFactory;
import android.view.View;
import android.view.ViewGroup;

/**
 * Each list item has two focusables that are close enough together that
 * it shouldn't require panning to move focus.
 */
public class ListItemFocusablesClose extends ListScenario {


    /**
     * Get the child of a list item.
     * @param listIndex The index of the currently visible items
     * @param index The index of the child.
     */
    public View getChildOfItem(int listIndex, int index) {
        return ((ViewGroup) getListView().getChildAt(listIndex)).getChildAt(index);

    }
        
    @Override
    protected void init(Params params) {
        params.setItemsFocusable(true)
                .setNumItems(2)
                .setItemScreenSizeFactor(0.55);
    }


    @Override
    protected View createView(int position, ViewGroup parent, int desiredHeight) {
        return ListItemFactory.twoButtonsSeparatedByFiller(
                position, parent.getContext(), desiredHeight);
    }
}
