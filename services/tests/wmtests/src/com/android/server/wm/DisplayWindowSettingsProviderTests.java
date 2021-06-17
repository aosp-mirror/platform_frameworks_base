/*
 * Copyright (C) 2020 The Android Open Source Project
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
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.WindowManager.DISPLAY_IME_POLICY_LOCAL;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.annotation.Nullable;
import android.platform.test.annotations.Presubmit;
import android.util.TypedXmlPullParser;
import android.util.Xml;
import android.view.Display;
import android.view.DisplayAddress;
import android.view.DisplayInfo;

import androidx.test.filters.SmallTest;

import com.android.server.wm.DisplayWindowSettings.SettingsProvider.SettingsEntry;

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
 * Tests for the {@link DisplayWindowSettingsProvider} class.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayWindowSettingsProviderTests
 */
@SmallTest
@Presubmit
@WindowTestsBase.UseTestDisplay
@RunWith(WindowTestRunner.class)
public class DisplayWindowSettingsProviderTests extends WindowTestsBase {
    private static final int DISPLAY_PORT = 0xFF;
    private static final long DISPLAY_MODEL = 0xEEEEEEEEL;

    private static final File TEST_FOLDER = getInstrumentation().getTargetContext().getCacheDir();

    private TestStorage mDefaultVendorSettingsStorage;
    private TestStorage mSecondaryVendorSettingsStorage;
    private TestStorage mOverrideSettingsStorage;

    private DisplayContent mPrimaryDisplay;
    private DisplayContent mSecondaryDisplay;

    @Before
    public void setUp() throws Exception {
        deleteRecursively(TEST_FOLDER);

        mDefaultVendorSettingsStorage = new TestStorage();
        mSecondaryVendorSettingsStorage = new TestStorage();
        mOverrideSettingsStorage = new TestStorage();

        mPrimaryDisplay = mWm.getDefaultDisplayContentLocked();
        mSecondaryDisplay = mDisplayContent;
        assertNotEquals(Display.DEFAULT_DISPLAY, mSecondaryDisplay.getDisplayId());
    }

    @After
    public void tearDown() {
        deleteRecursively(TEST_FOLDER);
    }

    @Test
    public void testReadingDisplaySettingsFromStorage() {
        final String displayIdentifier = mSecondaryDisplay.getDisplayInfo().uniqueId;
        prepareOverrideDisplaySettings(displayIdentifier);

        SettingsEntry expectedSettings = new SettingsEntry();
        expectedSettings.mWindowingMode = WINDOWING_MODE_PINNED;
        readAndAssertExpectedSettings(mSecondaryDisplay, expectedSettings);
    }

    @Test
    public void testReadingDisplaySettingsFromStorage_LegacyDisplayId() {
        final String displayIdentifier = mPrimaryDisplay.getDisplayInfo().name;
        prepareOverrideDisplaySettings(displayIdentifier);

        SettingsEntry expectedSettings = new SettingsEntry();
        expectedSettings.mWindowingMode = WINDOWING_MODE_PINNED;
        readAndAssertExpectedSettings(mPrimaryDisplay, expectedSettings);
    }

    @Test
    public void testReadingDisplaySettingsFromStorage_LegacyDisplayId_UpdateAfterAccess()
            throws Exception {
        // Store display settings with legacy display identifier.
        final DisplayInfo mPrimaryDisplayInfo = mPrimaryDisplay.getDisplayInfo();
        final String displayIdentifier = mPrimaryDisplayInfo.name;
        prepareOverrideDisplaySettings(displayIdentifier);

        // Update settings with new value, should trigger write to injector.
        DisplayWindowSettingsProvider provider = new DisplayWindowSettingsProvider(
                mDefaultVendorSettingsStorage, mOverrideSettingsStorage);
        SettingsEntry overrideSettings = provider.getOverrideSettings(mPrimaryDisplayInfo);
        overrideSettings.mForcedDensity = 200;
        provider.updateOverrideSettings(mPrimaryDisplayInfo, overrideSettings);
        assertTrue(mOverrideSettingsStorage.wasWriteSuccessful());

        // Verify that display identifier was updated.
        final String newDisplayIdentifier = getStoredDisplayAttributeValue(
                mOverrideSettingsStorage, "name");
        assertEquals("Display identifier must be updated to use uniqueId",
                mPrimaryDisplayInfo.uniqueId, newDisplayIdentifier);
    }

    @Test
    public void testReadingDisplaySettingsFromStorage_UsePortAsId() {
        final DisplayAddress.Physical displayAddress =
                DisplayAddress.fromPortAndModel(DISPLAY_PORT, DISPLAY_MODEL);
        mPrimaryDisplay.getDisplayInfo().address = displayAddress;

        final String displayIdentifier = "port:" + DISPLAY_PORT;
        prepareOverrideDisplaySettings(displayIdentifier, true /* usePortAsId */);

        SettingsEntry expectedSettings = new SettingsEntry();
        expectedSettings.mWindowingMode = WINDOWING_MODE_PINNED;
        readAndAssertExpectedSettings(mPrimaryDisplay, expectedSettings);
    }

    @Test
    public void testReadingDisplaySettingsFromStorage_UsePortAsId_IncorrectAddress() {
        final String displayIdentifier = mPrimaryDisplay.getDisplayInfo().uniqueId;
        prepareOverrideDisplaySettings(displayIdentifier, true /* usePortAsId */);

        mPrimaryDisplay.getDisplayInfo().address = DisplayAddress.fromPhysicalDisplayId(123456);

        // Verify that the entry is not matched and default settings are returned instead.
        SettingsEntry expectedSettings = new SettingsEntry();
        readAndAssertExpectedSettings(mPrimaryDisplay, expectedSettings);
    }

    @Test
    public void testReadingDisplaySettingsFromStorage_secondayVendorDisplaySettingsLocation() {
        final String displayIdentifier = mSecondaryDisplay.getDisplayInfo().uniqueId;
        prepareSecondaryDisplaySettings(displayIdentifier);

        final DisplayWindowSettingsProvider provider =
                new DisplayWindowSettingsProvider(mDefaultVendorSettingsStorage,
                        mOverrideSettingsStorage);

        // Expected settings should be empty because the default is to read from the primary vendor
        // settings location.
        SettingsEntry expectedSettings = new SettingsEntry();
        assertEquals(expectedSettings, provider.getSettings(mSecondaryDisplay.getDisplayInfo()));

        // Now switch to secondary vendor settings and assert proper settings.
        provider.setBaseSettingsStorage(mSecondaryVendorSettingsStorage);
        expectedSettings.mWindowingMode = WINDOWING_MODE_FULLSCREEN;
        assertEquals(expectedSettings, provider.getSettings(mSecondaryDisplay.getDisplayInfo()));

        // Switch back to primary and assert settings are empty again.
        provider.setBaseSettingsStorage(mDefaultVendorSettingsStorage);
        expectedSettings.mWindowingMode = WINDOWING_MODE_UNDEFINED;
        assertEquals(expectedSettings, provider.getSettings(mSecondaryDisplay.getDisplayInfo()));
    }

    @Test
    public void testReadingDisplaySettingsFromStorage_overrideSettingsTakePrecedenceOverVendor() {
        final String displayIdentifier = mSecondaryDisplay.getDisplayInfo().uniqueId;
        prepareOverrideDisplaySettings(displayIdentifier);
        prepareSecondaryDisplaySettings(displayIdentifier);

        final DisplayWindowSettingsProvider provider =
                new DisplayWindowSettingsProvider(mDefaultVendorSettingsStorage,
                        mOverrideSettingsStorage);
        provider.setBaseSettingsStorage(mSecondaryVendorSettingsStorage);

        // The windowing mode should be set to WINDOWING_MODE_PINNED because the override settings
        // take precedence over the vendor provided settings.
        SettingsEntry expectedSettings = new SettingsEntry();
        expectedSettings.mWindowingMode = WINDOWING_MODE_PINNED;
        assertEquals(expectedSettings, provider.getSettings(mSecondaryDisplay.getDisplayInfo()));
    }

    @Test
    public void testWritingDisplaySettingsToStorage() throws Exception {
        final DisplayInfo secondaryDisplayInfo = mSecondaryDisplay.getDisplayInfo();

        // Write some settings to storage.
        DisplayWindowSettingsProvider provider = new DisplayWindowSettingsProvider(
                mDefaultVendorSettingsStorage, mOverrideSettingsStorage);
        SettingsEntry overrideSettings = provider.getOverrideSettings(secondaryDisplayInfo);
        overrideSettings.mShouldShowSystemDecors = true;
        overrideSettings.mImePolicy = DISPLAY_IME_POLICY_LOCAL;
        overrideSettings.mDontMoveToTop = true;
        provider.updateOverrideSettings(secondaryDisplayInfo, overrideSettings);
        assertTrue(mOverrideSettingsStorage.wasWriteSuccessful());

        // Verify that settings were stored correctly.
        assertEquals("Attribute value must be stored", secondaryDisplayInfo.uniqueId,
                getStoredDisplayAttributeValue(mOverrideSettingsStorage, "name"));
        assertEquals("Attribute value must be stored", "true",
                getStoredDisplayAttributeValue(mOverrideSettingsStorage, "shouldShowSystemDecors"));
        assertEquals("Attribute value must be stored", "0",
                getStoredDisplayAttributeValue(mOverrideSettingsStorage, "imePolicy"));
        assertEquals("Attribute value must be stored", "true",
                getStoredDisplayAttributeValue(mOverrideSettingsStorage, "dontMoveToTop"));
    }

    @Test
    public void testWritingDisplaySettingsToStorage_UsePortAsId() throws Exception {
        prepareOverrideDisplaySettings(null /* displayIdentifier */, true /* usePortAsId */);

        // Store config to use port as identifier.
        final DisplayInfo secondaryDisplayInfo = mSecondaryDisplay.getDisplayInfo();
        final DisplayAddress.Physical displayAddress =
                DisplayAddress.fromPortAndModel(DISPLAY_PORT, DISPLAY_MODEL);
        secondaryDisplayInfo.address = displayAddress;

        // Write some settings to storage.
        DisplayWindowSettingsProvider provider = new DisplayWindowSettingsProvider(
                mDefaultVendorSettingsStorage, mOverrideSettingsStorage);
        SettingsEntry overrideSettings = provider.getOverrideSettings(secondaryDisplayInfo);
        overrideSettings.mShouldShowSystemDecors = true;
        overrideSettings.mImePolicy = DISPLAY_IME_POLICY_LOCAL;
        provider.updateOverrideSettings(secondaryDisplayInfo, overrideSettings);
        assertTrue(mOverrideSettingsStorage.wasWriteSuccessful());

        // Verify that settings were stored correctly.
        assertEquals("Attribute value must be stored", "port:" + DISPLAY_PORT,
                getStoredDisplayAttributeValue(mOverrideSettingsStorage, "name"));
        assertEquals("Attribute value must be stored", "true",
                getStoredDisplayAttributeValue(mOverrideSettingsStorage, "shouldShowSystemDecors"));
        assertEquals("Attribute value must be stored", "0",
                getStoredDisplayAttributeValue(mOverrideSettingsStorage, "imePolicy"));
    }

    /**
     * Prepares display settings and stores in {@link #mOverrideSettingsStorage}. Uses provided
     * display identifier and stores windowingMode=WINDOWING_MODE_PINNED.
     */
    private void prepareOverrideDisplaySettings(String displayIdentifier) {
        prepareOverrideDisplaySettings(displayIdentifier, false /* usePortAsId */);
    }

    private void prepareOverrideDisplaySettings(String displayIdentifier, boolean usePortAsId) {
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
        mOverrideSettingsStorage.setReadStream(is);
    }

    /**
     * Prepares display settings and stores in {@link #mSecondaryVendorSettingsStorage}. Uses
     * provided display identifier and stores windowingMode=WINDOWING_MODE_FULLSCREEN.
     */
    private void prepareSecondaryDisplaySettings(String displayIdentifier) {
        String contents = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<display-settings>\n";
        if (displayIdentifier != null) {
            contents += "  <display\n"
                    + "    name=\"" + displayIdentifier + "\"\n"
                    + "    windowingMode=\"" + WINDOWING_MODE_FULLSCREEN + "\"/>\n";
        }
        contents += "</display-settings>\n";

        final InputStream is = new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
        mSecondaryVendorSettingsStorage.setReadStream(is);
    }

    private void readAndAssertExpectedSettings(DisplayContent displayContent,
            SettingsEntry expectedSettings) {
        final DisplayWindowSettingsProvider provider =
                new DisplayWindowSettingsProvider(mDefaultVendorSettingsStorage,
                        mOverrideSettingsStorage);
        assertEquals(expectedSettings, provider.getSettings(displayContent.getDisplayInfo()));
    }

    @Nullable
    private String getStoredDisplayAttributeValue(TestStorage storage, String attr)
            throws Exception {
        try (InputStream stream = storage.openRead()) {
            TypedXmlPullParser parser = Xml.resolvePullParser(stream);
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
            storage.closeRead();
        }
        return null;
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
    public class TestStorage implements DisplayWindowSettingsProvider.WritableSettingsStorage {
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

        /** Overrides the read stream of the injector. By default it uses current write stream. */
        private void setReadStream(InputStream is) {
            mReadStream = is;
        }

        private boolean wasWriteSuccessful() {
            return mWasSuccessful;
        }
    }
}
