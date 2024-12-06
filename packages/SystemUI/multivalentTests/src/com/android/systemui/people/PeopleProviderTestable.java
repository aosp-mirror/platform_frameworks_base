/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.people;

import android.content.Context;
import android.content.pm.ProviderInfo;

import com.android.systemui.people.widget.PeopleSpaceWidgetManager;

public class PeopleProviderTestable extends PeopleProvider {

    public void initializeForTesting(Context context, String authority) {
        ProviderInfo info = new ProviderInfo();
        info.authority = authority;

        attachInfoForTesting(context, info);
    }

    void setPeopleSpaceWidgetManager(PeopleSpaceWidgetManager peopleSpaceWidgetManager) {
        mPeopleSpaceWidgetManager = peopleSpaceWidgetManager;
    }
}
