/*
1;3409;0c * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.content.res;

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOW_CONFIG_ROTATION;
import static android.app.WindowConfiguration.WINDOW_CONFIG_WINDOWING_MODE;
import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.content.pm.ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
import static android.content.pm.ActivityInfo.CONFIG_WINDOW_CONFIGURATION;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.content.res.Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED;
import static android.view.Surface.ROTATION_90;

import android.content.Context;
import android.os.LocaleList;
import android.platform.test.annotations.Presubmit;
import android.util.AtomicFile;
import android.util.proto.ProtoInputStream;
import android.util.proto.ProtoOutputStream;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import com.android.server.usage.IntervalStatsProto;

import junit.framework.TestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Locale;

/**
 * Build/install/run: bit FrameworksCoreTests:android.content.res.ConfigurationTest
 */
@RunWith(JUnit4.class)
@SmallTest
@Presubmit
public class ConfigurationTest extends TestCase {
    @Test
    public void testUpdateFromPreservesRoundBit() {
        Configuration config = new Configuration();
        config.screenLayout = Configuration.SCREENLAYOUT_ROUND_YES;
        Configuration config2 = new Configuration();

        config.updateFrom(config2);
        assertEquals(config.screenLayout, Configuration.SCREENLAYOUT_ROUND_YES);
    }

    @Test
    public void testUpdateFromPreservesCompatNeededBit() {
        Configuration config = new Configuration();
        config.screenLayout = Configuration.SCREENLAYOUT_COMPAT_NEEDED;
        Configuration config2 = new Configuration();
        config.updateFrom(config2);
        assertEquals(config.screenLayout, Configuration.SCREENLAYOUT_COMPAT_NEEDED);

        config2.updateFrom(config);
        assertEquals(config2.screenLayout, Configuration.SCREENLAYOUT_COMPAT_NEEDED);
    }

    @Test
    public void testReadWriteProto() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        final File testDir = new File(context.getFilesDir(), "ConfigurationTest");
        testDir.mkdirs();
        final File proto = new File(testDir, "configs");
        if (proto.exists()) {
            proto.delete();
        }

        final Locale arabic = new Locale.Builder().setLocale(new Locale("ar", "AE")).build();
        final Locale urdu = new Locale.Builder().setLocale(new Locale("ur", "IN")).build();
        final Locale urduExtension = new Locale.Builder().setLocale(new Locale("ur", "IN"))
                .setExtension('u', "nu-latn").build();
        Configuration write = new Configuration();
        write.setLocales(new LocaleList(arabic, urdu, urduExtension));
        dumpDebug(proto, write);
        assertTrue("Failed to write configs to proto.", proto.exists());

        final Configuration read = new Configuration();
        try {
            readFromProto(proto, read);
        } finally {
            proto.delete();
        }

        assertEquals("Missing locales in proto file written to disk.",
                read.getLocales().size(), write.getLocales().size());
        assertTrue("Arabic locale not found in Configuration locale list.",
                read.getLocales().indexOf(arabic) != -1);
        assertTrue("Urdu locale not found in Configuration locale list.",
                read.getLocales().indexOf(urdu) != -1);
        assertTrue("Urdu locale with extensions not found in Configuration locale list.",
                read.getLocales().indexOf(urduExtension) != -1);
    }

    @Test
    public void testMaskedSet() {
        Configuration config = new Configuration();
        Configuration other = new Configuration();
        config.smallestScreenWidthDp = 100;
        config.orientation = ORIENTATION_LANDSCAPE;
        config.windowConfiguration.setRotation(ROTATION_90);
        other.windowConfiguration.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        other.orientation = ORIENTATION_PORTRAIT;

        // no change
        config.setTo(other, 0, 0);
        assertEquals(100, config.smallestScreenWidthDp);
        assertEquals(ORIENTATION_LANDSCAPE, config.orientation);
        assertEquals(ROTATION_90, config.windowConfiguration.getRotation());

        final int justOrientationAndWindowConfig = CONFIG_ORIENTATION | CONFIG_WINDOW_CONFIGURATION;
        config.setTo(other, justOrientationAndWindowConfig, WINDOW_CONFIG_WINDOWING_MODE);
        assertEquals(100, config.smallestScreenWidthDp);
        assertEquals(other.orientation, config.orientation);
        assertEquals(other.windowConfiguration.getWindowingMode(),
                config.windowConfiguration.getWindowingMode());
        assertEquals(ROTATION_90, config.windowConfiguration.getRotation());

        // unset
        final int justSmallestSwAndWindowConfig =
                CONFIG_SMALLEST_SCREEN_SIZE | CONFIG_WINDOW_CONFIGURATION;
        config.setTo(other, justSmallestSwAndWindowConfig, WINDOW_CONFIG_ROTATION);
        assertEquals(ROTATION_UNDEFINED, config.windowConfiguration.getRotation());
        assertEquals(SMALLEST_SCREEN_WIDTH_DP_UNDEFINED, config.smallestScreenWidthDp);
    }

    @Test
    public void testNightModeHelper() {
        Configuration config = new Configuration();
        config.uiMode = Configuration.UI_MODE_NIGHT_YES;
        assertTrue(config.isNightModeActive());
        config.uiMode = Configuration.UI_MODE_NIGHT_NO;
        assertFalse(config.isNightModeActive());
    }

    private void dumpDebug(File f, Configuration config) throws Exception {
        final AtomicFile af = new AtomicFile(f);
        FileOutputStream fos = af.startWrite();
        try {
            final ProtoOutputStream protoOut = new ProtoOutputStream(fos);
            final long token = protoOut.start(IntervalStatsProto.CONFIGURATIONS);
            config.dumpDebug(protoOut, IntervalStatsProto.Configuration.CONFIG, false, false);
            protoOut.end(token);
            protoOut.flush();
            af.finishWrite(fos);
            fos = null;
        } finally {
            af.failWrite(fos);
        }
    }

    private void readFromProto(File f, Configuration config) throws Exception {
        final AtomicFile afRead = new AtomicFile(f);
        try (FileInputStream in = afRead.openRead()) {
            final ProtoInputStream protoIn = new ProtoInputStream(in);
            if (protoIn.nextField(IntervalStatsProto.CONFIGURATIONS)) {
                final long token = protoIn.start(IntervalStatsProto.CONFIGURATIONS);
                if (protoIn.nextField(IntervalStatsProto.Configuration.CONFIG)) {
                    config.readFromProto(protoIn, IntervalStatsProto.Configuration.CONFIG);
                    protoIn.end(token);
                }
            }
        }
    }
}
