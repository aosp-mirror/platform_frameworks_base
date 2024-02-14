/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.appop;

import static android.app.AppOpsManager.opToDefaultMode;

import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

class LegacyAppOpStateParser {
    static final String TAG = LegacyAppOpStateParser.class.getSimpleName();

    private static final int NO_FILE_VERSION = -2;
    private static final int NO_VERSION = -1;

    /**
     * Reads legacy app-ops data into given maps.
     */
    public int readState(AtomicFile file, SparseArray<SparseIntArray> uidModes,
            SparseArray<ArrayMap<String, SparseIntArray>> userPackageModes) {
        try (FileInputStream stream = file.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(stream);
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Parse next until we reach the start or end
            }

            if (type != XmlPullParser.START_TAG) {
                throw new IllegalStateException("no start tag found");
            }

            int versionAtBoot = parser.getAttributeInt(null, "v", NO_VERSION);

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("pkg")) {
                    // version 2 has the structure pkg -> uid -> op ->
                    // in version 3, since pkg and uid states are kept completely
                    // independent we switch to user -> pkg -> op
                    readPackage(parser, userPackageModes);
                } else if (tagName.equals("uid")) {
                    readUidOps(parser, uidModes);
                } else if (tagName.equals("user")) {
                    readUser(parser, userPackageModes);
                } else {
                    Slog.w(TAG, "Unknown element under <app-ops>: "
                            + parser.getName());
                    XmlUtils.skipCurrentTag(parser);
                }
            }
            return versionAtBoot;
        } catch (FileNotFoundException e) {
            Slog.i(TAG, "No existing app ops " + file.getBaseFile() + "; starting empty");
            return NO_FILE_VERSION;
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void readPackage(TypedXmlPullParser parser,
            SparseArray<ArrayMap<String, SparseIntArray>> userPackageModes)
            throws NumberFormatException, XmlPullParserException, IOException {
        String pkgName = parser.getAttributeValue(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("uid")) {
                readPackageUid(parser, pkgName, userPackageModes);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readPackageUid(TypedXmlPullParser parser, String pkgName,
            SparseArray<ArrayMap<String, SparseIntArray>> userPackageModes)
            throws NumberFormatException, XmlPullParserException, IOException {
        int userId = UserHandle.getUserId(parser.getAttributeInt(null, "n"));
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                readOp(parser, userId, pkgName, userPackageModes);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readUidOps(TypedXmlPullParser parser, SparseArray<SparseIntArray> uidModes)
            throws NumberFormatException,
            XmlPullParserException, IOException {
        final int uid = parser.getAttributeInt(null, "n");
        SparseIntArray modes = uidModes.get(uid);
        if (modes == null) {
            modes = new SparseIntArray();
            uidModes.put(uid, modes);
        }

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                final int code = parser.getAttributeInt(null, "n");
                final int mode = parser.getAttributeInt(null, "m");

                if (mode != opToDefaultMode(code)) {
                    modes.put(code, mode);
                }
            } else {
                Slog.w(TAG, "Unknown element under <uid>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readUser(TypedXmlPullParser parser,
            SparseArray<ArrayMap<String, SparseIntArray>> userPackageModes)
            throws NumberFormatException, XmlPullParserException, IOException {
        int userId = parser.getAttributeInt(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("pkg")) {
                readPackageOp(parser, userId, userPackageModes);
            } else {
                Slog.w(TAG, "Unknown element under <user>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    // read package tag refactored in Android U
    private void readPackageOp(TypedXmlPullParser parser, int userId,
            SparseArray<ArrayMap<String, SparseIntArray>> userPackageModes)
            throws NumberFormatException, XmlPullParserException, IOException {
        String pkgName = parser.getAttributeValue(null, "n");
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals("op")) {
                readOp(parser, userId, pkgName, userPackageModes);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readOp(TypedXmlPullParser parser, int userId, @NonNull String pkgName,
            SparseArray<ArrayMap<String, SparseIntArray>> userPackageModes)
            throws NumberFormatException, XmlPullParserException {
        final int opCode = parser.getAttributeInt(null, "n");
        final int defaultMode = AppOpsManager.opToDefaultMode(opCode);
        final int mode = parser.getAttributeInt(null, "m", defaultMode);

        if (mode != defaultMode) {
            ArrayMap<String, SparseIntArray> packageModes = userPackageModes.get(userId);
            if (packageModes == null) {
                packageModes = new ArrayMap<>();
                userPackageModes.put(userId, packageModes);
            }

            SparseIntArray modes = packageModes.get(pkgName);
            if (modes == null) {
                modes = new SparseIntArray();
                packageModes.put(pkgName, modes);
            }

            modes.put(opCode, mode);
        }
    }
}
