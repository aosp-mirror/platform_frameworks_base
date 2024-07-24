/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.accessibility;

import static android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE;
import static android.hardware.camera2.CameraCharacteristics.LENS_FACING;
import static android.hardware.camera2.CameraMetadata.LENS_FACING_BACK;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.accessibility.FlashNotificationsController.ACTION_FLASH_NOTIFICATION_START_PREVIEW;
import static com.android.server.accessibility.FlashNotificationsController.ACTION_FLASH_NOTIFICATION_STOP_PREVIEW;
import static com.android.server.accessibility.FlashNotificationsController.EXTRA_FLASH_NOTIFICATION_PREVIEW_COLOR;
import static com.android.server.accessibility.FlashNotificationsController.EXTRA_FLASH_NOTIFICATION_PREVIEW_TYPE;
import static com.android.server.accessibility.FlashNotificationsController.PREVIEW_TYPE_LONG;
import static com.android.server.accessibility.FlashNotificationsController.PREVIEW_TYPE_SHORT;
import static com.android.server.accessibility.FlashNotificationsController.SETTING_KEY_CAMERA_FLASH_NOTIFICATION;
import static com.android.server.accessibility.FlashNotificationsController.SETTING_KEY_SCREEN_FLASH_NOTIFICATION;
import static com.android.server.accessibility.FlashNotificationsController.SETTING_KEY_SCREEN_FLASH_NOTIFICATION_COLOR;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import static java.lang.Integer.max;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.PlayerBase;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.UserHandle;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContentResolver;
import android.testing.TestableContext;
import android.testing.TestableLooper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.NonNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tests for {@link FlashNotificationsController}.
 */
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class FlashNotificationsControllerTest {
    private static final String CALL_TAG = "com.android.server.telecom";
    private static final String NOTI_TAG = "android";
    private static final String NOTI_REASON_PKG = "noti.reason.pkg";
    private static final int COLOR_YELLOW = 0x66FFFF00;
    private static final int COLOR_BLUE = 0x4d0000fe;

    @Rule
    public final TestableContext mTestableContext = new TestableContext(
            getInstrumentation().getTargetContext());

    private final Map<String, CameraInfo> mCameraInfoMap = new HashMap<>();
    private final Set<View> mViews = new HashSet<>();

    @Mock
    private IBinder mMockToken;
    @Mock
    private WindowManager mMockWindowManager;
    @Mock
    private AudioManager mMockAudioManager;

    private final List<AudioPlaybackConfiguration> mAudioConfigsWithAlarm = getConfigWithAlarm();

    private CameraManager mCameraManager;
    private AudioManager.AudioPlaybackCallback mAudioPlaybackCallback;
    private TestableContentResolver mTestableContentResolver;

    private TestableLooper mTestableLooper;
    private Handler mTestHandler;
    private HandlerThread mFlashHandlerThread;
    private Handler mFlashHandler;

    private FlashNotificationsController mController;

    private int mScreenFlashCount = 0;
    private int mLastFlashedViewColor = Color.TRANSPARENT;

    private final CameraManager.TorchCallback mTorchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(@NonNull String cameraId, boolean enabled) {
            CameraInfo info = mCameraInfoMap.getOrDefault(cameraId,
                    new CameraInfo(false));
            info.setEnabled(enabled);
            mCameraInfoMap.put(cameraId, info);
        }
    };

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);
        mTestHandler = new Handler(mTestableLooper.getLooper());
        mFlashHandlerThread = new HandlerThread("TestFlashHandlerThread");
        mFlashHandlerThread.start();
        mFlashHandler = mFlashHandlerThread.getThreadHandler();

        doAnswer(invocation -> {
            final View view = invocation.getArgument(0);
            mLastFlashedViewColor = getBackgroundColor(view);
            mViews.add(view);
            return null;
        }).when(mMockWindowManager).addView(any(View.class), any(ViewGroup.LayoutParams.class));
        doAnswer(invocation -> {
            final View view = invocation.getArgument(0);
            final boolean isAnyViewAdded = !mViews.isEmpty();
            mViews.remove(view);
            if (isAnyViewAdded && mViews.isEmpty()) mScreenFlashCount++;
            return null;
        }).when(mMockWindowManager).removeView(any(View.class));
        mTestableContext.addMockSystemService(Context.WINDOW_SERVICE, mMockWindowManager);

        doAnswer(invocation -> {
            mAudioPlaybackCallback = invocation.getArgument(0);
            return null;
        }).when(mMockAudioManager).registerAudioPlaybackCallback(
                any(AudioManager.AudioPlaybackCallback.class), any(Handler.class));
        mTestableContext.addMockSystemService(Context.AUDIO_SERVICE, mMockAudioManager);

        mCameraManager = mTestableContext.getSystemService(CameraManager.class);
        if (mCameraManager != null) {
            try {
                for (String id : mCameraManager.getCameraIdList()) {
                    CameraCharacteristics value = mCameraManager.getCameraCharacteristics(id);
                    Boolean available = value.get(FLASH_INFO_AVAILABLE);
                    Integer facing = value.get(LENS_FACING);
                    mCameraInfoMap.put(id, new CameraInfo(
                            available != null && available
                                    && facing != null && facing == LENS_FACING_BACK
                    ));
                }
            } catch (CameraAccessException ignored) {
            }
            mCameraManager.registerTorchCallback(mTorchCallback, mTestHandler);
        }

        mTestableContentResolver = mTestableContext.getContentResolver();
        putCameraFlash(false);
        putScreenFlash(false);
        putScreenColor(Color.TRANSPARENT);
    }

    @After
    public void tearDown() {
        mCameraManager.unregisterTorchCallback(mTorchCallback);
        mFlashHandlerThread.quit();
        mTestableLooper.processAllMessages();
    }

    @Test
    public void testCallSequence_putCameraFlashTrue_assertCameraFlashedScreenNotFlashed()
            throws InterruptedException {
        assumeCameraTorchAvailable();
        putCameraFlash(true);
        initController(mFlashHandler);

        simulateCallSequence();

        assertThat(getCameraFlashedCount()).isGreaterThan(0);
        assertThat(mScreenFlashCount).isEqualTo(0);
    }

    @Test
    public void testCallSequence_putScreenFlashTrue_assertScreenFlashedCameraNotFlashed()
            throws InterruptedException {
        putScreenFlash(true);
        putScreenColor(COLOR_YELLOW);
        initController(mFlashHandler);

        simulateCallSequence();

        assertThat(mScreenFlashCount).isGreaterThan(0);
        assertThat(getCameraFlashedCount()).isEqualTo(0);
    }

    @Test
    public void testAlarmSequence_putCameraFlashTrue_assertCameraFlashedScreenNotFlashed()
            throws InterruptedException {
        assumeCameraTorchAvailable();
        putCameraFlash(true);
        initController(mFlashHandler);

        simulateAlarmSequence();

        assertThat(getCameraFlashedCount()).isGreaterThan(0);
        assertThat(mScreenFlashCount).isEqualTo(0);
    }

    @Test
    public void testAlarmSequence_putScreenFlashTrue_assertScreenFlashedCameraNotFlashed()
            throws InterruptedException {
        putScreenFlash(true);
        putScreenColor(COLOR_YELLOW);
        initController(mFlashHandler);

        simulateAlarmSequence();

        assertThat(mScreenFlashCount).isGreaterThan(0);
        assertThat(getCameraFlashedCount()).isEqualTo(0);

    }

    @Test
    public void testEvent_putCameraFlashTrue_assertCameraFlashedScreenNotFlashed() {
        assumeCameraTorchAvailable();
        putCameraFlash(true);
        initController(mTestHandler);

        simulateNotificationEvent();

        assertThat(getCameraFlashedCount()).isGreaterThan(0);
        assertThat(mScreenFlashCount).isEqualTo(0);
    }

    @Test
    public void testEvent_putScreenFlashTrue_assertScreenFlashedCameraNotFlashed() {
        putScreenFlash(true);
        putScreenColor(COLOR_YELLOW);
        initController(mTestHandler);

        simulateNotificationEvent();

        assertThat(mScreenFlashCount).isGreaterThan(0);
        assertThat(getCameraFlashedCount()).isEqualTo(0);
    }

    @Test
    public void testShortPreview_putCameraFlashTrue_assertCameraFlashedScreenNotFlashed() {
        assumeCameraTorchAvailable();
        putCameraFlash(true);
        initController(mTestHandler);

        simulateShortPreview();

        assertThat(getCameraFlashedCount()).isGreaterThan(0);
        assertThat(mScreenFlashCount).isEqualTo(0);
    }

    @Test
    public void testShortPreview_putScreenFlashTrue_assertScreenFlashedCameraNotFlashed() {
        putScreenFlash(true);
        putScreenColor(COLOR_YELLOW);
        initController(mTestHandler);

        simulateShortPreview();

        assertThat(mScreenFlashCount).isGreaterThan(0);
        assertThat(getCameraFlashedCount()).isEqualTo(0);
    }

    @Test
    public void testLongPreview_assertScreenFlashed()
            throws InterruptedException {
        initController(mFlashHandler);

        simulateLongPreview(COLOR_YELLOW);

        assertThat(mScreenFlashCount).isGreaterThan(0);
    }

    @Test
    public void testLongPreview_putScreenFlashColorBlue_assertScreenFlashedYellow()
            throws InterruptedException {
        putScreenColor(COLOR_YELLOW);
        initController(mFlashHandler);

        simulateLongPreview(COLOR_BLUE);

        assertThat(mLastFlashedViewColor).isEqualTo(COLOR_BLUE);
    }

    @Test
    public void testLongPreview_putCameraFlashTrue_assertNotCameraFlashed()
            throws InterruptedException {
        assumeCameraTorchAvailable();
        putCameraFlash(true);
        initController(mFlashHandler);

        simulateLongPreview(COLOR_YELLOW);

        assertThat(getCameraFlashedCount()).isEqualTo(0);
    }

    @Test
    public void testStartFlashNotificationSequence_invalidToken() {
        initController(mTestHandler);

        assertThat(mController.startFlashNotificationSequence(CALL_TAG,
                AccessibilityManager.FLASH_REASON_CALL, null)).isFalse();
    }

    @Test
    public void testOpenedCameraTest() {
        assumeCameraTorchAvailable();
        putCameraFlash(true);
        putScreenFlash(true);
        putScreenColor(COLOR_YELLOW);
        initController(mTestHandler);

        simulateCameraOpened();
        simulateNotificationEvent();
        simulateCameraClosed();

        assertThat(getCameraFlashedCount()).isEqualTo(0);
    }

    private void initController(Handler flashHandler) {
        mController = new FlashNotificationsController(mTestableContext,
                flashHandler, mTestHandler);
        mController.mFlashBroadcastReceiver.onReceive(mTestableContext,
                new Intent(Intent.ACTION_BOOT_COMPLETED));
    }

    private void assumeCameraTorchAvailable() {
        assumeTrue(mCameraManager != null);
        assumeTrue(!mCameraInfoMap.isEmpty());
        assumeTrue(mCameraInfoMap.values().stream().anyMatch(info -> info.mIsValid));
    }

    private void simulateCallSequence() throws InterruptedException {
        mController.startFlashNotificationSequence(CALL_TAG,
                AccessibilityManager.FLASH_REASON_CALL, mMockToken);
        processLooper(2500);
        Thread.sleep(2500);

        mController.stopFlashNotificationSequence(CALL_TAG);
        processLooper(500);
        Thread.sleep(500);
    }

    private void simulateAlarmSequence() throws InterruptedException {
        mAudioPlaybackCallback.onPlaybackConfigChanged(mAudioConfigsWithAlarm);
        processLooper(2500);
        Thread.sleep(2500);

        mAudioPlaybackCallback.onPlaybackConfigChanged(Collections.emptyList());
        processLooper(500);
        Thread.sleep(500);
    }

    private void simulateNotificationEvent() {
        mController.startFlashNotificationEvent(NOTI_TAG,
                AccessibilityManager.FLASH_REASON_NOTIFICATION, NOTI_REASON_PKG);

        processLooper(3000);
    }

    private void simulateShortPreview() {
        Intent intent = new Intent(ACTION_FLASH_NOTIFICATION_START_PREVIEW);
        intent.putExtra(EXTRA_FLASH_NOTIFICATION_PREVIEW_TYPE, PREVIEW_TYPE_SHORT);
        mController.mFlashBroadcastReceiver.onReceive(mTestableContext, intent);

        processLooper(3000);
    }

    private void simulateLongPreview(int color) throws InterruptedException {
        Intent startIntent = new Intent(ACTION_FLASH_NOTIFICATION_START_PREVIEW);
        startIntent.putExtra(EXTRA_FLASH_NOTIFICATION_PREVIEW_TYPE, PREVIEW_TYPE_LONG);
        startIntent.putExtra(EXTRA_FLASH_NOTIFICATION_PREVIEW_COLOR, color);
        mController.mFlashBroadcastReceiver.onReceive(mTestableContext, startIntent);
        processLooper(2500);
        Thread.sleep(2500);

        Intent stopIntent = new Intent(ACTION_FLASH_NOTIFICATION_STOP_PREVIEW);
        mController.mFlashBroadcastReceiver.onReceive(mTestableContext, stopIntent);
        processLooper(500);
        Thread.sleep(500);
    }

    private void processLooper(long millis) {
        mTestableLooper.moveTimeForward(millis);
        mTestableLooper.processAllMessages();
    }

    private static List<AudioPlaybackConfiguration> getConfigWithAlarm() {
        List<AudioPlaybackConfiguration> list = new ArrayList<>();

        AudioPlaybackConfiguration config = new AudioPlaybackConfiguration(
                mock(PlayerBase.PlayerIdCard.class), 0, 0, 0);
        config.handleStateEvent(AudioPlaybackConfiguration.PLAYER_STATE_STARTED,
                AudioPlaybackConfiguration.PLAYER_DEVICEID_INVALID);

        AudioAttributes.Builder builder = new AudioAttributes.Builder();
        builder.setUsage(AudioAttributes.USAGE_ALARM);
        AudioAttributes attr = builder.build();

        config.handleAudioAttributesEvent(attr);

        list.add(config);
        return list;
    }

    private int getCameraFlashedCount() {
        int count = 0;
        for (CameraInfo info : mCameraInfoMap.values()) {
            count = max(count, info.mTorchFlashCount);
        }
        return count;
    }

    private int getBackgroundColor(View view) {
        final Drawable background = view.getBackground();
        if (background instanceof ColorDrawable) {
            return ((ColorDrawable) background).getColor();
        } else {
            return Color.TRANSPARENT;
        }
    }

    private void putCameraFlash(boolean value) {
        Settings.System.putIntForUser(mTestableContentResolver,
                SETTING_KEY_CAMERA_FLASH_NOTIFICATION,
                value ? 1 : 0, UserHandle.USER_CURRENT);
    }

    private void putScreenFlash(boolean value) {
        Settings.System.putIntForUser(mTestableContentResolver,
                SETTING_KEY_SCREEN_FLASH_NOTIFICATION,
                value ? 1 : 0, UserHandle.USER_CURRENT);
    }

    private void putScreenColor(int color) {
        Settings.System.putIntForUser(mTestableContentResolver,
                SETTING_KEY_SCREEN_FLASH_NOTIFICATION_COLOR,
                color, UserHandle.USER_CURRENT);
    }

    private void simulateCameraOpened() {
        for (String cameraId : mCameraInfoMap.keySet()) {
            System.out.println("simulate open camera: " + cameraId);
            mController.mTorchAvailabilityCallback.onCameraOpened(cameraId, "");
        }
        processLooper(200);
    }

    private void simulateCameraClosed() {
        for (String cameraId : mCameraInfoMap.keySet()) {
            System.out.println("simulate close camera: " + cameraId);
            mController.mTorchAvailabilityCallback.onCameraClosed(cameraId);
        }
        processLooper(200);
    }

    private static class CameraInfo {
        final boolean mIsValid;
        boolean mEnabled = false;
        int mTorchFlashCount = 0;

        CameraInfo(boolean isValid) {
            mIsValid = isValid;
        }

        void setEnabled(boolean enabled) {
            if (mEnabled && !enabled) mTorchFlashCount++;
            mEnabled = enabled;
        }
    }
}
