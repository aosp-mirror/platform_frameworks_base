/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.pm;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import android.content.pm.Signature;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;

class PackageSignatures {
    Signature[] mSignatures;

    PackageSignatures(PackageSignatures orig) {
        if (orig != null && orig.mSignatures != null) {
            mSignatures = orig.mSignatures.clone();
        }
    }

    PackageSignatures(Signature[] sigs) {
        assignSignatures(sigs);
    }

    PackageSignatures() {
    }

    void writeXml(XmlSerializer serializer, String tagName,
            ArrayList<Signature> pastSignatures) throws IOException {
        if (mSignatures == null) {
            return;
        }
        serializer.startTag(null, tagName);
        serializer.attribute(null, "count",
                Integer.toString(mSignatures.length));
        for (int i=0; i<mSignatures.length; i++) {
            serializer.startTag(null, "cert");
            final Signature sig = mSignatures[i];
            final int sigHash = sig.hashCode();
            final int numPast = pastSignatures.size();
            int j;
            for (j=0; j<numPast; j++) {
                Signature pastSig = pastSignatures.get(j);
                if (pastSig.hashCode() == sigHash && pastSig.equals(sig)) {
                    serializer.attribute(null, "index", Integer.toString(j));
                    break;
                }
            }
            if (j >= numPast) {
                pastSignatures.add(sig);
                serializer.attribute(null, "index", Integer.toString(numPast));
                serializer.attribute(null, "key", sig.toCharsString());
            }
            serializer.endTag(null, "cert");
        }
        serializer.endTag(null, tagName);
    }

    void readXml(XmlPullParser parser, ArrayList<Signature> pastSignatures)
            throws IOException, XmlPullParserException {
        String countStr = parser.getAttributeValue(null, "count");
        if (countStr == null) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: <signatures> has"
                       + " no count at " + parser.getPositionDescription());
            XmlUtils.skipCurrentTag(parser);
        }
        final int count = Integer.parseInt(countStr);
        mSignatures = new Signature[count];
        int pos = 0;

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("cert")) {
                if (pos < count) {
                    String index = parser.getAttributeValue(null, "index");
                    if (index != null) {
                        try {
                            int idx = Integer.parseInt(index);
                            String key = parser.getAttributeValue(null, "key");
                            if (key == null) {
                                if (idx >= 0 && idx < pastSignatures.size()) {
                                    Signature sig = pastSignatures.get(idx);
                                    if (sig != null) {
                                        mSignatures[pos] = pastSignatures.get(idx);
                                        pos++;
                                    } else {
                                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                                "Error in package manager settings: <cert> "
                                                   + "index " + index + " is not defined at "
                                                   + parser.getPositionDescription());
                                    }
                                } else {
                                    PackageManagerService.reportSettingsProblem(Log.WARN,
                                            "Error in package manager settings: <cert> "
                                               + "index " + index + " is out of bounds at "
                                               + parser.getPositionDescription());
                                }
                            } else {
                                while (pastSignatures.size() <= idx) {
                                    pastSignatures.add(null);
                                }
                                Signature sig = new Signature(key);
                                pastSignatures.set(idx, sig);
                                mSignatures[pos] = sig;
                                pos++;
                            }
                        } catch (NumberFormatException e) {
                            PackageManagerService.reportSettingsProblem(Log.WARN,
                                    "Error in package manager settings: <cert> "
                                       + "index " + index + " is not a number at "
                                       + parser.getPositionDescription());
                        } catch (IllegalArgumentException e) {
                            PackageManagerService.reportSettingsProblem(Log.WARN,
                                    "Error in package manager settings: <cert> "
                                       + "index " + index + " has an invalid signature at "
                                       + parser.getPositionDescription() + ": "
                                       + e.getMessage());
                        }
                    } else {
                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: <cert> has"
                                   + " no index at " + parser.getPositionDescription());
                    }
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "Error in package manager settings: too "
                               + "many <cert> tags, expected " + count
                               + " at " + parser.getPositionDescription());
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <cert>: "
                        + parser.getName());
            }
            XmlUtils.skipCurrentTag(parser);
        }

        if (pos < count) {
            // Should never happen -- there is an error in the written
            // settings -- but if it does we don't want to generate
            // a bad array.
            Signature[] newSigs = new Signature[pos];
            System.arraycopy(mSignatures, 0, newSigs, 0, pos);
            mSignatures = newSigs;
        }
    }

    void assignSignatures(Signature[] sigs) {
        if (sigs == null) {
            mSignatures = null;
            return;
        }
        mSignatures = new Signature[sigs.length];
        for (int i=0; i<sigs.length; i++) {
            mSignatures[i] = sigs[i];
        }
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer(128);
        buf.append("PackageSignatures{");
        buf.append(Integer.toHexString(System.identityHashCode(this)));
        buf.append(" [");
        if (mSignatures != null) {
            for (int i=0; i<mSignatures.length; i++) {
                if (i > 0) buf.append(", ");
                buf.append(Integer.toHexString(
                        System.identityHashCode(mSignatures[i])));
            }
        }
        buf.append("]}");
        return buf.toString();
    }
}