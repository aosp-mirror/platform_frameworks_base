/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.firewall;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.os.Process;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

class SenderFilter {
    private static final String ATTR_TYPE = "type";

    private static final String VAL_SIGNATURE = "signature";
    private static final String VAL_SYSTEM = "system";
    private static final String VAL_SYSTEM_OR_SIGNATURE = "system|signature";
    private static final String VAL_USER_ID = "userId";

    static boolean isSystemApp(ApplicationInfo callerApp, int callerUid, int callerPid) {
        if (callerUid == Process.SYSTEM_UID ||
                callerPid == Process.myPid()) {
            return true;
        }
        if (callerApp == null) {
            return false;
        }
        return (callerApp.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    public static final FilterFactory FACTORY = new FilterFactory("sender") {
        @Override
        public Filter newFilter(XmlPullParser parser) throws IOException, XmlPullParserException {
            String typeString = parser.getAttributeValue(null, ATTR_TYPE);
            if (typeString == null) {
                throw new XmlPullParserException("type attribute must be specified for <sender>",
                        parser, null);
            }
            if (typeString.equals(VAL_SYSTEM)) {
                return SYSTEM;
            } else if (typeString.equals(VAL_SIGNATURE)) {
                return SIGNATURE;
            } else if (typeString.equals(VAL_SYSTEM_OR_SIGNATURE)) {
                return SYSTEM_OR_SIGNATURE;
            } else if (typeString.equals(VAL_USER_ID)) {
                return USER_ID;
            }
            throw new XmlPullParserException(
                    "Invalid type attribute for <sender>: " + typeString, parser, null);
        }
    };

    private static final Filter SIGNATURE = new Filter() {
        @Override
        public boolean matches(IntentFirewall ifw, Intent intent, ApplicationInfo callerApp,
                int callerUid, int callerPid, String resolvedType, ApplicationInfo resolvedApp) {
            if (callerApp == null) {
                return false;
            }
            return ifw.signaturesMatch(callerUid, resolvedApp.uid);
        }
    };

    private static final Filter SYSTEM = new Filter() {
        @Override
        public boolean matches(IntentFirewall ifw, Intent intent, ApplicationInfo callerApp,
                int callerUid, int callerPid, String resolvedType, ApplicationInfo resolvedApp) {
            if (callerApp == null) {
                // if callerApp is null, the caller is the system process
                return false;
            }
            return isSystemApp(callerApp, callerUid, callerPid);
        }
    };

    private static final Filter SYSTEM_OR_SIGNATURE = new Filter() {
        @Override
        public boolean matches(IntentFirewall ifw, Intent intent, ApplicationInfo callerApp,
                int callerUid, int callerPid, String resolvedType, ApplicationInfo resolvedApp) {
            return isSystemApp(callerApp, callerUid, callerPid) ||
                    ifw.signaturesMatch(callerUid, resolvedApp.uid);
        }
    };

    private static final Filter USER_ID = new Filter() {
        @Override
        public boolean matches(IntentFirewall ifw, Intent intent, ApplicationInfo callerApp,
                int callerUid, int callerPid, String resolvedType, ApplicationInfo resolvedApp) {
            // This checks whether the caller is either the system process, or has the same user id
            // I.e. the same app, or an app that uses the same shared user id.
            // This is the same set of applications that would be able to access the component if
            // it wasn't exported.
            return ifw.checkComponentPermission(null, callerPid, callerUid, resolvedApp.uid, false);
        }
    };
}
