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
 * limitations under the License
 */

package com.android.server.biometrics.face;

import android.content.Context;
import android.hardware.face.Face;
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


/**
 * Class managing the set of faces per user across device reboots.
 */
class FaceUserState {

    private static final String TAG = "FaceState";
    private static final String FACE_FILE = "settings_face.xml";

    private static final String TAG_FACE = "face";
    private static final String ATTR_DEVICE_ID = "deviceId";

    private final File mFile;

    @GuardedBy("this")
    private Face mFace = null;
    private final Context mCtx;

    public FaceUserState(Context ctx, int userId) {
        mFile = getFileForUser(userId);
        mCtx = ctx;
        synchronized (this) {
            readStateSyncLocked();
        }
    }

    public void addFace(int faceId) {
        synchronized (this) {
            mFace = new Face("Face", faceId, 0);
            scheduleWriteStateLocked();
        }
    }

    public void removeFace() {
        synchronized (this) {
            mFace = null;
            scheduleWriteStateLocked();
        }
    }

    public Face getFace() {
        synchronized (this) {
            return getCopy(mFace);
        }
    }

    private static File getFileForUser(int userId) {
        return new File(Environment.getUserSystemDirectory(userId), FACE_FILE);
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

    private Face getCopy(Face f) {
        if (f == null) {
            return null;
        }
        return new Face(f.getName(), f.getFaceId(), f.getDeviceId());
    }

    private void doWriteState() {
        AtomicFile destination = new AtomicFile(mFile);

        Face face;

        synchronized (this) {
            face = getCopy(mFace);
        }

        FileOutputStream out = null;
        try {
            out = destination.startWrite();

            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, "utf-8");
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_FACE);
            if (face != null) {
                serializer.attribute(null, ATTR_DEVICE_ID, Long.toString(face.getDeviceId()));
            }
            serializer.endTag(null, TAG_FACE);
            serializer.endDocument();
            destination.finishWrite(out);

            // Any error while writing is fatal.
        } catch (Throwable t) {
            Slog.wtf(TAG, "Failed to write settings, restoring backup", t);
            destination.failWrite(out);
            throw new IllegalStateException("Failed to write face", t);
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    private void readStateSyncLocked() {
        FileInputStream in;
        if (!mFile.exists()) {
            return;
        }
        try {
            in = new FileInputStream(mFile);
        } catch (FileNotFoundException fnfe) {
            Slog.i(TAG, "No face state");
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
            if (tagName.equals(TAG_FACE)) {
                parseFaceLocked(parser);
            }
        }
    }

    private void parseFaceLocked(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        String deviceId = parser.getAttributeValue(null, ATTR_DEVICE_ID);

        mFace = new Face("", 0, Integer.parseInt(deviceId));
    }

}
