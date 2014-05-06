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
 * The {@link PackageManagerService} maintains some {@link ForwardingIntentFilter}s for every user.
 * If an {@link Intent} matches the {@link ForwardingIntentFilter}, then it can be forwarded to the
 * {@link #mUserIdDest}.
 */
class ForwardingIntentFilter extends IntentFilter {
    private static final String ATTR_USER_ID_DEST = "userIdDest";
    private static final String ATTR_REMOVABLE = "removable";
    private static final String ATTR_FILTER = "filter";

    private static final String TAG = "ForwardingIntentFilter";

    // If the intent matches the IntentFilter, then it can be forwarded to this userId.
    final int mUserIdDest;
    boolean mRemovable;

    ForwardingIntentFilter(IntentFilter filter, boolean removable, int userIdDest) {
        super(filter);
        mUserIdDest = userIdDest;
        mRemovable = removable;
    }

    public int getUserIdDest() {
        return mUserIdDest;
    }

    public boolean isRemovable() {
        return mRemovable;
    }

    ForwardingIntentFilter(XmlPullParser parser) throws XmlPullParserException, IOException {
        String userIdDestString = parser.getAttributeValue(null, ATTR_USER_ID_DEST);
        if (userIdDestString == null) {
            String msg = "Missing element under " + TAG +": " + ATTR_USER_ID_DEST + " at " +
                    parser.getPositionDescription();
            PackageManagerService.reportSettingsProblem(Log.WARN, msg);
            mUserIdDest = UserHandle.USER_NULL;
        } else {
            mUserIdDest = Integer.parseInt(userIdDestString);
        }
        String removableString = parser.getAttributeValue(null, ATTR_REMOVABLE);
        if (removableString != null) {
            mRemovable = Boolean.parseBoolean(removableString);
        }
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
                    String msg = "Unknown element under " + Settings.TAG_FORWARDING_INTENT_FILTERS
                            + ": " + tagName + " at " + parser.getPositionDescription();
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

    public void writeToXml(XmlSerializer serializer) throws IOException {
        serializer.attribute(null, ATTR_USER_ID_DEST, Integer.toString(mUserIdDest));
        serializer.attribute(null, ATTR_REMOVABLE, Boolean.toString(mRemovable));
        serializer.startTag(null, ATTR_FILTER);
            super.writeToXml(serializer);
        serializer.endTag(null, ATTR_FILTER);
    }

    @Override
    public String toString() {
        return "ForwardingIntentFilter{0x" + Integer.toHexString(System.identityHashCode(this))
                + " " + Integer.toString(mUserIdDest) + "}";
    }
}
