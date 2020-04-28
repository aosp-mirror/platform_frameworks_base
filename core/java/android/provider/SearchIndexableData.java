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

import android.annotation.SystemApi;
import android.content.Context;

import java.util.Locale;

/**
 * The Indexable data for Search.
 *
 * This abstract class defines the common parts for all search indexable data.
 *
 * @hide
 */
@SystemApi
public abstract class SearchIndexableData {

    /**
     * The context for the data. Will usually allow retrieving some resources.
     *
     * @see Context
     */
    public Context context;

    /**
     * The locale for the data
     */
    public Locale locale;

    /**
     * Tells if the data will be included into the search results. This is application specific.
     */
    public boolean enabled;

    /**
     * The rank for the data. This is application specific.
     */
    public int rank;

    /**
     * The key for the data. This is application specific. Should be unique per data as the data
     * should be able to be retrieved by the key.
     * <p/>
     * This is required for indexing to work.
     */
    public String key;

    /**
     * The UserID for the data (in a multi user context). This is application specific and -1 is the
     * default non initialized value.
     */
    public int userId = -1;

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
        locale = Locale.getDefault();
        enabled = true;
    }

    /**
     * Constructor with a {@link Context}.
     *
     * @param ctx the Context
     */
    public SearchIndexableData(Context ctx) {
        this();
        context = ctx;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("SearchIndexableData[context: ");
        sb.append(context);
        sb.append(", ");
        sb.append("locale: ");
        sb.append(locale);
        sb.append(", ");
        sb.append("enabled: ");
        sb.append(enabled);
        sb.append(", ");
        sb.append("rank: ");
        sb.append(rank);
        sb.append(", ");
        sb.append("key: ");
        sb.append(key);
        sb.append(", ");
        sb.append("userId: ");
        sb.append(userId);
        sb.append(", ");
        sb.append("className: ");
        sb.append(className);
        sb.append(", ");
        sb.append("packageName: ");
        sb.append(packageName);
        sb.append(", ");
        sb.append("iconResId: ");
        sb.append(iconResId);
        sb.append(", ");
        sb.append("intentAction: ");
        sb.append(intentAction);
        sb.append(", ");
        sb.append("intentTargetPackage: ");
        sb.append(intentTargetPackage);
        sb.append(", ");
        sb.append("intentTargetClass: ");
        sb.append(intentTargetClass);
        sb.append("]");

        return sb.toString();
    }
}
