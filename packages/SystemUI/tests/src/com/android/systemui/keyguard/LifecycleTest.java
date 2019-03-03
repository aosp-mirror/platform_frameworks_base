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

package com.android.systemui.keyguard;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class LifecycleTest extends SysuiTestCase {

    private final Object mObj1 = new Object();
    private final Object mObj2 = new Object();

    private Lifecycle<Object> mLifecycle;
    private ArrayList<Object> mDispatchedObjects;

    @Before
    public void setUp() throws Exception {
        mLifecycle = new Lifecycle<>();
        mDispatchedObjects = new ArrayList<>();
    }

    @Test
    public void addObserver_addsObserver() throws Exception {
        mLifecycle.addObserver(mObj1);

        mLifecycle.dispatch(mDispatchedObjects::add);

        assertTrue(mDispatchedObjects.contains(mObj1));
    }

    @Test
    public void removeObserver() throws Exception {
        mLifecycle.addObserver(mObj1);
        mLifecycle.removeObserver(mObj1);

        mLifecycle.dispatch(mDispatchedObjects::add);

        assertFalse(mDispatchedObjects.contains(mObj1));
    }

    @Test
    public void dispatch() throws Exception {
        mLifecycle.addObserver(mObj1);
        mLifecycle.addObserver(mObj2);

        mLifecycle.dispatch(mDispatchedObjects::add);

        assertTrue(mDispatchedObjects.contains(mObj1));
        assertTrue(mDispatchedObjects.contains(mObj2));
    }

}