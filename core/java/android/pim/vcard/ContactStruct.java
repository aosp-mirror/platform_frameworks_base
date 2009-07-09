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

import android.content.AbstractSyncableContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.net.Uri;
import android.provider.Contacts;
import android.provider.Contacts.ContactMethods;
import android.provider.Contacts.Extensions;
import android.provider.Contacts.GroupMembership;
import android.provider.Contacts.Organizations;
import android.provider.Contacts.People;
import android.provider.Contacts.Phones;
import android.provider.Contacts.Photos;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

/**
 * This class bridges between data structure of Contact app and VCard data.
 */
public class ContactStruct {
    private static final String LOG_TAG = "ContactStruct";
    
    /**
     * @hide only for testing
     */
    static public class PhoneData {
        public final int type;
        public final String data;
        public final String label;
        // isPrimary is changable only when there's no appropriate one existing in
        // the original VCard.
        public boolean isPrimary;
        public PhoneData(int type, String data, String label, boolean isPrimary) {
            this.type = type;
            this.data = data;
            this.label = label;
            this.isPrimary = isPrimary;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof PhoneData) {
                return false;
            }
            PhoneData phoneData = (PhoneData)obj;
            return (type == phoneData.type && data.equals(phoneData.data) &&
                    label.equals(phoneData.label) && isPrimary == phoneData.isPrimary);
        }
        
        @Override
        public String toString() {
            return String.format("type: %d, data: %s, label: %s, isPrimary: %s",
                    type, data, label, isPrimary);
        }
    }

    /**
     * @hide only for testing
     */
    static public class ContactMethod {
        // Contacts.KIND_EMAIL, Contacts.KIND_POSTAL
        public final int kind;
        // e.g. Contacts.ContactMethods.TYPE_HOME, Contacts.PhoneColumns.TYPE_HOME
        // If type == Contacts.PhoneColumns.TYPE_CUSTOM, label is used.
        public final int type;
        public final String data;
        // Used only when TYPE is TYPE_CUSTOM.
        public final String label;
        // isPrimary is changable only when there's no appropriate one existing in
        // the original VCard.
        public boolean isPrimary;
        public ContactMethod(int kind, int type, String data, String label,
                boolean isPrimary) {
            this.kind = kind;
            this.type = type;
            this.data = data;
            this.label = data;
            this.isPrimary = isPrimary;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ContactMethod) {
                return false;
            }
            ContactMethod contactMethod = (ContactMethod)obj;
            return (kind == contactMethod.kind && type == contactMethod.type &&
                    data.equals(contactMethod.data) && label.equals(contactMethod.label) &&
                    isPrimary == contactMethod.isPrimary);
        }
        
        @Override
        public String toString() {
            return String.format("kind: %d, type: %d, data: %s, label: %s, isPrimary: %s",
                    kind, type, data, label, isPrimary);
        }
    }
    
    /**
     * @hide only for testing
     */
    static public class OrganizationData {
        public final int type;
        public final String companyName;
        // can be changed in some VCard format. 
        public String positionName;
        // isPrimary is changable only when there's no appropriate one existing in
        // the original VCard.
        public boolean isPrimary;
        public OrganizationData(int type, String companyName, String positionName,
                boolean isPrimary) {
            this.type = type;
            this.companyName = companyName;
            this.positionName = positionName;
            this.isPrimary = isPrimary;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof OrganizationData) {
                return false;
            }
            OrganizationData organization = (OrganizationData)obj;
            return (type == organization.type && companyName.equals(organization.companyName) &&
                    positionName.equals(organization.positionName) &&
                    isPrimary == organization.isPrimary);
        }
        
        @Override
        public String toString() {
            return String.format("type: %d, company: %s, position: %s, isPrimary: %s",
                    type, companyName, positionName, isPrimary);
        }
    }
    
    static class Property {
        private String mPropertyName;
        private Map<String, Collection<String>> mParameterMap =
            new HashMap<String, Collection<String>>();
        private List<String> mPropertyValueList = new ArrayList<String>();
        private byte[] mPropertyBytes;
        
        public Property() {
            clear();
        }
        
        public void setPropertyName(final String propertyName) {
            mPropertyName = propertyName;
        }
        
        public void addParameter(final String paramName, final String paramValue) {
            Collection<String> values;
            if (mParameterMap.containsKey(paramName)) {
                if (paramName.equals("TYPE")) {
                    values = new HashSet<String>();
                } else {
                    values = new ArrayList<String>();
                }
                mParameterMap.put(paramName, values);
            } else {
                values = mParameterMap.get(paramName);
            }
        }
        
        public void addToPropertyValueList(final String propertyValue) {
            mPropertyValueList.add(propertyValue);
        }
        
        public void setPropertyBytes(final byte[] propertyBytes) {
            mPropertyBytes = propertyBytes;
        }

        public final Collection<String> getParameters(String type) {
            return mParameterMap.get(type);
        }
        
        public final List<String> getPropertyValueList() {
            return mPropertyValueList;
        }
        
        public void clear() {
            mPropertyName = null;
            mParameterMap.clear();
            mPropertyValueList.clear();
        }
    }
    
    private String mName;
    private String mPhoneticName;
    // private String mPhotoType;
    private byte[] mPhotoBytes;
    private List<String> mNotes;
    private List<PhoneData> mPhoneList;
    private List<ContactMethod> mContactMethodList;
    private List<OrganizationData> mOrganizationList;
    private Map<String, List<String>> mExtensionMap;

    private int mNameOrderType;
    
    /* private variables bellow is for temporary use. */
    
    // For name, there are three fields in vCard: FN, N, NAME.
    // We prefer FN, which is a required field in vCard 3.0 , but not in vCard 2.1.
    // Next, we prefer NAME, which is defined only in vCard 3.0.
    // Finally, we use N, which is a little difficult to parse.
    private String mTmpFullName;
    private String mTmpNameFromNProperty;

    // Some vCard has "X-PHONETIC-FIRST-NAME", "X-PHONETIC-MIDDLE-NAME", and
    // "X-PHONETIC-LAST-NAME"
    private String mTmpXPhoneticFirstName;
    private String mTmpXPhoneticMiddleName;
    private String mTmpXPhoneticLastName;
    
    // Each Column of four properties has ISPRIMARY field
    // (See android.provider.Contacts)
    // If false even after the following loop, we choose the first
    // entry as a "primary" entry.
    private boolean mPrefIsSet_Address;
    private boolean mPrefIsSet_Phone;
    private boolean mPrefIsSet_Email;
    private boolean mPrefIsSet_Organization;

    public ContactStruct() {
        mNameOrderType = VCardConfig.NAME_ORDER_TYPE_DEFAULT;
    }
    
    public ContactStruct(int nameOrderType) {
        mNameOrderType = nameOrderType; 
    }
    
    /**
     * @hide only for test
     */
    public ContactStruct(String name,
            String phoneticName,
            byte[] photoBytes,
            List<String> notes,
            List<PhoneData> phoneList, 
            List<ContactMethod> contactMethodList,
            List<OrganizationData> organizationList,
            Map<String, List<String>> extensionMap) {
        mName = name;
        mPhoneticName = phoneticName;
        mPhotoBytes = photoBytes;
        mContactMethodList = contactMethodList;
        mOrganizationList = organizationList;
        mExtensionMap = extensionMap;
    }
    
    /**
     * @hide only for test
     */
    public String getName() {
        return mName;
    }
    
    /**
     * @hide only for test
     */
    public String getPhoneticName() {
        return mPhoneticName;
    }

    /**
     * @hide only for test
     */
    public final byte[] getPhotoBytes() {
        return mPhotoBytes;
    }
    
    /**
     * @hide only for test
     */
    public final List<String> getNotes() {
        return mNotes;
    }
    
    /**
     * @hide only for test
     */
    public final List<PhoneData> getPhoneList() {
        return mPhoneList;
    }
    
    /**
     * @hide only for test
     */
    public final List<ContactMethod> getContactMethodList() {
        return mContactMethodList;
    }
    
    /**
     * @hide only for test
     */
    public final List<OrganizationData> getOrganizationList() {
        return mOrganizationList;
    }

    /**
     * @hide only for test
     */
    public final Map<String, List<String>> getExtensionMap() {
        return mExtensionMap;
    }
    
    /**
     * Add a phone info to phoneList.
     * @param data phone number
     * @param type type col of content://contacts/phones
     * @param label lable col of content://contacts/phones
     */
    private void addPhone(int type, String data, String label, boolean isPrimary){
        if (mPhoneList == null) {
            mPhoneList = new ArrayList<PhoneData>();
        }
        StringBuilder builder = new StringBuilder();
        String trimed = data.trim();
        int length = trimed.length();
        for (int i = 0; i < length; i++) {
            char ch = trimed.charAt(i);
            if (('0' <= ch && ch <= '9') || (i == 0 && ch == '+')) {
                builder.append(ch);
            }
        }

        PhoneData phoneData = new PhoneData(type,
                PhoneNumberUtils.formatNumber(builder.toString()),
                label, isPrimary);

        mPhoneList.add(phoneData);
    }

    /**
     * Add a contactmethod info to contactmethodList.
     * @param kind integer value defined in Contacts.java
     * (e.g. Contacts.KIND_EMAIL)
     * @param type type col of content://contacts/contact_methods
     * @param data contact data
     * @param label extra string used only when kind is Contacts.KIND_CUSTOM.
     */
    private void addContactmethod(int kind, int type, String data,
            String label, boolean isPrimary){
        if (mContactMethodList == null) {
            mContactMethodList = new ArrayList<ContactMethod>();
        }
        mContactMethodList.add(new ContactMethod(kind, type, data, label, isPrimary));
    }
    
    /**
     * Add a Organization info to organizationList.
     */
    private void addOrganization(int type, String companyName, String positionName,
            boolean isPrimary) {
        if (mOrganizationList == null) {
            mOrganizationList = new ArrayList<OrganizationData>();
        }
        mOrganizationList.add(new OrganizationData(type, companyName, positionName, isPrimary));
    }

    /**
     * Set "position" value to the appropriate data. If there's more than one
     * OrganizationData objects, the value is set to the last one. If there's no
     * OrganizationData object, a new OrganizationData is created, whose company name is
     * empty.  
     * 
     * TODO: incomplete logic. fix this:
     * 
     * e.g. This assumes ORG comes earlier, but TITLE may come earlier like this, though we do not
     * know how to handle it in general cases...
     * ----
     * TITLE:Software Engineer
     * ORG:Google
     * ----
     */
    private void setPosition(String positionValue) {
        if (mOrganizationList == null) {
            mOrganizationList = new ArrayList<OrganizationData>();
        }
        int size = mOrganizationList.size();
        if (size == 0) {
            addOrganization(Contacts.OrganizationColumns.TYPE_OTHER, "", null, false);
            size = 1;
        }
        OrganizationData lastData = mOrganizationList.get(size - 1);
        lastData.positionName = positionValue;
    }
 
    private void addExtension(String propName, Map<String, Collection<String>> paramMap,
            List<String> propValueList) {
        if (propValueList.size() == 0) {
            return;
        }
        // Now store the string into extensionMap.
        List<String> list;
        if (mExtensionMap == null) {
            mExtensionMap = new HashMap<String, List<String>>();
        }
        if (!mExtensionMap.containsKey(propName)){
            list = new ArrayList<String>();
            mExtensionMap.put(propName, list);
        } else {
            list = mExtensionMap.get(propName);
        }        
        
        list.add(encodeProperty(propName, paramMap, propValueList));
    }

    private String encodeProperty(String propName, Map<String, Collection<String>> paramMap,
            List<String> propValueList) {
        // PropertyNode#toString() is for reading, not for parsing in the future.
        // We construct appropriate String here.
        StringBuilder builder = new StringBuilder();
        if (propName.length() > 0) {
            builder.append("propName:[");
            builder.append(propName);
            builder.append("],");
        }

        if (paramMap.size() > 0) {
            builder.append("paramMap:[");
            int size = paramMap.size(); 
            int i = 0;
            for (Map.Entry<String, Collection<String>> entry : paramMap.entrySet()) {
                String key = entry.getKey();
                for (String value : entry.getValue()) {
                    // Assuming param-key does not contain NON-ASCII nor symbols.
                    // TODO: check it.
                    //
                    // According to vCard 3.0:
                    // param-name   = iana-token / x-name
                    builder.append(key);

                    // param-value may contain any value including NON-ASCIIs.
                    // We use the following replacing rule.
                    // \ -> \\
                    // , -> \,
                    // In String#replaceAll(), "\\\\" means a single backslash.
                    builder.append("=");

                    // TODO: fix this.
                    builder.append(value.replaceAll("\\\\", "\\\\\\\\").replaceAll(",", "\\\\,"));
                    if (i < size -1) {
                        builder.append(",");
                    }
                    i++;
                }
            }

            builder.append("],");
        }

        int size = propValueList.size();
        if (size > 0) {
            builder.append("propValue:[");
            List<String> list = propValueList;
            for (int i = 0; i < size; i++) {
                // TODO: fix this.
                builder.append(list.get(i).replaceAll("\\\\", "\\\\\\\\").replaceAll(",", "\\\\,"));
                if (i < size -1) {
                    builder.append(",");
                }
            }
            builder.append("],");
        }

        return builder.toString();
    }
    
    private static String getNameFromNProperty(List<String> elems, int nameOrderType) {
        // Family, Given, Middle, Prefix, Suffix. (1 - 5)
        int size = elems.size();
        if (size > 1) {
            StringBuilder builder = new StringBuilder();
            boolean builderIsEmpty = true;
            // Prefix
            if (size > 3 && elems.get(3).length() > 0) {
                builder.append(elems.get(3));
                builderIsEmpty = false;
            }
            String first, second;
            if (nameOrderType == VCardConfig.NAME_ORDER_TYPE_JAPANESE) {
                first = elems.get(0);
                second = elems.get(1);
            } else {
                first = elems.get(1);
                second = elems.get(0);
            }
            if (first.length() > 0) {
                if (!builderIsEmpty) {
                    builder.append(' ');
                }
                builder.append(first);
                builderIsEmpty = false;
            }
            // Middle name
            if (size > 2 && elems.get(2).length() > 0) {
                if (!builderIsEmpty) {
                    builder.append(' ');
                }
                builder.append(elems.get(2));
                builderIsEmpty = false;
            }
            if (second.length() > 0) {
                if (!builderIsEmpty) {
                    builder.append(' ');
                }
                builder.append(second);
                builderIsEmpty = false;
            }
            // Suffix
            if (size > 4 && elems.get(4).length() > 0) {
                if (!builderIsEmpty) {
                    builder.append(' ');
                }
                builder.append(elems.get(4));
                builderIsEmpty = false;
            }
            return builder.toString();
        } else if (size == 1) {
            return elems.get(0);
        } else {
            return "";
        }
    }

    public void addProperty(Property property) {
        String propName = property.mPropertyName;
        final Map<String, Collection<String>> paramMap = property.mParameterMap;
        final List<String> propValueList = property.mPropertyValueList;
        byte[] propBytes = property.mPropertyBytes;
        
        if (propValueList.size() == 0) {
            return;
        }

        String propValue = listToString(propValueList);

        if (propName.equals("VERSION")) {
            // vCard version. Ignore this.
        } else if (propName.equals("FN")) {
            mTmpFullName = propValue;
        } else if (propName.equals("NAME") && mTmpFullName == null) {
            // Only in vCard 3.0. Use this if FN does not exist.
            // Though, note that vCard 3.0 requires FN.
            mTmpFullName = propValue;
        } else if (propName.equals("N")) {
            mTmpNameFromNProperty = getNameFromNProperty(propValueList, mNameOrderType);
        } else if (propName.equals("SORT-STRING")) {
            mPhoneticName = propValue;
        } else if (propName.equals("SOUND")) {
            if ("X-IRMC-N".equals(paramMap.get("TYPE")) && mPhoneticName == null) {
                // Some Japanese mobile phones use this field for phonetic name,
                // since vCard 2.1 does not have "SORT-STRING" type.
                // Also, in some cases, the field has some ';'s in it.
                // We remove them.
                StringBuilder builder = new StringBuilder();
                String value = propValue;
                int length = value.length();
                for (int i = 0; i < length; i++) {
                    char ch = value.charAt(i);
                    if (ch != ';') {
                        builder.append(ch);
                    }
                }
                if (builder.length() > 0) {
                    mPhoneticName = builder.toString();
                }
            } else {
                addExtension(propName, paramMap, propValueList);
            }
        } else if (propName.equals("ADR")) {
            boolean valuesAreAllEmpty = true;
            for (String value : propValueList) {
                if (value.length() > 0) {
                    valuesAreAllEmpty = false;
                    break;
                }
            }
            if (valuesAreAllEmpty) {
                return;
            }

            int kind = Contacts.KIND_POSTAL;
            int type = -1;
            String label = "";
            boolean isPrimary = false;
            Collection<String> typeCollection = paramMap.get("TYPE");
            if (typeCollection != null) {
                for (String typeString : typeCollection) {
                    if (typeString.equals("PREF") && !mPrefIsSet_Address) {
                        // Only first "PREF" is considered.
                        mPrefIsSet_Address = true;
                        isPrimary = true;
                    } else if (typeString.equalsIgnoreCase("HOME")) {
                        type = Contacts.ContactMethodsColumns.TYPE_HOME;
                        label = "";
                    } else if (typeString.equalsIgnoreCase("WORK") || 
                            typeString.equalsIgnoreCase("COMPANY")) {
                        // "COMPANY" seems emitted by Windows Mobile, which is not
                        // specifically supported by vCard 2.1. We assume this is same
                        // as "WORK".
                        type = Contacts.ContactMethodsColumns.TYPE_WORK;
                        label = "";
                    } else if (typeString.equalsIgnoreCase("POSTAL")) {
                        kind = Contacts.KIND_POSTAL;
                    } else if (typeString.equalsIgnoreCase("PARCEL") || 
                            typeString.equalsIgnoreCase("DOM") ||
                            typeString.equalsIgnoreCase("INTL")) {
                        // We do not have a kind or type matching these.
                        // TODO: fix this. We may need to split entries into two.
                        // (e.g. entries for KIND_POSTAL and KIND_PERCEL)
                    } else if (typeString.toUpperCase().startsWith("X-") &&
                            type < 0) {
                        type = Contacts.ContactMethodsColumns.TYPE_CUSTOM;
                        label = typeString.substring(2);
                    } else if (type < 0) {
                        // vCard 3.0 allows iana-token. Also some vCard 2.1 exporters
                        // emit non-standard types. We do not handle their values now.
                        type = Contacts.ContactMethodsColumns.TYPE_CUSTOM;
                        label = typeString;
                    }
                }
            }
            // We use "HOME" as default
            if (type < 0) {
                type = Contacts.ContactMethodsColumns.TYPE_HOME;
            }
                            
            // adr-value    = 0*6(text-value ";") text-value
            //              ; PO Box, Extended Address, Street, Locality, Region, Postal
            //              ; Code, Country Name
            String address;
            int size = propValueList.size();
            if (size > 1) {
                StringBuilder builder = new StringBuilder();
                boolean builderIsEmpty = true;
                if (Locale.getDefault().getCountry().equals(Locale.JAPAN.getCountry())) {
                    // In Japan, the order is reversed.
                    for (int i = size - 1; i >= 0; i--) {
                        String addressPart = propValueList.get(i);
                        if (addressPart.length() > 0) {
                            if (!builderIsEmpty) {
                                builder.append(' ');
                            }
                            builder.append(addressPart);
                            builderIsEmpty = false;
                        }
                    }
                } else {
                    for (int i = 0; i < size; i++) {
                        String addressPart = propValueList.get(i);
                        if (addressPart.length() > 0) {
                            if (!builderIsEmpty) {
                                builder.append(' ');
                            }
                            builder.append(addressPart);
                            builderIsEmpty = false;
                        }
                    }
                }
                address = builder.toString().trim();
            } else {
                address = propValue; 
            }
            addContactmethod(kind, type, address, label, isPrimary);
        } else if (propName.equals("ORG")) {
            // vCard specification does not specify other types.
            int type = Contacts.OrganizationColumns.TYPE_WORK;
            boolean isPrimary = false;
            
            Collection<String> typeCollection = paramMap.get("TYPE");
            if (typeCollection != null) {
                for (String typeString : typeCollection) {
                    if (typeString.equals("PREF") && !mPrefIsSet_Organization) {
                        // vCard specification officially does not have PREF in ORG.
                        // This is just for safety.
                        mPrefIsSet_Organization = true;
                        isPrimary = true;
                    }
                    // XXX: Should we cope with X- words?
                }
            }

            int size = propValueList.size();
            StringBuilder builder = new StringBuilder();
            for (Iterator<String> iter = propValueList.iterator(); iter.hasNext();) {
                builder.append(iter.next());
                if (iter.hasNext()) {
                    builder.append(' ');
                }
            }

            addOrganization(type, builder.toString(), "", isPrimary);
        } else if (propName.equals("TITLE")) {
            setPosition(propValue);
        } else if (propName.equals("ROLE")) {
            setPosition(propValue);
        } else if ((propName.equals("PHOTO") || (propName.equals("LOGO")) && mPhotoBytes == null)) {
            // We prefer PHOTO to LOGO.
            Collection<String> paramMapValue = paramMap.get("VALUE");
            if (paramMapValue != null && paramMapValue.contains("URL")) {
                // TODO: do something.
            } else {
                // Assume PHOTO is stored in BASE64. In that case,
                // data is already stored in propValue_bytes in binary form.
                // It should be automatically done by VBuilder (VDataBuilder/VCardDatabuilder) 
                mPhotoBytes = propBytes;
                /*
                Collection<String> typeCollection = paramMap.get("TYPE");
                if (typeCollection != null) {
                    if (typeCollection.size() > 1) {
                        StringBuilder builder = new StringBuilder();
                        int size = typeCollection.size(); 
                        int i = 0;
                        for (String type : typeCollection) {
                            builder.append(type);
                            if (i < size - 1) {
                                builder.append(',');
                            }
                            i++;
                        }
                        Log.w(LOG_TAG, "There is more than TYPE: " + builder.toString());
                    }
                    mPhotoType = typeCollection.iterator().next();
                }*/
            }
        } else if (propName.equals("EMAIL")) {
            int type = -1;
            String label = null;
            boolean isPrimary = false;
            Collection<String> typeCollection = paramMap.get("TYPE");
            if (typeCollection != null) {
                for (String typeString : typeCollection) {
                    if (typeString.equals("PREF") && !mPrefIsSet_Email) {
                        // Only first "PREF" is considered.
                        mPrefIsSet_Email = true;
                        isPrimary = true;
                    } else if (typeString.equalsIgnoreCase("HOME")) {
                        type = Contacts.ContactMethodsColumns.TYPE_HOME;
                    } else if (typeString.equalsIgnoreCase("WORK")) {
                        type = Contacts.ContactMethodsColumns.TYPE_WORK;
                    } else if (typeString.equalsIgnoreCase("CELL")) {
                        // We do not have Contacts.ContactMethodsColumns.TYPE_MOBILE yet.
                        type = Contacts.ContactMethodsColumns.TYPE_CUSTOM;
                        label = Contacts.ContactMethodsColumns.MOBILE_EMAIL_TYPE_NAME;
                    } else if (typeString.toUpperCase().startsWith("X-") &&
                            type < 0) {
                        type = Contacts.ContactMethodsColumns.TYPE_CUSTOM;
                        label = typeString.substring(2);
                    } else if (type < 0) {
                        // vCard 3.0 allows iana-token.
                        // We may have INTERNET (specified in vCard spec),
                        // SCHOOL, etc.
                        type = Contacts.ContactMethodsColumns.TYPE_CUSTOM;
                        label = typeString;
                    }
                }
            }
            if (type < 0) {
                type = Contacts.ContactMethodsColumns.TYPE_OTHER;
            }
            addContactmethod(Contacts.KIND_EMAIL, type, propValue,label, isPrimary);
        } else if (propName.equals("TEL")) {
            int type = -1;
            String label = null;
            boolean isPrimary = false;
            boolean isFax = false;
            Collection<String> typeCollection = paramMap.get("TYPE");
            if (typeCollection != null) {
                for (String typeString : typeCollection) {
                    if (typeString.equals("PREF") && !mPrefIsSet_Phone) {
                        // Only first "PREF" is considered.
                        mPrefIsSet_Phone = true;
                        isPrimary = true;
                    } else if (typeString.equalsIgnoreCase("HOME")) {
                        type = Contacts.PhonesColumns.TYPE_HOME;
                    } else if (typeString.equalsIgnoreCase("WORK")) {
                        type = Contacts.PhonesColumns.TYPE_WORK;
                    } else if (typeString.equalsIgnoreCase("CELL")) {
                        type = Contacts.PhonesColumns.TYPE_MOBILE;
                    } else if (typeString.equalsIgnoreCase("PAGER")) {
                        type = Contacts.PhonesColumns.TYPE_PAGER;
                    } else if (typeString.equalsIgnoreCase("FAX")) {
                        isFax = true;
                    } else if (typeString.equalsIgnoreCase("VOICE") ||
                            typeString.equalsIgnoreCase("MSG")) {
                        // Defined in vCard 3.0. Ignore these because they
                        // conflict with "HOME", "WORK", etc.
                        // XXX: do something?
                    } else if (typeString.toUpperCase().startsWith("X-") &&
                            type < 0) {
                        type = Contacts.PhonesColumns.TYPE_CUSTOM;
                        label = typeString.substring(2);
                    } else if (type < 0){
                        // We may have MODEM, CAR, ISDN, etc...
                        type = Contacts.PhonesColumns.TYPE_CUSTOM;
                        label = typeString;
                    }
                }
            }
            if (type < 0) {
                type = Contacts.PhonesColumns.TYPE_HOME;
            }
            if (isFax) {
                if (type == Contacts.PhonesColumns.TYPE_HOME) {
                    type = Contacts.PhonesColumns.TYPE_FAX_HOME; 
                } else if (type == Contacts.PhonesColumns.TYPE_WORK) {
                    type = Contacts.PhonesColumns.TYPE_FAX_WORK; 
                }
            }

            addPhone(type, propValue, label, isPrimary);
        } else if (propName.equals("NOTE")) {
            if (mNotes == null) {
                mNotes = new ArrayList<String>(1);
            }
            mNotes.add(propValue);
        } else if (propName.equals("BDAY")) {
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("URL")) {
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("REV")) {                
            // Revision of this VCard entry. I think we can ignore this.
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("UID")) {
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("KEY")) {
            // Type is X509 or PGP? I don't know how to handle this...
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("MAILER")) {
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("TZ")) {
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("GEO")) {
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("NICKNAME")) {
            // vCard 3.0 only.
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("CLASS")) {
            // vCard 3.0 only.
            // e.g. CLASS:CONFIDENTIAL
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("PROFILE")) {
            // VCard 3.0 only. Must be "VCARD". I think we can ignore this.
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("CATEGORIES")) {
            // VCard 3.0 only.
            // e.g. CATEGORIES:INTERNET,IETF,INDUSTRY,INFORMATION TECHNOLOGY
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("SOURCE")) {
            // VCard 3.0 only.
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("PRODID")) {
            // VCard 3.0 only.
            // To specify the identifier for the product that created
            // the vCard object.
            addExtension(propName, paramMap, propValueList);
        } else if (propName.equals("X-PHONETIC-FIRST-NAME")) {
            mTmpXPhoneticFirstName = propValue;
        } else if (propName.equals("X-PHONETIC-MIDDLE-NAME")) {
            mTmpXPhoneticMiddleName = propValue;
        } else if (propName.equals("X-PHONETIC-LAST-NAME")) {
            mTmpXPhoneticLastName = propValue;
        } else {
            // Unknown X- words and IANA token.
            addExtension(propName, paramMap, propValueList);
        }
    }
    
    public String displayString() {
        if (mName.length() > 0) {
            return mName;
        }
        if (mContactMethodList != null && mContactMethodList.size() > 0) {
            for (ContactMethod contactMethod : mContactMethodList) {
                if (contactMethod.kind == Contacts.KIND_EMAIL && contactMethod.isPrimary) {
                    return contactMethod.data;
                }
            }
        }
        if (mPhoneList != null && mPhoneList.size() > 0) {
            for (PhoneData phoneData : mPhoneList) {
                if (phoneData.isPrimary) {
                    return phoneData.data;
                }
            }
        }
        return "";
    }

    /**
     * Consolidate several fielsds (like mName) using name candidates, 
     */
    public void consolidateFields() {
        if (mTmpFullName != null) {
            mName = mTmpFullName;
        } else if(mTmpNameFromNProperty != null) {
            mName = mTmpNameFromNProperty;
        } else {
            mName = "";
        }

        if (mPhoneticName == null &&
                (mTmpXPhoneticFirstName != null || mTmpXPhoneticMiddleName != null ||
                        mTmpXPhoneticLastName != null)) {
            // Note: In Europe, this order should be "LAST FIRST MIDDLE". See the comment around
            //       NAME_ORDER_TYPE_* for more detail.
            String first;
            String second;
            if (mNameOrderType == VCardConfig.NAME_ORDER_TYPE_JAPANESE) {
                first = mTmpXPhoneticLastName;
                second = mTmpXPhoneticFirstName;
            } else {
                first = mTmpXPhoneticFirstName;
                second = mTmpXPhoneticLastName;
            }
            StringBuilder builder = new StringBuilder();
            if (first != null) {
                builder.append(first);
            }
            if (mTmpXPhoneticMiddleName != null) {
                builder.append(mTmpXPhoneticMiddleName);
            }
            if (second != null) {
                builder.append(second);
            }
            mPhoneticName = builder.toString();
        }
        
        // Remove unnecessary white spaces.
        // It is found that some mobile phone emits  phonetic name with just one white space
        // when a user does not specify one.
        // This logic is effective toward such kind of weird data.
        if (mPhoneticName != null) {
            mPhoneticName = mPhoneticName.trim();
        }

        // If there is no "PREF", we choose the first entries as primary.
        if (!mPrefIsSet_Phone && mPhoneList != null && mPhoneList.size() > 0) {
            mPhoneList.get(0).isPrimary = true;
        }

        if (!mPrefIsSet_Address && mContactMethodList != null) {
            for (ContactMethod contactMethod : mContactMethodList) {
                if (contactMethod.kind == Contacts.KIND_POSTAL) {
                    contactMethod.isPrimary = true;
                    break;
                }
            }
        }
        if (!mPrefIsSet_Email && mContactMethodList != null) {
            for (ContactMethod contactMethod : mContactMethodList) {
                if (contactMethod.kind == Contacts.KIND_EMAIL) {
                    contactMethod.isPrimary = true;
                    break;
                }
            }
        }
        if (!mPrefIsSet_Organization && mOrganizationList != null && mOrganizationList.size() > 0) {
            mOrganizationList.get(0).isPrimary = true;
        }
        
    }
    
    private void pushIntoContentProviderOrResolver(Object contentSomething,
            long myContactsGroupId) {
        ContentResolver resolver = null;
        AbstractSyncableContentProvider provider = null;
        if (contentSomething instanceof ContentResolver) {
            resolver = (ContentResolver)contentSomething;
        } else if (contentSomething instanceof AbstractSyncableContentProvider) {
            provider = (AbstractSyncableContentProvider)contentSomething;
        } else {
            Log.e(LOG_TAG, "Unsupported object came.");
            return;
        }
        
        ContentValues contentValues = new ContentValues();
        contentValues.put(People.NAME, mName);
        contentValues.put(People.PHONETIC_NAME, mPhoneticName);
        
        if (mNotes != null && mNotes.size() > 0) {
            if (mNotes.size() > 1) {
                StringBuilder builder = new StringBuilder();
                for (String note : mNotes) {
                    builder.append(note);
                    builder.append("\n");
                }
                contentValues.put(People.NOTES, builder.toString());
            } else {
                contentValues.put(People.NOTES, mNotes.get(0));
            }
        }

        Uri personUri;
        long personId = 0;
        if (resolver != null) {
            personUri = Contacts.People.createPersonInMyContactsGroup(resolver, contentValues);
            if (personUri != null) {
                personId = ContentUris.parseId(personUri);
            }
        } else {
            personUri = provider.insert(People.CONTENT_URI, contentValues);
            if (personUri != null) {
                personId = ContentUris.parseId(personUri);
                ContentValues values = new ContentValues();
                values.put(GroupMembership.PERSON_ID, personId);
                values.put(GroupMembership.GROUP_ID, myContactsGroupId);
                Uri resultUri = provider.insert(GroupMembership.CONTENT_URI, values);
                if (resultUri == null) {
                    Log.e(LOG_TAG, "Faild to insert the person to MyContact.");
                    provider.delete(personUri, null, null);
                    personUri = null;
                }
            }
        }

        if (personUri == null) {
            Log.e(LOG_TAG, "Failed to create the contact.");
            return;
        }
        
        if (mPhotoBytes != null) {
            if (resolver != null) {
                People.setPhotoData(resolver, personUri, mPhotoBytes);
            } else {
                Uri photoUri = Uri.withAppendedPath(personUri, Contacts.Photos.CONTENT_DIRECTORY);
                ContentValues values = new ContentValues();
                values.put(Photos.DATA, mPhotoBytes);
                provider.update(photoUri, values, null, null);
            }
        }
        
        long primaryPhoneId = -1;
        if (mPhoneList != null && mPhoneList.size() > 0) {
            for (PhoneData phoneData : mPhoneList) {
                ContentValues values = new ContentValues();
                values.put(Contacts.PhonesColumns.TYPE, phoneData.type);
                if (phoneData.type == Contacts.PhonesColumns.TYPE_CUSTOM) {
                    values.put(Contacts.PhonesColumns.LABEL, phoneData.label);
                }
                // Already formatted.
                values.put(Contacts.PhonesColumns.NUMBER, phoneData.data);
                
                // Not sure about Contacts.PhonesColumns.NUMBER_KEY ...
                values.put(Contacts.PhonesColumns.ISPRIMARY, 1);
                values.put(Contacts.Phones.PERSON_ID, personId);
                Uri phoneUri;
                if (resolver != null) {
                    phoneUri = resolver.insert(Phones.CONTENT_URI, values);
                } else {
                    phoneUri = provider.insert(Phones.CONTENT_URI, values);
                }
                if (phoneData.isPrimary) {
                    primaryPhoneId = Long.parseLong(phoneUri.getLastPathSegment());
                }
            }
        }
        
        long primaryOrganizationId = -1;
        if (mOrganizationList != null && mOrganizationList.size() > 0) {
            for (OrganizationData organizationData : mOrganizationList) {
                ContentValues values = new ContentValues();
                // Currently, we do not use TYPE_CUSTOM.
                values.put(Contacts.OrganizationColumns.TYPE,
                        organizationData.type);
                values.put(Contacts.OrganizationColumns.COMPANY,
                        organizationData.companyName);
                values.put(Contacts.OrganizationColumns.TITLE,
                        organizationData.positionName);
                values.put(Contacts.OrganizationColumns.ISPRIMARY, 1);
                values.put(Contacts.OrganizationColumns.PERSON_ID, personId);
                
                Uri organizationUri;
                if (resolver != null) {
                    organizationUri = resolver.insert(Organizations.CONTENT_URI, values);
                } else {
                    organizationUri = provider.insert(Organizations.CONTENT_URI, values);
                }
                if (organizationData.isPrimary) {
                    primaryOrganizationId = Long.parseLong(organizationUri.getLastPathSegment());
                }
            }
        }
        
        long primaryEmailId = -1;
        if (mContactMethodList != null && mContactMethodList.size() > 0) {
            for (ContactMethod contactMethod : mContactMethodList) {
                ContentValues values = new ContentValues();
                values.put(Contacts.ContactMethodsColumns.KIND, contactMethod.kind);
                values.put(Contacts.ContactMethodsColumns.TYPE, contactMethod.type);
                if (contactMethod.type == Contacts.ContactMethodsColumns.TYPE_CUSTOM) {
                    values.put(Contacts.ContactMethodsColumns.LABEL, contactMethod.label);
                }
                values.put(Contacts.ContactMethodsColumns.DATA, contactMethod.data);
                values.put(Contacts.ContactMethodsColumns.ISPRIMARY, 1);
                values.put(Contacts.ContactMethods.PERSON_ID, personId);
                
                if (contactMethod.kind == Contacts.KIND_EMAIL) {
                    Uri emailUri;
                    if (resolver != null) {
                        emailUri = resolver.insert(ContactMethods.CONTENT_URI, values);
                    } else {
                        emailUri = provider.insert(ContactMethods.CONTENT_URI, values);
                    }
                    if (contactMethod.isPrimary) {
                        primaryEmailId = Long.parseLong(emailUri.getLastPathSegment());
                    }
                } else {  // probably KIND_POSTAL
                    if (resolver != null) {
                        resolver.insert(ContactMethods.CONTENT_URI, values);
                    } else {
                        provider.insert(ContactMethods.CONTENT_URI, values);
                    }
                }
            }
        }
        
        if (mExtensionMap != null && mExtensionMap.size() > 0) {
            ArrayList<ContentValues> contentValuesArray;
            if (resolver != null) {
                contentValuesArray = new ArrayList<ContentValues>();
            } else {
                contentValuesArray = null;
            }
            for (Entry<String, List<String>> entry : mExtensionMap.entrySet()) {
                String key = entry.getKey();
                List<String> list = entry.getValue();
                for (String value : list) {
                    ContentValues values = new ContentValues();
                    values.put(Extensions.NAME, key);
                    values.put(Extensions.VALUE, value);
                    values.put(Extensions.PERSON_ID, personId);
                    if (resolver != null) {
                        contentValuesArray.add(values);
                    } else {
                        provider.insert(Extensions.CONTENT_URI, values);
                    }
                }
            }
            if (resolver != null) {
                resolver.bulkInsert(Extensions.CONTENT_URI,
                        contentValuesArray.toArray(new ContentValues[0]));
            }
        }
        
        if (primaryPhoneId >= 0 || primaryOrganizationId >= 0 || primaryEmailId >= 0) {
            ContentValues values = new ContentValues();
            if (primaryPhoneId >= 0) {
                values.put(People.PRIMARY_PHONE_ID, primaryPhoneId);
            }
            if (primaryOrganizationId >= 0) {
                values.put(People.PRIMARY_ORGANIZATION_ID, primaryOrganizationId);
            }
            if (primaryEmailId >= 0) {
                values.put(People.PRIMARY_EMAIL_ID, primaryEmailId);
            }
            if (resolver != null) {
                resolver.update(personUri, values, null, null);
            } else {
                provider.update(personUri, values, null, null);
            }
        }
    }

    /**
     * Push this object into database in the resolver.
     */
    public void pushIntoContentResolver(ContentResolver resolver) {
        pushIntoContentProviderOrResolver(resolver, 0);
    }
    
    /**
     * Push this object into AbstractSyncableContentProvider object.
     * {@link #consolidateFields() must be called before this method is called}
     * @hide
     */
    public void pushIntoAbstractSyncableContentProvider(
            AbstractSyncableContentProvider provider, long myContactsGroupId) {
        boolean successful = false;
        provider.beginBatch();
        try {
            pushIntoContentProviderOrResolver(provider, myContactsGroupId);
            successful = true;
        } finally {
            provider.endBatch(successful);
        }
    }
    
    public boolean isIgnorable() {
        return TextUtils.isEmpty(mName) &&
                TextUtils.isEmpty(mPhoneticName) &&
                (mPhoneList == null || mPhoneList.size() == 0) &&
                (mContactMethodList == null || mContactMethodList.size() == 0);
    }
    
    private String listToString(List<String> list){
        final int size = list.size();
        if (size > 1) {
            StringBuilder builder = new StringBuilder();
            int i = 0;
            for (String type : list) {
                builder.append(type);
                if (i < size - 1) {
                    builder.append(";");
                }
            }
            return builder.toString();
        } else if (size == 1) {
            return list.get(0);
        } else {
            return "";
        }
    }
}
