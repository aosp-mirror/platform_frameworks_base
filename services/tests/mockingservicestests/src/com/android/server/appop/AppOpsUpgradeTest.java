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
import static android.app.AppOpsManager.OP_USE_FULL_SCREEN_INTENT;
import static android.app.AppOpsManager._NUM_OP;
import static android.companion.virtual.VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT;

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
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.UserPackage;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseArray;
import android.util.Xml;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

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

    private static final String APP_OPS_VERSION_3_ASSET_PATH =
            "AppOpsUpgradeTest/appops-version-3.xml";

    private static final String APP_OPS_FILENAME = "appops-test.xml";

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
    @Mock
    private PermissionManager mPermissionManager;

    private Object mLock = new Object();
    private SparseArray<int[]> mSwitchedOps;

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

        // Build mSwitchedOps
        mSwitchedOps = buildSwitchedOpsArray();
    }

    private SparseArray<int[]> buildSwitchedOpsArray() {
        SparseArray<int[]> switchedOps = new SparseArray<>();
        for (int switchedCode = 0; switchedCode < _NUM_OP; switchedCode++) {
            int switchCode = AppOpsManager.opToSwitch(switchedCode);
            switchedOps.put(switchCode,
                    ArrayUtils.appendInt(switchedOps.get(switchCode), switchedCode));
        }
        return switchedOps;
    }

    @After
    public void tearDown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void upgradeRunAnyInBackground() {
        extractAppOpsFile(APP_OPS_UNVERSIONED_ASSET_PATH);

        AppOpsCheckingServiceImpl testService = new AppOpsCheckingServiceImpl(sAppOpsFile, mLock,
                mHandler, mTestContext, mSwitchedOps);
        testService.readState();

        testService.upgradeRunAnyInBackgroundLocked();

        assertSameModes(testService, AppOpsManager.OP_RUN_IN_BACKGROUND,
                AppOpsManager.OP_RUN_ANY_IN_BACKGROUND);
    }

    private void assertSameModes(AppOpsCheckingServiceImpl testService, int op1, int op2) {
        for (int uid : testService.getUidsWithNonDefaultModes()) {
            assertEquals(
                    testService.getUidMode(uid, PERSISTENT_DEVICE_ID_DEFAULT, op1),
                    testService.getUidMode(uid, PERSISTENT_DEVICE_ID_DEFAULT, op2)
            );
        }
        for (UserPackage pkg : testService.getPackagesWithNonDefaultModes()) {
            assertEquals(
                    testService.getPackageMode(pkg.packageName, op1, pkg.userId),
                    testService.getPackageMode(pkg.packageName, op2, pkg.userId)
            );
        }
    }

    private static int getModeInFile(int uid, int op) {
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
                return AppOpsManager.opToDefaultMode(op);
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

        AppOpsCheckingServiceImpl testService = new AppOpsCheckingServiceImpl(sAppOpsFile, mLock,
                mHandler, mTestContext, mSwitchedOps);
        testService.readState();

        testService.upgradeScheduleExactAlarmLocked();

        for (int userId : userIds) {
            for (int appId : appIds) {
                final int uid = UserHandle.getUid(userId, appId);
                final int previousMode = getModeInFile(uid, OP_SCHEDULE_EXACT_ALARM);

                final int expectedMode;
                if (previousMode == AppOpsManager.opToDefaultMode(OP_SCHEDULE_EXACT_ALARM)) {
                    expectedMode = AppOpsManager.MODE_ALLOWED;
                } else {
                    expectedMode = previousMode;
                }
                int mode =
                        testService.getUidMode(
                                uid, PERSISTENT_DEVICE_ID_DEFAULT, OP_SCHEDULE_EXACT_ALARM);
                assertEquals(expectedMode, mode);
            }
        }

        // These uids don't even declare the permission. So should stay as default / empty.
        int[] unrelatedUidsInFile = {10225, 10178};

        for (int uid : unrelatedUidsInFile) {
            int mode =
                    testService.getUidMode(
                            uid, PERSISTENT_DEVICE_ID_DEFAULT, OP_SCHEDULE_EXACT_ALARM);
            assertEquals(AppOpsManager.opToDefaultMode(OP_SCHEDULE_EXACT_ALARM), mode);
        }
    }

    @Test
    public void resetUseFullScreenIntent() {
        extractAppOpsFile(APP_OPS_VERSION_3_ASSET_PATH);

        String[] packageNames = {"p1", "package2", "pkg3", "package.4", "pkg-5", "pkg.6"};
        int[] appIds = {10267, 10181, 10198, 10199, 10200, 4213};
        int[] userIds = {0, 10, 11};
        int flag = 0;

        doReturn(userIds).when(mUserManagerInternal).getUserIds();

        doReturn(packageNames).when(mPermissionManagerInternal).getAppOpPermissionPackages(
                AppOpsManager.opToPermission(OP_USE_FULL_SCREEN_INTENT));

        doReturn(mPermissionManager).when(mTestContext).getSystemService(PermissionManager.class);

        doReturn(flag).when(mPackageManager).getPermissionFlags(
                anyString(), anyString(), isA(UserHandle.class));

        doAnswer(invocation -> {
            String pkg = invocation.getArgument(0);
            int index = ArrayUtils.indexOf(packageNames, pkg);
            if (index < 0) {
                return index;
            }
            int userId = invocation.getArgument(2);
            return UserHandle.getUid(userId, appIds[index]);
        }).when(mPackageManagerInternal).getPackageUid(anyString(), anyLong(), anyInt());

        AppOpsCheckingServiceImpl testService = new AppOpsCheckingServiceImpl(sAppOpsFile, mLock,
                mHandler, mTestContext, mSwitchedOps);
        testService.readState();

        synchronized (testService) {
            testService.resetUseFullScreenIntentLocked();
        }

        for (int userId : userIds) {
            for (int appId : appIds) {
                final int uid = UserHandle.getUid(userId, appId);
                final int expectedMode = AppOpsManager.opToDefaultMode(OP_USE_FULL_SCREEN_INTENT);
                synchronized (testService) {
                    int mode =
                            testService.getUidMode(
                                    uid, PERSISTENT_DEVICE_ID_DEFAULT, OP_USE_FULL_SCREEN_INTENT);
                    assertEquals(expectedMode, mode);
                }
            }
        }
    }

    @Test
    public void upgradeFromNoFile() {
        assertFalse(sAppOpsFile.exists());

        AppOpsCheckingServiceImpl testService = spy(new AppOpsCheckingServiceImpl(sAppOpsFile,
                mLock, mHandler, mTestContext, mSwitchedOps));
        testService.readState();

        doNothing().when(testService).upgradeRunAnyInBackgroundLocked();
        doNothing().when(testService).upgradeScheduleExactAlarmLocked();
        doNothing().when(testService).resetUseFullScreenIntentLocked();

        // trigger upgrade
        testService.systemReady();

        verify(testService, never()).upgradeRunAnyInBackgroundLocked();
        verify(testService, never()).upgradeScheduleExactAlarmLocked();
        verify(testService, never()).resetUseFullScreenIntentLocked();

        testService.writeState();

        assertTrue(sAppOpsFile.exists());

        AppOpsDataParser parser = new AppOpsDataParser(sAppOpsFile);
        assertTrue(parser.parse());
        assertEquals(AppOpsCheckingServiceImpl.CURRENT_VERSION, parser.mVersion);
    }

    @Test
    public void upgradeFromNoVersion() {
        extractAppOpsFile(APP_OPS_UNVERSIONED_ASSET_PATH);
        AppOpsDataParser parser = new AppOpsDataParser(sAppOpsFile);
        assertTrue(parser.parse());
        assertEquals(AppOpsDataParser.NO_VERSION, parser.mVersion);

        AppOpsCheckingServiceImpl testService = spy(new AppOpsCheckingServiceImpl(sAppOpsFile,
                mLock, mHandler, mTestContext, mSwitchedOps));
        testService.readState();

        doNothing().when(testService).upgradeRunAnyInBackgroundLocked();
        doNothing().when(testService).upgradeScheduleExactAlarmLocked();
        doNothing().when(testService).resetUseFullScreenIntentLocked();

        // trigger upgrade
        testService.systemReady();

        verify(testService).upgradeRunAnyInBackgroundLocked();
        verify(testService).upgradeScheduleExactAlarmLocked();
        verify(testService).resetUseFullScreenIntentLocked();

        testService.writeState();
        assertTrue(parser.parse());
        assertEquals(AppOpsCheckingServiceImpl.CURRENT_VERSION, parser.mVersion);
    }

    @Test
    public void upgradeFromVersion1() {
        extractAppOpsFile(APP_OPS_VERSION_1_ASSET_PATH);
        AppOpsDataParser parser = new AppOpsDataParser(sAppOpsFile);
        assertTrue(parser.parse());
        assertEquals(1, parser.mVersion);

        AppOpsCheckingServiceImpl testService = spy(new AppOpsCheckingServiceImpl(sAppOpsFile,
                mLock, mHandler, mTestContext, mSwitchedOps));
        testService.readState();

        doNothing().when(testService).upgradeRunAnyInBackgroundLocked();
        doNothing().when(testService).upgradeScheduleExactAlarmLocked();
        doNothing().when(testService).resetUseFullScreenIntentLocked();

        // trigger upgrade
        testService.systemReady();

        verify(testService, never()).upgradeRunAnyInBackgroundLocked();
        verify(testService).upgradeScheduleExactAlarmLocked();
        verify(testService).resetUseFullScreenIntentLocked();

        testService.writeState();
        assertTrue(parser.parse());
        assertEquals(AppOpsCheckingServiceImpl.CURRENT_VERSION, parser.mVersion);
    }

    @Test
    public void resetFromVersion3() {
        extractAppOpsFile(APP_OPS_VERSION_3_ASSET_PATH);
        AppOpsDataParser parser = new AppOpsDataParser(sAppOpsFile);
        assertTrue(parser.parse());
        assertEquals(3, parser.mVersion);

        AppOpsCheckingServiceImpl testService = spy(new AppOpsCheckingServiceImpl(sAppOpsFile,
                mLock, mHandler, mTestContext, mSwitchedOps));
        testService.readState();

        doNothing().when(testService).upgradeRunAnyInBackgroundLocked();
        doNothing().when(testService).upgradeScheduleExactAlarmLocked();
        doNothing().when(testService).resetUseFullScreenIntentLocked();

        testService.systemReady();

        verify(testService, never()).upgradeRunAnyInBackgroundLocked();
        verify(testService, never()).upgradeScheduleExactAlarmLocked();
        verify(testService).resetUseFullScreenIntentLocked();

        testService.writeState();
        assertTrue(parser.parse());
        assertEquals(AppOpsCheckingServiceImpl.CURRENT_VERSION, parser.mVersion);
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
