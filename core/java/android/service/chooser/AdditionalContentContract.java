/*
 * Copyright 2024 The Android Open Source Project
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

package android.service.chooser;

import android.annotation.FlaggedApi;

/**
 * Specifies constants used by Chooser when interacting with the additional content provider,
 * see {@link android.content.Intent#EXTRA_CHOOSER_ADDITIONAL_CONTENT_URI}.
 */
@FlaggedApi(android.service.chooser.Flags.FLAG_CHOOSER_PAYLOAD_TOGGLING)
public interface AdditionalContentContract {

    interface Columns {
        /**
         * Content URI for this item.
         * <p>
         * Note that this content URI must have a different authority from the content provided
         * given in {@link android.content.Intent#EXTRA_CHOOSER_ADDITIONAL_CONTENT_URI}.
         */
        String URI = "uri";
    }

    /**
     * Constants for {@link android.database.Cursor#getExtras} keys.
     */
    interface CursorExtraKeys {
        /**
         * An integer, zero-based cursor position that corresponds to the URI specified
         * with the {@link android.content.Intent#EXTRA_CHOOSER_FOCUSED_ITEM_POSITION} index into
         * the @link android.content.Intent#EXTRA_STREAM} array.
         */
        String POSITION = "position";
    }

    /**
     * Constants for method names used with {@link android.content.ContentResolver#call} method.
     */
    interface MethodNames {
        /**
         * A method name Chooser is using to notify the sharing app about a shared items selection
         * change.
         */
        String ON_SELECTION_CHANGED = "onSelectionChanged";
    }
}
