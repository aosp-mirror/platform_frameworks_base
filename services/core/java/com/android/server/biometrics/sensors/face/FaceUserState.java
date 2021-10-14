/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors.face;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.face.Face;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.GuardedBy;
import com.android.server.biometrics.sensors.BiometricUserState;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;


/**
 * Class managing the set of faces per user across device reboots.
 * @hide
 */
public class FaceUserState extends BiometricUserState<Face> {

    private static final String TAG = "FaceState";

    private static final String TAG_FACES = "faces";
    private static final String TAG_FACE = "face";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_FACE_ID = "faceId";
    private static final String ATTR_DEVICE_ID = "deviceId";

    public FaceUserState(Context ctx, int userId, String fileName) {
        super(ctx, userId, fileName);
    }

    @Override
    protected String getBiometricsTag() {
        return TAG_FACES;
    }

    @Override
    protected int getNameTemplateResource() {
        return com.android.internal.R.string.face_name_template;
    }

    @Override
    protected ArrayList<Face> getCopy(ArrayList<Face> array) {
        final ArrayList<Face> result = new ArrayList<>();
        for (Face f : array) {
            result.add(new Face(f.getName(), f.getBiometricId(), f.getDeviceId()));
        }
        return result;
    }

    @Override
    protected void doWriteState(@NonNull TypedXmlSerializer serializer) throws Exception {
        final ArrayList<Face> faces;

        synchronized (this) {
            faces = getCopy(mBiometrics);
        }

        serializer.startTag(null, TAG_FACES);

        final int count = faces.size();
        for (int i = 0; i < count; i++) {
            Face f = faces.get(i);
            serializer.startTag(null, TAG_FACE);
            serializer.attributeInt(null, ATTR_FACE_ID, f.getBiometricId());
            serializer.attribute(null, ATTR_NAME, f.getName().toString());
            serializer.attributeLong(null, ATTR_DEVICE_ID, f.getDeviceId());
            serializer.endTag(null, TAG_FACE);
        }

        serializer.endTag(null, TAG_FACES);
    }

    @GuardedBy("this")
    @Override
    protected void parseBiometricsLocked(TypedXmlPullParser parser)
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
                int faceId = parser.getAttributeInt(null, ATTR_FACE_ID);
                long deviceId = parser.getAttributeLong(null, ATTR_DEVICE_ID);
                mBiometrics.add(new Face(name, faceId, deviceId));
            }
        }
    }
}
