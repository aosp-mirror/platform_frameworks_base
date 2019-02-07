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

package com.android.server.wm.utils;

import android.util.Log;

import org.mockito.Mockito;
import org.mockito.MockitoFramework;
import org.mockito.listeners.MockCreationListener;
import org.mockito.mock.MockCreationSettings;

import java.util.IdentityHashMap;

/**
 * An util class used to track mock creation, and reset them when closing. Note only one instance is
 * allowed at anytime, as Mockito framework throws exception if there is already a listener of the
 * same type registered.
 */
public class MockTracker implements MockCreationListener, AutoCloseable {
    private static final String TAG = "MockTracker";

    private final MockitoFramework mMockitoFramework = Mockito.framework();

    private final IdentityHashMap<Object, Void> mMocks = new IdentityHashMap<>();

    public MockTracker() {
        mMockitoFramework.addListener(this);
    }

    @Override
    public void onMockCreated(Object mock, MockCreationSettings settings) {
        mMocks.put(mock, null);
    }

    @Override
    public void close() {
        mMockitoFramework.removeListener(this);

        for (final Object mock : mMocks.keySet()) {
            try {
                Mockito.reset(mock);
            } catch (Exception e) {
                Log.e(TAG, "Failed to reset " + mock, e);
            }
        }
        mMocks.clear();
    }
}
