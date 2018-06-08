/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tests app ops version upgrades
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppOpsUpgradeTest {
    private static final String TAG = AppOpsUpgradeTest.class.getSimpleName();
    private static final String APP_OPS_UNVERSIONED_ASSET_PATH =
            "AppOpsUpgradeTest/appops-unversioned.xml";
    private static final String APP_OPS_FILENAME = "appops-test.xml";
    private static final int NON_DEFAULT_OPS_IN_FILE = 4;
    private static final int CURRENT_VERSION = 1;

    private File mAppOpsFile;
    private Context mContext;
    private Handler mHandler;

    private void extractAppOpsFile() {
        mAppOpsFile.getParentFile().mkdirs();
        if (mAppOpsFile.exists()) {
            mAppOpsFile.delete();
        }
        try (FileOutputStream out = new FileOutputStream(mAppOpsFile);
             InputStream in = mContext.getAssets().open(APP_OPS_UNVERSIONED_ASSET_PATH,
                     AssetManager.ACCESS_BUFFER)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) >= 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            Log.d(TAG, "Successfully copied xml to " + mAppOpsFile.getAbsolutePath());
        } catch (IOException exc) {
            Log.e(TAG, "Exception while copying appops xml", exc);
            fail();
        }
    }

    private void assertSameModes(SparseArray<AppOpsService.UidState> uidStates, int op1, int op2) {
        int numberOfNonDefaultOps = 0;
        final int defaultModeOp1 = AppOpsManager.opToDefaultMode(op1);
        final int defaultModeOp2 = AppOpsManager.opToDefaultMode(op2);
        for(int i = 0; i < uidStates.size(); i++) {
            final AppOpsService.UidState uidState = uidStates.valueAt(i);
            if (uidState.opModes != null) {
                final int uidMode1 = uidState.opModes.get(op1, defaultModeOp1);
                final int uidMode2 = uidState.opModes.get(op2, defaultModeOp2);
                assertEquals(uidMode1, uidMode2);
                if (uidMode1 != defaultModeOp1) {
                    numberOfNonDefaultOps++;
                }
            }
            if (uidState.pkgOps == null) {
                continue;
            }
            for (int j = 0; j < uidState.pkgOps.size(); j++) {
                final AppOpsService.Ops ops = uidState.pkgOps.valueAt(j);
                if (ops == null) {
                    continue;
                }
                final AppOpsService.Op _op1 = ops.get(op1);
                final AppOpsService.Op _op2 = ops.get(op2);
                final int mode1 = (_op1 == null) ? defaultModeOp1 : _op1.mode;
                final int mode2 = (_op2 == null) ? defaultModeOp2 : _op2.mode;
                assertEquals(mode1, mode2);
                if (mode1 != defaultModeOp1) {
                    numberOfNonDefaultOps++;
                }
            }
        }
        assertEquals(numberOfNonDefaultOps, NON_DEFAULT_OPS_IN_FILE);
    }

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mAppOpsFile = new File(mContext.getFilesDir(), APP_OPS_FILENAME);
        extractAppOpsFile();
        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
    }

    @Test
    public void testUpgradeFromNoVersion() throws Exception {
        AppOpsDataParser parser = new AppOpsDataParser(mAppOpsFile);
        assertTrue(parser.parse());
        assertEquals(AppOpsDataParser.NO_VERSION, parser.mVersion);
        AppOpsService testService = new AppOpsService(mAppOpsFile, mHandler); // trigger upgrade
        assertSameModes(testService.mUidStates, AppOpsManager.OP_RUN_IN_BACKGROUND,
                AppOpsManager.OP_RUN_ANY_IN_BACKGROUND);
        testService.mContext = mContext;
        mHandler.removeCallbacks(testService.mWriteRunner);
        testService.writeState();
        assertTrue(parser.parse());
        assertEquals(CURRENT_VERSION, parser.mVersion);
    }

    /**
     * Class to parse data from the appops xml. Currently only parses and holds the version number.
     * Other fields may be added as and when required for testing.
     */
    private static final class AppOpsDataParser {
        static final int NO_VERSION = -1;
        int mVersion;
        private File mFile;

        AppOpsDataParser(File file) {
            mFile = file;
            mVersion = NO_VERSION;
        }

        boolean parse() {
            try (FileInputStream stream = new FileInputStream(mFile)) {
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(stream, StandardCharsets.UTF_8.name());
                int type;
                while ((type = parser.next()) != XmlPullParser.START_TAG
                        && type != XmlPullParser.END_DOCUMENT) {
                    ;
                }
                if (type != XmlPullParser.START_TAG) {
                    throw new IllegalStateException("no start tag found");
                }
                final String versionString = parser.getAttributeValue(null, "v");
                if (versionString != null) {
                    mVersion = Integer.parseInt(versionString);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed while parsing test appops xml", e);
                return false;
            }
            return true;
        }
    }
}
