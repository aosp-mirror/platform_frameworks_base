/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.webkit;

import java.io.Serializable;

/**
 * This class contains the back/forward list for a WebView.
 * WebView.copyBackForwardList() will return a copy of this class used to
 * inspect the entries in the list.
 */
public class WebBackForwardList implements Cloneable, Serializable {

    /**
     *  @hide
     */
    public WebBackForwardList() {
    }

    /**
     * Return the current history item. This method returns null if the list is
     * empty.
     * @return The current history item.
     */
    public synchronized WebHistoryItem getCurrentItem() {
        throw new MustOverrideException();
    }

    /**
     * Get the index of the current history item. This index can be used to
     * directly index into the array list.
     * @return The current index from 0...n or -1 if the list is empty.
     */
    public synchronized int getCurrentIndex() {
        throw new MustOverrideException();
    }

    /**
     * Get the history item at the given index. The index range is from 0...n
     * where 0 is the first item and n is the last item.
     * @param index The index to retrieve.
     */
    public synchronized WebHistoryItem getItemAtIndex(int index) {
        throw new MustOverrideException();
    }

    /**
     * Get the total size of the back/forward list.
     * @return The size of the list.
     */
    public synchronized int getSize() {
        throw new MustOverrideException();
    }

    /**
     * Clone the entire object to be used in the UI thread by clients of
     * WebView. This creates a copy that should never be modified by any of the
     * webkit package classes.
     */
    protected synchronized WebBackForwardList clone() {
        throw new MustOverrideException();
    }

}
