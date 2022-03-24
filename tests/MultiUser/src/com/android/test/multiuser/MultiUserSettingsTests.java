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

package com.android.test.multiuser;

import static android.provider.Settings.Secure.FONT_WEIGHT_ADJUSTMENT;
import static android.provider.Settings.System.FONT_SCALE;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;

import android.content.ContentResolver;
import android.content.Context;
import android.os.UserManager;
import android.provider.Settings;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class MultiUserSettingsTests {
    private final Context mContext = getInstrumentation().getTargetContext();
    private final ContentResolver mContentResolver = mContext.getContentResolver();

    private static void waitForBroadcastIdle() throws InterruptedException {
        final int sleepDuration = 1000;
        final String cmdAmWaitForBroadcastIdle = "am wait-for-broadcast-idle";

        Thread.sleep(sleepDuration);
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .executeShellCommand(cmdAmWaitForBroadcastIdle);
        Thread.sleep(sleepDuration);
    }

    private float getGlobalFontScale() {
        return mContext.getResources().getConfiguration().fontScale;
    }

    private int getGlobalFontWeight() {
        return mContext.getResources().getConfiguration().fontWeightAdjustment;
    }

    private float getFontScaleOfUser(int userId) {
        return Settings.System.getFloatForUser(mContentResolver, FONT_SCALE, 1, userId);
    }

    private int getFontWeightOfUser(int userId) {
        return Settings.Secure.getIntForUser(mContentResolver, FONT_WEIGHT_ADJUSTMENT, 1, userId);
    }

    private void setFontScaleOfUser(float fontScale, int userId) throws InterruptedException {
        Settings.System.putFloatForUser(mContentResolver, FONT_SCALE, fontScale, userId);
        waitForBroadcastIdle();
    }

    private void setFontWeightOfUser(int fontWeight, int userId) throws InterruptedException {
        Settings.Secure.putIntForUser(mContentResolver, FONT_WEIGHT_ADJUSTMENT, fontWeight, userId);
        waitForBroadcastIdle();
    }

    @Test
    public void testChangingFontScaleOfABackgroundUser_shouldNotAffectUI()
            throws InterruptedException {

        Assume.assumeTrue(UserManager.supportsMultipleUsers());

        UserManager userManager = UserManager.get(mContext);

        final int backgroundUserId = userManager.createUser("test_user",
                UserManager.USER_TYPE_FULL_SECONDARY, 0).id;
        final float oldFontScaleOfBgUser = getFontScaleOfUser(backgroundUserId);
        final float oldGlobalFontScale = getGlobalFontScale();
        final float newFontScaleOfBgUser = 1 + Math.max(oldGlobalFontScale, oldFontScaleOfBgUser);

        try {
            setFontScaleOfUser(newFontScaleOfBgUser, backgroundUserId);
            final float newGlobalFontScale = getGlobalFontScale();
            assertEquals(oldGlobalFontScale, newGlobalFontScale, 0);
        } finally {
            setFontScaleOfUser(oldFontScaleOfBgUser, backgroundUserId);
            userManager.removeUser(backgroundUserId);
        }
    }

    @Test
    public void testChangingFontWeightOfABackgroundUser_shouldNotAffectUI()
            throws InterruptedException {

        Assume.assumeTrue(UserManager.supportsMultipleUsers());

        UserManager userManager = UserManager.get(mContext);

        final int backgroundUserId = userManager.createUser("test_user",
                UserManager.USER_TYPE_FULL_SECONDARY, 0).id;
        final int oldFontWeightOfBgUser = getFontWeightOfUser(backgroundUserId);
        final int oldGlobalFontWeight = getGlobalFontWeight();
        final int newFontWeightOfBgUser = 2 * Math.max(oldGlobalFontWeight, oldFontWeightOfBgUser);

        try {
            setFontWeightOfUser(newFontWeightOfBgUser, backgroundUserId);
            final int newGlobalFontWeight = getGlobalFontWeight();
            assertEquals(oldGlobalFontWeight, newGlobalFontWeight);
        } finally {
            setFontWeightOfUser(oldFontWeightOfBgUser, backgroundUserId);
            userManager.removeUser(backgroundUserId);
        }
    }

    @Test
    public void testChangingFontScaleOfTheForegroundUser_shouldAffectUI()
            throws InterruptedException {

        Assume.assumeTrue(UserManager.supportsMultipleUsers());

        final int currentUserId = mContext.getUserId();
        final float oldFontScale = getFontScaleOfUser(currentUserId);
        final float newFontScale = 1 + oldFontScale;

        try {
            setFontScaleOfUser(newFontScale, currentUserId);
            final float globalFontScale = getGlobalFontScale();
            assertEquals(newFontScale, globalFontScale, 0);
        } finally {
            setFontScaleOfUser(oldFontScale, currentUserId);
        }
    }

    @Test
    public void testChangingFontWeightOfTheForegroundUser_shouldAffectUI()
            throws InterruptedException {

        Assume.assumeTrue(UserManager.supportsMultipleUsers());

        final int currentUserId = mContext.getUserId();
        final int oldFontWeight = getFontWeightOfUser(currentUserId);
        final int newFontWeight = 2 * oldFontWeight;

        try {
            setFontWeightOfUser(newFontWeight, currentUserId);
            final int globalFontWeight = getGlobalFontWeight();
            assertEquals(newFontWeight, globalFontWeight);
        } finally {
            setFontWeightOfUser(oldFontWeight, currentUserId);
        }
    }
}
