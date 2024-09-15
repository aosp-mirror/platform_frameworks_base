/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.app.AppOpsManager.extractFlagsFromKey;
import static android.app.AppOpsManager.extractUidStateFromKey;
import static android.companion.virtual.VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.companion.virtual.VirtualDeviceManager;
import android.os.Process;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseArray;
import android.util.Xml;

import com.android.internal.util.XmlUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * This class manages the read/write of AppOp recent accesses between memory and disk.
 */
final class AppOpsRecentAccessPersistence {
    static final String TAG = "AppOpsRecentAccessPersistence";
    final AtomicFile mRecentAccessesFile;
    final AppOpsService mAppOpsService;

    private static final String TAG_APP_OPS = "app-ops";
    private static final String TAG_PACKAGE = "pkg";
    private static final String TAG_UID = "uid";
    private static final String TAG_OP = "op";
    private static final String TAG_ATTRIBUTION_OP = "st";

    private static final String ATTR_NAME = "n";
    private static final String ATTR_ID = "id";
    private static final String ATTR_DEVICE_ID = "dv";
    private static final String ATTR_ACCESS_TIME = "t";
    private static final String ATTR_REJECT_TIME = "r";
    private static final String ATTR_ACCESS_DURATION = "d";
    private static final String ATTR_PROXY_PACKAGE = "pp";
    private static final String ATTR_PROXY_UID = "pu";
    private static final String ATTR_PROXY_ATTRIBUTION_TAG = "pc";
    private static final String ATTR_PROXY_DEVICE_ID = "pdv";

    /**
     * Version of the mRecentAccessesFile.
     * Increment by one every time an upgrade step is added at boot, none currently exists.
     */
    private static final int CURRENT_VERSION = 1;

    AppOpsRecentAccessPersistence(
            @NonNull AtomicFile recentAccessesFile, @NonNull AppOpsService appOpsService) {
        mRecentAccessesFile = recentAccessesFile;
        mAppOpsService = appOpsService;
    }

    /**
     * Load AppOp recent access data from disk into uidStates. The target uidStates will first clear
     * itself before loading.
     *
     * @param uidStates The in-memory object where you want to populate data from disk
     */
    void readRecentAccesses(@NonNull SparseArray<AppOpsService.UidState> uidStates) {
        synchronized (mRecentAccessesFile) {
            FileInputStream stream;
            try {
                stream = mRecentAccessesFile.openRead();
            } catch (FileNotFoundException e) {
                Slog.i(
                        TAG,
                        "No existing app ops "
                                + mRecentAccessesFile.getBaseFile()
                                + "; starting empty");
                return;
            }
            boolean success = false;
            uidStates.clear();
            mAppOpsService.mAppOpsCheckingService.clearAllModes();
            try {
                TypedXmlPullParser parser = Xml.resolvePullParser(stream);
                int type;
                while ((type = parser.next()) != XmlPullParser.START_TAG
                        && type != XmlPullParser.END_DOCUMENT) {
                    // Parse next until we reach the start or end
                }

                if (type != XmlPullParser.START_TAG) {
                    throw new IllegalStateException("no start tag found");
                }

                int outerDepth = parser.getDepth();
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }

                    String tagName = parser.getName();
                    if (tagName.equals(TAG_PACKAGE)) {
                        readPackage(parser, uidStates);
                    } else if (tagName.equals(TAG_UID)) {
                        // uid tag may be present during migration, don't print warning.
                        XmlUtils.skipCurrentTag(parser);
                    } else {
                        Slog.w(TAG, "Unknown element under <app-ops>: " + parser.getName());
                        XmlUtils.skipCurrentTag(parser);
                    }
                }

                success = true;
            } catch (IllegalStateException | NullPointerException | NumberFormatException
                     | XmlPullParserException | IOException | IndexOutOfBoundsException e) {
                Slog.w(TAG, "Failed parsing " + e);
            } finally {
                if (!success) {
                    uidStates.clear();
                    mAppOpsService.mAppOpsCheckingService.clearAllModes();
                }
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void readPackage(
            TypedXmlPullParser parser, SparseArray<AppOpsService.UidState> uidStates)
            throws NumberFormatException, XmlPullParserException, IOException {
        String pkgName = parser.getAttributeValue(null, ATTR_NAME);
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_UID)) {
                readUid(parser, pkgName, uidStates);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readUid(TypedXmlPullParser parser, @NonNull String pkgName,
            SparseArray<AppOpsService.UidState> uidStates)
            throws NumberFormatException, XmlPullParserException, IOException {
        int uid = parser.getAttributeInt(null, ATTR_NAME);
        final AppOpsService.UidState uidState = mAppOpsService.new UidState(uid);
        uidStates.put(uid, uidState);

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals(TAG_OP)) {
                readOp(parser, uidState, pkgName);
            } else {
                Slog.w(TAG, "Unknown element under <pkg>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }
    }

    private void readOp(TypedXmlPullParser parser,
            @NonNull AppOpsService.UidState uidState, @NonNull String pkgName)
            throws NumberFormatException, XmlPullParserException, IOException {
        int opCode = parser.getAttributeInt(null, ATTR_NAME);
        AppOpsService.Op op = mAppOpsService.new Op(uidState, pkgName, opCode, uidState.uid);

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals(TAG_ATTRIBUTION_OP)) {
                readAttributionOp(parser, op, XmlUtils.readStringAttribute(parser, ATTR_ID));
            } else {
                Slog.w(TAG, "Unknown element under <op>: "
                        + parser.getName());
                XmlUtils.skipCurrentTag(parser);
            }
        }

        AppOpsService.Ops ops = uidState.pkgOps.get(pkgName);
        if (ops == null) {
            ops = new AppOpsService.Ops(pkgName, uidState);
            uidState.pkgOps.put(pkgName, ops);
        }
        ops.put(op.op, op);
    }

    private void readAttributionOp(TypedXmlPullParser parser, @NonNull AppOpsService.Op parent,
            @Nullable String attribution)
            throws NumberFormatException, IOException, XmlPullParserException {
        final long key = parser.getAttributeLong(null, ATTR_NAME);
        final int uidState = extractUidStateFromKey(key);
        final int opFlags = extractFlagsFromKey(key);

        String deviceId = parser.getAttributeValue(null, ATTR_DEVICE_ID);
        final long accessTime = parser.getAttributeLong(null, ATTR_ACCESS_TIME, 0);
        final long rejectTime = parser.getAttributeLong(null, ATTR_REJECT_TIME, 0);
        final long accessDuration = parser.getAttributeLong(null, ATTR_ACCESS_DURATION, -1);
        final String proxyPkg = XmlUtils.readStringAttribute(parser, ATTR_PROXY_PACKAGE);
        final int proxyUid = parser.getAttributeInt(null, ATTR_PROXY_UID, Process.INVALID_UID);
        final String proxyAttributionTag =
                XmlUtils.readStringAttribute(parser, ATTR_PROXY_ATTRIBUTION_TAG);
        final String proxyDeviceId = parser.getAttributeValue(null, ATTR_PROXY_DEVICE_ID);

        if (deviceId == null || Objects.equals(deviceId, "")) {
            deviceId = PERSISTENT_DEVICE_ID_DEFAULT;
        }

        AttributedOp attributedOp = parent.getOrCreateAttribution(parent, attribution, deviceId);

        if (accessTime > 0) {
            attributedOp.accessed(accessTime, accessDuration, proxyUid, proxyPkg,
                    proxyAttributionTag, proxyDeviceId, uidState, opFlags);
        }
        if (rejectTime > 0) {
            attributedOp.rejected(rejectTime, uidState, opFlags);
        }
    }

    /**
     * Write uidStates into an XML file on the disk. It's a complete dump from memory, the XML file
     * will be re-written.
     *
     * @param uidStates The in-memory object that holds all AppOp recent access data.
     */
    void writeRecentAccesses(SparseArray<AppOpsService.UidState> uidStates) {
        synchronized (mRecentAccessesFile) {
            FileOutputStream stream;
            try {
                stream = mRecentAccessesFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state: " + e);
                return;
            }

            try {
                TypedXmlSerializer out = Xml.resolveSerializer(stream);
                out.startDocument(null, true);
                out.startTag(null, TAG_APP_OPS);
                out.attributeInt(null, "v", CURRENT_VERSION);

                for (int uidIndex = 0; uidIndex < uidStates.size(); uidIndex++) {
                    AppOpsService.UidState uidState = uidStates.valueAt(uidIndex);
                    int uid = uidState.uid;

                    for (int pkgIndex = 0; pkgIndex < uidState.pkgOps.size(); pkgIndex++) {
                        String packageName = uidState.pkgOps.keyAt(pkgIndex);
                        AppOpsService.Ops ops = uidState.pkgOps.valueAt(pkgIndex);

                        out.startTag(null, TAG_PACKAGE);
                        out.attribute(null, ATTR_NAME, packageName);
                        out.startTag(null, TAG_UID);
                        out.attributeInt(null, ATTR_NAME, uid);

                        for (int opIndex = 0; opIndex < ops.size(); opIndex++) {
                            AppOpsService.Op op = ops.valueAt(opIndex);

                            out.startTag(null, TAG_OP);
                            out.attributeInt(null, ATTR_NAME, op.op);

                            writeDeviceAttributedOps(out, op);

                            out.endTag(null, TAG_OP);
                        }

                        out.endTag(null, TAG_UID);
                        out.endTag(null, TAG_PACKAGE);
                    }
                }

                out.endTag(null, TAG_APP_OPS);
                out.endDocument();
                mRecentAccessesFile.finishWrite(stream);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to write state, restoring backup.", e);
                mRecentAccessesFile.failWrite(stream);
            }
        }
    }

    private void writeDeviceAttributedOps(TypedXmlSerializer out, AppOpsService.Op op)
            throws IOException {
        for (String deviceId : op.mDeviceAttributedOps.keySet()) {
            ArrayMap<String, AttributedOp> attributedOps =
                    op.mDeviceAttributedOps.get(deviceId);

            for (int attrIndex = 0; attrIndex < attributedOps.size(); attrIndex++) {
                String attributionTag = attributedOps.keyAt(attrIndex);
                AppOpsManager.AttributedOpEntry attributedOpEntry =
                        attributedOps.valueAt(attrIndex).createAttributedOpEntryLocked();

                final ArraySet<Long> keys = attributedOpEntry.collectKeys();
                for (int k = 0; k < keys.size(); k++) {
                    final long key = keys.valueAt(k);

                    final int uidState = AppOpsManager.extractUidStateFromKey(key);
                    final int flags = AppOpsManager.extractFlagsFromKey(key);

                    final long accessTime =
                            attributedOpEntry.getLastAccessTime(uidState, uidState, flags);
                    final long rejectTime =
                            attributedOpEntry.getLastRejectTime(uidState, uidState, flags);
                    final long accessDuration =
                            attributedOpEntry.getLastDuration(uidState, uidState, flags);

                    // Proxy information for rejections is not backed up
                    final AppOpsManager.OpEventProxyInfo proxy =
                            attributedOpEntry.getLastProxyInfo(uidState, uidState, flags);

                    if (accessTime <= 0 && rejectTime <= 0 && accessDuration <= 0
                            && proxy == null) {
                        continue;
                    }

                    out.startTag(null, TAG_ATTRIBUTION_OP);
                    if (attributionTag != null) {
                        out.attribute(null, ATTR_ID, attributionTag);
                    }
                    out.attributeLong(null, ATTR_NAME, key);

                    if (!Objects.equals(
                            deviceId, VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT)) {
                        out.attribute(null, ATTR_DEVICE_ID, deviceId);
                    }
                    if (accessTime > 0) {
                        out.attributeLong(null, ATTR_ACCESS_TIME, accessTime);
                    }
                    if (rejectTime > 0) {
                        out.attributeLong(null, ATTR_REJECT_TIME, rejectTime);
                    }
                    if (accessDuration > 0) {
                        out.attributeLong(null, ATTR_ACCESS_DURATION, accessDuration);
                    }
                    if (proxy != null) {
                        out.attributeInt(null, ATTR_PROXY_UID, proxy.getUid());

                        if (proxy.getPackageName() != null) {
                            out.attribute(null, ATTR_PROXY_PACKAGE, proxy.getPackageName());
                        }
                        if (proxy.getAttributionTag() != null) {
                            out.attribute(
                                    null, ATTR_PROXY_ATTRIBUTION_TAG, proxy.getAttributionTag());
                        }
                        if (proxy.getDeviceId() != null
                                && !Objects.equals(
                                        proxy.getDeviceId(),
                                        VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT)) {
                            out.attribute(null, ATTR_PROXY_DEVICE_ID, proxy.getDeviceId());
                        }
                    }

                    out.endTag(null, TAG_ATTRIBUTION_OP);
                }
            }
        }
    }
}
