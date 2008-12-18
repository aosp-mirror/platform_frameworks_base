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
 * Interface that should be implemented on Adapters to enable fast scrolling 
 * in an {@link AbsListView} between sections of the list. A section is a group of list items
 * to jump to that have something in common. For example, they may begin with the
 * same letter or they may be songs from the same artist. 
 */
public interface SectionIndexer {
    /**
     * This provides the list view with an array of section objects. In the simplest
     * case these are Strings, each containing one letter of the alphabet.
     * They could be more complex objects that indicate the grouping for the adapter's
     * consumption. The list view will call toString() on the objects to get the
     * preview letter to display while scrolling.
     * @return the array of objects that indicate the different sections of the list.
     */
    Object[] getSections();
    
    /**
     * Provides the starting index in the list for a given section.
     * @param section the index of the section to jump to.
     * @return the starting position of that section. If the section is out of bounds, the
     * position must be clipped to fall within the size of the list.
     */
    int getPositionForSection(int section);
    
    /**
     * This is a reverse mapping to fetch the section index for a given position
     * in the list.
     * @param position the position for which to return the section
     * @return the section index. If the position is out of bounds, the section index
     * must be clipped to fall within the size of the section array.
     */
    int getSectionForPosition(int position);    
}
