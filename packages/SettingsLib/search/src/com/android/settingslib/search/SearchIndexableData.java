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

package com.android.settingslib.search;

/**
 * A Bundle class used in {@link SearchIndexableResources} to provide search Index data.
 */
public class SearchIndexableData {
    private final Class mTargetClass;
    private final Indexable.SearchIndexProvider mSearchIndexProvider;

    /**
     * Constructs a SearchIndexableData
     *
     * @param targetClass The target opening class of the {@link Indexable.SearchIndexProvider}. It
     *                    should be a {@link android.app.Activity} or fragment {@link
     *                    androidx.fragment.app.Fragment}.
     *                    But fragment is only supported in Android Settings. Other apps should use
     *                    {@link android.app.Activity}
     * @param provider    provides searchable data for Android Settings
     */
    public SearchIndexableData(Class targetClass, Indexable.SearchIndexProvider provider) {
        mTargetClass = targetClass;
        mSearchIndexProvider = provider;
    }

    public Class getTargetClass() {
        return mTargetClass;
    }

    public Indexable.SearchIndexProvider getSearchIndexProvider() {
        return mSearchIndexProvider;
    }
}
