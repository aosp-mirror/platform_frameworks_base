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

package com.android.server.pm;

import android.content.pm.ResolveInfo;

import java.util.List;

// Collect the results of queryIntentActivitiesInternalBody into a single class
public final class QueryIntentActivitiesResult {
    public boolean sortResult = false;
    public boolean addInstant = false;
    public List<ResolveInfo> result = null;
    public List<ResolveInfo> answer = null;

    QueryIntentActivitiesResult(List<ResolveInfo> l) {
        answer = l;
    }

    QueryIntentActivitiesResult(boolean s, boolean a, List<ResolveInfo> l) {
        sortResult = s;
        addInstant = a;
        result = l;
    }
}
