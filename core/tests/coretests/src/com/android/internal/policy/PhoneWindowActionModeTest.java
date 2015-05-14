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

package com.android.internal.policy;

import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.view.ActionMode;
import android.view.ActionMode.Callback;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityEvent;

/**
 * Tests {@link PhoneWindow}'s {@link ActionMode} related methods.
 */
public final class PhoneWindowActionModeTest
        extends ActivityInstrumentationTestCase2<PhoneWindowActionModeTestActivity> {

    private PhoneWindow mPhoneWindow;
    private MockWindowCallback mWindowCallback;
    private MockActionModeCallback mActionModeCallback;

    public PhoneWindowActionModeTest() {
        super(PhoneWindowActionModeTestActivity.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mPhoneWindow = (PhoneWindow) getActivity().getWindow();
        mWindowCallback = new MockWindowCallback();
        mPhoneWindow.setCallback(mWindowCallback);
        mActionModeCallback = new MockActionModeCallback();
    }

    public void testStartActionModeWithCallback() {
        mWindowCallback.mShouldReturnOwnActionMode = true;

        ActionMode mode = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_FLOATING);

        assertEquals(mWindowCallback.mLastCreatedActionMode, mode);
    }

    public void testStartActionModePrimaryFinishesPreviousMode() {
        // Use custom callback to control the provided ActionMode.
        mWindowCallback.mShouldReturnOwnActionMode = true;

        ActionMode mode1 = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_PRIMARY);
        ActionMode mode2 = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_PRIMARY);

        assertTrue(mode1 instanceof MockActionMode);
        assertTrue(((MockActionMode) mode1).mIsFinished);
        assertNotNull(mode2);
    }

    public void testStartActionModeFloatingFinishesPreviousMode() {
        // Use custom callback to control the provided ActionMode.
        mWindowCallback.mShouldReturnOwnActionMode = true;

        ActionMode mode1 = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_FLOATING);
        ActionMode mode2 = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_FLOATING);

        assertTrue(mode1 instanceof MockActionMode);
        assertTrue(((MockActionMode) mode1).mIsFinished);
        assertNotNull(mode2);
    }

    public void testStartActionModePreservesPreviousModeOfDifferentType1() {
        // Use custom callback to control the provided ActionMode.
        mWindowCallback.mShouldReturnOwnActionMode = true;

        ActionMode mode1 = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_FLOATING);
        ActionMode mode2 = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_PRIMARY);

        assertTrue(mode1 instanceof MockActionMode);
        assertFalse(((MockActionMode) mode1).mIsFinished);
        assertNotNull(mode2);
    }

    public void testStartActionModePreservesPreviousModeOfDifferentType2() {
        // Use custom callback to control the provided ActionMode.
        mWindowCallback.mShouldReturnOwnActionMode = true;

        ActionMode mode1 = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_PRIMARY);
        ActionMode mode2 = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_FLOATING);

        assertTrue(mode1 instanceof MockActionMode);
        assertFalse(((MockActionMode) mode1).mIsFinished);
        assertNotNull(mode2);
    }

    public void testWindowCallbackModesLifecycleIsNotHandled() {
        mWindowCallback.mShouldReturnOwnActionMode = true;

        ActionMode mode = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_PRIMARY);

        assertNotNull(mode);
        assertEquals(mWindowCallback.mLastCreatedActionMode, mode);
        assertFalse(mActionModeCallback.mIsCreateActionModeCalled);
        assertTrue(mWindowCallback.mIsActionModeStarted);
    }

    @UiThreadTest
    public void testCreatedPrimaryModeLifecycleIsHandled() {
        mWindowCallback.mShouldReturnOwnActionMode = false;

        ActionMode mode = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_PRIMARY);

        assertNotNull(mode);
        assertEquals(ActionMode.TYPE_PRIMARY, mode.getType());
        assertTrue(mActionModeCallback.mIsCreateActionModeCalled);
        assertTrue(mWindowCallback.mIsActionModeStarted);
    }

    @UiThreadTest
    public void testCreatedFloatingModeLifecycleIsHandled() {
        mWindowCallback.mShouldReturnOwnActionMode = false;

        ActionMode mode = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_FLOATING);

        assertNotNull(mode);
        assertEquals(ActionMode.TYPE_FLOATING, mode.getType());
        assertTrue(mActionModeCallback.mIsCreateActionModeCalled);
        assertTrue(mWindowCallback.mIsActionModeStarted);
    }

    @UiThreadTest
    public void testCreatedModeIsNotStartedIfCreateReturnsFalse() {
        mWindowCallback.mShouldReturnOwnActionMode = false;
        mActionModeCallback.mShouldCreateActionMode = false;

        ActionMode mode = mPhoneWindow.getDecorView().startActionMode(
                mActionModeCallback, ActionMode.TYPE_FLOATING);

        assertTrue(mActionModeCallback.mIsCreateActionModeCalled);
        assertFalse(mWindowCallback.mIsActionModeStarted);
        assertNull(mode);
    }

    private static final class MockWindowCallback implements Window.Callback {
        private boolean mShouldReturnOwnActionMode = false;
        private MockActionMode mLastCreatedActionMode;
        private boolean mIsActionModeStarted = false;

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            return false;
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return false;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return false;
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return false;
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return false;
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return false;
        }

        @Override
        public View onCreatePanelView(int featureId) {
            return null;
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return false;
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return false;
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu) {
            return false;
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return false;
        }

        @Override
        public void onWindowAttributesChanged(LayoutParams attrs) {}

        @Override
        public void onContentChanged() {}

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {}

        @Override
        public void onAttachedToWindow() {}

        @Override
        public void onDetachedFromWindow() {}

        @Override
        public void onPanelClosed(int featureId, Menu menu) {}

        @Override
        public boolean onSearchRequested() {
            return false;
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent) {
            return false;
        }

        @Override
        public ActionMode onWindowStartingActionMode(Callback callback) {
            if (mShouldReturnOwnActionMode) {
                MockActionMode mode = new MockActionMode();
                mLastCreatedActionMode = mode;
                return mode;
            }
            return null;
        }

        @Override
        public ActionMode onWindowStartingActionMode(Callback callback, int type) {
            if (mShouldReturnOwnActionMode) {
                MockActionMode mode = new MockActionMode();
                mode.mActionModeType = type;
                mLastCreatedActionMode = mode;
                return mode;
            }
            return null;
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
            mIsActionModeStarted = true;
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {}
    }

    private static final class MockActionModeCallback implements ActionMode.Callback {
        private boolean mShouldCreateActionMode = true;
        private boolean mIsCreateActionModeCalled = false;

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {}

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

    private static final class MockActionMode extends ActionMode {
        private int mActionModeType = ActionMode.TYPE_PRIMARY;
        private boolean mIsFinished = false;

        @Override
        public int getType() {
            return mActionModeType;
        }

        @Override
        public void setTitle(CharSequence title) {}

        @Override
        public void setTitle(int resId) {}

        @Override
        public void setSubtitle(CharSequence subtitle) {}

        @Override
        public void setSubtitle(int resId) {}

        @Override
        public void setCustomView(View view) {}

        @Override
        public void invalidate() {}

        @Override
        public void finish() {
            mIsFinished = true;
        }

        @Override
        public Menu getMenu() {
            return null;
        }

        @Override
        public CharSequence getTitle() {
            return null;
        }

        @Override
        public CharSequence getSubtitle() {
            return null;
        }

        @Override
        public View getCustomView() {
            return null;
        }

        @Override
        public MenuInflater getMenuInflater() {
            return null;
        }
    }
}
