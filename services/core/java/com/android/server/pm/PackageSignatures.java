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

import android.annotation.NonNull;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.SigningDetails.SignatureSchemeVersion;
import android.content.pm.Signature;
import android.util.Log;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.ArrayList;

class PackageSignatures {

    @NonNull PackageParser.SigningDetails mSigningDetails;

    PackageSignatures(PackageSignatures orig) {
        if (orig != null && orig.mSigningDetails != PackageParser.SigningDetails.UNKNOWN) {
            mSigningDetails = new PackageParser.SigningDetails(orig.mSigningDetails);
        } else {
            mSigningDetails = PackageParser.SigningDetails.UNKNOWN;
        }
    }

    PackageSignatures(PackageParser.SigningDetails signingDetails) {
        mSigningDetails = signingDetails;
    }

    PackageSignatures() {
        mSigningDetails = PackageParser.SigningDetails.UNKNOWN;
    }

    void writeXml(XmlSerializer serializer, String tagName,
            ArrayList<Signature> writtenSignatures) throws IOException {
        if (mSigningDetails.signatures == null) {
            return;
        }
        serializer.startTag(null, tagName);
        serializer.attribute(null, "count", Integer.toString(mSigningDetails.signatures.length));
        serializer.attribute(null, "schemeVersion",
                Integer.toString(mSigningDetails.signatureSchemeVersion));
        writeCertsListXml(serializer, writtenSignatures, mSigningDetails.signatures, null);

        // if we have past signer certificate information, write it out
        if (mSigningDetails.pastSigningCertificates != null) {
            serializer.startTag(null, "pastSigs");
            serializer.attribute(null, "count",
                    Integer.toString(mSigningDetails.pastSigningCertificates.length));
            writeCertsListXml(
                    serializer, writtenSignatures, mSigningDetails.pastSigningCertificates,
                    mSigningDetails.pastSigningCertificatesFlags);
            serializer.endTag(null, "pastSigs");
        }
        serializer.endTag(null, tagName);
    }

    private void writeCertsListXml(XmlSerializer serializer, ArrayList<Signature> writtenSignatures,
            Signature[] signatures, int[] flags) throws IOException {
        for (int i=0; i<signatures.length; i++) {
            serializer.startTag(null, "cert");
            final Signature sig = signatures[i];
            final int sigHash = sig.hashCode();
            final int numWritten = writtenSignatures.size();
            int j;
            for (j=0; j<numWritten; j++) {
                Signature writtenSig = writtenSignatures.get(j);
                if (writtenSig.hashCode() == sigHash && writtenSig.equals(sig)) {
                    serializer.attribute(null, "index", Integer.toString(j));
                    break;
                }
            }
            if (j >= numWritten) {
                writtenSignatures.add(sig);
                serializer.attribute(null, "index", Integer.toString(numWritten));
                serializer.attribute(null, "key", sig.toCharsString());
            }
            if (flags != null) {
                serializer.attribute(null, "flags", Integer.toString(flags[i]));
            }
            serializer.endTag(null, "cert");
        }
    }

    void readXml(XmlPullParser parser, ArrayList<Signature> readSignatures)
            throws IOException, XmlPullParserException {
        PackageParser.SigningDetails.Builder builder =
                new PackageParser.SigningDetails.Builder();

        String countStr = parser.getAttributeValue(null, "count");
        if (countStr == null) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: <sigs> has"
                       + " no count at " + parser.getPositionDescription());
            XmlUtils.skipCurrentTag(parser);
        }
        final int count = Integer.parseInt(countStr);

        String schemeVersionStr = parser.getAttributeValue(null, "schemeVersion");
        int signatureSchemeVersion;
        if (schemeVersionStr == null) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: <sigs> has no schemeVersion at "
                        + parser.getPositionDescription());
            signatureSchemeVersion = SignatureSchemeVersion.UNKNOWN;
        } else {
            signatureSchemeVersion = Integer.parseInt(schemeVersionStr);
        }
        builder.setSignatureSchemeVersion(signatureSchemeVersion);
        Signature[] signatures = new Signature[count];
        int pos = readCertsListXml(parser, readSignatures, signatures, null, builder);
        builder.setSignatures(signatures);
        if (pos < count) {
            // Should never happen -- there is an error in the written
            // settings -- but if it does we don't want to generate
            // a bad array.
            Signature[] newSigs = new Signature[pos];
            System.arraycopy(signatures, 0, newSigs, 0, pos);
            builder = builder.setSignatures(newSigs);
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: <sigs> count does not match number of "
                            + " <cert> entries" + parser.getPositionDescription());
        }

        try {
            mSigningDetails = builder.build();
        } catch (CertificateException e) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: <sigs> "
                            + "unable to convert certificate(s) to public key(s).");
            mSigningDetails = PackageParser.SigningDetails.UNKNOWN;
        }
    }

    private int readCertsListXml(XmlPullParser parser, ArrayList<Signature> readSignatures,
            Signature[] signatures, int[] flags, PackageParser.SigningDetails.Builder builder)
            throws IOException, XmlPullParserException {
        int count = signatures.length;
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
                                if (idx >= 0 && idx < readSignatures.size()) {
                                    Signature sig = readSignatures.get(idx);
                                    if (sig != null) {
                                        signatures[pos] = readSignatures.get(idx);
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
                                while (readSignatures.size() <= idx) {
                                    readSignatures.add(null);
                                }
                                Signature sig = new Signature(key);
                                readSignatures.set(idx, sig);
                                signatures[pos] = sig;
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

                        if (flags != null) {
                            String flagsStr = parser.getAttributeValue(null, "flags");
                            if (flagsStr != null) {
                                try {
                                    flags[pos] = Integer.parseInt(flagsStr);
                                } catch (NumberFormatException e) {
                                    PackageManagerService.reportSettingsProblem(Log.WARN,
                                            "Error in package manager settings: <cert> "
                                                    + "flags " + flagsStr + " is not a number at "
                                                    + parser.getPositionDescription());
                                }
                            } else {
                                PackageManagerService.reportSettingsProblem(Log.WARN,
                                        "Error in package manager settings: <cert> has no"
                                                + " flags at " + parser.getPositionDescription());
                            }
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
                pos++;
                XmlUtils.skipCurrentTag(parser);
            } else if (tagName.equals("pastSigs")) {
                if (flags == null) {
                    // we haven't encountered pastSigs yet, go ahead
                    String countStr = parser.getAttributeValue(null, "count");
                    if (countStr == null) {
                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: <pastSigs> has"
                                        + " no count at " + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                    }
                    try {
                        final int pastSigsCount = Integer.parseInt(countStr);
                        Signature[] pastSignatures = new Signature[pastSigsCount];
                        int[] pastSignaturesFlags = new int[pastSigsCount];
                        int pastSigsPos = readCertsListXml(parser, readSignatures, pastSignatures,
                                pastSignaturesFlags, builder);
                        builder = builder
                                .setPastSigningCertificates(pastSignatures)
                                .setPastSigningCertificatesFlags(pastSignaturesFlags);

                        if (pastSigsPos < pastSigsCount) {
                            // Should never happen -- there is an error in the written
                            // settings -- but if it does we don't want to generate
                            // a bad array.
                            Signature[] newSigs = new Signature[pastSigsPos];
                            System.arraycopy(pastSignatures, 0, newSigs, 0, pastSigsPos);
                            int[] newFlags = new int[pastSigsPos];
                            System.arraycopy(pastSignaturesFlags, 0, newFlags, 0, pastSigsPos);
                            builder = builder
                                    .setPastSigningCertificates(newSigs)
                                    .setPastSigningCertificatesFlags(newFlags);
                            PackageManagerService.reportSettingsProblem(Log.WARN,
                                    "Error in package manager settings: <pastSigs> count does not "
                                    + "match number of <cert> entries "
                                    + parser.getPositionDescription());
                        }
                    } catch (NumberFormatException e) {
                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: <pastSigs> "
                                        + "count " + countStr + " is not a number at "
                                        + parser.getPositionDescription());
                    }
                } else {
                    PackageManagerService.reportSettingsProblem(Log.WARN,
                            "<pastSigs> encountered multiple times under the same <sigs> at "
                                    + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                }
            } else {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element under <sigs>: "
                                + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
        return pos;
    }

    @Override
    public String toString() {
        StringBuffer buf = new StringBuffer(128);
        buf.append("PackageSignatures{");
        buf.append(Integer.toHexString(System.identityHashCode(this)));
        buf.append(" version:");
        buf.append(mSigningDetails.signatureSchemeVersion);
        buf.append(", signatures:[");
        if (mSigningDetails.signatures != null) {
            for (int i = 0; i < mSigningDetails.signatures.length; i++) {
                if (i > 0) buf.append(", ");
                buf.append(Integer.toHexString(
                        mSigningDetails.signatures[i].hashCode()));
            }
        }
        buf.append("]");
        buf.append(", past signatures:[");
        if (mSigningDetails.pastSigningCertificates != null) {
            for (int i = 0; i < mSigningDetails.pastSigningCertificates.length; i++) {
                if (i > 0) buf.append(", ");
                buf.append(Integer.toHexString(
                        mSigningDetails.pastSigningCertificates[i].hashCode()));
                buf.append(" flags: ");
                buf.append(Integer.toHexString(mSigningDetails.pastSigningCertificatesFlags[i]));
            }
        }
        buf.append("]}");
        return buf.toString();
    }
}
