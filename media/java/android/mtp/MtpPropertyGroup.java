/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.mtp;

import android.content.ContentProviderClient;
import android.database.Cursor;
import android.net.Uri;
import android.os.RemoteException;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Files;
import android.provider.MediaStore.Images;
import android.util.Log;

import java.util.ArrayList;

/**
 * MtpPropertyGroup represents a list of MTP properties.
 * {@hide}
 */
class MtpPropertyGroup {
    private static final String TAG = MtpPropertyGroup.class.getSimpleName();

    private class Property {
        int code;
        int type;
        int column;

        Property(int code, int type, int column) {
            this.code = code;
            this.type = type;
            this.column = column;
        }
    }

    private final ContentProviderClient mProvider;
    private final String mVolumeName;
    private final Uri mUri;

    // list of all properties in this group
    private final Property[] mProperties;

    // list of columns for database query
    private String[] mColumns;

    private static final String PATH_WHERE = Files.FileColumns.DATA + "=?";

    // constructs a property group for a list of properties
    public MtpPropertyGroup(ContentProviderClient provider, String volumeName, int[] properties) {
        mProvider = provider;
        mVolumeName = volumeName;
        mUri = Files.getMtpObjectsUri(volumeName);

        int count = properties.length;
        ArrayList<String> columns = new ArrayList<>(count);
        columns.add(Files.FileColumns._ID);

        mProperties = new Property[count];
        for (int i = 0; i < count; i++) {
            mProperties[i] = createProperty(properties[i], columns);
        }
        count = columns.size();
        mColumns = new String[count];
        for (int i = 0; i < count; i++) {
            mColumns[i] = columns.get(i);
        }
    }

    private Property createProperty(int code, ArrayList<String> columns) {
        String column = null;
        int type;

        switch (code) {
            case MtpConstants.PROPERTY_STORAGE_ID:
                type = MtpConstants.TYPE_UINT32;
                break;
            case MtpConstants.PROPERTY_OBJECT_FORMAT:
                type = MtpConstants.TYPE_UINT16;
                break;
            case MtpConstants.PROPERTY_PROTECTION_STATUS:
                type = MtpConstants.TYPE_UINT16;
                break;
            case MtpConstants.PROPERTY_OBJECT_SIZE:
                type = MtpConstants.TYPE_UINT64;
                break;
            case MtpConstants.PROPERTY_OBJECT_FILE_NAME:
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_NAME:
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_DATE_MODIFIED:
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_DATE_ADDED:
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_ORIGINAL_RELEASE_DATE:
                column = Audio.AudioColumns.YEAR;
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_PARENT_OBJECT:
                type = MtpConstants.TYPE_UINT32;
                break;
            case MtpConstants.PROPERTY_PERSISTENT_UID:
                type = MtpConstants.TYPE_UINT128;
                break;
            case MtpConstants.PROPERTY_DURATION:
                column = Audio.AudioColumns.DURATION;
                type = MtpConstants.TYPE_UINT32;
                break;
            case MtpConstants.PROPERTY_TRACK:
                column = Audio.AudioColumns.TRACK;
                type = MtpConstants.TYPE_UINT16;
                break;
            case MtpConstants.PROPERTY_DISPLAY_NAME:
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_ARTIST:
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_ALBUM_NAME:
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_ALBUM_ARTIST:
                column = Audio.AudioColumns.ALBUM_ARTIST;
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_GENRE:
                // genre requires a special query
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_COMPOSER:
                column = Audio.AudioColumns.COMPOSER;
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_DESCRIPTION:
                column = Images.ImageColumns.DESCRIPTION;
                type = MtpConstants.TYPE_STR;
                break;
            case MtpConstants.PROPERTY_AUDIO_WAVE_CODEC:
            case MtpConstants.PROPERTY_AUDIO_BITRATE:
            case MtpConstants.PROPERTY_SAMPLE_RATE:
                // these are special cased
                type = MtpConstants.TYPE_UINT32;
                break;
            case MtpConstants.PROPERTY_BITRATE_TYPE:
            case MtpConstants.PROPERTY_NUMBER_OF_CHANNELS:
                // these are special cased
                type = MtpConstants.TYPE_UINT16;
                break;
            default:
                type = MtpConstants.TYPE_UNDEFINED;
                Log.e(TAG, "unsupported property " + code);
                break;
        }

        if (column != null) {
            columns.add(column);
            return new Property(code, type, columns.size() - 1);
        } else {
            return new Property(code, type, -1);
        }
    }

    private String queryAudio(String path, String column) {
        Cursor c = null;
        try {
            c = mProvider.query(Audio.Media.getContentUri(mVolumeName),
                            new String [] { column },
                            PATH_WHERE, new String[] {path}, null, null);
            if (c != null && c.moveToNext()) {
                return c.getString(0);
            } else {
                return "";
            }
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    private String queryGenre(String path) {
        Cursor c = null;
        try {
            c = mProvider.query(Audio.Genres.getContentUri(mVolumeName),
                            new String [] { Audio.GenresColumns.NAME },
                            PATH_WHERE, new String[] {path}, null, null);
            if (c != null && c.moveToNext()) {
                return c.getString(0);
            } else {
                return "";
            }
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /**
     * Gets the values of the properties represented by this property group for the given
     * object and adds them to the given property list.
     * @return Response_OK if the operation succeeded.
     */
    public int getPropertyList(MtpStorageManager.MtpObject object, MtpPropertyList list) {
        Cursor c = null;
        int id = object.getId();
        String path = object.getPath().toString();
        for (Property property : mProperties) {
            if (property.column != -1 && c == null) {
                try {
                    // Look up the entry in MediaProvider only if one of those properties is needed.
                    c = mProvider.query(mUri, mColumns,
                            PATH_WHERE, new String[] {path}, null, null);
                    if (c != null && !c.moveToNext()) {
                        c.close();
                        c = null;
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Mediaprovider lookup failed");
                }
            }
            switch (property.code) {
                case MtpConstants.PROPERTY_PROTECTION_STATUS:
                    // protection status is always 0
                    list.append(id, property.code, property.type, 0);
                    break;
                case MtpConstants.PROPERTY_NAME:
                case MtpConstants.PROPERTY_OBJECT_FILE_NAME:
                case MtpConstants.PROPERTY_DISPLAY_NAME:
                    list.append(id, property.code, object.getName());
                    break;
                case MtpConstants.PROPERTY_DATE_MODIFIED:
                case MtpConstants.PROPERTY_DATE_ADDED:
                    // convert from seconds to DateTime
                    list.append(id, property.code,
                            format_date_time(object.getModifiedTime()));
                    break;
                case MtpConstants.PROPERTY_STORAGE_ID:
                    list.append(id, property.code, property.type, object.getStorageId());
                    break;
                case MtpConstants.PROPERTY_OBJECT_FORMAT:
                    list.append(id, property.code, property.type, object.getFormat());
                    break;
                case MtpConstants.PROPERTY_OBJECT_SIZE:
                    list.append(id, property.code, property.type, object.getSize());
                    break;
                case MtpConstants.PROPERTY_PARENT_OBJECT:
                    list.append(id, property.code, property.type,
                            object.getParent().isRoot() ? 0 : object.getParent().getId());
                    break;
                case MtpConstants.PROPERTY_PERSISTENT_UID:
                    // The persistent uid must be unique and never reused among all objects,
                    // and remain the same between sessions.
                    long puid = (object.getPath().toString().hashCode() << 32)
                            + object.getModifiedTime();
                    list.append(id, property.code, property.type, puid);
                    break;
                case MtpConstants.PROPERTY_ORIGINAL_RELEASE_DATE:
                    // release date is stored internally as just the year
                    int year = 0;
                    if (c != null)
                        year = c.getInt(property.column);
                    String dateTime = Integer.toString(year) + "0101T000000";
                    list.append(id, property.code, dateTime);
                    break;
                case MtpConstants.PROPERTY_TRACK:
                    int track = 0;
                    if (c != null)
                        track = c.getInt(property.column);
                    list.append(id, property.code, MtpConstants.TYPE_UINT16,
                            track % 1000);
                    break;
                case MtpConstants.PROPERTY_ARTIST:
                    list.append(id, property.code,
                            queryAudio(path, Audio.AudioColumns.ARTIST));
                    break;
                case MtpConstants.PROPERTY_ALBUM_NAME:
                    list.append(id, property.code,
                            queryAudio(path, Audio.AudioColumns.ALBUM));
                    break;
                case MtpConstants.PROPERTY_GENRE:
                    String genre = queryGenre(path);
                    if (genre != null) {
                        list.append(id, property.code, genre);
                    }
                    break;
                case MtpConstants.PROPERTY_AUDIO_WAVE_CODEC:
                case MtpConstants.PROPERTY_AUDIO_BITRATE:
                case MtpConstants.PROPERTY_SAMPLE_RATE:
                    // we don't have these in our database, so return 0
                    list.append(id, property.code, MtpConstants.TYPE_UINT32, 0);
                    break;
                case MtpConstants.PROPERTY_BITRATE_TYPE:
                case MtpConstants.PROPERTY_NUMBER_OF_CHANNELS:
                    // we don't have these in our database, so return 0
                    list.append(id, property.code, MtpConstants.TYPE_UINT16, 0);
                    break;
                default:
                    switch(property.type) {
                        case MtpConstants.TYPE_UNDEFINED:
                            list.append(id, property.code, property.type, 0);
                            break;
                        case MtpConstants.TYPE_STR:
                            String value = "";
                            if (c != null)
                                value = c.getString(property.column);
                            list.append(id, property.code, value);
                            break;
                        default:
                            long longValue = 0L;
                            if (c != null)
                                longValue = c.getLong(property.column);
                            list.append(id, property.code, property.type, longValue);
                    }
            }
        }
        if (c != null)
            c.close();
        return MtpConstants.RESPONSE_OK;
    }

    private native String format_date_time(long seconds);
}
