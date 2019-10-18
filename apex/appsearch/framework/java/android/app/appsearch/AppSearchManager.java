/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.app.appsearch;

import android.annotation.SystemService;
import android.content.Context;

/**
 * TODO(b/142567528): add comments when implement this class
 * @hide
 */
@SystemService(Context.APP_SEARCH_SERVICE)
public class AppSearchManager {
    private final IAppSearchManager mService;
    /**
     * TODO(b/142567528): add comments when implement this class
     * @hide
     */
    public AppSearchManager(IAppSearchManager service) {
        mService = service;
    }
}
