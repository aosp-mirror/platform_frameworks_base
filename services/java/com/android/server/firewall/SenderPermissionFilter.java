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

import android.content.ComponentName;
import android.content.Intent;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

class SenderPermissionFilter implements Filter {
    private static final String ATTR_NAME = "name";

    private final String mPermission;

    private SenderPermissionFilter(String permission) {
        mPermission = permission;
    }

    @Override
    public boolean matches(IntentFirewall ifw, ComponentName resolvedComponent, Intent intent,
            int callerUid, int callerPid, String resolvedType, int receivingUid) {
        // We assume the component is exported here. If the component is not exported, then
        // ActivityManager would only resolve to this component for callers from the same uid.
        // In this case, it doesn't matter whether the component is exported or not.
        return ifw.checkComponentPermission(mPermission, callerPid, callerUid, receivingUid,
                true);
    }

    public static final FilterFactory FACTORY = new FilterFactory("sender-permission") {
        @Override
        public Filter newFilter(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            String permission = parser.getAttributeValue(null, ATTR_NAME);
            if (permission == null) {
                throw new XmlPullParserException("Permission name must be specified.",
                        parser, null);
            }
            return new SenderPermissionFilter(permission);
        }
    };
}
