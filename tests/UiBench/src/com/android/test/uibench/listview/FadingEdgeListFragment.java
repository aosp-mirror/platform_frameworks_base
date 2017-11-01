/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.test.uibench.listview;

import android.os.Bundle;
import android.support.v4.app.ListFragment;
import android.widget.ListView;

public class FadingEdgeListFragment extends ListFragment {

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        ListView listView = getListView();
        listView.setVerticalFadingEdgeEnabled(true);
        listView.setFadingEdgeLength(500);
        super.onActivityCreated(savedInstanceState);
    }
}
