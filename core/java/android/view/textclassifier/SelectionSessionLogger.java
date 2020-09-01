/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.view.textclassifier;

import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A helper for logging selection session events.
 *
 * @hide
 */
public final class SelectionSessionLogger {
    // Keep this in sync with the ResultIdUtils in libtextclassifier.
    private static final String CLASSIFIER_ID = "androidtc";

    static boolean isPlatformLocalTextClassifierSmartSelection(String signature) {
        return SelectionSessionLogger.CLASSIFIER_ID.equals(
                SelectionSessionLogger.SignatureParser.getClassifierId(signature));
    }

    /**
     * Helper for creating and parsing string ids for
     * {@link android.view.textclassifier.TextClassifierImpl}.
     */
    @VisibleForTesting
    public static final class SignatureParser {

        static String getClassifierId(@Nullable String signature) {
            if (signature == null) {
                return "";
            }
            final int end = signature.indexOf("|");
            if (end >= 0) {
                return signature.substring(0, end);
            }
            return "";
        }
    }
}
