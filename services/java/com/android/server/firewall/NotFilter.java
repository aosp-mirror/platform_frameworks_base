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
import com.android.internal.util.XmlUtils;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

class NotFilter implements Filter {
    private final Filter mChild;

    private NotFilter(Filter child) {
        mChild = child;
    }

    @Override
    public boolean matches(IntentFirewall ifw, ComponentName resolvedComponent, Intent intent,
            int callerUid, int callerPid, String resolvedType, int receivingUid) {
        return !mChild.matches(ifw, resolvedComponent, intent, callerUid, callerPid, resolvedType,
                receivingUid);
    }

    public static final FilterFactory FACTORY = new FilterFactory("not") {
        @Override
        public Filter newFilter(XmlPullParser parser)
                throws IOException, XmlPullParserException {
            Filter child = null;
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                Filter filter = IntentFirewall.parseFilter(parser);
                if (child == null) {
                    child = filter;
                } else {
                    throw new XmlPullParserException(
                            "<not> tag can only contain a single child filter.", parser, null);
                }
            }
            return new NotFilter(child);
        }
    };
}
