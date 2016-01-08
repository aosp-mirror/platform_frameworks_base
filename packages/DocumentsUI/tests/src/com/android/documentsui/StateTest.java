/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.documentsui;

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.documentsui.model.DocumentInfo;

@SmallTest
public class StateTest extends AndroidTestCase {
    public void testPushDocument() {
        final State state = new State();
        final DocumentInfo infoFirst = new DocumentInfo();
        infoFirst.displayName = "firstDirectory";
        final DocumentInfo infoSecond = new DocumentInfo();
        infoSecond.displayName = "secondDirectory";
        assertFalse(state.hasLocationChanged());
        state.pushDocument(infoFirst);
        state.pushDocument(infoSecond);
        assertTrue(state.hasLocationChanged());
        assertEquals("secondDirectory", state.stack.getFirst().displayName);
        state.popDocument();
        assertEquals("firstDirectory", state.stack.getFirst().displayName);
    }
}
