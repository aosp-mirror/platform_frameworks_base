/*
 * Copyright 2019 The Android Open Source Project
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

package android.processor.view.inspector;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.squareup.javapoet.ClassName;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Model of an inspectable class derived from annotations.
 *
 * This class does not use any {@code javax.lang.model} objects to facilitate building models for
 * testing {@link InspectionCompanionGenerator}.
 */
public final class InspectableClassModel {
    private final @NonNull ClassName mClassName;
    private final @NonNull Map<String, Property> mPropertyMap;

    /**
     * @param className The name of the modeled class
     */
    public InspectableClassModel(@NonNull ClassName className) {
        mClassName = className;
        mPropertyMap = new HashMap<>();
    }

    @NonNull
    public ClassName getClassName() {
        return mClassName;
    }

    /**
     * Add a property to the model, replacing an existing property of the same name.
     *
     * @param property The property to add or replace
     */
    public void putProperty(@NonNull Property property) {
        mPropertyMap.put(property.getName(), property);
    }

    /**
     * Get a property by name.
     *
     * @param name The name of the property
     * @return The property or an empty optional
     */
    @NonNull
    public Optional<Property> getProperty(@NonNull String name) {
        return Optional.ofNullable(mPropertyMap.get(name));
    }

    /**
     * Get all the properties defined on this model.
     *
     * @return An un-ordered collection of properties
     */
    @NonNull
    public Collection<Property> getAllProperties() {
        return mPropertyMap.values();
    }

    /**
     * Represents a way to access a property, either a getter or a field.
     */
    public static final class Accessor {
        private final @NonNull String mName;
        private final @NonNull Type mType;

        /**
         * Construct an accessor for a field.
         *
         * @param name The name of the field
         * @return The new accessor
         * @see Type#FIELD
         */
        @NonNull
        static Accessor ofField(@NonNull String name) {
            return new Accessor(name, Type.FIELD);
        }

        /**
         * Construct an accessor for a getter.
         *
         * @param name The name of the getter
         * @return The new accessor
         * @see Type#GETTER
         */
        @NonNull
        static Accessor ofGetter(@NonNull String name) {
            return new Accessor(name, Type.GETTER);
        }

        public Accessor(@NonNull String name, @NonNull Type type) {
            mName = Objects.requireNonNull(name, "Accessor name must not be null");
            mType = Objects.requireNonNull(type, "Accessor type must not be null");
        }

        @NonNull
        public String getName() {
            return mName;
        }

        @NonNull
        public Type getType() {
            return mType;
        }

        /**
         * Get the invocation of this accessor.
         *
         * Example: {@code "getValue()"} for a getter or {@code "valueField"} for a field.
         *
         * @return A string representing the invocation of this accessor
         */
        @NonNull
        public String invocation() {
            switch (mType) {
                case FIELD:
                    return mName;
                case GETTER:
                    return String.format("%s()", mName);
                default:
                    throw new NoSuchElementException(
                            String.format("No such accessor type %s", mType));
            }
        }

        public enum Type {
            /**
             * A property accessed by a public field.
             *
             * @see #ofField(String)
             */
            FIELD,

            /**
             * A property accessed by a public getter method.
             *
             * @see #ofGetter(String)
             */
            GETTER
        }
    }

    /**
     * Model an inspectable property
     */
    public static final class Property {
        private final @NonNull String mName;
        private final @NonNull Accessor mAccessor;
        private final @NonNull Type mType;
        private boolean mAttributeIdInferrableFromR = true;
        private int mAttributeId = 0;
        private @Nullable List<IntEnumEntry> mIntEnumEntries;
        private @Nullable List<IntFlagEntry> mIntFlagEntries;

        public Property(@NonNull String name, @NonNull Accessor accessor, @NonNull Type type) {
            mName = Objects.requireNonNull(name, "Name must not be null");
            mAccessor = Objects.requireNonNull(accessor, "Accessor must not be null");
            mType = Objects.requireNonNull(type, "Type must not be null");
        }

        public int getAttributeId() {
            return mAttributeId;
        }

        /**
         * Set the attribute ID, and mark the attribute ID as non-inferrable.
         *
         * @param attributeId The attribute ID for this property
         */
        public void setAttributeId(int attributeId) {
            mAttributeIdInferrableFromR = false;
            mAttributeId = attributeId;
        }

        public boolean isAttributeIdInferrableFromR() {
            return mAttributeIdInferrableFromR;
        }

        public void setAttributeIdInferrableFromR(boolean attributeIdInferrableFromR) {
            mAttributeIdInferrableFromR = attributeIdInferrableFromR;
        }

        @NonNull
        public String getName() {
            return mName;
        }

        @NonNull
        public Accessor getAccessor() {
            return mAccessor;
        }

        @NonNull
        public Type getType() {
            return mType;
        }

        /**
         * Get the mapping for an {@code int} enumeration, if present.
         *
         * @return A list of mapping entries, empty if absent
         */
        @NonNull
        public List<IntEnumEntry> getIntEnumEntries() {
            if (mIntEnumEntries != null) {
                return mIntEnumEntries;
            } else {
                return Collections.emptyList();
            }
        }

        public void setIntEnumEntries(@NonNull List<IntEnumEntry> intEnumEntries) {
            mIntEnumEntries = intEnumEntries;
        }

        /**
         * Get the mapping of {@code int} flags, if present.
         *
         * @return A list of mapping entries, empty if absent
         */
        @NonNull
        public List<IntFlagEntry> getIntFlagEntries() {
            if (mIntFlagEntries != null) {
                return mIntFlagEntries;
            } else {
                return Collections.emptyList();
            }
        }

        public void setIntFlagEntries(@NonNull List<IntFlagEntry> intFlagEntries) {
            mIntFlagEntries = intFlagEntries;
        }

        public enum Type {
            /** Primitive or boxed {@code boolean} */
            BOOLEAN,

            /** Primitive or boxed {@code byte} */
            BYTE,

            /** Primitive or boxed {@code char} */
            CHAR,

            /** Primitive or boxed {@code double} */
            DOUBLE,

            /** Primitive or boxed {@code float} */
            FLOAT,

            /** Primitive or boxed {@code int} */
            INT,

            /** Primitive or boxed {@code long} */
            LONG,

            /** Primitive or boxed {@code short} */
            SHORT,

            /** Any other object */
            OBJECT,

            /**
             * A color object or packed color {@code int} or {@code long}.
             *
             * @see android.graphics.Color
             * @see android.annotation.ColorInt
             * @see android.annotation.ColorLong
             */
            COLOR,

            /**
             * An {@code int} packed with a gravity specification
             *
             * @see android.view.Gravity
             */
            GRAVITY,

            /**
             * An enumeration packed into an {@code int}.
             *
             * @see android.view.inspector.IntEnumMapping
             * @see IntEnumEntry
             */
            INT_ENUM,

            /**
             * Non-exclusive or partially-exclusive flags packed into an {@code int}.
             *
             * @see android.view.inspector.IntFlagMapping
             * @see IntFlagEntry
             */
            INT_FLAG,

            /** A resource ID */
            RESOURCE_ID
        }
    }

    /**
     * Model one entry in a int enum mapping.
     *
     * @see android.view.inspector.IntEnumMapping
     */
    public static final class IntEnumEntry {
        private final @NonNull String mName;
        private final int mValue;

        public IntEnumEntry(int value, @NonNull String name) {
            mName = Objects.requireNonNull(name, "Name must not be null");
            mValue = value;
        }

        @NonNull
        public String getName() {
            return mName;
        }

        public int getValue() {
            return mValue;
        }
    }

    /**
     * Model one entry in an int flag mapping.
     *
     * @see android.view.inspector.IntFlagMapping
     */
    public static final class IntFlagEntry {
        private final @NonNull String mName;
        private final int mTarget;
        private final int mMask;

        public IntFlagEntry(int mask, int target, @NonNull String name) {
            mName = Objects.requireNonNull(name, "Name must not be null");
            mTarget = target;
            mMask = mask;
        }

        public IntFlagEntry(int target, String name) {
            this(target, target, name);
        }

        @NonNull
        public String getName() {
            return mName;
        }

        public int getTarget() {
            return mTarget;
        }

        public int getMask() {
            return mMask;
        }
    }
}
