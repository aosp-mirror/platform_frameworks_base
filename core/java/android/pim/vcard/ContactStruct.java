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

import android.accounts.Account;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Im;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * This class bridges between data structure of Contact app and VCard data.
 */
public class ContactStruct {
    private static final String LOG_TAG = "vcard.ContactStruct";
    
    // Key: the name shown in VCard. e.g. "X-AIM", "X-ICQ"
    // Value: the result of {@link Contacts.ContactMethods#encodePredefinedImProtocol}
    private static final Map<String, Integer> sImMap = new HashMap<String, Integer>();
    
    static {
        sImMap.put(Constants.PROPERTY_X_AIM, Im.PROTOCOL_AIM);
        sImMap.put(Constants.PROPERTY_X_MSN, Im.PROTOCOL_MSN);
        sImMap.put(Constants.PROPERTY_X_YAHOO, Im.PROTOCOL_YAHOO);
        sImMap.put(Constants.PROPERTY_X_ICQ, Im.PROTOCOL_ICQ);
        sImMap.put(Constants.PROPERTY_X_JABBER, Im.PROTOCOL_JABBER);
        sImMap.put(Constants.PROPERTY_X_SKYPE_USERNAME, Im.PROTOCOL_SKYPE);
        sImMap.put(Constants.PROPERTY_X_GOOGLE_TALK, Im.PROTOCOL_GOOGLE_TALK);
        sImMap.put(Constants.PROPERTY_X_GOOGLE_TALK_WITH_SPACE, Im.PROTOCOL_GOOGLE_TALK);
    }
    
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
    static public class EmailData {
        public final int type;
        public final String data;
        // Used only when TYPE is TYPE_CUSTOM.
        public final String label;
        // isPrimary is changable only when there's no appropriate one existing in
        // the original VCard.
        public boolean isPrimary;
        public EmailData(int type, String data, String label, boolean isPrimary) {
            this.type = type;
            this.data = data;
            this.label = label;
            this.isPrimary = isPrimary;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof EmailData) {
                return false;
            }
            EmailData emailData = (EmailData)obj;
            return (type == emailData.type && data.equals(emailData.data) &&
                    label.equals(emailData.label) && isPrimary == emailData.isPrimary);
        }
        
        @Override
        public String toString() {
            return String.format("type: %d, data: %s, label: %s, isPrimary: %s",
                    type, data, label, isPrimary);
        }
    }

    static public class PostalData {
        // Determined by vCard spec.
        // PO Box, Extended Addr, Street, Locality, Region, Postal Code, Country Name
        public static final int ADDR_MAX_DATA_SIZE = 7;
        private final String[] dataArray;
        public final String pobox;
        public final String extendedAddress;
        public final String street;
        public final String localty;
        public final String region;
        public final String postalCode;
        public final String country;

        public final int type;
        
        // Used only when type variable is TYPE_CUSTOM.
        public final String label;

        // isPrimary is changable only when there's no appropriate one existing in
        // the original VCard.
        public boolean isPrimary;
        public PostalData(int type, List<String> propValueList,
                String label, boolean isPrimary) {
            this.type = type;
            dataArray = new String[ADDR_MAX_DATA_SIZE];

            int size = propValueList.size();
            if (size > ADDR_MAX_DATA_SIZE) {
                size = ADDR_MAX_DATA_SIZE;
            }

            // adr-value    = 0*6(text-value ";") text-value
            //              ; PO Box, Extended Address, Street, Locality, Region, Postal
            //              ; Code, Country Name
            //
            // Use Iterator assuming List may be LinkedList, though actually it is
            // always ArrayList in the current implementation.
            int i = 0;
            for (String addressElement : propValueList) {
                dataArray[i] = addressElement;
                if (++i >= size) {
                    break;
                }
            }
            while (i < ADDR_MAX_DATA_SIZE) {
                dataArray[i++] = null;
            }

            this.pobox = dataArray[0];
            this.extendedAddress = dataArray[1];
            this.street = dataArray[2];
            this.localty = dataArray[3];
            this.region = dataArray[4];
            this.postalCode = dataArray[5];
            this.country = dataArray[6];
            
            this.label = label;
            this.isPrimary = isPrimary;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof PostalData) {
                return false;
            }
            PostalData postalData = (PostalData)obj;
            return (Arrays.equals(dataArray, postalData.dataArray) && 
                    (type == postalData.type &&
                            (type == StructuredPostal.TYPE_CUSTOM ?
                                    (label == postalData.label) : true)) &&
                    (isPrimary == postalData.isPrimary));
        }
        
        public String getFormattedAddress(int vcardType) {
            StringBuilder builder = new StringBuilder();
            boolean empty = true;
            if (VCardConfig.isJapaneseDevice(vcardType)) {
                // In Japan, the order is reversed.
                for (int i = ADDR_MAX_DATA_SIZE - 1; i >= 0; i--) {
                    String addressPart = dataArray[i];
                    if (!TextUtils.isEmpty(addressPart)) {
                        if (!empty) {
                            builder.append(' ');
                        }
                        builder.append(addressPart);
                        empty = false;
                    }
                }
            } else {
                for (int i = 0; i < ADDR_MAX_DATA_SIZE; i++) {
                    String addressPart = dataArray[i];
                    if (!TextUtils.isEmpty(addressPart)) {
                        if (!empty) {
                            builder.append(' ');
                        }
                        builder.append(addressPart);
                        empty = false;
                    }
                }
            }

            return builder.toString().trim();
        }
        
        @Override
        public String toString() {
            return String.format("type: %d, label: %s, isPrimary: %s",
                    type, label, isPrimary);
        }
    }
    
    /**
     * @hide only for testing.
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
    
    static public class ImData {
        public final int type;
        public final String data;
        public final String label;
        public final boolean isPrimary;
        
        // TODO: ContactsConstant#PROTOCOL, ContactsConstant#CUSTOM_PROTOCOL should be used?
        public ImData(int type, String data, String label, boolean isPrimary) {
            this.type = type;
            this.data = data;
            this.label = label;
            this.isPrimary = isPrimary;
        }
        
        @Override
        public boolean equals(Object obj) {
            if (obj instanceof ImData) {
                return false;
            }
            ImData imData = (ImData)obj;
            return (type == imData.type && data.equals(imData.data) &&
                    label.equals(imData.label) && isPrimary == imData.isPrimary);
        }
        
        @Override
        public String toString() {
            return String.format("type: %d, data: %s, label: %s, isPrimary: %s",
                    type, data, label, isPrimary);
        }
    }
    
    /**
     * @hide only for testing.
     */
    static public class PhotoData {
        public static final String FORMAT_FLASH = "SWF";
        public final int type;
        public final String formatName;  // used when type is not defined in ContactsContract.
        public final byte[] photoBytes;

        public PhotoData(int type, String formatName, byte[] photoBytes) {
            this.type = type;
            this.formatName = formatName;
            this.photoBytes = photoBytes;
        }
    }
    
    static /* package */ class Property {
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
            if (!mParameterMap.containsKey(paramName)) {
                if (paramName.equals("TYPE")) {
                    values = new HashSet<String>();
                } else {
                    values = new ArrayList<String>();
                }
                mParameterMap.put(paramName, values);
            } else {
                values = mParameterMap.get(paramName);
            }
            values.add(paramValue);
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
    
    private String mFamilyName;
    private String mGivenName;
    private String mMiddleName;
    private String mPrefix;
    private String mSuffix;

    // Used only when no family nor given name is found.
    private String mFullName;
    
    private String mPhoneticFamilyName;
    private String mPhoneticGivenName;
    private String mPhoneticMiddleName;
    
    private String mPhoneticFullName;

    private List<String> mNickNameList;

    private String mDisplayName; 

    private String mBirthday;
    
    private List<String> mNoteList;
    private List<PhoneData> mPhoneList;
    private List<EmailData> mEmailList;
    private List<PostalData> mPostalList;
    private List<OrganizationData> mOrganizationList;
    private List<ImData> mImList;
    private List<PhotoData> mPhotoList;
    private List<String> mWebsiteList;
    
    private final int mVCardType;
    private final Account mAccount;

    // Each Column of four properties has ISPRIMARY field
    // (See android.provider.Contacts)
    // If false even after the parsing loop, we choose the first entry as a "primary"
    // entry.
    private boolean mPrefIsSet_Address;
    private boolean mPrefIsSet_Phone;
    private boolean mPrefIsSet_Email;
    private boolean mPrefIsSet_Organization;

    public ContactStruct() {
        this(VCardConfig.VCARD_TYPE_V21_GENERIC);
    }

    public ContactStruct(int vcardType) {
        this(vcardType, null);
    }

    public ContactStruct(int vcardType, Account account) {
        mVCardType = vcardType;
        mAccount = account;
    }

    /**
     * @hide only for testing.
     */
    public ContactStruct(String givenName,
            String familyName,
            String middleName,
            String prefix,
            String suffix,
            String phoneticGivenName,
            String pheneticFamilyName,
            String phoneticMiddleName,
            List<byte[]> photoBytesList,
            List<String> notes,
            List<PhoneData> phoneList, 
            List<EmailData> emailList,
            List<PostalData> postalList,
            List<OrganizationData> organizationList,
            List<PhotoData> photoList,
            List<String> websiteList) {
        this(VCardConfig.VCARD_TYPE_DEFAULT);
        mGivenName = givenName;
        mFamilyName = familyName;
        mPrefix = prefix;
        mSuffix = suffix;
        mPhoneticGivenName = givenName;
        mPhoneticFamilyName = familyName;
        mPhoneticMiddleName = middleName;
        mEmailList = emailList;
        mPostalList = postalList;
        mOrganizationList = organizationList;
        mPhotoList = photoList;
        mWebsiteList = websiteList;
    }

    // All getter methods should be used carefully, since they may change
    // in the future as of 2009-09-24, on which I cannot be sure this structure
    // is completely consolidated.
    // When we are sure we will no longer change them, we'll be happy to
    // make it complete public (withouth @hide tag)
    //
    // Also note that these getter methods should be used only after
    // all properties being pushed into this object. If not, incorrect
    // value will "be stored in the local cache and" be returned to you.
    
    /**
     * @hide
     */
    public String getFamilyName() {
        return mFamilyName;
    }

    /**
     * @hide
     */
    public String getGivenName() {
        return mGivenName;
    }

    /**
     * @hide
     */
    public String getMiddleName() {
        return mMiddleName;
    }

    /**
     * @hide
     */
    public String getPrefix() {
        return mPrefix;
    }

    /**
     * @hide
     */
    public String getSuffix() {
        return mSuffix;
    }

    /**
     * @hide
     */
    public String getFullName() {
        return mFullName;
    }

    /**
     * @hide
     */
    public String getPhoneticFamilyName() {
        return mPhoneticFamilyName;
    }

    /**
     * @hide
     */
    public String getPhoneticGivenName() {
        return mPhoneticGivenName;
    }

    /**
     * @hide
     */
    public String getPhoneticMiddleName() {
        return mPhoneticMiddleName;
    }

    /**
     * @hide
     */
    public String getPhoneticFullName() {
        return mPhoneticFullName;
    }

    /**
     * @hide
     */
    public final List<String> getNickNameList() {
        return mNickNameList;
    }

    /**
     * @hide
     */
    public String getDisplayName() {
        if (mDisplayName == null) {
            constructDisplayName();
        }
        return mDisplayName;
    }

    /**
     * @hide
     */
    public String getBirthday() {
        return mBirthday;
    }

    /**
     * @hide
     */
    public final List<PhotoData> getPhotoList() {
        return mPhotoList;
    }

    /**
     * @hide
     */
    public final List<String> getNotes() {
        return mNoteList;
    }
    
    /**
     * @hide
     */
    public final List<PhoneData> getPhoneList() {
        return mPhoneList;
    }
    
    /**
     * @hide
     */
    public final List<EmailData> getEmailList() {
        return mEmailList;
    }
    
    /**
     * @hide
     */
    public final List<PostalData> getPostalList() {
        return mPostalList;
    }
    
    /**
     * @hide
     */
    public final List<OrganizationData> getOrganizationList() {
        return mOrganizationList;
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

    private void addNickName(final String nickName) {
        if (mNickNameList == null) {
            mNickNameList = new ArrayList<String>();
        }
        mNickNameList.add(nickName);
    }
    
    private void addEmail(int type, String data, String label, boolean isPrimary){
        if (mEmailList == null) {
            mEmailList = new ArrayList<EmailData>();
        }
        mEmailList.add(new EmailData(type, data, label, isPrimary));
    }
    
    private void addPostal(int type, List<String> propValueList, String label, boolean isPrimary){
        if (mPostalList == null) {
            mPostalList = new ArrayList<PostalData>();
        }
        mPostalList.add(new PostalData(type, propValueList, label, isPrimary));
    }
    
    private void addOrganization(int type, final String companyName,
            final String positionName, boolean isPrimary) {
        if (mOrganizationList == null) {
            mOrganizationList = new ArrayList<OrganizationData>();
        }
        mOrganizationList.add(new OrganizationData(type, companyName, positionName, isPrimary));
    }
    
    private void addIm(int type, String data, String label, boolean isPrimary) {
        if (mImList == null) {
            mImList = new ArrayList<ImData>();
        }
        mImList.add(new ImData(type, data, label, isPrimary));
    }
    
    private void addNote(final String note) {
        if (mNoteList == null) {
            mNoteList = new ArrayList<String>(1);
        }
        mNoteList.add(note);
    }
    
    private void addPhotoBytes(String formatName, byte[] photoBytes) {
        if (mPhotoList == null) {
            mPhotoList = new ArrayList<PhotoData>(1);
        }
        final PhotoData photoData = new PhotoData(0, null, photoBytes);
        mPhotoList.add(photoData);
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
            addOrganization(ContactsContract.CommonDataKinds.Organization.TYPE_OTHER,
                    "", null, false);
            size = 1;
        }
        OrganizationData lastData = mOrganizationList.get(size - 1);
        lastData.positionName = positionValue;
    }
 
    @SuppressWarnings("fallthrough")
    private void handleNProperty(List<String> elems) {
        // Family, Given, Middle, Prefix, Suffix. (1 - 5)
        int size;
        if (elems == null || (size = elems.size()) < 1) {
            return;
        }
        if (size > 5) {
            size = 5;
        }

        switch (size) {
        // fallthrough
        case 5:
            mSuffix = elems.get(4);
        case 4:
            mPrefix = elems.get(3);
        case 3:
            mMiddleName = elems.get(2);
        case 2:
            mGivenName = elems.get(1);
        default:
            mFamilyName = elems.get(0);
        }
    }
    
    /**
     * Some Japanese mobile phones use this field for phonetic name,
     *  since vCard 2.1 does not have "SORT-STRING" type.
     * Also, in some cases, the field has some ';'s in it.
     * Assume the ';' means the same meaning in N property
     */
    @SuppressWarnings("fallthrough")
    private void handlePhoneticNameFromSound(List<String> elems) {
        // Family, Given, Middle. (1-3)
        // This is not from specification but mere assumption. Some Japanese phones use this order.
        int size;
        if (elems == null || (size = elems.size()) < 1) {
            return;
        }
        if (size > 3) {
            size = 3;
        }

        switch (size) {
        // fallthrough
        case 3:
            mPhoneticMiddleName = elems.get(2);
        case 2:
            mPhoneticGivenName = elems.get(1);
        default:
            mPhoneticFamilyName = elems.get(0);
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
        final String propValue = listToString(propValueList).trim();
        
        if (propName.equals("VERSION")) {
            // vCard version. Ignore this.
        } else if (propName.equals("FN")) {
            mFullName = propValue;
        } else if (propName.equals("NAME") && mFullName == null) {
            // Only in vCard 3.0. Use this if FN, which must exist in vCard 3.0 but may not
            // actually exist in the real vCard data, does not exist.
            mFullName = propValue;
        } else if (propName.equals("N")) {
            handleNProperty(propValueList);
        } else if (propName.equals("SORT-STRING")) {
            mPhoneticFullName = propValue;
        } else if (propName.equals("NICKNAME") || propName.equals("X-NICKNAME")) {
            addNickName(propValue);
        } else if (propName.equals("SOUND")) {
            Collection<String> typeCollection = paramMap.get(Constants.ATTR_TYPE);
            if (typeCollection != null && typeCollection.contains(Constants.ATTR_TYPE_X_IRMC_N)) {
                handlePhoneticNameFromSound(propValueList);
            } else {
                // Ignore this field since Android cannot understand what it is.
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

            int type = -1;
            String label = "";
            boolean isPrimary = false;
            Collection<String> typeCollection = paramMap.get(Constants.ATTR_TYPE);
            if (typeCollection != null) {
                for (String typeString : typeCollection) {
                    typeString = typeString.toUpperCase();
                    if (typeString.equals(Constants.ATTR_TYPE_PREF) && !mPrefIsSet_Address) {
                        // Only first "PREF" is considered.
                        mPrefIsSet_Address = true;
                        isPrimary = true;
                    } else if (typeString.equals(Constants.ATTR_TYPE_HOME)) {
                        type = StructuredPostal.TYPE_HOME;
                        label = "";
                    } else if (typeString.equals(Constants.ATTR_TYPE_WORK) || 
                            typeString.equalsIgnoreCase("COMPANY")) {
                        // "COMPANY" seems emitted by Windows Mobile, which is not
                        // specifically supported by vCard 2.1. We assume this is same
                        // as "WORK".
                        type = StructuredPostal.TYPE_WORK;
                        label = "";
                    } else if (typeString.equals("PARCEL") || 
                            typeString.equals("DOM") ||
                            typeString.equals("INTL")) {
                        // We do not have any appropriate way to store this information.
                    } else {
                        if (typeString.startsWith("X-") && type < 0) {
                            typeString = typeString.substring(2);
                        }
                        // vCard 3.0 allows iana-token. Also some vCard 2.1 exporters
                        // emit non-standard types. We do not handle their values now.
                        type = StructuredPostal.TYPE_CUSTOM;
                        label = typeString;
                    }
                }
            }
            // We use "HOME" as default
            if (type < 0) {
                type = StructuredPostal.TYPE_HOME;
            }

            addPostal(type, propValueList, label, isPrimary);
        } else if (propName.equals("EMAIL")) {
            int type = -1;
            String label = null;
            boolean isPrimary = false;
            Collection<String> typeCollection = paramMap.get(Constants.ATTR_TYPE);
            if (typeCollection != null) {
                for (String typeString : typeCollection) {
                    typeString = typeString.toUpperCase();
                    if (typeString.equals(Constants.ATTR_TYPE_PREF) && !mPrefIsSet_Email) {
                        // Only first "PREF" is considered.
                        mPrefIsSet_Email = true;
                        isPrimary = true;
                    } else if (typeString.equals(Constants.ATTR_TYPE_HOME)) {
                        type = Email.TYPE_HOME;
                    } else if (typeString.equals(Constants.ATTR_TYPE_WORK)) {
                        type = Email.TYPE_WORK;
                    } else if (typeString.equals(Constants.ATTR_TYPE_CELL)) {
                        type = Email.TYPE_MOBILE;
                    } else {
                        if (typeString.startsWith("X-") && type < 0) {
                            typeString = typeString.substring(2);
                        }
                        // vCard 3.0 allows iana-token.
                        // We may have INTERNET (specified in vCard spec),
                        // SCHOOL, etc.
                        type = Email.TYPE_CUSTOM;
                        label = typeString;
                    }
                }
            }
            if (type < 0) {
                type = Email.TYPE_OTHER;
            }
            addEmail(type, propValue, label, isPrimary);
        } else if (propName.equals("ORG")) {
            // vCard specification does not specify other types.
            int type = Organization.TYPE_WORK;
            boolean isPrimary = false;
            
            Collection<String> typeCollection = paramMap.get(Constants.ATTR_TYPE);
            if (typeCollection != null) {
                for (String typeString : typeCollection) {
                    if (typeString.equals(Constants.ATTR_TYPE_PREF) && !mPrefIsSet_Organization) {
                        // vCard specification officially does not have PREF in ORG.
                        // This is just for safety.
                        mPrefIsSet_Organization = true;
                        isPrimary = true;
                    }
                }
            }

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
        } else if (propName.equals("PHOTO") || propName.equals("LOGO")) {
            String formatName = null;
            Collection<String> typeCollection = paramMap.get("TYPE");
            if (typeCollection != null) {
                formatName = typeCollection.iterator().next();
            }
            Collection<String> paramMapValue = paramMap.get("VALUE");
            if (paramMapValue != null && paramMapValue.contains("URL")) {
                // Currently we do not have appropriate example for testing this case.
            } else {
                addPhotoBytes(formatName, propBytes);
            }
        } else if (propName.equals("TEL")) {
            Collection<String> typeCollection = paramMap.get(Constants.ATTR_TYPE);
            Object typeObject = VCardUtils.getPhoneTypeFromStrings(typeCollection);
            final int type;
            final String label;
            if (typeObject instanceof Integer) {
                type = (Integer)typeObject;
                label = null;
            } else {
                type = Phone.TYPE_CUSTOM;
                label = typeObject.toString();
            }
            
            final boolean isPrimary;
            if (!mPrefIsSet_Phone && typeCollection != null &&
                    typeCollection.contains(Constants.ATTR_TYPE_PREF)) {
                mPrefIsSet_Phone = true;
                isPrimary = true;
            } else {
                isPrimary = false;
            }
            addPhone(type, propValue, label, isPrimary);
        } else if (propName.equals(Constants.PROPERTY_X_SKYPE_PSTNNUMBER)) {
            // The phone number available via Skype.
            Collection<String> typeCollection = paramMap.get(Constants.ATTR_TYPE);
            // XXX: should use TYPE_CUSTOM + the label "Skype"? (which may need localization)
            int type = Phone.TYPE_OTHER;
            final String label = null;
            final boolean isPrimary;
            if (!mPrefIsSet_Phone && typeCollection != null &&
                    typeCollection.contains(Constants.ATTR_TYPE_PREF)) {
                mPrefIsSet_Phone = true;
                isPrimary = true;
            } else {
                isPrimary = false;
            }
            addPhone(type, propValue, label, isPrimary);
        } else if (sImMap.containsKey(propName)){
            int type = sImMap.get(propName);
            boolean isPrimary = false;
            final Collection<String> typeCollection = paramMap.get(Constants.ATTR_TYPE);
            if (typeCollection != null) {
                for (String typeString : typeCollection) {
                    if (typeString.equals(Constants.ATTR_TYPE_PREF)) {
                        isPrimary = true;
                    } else if (typeString.equalsIgnoreCase(Constants.ATTR_TYPE_HOME)) {
                        type = Phone.TYPE_HOME;
                    } else if (typeString.equalsIgnoreCase(Constants.ATTR_TYPE_WORK)) {
                        type = Phone.TYPE_WORK;
                    }
                }
            }
            if (type < 0) {
                type = Phone.TYPE_HOME;
            }
            addIm(type, propValue, null, isPrimary);
        } else if (propName.equals("NOTE")) {
            addNote(propValue);
        } else if (propName.equals("URL")) {
            if (mWebsiteList == null) {
                mWebsiteList = new ArrayList<String>(1);
            }
            mWebsiteList.add(propValue);
        } else if (propName.equals("X-PHONETIC-FIRST-NAME")) {
            mPhoneticGivenName = propValue;
        } else if (propName.equals("X-PHONETIC-MIDDLE-NAME")) {
            mPhoneticMiddleName = propValue;
        } else if (propName.equals("X-PHONETIC-LAST-NAME")) {
            mPhoneticFamilyName = propValue;
        } else if (propName.equals("BDAY")) {
            mBirthday = propValue;
        /*} else if (propName.equals("REV")) {                
            // Revision of this VCard entry. I think we can ignore this.
        } else if (propName.equals("UID")) {
        } else if (propName.equals("KEY")) {
            // Type is X509 or PGP? I don't know how to handle this...
        } else if (propName.equals("MAILER")) {
        } else if (propName.equals("TZ")) {
        } else if (propName.equals("GEO")) {
        } else if (propName.equals("CLASS")) {
            // vCard 3.0 only.
            // e.g. CLASS:CONFIDENTIAL
        } else if (propName.equals("PROFILE")) {
            // VCard 3.0 only. Must be "VCARD". I think we can ignore this.
        } else if (propName.equals("CATEGORIES")) {
            // VCard 3.0 only.
            // e.g. CATEGORIES:INTERNET,IETF,INDUSTRY,INFORMATION TECHNOLOGY
        } else if (propName.equals("SOURCE")) {
            // VCard 3.0 only.
        } else if (propName.equals("PRODID")) {
            // VCard 3.0 only.
            // To specify the identifier for the product that created
            // the vCard object.*/
        } else {
            // Unknown X- words and IANA token.
        }
    }

    /**
     * Construct the display name. The constructed data must not be null.
     */
    private void constructDisplayName() {
        if (!(TextUtils.isEmpty(mFamilyName) && TextUtils.isEmpty(mGivenName))) {
            StringBuilder builder = new StringBuilder();
            List<String> nameList;
            switch (VCardConfig.getNameOrderType(mVCardType)) {
            case VCardConfig.NAME_ORDER_JAPANESE:
                if (VCardUtils.containsOnlyPrintableAscii(mFamilyName) &&
                        VCardUtils.containsOnlyPrintableAscii(mGivenName)) {
                    nameList = Arrays.asList(mPrefix, mGivenName, mMiddleName, mFamilyName, mSuffix);
                } else {
                    nameList = Arrays.asList(mPrefix, mFamilyName, mMiddleName, mGivenName, mSuffix);
                }
                break;
            case VCardConfig.NAME_ORDER_EUROPE:
                nameList = Arrays.asList(mPrefix, mMiddleName, mGivenName, mFamilyName, mSuffix);
                break;
            default:
                nameList = Arrays.asList(mPrefix, mGivenName, mMiddleName, mFamilyName, mSuffix);
                break;
            }
            boolean first = true;
            for (String namePart : nameList) {
                if (!TextUtils.isEmpty(namePart)) {
                    if (first) {
                        first = false;
                    } else {
                        builder.append(' ');
                    }
                    builder.append(namePart);
                }
            }
            mDisplayName = builder.toString();
        } else if (!TextUtils.isEmpty(mFullName)) {
            mDisplayName = mFullName;
        } else if (!(TextUtils.isEmpty(mPhoneticFamilyName) &&
                TextUtils.isEmpty(mPhoneticGivenName))) {
            mDisplayName = VCardUtils.constructNameFromElements(mVCardType,
                    mPhoneticFamilyName, mPhoneticMiddleName, mPhoneticGivenName);
        } else if (mEmailList != null && mEmailList.size() > 0) {
            mDisplayName = mEmailList.get(0).data;
        } else if (mPhoneList != null && mPhoneList.size() > 0) {
            mDisplayName = mPhoneList.get(0).data;
        } else if (mPostalList != null && mPostalList.size() > 0) {
            mDisplayName = mPostalList.get(0).getFormattedAddress(mVCardType);
        }

        if (mDisplayName == null) {
            mDisplayName = "";
        }
    }
    
    /**
     * Consolidate several fielsds (like mName) using name candidates, 
     */
    public void consolidateFields() {
        constructDisplayName();
        
        if (mPhoneticFullName != null) {
            mPhoneticFullName = mPhoneticFullName.trim();
        }

        // If there is no "PREF", we choose the first entries as primary.
        if (!mPrefIsSet_Phone && mPhoneList != null && mPhoneList.size() > 0) {
            mPhoneList.get(0).isPrimary = true;
        }

        if (!mPrefIsSet_Address && mPostalList != null && mPostalList.size() > 0) {
            mPostalList.get(0).isPrimary = true;
        }
        if (!mPrefIsSet_Email && mEmailList != null && mEmailList.size() > 0) {
            mEmailList.get(0).isPrimary = true;
        }
        if (!mPrefIsSet_Organization && mOrganizationList != null && mOrganizationList.size() > 0) {
            mOrganizationList.get(0).isPrimary = true;
        }
    }
    
    // From GoogleSource.java in Contacts app.
    private static final String ACCOUNT_TYPE_GOOGLE = "com.google";
    private static final String GOOGLE_MY_CONTACTS_GROUP = "System Group: My Contacts";

    public void pushIntoContentResolver(ContentResolver resolver) {
        ArrayList<ContentProviderOperation> operationList =
            new ArrayList<ContentProviderOperation>();  
        ContentProviderOperation.Builder builder =
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI);
        String myGroupsId = null;
        if (mAccount != null) {
            builder.withValue(RawContacts.ACCOUNT_NAME, mAccount.name);
            builder.withValue(RawContacts.ACCOUNT_TYPE, mAccount.type);

            // Assume that caller side creates this group if it does not exist.
            // TODO: refactor this code along with the change in GoogleSource.java
            if (ACCOUNT_TYPE_GOOGLE.equals(mAccount.type)) {
                final Cursor cursor = resolver.query(Groups.CONTENT_URI, new String[] {
                        Groups.SOURCE_ID },
                        Groups.TITLE + "=?", new String[] {
                        GOOGLE_MY_CONTACTS_GROUP }, null);
                try {
                    if (cursor != null && cursor.moveToFirst()) {
                        myGroupsId = cursor.getString(0);
                    }
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
        } else {
            builder.withValue(RawContacts.ACCOUNT_NAME, null);
            builder.withValue(RawContacts.ACCOUNT_TYPE, null);
        }
        operationList.add(builder.build());

        {
            builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
            builder.withValueBackReference(StructuredName.RAW_CONTACT_ID, 0);
            builder.withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);

            builder.withValue(StructuredName.GIVEN_NAME, mGivenName);
            builder.withValue(StructuredName.FAMILY_NAME, mFamilyName);
            builder.withValue(StructuredName.MIDDLE_NAME, mMiddleName);
            builder.withValue(StructuredName.PREFIX, mPrefix);
            builder.withValue(StructuredName.SUFFIX, mSuffix);

            builder.withValue(StructuredName.PHONETIC_GIVEN_NAME, mPhoneticGivenName);
            builder.withValue(StructuredName.PHONETIC_FAMILY_NAME, mPhoneticFamilyName);
            builder.withValue(StructuredName.PHONETIC_MIDDLE_NAME, mPhoneticMiddleName);

            builder.withValue(StructuredName.DISPLAY_NAME, getDisplayName());
            operationList.add(builder.build());
        }

        if (mNickNameList != null && mNickNameList.size() > 0) {
            boolean first = true;
            for (String nickName : mNickNameList) {
                builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
                builder.withValueBackReference(Nickname.RAW_CONTACT_ID, 0);
                builder.withValue(Data.MIMETYPE, Nickname.CONTENT_ITEM_TYPE);

                builder.withValue(Nickname.TYPE, Nickname.TYPE_DEFAULT);
                builder.withValue(Nickname.NAME, nickName);
                if (first) {
                    builder.withValue(Data.IS_PRIMARY, 1);
                    first = false;
                }
                operationList.add(builder.build());
            }
        }
        
        if (mPhoneList != null) {
            for (PhoneData phoneData : mPhoneList) {
                builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
                builder.withValueBackReference(Phone.RAW_CONTACT_ID, 0);
                builder.withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);

                builder.withValue(Phone.TYPE, phoneData.type);
                if (phoneData.type == Phone.TYPE_CUSTOM) {
                    builder.withValue(Phone.LABEL, phoneData.label);
                }
                builder.withValue(Phone.NUMBER, phoneData.data);
                if (phoneData.isPrimary) {
                    builder.withValue(Data.IS_PRIMARY, 1);
                }
                operationList.add(builder.build());
            }
        }
        
        if (mOrganizationList != null) {
            boolean first = true;
            for (OrganizationData organizationData : mOrganizationList) {
                builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
                builder.withValueBackReference(Organization.RAW_CONTACT_ID, 0);
                builder.withValue(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE);

                // Currently, we do not use TYPE_CUSTOM.
                builder.withValue(Organization.TYPE, organizationData.type);
                builder.withValue(Organization.COMPANY, organizationData.companyName);
                builder.withValue(Organization.TITLE, organizationData.positionName);
                if (first) {
                    builder.withValue(Data.IS_PRIMARY, 1);
                }
                operationList.add(builder.build());
            }
        }
        
        if (mEmailList != null) {
            for (EmailData emailData : mEmailList) {
                builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
                builder.withValueBackReference(Email.RAW_CONTACT_ID, 0);
                builder.withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE);

                builder.withValue(Email.TYPE, emailData.type);
                if (emailData.type == Email.TYPE_CUSTOM) {
                    builder.withValue(Email.LABEL, emailData.label);
                }
                builder.withValue(Email.DATA, emailData.data);
                if (emailData.isPrimary) {
                    builder.withValue(Data.IS_PRIMARY, 1);
                }
                operationList.add(builder.build());
            }
        }
        
        if (mPostalList != null) {
            for (PostalData postalData : mPostalList) {
                builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
                VCardUtils.insertStructuredPostalDataUsingContactsStruct(
                        mVCardType, builder, postalData);
                operationList.add(builder.build());
            }
        }
        
        if (mImList != null) {
            for (ImData imData : mImList) {
                builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
                builder.withValueBackReference(Im.RAW_CONTACT_ID, 0);
                builder.withValue(Data.MIMETYPE, Im.CONTENT_ITEM_TYPE);
                
                builder.withValue(Im.TYPE, imData.type);
                if (imData.type == Im.TYPE_CUSTOM) {
                    builder.withValue(Im.LABEL, imData.label);
                }
                builder.withValue(Im.DATA, imData.data);
                if (imData.isPrimary) {
                    builder.withValue(Data.IS_PRIMARY, 1);
                }
            }
        }
        
        if (mNoteList != null) {
            for (String note : mNoteList) {
                builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
                builder.withValueBackReference(Note.RAW_CONTACT_ID, 0);
                builder.withValue(Data.MIMETYPE, Note.CONTENT_ITEM_TYPE);

                builder.withValue(Note.NOTE, note);
                operationList.add(builder.build());
            }
        }
        
        if (mPhotoList != null) {
            boolean first = true;
            for (PhotoData photoData : mPhotoList) {
                builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
                builder.withValueBackReference(Photo.RAW_CONTACT_ID, 0);
                builder.withValue(Data.MIMETYPE, Photo.CONTENT_ITEM_TYPE);
                builder.withValue(Photo.PHOTO, photoData.photoBytes);
                if (first) {
                    builder.withValue(Data.IS_PRIMARY, 1);
                    first = false;
                }
                operationList.add(builder.build());
            }
        }

        if (mWebsiteList != null) {
            for (String website : mWebsiteList) {
                builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
                builder.withValueBackReference(Website.RAW_CONTACT_ID, 0);
                builder.withValue(Data.MIMETYPE, Website.CONTENT_ITEM_TYPE);
                builder.withValue(Website.URL, website);
                // There's no information about the type of URL in vCard.
                // We use TYPE_HOME for safety. 
                builder.withValue(Website.TYPE, Website.TYPE_HOME);
                operationList.add(builder.build());
            }
        }
        
        if (!TextUtils.isEmpty(mBirthday)) {
            builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
            builder.withValueBackReference(Event.RAW_CONTACT_ID, 0);
            builder.withValue(Data.MIMETYPE, Event.CONTENT_ITEM_TYPE);
            builder.withValue(Event.START_DATE, mBirthday);
            builder.withValue(Event.TYPE, Event.TYPE_BIRTHDAY);
            operationList.add(builder.build());
        }

        if (myGroupsId != null) {
            builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
            builder.withValueBackReference(GroupMembership.RAW_CONTACT_ID, 0);
            builder.withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE);
            builder.withValue(GroupMembership.GROUP_SOURCE_ID, myGroupsId);
            operationList.add(builder.build());
        }

        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, operationList);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        } catch (OperationApplicationException e) {
            Log.e(LOG_TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        }
    }

    public boolean isIgnorable() {
        return getDisplayName().length() == 0;
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
