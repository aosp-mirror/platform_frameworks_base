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
 * List that has different view types
 */
public class ListHeterogeneous extends ListScenario {

    @Override
    protected void init(Params params) {
        params.setNumItems(50)
                .setItemScreenSizeFactor(1.0 / 8)
                .setItemsFocusable(true)
                .setHeaderViewCount(3)
                .setFooterViewCount(2);
    }

    @Override
    protected View createView(int position, ViewGroup parent, int desiredHeight) {
        switch (position % 3) {
        case 0:
            return ListItemFactory.text(
                    position, parent.getContext(), getValueAtPosition(position), desiredHeight);
        case 1:
            return ListItemFactory.button(
                    position, parent.getContext(), getValueAtPosition(position), desiredHeight);
        case 2:
            return ListItemFactory.doubleText(
                    position, parent.getContext(), getValueAtPosition(position), desiredHeight);
        }
        
        return null;
    }

    @Override
    public View convertView(int position, View convertView, ViewGroup parent) {
        switch (position % 3) {
        case 0:
            return ListItemFactory.convertText(convertView, getValueAtPosition(position), position);
        case 1:
            return ListItemFactory.convertButton(convertView, getValueAtPosition(position),
                    position);
        case 2:
            return ListItemFactory.convertDoubleText(convertView, getValueAtPosition(position),
                    position);
        }

        return null;
    }
    
    @Override
    public int getItemViewType(int position) {
        return position % 3;
    }

    @Override
    public int getViewTypeCount() {
        return 3;
    }
    
    
}
