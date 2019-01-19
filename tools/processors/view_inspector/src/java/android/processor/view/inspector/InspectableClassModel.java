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

import com.squareup.javapoet.ClassName;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Model of an inspectable class derived from annotations.
 *
 * This class does not use any {@code javax.lang.model} objects to facilitate building models for
 * testing {@link InspectionCompanionGenerator}.
 */
public final class InspectableClassModel {
    private final ClassName mClassName;
    private final Map<String, Property> mPropertyMap;
    private Optional<String> mNodeName = Optional.empty();

    /**
     * @param className The name of the modeled class
     */
    public InspectableClassModel(ClassName className) {
        mClassName = className;
        mPropertyMap = new HashMap<>();
    }

    public ClassName getClassName() {
        return mClassName;
    }

    public Optional<String> getNodeName() {
        return mNodeName;
    }

    public void setNodeName(Optional<String> nodeName) {
        mNodeName = nodeName;
    }

    /**
     * Add a property to the model, replacing an existing property of the same name.
     *
     * @param property The property to add or replace
     */
    public void putProperty(Property property) {
        mPropertyMap.put(property.getName(), property);
    }

    /**
     * Get a property by name.
     *
     * @param name The name of the property
     * @return The property or an empty optional
     */
    public Optional<Property> getProperty(String name) {
        return Optional.ofNullable(mPropertyMap.get(name));
    }

    /**
     * Get all the properties defined on this model.
     *
     * @return An un-ordered collection of properties
     */
    public Collection<Property> getAllProperties() {
        return mPropertyMap.values();
    }

    /**
     * Model an inspectable property
     */
    public static final class Property {
        private final String mName;
        private final String mGetter;
        private final Type mType;
        private boolean mAttributeIdInferrableFromR = true;
        private int mAttributeId = 0;

        public Property(String name, String getter, Type type) {
            mName = Objects.requireNonNull(name, "Name must not be null");
            mGetter = Objects.requireNonNull(getter, "Getter must not be null");
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

        public String getName() {
            return mName;
        }

        public String getGetter() {
            return mGetter;
        }

        public Type getType() {
            return mType;
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
             */
            INT_ENUM,

            /**
             * Non-exclusive or partially-exclusive flags packed into an {@code int}.
             *
             * @see android.view.inspector.IntFlagMapping
             */
            INT_FLAG
        }
    }
}
