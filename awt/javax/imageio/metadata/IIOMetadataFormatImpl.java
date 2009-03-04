/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package javax.imageio.metadata;

import javax.imageio.ImageTypeSpecifier;
import java.util.*;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * The IIOMetadataFormatImpl class provides an implementation of the
 * IIOMetadataFormat interface.
 * 
 * @since Android 1.0
 */
public abstract class IIOMetadataFormatImpl implements IIOMetadataFormat {

    /**
     * The Constant standardMetadataFormatName.
     */
    @SuppressWarnings( {
        "ConstantDeclaredInAbstractClass"
    })
    public static final String standardMetadataFormatName = "javax_imageio_1.0";

    /**
     * The standard format.
     */
    @SuppressWarnings( {
        "StaticNonFinalField"
    })
    private static IIOMetadataFormatImpl standardFormat;

    /**
     * The root name.
     */
    private String rootName;

    /**
     * The element hash.
     */
    private HashMap<String, Element> elementHash = new HashMap<String, Element>();

    /**
     * The resource base name.
     */
    private String resourceBaseName = getClass().getName() + "Resources";

    /**
     * Instantiates an IIOMetadataFormatImpl with the specified root name and
     * child policy (not CHILD_POLICY_REPEAT).
     * 
     * @param rootName
     *            the name of root element.
     * @param childPolicy
     *            the child policy defined by one of the CHILD_POLICY_*
     *            constants (except CHILD_POLICY_REPEAT).
     */
    public IIOMetadataFormatImpl(String rootName, int childPolicy) {
        if (rootName == null) {
            throw new IllegalArgumentException("rootName is null");
        }
        if (childPolicy < CHILD_POLICY_EMPTY || childPolicy > CHILD_POLICY_MAX
                || childPolicy == CHILD_POLICY_REPEAT) {
            throw new IllegalArgumentException("childPolicy is not one of the predefined constants");
        }

        this.rootName = rootName;
        Element root = new Element();
        root.name = rootName;
        root.childPolicy = childPolicy;
        elementHash.put(rootName, root);
    }

    /**
     * Instantiates an IIOMetadataFormatImpl with the specified root name and
     * CHILD_POLICY_REPEAT child policy.
     * 
     * @param rootName
     *            the name of root element.
     * @param minChildren
     *            the minimum number of children.
     * @param maxChildren
     *            the maximum number of children
     */
    public IIOMetadataFormatImpl(String rootName, int minChildren, int maxChildren) {
        if (rootName == null) {
            throw new IllegalArgumentException("rootName is null");
        }
        if (minChildren < 0) {
            throw new IllegalArgumentException("minChildren < 0!");
        }
        if (minChildren > maxChildren) {
            throw new IllegalArgumentException("minChildren > maxChildren!");
        }

        this.rootName = rootName;
        Element root = new Element();
        root.name = rootName;
        root.minChildren = minChildren;
        root.maxChildren = maxChildren;
        root.childPolicy = CHILD_POLICY_REPEAT;
        elementHash.put(rootName, root);
    }

    @SuppressWarnings( {
        "AbstractMethodOverridesAbstractMethod"
    })
    public abstract boolean canNodeAppear(String elementName, ImageTypeSpecifier imageType);

    /**
     * Adds a new attribute to an existing element.
     * 
     * @param elementName
     *            the name of the element to which the new attribute will be
     *            added.
     * @param attrName
     *            the attribute name.
     * @param dataType
     *            the data type of the new attribute.
     * @param required
     *            the flag which indicates whether this attribute must be
     *            present.
     * @param listMinLength
     *            the minimum legal number of list items.
     * @param listMaxLength
     *            the the maximum legal number of list items.
     */
    protected void addAttribute(String elementName, String attrName, int dataType,
            boolean required, int listMinLength, int listMaxLength) {
        if (attrName == null) {
            throw new IllegalArgumentException("attrName == null!");
        }
        if (dataType < DATATYPE_STRING || dataType > DATATYPE_DOUBLE) {
            throw new IllegalArgumentException("Invalid value for dataType!");
        }
        if (listMinLength < 0 || listMinLength > listMaxLength) {
            throw new IllegalArgumentException("Invalid list bounds!");
        }

        Element element = findElement(elementName);
        Attlist attr = new Attlist();
        attr.name = attrName;
        attr.dataType = dataType;
        attr.required = required;
        attr.listMinLength = listMinLength;
        attr.listMaxLength = listMaxLength;
        attr.valueType = VALUE_LIST;

        element.attributes.put(attrName, attr);
    }

    /**
     * Adds a new attribute to an existing element.
     * 
     * @param elementName
     *            the name of the element to which the new attribute will be
     *            added.
     * @param attrName
     *            the attribute name.
     * @param dataType
     *            the data type of the new attribute.
     * @param required
     *            the flag which indicates whether this attribute must be
     *            present.
     * @param defaultValue
     *            the default value of the attribute.
     */
    protected void addAttribute(String elementName, String attrName, int dataType,
            boolean required, String defaultValue) {
        if (attrName == null) {
            throw new IllegalArgumentException("attrName == null!");
        }
        if (dataType < DATATYPE_STRING || dataType > DATATYPE_DOUBLE) {
            throw new IllegalArgumentException("Invalid value for dataType!");
        }

        Element element = findElement(elementName);
        Attlist attr = new Attlist();
        attr.name = attrName;
        attr.dataType = dataType;
        attr.required = required;
        attr.defaultValue = defaultValue;
        attr.valueType = VALUE_ARBITRARY;

        element.attributes.put(attrName, attr);
    }

    /**
     * Adds a new attribute to an existing element.
     * 
     * @param elementName
     *            the name of the element to which the new attribute will be
     *            added.
     * @param attrName
     *            the attribute name.
     * @param dataType
     *            the data type of the new attribute.
     * @param required
     *            the flag which indicates whether this attribute must be
     *            present.
     * @param defaultValue
     *            the default value of the attribute.
     * @param enumeratedValues
     *            the legal values for the attribute as a list of strings.
     */
    protected void addAttribute(String elementName, String attrName, int dataType,
            boolean required, String defaultValue, List<String> enumeratedValues) {
        if (attrName == null) {
            throw new IllegalArgumentException("attrName == null!");
        }
        if (dataType < DATATYPE_STRING || dataType > DATATYPE_DOUBLE) {
            throw new IllegalArgumentException("Invalid value for dataType!");
        }
        if (enumeratedValues == null || enumeratedValues.isEmpty()) {
            throw new IllegalArgumentException("enumeratedValues is empty or null");
        }

        try {
            for (String enumeratedValue : enumeratedValues) {
                if (enumeratedValue == null) {
                    throw new IllegalArgumentException("enumeratedValues contains a null!");
                }
            }
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("enumeratedValues contains a non-String value!");
        }

        Element element = findElement(elementName);
        Attlist attr = new Attlist();
        attr.name = attrName;
        attr.dataType = dataType;
        attr.required = required;
        attr.defaultValue = defaultValue;
        attr.enumeratedValues = enumeratedValues;
        attr.valueType = VALUE_ENUMERATION;

        element.attributes.put(attrName, attr);
    }

    /**
     * Adds a new attribute to an existing element.
     * 
     * @param elementName
     *            the name of the element to which the new attribute will be
     *            added.
     * @param attrName
     *            the attribute name.
     * @param dataType
     *            the data type of the new attribute.
     * @param required
     *            the flag which indicates whether this attribute must be
     *            present.
     * @param defaultValue
     *            the default value of attribute.
     * @param minValue
     *            the minimum legal value of an attribute.
     * @param maxValue
     *            the maximum legal value of an attribute.
     * @param minInclusive
     *            the flag which indicates whether the minValue is inclusive.
     * @param maxInclusive
     *            the flag which indicates whether the maxValue is inclusive.
     */
    protected void addAttribute(String elementName, String attrName, int dataType,
            boolean required, String defaultValue, String minValue, String maxValue,
            boolean minInclusive, boolean maxInclusive) {
        if (attrName == null) {
            throw new IllegalArgumentException("attrName == null!");
        }
        if (dataType < DATATYPE_STRING || dataType > DATATYPE_DOUBLE) {
            throw new IllegalArgumentException("Invalid value for dataType!");
        }

        Element element = findElement(elementName);
        Attlist attr = new Attlist();
        attr.name = attrName;
        attr.dataType = dataType;
        attr.required = required;
        attr.defaultValue = defaultValue;
        attr.minValue = minValue;
        attr.maxValue = maxValue;
        attr.minInclusive = minInclusive;
        attr.maxInclusive = maxInclusive;

        attr.valueType = VALUE_RANGE;
        attr.valueType |= minInclusive ? VALUE_RANGE_MIN_INCLUSIVE_MASK : 0;
        attr.valueType |= maxInclusive ? VALUE_RANGE_MAX_INCLUSIVE_MASK : 0;

        element.attributes.put(attrName, attr);
    }

    /**
     * Adds a new attribute with boolean data type to an existing element.
     * 
     * @param elementName
     *            the name of the element to which the new attribute will be
     *            added.
     * @param attrName
     *            the attribute name.
     * @param hasDefaultValue
     *            the flag which indicates whether this attribute must have a
     *            default value.
     * @param defaultValue
     *            the default value.
     */
    protected void addBooleanAttribute(String elementName, String attrName,
            boolean hasDefaultValue, boolean defaultValue) {
        String defaultVal = hasDefaultValue ? (defaultValue ? "TRUE" : "FALSE") : null;
        ArrayList<String> values = new ArrayList<String>(2);
        values.add("TRUE");
        values.add("FALSE");

        addAttribute(elementName, attrName, DATATYPE_BOOLEAN, true, defaultVal, values);
    }

    /**
     * Adds an existing element to the list of child elements of the specified
     * parent element.
     * 
     * @param elementName
     *            the name of the element to be added.
     * @param parentName
     *            the parent element name.
     */
    protected void addChildElement(String elementName, String parentName) {
        Element parent = findElement(parentName);
        Element element = findElement(elementName);
        parent.children.add(element.name);
    }

    /**
     * Adds a new element type to this IIOMetadataFormat with a child policy (if
     * policy is not CHILD_POLICY_REPEAT).
     * 
     * @param elementName
     *            the name of the element to be added.
     * @param parentName
     *            the parent element name.
     * @param childPolicy
     *            one of the CHILD_POLICY_* constants defined by
     *            IIOMetadataFormat.
     */
    protected void addElement(String elementName, String parentName, int childPolicy) {
        if (childPolicy < CHILD_POLICY_EMPTY || childPolicy > CHILD_POLICY_MAX
                || childPolicy == CHILD_POLICY_REPEAT) {
            throw new IllegalArgumentException("childPolicy is not one of the predefined constants");
        }

        Element parent = findElement(parentName);
        Element element = new Element();
        element.name = elementName;
        element.childPolicy = childPolicy;
        elementHash.put(elementName, element);
        parent.children.add(elementName);
    }

    /**
     * Adds a new element type to this IIOMetadataFormat with
     * CHILD_POLICY_REPEAT and the specified minimum and maximum number of child
     * elements.
     * 
     * @param elementName
     *            the element name to be added.
     * @param parentName
     *            the parent element name.
     * @param minChildren
     *            the minimum number of child elements.
     * @param maxChildren
     *            the maximum number of child elements.
     */
    protected void addElement(String elementName, String parentName, int minChildren,
            int maxChildren) {
        if (minChildren < 0) {
            throw new IllegalArgumentException("minChildren < 0!");
        }
        if (minChildren > maxChildren) {
            throw new IllegalArgumentException("minChildren > maxChildren!");
        }

        Element parent = findElement(parentName);
        Element element = new Element();
        element.name = elementName;
        element.childPolicy = CHILD_POLICY_REPEAT;
        element.minChildren = minChildren;
        element.maxChildren = maxChildren;
        elementHash.put(elementName, element);
        parent.children.add(elementName);
    }

    /**
     * Adds an Object reference with the specified class type to be stored as
     * element's value.
     * 
     * @param elementName
     *            the element name.
     * @param classType
     *            the class indicates the legal types for the object's value.
     * @param arrayMinLength
     *            the minimum legal length for the array.
     * @param arrayMaxLength
     *            the maximum legal length for the array.
     */
    protected void addObjectValue(String elementName, Class<?> classType, int arrayMinLength,
            int arrayMaxLength) {
        Element element = findElement(elementName);

        ObjectValue objVal = new ObjectValue();
        objVal.classType = classType;
        objVal.arrayMaxLength = arrayMaxLength;
        objVal.arrayMinLength = arrayMinLength;
        objVal.valueType = VALUE_LIST;

        element.objectValue = objVal;
    }

    /**
     * Adds an Object reference with the specified class type to be stored as an
     * element's value.
     * 
     * @param elementName
     *            the element name.
     * @param classType
     *            the class indicates the legal types for the object's value.
     * @param required
     *            a flag indicated that this object value must be present.
     * @param defaultValue
     *            the default value, or null.
     */
    protected <T> void addObjectValue(String elementName, Class<T> classType, boolean required,
            T defaultValue) {
        // note: reqired is an unused parameter
        Element element = findElement(elementName);

        ObjectValue<T> objVal = new ObjectValue<T>();
        objVal.classType = classType;
        objVal.defaultValue = defaultValue;
        objVal.valueType = VALUE_ARBITRARY;

        element.objectValue = objVal;
    }

    /**
     * Adds an Object reference with the specified class type to be stored as
     * the element's value.
     * 
     * @param elementName
     *            the element name.
     * @param classType
     *            the class indicates the legal types for the object value.
     * @param required
     *            a flag indicated that this object value must be present.
     * @param defaultValue
     *            the default value, or null.
     * @param enumeratedValues
     *            the list of legal values for the object.
     */
    protected <T> void addObjectValue(String elementName, Class<T> classType, boolean required,
            T defaultValue, List<? extends T> enumeratedValues) {
        // note: reqired is an unused parameter
        if (enumeratedValues == null || enumeratedValues.isEmpty()) {
            throw new IllegalArgumentException("enumeratedValues is empty or null");
        }

        try {
            for (T enumeratedValue : enumeratedValues) {
                if (enumeratedValue == null) {
                    throw new IllegalArgumentException("enumeratedValues contains a null!");
                }
            }
        } catch (ClassCastException e) {
            throw new IllegalArgumentException(
                    "enumeratedValues contains a value not of class classType!");
        }

        Element element = findElement(elementName);

        ObjectValue<T> objVal = new ObjectValue<T>();
        objVal.classType = classType;
        objVal.defaultValue = defaultValue;
        objVal.enumeratedValues = enumeratedValues;
        objVal.valueType = VALUE_ENUMERATION;

        element.objectValue = objVal;
    }

    /**
     * Adds an Object reference with the specified class type to be stored as
     * the element's value.
     * 
     * @param elementName
     *            the element name.
     * @param classType
     *            the class indicates the legal types for the object value.
     * @param defaultValue
     *            the default value, or null.
     * @param minValue
     *            the minimum legal value for the object value.
     * @param maxValue
     *            the maximum legal value for the object value.
     * @param minInclusive
     *            the flag which indicates whether the minValue is inclusive.
     * @param maxInclusive
     *            the flag which indicates whether the maxValue is inclusive.
     */
    protected <T extends Object & Comparable<? super T>> void addObjectValue(String elementName,
            Class<T> classType, T defaultValue, Comparable<? super T> minValue,
            Comparable<? super T> maxValue, boolean minInclusive, boolean maxInclusive) {
        Element element = findElement(elementName);

        ObjectValue<T> objVal = new ObjectValue<T>();
        objVal.classType = classType;
        objVal.defaultValue = defaultValue;
        objVal.minValue = minValue;
        objVal.maxValue = maxValue;
        objVal.minInclusive = minInclusive;
        objVal.maxInclusive = maxInclusive;

        objVal.valueType = VALUE_RANGE;
        objVal.valueType |= minInclusive ? VALUE_RANGE_MIN_INCLUSIVE_MASK : 0;
        objVal.valueType |= maxInclusive ? VALUE_RANGE_MAX_INCLUSIVE_MASK : 0;

        element.objectValue = objVal;
    }

    public int getAttributeDataType(String elementName, String attrName) {
        Attlist attr = findAttribute(elementName, attrName);
        return attr.dataType;
    }

    public String getAttributeDefaultValue(String elementName, String attrName) {
        Attlist attr = findAttribute(elementName, attrName);
        return attr.defaultValue;
    }

    public String getAttributeDescription(String elementName, String attrName, Locale locale) {
        findAttribute(elementName, attrName);
        return getResourceString(elementName + "/" + attrName, locale);
    }

    public String[] getAttributeEnumerations(String elementName, String attrName) {
        Attlist attr = findAttribute(elementName, attrName);
        if (attr.valueType != VALUE_ENUMERATION) {
            throw new IllegalArgumentException("Attribute is not an enumeration!");
        }

        return attr.enumeratedValues.toArray(new String[attr.enumeratedValues.size()]);
    }

    public int getAttributeListMaxLength(String elementName, String attrName) {
        Attlist attr = findAttribute(elementName, attrName);
        if (attr.valueType != VALUE_LIST) {
            throw new IllegalArgumentException("Attribute is not a list!");
        }
        return attr.listMaxLength;
    }

    public int getAttributeListMinLength(String elementName, String attrName) {
        Attlist attr = findAttribute(elementName, attrName);
        if (attr.valueType != VALUE_LIST) {
            throw new IllegalArgumentException("Attribute is not a list!");
        }
        return attr.listMinLength;
    }

    public String getAttributeMaxValue(String elementName, String attrName) {
        Attlist attr = findAttribute(elementName, attrName);
        if ((attr.valueType & VALUE_RANGE) == 0) {
            throw new IllegalArgumentException("Attribute is not a range!");
        }
        return attr.maxValue;
    }

    public String getAttributeMinValue(String elementName, String attrName) {
        Attlist attr = findAttribute(elementName, attrName);
        if ((attr.valueType & VALUE_RANGE) == 0) {
            throw new IllegalArgumentException("Attribute is not a range!");
        }
        return attr.minValue;
    }

    public String[] getAttributeNames(String elementName) {
        Element element = findElement(elementName);
        return element.attributes.keySet().toArray(new String[element.attributes.size()]);
    }

    public int getAttributeValueType(String elementName, String attrName) {
        Attlist attr = findAttribute(elementName, attrName);
        return attr.valueType;
    }

    public String[] getChildNames(String elementName) {
        Element element = findElement(elementName);
        if (element.childPolicy == CHILD_POLICY_EMPTY) { // Element cannot have
            // children
            return null;
        }
        return element.children.toArray(new String[element.children.size()]);
    }

    public int getChildPolicy(String elementName) {
        Element element = findElement(elementName);
        return element.childPolicy;
    }

    public String getElementDescription(String elementName, Locale locale) {
        findElement(elementName); // Check if there is such element
        return getResourceString(elementName, locale);
    }

    public int getElementMaxChildren(String elementName) {
        Element element = findElement(elementName);
        if (element.childPolicy != CHILD_POLICY_REPEAT) {
            throw new IllegalArgumentException("Child policy is not CHILD_POLICY_REPEAT!");
        }
        return element.maxChildren;
    }

    public int getElementMinChildren(String elementName) {
        Element element = findElement(elementName);
        if (element.childPolicy != CHILD_POLICY_REPEAT) {
            throw new IllegalArgumentException("Child policy is not CHILD_POLICY_REPEAT!");
        }
        return element.minChildren;
    }

    public int getObjectArrayMaxLength(String elementName) {
        Element element = findElement(elementName);
        ObjectValue v = element.objectValue;
        if (v == null || v.valueType != VALUE_LIST) {
            throw new IllegalArgumentException("Not a list!");
        }
        return v.arrayMaxLength;
    }

    public int getObjectArrayMinLength(String elementName) {
        Element element = findElement(elementName);
        ObjectValue v = element.objectValue;
        if (v == null || v.valueType != VALUE_LIST) {
            throw new IllegalArgumentException("Not a list!");
        }
        return v.arrayMinLength;
    }

    public Class<?> getObjectClass(String elementName) {
        ObjectValue v = findObjectValue(elementName);
        return v.classType;
    }

    public Object getObjectDefaultValue(String elementName) {
        ObjectValue v = findObjectValue(elementName);
        return v.defaultValue;
    }

    public Object[] getObjectEnumerations(String elementName) {
        Element element = findElement(elementName);
        ObjectValue v = element.objectValue;
        if (v == null || v.valueType != VALUE_ENUMERATION) {
            throw new IllegalArgumentException("Not an enumeration!");
        }
        return v.enumeratedValues.toArray();
    }

    public Comparable<?> getObjectMaxValue(String elementName) {
        Element element = findElement(elementName);
        ObjectValue v = element.objectValue;
        if (v == null || (v.valueType & VALUE_RANGE) == 0) {
            throw new IllegalArgumentException("Not a range!");
        }
        return v.maxValue;
    }

    public Comparable<?> getObjectMinValue(String elementName) {
        Element element = findElement(elementName);
        ObjectValue v = element.objectValue;
        if (v == null || (v.valueType & VALUE_RANGE) == 0) {
            throw new IllegalArgumentException("Not a range!");
        }
        return v.minValue;
    }

    public int getObjectValueType(String elementName) {
        Element element = findElement(elementName);
        if (element.objectValue == null) {
            return VALUE_NONE;
        }
        return element.objectValue.valueType;
    }

    /**
     * Gets the resource base name for locating ResourceBundles.
     * 
     * @return the current resource base name.
     */
    protected String getResourceBaseName() {
        return resourceBaseName;
    }

    public String getRootName() {
        return rootName;
    }

    /**
     * Gets the standard format instance.
     * 
     * @return the IIOMetadataFormat instance.
     */
    public static IIOMetadataFormat getStandardFormatInstance() {
        if (standardFormat == null) {
            standardFormat = new IIOStandardMetadataFormat();
        }

        return standardFormat;
    }

    public boolean isAttributeRequired(String elementName, String attrName) {
        return findAttribute(elementName, attrName).required;
    }

    /**
     * Removes the specified attribute from the specified element.
     * 
     * @param elementName
     *            the specified element name.
     * @param attrName
     *            the specified attribute name.
     */
    protected void removeAttribute(String elementName, String attrName) {
        Element element = findElement(elementName);
        element.attributes.remove(attrName);
    }

    /**
     * Removes the specified element from this format.
     * 
     * @param elementName
     *            the specified element name.
     */
    protected void removeElement(String elementName) {
        Element element;
        if ((element = elementHash.get(elementName)) != null) {
            elementHash.remove(elementName);
            for (Element e : elementHash.values()) {
                e.children.remove(element.name);
            }
        }
    }

    /**
     * Removes the object value from the specified element.
     * 
     * @param elementName
     *            the element name.
     */
    protected void removeObjectValue(String elementName) {
        Element element = findElement(elementName);
        element.objectValue = null;
    }

    /**
     * Sets a new base name for ResourceBundles containing descriptions of
     * elements and attributes for this format.
     * 
     * @param resourceBaseName
     *            the new resource base name.
     */
    protected void setResourceBaseName(String resourceBaseName) {
        if (resourceBaseName == null) {
            throw new IllegalArgumentException("resourceBaseName == null!");
        }
        this.resourceBaseName = resourceBaseName;
    }

    /**
     * The Class Element.
     */
    @SuppressWarnings( {
        "ClassWithoutConstructor"
    })
    private class Element {

        /**
         * The name.
         */
        String name;

        /**
         * The children.
         */
        ArrayList<String> children = new ArrayList<String>();

        /**
         * The attributes.
         */
        HashMap<String, Attlist> attributes = new HashMap<String, Attlist>();

        /**
         * The min children.
         */
        int minChildren;

        /**
         * The max children.
         */
        int maxChildren;

        /**
         * The child policy.
         */
        int childPolicy;

        /**
         * The object value.
         */
        ObjectValue objectValue;
    }

    /**
     * The Class Attlist.
     */
    @SuppressWarnings( {
        "ClassWithoutConstructor"
    })
    private class Attlist {

        /**
         * The name.
         */
        String name;

        /**
         * The data type.
         */
        int dataType;

        /**
         * The required.
         */
        boolean required;

        /**
         * The list min length.
         */
        int listMinLength;

        /**
         * The list max length.
         */
        int listMaxLength;

        /**
         * The default value.
         */
        String defaultValue;

        /**
         * The enumerated values.
         */
        List<String> enumeratedValues;

        /**
         * The min value.
         */
        String minValue;

        /**
         * The max value.
         */
        String maxValue;

        /**
         * The min inclusive.
         */
        boolean minInclusive;

        /**
         * The max inclusive.
         */
        boolean maxInclusive;

        /**
         * The value type.
         */
        int valueType;
    }

    /**
     * The Class ObjectValue.
     */
    @SuppressWarnings( {
        "ClassWithoutConstructor"
    })
    private class ObjectValue<T> {

        /**
         * The class type.
         */
        Class<T> classType;

        /**
         * The array min length.
         */
        int arrayMinLength;

        /**
         * The array max length.
         */
        int arrayMaxLength;

        /**
         * The default value.
         */
        T defaultValue;

        /**
         * The enumerated values.
         */
        List<? extends T> enumeratedValues;

        /**
         * The min value.
         */
        Comparable<? super T> minValue;

        /**
         * The max value.
         */
        Comparable<? super T> maxValue;

        /**
         * The min inclusive.
         */
        boolean minInclusive;

        /**
         * The max inclusive.
         */
        boolean maxInclusive;

        /**
         * The value type.
         */
        int valueType;
    }

    /**
     * Find element.
     * 
     * @param name
     *            the name.
     * @return the element.
     */
    private Element findElement(String name) {
        Element element;
        if ((element = elementHash.get(name)) == null) {
            throw new IllegalArgumentException("element name is null or no such element: " + name);
        }

        return element;
    }

    /**
     * Find attribute.
     * 
     * @param elementName
     *            the element name.
     * @param attributeName
     *            the attribute name.
     * @return the attlist.
     */
    private Attlist findAttribute(String elementName, String attributeName) {
        Element element = findElement(elementName);
        Attlist attribute;
        if ((attribute = element.attributes.get(attributeName)) == null) {
            throw new IllegalArgumentException("attribute name is null or no such attribute: "
                    + attributeName);
        }

        return attribute;
    }

    /**
     * Find object value.
     * 
     * @param elementName
     *            the element name.
     * @return the object value.
     */
    private ObjectValue findObjectValue(String elementName) {
        Element element = findElement(elementName);
        ObjectValue v = element.objectValue;
        if (v == null) {
            throw new IllegalArgumentException("No object within element");
        }
        return v;
    }

    /**
     * Gets the resource string.
     * 
     * @param key
     *            the key.
     * @param locale
     *            the locale.
     * @return the resource string.
     */
    private String getResourceString(String key, Locale locale) {
        if (locale == null) {
            locale = Locale.getDefault();
        }

        // Get the context class loader and try to locate the bundle with it
        // first
        ClassLoader contextClassloader = AccessController
                .doPrivileged(new PrivilegedAction<ClassLoader>() {
                    public ClassLoader run() {
                        return Thread.currentThread().getContextClassLoader();
                    }
                });

        // Now try to get the resource bundle
        ResourceBundle rb;
        try {
            rb = ResourceBundle.getBundle(resourceBaseName, locale, contextClassloader);
        } catch (MissingResourceException e) {
            try {
                rb = ResourceBundle.getBundle(resourceBaseName, locale);
            } catch (MissingResourceException e1) {
                return null;
            }
        }

        try {
            return rb.getString(key);
        } catch (MissingResourceException e) {
            return null;
        } catch (ClassCastException e) {
            return null; // Not a string resource
        }
    }
}
