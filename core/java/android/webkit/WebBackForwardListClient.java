/*
 * Copyright (C) 2010 The Android Open Source Project
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

/**
 * Interface to receive notifications when items are added to the
 * {@link WebBackForwardList}.
 * {@hide}
 */
public abstract class WebBackForwardListClient {

    /**
     * Notify the client that <var>item</var> has been added to the
     * WebBackForwardList.
     * @param item The newly created WebHistoryItem
     */
    public void onNewHistoryItem(WebHistoryItem item) { }

    /**
     * Notify the client that the <var>item</var> at <var>index</var> is now
     * the current history item.
     * @param item A WebHistoryItem
     * @param index The new history index
     */
    public void onIndexChanged(WebHistoryItem item, int index) { }
}
