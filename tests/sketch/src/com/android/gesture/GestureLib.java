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

import android.util.Log;
import android.util.Xml;
import android.util.Xml.Encoding;

import com.android.gesture.recognizer.Classifier;
import com.android.gesture.recognizer.Instance;
import com.android.gesture.recognizer.NearestNeighbor;
import com.android.gesture.recognizer.Prediction;

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
import java.util.StringTokenizer;

public class GestureLib {
  
    private static final String LOGTAG = "GestureLib";
    private static String namespace = "ink";
    private final String datapath;
    private HashMap<String, ArrayList<Gesture>> name2gestures =
          new HashMap<String, ArrayList<Gesture>>();
    private Classifier mClassifier;

    public GestureLib(String path) {
        datapath = path;
        mClassifier = new NearestNeighbor();
    }
    
    public Classifier getClassifier() {
        return mClassifier;
    }
    
    /**
     * get all the labels in the library
     * @return a set of strings
     */
    public Set<String> getLabels() {
        return name2gestures.keySet();
    }
    
    public ArrayList<Prediction> recognize(Gesture gesture) {
        Instance instance = Instance.createInstance(gesture, null);
        return mClassifier.classify(instance);
    }
    
    public void addGesture(String name, Gesture gesture) {
        Log.v(LOGTAG, "add an example for gesture: " + name);
        ArrayList<Gesture> gestures = name2gestures.get(name);
        if (gestures == null) {
            gestures = new ArrayList<Gesture>();
            name2gestures.put(name, gestures);
        }
        gestures.add(gesture);
        mClassifier.addInstance(
            Instance.createInstance(gesture, name));
    }
    
    public void removeGesture(String name, Gesture gesture) {
      ArrayList<Gesture> gestures = name2gestures.get(name);
      if (gestures == null) {
          return;
      } 
      
      gestures.remove(gesture);
      
      // if there are no more samples, remove the entry automatically 
      if (gestures.isEmpty()) {
          name2gestures.remove(name);
      }
      
      mClassifier.removeInstance(gesture.getID());
    }
    
    public ArrayList<Gesture> getGestures(String label) {
        ArrayList<Gesture> gestures = name2gestures.get(label);
        if (gestures != null)
            return (ArrayList<Gesture>)gestures.clone();
        else
            return null;
    }
    
    public void load() {
        String filename = datapath
                        + File.separator + "gestures.xml";
        File f = new File(filename);
        if (f.exists()) {
            try {
                loadInk(filename, null);
            }
            catch (SAXException ex) {
                ex.printStackTrace();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
    
    public void save() {
        try {
            compactSave();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }
    
    private void compactSave() throws IOException {
        File f = new File(datapath);
        if (f.exists() == false) {
            f.mkdirs();
        }
        String filename = datapath + File.separator + "gestures.xml";
        Log.v(LOGTAG, "save to " + filename);
        BufferedOutputStream fos = new BufferedOutputStream(
            new FileOutputStream(filename)); 
        
        PrintWriter writer = new PrintWriter(fos);
        XmlSerializer serializer = Xml.newSerializer();
        serializer.setOutput(writer);
        serializer.startDocument(Encoding.ISO_8859_1.name(), null);
        serializer.startTag(namespace, "gestures");
        Iterator<String> it = name2gestures.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            ArrayList<Gesture> gestures = name2gestures.get(key);
            saveGestures(serializer, key, gestures);
        }
        
        serializer.endTag(namespace, "gestures");
        serializer.endDocument();
        serializer.flush();
        writer.close();
        fos.close();    
    }
    
    private static void saveGestures(XmlSerializer serializer,
        String name, ArrayList<Gesture> strokes) throws IOException {
        serializer.startTag(namespace, "gesture");
        serializer.startTag(namespace, "name");
        serializer.text(name);
        serializer.endTag(namespace, "name");
        Iterator<Gesture> it = strokes.iterator();
        while (it.hasNext()) {
          Gesture stk = it.next();
          stk.toXML(namespace, serializer);
        }
        serializer.endTag(namespace, "gesture");
    }
  
    private void loadInk(String filename, String label) throws SAXException, IOException {
        Log.v(LOGTAG, "load from " + filename);
        BufferedInputStream in = new BufferedInputStream(
            new FileInputStream(filename));
        Xml.parse(in, Encoding.ISO_8859_1, new CompactInkHandler());
        in.close();
    }

    class CompactInkHandler implements ContentHandler {
        
        Gesture currentGesture = null;
        StringBuffer buffer = null;
        String gestureName;
        ArrayList<Gesture> gestures;
        
        CompactInkHandler() {
        }
        
        // Receive notification of character data.
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
        }
    
        //Receive notification of the end of a document.
        public void   endDocument() {
        }
        
        // Receive notification of the end of an element.
        public void   endElement(String uri, String localName, String qName) {
            if (localName.equals("gesture")) {
              name2gestures.put(gestureName, gestures);
              gestures = null;
            } else if (localName.equals("name")) {
              gestureName = buffer.toString();
            } else if (localName.equals("stroke")) {
              StringTokenizer tokenizer = new StringTokenizer(buffer.toString(), ",");
              while (tokenizer.hasMoreTokens()) {
                  String str = tokenizer.nextToken();
                  float x = Float.parseFloat(str);
                  str = tokenizer.nextToken();
                  float y = Float.parseFloat(str);
                  try
                  {
                      currentGesture.addPoint(x, y);
                  }
                  catch(Exception ex) {
                      ex.printStackTrace();
                  }
              }
              gestures.add(currentGesture);
              mClassifier.addInstance(
                  Instance.createInstance(currentGesture, gestureName));
              currentGesture = null;
            }
        }
        
        // End the scope of a prefix-URI mapping.
        public void   endPrefixMapping(String prefix) {
        }
          
        //Receive notification of ignorable whitespace in element content.
        public void   ignorableWhitespace(char[] ch, int start, int length) {
        }
          
        //Receive notification of a processing instruction.            
        public void   processingInstruction(String target, String data) {
        }
          
        // Receive an object for locating the origin of SAX document events.
        public void   setDocumentLocator(Locator locator) {
        }
          
        // Receive notification of a skipped entity.
        public void   skippedEntity(String name) {
        }
          
        // Receive notification of the beginning of a document.
        public void   startDocument() {
        }
          
        // Receive notification of the beginning of an element.
        public void   startElement(String uri, String localName, String qName, Attributes atts) {
            if (localName.equals("gesture")) {
                gestures = new ArrayList<Gesture>();
            } else if (localName.equals("name")) {
                buffer = new StringBuffer();
            } else if (localName.equals("stroke")) {
                currentGesture = new Gesture();
                currentGesture.setTimestamp(Long.parseLong(atts.getValue(namespace, "timestamp")));
                currentGesture.setColor(Integer.parseInt(atts.getValue(namespace, "color")));
                currentGesture.setStrokeWidth(Float.parseFloat(atts.getValue(namespace, "width")));
                buffer = new StringBuffer();
            }
        }
          
        public void   startPrefixMapping(String prefix, String uri) {
        }
    }
}
