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

package android.media;

import com.android.internal.database.SortCursor;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.app.Activity;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DrmStore;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Settings.System;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * RingtoneManager provides access to ringtones, notification, and other types
 * of sounds. It manages querying the different media providers and combines the
 * results into a single cursor. It also provides a {@link Ringtone} for each
 * ringtone. We generically call these sounds ringtones, however the
 * {@link #TYPE_RINGTONE} refers to the type of sounds that are suitable for the
 * phone ringer.
 * <p>
 * To show a ringtone picker to the user, use the
 * {@link #ACTION_RINGTONE_PICKER} intent to launch the picker as a subactivity.
 * 
 * @see Ringtone
 */
public class RingtoneManager {

    private static final String TAG = "RingtoneManager";

    // Make sure these are in sync with attrs.xml:
    // <attr name="ringtoneType">
    
    /**
     * Type that refers to sounds that are used for the phone ringer.
     */
    public static final int TYPE_RINGTONE = 1;
    
    /**
     * Type that refers to sounds that are used for notifications.
     */
    public static final int TYPE_NOTIFICATION = 2;
    
    /**
     * Type that refers to sounds that are used for the alarm.
     */
    public static final int TYPE_ALARM = 4;
    
    /**
     * All types of sounds.
     */
    public static final int TYPE_ALL = TYPE_RINGTONE | TYPE_NOTIFICATION | TYPE_ALARM;
    
    // </attr>
    
    /**
     * Activity Action: Shows a ringtone picker.
     * <p>
     * Input: {@link #EXTRA_RINGTONE_EXISTING_URI},
     * {@link #EXTRA_RINGTONE_SHOW_DEFAULT},
     * {@link #EXTRA_RINGTONE_SHOW_SILENT}, {@link #EXTRA_RINGTONE_TYPE},
     * {@link #EXTRA_RINGTONE_DEFAULT_URI}, {@link #EXTRA_RINGTONE_TITLE},
     * {@link #EXTRA_RINGTONE_INCLUDE_DRM}.
     * <p>
     * Output: {@link #EXTRA_RINGTONE_PICKED_URI}.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_RINGTONE_PICKER = "android.intent.action.RINGTONE_PICKER";

    /**
     * Given to the ringtone picker as a boolean. Whether to show an item for
     * "Default".
     * 
     * @see #ACTION_RINGTONE_PICKER
     */
    public static final String EXTRA_RINGTONE_SHOW_DEFAULT =
            "android.intent.extra.ringtone.SHOW_DEFAULT";
    
    /**
     * Given to the ringtone picker as a boolean. Whether to show an item for
     * "Silent". If the "Silent" item is picked,
     * {@link #EXTRA_RINGTONE_PICKED_URI} will be null.
     * 
     * @see #ACTION_RINGTONE_PICKER
     */
    public static final String EXTRA_RINGTONE_SHOW_SILENT =
            "android.intent.extra.ringtone.SHOW_SILENT";

    /**
     * Given to the ringtone picker as a boolean. Whether to include DRM ringtones.
     */
    public static final String EXTRA_RINGTONE_INCLUDE_DRM =
            "android.intent.extra.ringtone.INCLUDE_DRM";
    
    /**
     * Given to the ringtone picker as a {@link Uri}. The {@link Uri} of the
     * current ringtone, which will be used to show a checkmark next to the item
     * for this {@link Uri}. If showing an item for "Default" (@see
     * {@link #EXTRA_RINGTONE_SHOW_DEFAULT}), this can also be one of
     * {@link System#DEFAULT_RINGTONE_URI},
     * {@link System#DEFAULT_NOTIFICATION_URI}, or
     * {@link System#DEFAULT_ALARM_ALERT_URI} to have the "Default" item
     * checked.
     * 
     * @see #ACTION_RINGTONE_PICKER
     */
    public static final String EXTRA_RINGTONE_EXISTING_URI =
            "android.intent.extra.ringtone.EXISTING_URI";
    
    /**
     * Given to the ringtone picker as a {@link Uri}. The {@link Uri} of the
     * ringtone to play when the user attempts to preview the "Default"
     * ringtone. This can be one of {@link System#DEFAULT_RINGTONE_URI},
     * {@link System#DEFAULT_NOTIFICATION_URI}, or
     * {@link System#DEFAULT_ALARM_ALERT_URI} to have the "Default" point to
     * the current sound for the given default sound type. If you are showing a
     * ringtone picker for some other type of sound, you are free to provide any
     * {@link Uri} here.
     */
    public static final String EXTRA_RINGTONE_DEFAULT_URI =
            "android.intent.extra.ringtone.DEFAULT_URI";
    
    /**
     * Given to the ringtone picker as an int. Specifies which ringtone type(s) should be
     * shown in the picker. One or more of {@link #TYPE_RINGTONE},
     * {@link #TYPE_NOTIFICATION}, {@link #TYPE_ALARM}, or {@link #TYPE_ALL}
     * (bitwise-ored together).
     */
    public static final String EXTRA_RINGTONE_TYPE = "android.intent.extra.ringtone.TYPE";

    /**
     * Given to the ringtone picker as a {@link CharSequence}. The title to
     * show for the ringtone picker. This has a default value that is suitable
     * in most cases.
     */
    public static final String EXTRA_RINGTONE_TITLE = "android.intent.extra.ringtone.TITLE";
    
    /**
     * Returned from the ringtone picker as a {@link Uri}.
     * <p>
     * It will be one of:
     * <li> the picked ringtone,
     * <li> a {@link Uri} that equals {@link System#DEFAULT_RINGTONE_URI},
     * {@link System#DEFAULT_NOTIFICATION_URI}, or
     * {@link System#DEFAULT_ALARM_ALERT_URI} if the default was chosen,
     * <li> null if the "Silent" item was picked.
     * 
     * @see #ACTION_RINGTONE_PICKER
     */
    public static final String EXTRA_RINGTONE_PICKED_URI =
            "android.intent.extra.ringtone.PICKED_URI";
    
    // Make sure the column ordering and then ..._COLUMN_INDEX are in sync
    
    private static final String[] INTERNAL_COLUMNS = new String[] {
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
        "\"" + MediaStore.Audio.Media.INTERNAL_CONTENT_URI + "\"",
        MediaStore.Audio.Media.TITLE_KEY
    };

    private static final String[] DRM_COLUMNS = new String[] {
        DrmStore.Audio._ID, DrmStore.Audio.TITLE,
        "\"" + DrmStore.Audio.CONTENT_URI + "\"",
        DrmStore.Audio.TITLE + " AS " + MediaStore.Audio.Media.TITLE_KEY
    };

    private static final String[] MEDIA_COLUMNS = new String[] {
        MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE,
        "\"" + MediaStore.Audio.Media.EXTERNAL_CONTENT_URI + "\"",
        MediaStore.Audio.Media.TITLE_KEY
    };
    
    /**
     * The column index (in the cursor returned by {@link #getCursor()} for the
     * row ID.
     */
    public static final int ID_COLUMN_INDEX = 0;

    /**
     * The column index (in the cursor returned by {@link #getCursor()} for the
     * title.
     */
    public static final int TITLE_COLUMN_INDEX = 1;

    /**
     * The column index (in the cursor returned by {@link #getCursor()} for the
     * media provider's URI.
     */
    public static final int URI_COLUMN_INDEX = 2;

    private Activity mActivity;
    private Context mContext;
    
    private Cursor mCursor;

    private int mType = TYPE_RINGTONE;
    
    /**
     * If a column (item from this list) exists in the Cursor, its value must
     * be true (value of 1) for the row to be returned.
     */
    private final List<String> mFilterColumns = new ArrayList<String>();
    
    private boolean mStopPreviousRingtone = true;
    private Ringtone mPreviousRingtone;

    private boolean mIncludeDrm;
    
    /**
     * Constructs a RingtoneManager. This constructor is recommended as its
     * constructed instance manages cursor(s).
     * 
     * @param activity The activity used to get a managed cursor.
     */
    public RingtoneManager(Activity activity) {
        mContext = mActivity = activity;
        setType(mType);
    }

    /**
     * Constructs a RingtoneManager. The instance constructed by this
     * constructor will not manage the cursor(s), so the client should handle
     * this itself.
     * 
     * @param context The context to used to get a cursor.
     */
    public RingtoneManager(Context context) {
        mContext = context;
        setType(mType);
    }

    /**
     * Sets which type(s) of ringtones will be listed by this.
     * 
     * @param type The type(s), one or more of {@link #TYPE_RINGTONE},
     *            {@link #TYPE_NOTIFICATION}, {@link #TYPE_ALARM},
     *            {@link #TYPE_ALL}.
     * @see #EXTRA_RINGTONE_TYPE           
     */
    public void setType(int type) {

        if (mCursor != null) {
            throw new IllegalStateException(
                    "Setting filter columns should be done before querying for ringtones.");
        }
        
        mType = type;
        setFilterColumnsList(type);
    }

    /**
     * Infers the playback stream type based on what type of ringtones this
     * manager is returning.
     * 
     * @return The stream type.
     */
    public int inferStreamType() {
        switch (mType) {
            
            case TYPE_ALARM:
                return AudioManager.STREAM_ALARM;
                
            case TYPE_NOTIFICATION:
                return AudioManager.STREAM_NOTIFICATION;
                
            default:
                return AudioManager.STREAM_RING;
        }
    }
    
    /**
     * Whether retrieving another {@link Ringtone} will stop playing the
     * previously retrieved {@link Ringtone}.
     * <p>
     * If this is false, make sure to {@link Ringtone#stop()} any previous
     * ringtones to free resources.
     * 
     * @param stopPreviousRingtone If true, the previously retrieved
     *            {@link Ringtone} will be stopped.
     */
    public void setStopPreviousRingtone(boolean stopPreviousRingtone) {
        mStopPreviousRingtone = stopPreviousRingtone;
    }

    /**
     * @see #setStopPreviousRingtone(boolean)
     */
    public boolean getStopPreviousRingtone() {
        return mStopPreviousRingtone;
    }

    /**
     * Stops playing the last {@link Ringtone} retrieved from this.
     */
    public void stopPreviousRingtone() {
        if (mPreviousRingtone != null) {
            mPreviousRingtone.stop();
        }
    }
    
    /**
     * Returns whether DRM ringtones will be included.
     * 
     * @return Whether DRM ringtones will be included.
     * @see #setIncludeDrm(boolean)
     */
    public boolean getIncludeDrm() {
        return mIncludeDrm;
    }

    /**
     * Sets whether to include DRM ringtones.
     * 
     * @param includeDrm Whether to include DRM ringtones.
     */
    public void setIncludeDrm(boolean includeDrm) {
        mIncludeDrm = includeDrm;
    }

    /**
     * Returns a {@link Cursor} of all the ringtones available. The returned
     * cursor will be the same cursor returned each time this method is called,
     * so do not {@link Cursor#close()} the cursor. The cursor can be
     * {@link Cursor#deactivate()} safely.
     * <p>
     * If {@link RingtoneManager#RingtoneManager(Activity)} was not used, the
     * caller should manage the returned cursor through its activity's life
     * cycle to prevent leaking the cursor.
     * 
     * @return A {@link Cursor} of all the ringtones available.
     * @see #ID_COLUMN_INDEX
     * @see #TITLE_COLUMN_INDEX
     * @see #URI_COLUMN_INDEX
     */
    public Cursor getCursor() {
        if (mCursor != null && mCursor.requery()) {
            return mCursor;
        }
        
        final Cursor internalCursor = getInternalRingtones();
        final Cursor drmCursor = mIncludeDrm ? getDrmRingtones() : null;
        final Cursor mediaCursor = getMediaRingtones();
             
        return mCursor = new SortCursor(new Cursor[] { internalCursor, drmCursor, mediaCursor },
                MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
    }

    /**
     * Gets a {@link Ringtone} for the ringtone at the given position in the
     * {@link Cursor}.
     * 
     * @param position The position (in the {@link Cursor}) of the ringtone.
     * @return A {@link Ringtone} pointing to the ringtone.
     */
    public Ringtone getRingtone(int position) {
        if (mStopPreviousRingtone && mPreviousRingtone != null) {
            mPreviousRingtone.stop();
        }
        
        mPreviousRingtone = getRingtone(mContext, getRingtoneUri(position), inferStreamType());
        return mPreviousRingtone;
    }

    /**
     * Gets a {@link Uri} for the ringtone at the given position in the {@link Cursor}.
     * 
     * @param position The position (in the {@link Cursor}) of the ringtone.
     * @return A {@link Uri} pointing to the ringtone.
     */
    public Uri getRingtoneUri(int position) {
        final Cursor cursor = getCursor();
        
        if (!cursor.moveToPosition(position)) {
            return null;
        }
        
        return getUriFromCursor(cursor);
    }
    
    private static Uri getUriFromCursor(Cursor cursor) {
        return ContentUris.withAppendedId(Uri.parse(cursor.getString(URI_COLUMN_INDEX)), cursor
                .getLong(ID_COLUMN_INDEX));
    }
    
    /**
     * Gets the position of a {@link Uri} within this {@link RingtoneManager}.
     * 
     * @param ringtoneUri The {@link Uri} to retreive the position of.
     * @return The position of the {@link Uri}, or -1 if it cannot be found.
     */
    public int getRingtonePosition(Uri ringtoneUri) {
        
        if (ringtoneUri == null) return -1;
        
        final Cursor cursor = getCursor();
        final int cursorCount = cursor.getCount();
        
        if (!cursor.moveToFirst()) {
            return -1;
        }
        
        // Only create Uri objects when the actual URI changes
        Uri currentUri = null;
        String previousUriString = null;
        for (int i = 0; i < cursorCount; i++) {
            String uriString = cursor.getString(URI_COLUMN_INDEX);
            if (currentUri == null || !uriString.equals(previousUriString)) {
                currentUri = Uri.parse(uriString);
            }
            
            if (ringtoneUri.equals(ContentUris.withAppendedId(currentUri, cursor
                    .getLong(ID_COLUMN_INDEX)))) {
                return i;
            }
            
            cursor.move(1);
            
            previousUriString = uriString;
        }
        
        return -1;
    }

    /**
     * Returns a valid ringtone URI. No guarantees on which it returns. If it
     * cannot find one, returns null.
     * 
     * @param context The context to use for querying.
     * @return A ringtone URI, or null if one cannot be found.
     */
    public static Uri getValidRingtoneUri(Context context) {
        final RingtoneManager rm = new RingtoneManager(context);
        
        Uri uri = getValidRingtoneUriFromCursorAndClose(context, rm.getInternalRingtones());

        if (uri == null) {
            uri = getValidRingtoneUriFromCursorAndClose(context, rm.getMediaRingtones());
        }
        
        if (uri == null) {
            uri = getValidRingtoneUriFromCursorAndClose(context, rm.getDrmRingtones());
        }
        
        return uri;
    }
    
    private static Uri getValidRingtoneUriFromCursorAndClose(Context context, Cursor cursor) {
        if (cursor != null) {
            Uri uri = null;
            
            if (cursor.moveToFirst()) {
                uri = getUriFromCursor(cursor);
            }
            cursor.close();
            
            return uri;
        } else {
            return null;
        }
    }

    private Cursor getInternalRingtones() {
        return query(
                MediaStore.Audio.Media.INTERNAL_CONTENT_URI, INTERNAL_COLUMNS,
                constructBooleanTrueWhereClause(mFilterColumns, mIncludeDrm),
                null, MediaStore.Audio.Media.DEFAULT_SORT_ORDER);
    }
    
    private Cursor getDrmRingtones() {
        // DRM store does not have any columns to use for filtering 
        return query(
                DrmStore.Audio.CONTENT_URI, DRM_COLUMNS,
                null, null, DrmStore.Audio.TITLE);
    }

    private Cursor getMediaRingtones() {
         // Get the external media cursor. First check to see if it is mounted.
        final String status = Environment.getExternalStorageState();
        
        return (status.equals(Environment.MEDIA_MOUNTED) ||
                    status.equals(Environment.MEDIA_MOUNTED_READ_ONLY))
                ? query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, MEDIA_COLUMNS,
                    constructBooleanTrueWhereClause(mFilterColumns, mIncludeDrm), null,
                    MediaStore.Audio.Media.DEFAULT_SORT_ORDER)
                : null;
    }
    
    private void setFilterColumnsList(int type) {
        List<String> columns = mFilterColumns;
        columns.clear();
        
        if ((type & TYPE_RINGTONE) != 0) {
            columns.add(MediaStore.Audio.AudioColumns.IS_RINGTONE);
        }
        
        if ((type & TYPE_NOTIFICATION) != 0) {
            columns.add(MediaStore.Audio.AudioColumns.IS_NOTIFICATION);
        }
        
        if ((type & TYPE_ALARM) != 0) {
            columns.add(MediaStore.Audio.AudioColumns.IS_ALARM);
        }
    }
    
    /**
     * Constructs a where clause that consists of at least one column being 1
     * (true). This is used to find all matching sounds for the given sound
     * types (ringtone, notifications, etc.)
     * 
     * @param columns The columns that must be true.
     * @return The where clause.
     */
    private static String constructBooleanTrueWhereClause(List<String> columns, boolean includeDrm) {
        
        if (columns == null) return null;
        
        StringBuilder sb = new StringBuilder();
        sb.append("(");

        for (int i = columns.size() - 1; i >= 0; i--) {
            sb.append(columns.get(i)).append("=1 or ");
        }
        
        if (columns.size() > 0) {
            // Remove last ' or '
            sb.setLength(sb.length() - 4);
        }

        sb.append(")");

        if (!includeDrm) {
            // If not DRM files should be shown, the where clause
            // will be something like "(is_notification=1) and is_drm=0"
            sb.append(" and ");
            sb.append(MediaStore.MediaColumns.IS_DRM);
            sb.append("=0");
        }


        return sb.toString();
    }
    
    private Cursor query(Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        if (mActivity != null) {
            return mActivity.managedQuery(uri, projection, selection, selectionArgs, sortOrder);
        } else {
            return mContext.getContentResolver().query(uri, projection, selection, selectionArgs,
                    sortOrder);
        }
    }
    
    /**
     * Returns a {@link Ringtone} for a given sound URI.
     * <p>
     * If the given URI cannot be opened for any reason, this method will
     * attempt to fallback on another sound. If it cannot find any, it will
     * return null.
     * 
     * @param context A context used to query.
     * @param ringtoneUri The {@link Uri} of a sound or ringtone.
     * @return A {@link Ringtone} for the given URI, or null.
     */
    public static Ringtone getRingtone(final Context context, Uri ringtoneUri) {
        // Don't set the stream type
        return getRingtone(context, ringtoneUri, -1);
    }

    /**
     * Returns a {@link Ringtone} for a given sound URI on the given stream
     * type. Normally, if you change the stream type on the returned
     * {@link Ringtone}, it will re-create the {@link MediaPlayer}. This is just
     * an optimized route to avoid that.
     * 
     * @param streamType The stream type for the ringtone, or -1 if it should
     *            not be set (and the default used instead).
     * @see #getRingtone(Context, Uri)
     */
    private static Ringtone getRingtone(final Context context, Uri ringtoneUri, int streamType) {

        try {
            Ringtone r = new Ringtone(context);
            if (streamType >= 0) {
                r.setStreamType(streamType);
            }
            r.open(ringtoneUri);
            return r;
        } catch (Exception ex) {
            Log.e(TAG, "Failed to open ringtone " + ringtoneUri);
        }

        return null;
    }
    
    /**
     * Gets the current default sound's {@link Uri}. This will give the actual
     * sound {@link Uri}, instead of using this, most clients can use
     * {@link System#DEFAULT_RINGTONE_URI}.
     * 
     * @param context A context used for querying.
     * @param type The type whose default sound should be returned. One of
     *            {@link #TYPE_RINGTONE}, {@link #TYPE_NOTIFICATION}, or
     *            {@link #TYPE_ALARM}.
     * @return A {@link Uri} pointing to the default sound for the sound type.
     * @see #setActualDefaultRingtoneUri(Context, int, Uri)
     */
    public static Uri getActualDefaultRingtoneUri(Context context, int type) {
        String setting = getSettingForType(type);
        if (setting == null) return null;
        final String uriString = Settings.System.getString(context.getContentResolver(), setting);
        return uriString != null ? Uri.parse(uriString) : null;
    }
    
    /**
     * Sets the {@link Uri} of the default sound for a given sound type.
     * 
     * @param context A context used for querying.
     * @param type The type whose default sound should be set. One of
     *            {@link #TYPE_RINGTONE}, {@link #TYPE_NOTIFICATION}, or
     *            {@link #TYPE_ALARM}.
     * @param ringtoneUri A {@link Uri} pointing to the default sound to set.
     * @see #getActualDefaultRingtoneUri(Context, int)
     */
    public static void setActualDefaultRingtoneUri(Context context, int type, Uri ringtoneUri) {
        String setting = getSettingForType(type);
        if (setting == null) return;
        Settings.System.putString(context.getContentResolver(), setting,
                ringtoneUri != null ? ringtoneUri.toString() : null);
    }
    
    private static String getSettingForType(int type) {
        if ((type & TYPE_RINGTONE) != 0) {
            return Settings.System.RINGTONE;
        } else if ((type & TYPE_NOTIFICATION) != 0) {
            return Settings.System.NOTIFICATION_SOUND;
        } else if ((type & TYPE_ALARM) != 0) {
            return Settings.System.ALARM_ALERT;
        } else {
            return null;
        }
    }
    
    /**
     * Returns whether the given {@link Uri} is one of the default ringtones.
     * 
     * @param ringtoneUri The ringtone {@link Uri} to be checked.
     * @return Whether the {@link Uri} is a default.
     */
    public static boolean isDefault(Uri ringtoneUri) {
        return getDefaultType(ringtoneUri) != -1;
    }
    
    /**
     * Returns the type of a default {@link Uri}.
     * 
     * @param defaultRingtoneUri The default {@link Uri}. For example,
     *            {@link System#DEFAULT_RINGTONE_URI},
     *            {@link System#DEFAULT_NOTIFICATION_URI}, or
     *            {@link System#DEFAULT_ALARM_ALERT_URI}.
     * @return The type of the defaultRingtoneUri, or -1.
     */
    public static int getDefaultType(Uri defaultRingtoneUri) {
        if (defaultRingtoneUri == null) {
            return -1;
        } else if (defaultRingtoneUri.equals(Settings.System.DEFAULT_RINGTONE_URI)) {
            return TYPE_RINGTONE;
        } else if (defaultRingtoneUri.equals(Settings.System.DEFAULT_NOTIFICATION_URI)) {
            return TYPE_NOTIFICATION;
        } else if (defaultRingtoneUri.equals(Settings.System.DEFAULT_ALARM_ALERT_URI)) {
            return TYPE_ALARM;
        } else {
            return -1;
        }
    }
 
    /**
     * Returns the {@link Uri} for the default ringtone of a particular type.
     * Rather than returning the actual ringtone's sound {@link Uri}, this will
     * return the symbolic {@link Uri} which will resolved to the actual sound
     * when played.
     * 
     * @param type The ringtone type whose default should be returned.
     * @return The {@link Uri} of the default ringtone for the given type.
     */
    public static Uri getDefaultUri(int type) {
        if ((type & TYPE_RINGTONE) != 0) {
            return Settings.System.DEFAULT_RINGTONE_URI;
        } else if ((type & TYPE_NOTIFICATION) != 0) {
            return Settings.System.DEFAULT_NOTIFICATION_URI;
        } else if ((type & TYPE_ALARM) != 0) {
            return Settings.System.DEFAULT_ALARM_ALERT_URI;
        } else {
            return null;
        }
    }
    
}
