/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.systemui.volume;

import android.media.MediaMetadata;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import junit.framework.Assert;

import org.junit.Test;

@SmallTest
public class UtilTest extends SysuiTestCase {

    @Test
    public void testMediaMetadataToString_null() {
        Assert.assertEquals(null, Util.mediaMetadataToString(null));
    }

    @Test
    public void testMediaMetadataToString_notNull() {
        Assert.assertNotNull(Util.mediaMetadataToString(new MediaMetadata.Builder().build()));
    }
}
