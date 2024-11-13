/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.settingslib.spa.search

/**
 * Intent action used to identify SpaSearchProvider instances. This is used in the {@code
 * <intent-filter>} of a {@code <provider>}.
 */
const val PROVIDER_INTERFACE = "android.content.action.SPA_SEARCH_PROVIDER"

/** ContentProvider path for search static data */
const val SEARCH_STATIC_DATA = "search_static_data"

/** ContentProvider path for search dynamic data */
const val SEARCH_DYNAMIC_DATA = "search_dynamic_data"

/** ContentProvider path for search immutable status */
const val SEARCH_IMMUTABLE_STATUS = "search_immutable_status"

/** ContentProvider path for search mutable status */
const val SEARCH_MUTABLE_STATUS = "search_mutable_status"

/** ContentProvider path for search static row */
const val SEARCH_STATIC_ROW = "search_static_row"

/** ContentProvider path for search dynamic row */
const val SEARCH_DYNAMIC_ROW = "search_dynamic_row"


/** Enum to define all column names in provider. */
enum class ColumnEnum(val id: String) {
    ENTRY_ID("entryId"),
    ENTRY_LABEL("entryLabel"),
    SEARCH_TITLE("searchTitle"),
    SEARCH_KEYWORD("searchKw"),
    SEARCH_PATH("searchPath"),
    INTENT_TARGET_PACKAGE("intentTargetPackage"),
    INTENT_TARGET_CLASS("intentTargetClass"),
    INTENT_EXTRAS("intentExtras"),
    ENTRY_DISABLED("entryDisabled"),
}

/** Enum to define all queries supported in the provider. */
@SuppressWarnings("Immutable")
enum class QueryEnum(
    val queryPath: String,
    val columnNames: List<ColumnEnum>
) {
    SEARCH_STATIC_DATA_QUERY(
        SEARCH_STATIC_DATA,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.ENTRY_LABEL,
            ColumnEnum.SEARCH_TITLE,
            ColumnEnum.SEARCH_KEYWORD,
            ColumnEnum.SEARCH_PATH,
            ColumnEnum.INTENT_TARGET_PACKAGE,
            ColumnEnum.INTENT_TARGET_CLASS,
            ColumnEnum.INTENT_EXTRAS,
        )
    ),
    SEARCH_DYNAMIC_DATA_QUERY(
        SEARCH_DYNAMIC_DATA,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.ENTRY_LABEL,
            ColumnEnum.SEARCH_TITLE,
            ColumnEnum.SEARCH_KEYWORD,
            ColumnEnum.SEARCH_PATH,
            ColumnEnum.INTENT_TARGET_PACKAGE,
            ColumnEnum.INTENT_TARGET_CLASS,
            ColumnEnum.INTENT_EXTRAS,
        )
    ),
    SEARCH_IMMUTABLE_STATUS_DATA_QUERY(
        SEARCH_IMMUTABLE_STATUS,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.ENTRY_LABEL,
            ColumnEnum.ENTRY_DISABLED,
        )
    ),
    SEARCH_MUTABLE_STATUS_DATA_QUERY(
        SEARCH_MUTABLE_STATUS,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.ENTRY_LABEL,
            ColumnEnum.ENTRY_DISABLED,
        )
    ),
    SEARCH_STATIC_ROW_QUERY(
        SEARCH_STATIC_ROW,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.ENTRY_LABEL,
            ColumnEnum.SEARCH_TITLE,
            ColumnEnum.SEARCH_KEYWORD,
            ColumnEnum.SEARCH_PATH,
            ColumnEnum.INTENT_TARGET_PACKAGE,
            ColumnEnum.INTENT_TARGET_CLASS,
            ColumnEnum.INTENT_EXTRAS,
            ColumnEnum.ENTRY_DISABLED,
        )
    ),
    SEARCH_DYNAMIC_ROW_QUERY(
        SEARCH_DYNAMIC_ROW,
        listOf(
            ColumnEnum.ENTRY_ID,
            ColumnEnum.ENTRY_LABEL,
            ColumnEnum.SEARCH_TITLE,
            ColumnEnum.SEARCH_KEYWORD,
            ColumnEnum.SEARCH_PATH,
            ColumnEnum.INTENT_TARGET_PACKAGE,
            ColumnEnum.INTENT_TARGET_CLASS,
            ColumnEnum.INTENT_EXTRAS,
            ColumnEnum.ENTRY_DISABLED,
        )
    ),
}
