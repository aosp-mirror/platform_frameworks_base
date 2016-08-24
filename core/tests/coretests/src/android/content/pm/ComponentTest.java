/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.content.pm;

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.GET_DISABLED_COMPONENTS;

import android.test.suitebuilder.annotation.Suppress;
import com.android.frameworks.coretests.enabled_app.DisabledActivity;
import com.android.frameworks.coretests.enabled_app.DisabledProvider;
import com.android.frameworks.coretests.enabled_app.DisabledReceiver;
import com.android.frameworks.coretests.enabled_app.DisabledService;
import com.android.frameworks.coretests.enabled_app.EnabledActivity;
import com.android.frameworks.coretests.enabled_app.EnabledProvider;
import com.android.frameworks.coretests.enabled_app.EnabledReceiver;
import com.android.frameworks.coretests.enabled_app.EnabledService;

import android.content.ComponentName;
import android.content.Intent;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.List;

/**
 * Tests for disabling and enabling application components.
 *
 * Note: These tests are on the slow side.  This is probably because most of the tests trigger the
 * package settings file to get written out by the PackageManagerService.  Better, more unit-y test
 * would fix this.
 */
@Suppress  // Failing.
public class ComponentTest extends AndroidTestCase {

    private PackageManager mPackageManager;
    private Intent mDisabledActivityIntent;
    private Intent mEnabledActivityIntent;
    private Intent mDisabledServiceIntent;
    private Intent mEnabledServiceIntent;
    private Intent mDisabledReceiverIntent;
    private Intent mEnabledReceiverIntent;
    private Intent mDisabledAppEnabledActivityIntent;

    private static final String ENABLED_PACKAGENAME =
            "com.android.frameworks.coretests.enabled_app";
    private static final String DISABLED_PACKAGENAME =
            "com.android.frameworks.coretests.disabled_app";
    private static final String DISABLED_ACTIVITY_CLASSNAME =
            DisabledActivity.class.getName();
    private static final ComponentName DISABLED_ACTIVITY_COMPONENTNAME =
            new ComponentName(ENABLED_PACKAGENAME, DISABLED_ACTIVITY_CLASSNAME);
    private static final String ENABLED_ACTIVITY_CLASSNAME =
            EnabledActivity.class.getName();
    private static final ComponentName ENABLED_ACTIVITY_COMPONENTNAME =
            new ComponentName(ENABLED_PACKAGENAME, ENABLED_ACTIVITY_CLASSNAME);
    private static final String DISABLED_SERVICE_CLASSNAME =
            DisabledService.class.getName();
    private static final ComponentName DISABLED_SERVICE_COMPONENTNAME =
            new ComponentName(ENABLED_PACKAGENAME, DISABLED_SERVICE_CLASSNAME);
    private static final String DISABLED_PROVIDER_CLASSNAME =
            DisabledProvider.class.getName();
    private static final ComponentName DISABLED_PROVIDER_COMPONENTNAME =
            new ComponentName(ENABLED_PACKAGENAME, DISABLED_PROVIDER_CLASSNAME);
    private static final String DISABLED_PROVIDER_NAME = DisabledProvider.class.getName();
    private static final String ENABLED_SERVICE_CLASSNAME =
            EnabledService.class.getName();
    private static final ComponentName ENABLED_SERVICE_COMPONENTNAME =
            new ComponentName(ENABLED_PACKAGENAME, ENABLED_SERVICE_CLASSNAME);
    private static final String DISABLED_RECEIVER_CLASSNAME =
            DisabledReceiver.class.getName();
    private static final ComponentName DISABLED_RECEIVER_COMPONENTNAME =
            new ComponentName(ENABLED_PACKAGENAME, DISABLED_RECEIVER_CLASSNAME);
    private static final String ENABLED_RECEIVER_CLASSNAME =
            EnabledReceiver.class.getName();
    private static final ComponentName ENABLED_RECEIVER_COMPONENTNAME =
            new ComponentName(ENABLED_PACKAGENAME, ENABLED_RECEIVER_CLASSNAME);
    private static final String ENABLED_PROVIDER_CLASSNAME =
            EnabledProvider.class.getName();
    private static final ComponentName ENABLED_PROVIDER_COMPONENTNAME =
            new ComponentName(ENABLED_PACKAGENAME, ENABLED_PROVIDER_CLASSNAME);
    private static final String ENABLED_PROVIDER_NAME = EnabledProvider.class.getName();
    private static final String DISABLED_APP_ENABLED_ACTIVITY_CLASSNAME =
            com.android.frameworks.coretests.disabled_app.EnabledActivity.class.getName();
    private static final ComponentName DISABLED_APP_ENABLED_ACTIVITY_COMPONENTNAME =
            new ComponentName(DISABLED_PACKAGENAME, DISABLED_APP_ENABLED_ACTIVITY_CLASSNAME);
    private static final String TEST_CATEGORY =
            "com.android.frameworks.coretests.enabled_app.TEST_CATEGORY";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mPackageManager = mContext.getPackageManager();
        mDisabledActivityIntent = new Intent();
        mDisabledActivityIntent.setComponent(DISABLED_ACTIVITY_COMPONENTNAME);
        mEnabledActivityIntent = new Intent();
        mEnabledActivityIntent.setComponent(ENABLED_ACTIVITY_COMPONENTNAME);
        mDisabledServiceIntent = new Intent();
        mDisabledServiceIntent.setComponent(DISABLED_SERVICE_COMPONENTNAME);
        mEnabledServiceIntent = new Intent();
        mEnabledServiceIntent.setComponent(ENABLED_SERVICE_COMPONENTNAME);
        mDisabledReceiverIntent = new Intent("android.intent.action.ENABLED_APP_DISABLED_RECEIVER");
        mDisabledReceiverIntent.setComponent(DISABLED_RECEIVER_COMPONENTNAME);
        mEnabledReceiverIntent = new Intent("android.intent.action.ENABLED_APP_ENABLED_RECEIVER");
        mEnabledReceiverIntent.setComponent(ENABLED_RECEIVER_COMPONENTNAME);
        mDisabledAppEnabledActivityIntent = new Intent();
        mDisabledAppEnabledActivityIntent.setComponent(DISABLED_APP_ENABLED_ACTIVITY_COMPONENTNAME);
    }

    @SmallTest
    public void testContextNotNull() throws Exception {
        assertNotNull(mContext);
    }

    @SmallTest
    public void testResolveDisabledActivity() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info = mPackageManager.resolveActivity(mDisabledActivityIntent, 0);
        assertNull(info);

        final ResolveInfo info2 = mPackageManager.resolveActivity(
                mDisabledActivityIntent, GET_DISABLED_COMPONENTS);
        assertNotNull(info2);
        assertNotNull(info2.activityInfo);
        assertFalse(info2.activityInfo.enabled);
    }

    @SmallTest
    public void testResolveEnabledActivity() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info = mPackageManager.resolveActivity(mEnabledActivityIntent, 0);
        assertNotNull(info);
        assertNotNull(info);
        assertNotNull(info.activityInfo);
        assertTrue(info.activityInfo.enabled);
    }

    @MediumTest
    public void testQueryDisabledActivity() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final List<ResolveInfo> infoList =
                mPackageManager.queryIntentActivities(mDisabledActivityIntent, 0);
        assertEquals(0, infoList.size());

        final List<ResolveInfo> infoList2 =
                mPackageManager.queryIntentActivities(mDisabledActivityIntent,
                                                      GET_DISABLED_COMPONENTS);
        assertEquals(1, infoList2.size());
        final ResolveInfo info = infoList2.get(0);
        assertNotNull(info);
        assertNotNull(info.activityInfo);
        assertFalse(info.activityInfo.enabled);
    }

    @SmallTest
    public void testQueryEnabledActivity() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final List<ResolveInfo> infoList =
                mPackageManager.queryIntentActivities(mEnabledActivityIntent, 0);
        assertEquals(1, infoList.size());
        final ResolveInfo info = infoList.get(0);
        assertNotNull(info);
        assertNotNull(info.activityInfo);
        assertTrue(info.activityInfo.enabled);
    }

    @MediumTest
    public void testGetDisabledActivityInfo() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        try {
            mPackageManager.getActivityInfo(DISABLED_ACTIVITY_COMPONENTNAME, 0);
            fail("Attempt to get info on disabled component should fail.");
        } catch (PackageManager.NameNotFoundException e) {
            // expected
        }

        final ActivityInfo activityInfo =
              mPackageManager.getActivityInfo(DISABLED_ACTIVITY_COMPONENTNAME,
                                              GET_DISABLED_COMPONENTS);
        assertNotNull(activityInfo);
        assertFalse(activityInfo.enabled);
    }

    @SmallTest
    public void testGetEnabledActivityInfo() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        ActivityInfo activityInfo =
              mPackageManager.getActivityInfo(ENABLED_ACTIVITY_COMPONENTNAME, 0);
        assertNotNull(activityInfo);
        assertTrue(activityInfo.enabled);
    }

    @MediumTest
    public void testEnableActivity() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info = mPackageManager.resolveActivity(mDisabledActivityIntent, 0);
        assertNull(info);
        mPackageManager.setComponentEnabledSetting(DISABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_ENABLED,
                                                   PackageManager.DONT_KILL_APP);
        final ResolveInfo info2 =
                mPackageManager.resolveActivity(mDisabledActivityIntent,
                                                0);
        assertNotNull(info2);
        assertNotNull(info2.activityInfo);
        assertFalse(info2.activityInfo.enabled);

        final List<ResolveInfo> infoList =
                mPackageManager.queryIntentActivities(mDisabledActivityIntent, 0);
        assertEquals(1, infoList.size());
    }

    @MediumTest
    public void testDisableActivity() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info = mPackageManager.resolveActivity(mEnabledActivityIntent, 0);
        assertNotNull(info);
        assertNotNull(info.activityInfo);
        mPackageManager.setComponentEnabledSetting(ENABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DISABLED,
                                                   PackageManager.DONT_KILL_APP);
        final ResolveInfo info2 =
                mPackageManager.resolveActivity(mEnabledActivityIntent,
                                                0);
        assertNull(info2);

        final ResolveInfo info3 = mPackageManager.resolveActivity(mEnabledActivityIntent,
                                                                  GET_DISABLED_COMPONENTS);
        assertNotNull(info3);
        assertNotNull(info3.activityInfo);
        assertTrue(info3.activityInfo.enabled);

        final List<ResolveInfo> infoList =
                mPackageManager.queryIntentActivities(mEnabledActivityIntent, 0);
        assertEquals(0, infoList.size());
    }

    @SmallTest
    public void testResolveDisabledService() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_SERVICE_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info = mPackageManager.resolveService(mDisabledServiceIntent, 0);
        assertNull(info);

        final ResolveInfo info2 = mPackageManager.resolveService(
                mDisabledServiceIntent, GET_DISABLED_COMPONENTS);
        assertNotNull(info2);
        assertNotNull(info2.serviceInfo);
        assertFalse(info2.serviceInfo.enabled);
    }

    @SmallTest
    public void testResolveEnabledService() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_SERVICE_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info = mPackageManager.resolveService(mEnabledServiceIntent, 0);
        assertNotNull(info);
        assertNotNull(info);
        assertNotNull(info.serviceInfo);
        assertTrue(info.serviceInfo.enabled);
    }

    @SmallTest
    public void testQueryDisabledService() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_SERVICE_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final List<ResolveInfo> infoList =
                mPackageManager.queryIntentServices(mDisabledServiceIntent, 0);
        assertEquals(0, infoList.size());

        final List<ResolveInfo> infoList2 =
                mPackageManager.queryIntentServices(mDisabledServiceIntent,
                                                      GET_DISABLED_COMPONENTS);
        assertEquals(1, infoList2.size());
        final ResolveInfo info = infoList2.get(0);
        assertNotNull(info);
        assertNotNull(info.serviceInfo);
        assertFalse(info.serviceInfo.enabled);
    }

    @SmallTest
    public void testQueryEnabledService() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_SERVICE_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final List<ResolveInfo> infoList =
                mPackageManager.queryIntentServices(mEnabledServiceIntent, 0);
        assertEquals(1, infoList.size());
        final ResolveInfo info = infoList.get(0);
        assertNotNull(info);
        assertNotNull(info.serviceInfo);
        assertTrue(info.serviceInfo.enabled);
    }

    @MediumTest
    public void testGetDisabledServiceInfo() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_SERVICE_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        try {
            mPackageManager.getServiceInfo(DISABLED_SERVICE_COMPONENTNAME, 0);
            fail("Attempt to get info on disabled component should fail.");
        } catch (PackageManager.NameNotFoundException e) {
            // expected
        }

        final ServiceInfo serviceInfo =
              mPackageManager.getServiceInfo(DISABLED_SERVICE_COMPONENTNAME,
                                              GET_DISABLED_COMPONENTS);
        assertNotNull(serviceInfo);
        assertFalse(serviceInfo.enabled);
    }

    @SmallTest
    public void testGetEnabledServiceInfo() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_SERVICE_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        ServiceInfo serviceInfo =
              mPackageManager.getServiceInfo(ENABLED_SERVICE_COMPONENTNAME, 0);
        assertNotNull(serviceInfo);
        assertTrue(serviceInfo.enabled);
    }

    @MediumTest
    public void testEnableService() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_SERVICE_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info = mPackageManager.resolveService(mDisabledServiceIntent, 0);
        assertNull(info);
        mPackageManager.setComponentEnabledSetting(DISABLED_SERVICE_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_ENABLED,
                                                   PackageManager.DONT_KILL_APP);
        final ResolveInfo info2 =
                mPackageManager.resolveService(mDisabledServiceIntent,
                                                0);
        assertNotNull(info2);
        assertNotNull(info2.serviceInfo);
        assertFalse(info2.serviceInfo.enabled);
    }

    @MediumTest
    public void testDisableService() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_SERVICE_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info = mPackageManager.resolveService(mEnabledServiceIntent, 0);
        assertNotNull(info);
        assertNotNull(info.serviceInfo);
        mPackageManager.setComponentEnabledSetting(ENABLED_SERVICE_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DISABLED,
                                                   PackageManager.DONT_KILL_APP);
        final ResolveInfo info2 =
                mPackageManager.resolveService(mEnabledServiceIntent,
                                                0);
        assertNull(info2);

        final ResolveInfo info3 = mPackageManager.resolveService(mEnabledServiceIntent,
                                                                  GET_DISABLED_COMPONENTS);
        assertNotNull(info3);
        assertNotNull(info3.serviceInfo);
        assertTrue(info3.serviceInfo.enabled);
    }

    @SmallTest
    public void testQueryDisabledReceiver() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_RECEIVER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final List<ResolveInfo> infoList =
                mPackageManager.queryBroadcastReceivers(mDisabledReceiverIntent, 0);
        assertEquals(0, infoList.size());

        final List<ResolveInfo> infoList2 =
                mPackageManager.queryBroadcastReceivers(mDisabledReceiverIntent,
                                                      GET_DISABLED_COMPONENTS);
        assertEquals(1, infoList2.size());
        final ResolveInfo info = infoList2.get(0);
        assertNotNull(info);
        assertNotNull(info.activityInfo);
        assertFalse(info.activityInfo.enabled);
    }

    @SmallTest
    public void testQueryEnabledReceiver() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_RECEIVER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final List<ResolveInfo> infoList =
                mPackageManager.queryBroadcastReceivers(mEnabledReceiverIntent, 0);
        assertEquals(1, infoList.size());
        final ResolveInfo info = infoList.get(0);
        assertNotNull(info);
        assertNotNull(info.activityInfo);
        assertTrue(info.activityInfo.enabled);
    }

    @MediumTest
    public void testGetDisabledReceiverInfo() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_RECEIVER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        try {
            mPackageManager.getReceiverInfo(DISABLED_RECEIVER_COMPONENTNAME, 0);
            fail("Attempt to get info on disabled component should fail.");
        } catch (PackageManager.NameNotFoundException e) {
            // expected
        }

        final ActivityInfo activityInfo =
              mPackageManager.getReceiverInfo(DISABLED_RECEIVER_COMPONENTNAME,
                                              GET_DISABLED_COMPONENTS);
        assertNotNull(activityInfo);
        assertFalse(activityInfo.enabled);
    }

    @SmallTest
    public void testGetEnabledReceiverInfo() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_RECEIVER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        ActivityInfo activityInfo =
              mPackageManager.getReceiverInfo(ENABLED_RECEIVER_COMPONENTNAME, 0);
        assertNotNull(activityInfo);
        assertTrue(activityInfo.enabled);
    }

    @MediumTest
    public void testEnableReceiver() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_RECEIVER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        try {
            mPackageManager.getReceiverInfo(DISABLED_RECEIVER_COMPONENTNAME, 0);
            fail("Attempt to get info on disabled component should fail.");
        } catch (PackageManager.NameNotFoundException e) {
            // expected
        }

        mPackageManager.setComponentEnabledSetting(DISABLED_RECEIVER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_ENABLED,
                                                   PackageManager.DONT_KILL_APP);
        ActivityInfo activityInfo =
              mPackageManager.getReceiverInfo(DISABLED_RECEIVER_COMPONENTNAME, 0);
        assertNotNull(activityInfo);
        assertFalse(activityInfo.enabled);
    }

    @MediumTest
    public void testDisableReceiver() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_RECEIVER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        ActivityInfo activityInfo =
              mPackageManager.getReceiverInfo(ENABLED_RECEIVER_COMPONENTNAME, 0);
        assertNotNull(activityInfo);
        assertTrue(activityInfo.enabled);
        mPackageManager.setComponentEnabledSetting(DISABLED_RECEIVER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DISABLED,
                                                   PackageManager.DONT_KILL_APP);
        try {
            mPackageManager.getReceiverInfo(DISABLED_RECEIVER_COMPONENTNAME, 0);
            fail("Attempt to get info on disabled component should fail.");
        } catch (PackageManager.NameNotFoundException e) {
            // expected
        }
    }

    @SmallTest
    public void testResolveEnabledProvider() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_PROVIDER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        ProviderInfo providerInfo =
                mPackageManager.resolveContentProvider(ENABLED_PROVIDER_NAME, 0);
        assertNotNull(providerInfo);
        assertTrue(providerInfo.enabled);
    }

    @SmallTest
    public void testResolveDisabledProvider() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_PROVIDER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        ProviderInfo providerInfo =
                mPackageManager.resolveContentProvider(DISABLED_PROVIDER_NAME, 0);
        assertNull(providerInfo);
        ProviderInfo providerInfo2 =
                mPackageManager.resolveContentProvider(DISABLED_PROVIDER_NAME,
                                                       GET_DISABLED_COMPONENTS);
        assertNotNull(providerInfo2);
        assertFalse(providerInfo2.enabled);
    }

    @MediumTest
    public void testEnableProvider() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_PROVIDER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);
        ProviderInfo providerInfo =
                mPackageManager.resolveContentProvider(DISABLED_PROVIDER_NAME, 0);
        assertNull(providerInfo);

        mPackageManager.setComponentEnabledSetting(DISABLED_PROVIDER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_ENABLED,
                                                   PackageManager.DONT_KILL_APP);
        ProviderInfo providerInfo2 =
                mPackageManager.resolveContentProvider(DISABLED_PROVIDER_NAME, 0);
        assertNotNull(providerInfo2);
        assertFalse(providerInfo2.enabled);
    }

    @MediumTest
    public void testDisableProvider() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_PROVIDER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);
        ProviderInfo providerInfo =
                mPackageManager.resolveContentProvider(ENABLED_PROVIDER_NAME, 0);
        assertNotNull(providerInfo);
        assertTrue(providerInfo.enabled);

        mPackageManager.setComponentEnabledSetting(ENABLED_PROVIDER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DISABLED,
                                                   PackageManager.DONT_KILL_APP);
        ProviderInfo providerInfo2 =
                mPackageManager.resolveContentProvider(ENABLED_PROVIDER_NAME, 0);
        assertNull(providerInfo2);
    }

    @SmallTest
    public void testQueryEnabledProvider() throws Exception {
        mPackageManager.setComponentEnabledSetting(ENABLED_PROVIDER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        String enabledProviderProcessName = getComponentProcessName(ENABLED_PROVIDER_NAME);
        PackageInfo pi = mPackageManager.getPackageInfo(ENABLED_PACKAGENAME, 0);
        List<ProviderInfo> providerInfoList =
                mPackageManager.queryContentProviders(enabledProviderProcessName,
                        pi.applicationInfo.uid, 0);
        assertNotNull(providerInfoList);
        assertEquals(1, providerInfoList.size());
        assertEquals(ENABLED_PROVIDER_CLASSNAME,
                     providerInfoList.get(0).name);
    }

    @MediumTest
    public void testQueryDisabledProvider() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_PROVIDER_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        PackageInfo pi = mPackageManager.getPackageInfo(ENABLED_PACKAGENAME, 0);
        
        String disabledProviderProcessName = getComponentProcessName(DISABLED_PROVIDER_NAME);
        List<ProviderInfo> providerInfoList =
                mPackageManager.queryContentProviders(disabledProviderProcessName,
                        pi.applicationInfo.uid, 0);
        assertNull(providerInfoList);


        List<ProviderInfo> providerInfoList2 =
                mPackageManager.queryContentProviders(disabledProviderProcessName,
                        pi.applicationInfo.uid, GET_DISABLED_COMPONENTS);
        assertNotNull(providerInfoList2);
        assertEquals(1, providerInfoList2.size());
        assertEquals(DISABLED_PROVIDER_CLASSNAME,
                     providerInfoList2.get(0).name);
    }

    private String getComponentProcessName(String componentNameStr) {
        ComponentInfo providerInfo =
                mPackageManager.resolveContentProvider(componentNameStr,
                                                       GET_DISABLED_COMPONENTS);
        return providerInfo.processName;
    }

    public void DISABLED_testResolveEnabledActivityInDisabledApp() throws Exception {
        mPackageManager.setApplicationEnabledSetting(DISABLED_PACKAGENAME,
                                                     COMPONENT_ENABLED_STATE_DEFAULT,
                                                     0);
        mPackageManager.setComponentEnabledSetting(DISABLED_APP_ENABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info =
                mPackageManager.resolveActivity(mDisabledAppEnabledActivityIntent, 0);
        assertNull(info);

        final ResolveInfo info2 = mPackageManager.resolveActivity(
                mDisabledAppEnabledActivityIntent, GET_DISABLED_COMPONENTS);
        assertNotNull(info2);
        assertNotNull(info2.activityInfo);
        assertTrue(info2.activityInfo.enabled);
    }

    public void DISABLED_testEnableApplication() throws Exception {
        mPackageManager.setApplicationEnabledSetting(DISABLED_PACKAGENAME,
                                                     COMPONENT_ENABLED_STATE_DEFAULT,
                                                     0);
        mPackageManager.setComponentEnabledSetting(DISABLED_APP_ENABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info =
                mPackageManager.resolveActivity(mDisabledAppEnabledActivityIntent, 0);
        assertNull(info);

        mPackageManager.setApplicationEnabledSetting(DISABLED_PACKAGENAME,
                                                     COMPONENT_ENABLED_STATE_ENABLED,
                                                     0);
        final ResolveInfo info2 = mPackageManager.resolveActivity(
                mDisabledAppEnabledActivityIntent, 0);
        assertNotNull(info2);
        assertNotNull(info2.activityInfo);
        assertTrue(info2.activityInfo.enabled);

    }

    public void DISABLED_testDisableApplication() throws Exception {
        mPackageManager.setApplicationEnabledSetting(ENABLED_PACKAGENAME,
                                                     COMPONENT_ENABLED_STATE_DEFAULT,
                                                     0);
        mPackageManager.setComponentEnabledSetting(ENABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        final ResolveInfo info = mPackageManager.resolveActivity(mEnabledActivityIntent, 0);
        assertNotNull(info);
        assertNotNull(info.activityInfo);
        assertTrue(info.activityInfo.enabled);

        mPackageManager.setApplicationEnabledSetting(ENABLED_PACKAGENAME,
                                                     COMPONENT_ENABLED_STATE_DISABLED,
                                                     0);
        final ResolveInfo info2 = mPackageManager.resolveActivity(mEnabledActivityIntent, 0);
        assertNull(info2);

        // Clean up
        mPackageManager.setApplicationEnabledSetting(ENABLED_PACKAGENAME,
                                                     COMPONENT_ENABLED_STATE_DEFAULT,
                                                     0);

    }

    @MediumTest
    public void testNonExplicitResolveAfterEnabling() throws Exception {
        mPackageManager.setComponentEnabledSetting(DISABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_DEFAULT,
                                                   PackageManager.DONT_KILL_APP);

        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(TEST_CATEGORY);

        final List<ResolveInfo> launchables =
                mPackageManager.queryIntentActivities(intent, 0);

        int numItems = launchables.size();
        assertEquals(0, numItems);

        mPackageManager.setComponentEnabledSetting(DISABLED_ACTIVITY_COMPONENTNAME,
                                                   COMPONENT_ENABLED_STATE_ENABLED,
                                                   PackageManager.DONT_KILL_APP);

        final List<ResolveInfo> launchables2 =
                mPackageManager.queryIntentActivities(intent, 0);

        int numItems2 = launchables2.size();
        assertEquals(1, numItems2);
    }
}
