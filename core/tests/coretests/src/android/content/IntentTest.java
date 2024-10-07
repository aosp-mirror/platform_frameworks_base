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

package android.content;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.security.Flags;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 *  Build/Install/Run:
 *   atest FrameworksCoreTests:IntentTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class IntentTest {
    private static final String TEST_ACTION = "android.content.IntentTest_test";
    private static final String TEST_EXTRA_NAME = "testExtraName";
    private static final Uri TEST_URI = Uri.parse("content://com.example/people");

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_PREVENT_INTENT_REDIRECT)
    public void testReadFromParcelWithExtraIntentKeys() {
        Intent intent = new Intent("TEST_ACTION");
        intent.putExtra(TEST_EXTRA_NAME, new Intent(TEST_ACTION));
        intent.putExtra(TEST_EXTRA_NAME + "2", 1);

        intent.collectExtraIntentKeys();
        final Parcel parcel = Parcel.obtain();
        intent.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        final Intent target = new Intent();
        target.readFromParcel(parcel);

        assertEquals(intent.getAction(), target.getAction());
        assertEquals(intent.getExtraIntentKeys(), target.getExtraIntentKeys());
        assertThat(intent.getExtraIntentKeys()).hasSize(1);
    }

    @Test
    public void testCreatorTokenInfo() {
        Intent intent = new Intent(TEST_ACTION);
        IBinder creatorToken = new Binder();

        intent.setCreatorToken(creatorToken);
        assertThat(intent.getCreatorToken()).isEqualTo(creatorToken);

        intent.removeCreatorTokenInfo();
        assertThat(intent.getCreatorToken()).isNull();
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_PREVENT_INTENT_REDIRECT)
    public void testCollectExtraIntentKeys() {
        Intent intent = new Intent(TEST_ACTION);
        Intent extraIntent = new Intent(TEST_ACTION, TEST_URI);
        intent.putExtra(TEST_EXTRA_NAME, extraIntent);

        intent.collectExtraIntentKeys();

        assertThat(intent.getExtraIntentKeys()).hasSize(1);
        assertThat(intent.getExtraIntentKeys()).contains(TEST_EXTRA_NAME);
    }

}
