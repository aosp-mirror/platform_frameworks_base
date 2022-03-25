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

import static android.content.componentalias.tests.ComponentAliasTestCommon.MAIN_PACKAGE;
import static android.content.componentalias.tests.ComponentAliasTestCommon.SUB1_PACKAGE;
import static android.content.componentalias.tests.ComponentAliasTestCommon.SUB2_PACKAGE;
import static android.content.componentalias.tests.ComponentAliasTestCommon.TAG;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;
import android.content.Intent;

import com.android.compatibility.common.util.BroadcastMessenger.Receiver;

import org.junit.Test;

import java.util.function.Consumer;

public class ComponentAliasBroadcastTest extends BaseComponentAliasTest {
    private void forEachCombo(Consumer<Combo> callback) {
        new Combo(
                new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".b.Alias00"),
                new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".b.Target00"),
                MAIN_PACKAGE + ".IS_RECEIVER_00").apply(callback);

        new Combo(
                new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".b.Alias01"),
                new ComponentName(SUB1_PACKAGE, MAIN_PACKAGE + ".b.Target01"),
                MAIN_PACKAGE + ".IS_RECEIVER_01").apply(callback);
        new Combo(
                new ComponentName(MAIN_PACKAGE, MAIN_PACKAGE + ".b.Alias02"),
                new ComponentName(SUB2_PACKAGE, MAIN_PACKAGE + ".b.Target02"),
                MAIN_PACKAGE + ".IS_RECEIVER_02").apply(callback);
    }

    @Test
    public void testBroadcast_explicitComponentName() {
        forEachCombo((c) -> {
            Intent i = new Intent().setComponent(c.alias);
            i.setAction("ACTION_BROADCAST");
            ComponentAliasMessage m;

            try (Receiver<ComponentAliasMessage> receiver = new Receiver<>(sContext, TAG)) {
                log("Sending: " + i);
                sContext.sendBroadcast(i);

                m = receiver.waitForNextMessage();

                assertThat(m.getMethodName()).isEqualTo("onReceive");
                assertThat(m.getSenderIdentity()).isEqualTo(c.target.flattenToShortString());

                // The broadcast intent will always have the receiving component name set.
                assertThat(m.getIntent().getComponent()).isEqualTo(c.target);

                receiver.ensureNoMoreMessages();
            }
        });
    }

    @Test
    public void testBroadcast_explicitPackageName() {
        forEachCombo((c) -> {
            // In this test, we only set the package name to the intent.
            // If the alias and target are the same package, the intent will be sent to both of them
            // *and* the one to the alias is redirected to the target, so the target will receive
            // the intent twice. This case is haled at *1 below.


            Intent i = new Intent().setPackage(c.alias.getPackageName());
            i.setAction(c.action);
            ComponentAliasMessage m;

            try (Receiver<ComponentAliasMessage> receiver = new Receiver<>(sContext, TAG)) {
                log("Sending broadcast: " + i);
                sContext.sendBroadcast(i);

                m = receiver.waitForNextMessage();

                assertThat(m.getMethodName()).isEqualTo("onReceive");
                assertThat(m.getSenderIdentity()).isEqualTo(c.target.flattenToShortString());
                assertThat(m.getIntent().getComponent()).isEqualTo(c.target);

                // *1 -- if the alias and target are in the same package, we expect one more
                // message.
                if (c.alias.getPackageName().equals(c.target.getPackageName())) {
                    m = receiver.waitForNextMessage();
                    assertThat(m.getMethodName()).isEqualTo("onReceive");
                    assertThat(m.getSenderIdentity()).isEqualTo(c.target.flattenToShortString());
                    assertThat(m.getIntent().getComponent()).isEqualTo(c.target);
                }
                receiver.ensureNoMoreMessages();
            }
        });
    }
}
