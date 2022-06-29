/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.sensorprivacy;

import android.annotation.NonNull;
import android.os.Environment;
import android.os.Handler;
import android.util.AtomicFile;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.IoThread;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;

class AllSensorStateController {

    private static final String LOG_TAG = AllSensorStateController.class.getSimpleName();

    private static final String SENSOR_PRIVACY_XML_FILE = "sensor_privacy.xml";
    private static final String XML_TAG_SENSOR_PRIVACY = "all-sensor-privacy";
    private static final String XML_TAG_SENSOR_PRIVACY_LEGACY = "sensor-privacy";
    private static final String XML_ATTRIBUTE_ENABLED = "enabled";

    private static AllSensorStateController sInstance;

    private final AtomicFile mAtomicFile =
            new AtomicFile(new File(Environment.getDataSystemDirectory(), SENSOR_PRIVACY_XML_FILE));

    private boolean mEnabled;
    private SensorPrivacyStateController.AllSensorPrivacyListener mListener;
    private Handler mListenerHandler;

    static AllSensorStateController getInstance() {
        if (sInstance == null) {
            sInstance = new AllSensorStateController();
        }
        return sInstance;
    }

    private AllSensorStateController() {
        if (!mAtomicFile.exists()) {
            return;
        }
        try (FileInputStream inputStream = mAtomicFile.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(inputStream);

            while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                String tagName = parser.getName();
                if (XML_TAG_SENSOR_PRIVACY.equals(tagName)) {
                    mEnabled |= XmlUtils
                            .readBooleanAttribute(parser, XML_ATTRIBUTE_ENABLED, false);
                    break;
                }
                if (XML_TAG_SENSOR_PRIVACY_LEGACY.equals(tagName)) {
                    mEnabled |= XmlUtils
                            .readBooleanAttribute(parser, XML_ATTRIBUTE_ENABLED, false);
                }
                if ("user".equals(tagName)) { // Migrate from mic/cam toggles format
                    int user = XmlUtils.readIntAttribute(parser, "id", -1);
                    if (user == 0) {
                        mEnabled |=
                                XmlUtils.readBooleanAttribute(parser, XML_ATTRIBUTE_ENABLED);
                    }
                }
                XmlUtils.nextElement(parser);
            }
        } catch (IOException | XmlPullParserException e) {
            Log.e(LOG_TAG, "Caught an exception reading the state from storage: ", e);
            mEnabled = false;
        }
    }

    public boolean getAllSensorStateLocked() {
        return mEnabled;
    }

    public void setAllSensorStateLocked(boolean enabled) {
        if (mEnabled != enabled) {
            mEnabled = enabled;
            if (mListener != null && mListenerHandler != null) {
                mListenerHandler.sendMessage(
                        PooledLambda.obtainMessage(mListener::onAllSensorPrivacyChanged, enabled));
            }
        }
    }

    void setAllSensorPrivacyListenerLocked(Handler handler,
            SensorPrivacyStateController.AllSensorPrivacyListener listener) {
        Objects.requireNonNull(handler);
        Objects.requireNonNull(listener);
        if (mListener != null) {
            throw new IllegalStateException("Listener is already set");
        }
        mListener = listener;
        mListenerHandler = handler;
    }

    public void schedulePersistLocked() {
        IoThread.getHandler().sendMessage(PooledLambda.obtainMessage(this::persist, mEnabled));
    }

    private void persist(boolean enabled) {
        FileOutputStream outputStream = null;
        try {
            outputStream = mAtomicFile.startWrite();
            TypedXmlSerializer serializer = Xml.resolveSerializer(outputStream);
            serializer.startDocument(null, true);
            serializer.startTag(null, XML_TAG_SENSOR_PRIVACY);
            serializer.attributeBoolean(null, XML_ATTRIBUTE_ENABLED, enabled);
            serializer.endTag(null, XML_TAG_SENSOR_PRIVACY);
            serializer.endDocument();
            mAtomicFile.finishWrite(outputStream);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Caught an exception persisting the sensor privacy state: ", e);
            mAtomicFile.failWrite(outputStream);
        }
    }

    void resetForTesting() {
        mListener = null;
        mListenerHandler = null;
        mEnabled = false;
    }

    void dumpLocked(@NonNull DualDumpOutputStream dumpStream) {
        // TODO stub
    }
}
