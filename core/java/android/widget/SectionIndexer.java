/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.widget;

/**
 * Interface that may implemented on {@link Adapter}s to enable fast scrolling
 * between sections of an {@link AbsListView}.
 * <p>
 * A section is a group of list items that have something in common. For
 * example, they may begin with the same letter or they may be songs from the
 * same artist.
 * <p>
 * {@link ExpandableListAdapter}s that consider groups and sections as
 * synonymous should account for collapsed groups and return an appropriate
 * section/position.
 *
 * @see AbsListView#setFastScrollEnabled(boolean)
 */
public interface SectionIndexer {
    /**
     * Returns an array of objects representing sections of the list. The
     * returned array and its contents should be non-null.
     * <p>
     * The list view will call toString() on the objects to get the preview text
     * to display while scrolling. For example, an adapter may return an array
     * of Strings representing letters of the alphabet. Or, it may return an
     * array of objects whose toString() methods return their section titles.
     *
     * @return the array of section objects
     */
    Object[] getSections();

    /**
     * Given the index of a section within the array of section objects, returns
     * the starting position of that section within the adapter.
     * <p>
     * If the section's starting position is outside of the adapter bounds, the
     * position must be clipped to fall within the size of the adapter.
     *
     * @param sectionIndex the index of the section within the array of section
     *            objects
     * @return the starting position of that section within the adapter,
     *         constrained to fall within the adapter bounds
     */
    int getPositionForSection(int sectionIndex);

    /**
     * Given a position within the adapter, returns the index of the
     * corresponding section within the array of section objects.
     * <p>
     * If the section index is outside of the section array bounds, the index
     * must be clipped to fall within the size of the section array.
     * <p>
     * For example, consider an indexer where the section at array index 0
     * starts at adapter position 100. Calling this method with position 10,
     * which is before the first section, must return index 0.
     *
     * @param position the position within the adapter for which to return the
     *            corresponding section index
     * @return the index of the corresponding section within the array of
     *         section objects, constrained to fall within the array bounds
     */
    int getSectionForPosition(int position);
}
