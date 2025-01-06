/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.semantics;

/** Marker interface for a Component or Modifier that is relevant for Semantics. */
public interface AccessibilitySemantics {

    /**
     * Determines if this element is interesting for semantic analysis.
     *
     * <p>This method is used to filter elements during semantic analysis. By default, all elements
     * are considered interesting. Subclasses can override this method to exclude specific elements
     * from semantic analysis.
     *
     * @return {@code true} if this element is interesting for semantic analysis, {@code false}
     *     otherwise.
     */
    default boolean isInterestingForSemantics() {
        return true;
    }
}
