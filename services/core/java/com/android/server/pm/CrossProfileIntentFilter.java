/*
 * Copyright 2014, The Android Open Source Project
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

import android.content.Intent;
import android.content.IntentFilter;
import android.os.UserHandle;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.util.XmlUtils;
import com.android.server.utils.SnapshotCache;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * The {@link PackageManagerService} maintains some {@link CrossProfileIntentFilter}s for each user.
 * If an {@link Intent} matches the {@link CrossProfileIntentFilter}, then activities in the user
 * {@link #mTargetUserId} can access it.
 */
class CrossProfileIntentFilter extends WatchedIntentFilter {
    private static final String ATTR_TARGET_USER_ID = "targetUserId";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_OWNER_PACKAGE = "ownerPackage";
    private static final String ATTR_FILTER = "filter";

    private static final String TAG = "CrossProfileIntentFilter";

    // If the intent matches the IntentFilter, then it can be forwarded to this userId.
    final int mTargetUserId;
    final String mOwnerPackage; // packageName of the app.
    final int mFlags;

    // The cache for snapshots, so they are not rebuilt if the base object has not
    // changed.
    final SnapshotCache<CrossProfileIntentFilter> mSnapshot;

    private SnapshotCache makeCache() {
        return new SnapshotCache<CrossProfileIntentFilter>(this, this) {
            @Override
            public CrossProfileIntentFilter createSnapshot() {
                CrossProfileIntentFilter s = new CrossProfileIntentFilter(mSource);
                s.seal();
                return s;
            }};
    }

    CrossProfileIntentFilter(IntentFilter filter, String ownerPackage, int targetUserId,
            int flags) {
        super(filter);
        mTargetUserId = targetUserId;
        mOwnerPackage = ownerPackage;
        mFlags = flags;
        mSnapshot = makeCache();
    }

    CrossProfileIntentFilter(WatchedIntentFilter filter, String ownerPackage, int targetUserId,
            int flags) {
        this(filter.mFilter, ownerPackage, targetUserId, flags);
    }

    // Copy constructor used only to create a snapshot.
    private CrossProfileIntentFilter(CrossProfileIntentFilter f) {
        super(f);
        mTargetUserId = f.mTargetUserId;
        mOwnerPackage = f.mOwnerPackage;
        mFlags = f.mFlags;
        mSnapshot = new SnapshotCache.Sealed();
    }

    public int getTargetUserId() {
        return mTargetUserId;
    }

    public int getFlags() {
        return mFlags;
    }

    public String getOwnerPackage() {
        return mOwnerPackage;
    }

    CrossProfileIntentFilter(TypedXmlPullParser parser) throws XmlPullParserException, IOException {
        mTargetUserId = parser.getAttributeInt(null, ATTR_TARGET_USER_ID, UserHandle.USER_NULL);
        mOwnerPackage = getStringFromXml(parser, ATTR_OWNER_PACKAGE, "");
        mFlags = parser.getAttributeInt(null, ATTR_FLAGS, 0);
        mSnapshot = makeCache();

        int outerDepth = parser.getDepth();
        String tagName = parser.getName();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            tagName = parser.getName();
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            } else if (type == XmlPullParser.START_TAG) {
                if (tagName.equals(ATTR_FILTER)) {
                    break;
                } else {
                    String msg = "Unknown element under "
                            + Settings.TAG_CROSS_PROFILE_INTENT_FILTERS + ": " + tagName + " at "
                            + parser.getPositionDescription();
                    PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                    XmlUtils.skipCurrentTag(parser);
                }
            }
        }
        if (tagName.equals(ATTR_FILTER)) {
            mFilter.readFromXml(parser);
        } else {
            String msg = "Missing element under " + TAG + ": " + ATTR_FILTER +
                    " at " + parser.getPositionDescription();
            PackageManagerService.reportSettingsProblem(Log.WARN, msg);
            XmlUtils.skipCurrentTag(parser);
        }
    }

    private String getStringFromXml(TypedXmlPullParser parser, String attribute,
            String defaultValue) {
        String value = parser.getAttributeValue(null, attribute);
        if (value == null) {
            String msg = "Missing element under " + TAG +": " + attribute + " at " +
                    parser.getPositionDescription();
            PackageManagerService.reportSettingsProblem(Log.WARN, msg);
            return defaultValue;
        } else {
            return value;
        }
    }

    public void writeToXml(TypedXmlSerializer serializer) throws IOException {
        serializer.attributeInt(null, ATTR_TARGET_USER_ID, mTargetUserId);
        serializer.attributeInt(null, ATTR_FLAGS, mFlags);
        serializer.attribute(null, ATTR_OWNER_PACKAGE, mOwnerPackage);
        serializer.startTag(null, ATTR_FILTER);
        mFilter.writeToXml(serializer);
        serializer.endTag(null, ATTR_FILTER);
    }

    @Override
    public String toString() {
        return "CrossProfileIntentFilter{0x" + Integer.toHexString(System.identityHashCode(this))
                + " " + Integer.toString(mTargetUserId) + "}";
    }

    boolean equalsIgnoreFilter(CrossProfileIntentFilter other) {
        return mTargetUserId == other.mTargetUserId
                && mOwnerPackage.equals(other.mOwnerPackage)
                && mFlags == other.mFlags;
    }

    public CrossProfileIntentFilter snapshot() {
        return mSnapshot.snapshot();
    }
}
