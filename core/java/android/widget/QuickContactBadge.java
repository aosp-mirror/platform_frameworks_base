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

package android.widget;

import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Intents;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.ContactsContract.QuickContact;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import com.android.internal.R;

/**
 * Widget used to show an image with the standard QuickContact badge
 * and on-click behavior.
 */
public class QuickContactBadge extends ImageView implements OnClickListener {

    private Uri mContactUri;
    private String mContactEmail;
    private String mContactPhone;
    private int mMode;
    private QueryHandler mQueryHandler;
    private Drawable mBadgeBackground;
    private Drawable mNoBadgeBackground;

    protected String[] mExcludeMimes = null;

    static final private int TOKEN_EMAIL_LOOKUP = 0;
    static final private int TOKEN_PHONE_LOOKUP = 1;
    static final private int TOKEN_EMAIL_LOOKUP_AND_TRIGGER = 2;
    static final private int TOKEN_PHONE_LOOKUP_AND_TRIGGER = 3;
    static final private int TOKEN_CONTACT_LOOKUP_AND_TRIGGER = 4;

    static final String[] EMAIL_LOOKUP_PROJECTION = new String[] {
        RawContacts.CONTACT_ID,
        Contacts.LOOKUP_KEY,
    };
    static final int EMAIL_ID_COLUMN_INDEX = 0;
    static final int EMAIL_LOOKUP_STRING_COLUMN_INDEX = 1;

    static final String[] PHONE_LOOKUP_PROJECTION = new String[] {
        PhoneLookup._ID,
        PhoneLookup.LOOKUP_KEY,
    };
    static final int PHONE_ID_COLUMN_INDEX = 0;
    static final int PHONE_LOOKUP_STRING_COLUMN_INDEX = 1;

    static final String[] CONTACT_LOOKUP_PROJECTION = new String[] {
        Contacts._ID,
        Contacts.LOOKUP_KEY,
    };
    static final int CONTACT_ID_COLUMN_INDEX = 0;
    static final int CONTACT_LOOKUPKEY_COLUMN_INDEX = 1;


    public QuickContactBadge(Context context) {
        this(context, null);
    }

    public QuickContactBadge(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public QuickContactBadge(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        TypedArray a =
            context.obtainStyledAttributes(attrs,
                    com.android.internal.R.styleable.QuickContactBadge, defStyle, 0);

        mMode = a.getInt(com.android.internal.R.styleable.QuickContactBadge_quickContactWindowSize,
                QuickContact.MODE_MEDIUM);

        a.recycle();

        init();

        mBadgeBackground = getBackground();
    }

    private void init() {
        mQueryHandler = new QueryHandler(mContext.getContentResolver());
        setOnClickListener(this);
    }

    /**
     * Set the QuickContact window mode. Options are {@link QuickContact#MODE_SMALL},
     * {@link QuickContact#MODE_MEDIUM}, {@link QuickContact#MODE_LARGE}.
     * @param size
     */
    public void setMode(int size) {
        mMode = size;
    }

    /**
     * Assign the contact uri that this QuickContactBadge should be associated
     * with. Note that this is only used for displaying the QuickContact window and
     * won't bind the contact's photo for you.
     *
     * @param contactUri Either a {@link Contacts#CONTENT_URI} or
     *            {@link Contacts#CONTENT_LOOKUP_URI} style URI.
     */
    public void assignContactUri(Uri contactUri) {
        mContactUri = contactUri;
        mContactEmail = null;
        mContactPhone = null;
        onContactUriChanged();
    }

    private void onContactUriChanged() {
        if (mContactUri == null && mContactEmail == null && mContactPhone == null) {
            if (mNoBadgeBackground == null) {
                mNoBadgeBackground = getResources().getDrawable(R.drawable.quickcontact_nobadge);
            }
            setBackgroundDrawable(mNoBadgeBackground);
        } else {
            setBackgroundDrawable(mBadgeBackground);
        }
    }

    /**
     * Assign a contact based on an email address. This should only be used when
     * the contact's URI is not available, as an extra query will have to be
     * performed to lookup the URI based on the email.
     *
     * @param emailAddress The email address of the contact.
     * @param lazyLookup If this is true, the lookup query will not be performed
     * until this view is clicked.
     */
    public void assignContactFromEmail(String emailAddress, boolean lazyLookup) {
        mContactEmail = emailAddress;
        if (!lazyLookup) {
            mQueryHandler.startQuery(TOKEN_EMAIL_LOOKUP, null,
                    Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode(mContactEmail)),
                    EMAIL_LOOKUP_PROJECTION, null, null, null);
        } else {
            mContactUri = null;
            onContactUriChanged();
        }
    }

    /**
     * Assign a contact based on a phone number. This should only be used when
     * the contact's URI is not available, as an extra query will have to be
     * performed to lookup the URI based on the phone number.
     *
     * @param phoneNumber The phone number of the contact.
     * @param lazyLookup If this is true, the lookup query will not be performed
     * until this view is clicked.
     */
    public void assignContactFromPhone(String phoneNumber, boolean lazyLookup) {
        mContactPhone = phoneNumber;
        if (!lazyLookup) {
            mQueryHandler.startQuery(TOKEN_PHONE_LOOKUP, null,
                    Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, mContactPhone),
                    PHONE_LOOKUP_PROJECTION, null, null, null);
        } else {
            mContactUri = null;
            onContactUriChanged();
        }
    }

    public void onClick(View v) {
        if (mContactUri != null) {
            mQueryHandler.startQuery(TOKEN_CONTACT_LOOKUP_AND_TRIGGER, null,
                    mContactUri,
                    CONTACT_LOOKUP_PROJECTION, null, null, null);
        } else if (mContactEmail != null) {
            mQueryHandler.startQuery(TOKEN_EMAIL_LOOKUP_AND_TRIGGER, mContactEmail,
                    Uri.withAppendedPath(Email.CONTENT_LOOKUP_URI, Uri.encode(mContactEmail)),
                    EMAIL_LOOKUP_PROJECTION, null, null, null);
        } else if (mContactPhone != null) {
            mQueryHandler.startQuery(TOKEN_PHONE_LOOKUP_AND_TRIGGER, mContactPhone,
                    Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, mContactPhone),
                    PHONE_LOOKUP_PROJECTION, null, null, null);
        } else {
            // If a contact hasn't been assigned, don't react to click.
            return;
        }
    }

    /**
     * Set a list of specific MIME-types to exclude and not display. For
     * example, this can be used to hide the {@link Contacts#CONTENT_ITEM_TYPE}
     * profile icon.
     */
    public void setExcludeMimes(String[] excludeMimes) {
        mExcludeMimes = excludeMimes;
    }

    private void trigger(Uri lookupUri) {
        QuickContact.showQuickContact(getContext(), this, lookupUri, mMode, mExcludeMimes);
    }

    private class QueryHandler extends AsyncQueryHandler {

        public QueryHandler(ContentResolver cr) {
            super(cr);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            Uri lookupUri = null;
            Uri createUri = null;
            boolean trigger = false;

            try {
                switch(token) {
                    case TOKEN_PHONE_LOOKUP_AND_TRIGGER:
                        trigger = true;
                        createUri = Uri.fromParts("tel", (String)cookie, null);

                    case TOKEN_PHONE_LOOKUP: {
                        if (cursor != null && cursor.moveToFirst()) {
                            long contactId = cursor.getLong(PHONE_ID_COLUMN_INDEX);
                            String lookupKey = cursor.getString(PHONE_LOOKUP_STRING_COLUMN_INDEX);
                            lookupUri = Contacts.getLookupUri(contactId, lookupKey);
                        }

                        break;
                    }
                    case TOKEN_EMAIL_LOOKUP_AND_TRIGGER:
                        trigger = true;
                        createUri = Uri.fromParts("mailto", (String)cookie, null);

                    case TOKEN_EMAIL_LOOKUP: {
                        if (cursor != null && cursor.moveToFirst()) {
                            long contactId = cursor.getLong(EMAIL_ID_COLUMN_INDEX);
                            String lookupKey = cursor.getString(EMAIL_LOOKUP_STRING_COLUMN_INDEX);
                            lookupUri = Contacts.getLookupUri(contactId, lookupKey);
                        }
                    }

                    case TOKEN_CONTACT_LOOKUP_AND_TRIGGER: {
                        if (cursor != null && cursor.moveToFirst()) {
                            long contactId = cursor.getLong(CONTACT_ID_COLUMN_INDEX);
                            String lookupKey = cursor.getString(CONTACT_LOOKUPKEY_COLUMN_INDEX);
                            lookupUri = Contacts.getLookupUri(contactId, lookupKey);
                            trigger = true;
                        }

                        break;
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }

            mContactUri = lookupUri;
            onContactUriChanged();

            if (trigger && lookupUri != null) {
                // Found contact, so trigger track
                trigger(lookupUri);
            } else if (createUri != null) {
                // Prompt user to add this person to contacts
                final Intent intent = new Intent(Intents.SHOW_OR_CREATE_CONTACT, createUri);
                getContext().startActivity(intent);
            }
        }
    }
}
