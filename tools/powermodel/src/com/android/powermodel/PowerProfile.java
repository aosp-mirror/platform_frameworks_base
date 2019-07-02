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
 * limitations under the License.
 */

package com.android.powermodel;

import java.io.InputStream;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

import com.android.powermodel.component.AudioProfile;
import com.android.powermodel.component.BluetoothProfile;
import com.android.powermodel.component.CameraProfile;
import com.android.powermodel.component.CpuProfile;
import com.android.powermodel.component.FlashlightProfile;
import com.android.powermodel.component.GpsProfile;
import com.android.powermodel.component.ModemProfile;
import com.android.powermodel.component.ScreenProfile;
import com.android.powermodel.component.VideoProfile;
import com.android.powermodel.component.WifiProfile;
import com.android.powermodel.util.Conversion;

public class PowerProfile {

    // Remaining fields from the android code for which the actual usage is unclear.
    //   battery.capacity
    //   bluetooth.controller.voltage
    //   modem.controller.voltage
    //   gps.voltage
    //   wifi.controller.voltage
    //   radio.on
    //   radio.scanning
    //   radio.active
    //   memory.bandwidths
    //   wifi.batchedscan
    //   wifi.scan
    //   wifi.on
    //   wifi.active
    //   wifi.controller.tx_levels

    private static Pattern RE_CLUSTER_POWER = Pattern.compile("cpu.cluster_power.cluster([0-9]*)");
    private static Pattern RE_CORE_SPEEDS = Pattern.compile("cpu.core_speeds.cluster([0-9]*)");
    private static Pattern RE_CORE_POWER = Pattern.compile("cpu.core_power.cluster([0-9]*)");

    private HashMap<Component, ComponentProfile> mComponents = new HashMap();

    /**
     * Which element we are currently parsing.
     */
    enum ElementState {
        BEGIN,
        TOP,
        ITEM,
        ARRAY,
        VALUE
    }

    /**
     * Implements the reading and power model logic.
     */
    private static class Parser {
        private final InputStream mStream;
        private final PowerProfile mResult;

        // Builders for the ComponentProfiles.
        private final AudioProfile mAudio = new AudioProfile();
        private final BluetoothProfile mBluetooth = new BluetoothProfile();
        private final CameraProfile mCamera = new CameraProfile();
        private final CpuProfile.Builder mCpuBuilder = new CpuProfile.Builder();
        private final FlashlightProfile mFlashlight = new FlashlightProfile();
        private final GpsProfile.Builder mGpsBuilder = new GpsProfile.Builder();
        private final ModemProfile.Builder mModemBuilder = new ModemProfile.Builder();
        private final ScreenProfile mScreen = new ScreenProfile();
        private final VideoProfile mVideo = new VideoProfile();
        private final WifiProfile mWifi = new WifiProfile();

        /**
         * Constructor to capture the parameters to read.
         */
        Parser(InputStream stream) {
            mStream = stream;
            mResult = new PowerProfile();
        }

        /**
         * Read the stream, parse it, and apply the power model.
         * Do not call this more than once.
         */
        PowerProfile parse() throws ParseException {
            final SAXParserFactory factory = SAXParserFactory.newInstance();
            AndroidResourceHandler handler = null;
            try {
                final SAXParser saxParser = factory.newSAXParser();

                handler = new AndroidResourceHandler() {
                    @Override
                    public void onItem(Locator locator, String name, float value)
                            throws SAXParseException {
                        Parser.this.onItem(locator, name, value);
                    }

                    @Override
                    public void onArray(Locator locator, String name, float[] value)
                            throws SAXParseException {
                        Parser.this.onArray(locator, name, value);
                    }
                };

                saxParser.parse(mStream, handler);
            } catch (ParserConfigurationException ex) {
                // Coding error, not runtime error.
                throw new RuntimeException(ex);
            } catch (SAXParseException ex) {
                throw new ParseException(ex.getLineNumber(), ex.getMessage(), ex);
            } catch (SAXException | IOException ex) {
                // Make a guess about the line number.
                throw new ParseException(handler.getLineNumber(), ex.getMessage(), ex);
            }

            // TODO: This doesn't cover the multiple algorithms. Some refactoring will
            // be necessary.
            mResult.mComponents.put(Component.AUDIO, mAudio);
            mResult.mComponents.put(Component.BLUETOOTH, mBluetooth);
            mResult.mComponents.put(Component.CAMERA, mCamera);
            mResult.mComponents.put(Component.CPU, mCpuBuilder.build());
            mResult.mComponents.put(Component.FLASHLIGHT, mFlashlight);
            mResult.mComponents.put(Component.GPS, mGpsBuilder.build());
            mResult.mComponents.put(Component.MODEM, mModemBuilder.build());
            mResult.mComponents.put(Component.SCREEN, mScreen);
            mResult.mComponents.put(Component.VIDEO, mVideo);
            mResult.mComponents.put(Component.WIFI, mWifi);

            return mResult;
        }

        /**
         * Handles an item tag in the power_profile.xml.
         */
        public void onItem(Locator locator, String name, float value) throws SAXParseException {
            Integer index;
            try {
                if ("ambient.on".equals(name)) {
                    mScreen.ambientMa = value;
                } else if ("audio".equals(name)) {
                    mAudio.onMa = value;
                } else if ("bluetooth.controller.idle".equals(name)) {
                    mBluetooth.idleMa = value;
                } else if ("bluetooth.controller.rx".equals(name)) {
                    mBluetooth.rxMa = value;
                } else if ("bluetooth.controller.tx".equals(name)) {
                    mBluetooth.txMa = value;
                } else if ("camera.avg".equals(name)) {
                    mCamera.onMa = value;
                } else if ("camera.flashlight".equals(name)) {
                    mFlashlight.onMa = value;
                } else if ("cpu.suspend".equals(name)) {
                    mCpuBuilder.setSuspendMa(value);
                } else if ("cpu.idle".equals(name)) {
                    mCpuBuilder.setIdleMa(value);
                } else if ("cpu.active".equals(name)) {
                    mCpuBuilder.setActiveMa(value);
                } else if ((index = matchIndexedRegex(locator, RE_CLUSTER_POWER, name)) != null) {
                    mCpuBuilder.setClusterPower(index, value);
                } else if ("gps.on".equals(name)) {
                    mGpsBuilder.setOnMa(value);
                } else if ("modem.controller.sleep".equals(name)) {
                    mModemBuilder.setSleepMa(value);
                } else if ("modem.controller.idle".equals(name)) {
                    mModemBuilder.setIdleMa(value);
                } else if ("modem.controller.rx".equals(name)) {
                    mModemBuilder.setRxMa(value);
                } else if ("radio.scanning".equals(name)) {
                    mModemBuilder.setScanningMa(value);
                } else if ("screen.on".equals(name)) {
                    mScreen.onMa = value;
                } else if ("screen.full".equals(name)) {
                    mScreen.fullMa = value;
                } else if ("video".equals(name)) {
                    mVideo.onMa = value;
                } else if ("wifi.controller.idle".equals(name)) {
                    mWifi.idleMa = value;
                } else if ("wifi.controller.rx".equals(name)) {
                    mWifi.rxMa = value;
                } else if ("wifi.controller.tx".equals(name)) {
                    mWifi.txMa = value;
                } else {
                    // TODO: Uncomment this when we have all of the items parsed.
                    // throw new SAXParseException("Unhandled <item name=\"" + name + "\"> element",
                    //        locator, ex);

                }
            } catch (ParseException ex) {
                throw new SAXParseException(ex.getMessage(), locator, ex);
            }
        }

        /**
         * Handles an array tag in the power_profile.xml.
         */
        public void onArray(Locator locator, String name, float[] value) throws SAXParseException {
            Integer index;
            try {
                if ("cpu.clusters.cores".equals(name)) {
                    mCpuBuilder.setCoreCount(Conversion.toIntArray(value));
                } else if ((index = matchIndexedRegex(locator, RE_CORE_SPEEDS, name)) != null) {
                    mCpuBuilder.setCoreSpeeds(index, Conversion.toIntArray(value));
                } else if ((index = matchIndexedRegex(locator, RE_CORE_POWER, name)) != null) {
                    mCpuBuilder.setCorePower(index, value);
                } else if ("gps.signalqualitybased".equals(name)) {
                    mGpsBuilder.setSignalMa(value);
                } else if ("modem.controller.tx".equals(name)) {
                    mModemBuilder.setTxMa(value);
                } else {
                    // TODO: Uncomment this when we have all of the items parsed.
                    // throw new SAXParseException("Unhandled <item name=\"" + name + "\"> element",
                    //        locator, ex);
                }
            } catch (ParseException ex) {
                throw new SAXParseException(ex.getMessage(), locator, ex);
            }
        }
    }

    /**
     * SAX XML handler that can parse the android resource files.
     * In our case, all elements are floats.
     */
    abstract static class AndroidResourceHandler extends DefaultHandler {
        /**
         * The set of names already processed. Map of name to line number.
         */
        private HashMap<String,Integer> mAlreadySeen = new HashMap<String,Integer>();

        /**
         * Where in the document we are parsing.
         */
        private Locator mLocator;

        /**
         * Which element we are currently parsing.
         */
        private ElementState mState = ElementState.BEGIN;

        /**
         * Saved name from item and array elements.
         */
        private String mName;

        /**
         * The text that is currently being captured, or null if {@link #startCapturingText()}
         * has not been called.
         */
        private StringBuilder mText;

        /**
         * The array values that have been parsed so for for this array. Null if we are
         * not inside an array tag.
         */
        private ArrayList<Float> mArray;

        /**
         * Called when an item tag is encountered.
         */
        public abstract void onItem(Locator locator, String name, float value)
                throws SAXParseException;

        /**
         * Called when an array is encountered.
         */
        public abstract void onArray(Locator locator, String name, float[] value)
                throws SAXParseException;

        /**
         * If we have a Locator set, return the line number, otherwise return 0.
         */
        public int getLineNumber() {
            return mLocator != null ? mLocator.getLineNumber() : 0;
        }

        /**
         * Handle setting the parse location object.
         */
        public void setDocumentLocator(Locator locator) {
            mLocator = locator;
        }

        /**
         * Handle beginning of an element.
         *
         * @param ns Namespace uri
         * @param ln Local name (inside namespace)
         * @param element Tag name
         */
        @Override
        public void startElement(String ns, String ln, String element,
                Attributes attr) throws SAXException {
            switch (mState) {
                case BEGIN:
                    // Outer element, we don't care the tag name.
                    mState = ElementState.TOP;
                    return;
                case TOP:
                    if ("item".equals(element)) {
                        mState = ElementState.ITEM;
                        saveNameAttribute(attr);
                        startCapturingText();
                        return;
                    } else if ("array".equals(element)) {
                        mState = ElementState.ARRAY;
                        mArray = new ArrayList<Float>();
                        saveNameAttribute(attr);
                        return;
                    }
                    break;
                case ARRAY:
                    if ("value".equals(element)) {
                        mState = ElementState.VALUE;
                        startCapturingText();
                        return;
                    }
                    break;
            }
            throw new SAXParseException("unexpected element: '" + element + "'", mLocator);
        }

        /**
         * Handle end of an element.
         *
         * @param ns Namespace uri
         * @param ln Local name (inside namespace)
         * @param element Tag name
         */
        @Override
        public void endElement(String ns, String ln, String element) throws SAXException {
            switch (mState) {
                case ITEM: {
                    float value = parseFloat(finishCapturingText());
                    mState = ElementState.TOP;
                    onItem(mLocator, mName, value);
                    break;
                }
                case ARRAY: {
                    final int N = mArray.size();
                    float[] values = new float[N];
                    for (int i=0; i<N; i++) {
                        values[i] = mArray.get(i);
                    }
                    mArray = null;
                    mState = ElementState.TOP;
                    onArray(mLocator, mName, values);
                    break;
                }
                case VALUE: {
                    mArray.add(parseFloat(finishCapturingText()));
                    mState = ElementState.ARRAY;
                    break;
                }
            }
        }

        /**
         * Interstitial text received.
         *
         * @throws SAXException if there shouldn't be non-whitespace text here
         */
        @Override
        public void characters(char text[], int start, int length) throws SAXException {
            if (mText == null && length > 0 && !isWhitespace(text, start, length)) {
                throw new SAXParseException("unexpected text: '"
                        + firstLine(text, start, length).trim() + "'", mLocator);
            }
            if (mText != null) {
                mText.append(text, start, length);
            }
        }

        /**
         * Begin collecting text from inside an element.
         */
        private void startCapturingText() {
            if (mText != null) {
                throw new RuntimeException("ASSERTION FAILED: Shouldn't be already capturing"
                        + " text. mState=" + mState.name()
                        + " line=" + mLocator.getLineNumber()
                        + " column=" + mLocator.getColumnNumber());
            }
            mText = new StringBuilder();
        }

        /**
         * Stop capturing text from inside an element.
         *
         * @return the captured text
         */
        private String finishCapturingText() {
            if (mText == null) {
                throw new RuntimeException("ASSERTION FAILED: Should already be capturing"
                        + " text. mState=" + mState.name()
                        + " line=" + mLocator.getLineNumber()
                        + " column=" + mLocator.getColumnNumber());
            }
            final String result = mText.toString().trim();
            mText = null;
            return result;
        }

        /**
         * Get the "name" attribute.
         *
         * @throws SAXParseException if the name attribute is not present or if
         * the name has already been seen in the file.
         */
        private void saveNameAttribute(Attributes attr) throws SAXParseException {
            final String name = attr.getValue("name");
            if (name == null) {
                throw new SAXParseException("expected 'name' attribute", mLocator);
            }
            Integer prev = mAlreadySeen.put(name, mLocator.getLineNumber());
            if (prev != null) {
                throw new SAXParseException("name '" + name + "' already seen on line: " + prev,
                        mLocator);
            }
            mName = name;
        }

        /**
         * Gets the float value of the string.
         *
         * @throws SAXParseException if 'text' can't be parsed as a float.
         */
        private float parseFloat(String text) throws SAXParseException {
            try {
                return Float.parseFloat(text);
            } catch (NumberFormatException ex) {
                throw new SAXParseException("not a valid float value: '" + text + "'",
                        mLocator, ex);
            }
        }
    }

    /**
     * Return whether the given substring is all whitespace.
     */
    private static boolean isWhitespace(char[] text, int start, int length) {
        for (int i = start; i < (start + length); i++) {
            if (!Character.isSpace(text[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Return the contents of text up to the first newline.
     */
    private static String firstLine(char[] text, int start, int length) {
        // TODO: The line number will be wrong if we skip preceeding blank lines.
        while (length > 0) {
            if (Character.isSpace(text[start])) {
                start++;
                length--;
            }
        }
        int newlen = 0;
        for (; newlen < length; newlen++) {
            final char c = text[newlen];
            if (c == '\n' || c == '\r') {
                break;
            }
        }
        return new String(text, start, newlen);
    }

    /**
     * If the pattern matches, return the first group of that as an Integer.
     * If not return null.
     */
    private static Integer matchIndexedRegex(Locator locator, Pattern pattern, String text)
            throws SAXParseException {
        final Matcher m = pattern.matcher(text);
        if (m.matches()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ex) {
                throw new SAXParseException("Invalid field name: '" + text + "'", locator, ex);
            }
        } else {
            return null;
        }
    }

    public static PowerProfile parse(InputStream stream) throws ParseException {
        return (new Parser(stream)).parse();
    }

    private PowerProfile() {
    }

    public ComponentProfile getComponent(Component component) {
        return mComponents.get(component);
    }

}
