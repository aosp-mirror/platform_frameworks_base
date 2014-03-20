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

import android.content.ContentResolver;

/**
 * Describe the contract for an Indexable data.
 *
 * @hide
 */
public class SearchIndexablesContract {

    /**
     * Intent action used to identify {@link SearchIndexablesProvider}
     * instances. This is used in the {@code <intent-filter>} of a {@code <provider>}.
     */
    public static final String PROVIDER_INTERFACE =
            "android.content.action.SEARCH_INDEXABLES_PROVIDER";

    private static final String SETTINGS = "settings";

    /**
     * Indexable references name.
     */
    public static final String INDEXABLES_XML_RES = "indexables_xml_res";

    /**
     * ContentProvider path for indexable xml resources.
     */
    public static final String INDEXABLES_XML_RES_PATH = SETTINGS + "/" + INDEXABLES_XML_RES;

    /**
     * Indexable raw data name.
     */
    public static final String INDEXABLES_RAW = "indexables_raw";

    /**
     * ContentProvider path for indexable raw data.
     */
    public static final String INDEXABLES_RAW_PATH = SETTINGS + "/" + INDEXABLES_RAW;

    /**
     * Indexable xml resources colums.
     */
    public static final String[] INDEXABLES_XML_RES_COLUMNS = new String[] {
            XmlResource.COLUMN_RANK,
            XmlResource.COLUMN_XML_RESID,
            XmlResource.COLUMN_CLASS_NAME,
            XmlResource.COLUMN_ICON_RESID,
            XmlResource.COLUMN_INTENT_ACTION,
            XmlResource.COLUMN_INTENT_TARGET_PACKAGE,
            XmlResource.COLUMN_INTENT_TARGET_CLASS
    };

    /**
     * Indexable raw data colums.
     */
    public static final String[] INDEXABLES_RAW_COLUMNS = new String[] {
            RawData.COLUMN_RANK,
            RawData.COLUMN_TITLE,
            RawData.COLUMN_SUMMARY,
            RawData.COLUMN_KEYWORDS,
            RawData.COLUMN_SCREEN_TITLE,
            RawData.COLUMN_CLASS_NAME,
            RawData.COLUMN_ICON_RESID,
            RawData.COLUMN_INTENT_ACTION,
            RawData.COLUMN_INTENT_TARGET_PACKAGE,
            RawData.COLUMN_INTENT_TARGET_CLASS,
    };

    /**
     * Constants related to a {@link SearchIndexableResource}.
     *
     * This is a description of
     */
    public static final class XmlResource extends BaseColumns {
        private XmlResource() {
        }

        public static final String MIME_TYPE = ContentResolver.CURSOR_DIR_BASE_TYPE +
                "/" + INDEXABLES_XML_RES;

        /**
         * XML resource ID for the {@link android.preference.PreferenceScreen} to load and index.
         */
        public static final String COLUMN_XML_RESID = "xmlResId";
    }

    /**
     * Constants related to a {@link SearchIndexableData}.
     *
     * This is the raw data that is stored into an Index. This is related to
     * {@link android.preference.Preference} and its attributes like
     * {@link android.preference.Preference#getTitle()},
     * {@link android.preference.Preference#getSummary()}, etc.
     *
     */
    public static final class RawData extends BaseColumns {
        private RawData() {
        }

        public static final String MIME_TYPE = ContentResolver.CURSOR_DIR_BASE_TYPE +
                "/" + INDEXABLES_RAW;

        /**
         * Title's raw data.
         */
        public static final String COLUMN_TITLE = "title";

        /**
         * Summary's raw data.
         */
        public static final String COLUMN_SUMMARY = "summary";

        /**
         * Keywords' raw data.
         */
        public static final String COLUMN_KEYWORDS = "keywords";

        /**
         * Fragment's title associated with the raw data.
         */
        public static final String COLUMN_SCREEN_TITLE = "screenTitle";
    }

    /**
     * The base columns.
     */
    private static class BaseColumns {
        private BaseColumns() {
        }

        /**
         * Rank of the data. This is an integer used for ranking the search results. This is
         * application specific.
         */
        public static final String COLUMN_RANK = "rank";

        /**
         * Class name associated with the data (usually a Fragment class name).
         */
        public static final String COLUMN_CLASS_NAME = "className";

        /**
         * Icon resource ID for the data.
         */
        public static final String COLUMN_ICON_RESID = "iconResId";

        /**
         * Intent action associated with the data.
         */
        public static final String COLUMN_INTENT_ACTION = "intentAction";

        /**
         * Intent target package associated with the data.
         */
        public static final String COLUMN_INTENT_TARGET_PACKAGE = "intentTargetPackage";

        /**
         * Intent target class associated with the data.
         */
        public static final String COLUMN_INTENT_TARGET_CLASS = "intentTargetClass";
    }
}
