/*
 * Copyright 2018 The Android Open Source Project
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

package android.view.inspector;

import android.annotation.NonNull;

/**
 * Interface for visiting all the child nodes of an inspectable object.
 *
 * Inspectable objects may return a collection of children as an array, an {@link Iterable} or an
 * {@link java.util.Iterator}. This provides a unified API for traversing across all the children
 * of an inspectable node.
 *
 * This interface is consumed by {@link InspectionHelper#traverseChildren(Object, ChildTraverser)}
 * and may be implemented as a lambda.
 *
 * @see InspectionHelper#traverseChildren(Object, ChildTraverser)
 * @hide
 */
@FunctionalInterface
public interface ChildTraverser {
    /**
     * Visit one child object of a parent inspectable object.
     *
     * The iteration interface will filter null values out before passing them to this method, but
     * some child objects may not be inspectable. It is up to the implementor to determine their
     * inspectablity and what to do with them.
     *
     * @param child A child object, guaranteed not to be null.
     */
    void traverseChild(@NonNull Object child);
}
