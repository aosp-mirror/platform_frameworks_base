/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.util;

/**
 * A ValueModel is an abstraction for a 'slot' or place in memory in which a value
 * may be stored and retrieved. A common implementation of ValueModel is a regular property of
 * an object, whose value may be retrieved by calling the appropriate <em>getter</em>
 * method and set by calling the corresponding <em>setter</em> method.
 *
 * @param <T> the value type
 *
 * @see PropertyValueModel
 */
public abstract class ValueModel<T> {
    /**
     * The empty model should be used in place of {@code null} to indicate that a
     * model has not been set. The empty model has no value and does nothing when it is set.
     */
    public static final ValueModel EMPTY = new ValueModel() {
        @Override
        public Class getType() {
            return Object.class;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public void set(Object value) {

        }
    };

    protected ValueModel() {
    }

    /**
     * Returns the type of this property.
     *
     * @return the property type
     */
    public abstract Class<T> getType();

    /**
     * Returns the value of this property.
     *
     * @return the property value
     */
    public abstract T get();

    /**
     * Sets the value of this property.
     *
     * @param value the new value for this property
     */
    public abstract void set(T value);
}