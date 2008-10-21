/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.syncml.pim.vcard;

import java.util.List;
import java.util.ArrayList;

/**
 * The parameter class of VCardCreator.
 * This class standy by the person-contact in
 * Android system, we must use this class instance as parameter to transmit to
 * VCardCreator so that create vCard string.
 */
// TODO: rename the class name, next step
public class ContactStruct {
    public String company;
    /** MUST exist */
    public String name;
    /** maybe folding */
    public String notes;
    /** maybe folding */
    public String title;
    /** binary bytes of pic. */
    public byte[] photoBytes;
    /** mime_type col of images table */
    public String photoType;
    /** Only for GET. Use addPhoneList() to PUT. */
    public List<PhoneData> phoneList;
    /** Only for GET. Use addContactmethodList() to PUT. */
    public List<ContactMethod> contactmethodList;

    public static class PhoneData{
        /** maybe folding */
        public String data;
        public String type;
        public String label;
    }

    public static class ContactMethod{
        public String kind;
        public String type;
        public String data;
        public String label;
    }

    /**
     * Add a phone info to phoneList.
     * @param data phone number
     * @param type type col of content://contacts/phones
     * @param label lable col of content://contacts/phones
     */
    public void addPhone(String data, String type, String label){
        if(phoneList == null)
            phoneList = new ArrayList<PhoneData>();
        PhoneData st = new PhoneData();
        st.data = data;
        st.type = type;
        st.label = label;
        phoneList.add(st);
    }
    /**
     * Add a contactmethod info to contactmethodList.
     * @param data contact data
     * @param type type col of content://contacts/contact_methods
     */
    public void addContactmethod(String kind, String data, String type,
            String label){
        if(contactmethodList == null)
            contactmethodList = new ArrayList<ContactMethod>();
        ContactMethod st = new ContactMethod();
        st.kind = kind;
        st.data = data;
        st.type = type;
        st.label = label;
        contactmethodList.add(st);
    }
}
