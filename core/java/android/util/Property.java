/*
 * Copyright (C) 2011 The Android Open Source Project
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
 * A property is an abstraction that can be used to represent a <emb>mutable</em> value that is held
 * in a <em>host</em> object. The Property's {@link #set(Object, Object)} or {@link #get(Object)}
 * methods can be implemented in terms of the private fields of the host object, or via "setter" and
 * "getter" methods or by some other mechanism, as appropriate.
 *
 * @param <T> The class on which the property is declared.
 * @param <V> The type that this property represents.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public abstract class Property<T, V> {

    private final String mName;
    private final Class<V> mType;

    /**
     * This factory method creates and returns a Property given the <code>class</code> and
     * <code>name</code> parameters, where the <code>"name"</code> parameter represents either:
     * <ul>
     *     <li>a public <code>getName()</code> method on the class which takes no arguments, plus an
     *     optional public <code>setName()</code> method which takes a value of the same type
     *     returned by <code>getName()</code>
     *     <li>a public <code>isName()</code> method on the class which takes no arguments, plus an
     *     optional public <code>setName()</code> method which takes a value of the same type
     *     returned by <code>isName()</code>
     *     <li>a public <code>name</code> field on the class
     * </ul>
     *
     * <p>If either of the get/is method alternatives is found on the class, but an appropriate
     * <code>setName()</code> method is not found, the <code>Property</code> will be
     * {@link #isReadOnly() readOnly}. Calling the {@link #set(Object, Object)} method on such
     * a property is allowed, but will have no effect.</p>
     *
     * <p>If neither the methods nor the field are found on the class a
     * {@link NoSuchPropertyException} exception will be thrown.</p>
     */
    public static <T, V> Property<T, V> of(Class<T> hostType, Class<V> valueType, String name) {
        return new ReflectiveProperty<T, V>(hostType, valueType, name);
    }

    /**
     * A constructor that takes an identifying name and {@link #getType() type} for the property.
     */
    public Property(Class<V> type, String name) {
        mName = name;
        mType = type;
    }

    /**
     * Returns true if the {@link #set(Object, Object)} method does not set the value on the target
     * object (in which case the {@link #set(Object, Object) set()} method should throw a {@link
     * NoSuchPropertyException} exception). This may happen if the Property wraps functionality that
     * allows querying the underlying value but not setting it. For example, the {@link #of(Class,
     * Class, String)} factory method may return a Property with name "foo" for an object that has
     * only a <code>getFoo()</code> or <code>isFoo()</code> method, but no matching
     * <code>setFoo()</code> method.
     */
    public boolean isReadOnly() {
        return false;
    }

    /**
     * Sets the value on <code>object</code> which this property represents. If the method is unable
     * to set the value on the target object it will throw an {@link UnsupportedOperationException}
     * exception.
     */
    public void set(T object, V value) {
        throw new UnsupportedOperationException("Property " + getName() +" is read-only");
    }

    /**
     * Returns the current value that this property represents on the given <code>object</code>.
     */
    public abstract V get(T object);

    /**
     * Returns the name for this property.
     */
    public String getName() {
        return mName;
    }

    /**
     * Returns the type for this property.
     */
    public Class<V> getType() {
        return mType;
    }
}
