/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.server.policy;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.KeyEvent.KEYCODE_ALT_LEFT;
import static android.view.KeyEvent.KEYCODE_ALT_RIGHT;
import static android.view.KeyEvent.KEYCODE_CTRL_LEFT;
import static android.view.KeyEvent.KEYCODE_CTRL_RIGHT;
import static android.view.KeyEvent.KEYCODE_META_LEFT;
import static android.view.KeyEvent.KEYCODE_META_RIGHT;
import static android.view.KeyEvent.KEYCODE_SHIFT_LEFT;
import static android.view.KeyEvent.KEYCODE_SHIFT_RIGHT;
import static android.view.KeyEvent.META_ALT_LEFT_ON;
import static android.view.KeyEvent.META_ALT_ON;
import static android.view.KeyEvent.META_ALT_RIGHT_ON;
import static android.view.KeyEvent.META_CTRL_LEFT_ON;
import static android.view.KeyEvent.META_CTRL_ON;
import static android.view.KeyEvent.META_CTRL_RIGHT_ON;
import static android.view.KeyEvent.META_META_LEFT_ON;
import static android.view.KeyEvent.META_META_ON;
import static android.view.KeyEvent.META_META_RIGHT_ON;
import static android.view.KeyEvent.META_SHIFT_LEFT_ON;
import static android.view.KeyEvent.META_SHIFT_ON;
import static android.view.KeyEvent.META_SHIFT_RIGHT_ON;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.server.policy.WindowManagerPolicy.ACTION_PASS_TO_USER;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.ArgumentMatchers.eq;

import static java.util.Collections.unmodifiableMap;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.hardware.input.KeyGestureEvent;
import android.platform.test.flag.junit.SetFlagsRule;
import android.util.ArrayMap;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.RuleChain;

import java.util.Map;

class ShortcutKeyTestBase {

    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    public final FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Rule
    public RuleChain rules = RuleChain.outerRule(mSettingsProviderRule).around(mSetFlagsRule);

    private Resources mResources;
    private PackageManager mPackageManager;
    TestPhoneWindowManager mPhoneWindowManager;
    DispatchedKeyHandler mDispatchedKeyHandler = event -> false;
    Context mContext;

    /** Modifier key to meta state */
    protected static final Map<Integer, Integer> MODIFIER;
    static {
        final Map<Integer, Integer> map = new ArrayMap<>();
        map.put(KEYCODE_CTRL_LEFT, META_CTRL_LEFT_ON | META_CTRL_ON);
        map.put(KEYCODE_CTRL_RIGHT, META_CTRL_RIGHT_ON | META_CTRL_ON);
        map.put(KEYCODE_ALT_LEFT, META_ALT_LEFT_ON | META_ALT_ON);
        map.put(KEYCODE_ALT_RIGHT, META_ALT_RIGHT_ON | META_ALT_ON);
        map.put(KEYCODE_SHIFT_LEFT, META_SHIFT_LEFT_ON | META_SHIFT_ON);
        map.put(KEYCODE_SHIFT_RIGHT, META_SHIFT_RIGHT_ON | META_SHIFT_ON);
        map.put(KEYCODE_META_LEFT, META_META_LEFT_ON | META_META_ON);
        map.put(KEYCODE_META_RIGHT, META_META_RIGHT_ON | META_META_ON);

        MODIFIER = unmodifiableMap(map);
    }

    @Before
    public void setup() {
        mContext = spy(getInstrumentation().getTargetContext());
        mResources = spy(mContext.getResources());
        mPackageManager = spy(mContext.getPackageManager());
        doReturn(mContext).when(mContext).createContextAsUser(anyObject(), anyInt());
        doReturn(mResources).when(mContext).getResources();
        doReturn(mSettingsProviderRule.mockContentResolver(mContext))
                .when(mContext).getContentResolver();
        XmlResourceParser testBookmarks = mResources.getXml(
                com.android.frameworks.wmtests.R.xml.bookmarks);
        doReturn(testBookmarks).when(mResources).getXml(com.android.internal.R.xml.bookmarks);

        try {
            // Keep packageName / className in sync with
            // services/tests/wmtests/res/xml/bookmarks.xml
            ActivityInfo testActivityInfo = new ActivityInfo();
            testActivityInfo.applicationInfo = new ApplicationInfo();
            testActivityInfo.packageName =
                    testActivityInfo.applicationInfo.packageName = "com.test";
            doReturn(testActivityInfo).when(mPackageManager).getActivityInfo(
                    eq(new ComponentName("com.test", "com.test.BookmarkTest")), anyInt());
        } catch (PackageManager.NameNotFoundException ignored) { }
    }


    /** Same as {@link setUpPhoneWindowManager(boolean)}, without supporting settings update. */
    protected final void setUpPhoneWindowManager() {
        setUpPhoneWindowManager(/* supportSettingsUpdate= */ false);
    }

    /**
     * Creates and sets up a {@link TestPhoneWindowManager} instance.
     *
     * <p>Subclasses must call this at the start of the test if they intend to interact with phone
     * window manager.
     *
     * @param supportSettingsUpdate {@code true} to have PWM respond to any Settings changes upon
     *    instantiation. Although this is supposed to also allow a test to listen to any Settings
     *    changes after instantiation, MockContentResolver in this class's setup stubs out
     *    notifyChange(), which prevents SettingsObserver from getting notified of events. So
     *    we're effectively always instantiating TestPhoneWindowManager with
     *    supportSettingsUpdate=false.
     */
    protected final void setUpPhoneWindowManager(boolean supportSettingsUpdate) {
        mPhoneWindowManager = new TestPhoneWindowManager(mContext, supportSettingsUpdate);
    }

    protected final void setDispatchedKeyHandler(DispatchedKeyHandler keyHandler) {
        mDispatchedKeyHandler = keyHandler;
    }

    @After
    public void tearDown() {
        if (mPhoneWindowManager != null) {
            mPhoneWindowManager.tearDown();
        }
    }

    void sendKeyCombination(int[] keyCodes, long durationMillis, boolean longPress, int displayId) {
        final long downTime = mPhoneWindowManager.getCurrentTime();
        final int count = keyCodes.length;
        int metaState = 0;

        for (int i = 0; i < count; i++) {
            final int keyCode = keyCodes[i];
            final KeyEvent event = new KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode,
                    0 /*repeat*/, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/,
                    0 /*flags*/, InputDevice.SOURCE_KEYBOARD);
            event.setDisplayId(displayId);
            interceptKey(event);
            // The order is important here, metaState could be updated and applied to the next key.
            metaState |= MODIFIER.getOrDefault(keyCode, 0);
        }

        if (durationMillis > 0) {
            mPhoneWindowManager.moveTimeForward(durationMillis);
        }

        if (longPress) {
            final long nextDownTime = mPhoneWindowManager.getCurrentTime();
            for (int i = 0; i < count; i++) {
                final int keyCode = keyCodes[i];
                final KeyEvent nextDownEvent = new KeyEvent(downTime, nextDownTime,
                        KeyEvent.ACTION_DOWN, keyCode, 1 /*repeat*/, metaState,
                        KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/,
                        KeyEvent.FLAG_LONG_PRESS /*flags*/, InputDevice.SOURCE_KEYBOARD);
                nextDownEvent.setDisplayId(displayId);
                interceptKey(nextDownEvent);
            }
        }

        final long eventTime = mPhoneWindowManager.getCurrentTime();
        for (int i = count - 1; i >= 0; i--) {
            final int keyCode = keyCodes[i];
            final KeyEvent upEvent = new KeyEvent(downTime, eventTime, KeyEvent.ACTION_UP, keyCode,
                    0, metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0 /*scancode*/, 0 /*flags*/,
                    InputDevice.SOURCE_KEYBOARD);
            upEvent.setDisplayId(displayId);
            interceptKey(upEvent);
            metaState &= ~MODIFIER.getOrDefault(keyCode, 0);
        }
    }

    void sendKeyCombination(int[] keyCodes, long durationMillis) {
        sendKeyCombination(keyCodes, durationMillis, false /* longPress */, DEFAULT_DISPLAY);
    }

    void sendKeyCombination(int[] keyCodes, long durationMillis, int displayId) {
        sendKeyCombination(keyCodes, durationMillis, false /* longPress */, displayId);
    }

    void sendLongPressKeyCombination(int[] keyCodes) {
        sendKeyCombination(keyCodes, ViewConfiguration.getLongPressTimeout(), true /* longPress */,
                DEFAULT_DISPLAY);
    }

    void sendKey(int keyCode) {
        sendKey(keyCode, false);
    }

    void sendKey(int keyCode, boolean longPress) {
        sendKeyCombination(new int[]{keyCode}, 0 /*durationMillis*/, longPress, DEFAULT_DISPLAY);
    }

    boolean sendKeyGestureEventStart(int gestureType) {
        return mPhoneWindowManager.sendKeyGestureEvent(
                new KeyGestureEvent.Builder().setKeyGestureType(gestureType).setAction(
                        KeyGestureEvent.ACTION_GESTURE_START).build());
    }

    boolean sendKeyGestureEventComplete(int gestureType) {
        return mPhoneWindowManager.sendKeyGestureEvent(
                new KeyGestureEvent.Builder().setKeyGestureType(gestureType).setAction(
                        KeyGestureEvent.ACTION_GESTURE_COMPLETE).build());
    }

    boolean sendKeyGestureEventComplete(int gestureType, int modifierState) {
        return mPhoneWindowManager.sendKeyGestureEvent(
                new KeyGestureEvent.Builder().setModifierState(modifierState).setKeyGestureType(
                        gestureType).setAction(KeyGestureEvent.ACTION_GESTURE_COMPLETE).build());
    }

    boolean sendKeyGestureEventComplete(int keycode, int modifierState, int gestureType) {
        return mPhoneWindowManager.sendKeyGestureEvent(
                new KeyGestureEvent.Builder().setKeycodes(new int[]{keycode}).setModifierState(
                        modifierState).setKeyGestureType(gestureType).setAction(
                        KeyGestureEvent.ACTION_GESTURE_COMPLETE).build());
    }

    /**
     * Since we use SettingsProviderRule to mock the ContentResolver in these
     * tests, the settings observer registered by PhoneWindowManager will not
     * be triggered automatically by the mock. Use this method to force the
     * settings observer change after modifying any settings.
     */
    void triggerSettingsObserverChange() {
        mPhoneWindowManager.getSettingsObserver().onChange(
                // This boolean doesn't matter. This observer does the same thing regardless.
                /*selfChange=*/true);
    }

    /** Override a resource's return value. */
    void overrideResource(int resId, int expectedBehavior) {
        doReturn(expectedBehavior).when(mResources).getInteger(eq(resId));
    }

    private void interceptKey(KeyEvent keyEvent) {
        int actions = mPhoneWindowManager.interceptKeyBeforeQueueing(keyEvent);
        if ((actions & ACTION_PASS_TO_USER) != 0) {
            if (0 == mPhoneWindowManager.interceptKeyBeforeDispatching(keyEvent)) {
                if (!mDispatchedKeyHandler.onKeyDispatched(keyEvent)) {
                    mPhoneWindowManager.dispatchUnhandledKey(keyEvent);
                }
            }
        }
        mPhoneWindowManager.dispatchAllPendingEvents();
    }

    interface DispatchedKeyHandler {
        /**
         * Called when a key event is dispatched to app.
         *
         * @return true if the event is consumed by app.
         */
        boolean onKeyDispatched(KeyEvent event);
    }
}
