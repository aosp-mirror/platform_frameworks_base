/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.asllib.util;


import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class XmlUtils {
    public static final String DATA_TYPE_SEPARATOR = "_data_type_";

    public static final String HR_TAG_APP_METADATA_BUNDLES = "app-metadata-bundles";
    public static final String HR_TAG_SYSTEM_APP_SAFETY_LABEL = "system-app-safety-label";
    public static final String HR_TAG_SAFETY_LABELS = "safety-labels";
    public static final String HR_TAG_TRANSPARENCY_INFO = "transparency-info";
    public static final String HR_TAG_DEVELOPER_INFO = "developer-info";
    public static final String HR_TAG_APP_INFO = "app-info";
    public static final String HR_TAG_DATA_LABELS = "data-labels";
    public static final String HR_TAG_SECURITY_LABELS = "security-labels";
    public static final String HR_TAG_THIRD_PARTY_VERIFICATION = "third-party-verification";
    public static final String HR_TAG_DATA_ACCESSED = "data-accessed";
    public static final String HR_TAG_DATA_COLLECTED = "data-collected";
    public static final String HR_TAG_DATA_COLLECTED_EPHEMERAL = "data-collected-ephemeral";
    public static final String HR_TAG_DATA_SHARED = "data-shared";
    public static final String HR_TAG_ITEM = "item";
    public static final String HR_ATTR_NAME = "name";
    public static final String HR_ATTR_EMAIL = "email";
    public static final String HR_ATTR_ADDRESS = "address";
    public static final String HR_ATTR_COUNTRY_REGION = "countryRegion";
    public static final String HR_ATTR_DEVELOPER_RELATIONSHIP = "relationship";
    public static final String HR_ATTR_WEBSITE = "website";
    public static final String HR_ATTR_APP_DEVELOPER_REGISTRY_ID = "registryId";
    public static final String HR_ATTR_DATA_CATEGORY = "dataCategory";
    public static final String HR_ATTR_DATA_TYPE = "dataType";
    public static final String HR_ATTR_IS_COLLECTION_OPTIONAL = "isCollectionOptional";
    public static final String HR_ATTR_IS_SHARING_OPTIONAL = "isSharingOptional";
    public static final String HR_ATTR_IS_DATA_DELETABLE = "isDataDeletable";
    public static final String HR_ATTR_IS_DATA_ENCRYPTED = "isDataEncrypted";
    // public static final String HR_ATTR_EPHEMERAL = "ephemeral";
    public static final String HR_ATTR_PURPOSES = "purposes";
    public static final String HR_ATTR_VERSION = "version";
    public static final String HR_ATTR_URL = "url";
    public static final String HR_ATTR_DECLARATION = "declaration";
    public static final String HR_ATTR_TITLE = "title";
    public static final String HR_ATTR_DESCRIPTION = "description";
    public static final String HR_ATTR_CONTAINS_ADS = "containsAds";
    public static final String HR_ATTR_OBEY_APS = "obeyAps";
    public static final String HR_ATTR_APS_COMPLIANT = "apsCompliant";
    public static final String HR_ATTR_ADS_FINGERPRINTING = "adsFingerprinting";
    public static final String HR_ATTR_SECURITY_FINGERPRINTING = "securityFingerprinting";
    public static final String HR_ATTR_PRIVACY_POLICY = "privacyPolicy";
    public static final String HR_ATTR_DEVELOPER_ID = "developerId";
    public static final String HR_ATTR_APPLICATION_ID = "applicationId";
    public static final String HR_ATTR_SECURITY_ENDPOINTS = "securityEndpoints";
    public static final String HR_TAG_FIRST_PARTY_ENDPOINTS = "first-party-endpoints";
    public static final String HR_TAG_SERVICE_PROVIDER_ENDPOINTS = "service-provider-endpoints";
    public static final String HR_ATTR_CATEGORY = "category";

    public static final String OD_TAG_BUNDLE = "bundle";
    public static final String OD_TAG_PBUNDLE_AS_MAP = "pbundle_as_map";
    public static final String OD_TAG_BOOLEAN = "boolean";
    public static final String OD_TAG_LONG = "long";
    public static final String OD_TAG_STRING = "string";
    public static final String OD_TAG_INT_ARRAY = "int-array";
    public static final String OD_TAG_STRING_ARRAY = "string-array";
    public static final String OD_TAG_ITEM = "item";
    public static final String OD_ATTR_NAME = "name";
    public static final String OD_ATTR_VALUE = "value";
    public static final String OD_ATTR_NUM = "num";
    public static final String OD_NAME_SAFETY_LABELS = "safety_labels";
    public static final String OD_NAME_TRANSPARENCY_INFO = "transparency_info";
    public static final String OD_NAME_DEVELOPER_INFO = "developer_info";
    public static final String OD_NAME_NAME = "name";
    public static final String OD_NAME_EMAIL = "email";
    public static final String OD_NAME_ADDRESS = "address";
    public static final String OD_NAME_COUNTRY_REGION = "country_region";
    public static final String OD_NAME_DEVELOPER_RELATIONSHIP = "relationship";
    public static final String OD_NAME_WEBSITE = "website";
    public static final String OD_NAME_APP_DEVELOPER_REGISTRY_ID = "app_developer_registry_id";
    public static final String OD_NAME_APP_INFO = "app_info";
    public static final String OD_NAME_TITLE = "title";
    public static final String OD_NAME_DESCRIPTION = "description";
    public static final String OD_NAME_CONTAINS_ADS = "contains_ads";
    public static final String OD_NAME_OBEY_APS = "obey_aps";
    public static final String OD_NAME_APS_COMPLIANT = "aps_compliant";
    public static final String OD_NAME_DEVELOPER_ID = "developer_id";
    public static final String OD_NAME_APPLICATION_ID = "application_id";
    public static final String OD_NAME_ADS_FINGERPRINTING = "ads_fingerprinting";
    public static final String OD_NAME_SECURITY_FINGERPRINTING = "security_fingerprinting";
    public static final String OD_NAME_PRIVACY_POLICY = "privacy_policy";
    public static final String OD_NAME_SECURITY_ENDPOINTS = "security_endpoints";
    public static final String OD_NAME_FIRST_PARTY_ENDPOINTS = "first_party_endpoints";
    public static final String OD_NAME_SERVICE_PROVIDER_ENDPOINTS = "service_provider_endpoints";
    public static final String OD_NAME_CATEGORY = "category";
    public static final String OD_NAME_VERSION = "version";
    public static final String OD_NAME_URL = "url";
    public static final String OD_NAME_DECLARATION = "declaration";
    public static final String OD_NAME_SYSTEM_APP_SAFETY_LABEL = "system_app_safety_label";
    public static final String OD_NAME_SECURITY_LABELS = "security_labels";
    public static final String OD_NAME_THIRD_PARTY_VERIFICATION = "third_party_verification";
    public static final String OD_NAME_DATA_LABELS = "data_labels";
    public static final String OD_NAME_DATA_ACCESSED = "data_accessed";
    public static final String OD_NAME_DATA_COLLECTED = "data_collected";
    public static final String OD_NAME_DATA_SHARED = "data_shared";
    public static final String OD_NAME_PURPOSES = "purposes";
    public static final String OD_NAME_IS_COLLECTION_OPTIONAL = "is_collection_optional";
    public static final String OD_NAME_IS_SHARING_OPTIONAL = "is_sharing_optional";
    public static final String OD_NAME_IS_DATA_DELETABLE = "is_data_deletable";
    public static final String OD_NAME_IS_DATA_ENCRYPTED = "is_data_encrypted";
    public static final String OD_NAME_EPHEMERAL = "ephemeral";

    public static final String TRUE_STR = "true";
    public static final String FALSE_STR = "false";

    /** Gets the top-level children with the tag name.. */
    public static List<Element> getChildrenByTagName(Node parentEle, String tagName) {
        var elements = XmlUtils.asElementList(parentEle.getChildNodes());
        return elements.stream()
                .filter(e -> e.getTagName().equals(tagName))
                .collect(Collectors.toList());
    }

    /**
     * Gets the single {@link Element} within {@param parentEle} and having the {@param tagName}.
     */
    public static Element getSingleChildElement(
            Node parentEle, String tagName, Set<String> requiredStrings)
            throws MalformedXmlException {
        return getSingleChildElement(parentEle, tagName, requiredStrings.contains(tagName));
    }

    /**
     * Gets the single {@link Element} within {@param parentEle} and having the {@param tagName}.
     */
    public static Element getSingleChildElement(Node parentEle, String tagName, boolean required)
            throws MalformedXmlException {
        String parentTagNameForErrorMsg =
                (parentEle instanceof Element) ? ((Element) parentEle).getTagName() : "Node";
        var elements = getChildrenByTagName(parentEle, tagName);

        if (elements.size() > 1) {
            throw new MalformedXmlException(
                    String.format(
                            "Expected 1 %s in %s but got %s.",
                            tagName, parentTagNameForErrorMsg, elements.size()));
        } else if (elements.isEmpty()) {
            if (required) {
                throw new MalformedXmlException(
                        String.format(
                                "Expected 1 %s in %s but got 0.",
                                tagName, parentTagNameForErrorMsg));
            } else {
                return null;
            }
        }
        return elements.get(0);
    }

    /** Gets the single {@link Element} within {@param elements}. */
    public static Element getSingleElement(List<Element> elements) {
        if (elements.size() != 1) {
            throw new IllegalStateException(
                    String.format("Expected 1 element in list but got %s.", elements.size()));
        }
        return elements.get(0);
    }

    /** Converts {@param nodeList} into List of {@link Element}. */
    public static List<Element> asElementList(NodeList nodeList) {
        List<Element> elementList = new ArrayList<Element>();
        for (int i = 0; i < nodeList.getLength(); i++) {
            var elementAsNode = nodeList.item(i);
            if (elementAsNode instanceof Element) {
                elementList.add(((Element) elementAsNode));
            }
        }
        return elementList;
    }

    /** Appends {@param children} to the {@param ele}. */
    public static void appendChildren(Element ele, List<Element> children) {
        for (Element c : children) {
            ele.appendChild(c);
        }
    }

    /** Gets the Boolean from the String value. */
    private static Boolean fromString(String s) {
        if (s == null) {
            return null;
        }
        if (s.equals(TRUE_STR)) {
            return true;
        } else if (s.equals(FALSE_STR)) {
            return false;
        }
        return null;
    }

    /** Creates an on-device PBundle DOM Element with the given attribute name. */
    public static Element createPbundleEleWithName(Document doc, String name) {
        var ele = doc.createElement(XmlUtils.OD_TAG_PBUNDLE_AS_MAP);
        ele.setAttribute(XmlUtils.OD_ATTR_NAME, name);
        return ele;
    }

    /** Create an on-device Boolean DOM Element with the given attribute name. */
    public static Element createOdBooleanEle(Document doc, String name, boolean b) {
        var ele = doc.createElement(XmlUtils.OD_TAG_BOOLEAN);
        ele.setAttribute(XmlUtils.OD_ATTR_NAME, name);
        ele.setAttribute(XmlUtils.OD_ATTR_VALUE, String.valueOf(b));
        return ele;
    }

    /** Sets human-readable bool attribute if non-null. */
    public static void maybeSetHrBoolAttr(Element ele, String attrName, Boolean b) {
        if (b != null) {
            ele.setAttribute(attrName, String.valueOf(b));
        }
    }

    /** Create an on-device Long DOM Element with the given attribute name. */
    public static Element createOdLongEle(Document doc, String name, long l) {
        var ele = doc.createElement(XmlUtils.OD_TAG_LONG);
        ele.setAttribute(XmlUtils.OD_ATTR_NAME, name);
        ele.setAttribute(XmlUtils.OD_ATTR_VALUE, String.valueOf(l));
        return ele;
    }

    /** Create an on-device Long DOM Element with the given attribute name. */
    public static Element createOdStringEle(Document doc, String name, String val) {
        var ele = doc.createElement(XmlUtils.OD_TAG_STRING);
        ele.setAttribute(XmlUtils.OD_ATTR_NAME, name);
        ele.setAttribute(XmlUtils.OD_ATTR_VALUE, val);
        return ele;
    }

    /** Create HR style array DOM Element. */
    public static Element createHrArray(Document doc, String arrayTagName, List<String> arrayVals) {
        Element arrEle = doc.createElement(arrayTagName);
        for (String s : arrayVals) {
            Element itemEle = doc.createElement(XmlUtils.HR_TAG_ITEM);
            itemEle.setTextContent(s);
            arrEle.appendChild(itemEle);
        }
        return arrEle;
    }

    /** Create OD style array DOM Element, which can represent any type but is stored as Strings. */
    public static Element createOdArray(
            Document doc, String arrayTag, String arrayName, List<String> arrayVals) {
        Element arrEle = doc.createElement(arrayTag);
        arrEle.setAttribute(XmlUtils.OD_ATTR_NAME, arrayName);
        arrEle.setAttribute(XmlUtils.OD_ATTR_NUM, String.valueOf(arrayVals.size()));
        for (String s : arrayVals) {
            Element itemEle = doc.createElement(XmlUtils.OD_TAG_ITEM);
            itemEle.setAttribute(XmlUtils.OD_ATTR_VALUE, s);
            arrEle.appendChild(itemEle);
        }
        return arrEle;
    }

    /** Returns whether the String is null or empty. */
    public static boolean isNullOrEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /** Tries getting required version attribute and throws exception if it doesn't exist */
    public static Long tryGetVersion(Element ele) throws MalformedXmlException {
        long version;
        try {
            version = Long.parseLong(ele.getAttribute(XmlUtils.HR_ATTR_VERSION));
        } catch (Exception e) {
            throw new MalformedXmlException(
                    String.format(
                            "Malformed or missing required version in: %s", ele.getTagName()));
        }
        return version;
    }

    /** Gets a pipeline-split attribute. */
    public static List<String> getPipelineSplitAttr(
            Element ele, String attrName, Set<String> requiredNames) throws MalformedXmlException {
        return getPipelineSplitAttr(ele, attrName, requiredNames.contains(attrName));
    }

    /** Gets a pipeline-split attribute. */
    public static List<String> getPipelineSplitAttr(Element ele, String attrName, boolean required)
            throws MalformedXmlException {
        List<String> list =
                Arrays.stream(ele.getAttribute(attrName).split("\\|")).collect(Collectors.toList());
        if ((list.isEmpty() || list.get(0).isEmpty())) {
            if (required) {
                throw new MalformedXmlException(
                        String.format(
                                "Delimited string %s was required but missing, in %s.",
                                attrName, ele.getTagName()));
            }
            return null;
        }
        return list;
    }

    /**
     * Gets the single {@link Element} within {@param parentEle} and having the {@param tagName}.
     */
    public static Boolean getBoolAttr(Element ele, String attrName, Set<String> requiredStrings)
            throws MalformedXmlException {
        return getBoolAttr(ele, attrName, requiredStrings.contains(attrName));
    }

    /** Gets a Boolean attribute. */
    public static Boolean getBoolAttr(Element ele, String attrName, boolean required)
            throws MalformedXmlException {
        Boolean b = XmlUtils.fromString(ele.getAttribute(attrName));
        if (b == null && required) {
            throw new MalformedXmlException(
                    String.format(
                            "Boolean %s was required but missing, in %s.",
                            attrName, ele.getTagName()));
        }
        return b;
    }

    /** Gets a Boolean attribute. */
    public static Boolean getOdBoolEle(Element ele, String nameName, Set<String> requiredNames)
            throws MalformedXmlException {
        return getOdBoolEle(ele, nameName, requiredNames.contains(nameName));
    }

    /** Gets a Boolean attribute. */
    public static Boolean getOdBoolEle(Element ele, String nameName, boolean required)
            throws MalformedXmlException {
        List<Element> boolEles =
                XmlUtils.getChildrenByTagName(ele, XmlUtils.OD_TAG_BOOLEAN).stream()
                        .filter(e -> e.getAttribute(XmlUtils.OD_ATTR_NAME).equals(nameName))
                        .collect(Collectors.toList());
        if (boolEles.size() > 1) {
            throw new MalformedXmlException(
                    String.format(
                            "Found more than one boolean %s in %s.", nameName, ele.getTagName()));
        }
        if (boolEles.isEmpty()) {
            if (required) {
                throw new MalformedXmlException(
                        String.format("Found no boolean %s in %s.", nameName, ele.getTagName()));
            }
            return null;
        }
        Element boolEle = boolEles.get(0);

        Boolean b = XmlUtils.fromString(boolEle.getAttribute(XmlUtils.OD_ATTR_VALUE));
        if (b == null && required) {
            throw new MalformedXmlException(
                    String.format(
                            "Boolean %s was required but missing, in %s.",
                            nameName, ele.getTagName()));
        }
        return b;
    }

    /** Gets an on-device Long attribute. */
    public static Long getOdLongEle(Element ele, String nameName, boolean required)
            throws MalformedXmlException {
        List<Element> longEles =
                XmlUtils.getChildrenByTagName(ele, XmlUtils.OD_TAG_LONG).stream()
                        .filter(e -> e.getAttribute(XmlUtils.OD_ATTR_NAME).equals(nameName))
                        .collect(Collectors.toList());
        if (longEles.size() > 1) {
            throw new MalformedXmlException(
                    String.format(
                            "Found more than one long %s in %s.", nameName, ele.getTagName()));
        }
        if (longEles.isEmpty()) {
            if (required) {
                throw new MalformedXmlException(
                        String.format("Found no long %s in %s.", nameName, ele.getTagName()));
            }
            return null;
        }
        Element longEle = longEles.get(0);
        Long l = null;
        try {
            l = Long.parseLong(longEle.getAttribute(XmlUtils.OD_ATTR_VALUE));
        } catch (NumberFormatException e) {
            throw new MalformedXmlException(
                    String.format(
                            "%s in %s was not formatted as long", nameName, ele.getTagName()));
        }
        return l;
    }

    /** Gets an on-device String attribute. */
    public static String getOdStringEle(Element ele, String nameName, Set<String> requiredNames)
            throws MalformedXmlException {
        return getOdStringEle(ele, nameName, requiredNames.contains(nameName));
    }

    /** Gets an on-device String attribute. */
    public static String getOdStringEle(Element ele, String nameName, boolean required)
            throws MalformedXmlException {
        List<Element> eles =
                XmlUtils.getChildrenByTagName(ele, XmlUtils.OD_TAG_STRING).stream()
                        .filter(e -> e.getAttribute(XmlUtils.OD_ATTR_NAME).equals(nameName))
                        .collect(Collectors.toList());
        if (eles.size() > 1) {
            throw new MalformedXmlException(
                    String.format(
                            "Found more than one string %s in %s.", nameName, ele.getTagName()));
        }
        if (eles.isEmpty()) {
            if (required) {
                throw new MalformedXmlException(
                        String.format("Found no string %s in %s.", nameName, ele.getTagName()));
            }
            return null;
        }
        String str = eles.get(0).getAttribute(XmlUtils.OD_ATTR_VALUE);
        if (XmlUtils.isNullOrEmpty(str) && required) {
            throw new MalformedXmlException(
                    String.format(
                            "%s in %s was empty or missing value", nameName, ele.getTagName()));
        }
        return str;
    }

    /** Gets a OD Pbundle Element attribute with the specified name. */
    public static Element getOdPbundleWithName(
            Element ele, String nameName, Set<String> requiredStrings)
            throws MalformedXmlException {
        return getOdPbundleWithName(ele, nameName, requiredStrings.contains(nameName));
    }

    /** Gets a OD Pbundle Element attribute with the specified name. */
    public static Element getOdPbundleWithName(Element ele, String nameName, boolean required)
            throws MalformedXmlException {
        List<Element> eles =
                XmlUtils.getChildrenByTagName(ele, XmlUtils.OD_TAG_PBUNDLE_AS_MAP).stream()
                        .filter(e -> e.getAttribute(XmlUtils.OD_ATTR_NAME).equals(nameName))
                        .collect(Collectors.toList());
        if (eles.size() > 1) {
            throw new MalformedXmlException(
                    String.format(
                            "Found more than one pbundle %s in %s.", nameName, ele.getTagName()));
        }
        if (eles.isEmpty()) {
            if (required) {
                throw new MalformedXmlException(
                        String.format("Found no pbundle %s in %s.", nameName, ele.getTagName()));
            }
            return null;
        }
        return eles.get(0);
    }

    /**
     * Gets the single {@link Element} within {@param parentEle} and having the {@param tagName}.
     */
    public static String getStringAttr(Element ele, String attrName, Set<String> requiredStrings)
            throws MalformedXmlException {
        return getStringAttr(ele, attrName, requiredStrings.contains(attrName));
    }

    /** Gets a required String attribute. */
    public static String getStringAttr(Element ele, String attrName) throws MalformedXmlException {
        return getStringAttr(ele, attrName, true);
    }

    /** Gets a String attribute; throws exception if required and non-existent. */
    public static String getStringAttr(Element ele, String attrName, boolean required)
            throws MalformedXmlException {
        String s = ele.getAttribute(attrName);
        if (isNullOrEmpty(s)) {
            if (required) {
                throw new MalformedXmlException(
                        String.format(
                                "Malformed or missing required %s in: %s",
                                attrName, ele.getTagName()));
            } else {
                return null;
            }
        }
        return s;
    }

    /** Gets on-device style int array. */
    public static List<Integer> getOdIntArray(Element ele, String nameName, boolean required)
            throws MalformedXmlException {
        List<Element> intArrayEles =
                XmlUtils.getChildrenByTagName(ele, XmlUtils.OD_TAG_INT_ARRAY).stream()
                        .filter(e -> e.getAttribute(XmlUtils.OD_ATTR_NAME).equals(nameName))
                        .collect(Collectors.toList());
        if (intArrayEles.size() > 1) {
            throw new MalformedXmlException(
                    String.format("Found more than one %s in %s.", nameName, ele.getTagName()));
        }
        if (intArrayEles.isEmpty()) {
            if (required) {
                throw new MalformedXmlException(
                        String.format("Found no %s in %s.", nameName, ele.getTagName()));
            }
            return null;
        }
        Element intArrayEle = intArrayEles.get(0);
        List<Element> itemEles = XmlUtils.getChildrenByTagName(intArrayEle, XmlUtils.OD_TAG_ITEM);
        List<Integer> ints = new ArrayList<Integer>();
        for (Element itemEle : itemEles) {
            ints.add(Integer.parseInt(XmlUtils.getStringAttr(itemEle, XmlUtils.OD_ATTR_VALUE)));
        }
        return ints;
    }

    /** Gets human-readable style String array. */
    public static List<String> getHrItemsAsStrings(
            Element parent, String elementName, Set<String> requiredNames)
            throws MalformedXmlException {
        return getHrItemsAsStrings(parent, elementName, requiredNames.contains(elementName));
    }

    /** Gets human-readable style String array. */
    public static List<String> getHrItemsAsStrings(
            Element parent, String elementName, boolean required) throws MalformedXmlException {

        List<Element> arrayEles = XmlUtils.getChildrenByTagName(parent, elementName);
        if (arrayEles.size() > 1) {
            throw new MalformedXmlException(
                    String.format(
                            "Found more than one %s in %s.", elementName, parent.getTagName()));
        }
        if (arrayEles.isEmpty()) {
            if (required) {
                throw new MalformedXmlException(
                        String.format("Found no %s in %s.", elementName, parent.getTagName()));
            }
            return null;
        }
        Element arrayEle = arrayEles.get(0);
        List<Element> itemEles = XmlUtils.getChildrenByTagName(arrayEle, XmlUtils.HR_TAG_ITEM);
        List<String> strs = new ArrayList<String>();
        for (Element itemEle : itemEles) {
            strs.add(itemEle.getTextContent());
        }
        return strs;
    }

    /** Gets on-device style String array. */
    public static List<String> getOdStringArray(
            Element ele, String nameName, Set<String> requiredNames) throws MalformedXmlException {
        return getOdStringArray(ele, nameName, requiredNames.contains(nameName));
    }

    /** Gets on-device style String array. */
    public static List<String> getOdStringArray(Element ele, String nameName, boolean required)
            throws MalformedXmlException {
        List<Element> arrayEles =
                XmlUtils.getChildrenByTagName(ele, XmlUtils.OD_TAG_STRING_ARRAY).stream()
                        .filter(e -> e.getAttribute(XmlUtils.OD_ATTR_NAME).equals(nameName))
                        .collect(Collectors.toList());
        if (arrayEles.size() > 1) {
            throw new MalformedXmlException(
                    String.format(
                            "Found more than one string array %s in %s.",
                            nameName, ele.getTagName()));
        }
        if (arrayEles.isEmpty()) {
            if (required) {
                throw new MalformedXmlException(
                        String.format(
                                "Found no string array %s in %s.", nameName, ele.getTagName()));
            }
            return null;
        }
        Element arrayEle = arrayEles.get(0);
        List<Element> itemEles = XmlUtils.getChildrenByTagName(arrayEle, XmlUtils.OD_TAG_ITEM);
        List<String> strs = new ArrayList<String>();
        for (Element itemEle : itemEles) {
            strs.add(XmlUtils.getStringAttr(itemEle, XmlUtils.OD_ATTR_VALUE, true));
        }
        return strs;
    }

    /** Throws if extraneous child elements detected */
    public static void throwIfExtraneousChildrenHr(Element ele, Set<String> expectedChildNames)
            throws MalformedXmlException {
        var childEles = XmlUtils.asElementList(ele.getChildNodes());
        List<Element> extraneousEles =
                childEles.stream()
                        .filter(e -> !expectedChildNames.contains(e.getTagName()))
                        .collect(Collectors.toList());
        if (!extraneousEles.isEmpty()) {
            throw new MalformedXmlException(
                    String.format(
                            "Unexpected element(s) %s in %s.",
                            extraneousEles.stream()
                                    .map(Element::getTagName)
                                    .collect(Collectors.joining(",")),
                            ele.getTagName()));
        }
    }

    /** Throws if extraneous child elements detected */
    public static void throwIfExtraneousChildrenOd(Element ele, Set<String> expectedChildNames)
            throws MalformedXmlException {
        var allChildElements = XmlUtils.asElementList(ele.getChildNodes());
        List<Element> extraneousEles =
                allChildElements.stream()
                        .filter(
                                e ->
                                        !e.getAttribute(XmlUtils.OD_ATTR_NAME).isEmpty()
                                                && !expectedChildNames.contains(
                                                        e.getAttribute(XmlUtils.OD_ATTR_NAME)))
                        .collect(Collectors.toList());
        if (!extraneousEles.isEmpty()) {
            throw new MalformedXmlException(
                    String.format(
                            "Unexpected element(s) in %s: %s",
                            ele.getTagName(),
                            extraneousEles.stream()
                                    .map(
                                            e ->
                                                    String.format(
                                                            "%s name=%s",
                                                            e.getTagName(),
                                                            e.getAttribute(XmlUtils.OD_ATTR_NAME)))
                                    .collect(Collectors.joining(","))));
        }
    }

    /** Throws if extraneous attributes detected */
    public static void throwIfExtraneousAttributes(Element ele, Set<String> expectedAttrNames)
            throws MalformedXmlException {
        var attrs = ele.getAttributes();
        List<String> attrNames = new ArrayList<>();
        for (int i = 0; i < attrs.getLength(); i++) {
            attrNames.add(attrs.item(i).getNodeName());
        }
        List<String> extraneousAttrs =
                attrNames.stream()
                        .filter(s -> !expectedAttrNames.contains(s))
                        .collect(Collectors.toList());
        if (!extraneousAttrs.isEmpty()) {
            throw new MalformedXmlException(
                    String.format(
                            "Unexpected attr(s) %s in %s.",
                            String.join(",", extraneousAttrs), ele.getTagName()));
        }
    }

    /**
     * Utility method for making a List from one element, to support easier refactoring if needed.
     * For example, List.of() doesn't support null elements.
     */
    public static List<Element> listOf(Element e) {
        return Arrays.asList(e);
    }

    /**
     * Gets the most recent version of fields in the mapping. This way when a new version is
     * released, we only need to update the mappings that were modified. The rest will fall back to
     * the most recent previous version.
     */
    public static Set<String> getMostRecentVersion(
            Map<Long, Set<String>> versionToFieldsMapping, long version)
            throws MalformedXmlException {
        long bestVersion = 0;
        Set<String> bestSet = null;
        for (Map.Entry<Long, Set<String>> entry : versionToFieldsMapping.entrySet()) {
            if (entry.getKey() > bestVersion && entry.getKey() <= version) {
                bestVersion = entry.getKey();
                bestSet = entry.getValue();
            }
        }
        if (bestSet == null) {
            throw new MalformedXmlException("Unexpected version: " + version);
        }
        return bestSet;
    }
}
