/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_MORE_DETAILS;
import static android.content.pm.SuspendDialogInfo.BUTTON_ACTION_UNSUSPEND;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import android.content.pm.SuspendDialogInfo;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class SuspendDialogInfoTest {
    private static final int VALID_TEST_RES_ID_1 = 0x11110001;
    private static final int VALID_TEST_RES_ID_2 = 0x11110002;

    private static SuspendDialogInfo.Builder createDefaultDialogBuilder() {
        return new SuspendDialogInfo.Builder()
                .setIcon(VALID_TEST_RES_ID_1)
                .setTitle(VALID_TEST_RES_ID_1)
                .setMessage(VALID_TEST_RES_ID_1)
                .setNeutralButtonText(VALID_TEST_RES_ID_1)
                .setNeutralButtonAction(BUTTON_ACTION_MORE_DETAILS);
    }

    @Test
    public void equalsComparesIcons() {
        final SuspendDialogInfo.Builder dialogBuilder1 = createDefaultDialogBuilder();
        final SuspendDialogInfo.Builder dialogBuilder2 = createDefaultDialogBuilder();
        assertEquals(dialogBuilder1.build(), dialogBuilder2.build());
        // Only icon is different
        dialogBuilder2.setIcon(VALID_TEST_RES_ID_2);
        assertNotEquals(dialogBuilder1.build(), dialogBuilder2.build());
    }

    @Test
    public void equalsComparesTitle() {
        final SuspendDialogInfo.Builder dialogBuilder1 = createDefaultDialogBuilder();
        final SuspendDialogInfo.Builder dialogBuilder2 = createDefaultDialogBuilder();
        assertEquals(dialogBuilder1.build(), dialogBuilder2.build());
        // Only title is different
        dialogBuilder2.setTitle(VALID_TEST_RES_ID_2);
        assertNotEquals(dialogBuilder1.build(), dialogBuilder2.build());
    }

    @Test
    public void equalsComparesButtonText() {
        final SuspendDialogInfo.Builder dialogBuilder1 = createDefaultDialogBuilder();
        final SuspendDialogInfo.Builder dialogBuilder2 = createDefaultDialogBuilder();
        assertEquals(dialogBuilder1.build(), dialogBuilder2.build());
        // Only button text is different
        dialogBuilder2.setNeutralButtonText(VALID_TEST_RES_ID_2);
        assertNotEquals(dialogBuilder1.build(), dialogBuilder2.build());
    }

    @Test
    public void equalsComparesButtonAction() {
        final SuspendDialogInfo.Builder dialogBuilder1 = createDefaultDialogBuilder();
        final SuspendDialogInfo.Builder dialogBuilder2 = createDefaultDialogBuilder();
        assertEquals(dialogBuilder1.build(), dialogBuilder2.build());
        // Only button action is different
        dialogBuilder2.setNeutralButtonAction(BUTTON_ACTION_UNSUSPEND);
        assertNotEquals(dialogBuilder1.build(), dialogBuilder2.build());
    }

    @Test
    public void defaultButtonAction() {
        final SuspendDialogInfo.Builder dialogBuilder = new SuspendDialogInfo.Builder()
                .setIcon(VALID_TEST_RES_ID_1)
                .setTitle(VALID_TEST_RES_ID_1)
                .setMessage(VALID_TEST_RES_ID_1);
        assertEquals(BUTTON_ACTION_MORE_DETAILS, dialogBuilder.build().getNeutralButtonAction());
    }

    @Test
    public void equalsComparesMessageIds() {
        final SuspendDialogInfo.Builder dialogBuilder1 = createDefaultDialogBuilder();
        final SuspendDialogInfo.Builder dialogBuilder2 = createDefaultDialogBuilder();
        assertEquals(dialogBuilder1.build(), dialogBuilder2.build());
        // Only message is different
        dialogBuilder2.setMessage(VALID_TEST_RES_ID_2);
        assertNotEquals(dialogBuilder1.build(), dialogBuilder2.build());
    }

    @Test
    public void equalsIgnoresMessageStringsWhenIdsSet() {
        final SuspendDialogInfo.Builder dialogBuilder1 = new SuspendDialogInfo.Builder()
                .setMessage(VALID_TEST_RES_ID_1)
                .setMessage("1st message");
        final SuspendDialogInfo.Builder dialogBuilder2 = new SuspendDialogInfo.Builder()
                .setMessage(VALID_TEST_RES_ID_1)
                .setMessage("2nd message");
        // String messages different but should get be ignored when resource ids are set
        assertEquals(dialogBuilder1.build(), dialogBuilder2.build());
    }

    @Test
    public void equalsComparesMessageStringsWhenNoIdsSet() {
        final SuspendDialogInfo.Builder dialogBuilder1 = new SuspendDialogInfo.Builder()
                .setMessage("1st message");
        final SuspendDialogInfo.Builder dialogBuilder2 = new SuspendDialogInfo.Builder()
                .setMessage("2nd message");
        // Both have different messages, which are not ignored as resource ids aren't set
        assertNotEquals(dialogBuilder1.build(), dialogBuilder2.build());
    }

    @Test
    public void messageStringClearedWhenResIdSet() {
        final SuspendDialogInfo dialogInfo = new SuspendDialogInfo.Builder()
                .setMessage(VALID_TEST_RES_ID_2)
                .setMessage("Should be cleared on build")
                .build();
        assertNull(dialogInfo.getDialogMessage());
        assertEquals(VALID_TEST_RES_ID_2, dialogInfo.getDialogMessageResId());
    }
}
