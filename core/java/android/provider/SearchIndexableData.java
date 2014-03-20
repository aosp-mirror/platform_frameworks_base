/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.provider;

import android.content.Context;

import java.util.Locale;

/**
 * The Indexable data for Search. This abstract class defines the common parts for all search
 * indexable data.
 *
 * @hide
 */
public abstract class SearchIndexableData {

    /**
     * The context for the data. Will usually allow to retrieve some resources.
     *
     * @see Context
     */
    public Context context;

    /**
     * The locale for the data
     */
    public Locale locale;

    /**
     * The rank for the data. This is application specific.
     */
    public int rank;

    /**
     * The class name associated with the data. Generally this is a Fragment class name for
     * referring where the data is coming from and for launching the associated Fragment for
     * displaying the data. This is used only when the data is provided "locally".
     *
     * If the data is provided "externally", the relevant information come from the
     * {@link SearchIndexableData#intentAction} and {@link SearchIndexableData#intentTargetPackage}
     * and {@link SearchIndexableData#intentTargetClass}.
     *
     * @see SearchIndexableData#intentAction
     * @see SearchIndexableData#intentTargetPackage
     * @see SearchIndexableData#intentTargetClass
     */
    public String className;

    /**
     * The package name for retrieving the icon associated with the data.
     *
     * @see SearchIndexableData#iconResId
     */
    public String packageName;

    /**
     * The icon resource ID associated with the data.
     *
     * @see SearchIndexableData#packageName
     */
    public int iconResId;

    /**
     * The Intent action associated with the data. This is used when the
     * {@link SearchIndexableData#className} is not relevant.
     *
     * @see SearchIndexableData#intentTargetPackage
     * @see SearchIndexableData#intentTargetClass
     */
    public String intentAction;

    /**
     * The Intent target package associated with the data.
     *
     * @see SearchIndexableData#intentAction
     * @see SearchIndexableData#intentTargetClass
     */
    public String intentTargetPackage;

    /**
     * The Intent target class associated with the data.
     *
     * @see SearchIndexableData#intentAction
     * @see SearchIndexableData#intentTargetPackage
     */
    public String intentTargetClass;

    /**
     * Default constructor.
     */
    public SearchIndexableData() {
    }

    /**
     * Constructor with a {@link Context}.
     *
     * @param ctx the Context
     */
    public SearchIndexableData(Context ctx) {
        context = ctx;
        locale = Locale.getDefault();
    }
}
