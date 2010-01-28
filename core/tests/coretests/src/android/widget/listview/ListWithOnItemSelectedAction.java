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

import android.widget.TextView;
import android.util.ListScenario;

/**
 * The header text view echos the value of the selected item by using (indirectly)
 * the {@link android.widget.AdapterView.OnItemSelectedListener}.
 */
public class ListWithOnItemSelectedAction extends ListScenario {
    protected void init(Params params) {
        params.setNumItems(8)
                .setItemScreenSizeFactor(0.2)
                .includeHeaderAboveList(true);

    }

    @Override
    protected void positionSelected(int positon) {
        if (positon != getListView().getSelectedItemPosition()) {
            throw new IllegalStateException("something is fishy... the selected postion does not " +
                    "match what the list reports.");
        }
        setHeaderValue(
                ((TextView) getListView().getSelectedView()).getText().toString());

    }
}
