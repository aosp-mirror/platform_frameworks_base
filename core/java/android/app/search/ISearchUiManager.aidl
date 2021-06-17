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

package android.app.search;

import android.app.search.Query;
import android.app.search.SearchTarget;
import android.app.search.SearchTargetEvent;
import android.app.search.SearchSessionId;
import android.app.search.SearchContext;
import android.app.search.ISearchCallback;
import android.content.pm.ParceledListSlice;

/**
 * @hide
 */
interface ISearchUiManager {

    void createSearchSession(in SearchContext context, in SearchSessionId sessionId,
            in IBinder token);

    void query(in SearchSessionId sessionId, in Query input, in ISearchCallback callback);

    void notifyEvent(in SearchSessionId sessionId, in Query input, in SearchTargetEvent event);

    void destroySearchSession(in SearchSessionId sessionId);
}
