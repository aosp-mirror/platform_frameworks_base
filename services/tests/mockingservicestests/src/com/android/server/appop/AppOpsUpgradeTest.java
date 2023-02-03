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

package com.android.server.appop;

import static android.app.AppOpsManager.OP_SCHEDULE_EXACT_ALARM;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doAnswer;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.util.ArrayUtils;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.server.LocalServices;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.permission.PermissionManagerServiceInternal;
import com.android.server.pm.pkg.PackageStateInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;
import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tests app ops version upgrades
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppOpsUpgradeTest {
    private static final String TAG = AppOpsUpgradeTest.class.getSimpleName();
    private static final String APP_OPS_UNVERSIONED_ASSET_PATH =
            "AppOpsUpgradeTest/appops-unversioned.xml";
    private static final String APP_OPS_VERSION_1_ASSET_PATH =
            "AppOpsUpgradeTest/appops-version-1.xml";
    private static final String APP_OPS_FILENAME = "appops-test.xml";
    private static final int NON_DEFAULT_OPS_IN_FILE = 4;

    private static final Context sContext = InstrumentationRegistry.getTargetContext();
    private static final File sAppOpsFile = new File(sContext.getFilesDir(), APP_OPS_FILENAME);

    private Context mTestContext;
    private MockitoSession mMockitoSession;

    @Mock
    private PackageManagerInternal mPackageManagerInternal;
    @Mock
    private PackageManager mPackageManager;
    @Mock
    private UserManagerInternal mUserManagerInternal;
    @Mock
    private PermissionManagerServiceInternal mPermissionManagerInternal;
    @Mock
    private Handler mHandler;

    private static void extractAppOpsFile(String assetPath) {
        sAppOpsFile.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(sAppOpsFile);
             InputStream in = sContext.getAssets().open(assetPath, AssetManager.ACCESS_BUFFER)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) >= 0) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            Log.d(TAG, "Successfully copied xml to " + sAppOpsFile.getAbsolutePath());
        } catch (IOException exc) {
            Log.e(TAG, "Exception while copying appops xml", exc);
            fail();
        }
    }

    private void assertSameModes(SparseArray<AppOpsService.UidState> uidStates,
            int op1, int op2) {
        int numberOfNonDefaultOps = 0;
        final int defaultModeOp1 = AppOpsManager.opToDefaultMode(op1);
        final int defaultModeOp2 = AppOpsManager.opToDefaultMode(op2);
        for (int i = 0; i < uidStates.size(); i++) {
            final AppOpsService.UidState uidState = uidStates.valueAt(i);
            SparseIntArray opModes = uidState.getNonDefaultUidModes();
            if (opModes != null) {
                final int uidMode1 = opModes.get(op1, defaultModeOp1);
                final int uidMode2 = opModes.get(op2, defaultModeOp2);
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
                final int mode1 = (_op1 == null) ? defaultModeOp1 : _op1.getMode();
                final int mode2 = (_op2 == null) ? defaultModeOp2 : _op2.getMode();
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
        if (sAppOpsFile.exists()) {
            sAppOpsFile.delete();
        }

        mMockitoSession = mockitoSession()
                .initMocks(this)
                .spyStatic(LocalServices.class)
                .mockStatic(SystemServerInitThreadPool.class)
                .strictness(Strictness.LENIENT)
                .startMocking();

        doReturn(mPermissionManagerInternal).when(
                () -> LocalServices.getService(PermissionManagerServiceInternal.class));
        doReturn(mUserManagerInternal).when(
                () -> LocalServices.getService(UserManagerInternal.class));
        doReturn(mPackageManagerInternal).when(
                () -> LocalServices.getService(PackageManagerInternal.class));

        mTestContext = spy(sContext);

        // Pretend everybody has all permissions
        doNothing().when(mTestContext).enforcePermission(anyString(), anyInt(), anyInt(),
                nullable(String.class));

        doReturn(mPackageManager).when(mTestContext).getPackageManager();

        // Stub out package calls to disable AppOpsService#updatePermissionRevokedCompat
        doReturn(null).when(mPackageManager).getPackagesForUid(anyInt());

        doReturn(new ArrayMap<String, PackageStateInternal>()).when(mPackageManagerInternal)
                .getPackageStates();

        doReturn(new int[] {0}).when(mUserManagerInternal).getUserIds();
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void upgradeRunAnyInBackground() {
        extractAppOpsFile(APP_OPS_UNVERSIONED_ASSET_PATH);

        AppOpsService testService = new AppOpsService(sAppOpsFile, mHandler, mTestContext);

        testService.upgradeRunAnyInBackgroundLocked();
        assertSameModes(testService.mUidStates, AppOpsManager.OP_RUN_IN_BACKGROUND,
                AppOpsManager.OP_RUN_ANY_IN_BACKGROUND);
    }

    private static int getModeInFile(int uid) {
        switch (uid) {
            case 10198:
                return 0;
            case 10200:
                return 1;
            case 1110200:
            case 10267:
            case 1110181:
                return 2;
            default:
                return AppOpsManager.opToDefaultMode(OP_SCHEDULE_EXACT_ALARM);
        }
    }

    @Test
    public void upgradeScheduleExactAlarm() {
        extractAppOpsFile(APP_OPS_VERSION_1_ASSET_PATH);

        String[] packageNames = {"p1", "package2", "pkg3", "package.4", "pkg-5", "pkg.6"};
        int[] appIds = {10267, 10181, 10198, 10199, 10200, 4213};
        int[] userIds = {0, 10, 11};

        doReturn(userIds).when(mUserManagerInternal).getUserIds();

        doReturn(packageNames).when(mPermissionManagerInternal).getAppOpPermissionPackages(
                AppOpsManager.opToPermission(OP_SCHEDULE_EXACT_ALARM));

        doAnswer(invocation -> {
            String pkg = invocation.getArgument(0);
            int index = ArrayUtils.indexOf(packageNames, pkg);
            if (index < 0) {
                return index;
            }
            int userId = invocation.getArgument(2);
            return UserHandle.getUid(userId, appIds[index]);
        }).when(mPackageManagerInternal).getPackageUid(anyString(), anyLong(), anyInt());

        AppOpsService testService = new AppOpsService(sAppOpsFile, mHandler, mTestContext);

        testService.upgradeScheduleExactAlarmLocked();

        for (int userId : userIds) {
            for (int appId : appIds) {
                final int uid = UserHandle.getUid(userId, appId);
                final int previousMode = getModeInFile(uid);

                final int expectedMode;
                if (previousMode == AppOpsManager.opToDefaultMode(OP_SCHEDULE_EXACT_ALARM)) {
                    expectedMode = AppOpsManager.MODE_ALLOWED;
                } else {
                    expectedMode = previousMode;
                }
                final AppOpsService.UidState uidState = testService.mUidStates.get(uid);
                assertEquals(expectedMode, uidState.getUidMode(OP_SCHEDULE_EXACT_ALARM));
            }
        }

        // These uids don't even declare the permission. So should stay as default / empty.
        int[] unrelatedUidsInFile = {10225, 10178};

        for (int uid : unrelatedUidsInFile) {
            final AppOpsService.UidState uidState = testService.mUidStates.get(uid);
            assertEquals(AppOpsManager.opToDefaultMode(OP_SCHEDULE_EXACT_ALARM),
                    uidState.getUidMode(OP_SCHEDULE_EXACT_ALARM));
        }
    }

    @Test
    public void upgradeFromNoFile() {
        assertFalse(sAppOpsFile.exists());

        AppOpsService testService = spy(
                new AppOpsService(sAppOpsFile, mHandler, mTestContext));

        doNothing().when(testService).upgradeRunAnyInBackgroundLocked();
        doNothing().when(testService).upgradeScheduleExactAlarmLocked();

        // trigger upgrade
        testService.systemReady();

        verify(testService, never()).upgradeRunAnyInBackgroundLocked();
        verify(testService, never()).upgradeScheduleExactAlarmLocked();

        testService.writeState();

        assertTrue(sAppOpsFile.exists());

        AppOpsDataParser parser = new AppOpsDataParser(sAppOpsFile);
        assertTrue(parser.parse());
        assertEquals(AppOpsService.CURRENT_VERSION, parser.mVersion);
    }

    @Test
    public void upgradeFromNoVersion() {
        extractAppOpsFile(APP_OPS_UNVERSIONED_ASSET_PATH);
        AppOpsDataParser parser = new AppOpsDataParser(sAppOpsFile);
        assertTrue(parser.parse());
        assertEquals(AppOpsDataParser.NO_VERSION, parser.mVersion);

        AppOpsService testService = spy(
                new AppOpsService(sAppOpsFile, mHandler, mTestContext));

        doNothing().when(testService).upgradeRunAnyInBackgroundLocked();
        doNothing().when(testService).upgradeScheduleExactAlarmLocked();

        // trigger upgrade
        testService.systemReady();

        verify(testService).upgradeRunAnyInBackgroundLocked();
        verify(testService).upgradeScheduleExactAlarmLocked();

        testService.writeState();
        assertTrue(parser.parse());
        assertEquals(AppOpsService.CURRENT_VERSION, parser.mVersion);
    }

    @Test
    public void upgradeFromVersion1() {
        extractAppOpsFile(APP_OPS_VERSION_1_ASSET_PATH);
        AppOpsDataParser parser = new AppOpsDataParser(sAppOpsFile);
        assertTrue(parser.parse());
        assertEquals(1, parser.mVersion);

        AppOpsService testService = spy(
                new AppOpsService(sAppOpsFile, mHandler, mTestContext));

        doNothing().when(testService).upgradeRunAnyInBackgroundLocked();
        doNothing().when(testService).upgradeScheduleExactAlarmLocked();

        // trigger upgrade
        testService.systemReady();

        verify(testService, never()).upgradeRunAnyInBackgroundLocked();
        verify(testService).upgradeScheduleExactAlarmLocked();

        testService.writeState();
        assertTrue(parser.parse());
        assertEquals(AppOpsService.CURRENT_VERSION, parser.mVersion);
    }

    /**
     * Class to parse data from the appops xml. Currently only parses and holds the version number.
     * Other fields may be added as and when required for testing.
     */
    private static final class AppOpsDataParser {
        static final int NO_VERSION = -123;
        int mVersion;
        private File mFile;

        AppOpsDataParser(File file) {
            mFile = file;
            mVersion = NO_VERSION;
        }

        boolean parse() {
            try (FileInputStream stream = new FileInputStream(mFile)) {
                TypedXmlPullParser parser = Xml.resolvePullParser(stream);
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
