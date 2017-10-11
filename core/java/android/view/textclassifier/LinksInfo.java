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

import android.annotation.NonNull;

/**
 * Link information that can be applied to text. See: {@link #apply(CharSequence)}.
 * Typical implementations of this interface will annotate spannable text with e.g
 * {@link android.text.style.ClickableSpan}s or other annotations.
 * @hide
 */
public interface LinksInfo {

    /**
     * @hide
     */
    LinksInfo NO_OP = text -> false;

    /**
     * Applies link annotations to the specified text.
     * These annotations are not guaranteed to be applied. For example, the annotations may not be
     * applied if the text has changed from what it was when the link spec was generated for it.
     *
     * @return Whether or not the link annotations were successfully applied.
     */
    boolean apply(@NonNull CharSequence text);
}
