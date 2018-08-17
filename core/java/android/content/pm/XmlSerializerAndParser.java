/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.content.pm;

import org.xmlpull.v1.XmlSerializer;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.UnsupportedAppUsage;
import java.io.IOException;

/** @hide */
public interface XmlSerializerAndParser<T> {
    @UnsupportedAppUsage
    void writeAsXml(T item, XmlSerializer out) throws IOException;
    @UnsupportedAppUsage
    T createFromXml(XmlPullParser parser) throws IOException, XmlPullParserException;
}
