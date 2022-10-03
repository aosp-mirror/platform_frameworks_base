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

package android.text;

import android.annotation.IntRange;

/**
 * Finds text segment boundaries within text. Subclasses can implement different types of text
 * segments. Grapheme clusters and words are examples of possible text segments.
 *
 * <p>Text segments may not overlap, so every character belongs to at most one text segment. A
 * character may belong to no text segments.
 *
 * <p>For example, a word level text segment finder may subdivide the text "Hello, World!" into four
 * text segments: "Hello", ",", "World", "!". The space character does not belong to any text
 * segments.
 *
 * @hide
 */
public abstract class SegmentFinder {
    public static final int DONE = -1;

    /**
     * Returns the character offset of the previous text segment start boundary before the specified
     * character offset, or {@code DONE} if there are none.
     */
    public abstract int previousStartBoundary(@IntRange(from = 0) int offset);

    /**
     * Returns the character offset of the previous text segment end boundary before the specified
     * character offset, or {@code DONE} if there are none.
     */
    public abstract int previousEndBoundary(@IntRange(from = 0) int offset);

    /**
     * Returns the character offset of the next text segment start boundary after the specified
     * character offset, or {@code DONE} if there are none.
     */
    public abstract int nextStartBoundary(@IntRange(from = 0) int offset);

    /**
     * Returns the character offset of the next text segment end boundary after the specified
     * character offset, or {@code DONE} if there are none.
     */
    public abstract int nextEndBoundary(@IntRange(from = 0) int offset);
}
