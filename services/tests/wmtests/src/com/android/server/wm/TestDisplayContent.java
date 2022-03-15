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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;
import static android.view.WindowManagerPolicyConstants.NAV_BAR_BOTTOM;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.display.DisplayManagerGlobal;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;

import com.android.server.wm.DisplayWindowSettings.SettingsProvider.SettingsEntry;

import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

class TestDisplayContent extends DisplayContent {

    public static final int DEFAULT_LOGICAL_DISPLAY_DENSITY = 300;

    /** Please use the {@link Builder} to create, visible for use in test builder overrides only. */
    TestDisplayContent(RootWindowContainer rootWindowContainer, Display display) {
        super(display, rootWindowContainer);
        // Normally this comes from display-properties as exposed by WM. Without that, just
        // hard-code to FULLSCREEN for tests.
        setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        spyOn(this);
        forAllTaskDisplayAreas(taskDisplayArea -> {
            spyOn(taskDisplayArea);
        });
        final DisplayRotation displayRotation = getDisplayRotation();
        spyOn(displayRotation);
        doAnswer(invocation -> {
            // Bypass all the rotation animation and display freezing stuff for testing and just
            // set the rotation we want for the display
            final int oldRotation = displayRotation.getRotation();
            final int rotation = displayRotation.rotationForOrientation(
                    displayRotation.getLastOrientation(), oldRotation);
            if (oldRotation == rotation) {
                return false;
            }
            setLayoutNeeded();
            displayRotation.setRotation(rotation);
            return true;
        }).when(displayRotation).updateRotationUnchecked(anyBoolean());

        final InputMonitor inputMonitor = getInputMonitor();
        spyOn(inputMonitor);
        doNothing().when(inputMonitor).resumeDispatchingLw(any());
    }

    public static class Builder {
        private final DisplayInfo mInfo;
        private boolean mCanRotate = true;
        private int mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        private int mPosition = POSITION_BOTTOM;
        protected final ActivityTaskManagerService mService;
        private boolean mSystemDecorations = false;
        private int mStatusBarHeight = 0;
        private SettingsEntry mOverrideSettings;
        private DisplayMetrics mDisplayMetrics;
        @Mock
        Context mMockContext;
        @Mock
        Resources mResources;

        Builder(ActivityTaskManagerService service, int width, int height) {
            mService = service;
            mInfo = new DisplayInfo();
            mService.mContext.getDisplay().getDisplayInfo(mInfo);
            mInfo.logicalWidth = width;
            mInfo.logicalHeight = height;
            mInfo.logicalDensityDpi = DEFAULT_LOGICAL_DISPLAY_DENSITY;
            mInfo.displayCutout = null;
            // Set unique ID so physical display overrides are not inheritted from
            // DisplayWindowSettings.
            mInfo.uniqueId = generateUniqueId();
            mDisplayMetrics = new DisplayMetrics();
            updateDisplayMetrics();
        }
        Builder(ActivityTaskManagerService service, DisplayInfo info) {
            mService = service;
            mInfo = info;
            // Set unique ID so physical display overrides are not inheritted from
            // DisplayWindowSettings.
            mInfo.uniqueId = generateUniqueId();
        }
        private String generateUniqueId() {
            return "TEST_DISPLAY_CONTENT_" + System.currentTimeMillis();
        }
        Builder setOverrideSettings(@Nullable SettingsEntry overrideSettings) {
            mOverrideSettings = overrideSettings;
            return this;
        }
        Builder setSystemDecorations(boolean yes) {
            mSystemDecorations = yes;
            return this;
        }
        Builder setPosition(int position) {
            mPosition = position;
            return this;
        }
        Builder setUniqueId(String uniqueId) {
            mInfo.uniqueId = uniqueId;
            return this;
        }
        Builder setType(int type) {
            mInfo.type = type;
            return this;
        }
        Builder setOwnerUid(int ownerUid) {
            mInfo.ownerUid = ownerUid;
            return this;
        }
        Builder setNotch(int height) {
            mInfo.displayCutout = new DisplayCutout(
                    Insets.of(0, height, 0, 0), null, new Rect(20, 0, 80, height), null, null);
            return this;
        }
        Builder setStatusBarHeight(int height) {
            mStatusBarHeight = height;
            return this;
        }
        Builder setCanRotate(boolean canRotate) {
            mCanRotate = canRotate;
            return this;
        }
        Builder setWindowingMode(int windowingMode) {
            mWindowingMode = windowingMode;
            return this;
        }
        Builder setDensityDpi(int dpi) {
            mInfo.logicalDensityDpi = dpi;
            return this;
        }
        Builder updateDisplayMetrics() {
            mInfo.getAppMetrics(mDisplayMetrics);
            return this;
        }
        Builder setDefaultMinTaskSizeDp(int valueDp) {
            MockitoAnnotations.initMocks(this);
            doReturn(mMockContext).when(mService.mContext).createConfigurationContext(any());
            doReturn(mResources).when(mMockContext).getResources();
            doReturn(valueDp * mDisplayMetrics.density)
                    .when(mResources)
                    .getDimension(
                        com.android.internal.R.dimen.default_minimal_size_resizable_task);
            return this;
        }
        TestDisplayContent createInternal(Display display) {
            return new TestDisplayContent(mService.mRootWindowContainer, display);
        }
        TestDisplayContent build() {
            SystemServicesTestRule.checkHoldsLock(mService.mGlobalLock);

            if (mOverrideSettings != null) {
                mService.mWindowManager.mDisplayWindowSettingsProvider
                        .updateOverrideSettings(mInfo, mOverrideSettings);
            }

            final int displayId = SystemServicesTestRule.sNextDisplayId++;
            final Display display = new Display(DisplayManagerGlobal.getInstance(), displayId,
                    mInfo, DEFAULT_DISPLAY_ADJUSTMENTS);
            final TestDisplayContent newDisplay = createInternal(display);
            // disable the normal system decorations
            final DisplayPolicy displayPolicy = newDisplay.getDisplayPolicy();
            spyOn(displayPolicy);
            if (mSystemDecorations) {
                doReturn(true).when(newDisplay).supportsSystemDecorations();
                doReturn(true).when(displayPolicy).hasNavigationBar();
                doReturn(NAV_BAR_BOTTOM).when(displayPolicy).navigationBarPosition(anyInt());
                doReturn(20).when(displayPolicy).getNavigationBarHeight(anyInt());
            } else {
                doReturn(false).when(displayPolicy).hasNavigationBar();
                doReturn(false).when(displayPolicy).hasStatusBar();
                doReturn(false).when(newDisplay).supportsSystemDecorations();
            }
            // Update the display policy to make the screen fully turned on so animation is allowed
            displayPolicy.screenTurnedOn(null /* screenOnListener */);
            displayPolicy.finishKeyguardDrawn();
            displayPolicy.finishWindowsDrawn();
            displayPolicy.finishScreenTurningOn();
            if (mStatusBarHeight > 0) {
                doReturn(true).when(displayPolicy).hasStatusBar();
                doAnswer(invocation -> {
                    Rect inOutInsets = (Rect) invocation.getArgument(0);
                    inOutInsets.top = mStatusBarHeight;
                    return null;
                }).when(displayPolicy).convertNonDecorInsetsToStableInsets(any(), anyInt());
            }
            Configuration c = new Configuration();
            newDisplay.computeScreenConfiguration(c);
            c.windowConfiguration.setWindowingMode(mWindowingMode);
            newDisplay.onRequestedOverrideConfigurationChanged(c);
            if (!mCanRotate) {
                final DisplayRotation displayRotation = newDisplay.getDisplayRotation();
                doReturn(true).when(displayRotation).isFixedToUserRotation();
            }
            // Please add stubbing before this line. Services will start using this display in other
            // threads immediately after adding it to hierarchy. Calling doAnswer() type of stubbing
            // reduces chance of races, but still doesn't eliminate race conditions.
            mService.mRootWindowContainer.addChild(newDisplay, mPosition);

            // Set the default focused TDA.
            newDisplay.onLastFocusedTaskDisplayAreaChanged(newDisplay.getDefaultTaskDisplayArea());

            return newDisplay;
        }
    }
}
