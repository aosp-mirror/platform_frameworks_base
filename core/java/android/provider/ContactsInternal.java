/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */
package android.provider;

import android.annotation.UnsupportedAppUsage;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.UriMatcher;
import android.net.Uri;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.widget.Toast;

import java.util.List;

/**
 * Contacts related internal methods.
 *
 * @hide
 */
public class ContactsInternal {
    private ContactsInternal() {
    }

    /** URI matcher used to parse contact URIs. */
    private static final UriMatcher sContactsUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int CONTACTS_URI_LOOKUP_ID = 1000;
    private static final int CONTACTS_URI_LOOKUP = 1001;

    static {
        // Contacts URI matching table
        final UriMatcher matcher = sContactsUriMatcher;
        matcher.addURI(ContactsContract.AUTHORITY, "contacts/lookup/*", CONTACTS_URI_LOOKUP);
        matcher.addURI(ContactsContract.AUTHORITY, "contacts/lookup/*/#", CONTACTS_URI_LOOKUP_ID);
    }

    /**
     * Called by {@link ContactsContract} to star Quick Contact, possibly on the managed profile.
     */
    @UnsupportedAppUsage
    public static void startQuickContactWithErrorToast(Context context, Intent intent) {
        final Uri uri = intent.getData();

        final int match = sContactsUriMatcher.match(uri);
        switch (match) {
            case CONTACTS_URI_LOOKUP:
            case CONTACTS_URI_LOOKUP_ID: {
                if (maybeStartManagedQuickContact(context, intent)) {
                    return; // Request handled by DPM.  Just return here.
                }
                break;
            }
        }
        // Launch on the current profile.
        startQuickContactWithErrorToastForUser(context, intent, context.getUser());
    }

    public static void startQuickContactWithErrorToastForUser(Context context, Intent intent,
            UserHandle user) {
        try {
            context.startActivityAsUser(intent, user);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, com.android.internal.R.string.quick_contacts_not_available,
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * If the URI in {@code intent} is of a corp contact, launch quick contact on the managed
     * profile.
     *
     * @return the URI in {@code intent} is of a corp contact thus launched on the managed profile.
     */
    private static boolean maybeStartManagedQuickContact(Context context, Intent originalIntent) {
        final Uri uri = originalIntent.getData();

        // Decompose into an ID and a lookup key.
        final List<String> pathSegments = uri.getPathSegments();
        final boolean isContactIdIgnored = pathSegments.size() < 4;
        final long contactId = isContactIdIgnored
                ? ContactsContract.Contacts.ENTERPRISE_CONTACT_ID_BASE //contact id will be ignored
                : ContentUris.parseId(uri);
        final String lookupKey = pathSegments.get(2);
        final String directoryIdStr = uri.getQueryParameter(ContactsContract.DIRECTORY_PARAM_KEY);
        final long directoryId = (directoryIdStr == null)
                ? ContactsContract.Directory.ENTERPRISE_DIRECTORY_ID_BASE
                : Long.parseLong(directoryIdStr);

        // See if it has a corp lookupkey.
        if (TextUtils.isEmpty(lookupKey)
                || !lookupKey.startsWith(
                        ContactsContract.Contacts.ENTERPRISE_CONTACT_LOOKUP_PREFIX)) {
            return false; // It's not a corp lookup key.
        }

        if (!ContactsContract.Contacts.isEnterpriseContactId(contactId)) {
            throw new IllegalArgumentException("Invalid enterprise contact id: " + contactId);
        }
        if (!ContactsContract.Directory.isEnterpriseDirectoryId(directoryId)) {
            throw new IllegalArgumentException("Invalid enterprise directory id: " + directoryId);
        }

        // Launch Quick Contact on the managed profile, if the policy allows.
        final DevicePolicyManager dpm = context.getSystemService(DevicePolicyManager.class);
        final String actualLookupKey = lookupKey.substring(
                ContactsContract.Contacts.ENTERPRISE_CONTACT_LOOKUP_PREFIX.length());
        final long actualContactId =
                (contactId - ContactsContract.Contacts.ENTERPRISE_CONTACT_ID_BASE);
        final long actualDirectoryId = (directoryId
                - ContactsContract.Directory.ENTERPRISE_DIRECTORY_ID_BASE);

        dpm.startManagedQuickContact(actualLookupKey, actualContactId, isContactIdIgnored,
                actualDirectoryId, originalIntent);
        return true;
    }
}
