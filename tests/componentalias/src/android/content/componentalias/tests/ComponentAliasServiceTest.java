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

package android.content.componentalias.tests;

import static android.content.Context.BIND_AUTO_CREATE;
import static android.content.componentalias.tests.common.ComponentAliasTestCommon.APP_PACKAGE;
import static android.content.componentalias.tests.common.ComponentAliasTestCommon.SUB1_PACKAGE;
import static android.content.componentalias.tests.common.ComponentAliasTestCommon.SUB2_PACKAGE;
import static android.content.componentalias.tests.common.ComponentAliasTestCommon.TAG;
import static android.content.componentalias.tests.common.ComponentAliasTestCommon.TEST_PACKAGE;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.componentalias.tests.common.ComponentAliasMessage;
import android.os.IBinder;
import android.provider.DeviceConfig;
import android.util.Log;

import com.android.compatibility.common.util.BroadcastMessenger;
import com.android.compatibility.common.util.BroadcastMessenger.Receiver;
import com.android.compatibility.common.util.DeviceConfigStateHelper;
import com.android.compatibility.common.util.ShellUtils;
import com.android.compatibility.common.util.TestUtils;

import androidx.test.InstrumentationRegistry;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

/**
 * Test for the experimental "Component alias" feature.
 *
 * Note this test exercises the relevant APIs, but don't actually check if the aliases are
 * resolved.
 *
 * Note all the helper APKs are battery-exempted (via AndroidTest.xml), so they can run
 * BG services.
 */
public class ComponentAliasServiceTest {

    private static final Context sContext = InstrumentationRegistry.getTargetContext();

    private static final DeviceConfigStateHelper sDeviceConfig = new DeviceConfigStateHelper(
            DeviceConfig.NAMESPACE_ACTIVITY_MANAGER);
    @Before
    public void enableComponentAlias() throws Exception {
        sDeviceConfig.set("component_alias_overrides", "");
        sDeviceConfig.set("enable_experimental_component_alias", "true");

        // Device config propagation happens on a handler, so we need to wait for AM to
        // actually set it.
        TestUtils.waitUntil("Wait until component alias is actually enabled", () -> {
            return ShellUtils.runShellCommand("dumpsys activity component-alias")
                    .indexOf("Enabled: true") > 0;
        });
    }

    @AfterClass
    public static void restoreDeviceConfig() throws Exception {
        sDeviceConfig.close();
    }

    /**
     * Service connection used throughout the tests. It sends a message for each callback via
     * the messenger.
     */
    private static final ServiceConnection sServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.w(TAG, "onServiceConnected: " + name);

            ComponentAliasMessage m = new ComponentAliasMessage()
                    .setSenderIdentity("sServiceConnection")
                    .setMethodName("onServiceConnected")
                    .setComponent(name);

            BroadcastMessenger.send(sContext, TEST_PACKAGE, m);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "onServiceDisconnected: " + name);

            ComponentAliasMessage m = new ComponentAliasMessage()
                    .setSenderIdentity("sServiceConnection")
                    .setMethodName("onServiceDisconnected")
                    .setComponent(name);

            BroadcastMessenger.send(sContext, TEST_PACKAGE, m);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "onBindingDied: " + name);

            ComponentAliasMessage m = new ComponentAliasMessage()
                    .setSenderIdentity("sServiceConnection")
                    .setMethodName("onBindingDied");

            BroadcastMessenger.send(sContext, TEST_PACKAGE, m);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "onNullBinding: " + name);

            ComponentAliasMessage m = new ComponentAliasMessage()
                    .setSenderIdentity("sServiceConnection")
                    .setMethodName("onNullBinding");

            BroadcastMessenger.send(sContext, TEST_PACKAGE, m);
        }
    };

    private void testStartAndStopService_common(
            Intent originalIntent,
            ComponentName componentNameForClient,
            ComponentName componentNameForTarget) throws Exception {

        ComponentAliasMessage m;

        try (Receiver<ComponentAliasMessage> receiver = new Receiver<>(sContext)) {
            // Start the service.
            ComponentName result = sContext.startService(originalIntent);
            assertThat(result).isEqualTo(componentNameForClient);

            // Check
            m = receiver.waitForNextMessage();

            assertThat(m.getMethodName()).isEqualTo("onStartCommand");
            // The app sees the rewritten intent.
            assertThat(m.getIntent().getComponent()).isEqualTo(componentNameForTarget);

            // Verify the original intent.
            assertThat(m.getIntent().getOriginalIntent().getComponent())
                    .isEqualTo(originalIntent.getComponent());
            assertThat(m.getIntent().getOriginalIntent().getPackage())
                    .isEqualTo(originalIntent.getPackage());

            // Stop the service.
            sContext.stopService(originalIntent);

            // Check
            m = receiver.waitForNextMessage();

            assertThat(m.getMethodName()).isEqualTo("onDestroy");
        }
    }

    @Test
    public void testStartAndStopService_explicitComponentName() throws Exception {
        Intent i = new Intent().setComponent(
                new ComponentName(APP_PACKAGE, APP_PACKAGE + ".s.Alias01"));

        ComponentName alias = new ComponentName(APP_PACKAGE, APP_PACKAGE + ".s.Alias01");
        ComponentName target = new ComponentName(SUB1_PACKAGE, APP_PACKAGE + ".s.Target01");

        testStartAndStopService_common(i, alias, target);
    }

    @Test
    public void testStartAndStopService_explicitPackageName() throws Exception {
        Intent i = new Intent().setPackage(APP_PACKAGE);
        i.setAction(APP_PACKAGE + ".IS_ALIAS_02");

        ComponentName alias = new ComponentName(APP_PACKAGE, APP_PACKAGE + ".s.Alias02");
        ComponentName target = new ComponentName(SUB2_PACKAGE, APP_PACKAGE + ".s.Target02");

        testStartAndStopService_common(i, alias, target);
    }

    @Test
    public void testStartAndStopService_override() throws Exception {
        Intent i = new Intent().setPackage(APP_PACKAGE);
        i.setAction(APP_PACKAGE + ".IS_ALIAS_02");

        ComponentName alias = new ComponentName(APP_PACKAGE, APP_PACKAGE + ".s.Alias02");

        // Note, alias02 originally points at sub*2* package, but we override it in this test.
        ComponentName target = new ComponentName(SUB1_PACKAGE, APP_PACKAGE + ".s.Target02");

        sDeviceConfig.set("component_alias_overrides",
                alias.flattenToShortString() + ":" + target.flattenToShortString());

        TestUtils.waitUntil("Wait until component alias is actually enabled", () -> {
            return ShellUtils.runShellCommand("dumpsys activity component-alias")
                    .indexOf(alias.flattenToShortString() + " -> " + target.flattenToShortString())
                    > 0;
        });


        testStartAndStopService_common(i, alias, target);
    }

    private void testBindAndUnbindService_common(
            Intent originalIntent,
            ComponentName componentNameForClient,
            ComponentName componentNameForTarget) throws Exception {
        ComponentAliasMessage m;

        try (Receiver<ComponentAliasMessage> receiver = new Receiver<>(sContext)) {
            // Bind to the service.
            assertThat(sContext.bindService(
                    originalIntent, sServiceConnection, BIND_AUTO_CREATE)).isTrue();

            // Check the target side behavior.
            m = receiver.waitForNextMessage();

            assertThat(m.getMethodName()).isEqualTo("onBind");
            // The app sees the rewritten intent.
            assertThat(m.getIntent().getComponent()).isEqualTo(componentNameForTarget);

            // Verify the original intent.
            assertThat(m.getIntent().getOriginalIntent().getComponent())
                    .isEqualTo(originalIntent.getComponent());
            assertThat(m.getIntent().getOriginalIntent().getPackage())
                    .isEqualTo(originalIntent.getPackage());

            // Check the client side behavior.
            m = receiver.waitForNextMessage();

            assertThat(m.getMethodName()).isEqualTo("onServiceConnected");
            // The app sees the rewritten intent.
            assertThat(m.getComponent()).isEqualTo(componentNameForClient);

            // Unbind.
            sContext.unbindService(sServiceConnection);

            // Check the target side behavior.
            m = receiver.waitForNextMessage();

            assertThat(m.getMethodName()).isEqualTo("onDestroy");

            // Note onServiceDisconnected() won't be called in this case.
        }
    }

    @Test
    public void testBindService_explicitComponentName() throws Exception {
        Intent i = new Intent().setComponent(
                new ComponentName(APP_PACKAGE, APP_PACKAGE + ".s.Alias01"));

        testBindAndUnbindService_common(i,
                new ComponentName(APP_PACKAGE, APP_PACKAGE + ".s.Alias01"),
                new ComponentName(SUB1_PACKAGE, APP_PACKAGE + ".s.Target01"));
    }

    @Test
    public void testBindService_explicitPackageName() throws Exception {
        Intent i = new Intent().setPackage(APP_PACKAGE);
        i.setAction(APP_PACKAGE + ".IS_ALIAS_02");

        testBindAndUnbindService_common(i,
                new ComponentName(APP_PACKAGE, APP_PACKAGE + ".s.Alias02"),
                new ComponentName(SUB2_PACKAGE, APP_PACKAGE + ".s.Target02"));
    }
}
