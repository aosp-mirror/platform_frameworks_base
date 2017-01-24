/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.internal.logging.legacy;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;


import android.metrics.LogMaker;

public class SysuiMultiActionParserTest extends ParserTest {

    public SysuiMultiActionParserTest() {
        mParser = new SysuiMultiActionParser();
    }

    public void testParseAllFields() {
        int category = 10;
        int type = 11;
        int subtype = 12;
        long timestamp = 1484669007890L;
        String packageName = "com.foo.bar";
        String counterName = "sheep";
        int bucket = 13;
        int value = 14;
        LogMaker builder = new LogMaker(category);
        builder.setType(type);
        builder.setSubtype(subtype);
        builder.setPackageName(packageName);
        builder.setCounterName(counterName);
        builder.setCounterBucket(bucket);
        builder.setCounterValue(value);
        builder.addTaggedData(1, "one");
        builder.addTaggedData(2, "two");
        Object[] out = builder.serialize();
        int t = 1000;

        mParser.parseEvent(mLogger, timestamp, out);

        verify(mLogger, times(1)).addEvent(mProtoCaptor.capture());

        LogMaker proto = mProtoCaptor.getValue();
        assertEquals(category, proto.getCategory());
        assertEquals(type, proto.getType());
        assertEquals(subtype, proto.getSubtype());
        assertEquals(timestamp, proto.getTimestamp());
        assertEquals(packageName, proto.getPackageName());
        assertEquals(counterName, proto.getCounterName());
        assertEquals(bucket, proto.getCounterBucket());
        assertEquals(value, proto.getCounterValue());
        assertEquals("one", proto.getTaggedData(1));
        assertEquals("two", proto.getTaggedData(2));
    }
}
