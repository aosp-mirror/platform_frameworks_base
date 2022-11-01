/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.clipboardoverlay;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipDescription;
import android.os.PersistableBundle;
import android.testing.TestableResources;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ClipboardOverlayUtilsTest extends SysuiTestCase {

    private ClipboardOverlayUtils mClipboardUtils;

    @Before
    public void setUp() {
        mClipboardUtils = new ClipboardOverlayUtils();
    }

    @Test
    public void test_extra_withPackage_returnsTrue() {
        PersistableBundle b = new PersistableBundle();
        b.putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true);
        ClipData data = constructClipData(
                new String[]{"text/plain"}, new ClipData.Item("6175550000"), b);
        TestableResources res = mContext.getOrCreateTestableResources();
        res.addOverride(
                R.string.config_remoteCopyPackage, "com.android.remote/.RemoteActivity");

        assertTrue(mClipboardUtils.isRemoteCopy(mContext, data, "com.android.remote"));
    }

    @Test
    public void test_noExtra_returnsFalse() {
        ClipData data = constructClipData(
                new String[]{"text/plain"}, new ClipData.Item("6175550000"), null);
        TestableResources res = mContext.getOrCreateTestableResources();
        res.addOverride(
                R.string.config_remoteCopyPackage, "com.android.remote/.RemoteActivity");

        assertFalse(mClipboardUtils.isRemoteCopy(mContext, data, "com.android.remote"));
    }

    @Test
    public void test_falseExtra_returnsFalse() {
        PersistableBundle b = new PersistableBundle();
        b.putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, false);
        ClipData data = constructClipData(
                new String[]{"text/plain"}, new ClipData.Item("6175550000"), b);
        TestableResources res = mContext.getOrCreateTestableResources();
        res.addOverride(
                R.string.config_remoteCopyPackage, "com.android.remote/.RemoteActivity");

        assertFalse(mClipboardUtils.isRemoteCopy(mContext, data, "com.android.remote"));
    }

    @Test
    public void test_wrongPackage_returnsFalse() {
        PersistableBundle b = new PersistableBundle();
        b.putBoolean(ClipDescription.EXTRA_IS_REMOTE_DEVICE, true);
        ClipData data = constructClipData(
                new String[]{"text/plain"}, new ClipData.Item("6175550000"), b);

        assertFalse(mClipboardUtils.isRemoteCopy(mContext, data, ""));
    }

    static ClipData constructClipData(String[] mimeTypes, ClipData.Item item,
            PersistableBundle extras) {
        ClipDescription description = new ClipDescription("Test", mimeTypes);
        if (extras != null) {
            description.setExtras(extras);
        }
        return new ClipData(description, item);
    }
}
