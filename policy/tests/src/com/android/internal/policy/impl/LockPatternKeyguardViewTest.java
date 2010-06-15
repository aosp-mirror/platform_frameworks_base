/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.policy.impl;

import android.content.Context;
import com.android.internal.telephony.IccCard;
import android.content.res.Configuration;
import android.test.AndroidTestCase;
import android.view.View;
import android.view.KeyEvent;
import com.android.internal.widget.LockPatternUtils;
import com.google.android.collect.Lists;

import java.util.List;

/**
 * Tests for {@link com.android.internal.policy.impl.LockPatternKeyguardView},
 * which handles the management of screens while the keyguard is showing.
 */
public class LockPatternKeyguardViewTest extends AndroidTestCase {
    private MockUpdateMonitor mUpdateMonitor;
    private LockPatternUtils mLockPatternUtils;
    private TestableLockPatternKeyguardView mLPKV;
    private MockKeyguardCallback mKeyguardViewCallback;

    private static class MockUpdateMonitor extends KeyguardUpdateMonitor {

        public IccCard.State simState = IccCard.State.READY;

        private MockUpdateMonitor(Context context) {
            super(context);
        }

        @Override
        public IccCard.State getSimState() {
            return simState;
        }
    }

    private static class MockLockPatternUtils extends LockPatternUtils {
        boolean isLockPatternEnabled = true;
        public boolean isPermanentlyLocked = false;

        public MockLockPatternUtils(Context context) {
            super(context);
        }

        @Override
        public boolean isLockPatternEnabled() {
            return isLockPatternEnabled;
        }

        @Override
        public void setLockPatternEnabled(boolean lockPatternEnabled) {
            isLockPatternEnabled = lockPatternEnabled;
        }

        @Override
        public boolean isPermanentlyLocked() {
            return isPermanentlyLocked;
        }

        public void setPermanentlyLocked(boolean permanentlyLocked) {
            isPermanentlyLocked = permanentlyLocked;
        }
    }

    private static class MockKeyguardScreen extends View implements KeyguardScreen {

        private int mOnPauseCount = 0;
        private int mOnResumeCount = 0;
        private int mCleanupCount = 0;

        private MockKeyguardScreen(Context context) {
            super(context);
            setFocusable(true);
        }

        /** {@inheritDoc} */
        public boolean needsInput() {
            return false;
        }

        /** {@inheritDoc} */
        public void onPause() {
            mOnPauseCount++;
        }

        /** {@inheritDoc} */
        public void onResume() {
            mOnResumeCount++;
        }

        /** {@inheritDoc} */
        public void cleanUp() {
            mCleanupCount++;
        }

        public int getOnPauseCount() {
            return mOnPauseCount;
        }

        public int getOnResumeCount() {
            return mOnResumeCount;
        }

        public int getCleanupCount() {
            return mCleanupCount;
        }
    }

    /**
     * Allows us to inject the lock and unlock views to simulate their behavior
     * and detect their creation.
     */
    private static class TestableLockPatternKeyguardView extends LockPatternKeyguardView {
        private List<MockKeyguardScreen> mInjectedLockScreens;
        private List<MockKeyguardScreen> mInjectedUnlockScreens;



        private TestableLockPatternKeyguardView(Context context, KeyguardUpdateMonitor updateMonitor,
                LockPatternUtils lockPatternUtils, KeyguardWindowController controller) {
            super(context, updateMonitor, lockPatternUtils, controller);
        }

        @Override
        View createLockScreen() {
            final MockKeyguardScreen newView = new MockKeyguardScreen(getContext());
            if (mInjectedLockScreens == null) mInjectedLockScreens = Lists.newArrayList();
            mInjectedLockScreens.add(newView);
            return newView;
        }

        @Override
        View createUnlockScreenFor(UnlockMode unlockMode) {
            final MockKeyguardScreen newView = new MockKeyguardScreen(getContext());
            if (mInjectedUnlockScreens == null) mInjectedUnlockScreens = Lists.newArrayList();
            mInjectedUnlockScreens.add(newView);
            return newView;
        }

        public List<MockKeyguardScreen> getInjectedLockScreens() {
            return mInjectedLockScreens;
        }

        public List<MockKeyguardScreen> getInjectedUnlockScreens() {
            return mInjectedUnlockScreens;
        }
    }

    private static class MockKeyguardCallback implements KeyguardViewCallback {

        private int mPokeWakelockCount = 0;
        private int mKeyguardDoneCount = 0;

        public void pokeWakelock() {
            mPokeWakelockCount++;
        }

        public void pokeWakelock(int millis) {
            mPokeWakelockCount++;
        }

        public void keyguardDone(boolean authenticated) {
            mKeyguardDoneCount++;
        }

        public void keyguardDoneDrawing() {

        }

        public int getPokeWakelockCount() {
            return mPokeWakelockCount;
        }

        public int getKeyguardDoneCount() {
            return mKeyguardDoneCount;
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mUpdateMonitor = new MockUpdateMonitor(getContext());
        mLockPatternUtils = new MockLockPatternUtils(getContext());

        mLPKV = new TestableLockPatternKeyguardView(getContext(), mUpdateMonitor,
                mLockPatternUtils, new KeyguardWindowController() {
            public void setNeedsInput(boolean needsInput) {
            }
        });
        mKeyguardViewCallback = new MockKeyguardCallback();
        mLPKV.setCallback(mKeyguardViewCallback);
    }

    public void testStateAfterCreatedWhileScreenOff() {

        assertEquals(1, mLPKV.getInjectedLockScreens().size());
        assertEquals(1, mLPKV.getInjectedUnlockScreens().size());

        MockKeyguardScreen lockScreen = mLPKV.getInjectedLockScreens().get(0);
        MockKeyguardScreen unlockScreen = mLPKV.getInjectedUnlockScreens().get(0);

        assertEquals(0, lockScreen.getOnPauseCount());
        assertEquals(0, lockScreen.getOnResumeCount());
        assertEquals(0, lockScreen.getCleanupCount());

        assertEquals(0, unlockScreen.getOnPauseCount());
        assertEquals(0, unlockScreen.getOnResumeCount());
        assertEquals(0, unlockScreen.getCleanupCount());

        assertEquals(0, mKeyguardViewCallback.getPokeWakelockCount());
        assertEquals(0, mKeyguardViewCallback.getKeyguardDoneCount());
    }

    public void testWokenByNonMenuKey() {
        mLPKV.wakeWhenReadyTq(0);

        // should have poked the wakelock to turn on the screen
        assertEquals(1, mKeyguardViewCallback.getPokeWakelockCount());

        // shouldn't be any additional views created
        assertEquals(1, mLPKV.getInjectedLockScreens().size());
        assertEquals(1, mLPKV.getInjectedUnlockScreens().size());
        MockKeyguardScreen lockScreen = mLPKV.getInjectedLockScreens().get(0);
        MockKeyguardScreen unlockScreen = mLPKV.getInjectedUnlockScreens().get(0);

        // lock screen should be only visible one
        assertEquals(View.VISIBLE, lockScreen.getVisibility());
        assertEquals(View.GONE, unlockScreen.getVisibility());

        // on resume not called until screen turns on
        assertEquals(0, lockScreen.getOnPauseCount());
        assertEquals(0, lockScreen.getOnResumeCount());
        assertEquals(0, lockScreen.getCleanupCount());

        assertEquals(0, unlockScreen.getOnPauseCount());
        assertEquals(0, unlockScreen.getOnResumeCount());
        assertEquals(0, unlockScreen.getCleanupCount());

        // simulate screen turning on
        mLPKV.onScreenTurnedOn();

        assertEquals(0, lockScreen.getOnPauseCount());
        assertEquals(1, lockScreen.getOnResumeCount());
        assertEquals(0, lockScreen.getCleanupCount());

        assertEquals(0, unlockScreen.getOnPauseCount());
        assertEquals(0, unlockScreen.getOnResumeCount());
        assertEquals(0, unlockScreen.getCleanupCount());
    }

    public void testWokenByMenuKeyWhenPatternSet() {
        assertEquals(true, mLockPatternUtils.isLockPatternEnabled());

        mLPKV.wakeWhenReadyTq(KeyEvent.KEYCODE_MENU);

        // should have poked the wakelock to turn on the screen
        assertEquals(1, mKeyguardViewCallback.getPokeWakelockCount());

        // shouldn't be any additional views created
        assertEquals(1, mLPKV.getInjectedLockScreens().size());
        assertEquals(1, mLPKV.getInjectedUnlockScreens().size());
        MockKeyguardScreen lockScreen = mLPKV.getInjectedLockScreens().get(0);
        MockKeyguardScreen unlockScreen = mLPKV.getInjectedUnlockScreens().get(0);

        // unlock screen should be only visible one
        assertEquals(View.GONE, lockScreen.getVisibility());
        assertEquals(View.VISIBLE, unlockScreen.getVisibility());
    }

    public void testScreenRequestsRecreation() {
        mLPKV.wakeWhenReadyTq(0);
        mLPKV.onScreenTurnedOn();

        assertEquals(1, mLPKV.getInjectedLockScreens().size());
        assertEquals(1, mLPKV.getInjectedUnlockScreens().size());
        MockKeyguardScreen lockScreen = mLPKV.getInjectedLockScreens().get(0);

        assertEquals(0, lockScreen.getOnPauseCount());
        assertEquals(1, lockScreen.getOnResumeCount());

        // simulate screen asking to be recreated
        mLPKV.mKeyguardScreenCallback.recreateMe(new Configuration());

        // should have been recreated
        assertEquals(2, mLPKV.getInjectedLockScreens().size());
        assertEquals(2, mLPKV.getInjectedUnlockScreens().size());

        // both old screens should have been cleaned up
        assertEquals(1, mLPKV.getInjectedLockScreens().get(0).getCleanupCount());
        assertEquals(1, mLPKV.getInjectedUnlockScreens().get(0).getCleanupCount());

        // old lock screen should have been paused
        assertEquals(1, mLPKV.getInjectedLockScreens().get(0).getOnPauseCount());
        assertEquals(0, mLPKV.getInjectedUnlockScreens().get(0).getOnPauseCount());

        // new lock screen should have been resumed
        assertEquals(1, mLPKV.getInjectedLockScreens().get(1).getOnResumeCount());
        assertEquals(0, mLPKV.getInjectedUnlockScreens().get(1).getOnResumeCount());
    }

    public void testMenuDoesntGoToUnlockScreenOnWakeWhenPukLocked() {
        // PUK locked
        mUpdateMonitor.simState = IccCard.State.PUK_REQUIRED;

        // wake by menu
        mLPKV.wakeWhenReadyTq(KeyEvent.KEYCODE_MENU);

        assertEquals(1, mLPKV.getInjectedLockScreens().size());
        assertEquals(1, mLPKV.getInjectedUnlockScreens().size());
        MockKeyguardScreen lockScreen = mLPKV.getInjectedLockScreens().get(0);
        MockKeyguardScreen unlockScreen = mLPKV.getInjectedUnlockScreens().get(0);

        // lock screen should be only visible one
        assertEquals(View.VISIBLE, lockScreen.getVisibility());
        assertEquals(View.GONE, unlockScreen.getVisibility());
    }

    public void testMenuGoesToLockScreenWhenDeviceNotSecure() {
        mLockPatternUtils.setLockPatternEnabled(false);

        // wake by menu
        mLPKV.wakeWhenReadyTq(KeyEvent.KEYCODE_MENU);

        assertEquals(1, mLPKV.getInjectedLockScreens().size());
        assertEquals(1, mLPKV.getInjectedUnlockScreens().size());
        MockKeyguardScreen lockScreen = mLPKV.getInjectedLockScreens().get(0);
        MockKeyguardScreen unlockScreen = mLPKV.getInjectedUnlockScreens().get(0);

        // lock screen should be only visible one
        assertEquals(View.VISIBLE, lockScreen.getVisibility());
        assertEquals(View.GONE, unlockScreen.getVisibility());
    }
}
