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
import java.util.Locale;

/**
 * The Interface IIOMetadataFormat is implemented by classes that describe the
 * rules and allowed elements for a metadata document tree.
 * 
 * @since Android 1.0
 */
public interface IIOMetadataFormat {

    /**
     * The CHILD_POLICY_EMPTY.
     */
    int CHILD_POLICY_EMPTY = 0;

    /**
     * The CHILD_POLICY_ALL.
     */
    int CHILD_POLICY_ALL = 1;

    /**
     * The CHILD_POLICY_SOME.
     */
    int CHILD_POLICY_SOME = 2;

    /**
     * The CHILD_POLICY_CHOICE.
     */
    int CHILD_POLICY_CHOICE = 3;

    /**
     * The CHILD_POLICY_SEQUENCE.
     */
    int CHILD_POLICY_SEQUENCE = 4;

    /**
     * The CHILD_POLICY_REPEAT.
     */
    int CHILD_POLICY_REPEAT = 5;

    /**
     * The maximum value for the child policy.
     */
    int CHILD_POLICY_MAX = CHILD_POLICY_REPEAT;

    /**
     * The DATATYPE_STRING.
     */
    int DATATYPE_STRING = 0;

    /**
     * The DATATYPE_BOOLEAN.
     */
    int DATATYPE_BOOLEAN = 1;

    /**
     * The DATATYPE_INTEGER.
     */
    int DATATYPE_INTEGER = 2;

    /**
     * The DATATYPE_FLOAT.
     */
    int DATATYPE_FLOAT = 3;

    /**
     * The DATATYPE_DOUBLE.
     */
    int DATATYPE_DOUBLE = 4;

    /**
     * The VALUE_NONE.
     */
    int VALUE_NONE = 0;

    /**
     * The VALUE_ARBITRARY.
     */
    int VALUE_ARBITRARY = 1;

    /**
     * The VALUE_RANGE.
     */
    int VALUE_RANGE = 2;

    /**
     * The VALUE_RANGE_MIN_INCLUSIVE_MASK.
     */
    int VALUE_RANGE_MIN_INCLUSIVE_MASK = 4;

    /**
     * The VALUE_RANGE_MAX_INCLUSIVE_MASK.
     */
    int VALUE_RANGE_MAX_INCLUSIVE_MASK = 8;

    /**
     * The VALUE_ENUMERATION.
     */
    int VALUE_ENUMERATION = 16;

    /**
     * The VALUE_LIST.
     */
    int VALUE_LIST = 32;

    /**
     * The VALUE_RANGE_MIN_INCLUSIVE.
     */
    int VALUE_RANGE_MIN_INCLUSIVE = VALUE_RANGE | VALUE_RANGE_MIN_INCLUSIVE_MASK;

    /**
     * The VALUE_RANGE_MAX_INCLUSIVE.
     */
    int VALUE_RANGE_MAX_INCLUSIVE = VALUE_RANGE | VALUE_RANGE_MAX_INCLUSIVE_MASK;

    /**
     * The VALUE_RANGE_MIN_MAX_INCLUSIVE.
     */
    int VALUE_RANGE_MIN_MAX_INCLUSIVE = VALUE_RANGE | VALUE_RANGE_MIN_INCLUSIVE_MASK
            | VALUE_RANGE_MAX_INCLUSIVE_MASK;

    /**
     * Tells whether the specified element is allowed for the specified image
     * type.
     * 
     * @param elementName
     *            the element name.
     * @param imageType
     *            the image type.
     * @return true, if the specified element is allowed for the specified image
     *         type.
     */
    boolean canNodeAppear(String elementName, ImageTypeSpecifier imageType);

    /**
     * Gets data type of the specified attribute of the specified element.
     * 
     * @param elementName
     *            the element name.
     * @param attrName
     *            the attribute name.
     * @return the attribute's data type.
     */
    int getAttributeDataType(String elementName, String attrName);

    /**
     * Gets the default value of the specified attribute of the specified
     * element.
     * 
     * @param elementName
     *            the element name.
     * @param attrName
     *            the attribute name.
     * @return the attribute's default value.
     */
    String getAttributeDefaultValue(String elementName, String attrName);

    /**
     * Gets the user-friendly description of the attribute.
     * 
     * @param elementName
     *            the element name.
     * @param attrName
     *            the attribute name.
     * @param locale
     *            the locale giving the desired language for the description.
     * @return the attribute description.
     */
    String getAttributeDescription(String elementName, String attrName, Locale locale);

    /**
     * Gets the attribute enumerations.
     * 
     * @param elementName
     *            the element name.
     * @param attrName
     *            the attribute name.
     * @return the attribute enumerations.
     */
    String[] getAttributeEnumerations(String elementName, String attrName);

    /**
     * Gets the maximum length of the attribute list.
     * 
     * @param elementName
     *            the element name.
     * @param attrName
     *            the attribute name.
     * @return the maximum length of the attribute list.
     */
    int getAttributeListMaxLength(String elementName, String attrName);

    /**
     * Gets the minimum length of the attribute list.
     * 
     * @param elementName
     *            the element name.
     * @param attrName
     *            the attribute name.
     * @return the minimum length of the attribute list.
     */
    int getAttributeListMinLength(String elementName, String attrName);

    /**
     * Gets the maximum value allowed for the attribute.
     * 
     * @param elementName
     *            the element name.
     * @param attrName
     *            the attribute name.
     * @return the maximum value allowed for the attribute.
     */
    String getAttributeMaxValue(String elementName, String attrName);

    /**
     * Gets the minimum value allowed for the attribute.
     * 
     * @param elementName
     *            the element name.
     * @param attrName
     *            the attribute name.
     * @return the minimum value allowed for the attribute.
     */
    String getAttributeMinValue(String elementName, String attrName);

    /**
     * Gets the attribute names allowed for the specified element.
     * 
     * @param elementName
     *            the element name.
     * @return the attribute names.
     */
    String[] getAttributeNames(String elementName);

    /**
     * Gets the attribute value type.
     * 
     * @param elementName
     *            the element name.
     * @param attrName
     *            the attribute name.
     * @return the attribute value type.
     */
    int getAttributeValueType(String elementName, String attrName);

    /**
     * Checks whether the specified attribute is required for the specified
     * element.
     * 
     * @param elementName
     *            the element name.
     * @param attrName
     *            the attribute name.
     * @return true, if the specified attribute is required for the specified
     *         element.
     */
    boolean isAttributeRequired(String elementName, String attrName);

    /**
     * Gets the names of the possible child elements for the given element.
     * 
     * @param elementName
     *            the element name.
     * @return the child names.
     */
    String[] getChildNames(String elementName);

    /**
     * Gets the constant describing the element's child policy.
     * 
     * @param elementName
     *            the element name.
     * @return the child policy.
     */
    int getChildPolicy(String elementName);

    /**
     * Gets the user-friendly description of the element.
     * 
     * @param elementName
     *            the element name.
     * @param locale
     *            the locale giving the desired language for the description.
     * @return the element description.
     */
    String getElementDescription(String elementName, Locale locale);

    /**
     * Gets the maximum number of children allowed for the element.
     * 
     * @param elementName
     *            the element name.
     * @return the maximum number of children allowed for the element.
     */
    int getElementMaxChildren(String elementName);

    /**
     * Gets the minimum number of children allowed for the element.
     * 
     * @param elementName
     *            the element name.
     * @return the minimum number of children allowed for the element.
     */
    int getElementMinChildren(String elementName);

    /**
     * Gets the maximum object array length allowed for the element.
     * 
     * @param elementName
     *            the element name.
     * @return the maximum object array length allowed for the element.
     */
    int getObjectArrayMaxLength(String elementName);

    /**
     * Gets the minimum object array length allowed for the element.
     * 
     * @param elementName
     *            the element name.
     * @return the minimum object array length allowed for the element.
     */
    int getObjectArrayMinLength(String elementName);

    /**
     * Gets the object class corresponding to the specified element.
     * 
     * @param elementName
     *            the element name.
     * @return the object class corresponding to the specified element.
     */
    Class<?> getObjectClass(String elementName);

    /**
     * Gets the object default value for the element.
     * 
     * @param elementName
     *            the element name.
     * @return the object default value for the element.
     */
    Object getObjectDefaultValue(String elementName);

    /**
     * Gets the object enumerations.
     * 
     * @param elementName
     *            the element name.
     * @return the object enumerations.
     */
    Object[] getObjectEnumerations(String elementName);

    /**
     * Gets the maximum value allowed for the element's object.
     * 
     * @param elementName
     *            the element name.
     * @return the maximum value allowed for the element's object.
     */
    Comparable<?> getObjectMaxValue(String elementName);

    /**
     * Gets the minimum value allowed for the element's object.
     * 
     * @param elementName
     *            the element name.
     * @return the minimum value allowed for the element's object.
     */
    Comparable<?> getObjectMinValue(String elementName);

    /**
     * Gets the constant that indicates the type of the element's value.
     * 
     * @param elementName
     *            the element name.
     * @return the constant that indicates the type of the element's value.
     */
    int getObjectValueType(String elementName);

    /**
     * Gets the name of the root element.
     * 
     * @return the name of the root element.
     */
    String getRootName();
}
