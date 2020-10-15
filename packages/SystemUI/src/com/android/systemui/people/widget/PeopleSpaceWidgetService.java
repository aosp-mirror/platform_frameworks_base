/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.people.widget;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViewsService;

import com.android.systemui.people.PeopleSpaceUtils;

/** People Space Widget Service class. */
public class PeopleSpaceWidgetService extends RemoteViewsService {
    private static final String TAG = "PeopleSpaceWidgetSvc";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        if (DEBUG) Log.d(TAG, "onGetViewFactory called");
        return new PeopleSpaceWidgetRemoteViewsFactory(this.getApplicationContext(), intent);
    }
}
