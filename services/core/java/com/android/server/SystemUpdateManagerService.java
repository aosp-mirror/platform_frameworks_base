/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server;

import static android.os.SystemUpdateManager.KEY_STATUS;
import static android.os.SystemUpdateManager.STATUS_IDLE;
import static android.os.SystemUpdateManager.STATUS_UNKNOWN;

import static org.xmlpull.v1.XmlPullParser.END_DOCUMENT;
import static org.xmlpull.v1.XmlPullParser.END_TAG;
import static org.xmlpull.v1.XmlPullParser.START_TAG;

import android.Manifest;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ISystemUpdateManager;
import android.os.PersistableBundle;
import android.os.SystemUpdateManager;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class SystemUpdateManagerService extends ISystemUpdateManager.Stub {

    private static final String TAG = "SystemUpdateManagerService";

    private static final int UID_UNKNOWN = -1;

    private static final String INFO_FILE = "system-update-info.xml";
    private static final int INFO_FILE_VERSION = 0;
    private static final String TAG_INFO = "info";
    private static final String KEY_VERSION = "version";
    private static final String KEY_UID = "uid";
    private static final String KEY_BOOT_COUNT = "boot-count";
    private static final String KEY_INFO_BUNDLE = "info-bundle";

    private final Context mContext;
    private final AtomicFile mFile;
    private final Object mLock = new Object();
    private int mLastUid = UID_UNKNOWN;
    private int mLastStatus = STATUS_UNKNOWN;

    public SystemUpdateManagerService(Context context) {
        mContext = context;
        mFile = new AtomicFile(new File(Environment.getDataSystemDirectory(), INFO_FILE));

        // Populate mLastUid and mLastStatus.
        synchronized (mLock) {
            loadSystemUpdateInfoLocked();
        }
    }

    @Override
    public void updateSystemUpdateInfo(PersistableBundle infoBundle) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.RECOVERY, TAG);

        int status = infoBundle.getInt(KEY_STATUS, STATUS_UNKNOWN);
        if (status == STATUS_UNKNOWN) {
            Slog.w(TAG, "Invalid status info. Ignored");
            return;
        }

        // There could be multiple updater apps running on a device. But only one at most should
        // be active (i.e. with a pending update), with the rest reporting idle status. We will
        // only accept the reported status if any of the following conditions holds:
        //   a) none has been reported before;
        //   b) the current on-file status was last reported by the same caller;
        //   c) an active update is being reported.
        int uid = Binder.getCallingUid();
        if (mLastUid == UID_UNKNOWN || mLastUid == uid || status != STATUS_IDLE) {
            synchronized (mLock) {
                saveSystemUpdateInfoLocked(infoBundle, uid);
            }
        } else {
            Slog.i(TAG, "Inactive updater reporting IDLE status. Ignored");
        }
    }

    @Override
    public Bundle retrieveSystemUpdateInfo() {
        if (mContext.checkCallingOrSelfPermission(Manifest.permission.READ_SYSTEM_UPDATE_INFO)
                == PackageManager.PERMISSION_DENIED
                && mContext.checkCallingOrSelfPermission(Manifest.permission.RECOVERY)
                == PackageManager.PERMISSION_DENIED) {
            throw new SecurityException("Can't read system update info. Requiring "
                    + "READ_SYSTEM_UPDATE_INFO or RECOVERY permission.");
        }

        synchronized (mLock) {
            return loadSystemUpdateInfoLocked();
        }
    }

    // Reads and validates the info file. Returns the loaded info bundle on success; or a default
    // info bundle with UNKNOWN status.
    private Bundle loadSystemUpdateInfoLocked() {
        PersistableBundle loadedBundle = null;
        try (FileInputStream fis = mFile.openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(fis, StandardCharsets.UTF_8.name());
            loadedBundle = readInfoFileLocked(parser);
        } catch (FileNotFoundException e) {
            Slog.i(TAG, "No existing info file " + mFile.getBaseFile());
        } catch (XmlPullParserException e) {
            Slog.e(TAG, "Failed to parse the info file:", e);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read the info file:", e);
        }

        // Validate the loaded bundle.
        if (loadedBundle == null) {
            return removeInfoFileAndGetDefaultInfoBundleLocked();
        }

        int version = loadedBundle.getInt(KEY_VERSION, -1);
        if (version == -1) {
            Slog.w(TAG, "Invalid info file (invalid version). Ignored");
            return removeInfoFileAndGetDefaultInfoBundleLocked();
        }

        int lastUid = loadedBundle.getInt(KEY_UID, -1);
        if (lastUid == -1) {
            Slog.w(TAG, "Invalid info file (invalid UID). Ignored");
            return removeInfoFileAndGetDefaultInfoBundleLocked();
        }

        int lastBootCount = loadedBundle.getInt(KEY_BOOT_COUNT, -1);
        if (lastBootCount == -1 || lastBootCount != getBootCount()) {
            Slog.w(TAG, "Outdated info file. Ignored");
            return removeInfoFileAndGetDefaultInfoBundleLocked();
        }

        PersistableBundle infoBundle = loadedBundle.getPersistableBundle(KEY_INFO_BUNDLE);
        if (infoBundle == null) {
            Slog.w(TAG, "Invalid info file (missing info). Ignored");
            return removeInfoFileAndGetDefaultInfoBundleLocked();
        }

        int lastStatus = infoBundle.getInt(KEY_STATUS, STATUS_UNKNOWN);
        if (lastStatus == STATUS_UNKNOWN) {
            Slog.w(TAG, "Invalid info file (invalid status). Ignored");
            return removeInfoFileAndGetDefaultInfoBundleLocked();
        }

        // Everything looks good upon reaching this point.
        mLastStatus = lastStatus;
        mLastUid = lastUid;
        return new Bundle(infoBundle);
    }

    private void saveSystemUpdateInfoLocked(PersistableBundle infoBundle, int uid) {
        // Wrap the incoming bundle with extra info (e.g. version, uid, boot count). We use nested
        // PersistableBundle to avoid manually parsing XML attributes when loading the info back.
        PersistableBundle outBundle = new PersistableBundle();
        outBundle.putPersistableBundle(KEY_INFO_BUNDLE, infoBundle);
        outBundle.putInt(KEY_VERSION, INFO_FILE_VERSION);
        outBundle.putInt(KEY_UID, uid);
        outBundle.putInt(KEY_BOOT_COUNT, getBootCount());

        // Only update the info on success.
        if (writeInfoFileLocked(outBundle)) {
            mLastUid = uid;
            mLastStatus = infoBundle.getInt(KEY_STATUS);
        }
    }

    // Performs I/O work only, without validating the loaded info.
    @Nullable
    private PersistableBundle readInfoFileLocked(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int type;
        while ((type = parser.next()) != END_DOCUMENT) {
            if (type == START_TAG && TAG_INFO.equals(parser.getName())) {
                return PersistableBundle.restoreFromXml(parser);
            }
        }
        return null;
    }

    private boolean writeInfoFileLocked(PersistableBundle outBundle) {
        FileOutputStream fos = null;
        try {
            fos = mFile.startWrite();

            XmlSerializer out = new FastXmlSerializer();
            out.setOutput(fos, StandardCharsets.UTF_8.name());
            out.startDocument(null, true);

            out.startTag(null, TAG_INFO);
            outBundle.saveToXml(out);
            out.endTag(null, TAG_INFO);

            out.endDocument();
            mFile.finishWrite(fos);
            return true;
        } catch (IOException | XmlPullParserException e) {
            Slog.e(TAG, "Failed to save the info file:", e);
            if (fos != null) {
                mFile.failWrite(fos);
            }
        }
        return false;
    }

    private Bundle removeInfoFileAndGetDefaultInfoBundleLocked() {
        if (mFile.exists()) {
            Slog.i(TAG, "Removing info file");
            mFile.delete();
        }

        mLastStatus = STATUS_UNKNOWN;
        mLastUid = UID_UNKNOWN;
        Bundle infoBundle = new Bundle();
        infoBundle.putInt(KEY_STATUS, STATUS_UNKNOWN);
        return infoBundle;
    }

    private int getBootCount() {
        return Settings.Global.getInt(mContext.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
    }
}
