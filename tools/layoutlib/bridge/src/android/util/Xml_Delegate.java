/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.util;

import com.android.layoutlib.bridge.impl.DelegateManager;
import com.android.layoutlib.bridge.impl.ParserFactory;
import com.android.tools.layoutlib.annotations.LayoutlibDelegate;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

/**
 * Delegate overriding some methods of android.util.Xml
 *
 * Through the layoutlib_create tool, the original methods of Xml have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * Because it's a stateless class to start with, there's no need to keep a {@link DelegateManager}
 * around to map int to instance of the delegate.
 */
public class Xml_Delegate {

    @LayoutlibDelegate
    /*package*/ static XmlPullParser newPullParser() {
        try {
            return ParserFactory.instantiateParser(null);
        } catch (XmlPullParserException e) {
            throw new AssertionError();
        }
    }
}
