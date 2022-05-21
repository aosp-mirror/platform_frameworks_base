/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.accessibilityservice.MagnificationConfig.MAGNIFICATION_MODE_FULLSCREEN;
import static android.accessibilityservice.MagnificationConfig.MAGNIFICATION_MODE_WINDOW;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN;
import static android.provider.Settings.Secure.ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.accessibilityservice.MagnificationConfig;
import android.graphics.Region;

import com.android.server.accessibility.magnification.FullScreenMagnificationController;
import com.android.server.accessibility.magnification.MagnificationController;
import com.android.server.accessibility.magnification.MagnificationProcessor;
import com.android.server.accessibility.magnification.WindowMagnificationManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

/**
 * Tests for the {@link MagnificationProcessor}
 */
public class MagnificationProcessorTest {

    private static final int TEST_DISPLAY = 0;
    private static final int SERVICE_ID = 42;
    private static final float TEST_SCALE = 1.8f;
    private static final float TEST_CENTER_X = 50.5f;
    private static final float TEST_CENTER_Y = 100.5f;
    private MagnificationProcessor mMagnificationProcessor;
    @Mock
    private MagnificationController mMockMagnificationController;
    @Mock
    private FullScreenMagnificationController mMockFullScreenMagnificationController;
    @Mock
    private WindowMagnificationManager mMockWindowMagnificationManager;
    FullScreenMagnificationControllerStub mFullScreenMagnificationControllerStub;
    WindowMagnificationManagerStub mWindowMagnificationManagerStub;

    @Before

    public void setup() {
        MockitoAnnotations.initMocks(this);
        mFullScreenMagnificationControllerStub = new FullScreenMagnificationControllerStub(
                mMockFullScreenMagnificationController);
        mWindowMagnificationManagerStub = new WindowMagnificationManagerStub(
                mMockWindowMagnificationManager);
        when(mMockMagnificationController.getFullScreenMagnificationController()).thenReturn(
                mMockFullScreenMagnificationController);
        when(mMockMagnificationController.getWindowMagnificationMgr()).thenReturn(
                mMockWindowMagnificationManager);
        mMagnificationProcessor = new MagnificationProcessor(mMockMagnificationController);
    }

    @Test
    public void getScale_fullscreenMode_expectedValue() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_FULLSCREEN)
                .setScale(TEST_SCALE).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        float scale = mMagnificationProcessor.getScale(TEST_DISPLAY);

        assertEquals(scale, TEST_SCALE, 0);
    }

    @Test
    public void getScale_windowMode_expectedValue() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_WINDOW)
                .setScale(TEST_SCALE).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        float scale = mMagnificationProcessor.getMagnificationConfig(TEST_DISPLAY).getScale();

        assertEquals(scale, TEST_SCALE, 0);
    }

    @Test
    public void getCenterX_canControlFullscreenMagnification_returnCenterX() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_FULLSCREEN)
                .setCenterX(TEST_CENTER_X).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        float centerX = mMagnificationProcessor.getCenterX(
                TEST_DISPLAY, /* canControlMagnification= */true);

        assertEquals(centerX, TEST_CENTER_X, 0);
    }

    @Test
    public void getCenterX_controlWindowMagnification_returnCenterX() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_WINDOW)
                .setCenterX(TEST_CENTER_X).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        float centerX = mMagnificationProcessor.getMagnificationConfig(TEST_DISPLAY).getCenterX();

        assertEquals(centerX, TEST_CENTER_X, 0);
    }

    @Test
    public void getCenterY_canControlFullscreenMagnification_returnCenterY() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_FULLSCREEN)
                .setCenterY(TEST_CENTER_Y).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        float centerY = mMagnificationProcessor.getCenterY(
                TEST_DISPLAY,  /* canControlMagnification= */false);

        assertEquals(centerY, TEST_CENTER_Y, 0);
    }

    @Test
    public void getCenterY_controlWindowMagnification_returnCenterY() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_WINDOW)
                .setCenterY(TEST_CENTER_Y).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        float centerY = mMagnificationProcessor.getMagnificationConfig(TEST_DISPLAY).getCenterY();

        assertEquals(centerY, TEST_CENTER_Y, 0);
    }

    @Test
    public void getMagnificationRegion_canControlFullscreenMagnification_returnRegion() {
        final Region region = new Region(10, 20, 100, 200);
        setMagnificationActivated(TEST_DISPLAY, MAGNIFICATION_MODE_FULLSCREEN);
        mMagnificationProcessor.getFullscreenMagnificationRegion(TEST_DISPLAY,
                region,  /* canControlMagnification= */true);

        verify(mMockFullScreenMagnificationController).getMagnificationRegion(eq(TEST_DISPLAY),
                eq(region));
    }

    @Test
    public void getMagnificationRegion_fullscreenModeNotRegistered_shouldRegisterThenUnregister() {
        final Region region = new Region(10, 20, 100, 200);
        setMagnificationActivated(TEST_DISPLAY, MAGNIFICATION_MODE_FULLSCREEN);
        doAnswer((invocation) -> {
            ((Region) invocation.getArguments()[1]).set(region);
            return null;
        }).when(mMockFullScreenMagnificationController).getMagnificationRegion(eq(TEST_DISPLAY),
                any());

        final Region result = new Region();
        mMagnificationProcessor.getFullscreenMagnificationRegion(TEST_DISPLAY,
                result, /* canControlMagnification= */true);
        assertEquals(region, result);
        verify(mMockFullScreenMagnificationController).register(TEST_DISPLAY);
        verify(mMockFullScreenMagnificationController).unregister(TEST_DISPLAY);
    }

    @Test
    public void getCurrentMagnificationRegion_windowModeActivated_returnRegion() {
        final Region region = new Region(10, 20, 100, 200);
        setMagnificationActivated(TEST_DISPLAY, MAGNIFICATION_MODE_WINDOW);
        doAnswer((invocation) -> {
            ((Region) invocation.getArguments()[1]).set(region);
            return null;
        }).when(mMockWindowMagnificationManager).getMagnificationSourceBounds(eq(TEST_DISPLAY),
                any());

        final Region result = new Region();
        mMagnificationProcessor.getCurrentMagnificationRegion(TEST_DISPLAY,
                result, /* canControlMagnification= */true);
        assertEquals(region, result);
    }

    @Test
    public void getCurrentMagnificationRegion_fullscreenModeActivated_returnRegion() {
        final Region region = new Region(10, 20, 100, 200);
        setMagnificationActivated(TEST_DISPLAY, MAGNIFICATION_MODE_FULLSCREEN);
        doAnswer((invocation) -> {
            ((Region) invocation.getArguments()[1]).set(region);
            return null;
        }).when(mMockFullScreenMagnificationController).getMagnificationRegion(eq(TEST_DISPLAY),
                any());

        final Region result = new Region();
        mMagnificationProcessor.getCurrentMagnificationRegion(TEST_DISPLAY,
                result, /* canControlMagnification= */true);
        assertEquals(region, result);
    }

    @Test
    public void getMagnificationCenterX_fullscreenModeNotRegistered_shouldRegisterThenUnregister() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_FULLSCREEN)
                .setCenterX(TEST_CENTER_X).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        final float result = mMagnificationProcessor.getCenterX(
                TEST_DISPLAY,  /* canControlMagnification= */ true);
        assertEquals(TEST_CENTER_X, result, 0);
        verify(mMockFullScreenMagnificationController).register(TEST_DISPLAY);
        verify(mMockFullScreenMagnificationController).unregister(TEST_DISPLAY);
    }

    @Test
    public void getMagnificationCenterY_fullscreenModeNotRegistered_shouldRegisterThenUnregister() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_FULLSCREEN)
                .setCenterY(TEST_CENTER_Y).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        final float result = mMagnificationProcessor.getCenterY(
                TEST_DISPLAY,  /* canControlMagnification= */ true);
        assertEquals(TEST_CENTER_Y, result, 0);
        verify(mMockFullScreenMagnificationController).register(TEST_DISPLAY);
        verify(mMockFullScreenMagnificationController).unregister(TEST_DISPLAY);
    }

    @Test
    public void getCurrentMode_configDefaultMode_returnActivatedMode() {
        final int targetMode = MAGNIFICATION_MODE_WINDOW;
        setMagnificationActivated(TEST_DISPLAY, targetMode);

        int currentMode = mMagnificationProcessor.getControllingMode(TEST_DISPLAY);

        assertEquals(MAGNIFICATION_MODE_WINDOW, currentMode);
    }

    @Test
    public void getCurrentMode_changeOtherDisplayMode_returnDefaultModeOnDefaultDisplay() {
        final int otherDisplayId = TEST_DISPLAY + 1;
        setMagnificationActivated(otherDisplayId, MAGNIFICATION_MODE_WINDOW);

        int currentMode = mMagnificationProcessor.getControllingMode(TEST_DISPLAY);

        assertFalse(mMockMagnificationController.isActivated(TEST_DISPLAY,
                ACCESSIBILITY_MAGNIFICATION_MODE_FULLSCREEN));
        assertFalse(mMockMagnificationController.isActivated(TEST_DISPLAY,
                ACCESSIBILITY_MAGNIFICATION_MODE_WINDOW));
        assertEquals(MAGNIFICATION_MODE_FULLSCREEN, currentMode);
    }

    @Test
    public void resetFullscreenMagnification_fullscreenMagnificationActivated() {
        setMagnificationActivated(TEST_DISPLAY, MAGNIFICATION_MODE_FULLSCREEN);

        mMagnificationProcessor.resetFullscreenMagnification(TEST_DISPLAY, /* animate= */false);

        verify(mMockFullScreenMagnificationController).reset(TEST_DISPLAY, false);
    }

    @Test
    public void resetCurrentMagnification_windowMagnificationActivated() {
        setMagnificationActivated(TEST_DISPLAY, MAGNIFICATION_MODE_WINDOW);

        mMagnificationProcessor.resetCurrentMagnification(TEST_DISPLAY, /* animate= */false);

        verify(mMockWindowMagnificationManager).disableWindowMagnification(TEST_DISPLAY, false,
                null);
    }

    @Test
    public void resetAllIfNeeded_resetFullscreenAndWindowMagnificationByConnectionId() {
        final int connectionId = 1;
        mMagnificationProcessor.resetAllIfNeeded(connectionId);

        verify(mMockFullScreenMagnificationController).resetAllIfNeeded(eq(connectionId));
        verify(mMockWindowMagnificationManager).resetAllIfNeeded(eq(connectionId));
    }

    @Test
    public void setMagnificationConfig_fullscreenModeNotRegistered_shouldRegister() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_FULLSCREEN)
                .setScale(TEST_SCALE)
                .setCenterX(TEST_CENTER_X)
                .setCenterY(TEST_CENTER_Y).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        final boolean result = mMagnificationProcessor.setMagnificationConfig(
                TEST_DISPLAY, config, true, SERVICE_ID);
        assertTrue(result);
        verify(mMockFullScreenMagnificationController).register(TEST_DISPLAY);
    }

    @Test
    public void setMagnificationConfig_windowMode_enableMagnification() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_WINDOW)
                .setScale(TEST_SCALE)
                .setCenterX(TEST_CENTER_X)
                .setCenterY(TEST_CENTER_Y).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        final boolean result = mMagnificationProcessor.setMagnificationConfig(
                TEST_DISPLAY, config, true, SERVICE_ID);

        assertTrue(result);
    }

    @Test
    public void getMagnificationConfig_fullscreenEnabled_expectedConfigValues() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_FULLSCREEN)
                .setScale(TEST_SCALE)
                .setCenterX(TEST_CENTER_X)
                .setCenterY(TEST_CENTER_Y).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        final MagnificationConfig result = mMagnificationProcessor.getMagnificationConfig(
                TEST_DISPLAY);

        assertConfigEquals(config, result);
    }

    @Test
    public void getMagnificationConfig_windowEnabled_expectedConfigValues() {
        final MagnificationConfig config = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_WINDOW)
                .setScale(TEST_SCALE)
                .setCenterX(TEST_CENTER_X)
                .setCenterY(TEST_CENTER_Y).build();
        setMagnificationActivated(TEST_DISPLAY, config);

        final MagnificationConfig result = mMagnificationProcessor.getMagnificationConfig(
                TEST_DISPLAY);

        assertConfigEquals(config, result);
    }

    @Test
    public void setWindowModeConfig_fullScreenMode_transitionConfigMode() {
        final int currentActivatedMode = MAGNIFICATION_MODE_FULLSCREEN;
        final MagnificationConfig oldConfig = new MagnificationConfig.Builder()
                .setMode(currentActivatedMode)
                .setScale(TEST_SCALE)
                .setCenterX(TEST_CENTER_X)
                .setCenterY(TEST_CENTER_Y).build();
        setMagnificationActivated(TEST_DISPLAY, oldConfig);
        final MagnificationConfig targetConfig = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_WINDOW)
                .setScale(TEST_SCALE)
                .setCenterX(TEST_CENTER_X + 10)
                .setCenterY(TEST_CENTER_Y + 10).build();

        mMagnificationProcessor.setMagnificationConfig(TEST_DISPLAY, targetConfig, false,
                SERVICE_ID);

        verify(mMockMagnificationController).transitionMagnificationConfigMode(eq(TEST_DISPLAY),
                eq(targetConfig), eq(false), eq(SERVICE_ID));
    }

    @Test
    public void setConfigWithDefaultMode_fullScreenMode_expectedConfig() {
        final MagnificationConfig oldConfig = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_FULLSCREEN)
                .setScale(TEST_SCALE)
                .setCenterX(TEST_CENTER_X)
                .setCenterY(TEST_CENTER_Y).build();
        setMagnificationActivated(TEST_DISPLAY, oldConfig);
        final MagnificationConfig targetConfig = new MagnificationConfig.Builder()
                .setScale(TEST_SCALE + 1)
                .setCenterX(TEST_CENTER_X + 10)
                .setCenterY(TEST_CENTER_Y + 10).build();

        mMagnificationProcessor.setMagnificationConfig(TEST_DISPLAY, targetConfig, false,
                SERVICE_ID);

        verify(mMockMagnificationController, never()).transitionMagnificationConfigMode(
                eq(TEST_DISPLAY), any(MagnificationConfig.class), eq(false), eq(SERVICE_ID));
        final MagnificationConfig expectedConfig = new MagnificationConfig.Builder()
                .setMode(MAGNIFICATION_MODE_FULLSCREEN)
                .setScale(TEST_SCALE + 1)
                .setCenterX(TEST_CENTER_X + 10)
                .setCenterY(TEST_CENTER_Y + 10).build();
        assertConfigEquals(expectedConfig,
                mMagnificationProcessor.getMagnificationConfig(TEST_DISPLAY));
    }

    @Test
    public void setWindowModeConfig_transitionToFullScreenModeWithAnimation_transitionConfigMode() {
        final int currentActivatedMode = MAGNIFICATION_MODE_WINDOW;
        final int targetMode = MAGNIFICATION_MODE_WINDOW;
        final MagnificationConfig oldConfig = new MagnificationConfig.Builder()
                .setMode(currentActivatedMode)
                .setScale(TEST_SCALE)
                .setCenterX(TEST_CENTER_X)
                .setCenterY(TEST_CENTER_Y).build();
        setMagnificationActivated(TEST_DISPLAY, oldConfig);
        final MagnificationConfig newConfig = new MagnificationConfig.Builder()
                .setMode(targetMode)
                .setScale(TEST_SCALE)
                .setCenterX(TEST_CENTER_X + 10)
                .setCenterY(TEST_CENTER_Y + 10).build();
        // Has magnification animation running
        when(mMockMagnificationController.hasDisableMagnificationCallback(TEST_DISPLAY)).thenReturn(
                true);

        mMagnificationProcessor.setMagnificationConfig(TEST_DISPLAY, newConfig, false, SERVICE_ID);

        verify(mMockMagnificationController).transitionMagnificationConfigMode(eq(TEST_DISPLAY),
                eq(newConfig), anyBoolean(), anyInt());
    }

    private void setMagnificationActivated(int displayId, int configMode) {
        setMagnificationActivated(displayId,
                new MagnificationConfig.Builder().setMode(configMode).build());
    }

    private void setMagnificationActivated(int displayId, MagnificationConfig config) {
        when(mMockMagnificationController.isActivated(displayId, config.getMode())).thenReturn(
                true);
        mMagnificationProcessor.setMagnificationConfig(displayId, config, false, SERVICE_ID);
        if (config.getMode() == MAGNIFICATION_MODE_FULLSCREEN) {
            when(mMockMagnificationController.isActivated(displayId,
                    MAGNIFICATION_MODE_WINDOW)).thenReturn(false);
            mFullScreenMagnificationControllerStub.resetAndStubMethods();
            mMockFullScreenMagnificationController.setScaleAndCenter(displayId, config.getScale(),
                    config.getCenterX(), config.getCenterY(), false, SERVICE_ID);
        } else if (config.getMode() == MAGNIFICATION_MODE_WINDOW) {
            when(mMockMagnificationController.isActivated(displayId,
                    MAGNIFICATION_MODE_FULLSCREEN)).thenReturn(false);
            mWindowMagnificationManagerStub.resetAndStubMethods();
            mMockWindowMagnificationManager.enableWindowMagnification(displayId, config.getScale(),
                    config.getCenterX(), config.getCenterY());
        }
    }

    private void assertConfigEquals(MagnificationConfig expected, MagnificationConfig actual) {
        assertEquals(expected.getMode(), actual.getMode());
        assertEquals(expected.getScale(), actual.getScale(), 0);
        assertEquals(expected.getCenterX(), actual.getCenterX(), 0);
        assertEquals(expected.getCenterY(), actual.getCenterY(), 0);
    }

    private static class FullScreenMagnificationControllerStub {
        private final FullScreenMagnificationController mScreenMagnificationController;
        private float mScale = 1.0f;
        private float mCenterX = 0;
        private float mCenterY = 0;
        private boolean mIsRegistered = false;

        FullScreenMagnificationControllerStub(
                FullScreenMagnificationController screenMagnificationController) {
            mScreenMagnificationController = screenMagnificationController;
        }

        private void stubMethods() {
            doAnswer(invocation -> mScale).when(mScreenMagnificationController).getScale(
                    TEST_DISPLAY);
            doAnswer(invocation -> mCenterX).when(mScreenMagnificationController).getCenterX(
                    TEST_DISPLAY);
            doAnswer(invocation -> mCenterY).when(mScreenMagnificationController).getCenterY(
                    TEST_DISPLAY);
            doAnswer(invocation -> mIsRegistered).when(mScreenMagnificationController).isRegistered(
                    TEST_DISPLAY);
            Answer enableMagnificationStubAnswer = invocation -> {
                mScale = invocation.getArgument(1);
                mCenterX = invocation.getArgument(2);
                mCenterY = invocation.getArgument(3);
                return true;
            };
            doAnswer(enableMagnificationStubAnswer).when(
                    mScreenMagnificationController).setScaleAndCenter(eq(TEST_DISPLAY), anyFloat(),
                    anyFloat(), anyFloat(), anyBoolean(), eq(SERVICE_ID));

            Answer registerStubAnswer = invocation -> {
                mIsRegistered = true;
                return true;
            };
            doAnswer(registerStubAnswer).when(
                    mScreenMagnificationController).register(eq(TEST_DISPLAY));

            Answer unregisterStubAnswer = invocation -> {
                mIsRegistered = false;
                return true;
            };
            doAnswer(unregisterStubAnswer).when(
                    mScreenMagnificationController).unregister(eq(TEST_DISPLAY));
            doAnswer(unregisterStubAnswer).when(
                    mScreenMagnificationController).reset(eq(TEST_DISPLAY), anyBoolean());
        }

        public void resetAndStubMethods() {
            Mockito.reset(mScreenMagnificationController);
            stubMethods();
        }
    }

    private static class WindowMagnificationManagerStub {
        private final WindowMagnificationManager mWindowMagnificationManager;
        private float mScale = 1.0f;
        private float mCenterX = 0;
        private float mCenterY = 0;

        WindowMagnificationManagerStub(
                WindowMagnificationManager windowMagnificationManager) {
            mWindowMagnificationManager = windowMagnificationManager;
        }

        private void stubMethods() {
            doAnswer(invocation -> mScale).when(mWindowMagnificationManager).getScale(
                    TEST_DISPLAY);
            doAnswer(invocation -> mCenterX).when(mWindowMagnificationManager).getCenterX(
                    TEST_DISPLAY);
            doAnswer(invocation -> mCenterY).when(mWindowMagnificationManager).getCenterY(
                    TEST_DISPLAY);
            Answer enableWindowMagnificationStubAnswer = invocation -> {
                mScale = invocation.getArgument(1);
                mCenterX = invocation.getArgument(2);
                mCenterY = invocation.getArgument(3);
                return true;
            };
            doAnswer(enableWindowMagnificationStubAnswer).when(
                    mWindowMagnificationManager).enableWindowMagnification(eq(TEST_DISPLAY),
                    anyFloat(), anyFloat(), anyFloat());
            doAnswer(enableWindowMagnificationStubAnswer).when(
                    mWindowMagnificationManager).enableWindowMagnification(eq(TEST_DISPLAY),
                    anyFloat(), anyFloat(), anyFloat(), any(), anyInt());
        }

        public void resetAndStubMethods() {
            Mockito.reset(mWindowMagnificationManager);
            stubMethods();
        }
    }
}
