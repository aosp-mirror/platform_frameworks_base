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
import android.hardware.biometrics.BiometricAuthenticator;
import android.hardware.face.Face;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.server.biometrics.BiometricUserState;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;


/**
 * Class managing the set of faces per user across device reboots.
 * @hide
 */
public class FaceUserState extends BiometricUserState {

    private static final String TAG = "FaceState";
    private static final String FACE_FILE = "settings_face.xml";

    private static final String TAG_FACES = "faces";
    private static final String TAG_FACE = "face";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_FACE_ID = "faceId";
    private static final String ATTR_DEVICE_ID = "deviceId";

    public FaceUserState(Context ctx, int userId) {
        super(ctx, userId);
    }

    @Override
    protected String getBiometricsTag() {
        return TAG_FACES;
    }

    @Override
    protected String getBiometricFile() {
        return FACE_FILE;
    }

    @Override
    protected int getNameTemplateResource() {
        return com.android.internal.R.string.face_name_template;
    }

    @Override
    public void addBiometric(BiometricAuthenticator.Identifier identifier) {
        if (identifier instanceof Face) {
            super.addBiometric(identifier);
        } else {
            Slog.w(TAG, "Attempted to add non-face identifier");
        }
    }

    @Override
    protected ArrayList getCopy(ArrayList array) {
        ArrayList<Face> result = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            Face f = (Face) array.get(i);
            result.add(new Face(f.getName(), f.getBiometricId(), f.getDeviceId()));
        }
        return result;
    }

    @Override
    protected void doWriteState() {
        AtomicFile destination = new AtomicFile(mFile);

        ArrayList<Face> faces;

        synchronized (this) {
            faces = getCopy(mBiometrics);
        }

        FileOutputStream out = null;
        try {
            out = destination.startWrite();

            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(out, "utf-8");
            serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
            serializer.startDocument(null, true);
            serializer.startTag(null, TAG_FACES);

            final int count = faces.size();
            for (int i = 0; i < count; i++) {
                Face f = faces.get(i);
                serializer.startTag(null, TAG_FACE);
                serializer.attribute(null, ATTR_FACE_ID, Integer.toString(f.getBiometricId()));
                serializer.attribute(null, ATTR_NAME, f.getName().toString());
                serializer.attribute(null, ATTR_DEVICE_ID, Long.toString(f.getDeviceId()));
                serializer.endTag(null, TAG_FACE);
            }

            serializer.endTag(null, TAG_FACES);
            serializer.endDocument();
            destination.finishWrite(out);

            // Any error while writing is fatal.
        } catch (Throwable t) {
            Slog.wtf(TAG, "Failed to write settings, restoring backup", t);
            destination.failWrite(out);
            throw new IllegalStateException("Failed to write faces", t);
        } finally {
            IoUtils.closeQuietly(out);
        }
    }

    @GuardedBy("this")
    @Override
    protected void parseBiometricsLocked(XmlPullParser parser)
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
                String name = parser.getAttributeValue(null, ATTR_NAME);
                String faceId = parser.getAttributeValue(null, ATTR_FACE_ID);
                String deviceId = parser.getAttributeValue(null, ATTR_DEVICE_ID);
                mBiometrics.add(new Face(name, Integer.parseInt(faceId), Integer.parseInt(deviceId)));
            }
        }
    }
}
