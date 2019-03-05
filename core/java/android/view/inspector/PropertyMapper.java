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

import android.annotation.AttrRes;
import android.annotation.NonNull;

/**
 * An interface for mapping the string names of inspectable properties to integer identifiers.
 *
 * This interface is consumed by {@link InspectionCompanion#mapProperties(PropertyMapper)}.
 *
 * Mapping properties to IDs enables quick comparisons against shadow copies of inspectable
 * objects without performing a large number of string comparisons.
 *
 * @see InspectionCompanion#mapProperties(PropertyMapper)
 */
public interface PropertyMapper {
    /**
     * Map a string name to an integer ID for a primitive boolean property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapBoolean(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for a primitive byte property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapByte(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for a primitive char property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapChar(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for a primitive double property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapDouble(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for a primitive float property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapFloat(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for a primitive int property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapInt(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for a primitive long property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapLong(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for a primitive short property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapShort(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for an object property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapObject(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for a color property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     * @see android.graphics.Color
     */
    int mapColor(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for a gravity property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     * @see android.view.Gravity
     */
    int mapGravity(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for an enumeration packed into an int property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @param mapping A mapping from int to String
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapIntEnum(
            @NonNull String name,
            @AttrRes int attributeId,
            @NonNull IntEnumMapping mapping);

    /**
     * Map a string name to an integer ID for an attribute that contains resource IDs.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapResourceId(@NonNull String name, @AttrRes int attributeId);

    /**
     * Map a string name to an integer ID for a flag set packed into an int property.
     *
     * @param name The name of the property
     * @param attributeId If the property is from an XML attribute, the resource ID of the property
     * @param mapping A mapping from int to a set of strings
     * @return An integer ID for the property
     * @throws PropertyConflictException If the property name is already mapped as another type.
     */
    int mapIntFlag(
            @NonNull String name,
            @AttrRes int attributeId,
            @NonNull IntFlagMapping mapping);
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
