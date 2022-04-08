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

import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;
import android.content.IntentFilter;
import android.util.Log;
import java.io.IOException;
import android.os.UserHandle;

/**
 * The {@link PackageManagerService} maintains some {@link CrossProfileIntentFilter}s for each user.
 * If an {@link Intent} matches the {@link CrossProfileIntentFilter}, then activities in the user
 * {@link #mTargetUserId} can access it.
 */
class CrossProfileIntentFilter extends IntentFilter {
    private static final String ATTR_TARGET_USER_ID = "targetUserId";
    private static final String ATTR_FLAGS = "flags";
    private static final String ATTR_OWNER_PACKAGE = "ownerPackage";
    private static final String ATTR_FILTER = "filter";

    private static final String TAG = "CrossProfileIntentFilter";

    // If the intent matches the IntentFilter, then it can be forwarded to this userId.
    final int mTargetUserId;
    final String mOwnerPackage; // packageName of the app.
    final int mFlags;

    CrossProfileIntentFilter(IntentFilter filter, String ownerPackage, int targetUserId,
            int flags) {
        super(filter);
        mTargetUserId = targetUserId;
        mOwnerPackage = ownerPackage;
        mFlags = flags;
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

    CrossProfileIntentFilter(XmlPullParser parser) throws XmlPullParserException, IOException {
        mTargetUserId = getIntFromXml(parser, ATTR_TARGET_USER_ID, UserHandle.USER_NULL);
        mOwnerPackage = getStringFromXml(parser, ATTR_OWNER_PACKAGE, "");
        mFlags = getIntFromXml(parser, ATTR_FLAGS, 0);

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
            readFromXml(parser);
        } else {
            String msg = "Missing element under " + TAG + ": " + ATTR_FILTER +
                    " at " + parser.getPositionDescription();
            PackageManagerService.reportSettingsProblem(Log.WARN, msg);
            XmlUtils.skipCurrentTag(parser);
        }
    }

    String getStringFromXml(XmlPullParser parser, String attribute, String defaultValue) {
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

    int getIntFromXml(XmlPullParser parser, String attribute, int defaultValue) {
        String stringValue = getStringFromXml(parser, attribute, null);
        if (stringValue != null) {
            return Integer.parseInt(stringValue);
        }
        return defaultValue;
    }

    public void writeToXml(XmlSerializer serializer) throws IOException {
        serializer.attribute(null, ATTR_TARGET_USER_ID, Integer.toString(mTargetUserId));
        serializer.attribute(null, ATTR_FLAGS, Integer.toString(mFlags));
        serializer.attribute(null, ATTR_OWNER_PACKAGE, mOwnerPackage);
        serializer.startTag(null, ATTR_FILTER);
            super.writeToXml(serializer);
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
}
