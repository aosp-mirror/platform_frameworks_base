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

package android.provider;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteException;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.MiniThumbFile;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The Media provider contains meta data for all available media on both internal
 * and external storage devices.
 */
public final class MediaStore {
    private final static String TAG = "MediaStore";

    public static final String AUTHORITY = "media";

    private static final String CONTENT_AUTHORITY_SLASH = "content://" + AUTHORITY + "/";

   /**
     * Broadcast Action:  A broadcast to indicate the end of an MTP session with the host.
     * This broadcast is only sent if MTP activity has modified the media database during the
     * most recent MTP session.
     *
     * @hide
     */
    public static final String ACTION_MTP_SESSION_END = "android.provider.action.MTP_SESSION_END";

    /**
     * The method name used by the media scanner and mtp to tell the media provider to
     * rescan and reclassify that have become unhidden because of renaming folders or
     * removing nomedia files
     * @hide
     */
    public static final String UNHIDE_CALL = "unhide";

    /**
     * This is for internal use by the media scanner only.
     * Name of the (optional) Uri parameter that determines whether to skip deleting
     * the file pointed to by the _data column, when deleting the database entry.
     * The only appropriate value for this parameter is "false", in which case the
     * delete will be skipped. Note especially that setting this to true, or omitting
     * the parameter altogether, will perform the default action, which is different
     * for different types of media.
     * @hide
     */
    public static final String PARAM_DELETE_DATA = "deletedata";

    /**
     * Activity Action: Launch a music player.
     * The activity should be able to play, browse, or manipulate music files stored on the device.
     *
     * @deprecated Use {@link android.content.Intent#CATEGORY_APP_MUSIC} instead.
     */
    @Deprecated
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String INTENT_ACTION_MUSIC_PLAYER = "android.intent.action.MUSIC_PLAYER";

    /**
     * Activity Action: Perform a search for media.
     * Contains at least the {@link android.app.SearchManager#QUERY} extra.
     * May also contain any combination of the following extras:
     * EXTRA_MEDIA_ARTIST, EXTRA_MEDIA_ALBUM, EXTRA_MEDIA_TITLE, EXTRA_MEDIA_FOCUS
     *
     * @see android.provider.MediaStore#EXTRA_MEDIA_ARTIST
     * @see android.provider.MediaStore#EXTRA_MEDIA_ALBUM
     * @see android.provider.MediaStore#EXTRA_MEDIA_TITLE
     * @see android.provider.MediaStore#EXTRA_MEDIA_FOCUS
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String INTENT_ACTION_MEDIA_SEARCH = "android.intent.action.MEDIA_SEARCH";

    /**
     * An intent to perform a search for music media and automatically play content from the
     * result when possible. This can be fired, for example, by the result of a voice recognition
     * command to listen to music.
     * <p>
     * Contains the {@link android.app.SearchManager#QUERY} extra, which is a string
     * that can contain any type of unstructured music search, like the name of an artist,
     * an album, a song, a genre, or any combination of these.
     * <p>
     * Because this intent includes an open-ended unstructured search string, it makes the most
     * sense for apps that can support large-scale search of music, such as services connected
     * to an online database of music which can be streamed and played on the device.
     */
    public static final String INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH =
            "android.media.action.MEDIA_PLAY_FROM_SEARCH";
    
    /**
     * The name of the Intent-extra used to define the artist
     */
    public static final String EXTRA_MEDIA_ARTIST = "android.intent.extra.artist";
    /**
     * The name of the Intent-extra used to define the album
     */
    public static final String EXTRA_MEDIA_ALBUM = "android.intent.extra.album";
    /**
     * The name of the Intent-extra used to define the song title
     */
    public static final String EXTRA_MEDIA_TITLE = "android.intent.extra.title";
    /**
     * The name of the Intent-extra used to define the search focus. The search focus
     * indicates whether the search should be for things related to the artist, album
     * or song that is identified by the other extras.
     */
    public static final String EXTRA_MEDIA_FOCUS = "android.intent.extra.focus";

    /**
     * The name of the Intent-extra used to control the orientation of a ViewImage or a MovieView.
     * This is an int property that overrides the activity's requestedOrientation.
     * @see android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
     */
    public static final String EXTRA_SCREEN_ORIENTATION = "android.intent.extra.screenOrientation";

    /**
     * The name of an Intent-extra used to control the UI of a ViewImage.
     * This is a boolean property that overrides the activity's default fullscreen state.
     */
    public static final String EXTRA_FULL_SCREEN = "android.intent.extra.fullScreen";

    /**
     * The name of an Intent-extra used to control the UI of a ViewImage.
     * This is a boolean property that specifies whether or not to show action icons.
     */
    public static final String EXTRA_SHOW_ACTION_ICONS = "android.intent.extra.showActionIcons";

    /**
     * The name of the Intent-extra used to control the onCompletion behavior of a MovieView.
     * This is a boolean property that specifies whether or not to finish the MovieView activity
     * when the movie completes playing. The default value is true, which means to automatically
     * exit the movie player activity when the movie completes playing.
     */
    public static final String EXTRA_FINISH_ON_COMPLETION = "android.intent.extra.finishOnCompletion";

    /**
     * The name of the Intent action used to launch a camera in still image mode.
     */
    public static final String INTENT_ACTION_STILL_IMAGE_CAMERA = "android.media.action.STILL_IMAGE_CAMERA";

    /**
     * The name of the Intent action used to launch a camera in video mode.
     */
    public static final String INTENT_ACTION_VIDEO_CAMERA = "android.media.action.VIDEO_CAMERA";

    /**
     * Standard Intent action that can be sent to have the camera application
     * capture an image and return it.
     * <p>
     * The caller may pass an extra EXTRA_OUTPUT to control where this image will be written.
     * If the EXTRA_OUTPUT is not present, then a small sized image is returned as a Bitmap
     * object in the extra field. This is useful for applications that only need a small image.
     * If the EXTRA_OUTPUT is present, then the full-sized image will be written to the Uri
     * value of EXTRA_OUTPUT.
     * @see #EXTRA_OUTPUT
     */
    public final static String ACTION_IMAGE_CAPTURE = "android.media.action.IMAGE_CAPTURE";

    /**
     * Standard Intent action that can be sent to have the camera application
     * capture a video and return it.
     * <p>
     * The caller may pass in an extra EXTRA_VIDEO_QUALITY to control the video quality.
     * <p>
     * The caller may pass in an extra EXTRA_OUTPUT to control
     * where the video is written. If EXTRA_OUTPUT is not present the video will be
     * written to the standard location for videos, and the Uri of that location will be
     * returned in the data field of the Uri.
     * @see #EXTRA_OUTPUT
     * @see #EXTRA_VIDEO_QUALITY
     * @see #EXTRA_SIZE_LIMIT
     * @see #EXTRA_DURATION_LIMIT
     */
    public final static String ACTION_VIDEO_CAPTURE = "android.media.action.VIDEO_CAPTURE";

    /**
     * The name of the Intent-extra used to control the quality of a recorded video. This is an
     * integer property. Currently value 0 means low quality, suitable for MMS messages, and
     * value 1 means high quality. In the future other quality levels may be added.
     */
    public final static String EXTRA_VIDEO_QUALITY = "android.intent.extra.videoQuality";

    /**
     * Specify the maximum allowed size.
     */
    public final static String EXTRA_SIZE_LIMIT = "android.intent.extra.sizeLimit";

    /**
     * Specify the maximum allowed recording duration in seconds.
     */
    public final static String EXTRA_DURATION_LIMIT = "android.intent.extra.durationLimit";

    /**
     * The name of the Intent-extra used to indicate a content resolver Uri to be used to
     * store the requested image or video.
     */
    public final static String EXTRA_OUTPUT = "output";

    /**
      * The string that is used when a media attribute is not known. For example,
      * if an audio file does not have any meta data, the artist and album columns
      * will be set to this value.
      */
    public static final String UNKNOWN_STRING = "<unknown>";

    /**
     * Common fields for most MediaProvider tables
     */

    public interface MediaColumns extends BaseColumns {
        /**
         * The data stream for the file
         * <P>Type: DATA STREAM</P>
         */
        public static final String DATA = "_data";

        /**
         * The size of the file in bytes
         * <P>Type: INTEGER (long)</P>
         */
        public static final String SIZE = "_size";

        /**
         * The display name of the file
         * <P>Type: TEXT</P>
         */
        public static final String DISPLAY_NAME = "_display_name";

        /**
         * The title of the content
         * <P>Type: TEXT</P>
         */
        public static final String TITLE = "title";

        /**
         * The time the file was added to the media provider
         * Units are seconds since 1970.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE_ADDED = "date_added";

        /**
         * The time the file was last modified
         * Units are seconds since 1970.
         * NOTE: This is for internal use by the media scanner.  Do not modify this field.
         * <P>Type: INTEGER (long)</P>
         */
        public static final String DATE_MODIFIED = "date_modified";

        /**
         * The MIME type of the file
         * <P>Type: TEXT</P>
         */
        public static final String MIME_TYPE = "mime_type";

        /**
         * The MTP object handle of a newly transfered file.
         * Used to pass the new file's object handle through the media scanner
         * from MTP to the media provider
         * For internal use only by MTP, media scanner and media provider.
         * <P>Type: INTEGER</P>
         * @hide
         */
        public static final String MEDIA_SCANNER_NEW_OBJECT_ID = "media_scanner_new_object_id";

        /**
         * Non-zero if the media file is drm-protected
         * <P>Type: INTEGER (boolean)</P>
         * @hide
         */
        public static final String IS_DRM = "is_drm";

        /**
         * The width of the image/video in pixels.
         */
        public static final String WIDTH = "width";

        /**
         * The height of the image/video in pixels.
         */
        public static final String HEIGHT = "height";
     }

    /**
     * Media provider table containing an index of all files in the media storage,
     * including non-media files.  This should be used by applications that work with
     * non-media file types (text, HTML, PDF, etc) as well as applications that need to
     * work with multiple media file types in a single query.
     */
    public static final class Files {

        /**
         * Get the content:// style URI for the files table on the
         * given volume.
         *
         * @param volumeName the name of the volume to get the URI for
         * @return the URI to the files table on the given volume
         */
        public static Uri getContentUri(String volumeName) {
            return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                    "/file");
        }

        /**
         * Get the content:// style URI for a single row in the files table on the
         * given volume.
         *
         * @param volumeName the name of the volume to get the URI for
         * @param rowId the file to get the URI for
         * @return the URI to the files table on the given volume
         */
        public static final Uri getContentUri(String volumeName,
                long rowId) {
            return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName
                    + "/file/" + rowId);
        }

        /**
         * For use only by the MTP implementation.
         * @hide
         */
        public static Uri getMtpObjectsUri(String volumeName) {
            return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                    "/object");
        }

        /**
         * For use only by the MTP implementation.
         * @hide
         */
        public static final Uri getMtpObjectsUri(String volumeName,
                long fileId) {
            return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName
                    + "/object/" + fileId);
        }

        /**
         * Used to implement the MTP GetObjectReferences and SetObjectReferences commands.
         * @hide
         */
        public static final Uri getMtpReferencesUri(String volumeName,
                long fileId) {
            return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName
                    + "/object/" + fileId + "/references");
        }

        /**
         * Fields for master table for all media files.
         * Table also contains MediaColumns._ID, DATA, SIZE and DATE_MODIFIED.
         */
        public interface FileColumns extends MediaColumns {
            /**
             * The MTP storage ID of the file
             * <P>Type: INTEGER</P>
             * @hide
             */
            public static final String STORAGE_ID = "storage_id";

            /**
             * The MTP format code of the file
             * <P>Type: INTEGER</P>
             * @hide
             */
            public static final String FORMAT = "format";

            /**
             * The index of the parent directory of the file
             * <P>Type: INTEGER</P>
             */
            public static final String PARENT = "parent";

            /**
             * The MIME type of the file
             * <P>Type: TEXT</P>
             */
            public static final String MIME_TYPE = "mime_type";

            /**
             * The title of the content
             * <P>Type: TEXT</P>
             */
            public static final String TITLE = "title";

            /**
             * The media type (audio, video, image or playlist)
             * of the file, or 0 for not a media file
             * <P>Type: TEXT</P>
             */
            public static final String MEDIA_TYPE = "media_type";

            /**
             * Constant for the {@link #MEDIA_TYPE} column indicating that file
             * is not an audio, image, video or playlist file.
             */
            public static final int MEDIA_TYPE_NONE = 0;

            /**
             * Constant for the {@link #MEDIA_TYPE} column indicating that file is an image file.
             */
            public static final int MEDIA_TYPE_IMAGE = 1;

            /**
             * Constant for the {@link #MEDIA_TYPE} column indicating that file is an audio file.
             */
            public static final int MEDIA_TYPE_AUDIO = 2;

            /**
             * Constant for the {@link #MEDIA_TYPE} column indicating that file is a video file.
             */
            public static final int MEDIA_TYPE_VIDEO = 3;

            /**
             * Constant for the {@link #MEDIA_TYPE} column indicating that file is a playlist file.
             */
            public static final int MEDIA_TYPE_PLAYLIST = 4;
        }
    }

    /**
     * This class is used internally by Images.Thumbnails and Video.Thumbnails, it's not intended
     * to be accessed elsewhere.
     */
    private static class InternalThumbnails implements BaseColumns {
        private static final int MINI_KIND = 1;
        private static final int FULL_SCREEN_KIND = 2;
        private static final int MICRO_KIND = 3;
        private static final String[] PROJECTION = new String[] {_ID, MediaColumns.DATA};
        static final int DEFAULT_GROUP_ID = 0;
        private static final Object sThumbBufLock = new Object();
        private static byte[] sThumbBuf;

        private static Bitmap getMiniThumbFromFile(Cursor c, Uri baseUri, ContentResolver cr, BitmapFactory.Options options) {
            Bitmap bitmap = null;
            Uri thumbUri = null;
            try {
                long thumbId = c.getLong(0);
                String filePath = c.getString(1);
                thumbUri = ContentUris.withAppendedId(baseUri, thumbId);
                ParcelFileDescriptor pfdInput = cr.openFileDescriptor(thumbUri, "r");
                bitmap = BitmapFactory.decodeFileDescriptor(
                        pfdInput.getFileDescriptor(), null, options);
                pfdInput.close();
            } catch (FileNotFoundException ex) {
                Log.e(TAG, "couldn't open thumbnail " + thumbUri + "; " + ex);
            } catch (IOException ex) {
                Log.e(TAG, "couldn't open thumbnail " + thumbUri + "; " + ex);
            } catch (OutOfMemoryError ex) {
                Log.e(TAG, "failed to allocate memory for thumbnail "
                        + thumbUri + "; " + ex);
            }
            return bitmap;
        }

        /**
         * This method cancels the thumbnail request so clients waiting for getThumbnail will be
         * interrupted and return immediately. Only the original process which made the getThumbnail
         * requests can cancel their own requests.
         *
         * @param cr ContentResolver
         * @param origId original image or video id. use -1 to cancel all requests.
         * @param groupId the same groupId used in getThumbnail
         * @param baseUri the base URI of requested thumbnails
         */
        static void cancelThumbnailRequest(ContentResolver cr, long origId, Uri baseUri,
                long groupId) {
            Uri cancelUri = baseUri.buildUpon().appendQueryParameter("cancel", "1")
                    .appendQueryParameter("orig_id", String.valueOf(origId))
                    .appendQueryParameter("group_id", String.valueOf(groupId)).build();
            Cursor c = null;
            try {
                c = cr.query(cancelUri, PROJECTION, null, null, null);
            }
            finally {
                if (c != null) c.close();
            }
        }
        /**
         * This method ensure thumbnails associated with origId are generated and decode the byte
         * stream from database (MICRO_KIND) or file (MINI_KIND).
         *
         * Special optimization has been done to avoid further IPC communication for MICRO_KIND
         * thumbnails.
         *
         * @param cr ContentResolver
         * @param origId original image or video id
         * @param kind could be MINI_KIND or MICRO_KIND
         * @param options this is only used for MINI_KIND when decoding the Bitmap
         * @param baseUri the base URI of requested thumbnails
         * @param groupId the id of group to which this request belongs
         * @return Bitmap bitmap of specified thumbnail kind
         */
        static Bitmap getThumbnail(ContentResolver cr, long origId, long groupId, int kind,
                BitmapFactory.Options options, Uri baseUri, boolean isVideo) {
            Bitmap bitmap = null;
            String filePath = null;
            // Log.v(TAG, "getThumbnail: origId="+origId+", kind="+kind+", isVideo="+isVideo);
            // If the magic is non-zero, we simply return thumbnail if it does exist.
            // querying MediaProvider and simply return thumbnail.
            MiniThumbFile thumbFile = new MiniThumbFile(isVideo ? Video.Media.EXTERNAL_CONTENT_URI
                    : Images.Media.EXTERNAL_CONTENT_URI);
            Cursor c = null;
            try {
                long magic = thumbFile.getMagic(origId);
                if (magic != 0) {
                    if (kind == MICRO_KIND) {
                        synchronized (sThumbBufLock) {
                            if (sThumbBuf == null) {
                                sThumbBuf = new byte[MiniThumbFile.BYTES_PER_MINTHUMB];
                            }
                            if (thumbFile.getMiniThumbFromFile(origId, sThumbBuf) != null) {
                                bitmap = BitmapFactory.decodeByteArray(sThumbBuf, 0, sThumbBuf.length);
                                if (bitmap == null) {
                                    Log.w(TAG, "couldn't decode byte array.");
                                }
                            }
                        }
                        return bitmap;
                    } else if (kind == MINI_KIND) {
                        String column = isVideo ? "video_id=" : "image_id=";
                        c = cr.query(baseUri, PROJECTION, column + origId, null, null);
                        if (c != null && c.moveToFirst()) {
                            bitmap = getMiniThumbFromFile(c, baseUri, cr, options);
                            if (bitmap != null) {
                                return bitmap;
                            }
                        }
                    }
                }

                Uri blockingUri = baseUri.buildUpon().appendQueryParameter("blocking", "1")
                        .appendQueryParameter("orig_id", String.valueOf(origId))
                        .appendQueryParameter("group_id", String.valueOf(groupId)).build();
                if (c != null) c.close();
                c = cr.query(blockingUri, PROJECTION, null, null, null);
                // This happens when original image/video doesn't exist.
                if (c == null) return null;

                // Assuming thumbnail has been generated, at least original image exists.
                if (kind == MICRO_KIND) {
                    synchronized (sThumbBufLock) {
                        if (sThumbBuf == null) {
                            sThumbBuf = new byte[MiniThumbFile.BYTES_PER_MINTHUMB];
                        }
                        if (thumbFile.getMiniThumbFromFile(origId, sThumbBuf) != null) {
                            bitmap = BitmapFactory.decodeByteArray(sThumbBuf, 0, sThumbBuf.length);
                            if (bitmap == null) {
                                Log.w(TAG, "couldn't decode byte array.");
                            }
                        }
                    }
                } else if (kind == MINI_KIND) {
                    if (c.moveToFirst()) {
                        bitmap = getMiniThumbFromFile(c, baseUri, cr, options);
                    }
                } else {
                    throw new IllegalArgumentException("Unsupported kind: " + kind);
                }

                // We probably run out of space, so create the thumbnail in memory.
                if (bitmap == null) {
                    Log.v(TAG, "Create the thumbnail in memory: origId=" + origId
                            + ", kind=" + kind + ", isVideo="+isVideo);
                    Uri uri = Uri.parse(
                            baseUri.buildUpon().appendPath(String.valueOf(origId))
                                    .toString().replaceFirst("thumbnails", "media"));
                    if (filePath == null) {
                        if (c != null) c.close();
                        c = cr.query(uri, PROJECTION, null, null, null);
                        if (c == null || !c.moveToFirst()) {
                            return null;
                        }
                        filePath = c.getString(1);
                    }
                    if (isVideo) {
                        bitmap = ThumbnailUtils.createVideoThumbnail(filePath, kind);
                    } else {
                        bitmap = ThumbnailUtils.createImageThumbnail(filePath, kind);
                    }
                }
            } catch (SQLiteException ex) {
                Log.w(TAG, ex);
            } finally {
                if (c != null) c.close();
                // To avoid file descriptor leak in application process.
                thumbFile.deactivate();
                thumbFile = null;
            }
            return bitmap;
        }
    }

    /**
     * Contains meta data for all available images.
     */
    public static final class Images {
        public interface ImageColumns extends MediaColumns {
            /**
             * The description of the image
             * <P>Type: TEXT</P>
             */
            public static final String DESCRIPTION = "description";

            /**
             * The picasa id of the image
             * <P>Type: TEXT</P>
             */
            public static final String PICASA_ID = "picasa_id";

            /**
             * Whether the video should be published as public or private
             * <P>Type: INTEGER</P>
             */
            public static final String IS_PRIVATE = "isprivate";

            /**
             * The latitude where the image was captured.
             * <P>Type: DOUBLE</P>
             */
            public static final String LATITUDE = "latitude";

            /**
             * The longitude where the image was captured.
             * <P>Type: DOUBLE</P>
             */
            public static final String LONGITUDE = "longitude";

            /**
             * The date & time that the image was taken in units
             * of milliseconds since jan 1, 1970.
             * <P>Type: INTEGER</P>
             */
            public static final String DATE_TAKEN = "datetaken";

            /**
             * The orientation for the image expressed as degrees.
             * Only degrees 0, 90, 180, 270 will work.
             * <P>Type: INTEGER</P>
             */
            public static final String ORIENTATION = "orientation";

            /**
             * The mini thumb id.
             * <P>Type: INTEGER</P>
             */
            public static final String MINI_THUMB_MAGIC = "mini_thumb_magic";

            /**
             * The bucket id of the image. This is a read-only property that
             * is automatically computed from the DATA column.
             * <P>Type: TEXT</P>
             */
            public static final String BUCKET_ID = "bucket_id";

            /**
             * The bucket display name of the image. This is a read-only property that
             * is automatically computed from the DATA column.
             * <P>Type: TEXT</P>
             */
            public static final String BUCKET_DISPLAY_NAME = "bucket_display_name";
        }

        public static final class Media implements ImageColumns {
            public static final Cursor query(ContentResolver cr, Uri uri, String[] projection) {
                return cr.query(uri, projection, null, null, DEFAULT_SORT_ORDER);
            }

            public static final Cursor query(ContentResolver cr, Uri uri, String[] projection,
                    String where, String orderBy) {
                return cr.query(uri, projection, where,
                                             null, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
            }

            public static final Cursor query(ContentResolver cr, Uri uri, String[] projection,
                    String selection, String [] selectionArgs, String orderBy) {
                return cr.query(uri, projection, selection,
                        selectionArgs, orderBy == null ? DEFAULT_SORT_ORDER : orderBy);
            }

            /**
             * Retrieves an image for the given url as a {@link Bitmap}.
             *
             * @param cr The content resolver to use
             * @param url The url of the image
             * @throws FileNotFoundException
             * @throws IOException
             */
            public static final Bitmap getBitmap(ContentResolver cr, Uri url)
                    throws FileNotFoundException, IOException {
                InputStream input = cr.openInputStream(url);
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                input.close();
                return bitmap;
            }

            /**
             * Insert an image and create a thumbnail for it.
             *
             * @param cr The content resolver to use
             * @param imagePath The path to the image to insert
             * @param name The name of the image
             * @param description The description of the image
             * @return The URL to the newly created image
             * @throws FileNotFoundException
             */
            public static final String insertImage(ContentResolver cr, String imagePath,
                    String name, String description) throws FileNotFoundException {
                // Check if file exists with a FileInputStream
                FileInputStream stream = new FileInputStream(imagePath);
                try {
                    Bitmap bm = BitmapFactory.decodeFile(imagePath);
                    String ret = insertImage(cr, bm, name, description);
                    bm.recycle();
                    return ret;
                } finally {
                    try {
                        stream.close();
                    } catch (IOException e) {
                    }
                }
            }

            private static final Bitmap StoreThumbnail(
                    ContentResolver cr,
                    Bitmap source,
                    long id,
                    float width, float height,
                    int kind) {
                // create the matrix to scale it
                Matrix matrix = new Matrix();

                float scaleX = width / source.getWidth();
                float scaleY = height / source.getHeight();

                matrix.setScale(scaleX, scaleY);

                Bitmap thumb = Bitmap.createBitmap(source, 0, 0,
                                                   source.getWidth(),
                                                   source.getHeight(), matrix,
                                                   true);

                ContentValues values = new ContentValues(4);
                values.put(Images.Thumbnails.KIND,     kind);
                values.put(Images.Thumbnails.IMAGE_ID, (int)id);
                values.put(Images.Thumbnails.HEIGHT,   thumb.getHeight());
                values.put(Images.Thumbnails.WIDTH,    thumb.getWidth());

                Uri url = cr.insert(Images.Thumbnails.EXTERNAL_CONTENT_URI, values);

                try {
                    OutputStream thumbOut = cr.openOutputStream(url);

                    thumb.compress(Bitmap.CompressFormat.JPEG, 100, thumbOut);
                    thumbOut.close();
                    return thumb;
                }
                catch (FileNotFoundException ex) {
                    return null;
                }
                catch (IOException ex) {
                    return null;
                }
            }

            /**
             * Insert an image and create a thumbnail for it.
             *
             * @param cr The content resolver to use
             * @param source The stream to use for the image
             * @param title The name of the image
             * @param description The description of the image
             * @return The URL to the newly created image, or <code>null</code> if the image failed to be stored
             *              for any reason.
             */
            public static final String insertImage(ContentResolver cr, Bitmap source,
                                                   String title, String description) {
                ContentValues values = new ContentValues();
                values.put(Images.Media.TITLE, title);
                values.put(Images.Media.DESCRIPTION, description);
                values.put(Images.Media.MIME_TYPE, "image/jpeg");

                Uri url = null;
                String stringUrl = null;    /* value to be returned */

                try {
                    url = cr.insert(EXTERNAL_CONTENT_URI, values);

                    if (source != null) {
                        OutputStream imageOut = cr.openOutputStream(url);
                        try {
                            source.compress(Bitmap.CompressFormat.JPEG, 50, imageOut);
                        } finally {
                            imageOut.close();
                        }

                        long id = ContentUris.parseId(url);
                        // Wait until MINI_KIND thumbnail is generated.
                        Bitmap miniThumb = Images.Thumbnails.getThumbnail(cr, id,
                                Images.Thumbnails.MINI_KIND, null);
                        // This is for backward compatibility.
                        Bitmap microThumb = StoreThumbnail(cr, miniThumb, id, 50F, 50F,
                                Images.Thumbnails.MICRO_KIND);
                    } else {
                        Log.e(TAG, "Failed to create thumbnail, removing original");
                        cr.delete(url, null, null);
                        url = null;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to insert image", e);
                    if (url != null) {
                        cr.delete(url, null, null);
                        url = null;
                    }
                }

                if (url != null) {
                    stringUrl = url.toString();
                }

                return stringUrl;
            }

            /**
             * Get the content:// style URI for the image media table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the image media table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                        "/images/media");
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type of of this directory of
             * images.  Note that each entry in this directory will have a standard
             * image MIME type as appropriate -- for example, image/jpeg.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/image";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = ImageColumns.BUCKET_DISPLAY_NAME;
        }

        /**
         * This class allows developers to query and get two kinds of thumbnails:
         * MINI_KIND: 512 x 384 thumbnail
         * MICRO_KIND: 96 x 96 thumbnail
         */
        public static class Thumbnails implements BaseColumns {
            public static final Cursor query(ContentResolver cr, Uri uri, String[] projection) {
                return cr.query(uri, projection, null, null, DEFAULT_SORT_ORDER);
            }

            public static final Cursor queryMiniThumbnails(ContentResolver cr, Uri uri, int kind,
                    String[] projection) {
                return cr.query(uri, projection, "kind = " + kind, null, DEFAULT_SORT_ORDER);
            }

            public static final Cursor queryMiniThumbnail(ContentResolver cr, long origId, int kind,
                    String[] projection) {
                return cr.query(EXTERNAL_CONTENT_URI, projection,
                        IMAGE_ID + " = " + origId + " AND " + KIND + " = " +
                        kind, null, null);
            }

            /**
             * This method cancels the thumbnail request so clients waiting for getThumbnail will be
             * interrupted and return immediately. Only the original process which made the getThumbnail
             * requests can cancel their own requests.
             *
             * @param cr ContentResolver
             * @param origId original image id
             */
            public static void cancelThumbnailRequest(ContentResolver cr, long origId) {
                InternalThumbnails.cancelThumbnailRequest(cr, origId, EXTERNAL_CONTENT_URI,
                        InternalThumbnails.DEFAULT_GROUP_ID);
            }

            /**
             * This method checks if the thumbnails of the specified image (origId) has been created.
             * It will be blocked until the thumbnails are generated.
             *
             * @param cr ContentResolver used to dispatch queries to MediaProvider.
             * @param origId Original image id associated with thumbnail of interest.
             * @param kind The type of thumbnail to fetch. Should be either MINI_KIND or MICRO_KIND.
             * @param options this is only used for MINI_KIND when decoding the Bitmap
             * @return A Bitmap instance. It could be null if the original image
             *         associated with origId doesn't exist or memory is not enough.
             */
            public static Bitmap getThumbnail(ContentResolver cr, long origId, int kind,
                    BitmapFactory.Options options) {
                return InternalThumbnails.getThumbnail(cr, origId,
                        InternalThumbnails.DEFAULT_GROUP_ID, kind, options,
                        EXTERNAL_CONTENT_URI, false);
            }

            /**
             * This method cancels the thumbnail request so clients waiting for getThumbnail will be
             * interrupted and return immediately. Only the original process which made the getThumbnail
             * requests can cancel their own requests.
             *
             * @param cr ContentResolver
             * @param origId original image id
             * @param groupId the same groupId used in getThumbnail.
             */
            public static void cancelThumbnailRequest(ContentResolver cr, long origId, long groupId) {
                InternalThumbnails.cancelThumbnailRequest(cr, origId, EXTERNAL_CONTENT_URI, groupId);
            }

            /**
             * This method checks if the thumbnails of the specified image (origId) has been created.
             * It will be blocked until the thumbnails are generated.
             *
             * @param cr ContentResolver used to dispatch queries to MediaProvider.
             * @param origId Original image id associated with thumbnail of interest.
             * @param groupId the id of group to which this request belongs
             * @param kind The type of thumbnail to fetch. Should be either MINI_KIND or MICRO_KIND.
             * @param options this is only used for MINI_KIND when decoding the Bitmap
             * @return A Bitmap instance. It could be null if the original image
             *         associated with origId doesn't exist or memory is not enough.
             */
            public static Bitmap getThumbnail(ContentResolver cr, long origId, long groupId,
                    int kind, BitmapFactory.Options options) {
                return InternalThumbnails.getThumbnail(cr, origId, groupId, kind, options,
                        EXTERNAL_CONTENT_URI, false);
            }

            /**
             * Get the content:// style URI for the image media table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the image media table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                        "/images/thumbnails");
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = "image_id ASC";

            /**
             * The data stream for the thumbnail
             * <P>Type: DATA STREAM</P>
             */
            public static final String DATA = "_data";

            /**
             * The original image for the thumbnal
             * <P>Type: INTEGER (ID from Images table)</P>
             */
            public static final String IMAGE_ID = "image_id";

            /**
             * The kind of the thumbnail
             * <P>Type: INTEGER (One of the values below)</P>
             */
            public static final String KIND = "kind";

            public static final int MINI_KIND = 1;
            public static final int FULL_SCREEN_KIND = 2;
            public static final int MICRO_KIND = 3;
            /**
             * The blob raw data of thumbnail
             * <P>Type: DATA STREAM</P>
             */
            public static final String THUMB_DATA = "thumb_data";

            /**
             * The width of the thumbnal
             * <P>Type: INTEGER (long)</P>
             */
            public static final String WIDTH = "width";

            /**
             * The height of the thumbnail
             * <P>Type: INTEGER (long)</P>
             */
            public static final String HEIGHT = "height";
        }
    }

    /**
     * Container for all audio content.
     */
    public static final class Audio {
        /**
         * Columns for audio file that show up in multiple tables.
         */
        public interface AudioColumns extends MediaColumns {

            /**
             * A non human readable key calculated from the TITLE, used for
             * searching, sorting and grouping
             * <P>Type: TEXT</P>
             */
            public static final String TITLE_KEY = "title_key";

            /**
             * The duration of the audio file, in ms
             * <P>Type: INTEGER (long)</P>
             */
            public static final String DURATION = "duration";

            /**
             * The position, in ms, playback was at when playback for this file
             * was last stopped.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String BOOKMARK = "bookmark";

            /**
             * The id of the artist who created the audio file, if any
             * <P>Type: INTEGER (long)</P>
             */
            public static final String ARTIST_ID = "artist_id";

            /**
             * The artist who created the audio file, if any
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST = "artist";

            /**
             * The artist credited for the album that contains the audio file
             * <P>Type: TEXT</P>
             * @hide
             */
            public static final String ALBUM_ARTIST = "album_artist";

            /**
             * Whether the song is part of a compilation
             * <P>Type: TEXT</P>
             * @hide
             */
            public static final String COMPILATION = "compilation";

            /**
             * A non human readable key calculated from the ARTIST, used for
             * searching, sorting and grouping
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST_KEY = "artist_key";

            /**
             * The composer of the audio file, if any
             * <P>Type: TEXT</P>
             */
            public static final String COMPOSER = "composer";

            /**
             * The id of the album the audio file is from, if any
             * <P>Type: INTEGER (long)</P>
             */
            public static final String ALBUM_ID = "album_id";

            /**
             * The album the audio file is from, if any
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM = "album";

            /**
             * A non human readable key calculated from the ALBUM, used for
             * searching, sorting and grouping
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM_KEY = "album_key";

            /**
             * The track number of this song on the album, if any.
             * This number encodes both the track number and the
             * disc number. For multi-disc sets, this number will
             * be 1xxx for tracks on the first disc, 2xxx for tracks
             * on the second disc, etc.
             * <P>Type: INTEGER</P>
             */
            public static final String TRACK = "track";

            /**
             * The year the audio file was recorded, if any
             * <P>Type: INTEGER</P>
             */
            public static final String YEAR = "year";

            /**
             * Non-zero if the audio file is music
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_MUSIC = "is_music";

            /**
             * Non-zero if the audio file is a podcast
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_PODCAST = "is_podcast";

            /**
             * Non-zero if the audio file may be a ringtone
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_RINGTONE = "is_ringtone";

            /**
             * Non-zero if the audio file may be an alarm
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_ALARM = "is_alarm";

            /**
             * Non-zero if the audio file may be a notification sound
             * <P>Type: INTEGER (boolean)</P>
             */
            public static final String IS_NOTIFICATION = "is_notification";

            /**
             * The genre of the audio file, if any
             * <P>Type: TEXT</P>
             * Does not exist in the database - only used by the media scanner for inserts.
             * @hide
             */
            public static final String GENRE = "genre";
        }

        /**
         * Converts a name to a "key" that can be used for grouping, sorting
         * and searching.
         * The rules that govern this conversion are:
         * - remove 'special' characters like ()[]'!?.,
         * - remove leading/trailing spaces
         * - convert everything to lowercase
         * - remove leading "the ", "an " and "a "
         * - remove trailing ", the|an|a"
         * - remove accents. This step leaves us with CollationKey data,
         *   which is not human readable
         *
         * @param name The artist or album name to convert
         * @return The "key" for the given name.
         */
        public static String keyFor(String name) {
            if (name != null)  {
                boolean sortfirst = false;
                if (name.equals(UNKNOWN_STRING)) {
                    return "\001";
                }
                // Check if the first character is \001. We use this to
                // force sorting of certain special files, like the silent ringtone.
                if (name.startsWith("\001")) {
                    sortfirst = true;
                }
                name = name.trim().toLowerCase();
                if (name.startsWith("the ")) {
                    name = name.substring(4);
                }
                if (name.startsWith("an ")) {
                    name = name.substring(3);
                }
                if (name.startsWith("a ")) {
                    name = name.substring(2);
                }
                if (name.endsWith(", the") || name.endsWith(",the") ||
                    name.endsWith(", an") || name.endsWith(",an") ||
                    name.endsWith(", a") || name.endsWith(",a")) {
                    name = name.substring(0, name.lastIndexOf(','));
                }
                name = name.replaceAll("[\\[\\]\\(\\)\"'.,?!]", "").trim();
                if (name.length() > 0) {
                    // Insert a separator between the characters to avoid
                    // matches on a partial character. If we ever change
                    // to start-of-word-only matches, this can be removed.
                    StringBuilder b = new StringBuilder();
                    b.append('.');
                    int nl = name.length();
                    for (int i = 0; i < nl; i++) {
                        b.append(name.charAt(i));
                        b.append('.');
                    }
                    name = b.toString();
                    String key = DatabaseUtils.getCollationKey(name);
                    if (sortfirst) {
                        key = "\001" + key;
                    }
                    return key;
               } else {
                    return "";
                }
            }
            return null;
        }

        public static final class Media implements AudioColumns {
            /**
             * Get the content:// style URI for the audio media table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the audio media table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                        "/audio/media");
            }

            public static Uri getContentUriForPath(String path) {
                return (path.startsWith(Environment.getExternalStorageDirectory().getPath()) ?
                        EXTERNAL_CONTENT_URI : INTERNAL_CONTENT_URI);
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/audio";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = TITLE_KEY;

            /**
             * Activity Action: Start SoundRecorder application.
             * <p>Input: nothing.
             * <p>Output: An uri to the recorded sound stored in the Media Library
             * if the recording was successful.
             * May also contain the extra EXTRA_MAX_BYTES.
             * @see #EXTRA_MAX_BYTES
             */
            public static final String RECORD_SOUND_ACTION =
                    "android.provider.MediaStore.RECORD_SOUND";

            /**
             * The name of the Intent-extra used to define a maximum file size for
             * a recording made by the SoundRecorder application.
             *
             * @see #RECORD_SOUND_ACTION
             */
             public static final String EXTRA_MAX_BYTES =
                    "android.provider.MediaStore.extra.MAX_BYTES";
        }

        /**
         * Columns representing an audio genre
         */
        public interface GenresColumns {
            /**
             * The name of the genre
             * <P>Type: TEXT</P>
             */
            public static final String NAME = "name";
        }

        /**
         * Contains all genres for audio files
         */
        public static final class Genres implements BaseColumns, GenresColumns {
            /**
             * Get the content:// style URI for the audio genres table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the audio genres table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                        "/audio/genres");
            }

            /**
             * Get the content:// style URI for querying the genres of an audio file.
             *
             * @param volumeName the name of the volume to get the URI for
             * @param audioId the ID of the audio file for which to retrieve the genres
             * @return the URI to for querying the genres for the audio file
             * with the given the volume and audioID
             */
            public static Uri getContentUriForAudioId(String volumeName, int audioId) {
                return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                        "/audio/media/" + audioId + "/genres");
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/genre";

            /**
             * The MIME type for entries in this table.
             */
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/genre";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = NAME;

            /**
             * Sub-directory of each genre containing all members.
             */
            public static final class Members implements AudioColumns {

                public static final Uri getContentUri(String volumeName,
                        long genreId) {
                    return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName
                            + "/audio/genres/" + genreId + "/members");
                }

                /**
                 * A subdirectory of each genre containing all member audio files.
                 */
                public static final String CONTENT_DIRECTORY = "members";

                /**
                 * The default sort order for this table
                 */
                public static final String DEFAULT_SORT_ORDER = TITLE_KEY;

                /**
                 * The ID of the audio file
                 * <P>Type: INTEGER (long)</P>
                 */
                public static final String AUDIO_ID = "audio_id";

                /**
                 * The ID of the genre
                 * <P>Type: INTEGER (long)</P>
                 */
                public static final String GENRE_ID = "genre_id";
            }
        }

        /**
         * Columns representing a playlist
         */
        public interface PlaylistsColumns {
            /**
             * The name of the playlist
             * <P>Type: TEXT</P>
             */
            public static final String NAME = "name";

            /**
             * The data stream for the playlist file
             * <P>Type: DATA STREAM</P>
             */
            public static final String DATA = "_data";

            /**
             * The time the file was added to the media provider
             * Units are seconds since 1970.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String DATE_ADDED = "date_added";

            /**
             * The time the file was last modified
             * Units are seconds since 1970.
             * NOTE: This is for internal use by the media scanner.  Do not modify this field.
             * <P>Type: INTEGER (long)</P>
             */
            public static final String DATE_MODIFIED = "date_modified";
        }

        /**
         * Contains playlists for audio files
         */
        public static final class Playlists implements BaseColumns,
                PlaylistsColumns {
            /**
             * Get the content:// style URI for the audio playlists table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the audio playlists table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                        "/audio/playlists");
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/playlist";

            /**
             * The MIME type for entries in this table.
             */
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/playlist";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = NAME;

            /**
             * Sub-directory of each playlist containing all members.
             */
            public static final class Members implements AudioColumns {
                public static final Uri getContentUri(String volumeName,
                        long playlistId) {
                    return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName
                            + "/audio/playlists/" + playlistId + "/members");
                }

                /**
                 * Convenience method to move a playlist item to a new location
                 * @param res The content resolver to use
                 * @param playlistId The numeric id of the playlist
                 * @param from The position of the item to move
                 * @param to The position to move the item to
                 * @return true on success
                 */
                public static final boolean moveItem(ContentResolver res,
                        long playlistId, int from, int to) {
                    Uri uri = MediaStore.Audio.Playlists.Members.getContentUri("external",
                            playlistId)
                            .buildUpon()
                            .appendEncodedPath(String.valueOf(from))
                            .appendQueryParameter("move", "true")
                            .build();
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, to);
                    return res.update(uri, values, null, null) != 0;
                }

                /**
                 * The ID within the playlist.
                 */
                public static final String _ID = "_id";

                /**
                 * A subdirectory of each playlist containing all member audio
                 * files.
                 */
                public static final String CONTENT_DIRECTORY = "members";

                /**
                 * The ID of the audio file
                 * <P>Type: INTEGER (long)</P>
                 */
                public static final String AUDIO_ID = "audio_id";

                /**
                 * The ID of the playlist
                 * <P>Type: INTEGER (long)</P>
                 */
                public static final String PLAYLIST_ID = "playlist_id";

                /**
                 * The order of the songs in the playlist
                 * <P>Type: INTEGER (long)></P>
                 */
                public static final String PLAY_ORDER = "play_order";

                /**
                 * The default sort order for this table
                 */
                public static final String DEFAULT_SORT_ORDER = PLAY_ORDER;
            }
        }

        /**
         * Columns representing an artist
         */
        public interface ArtistColumns {
            /**
             * The artist who created the audio file, if any
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST = "artist";

            /**
             * A non human readable key calculated from the ARTIST, used for
             * searching, sorting and grouping
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST_KEY = "artist_key";

            /**
             * The number of albums in the database for this artist
             */
            public static final String NUMBER_OF_ALBUMS = "number_of_albums";

            /**
             * The number of albums in the database for this artist
             */
            public static final String NUMBER_OF_TRACKS = "number_of_tracks";
        }

        /**
         * Contains artists for audio files
         */
        public static final class Artists implements BaseColumns, ArtistColumns {
            /**
             * Get the content:// style URI for the artists table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the audio artists table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                        "/audio/artists");
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/artists";

            /**
             * The MIME type for entries in this table.
             */
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/artist";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = ARTIST_KEY;

            /**
             * Sub-directory of each artist containing all albums on which
             * a song by the artist appears.
             */
            public static final class Albums implements AlbumColumns {
                public static final Uri getContentUri(String volumeName,
                        long artistId) {
                    return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName
                            + "/audio/artists/" + artistId + "/albums");
                }
            }
        }

        /**
         * Columns representing an album
         */
        public interface AlbumColumns {

            /**
             * The id for the album
             * <P>Type: INTEGER</P>
             */
            public static final String ALBUM_ID = "album_id";

            /**
             * The album on which the audio file appears, if any
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM = "album";

            /**
             * The artist whose songs appear on this album
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST = "artist";

            /**
             * The number of songs on this album
             * <P>Type: INTEGER</P>
             */
            public static final String NUMBER_OF_SONGS = "numsongs";

            /**
             * This column is available when getting album info via artist,
             * and indicates the number of songs on the album by the given
             * artist.
             * <P>Type: INTEGER</P>
             */
            public static final String NUMBER_OF_SONGS_FOR_ARTIST = "numsongs_by_artist";

            /**
             * The year in which the earliest songs
             * on this album were released. This will often
             * be the same as {@link #LAST_YEAR}, but for compilation albums
             * they might differ.
             * <P>Type: INTEGER</P>
             */
            public static final String FIRST_YEAR = "minyear";

            /**
             * The year in which the latest songs
             * on this album were released. This will often
             * be the same as {@link #FIRST_YEAR}, but for compilation albums
             * they might differ.
             * <P>Type: INTEGER</P>
             */
            public static final String LAST_YEAR = "maxyear";

            /**
             * A non human readable key calculated from the ALBUM, used for
             * searching, sorting and grouping
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM_KEY = "album_key";

            /**
             * Cached album art.
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM_ART = "album_art";
        }

        /**
         * Contains artists for audio files
         */
        public static final class Albums implements BaseColumns, AlbumColumns {
            /**
             * Get the content:// style URI for the albums table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the audio albums table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                        "/audio/albums");
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/albums";

            /**
             * The MIME type for entries in this table.
             */
            public static final String ENTRY_CONTENT_TYPE = "vnd.android.cursor.item/album";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = ALBUM_KEY;
        }
    }

    public static final class Video {

        /**
         * The default sort order for this table.
         */
        public static final String DEFAULT_SORT_ORDER = MediaColumns.DISPLAY_NAME;

        public static final Cursor query(ContentResolver cr, Uri uri, String[] projection) {
            return cr.query(uri, projection, null, null, DEFAULT_SORT_ORDER);
        }

        public interface VideoColumns extends MediaColumns {

            /**
             * The duration of the video file, in ms
             * <P>Type: INTEGER (long)</P>
             */
            public static final String DURATION = "duration";

            /**
             * The artist who created the video file, if any
             * <P>Type: TEXT</P>
             */
            public static final String ARTIST = "artist";

            /**
             * The album the video file is from, if any
             * <P>Type: TEXT</P>
             */
            public static final String ALBUM = "album";

            /**
             * The resolution of the video file, formatted as "XxY"
             * <P>Type: TEXT</P>
             */
            public static final String RESOLUTION = "resolution";

            /**
             * The description of the video recording
             * <P>Type: TEXT</P>
             */
            public static final String DESCRIPTION = "description";

            /**
             * Whether the video should be published as public or private
             * <P>Type: INTEGER</P>
             */
            public static final String IS_PRIVATE = "isprivate";

            /**
             * The user-added tags associated with a video
             * <P>Type: TEXT</P>
             */
            public static final String TAGS = "tags";

            /**
             * The YouTube category of the video
             * <P>Type: TEXT</P>
             */
            public static final String CATEGORY = "category";

            /**
             * The language of the video
             * <P>Type: TEXT</P>
             */
            public static final String LANGUAGE = "language";

            /**
             * The latitude where the video was captured.
             * <P>Type: DOUBLE</P>
             */
            public static final String LATITUDE = "latitude";

            /**
             * The longitude where the video was captured.
             * <P>Type: DOUBLE</P>
             */
            public static final String LONGITUDE = "longitude";

            /**
             * The date & time that the video was taken in units
             * of milliseconds since jan 1, 1970.
             * <P>Type: INTEGER</P>
             */
            public static final String DATE_TAKEN = "datetaken";

            /**
             * The mini thumb id.
             * <P>Type: INTEGER</P>
             */
            public static final String MINI_THUMB_MAGIC = "mini_thumb_magic";

            /**
             * The bucket id of the video. This is a read-only property that
             * is automatically computed from the DATA column.
             * <P>Type: TEXT</P>
             */
            public static final String BUCKET_ID = "bucket_id";

            /**
             * The bucket display name of the video. This is a read-only property that
             * is automatically computed from the DATA column.
             * <P>Type: TEXT</P>
             */
            public static final String BUCKET_DISPLAY_NAME = "bucket_display_name";

            /**
             * The bookmark for the video. Time in ms. Represents the location in the video that the
             * video should start playing at the next time it is opened. If the value is null or
             * out of the range 0..DURATION-1 then the video should start playing from the
             * beginning.
             * <P>Type: INTEGER</P>
             */
            public static final String BOOKMARK = "bookmark";
        }

        public static final class Media implements VideoColumns {
            /**
             * Get the content:// style URI for the video media table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the video media table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                        "/video/media");
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The MIME type for this table.
             */
            public static final String CONTENT_TYPE = "vnd.android.cursor.dir/video";

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = TITLE;
        }

        /**
         * This class allows developers to query and get two kinds of thumbnails:
         * MINI_KIND: 512 x 384 thumbnail
         * MICRO_KIND: 96 x 96 thumbnail
         *
         */
        public static class Thumbnails implements BaseColumns {
            /**
             * This method cancels the thumbnail request so clients waiting for getThumbnail will be
             * interrupted and return immediately. Only the original process which made the getThumbnail
             * requests can cancel their own requests.
             *
             * @param cr ContentResolver
             * @param origId original video id
             */
            public static void cancelThumbnailRequest(ContentResolver cr, long origId) {
                InternalThumbnails.cancelThumbnailRequest(cr, origId, EXTERNAL_CONTENT_URI,
                        InternalThumbnails.DEFAULT_GROUP_ID);
            }

            /**
             * This method checks if the thumbnails of the specified image (origId) has been created.
             * It will be blocked until the thumbnails are generated.
             *
             * @param cr ContentResolver used to dispatch queries to MediaProvider.
             * @param origId Original image id associated with thumbnail of interest.
             * @param kind The type of thumbnail to fetch. Should be either MINI_KIND or MICRO_KIND.
             * @param options this is only used for MINI_KIND when decoding the Bitmap
             * @return A Bitmap instance. It could be null if the original image
             *         associated with origId doesn't exist or memory is not enough.
             */
            public static Bitmap getThumbnail(ContentResolver cr, long origId, int kind,
                    BitmapFactory.Options options) {
                return InternalThumbnails.getThumbnail(cr, origId,
                        InternalThumbnails.DEFAULT_GROUP_ID, kind, options,
                        EXTERNAL_CONTENT_URI, true);
            }

            /**
             * This method checks if the thumbnails of the specified image (origId) has been created.
             * It will be blocked until the thumbnails are generated.
             *
             * @param cr ContentResolver used to dispatch queries to MediaProvider.
             * @param origId Original image id associated with thumbnail of interest.
             * @param groupId the id of group to which this request belongs
             * @param kind The type of thumbnail to fetch. Should be either MINI_KIND or MICRO_KIND
             * @param options this is only used for MINI_KIND when decoding the Bitmap
             * @return A Bitmap instance. It could be null if the original image associated with
             *         origId doesn't exist or memory is not enough.
             */
            public static Bitmap getThumbnail(ContentResolver cr, long origId, long groupId,
                    int kind, BitmapFactory.Options options) {
                return InternalThumbnails.getThumbnail(cr, origId, groupId, kind, options,
                        EXTERNAL_CONTENT_URI, true);
            }

            /**
             * This method cancels the thumbnail request so clients waiting for getThumbnail will be
             * interrupted and return immediately. Only the original process which made the getThumbnail
             * requests can cancel their own requests.
             *
             * @param cr ContentResolver
             * @param origId original video id
             * @param groupId the same groupId used in getThumbnail.
             */
            public static void cancelThumbnailRequest(ContentResolver cr, long origId, long groupId) {
                InternalThumbnails.cancelThumbnailRequest(cr, origId, EXTERNAL_CONTENT_URI, groupId);
            }

            /**
             * Get the content:// style URI for the image media table on the
             * given volume.
             *
             * @param volumeName the name of the volume to get the URI for
             * @return the URI to the image media table on the given volume
             */
            public static Uri getContentUri(String volumeName) {
                return Uri.parse(CONTENT_AUTHORITY_SLASH + volumeName +
                        "/video/thumbnails");
            }

            /**
             * The content:// style URI for the internal storage.
             */
            public static final Uri INTERNAL_CONTENT_URI =
                    getContentUri("internal");

            /**
             * The content:// style URI for the "primary" external storage
             * volume.
             */
            public static final Uri EXTERNAL_CONTENT_URI =
                    getContentUri("external");

            /**
             * The default sort order for this table
             */
            public static final String DEFAULT_SORT_ORDER = "video_id ASC";

            /**
             * The data stream for the thumbnail
             * <P>Type: DATA STREAM</P>
             */
            public static final String DATA = "_data";

            /**
             * The original image for the thumbnal
             * <P>Type: INTEGER (ID from Video table)</P>
             */
            public static final String VIDEO_ID = "video_id";

            /**
             * The kind of the thumbnail
             * <P>Type: INTEGER (One of the values below)</P>
             */
            public static final String KIND = "kind";

            public static final int MINI_KIND = 1;
            public static final int FULL_SCREEN_KIND = 2;
            public static final int MICRO_KIND = 3;

            /**
             * The width of the thumbnal
             * <P>Type: INTEGER (long)</P>
             */
            public static final String WIDTH = "width";

            /**
             * The height of the thumbnail
             * <P>Type: INTEGER (long)</P>
             */
            public static final String HEIGHT = "height";
        }
    }

    /**
     * Uri for querying the state of the media scanner.
     */
    public static Uri getMediaScannerUri() {
        return Uri.parse(CONTENT_AUTHORITY_SLASH + "none/media_scanner");
    }

    /**
     * Name of current volume being scanned by the media scanner.
     */
    public static final String MEDIA_SCANNER_VOLUME = "volume";

    /**
     * Name of the file signaling the media scanner to ignore media in the containing directory
     * and its subdirectories. Developers should use this to avoid application graphics showing
     * up in the Gallery and likewise prevent application sounds and music from showing up in
     * the Music app.
     */
    public static final String MEDIA_IGNORE_FILENAME = ".nomedia";

    /**
     * Get the media provider's version.
     * Applications that import data from the media provider into their own caches
     * can use this to detect that the media provider changed, and reimport data
     * as needed. No other assumptions should be made about the meaning of the version.
     * @param context Context to use for performing the query.
     * @return A version string, or null if the version could not be determined.
     */
    public static String getVersion(Context context) {
        Cursor c = context.getContentResolver().query(
                Uri.parse(CONTENT_AUTHORITY_SLASH + "none/version"),
                null, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    return c.getString(0);
                }
            } finally {
                c.close();
            }
        }
        return null;
    }

}
