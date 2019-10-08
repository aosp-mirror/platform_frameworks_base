/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.internal.util;

import junit.framework.TestCase;

/**
 * Tests for {@link MimeIconUtils}.
 */
public class MimeIconUtilsTest extends TestCase {
    public void testSimple() throws Exception {
        assertEquals("PNG image",
                MimeIconUtils.getTypeInfo("image/png").getLabel());
        assertEquals("Image",
                MimeIconUtils.getTypeInfo("image/x-custom").getLabel());

        assertEquals("ALC file",
                MimeIconUtils.getTypeInfo("chemical/x-alchemy").getLabel());
        assertEquals("File",
                MimeIconUtils.getTypeInfo("x-custom/x-custom").getLabel());

        assertEquals("Folder",
                MimeIconUtils.getTypeInfo("inode/directory").getLabel());

        assertEquals("ZIP archive",
                MimeIconUtils.getTypeInfo("application/zip").getLabel());
        assertEquals("RAR archive",
                MimeIconUtils.getTypeInfo("application/rar").getLabel());

        assertEquals("TXT document",
                MimeIconUtils.getTypeInfo("text/plain").getLabel());
        assertEquals("Document",
                MimeIconUtils.getTypeInfo("text/x-custom").getLabel());

        assertEquals("FLAC audio",
                MimeIconUtils.getTypeInfo("audio/flac").getLabel());
        assertEquals("FLAC audio",
                MimeIconUtils.getTypeInfo("application/x-flac").getLabel());
    }
}
