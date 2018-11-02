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
import android.annotation.Nullable;

/**
 * An interface for companion objects used to inspect views.
 *
 * Inspection helpers only need to handle the properties, name and traversal of the specific class
 * they are defined for, not anything from a parent class. At runtime, the inspector instantiates
 * one instance of each inspection helper, and handles visiting them in the correct inheritance
 * order for each type it inspects.
 *
 * Properties are read from the top of the type tree to the bottom, so that classes that override
 * a property in their parent class can overwrite it in the reader. In general, properties will
 * cleanly inherit through their getters, and the inspector runtime will read the properties of a
 * parent class via the parent's inspection helper, and the child helper will only read properties
 * added or changed since the parent was defined.
 *
 * Only one child traversal is considered for each class. If a descendant class defines a
 * different child traversal than its parent, only the bottom traversal is used. If a class does
 * not define its own child traversal, but one of its ancestors does, the bottom-most ancestor's
 * traversal will be used.
 *
 * @param <T> The type of inspectable this helper operates on
 * @hide
 */
public interface InspectionHelper<T> {
    /**
     * Map the string names of the properties this helper knows about to integer IDs.
     *
     * Each helper is responsible for storing the integer IDs of all its properties. This is the
     * only method that is allowed to modify the stored IDs.
     *
     * Calling {@link #readProperties(T, PropertyReader)} before calling this results in
     * undefined behavior.
     *
     * @param propertyMapper A {@link PropertyMapper} or lambda which maps string names to IDs.
     */
    void mapProperties(@NonNull PropertyMapper propertyMapper);

    /**
     * Read the values of an instance of this helper's type into a {@link PropertyReader}.
     *
     * This method needs to return the property IDs stored by
     * {@link #mapProperties(PropertyMapper)}. Implementations should track if their properties
     * have been mapped and throw a {@link UninitializedPropertyMapException} if this method is
     * called before {mapProperties}.
     *
     * @param inspectable A object of type {@link T} to read the properties of.
     * @param propertyReader An object which receives the property IDs and values.
     */
    void readProperties(@NonNull T inspectable, @NonNull PropertyReader propertyReader);

    /**
     * Query if this inspectable type can potentially have child nodes.
     *
     * E.g.: any descendant of {@link android.view.ViewGroup} can have child nodes, but a leaf
     * view like {@link android.widget.ImageView} may not.
     *
     * The default implementation always returns false. If an implementing class overrides this, it
     * should also define {@link #traverseChildren(T, ChildTraverser)}.
     *
     * @return True if this inspectable type can potentially have child nodes, false otherwise.
     */
    default boolean hasChildTraversal() {
        return false;
    }

    /**
     * Traverse the child nodes of an instance of this helper's type into a {@link ChildTraverser}.
     *
     * This provides the ability to traverse over a variety of collection APIs (e.g.: arrays,
     * {@link Iterable}, or {@link java.util.Iterator}) in a uniform fashion. The traversal must be
     * in the order defined by this helper's type. If the getter returns null, the helper must
     * treat it as an empty collection.
     *
     * The default implementation throws a {@link NoChildTraversalException}. If
     * {@link #hasChildTraversal()} returns is overriden to return true, it is expected that the
     * implementing class will also override this method and provide a traversal.
     *
     * @param inspectable An object of type {@link T} to traverse the child nodes of.
     * @param childTraverser A {@link ChildTraverser} or lamba to receive the children in order.
     * @throws NoChildTraversalException If there is no defined child traversal
     */
    default void traverseChildren(
            @NonNull T inspectable,
            @SuppressWarnings("unused") @NonNull ChildTraverser childTraverser) {
        throw new NoChildTraversalException(inspectable.getClass());
    }

    /**
     * Get an optional name to display to developers for inspection nodes of this helper's type.
     *
     * The default implementation returns null, which will cause the runtime to use the class's
     * simple name as defined by {@link Class#getSimpleName()} as the node name.
     *
     * If the type of this helper is inflated from XML, this method should be overridden to return
     * the string used as the tag name for this type in XML.
     *
     * @return A string to use as the node name, or null to use the simple class name fallback.
     */
    @Nullable
    default String getNodeName() {
        return null;
    }

    /**
     * Thrown by {@link #readProperties(Object, PropertyReader)} if called before
     * {@link #mapProperties(PropertyMapper)}.
     */
    class UninitializedPropertyMapException extends RuntimeException {
        public UninitializedPropertyMapException() {
            super("Unable to read properties of an inspectable before mapping their IDs.");
        }
    }

    /**
     * Thrown by {@link #traverseChildren(Object, ChildTraverser)} if no child traversal exists.
     */
    class NoChildTraversalException extends RuntimeException {
        public NoChildTraversalException(Class cls) {
            super(String.format(
                    "Class %s does not have a defined child traversal. Cannot traverse children.",
                    cls.getCanonicalName()
            ));
        }
    }
}
