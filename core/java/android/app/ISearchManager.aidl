/**
 * Copyright (c) 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app;

import android.app.ISearchManagerCallback;
import android.content.ComponentName;
import android.content.res.Configuration;
import android.os.Bundle;
import android.server.search.SearchableInfo;

/** @hide */
interface ISearchManager {
   SearchableInfo getSearchableInfo(in ComponentName launchActivity, boolean globalSearch);
   List<SearchableInfo> getSearchablesInGlobalSearch();
   List<SearchableInfo> getSearchablesForWebSearch();
   SearchableInfo getDefaultSearchableForWebSearch();
   void setDefaultWebSearch(in ComponentName component);
   void startSearch(in String initialQuery,
            boolean selectInitialQuery,
            in ComponentName launchActivity,
            in Bundle appSearchData,
            boolean globalSearch,
            ISearchManagerCallback searchManagerCallback,
            int ident);
    void stopSearch();
    boolean isVisible();
}
