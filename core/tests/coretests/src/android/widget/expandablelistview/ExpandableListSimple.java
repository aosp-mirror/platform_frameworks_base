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

package android.widget.expandablelistview;

import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.widget.BaseExpandableListAdapter;

import android.util.ExpandableListScenario;

public class ExpandableListSimple extends ExpandableListScenario {
    private static final int[] NUM_CHILDREN = {4, 3, 2, 1, 0};

    @Override
    protected void init(ExpandableParams params) {
        params.setNumChildren(NUM_CHILDREN)
                .setItemScreenSizeFactor(0.14);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        menu.add("Add item").setOnMenuItemClickListener(new OnMenuItemClickListener() {
            public boolean onMenuItemClick(MenuItem item) {
                mGroups.add(0, new MyGroup(2));
                ((BaseExpandableListAdapter) mAdapter).notifyDataSetChanged();
                return true;
            }
        });
        
        return true;
    }
    
}
