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

import java.util.Iterator;

/**
 * A specialization of {@link Iterator} that allows iterating over a collection of
 * {@link Entity} objects. In addition to the iteration functionality it also allows
 * resetting the iterator back to the beginning and provides for an explicit {@link #close()}
 * method to indicate that the iterator is no longer needed and that its resources
 * can be released.
 */
public interface EntityIterator extends Iterator<Entity> {
    /**
     * Reset the iterator back to the beginning.
     */
    public void reset();

    /**
     * Indicates that this iterator is no longer needed and that any associated resources
     * may be released (such as a SQLite cursor).
     */
    public void close();
}
