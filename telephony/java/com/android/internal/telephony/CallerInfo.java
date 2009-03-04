/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.internal.telephony;

import android.content.Context;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Contacts;
import android.provider.Contacts.People;
import android.provider.Contacts.Phones;
import android.text.TextUtils;
import android.telephony.TelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.util.Config;
import android.util.Log;

/**
 * Looks up caller information for the given phone number.
 *
 * {@hide}
 */
public class CallerInfo {
    private static final String TAG = "CallerInfo";

    public static final String UNKNOWN_NUMBER = "-1";
    public static final String PRIVATE_NUMBER = "-2";

    /**
     * Please note that, any one of these member variables can be null,
     * and any accesses to them should be prepared to handle such a case.
     *
     * Also, it is implied that phoneNumber is more often populated than
     * name is, (think of calls being dialed/received using numbers where
     * names are not known to the device), so phoneNumber should serve as
     * a dependable fallback when name is unavailable.
     *
     * One other detail here is that this CallerInfo object reflects
     * information found on a connection, it is an OUTPUT that serves
     * mainly to display information to the user.  In no way is this object
     * used as input to make a connection, so we can choose to display
     * whatever human-readable text makes sense to the user for a
     * connection.  This is especially relevant for the phone number field,
     * since it is the one field that is most likely exposed to the user.
     *
     * As an example:
     *   1. User dials "911"
     *   2. Device recognizes that this is an emergency number
     *   3. We use the "Emergency Number" string instead of "911" in the
     *     phoneNumber field.
     *
     * What we're really doing here is treating phoneNumber as an essential
     * field here, NOT name.  We're NOT always guaranteed to have a name
     * for a connection, but the number should be displayable.
     */
    public String name;
    public String phoneNumber;
    public String phoneLabel;
    /* Split up the phoneLabel into number type and label name */
    public int    numberType;
    public String numberLabel;
    
    public int photoResource;
    public long person_id;
    public boolean needUpdate;
    public Uri contactRefUri;
    
    // fields to hold individual contact preference data, 
    // including the send to voicemail flag and the ringtone
    // uri reference.
    public Uri contactRingtoneUri;
    public boolean shouldSendToVoicemail;

    /**
     * Drawable representing the caller image.  This is essentially
     * a cache for the image data tied into the connection /
     * callerinfo object.  The isCachedPhotoCurrent flag indicates
     * if the image data needs to be reloaded.
     */
    public Drawable cachedPhoto;
    public boolean isCachedPhotoCurrent;

    // Don't keep checking VM if it's going to throw an exception for this proc.
    private static boolean sSkipVmCheck = false;

    public CallerInfo() {
    }

    /**
     * getCallerInfo given a Cursor.
     * @param context the context used to retrieve string constants
     * @param contactRef the URI to attach to this CallerInfo object
     * @param cursor the first object in the cursor is used to build the CallerInfo object.
     * @return the CallerInfo which contains the caller id for the given
     * number. The returned CallerInfo is null if no number is supplied.
     */
    public static CallerInfo getCallerInfo(Context context, Uri contactRef, Cursor cursor) {
        
        CallerInfo info = new CallerInfo();
        info.photoResource = 0;
        info.phoneLabel = null;
        info.numberType = 0;
        info.numberLabel = null;
        info.cachedPhoto = null;
        info.isCachedPhotoCurrent = false;
        
        if (Config.LOGV) Log.v(TAG, "construct callerInfo from cursor");
        
        if (cursor != null) {
            if (cursor.moveToFirst()) {

                int columnIndex;

                // Look for the name
                columnIndex = cursor.getColumnIndex(People.NAME);
                if (columnIndex != -1) {
                    info.name = cursor.getString(columnIndex);
                }

                // Look for the number
                columnIndex = cursor.getColumnIndex(Phones.NUMBER);
                if (columnIndex != -1) {
                    info.phoneNumber = cursor.getString(columnIndex);
                }
                
                // Look for the label/type combo
                columnIndex = cursor.getColumnIndex(Phones.LABEL);
                if (columnIndex != -1) {
                    int typeColumnIndex = cursor.getColumnIndex(Phones.TYPE);
                    if (typeColumnIndex != -1) {
                        info.numberType = cursor.getInt(typeColumnIndex);
                        info.numberLabel = cursor.getString(columnIndex);
                        info.phoneLabel = Contacts.Phones.getDisplayLabel(context,
                                info.numberType, info.numberLabel)
                                .toString();
                    }
                }

                // Look for the person ID
                columnIndex = cursor.getColumnIndex(Phones.PERSON_ID);
                if (columnIndex != -1) {
                    info.person_id = cursor.getLong(columnIndex);
                } else {
                    columnIndex = cursor.getColumnIndex(People._ID);
                    if (columnIndex != -1) {
                        info.person_id = cursor.getLong(columnIndex);
                    }
                }
                
                // look for the custom ringtone, create from the string stored
                // in the database.
                columnIndex = cursor.getColumnIndex(People.CUSTOM_RINGTONE);
                if ((columnIndex != -1) && (cursor.getString(columnIndex) != null)) {
                    info.contactRingtoneUri = Uri.parse(cursor.getString(columnIndex));
                } else {
                    info.contactRingtoneUri = null;
                }

                // look for the send to voicemail flag, set it to true only
                // under certain circumstances.
                columnIndex = cursor.getColumnIndex(People.SEND_TO_VOICEMAIL);
                info.shouldSendToVoicemail = (columnIndex != -1) && 
                        ((cursor.getInt(columnIndex)) == 1);
            }
            cursor.close();
        }

        info.needUpdate = false;
        info.name = normalize(info.name);
        info.contactRefUri = contactRef;

        return info;
    }
    
    /**
     * getCallerInfo given a URI, look up in the call-log database
     * for the uri unique key.
     * @param context the context used to get the ContentResolver
     * @param contactRef the URI used to lookup caller id
     * @return the CallerInfo which contains the caller id for the given
     * number. The returned CallerInfo is null if no number is supplied.
     */
    public static CallerInfo getCallerInfo(Context context, Uri contactRef) {
        
        return getCallerInfo(context, contactRef, 
                context.getContentResolver().query(contactRef, null, null, null, null));
    }
    
    /**
     * getCallerInfo given a phone number, look up in the call-log database
     * for the matching caller id info.
     * @param context the context used to get the ContentResolver
     * @param number the phone number used to lookup caller id
     * @return the CallerInfo which contains the caller id for the given
     * number. The returned CallerInfo is null if no number is supplied. If
     * a matching number is not found, then a generic caller info is returned,
     * with all relevant fields empty or null.
     */
    public static CallerInfo getCallerInfo(Context context, String number) {
        if (TextUtils.isEmpty(number)) {
            return null;
        } else {
            // Change the callerInfo number ONLY if it is an emergency number
            // or if it is the voicemail number.  If it is either, take a 
            // shortcut and skip the query.
            if (PhoneNumberUtils.isEmergencyNumber(number)) {
                CallerInfo ci = new CallerInfo();

                // Note we're setting the phone number here (refer to javadoc
                // comments at the top of CallerInfo class). 
                ci.phoneNumber = context.getString(
                        com.android.internal.R.string.emergency_call_dialog_number_for_display);
                return ci;
            } else {
                try {
                    if (!sSkipVmCheck && PhoneNumberUtils.compare(number,
                                TelephonyManager.getDefault().getVoiceMailNumber())) {
                        CallerInfo ci = new CallerInfo();

                        // Note we're setting the phone number here (refer to javadoc
                        // comments at the top of CallerInfo class). 
                        ci.phoneNumber = TelephonyManager.getDefault().getVoiceMailAlphaTag();
                        // TODO: FIND ANOTHER ICON
                        //info.photoResource = android.R.drawable.badge_voicemail;
                        return ci;
                    }
                } catch (SecurityException ex) {
                    // Don't crash if this process doesn't have permission to 
                    // retrieve VM number.  It's still allowed to look up caller info.
                    // But don't try it again.
                    sSkipVmCheck = true;
                }
            }
        }

        Uri contactUri = Uri.withAppendedPath(Contacts.Phones.CONTENT_FILTER_URL,
                                              Uri.encode(number)); 
        
        CallerInfo info = getCallerInfo(context, contactUri);

        // if no query results were returned with a viable number, 
        // fill in the original number value we used to query with. 
        if (TextUtils.isEmpty(info.phoneNumber)) {
            info.phoneNumber = number;
        }
                
        return info;
    }

    /**
     * getCallerId: a convenience method to get the caller id for a given
     * number.
     *
     * @param context the context used to get the ContentResolver.
     * @param number a phone number.
     * @return if the number belongs to a contact, the contact's name is
     * returned; otherwise, the number itself is returned.
     * 
     * TODO NOTE: This MAY need to refer to the Asynchronous Query API 
     * [startQuery()], instead of getCallerInfo, but since it looks like 
     * it is only being used by the provider calls in the messaging app:
     *   1. android.provider.Telephony.Mms.getDisplayAddress()
     *   2. android.provider.Telephony.Sms.getDisplayAddress()
     * We may not need to make the change.
     */
    public static String getCallerId(Context context, String number) {
        CallerInfo info = getCallerInfo(context, number);
        String callerID = null;

        if (info != null) {
            String name = info.name;

            if (!TextUtils.isEmpty(name)) {
                callerID = name;
            } else {
                callerID = number;
            }
        }

        return callerID;
    }

    private static String normalize(String s) {
        if (s == null || s.length() > 0) {
            return s;
        } else {
            return null;
        }
    }
}    

