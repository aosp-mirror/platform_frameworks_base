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

package com.android.internal.widget;

import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.SocialContract;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Intents;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.ContactsContract.RawContacts;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.SocialContract.Activities;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.android.internal.R;


/* Widget that is used across system apps for displaying a header banner with contact info */
public class ContactHeaderWidget extends FrameLayout implements View.OnClickListener,
        View.OnLongClickListener {

    private static final String TAG = "ContactHeaderWidget";

    private TextView mDisplayNameView;
    private TextView mPhoneticNameView;
    private CheckBox mStarredView;
    private ImageView mPhotoView;
    private TextView mStatusView;
    private int mNoPhotoResource;
    private QueryHandler mQueryHandler;

    protected long mContactId;
    protected Uri mContactDataUri;
    protected Uri mContactUri;
    protected Uri mStatusUri;

    protected ContentResolver mContentResolver;

    public interface ContactHeaderListener {
        public void onPhotoLongClick(View view);
        public void onDisplayNameLongClick(View view);
    }

    private ContactHeaderListener mListener;

    //Projection used for the summary info in the header.
    protected static final String[] HEADER_PROJECTION = new String[] {
        Contacts.DISPLAY_NAME,
        Contacts.STARRED,
        Contacts.PHOTO_ID,
    };
    protected static final int HEADER_DISPLAY_NAME_COLUMN_INDEX = 0;
    //TODO: We need to figure out how we're going to get the phonetic name.
    //static final int HEADER_PHONETIC_NAME_COLUMN_INDEX
    protected static final int HEADER_STARRED_COLUMN_INDEX = 1;
    protected static final int HEADER_PHOTO_ID_COLUMN_INDEX = 2;

    //Projection used for finding the most recent social status.
    protected static final String[] SOCIAL_PROJECTION = new String[] {
        Activities.TITLE,
        Activities.PUBLISHED,
    };
    protected static final int SOCIAL_TITLE_COLUMN_INDEX = 0;
    protected static final int SOCIAL_PUBLISHED_COLUMN_INDEX = 1;

    //Projection used for looking up contact id from phone number
    protected static final String[] PHONE_LOOKUP_PROJECTION = new String[] {
        RawContacts.CONTACT_ID,
    };
    protected static final int PHONE_LOOKUP_CONTACT_ID_COLUMN_INDEX = 0;

    //Projection used for looking up contact id from email address
    protected static final String[] EMAIL_LOOKUP_PROJECTION = new String[] {
        RawContacts.CONTACT_ID,
    };
    protected static final int EMAIL_LOOKUP_CONTACT_ID_COLUMN_INDEX = 0;


    private static final int TOKEN_CONTACT_INFO = 0;
    private static final int TOKEN_SOCIAL = 1;

    public ContactHeaderWidget(Context context) {
        this(context, null);
    }

    public ContactHeaderWidget(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ContactHeaderWidget(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        mContentResolver = mContext.getContentResolver();

        LayoutInflater inflater =
            (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.contact_header, this);

        mDisplayNameView = (TextView) findViewById(R.id.name);
        mDisplayNameView.setOnLongClickListener(this);

        mPhoneticNameView = (TextView) findViewById(R.id.phonetic_name);

        mStarredView = (CheckBox)findViewById(R.id.star);
        mStarredView.setOnClickListener(this);

        mPhotoView = (ImageView)findViewById(R.id.photo);
        mPhotoView.setOnClickListener(this);
        mPhotoView.setOnLongClickListener(this);

        mStatusView = (TextView)findViewById(R.id.status);

        // Set the photo with a random "no contact" image
        long now = SystemClock.elapsedRealtime();
        int num = (int) now & 0xf;
        if (num < 9) {
            // Leaning in from right, common
            mNoPhotoResource = R.drawable.ic_contact_picture;
        } else if (num < 14) {
            // Leaning in from left uncommon
            mNoPhotoResource = R.drawable.ic_contact_picture_2;
        } else {
            // Coming in from the top, rare
            mNoPhotoResource = R.drawable.ic_contact_picture_3;
        }

        mQueryHandler = new QueryHandler(mContentResolver);
    }

    public void setContactHeaderListener(ContactHeaderListener listener) {
        mListener = listener;
    }

    /** {@inheritDoc} */
    public boolean onLongClick(View v) {
        switch (v.getId()) {
            case R.id.photo:
                performPhotoLongClick();
                return true;
            case R.id.name:
                performDisplayNameLongClick();
                return true;
        }
        return false;
    }

    private void performPhotoLongClick() {
        if (mListener != null) {
            mListener.onPhotoLongClick(mPhotoView);
        }
    }

    private void performDisplayNameLongClick() {
        if (mListener != null) {
            mListener.onDisplayNameLongClick(mDisplayNameView);
        }
    }

    private class QueryHandler extends AsyncQueryHandler {

        public QueryHandler(ContentResolver cr) {
            super(cr);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            try{
                if (token == TOKEN_CONTACT_INFO) {
                    bindContactInfo(cursor);
                    invalidate();
                } else if (token == TOKEN_SOCIAL) {
                    bindSocial(cursor);
                    invalidate();
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }


    /** {@inheritDoc} */
    public void onQueryComplete(int token, Object cookie, Cursor cursor) {
        try{
            if (token == TOKEN_CONTACT_INFO) {
                bindContactInfo(cursor);
                invalidate();
            } else if (token == TOKEN_SOCIAL) {
                bindSocial(cursor);
                invalidate();
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Turn on/off showing of the star element.
     */
    public void showStar(boolean showStar) {
        mStarredView.setVisibility(showStar ? View.VISIBLE : View.GONE);
    }

    /**
     * Convenience method for binding all available data from an existing
     * contact.
     *
     * @param contactId the contact id of the contact whose info should be displayed.
     */
    public void bindFromContactId(long contactId) {
        mContactId = contactId;
        mContactUri = ContentUris.withAppendedId(Contacts.CONTENT_URI, mContactId);
        mContactDataUri = Uri.withAppendedPath(mContactUri, Contacts.Data.CONTENT_DIRECTORY);
        mStatusUri = ContentUris.withAppendedId(
                SocialContract.Activities.CONTENT_CONTACT_STATUS_URI, mContactId);
        redrawHeader();
    }

    /**
     * Convenience method for binding all available data from an existing
     * contact.
     *
     * @param emailAddress The email address used to do a reverse lookup in
     * the contacts database. If more than one contact contains this email
     * address, one of them will be chosen to bind to.
     */
    public void bindFromEmail(String emailAddress) {
        Cursor c = null;
        try {
            c = mContentResolver.query(Uri.withAppendedPath(
                    RawContacts.CONTENT_FILTER_EMAIL_URI, Uri.encode(emailAddress)),
                    EMAIL_LOOKUP_PROJECTION, null, null, null);
            if (c.moveToFirst()) {
                long contactId = c.getLong(EMAIL_LOOKUP_CONTACT_ID_COLUMN_INDEX);
                bindFromContactId(contactId);
            } else {
                bindStatic(emailAddress, "");
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Convenience method for binding all available data from an existing
     * contact.
     *
     * @param number The phone number used to do a reverse lookup in
     * the contacts database. If more than one contact contains this phone
     * number, one of them will be chosen to bind to.
     */
    public void bindFromPhoneNumber(String number) {
        Cursor c = null;
        try {
            c = mContentResolver.query(Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, number),
                    PHONE_LOOKUP_PROJECTION, null, null, null);
            if (c.moveToFirst()) {
                long contactId = c.getLong(PHONE_LOOKUP_CONTACT_ID_COLUMN_INDEX);
                bindFromContactId(contactId);
            } else {
                bindStatic(number, "");
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    public void bindStatic(String main, String secondary) {
        mDisplayNameView.setText(main);
        mStatusView.setText(secondary);
        mStarredView.setVisibility(View.GONE);
        mPhotoView.setImageBitmap(loadPlaceholderPhoto(null));
    }

    protected void redrawHeader() {
        if (mContactDataUri != null) {
            mQueryHandler.startQuery(TOKEN_CONTACT_INFO, null, mContactDataUri, HEADER_PROJECTION,
                    null, null, null);
        }

        if (mStatusUri != null) {
            mQueryHandler.startQuery(TOKEN_SOCIAL, null, mStatusUri, SOCIAL_PROJECTION,
                    null, null, null);
        }
    }

    protected void bindContactInfo(Cursor c) {
        if (c == null) {
            return;
        }
        if (c.moveToFirst()) {
            //Set name
            String displayName = c.getString(HEADER_DISPLAY_NAME_COLUMN_INDEX);
            Log.i(TAG, displayName);
            mDisplayNameView.setText(displayName);
            //TODO: Bring back phonetic name
            /*if (mPhoneticNameView != null) {
                String phoneticName = c.getString(CONTACT_PHONETIC_NAME_COLUMN);
                mPhoneticNameView.setText(phoneticName);
            }*/

            //Set starred
            mStarredView.setChecked(c.getInt(HEADER_STARRED_COLUMN_INDEX) == 1);

            //Set the photo
            Bitmap photoBitmap = loadContactPhoto(c.getLong(HEADER_PHOTO_ID_COLUMN_INDEX), null);
            if (photoBitmap == null) {
                photoBitmap = loadPlaceholderPhoto(null);
            }
            mPhotoView.setImageBitmap(photoBitmap);
        }
    }

    protected void bindSocial(Cursor c) {
        if (c == null) {
            return;
        }
        if (c.moveToFirst()) {
            String status = c.getString(SOCIAL_TITLE_COLUMN_INDEX);
            mStatusView.setText(status);
        }
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.star: {
                // Toggle "starred" state
                final ContentValues values = new ContentValues(1);
                values.put(Contacts.STARRED, mStarredView.isChecked());
                mContentResolver.update(mContactUri, values, null, null);
                break;
            }
            case R.id.photo: {
                // Photo launches contact detail action
                final Intent intent = new Intent(Intents.SHOW_OR_CREATE_CONTACT, mContactUri);
                final Rect target = getTargetRect(view);
                intent.putExtra(Intents.EXTRA_TARGET_RECT, target);
                intent.putExtra(Intents.EXTRA_MODE, Intents.MODE_SMALL);
                mContext.startActivity(intent);
                break;
            }
        }
    }

    private Rect getTargetRect(View anchor) {
        final int[] location = new int[2];
        anchor.getLocationOnScreen(location);

        final Rect rect = new Rect();
        rect.left = location[0];
        rect.top = location[1];
        rect.right = rect.left + anchor.getWidth();
        rect.bottom = rect.top + anchor.getHeight();
        return rect;
    }

    private Bitmap loadContactPhoto(long photoId, BitmapFactory.Options options) {
        Cursor photoCursor = null;
        Bitmap photoBm = null;

        try {
            photoCursor = mContentResolver.query(
                    ContentUris.withAppendedId(Data.CONTENT_URI, photoId),
                    new String[] { Photo.PHOTO },
                    null, null, null);

            if (photoCursor.moveToFirst() && !photoCursor.isNull(0)) {
                byte[] photoData = photoCursor.getBlob(0);
                photoBm = BitmapFactory.decodeByteArray(photoData, 0,
                        photoData.length, options);
            }
        } finally {
            if (photoCursor != null) {
                photoCursor.close();
            }
        }

        return photoBm;
    }

    private Bitmap loadPlaceholderPhoto(BitmapFactory.Options options) {
        if (mNoPhotoResource == 0) {
            return null;
        }
        return BitmapFactory.decodeResource(mContext.getResources(),
                mNoPhotoResource, options);
    }
}
