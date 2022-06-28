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

import android.annotation.NonNull;
import android.content.pm.Signature;
import android.content.pm.SigningDetails;
import android.content.pm.SigningDetails.SignatureSchemeVersion;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.security.cert.CertificateException;
import java.util.ArrayList;

class PackageSignatures {

    @NonNull SigningDetails mSigningDetails;

    PackageSignatures(PackageSignatures orig) {
        if (orig != null && orig.mSigningDetails != SigningDetails.UNKNOWN) {
            mSigningDetails = new SigningDetails(orig.mSigningDetails);
        } else {
            mSigningDetails = SigningDetails.UNKNOWN;
        }
    }

    PackageSignatures(SigningDetails signingDetails) {
        mSigningDetails = signingDetails;
    }

    PackageSignatures() {
        mSigningDetails = SigningDetails.UNKNOWN;
    }

    void writeXml(TypedXmlSerializer serializer, String tagName,
            ArrayList<Signature> writtenSignatures) throws IOException {
        if (mSigningDetails.getSignatures() == null) {
            return;
        }
        serializer.startTag(null, tagName);
        serializer.attributeInt(null, "count", mSigningDetails.getSignatures().length);
        serializer.attributeInt(null, "schemeVersion", mSigningDetails.getSignatureSchemeVersion());
        writeCertsListXml(serializer, writtenSignatures, mSigningDetails.getSignatures(), false);

        // if we have past signer certificate information, write it out
        if (mSigningDetails.getPastSigningCertificates() != null) {
            serializer.startTag(null, "pastSigs");
            serializer.attributeInt(null, "count",
                    mSigningDetails.getPastSigningCertificates().length);
            writeCertsListXml(serializer, writtenSignatures,
                    mSigningDetails.getPastSigningCertificates(), true);
            serializer.endTag(null, "pastSigs");
        }
        serializer.endTag(null, tagName);
    }

    private void writeCertsListXml(TypedXmlSerializer serializer,
            ArrayList<Signature> writtenSignatures, Signature[] signatures, boolean isPastSigs)
            throws IOException {
        for (int i=0; i<signatures.length; i++) {
            serializer.startTag(null, "cert");
            final Signature sig = signatures[i];
            final int sigHash = sig.hashCode();
            final int numWritten = writtenSignatures.size();
            int j;
            for (j=0; j<numWritten; j++) {
                Signature writtenSig = writtenSignatures.get(j);
                if (writtenSig.hashCode() == sigHash && writtenSig.equals(sig)) {
                    serializer.attributeInt(null, "index", j);
                    break;
                }
            }
            if (j >= numWritten) {
                writtenSignatures.add(sig);
                serializer.attributeInt(null, "index", numWritten);
                sig.writeToXmlAttributeBytesHex(serializer, null, "key");
            }
            // The flags attribute is only written for previous signatures to represent the
            // capabilities the developer wants to grant to the previous signing certificates.
            if (isPastSigs) {
                serializer.attributeInt(null, "flags", sig.getFlags());
            }
            serializer.endTag(null, "cert");
        }
    }

    void readXml(TypedXmlPullParser parser, ArrayList<Signature> readSignatures)
            throws IOException, XmlPullParserException {
        SigningDetails.Builder builder = new SigningDetails.Builder();

        final int count = parser.getAttributeInt(null, "count", -1);
        if (count == -1) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: <sigs> has"
                       + " no count at " + parser.getPositionDescription());
            XmlUtils.skipCurrentTag(parser);
            return;
        }

        final int signatureSchemeVersion = parser.getAttributeInt(null, "schemeVersion",
                SignatureSchemeVersion.UNKNOWN);
        if (signatureSchemeVersion == SignatureSchemeVersion.UNKNOWN) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: <sigs> has no schemeVersion at "
                        + parser.getPositionDescription());
        }
        builder.setSignatureSchemeVersion(signatureSchemeVersion);
        ArrayList<Signature> signatureList = new ArrayList<>();
        int pos = readCertsListXml(parser, readSignatures, signatureList, count, false, builder);
        Signature[] signatures = signatureList.toArray(new Signature[signatureList.size()]);
        builder.setSignatures(signatures);
        if (pos < count) {
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
            mSigningDetails = SigningDetails.UNKNOWN;
        }
    }

    private int readCertsListXml(TypedXmlPullParser parser, ArrayList<Signature> readSignatures,
            ArrayList<Signature> signatures, int count, boolean isPastSigs,
            SigningDetails.Builder builder)
            throws IOException, XmlPullParserException {
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
                    final int index = parser.getAttributeInt(null, "index", -1);
                    if (index != -1) {
                        boolean signatureParsed = false;
                        try {
                            final byte[] key = parser.getAttributeBytesHex(null, "key", null);
                            if (key == null) {
                                if (index >= 0 && index < readSignatures.size()) {
                                    Signature sig = readSignatures.get(index);
                                    if (sig != null) {
                                        // An app using a shared signature in its signing lineage
                                        // can have unique capabilities assigned to this previous
                                        // signer; create a new instance of this Signature to ensure
                                        // its flags do not overwrite those of the instance from
                                        // readSignatures.
                                        if (isPastSigs) {
                                            signatures.add(new Signature(sig));
                                        } else {
                                            signatures.add(sig);
                                        }
                                        signatureParsed = true;
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
                                // Create the signature first to prevent adding null entries to the
                                // output List if the key value is invalid.
                                Signature sig = new Signature(key);
                                while (readSignatures.size() < index) {
                                    readSignatures.add(null);
                                }
                                readSignatures.add(sig);
                                signatures.add(sig);
                                signatureParsed = true;
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

                        if (isPastSigs) {
                            final int flagsValue = parser.getAttributeInt(null, "flags", -1);
                            if (flagsValue != -1) {
                                try {
                                    // only modify the flags if the signature of the previous signer
                                    // was successfully parsed above
                                    if (signatureParsed) {
                                        signatures.get(signatures.size() - 1).setFlags(flagsValue);
                                    } else {
                                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                                "Error in package manager settings: signature not "
                                                        + "available at index "
                                                        + pos + " to set flags at "
                                                        + parser.getPositionDescription());
                                    }
                                } catch (NumberFormatException e) {
                                    PackageManagerService.reportSettingsProblem(Log.WARN,
                                            "Error in package manager settings: <cert> "
                                                    + "flags " + flagsValue + " is not a number at "
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
                if (!isPastSigs) {
                    // we haven't encountered pastSigs yet, go ahead
                    final int pastSigsCount = parser.getAttributeInt(null, "count", -1);
                    if (pastSigsCount == -1) {
                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: <pastSigs> has"
                                        + " no count at " + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    try {
                        ArrayList<Signature> pastSignatureList = new ArrayList<>();
                        int pastSigsPos = readCertsListXml(parser, readSignatures,
                                pastSignatureList,
                                pastSigsCount, true, builder);
                        Signature[] pastSignatures = pastSignatureList.toArray(
                                new Signature[pastSignatureList.size()]);
                        builder = builder.setPastSigningCertificates(pastSignatures);

                        if (pastSigsPos < pastSigsCount) {
                            PackageManagerService.reportSettingsProblem(Log.WARN,
                                    "Error in package manager settings: <pastSigs> count does not "
                                            + "match number of <cert> entries "
                                            + parser.getPositionDescription());
                        }
                    } catch (NumberFormatException e) {
                        PackageManagerService.reportSettingsProblem(Log.WARN,
                                "Error in package manager settings: <pastSigs> "
                                        + "count " + pastSigsCount + " is not a number at "
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
        StringBuilder buf = new StringBuilder(128);
        buf.append("PackageSignatures{");
        buf.append(Integer.toHexString(System.identityHashCode(this)));
        buf.append(" version:");
        buf.append(mSigningDetails.getSignatureSchemeVersion());
        buf.append(", signatures:[");
        if (mSigningDetails.getSignatures() != null) {
            for (int i = 0; i < mSigningDetails.getSignatures().length; i++) {
                if (i > 0) buf.append(", ");
                buf.append(Integer.toHexString(
                        mSigningDetails.getSignatures()[i].hashCode()));
            }
        }
        buf.append("]");
        buf.append(", past signatures:[");
        if (mSigningDetails.getPastSigningCertificates() != null) {
            for (int i = 0; i < mSigningDetails.getPastSigningCertificates().length; i++) {
                if (i > 0) buf.append(", ");
                buf.append(Integer.toHexString(
                        mSigningDetails.getPastSigningCertificates()[i].hashCode()));
                buf.append(" flags: ");
                buf.append(Integer.toHexString(
                        mSigningDetails.getPastSigningCertificates()[i].getFlags()));
            }
        }
        buf.append("]}");
        return buf.toString();
    }
}
