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

import android.annotation.AnyRes;
import android.annotation.ColorInt;
import android.annotation.ColorLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Color;

/**
 * An interface for reading the properties of an inspectable object.
 *
 * {@code PropertyReader} is defined as an interface that will be called by
 * {@link InspectionCompanion#readProperties(Object, PropertyReader)}. This approach allows a
 * client inspector to read the values of primitive properties without the overhead of
 * instantiating a class to hold the property values for each inspection pass. If an inspectable
 * remains unchanged between reading passes, it should be possible for a {@code PropertyReader} to
 * avoid new allocations for subsequent reading passes.
 *
 * It has separate methods for all primitive types to avoid autoboxing overhead if a concrete
 * implementation is able to work with primitives. Implementations should be prepared to accept
 * {null} as the value of {@link PropertyReader#readObject(int, Object)}.
 *
 * @see InspectionCompanion#readProperties(Object, PropertyReader)
 */
public interface PropertyReader {
    /**
     * Read a primitive boolean property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {@code boolean}
     */
    void readBoolean(int id, boolean value);

    /**
     * Read a primitive byte property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {@code byte}
     */
    void readByte(int id, byte value);

    /**
     * Read a primitive character property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {@code char}
     */
    void readChar(int id, char value);

    /**
     * Read a read a primitive double property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {@code double}
     */
    void readDouble(int id, double value);

    /**
     * Read a primitive float property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {@code float}
     */
    void readFloat(int id, float value);

    /**
     * Read a primitive integer property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as an {@code int}
     */
    void readInt(int id, int value);

    /**
     * Read a primitive long property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {@code long}
     */
    void readLong(int id, long value);

    /**
     * Read a primitive short property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {@code short}
     */
    void readShort(int id, short value);

    /**
     * Read any object as a property.
     *
     * If value is null, the property is marked as empty.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as an object
     */
    void readObject(int id, @Nullable Object value);

    /**
     * Read a color packed into an int as a property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a color
     */
    void readColor(int id, @ColorInt int value);

    /**
     * Read a color packed into a {@code ColorLong} as a property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property packed as a {@code ColorLong}. See the
     *              {@link Color} class for details of the packing.
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a color
     */
    void readColor(int id, @ColorLong long value);

    /**
     * Read a {@link Color} object as a property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a color
     */
    void readColor(int id, @Nullable Color value);

    /**
     * Read {@link android.view.Gravity} packed into an primitive {@code int}.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a gravity property
     */
    void readGravity(int id, int value);

    /**
     * Read an enumeration packed into a primitive {@code int}.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as an object
     */
    void readIntEnum(int id, int value);

    /**
     * Read a flag packed into a primitive {@code int}.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as an object
     */
    void readIntFlag(int id, int value);

    /**
     * Read an integer that contains a resource ID.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a resource ID.
     */
    void readResourceId(int id, @AnyRes int value);

    /**
     * Thrown if a client calls a typed read method for a property of a different type.
     */
    class PropertyTypeMismatchException extends RuntimeException {
        public PropertyTypeMismatchException(
                int id,
                @NonNull String expectedPropertyType,
                @NonNull String actualPropertyType,
                @Nullable String propertyName) {
            super(formatMessage(id, expectedPropertyType, actualPropertyType, propertyName));
        }

        public PropertyTypeMismatchException(
                int id,
                @NonNull String expectedPropertyType,
                @NonNull String actualPropertyType) {
            super(formatMessage(id, expectedPropertyType, actualPropertyType, null));
        }

        private static @NonNull String formatMessage(
                int id,
                @NonNull String expectedPropertyType,
                @NonNull String actualPropertyType,
                @Nullable String propertyName) {

            if (propertyName == null) {
                return String.format(
                        "Attempted to read property with ID 0x%08X as type %s, "
                            + "but the ID is of type %s.",
                        id,
                        expectedPropertyType,
                        actualPropertyType
                );
            } else {
                return String.format(
                        "Attempted to read property \"%s\" with ID 0x%08X as type %s, "
                            + "but the ID is of type %s.",
                        propertyName,
                        id,
                        expectedPropertyType,
                        actualPropertyType
                );
            }
        }
    }
}
