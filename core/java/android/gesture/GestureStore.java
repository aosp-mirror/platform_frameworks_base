/*
 * Copyright (C) 2008-2009 The Android Open Source Project
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

package android.gesture;

import android.util.Log;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;
import java.util.Map;

import static android.gesture.GestureConstants.LOG_TAG;

/**
 * GestureLibrary maintains gesture examples and makes predictions on a new
 * gesture
 */
//
//    File format for GestureStore:
//
//                Nb. bytes   Java type   Description
//                -----------------------------------
//    Header
//                2 bytes     short       File format version number
//                4 bytes     int         Number of entries
//    Entry
//                X bytes     UTF String  Entry name
//                4 bytes     int         Number of gestures
//    Gesture
//                8 bytes     long        Gesture ID
//                4 bytes     int         Number of strokes
//    Stroke
//                4 bytes     int         Number of points
//    Point
//                4 bytes     float       X coordinate of the point
//                4 bytes     float       Y coordinate of the point
//                8 bytes     long        Time stamp
//
public class GestureStore {
    public static final int SEQUENCE_INVARIANT = 1;
    // when SEQUENCE_SENSITIVE is used, only single stroke gestures are currently allowed
    public static final int SEQUENCE_SENSITIVE = 2;

    // ORIENTATION_SENSITIVE and ORIENTATION_INVARIANT are only for SEQUENCE_SENSITIVE gestures
    public static final int ORIENTATION_INVARIANT = 1;
    // at most 2 directions can be recognized
    public static final int ORIENTATION_SENSITIVE = 2;
    // at most 4 directions can be recognized
    static final int ORIENTATION_SENSITIVE_4 = 4;
    // at most 8 directions can be recognized
    static final int ORIENTATION_SENSITIVE_8 = 8;

    private static final short FILE_FORMAT_VERSION = 1;

    private static final boolean PROFILE_LOADING_SAVING = false;

    private int mSequenceType = SEQUENCE_SENSITIVE;
    private int mOrientationStyle = ORIENTATION_SENSITIVE;

    private final HashMap<String, ArrayList<Gesture>> mNamedGestures =
            new HashMap<String, ArrayList<Gesture>>();

    private Learner mClassifier;

    private boolean mChanged = false;

    public GestureStore() {
        mClassifier = new InstanceLearner();
    }

    /**
     * Specify how the gesture library will handle orientation. 
     * Use ORIENTATION_INVARIANT or ORIENTATION_SENSITIVE
     * 
     * @param style
     */
    public void setOrientationStyle(int style) {
        mOrientationStyle = style;
    }

    public int getOrientationStyle() {
        return mOrientationStyle;
    }

    /**
     * @param type SEQUENCE_INVARIANT or SEQUENCE_SENSITIVE
     */
    public void setSequenceType(int type) {
        mSequenceType = type;
    }

    /**
     * @return SEQUENCE_INVARIANT or SEQUENCE_SENSITIVE
     */
    public int getSequenceType() {
        return mSequenceType;
    }

    /**
     * Get all the gesture entry names in the library
     * 
     * @return a set of strings
     */
    public Set<String> getGestureEntries() {
        return mNamedGestures.keySet();
    }

    /**
     * Recognize a gesture
     * 
     * @param gesture the query
     * @return a list of predictions of possible entries for a given gesture
     */
    public ArrayList<Prediction> recognize(Gesture gesture) {
        Instance instance = Instance.createInstance(mSequenceType,
                mOrientationStyle, gesture, null);
        return mClassifier.classify(mSequenceType, mOrientationStyle, instance.vector);
    }

    /**
     * Add a gesture for the entry
     * 
     * @param entryName entry name
     * @param gesture
     */
    public void addGesture(String entryName, Gesture gesture) {
        if (entryName == null || entryName.length() == 0) {
            return;
        }
        ArrayList<Gesture> gestures = mNamedGestures.get(entryName);
        if (gestures == null) {
            gestures = new ArrayList<Gesture>();
            mNamedGestures.put(entryName, gestures);
        }
        gestures.add(gesture);
        mClassifier.addInstance(
                Instance.createInstance(mSequenceType, mOrientationStyle, gesture, entryName));
        mChanged = true;
    }

    /**
     * Remove a gesture from the library. If there are no more gestures for the
     * given entry, the gesture entry will be removed.
     * 
     * @param entryName entry name
     * @param gesture
     */
    public void removeGesture(String entryName, Gesture gesture) {
        ArrayList<Gesture> gestures = mNamedGestures.get(entryName);
        if (gestures == null) {
            return;
        }

        gestures.remove(gesture);

        // if there are no more samples, remove the entry automatically
        if (gestures.isEmpty()) {
            mNamedGestures.remove(entryName);
        }

        mClassifier.removeInstance(gesture.getID());

        mChanged = true;
    }

    /**
     * Remove a entry of gestures
     * 
     * @param entryName the entry name
     */
    public void removeEntry(String entryName) {
        mNamedGestures.remove(entryName);
        mClassifier.removeInstances(entryName);
        mChanged = true;
    }

    /**
     * Get all the gestures of an entry
     * 
     * @param entryName
     * @return the list of gestures that is under this name
     */
    public ArrayList<Gesture> getGestures(String entryName) {
        ArrayList<Gesture> gestures = mNamedGestures.get(entryName);
        if (gestures != null) {
            return new ArrayList<Gesture>(gestures);
        } else {
            return null;
        }
    }

    public boolean hasChanged() {
        return mChanged;
    }

    /**
     * Save the gesture library
     */
    public void save(OutputStream stream) throws IOException {
        save(stream, false);
    }

    public void save(OutputStream stream, boolean closeStream) throws IOException {
        DataOutputStream out = null;

        try {
            long start;
            if (PROFILE_LOADING_SAVING) {
                start = SystemClock.elapsedRealtime();
            }

            final HashMap<String, ArrayList<Gesture>> maps = mNamedGestures;

            out = new DataOutputStream((stream instanceof BufferedOutputStream) ? stream :
                    new BufferedOutputStream(stream, GestureConstants.IO_BUFFER_SIZE));
            // Write version number
            out.writeShort(FILE_FORMAT_VERSION);
            // Write number of entries
            out.writeInt(maps.size());

            for (Map.Entry<String, ArrayList<Gesture>> entry : maps.entrySet()) {
                final String key = entry.getKey();
                final ArrayList<Gesture> examples = entry.getValue();
                final int count = examples.size();

                // Write entry name
                out.writeUTF(key);
                // Write number of examples for this entry
                out.writeInt(count);

                for (int i = 0; i < count; i++) {
                    examples.get(i).serialize(out);
                }
            }

            out.flush();

            if (PROFILE_LOADING_SAVING) {
                long end = SystemClock.elapsedRealtime();
                Log.d(LOG_TAG, "Saving gestures library = " + (end - start) + " ms");
            }

            mChanged = false;
        } finally {
            if (closeStream) GestureUtils.closeStream(out);
        }
    }

    /**
     * Load the gesture library
     */
    public void load(InputStream stream) throws IOException {
        load(stream, false);
    }

    public void load(InputStream stream, boolean closeStream) throws IOException {
        DataInputStream in = null;
        try {
            in = new DataInputStream((stream instanceof BufferedInputStream) ? stream :
                    new BufferedInputStream(stream, GestureConstants.IO_BUFFER_SIZE));

            long start;
            if (PROFILE_LOADING_SAVING) {
                start = SystemClock.elapsedRealtime();
            }

            // Read file format version number
            final short versionNumber = in.readShort();
            switch (versionNumber) {
                case 1:
                    readFormatV1(in);
                    break;
            }

            if (PROFILE_LOADING_SAVING) {
                long end = SystemClock.elapsedRealtime();
                Log.d(LOG_TAG, "Loading gestures library = " + (end - start) + " ms");
            }
        } finally {
            if (closeStream) GestureUtils.closeStream(in);
        }
    }

    private void readFormatV1(DataInputStream in) throws IOException {
        final Learner classifier = mClassifier;
        final HashMap<String, ArrayList<Gesture>> namedGestures = mNamedGestures;
        namedGestures.clear();

        // Number of entries in the library
        final int entriesCount = in.readInt();

        for (int i = 0; i < entriesCount; i++) {
            // Entry name
            final String name = in.readUTF();
            // Number of gestures
            final int gestureCount = in.readInt();

            final ArrayList<Gesture> gestures = new ArrayList<Gesture>(gestureCount);
            for (int j = 0; j < gestureCount; j++) {
                final Gesture gesture = Gesture.deserialize(in);
                gestures.add(gesture);
                classifier.addInstance(
                        Instance.createInstance(mSequenceType, mOrientationStyle, gesture, name));
            }

            namedGestures.put(name, gestures);
        }
    }
    
    Learner getLearner() {
        return mClassifier;
    }
}
