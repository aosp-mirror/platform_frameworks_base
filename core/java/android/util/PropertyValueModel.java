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
 * A value model for a {@link Property property} of a host object. This class can be used for
 * both reflective and non-reflective property implementations.
 *
 * @param <H> the host type, where the host is the object that holds this property
 * @param <T> the value type
 *
 * @see Property
 * @see ValueModel
 */
public class PropertyValueModel<H, T> extends ValueModel<T> {
    private final H mHost;
    private final Property<H, T> mProperty;

    private PropertyValueModel(H host, Property<H, T> property) {
        mProperty = property;
        mHost = host;
    }

    /**
     * Returns the host.
     *
     * @return the host
     */
    public H getHost() {
        return mHost;
    }

    /**
     * Returns the property.
     *
     * @return the property
     */
    public Property<H, T> getProperty() {
        return mProperty;
    }

    @Override
    public Class<T> getType() {
        return mProperty.getType();
    }

    @Override
    public T get() {
        return mProperty.get(mHost);
    }

    @Override
    public void set(T value) {
        mProperty.set(mHost, value);
    }

    /**
     * Return an appropriate PropertyValueModel for this host and property.
     *
     * @param host the host
     * @param property the property
     * @return the value model
     */
    public static <H, T> PropertyValueModel<H, T> of(H host, Property<H, T> property) {
        return new PropertyValueModel<H, T>(host, property);
    }

    /**
     * Return a PropertyValueModel for this {@code host} and a
     * reflective property, constructed from this {@code propertyType} and {@code propertyName}.
     *
     * @param host
     * @param propertyType the property type
     * @param propertyName the property name
     * @return a value model with this host and a reflective property with this type and name
     *
     * @see Property#of
     */
    public static <H, T> PropertyValueModel<H, T> of(H host, Class<T> propertyType,
            String propertyName) {
        return of(host, Property.of((Class<H>) host.getClass(), propertyType, propertyName));
    }

    private static Class getNullaryMethodReturnType(Class c, String name) {
        try {
            return c.getMethod(name).getReturnType();
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static Class getFieldType(Class c, String name) {
        try {
            return c.getField(name).getType();
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private static String capitalize(String name) {
        if (name.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /**
     * Return a PropertyValueModel for this {@code host} and and {@code propertyName}.
     *
     * @param host the host
     * @param propertyName the property name
     * @return a value model with this host and a reflective property with this name
     */
    public static PropertyValueModel of(Object host, String propertyName) {
        Class clazz = host.getClass();
        String suffix = capitalize(propertyName);
        Class propertyType = getNullaryMethodReturnType(clazz, "get" + suffix);
        if (propertyType == null) {
            propertyType = getNullaryMethodReturnType(clazz, "is" + suffix);
        } 
        if (propertyType == null) {
            propertyType = getFieldType(clazz, propertyName); 
        }         
        if (propertyType == null) {
            throw new NoSuchPropertyException(propertyName); 
        }
        return of(host, propertyType, propertyName);
    }
}
