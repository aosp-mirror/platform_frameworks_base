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

package com.android.server.fingerprint;

import android.content.Context;
import android.hardware.fingerprint.Fingerprint;
import android.os.AsyncTask;
import android.os.Environment;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;

import libcore.io.IoUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Class managing the set of fingerprint per user across device reboots.
 */
class FingerprintsUserState {

    private static final String TAG = "FingerprintState";
    private static final String FINGERPRINT_FILE = "settings_fingerprint.xml";

    private static final String TAG_FINGERPRINTS = "fingerprints";
    private static final String TAG_FINGERPRINT = "fingerprint";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_GROUP_ID = "groupId";
    private static final String ATTR_FINGER_ID = "fingerId";
    private static final String ATTR_DEVICE_ID = "deviceId";

    private final File mFile;

    @GuardedBy("this")
    private final ArrayList<Fingerprint> mFingerprints = new ArrayList<Fingerprint>();
    private final Context mCtx;

    public FingerprintsUserState(Context ctx, int userId) {
        mFile = getFileForUser(userId);
        mCtx = ctx;
        synchronized (this) {
            readStateSyncLocked();
        }
    }

    public void addFingerprint(int fingerId, int groupId) {
        synchronized (this) {
            mFingerprints.add(new Fingerprint(getUniqueName(), groupId, fingerId, 0));
            scheduleWriteStateLocked();
        }
    }

    public void removeFingerprint(int fingerId) {
        synchronized (this) {
            for (int i = 0; i < mFingerprints.size(); i++) {
                if (mFingerprints.get(i).getFingerId() == fingerId) {
                    mFingerprints.remove(i);
                    scheduleWriteStateLocked();
                    break;
                }
            }
        }
    }

    public void renameFingerprint(int fingerId, CharSequence name) {
        synchronized (this) {
            for (int i = 0; i < mFingerprints.size(); i++) {
                if (mFingerprints.get(i).getFingerId() == fingerId) {
                    Fingerprint old = mFingerprints.get(i);
                    mFingerprints.set(i, new Fingerprint(name, old.getGroupId(), old.getFingerId(),
                            old.getDeviceId()));
                    scheduleWriteStateLocked();
                    break;
                }
            }
        }
    }

    public List<Fingerprint> getFingerprints() {
        synchronized (this) {
            return getCopy(mFingerprints);
        }
    }

    /**
     * Finds a unique name for the given fingerprint
     * @return unique name
     */
    private String getUniqueName() {
        int guess = 1;
        while (true) {
            // Not the most efficient algorithm in the world, but there shouldn't be more than 10
            String name = mCtx.getString(com.android.internal.R.string.fingerprint_name_template,
                    guess);
            if (isUnique(name)) {
                return name;
            }
            guess++;
        }
    }

    private boolean isUnique(String name) {
        for (Fingerprint fp : mFingerprints) {
            if (fp.getName().equals(name)) {
                return false;
            }
        }
        return true;
    }

    private static File getFileForUser(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), FINGERPRINT_FILE);
    }

    private final Runnable mWriteStateRunnable = new Runnable() {
        @Override
        public void run() {
            doWriteState();
        }
    };

    private void scheduleWriteStateLocked() {
        AsyncTask.execute(mWriteStateRunnable);
    }

    private ArrayList<Fingerprint> getCopy(ArrayList<Fingerprint> array) {
        ArrayList<Fingerprint> result = new ArrayList<Fingerprint>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Fingerprint fp = array.get(i);
            result.add(new Fingerprint(fp.getName(), fp.getGroupId(), fp.getFingerId(),
                    fp.getDeviceId()));
        }
        return result;
    }

    private void doWriteState() {
        AtomicFile destination = new AtomicFile(mFile);

        ArrayList<Fingerprint> fingerprints;

        synchronized (this) {
            fingerprints = getCopy(mFingerprints);
        }

        FileOutputStream out = null;
        try {
            out = destination.startWrite();

            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, "utf-8");
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_FINGERPRINTS);

            final int count = fingerprints.size();
            for (int i = 0; i < count; i++) {
                Fingerprint fp = fingerprints.get(i);
                serializer.startTag(null, TAG_FINGERPRINT);
                serializer.attribute(null, ATTR_FINGER_ID, Integer.toString(fp.getFingerId()));
                serializer.attribute(null, ATTR_NAME, fp.getName().toString());
                serializer.attribute(null, ATTR_GROUP_ID, Integer.toString(fp.getGroupId()));
                serializer.attribute(null, ATTR_DEVICE_ID, Long.toString(fp.getDeviceId()));
                serializer.endTag(null, TAG_FINGERPRINT);
            }

            serializer.endTag(null, TAG_FINGERPRINTS);
            serializer.endDocument();
            destination.finishWrite(out);

            // Any error while writing is fatal.
        } catch (Throwable t) {
            Slog.wtf(TAG, "Failed to write settings, restoring backup", t);
            destination.failWrite(out);
            throw new IllegalStateException("Failed to write fingerprints", t);
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    @GuardedBy("this")
    private void readStateSyncLocked() {
        FileInputStream in;
        if (!mFile.exists()) {
            return;
        }
        try {
            in = new FileInputStream(mFile);
        } catch (FileNotFoundException fnfe) {
            Slog.i(TAG, "No fingerprint state");
            return;
        }
        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            parseStateLocked(parser);

        } catch (XmlPullParserException | IOException e) {
            throw new IllegalStateException("Failed parsing settings file: "
                    + mFile , e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    @GuardedBy("this")
    private void parseStateLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_FINGERPRINTS)) {
                parseFingerprintsLocked(parser);
            }
        }
    }

    @GuardedBy("this")
    private void parseFingerprintsLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException {

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_FINGERPRINT)) {
                String name = parser.getAttributeValue(null, ATTR_NAME);
                String groupId = parser.getAttributeValue(null, ATTR_GROUP_ID);
                String fingerId = parser.getAttributeValue(null, ATTR_FINGER_ID);
                String deviceId = parser.getAttributeValue(null, ATTR_DEVICE_ID);
                mFingerprints.add(new Fingerprint(name, Integer.parseInt(groupId),
                        Integer.parseInt(fingerId), Integer.parseInt(deviceId)));
            }
        }
    }

}
