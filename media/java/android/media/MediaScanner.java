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

import android.annotation.UnsupportedAppUsage;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.SQLException;
import android.drm.DrmManagerClient;
import android.graphics.BitmapFactory;
import android.mtp.MtpConstants;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio;
import android.provider.MediaStore.Audio.Playlists;
import android.provider.MediaStore.Files;
import android.provider.MediaStore.Files.FileColumns;
import android.provider.MediaStore.Images;
import android.provider.MediaStore.Video;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.sax.Element;
import android.sax.ElementListener;
import android.sax.RootElement;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;

import dalvik.system.CloseGuard;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Internal service helper that no-one should use directly.
 *
 * The way the scan currently works is:
 * - The Java MediaScannerService creates a MediaScanner (this class), and calls
 *   MediaScanner.scanDirectories on it.
 * - scanDirectories() calls the native processDirectory() for each of the specified directories.
 * - the processDirectory() JNI method wraps the provided mediascanner client in a native
 *   'MyMediaScannerClient' class, then calls processDirectory() on the native MediaScanner
 *   object (which got created when the Java MediaScanner was created).
 * - native MediaScanner.processDirectory() calls
 *   doProcessDirectory(), which recurses over the folder, and calls
 *   native MyMediaScannerClient.scanFile() for every file whose extension matches.
 * - native MyMediaScannerClient.scanFile() calls back on Java MediaScannerClient.scanFile,
 *   which calls doScanFile, which after some setup calls back down to native code, calling
 *   MediaScanner.processFile().
 * - MediaScanner.processFile() calls one of several methods, depending on the type of the
 *   file: parseMP3, parseMP4, parseMidi, parseOgg or parseWMA.
 * - each of these methods gets metadata key/value pairs from the file, and repeatedly
 *   calls native MyMediaScannerClient.handleStringTag, which calls back up to its Java
 *   counterparts in this file.
 * - Java handleStringTag() gathers the key/value pairs that it's interested in.
 * - once processFile returns and we're back in Java code in doScanFile(), it calls
 *   Java MyMediaScannerClient.endFile(), which takes all the data that's been
 *   gathered and inserts an entry in to the database.
 *
 * In summary:
 * Java MediaScannerService calls
 * Java MediaScanner scanDirectories, which calls
 * Java MediaScanner processDirectory (native method), which calls
 * native MediaScanner processDirectory, which calls
 * native MyMediaScannerClient scanFile, which calls
 * Java MyMediaScannerClient scanFile, which calls
 * Java MediaScannerClient doScanFile, which calls
 * Java MediaScanner processFile (native method), which calls
 * native MediaScanner processFile, which calls
 * native parseMP3, parseMP4, parseMidi, parseOgg or parseWMA, which calls
 * native MyMediaScanner handleStringTag, which calls
 * Java MyMediaScanner handleStringTag.
 * Once MediaScanner processFile returns, an entry is inserted in to the database.
 *
 * The MediaScanner class is not thread-safe, so it should only be used in a single threaded manner.
 *
 * {@hide}
 *
 * @deprecated this media scanner has served faithfully for many years, but it's
 *             become tedious to test and maintain, mainly due to the way it
 *             weaves obscurely between managed and native code. It's been
 *             replaced by {@code ModernMediaScanner} in the
 *             {@code MediaProvider} package.
 */
@Deprecated
public class MediaScanner implements AutoCloseable {
    static {
        System.loadLibrary("media_jni");
        native_init();
    }

    private final static String TAG = "MediaScanner";

    @UnsupportedAppUsage
    private static final String[] FILES_PRESCAN_PROJECTION = new String[] {
            Files.FileColumns._ID, // 0
            Files.FileColumns.DATA, // 1
            Files.FileColumns.FORMAT, // 2
            Files.FileColumns.DATE_MODIFIED, // 3
            Files.FileColumns.MEDIA_TYPE, // 4
    };

    private static final String[] ID_PROJECTION = new String[] {
            Files.FileColumns._ID,
    };

    private static final int FILES_PRESCAN_ID_COLUMN_INDEX = 0;
    private static final int FILES_PRESCAN_PATH_COLUMN_INDEX = 1;
    private static final int FILES_PRESCAN_FORMAT_COLUMN_INDEX = 2;
    private static final int FILES_PRESCAN_DATE_MODIFIED_COLUMN_INDEX = 3;
    private static final int FILES_PRESCAN_MEDIA_TYPE_COLUMN_INDEX = 4;

    private static final String[] PLAYLIST_MEMBERS_PROJECTION = new String[] {
            Audio.Playlists.Members.PLAYLIST_ID, // 0
     };

    private static final int ID_PLAYLISTS_COLUMN_INDEX = 0;
    private static final int PATH_PLAYLISTS_COLUMN_INDEX = 1;
    private static final int DATE_MODIFIED_PLAYLISTS_COLUMN_INDEX = 2;

    private static final String RINGTONES_DIR = "/ringtones/";
    private static final String NOTIFICATIONS_DIR = "/notifications/";
    private static final String ALARMS_DIR = "/alarms/";
    private static final String MUSIC_DIR = "/music/";
    private static final String PODCASTS_DIR = "/podcasts/";
    private static final String AUDIOBOOKS_DIR = "/audiobooks/";

    public static final String SCANNED_BUILD_PREFS_NAME = "MediaScanBuild";
    public static final String LAST_INTERNAL_SCAN_FINGERPRINT = "lastScanFingerprint";
    private static final String SYSTEM_SOUNDS_DIR = Environment.getRootDirectory() + "/media/audio";
    private static final String OEM_SOUNDS_DIR = Environment.getOemDirectory() + "/media/audio";
    private static final String PRODUCT_SOUNDS_DIR = Environment.getProductDirectory() + "/media/audio";
    private static String sLastInternalScanFingerprint;

    private static final String[] ID3_GENRES = {
        // ID3v1 Genres
        "Blues",
        "Classic Rock",
        "Country",
        "Dance",
        "Disco",
        "Funk",
        "Grunge",
        "Hip-Hop",
        "Jazz",
        "Metal",
        "New Age",
        "Oldies",
        "Other",
        "Pop",
        "R&B",
        "Rap",
        "Reggae",
        "Rock",
        "Techno",
        "Industrial",
        "Alternative",
        "Ska",
        "Death Metal",
        "Pranks",
        "Soundtrack",
        "Euro-Techno",
        "Ambient",
        "Trip-Hop",
        "Vocal",
        "Jazz+Funk",
        "Fusion",
        "Trance",
        "Classical",
        "Instrumental",
        "Acid",
        "House",
        "Game",
        "Sound Clip",
        "Gospel",
        "Noise",
        "AlternRock",
        "Bass",
        "Soul",
        "Punk",
        "Space",
        "Meditative",
        "Instrumental Pop",
        "Instrumental Rock",
        "Ethnic",
        "Gothic",
        "Darkwave",
        "Techno-Industrial",
        "Electronic",
        "Pop-Folk",
        "Eurodance",
        "Dream",
        "Southern Rock",
        "Comedy",
        "Cult",
        "Gangsta",
        "Top 40",
        "Christian Rap",
        "Pop/Funk",
        "Jungle",
        "Native American",
        "Cabaret",
        "New Wave",
        "Psychadelic",
        "Rave",
        "Showtunes",
        "Trailer",
        "Lo-Fi",
        "Tribal",
        "Acid Punk",
        "Acid Jazz",
        "Polka",
        "Retro",
        "Musical",
        "Rock & Roll",
        "Hard Rock",
        // The following genres are Winamp extensions
        "Folk",
        "Folk-Rock",
        "National Folk",
        "Swing",
        "Fast Fusion",
        "Bebob",
        "Latin",
        "Revival",
        "Celtic",
        "Bluegrass",
        "Avantgarde",
        "Gothic Rock",
        "Progressive Rock",
        "Psychedelic Rock",
        "Symphonic Rock",
        "Slow Rock",
        "Big Band",
        "Chorus",
        "Easy Listening",
        "Acoustic",
        "Humour",
        "Speech",
        "Chanson",
        "Opera",
        "Chamber Music",
        "Sonata",
        "Symphony",
        "Booty Bass",
        "Primus",
        "Porn Groove",
        "Satire",
        "Slow Jam",
        "Club",
        "Tango",
        "Samba",
        "Folklore",
        "Ballad",
        "Power Ballad",
        "Rhythmic Soul",
        "Freestyle",
        "Duet",
        "Punk Rock",
        "Drum Solo",
        "A capella",
        "Euro-House",
        "Dance Hall",
        // The following ones seem to be fairly widely supported as well
        "Goa",
        "Drum & Bass",
        "Club-House",
        "Hardcore",
        "Terror",
        "Indie",
        "Britpop",
        null,
        "Polsk Punk",
        "Beat",
        "Christian Gangsta",
        "Heavy Metal",
        "Black Metal",
        "Crossover",
        "Contemporary Christian",
        "Christian Rock",
        "Merengue",
        "Salsa",
        "Thrash Metal",
        "Anime",
        "JPop",
        "Synthpop",
        // 148 and up don't seem to have been defined yet.
    };

    private long mNativeContext;
    @UnsupportedAppUsage
    private final Context mContext;
    @UnsupportedAppUsage
    private final String mPackageName;
    private final String mVolumeName;
    private final ContentProviderClient mMediaProvider;
    @UnsupportedAppUsage
    private final Uri mAudioUri;
    private final Uri mVideoUri;
    private final Uri mImagesUri;
    private final Uri mPlaylistsUri;
    @UnsupportedAppUsage
    private final Uri mFilesUri;
    private final Uri mFilesFullUri;
    private final boolean mProcessPlaylists;
    private final boolean mProcessGenres;
    private int mMtpObjectHandle;

    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final CloseGuard mCloseGuard = CloseGuard.get();

    /** whether to use bulk inserts or individual inserts for each item */
    private static final boolean ENABLE_BULK_INSERTS = true;

    // used when scanning the image database so we know whether we have to prune
    // old thumbnail files
    private int mOriginalCount;
    /** Whether the scanner has set a default sound for the ringer ringtone. */
    private boolean mDefaultRingtoneSet;
    /** Whether the scanner has set a default sound for the ringer ringtone2. */
    private boolean mDefaultRingtone2Set;
    /** Whether the scanner has set a default sound for the notification ringtone. */
    private boolean mDefaultNotificationSet;
    /** Whether the scanner has set a default sound for the alarm ringtone. */
    private boolean mDefaultAlarmSet;
    /** The filename for the default sound for the ringer ringtone. */
    @UnsupportedAppUsage
    private String mDefaultRingtoneFilename;
    /** The filename for the default sound for the ringer ringtone2. */
    private String mDefaultRingtone2Filename;
    /** The filename for the default sound for the notification ringtone. */
    @UnsupportedAppUsage
    private String mDefaultNotificationFilename;
    /** The filename for the default sound for the alarm ringtone. */
    @UnsupportedAppUsage
    private String mDefaultAlarmAlertFilename;
    /**
     * The prefix for system properties that define the default sound for
     * ringtones. Concatenate the name of the setting from Settings
     * to get the full system property.
     */
    private static final String DEFAULT_RINGTONE_PROPERTY_PREFIX = "ro.config.";
    /** The seperator to split ringtone for each slot in ro.config.ringtone. */
    private static final String RINGTONE_SEPARATOR = ",";

    private final BitmapFactory.Options mBitmapOptions = new BitmapFactory.Options();

    private static class FileEntry {
        @UnsupportedAppUsage
        long mRowId;
        String mPath;
        long mLastModified;
        int mFormat;
        int mMediaType;
        @UnsupportedAppUsage
        boolean mLastModifiedChanged;

        /** @deprecated kept intact for lame apps using reflection */
        @Deprecated
        @UnsupportedAppUsage
        FileEntry(long rowId, String path, long lastModified, int format) {
            this(rowId, path, lastModified, format, FileColumns.MEDIA_TYPE_NONE);
        }

        FileEntry(long rowId, String path, long lastModified, int format, int mediaType) {
            mRowId = rowId;
            mPath = path;
            mLastModified = lastModified;
            mFormat = format;
            mMediaType = mediaType;
            mLastModifiedChanged = false;
        }

        @Override
        public String toString() {
            return mPath + " mRowId: " + mRowId;
        }
    }

    private static class PlaylistEntry {
        String path;
        long bestmatchid;
        int bestmatchlevel;
    }

    private final ArrayList<PlaylistEntry> mPlaylistEntries = new ArrayList<>();
    private final ArrayList<FileEntry> mPlayLists = new ArrayList<>();

    @UnsupportedAppUsage
    private MediaInserter mMediaInserter;

    private DrmManagerClient mDrmManagerClient = null;

    @UnsupportedAppUsage
    public MediaScanner(Context c, String volumeName) {
        native_setup();
        mContext = c;
        mPackageName = c.getPackageName();
        mVolumeName = volumeName;

        mBitmapOptions.inSampleSize = 1;
        mBitmapOptions.inJustDecodeBounds = true;

        setDefaultRingtoneFileNames();

        mMediaProvider = mContext.getContentResolver()
                .acquireContentProviderClient(MediaStore.AUTHORITY);

        if (sLastInternalScanFingerprint == null) {
            final SharedPreferences scanSettings =
                    mContext.getSharedPreferences(SCANNED_BUILD_PREFS_NAME, Context.MODE_PRIVATE);
            sLastInternalScanFingerprint =
                    scanSettings.getString(LAST_INTERNAL_SCAN_FINGERPRINT, new String());
        }

        mAudioUri = Audio.Media.getContentUri(volumeName);
        mVideoUri = Video.Media.getContentUri(volumeName);
        mImagesUri = Images.Media.getContentUri(volumeName);
        mFilesUri = Files.getContentUri(volumeName);

        Uri filesFullUri = mFilesUri.buildUpon().appendQueryParameter("nonotify", "1").build();
        filesFullUri = MediaStore.setIncludePending(filesFullUri);
        filesFullUri = MediaStore.setIncludeTrashed(filesFullUri);
        mFilesFullUri = filesFullUri;

        if (!volumeName.equals("internal")) {
            // we only support playlists on external media
            mProcessPlaylists = true;
            mProcessGenres = true;
            mPlaylistsUri = Playlists.getContentUri(volumeName);
        } else {
            mProcessPlaylists = false;
            mProcessGenres = false;
            mPlaylistsUri = null;
        }

        final Locale locale = mContext.getResources().getConfiguration().locale;
        if (locale != null) {
            String language = locale.getLanguage();
            String country = locale.getCountry();
            if (language != null) {
                if (country != null) {
                    setLocale(language + "_" + country);
                } else {
                    setLocale(language);
                }
            }
        }

        mCloseGuard.open("close");
    }

    private void setDefaultRingtoneFileNames() {
        String defaultRingtoneProp = SystemProperties.get(DEFAULT_RINGTONE_PROPERTY_PREFIX
                + Settings.System.RINGTONE);
        if (!TextUtils.isEmpty(defaultRingtoneProp)) {
            String defaultRingtoneArray[] = defaultRingtoneProp.split(RINGTONE_SEPARATOR);
            mDefaultRingtoneFilename = defaultRingtoneArray[0];
            if (defaultRingtoneArray.length > 1) {
                // If length of defaultRingtoneArray more than 1, it means ro.default.ringtone
                // also includes slot2's ringtone
                mDefaultRingtone2Filename = defaultRingtoneArray[1];
            }
        }
        mDefaultNotificationFilename = SystemProperties.get(DEFAULT_RINGTONE_PROPERTY_PREFIX
                + Settings.System.NOTIFICATION_SOUND);
        mDefaultAlarmAlertFilename = SystemProperties.get(DEFAULT_RINGTONE_PROPERTY_PREFIX
                + Settings.System.ALARM_ALERT);
    }

    @UnsupportedAppUsage
    private final MyMediaScannerClient mClient = new MyMediaScannerClient();

    @UnsupportedAppUsage
    private boolean isDrmEnabled() {
        String prop = SystemProperties.get("drm.service.enabled");
        return prop != null && prop.equals("true");
    }

    private class MyMediaScannerClient implements MediaScannerClient {

        private final SimpleDateFormat mDateFormatter;

        private String mArtist;
        private String mAlbumArtist;    // use this if mArtist is missing
        private String mAlbum;
        private String mTitle;
        private String mComposer;
        private String mGenre;
        @UnsupportedAppUsage
        private String mMimeType;
        /** @deprecated file types no longer exist */
        @Deprecated
        @UnsupportedAppUsage
        private int mFileType;
        private int mTrack;
        private int mYear;
        private int mDuration;
        @UnsupportedAppUsage
        private String mPath;
        private long mDate;
        private long mLastModified;
        private long mFileSize;
        private String mWriter;
        private int mCompilation;
        @UnsupportedAppUsage
        private boolean mIsDrm;
        @UnsupportedAppUsage
        private boolean mNoMedia;   // flag to suppress file from appearing in media tables
        private boolean mScanSuccess;
        private int mWidth;
        private int mHeight;
        private int mColorStandard;
        private int mColorTransfer;
        private int mColorRange;

        public MyMediaScannerClient() {
            mDateFormatter = new SimpleDateFormat("yyyyMMdd'T'HHmmss");
            mDateFormatter.setTimeZone(TimeZone.getTimeZone("UTC"));
        }

        @UnsupportedAppUsage
        public FileEntry beginFile(String path, String mimeType, long lastModified,
                long fileSize, boolean isDirectory, boolean noMedia) {
            mMimeType = mimeType;
            mFileSize = fileSize;
            mIsDrm = false;
            mScanSuccess = true;

            if (!isDirectory) {
                if (!noMedia && isNoMediaFile(path)) {
                    noMedia = true;
                }
                mNoMedia = noMedia;

                // if mimeType was not specified, compute file type based on file extension.
                if (mMimeType == null) {
                    mMimeType = MediaFile.getMimeTypeForFile(path);
                }

                if (isDrmEnabled() && MediaFile.isDrmMimeType(mMimeType)) {
                    getMimeTypeFromDrm(path);
                }
            }

            FileEntry entry = makeEntryFor(path);
            // add some slack to avoid a rounding error
            long delta = (entry != null) ? (lastModified - entry.mLastModified) : 0;
            boolean wasModified = delta > 1 || delta < -1;
            if (entry == null || wasModified) {
                if (wasModified) {
                    entry.mLastModified = lastModified;
                } else {
                    entry = new FileEntry(0, path, lastModified,
                            (isDirectory ? MtpConstants.FORMAT_ASSOCIATION : 0),
                            FileColumns.MEDIA_TYPE_NONE);
                }
                entry.mLastModifiedChanged = true;
            }

            if (mProcessPlaylists && MediaFile.isPlayListMimeType(mMimeType)) {
                mPlayLists.add(entry);
                // we don't process playlists in the main scan, so return null
                return null;
            }

            // clear all the metadata
            mArtist = null;
            mAlbumArtist = null;
            mAlbum = null;
            mTitle = null;
            mComposer = null;
            mGenre = null;
            mTrack = 0;
            mYear = 0;
            mDuration = 0;
            mPath = path;
            mDate = 0;
            mLastModified = lastModified;
            mWriter = null;
            mCompilation = 0;
            mWidth = 0;
            mHeight = 0;
            mColorStandard = -1;
            mColorTransfer = -1;
            mColorRange = -1;

            return entry;
        }

        @Override
        @UnsupportedAppUsage
        public void scanFile(String path, long lastModified, long fileSize,
                boolean isDirectory, boolean noMedia) {
            // This is the callback funtion from native codes.
            // Log.v(TAG, "scanFile: "+path);
            doScanFile(path, null, lastModified, fileSize, isDirectory, false, noMedia);
        }

        @UnsupportedAppUsage
        public Uri doScanFile(String path, String mimeType, long lastModified,
                long fileSize, boolean isDirectory, boolean scanAlways, boolean noMedia) {
            Uri result = null;
//            long t1 = System.currentTimeMillis();
            try {
                FileEntry entry = beginFile(path, mimeType, lastModified,
                        fileSize, isDirectory, noMedia);

                if (entry == null) {
                    return null;
                }

                // if this file was just inserted via mtp, set the rowid to zero
                // (even though it already exists in the database), to trigger
                // the correct code path for updating its entry
                if (mMtpObjectHandle != 0) {
                    entry.mRowId = 0;
                }

                if (entry.mPath != null) {
                    if (((!mDefaultNotificationSet &&
                                doesPathHaveFilename(entry.mPath, mDefaultNotificationFilename))
                        || (!mDefaultRingtoneSet &&
                                !TextUtils.isEmpty(mDefaultRingtoneFilename) &&
                                doesPathHaveFilename(entry.mPath, mDefaultRingtoneFilename))
                        || (!mDefaultRingtone2Set &&
                                !TextUtils.isEmpty(mDefaultRingtone2Filename) &&
                                doesPathHaveFilename(entry.mPath, mDefaultRingtone2Filename))
                        || (!mDefaultAlarmSet &&
                                doesPathHaveFilename(entry.mPath, mDefaultAlarmAlertFilename)))) {
                        Log.w(TAG, "forcing rescan of " + entry.mPath +
                                "since ringtone setting didn't finish");
                        scanAlways = true;
                    } else if (isSystemSoundWithMetadata(entry.mPath)
                            && !Build.CUSTOM_FINGERPRINT.equals(sLastInternalScanFingerprint)) {
                        // file is located on the system partition where the date cannot be trusted:
                        // rescan if the build fingerprint has changed since the last scan.
                        Log.i(TAG, "forcing rescan of " + entry.mPath
                                + " since build fingerprint changed");
                        scanAlways = true;
                    }
                }

                // rescan for metadata if file was modified since last scan
                if (entry != null && (entry.mLastModifiedChanged || scanAlways)) {
                    if (noMedia) {
                        result = endFile(entry, false, false, false, false, false, false);
                    } else {
                        boolean isaudio = MediaFile.isAudioMimeType(mMimeType);
                        boolean isvideo = MediaFile.isVideoMimeType(mMimeType);
                        boolean isimage = MediaFile.isImageMimeType(mMimeType);

                        if (isaudio || isvideo || isimage) {
                            path = Environment.maybeTranslateEmulatedPathToInternal(new File(path))
                                    .getAbsolutePath();
                        }

                        // we only extract metadata for audio and video files
                        if (isaudio || isvideo) {
                            mScanSuccess = processFile(path, mimeType, this);
                        }

                        if (isimage) {
                            mScanSuccess = processImageFile(path);
                        }

                        String lowpath = path.toLowerCase(Locale.ROOT);
                        boolean ringtones = mScanSuccess && (lowpath.indexOf(RINGTONES_DIR) > 0);
                        boolean notifications = mScanSuccess &&
                                (lowpath.indexOf(NOTIFICATIONS_DIR) > 0);
                        boolean alarms = mScanSuccess && (lowpath.indexOf(ALARMS_DIR) > 0);
                        boolean podcasts = mScanSuccess && (lowpath.indexOf(PODCASTS_DIR) > 0);
                        boolean audiobooks = mScanSuccess && (lowpath.indexOf(AUDIOBOOKS_DIR) > 0);
                        boolean music = mScanSuccess && ((lowpath.indexOf(MUSIC_DIR) > 0) ||
                            (!ringtones && !notifications && !alarms && !podcasts && !audiobooks));

                        result = endFile(entry, ringtones, notifications, alarms, podcasts,
                                audiobooks, music);
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in MediaScanner.scanFile()", e);
            }
//            long t2 = System.currentTimeMillis();
//            Log.v(TAG, "scanFile: " + path + " took " + (t2-t1));
            return result;
        }

        private long parseDate(String date) {
            try {
              return mDateFormatter.parse(date).getTime();
            } catch (ParseException e) {
              return 0;
            }
        }

        private int parseSubstring(String s, int start, int defaultValue) {
            int length = s.length();
            if (start == length) return defaultValue;

            char ch = s.charAt(start++);
            // return defaultValue if we have no integer at all
            if (ch < '0' || ch > '9') return defaultValue;

            int result = ch - '0';
            while (start < length) {
                ch = s.charAt(start++);
                if (ch < '0' || ch > '9') return result;
                result = result * 10 + (ch - '0');
            }

            return result;
        }

        @UnsupportedAppUsage
        public void handleStringTag(String name, String value) {
            if (name.equalsIgnoreCase("title") || name.startsWith("title;")) {
                // Don't trim() here, to preserve the special \001 character
                // used to force sorting. The media provider will trim() before
                // inserting the title in to the database.
                mTitle = value;
            } else if (name.equalsIgnoreCase("artist") || name.startsWith("artist;")) {
                mArtist = value.trim();
            } else if (name.equalsIgnoreCase("albumartist") || name.startsWith("albumartist;")
                    || name.equalsIgnoreCase("band") || name.startsWith("band;")) {
                mAlbumArtist = value.trim();
            } else if (name.equalsIgnoreCase("album") || name.startsWith("album;")) {
                mAlbum = value.trim();
            } else if (name.equalsIgnoreCase("composer") || name.startsWith("composer;")) {
                mComposer = value.trim();
            } else if (mProcessGenres &&
                    (name.equalsIgnoreCase("genre") || name.startsWith("genre;"))) {
                mGenre = getGenreName(value);
            } else if (name.equalsIgnoreCase("year") || name.startsWith("year;")) {
                mYear = parseSubstring(value, 0, 0);
            } else if (name.equalsIgnoreCase("tracknumber") || name.startsWith("tracknumber;")) {
                // track number might be of the form "2/12"
                // we just read the number before the slash
                int num = parseSubstring(value, 0, 0);
                mTrack = (mTrack / 1000) * 1000 + num;
            } else if (name.equalsIgnoreCase("discnumber") ||
                    name.equals("set") || name.startsWith("set;")) {
                // set number might be of the form "1/3"
                // we just read the number before the slash
                int num = parseSubstring(value, 0, 0);
                mTrack = (num * 1000) + (mTrack % 1000);
            } else if (name.equalsIgnoreCase("duration")) {
                mDuration = parseSubstring(value, 0, 0);
            } else if (name.equalsIgnoreCase("writer") || name.startsWith("writer;")) {
                mWriter = value.trim();
            } else if (name.equalsIgnoreCase("compilation")) {
                mCompilation = parseSubstring(value, 0, 0);
            } else if (name.equalsIgnoreCase("isdrm")) {
                mIsDrm = (parseSubstring(value, 0, 0) == 1);
            } else if (name.equalsIgnoreCase("date")) {
                mDate = parseDate(value);
            } else if (name.equalsIgnoreCase("width")) {
                mWidth = parseSubstring(value, 0, 0);
            } else if (name.equalsIgnoreCase("height")) {
                mHeight = parseSubstring(value, 0, 0);
            } else if (name.equalsIgnoreCase("colorstandard")) {
                mColorStandard = parseSubstring(value, 0, -1);
            } else if (name.equalsIgnoreCase("colortransfer")) {
                mColorTransfer = parseSubstring(value, 0, -1);
            } else if (name.equalsIgnoreCase("colorrange")) {
                mColorRange = parseSubstring(value, 0, -1);
            } else {
                //Log.v(TAG, "unknown tag: " + name + " (" + mProcessGenres + ")");
            }
        }

        private boolean convertGenreCode(String input, String expected) {
            String output = getGenreName(input);
            if (output.equals(expected)) {
                return true;
            } else {
                Log.d(TAG, "'" + input + "' -> '" + output + "', expected '" + expected + "'");
                return false;
            }
        }
        private void testGenreNameConverter() {
            convertGenreCode("2", "Country");
            convertGenreCode("(2)", "Country");
            convertGenreCode("(2", "(2");
            convertGenreCode("2 Foo", "Country");
            convertGenreCode("(2) Foo", "Country");
            convertGenreCode("(2 Foo", "(2 Foo");
            convertGenreCode("2Foo", "2Foo");
            convertGenreCode("(2)Foo", "Country");
            convertGenreCode("200 Foo", "Foo");
            convertGenreCode("(200) Foo", "Foo");
            convertGenreCode("200Foo", "200Foo");
            convertGenreCode("(200)Foo", "Foo");
            convertGenreCode("200)Foo", "200)Foo");
            convertGenreCode("200) Foo", "200) Foo");
        }

        public String getGenreName(String genreTagValue) {

            if (genreTagValue == null) {
                return null;
            }
            final int length = genreTagValue.length();

            if (length > 0) {
                boolean parenthesized = false;
                StringBuffer number = new StringBuffer();
                int i = 0;
                for (; i < length; ++i) {
                    char c = genreTagValue.charAt(i);
                    if (i == 0 && c == '(') {
                        parenthesized = true;
                    } else if (Character.isDigit(c)) {
                        number.append(c);
                    } else {
                        break;
                    }
                }
                char charAfterNumber = i < length ? genreTagValue.charAt(i) : ' ';
                if ((parenthesized && charAfterNumber == ')')
                        || !parenthesized && Character.isWhitespace(charAfterNumber)) {
                    try {
                        short genreIndex = Short.parseShort(number.toString());
                        if (genreIndex >= 0) {
                            if (genreIndex < ID3_GENRES.length && ID3_GENRES[genreIndex] != null) {
                                return ID3_GENRES[genreIndex];
                            } else if (genreIndex == 0xFF) {
                                return null;
                            } else if (genreIndex < 0xFF && (i + 1) < length) {
                                // genre is valid but unknown,
                                // if there is a string after the value we take it
                                if (parenthesized && charAfterNumber == ')') {
                                    i++;
                                }
                                String ret = genreTagValue.substring(i).trim();
                                if (ret.length() != 0) {
                                    return ret;
                                }
                            } else {
                                // else return the number, without parentheses
                                return number.toString();
                            }
                        }
                    } catch (NumberFormatException e) {
                    }
                }
            }

            return genreTagValue;
        }

        private boolean processImageFile(String path) {
            try {
                mBitmapOptions.outWidth = 0;
                mBitmapOptions.outHeight = 0;
                BitmapFactory.decodeFile(path, mBitmapOptions);
                mWidth = mBitmapOptions.outWidth;
                mHeight = mBitmapOptions.outHeight;
                return mWidth > 0 && mHeight > 0;
            } catch (Throwable th) {
                // ignore;
            }
            return false;
        }

        @UnsupportedAppUsage
        public void setMimeType(String mimeType) {
            if ("audio/mp4".equals(mMimeType) &&
                    mimeType.startsWith("video")) {
                // for feature parity with Donut, we force m4a files to keep the
                // audio/mp4 mimetype, even if they are really "enhanced podcasts"
                // with a video track
                return;
            }
            mMimeType = mimeType;
        }

        /**
         * Formats the data into a values array suitable for use with the Media
         * Content Provider.
         *
         * @return a map of values
         */
        @UnsupportedAppUsage
        private ContentValues toValues() {
            ContentValues map = new ContentValues();

            map.put(MediaStore.MediaColumns.DATA, mPath);
            map.put(MediaStore.MediaColumns.TITLE, mTitle);
            map.put(MediaStore.MediaColumns.DATE_MODIFIED, mLastModified);
            map.put(MediaStore.MediaColumns.SIZE, mFileSize);
            map.put(MediaStore.MediaColumns.MIME_TYPE, mMimeType);
            map.put(MediaStore.MediaColumns.IS_DRM, mIsDrm);
            map.putNull(MediaStore.MediaColumns.HASH);

            String resolution = null;
            if (mWidth > 0 && mHeight > 0) {
                map.put(MediaStore.MediaColumns.WIDTH, mWidth);
                map.put(MediaStore.MediaColumns.HEIGHT, mHeight);
                resolution = mWidth + "x" + mHeight;
            }

            if (!mNoMedia) {
                if (MediaFile.isVideoMimeType(mMimeType)) {
                    map.put(Video.Media.ARTIST, (mArtist != null && mArtist.length() > 0
                            ? mArtist : MediaStore.UNKNOWN_STRING));
                    map.put(Video.Media.ALBUM, (mAlbum != null && mAlbum.length() > 0
                            ? mAlbum : MediaStore.UNKNOWN_STRING));
                    map.put(Video.Media.DURATION, mDuration);
                    if (resolution != null) {
                        map.put(Video.Media.RESOLUTION, resolution);
                    }
                    if (mColorStandard >= 0) {
                        map.put(Video.Media.COLOR_STANDARD, mColorStandard);
                    }
                    if (mColorTransfer >= 0) {
                        map.put(Video.Media.COLOR_TRANSFER, mColorTransfer);
                    }
                    if (mColorRange >= 0) {
                        map.put(Video.Media.COLOR_RANGE, mColorRange);
                    }
                    if (mDate > 0) {
                        map.put(Video.Media.DATE_TAKEN, mDate);
                    }
                } else if (MediaFile.isImageMimeType(mMimeType)) {
                    // FIXME - add DESCRIPTION
                } else if (MediaFile.isAudioMimeType(mMimeType)) {
                    map.put(Audio.Media.ARTIST, (mArtist != null && mArtist.length() > 0) ?
                            mArtist : MediaStore.UNKNOWN_STRING);
                    map.put(Audio.Media.ALBUM_ARTIST, (mAlbumArtist != null &&
                            mAlbumArtist.length() > 0) ? mAlbumArtist : null);
                    map.put(Audio.Media.ALBUM, (mAlbum != null && mAlbum.length() > 0) ?
                            mAlbum : MediaStore.UNKNOWN_STRING);
                    map.put(Audio.Media.COMPOSER, mComposer);
                    map.put(Audio.Media.GENRE, mGenre);
                    if (mYear != 0) {
                        map.put(Audio.Media.YEAR, mYear);
                    }
                    map.put(Audio.Media.TRACK, mTrack);
                    map.put(Audio.Media.DURATION, mDuration);
                    map.put(Audio.Media.COMPILATION, mCompilation);
                }
            }
            return map;
        }

        @UnsupportedAppUsage
        private Uri endFile(FileEntry entry, boolean ringtones, boolean notifications,
                boolean alarms, boolean podcasts, boolean audiobooks, boolean music)
                throws RemoteException {
            // update database

            // use album artist if artist is missing
            if (mArtist == null || mArtist.length() == 0) {
                mArtist = mAlbumArtist;
            }

            ContentValues values = toValues();
            String title = values.getAsString(MediaStore.MediaColumns.TITLE);
            if (title == null || TextUtils.isEmpty(title.trim())) {
                title = MediaFile.getFileTitle(values.getAsString(MediaStore.MediaColumns.DATA));
                values.put(MediaStore.MediaColumns.TITLE, title);
            }
            String album = values.getAsString(Audio.Media.ALBUM);
            if (MediaStore.UNKNOWN_STRING.equals(album)) {
                album = values.getAsString(MediaStore.MediaColumns.DATA);
                // extract last path segment before file name
                int lastSlash = album.lastIndexOf('/');
                if (lastSlash >= 0) {
                    int previousSlash = 0;
                    while (true) {
                        int idx = album.indexOf('/', previousSlash + 1);
                        if (idx < 0 || idx >= lastSlash) {
                            break;
                        }
                        previousSlash = idx;
                    }
                    if (previousSlash != 0) {
                        album = album.substring(previousSlash + 1, lastSlash);
                        values.put(Audio.Media.ALBUM, album);
                    }
                }
            }
            long rowId = entry.mRowId;
            if (MediaFile.isAudioMimeType(mMimeType) && (rowId == 0 || mMtpObjectHandle != 0)) {
                // Only set these for new entries. For existing entries, they
                // may have been modified later, and we want to keep the current
                // values so that custom ringtones still show up in the ringtone
                // picker.
                values.put(Audio.Media.IS_RINGTONE, ringtones);
                values.put(Audio.Media.IS_NOTIFICATION, notifications);
                values.put(Audio.Media.IS_ALARM, alarms);
                values.put(Audio.Media.IS_MUSIC, music);
                values.put(Audio.Media.IS_PODCAST, podcasts);
                values.put(Audio.Media.IS_AUDIOBOOK, audiobooks);
            } else if (MediaFile.isExifMimeType(mMimeType) && !mNoMedia) {
                ExifInterface exif = null;
                try {
                    exif = new ExifInterface(entry.mPath);
                } catch (Exception ex) {
                    // exif is null
                }
                if (exif != null) {
                    long time = exif.getGpsDateTime();
                    if (time != -1) {
                        values.put(Images.Media.DATE_TAKEN, time);
                    } else {
                        // If no time zone information is available, we should consider using
                        // EXIF local time as taken time if the difference between file time
                        // and EXIF local time is not less than 1 Day, otherwise MediaProvider
                        // will use file time as taken time.
                        time = exif.getDateTime();
                        if (time != -1 && Math.abs(mLastModified * 1000 - time) >= 86400000) {
                            values.put(Images.Media.DATE_TAKEN, time);
                        }
                    }

                    int orientation = exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, -1);
                    if (orientation != -1) {
                        // We only recognize a subset of orientation tag values.
                        int degree;
                        switch(orientation) {
                            case ExifInterface.ORIENTATION_ROTATE_90:
                                degree = 90;
                                break;
                            case ExifInterface.ORIENTATION_ROTATE_180:
                                degree = 180;
                                break;
                            case ExifInterface.ORIENTATION_ROTATE_270:
                                degree = 270;
                                break;
                            default:
                                degree = 0;
                                break;
                        }
                        values.put(Images.Media.ORIENTATION, degree);
                    }
                }
            }

            Uri tableUri = mFilesUri;
            int mediaType = FileColumns.MEDIA_TYPE_NONE;
            MediaInserter inserter = mMediaInserter;
            if (!mNoMedia) {
                if (MediaFile.isVideoMimeType(mMimeType)) {
                    tableUri = mVideoUri;
                    mediaType = FileColumns.MEDIA_TYPE_VIDEO;
                } else if (MediaFile.isImageMimeType(mMimeType)) {
                    tableUri = mImagesUri;
                    mediaType = FileColumns.MEDIA_TYPE_IMAGE;
                } else if (MediaFile.isAudioMimeType(mMimeType)) {
                    tableUri = mAudioUri;
                    mediaType = FileColumns.MEDIA_TYPE_AUDIO;
                } else if (MediaFile.isPlayListMimeType(mMimeType)) {
                    tableUri = mPlaylistsUri;
                    mediaType = FileColumns.MEDIA_TYPE_PLAYLIST;
                }
            }
            Uri result = null;
            boolean needToSetSettings = false;
            // Setting a flag in order not to use bulk insert for the file related with
            // notifications, ringtones, and alarms, because the rowId of the inserted file is
            // needed.
            if (notifications && !mDefaultNotificationSet) {
                if (TextUtils.isEmpty(mDefaultNotificationFilename) ||
                        doesPathHaveFilename(entry.mPath, mDefaultNotificationFilename)) {
                    needToSetSettings = true;
                }
            } else if (ringtones) {
                if (!mDefaultRingtoneSet && (TextUtils.isEmpty(mDefaultRingtoneFilename) ||
                        doesPathHaveFilename(entry.mPath, mDefaultRingtoneFilename))) {
                    needToSetSettings = true;
                }
                if (!mDefaultRingtone2Set && (TextUtils.isEmpty(mDefaultRingtone2Filename) ||
                        doesPathHaveFilename(entry.mPath, mDefaultRingtone2Filename))) {
                    needToSetSettings = true;
                }
            } else if (alarms && !mDefaultAlarmSet) {
                if (TextUtils.isEmpty(mDefaultAlarmAlertFilename) ||
                        doesPathHaveFilename(entry.mPath, mDefaultAlarmAlertFilename)) {
                    needToSetSettings = true;
                }
            }

            if (rowId == 0) {
                if (mMtpObjectHandle != 0) {
                    values.put(MediaStore.MediaColumns.MEDIA_SCANNER_NEW_OBJECT_ID, mMtpObjectHandle);
                }
                if (tableUri == mFilesUri) {
                    int format = entry.mFormat;
                    if (format == 0) {
                        format = MediaFile.getFormatCode(entry.mPath, mMimeType);
                    }
                    values.put(Files.FileColumns.FORMAT, format);
                }
                // New file, insert it.
                // Directories need to be inserted before the files they contain, so they
                // get priority when bulk inserting.
                // If the rowId of the inserted file is needed, it gets inserted immediately,
                // bypassing the bulk inserter.
                if (inserter == null || needToSetSettings) {
                    if (inserter != null) {
                        inserter.flushAll();
                    }
                    result = mMediaProvider.insert(tableUri, values);
                } else if (entry.mFormat == MtpConstants.FORMAT_ASSOCIATION) {
                    inserter.insertwithPriority(tableUri, values);
                } else {
                    inserter.insert(tableUri, values);
                }

                if (result != null) {
                    rowId = ContentUris.parseId(result);
                    entry.mRowId = rowId;
                }
            } else {
                // updated file
                result = ContentUris.withAppendedId(tableUri, rowId);
                // path should never change, and we want to avoid replacing mixed cased paths
                // with squashed lower case paths
                values.remove(MediaStore.MediaColumns.DATA);

                if (!mNoMedia) {
                    // Changing media type must be done as separate update
                    if (mediaType != entry.mMediaType) {
                        final ContentValues mediaTypeValues = new ContentValues();
                        mediaTypeValues.put(FileColumns.MEDIA_TYPE, mediaType);
                        mMediaProvider.update(ContentUris.withAppendedId(mFilesUri, rowId),
                                mediaTypeValues, null, null);
                    }
                }

                mMediaProvider.update(result, values, null, null);
            }

            if(needToSetSettings) {
                if (notifications) {
                    setRingtoneIfNotSet(Settings.System.NOTIFICATION_SOUND, tableUri, rowId);
                    mDefaultNotificationSet = true;
                } else if (ringtones) {
                    if (!TextUtils.isEmpty(mDefaultRingtoneFilename)
                            && doesPathHaveFilename(entry.mPath, mDefaultRingtoneFilename)) {
                        setRingtoneIfNotSet(Settings.System.RINGTONE, tableUri, rowId);
                        mDefaultRingtoneSet = true;
                    }
                    if (!TextUtils.isEmpty(mDefaultRingtone2Filename)
                            && doesPathHaveFilename(entry.mPath, mDefaultRingtone2Filename)) {
                        setRingtoneIfNotSet(Settings.System.RINGTONE2, tableUri, rowId);
                        mDefaultRingtone2Set = true;
                    }
                } else if (alarms) {
                    setRingtoneIfNotSet(Settings.System.ALARM_ALERT, tableUri, rowId);
                    mDefaultAlarmSet = true;
                }
            }

            return result;
        }

        private boolean doesPathHaveFilename(String path, String filename) {
            int pathFilenameStart = path.lastIndexOf(File.separatorChar) + 1;
            int filenameLength = filename.length();
            return path.regionMatches(pathFilenameStart, filename, 0, filenameLength) &&
                    pathFilenameStart + filenameLength == path.length();
        }

        private void setRingtoneIfNotSet(String settingName, Uri uri, long rowId) {
            if (wasRingtoneAlreadySet(settingName)) {
                return;
            }

            ContentResolver cr = mContext.getContentResolver();
            String existingSettingValue = Settings.System.getString(cr, settingName);
            if (TextUtils.isEmpty(existingSettingValue)) {
                final Uri settingUri = Settings.System.getUriFor(settingName);
                final Uri ringtoneUri = ContentUris.withAppendedId(uri, rowId);
                final int slotId = Settings.System.RINGTONE2.equals(settingName) ? 1 : 0;
                RingtoneManager.setActualDefaultRingtoneUriBySlot(mContext,
                        RingtoneManager.getDefaultType(settingUri), ringtoneUri, slotId);
            }
            Settings.System.putInt(cr, settingSetIndicatorName(settingName), 1);
        }

        /** @deprecated file types no longer exist */
        @Deprecated
        @UnsupportedAppUsage
        private int getFileTypeFromDrm(String path) {
            return 0;
        }

        private void getMimeTypeFromDrm(String path) {
            mMimeType = null;

            if (mDrmManagerClient == null) {
                mDrmManagerClient = new DrmManagerClient(mContext);
            }

            if (mDrmManagerClient.canHandle(path, null)) {
                mIsDrm = true;
                mMimeType = mDrmManagerClient.getOriginalMimeType(path);
            }

            if (mMimeType == null) {
                mMimeType = ContentResolver.MIME_TYPE_DEFAULT;
            }
        }

    }; // end of anonymous MediaScannerClient instance

    private static boolean isSystemSoundWithMetadata(String path) {
        if (path.startsWith(SYSTEM_SOUNDS_DIR + ALARMS_DIR)
                || path.startsWith(SYSTEM_SOUNDS_DIR + RINGTONES_DIR)
                || path.startsWith(SYSTEM_SOUNDS_DIR + NOTIFICATIONS_DIR)
                || path.startsWith(OEM_SOUNDS_DIR + ALARMS_DIR)
                || path.startsWith(OEM_SOUNDS_DIR + RINGTONES_DIR)
                || path.startsWith(OEM_SOUNDS_DIR + NOTIFICATIONS_DIR)
                || path.startsWith(PRODUCT_SOUNDS_DIR + ALARMS_DIR)
                || path.startsWith(PRODUCT_SOUNDS_DIR + RINGTONES_DIR)
                || path.startsWith(PRODUCT_SOUNDS_DIR + NOTIFICATIONS_DIR)) {
            return true;
        }
        return false;
    }

    private String settingSetIndicatorName(String base) {
        return base + "_set";
    }

    private boolean wasRingtoneAlreadySet(String name) {
        ContentResolver cr = mContext.getContentResolver();
        String indicatorName = settingSetIndicatorName(name);
        try {
            return Settings.System.getInt(cr, indicatorName) != 0;
        } catch (SettingNotFoundException e) {
            return false;
        }
    }

    @UnsupportedAppUsage
    private void prescan(String filePath, boolean prescanFiles) throws RemoteException {
        Cursor c = null;
        String where = null;
        String[] selectionArgs = null;

        mPlayLists.clear();

        if (filePath != null) {
            // query for only one file
            where = MediaStore.Files.FileColumns._ID + ">?" +
                " AND " + Files.FileColumns.DATA + "=?";
            selectionArgs = new String[] { "", filePath };
        } else {
            where = MediaStore.Files.FileColumns._ID + ">?";
            selectionArgs = new String[] { "" };
        }

        mDefaultRingtoneSet = wasRingtoneAlreadySet(Settings.System.RINGTONE);
        mDefaultRingtone2Set = wasRingtoneAlreadySet(Settings.System.RINGTONE2);
        mDefaultNotificationSet = wasRingtoneAlreadySet(Settings.System.NOTIFICATION_SOUND);
        mDefaultAlarmSet = wasRingtoneAlreadySet(Settings.System.ALARM_ALERT);

        // Tell the provider to not delete the file.
        // If the file is truly gone the delete is unnecessary, and we want to avoid
        // accidentally deleting files that are really there (this may happen if the
        // filesystem is mounted and unmounted while the scanner is running).
        Uri.Builder builder = mFilesUri.buildUpon();
        builder.appendQueryParameter(MediaStore.PARAM_DELETE_DATA, "false");
        MediaBulkDeleter deleter = new MediaBulkDeleter(mMediaProvider, builder.build());

        // Build the list of files from the content provider
        try {
            if (prescanFiles) {
                // First read existing files from the files table.
                // Because we'll be deleting entries for missing files as we go,
                // we need to query the database in small batches, to avoid problems
                // with CursorWindow positioning.
                long lastId = Long.MIN_VALUE;
                Uri limitUri = mFilesUri.buildUpon()
                        .appendQueryParameter(MediaStore.PARAM_LIMIT, "1000").build();

                while (true) {
                    selectionArgs[0] = "" + lastId;
                    if (c != null) {
                        c.close();
                        c = null;
                    }
                    c = mMediaProvider.query(limitUri, FILES_PRESCAN_PROJECTION,
                            where, selectionArgs, MediaStore.Files.FileColumns._ID, null);
                    if (c == null) {
                        break;
                    }

                    int num = c.getCount();

                    if (num == 0) {
                        break;
                    }
                    while (c.moveToNext()) {
                        long rowId = c.getLong(FILES_PRESCAN_ID_COLUMN_INDEX);
                        String path = c.getString(FILES_PRESCAN_PATH_COLUMN_INDEX);
                        int format = c.getInt(FILES_PRESCAN_FORMAT_COLUMN_INDEX);
                        long lastModified = c.getLong(FILES_PRESCAN_DATE_MODIFIED_COLUMN_INDEX);
                        lastId = rowId;

                        // Only consider entries with absolute path names.
                        // This allows storing URIs in the database without the
                        // media scanner removing them.
                        if (path != null && path.startsWith("/")) {
                            boolean exists = false;
                            try {
                                exists = Os.access(path, android.system.OsConstants.F_OK);
                            } catch (ErrnoException e1) {
                            }
                            if (!exists && !MtpConstants.isAbstractObject(format)) {
                                // do not delete missing playlists, since they may have been
                                // modified by the user.
                                // The user can delete them in the media player instead.
                                // instead, clear the path and lastModified fields in the row
                                String mimeType = MediaFile.getMimeTypeForFile(path);
                                if (!MediaFile.isPlayListMimeType(mimeType)) {
                                    deleter.delete(rowId);
                                    if (path.toLowerCase(Locale.US).endsWith("/.nomedia")) {
                                        deleter.flush();
                                        String parent = new File(path).getParent();
                                        mMediaProvider.call(MediaStore.UNHIDE_CALL, parent, null);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        finally {
            if (c != null) {
                c.close();
            }
            deleter.flush();
        }

        // compute original size of images
        mOriginalCount = 0;
        c = mMediaProvider.query(mImagesUri, ID_PROJECTION, null, null, null, null);
        if (c != null) {
            mOriginalCount = c.getCount();
            c.close();
        }
    }

    static class MediaBulkDeleter {
        StringBuilder whereClause = new StringBuilder();
        ArrayList<String> whereArgs = new ArrayList<String>(100);
        final ContentProviderClient mProvider;
        final Uri mBaseUri;

        public MediaBulkDeleter(ContentProviderClient provider, Uri baseUri) {
            mProvider = provider;
            mBaseUri = baseUri;
        }

        public void delete(long id) throws RemoteException {
            if (whereClause.length() != 0) {
                whereClause.append(",");
            }
            whereClause.append("?");
            whereArgs.add("" + id);
            if (whereArgs.size() > 100) {
                flush();
            }
        }
        public void flush() throws RemoteException {
            int size = whereArgs.size();
            if (size > 0) {
                String [] foo = new String [size];
                foo = whereArgs.toArray(foo);
                int numrows = mProvider.delete(mBaseUri,
                        MediaStore.MediaColumns._ID + " IN (" +
                        whereClause.toString() + ")", foo);
                //Log.i("@@@@@@@@@", "rows deleted: " + numrows);
                whereClause.setLength(0);
                whereArgs.clear();
            }
        }
    }

    @UnsupportedAppUsage
    private void postscan(final String[] directories) throws RemoteException {

        // handle playlists last, after we know what media files are on the storage.
        if (mProcessPlaylists) {
            processPlayLists();
        }

        // allow GC to clean up
        mPlayLists.clear();
    }

    private void releaseResources() {
        // release the DrmManagerClient resources
        if (mDrmManagerClient != null) {
            mDrmManagerClient.close();
            mDrmManagerClient = null;
        }
    }

    public void scanDirectories(String[] directories) {
        try {
            long start = System.currentTimeMillis();
            prescan(null, true);
            long prescan = System.currentTimeMillis();

            if (ENABLE_BULK_INSERTS) {
                // create MediaInserter for bulk inserts
                mMediaInserter = new MediaInserter(mMediaProvider, 500);
            }

            for (int i = 0; i < directories.length; i++) {
                processDirectory(directories[i], mClient);
            }

            if (ENABLE_BULK_INSERTS) {
                // flush remaining inserts
                mMediaInserter.flushAll();
                mMediaInserter = null;
            }

            long scan = System.currentTimeMillis();
            postscan(directories);
            long end = System.currentTimeMillis();

            if (false) {
                Log.d(TAG, " prescan time: " + (prescan - start) + "ms\n");
                Log.d(TAG, "    scan time: " + (scan - prescan) + "ms\n");
                Log.d(TAG, "postscan time: " + (end - scan) + "ms\n");
                Log.d(TAG, "   total time: " + (end - start) + "ms\n");
            }
        } catch (SQLException e) {
            // this might happen if the SD card is removed while the media scanner is running
            Log.e(TAG, "SQLException in MediaScanner.scan()", e);
        } catch (UnsupportedOperationException e) {
            // this might happen if the SD card is removed while the media scanner is running
            Log.e(TAG, "UnsupportedOperationException in MediaScanner.scan()", e);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in MediaScanner.scan()", e);
        } finally {
            releaseResources();
        }
    }

    // this function is used to scan a single file
    @UnsupportedAppUsage
    public Uri scanSingleFile(String path, String mimeType) {
        try {
            prescan(path, true);

            File file = new File(path);
            if (!file.exists() || !file.canRead()) {
                return null;
            }

            // lastModified is in milliseconds on Files.
            long lastModifiedSeconds = file.lastModified() / 1000;

            // always scan the file, so we can return the content://media Uri for existing files
            return mClient.doScanFile(path, mimeType, lastModifiedSeconds, file.length(),
                    false, true, MediaScanner.isNoMediaPath(path));
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in MediaScanner.scanFile()", e);
            return null;
        } finally {
            releaseResources();
        }
    }

    private static boolean isNoMediaFile(String path) {
        File file = new File(path);
        if (file.isDirectory()) return false;

        // special case certain file names
        // I use regionMatches() instead of substring() below
        // to avoid memory allocation
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 2 < path.length()) {
            // ignore those ._* files created by MacOS
            if (path.regionMatches(lastSlash + 1, "._", 0, 2)) {
                return true;
            }

            // ignore album art files created by Windows Media Player:
            // Folder.jpg, AlbumArtSmall.jpg, AlbumArt_{...}_Large.jpg
            // and AlbumArt_{...}_Small.jpg
            if (path.regionMatches(true, path.length() - 4, ".jpg", 0, 4)) {
                if (path.regionMatches(true, lastSlash + 1, "AlbumArt_{", 0, 10) ||
                        path.regionMatches(true, lastSlash + 1, "AlbumArt.", 0, 9)) {
                    return true;
                }
                int length = path.length() - lastSlash - 1;
                if ((length == 17 && path.regionMatches(
                        true, lastSlash + 1, "AlbumArtSmall", 0, 13)) ||
                        (length == 10
                         && path.regionMatches(true, lastSlash + 1, "Folder", 0, 6))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static HashMap<String,String> mNoMediaPaths = new HashMap<String,String>();
    private static HashMap<String,String> mMediaPaths = new HashMap<String,String>();

    /* MediaProvider calls this when a .nomedia file is added or removed */
    public static void clearMediaPathCache(boolean clearMediaPaths, boolean clearNoMediaPaths) {
        synchronized (MediaScanner.class) {
            if (clearMediaPaths) {
                mMediaPaths.clear();
            }
            if (clearNoMediaPaths) {
                mNoMediaPaths.clear();
            }
        }
    }

    @UnsupportedAppUsage
    public static boolean isNoMediaPath(String path) {
        if (path == null) {
            return false;
        }
        // return true if file or any parent directory has name starting with a dot
        if (path.indexOf("/.") >= 0) {
            return true;
        }

        int firstSlash = path.lastIndexOf('/');
        if (firstSlash <= 0) {
            return false;
        }
        String parent = path.substring(0,  firstSlash);

        synchronized (MediaScanner.class) {
            if (mNoMediaPaths.containsKey(parent)) {
                return true;
            } else if (!mMediaPaths.containsKey(parent)) {
                // check to see if any parent directories have a ".nomedia" file
                // start from 1 so we don't bother checking in the root directory
                int offset = 1;
                while (offset >= 0) {
                    int slashIndex = path.indexOf('/', offset);
                    if (slashIndex > offset) {
                        slashIndex++; // move past slash
                        File file = new File(path.substring(0, slashIndex) + ".nomedia");
                        if (file.exists()) {
                            // we have a .nomedia in one of the parent directories
                            mNoMediaPaths.put(parent, "");
                            return true;
                        }
                    }
                    offset = slashIndex;
                }
                mMediaPaths.put(parent, "");
            }
        }

        return isNoMediaFile(path);
    }

    public void scanMtpFile(String path, int objectHandle, int format) {
        String mimeType = MediaFile.getMimeType(path, format);
        File file = new File(path);
        long lastModifiedSeconds = file.lastModified() / 1000;

        if (!MediaFile.isAudioMimeType(mimeType) && !MediaFile.isVideoMimeType(mimeType) &&
            !MediaFile.isImageMimeType(mimeType) && !MediaFile.isPlayListMimeType(mimeType) &&
            !MediaFile.isDrmMimeType(mimeType)) {

            // no need to use the media scanner, but we need to update last modified and file size
            ContentValues values = new ContentValues();
            values.put(Files.FileColumns.SIZE, file.length());
            values.put(Files.FileColumns.DATE_MODIFIED, lastModifiedSeconds);
            try {
                String[] whereArgs = new String[] {  Integer.toString(objectHandle) };
                mMediaProvider.update(Files.getMtpObjectsUri(mVolumeName), values,
                        "_id=?", whereArgs);
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in scanMtpFile", e);
            }
            return;
        }

        mMtpObjectHandle = objectHandle;
        Cursor fileList = null;
        try {
            if (MediaFile.isPlayListMimeType(mimeType)) {
                // build file cache so we can look up tracks in the playlist
                prescan(null, true);

                FileEntry entry = makeEntryFor(path);
                if (entry != null) {
                    fileList = mMediaProvider.query(mFilesUri,
                            FILES_PRESCAN_PROJECTION, null, null, null, null);
                    processPlayList(entry, fileList);
                }
            } else {
                // MTP will create a file entry for us so we don't want to do it in prescan
                prescan(path, false);

                // always scan the file, so we can return the content://media Uri for existing files
                mClient.doScanFile(path, mimeType, lastModifiedSeconds, file.length(),
                    (format == MtpConstants.FORMAT_ASSOCIATION), true, isNoMediaPath(path));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in MediaScanner.scanFile()", e);
        } finally {
            mMtpObjectHandle = 0;
            if (fileList != null) {
                fileList.close();
            }
            releaseResources();
        }
    }

    @UnsupportedAppUsage
    FileEntry makeEntryFor(String path) {
        String where;
        String[] selectionArgs;

        Cursor c = null;
        try {
            where = Files.FileColumns.DATA + "=?";
            selectionArgs = new String[] { path };
            c = mMediaProvider.query(mFilesFullUri, FILES_PRESCAN_PROJECTION,
                    where, selectionArgs, null, null);
            if (c != null && c.moveToFirst()) {
                long rowId = c.getLong(FILES_PRESCAN_ID_COLUMN_INDEX);
                long lastModified = c.getLong(FILES_PRESCAN_DATE_MODIFIED_COLUMN_INDEX);
                int format = c.getInt(FILES_PRESCAN_FORMAT_COLUMN_INDEX);
                int mediaType = c.getInt(FILES_PRESCAN_MEDIA_TYPE_COLUMN_INDEX);
                return new FileEntry(rowId, path, lastModified, format, mediaType);
            }
        } catch (RemoteException e) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    // returns the number of matching file/directory names, starting from the right
    private int matchPaths(String path1, String path2) {
        int result = 0;
        int end1 = path1.length();
        int end2 = path2.length();

        while (end1 > 0 && end2 > 0) {
            int slash1 = path1.lastIndexOf('/', end1 - 1);
            int slash2 = path2.lastIndexOf('/', end2 - 1);
            int backSlash1 = path1.lastIndexOf('\\', end1 - 1);
            int backSlash2 = path2.lastIndexOf('\\', end2 - 1);
            int start1 = (slash1 > backSlash1 ? slash1 : backSlash1);
            int start2 = (slash2 > backSlash2 ? slash2 : backSlash2);
            if (start1 < 0) start1 = 0; else start1++;
            if (start2 < 0) start2 = 0; else start2++;
            int length = end1 - start1;
            if (end2 - start2 != length) break;
            if (path1.regionMatches(true, start1, path2, start2, length)) {
                result++;
                end1 = start1 - 1;
                end2 = start2 - 1;
            } else break;
        }

        return result;
    }

    private boolean matchEntries(long rowId, String data) {

        int len = mPlaylistEntries.size();
        boolean done = true;
        for (int i = 0; i < len; i++) {
            PlaylistEntry entry = mPlaylistEntries.get(i);
            if (entry.bestmatchlevel == Integer.MAX_VALUE) {
                continue; // this entry has been matched already
            }
            done = false;
            if (data.equalsIgnoreCase(entry.path)) {
                entry.bestmatchid = rowId;
                entry.bestmatchlevel = Integer.MAX_VALUE;
                continue; // no need for path matching
            }

            int matchLength = matchPaths(data, entry.path);
            if (matchLength > entry.bestmatchlevel) {
                entry.bestmatchid = rowId;
                entry.bestmatchlevel = matchLength;
            }
        }
        return done;
    }

    private void cachePlaylistEntry(String line, String playListDirectory) {
        PlaylistEntry entry = new PlaylistEntry();
        // watch for trailing whitespace
        int entryLength = line.length();
        while (entryLength > 0 && Character.isWhitespace(line.charAt(entryLength - 1))) entryLength--;
        // path should be longer than 3 characters.
        // avoid index out of bounds errors below by returning here.
        if (entryLength < 3) return;
        if (entryLength < line.length()) line = line.substring(0, entryLength);

        // does entry appear to be an absolute path?
        // look for Unix or DOS absolute paths
        char ch1 = line.charAt(0);
        boolean fullPath = (ch1 == '/' ||
                (Character.isLetter(ch1) && line.charAt(1) == ':' && line.charAt(2) == '\\'));
        // if we have a relative path, combine entry with playListDirectory
        if (!fullPath)
            line = playListDirectory + line;
        entry.path = line;
        //FIXME - should we look for "../" within the path?

        mPlaylistEntries.add(entry);
    }

    private void processCachedPlaylist(Cursor fileList, ContentValues values, Uri playlistUri) {
        fileList.moveToPosition(-1);
        while (fileList.moveToNext()) {
            long rowId = fileList.getLong(FILES_PRESCAN_ID_COLUMN_INDEX);
            String data = fileList.getString(FILES_PRESCAN_PATH_COLUMN_INDEX);
            if (matchEntries(rowId, data)) {
                break;
            }
        }

        int len = mPlaylistEntries.size();
        int index = 0;
        for (int i = 0; i < len; i++) {
            PlaylistEntry entry = mPlaylistEntries.get(i);
            if (entry.bestmatchlevel > 0) {
                try {
                    values.clear();
                    values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, Integer.valueOf(index));
                    values.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, Long.valueOf(entry.bestmatchid));
                    mMediaProvider.insert(playlistUri, values);
                    index++;
                } catch (RemoteException e) {
                    Log.e(TAG, "RemoteException in MediaScanner.processCachedPlaylist()", e);
                    return;
                }
            }
        }
        mPlaylistEntries.clear();
    }

    private void processM3uPlayList(String path, String playListDirectory, Uri uri,
            ContentValues values, Cursor fileList) {
        BufferedReader reader = null;
        try {
            File f = new File(path);
            if (f.exists()) {
                reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f)), 8192);
                String line = reader.readLine();
                mPlaylistEntries.clear();
                while (line != null) {
                    // ignore comment lines, which begin with '#'
                    if (line.length() > 0 && line.charAt(0) != '#') {
                        cachePlaylistEntry(line, playListDirectory);
                    }
                    line = reader.readLine();
                }

                processCachedPlaylist(fileList, values, uri);
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException in MediaScanner.processM3uPlayList()", e);
        } finally {
            try {
                if (reader != null)
                    reader.close();
            } catch (IOException e) {
                Log.e(TAG, "IOException in MediaScanner.processM3uPlayList()", e);
            }
        }
    }

    private void processPlsPlayList(String path, String playListDirectory, Uri uri,
            ContentValues values, Cursor fileList) {
        BufferedReader reader = null;
        try {
            File f = new File(path);
            if (f.exists()) {
                reader = new BufferedReader(
                        new InputStreamReader(new FileInputStream(f)), 8192);
                String line = reader.readLine();
                mPlaylistEntries.clear();
                while (line != null) {
                    // ignore comment lines, which begin with '#'
                    if (line.startsWith("File")) {
                        int equals = line.indexOf('=');
                        if (equals > 0) {
                            cachePlaylistEntry(line.substring(equals + 1), playListDirectory);
                        }
                    }
                    line = reader.readLine();
                }

                processCachedPlaylist(fileList, values, uri);
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException in MediaScanner.processPlsPlayList()", e);
        } finally {
            try {
                if (reader != null)
                    reader.close();
            } catch (IOException e) {
                Log.e(TAG, "IOException in MediaScanner.processPlsPlayList()", e);
            }
        }
    }

    class WplHandler implements ElementListener {

        final ContentHandler handler;
        String playListDirectory;

        public WplHandler(String playListDirectory, Uri uri, Cursor fileList) {
            this.playListDirectory = playListDirectory;

            RootElement root = new RootElement("smil");
            Element body = root.getChild("body");
            Element seq = body.getChild("seq");
            Element media = seq.getChild("media");
            media.setElementListener(this);

            this.handler = root.getContentHandler();
        }

        @Override
        public void start(Attributes attributes) {
            String path = attributes.getValue("", "src");
            if (path != null) {
                cachePlaylistEntry(path, playListDirectory);
            }
        }

       @Override
       public void end() {
       }

        ContentHandler getContentHandler() {
            return handler;
        }
    }

    private void processWplPlayList(String path, String playListDirectory, Uri uri,
            ContentValues values, Cursor fileList) {
        FileInputStream fis = null;
        try {
            File f = new File(path);
            if (f.exists()) {
                fis = new FileInputStream(f);

                mPlaylistEntries.clear();
                Xml.parse(fis, Xml.findEncodingByName("UTF-8"),
                        new WplHandler(playListDirectory, uri, fileList).getContentHandler());

                processCachedPlaylist(fileList, values, uri);
            }
        } catch (SAXException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fis != null)
                    fis.close();
            } catch (IOException e) {
                Log.e(TAG, "IOException in MediaScanner.processWplPlayList()", e);
            }
        }
    }

    private void processPlayList(FileEntry entry, Cursor fileList) throws RemoteException {
        String path = entry.mPath;
        ContentValues values = new ContentValues();
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) throw new IllegalArgumentException("bad path " + path);
        Uri uri, membersUri;
        long rowId = entry.mRowId;

        // make sure we have a name
        String name = values.getAsString(MediaStore.Audio.Playlists.NAME);
        if (name == null) {
            name = values.getAsString(MediaStore.MediaColumns.TITLE);
            if (name == null) {
                // extract name from file name
                int lastDot = path.lastIndexOf('.');
                name = (lastDot < 0 ? path.substring(lastSlash + 1)
                        : path.substring(lastSlash + 1, lastDot));
            }
        }

        values.put(MediaStore.Audio.Playlists.NAME, name);
        values.put(MediaStore.Audio.Playlists.DATE_MODIFIED, entry.mLastModified);

        if (rowId == 0) {
            values.put(MediaStore.Audio.Playlists.DATA, path);
            uri = mMediaProvider.insert(mPlaylistsUri, values);
            rowId = ContentUris.parseId(uri);
            membersUri = Uri.withAppendedPath(uri, Playlists.Members.CONTENT_DIRECTORY);
        } else {
            uri = ContentUris.withAppendedId(mPlaylistsUri, rowId);
            mMediaProvider.update(uri, values, null, null);

            // delete members of existing playlist
            membersUri = Uri.withAppendedPath(uri, Playlists.Members.CONTENT_DIRECTORY);
            mMediaProvider.delete(membersUri, null, null);
        }

        String playListDirectory = path.substring(0, lastSlash + 1);
        String mimeType = MediaFile.getMimeTypeForFile(path);
        switch (mimeType) {
            case "application/vnd.ms-wpl":
                processWplPlayList(path, playListDirectory, membersUri, values, fileList);
                break;
            case "audio/x-mpegurl":
                processM3uPlayList(path, playListDirectory, membersUri, values, fileList);
                break;
            case "audio/x-scpls":
                processPlsPlayList(path, playListDirectory, membersUri, values, fileList);
                break;
        }
    }

    private void processPlayLists() throws RemoteException {
        Iterator<FileEntry> iterator = mPlayLists.iterator();
        Cursor fileList = null;
        try {
            // use the files uri and projection because we need the format column,
            // but restrict the query to just audio files
            fileList = mMediaProvider.query(mFilesUri, FILES_PRESCAN_PROJECTION,
                    "media_type=2", null, null, null);
            while (iterator.hasNext()) {
                FileEntry entry = iterator.next();
                // only process playlist files if they are new or have been modified since the last scan
                if (entry.mLastModifiedChanged) {
                    processPlayList(entry, fileList);
                }
            }
        } catch (RemoteException e1) {
        } finally {
            if (fileList != null) {
                fileList.close();
            }
        }
    }

    private native void processDirectory(String path, MediaScannerClient client);
    private native boolean processFile(String path, String mimeType, MediaScannerClient client);
    @UnsupportedAppUsage
    private native void setLocale(String locale);

    public native byte[] extractAlbumArt(FileDescriptor fd);

    private static native final void native_init();
    private native final void native_setup();
    private native final void native_finalize();

    @Override
    public void close() {
        mCloseGuard.close();
        if (mClosed.compareAndSet(false, true)) {
            mMediaProvider.close();
            native_finalize();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }

            close();
        } finally {
            super.finalize();
        }
    }
}
