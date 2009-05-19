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
import java.util.Iterator;
import java.util.Set;

/**
 * GestureLibrary maintains gesture examples and makes predictions on a new
 * gesture
 */
public class GestureLibrary {

    public static final int SEQUENCE_INVARIANT = 1;

    // when SEQUENCE_SENSITIVE is used, only single stroke gestures are allowed
    public static final int SEQUENCE_SENSITIVE = 2;

    private int mSequenceType = SEQUENCE_SENSITIVE;

    public static final int ORIENTATION_INVARIANT = 1;

    // ORIENTATION_SENSITIVE is only available for single stroke gestures
    public static final int ORIENTATION_SENSITIVE = 2;

    private int mOrientationStyle = ORIENTATION_SENSITIVE;

    private static final String LOGTAG = "GestureLibrary";

    private static final String NAMESPACE = "";

    private final String mGestureFileName;

    private HashMap<String, ArrayList<Gesture>> mEntryName2gestures = new HashMap<String, ArrayList<Gesture>>();

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
     * Specify whether the gesture library will handle orientation sensitive
     * gestures. Use ORIENTATION_INVARIANT or ORIENTATION_SENSITIVE
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
        Instance instance = Instance.createInstance(this, gesture, null);
        return mClassifier.classify(this, instance);
    }

    /**
     * Add a gesture for the entry
     * 
     * @param entryName entry name
     * @param gesture
     */
    public void addGesture(String entryName, Gesture gesture) {
        if (Config.DEBUG) {
            Log.v(LOGTAG, "Add an example for gesture: " + entryName);
        }
        if (entryName == null || entryName.length() == 0) {
            return;
        }
        ArrayList<Gesture> gestures = mEntryName2gestures.get(entryName);
        if (gestures == null) {
            gestures = new ArrayList<Gesture>();
            mEntryName2gestures.put(entryName, gestures);
        }
        gestures.add(gesture);
        mClassifier.addInstance(Instance.createInstance(this, gesture, entryName));
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
    @SuppressWarnings("unchecked")
    public ArrayList<Gesture> getGestures(String entryName) {
        ArrayList<Gesture> gestures = mEntryName2gestures.get(entryName);
        if (gestures != null) {
            return (ArrayList<Gesture>)gestures.clone();
        } else {
            return null;
        }
    }

    /**
     * Save the gesture library
     */
    public void save() {
        if (!mChanged)
            return;

        try {
            File file = new File(mGestureFileName);
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            if (Config.DEBUG) {
                Log.v(LOGTAG, "Save to " + mGestureFileName);
            }
            BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(
                    mGestureFileName), GestureConstants.IO_BUFFER_SIZE);

            PrintWriter writer = new PrintWriter(outputStream);
            XmlSerializer serializer = Xml.newSerializer();
            serializer.setOutput(writer);
            serializer.startDocument(Encoding.ISO_8859_1.name(), null);
            serializer.startTag(NAMESPACE, GestureConstants.XML_TAG_LIBRARY);
            HashMap<String, ArrayList<Gesture>> maps = mEntryName2gestures;
            Iterator<String> it = maps.keySet().iterator();
            while (it.hasNext()) {
                String key = it.next();
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
            writer.close();
            outputStream.close();
            mChanged = false;
        } catch (IOException ex) {
            Log.d(LOGTAG, "Failed to save gestures:", ex);
        }
    }

    /**
     * Load the gesture library
     */
    public void load() {
        File file = new File(mGestureFileName);
        if (file.exists()) {
            try {
                if (Config.DEBUG) {
                    Log.v(LOGTAG, "Load from " + mGestureFileName);
                }
                BufferedInputStream in = new BufferedInputStream(new FileInputStream(
                        mGestureFileName), GestureConstants.IO_BUFFER_SIZE);
                Xml.parse(in, Encoding.ISO_8859_1, new CompactInkHandler());
                in.close();
            } catch (SAXException ex) {
                Log.d(LOGTAG, "Failed to load gestures:", ex);
            } catch (IOException ex) {
                Log.d(LOGTAG, "Failed to load gestures:", ex);
            }
        }
    }

    private class CompactInkHandler implements ContentHandler {
        Gesture currentGesture = null;

        StringBuilder buffer = new StringBuilder(GestureConstants.STROKE_STRING_BUFFER_SIZE);

        String entryName;

        ArrayList<Gesture> gestures;

        CompactInkHandler() {
        }

        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
        }

        public void endDocument() {
        }

        public void endElement(String uri, String localName, String qName) {
            if (localName.equals(GestureConstants.XML_TAG_ENTRY)) {
                mEntryName2gestures.put(entryName, gestures);
                gestures = null;
            } else if (localName.equals(GestureConstants.XML_TAG_GESTURE)) {
                gestures.add(currentGesture);
                mClassifier.addInstance(Instance.createInstance(GestureLibrary.this,
                        currentGesture, entryName));
                currentGesture = null;
            } else if (localName.equals(GestureConstants.XML_TAG_STROKE)) {
                currentGesture.addStroke(GestureStroke.createFromString(buffer.toString()));
                buffer.setLength(0);
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
                gestures = new ArrayList<Gesture>();
                entryName = attributes.getValue(NAMESPACE, GestureConstants.XML_TAG_NAME);
            } else if (localName.equals(GestureConstants.XML_TAG_GESTURE)) {
                currentGesture = new Gesture();
                currentGesture.setID(Long.parseLong(attributes.getValue(NAMESPACE,
                        GestureConstants.XML_TAG_ID)));
            }
        }

        public void startPrefixMapping(String prefix, String uri) {
        }
    }
}
