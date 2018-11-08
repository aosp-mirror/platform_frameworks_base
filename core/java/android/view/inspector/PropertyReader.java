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
 * An interface for reading the properties of an inspectable object.
 *
 * Used as the parameter for {@link InspectionHelper#readProperties(Object, PropertyReader)}.
 * It has separate methods for all primitive types to avoid autoboxing overhead if a concrete
 * implementation is able to work with primitives. Implementations should be prepared to accept
 * {null} as the value of {@link PropertyReader#readObject(int, Object)}.
 *
 * @see InspectionHelper#readProperties(Object, PropertyReader)
 * @hide
 */
public interface PropertyReader {
    /**
     * Read a primitive boolean property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {boolean}
     */
    void readBoolean(int id, boolean value);

    /**
     * Read a primitive byte property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {byte}
     */
    void readByte(int id, byte value);

    /**
     * Read a primitive character property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {char}
     */
    void readChar(int id, char value);

    /**
     * Read a read a primitive double property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {double}
     */
    void readDouble(int id, double value);

    /**
     * Read a primitive float property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {float}
     */
    void readFloat(int id, float value);

    /**
     * Read a primitive integer property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as an {int}
     */
    void readInt(int id, int value);

    /**
     * Read a primitive long property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {long}
     */
    void readLong(int id, long value);

    /**
     * Read a primitive short property.
     *
     * @param id Identifier of the property from a {@link PropertyMapper}
     * @param value Value of the property
     * @throws PropertyTypeMismatchException If the property ID is not mapped as a {short}
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
