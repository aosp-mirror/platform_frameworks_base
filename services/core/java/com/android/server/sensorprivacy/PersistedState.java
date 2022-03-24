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

import android.hardware.SensorPrivacyManager;
import android.os.Environment;
import android.os.UserHandle;
import android.service.SensorPrivacyIndividualEnabledSensorProto;
import android.service.SensorPrivacySensorProto;
import android.service.SensorPrivacyServiceDumpProto;
import android.service.SensorPrivacyUserProto;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.QuadConsumer;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.IoThread;
import com.android.server.LocalServices;
import com.android.server.pm.UserManagerInternal;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Class for managing persisted state. Synchronization must be handled by the caller.
 */
class PersistedState {

    private static final String LOG_TAG = PersistedState.class.getSimpleName();

    /** Version number indicating compatibility parsing the persisted file */
    private static final int CURRENT_PERSISTENCE_VERSION = 2;
    /** Version number indicating the persisted data needs upgraded to match new internal data
     *  structures and features */
    private static final int CURRENT_VERSION = 2;

    private static final String XML_TAG_SENSOR_PRIVACY = "sensor-privacy";
    private static final String XML_TAG_SENSOR_STATE = "sensor-state";
    private static final String XML_ATTRIBUTE_PERSISTENCE_VERSION = "persistence-version";
    private static final String XML_ATTRIBUTE_VERSION = "version";
    private static final String XML_ATTRIBUTE_TOGGLE_TYPE = "toggle-type";
    private static final String XML_ATTRIBUTE_USER_ID = "user-id";
    private static final String XML_ATTRIBUTE_SENSOR = "sensor";
    private static final String XML_ATTRIBUTE_STATE_TYPE = "state-type";
    private static final String XML_ATTRIBUTE_LAST_CHANGE = "last-change";

    private final AtomicFile mAtomicFile;

    private ArrayMap<TypeUserSensor, SensorState> mStates = new ArrayMap<>();

    static PersistedState fromFile(String fileName) {
        return new PersistedState(fileName);
    }

    private PersistedState(String fileName) {
        mAtomicFile = new AtomicFile(new File(Environment.getDataSystemDirectory(), fileName));
        readState();
    }

    private void readState() {
        AtomicFile file = mAtomicFile;
        if (!file.exists()) {
            AtomicFile fileToMigrateFrom =
                    new AtomicFile(new File(Environment.getDataSystemDirectory(),
                            "sensor_privacy.xml"));

            if (fileToMigrateFrom.exists()) {
                // Sample the start tag to determine if migration is needed
                try (FileInputStream inputStream = fileToMigrateFrom.openRead()) {
                    TypedXmlPullParser parser = Xml.resolvePullParser(inputStream);
                    XmlUtils.beginDocument(parser, XML_TAG_SENSOR_PRIVACY);
                    file = fileToMigrateFrom;
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Caught an exception reading the state from storage: ", e);
                    // Delete the file to prevent the same error on subsequent calls and assume
                    // sensor privacy is not enabled.
                    fileToMigrateFrom.delete();
                } catch (XmlPullParserException e) {
                    // No migration needed
                }
            }
        }

        Object nonupgradedState = null;
        if (file.exists()) {
            try (FileInputStream inputStream = file.openRead()) {
                TypedXmlPullParser parser = Xml.resolvePullParser(inputStream);
                XmlUtils.beginDocument(parser, XML_TAG_SENSOR_PRIVACY);
                final int persistenceVersion = parser.getAttributeInt(null,
                        XML_ATTRIBUTE_PERSISTENCE_VERSION, 0);

                // Use inline string literals for xml tags/attrs when parsing old versions since
                // these should never be changed even with refactorings.
                if (persistenceVersion == 0) {
                    int version = 0;
                    PVersion0 version0 = new PVersion0(version);
                    nonupgradedState = version0;
                    readPVersion0(parser, version0);
                } else if (persistenceVersion == 1) {
                    int version = parser.getAttributeInt(null,
                            "version", 1);
                    PVersion1 version1 = new PVersion1(version);
                    nonupgradedState = version1;

                    readPVersion1(parser, version1);
                } else if (persistenceVersion == CURRENT_PERSISTENCE_VERSION) {
                    int version = parser.getAttributeInt(null,
                            XML_ATTRIBUTE_VERSION, 2);
                    PVersion2 version2 = new PVersion2(version);
                    nonupgradedState = version2;

                    readPVersion2(parser, version2);
                } else {
                    Log.e(LOG_TAG, "Unknown persistence version: " + persistenceVersion
                                    + ". Deleting.",
                            new RuntimeException());
                    file.delete();
                    nonupgradedState = null;
                }

            } catch (IOException | XmlPullParserException | RuntimeException e) {
                Log.e(LOG_TAG, "Caught an exception reading the state from storage: ", e);
                // Delete the file to prevent the same error on subsequent calls and assume
                // sensor privacy is not enabled.
                file.delete();
                nonupgradedState = null;
            }
        }

        if (nonupgradedState == null) {
            // New file, default state for current version goes here.
            nonupgradedState = new PVersion2(2);
        }

        if (nonupgradedState instanceof PVersion0) {
            nonupgradedState = PVersion1.fromPVersion0((PVersion0) nonupgradedState);
        }
        if (nonupgradedState instanceof PVersion1) {
            nonupgradedState = PVersion2.fromPVersion1((PVersion1) nonupgradedState);
        }
        if (nonupgradedState instanceof PVersion2) {
            PVersion2 upgradedState = (PVersion2) nonupgradedState;
            mStates = upgradedState.mStates;
        } else {
            Log.e(LOG_TAG, "State not successfully upgraded.");
            mStates = new ArrayMap<>();
        }
    }

    private static void readPVersion0(TypedXmlPullParser parser, PVersion0 version0)
            throws XmlPullParserException, IOException {

        XmlUtils.nextElement(parser);
        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
            if ("individual-sensor-privacy".equals(parser.getName())) {
                int sensor = XmlUtils.readIntAttribute(parser, "sensor");
                boolean indEnabled = XmlUtils.readBooleanAttribute(parser,
                        "enabled");
                version0.addState(sensor, indEnabled);
                XmlUtils.skipCurrentTag(parser);
            } else {
                XmlUtils.nextElement(parser);
            }
        }
    }

    private static void readPVersion1(TypedXmlPullParser parser, PVersion1 version1)
            throws XmlPullParserException, IOException {
        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
            XmlUtils.nextElement(parser);

            if ("user".equals(parser.getName())) {
                int currentUserId = parser.getAttributeInt(null, "id");
                int depth = parser.getDepth();
                while (XmlUtils.nextElementWithin(parser, depth)) {
                    if ("individual-sensor-privacy".equals(parser.getName())) {
                        int sensor = parser.getAttributeInt(null, "sensor");
                        boolean isEnabled = parser.getAttributeBoolean(null,
                                "enabled");
                        version1.addState(currentUserId, sensor, isEnabled);
                    }
                }
            }
        }
    }

    private static void readPVersion2(TypedXmlPullParser parser, PVersion2 version2)
            throws XmlPullParserException, IOException {

        while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
            XmlUtils.nextElement(parser);

            if (XML_TAG_SENSOR_STATE.equals(parser.getName())) {
                int toggleType = parser.getAttributeInt(null, XML_ATTRIBUTE_TOGGLE_TYPE);
                int userId = parser.getAttributeInt(null, XML_ATTRIBUTE_USER_ID);
                int sensor = parser.getAttributeInt(null, XML_ATTRIBUTE_SENSOR);
                int state = parser.getAttributeInt(null, XML_ATTRIBUTE_STATE_TYPE);
                long lastChange = parser.getAttributeLong(null, XML_ATTRIBUTE_LAST_CHANGE);

                version2.addState(toggleType, userId, sensor, state, lastChange);
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    public SensorState getState(int toggleType, int userId, int sensor) {
        return mStates.get(new TypeUserSensor(toggleType, userId, sensor));
    }

    public SensorState setState(int toggleType, int userId, int sensor, SensorState sensorState) {
        return mStates.put(new TypeUserSensor(toggleType, userId, sensor), sensorState);
    }

    private static class TypeUserSensor {

        int mType;
        int mUserId;
        int mSensor;

        TypeUserSensor(int type, int userId, int sensor) {
            mType = type;
            mUserId = userId;
            mSensor = sensor;
        }

        TypeUserSensor(TypeUserSensor typeUserSensor) {
            this(typeUserSensor.mType, typeUserSensor.mUserId, typeUserSensor.mSensor);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TypeUserSensor)) return false;
            TypeUserSensor that = (TypeUserSensor) o;
            return mType == that.mType && mUserId == that.mUserId && mSensor == that.mSensor;
        }

        @Override
        public int hashCode() {
            return 31 * (31 * mType + mUserId) + mSensor;
        }
    }

    void schedulePersist() {
        int numStates = mStates.size();

        ArrayMap<TypeUserSensor, SensorState> statesCopy = new ArrayMap<>();
        for (int i = 0; i < numStates; i++) {
            statesCopy.put(new TypeUserSensor(mStates.keyAt(i)),
                    new SensorState(mStates.valueAt(i)));
        }
        IoThread.getHandler().sendMessage(
                PooledLambda.obtainMessage(PersistedState::persist, this, statesCopy));
    }

    private void persist(ArrayMap<TypeUserSensor, SensorState> states) {
        FileOutputStream outputStream = null;
        try {
            outputStream = mAtomicFile.startWrite();
            TypedXmlSerializer serializer = Xml.resolveSerializer(outputStream);
            serializer.startDocument(null, true);
            serializer.startTag(null, XML_TAG_SENSOR_PRIVACY);
            serializer.attributeInt(null, XML_ATTRIBUTE_PERSISTENCE_VERSION,
                    CURRENT_PERSISTENCE_VERSION);
            serializer.attributeInt(null, XML_ATTRIBUTE_VERSION, CURRENT_VERSION);
            for (int i = 0; i < states.size(); i++) {
                TypeUserSensor userSensor = states.keyAt(i);
                SensorState sensorState = states.valueAt(i);

                // Do not persist hardware toggle states. Will be restored on reboot
                if (userSensor.mType != SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE) {
                    continue;
                }

                serializer.startTag(null, XML_TAG_SENSOR_STATE);
                serializer.attributeInt(null, XML_ATTRIBUTE_TOGGLE_TYPE,
                        userSensor.mType);
                serializer.attributeInt(null, XML_ATTRIBUTE_USER_ID,
                        userSensor.mUserId);
                serializer.attributeInt(null, XML_ATTRIBUTE_SENSOR,
                        userSensor.mSensor);
                serializer.attributeInt(null, XML_ATTRIBUTE_STATE_TYPE,
                        sensorState.getState());
                serializer.attributeLong(null, XML_ATTRIBUTE_LAST_CHANGE,
                        sensorState.getLastChange());
                serializer.endTag(null, XML_TAG_SENSOR_STATE);
            }

            serializer.endTag(null, XML_TAG_SENSOR_PRIVACY);
            serializer.endDocument();
            mAtomicFile.finishWrite(outputStream);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Caught an exception persisting the sensor privacy state: ", e);
            mAtomicFile.failWrite(outputStream);
        }
    }

    void dump(DualDumpOutputStream dumpStream) {
        // Collect per user, then per sensor. <toggle type, state>
        SparseArray<SparseArray<Pair<Integer, SensorState>>> statesMatrix = new SparseArray<>();
        int numStates = mStates.size();
        for (int i = 0; i < numStates; i++) {
            int toggleType = mStates.keyAt(i).mType;
            int userId = mStates.keyAt(i).mUserId;
            int sensor = mStates.keyAt(i).mSensor;

            SparseArray<Pair<Integer, SensorState>> userStates = statesMatrix.get(userId);
            if (userStates == null) {
                userStates = new SparseArray<>();
                statesMatrix.put(userId, userStates);
            }
            userStates.put(sensor, new Pair<>(toggleType, mStates.valueAt(i)));
        }

        dumpStream.write("storage_implementation",
                SensorPrivacyServiceDumpProto.STORAGE_IMPLEMENTATION,
                SensorPrivacyStateControllerImpl.class.getName());

        int numUsers = statesMatrix.size();
        for (int i = 0; i < numUsers; i++) {
            int userId = statesMatrix.keyAt(i);
            long userToken = dumpStream.start("users", SensorPrivacyServiceDumpProto.USER);
            dumpStream.write("user_id", SensorPrivacyUserProto.USER_ID, userId);
            SparseArray<Pair<Integer, SensorState>> userStates = statesMatrix.valueAt(i);
            int numSensors = userStates.size();
            for (int j = 0; j < numSensors; j++) {
                int sensor = userStates.keyAt(j);
                int toggleType = userStates.valueAt(j).first;
                SensorState sensorState = userStates.valueAt(j).second;
                long sensorToken = dumpStream.start("sensors", SensorPrivacyUserProto.SENSORS);
                dumpStream.write("sensor", SensorPrivacySensorProto.SENSOR, sensor);
                long toggleToken = dumpStream.start("toggles", SensorPrivacySensorProto.TOGGLES);
                dumpStream.write("toggle_type",
                        SensorPrivacyIndividualEnabledSensorProto.TOGGLE_TYPE,
                        toggleType);
                dumpStream.write("state_type",
                        SensorPrivacyIndividualEnabledSensorProto.STATE_TYPE,
                        sensorState.getState());
                dumpStream.write("last_change",
                        SensorPrivacyIndividualEnabledSensorProto.LAST_CHANGE,
                        sensorState.getLastChange());
                dumpStream.end(toggleToken);
                dumpStream.end(sensorToken);
            }
            dumpStream.end(userToken);
        }
    }

    void forEachKnownState(QuadConsumer<Integer, Integer, Integer, SensorState> consumer) {
        int numStates = mStates.size();
        for (int i = 0; i < numStates; i++) {
            TypeUserSensor tus = mStates.keyAt(i);
            SensorState sensorState = mStates.valueAt(i);
            consumer.accept(tus.mType, tus.mUserId, tus.mSensor, sensorState);
        }
    }

    // Structure for persistence version 0
    private static class PVersion0 {
        private SparseArray<SensorState> mIndividualEnabled = new SparseArray<>();

        private PVersion0(int version) {
            if (version != 0) {
                throw new RuntimeException("Only version 0 supported");
            }
        }

        private void addState(int sensor, boolean enabled) {
            mIndividualEnabled.put(sensor, new SensorState(enabled));
        }

        private void upgrade() {
            // No op, only version 0 is supported
        }
    }

    // Structure for persistence version 1
    private static class PVersion1 {
        private SparseArray<SparseArray<SensorState>> mIndividualEnabled = new SparseArray<>();

        private PVersion1(int version) {
            if (version != 1) {
                throw new RuntimeException("Only version 1 supported");
            }
        }

        private static PVersion1 fromPVersion0(PVersion0 version0) {
            version0.upgrade();

            PVersion1 result = new PVersion1(1);

            int[] users = {UserHandle.USER_SYSTEM};
            try {
                users = LocalServices.getService(UserManagerInternal.class).getUserIds();
            } catch (Exception e) {
                Log.e(LOG_TAG, "Unable to get users.", e);
            }

            // Copy global state to each user
            for (int i = 0; i < users.length; i++) {
                int userId = users[i];

                for (int j = 0; j < version0.mIndividualEnabled.size(); j++) {
                    final int sensor = version0.mIndividualEnabled.keyAt(j);
                    final SensorState sensorState = version0.mIndividualEnabled.valueAt(j);

                    result.addState(userId, sensor, sensorState.isEnabled());
                }
            }

            return result;
        }

        private void addState(int userId, int sensor, boolean enabled) {
            SparseArray<SensorState> userIndividualSensorEnabled =
                    mIndividualEnabled.get(userId, new SparseArray<>());
            mIndividualEnabled.put(userId, userIndividualSensorEnabled);

            userIndividualSensorEnabled
                    .put(sensor, new SensorState(enabled));
        }

        private void upgrade() {
            // No op, only version 1 is supported
        }
    }

    // Structure for persistence version 2
    private static class PVersion2 {
        private ArrayMap<TypeUserSensor, SensorState> mStates = new ArrayMap<>();

        private PVersion2(int version) {
            if (version != 2) {
                throw new RuntimeException("Only version 2 supported");
            }
        }

        private static PVersion2 fromPVersion1(PVersion1 version1) {
            version1.upgrade();

            PVersion2 result = new PVersion2(2);

            SparseArray<SparseArray<SensorState>> individualEnabled =
                    version1.mIndividualEnabled;
            int numUsers = individualEnabled.size();
            for (int i = 0; i < numUsers; i++) {
                int userId = individualEnabled.keyAt(i);
                SparseArray<SensorState> userIndividualEnabled = individualEnabled.valueAt(i);
                int numSensors = userIndividualEnabled.size();
                for (int j = 0; j < numSensors; j++) {
                    int sensor = userIndividualEnabled.keyAt(j);
                    SensorState sensorState = userIndividualEnabled.valueAt(j);
                    result.addState(SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE,
                            userId, sensor, sensorState.getState(), sensorState.getLastChange());
                }
            }

            return result;
        }

        private void addState(int toggleType, int userId, int sensor, int state,
                long lastChange) {
            mStates.put(new TypeUserSensor(toggleType, userId, sensor),
                    new SensorState(state, lastChange));
        }
    }

    public void resetForTesting() {
        mStates = new ArrayMap<>();
    }
}
