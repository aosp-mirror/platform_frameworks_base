/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.timezone;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.io.PrintWriter;

import static com.android.server.timezone.PackageStatus.CHECK_COMPLETED_FAILURE;
import static com.android.server.timezone.PackageStatus.CHECK_COMPLETED_SUCCESS;
import static com.android.server.timezone.PackageStatus.CHECK_STARTED;
import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

/**
 * Storage logic for accessing/mutating the Android system's persistent state related to time zone
 * update checking. There is expected to be a single instance. All non-private methods are thread
 * safe.
 */
final class PackageStatusStorage {

    private static final String LOG_TAG = "timezone.PackageStatusStorage";

    private static final String TAG_PACKAGE_STATUS = "PackageStatus";

    /**
     * Attribute that stores a monotonically increasing lock ID, used to detect concurrent update
     * issues without on-line locks. Incremented on every write.
     */
    private static final String ATTRIBUTE_OPTIMISTIC_LOCK_ID = "optimisticLockId";

    /**
     * Attribute that stores the current "check status" of the time zone update application
     * packages.
     */
    private static final String ATTRIBUTE_CHECK_STATUS = "checkStatus";

    /**
     * Attribute that stores the version of the time zone rules update application being checked
     * / last checked.
     */
    private static final String ATTRIBUTE_UPDATE_APP_VERSION = "updateAppPackageVersion";

    /**
     * Attribute that stores the version of the time zone rules data application being checked
     * / last checked.
     */
    private static final String ATTRIBUTE_DATA_APP_VERSION = "dataAppPackageVersion";

    private static final int UNKNOWN_PACKAGE_VERSION = -1;

    private final AtomicFile mPackageStatusFile;

    PackageStatusStorage(File storageDir) {
        mPackageStatusFile = new AtomicFile(new File(storageDir, "package-status.xml"));
        if (!mPackageStatusFile.getBaseFile().exists()) {
            try {
                insertInitialPackageStatus();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    void deleteFileForTests() {
        synchronized(this) {
            mPackageStatusFile.delete();
        }
    }

    /**
     * Obtain the current check status of the application packages. Returns {@code null} the first
     * time it is called, or after {@link #resetCheckState()}.
     */
    PackageStatus getPackageStatus() {
        synchronized (this) {
            try {
                return getPackageStatusLocked();
            } catch (ParseException e) {
                // This means that data exists in the file but it was bad.
                Slog.e(LOG_TAG, "Package status invalid, resetting and retrying", e);

                // Reset the storage so it is in a good state again.
                recoverFromBadData(e);
                try {
                    return getPackageStatusLocked();
                } catch (ParseException e2) {
                    throw new IllegalStateException("Recovery from bad file failed", e2);
                }
            }
        }
    }

    @GuardedBy("this")
    private PackageStatus getPackageStatusLocked() throws ParseException {
        try (FileInputStream fis = mPackageStatusFile.openRead()) {
            XmlPullParser parser = parseToPackageStatusTag(fis);
            Integer checkStatus = getNullableIntAttribute(parser, ATTRIBUTE_CHECK_STATUS);
            if (checkStatus == null) {
                return null;
            }
            int updateAppVersion = getIntAttribute(parser, ATTRIBUTE_UPDATE_APP_VERSION);
            int dataAppVersion = getIntAttribute(parser, ATTRIBUTE_DATA_APP_VERSION);
            return new PackageStatus(checkStatus,
                    new PackageVersions(updateAppVersion, dataAppVersion));
        } catch (IOException e) {
            ParseException e2 = new ParseException("Error reading package status", 0);
            e2.initCause(e);
            throw e2;
        }
    }

    @GuardedBy("this")
    private int recoverFromBadData(Exception cause) {
        mPackageStatusFile.delete();
        try {
            return insertInitialPackageStatus();
        } catch (IOException e) {
            IllegalStateException fatal = new IllegalStateException(e);
            fatal.addSuppressed(cause);
            throw fatal;
        }
    }

    /** Insert the initial data, returning the optimistic lock ID */
    private int insertInitialPackageStatus() throws IOException {
        // Doesn't matter what it is, but we avoid the obvious starting value each time the data
        // is reset to ensure that old tokens are unlikely to work.
        final int initialOptimisticLockId = (int) System.currentTimeMillis();

        writePackageStatusLocked(null /* status */, initialOptimisticLockId,
                null /* packageVersions */);
        return initialOptimisticLockId;
    }

    /**
     * Generate a new {@link CheckToken} that can be passed to the time zone rules update
     * application.
     */
    CheckToken generateCheckToken(PackageVersions currentInstalledVersions) {
        if (currentInstalledVersions == null) {
            throw new NullPointerException("currentInstalledVersions == null");
        }

        synchronized (this) {
            int optimisticLockId;
            try {
                optimisticLockId = getCurrentOptimisticLockId();
            } catch (ParseException e) {
                Slog.w(LOG_TAG, "Unable to find optimistic lock ID from package status");

                // Recover.
                optimisticLockId = recoverFromBadData(e);
            }

            int newOptimisticLockId = optimisticLockId + 1;
            try {
                boolean statusUpdated = writePackageStatusWithOptimisticLockCheck(
                        optimisticLockId, newOptimisticLockId, CHECK_STARTED,
                        currentInstalledVersions);
                if (!statusUpdated) {
                    throw new IllegalStateException("Unable to update status to CHECK_STARTED."
                            + " synchronization failure?");
                }
                return new CheckToken(newOptimisticLockId, currentInstalledVersions);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Reset the current device state to "unknown".
     */
    void resetCheckState() {
        synchronized(this) {
            int optimisticLockId;
            try {
                optimisticLockId = getCurrentOptimisticLockId();
            } catch (ParseException e) {
                Slog.w(LOG_TAG, "resetCheckState: Unable to find optimistic lock ID from package"
                        + " status");
                // Attempt to recover the storage state.
                optimisticLockId = recoverFromBadData(e);
            }

            int newOptimisticLockId = optimisticLockId + 1;
            try {
                if (!writePackageStatusWithOptimisticLockCheck(optimisticLockId,
                        newOptimisticLockId, null /* status */, null /* packageVersions */)) {
                    throw new IllegalStateException("resetCheckState: Unable to reset package"
                            + " status, newOptimisticLockId=" + newOptimisticLockId);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /**
     * Update the current device state if possible. Returns true if the update was successful.
     * {@code false} indicates the storage has been changed since the {@link CheckToken} was
     * generated and the update was discarded.
     */
    boolean markChecked(CheckToken checkToken, boolean succeeded) {
        synchronized (this) {
            int optimisticLockId = checkToken.mOptimisticLockId;
            int newOptimisticLockId = optimisticLockId + 1;
            int status = succeeded ? CHECK_COMPLETED_SUCCESS : CHECK_COMPLETED_FAILURE;
            try {
                return writePackageStatusWithOptimisticLockCheck(optimisticLockId,
                        newOptimisticLockId, status, checkToken.mPackageVersions);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @GuardedBy("this")
    private int getCurrentOptimisticLockId() throws ParseException {
        try (FileInputStream fis = mPackageStatusFile.openRead()) {
            XmlPullParser parser = parseToPackageStatusTag(fis);
            return getIntAttribute(parser, ATTRIBUTE_OPTIMISTIC_LOCK_ID);
        } catch (IOException e) {
            ParseException e2 = new ParseException("Unable to read file", 0);
            e2.initCause(e);
            throw e2;
        }
    }

    /** Returns a parser or throws ParseException, never returns null. */
    private static XmlPullParser parseToPackageStatusTag(FileInputStream fis)
            throws ParseException {
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());
            int type;
            while ((type = parser.next()) != END_DOCUMENT) {
                final String tag = parser.getName();
                if (type == START_TAG && TAG_PACKAGE_STATUS.equals(tag)) {
                    return parser;
                }
            }
            throw new ParseException("Unable to find " + TAG_PACKAGE_STATUS + " tag", 0);
        } catch (XmlPullParserException e) {
            throw new IllegalStateException("Unable to configure parser", e);
        } catch (IOException e) {
            ParseException e2 = new ParseException("Error reading XML", 0);
            e.initCause(e);
            throw e2;
        }
    }

    @GuardedBy("this")
    private boolean writePackageStatusWithOptimisticLockCheck(int optimisticLockId,
            int newOptimisticLockId, Integer status, PackageVersions packageVersions)
            throws IOException {

        int currentOptimisticLockId;
        try {
            currentOptimisticLockId = getCurrentOptimisticLockId();
            if (currentOptimisticLockId != optimisticLockId) {
                return false;
            }
        } catch (ParseException e) {
            recoverFromBadData(e);
            return false;
        }

        writePackageStatusLocked(status, newOptimisticLockId, packageVersions);
        return true;
    }

    @GuardedBy("this")
    private void writePackageStatusLocked(Integer status, int optimisticLockId,
            PackageVersions packageVersions) throws IOException {
        if ((status == null) != (packageVersions == null)) {
            throw new IllegalArgumentException(
                    "Provide both status and packageVersions, or neither.");
        }

        FileOutputStream fos = null;
        try {
            fos = mPackageStatusFile.startWrite();
            XmlSerializer serializer = new FastXmlSerializer();
            serializer.setOutput(fos, StandardCharsets.UTF_8.name());
            serializer.startDocument(null /* encoding */, true /* standalone */);
            final String namespace = null;
            serializer.startTag(namespace, TAG_PACKAGE_STATUS);
            String statusAttributeValue = status == null ? "" : Integer.toString(status);
            serializer.attribute(namespace, ATTRIBUTE_CHECK_STATUS, statusAttributeValue);
            serializer.attribute(namespace, ATTRIBUTE_OPTIMISTIC_LOCK_ID,
                    Integer.toString(optimisticLockId));
            int updateAppVersion = status == null
                    ? UNKNOWN_PACKAGE_VERSION : packageVersions.mUpdateAppVersion;
            serializer.attribute(namespace, ATTRIBUTE_UPDATE_APP_VERSION,
                    Integer.toString(updateAppVersion));
            int dataAppVersion = status == null
                    ? UNKNOWN_PACKAGE_VERSION : packageVersions.mDataAppVersion;
            serializer.attribute(namespace, ATTRIBUTE_DATA_APP_VERSION,
                    Integer.toString(dataAppVersion));
            serializer.endTag(namespace, TAG_PACKAGE_STATUS);
            serializer.endDocument();
            serializer.flush();
            mPackageStatusFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mPackageStatusFile.failWrite(fos);
            }
            throw e;
        }

    }

    /** Only used during tests to force a known table state. */
    public void forceCheckStateForTests(int checkStatus, PackageVersions packageVersions) {
        synchronized (this) {
            try {
                int optimisticLockId = getCurrentOptimisticLockId();
                writePackageStatusWithOptimisticLockCheck(optimisticLockId, optimisticLockId,
                        checkStatus, packageVersions);
            } catch (IOException | ParseException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Integer getNullableIntAttribute(XmlPullParser parser, String attributeName)
            throws ParseException {
        String attributeValue = parser.getAttributeValue(null, attributeName);
        try {
            if (attributeValue == null) {
                throw new ParseException("Attribute " + attributeName + " missing", 0);
            } else if (attributeValue.isEmpty()) {
                return null;
            }
            return Integer.parseInt(attributeValue);
        } catch (NumberFormatException e) {
            throw new ParseException(
                    "Bad integer for attributeName=" + attributeName + ": " + attributeValue, 0);
        }
    }

    private static int getIntAttribute(XmlPullParser parser, String attributeName)
            throws ParseException {
        Integer value = getNullableIntAttribute(parser, attributeName);
        if (value == null) {
            throw new ParseException("Missing attribute " + attributeName, 0);
        }
        return value;
    }

    public void dump(PrintWriter printWriter) {
        printWriter.println("Package status: " + getPackageStatus());
    }
}
