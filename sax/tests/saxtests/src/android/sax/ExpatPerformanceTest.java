/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.sax;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.util.Xml;
import org.kxml2.io.KXmlParser;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import com.android.frameworks.saxtests.R;

public class ExpatPerformanceTest extends AndroidTestCase {

    private static final String TAG = ExpatPerformanceTest.class.getSimpleName();

    private byte[] mXmlBytes;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        InputStream in = mContext.getResources().openRawResource(R.raw.youtube);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = in.read(buffer)) != -1) {
            out.write(buffer, 0, length);
        }
        mXmlBytes = out.toByteArray();

        Log.i("***", "File size: " + (mXmlBytes.length / 1024) + "k");
    }

    @LargeTest
    public void testPerformance() throws Exception {
//        try {
//            Debug.startMethodTracing("expat3");
//        for (int i = 0; i < 1; i++) {
            runJavaPullParser();
            runSax();
            runExpatPullParser();
//        }
//    } finally {
//            Debug.stopMethodTracing();
//        }
    }

    private InputStream newInputStream() {
        return new ByteArrayInputStream(mXmlBytes);
    }

    private void runSax() throws IOException, SAXException {
        long start = System.currentTimeMillis();
        Xml.parse(newInputStream(), Xml.Encoding.UTF_8, new DefaultHandler());
        long elapsed = System.currentTimeMillis() - start;
        Log.i(TAG, "expat SAX: " + elapsed + "ms");
    }

    private void runExpatPullParser() throws XmlPullParserException, IOException {
        long start = System.currentTimeMillis();
        XmlPullParser pullParser = Xml.newPullParser();
        pullParser.setInput(newInputStream(), "UTF-8");
        withPullParser(pullParser);
        long elapsed = System.currentTimeMillis() - start;
        Log.i(TAG, "expat pull: " + elapsed + "ms");
    }

    private void runJavaPullParser() throws XmlPullParserException, IOException {
        XmlPullParser pullParser;
        long start = System.currentTimeMillis();
        pullParser = new KXmlParser();
        pullParser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        pullParser.setInput(newInputStream(), "UTF-8");
        withPullParser(pullParser);
        long elapsed = System.currentTimeMillis() - start;
        Log.i(TAG, "java pull parser: " + elapsed + "ms");
    }

    private static void withPullParser(XmlPullParser pullParser)
            throws IOException, XmlPullParserException {
        int eventType = pullParser.next();
        while (eventType != XmlPullParser.END_DOCUMENT) {
            switch (eventType) {
                case XmlPullParser.START_TAG:
                    pullParser.getName();
//                        int nattrs = pullParser.getAttributeCount();
//                        for (int i = 0; i < nattrs; ++i) {
//                            pullParser.getAttributeName(i);
//                            pullParser.getAttributeValue(i);
//                        }
                    break;
                case XmlPullParser.END_TAG:
                    pullParser.getName();
                    break;
                case XmlPullParser.TEXT:
                    pullParser.getText();
                    break;
            }
            eventType = pullParser.next();
        }
    }
}
