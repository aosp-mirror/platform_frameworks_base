/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.app.backup;

import android.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.system.ErrnoException;
import android.system.Os;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Global constant definitions et cetera related to the full-backup-to-fd
 * binary format.  Nothing in this namespace is part of any API; it's all
 * hidden details of the current implementation gathered into one location.
 *
 * @hide
 */
public class FullBackup {
    static final String TAG = "FullBackup";
    /** Enable this log tag to get verbose information while parsing the client xml. */
    static final String TAG_XML_PARSER = "BackupXmlParserLogging";

    public static final String APK_TREE_TOKEN = "a";
    public static final String OBB_TREE_TOKEN = "obb";
    public static final String KEY_VALUE_DATA_TOKEN = "k";

    public static final String ROOT_TREE_TOKEN = "r";
    public static final String FILES_TREE_TOKEN = "f";
    public static final String NO_BACKUP_TREE_TOKEN = "nb";
    public static final String DATABASE_TREE_TOKEN = "db";
    public static final String SHAREDPREFS_TREE_TOKEN = "sp";
    public static final String CACHE_TREE_TOKEN = "c";

    public static final String DEVICE_ROOT_TREE_TOKEN = "d_r";
    public static final String DEVICE_FILES_TREE_TOKEN = "d_f";
    public static final String DEVICE_NO_BACKUP_TREE_TOKEN = "d_nb";
    public static final String DEVICE_DATABASE_TREE_TOKEN = "d_db";
    public static final String DEVICE_SHAREDPREFS_TREE_TOKEN = "d_sp";
    public static final String DEVICE_CACHE_TREE_TOKEN = "d_c";

    public static final String MANAGED_EXTERNAL_TREE_TOKEN = "ef";
    public static final String SHARED_STORAGE_TOKEN = "shared";

    public static final String APPS_PREFIX = "apps/";
    public static final String SHARED_PREFIX = SHARED_STORAGE_TOKEN + "/";

    public static final String FULL_BACKUP_INTENT_ACTION = "fullback";
    public static final String FULL_RESTORE_INTENT_ACTION = "fullrest";
    public static final String CONF_TOKEN_INTENT_EXTRA = "conftoken";

    public static final String FLAG_REQUIRED_CLIENT_SIDE_ENCRYPTION = "clientSideEncryption";
    public static final String FLAG_REQUIRED_DEVICE_TO_DEVICE_TRANSFER = "deviceToDeviceTransfer";
    public static final String FLAG_REQUIRED_FAKE_CLIENT_SIDE_ENCRYPTION =
            "fakeClientSideEncryption";

    /**
     * @hide
     */
    @UnsupportedAppUsage
    static public native int backupToTar(String packageName, String domain,
            String linkdomain, String rootpath, String path, FullBackupDataOutput output);

    private static final Map<String, BackupScheme> kPackageBackupSchemeMap =
            new ArrayMap<String, BackupScheme>();

    static synchronized BackupScheme getBackupScheme(Context context) {
        BackupScheme backupSchemeForPackage =
                kPackageBackupSchemeMap.get(context.getPackageName());
        if (backupSchemeForPackage == null) {
            backupSchemeForPackage = new BackupScheme(context);
            kPackageBackupSchemeMap.put(context.getPackageName(), backupSchemeForPackage);
        }
        return backupSchemeForPackage;
    }

    public static BackupScheme getBackupSchemeForTest(Context context) {
        BackupScheme testing = new BackupScheme(context);
        testing.mExcludes = new ArraySet();
        testing.mIncludes = new ArrayMap();
        return testing;
    }


    /**
     * Copy data from a socket to the given File location on permanent storage.  The
     * modification time and access mode of the resulting file will be set if desired,
     * although group/all rwx modes will be stripped: the restored file will not be
     * accessible from outside the target application even if the original file was.
     * If the {@code type} parameter indicates that the result should be a directory,
     * the socket parameter may be {@code null}; even if it is valid, no data will be
     * read from it in this case.
     * <p>
     * If the {@code mode} argument is negative, then the resulting output file will not
     * have its access mode or last modification time reset as part of this operation.
     *
     * @param data Socket supplying the data to be copied to the output file.  If the
     *    output is a directory, this may be {@code null}.
     * @param size Number of bytes of data to copy from the socket to the file.  At least
     *    this much data must be available through the {@code data} parameter.
     * @param type Must be either {@link BackupAgent#TYPE_FILE} for ordinary file data
     *    or {@link BackupAgent#TYPE_DIRECTORY} for a directory.
     * @param mode Unix-style file mode (as used by the chmod(2) syscall) to be set on
     *    the output file or directory.  group/all rwx modes are stripped even if set
     *    in this parameter.  If this parameter is negative then neither
     *    the mode nor the mtime values will be applied to the restored file.
     * @param mtime A timestamp in the standard Unix epoch that will be imposed as the
     *    last modification time of the output file.  if the {@code mode} parameter is
     *    negative then this parameter will be ignored.
     * @param outFile Location within the filesystem to place the data.  This must point
     *    to a location that is writeable by the caller, preferably using an absolute path.
     * @throws IOException
     */
    static public void restoreFile(ParcelFileDescriptor data,
            long size, int type, long mode, long mtime, File outFile) throws IOException {
        if (type == BackupAgent.TYPE_DIRECTORY) {
            // Canonically a directory has no associated content, so we don't need to read
            // anything from the pipe in this case.  Just create the directory here and
            // drop down to the final metadata adjustment.
            if (outFile != null) outFile.mkdirs();
        } else {
            FileOutputStream out = null;

            // Pull the data from the pipe, copying it to the output file, until we're done
            try {
                if (outFile != null) {
                    File parent = outFile.getParentFile();
                    if (!parent.exists()) {
                        // in practice this will only be for the default semantic directories,
                        // and using the default mode for those is appropriate.
                        // This can also happen for the case where a parent directory has been
                        // excluded, but a file within that directory has been included.
                        parent.mkdirs();
                    }
                    out = new FileOutputStream(outFile);
                }
            } catch (IOException e) {
                Log.e(TAG, "Unable to create/open file " + outFile.getPath(), e);
            }

            byte[] buffer = new byte[32 * 1024];
            final long origSize = size;
            FileInputStream in = new FileInputStream(data.getFileDescriptor());
            while (size > 0) {
                int toRead = (size > buffer.length) ? buffer.length : (int)size;
                int got = in.read(buffer, 0, toRead);
                if (got <= 0) {
                    Log.w(TAG, "Incomplete read: expected " + size + " but got "
                            + (origSize - size));
                    break;
                }
                if (out != null) {
                    try {
                        out.write(buffer, 0, got);
                    } catch (IOException e) {
                        // Problem writing to the file.  Quit copying data and delete
                        // the file, but of course keep consuming the input stream.
                        Log.e(TAG, "Unable to write to file " + outFile.getPath(), e);
                        out.close();
                        out = null;
                        outFile.delete();
                    }
                }
                size -= got;
            }
            if (out != null) out.close();
        }

        // Now twiddle the state to match the backup, assuming all went well
        if (mode >= 0 && outFile != null) {
            try {
                // explicitly prevent emplacement of files accessible by outside apps
                mode &= 0700;
                Os.chmod(outFile.getPath(), (int)mode);
            } catch (ErrnoException e) {
                e.rethrowAsIOException();
            }
            outFile.setLastModified(mtime);
        }
    }

    @VisibleForTesting
    public static class BackupScheme {
        private final File FILES_DIR;
        private final File DATABASE_DIR;
        private final File ROOT_DIR;
        private final File SHAREDPREF_DIR;
        private final File CACHE_DIR;
        private final File NOBACKUP_DIR;

        private final File DEVICE_FILES_DIR;
        private final File DEVICE_DATABASE_DIR;
        private final File DEVICE_ROOT_DIR;
        private final File DEVICE_SHAREDPREF_DIR;
        private final File DEVICE_CACHE_DIR;
        private final File DEVICE_NOBACKUP_DIR;

        private final File EXTERNAL_DIR;

        private final static String TAG_INCLUDE = "include";
        private final static String TAG_EXCLUDE = "exclude";

        final int mFullBackupContent;
        final PackageManager mPackageManager;
        final StorageManager mStorageManager;
        final String mPackageName;

        // lazy initialized, only when needed
        private StorageVolume[] mVolumes = null;

        /**
         * Parse out the semantic domains into the correct physical location.
         */
        String tokenToDirectoryPath(String domainToken) {
            try {
                if (domainToken.equals(FullBackup.FILES_TREE_TOKEN)) {
                    return FILES_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.DATABASE_TREE_TOKEN)) {
                    return DATABASE_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.ROOT_TREE_TOKEN)) {
                    return ROOT_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.SHAREDPREFS_TREE_TOKEN)) {
                    return SHAREDPREF_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.CACHE_TREE_TOKEN)) {
                    return CACHE_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.NO_BACKUP_TREE_TOKEN)) {
                    return NOBACKUP_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.DEVICE_FILES_TREE_TOKEN)) {
                    return DEVICE_FILES_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.DEVICE_DATABASE_TREE_TOKEN)) {
                    return DEVICE_DATABASE_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.DEVICE_ROOT_TREE_TOKEN)) {
                    return DEVICE_ROOT_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.DEVICE_SHAREDPREFS_TREE_TOKEN)) {
                    return DEVICE_SHAREDPREF_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.DEVICE_CACHE_TREE_TOKEN)) {
                    return DEVICE_CACHE_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.DEVICE_NO_BACKUP_TREE_TOKEN)) {
                    return DEVICE_NOBACKUP_DIR.getCanonicalPath();
                } else if (domainToken.equals(FullBackup.MANAGED_EXTERNAL_TREE_TOKEN)) {
                    if (EXTERNAL_DIR != null) {
                        return EXTERNAL_DIR.getCanonicalPath();
                    } else {
                        return null;
                    }
                } else if (domainToken.startsWith(FullBackup.SHARED_PREFIX)) {
                    return sharedDomainToPath(domainToken);
                }
                // Not a supported location
                Log.i(TAG, "Unrecognized domain " + domainToken);
                return null;
            } catch (Exception e) {
                Log.i(TAG, "Error reading directory for domain: " + domainToken);
                return null;
            }

        }

        private String sharedDomainToPath(String domain) throws IOException {
            // already known to start with SHARED_PREFIX, so we just look after that
            final String volume = domain.substring(FullBackup.SHARED_PREFIX.length());
            final StorageVolume[] volumes = getVolumeList();
            final int volNum = Integer.parseInt(volume);
            if (volNum < mVolumes.length) {
                return volumes[volNum].getPathFile().getCanonicalPath();
            }
            return null;
        }

        private StorageVolume[] getVolumeList() {
            if (mStorageManager != null) {
                if (mVolumes == null) {
                    mVolumes = mStorageManager.getVolumeList();
                }
            } else {
                Log.e(TAG, "Unable to access Storage Manager");
            }
            return mVolumes;
        }

        /**
         * Represents a path attribute specified in an <include /> rule along with optional
         * transport flags required from the transport to include file(s) under that path as
         * specified by requiredFlags attribute. If optional requiredFlags attribute is not
         * provided, default requiredFlags to 0.
         * Note: since our parsing codepaths were the same for <include /> and <exclude /> tags,
         * this structure is also used for <exclude /> tags to preserve that, however you can expect
         * the getRequiredFlags() to always return 0 for exclude rules.
         */
        public static class PathWithRequiredFlags {
            private final String mPath;
            private final int mRequiredFlags;

            public PathWithRequiredFlags(String path, int requiredFlags) {
                mPath = path;
                mRequiredFlags = requiredFlags;
            }

            public String getPath() {
                return mPath;
            }

            public int getRequiredFlags() {
                return mRequiredFlags;
            }
        }

        /**
         * A map of domain -> set of pairs (canonical file; required transport flags) in that
         * domain that are to be included if the transport has decared the required flags.
         * We keep track of the domain so that we can go through the file system in order later on.
         */
        Map<String, Set<PathWithRequiredFlags>> mIncludes;

        /**
         * Set that will be populated with pairs (canonical file; requiredFlags=0) for each file or
         * directory that is to be excluded. Note that for excludes, the requiredFlags attribute is
         * ignored and the value should be always set to 0.
         */
        ArraySet<PathWithRequiredFlags> mExcludes;

        BackupScheme(Context context) {
            mFullBackupContent = context.getApplicationInfo().fullBackupContent;
            mStorageManager = (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            mPackageManager = context.getPackageManager();
            mPackageName = context.getPackageName();

            // System apps have control over where their default storage context
            // is pointed, so we're always explicit when building paths.
            final Context ceContext = context.createCredentialProtectedStorageContext();
            FILES_DIR = ceContext.getFilesDir();
            DATABASE_DIR = ceContext.getDatabasePath("foo").getParentFile();
            ROOT_DIR = ceContext.getDataDir();
            SHAREDPREF_DIR = ceContext.getSharedPreferencesPath("foo").getParentFile();
            CACHE_DIR = ceContext.getCacheDir();
            NOBACKUP_DIR = ceContext.getNoBackupFilesDir();

            final Context deContext = context.createDeviceProtectedStorageContext();
            DEVICE_FILES_DIR = deContext.getFilesDir();
            DEVICE_DATABASE_DIR = deContext.getDatabasePath("foo").getParentFile();
            DEVICE_ROOT_DIR = deContext.getDataDir();
            DEVICE_SHAREDPREF_DIR = deContext.getSharedPreferencesPath("foo").getParentFile();
            DEVICE_CACHE_DIR = deContext.getCacheDir();
            DEVICE_NOBACKUP_DIR = deContext.getNoBackupFilesDir();

            if (android.os.Process.myUid() != Process.SYSTEM_UID) {
                EXTERNAL_DIR = context.getExternalFilesDir(null);
            } else {
                EXTERNAL_DIR = null;
            }
        }

        boolean isFullBackupContentEnabled() {
            if (mFullBackupContent < 0) {
                // android:fullBackupContent="false", bail.
                if (Log.isLoggable(FullBackup.TAG_XML_PARSER, Log.VERBOSE)) {
                    Log.v(FullBackup.TAG_XML_PARSER, "android:fullBackupContent - \"false\"");
                }
                return false;
            }
            return true;
        }

        /**
         * @return A mapping of domain -> set of pairs (canonical file; required transport flags)
         * in that domain that are to be included if the transport has decared the required flags.
         * Each of these paths specifies a file that the client has explicitly included in their
         * backup set. If this map is empty we will back up the entire data directory (including
         * managed external storage).
         */
        public synchronized Map<String, Set<PathWithRequiredFlags>>
                maybeParseAndGetCanonicalIncludePaths() throws IOException, XmlPullParserException {
            if (mIncludes == null) {
                maybeParseBackupSchemeLocked();
            }
            return mIncludes;
        }

        /**
         * @return A set of (canonical paths; requiredFlags=0) that are to be excluded from the
         * backup/restore set.
         */
        public synchronized ArraySet<PathWithRequiredFlags> maybeParseAndGetCanonicalExcludePaths()
                throws IOException, XmlPullParserException {
            if (mExcludes == null) {
                maybeParseBackupSchemeLocked();
            }
            return mExcludes;
        }

        private void maybeParseBackupSchemeLocked() throws IOException, XmlPullParserException {
            // This not being null is how we know that we've tried to parse the xml already.
            mIncludes = new ArrayMap<String, Set<PathWithRequiredFlags>>();
            mExcludes = new ArraySet<PathWithRequiredFlags>();

            if (mFullBackupContent == 0) {
                // android:fullBackupContent="true" which means that we'll do everything.
                if (Log.isLoggable(FullBackup.TAG_XML_PARSER, Log.VERBOSE)) {
                    Log.v(FullBackup.TAG_XML_PARSER, "android:fullBackupContent - \"true\"");
                }
            } else {
                // android:fullBackupContent="@xml/some_resource".
                if (Log.isLoggable(FullBackup.TAG_XML_PARSER, Log.VERBOSE)) {
                    Log.v(FullBackup.TAG_XML_PARSER,
                            "android:fullBackupContent - found xml resource");
                }
                XmlResourceParser parser = null;
                try {
                    parser = mPackageManager
                            .getResourcesForApplication(mPackageName)
                            .getXml(mFullBackupContent);
                    parseBackupSchemeFromXmlLocked(parser, mExcludes, mIncludes);
                } catch (PackageManager.NameNotFoundException e) {
                    // Throw it as an IOException
                    throw new IOException(e);
                } finally {
                    if (parser != null) {
                        parser.close();
                    }
                }
            }
        }

        @VisibleForTesting
        public void parseBackupSchemeFromXmlLocked(XmlPullParser parser,
                                                   Set<PathWithRequiredFlags> excludes,
                                                   Map<String, Set<PathWithRequiredFlags>> includes)
                throws IOException, XmlPullParserException {
            int event = parser.getEventType(); // START_DOCUMENT
            while (event != XmlPullParser.START_TAG) {
                event = parser.next();
            }

            if (!"full-backup-content".equals(parser.getName())) {
                throw new XmlPullParserException("Xml file didn't start with correct tag" +
                        " (<full-backup-content>). Found \"" + parser.getName() + "\"");
            }

            if (Log.isLoggable(TAG_XML_PARSER, Log.VERBOSE)) {
                Log.v(TAG_XML_PARSER, "\n");
                Log.v(TAG_XML_PARSER, "====================================================");
                Log.v(TAG_XML_PARSER, "Found valid fullBackupContent; parsing xml resource.");
                Log.v(TAG_XML_PARSER, "====================================================");
                Log.v(TAG_XML_PARSER, "");
            }

            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG:
                        validateInnerTagContents(parser);
                        final String domainFromXml = parser.getAttributeValue(null, "domain");
                        final File domainDirectory = getDirectoryForCriteriaDomain(domainFromXml);
                        if (domainDirectory == null) {
                            if (Log.isLoggable(TAG_XML_PARSER, Log.VERBOSE)) {
                                Log.v(TAG_XML_PARSER, "...parsing \"" + parser.getName() + "\": "
                                        + "domain=\"" + domainFromXml + "\" invalid; skipping");
                            }
                            break;
                        }
                        final File canonicalFile =
                                extractCanonicalFile(domainDirectory,
                                        parser.getAttributeValue(null, "path"));
                        if (canonicalFile == null) {
                            break;
                        }

                        int requiredFlags = 0; // no transport flags are required by default
                        if (TAG_INCLUDE.equals(parser.getName())) {
                            // requiredFlags are only supported for <include /> tag, for <exclude />
                            // we should always leave them as the default = 0
                            requiredFlags = getRequiredFlagsFromString(
                                    parser.getAttributeValue(null, "requireFlags"));
                        }

                        // retrieve the include/exclude set we'll be adding this rule to
                        Set<PathWithRequiredFlags> activeSet = parseCurrentTagForDomain(
                                parser, excludes, includes, domainFromXml);
                        activeSet.add(new PathWithRequiredFlags(canonicalFile.getCanonicalPath(),
                                requiredFlags));
                        if (Log.isLoggable(TAG_XML_PARSER, Log.VERBOSE)) {
                            Log.v(TAG_XML_PARSER, "...parsed " + canonicalFile.getCanonicalPath()
                                    + " for domain \"" + domainFromXml + "\", requiredFlags + \""
                                    + requiredFlags + "\"");
                        }

                        // Special case journal files (not dirs) for sqlite database. frowny-face.
                        // Note that for a restore, the file is never a directory (b/c it doesn't
                        // exist). We have no way of knowing a priori whether or not to expect a
                        // dir, so we add the -journal anyway to be safe.
                        if ("database".equals(domainFromXml) && !canonicalFile.isDirectory()) {
                            final String canonicalJournalPath =
                                    canonicalFile.getCanonicalPath() + "-journal";
                            activeSet.add(new PathWithRequiredFlags(canonicalJournalPath,
                                    requiredFlags));
                            if (Log.isLoggable(TAG_XML_PARSER, Log.VERBOSE)) {
                                Log.v(TAG_XML_PARSER, "...automatically generated "
                                        + canonicalJournalPath + ". Ignore if nonexistent.");
                            }
                            final String canonicalWalPath =
                                    canonicalFile.getCanonicalPath() + "-wal";
                            activeSet.add(new PathWithRequiredFlags(canonicalWalPath,
                                    requiredFlags));
                            if (Log.isLoggable(TAG_XML_PARSER, Log.VERBOSE)) {
                                Log.v(TAG_XML_PARSER, "...automatically generated "
                                        + canonicalWalPath + ". Ignore if nonexistent.");
                            }
                        }

                        // Special case for sharedpref files (not dirs) also add ".xml" suffix file.
                        if ("sharedpref".equals(domainFromXml) && !canonicalFile.isDirectory() &&
                            !canonicalFile.getCanonicalPath().endsWith(".xml")) {
                            final String canonicalXmlPath =
                                    canonicalFile.getCanonicalPath() + ".xml";
                            activeSet.add(new PathWithRequiredFlags(canonicalXmlPath,
                                    requiredFlags));
                            if (Log.isLoggable(TAG_XML_PARSER, Log.VERBOSE)) {
                                Log.v(TAG_XML_PARSER, "...automatically generated "
                                        + canonicalXmlPath + ". Ignore if nonexistent.");
                            }
                        }
                }
            }
            if (Log.isLoggable(TAG_XML_PARSER, Log.VERBOSE)) {
                Log.v(TAG_XML_PARSER, "\n");
                Log.v(TAG_XML_PARSER, "Xml resource parsing complete.");
                Log.v(TAG_XML_PARSER, "Final tally.");
                Log.v(TAG_XML_PARSER, "Includes:");
                if (includes.isEmpty()) {
                    Log.v(TAG_XML_PARSER, "  ...nothing specified (This means the entirety of app"
                            + " data minus excludes)");
                } else {
                    for (Map.Entry<String, Set<PathWithRequiredFlags>> entry
                            : includes.entrySet()) {
                        Log.v(TAG_XML_PARSER, "  domain=" + entry.getKey());
                        for (PathWithRequiredFlags includeData : entry.getValue()) {
                            Log.v(TAG_XML_PARSER, " path: " + includeData.getPath()
                                    + " requiredFlags: " + includeData.getRequiredFlags());
                        }
                    }
                }

                Log.v(TAG_XML_PARSER, "Excludes:");
                if (excludes.isEmpty()) {
                    Log.v(TAG_XML_PARSER, "  ...nothing to exclude.");
                } else {
                    for (PathWithRequiredFlags excludeData : excludes) {
                        Log.v(TAG_XML_PARSER, " path: " + excludeData.getPath()
                                + " requiredFlags: " + excludeData.getRequiredFlags());
                    }
                }

                Log.v(TAG_XML_PARSER, "  ");
                Log.v(TAG_XML_PARSER, "====================================================");
                Log.v(TAG_XML_PARSER, "\n");
            }
        }

        private int getRequiredFlagsFromString(String requiredFlags) {
            int flags = 0;
            if (requiredFlags == null || requiredFlags.length() == 0) {
                // requiredFlags attribute was missing or empty in <include /> tag
                return flags;
            }
            String[] flagsStr = requiredFlags.split("\\|");
            for (String f : flagsStr) {
                switch (f) {
                    case FLAG_REQUIRED_CLIENT_SIDE_ENCRYPTION:
                        flags |= BackupAgent.FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED;
                        break;
                    case FLAG_REQUIRED_DEVICE_TO_DEVICE_TRANSFER:
                        flags |= BackupAgent.FLAG_DEVICE_TO_DEVICE_TRANSFER;
                        break;
                    case FLAG_REQUIRED_FAKE_CLIENT_SIDE_ENCRYPTION:
                        flags |= BackupAgent.FLAG_FAKE_CLIENT_SIDE_ENCRYPTION_ENABLED;
                    default:
                        Log.w(TAG, "Unrecognized requiredFlag provided, value: \"" + f + "\"");
                }
            }
            return flags;
        }

        private Set<PathWithRequiredFlags> parseCurrentTagForDomain(XmlPullParser parser,
                Set<PathWithRequiredFlags> excludes,
                Map<String, Set<PathWithRequiredFlags>> includes, String domain)
                throws XmlPullParserException {
            if (TAG_INCLUDE.equals(parser.getName())) {
                final String domainToken = getTokenForXmlDomain(domain);
                Set<PathWithRequiredFlags> includeSet = includes.get(domainToken);
                if (includeSet == null) {
                    includeSet = new ArraySet<PathWithRequiredFlags>();
                    includes.put(domainToken, includeSet);
                }
                return includeSet;
            } else if (TAG_EXCLUDE.equals(parser.getName())) {
                return excludes;
            } else {
                // Unrecognised tag => hard failure.
                if (Log.isLoggable(TAG_XML_PARSER, Log.VERBOSE)) {
                    Log.v(TAG_XML_PARSER, "Invalid tag found in xml \""
                            + parser.getName() + "\"; aborting operation.");
                }
                throw new XmlPullParserException("Unrecognised tag in backup" +
                        " criteria xml (" + parser.getName() + ")");
            }
        }

        /**
         * Map xml specified domain (human-readable, what clients put in their manifest's xml) to
         * BackupAgent internal data token.
         * @return null if the xml domain was invalid.
         */
        private String getTokenForXmlDomain(String xmlDomain) {
            if ("root".equals(xmlDomain)) {
                return FullBackup.ROOT_TREE_TOKEN;
            } else if ("file".equals(xmlDomain)) {
                return FullBackup.FILES_TREE_TOKEN;
            } else if ("database".equals(xmlDomain)) {
                return FullBackup.DATABASE_TREE_TOKEN;
            } else if ("sharedpref".equals(xmlDomain)) {
                return FullBackup.SHAREDPREFS_TREE_TOKEN;
            } else if ("device_root".equals(xmlDomain)) {
                return FullBackup.DEVICE_ROOT_TREE_TOKEN;
            } else if ("device_file".equals(xmlDomain)) {
                return FullBackup.DEVICE_FILES_TREE_TOKEN;
            } else if ("device_database".equals(xmlDomain)) {
                return FullBackup.DEVICE_DATABASE_TREE_TOKEN;
            } else if ("device_sharedpref".equals(xmlDomain)) {
                return FullBackup.DEVICE_SHAREDPREFS_TREE_TOKEN;
            } else if ("external".equals(xmlDomain)) {
                return FullBackup.MANAGED_EXTERNAL_TREE_TOKEN;
            } else {
                return null;
            }
        }

        /**
         *
         * @param domain Directory where the specified file should exist. Not null.
         * @param filePathFromXml parsed from xml. Not sanitised before calling this function so may
         *                        be null.
         * @return The canonical path of the file specified or null if no such file exists.
         */
        private File extractCanonicalFile(File domain, String filePathFromXml) {
            if (filePathFromXml == null) {
                // Allow things like <include domain="sharedpref"/>
                filePathFromXml = "";
            }
            if (filePathFromXml.contains("..")) {
                if (Log.isLoggable(TAG_XML_PARSER, Log.VERBOSE)) {
                    Log.v(TAG_XML_PARSER, "...resolved \"" + domain.getPath() + " " + filePathFromXml
                            + "\", but the \"..\" path is not permitted; skipping.");
                }
                return null;
            }
            if (filePathFromXml.contains("//")) {
                if (Log.isLoggable(TAG_XML_PARSER, Log.VERBOSE)) {
                    Log.v(TAG_XML_PARSER, "...resolved \"" + domain.getPath() + " " + filePathFromXml
                            + "\", which contains the invalid \"//\" sequence; skipping.");
                }
                return null;
            }
            return new File(domain, filePathFromXml);
        }

        /**
         * @param domain parsed from xml. Not sanitised before calling this function so may be null.
         * @return The directory relevant to the domain specified.
         */
        private File getDirectoryForCriteriaDomain(String domain) {
            if (TextUtils.isEmpty(domain)) {
                return null;
            }
            if ("file".equals(domain)) {
                return FILES_DIR;
            } else if ("database".equals(domain)) {
                return DATABASE_DIR;
            } else if ("root".equals(domain)) {
                return ROOT_DIR;
            } else if ("sharedpref".equals(domain)) {
                return SHAREDPREF_DIR;
            } else if ("device_file".equals(domain)) {
                return DEVICE_FILES_DIR;
            } else if ("device_database".equals(domain)) {
                return DEVICE_DATABASE_DIR;
            } else if ("device_root".equals(domain)) {
                return DEVICE_ROOT_DIR;
            } else if ("device_sharedpref".equals(domain)) {
                return DEVICE_SHAREDPREF_DIR;
            } else if ("external".equals(domain)) {
                return EXTERNAL_DIR;
            } else {
                return null;
            }
        }

        /**
         * Let's be strict about the type of xml the client can write. If we see anything untoward,
         * throw an XmlPullParserException.
         */
        private void validateInnerTagContents(XmlPullParser parser) throws XmlPullParserException {
            if (parser == null) {
                return;
            }
            switch (parser.getName()) {
                case TAG_INCLUDE:
                    if (parser.getAttributeCount() > 3) {
                        throw new XmlPullParserException("At most 3 tag attributes allowed for "
                                + "\"include\" tag (\"domain\" & \"path\""
                                + " & optional \"requiredFlags\").");
                    }
                    break;
                case TAG_EXCLUDE:
                    if (parser.getAttributeCount() > 2) {
                        throw new XmlPullParserException("At most 2 tag attributes allowed for "
                                + "\"exclude\" tag (\"domain\" & \"path\".");
                    }
                    break;
                default:
                    throw new XmlPullParserException("A valid tag is one of \"<include/>\" or" +
                            " \"<exclude/>. You provided \"" + parser.getName() + "\"");
            }
        }
    }
}
