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

package com.android.gesture;

import android.util.Config;
import android.util.Log;
import android.util.Xml;
import android.util.Xml.Encoding;

import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

import static com.android.gesture.GestureConstants.LOG_TAG;

/**
 * GestureLibrary maintains gesture examples and makes predictions on a new
 * gesture
 */
public class GestureLibrary {

    private static final String NAMESPACE = "";

    public static final int SEQUENCE_INVARIANT = 1;
    // when SEQUENCE_SENSITIVE is used, only single stroke gestures are currently allowed
    public static final int SEQUENCE_SENSITIVE = 2;

    // ORIENTATION_SENSITIVE and ORIENTATION_INVARIANT are only for SEQUENCE_SENSITIVE gestures
    public static final int ORIENTATION_INVARIANT = 1;
    public static final int ORIENTATION_SENSITIVE = 2;

    private int mSequenceType = SEQUENCE_SENSITIVE;
    private int mOrientationStyle = ORIENTATION_SENSITIVE;

    private final String mGestureFileName;

    private final HashMap<String, ArrayList<Gesture>> mEntryName2gestures =
            new HashMap<String, ArrayList<Gesture>>();

    private Learner mClassifier;

    private boolean mChanged = false;

    /**
     * @param path where gesture data is stored
     */
    public GestureLibrary(String path) {
        mGestureFileName = path;
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

    public void setGestureType(int type) {
        mSequenceType = type;
    }

    public int getGestureType() {
        return mSequenceType;
    }

    /**
     * Get all the gesture entry names in the library
     * 
     * @return a set of strings
     */
    public Set<String> getGestureEntries() {
        return mEntryName2gestures.keySet();
    }

    /**
     * Recognize a gesture
     * 
     * @param gesture the query
     * @return a list of predictions of possible entries for a given gesture
     */
    public ArrayList<Prediction> recognize(Gesture gesture) {
        Instance instance = Instance.createInstance(mSequenceType, gesture, null);
        return mClassifier.classify(mSequenceType, instance.vector);
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
        ArrayList<Gesture> gestures = mEntryName2gestures.get(entryName);
        if (gestures == null) {
            gestures = new ArrayList<Gesture>();
            mEntryName2gestures.put(entryName, gestures);
        }
        gestures.add(gesture);
        mClassifier.addInstance(Instance.createInstance(mSequenceType, gesture, entryName));
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
        ArrayList<Gesture> gestures = mEntryName2gestures.get(entryName);
        if (gestures == null) {
            return;
        }

        gestures.remove(gesture);

        // if there are no more samples, remove the entry automatically
        if (gestures.isEmpty()) {
            mEntryName2gestures.remove(entryName);
        }

        mClassifier.removeInstance(gesture.getID());

        mChanged = true;
    }

    /**
     * Remove a entry of gestures
     * 
     * @param entryName the entry name
     */
    public void removeEntireEntry(String entryName) {
        mEntryName2gestures.remove(entryName);
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
        ArrayList<Gesture> gestures = mEntryName2gestures.get(entryName);
        if (gestures != null) {
            return new ArrayList<Gesture>(gestures);
        } else {
            return null;
        }
    }

    /**
     * Save the gesture library
     */
    public boolean save() {
        if (!mChanged) {
            return true;
        }

        boolean result= false;
        PrintWriter writer = null;

        try {
            File file = new File(mGestureFileName);
            if (!file.getParentFile().exists()) {
                if (!file.getParentFile().mkdirs()) {
                    return false;
                }
            }

            writer = new PrintWriter(new BufferedOutputStream(new FileOutputStream(
                    mGestureFileName), GestureConstants.IO_BUFFER_SIZE));

            final XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(writer);
            serializer.startDocument(Encoding.ISO_8859_1.name(), null);
            serializer.startTag(NAMESPACE, GestureConstants.XML_TAG_LIBRARY);

            final HashMap<String, ArrayList<Gesture>> maps = mEntryName2gestures;

            for (String key : maps.keySet()) {
                ArrayList<Gesture> examples = maps.get(key);
                // save an entry
                serializer.startTag(NAMESPACE, GestureConstants.XML_TAG_ENTRY);
                serializer.attribute(NAMESPACE, GestureConstants.XML_TAG_NAME, key);
                int count = examples.size();
                for (int i = 0; i < count; i++) {
                    Gesture gesture = examples.get(i);
                    // save each gesture in the entry
                    gesture.toXML(NAMESPACE, serializer);
                }
                serializer.endTag(NAMESPACE, GestureConstants.XML_TAG_ENTRY);
            }

            serializer.endTag(NAMESPACE, GestureConstants.XML_TAG_LIBRARY);
            serializer.endDocument();
            serializer.flush();

            mChanged = false;
            result = true;
        } catch (IOException ex) {
            Log.d(LOG_TAG, "Failed to save gestures:", ex);
        } finally {
            GestureUtilities.closeStream(writer);
        }

        return result;
    }

    /**
     * Load the gesture library
     */
    public boolean load() {
        boolean result = false;

        final File file = new File(mGestureFileName);
        if (file.exists()) {
            BufferedInputStream in = null;
            try {
                if (Config.DEBUG) {
                    Log.v(LOG_TAG, "Load from " + mGestureFileName);
                }
                in = new BufferedInputStream(new FileInputStream(
                        mGestureFileName), GestureConstants.IO_BUFFER_SIZE);
                Xml.parse(in, Encoding.ISO_8859_1, new CompactInkHandler());
                result = true;
            } catch (SAXException ex) {
                Log.d(LOG_TAG, "Failed to load gestures:", ex);
            } catch (IOException ex) {
                Log.d(LOG_TAG, "Failed to load gestures:", ex);
            } finally {
                GestureUtilities.closeStream(in);
            }
        }

        return result;
    }

    private class CompactInkHandler implements ContentHandler {
        final StringBuilder mBuffer = new StringBuilder(GestureConstants.STROKE_STRING_BUFFER_SIZE);

        String mEntryName;

        Gesture mCurrentGesture = null;
        ArrayList<Gesture> mGestures;

        CompactInkHandler() {
        }

        public void characters(char[] ch, int start, int length) {
            mBuffer.append(ch, start, length);
        }

        public void endDocument() {
        }

        public void endElement(String uri, String localName, String qName) {
            if (localName.equals(GestureConstants.XML_TAG_ENTRY)) {
                mEntryName2gestures.put(mEntryName, mGestures);
                mGestures = null;
            } else if (localName.equals(GestureConstants.XML_TAG_GESTURE)) {
                mGestures.add(mCurrentGesture);
                mClassifier.addInstance(Instance.createInstance(mSequenceType,
                        mCurrentGesture, mEntryName));
                mCurrentGesture = null;
            } else if (localName.equals(GestureConstants.XML_TAG_STROKE)) {
                mCurrentGesture.addStroke(GestureStroke.createFromString(mBuffer.toString()));
                mBuffer.setLength(0);
            }
        }

        public void endPrefixMapping(String prefix) {
        }

        public void ignorableWhitespace(char[] ch, int start, int length) {
        }

        public void processingInstruction(String target, String data) {
        }

        public void setDocumentLocator(Locator locator) {
        }

        public void skippedEntity(String name) {
        }

        public void startDocument() {
        }

        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if (localName.equals(GestureConstants.XML_TAG_ENTRY)) {
                mGestures = new ArrayList<Gesture>();
                mEntryName = attributes.getValue(NAMESPACE, GestureConstants.XML_TAG_NAME);
            } else if (localName.equals(GestureConstants.XML_TAG_GESTURE)) {
                mCurrentGesture = new Gesture();
                mCurrentGesture.setID(Long.parseLong(attributes.getValue(NAMESPACE,
                        GestureConstants.XML_TAG_ID)));
            }
        }

        public void startPrefixMapping(String prefix, String uri) {
        }
    }
}
