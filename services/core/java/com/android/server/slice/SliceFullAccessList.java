/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.server.slice;

import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.util.List;

public class SliceFullAccessList {

    static final int DB_VERSION = 1;
    private static final String TAG = "SliceFullAccessList";

    private static final String TAG_LIST = "slice-access-list";
    private static final String TAG_PKG = "pkg";
    private static final String TAG_USER = "user";

    private final String ATT_USER_ID = "user";
    private final String ATT_VERSION = "version";

    private final SparseArray<ArraySet<String>> mFullAccessPkgs = new SparseArray<>();
    private final Context mContext;

    public SliceFullAccessList(Context context) {
        mContext = context;
    }

    public boolean hasFullAccess(String pkg, int userId) {
        ArraySet<String> pkgs = mFullAccessPkgs.get(userId, null);
        return pkgs != null && pkgs.contains(pkg);
    }

    public void grantFullAccess(String pkg, int userId) {
        ArraySet<String> pkgs = mFullAccessPkgs.get(userId, null);
        if (pkgs == null) {
            pkgs = new ArraySet<>();
            mFullAccessPkgs.put(userId, pkgs);
        }
        pkgs.add(pkg);
    }

    public void removeGrant(String pkg, int userId) {
        ArraySet<String> pkgs = mFullAccessPkgs.get(userId, null);
        if (pkgs == null) {
            pkgs = new ArraySet<>();
            mFullAccessPkgs.put(userId, pkgs);
        }
        pkgs.remove(pkg);
    }

    public void writeXml(XmlSerializer out, int user) throws IOException {
        out.startTag(null, TAG_LIST);
        out.attribute(null, ATT_VERSION, String.valueOf(DB_VERSION));

        final int N = mFullAccessPkgs.size();
        for (int i = 0 ; i < N; i++) {
            final int userId = mFullAccessPkgs.keyAt(i);
            final ArraySet<String> pkgs = mFullAccessPkgs.valueAt(i);
            if (user != UserHandle.USER_ALL && user != userId) {
                continue;
            }
            out.startTag(null, TAG_USER);
            out.attribute(null, ATT_USER_ID, Integer.toString(userId));
            if (pkgs != null) {
                final int M = pkgs.size();
                for (int j = 0; j < M; j++) {
                        out.startTag(null, TAG_PKG);
                        out.text(pkgs.valueAt(j));
                        out.endTag(null, TAG_PKG);
                }
            }
            out.endTag(null, TAG_USER);
        }
        out.endTag(null, TAG_LIST);
    }

    public void readXml(XmlPullParser parser) throws XmlPullParserException, IOException {
        // upgrade xml
        int xmlVersion = XmlUtils.readIntAttribute(parser, ATT_VERSION, 0);
        final List<UserInfo> activeUsers = UserManager.get(mContext).getAliveUsers();
        for (UserInfo userInfo : activeUsers) {
            upgradeXml(xmlVersion, userInfo.getUserHandle().getIdentifier());
        }

        mFullAccessPkgs.clear();
        // read grants
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            if (type == XmlPullParser.END_TAG
                    && TAG_LIST.equals(tag)) {
                break;
            }
            if (type == XmlPullParser.START_TAG) {
                if (TAG_USER.equals(tag)) {
                    final int userId = XmlUtils.readIntAttribute(parser, ATT_USER_ID, 0);
                    ArraySet<String> pkgs = new ArraySet<>();
                    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT) {
                        String userTag = parser.getName();
                        if (type == XmlPullParser.END_TAG
                                && TAG_USER.equals(userTag)) {
                            break;
                        }
                        if (type == XmlPullParser.START_TAG) {
                            if (TAG_PKG.equals(userTag)) {
                                final String pkg = parser.nextText();
                                pkgs.add(pkg);
                            }
                        }
                    }
                    mFullAccessPkgs.put(userId, pkgs);
                }
            }
        }
    }

    protected void upgradeXml(final int xmlVersion, final int userId) {}
}
