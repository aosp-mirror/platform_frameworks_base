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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_DESTROY;
import static android.view.WindowManager.REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.DisplayRotation.FIXED_TO_USER_ROTATION_DEFAULT;
import static com.android.server.wm.DisplayRotation.FIXED_TO_USER_ROTATION_DISABLED;
import static com.android.server.wm.DisplayRotation.FIXED_TO_USER_ROTATION_ENABLED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Matchers.eq;

import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.platform.test.annotations.Presubmit;
import android.util.Xml;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;
import android.view.Surface;

import androidx.test.filters.SmallTest;

import com.android.server.LocalServices;
import com.android.server.policy.WindowManagerPolicy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tests for the {@link DisplayWindowSettings} class.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayWindowSettingsTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DisplayWindowSettingsTests extends WindowTestsBase {

    private static final File TEST_FOLDER = getInstrumentation().getTargetContext().getCacheDir();
    private DisplayWindowSettings mTarget;

    private DisplayInfo mPrivateDisplayInfo;

    private DisplayContent mPrimaryDisplay;
    private DisplayContent mSecondaryDisplay;
    private DisplayContent mPrivateDisplay;

    private TestStorage mStorage;

    @Before
    public void setUp() throws Exception {
        deleteRecursively(TEST_FOLDER);

        mWm.mAtmService.mSupportsFreeformWindowManagement = false;
        mWm.setIsPc(false);
        mWm.setForceDesktopModeOnExternalDisplays(false);

        mStorage = new TestStorage();
        mTarget = new DisplayWindowSettings(mWm, mStorage);

        mPrimaryDisplay = mWm.getDefaultDisplayContentLocked();
        mSecondaryDisplay = mDisplayContent;
        assertNotEquals(Display.DEFAULT_DISPLAY, mSecondaryDisplay.getDisplayId());

        mPrivateDisplayInfo = new DisplayInfo(mDisplayInfo);
        mPrivateDisplayInfo.flags |= Display.FLAG_PRIVATE;
        mPrivateDisplay = createNewDisplay(mPrivateDisplayInfo);
        assertNotEquals(Display.DEFAULT_DISPLAY, mPrivateDisplay.getDisplayId());
        assertNotEquals(mSecondaryDisplay.getDisplayId(), mPrivateDisplay.getDisplayId());
    }

    @After
    public void tearDown() {
        deleteRecursively(TEST_FOLDER);

        // TODO(b/121296525): We may want to restore other display settings (not only overscans in
        // testPersistOverscan*test) on mPrimaryDisplay and mSecondaryDisplay back to default
        // values after each test finishes, since we are going to reuse a singleton
        // WindowManagerService instance among all tests that extend {@link WindowTestsBase} class
        // (b/113239988).
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreen_NoFreeformSupport() {
        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreen_HasFreeformSupport_NonPc_NoDesktopMode() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFullscreen_HasFreeformSupport_NonPc_HasDesktopMode() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;
        mWm.setForceDesktopModeOnExternalDisplays(true);

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayDefaultToFreeform_HasFreeformSupport_IsPc() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;
        mWm.setIsPc(true);

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertEquals(WINDOWING_MODE_FREEFORM,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testPrimaryDisplayUpdateToFreeform_HasFreeformSupport_IsPc() {
        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        mWm.mAtmService.mSupportsFreeformWindowManagement = true;
        mWm.setIsPc(true);

        mTarget.updateSettingsForDisplay(mPrimaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FREEFORM,
                mPrimaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFullscreen_NoFreeformSupport() {
        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeform_HasFreeformSupport_NonPc_NoDesktopMode() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WindowConfiguration.WINDOWING_MODE_FULLSCREEN,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeform_HasFreeformSupport_NonPc_HasDesktopMode() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;
        mWm.setForceDesktopModeOnExternalDisplays(true);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WINDOWING_MODE_FREEFORM,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testSecondaryDisplayDefaultToFreeform_HasFreeformSupport_IsPc() {
        mWm.mAtmService.mSupportsFreeformWindowManagement = true;
        mWm.setIsPc(true);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(WINDOWING_MODE_FREEFORM,
                mSecondaryDisplay.getWindowingMode());
    }

    @Test
    public void testDefaultToOriginalMetrics() {
        final int originalWidth = mSecondaryDisplay.mBaseDisplayWidth;
        final int originalHeight = mSecondaryDisplay.mBaseDisplayHeight;
        final int originalDensity = mSecondaryDisplay.mBaseDisplayDensity;
        final boolean originalScalingDisabled = mSecondaryDisplay.mDisplayScalingDisabled;

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(originalWidth, mSecondaryDisplay.mBaseDisplayWidth);
        assertEquals(originalHeight, mSecondaryDisplay.mBaseDisplayHeight);
        assertEquals(originalDensity, mSecondaryDisplay.mBaseDisplayDensity);
        assertEquals(originalScalingDisabled, mSecondaryDisplay.mDisplayScalingDisabled);
    }

    @Test
    public void testSetForcedSize() {
        final DisplayInfo originalInfo = new DisplayInfo(mSecondaryDisplay.getDisplayInfo());
        // Provides the orginal display info to avoid changing initial display size.
        doAnswer(invocation -> {
            ((DisplayInfo) invocation.getArguments()[1]).copyFrom(originalInfo);
            return null;
        }).when(mWm.mDisplayManagerInternal).getNonOverrideDisplayInfo(anyInt(), any());

        mTarget.setForcedSize(mSecondaryDisplay, 1000 /* width */, 2000 /* height */);
        applySettingsToDisplayByNewInstance(mSecondaryDisplay);

        assertEquals(1000 /* width */, mSecondaryDisplay.mBaseDisplayWidth);
        assertEquals(2000 /* height */, mSecondaryDisplay.mBaseDisplayHeight);

        mWm.clearForcedDisplaySize(mSecondaryDisplay.getDisplayId());
        assertEquals(mSecondaryDisplay.mInitialDisplayWidth, mSecondaryDisplay.mBaseDisplayWidth);
        assertEquals(mSecondaryDisplay.mInitialDisplayHeight, mSecondaryDisplay.mBaseDisplayHeight);
    }

    @Test
    public void testSetForcedDensity() {
        mTarget.setForcedDensity(mSecondaryDisplay, 600 /* density */, 0 /* userId */);
        applySettingsToDisplayByNewInstance(mSecondaryDisplay);

        assertEquals(600 /* density */, mSecondaryDisplay.mBaseDisplayDensity);

        mWm.clearForcedDisplayDensityForUser(mSecondaryDisplay.getDisplayId(), 0 /* userId */);
        assertEquals(mSecondaryDisplay.mInitialDisplayDensity,
                mSecondaryDisplay.mBaseDisplayDensity);
    }

    @Test
    public void testSetForcedScalingMode() {
        mTarget.setForcedScalingMode(mSecondaryDisplay, DisplayContent.FORCE_SCALING_MODE_DISABLED);
        applySettingsToDisplayByNewInstance(mSecondaryDisplay);

        assertTrue(mSecondaryDisplay.mDisplayScalingDisabled);

        mWm.setForcedDisplayScalingMode(mSecondaryDisplay.getDisplayId(),
                DisplayContent.FORCE_SCALING_MODE_AUTO);
        assertFalse(mSecondaryDisplay.mDisplayScalingDisabled);
    }

    @Test
    public void testDefaultToZeroOverscan() {
        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        assertOverscan(mPrimaryDisplay, 0 /* left */, 0 /* top */, 0 /* right */, 0 /* bottom */);
    }

    @Test
    public void testPersistOverscanInSameInstance() {
        final DisplayInfo info = mPrimaryDisplay.getDisplayInfo();
        try {
            mTarget.setOverscanLocked(info, 1 /* left */, 2 /* top */, 3 /* right */,
                    4 /* bottom */);

            mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

            assertOverscan(mPrimaryDisplay, 1 /* left */, 2 /* top */, 3 /* right */,
                    4 /* bottom */);
        } finally {
            mTarget.setOverscanLocked(info, 0, 0, 0, 0);
            mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);
        }
    }

    @Test
    public void testPersistOverscanAcrossInstances() {
        final DisplayInfo info = mPrimaryDisplay.getDisplayInfo();
        try {
            mTarget.setOverscanLocked(info, 10 /* left */, 20 /* top */, 30 /* right */,
                    40 /* bottom */);

            applySettingsToDisplayByNewInstance(mPrimaryDisplay);

            assertOverscan(mPrimaryDisplay, 10 /* left */, 20 /* top */, 30 /* right */,
                    40 /* bottom */);
        } finally {
            mTarget.setOverscanLocked(info, 0, 0, 0, 0);
            mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);
        }
    }

    @Test
    public void testDefaultToFreeUserRotation() {
        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        final DisplayRotation rotation = mSecondaryDisplay.getDisplayRotation();
        assertEquals(WindowManagerPolicy.USER_ROTATION_FREE, rotation.getUserRotationMode());
        assertFalse(rotation.isRotationFrozen());
    }

    @Test
    public void testDefaultTo0DegRotation() {
        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(Surface.ROTATION_0, mSecondaryDisplay.getDisplayRotation().getUserRotation());
    }

    @Test
    public void testPrivateDisplayDefaultToDestroyContent() {
        assertEquals(REMOVE_CONTENT_MODE_DESTROY,
                mTarget.getRemoveContentModeLocked(mPrivateDisplay));
    }

    @Test
    public void testPublicDisplayDefaultToMoveToPrimary() {
        assertEquals(REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY,
                mTarget.getRemoveContentModeLocked(mSecondaryDisplay));
    }

    @Test
    public void testDefaultToNotShowWithInsecureKeyguard() {
        assertFalse(mTarget.shouldShowWithInsecureKeyguardLocked(mPrivateDisplay));
        assertFalse(mTarget.shouldShowWithInsecureKeyguardLocked(mSecondaryDisplay));
    }

    @Test
    public void testPublicDisplayNotAllowSetShouldShowWithInsecureKeyguard() {
        mTarget.setShouldShowWithInsecureKeyguardLocked(mSecondaryDisplay, true);

        assertFalse(mTarget.shouldShowWithInsecureKeyguardLocked(mSecondaryDisplay));
    }

    @Test
    public void testPrivateDisplayAllowSetShouldShowWithInsecureKeyguard() {
        mTarget.setShouldShowWithInsecureKeyguardLocked(mPrivateDisplay, true);

        assertTrue(mTarget.shouldShowWithInsecureKeyguardLocked(mPrivateDisplay));
    }

    @Test
    public void testPrimaryDisplayShouldShowSystemDecors() {
        assertTrue(mTarget.shouldShowSystemDecorsLocked(mPrimaryDisplay));

        mTarget.setShouldShowSystemDecorsLocked(mPrimaryDisplay, false);

        // Default display should show system decors
        assertTrue(mTarget.shouldShowSystemDecorsLocked(mPrimaryDisplay));
    }

    @Test
    public void testSecondaryDisplayDefaultToNotShowSystemDecors() {
        assertFalse(mTarget.shouldShowSystemDecorsLocked(mSecondaryDisplay));
    }

    @Test
    public void testPrimaryDisplayShouldShowIme() {
        assertTrue(mTarget.shouldShowImeLocked(mPrimaryDisplay));

        mTarget.setShouldShowImeLocked(mPrimaryDisplay, false);

        assertTrue(mTarget.shouldShowImeLocked(mPrimaryDisplay));
    }

    @Test
    public void testSecondaryDisplayDefaultToNotShowIme() {
        assertFalse(mTarget.shouldShowImeLocked(mSecondaryDisplay));
    }

    @Test
    public void testPersistUserRotationModeInSameInstance() {
        mTarget.setUserRotation(mSecondaryDisplay, WindowManagerPolicy.USER_ROTATION_LOCKED,
                Surface.ROTATION_90);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        final DisplayRotation rotation = mSecondaryDisplay.getDisplayRotation();
        assertEquals(WindowManagerPolicy.USER_ROTATION_LOCKED, rotation.getUserRotationMode());
        assertTrue(rotation.isRotationFrozen());
    }

    @Test
    public void testPersistUserRotationInSameInstance() {
        mTarget.setUserRotation(mSecondaryDisplay, WindowManagerPolicy.USER_ROTATION_LOCKED,
                Surface.ROTATION_90);

        mTarget.applySettingsToDisplayLocked(mSecondaryDisplay);

        assertEquals(Surface.ROTATION_90, mSecondaryDisplay.getDisplayRotation().getUserRotation());
    }

    @Test
    public void testPersistUserRotationModeAcrossInstances() {
        mTarget.setUserRotation(mSecondaryDisplay, WindowManagerPolicy.USER_ROTATION_LOCKED,
                Surface.ROTATION_270);

        applySettingsToDisplayByNewInstance(mSecondaryDisplay);

        final DisplayRotation rotation = mSecondaryDisplay.getDisplayRotation();
        assertEquals(WindowManagerPolicy.USER_ROTATION_LOCKED, rotation.getUserRotationMode());
        assertTrue(rotation.isRotationFrozen());
    }

    @Test
    public void testPersistUserRotationAcrossInstances() {
        mTarget.setUserRotation(mSecondaryDisplay, WindowManagerPolicy.USER_ROTATION_LOCKED,
                Surface.ROTATION_270);

        applySettingsToDisplayByNewInstance(mSecondaryDisplay);

        assertEquals(Surface.ROTATION_270,
                mSecondaryDisplay.getDisplayRotation().getUserRotation());
    }

    @Test
    public void testFixedToUserRotationDefault() {
        mTarget.setUserRotation(mPrimaryDisplay, WindowManagerPolicy.USER_ROTATION_LOCKED,
                Surface.ROTATION_0);

        final DisplayRotation displayRotation = mock(DisplayRotation.class);
        spyOn(mPrimaryDisplay);
        doReturn(displayRotation).when(mPrimaryDisplay).getDisplayRotation();

        mTarget.applySettingsToDisplayLocked(mPrimaryDisplay);

        verify(displayRotation).restoreSettings(anyInt(), anyInt(),
                eq(FIXED_TO_USER_ROTATION_DEFAULT));
    }

    @Test
    public void testSetFixedToUserRotationDisabled() {
        mTarget.setFixedToUserRotation(mPrimaryDisplay, FIXED_TO_USER_ROTATION_DISABLED);

        final DisplayRotation displayRotation = mock(DisplayRotation.class);
        spyOn(mPrimaryDisplay);
        doReturn(displayRotation).when(mPrimaryDisplay).getDisplayRotation();

        applySettingsToDisplayByNewInstance(mPrimaryDisplay);

        verify(displayRotation).restoreSettings(anyInt(), anyInt(),
                eq(FIXED_TO_USER_ROTATION_DISABLED));
    }

    @Test
    public void testSetFixedToUserRotationEnabled() {
        mTarget.setFixedToUserRotation(mPrimaryDisplay, FIXED_TO_USER_ROTATION_ENABLED);

        final DisplayRotation displayRotation = mock(DisplayRotation.class);
        spyOn(mPrimaryDisplay);
        doReturn(displayRotation).when(mPrimaryDisplay).getDisplayRotation();

        applySettingsToDisplayByNewInstance(mPrimaryDisplay);

        verify(displayRotation).restoreSettings(anyInt(), anyInt(),
                eq(FIXED_TO_USER_ROTATION_ENABLED));
    }

    @Test
    public void testReadingDisplaySettingsFromStorage() {
        final String displayIdentifier = mSecondaryDisplay.getDisplayInfo().uniqueId;
        prepareDisplaySettings(displayIdentifier);

        readAndAssertDisplaySettings(mPrimaryDisplay);
    }

    @Test
    public void testReadingDisplaySettingsFromStorage_LegacyDisplayId() {
        final String displayIdentifier = mPrimaryDisplay.getDisplayInfo().name;
        prepareDisplaySettings(displayIdentifier);

        readAndAssertDisplaySettings(mPrimaryDisplay);
    }

    @Test
    public void testReadingDisplaySettingsFromStorage_LegacyDisplayId_UpdateAfterAccess()
            throws Exception {
        // Store display settings with legacy display identifier.
        final String displayIdentifier = mPrimaryDisplay.getDisplayInfo().name;
        prepareDisplaySettings(displayIdentifier);

        // Update settings with new value, should trigger write to injector.
        final DisplayWindowSettings settings = new DisplayWindowSettings(mWm, mStorage);
        settings.setRemoveContentModeLocked(mPrimaryDisplay, REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY);
        assertEquals("Settings value must be updated", REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY,
                settings.getRemoveContentModeLocked(mPrimaryDisplay));
        assertTrue(mStorage.wasWriteSuccessful());

        // Verify that display identifier was updated.
        final String newDisplayIdentifier = getStoredDisplayAttributeValue("name");
        assertEquals("Display identifier must be updated to use uniqueId",
                mPrimaryDisplay.getDisplayInfo().uniqueId, newDisplayIdentifier);
    }

    @Test
    public void testReadingDisplaySettingsFromStorage_UsePortAsId() {
        final DisplayAddress.Physical displayAddress = DisplayAddress.fromPhysicalDisplayId(123456);
        mPrimaryDisplay.getDisplayInfo().address = displayAddress;

        final String displayIdentifier = "port:" + displayAddress.getPort();
        prepareDisplaySettings(displayIdentifier, true /* usePortAsId */);

        readAndAssertDisplaySettings(mPrimaryDisplay);
    }

    @Test
    public void testReadingDisplaySettingsFromStorage_UsePortAsId_IncorrectAddress() {
        final String displayIdentifier = mPrimaryDisplay.getDisplayInfo().uniqueId;
        prepareDisplaySettings(displayIdentifier, true /* usePortAsId */);

        mPrimaryDisplay.getDisplayInfo().address = DisplayAddress.fromPhysicalDisplayId(123456);

        // Verify that the entry is not matched and default settings are returned instead.
        final DisplayWindowSettings settings = new DisplayWindowSettings(mWm);
        assertNotEquals("Default setting must be returned for new entry",
                WINDOWING_MODE_PINNED, settings.getWindowingModeLocked(mPrimaryDisplay));
    }

    @Test
    public void testWritingDisplaySettingsToStorage() throws Exception {
        // Write some settings to storage.
        final DisplayWindowSettings settings = new DisplayWindowSettings(mWm, mStorage);
        settings.setShouldShowSystemDecorsLocked(mSecondaryDisplay, true);
        settings.setShouldShowImeLocked(mSecondaryDisplay, true);
        assertTrue(mStorage.wasWriteSuccessful());

        // Verify that settings were stored correctly.
        assertEquals("Attribute value must be stored", mSecondaryDisplay.getDisplayInfo().uniqueId,
                getStoredDisplayAttributeValue("name"));
        assertEquals("Attribute value must be stored", "true",
                getStoredDisplayAttributeValue("shouldShowSystemDecors"));
        assertEquals("Attribute value must be stored", "true",
                getStoredDisplayAttributeValue("shouldShowIme"));
    }

    @Test
    public void testWritingDisplaySettingsToStorage_UsePortAsId() throws Exception {
        // Store config to use port as identifier.
        final DisplayAddress.Physical displayAddress = DisplayAddress.fromPhysicalDisplayId(123456);
        mSecondaryDisplay.getDisplayInfo().address = displayAddress;
        prepareDisplaySettings(null /* displayIdentifier */, true /* usePortAsId */);

        // Write some settings.
        final DisplayWindowSettings settings = new DisplayWindowSettings(mWm, mStorage);
        settings.setShouldShowSystemDecorsLocked(mSecondaryDisplay, true);
        settings.setShouldShowImeLocked(mSecondaryDisplay, true);
        assertTrue(mStorage.wasWriteSuccessful());

        // Verify that settings were stored correctly.
        assertEquals("Attribute value must be stored", "port:" + displayAddress.getPort(),
                getStoredDisplayAttributeValue("name"));
        assertEquals("Attribute value must be stored", "true",
                getStoredDisplayAttributeValue("shouldShowSystemDecors"));
        assertEquals("Attribute value must be stored", "true",
                getStoredDisplayAttributeValue("shouldShowIme"));
    }

    @Test
    public void testShouldShowImeWithinForceDesktopMode() {
        try {
            // Presume display enabled force desktop mode from developer options.
            final DisplayContent dc = createMockSimulatedDisplay();
            mWm.setForceDesktopModeOnExternalDisplays(true);
            final WindowManagerInternal wmInternal = LocalServices.getService(
                    WindowManagerInternal.class);
            // Make sure WindowManagerInter#shouldShowIme as true is due to
            // mForceDesktopModeOnExternalDisplays as true.
            assertFalse(mWm.mDisplayWindowSettings.shouldShowImeLocked(dc));
            assertTrue(wmInternal.shouldShowIme(dc.getDisplayId()));
        } finally {
            mWm.setForceDesktopModeOnExternalDisplays(false);
        }
    }

    @Test
    public void testDisplayWindowSettingsAppliedOnDisplayReady() {
        // Set forced densities for two displays in DisplayWindowSettings
        final DisplayContent dc = createMockSimulatedDisplay();
        final DisplayWindowSettings settings = new DisplayWindowSettings(mWm, mStorage);
        settings.setForcedDensity(mPrimaryDisplay, 123, 0 /* userId */);
        settings.setForcedDensity(dc, 456, 0 /* userId */);

        // Apply settings to displays - the settings will be stored, but config will not be
        // recalculated immediately.
        settings.applySettingsToDisplayLocked(mPrimaryDisplay);
        settings.applySettingsToDisplayLocked(dc);
        assertFalse(mPrimaryDisplay.mWaitingForConfig);
        assertFalse(dc.mWaitingForConfig);

        // Notify WM that the displays are ready and check that they are reconfigured.
        mWm.displayReady();
        waitUntilHandlersIdle();

        final Configuration config = new Configuration();
        mPrimaryDisplay.computeScreenConfiguration(config);
        assertEquals(123, config.densityDpi);
        dc.computeScreenConfiguration(config);
        assertEquals(456, config.densityDpi);
    }

    /**
     * Prepares display settings and stores in {@link #mStorage}. Uses provided display identifier
     * and stores windowingMode=WINDOWING_MODE_PINNED.
     */
    private void prepareDisplaySettings(String displayIdentifier) {
        prepareDisplaySettings(displayIdentifier, false /* usePortAsId */);
    }

    private void prepareDisplaySettings(String displayIdentifier, boolean usePortAsId) {
        String contents = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<display-settings>\n";
        if (usePortAsId) {
            contents += "  <config identifier=\"1\"/>\n";
        }
        if (displayIdentifier != null) {
            contents += "  <display\n"
                    + "    name=\"" + displayIdentifier + "\"\n"
                    + "    windowingMode=\"" + WINDOWING_MODE_PINNED + "\"/>\n";
        }
        contents += "</display-settings>\n";

        final InputStream is = new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
        mStorage.setReadStream(is);
    }

    private void readAndAssertDisplaySettings(DisplayContent displayContent) {
        final DisplayWindowSettings settings = new DisplayWindowSettings(mWm, mStorage);
        assertEquals("Stored setting must be read",
                WINDOWING_MODE_PINNED, settings.getWindowingModeLocked(displayContent));
        assertEquals("Not stored setting must be set to default value",
                REMOVE_CONTENT_MODE_MOVE_TO_PRIMARY,
                settings.getRemoveContentModeLocked(displayContent));
    }

    private String getStoredDisplayAttributeValue(String attr) throws Exception {
        try (InputStream stream = mStorage.openRead()) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(stream, StandardCharsets.UTF_8.name());
            int type;
            while ((type = parser.next()) != XmlPullParser.START_TAG
                    && type != XmlPullParser.END_DOCUMENT) {
                // Do nothing.
            }

            if (type != XmlPullParser.START_TAG) {
                throw new IllegalStateException("no start tag found");
            }

            int outerDepth = parser.getDepth();
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                    continue;
                }

                String tagName = parser.getName();
                if (tagName.equals("display")) {
                    return parser.getAttributeValue(null, attr);
                }
            }
        } finally {
            mStorage.closeRead();
        }
        return null;
    }

    private static void assertOverscan(DisplayContent display, int left, int top, int right,
            int bottom) {
        final DisplayInfo info = display.getDisplayInfo();

        assertEquals(left, info.overscanLeft);
        assertEquals(top, info.overscanTop);
        assertEquals(right, info.overscanRight);
        assertEquals(bottom, info.overscanBottom);
    }

    /**
     * This method helps to ensure read and write persistent settings successfully because the
     * constructor of {@link DisplayWindowSettings} should read the persistent file from the given
     * path that also means the previous state must be written correctly.
     */
    private void applySettingsToDisplayByNewInstance(DisplayContent display) {
        // Assert that prior write completed successfully.
        assertTrue(mStorage.wasWriteSuccessful());

        // Read and apply settings.
        new DisplayWindowSettings(mWm, mStorage).applySettingsToDisplayLocked(display);
    }

    private static boolean deleteRecursively(File file) {
        boolean fullyDeleted = true;
        if (file.isFile()) {
            return file.delete();
        } else if (file.isDirectory()) {
            final File[] files = file.listFiles();
            for (File child : files) {
                fullyDeleted &= deleteRecursively(child);
            }
            fullyDeleted &= file.delete();
        }
        return fullyDeleted;
    }

    /** In-memory storage implementation. */
    public class TestStorage implements DisplayWindowSettings.SettingPersister {
        private InputStream mReadStream;
        private ByteArrayOutputStream mWriteStream;

        private boolean mWasSuccessful;

        /**
         * Returns input stream for reading. By default tries forward the output stream if previous
         * write was successful.
         * @see #closeRead()
         */
        @Override
        public InputStream openRead() throws FileNotFoundException {
            if (mReadStream == null && mWasSuccessful) {
                mReadStream = new ByteArrayInputStream(mWriteStream.toByteArray());
            }
            if (mReadStream == null) {
                throw new FileNotFoundException();
            }
            if (mReadStream.markSupported()) {
                mReadStream.mark(Integer.MAX_VALUE);
            }
            return mReadStream;
        }

        /** Must be called after each {@link #openRead} to reset the position in the stream. */
        void closeRead() throws IOException {
            if (mReadStream == null) {
                throw new FileNotFoundException();
            }
            if (mReadStream.markSupported()) {
                mReadStream.reset();
            }
            mReadStream = null;
        }

        /**
         * Creates new or resets existing output stream for write. Automatically closes previous
         * read stream, since following reads should happen based on this new write.
         */
        @Override
        public OutputStream startWrite() throws IOException {
            if (mWriteStream == null) {
                mWriteStream = new ByteArrayOutputStream();
            } else {
                mWriteStream.reset();
            }
            if (mReadStream != null) {
                closeRead();
            }
            return mWriteStream;
        }

        @Override
        public void finishWrite(OutputStream os, boolean success) {
            mWasSuccessful = success;
            try {
                os.close();
            } catch (IOException e) {
                // This method can't throw IOException since the super implementation doesn't, so
                // we just wrap it in a RuntimeException so we end up crashing the test all the
                // same.
                throw new RuntimeException(e);
            }
        }

        /** Override the read stream of the injector. By default it uses current write stream. */
        private void setReadStream(InputStream is) {
            mReadStream = is;
        }

        private boolean wasWriteSuccessful() {
            return mWasSuccessful;
        }
    }
}
