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

package com.android.internal.util;

import junit.framework.TestCase;

public final class HexDumpTest extends TestCase {
    public void testBytesToHexString() {
        assertEquals("abcdef", HexDump.toHexString(
                new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef }, false));
        assertEquals("ABCDEF", HexDump.toHexString(
                new byte[] { (byte) 0xab, (byte) 0xcd, (byte) 0xef }, true));
    }
}
