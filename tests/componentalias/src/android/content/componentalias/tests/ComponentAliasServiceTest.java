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
import static android.content.componentalias.tests.ComponentAliasTestCommon.MAIN_PACKAGE;
import static android.content.componentalias.tests.ComponentAliasTestCommon.SUB1_PACKAGE;
import static android.content.componentalias.tests.ComponentAliasTestCommon.SUB2_PACKAGE;
import static android.content.componentalias.tests.ComponentAliasTestCommon.TAG;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.core.IsNot.not;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.provider.DeviceConfig;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.BroadcastMessenger;
import com.android.compatibility.common.util.BroadcastMessenger.Receiver;
import com.android.compatibility.common.util.DeviceConfigStateHelper;
import com.android.compatibility.common.util.ShellUtils;
import com.android.compatibility.common.util.TestUtils;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.util.function.Consumer;

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

            BroadcastMessenger.send(sContext, TAG, m);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "onServiceDisconnected: " + name);

            ComponentAliasMessage m = new ComponentAliasMessage()
                    .setSenderIdentity("sServiceConnection")
                    .setMethodName("onServiceDisconnected")
                    .setComponent(name);

            BroadcastMessenger.send(sContext, TAG, m);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "onBindingDied: " + name);

            ComponentAliasMessage m = new ComponentAliasMessage()
                    .setSenderIdentity("sServiceConnection")
                    .setMethodName("onBindingDied");

            BroadcastMessenger.send(sContext, TAG, m);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "onNullBinding: " + name);

            ComponentAliasMessage m = new ComponentAliasMessage()
                    .setSenderIdentity("sServiceConnection")
                    .setMethodName("onNullBinding");

            BroadcastMessenger.send(sContext, TAG, m);
        }
    };

    private void testStartAndStopService_common(
            Intent originalIntent,
            ComponentName componentNameForClient,
            ComponentName componentNameForTarget) {

        ComponentAliasMessage m;

        try (Receiver<ComponentAliasMessage> receiver = new Receiver<>(sContext, TAG)) {
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

            receiver.ensureNoMoreMessages();
        }
    }

    private static class Combo {
        public final ComponentName alias;
        public final ComponentName target;
        public final String action;

        private Combo(ComponentName alias, ComponentName target, String action) {
            this.alias = alias;
            this.target = target;
            this.action = action;
        }
    }

    private void forEachCombo(Consumer<Combo> callback) {
        callback.accept(new Combo(
                new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".s.Alias00"),
                new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".s.Target00"),
                MAIN_PACKAGE + ".IS_ALIAS_00"));
        callback.accept(new Combo(
                new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".s.Alias01"),
                new ComponentName(SUB1_PACKAGE, MAIN_PACKAGE + ".s.Target01"),
                MAIN_PACKAGE + ".IS_ALIAS_01"));
        callback.accept(new Combo(
                new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".s.Alias02"),
                new ComponentName(SUB2_PACKAGE, MAIN_PACKAGE + ".s.Target02"),
                MAIN_PACKAGE + ".IS_ALIAS_02"));
    }


    @Test
    public void testStartAndStopService_explicitComponentName() throws Exception {
        forEachCombo((c) -> {
            Intent i = new Intent().setComponent(c.alias);
            testStartAndStopService_common(i, c.alias, c.target);
        });
    }

    @Test
    public void testStartAndStopService_explicitPackageName() throws Exception {
        forEachCombo((c) -> {
            Intent i = new Intent().setPackage(c.alias.getPackageName());
            i.setAction(c.action);

            testStartAndStopService_common(i, c.alias, c.target);
        });
    }

    @Test
    public void testStartAndStopService_override() throws Exception {
        Intent i = new Intent().setPackage(MAIN_PACKAGE);
        i.setAction(MAIN_PACKAGE + ".IS_ALIAS_01");

        // Change some of the aliases from what's defined in <meta-data>.

        ComponentName aliasA = new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".s.Alias01");
        ComponentName targetA = new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".s.Target02");

        ComponentName aliasB = new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".s.Alias02");
        ComponentName targetB = new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".s.Target01");

        sDeviceConfig.set("component_alias_overrides",
                aliasA.flattenToShortString() + ":" + targetA.flattenToShortString()
                + ","
                + aliasB.flattenToShortString() + ":" + targetB.flattenToShortString());

        TestUtils.waitUntil("Wait until component alias is actually enabled", () -> {
            return ShellUtils.runShellCommand("dumpsys activity component-alias")
                    .indexOf(aliasA.flattenToShortString()
                            + " -> " + targetA.flattenToShortString()) > 0;
        });


        testStartAndStopService_common(i, aliasA, targetA);
    }

    private void testBindAndUnbindService_common(
            Intent originalIntent,
            ComponentName componentNameForClient,
            ComponentName componentNameForTarget) {
        ComponentAliasMessage m;

        try (Receiver<ComponentAliasMessage> receiver = new Receiver<>(sContext, TAG)) {
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
            receiver.ensureNoMoreMessages();
        }
    }

    @Test
    public void testBindService_explicitComponentName() throws Exception {
        forEachCombo((c) -> {
            Intent i = new Intent().setComponent(c.alias);

            testBindAndUnbindService_common(i, c.alias, c.target);
        });

    }

    @Test
    public void testBindService_explicitPackageName() throws Exception {
        forEachCombo((c) -> {
            Intent i = new Intent().setPackage(c.alias.getPackageName());
            i.setAction(c.action);

            testBindAndUnbindService_common(i, c.alias, c.target);
        });
    }

    /**
     * Make sure, when the service process is killed, the client will get a callback with the
     * right component name.
     */
    @Test
    public void testBindService_serviceKilled() throws Exception {

        // We need to kill SUB2_PACKAGE, don't run it for this package.
        Assume.assumeThat(sContext.getPackageName(), not(SUB2_PACKAGE));

        Intent originalIntent = new Intent().setPackage(MAIN_PACKAGE);
        originalIntent.setAction(MAIN_PACKAGE + ".IS_ALIAS_02");

        final ComponentName componentNameForClient =
                new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".s.Alias02");
        final ComponentName componentNameForTarget =
                new ComponentName(SUB2_PACKAGE, MAIN_PACKAGE + ".s.Target02");

        ComponentAliasMessage m;

        try (Receiver<ComponentAliasMessage> receiver = new Receiver<>(sContext, TAG)) {
            // Bind to the service.
            assertThat(sContext.bindService(
                    originalIntent, sServiceConnection, BIND_AUTO_CREATE)).isTrue();

            // Check the target side behavior.
            m = receiver.waitForNextMessage();

            assertThat(m.getMethodName()).isEqualTo("onBind");

            m = receiver.waitForNextMessage();
            assertThat(m.getMethodName()).isEqualTo("onServiceConnected");
            assertThat(m.getComponent()).isEqualTo(componentNameForClient);
            // We don't need to check all the fields because these are tested else where.

            // Now kill the service process.
            ShellUtils.runShellCommand("su 0 killall %s", SUB2_PACKAGE);

            // Check the target side behavior.
            m = receiver.waitForNextMessage();

            assertThat(m.getMethodName()).isEqualTo("onServiceDisconnected");
            assertThat(m.getComponent()).isEqualTo(componentNameForClient);

            receiver.ensureNoMoreMessages();
        }
    }
}
