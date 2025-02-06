/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.test.pagesizecompat;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class PageSizeCompatTest {
    private static final String WARNING_TEXT = "PageSizeCompatTestApp";
    private static final long TIMEOUT = 5000;

    public void testPageSizeCompat_appLaunch(boolean shouldPass) throws Exception {
        Context context = InstrumentationRegistry.getContext();
        CountDownLatch receivedSignal = new CountDownLatch(1);

        // Test app is expected to receive this and perform addition of operands using ELF
        // loaded in compat mode on 16 KB device
        int op1 = 48;
        int op2 = 75;
        IntentFilter intentFilter = new IntentFilter(MainActivity.INTENT_TYPE);
        BroadcastReceiver broadcastReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        receivedSignal.countDown();
                        int result = intent.getIntExtra(MainActivity.KEY_RESULT, 1000);
                        Assert.assertEquals(result, op1 + op2);

                    }
                };
        context.registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED);

        Intent launchIntent =
                context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        launchIntent.putExtra(MainActivity.KEY_OPERAND_1, op1);
        launchIntent.putExtra(MainActivity.KEY_OPERAND_2, op2);
        context.startActivity(launchIntent);

        UiDevice device = UiDevice.getInstance(getInstrumentation());
        device.waitForWindowUpdate(null, TIMEOUT);

        Assert.assertEquals(receivedSignal.await(10, TimeUnit.SECONDS), shouldPass);
    }

    @Test
    public void testPageSizeCompat_compatEnabled() throws Exception {
        testPageSizeCompat_appLaunch(true);
    }

    @Test
    public void testPageSizeCompat_compatDisabled() throws Exception {
        testPageSizeCompat_appLaunch(false);
    }

    @Test
    public void testPageSizeCompat_compatByAlignmentChecks() throws Exception {
        testPageSizeCompat_appLaunch(true);

        //verify warning dialog
        UiDevice device = UiDevice.getInstance(getInstrumentation());
        device.waitForWindowUpdate(null, TIMEOUT);
        UiObject2 targetObject = device.wait(Until.findObject(By.text(WARNING_TEXT)), TIMEOUT);
        Assert.assertTrue(targetObject != null);
    }
}
