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

    private static final DocumentInfo DIR_1;
    private static final DocumentInfo DIR_2;

    private State mState;

    static {
        DIR_1 = new DocumentInfo();
        DIR_1.displayName = "firstDirectory";
        DIR_2 = new DocumentInfo();
        DIR_2.displayName = "secondDirectory";
    }

    @Override
    protected void setUp() throws Exception {
        mState = new State();
    }

    public void testInitialStateEmpty() {
        assertFalse(mState.hasLocationChanged());
    }

    public void testPushDocument_ChangesLocation() {
        mState.pushDocument(DIR_1);
        mState.pushDocument(DIR_2);
        assertTrue(mState.hasLocationChanged());
    }

    public void testPushDocument_ModifiesStack() {
        mState.pushDocument(DIR_1);
        mState.pushDocument(DIR_2);
        assertEquals(DIR_2, mState.stack.getFirst());
    }

    public void testPopDocument_ModifiesStack() {
        mState.pushDocument(DIR_1);
        mState.pushDocument(DIR_2);
        mState.popDocument();
        assertEquals(DIR_1, mState.stack.getFirst());
    }
}
