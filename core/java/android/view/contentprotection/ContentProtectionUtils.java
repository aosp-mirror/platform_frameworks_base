/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.view.contentprotection;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.view.contentcapture.ContentCaptureEvent;
import android.view.contentcapture.ViewNode;

/**
 * Utilities for reading data from {@link ContentCaptureEvent} and {@link ViewNode}.
 *
 * @hide
 */
public final class ContentProtectionUtils {

    /** Returns the lowercase text extracted from the {@link ContentCaptureEvent}, if set. */
    @Nullable
    public static String getEventTextLower(@NonNull ContentCaptureEvent event) {
        CharSequence text = event.getText();
        if (text == null) {
            return null;
        }
        return text.toString().toLowerCase();
    }

    /** Returns the lowercase text extracted from the {@link ViewNode}, if set. */
    @Nullable
    public static String getViewNodeTextLower(@Nullable ViewNode viewNode) {
        if (viewNode == null) {
            return null;
        }
        CharSequence text = viewNode.getText();
        if (text == null) {
            return null;
        }
        return text.toString().toLowerCase();
    }

    /** Returns the lowercase hint text extracted from the {@link ViewNode}, if set. */
    @Nullable
    public static String getHintTextLower(@Nullable ViewNode viewNode) {
        if (viewNode == null) {
            return null;
        }
        String text = viewNode.getHint();
        if (text == null) {
            return null;
        }
        return text.toLowerCase();
    }
}
