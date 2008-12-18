/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.frameworktest.settings;

import com.android.frameworktest.settings.RingtonePickerActivityLauncher;

import android.app.Instrumentation;
import android.database.Cursor;
import android.media.RingtoneManager;
import android.net.Uri;
import android.provider.MediaStore;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.Suppress;
import android.view.KeyEvent;

/**
 * Tests the RingtonePickerActivity.
 * <p>
 * There is a launcher for launching the RingtonePickerActivity (RPA) since the RPA needs
 * to be a subactivity.  We don't have a reference to the actual RPA.
 * <p>
 * This relies heavily on keypresses getting to the right widget.  It depends on:
 * <li> Less than NUM_RINGTONES_AND_SOME ringtones on the system
 * <li> Pressing arrow-down a ton will eventually end up on the 'Cancel' button
 * <li> From the 'Cancel' button, pressing arrow-left will end up on 'OK' button
 */
@Suppress
public class RingtonePickerActivityTest extends ActivityInstrumentationTestCase<RingtonePickerActivityLauncher> {

    private static final int NUM_RINGTONES_AND_SOME = 20;
    private RingtonePickerActivityLauncher mActivity;
    private Instrumentation mInstrumentation;
    
    public RingtonePickerActivityTest() {
        super("com.android.frameworktest", RingtonePickerActivityLauncher.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        
        mActivity = getActivity();
        mInstrumentation = getInstrumentation();
        assertNotNull(mActivity);
        assertFalse(mActivity.resultReceived);
        assertNotNull(mInstrumentation);
    }
    
    public void testDefault() {
        mActivity.launchRingtonePickerActivity(true, null, RingtoneManager.TYPE_ALL);
        mInstrumentation.waitForIdleSync();
        
        // Go to top
        goTo(true);
        // Select default ringtone 
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        // Go to bottom/cancel button
        goTo(false);
        // Select OK button
        sendKeys(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER);
        
        mInstrumentation.waitForIdleSync();
        
        assertTrue(mActivity.resultReceived);
        assertNotNull(mActivity.result);
        assertTrue(RingtoneManager.isDefault(mActivity.pickedUri));
    }
    
    public void testFirst() {
        mActivity.launchRingtonePickerActivity(true, null, RingtoneManager.TYPE_ALL);
        mInstrumentation.waitForIdleSync();
        
        // Go to top
        goTo(true);
        // Select first (non-default) ringtone 
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER);
        // Go to bottom/cancel button
        goTo(false);
        // Select OK button
        sendKeys(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER);
        
        mInstrumentation.waitForIdleSync();
        
        assertTrue(mActivity.resultReceived);
        assertNotNull(mActivity.result);
        assertNotNull(mActivity.pickedUri);
        assertFalse(RingtoneManager.isDefault(mActivity.pickedUri));
    }

    public void testExisting() {
        // We need to get an existing ringtone first, so launch it, pick first,
        // and keep that URI
        testFirst();
        Uri firstUri = mActivity.pickedUri;
        
        mActivity.launchRingtonePickerActivity(true, firstUri, RingtoneManager.TYPE_ALL);
        mInstrumentation.waitForIdleSync();

        //// Hit cancel:
        
        // Go to bottom
        goTo(false);
        // Select Cancel button
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        
        mInstrumentation.waitForIdleSync();
        
        assertTrue(mActivity.resultReceived);
        assertEquals(mActivity.pickedUri, firstUri);
    }
    
    public void testExistingButDifferent() {
        // We need to get an existing ringtone first, so launch it, pick first,
        // and keep that URI
        testFirst();
        Uri firstUri = mActivity.pickedUri;
        
        mActivity.launchRingtonePickerActivity(true, firstUri, RingtoneManager.TYPE_ALL);
        mInstrumentation.waitForIdleSync();

        //// Pick second:
        
        // Go to top
        goTo(true);
        // Select second (non-default) ringtone 
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER);
        // Go to bottom/cancel button
        goTo(false);
        // Select OK button
        sendKeys(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER);
        
        mInstrumentation.waitForIdleSync();
        
        assertTrue(mActivity.resultReceived);
        assertNotNull(mActivity.result);
        assertTrue(!firstUri.equals(mActivity.pickedUri));
    }
    
    public void testCancel() {
        mActivity.launchRingtonePickerActivity(true, null, RingtoneManager.TYPE_ALL);
        mInstrumentation.waitForIdleSync();
        
        // Go to bottom
        goTo(false);
        // Select Cancel button
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        
        mInstrumentation.waitForIdleSync();
        
        assertTrue(mActivity.resultReceived);
        assertNull(mActivity.result);
    }

    public void testNoDefault() {
        mActivity.launchRingtonePickerActivity(false, null, RingtoneManager.TYPE_ALL);
        mInstrumentation.waitForIdleSync();
        
        // Go to top
        goTo(true);
        // Select first (non-default) ringtone 
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        // Go to bottom/cancel button
        goTo(false);
        // Select OK button
        sendKeys(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER);
        
        mInstrumentation.waitForIdleSync();
        
        assertTrue(mActivity.resultReceived);
        assertNotNull(mActivity.result);
        assertNotNull(mActivity.pickedUri);
        assertFalse(RingtoneManager.isDefault(mActivity.pickedUri));
    }
    
    public void testNotifications() {
        mActivity.launchRingtonePickerActivity(false, null, RingtoneManager.TYPE_NOTIFICATION);
        mInstrumentation.waitForIdleSync();
        
        // Move to top of list
        goTo(true);
        // Select first ringtone in list
        sendKeys(KeyEvent.KEYCODE_DPAD_CENTER);
        // Move all the way down (will focus 'Cancel')
        goTo(false);
        // Move left and click (will click 'Ok')
        sendKeys(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_CENTER);

        // Wait until main thread is idle
        mInstrumentation.waitForIdleSync();

        assertTrue(mActivity.resultReceived);
        assertNotNull(mActivity.result);
        assertNotNull(mActivity.pickedUri);
        
        // Get the path of the picked ringtone
        Uri uri = mActivity.pickedUri;
        Cursor c = mActivity.getContentResolver().query(uri, new String[] { "_data" },
                null, null, null);
        assertTrue("Query for selected ringtone URI does not have a result", c.moveToFirst());
        String path = c.getString(0);
        // Quick check to see if the ringtone is a notification
        assertTrue("The path of the selected ringtone did not contain \"notification\"",
                path.contains("notifications"));
    }
    
    private void goTo(boolean top) {
        // Get to the buttons at the bottom (top == false), or the top (top == true)
        for (int i = 0; i < NUM_RINGTONES_AND_SOME; i++) {
            sendKeys(top ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN);
        }
    }
}
