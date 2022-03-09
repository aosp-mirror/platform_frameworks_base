/*
 * Copyright 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.display.BrightnessConfiguration;
import android.util.Pair;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PersistentDataStoreTest {
    private PersistentDataStore mDataStore;
    private TestInjector mInjector;

    @Before
    public void setUp() {
        mInjector = new TestInjector();
        mDataStore = new PersistentDataStore(mInjector);
    }

    @Test
    public void testLoadingBrightnessConfigurations() {
        String contents = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<display-manager-state>\n"
                + "  <brightness-configurations>\n"
                + "    <brightness-configuration"
                + "         user-serial=\"1\""
                + "         package-name=\"example.com\""
                + "         timestamp=\"123456\">\n"
                + "      <brightness-curve description=\"something\">\n"
                + "        <brightness-point lux=\"0\" nits=\"13.25\"/>\n"
                + "        <brightness-point lux=\"25\" nits=\"35.94\"/>\n"
                + "      </brightness-curve>\n"
                + "    </brightness-configuration>\n"
                + "    <brightness-configuration user-serial=\"3\">\n"
                + "      <brightness-curve>\n"
                + "        <brightness-point lux=\"0\" nits=\"13.25\"/>\n"
                + "        <brightness-point lux=\"10.2\" nits=\"15\"/>\n"
                + "      </brightness-curve>\n"
                + "    </brightness-configuration>\n"
                + "  </brightness-configurations>\n"
                + "</display-manager-state>\n";
        InputStream is = new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
        mInjector.setReadStream(is);
        mDataStore.loadIfNeeded();
        BrightnessConfiguration config = mDataStore.getBrightnessConfiguration(1 /*userSerial*/);
        Pair<float[], float[]> curve = config.getCurve();
        float[] expectedLux = { 0f, 25f };
        float[] expectedNits = { 13.25f, 35.94f };
        assertArrayEquals(expectedLux, curve.first, "lux");
        assertArrayEquals(expectedNits, curve.second, "nits");
        assertEquals("something", config.getDescription());

        config = mDataStore.getBrightnessConfiguration(3 /*userSerial*/);
        curve = config.getCurve();
        expectedLux = new float[] { 0f, 10.2f };
        expectedNits = new float[] { 13.25f, 15f };
        assertArrayEquals(expectedLux, curve.first, "lux");
        assertArrayEquals(expectedNits, curve.second, "nits");
        assertNull(config.getDescription());
    }

    @Test
    public void testBrightnessConfigWithInvalidCurveIsIgnored() {
        String contents = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<display-manager-state>\n"
                + "  <brightness-configurations>\n"
                + "    <brightness-configuration user-serial=\"0\">\n"
                + "      <brightness-curve>\n"
                + "        <brightness-point lux=\"1\" nits=\"13.25\"/>\n"
                + "        <brightness-point lux=\"25\" nits=\"35.94\"/>\n"
                + "      </brightness-curve>\n"
                + "    </brightness-configuration>\n"
                + "  </brightness-configurations>\n"
                + "</display-manager-state>\n";
        InputStream is = new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
        mInjector.setReadStream(is);
        mDataStore.loadIfNeeded();
        assertNull(mDataStore.getBrightnessConfiguration(0 /*userSerial*/));
    }

    @Test
    public void testBrightnessConfigWithInvalidFloatsIsIgnored() {
        String contents = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<display-manager-state>\n"
                + "  <brightness-configurations>\n"
                + "    <brightness-configuration user-serial=\"0\">\n"
                + "      <brightness-curve>\n"
                + "        <brightness-point lux=\"0\" nits=\"13.25\"/>\n"
                + "        <brightness-point lux=\"0xFF\" nits=\"foo\"/>\n"
                + "      </brightness-curve>\n"
                + "    </brightness-configuration>\n"
                + "  </brightness-configurations>\n"
                + "</display-manager-state>\n";
        InputStream is = new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
        mInjector.setReadStream(is);
        mDataStore.loadIfNeeded();
        assertNull(mDataStore.getBrightnessConfiguration(0 /*userSerial*/));
    }

    @Test
    public void testEmptyBrightnessConfigurationsDoesntCrash() {
        String contents = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
                + "<display-manager-state>\n"
                + "  <brightness-configurations />\n"
                + "</display-manager-state>\n";
        InputStream is = new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
        mInjector.setReadStream(is);
        mDataStore.loadIfNeeded();
        assertNull(mDataStore.getBrightnessConfiguration(0 /*userSerial*/));
    }

    @Test
    public void testStoreAndReloadOfDisplayBrightnessConfigurations() {
        final String uniqueDisplayId = "test:123";
        int userSerial = 0;
        String packageName = "pdsTestPackage";
        final float[] lux = { 0f, 10f };
        final float[] nits = {1f, 100f };
        final BrightnessConfiguration config = new BrightnessConfiguration.Builder(lux, nits)
                .setDescription("a description")
                .build();
        mDataStore.loadIfNeeded();
        assertNull(mDataStore.getBrightnessConfigurationForDisplayLocked(uniqueDisplayId,
                userSerial));

        DisplayDevice testDisplayDevice = new DisplayDevice(null, null, uniqueDisplayId, null) {
            @Override
            public boolean hasStableUniqueId() {
                return true;
            }

            @Override
            public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
                return null;
            }
        };

        mDataStore.setBrightnessConfigurationForDisplayLocked(config, testDisplayDevice, userSerial,
                packageName);

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mInjector.setWriteStream(baos);
        mDataStore.saveIfNeeded();
        assertTrue(mInjector.wasWriteSuccessful());
        TestInjector newInjector = new TestInjector();
        PersistentDataStore newDataStore = new PersistentDataStore(newInjector);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        newInjector.setReadStream(bais);
        newDataStore.loadIfNeeded();
        assertNotNull(newDataStore.getBrightnessConfigurationForDisplayLocked(uniqueDisplayId,
                userSerial));
        assertEquals(mDataStore.getBrightnessConfigurationForDisplayLocked(uniqueDisplayId,
                userSerial), newDataStore.getBrightnessConfigurationForDisplayLocked(
                        uniqueDisplayId, userSerial));
    }

    @Test
    public void testSetBrightnessConfigurationFailsWithUnstableId() {
        final String uniqueDisplayId = "test:123";
        int userSerial = 0;
        String packageName = "pdsTestPackage";
        final float[] lux = { 0f, 10f };
        final float[] nits = {1f, 100f };
        final BrightnessConfiguration config = new BrightnessConfiguration.Builder(lux, nits)
                .setDescription("a description")
                .build();
        mDataStore.loadIfNeeded();
        assertNull(mDataStore.getBrightnessConfigurationForDisplayLocked(uniqueDisplayId,
                userSerial));

        DisplayDevice testDisplayDevice = new DisplayDevice(null, null, uniqueDisplayId, null) {
            @Override
            public boolean hasStableUniqueId() {
                return false;
            }

            @Override
            public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
                return null;
            }
        };

        assertFalse(mDataStore.setBrightnessConfigurationForDisplayLocked(
                config, testDisplayDevice, userSerial, packageName));
    }

    @Test
    public void testStoreAndReloadOfBrightnessConfigurations() {
        final float[] lux = { 0f, 10f };
        final float[] nits = {1f, 100f };
        final BrightnessConfiguration config = new BrightnessConfiguration.Builder(lux, nits)
                .setDescription("a description")
                .build();
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        String packageName = context.getPackageName();

        mDataStore.loadIfNeeded();
        assertNull(mDataStore.getBrightnessConfiguration(0 /*userSerial*/));
        mDataStore.setBrightnessConfigurationForUser(config, 0, packageName);

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        mInjector.setWriteStream(baos);
        mDataStore.saveIfNeeded();
        assertTrue(mInjector.wasWriteSuccessful());

        TestInjector newInjector = new TestInjector();
        PersistentDataStore newDataStore = new PersistentDataStore(newInjector);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        newInjector.setReadStream(bais);
        newDataStore.loadIfNeeded();
        assertNotNull(newDataStore.getBrightnessConfiguration(0 /*userSerial*/));
        assertEquals(mDataStore.getBrightnessConfiguration(0 /*userSerial*/),
                newDataStore.getBrightnessConfiguration(0 /*userSerial*/));
    }

    @Test
    public void testNullBrightnessConfiguration() {
        final float[] lux = { 0f, 10f };
        final float[] nits = {1f, 100f };
        int userSerial = 0;
        final BrightnessConfiguration config = new BrightnessConfiguration.Builder(lux, nits)
                .setDescription("a description")
                .build();
        mDataStore.loadIfNeeded();
        assertNull(mDataStore.getBrightnessConfiguration(userSerial));

        mDataStore.setBrightnessConfigurationForUser(config, userSerial, "packagename");
        assertNotNull(mDataStore.getBrightnessConfiguration(userSerial));

        mDataStore.setBrightnessConfigurationForUser(null, userSerial, "packagename");
        assertNull(mDataStore.getBrightnessConfiguration(userSerial));
    }

    public class TestInjector extends PersistentDataStore.Injector {
        private InputStream mReadStream;
        private OutputStream mWriteStream;

        private boolean mWasSuccessful;

        @Override
        public InputStream openRead() throws FileNotFoundException {
            if (mReadStream != null) {
                return mReadStream;
            } else {
                throw new FileNotFoundException();
            }
        }

        @Override
        public OutputStream startWrite() {
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

        public void setReadStream(InputStream is) {
            mReadStream = is;
        }

        public void setWriteStream(OutputStream os) {
            mWriteStream = os;
        }

        public boolean wasWriteSuccessful() {
            return mWasSuccessful;
        }
    }

    private static void assertArrayEquals(float[] expected, float[] actual, String name) {
        assertEquals("Expected " + name + " arrays to be the same length!",
                expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("Expected " + name + " arrays to be equivalent when value " + i
                    + "differs", expected[i], actual[i], 0.01 /*tolerance*/);
        }
    }
}
