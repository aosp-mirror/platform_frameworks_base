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
 * An interface for mapping the string names of inspectable properties to integer identifiers.
 *
 * This interface is consumed by {@link InspectionHelper#mapProperties(PropertyMapper)}.
 *
 * Mapping properties to IDs enables quick comparisons against shadow copies of inspectable
 * objects without performing a large number of string comparisons.
 *
 * @see InspectionHelper#mapProperties(PropertyMapper)
 * @hide
 */
public interface PropertyMapper {
    /**
     * Map a string name to an integer ID for a primitive boolean property.
     *
     * @param name The name of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapBoolean(@NonNull String name);

    /**
     * Map a string name to an integer ID for a primitive byte property.
     *
     * @param name The name of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapByte(@NonNull String name);

    /**
     * Map a string name to an integer ID for a primitive char property.
     *
     * @param name The name of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapChar(@NonNull String name);

    /**
     * Map a string name to an integer ID for a primitive double property.
     *
     * @param name The name of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapDouble(@NonNull String name);

    /**
     * Map a string name to an integer ID for a primitive float property.
     *
     * @param name The name of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapFloat(@NonNull String name);

    /**
     * Map a string name to an integer ID for a primitive int property.
     *
     * @param name The name of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapInt(@NonNull String name);

    /**
     * Map a string name to an integer ID for a primitive long property.
     *
     * @param name The name of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapLong(@NonNull String name);

    /**
     * Map a string name to an integer ID for a primitive short property.
     *
     * @param name The name of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapShort(@NonNull String name);

    /**
     * Map a string name to an integer ID for an object property.
     *
     * @param name The name of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapObject(@NonNull String name);

    /**
     * Thrown from a map method if a property name is already mapped as different type.
     */
    class PropertyConflictException extends RuntimeException {
        public PropertyConflictException(
                @NonNull String name,
                @NonNull String newPropertyType,
                @NonNull String existingPropertyType) {
            super(String.format(
                    "Attempted to map property \"%s\" as type %s, but it is already mapped as %s.",
                    name,
                    newPropertyType,
                    existingPropertyType
            ));
        }
    }
}
