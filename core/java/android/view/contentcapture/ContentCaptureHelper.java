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
package android.view.contentcapture;

import android.annotation.Nullable;

/**
 * Helpe class for this package.
 */
final class ContentCaptureHelper {

    // TODO(b/121044306): define a way to dynamically set them(for example, using settings?)
    static final boolean VERBOSE = false;
    static final boolean DEBUG = true; // STOPSHIP if not set to false

    /**
     * Used to log text that could contain PII.
     */
    @Nullable
    public static String getSanitizedString(@Nullable CharSequence text) {
        return text == null ? null : text.length() + "_chars";
    }

    private ContentCaptureHelper() {
        throw new UnsupportedOperationException("contains only static methods");
    }
}
