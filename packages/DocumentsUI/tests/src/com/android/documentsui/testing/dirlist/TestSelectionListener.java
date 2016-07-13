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

package com.android.documentsui.testing.dirlist;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.documentsui.dirlist.MultiSelectManager;

import java.util.HashSet;
import java.util.Set;

public final class TestSelectionListener implements MultiSelectManager.Callback {

    Set<String> ignored = new HashSet<>();
    private boolean mSelectionChanged = false;

    @Override
    public void onItemStateChanged(String modelId, boolean selected) {}

    @Override
    public boolean onBeforeItemStateChange(String modelId, boolean selected) {
        return !ignored.contains(modelId);
    }

    @Override
    public void onSelectionChanged() {
        mSelectionChanged = true;
    }

    public void reset() {
        mSelectionChanged = false;
    }

    public void assertSelectionChanged() {
        assertTrue(mSelectionChanged);
    }

    public void assertSelectionUnchanged() {
        assertFalse(mSelectionChanged);
    }
}