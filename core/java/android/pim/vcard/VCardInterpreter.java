/*
 * Copyright (C) 2009 The Android Open Source Project
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
package android.pim.vcard;

import java.util.List;

/**
 * <P>
 * The interface which should be implemented by the classes which have to analyze each
 * vCard entry more minutely than {@link VCardEntry} class analysis.
 * </P>
 * <P>
 * Here, there are several terms specific to vCard (and this library).
 * </P>
 * <P>
 * The term "entry" is one vCard representation in the input, which should start with "BEGIN:VCARD"
 * and end with "END:VCARD".
 * </P>
 * <P>
 * The term "property" is one line in vCard entry, which consists of "group", "property name",
 * "parameter(param) names and values", and "property values".
 * </P>
 * <P>
 * e.g. group1.propName;paramName1=paramValue1;paramName2=paramValue2;propertyValue1;propertyValue2...
 * </P>
 */
public interface VCardInterpreter {
    /**
     * Called when vCard interpretation started.
     */
    void start();

    /**
     * Called when vCard interpretation finished.
     */
    void end();

    /** 
     * Called when parsing one vCard entry started.
     * More specifically, this method is called when "BEGIN:VCARD" is read.
     */
    void startEntry();

    /**
     * Called when parsing one vCard entry ended.
     * More specifically, this method is called when "END:VCARD" is read.
     * Note that {@link #startEntry()} may be called since
     * vCard (especially 2.1) allows nested vCard.
     */
    void endEntry();

    /**
     * Called when reading one property started.
     */
    void startProperty();

    /**
     * Called when reading one property ended.
     */
    void endProperty();

    /**
     * @param group A group name. This method may be called more than once or may not be
     * called at all, depending on how many gruoups are appended to the property.
     */
    void propertyGroup(String group);

    /**
     * @param name A property name like "N", "FN", "ADR", etc.
     */
    void propertyName(String name);

    /**
     * @param type A parameter name like "ENCODING", "CHARSET", etc.
     */
    void propertyParamType(String type);

    /**
     * @param value A parameter value. This method may be called without
     * {@link #propertyParamType(String)} being called (when the vCard is vCard 2.1).
     */
    void propertyParamValue(String value);

    /**
     * @param values List of property values. The size of values would be 1 unless
     * coressponding property name is "N", "ADR", or "ORG".
     */
    void propertyValues(List<String> values);
}
