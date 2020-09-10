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

package com.android.settingslib.searchprovider;

import static android.provider.SearchIndexablesContract.INDEXABLES_XML_RES_COLUMNS;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.SearchIndexableResource;
import android.provider.SearchIndexablesContract.XmlResource;
import android.provider.SearchIndexablesProvider;
import android.text.TextUtils;

import java.util.Collection;

/**
 * An abstract SearchIndexProvider using {@link SearchIndexableIntentResource} for indexing
 */
public abstract class SettingsXmlIndexProvider extends SearchIndexablesProvider {
    private static final String TAG = "XmlIndexProvider";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryXmlResources(String[] projection) {
        final Context context = getContext();
        final MatrixCursor cursor = new MatrixCursor(INDEXABLES_XML_RES_COLUMNS);
        final Collection<SearchIndexableIntentResource> resources = getIntentResources();

        for (SearchIndexableIntentResource indexableResource : resources) {
            cursor.newRow()
                    .add(XmlResource.COLUMN_RANK, indexableResource.rank)
                    .add(XmlResource.COLUMN_XML_RESID, indexableResource.xmlResId)
                    .add(XmlResource.COLUMN_CLASS_NAME, indexableResource.className)
                    .add(XmlResource.COLUMN_INTENT_ACTION, indexableResource.intentAction)
                    .add(XmlResource.COLUMN_INTENT_TARGET_PACKAGE, context.getPackageName())
                    .add(XmlResource.COLUMN_INTENT_TARGET_CLASS,
                            indexableResource.intentTargetClass);
        }
        return cursor;
    }

    /**
     * Returns all {@link android.provider.SearchIndexablesContract.RawData}.
     *
     * Those are the raw indexable data.
     *
     * @param projection list of {@link android.provider.SearchIndexablesContract.RawData} columns
     *                   to put into the cursor. If {@code null} all supported columns should be
     *                   included.
     */
    public Cursor queryRawData(String[] projection) {
        return null;
    }

    /**
     * Returns all {@link android.provider.SearchIndexablesContract.NonIndexableKey}.
     *
     * Those are the non indexable data keys.
     *
     * @param projection list of {@link android.provider.SearchIndexablesContract.NonIndexableKey}
     *                   columns to put into the cursor. If {@code null} all supported columns
     *                   should be included.
     */
    public Cursor queryNonIndexableKeys(String[] projection) {
        return null;
    }

    /**
     * Returns a Collection of {@link SearchIndexableIntentResource} that should be indexed for
     * search.
     */
    protected abstract Collection<SearchIndexableIntentResource> getIntentResources();

    /**
     * Wrapper class of {@link SearchIndexableResource}. It is for setting the search indexable
     * resource of corresponding XML and intent action with class.
     */
    public static final class SearchIndexableIntentResource extends SearchIndexableResource {
        /**
         * Constructor of {@link SearchIndexableIntentResource}.
         *
         * @param xmlResId preference xml of target {@link prefereceFragment}
         * @param intentAction the intent to open target {@link Activity}
         * @param className the target {@link Activity} class name
         */
        public SearchIndexableIntentResource(int xmlResId, String intentAction,
                String className) {
            super(
                    0 /* rank */,
                    xmlResId,
                    className,
                    0 /* icon resource id */);
            if (TextUtils.isEmpty(intentAction)) {
                this.intentAction = "android.intent.action.MAIN";
            } else {
                this.intentAction = intentAction;
            }
            this.intentTargetClass = className;
        }
    }
}
