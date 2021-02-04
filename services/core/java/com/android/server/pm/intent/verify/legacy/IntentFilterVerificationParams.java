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

package com.android.server.pm.intent.verify.legacy;

import android.content.pm.parsing.component.ParsedActivity;

import java.util.List;

public class IntentFilterVerificationParams {

    String packageName;
    boolean hasDomainUrls;
    List<ParsedActivity> activities;
    boolean replacing;
    int userId;
    int verifierUid;

    public IntentFilterVerificationParams(String packageName, boolean hasDomainUrls,
            List<ParsedActivity> activities, boolean _replacing,
            int _userId, int _verifierUid) {
        this.packageName = packageName;
        this.hasDomainUrls = hasDomainUrls;
        this.activities = activities;
        replacing = _replacing;
        userId = _userId;
        verifierUid = _verifierUid;
    }
}
