/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content;

import android.os.RemoteException;

/**
 * @hide
 */
public interface EntityIterator {
    /**
     * Returns whether there are more elements to iterate, i.e. whether the
     * iterator is positioned in front of an element.
     *
     * @return {@code true} if there are more elements, {@code false} otherwise.
     * @see #next
     * @since Android 1.0
     */
    public boolean hasNext() throws RemoteException;

    /**
     * Returns the next object in the iteration, i.e. returns the element in
     * front of the iterator and advances the iterator by one position.
     *
     * @return the next object.
     * @throws java.util.NoSuchElementException
     *             if there are no more elements.
     * @see #hasNext
     * @since Android 1.0
     */
    public Entity next() throws RemoteException;

    public void reset() throws RemoteException;

    /**
     * Indicates that this iterator is no longer needed and that any associated resources
     * may be released (such as a SQLite cursor).
     */
    public void close();
}
