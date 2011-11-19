/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server;

import static com.android.server.NativeDaemonConnector.appendEscaped;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;

/**
 * Tests for {@link NativeDaemonConnector}.
 */
@MediumTest
public class NativeDaemonConnectorTest extends AndroidTestCase {
    private static final String TAG = "NativeDaemonConnectorTest";

    public void testArgumentNormal() throws Exception {
        final StringBuilder builder = new StringBuilder();

        builder.setLength(0);
        appendEscaped(builder, "");
        assertEquals("", builder.toString());

        builder.setLength(0);
        appendEscaped(builder, "foo");
        assertEquals("foo", builder.toString());

        builder.setLength(0);
        appendEscaped(builder, "foo\"bar");
        assertEquals("foo\\\"bar", builder.toString());

        builder.setLength(0);
        appendEscaped(builder, "foo\\bar\\\"baz");
        assertEquals("foo\\\\bar\\\\\\\"baz", builder.toString());
    }

    public void testArgumentWithSpaces() throws Exception {
        final StringBuilder builder = new StringBuilder();

        builder.setLength(0);
        appendEscaped(builder, "foo bar");
        assertEquals("\"foo bar\"", builder.toString());

        builder.setLength(0);
        appendEscaped(builder, "foo\"bar\\baz foo");
        assertEquals("\"foo\\\"bar\\\\baz foo\"", builder.toString());
    }

    public void testArgumentWithUtf() throws Exception {
        final StringBuilder builder = new StringBuilder();

        builder.setLength(0);
        appendEscaped(builder, "caf\u00E9 c\u00F6ffee");
        assertEquals("\"caf\u00E9 c\u00F6ffee\"", builder.toString());
    }
}
