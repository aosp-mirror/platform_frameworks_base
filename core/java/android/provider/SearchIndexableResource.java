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

/**
 * Search Indexable Resource.
 *
 * This class wraps a set of reference information representing data that can be indexed from a
 * resource which would typically be a {@link android.preference.PreferenceScreen}.
 *
 * xmlResId: the resource ID of a {@link android.preference.PreferenceScreen} XML file.
 *
 * @see SearchIndexableData
 * @see android.preference.PreferenceScreen
 *
 * @hide
 */
public class SearchIndexableResource extends SearchIndexableData {

    /**
     * Resource ID of the associated {@link android.preference.PreferenceScreen} XML file.
     */
    public int xmlResId;

    /**
     * Constructor.
     *
     * @param rank the rank of the data.
     * @param xmlResId the resource ID of a {@link android.preference.PreferenceScreen} XML file.
     * @param className the class name associated with the data (generally a
     *                  {@link android.app.Fragment}).
     * @param iconResId the resource ID associated with the data.
     */
    public SearchIndexableResource(int rank, int xmlResId, String className, int iconResId) {
        super();
        this.rank = rank;
        this.xmlResId = xmlResId;
        this.className = className;
        this.iconResId = iconResId;
    }

    /**
     * Constructor.
     *
     * @param context the Context associated with the data.
     */
    public SearchIndexableResource(Context context) {
        super(context);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("SearchIndexableResource[");
        sb.append(super.toString());
        sb.append(", ");
        sb.append("xmlResId: ");
        sb.append(xmlResId);
        sb.append("]");

        return sb.toString();
    }
}