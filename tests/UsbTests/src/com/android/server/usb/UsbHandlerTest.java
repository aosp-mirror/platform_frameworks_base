/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.usb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.debug.AdbManagerInternal;
import android.debug.AdbTransportType;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.server.FgThread;
import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tests for UsbHandler state changes.
 */
@RunWith(AndroidJUnit4.class)
public class UsbHandlerTest {
    private static final String TAG = UsbHandlerTest.class.getSimpleName();

    @Mock
    private UsbDeviceManager mUsbDeviceManager;
    @Mock
    private UsbAlsaManager mUsbAlsaManager;
    @Mock
    private UsbSettingsManager mUsbSettingsManager;
    @Mock
    private UsbPermissionManager mUsbPermissionManager;
    @Mock
    private SharedPreferences mSharedPreferences;
    @Mock
    private SharedPreferences.Editor mEditor;
    @Mock
    private AdbManagerInternal mAdbManagerInternal;

    private MockUsbHandler mUsbHandler;

    private static final int MSG_UPDATE_STATE = 0;
    private static final int MSG_ENABLE_ADB = 1;
    private static final int MSG_SET_CURRENT_FUNCTIONS = 2;
    private static final int MSG_SYSTEM_READY = 3;
    private static final int MSG_BOOT_COMPLETED = 4;
    private static final int MSG_USER_SWITCHED = 5;
    private static final int MSG_UPDATE_USER_RESTRICTIONS = 6;
    private static final int MSG_SET_SCREEN_UNLOCKED_FUNCTIONS = 12;
    private static final int MSG_UPDATE_SCREEN_LOCK = 13;

    private Map<String, String> mMockProperties;
    private Map<String, Integer> mMockGlobalSettings;
    private MockitoSession mStaticMockSession;

    private class MockUsbHandler extends UsbDeviceManager.UsbHandler {
        boolean mIsUsbTransferAllowed;
        Intent mBroadcastedIntent;

        MockUsbHandler(Looper looper, Context context, UsbDeviceManager deviceManager,
                UsbAlsaManager alsaManager, UsbSettingsManager settingsManager,
                UsbPermissionManager permissionManager) {
            super(looper, context, deviceManager, alsaManager, permissionManager);
            mUseUsbNotification = false;
            mIsUsbTransferAllowed = true;
            mCurrentUsbFunctionsReceived = true;
        }

        @Override
        protected void setEnabledFunctions(long functions, boolean force, int operationId) {
            mCurrentFunctions = functions;
        }

        @Override
        protected void setSystemProperty(String property, String value) {
            mMockProperties.put(property, value);
        }

        @Override
        protected void putGlobalSettings(ContentResolver resolver, String setting, int val) {
            mMockGlobalSettings.put(setting, val);
        }

        @Override
        protected String getSystemProperty(String property, String def) {
            if (mMockProperties.containsKey(property)) {
                return mMockProperties.get(property);
            }
            return def;
        }

        @Override
        protected boolean isUsbTransferAllowed() {
            return mIsUsbTransferAllowed;
        }

        @Override
        protected SharedPreferences getPinnedSharedPrefs(Context context) {
            return mSharedPreferences;
        }

        @Override
        protected void sendStickyBroadcast(Intent intent) {
            mBroadcastedIntent = intent;
        }

        @Override
        public void handlerInitDone(int operationId) {
        }

        @Override
        public void setCurrentUsbFunctionsCb(long functions,
                    int status, int mRequest, long mFunctions, boolean mChargingFunctions){
        }

        @Override
        public void getUsbSpeedCb(int speed){
        }

        @Override
        public void resetCb(int status){
        }

    }

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        mStaticMockSession = ExtendedMockito.mockitoSession()
                .mockStatic(LocalServices.class)
                .strictness(Strictness.WARN)
                .startMocking();
        mMockProperties = new HashMap<>();
        mMockGlobalSettings = new HashMap<>();
        when(mSharedPreferences.edit()).thenReturn(mEditor);

        mUsbHandler = new MockUsbHandler(FgThread.get().getLooper(),
                InstrumentationRegistry.getContext(), mUsbDeviceManager, mUsbAlsaManager,
                mUsbSettingsManager, mUsbPermissionManager);

        when(LocalServices.getService(eq(AdbManagerInternal.class)))
                .thenReturn(mAdbManagerInternal);
    }

    @After
    public void tearDown() throws Exception {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    @SmallTest
    @Test
    public void setFunctionsMtp() {
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_MTP));
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_MTP, 0);
    }

    @SmallTest
    @Test
    public void setFunctionsPtp() {
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_PTP));
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_PTP, 0);
    }

    @SmallTest
    @Test
    public void setFunctionsMidi() {
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_MIDI));
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_MIDI, 0);
    }

    @SmallTest
    @Test
    public void setFunctionsRndis() {
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_RNDIS));
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_RNDIS, 0);
    }

    @SmallTest
    @Test
    public void setFunctionsNcm() {
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_NCM));
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_NCM, 0);
    }

    @SmallTest
    @Test
    public void setFunctionsNcmAndRndis() {
        final long rndisPlusNcm = UsbManager.FUNCTION_RNDIS | UsbManager.FUNCTION_NCM;

        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_NCM));
        assertEquals(UsbManager.FUNCTION_NCM, mUsbHandler.getEnabledFunctions() & rndisPlusNcm);

        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                rndisPlusNcm));
        assertEquals(rndisPlusNcm, mUsbHandler.getEnabledFunctions() & rndisPlusNcm);

        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_NCM));
        assertEquals(UsbManager.FUNCTION_NCM, mUsbHandler.getEnabledFunctions() & rndisPlusNcm);
    }

    @SmallTest
    @Test
    public void enableAdb() {
        sendBootCompleteMessages(mUsbHandler);
        Message msg = mUsbHandler.obtainMessage(MSG_ENABLE_ADB);
        msg.arg1 = 1;
        mUsbHandler.handleMessage(msg);
        assertEquals(mUsbHandler.getEnabledFunctions(), UsbManager.FUNCTION_NONE);
        assertEquals(mMockProperties.get(UsbDeviceManager.UsbHandler
                .USB_PERSISTENT_CONFIG_PROPERTY), UsbManager.USB_FUNCTION_ADB);

        when(mAdbManagerInternal.isAdbEnabled(eq(AdbTransportType.USB))).thenReturn(true);
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_UPDATE_STATE, 1, 1));

        assertTrue(mUsbHandler.mBroadcastedIntent.getBooleanExtra(UsbManager.USB_CONNECTED, false));
        assertTrue(mUsbHandler.mBroadcastedIntent
                .getBooleanExtra(UsbManager.USB_CONFIGURED, false));
        assertTrue(mUsbHandler.mBroadcastedIntent
                .getBooleanExtra(UsbManager.USB_FUNCTION_ADB, false));
    }

    @SmallTest
    @Test
    public void disableAdb() {
        mMockProperties.put(UsbDeviceManager.UsbHandler.USB_PERSISTENT_CONFIG_PROPERTY,
                UsbManager.USB_FUNCTION_ADB);
        mUsbHandler = new MockUsbHandler(FgThread.get().getLooper(),
                InstrumentationRegistry.getContext(), mUsbDeviceManager, mUsbAlsaManager,
                mUsbSettingsManager, mUsbPermissionManager);

        sendBootCompleteMessages(mUsbHandler);
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_ENABLE_ADB, 0));
        assertEquals(mUsbHandler.getEnabledFunctions(), UsbManager.FUNCTION_NONE);
        assertFalse(mUsbHandler.isAdbEnabled());
        assertEquals(mMockProperties.get(UsbDeviceManager.UsbHandler
                .USB_PERSISTENT_CONFIG_PROPERTY), "");
    }

    @SmallTest
    @Test
    public void bootCompletedCharging() {
        sendBootCompleteMessages(mUsbHandler);
        assertEquals(mUsbHandler.getEnabledFunctions(), UsbManager.FUNCTION_NONE);
    }

    @SmallTest
    @Test
    public void userSwitchedDisablesMtp() {
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_MTP));
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_MTP, 0);

        Message msg = mUsbHandler.obtainMessage(MSG_USER_SWITCHED);
        msg.arg1 = ActivityManager.getCurrentUser() + 1;
        mUsbHandler.handleMessage(msg);
        assertEquals(mUsbHandler.getEnabledFunctions(), UsbManager.FUNCTION_NONE);
    }

    @SmallTest
    @Test
    public void changedRestrictionsDisablesMtp() {
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_MTP));
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_MTP, 0);

        mUsbHandler.mIsUsbTransferAllowed = false;
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_UPDATE_USER_RESTRICTIONS));
        assertEquals(mUsbHandler.getEnabledFunctions(), UsbManager.FUNCTION_NONE);
    }

    @SmallTest
    @Test
    public void disconnectResetsCharging() {
        sendBootCompleteMessages(mUsbHandler);

        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_MTP));
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_MTP, 0);

        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_UPDATE_STATE, 0, 0));

        assertEquals(mUsbHandler.getEnabledFunctions(), UsbManager.FUNCTION_NONE);
    }

    @SmallTest
    @Test
    public void configuredSendsBroadcast() {
        sendBootCompleteMessages(mUsbHandler);
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_CURRENT_FUNCTIONS,
                UsbManager.FUNCTION_MTP));
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_MTP, 0);

        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_UPDATE_STATE, 1, 1));

        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_MTP, 0);
        assertTrue(mUsbHandler.mBroadcastedIntent.getBooleanExtra(UsbManager.USB_CONNECTED, false));
        assertTrue(mUsbHandler.mBroadcastedIntent
                .getBooleanExtra(UsbManager.USB_CONFIGURED, false));
        assertTrue(mUsbHandler.mBroadcastedIntent
                .getBooleanExtra(UsbManager.USB_FUNCTION_MTP, false));
    }

    @SmallTest
    @Test
    public void setScreenUnlockedFunctions() {
        sendBootCompleteMessages(mUsbHandler);
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_UPDATE_SCREEN_LOCK, 0));

        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_SET_SCREEN_UNLOCKED_FUNCTIONS,
                UsbManager.FUNCTION_MTP));
        assertNotEquals(mUsbHandler.getScreenUnlockedFunctions() & UsbManager.FUNCTION_MTP, 0);
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_MTP, 0);
        verify(mEditor).putString(String.format(Locale.ENGLISH,
                UsbDeviceManager.UNLOCKED_CONFIG_PREF, mUsbHandler.mCurrentUser),
                UsbManager.USB_FUNCTION_MTP);
    }

    @SmallTest
    @Test
    public void unlockScreen() {
        when(mSharedPreferences.getString(String.format(Locale.ENGLISH,
                UsbDeviceManager.UNLOCKED_CONFIG_PREF, mUsbHandler.mCurrentUser), ""))
                .thenReturn(UsbManager.USB_FUNCTION_MTP);
        mUsbHandler = new MockUsbHandler(FgThread.get().getLooper(),
                InstrumentationRegistry.getContext(), mUsbDeviceManager, mUsbAlsaManager,
                mUsbSettingsManager, mUsbPermissionManager);
        sendBootCompleteMessages(mUsbHandler);
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_UPDATE_SCREEN_LOCK, 1));
        mUsbHandler.handleMessage(mUsbHandler.obtainMessage(MSG_UPDATE_SCREEN_LOCK, 0));

        assertNotEquals(mUsbHandler.getScreenUnlockedFunctions() & UsbManager.FUNCTION_MTP, 0);
        assertNotEquals(mUsbHandler.getEnabledFunctions() & UsbManager.FUNCTION_MTP, 0);
    }

    private static void sendBootCompleteMessages(Handler handler) {
        handler.handleMessage(handler.obtainMessage(MSG_BOOT_COMPLETED));
        handler.handleMessage(handler.obtainMessage(MSG_SYSTEM_READY));
    }
}
