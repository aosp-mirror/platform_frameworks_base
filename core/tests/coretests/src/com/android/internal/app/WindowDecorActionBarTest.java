/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.app;

import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;

/**
 * Tests for {@link WindowDecorActionBar}.
 */
@SmallTest
public class WindowDecorActionBarTest
        extends ActivityInstrumentationTestCase2<WindowDecorActionBarTestActivity> {
    private WindowDecorActionBar mWindowDecorActionBar;
    private MockActionModeCallback mCallback;

    public WindowDecorActionBarTest() {
        super(WindowDecorActionBarTestActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mWindowDecorActionBar = (WindowDecorActionBar) getActivity().getActionBar();
        mCallback = new MockActionModeCallback();
    }

    @UiThreadTest
    public void testStartActionMode() {
        ActionMode mode = mWindowDecorActionBar.startActionMode(mCallback);

        assertNotNull(mode);
        assertTrue(mCallback.mIsCreateActionModeCalled);
    }

    @UiThreadTest
    public void testStartActionModeWhenCreateReturnsFalse() {
        mCallback.mShouldCreateActionMode = false;

        ActionMode mode = mWindowDecorActionBar.startActionMode(mCallback);

        assertNull(mode);
        assertTrue(mCallback.mIsCreateActionModeCalled);
    }

    @UiThreadTest
    public void testStartActionModeFinishesPreviousMode() {
        ActionMode mode1 = mWindowDecorActionBar.startActionMode(mCallback);
        ActionMode mode2 = mWindowDecorActionBar.startActionMode(new MockActionModeCallback());

        assertNotNull(mode1);
        assertNotNull(mode2);
        assertTrue(mCallback.mIsDestroyActionModeCalled);
    }

    private static final class MockActionModeCallback implements ActionMode.Callback {
        private boolean mShouldCreateActionMode = true;
        private boolean mIsCreateActionModeCalled = false;
        private boolean mIsDestroyActionModeCalled = false;

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            mIsDestroyActionModeCalled = true;
        }

        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            mIsCreateActionModeCalled = true;
            return mShouldCreateActionMode;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            return false;
        }
    }
}
