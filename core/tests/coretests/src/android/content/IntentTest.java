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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
        // Not an intent, don't count.
        intent.putExtra(TEST_EXTRA_NAME + "2", 1);
        ArrayList<Intent> intents = new ArrayList<>();
        intents.add(new Intent(TEST_ACTION));
        intent.putParcelableArrayListExtra(TEST_EXTRA_NAME + "3", intents);
        intent.setClipData(ClipData.newIntent("label", new Intent(TEST_ACTION)));

        intent.collectExtraIntentKeys();
        final Parcel parcel = Parcel.obtain();
        intent.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        final Intent target = new Intent();
        target.readFromParcel(parcel);

        assertEquals(intent.getAction(), target.getAction());
        assertEquals(intent.getExtraIntentKeys(), target.getExtraIntentKeys());
        assertThat(intent.getExtraIntentKeys()).hasSize(3);
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

        Intent[] intents = new Intent[10];
        for (int i = 0; i < intents.length; i++) {
            intents[i] = new Intent("action" + i);
        }
        Intent[] intents2 = new Intent[2]; // intents[6-7]
        System.arraycopy(intents, 6, intents2, 0, intents2.length);
        ArrayList<Intent> intents3 = new ArrayList<>(2);
        intents3.addAll(Arrays.asList(intents).subList(8, 10)); // intents[8-9]
        intent.putExtra("key1", intents[0]);
        intent.putExtra("array-key", intents2);
        intent.setClipData(ClipData.newIntent("label2", intents[1]));
        intent.putExtra("intkey", 1);
        intents[0].putExtra("key3", intents[2]);
        intents[0].setClipData(ClipData.newIntent("label4", intents[3]));
        intents[0].putParcelableArrayListExtra("array-list-key", intents3);
        intents[1].putExtra("key3", intents[4]);
        intents[1].setClipData(ClipData.newIntent("label4", intents[5]));
        intents[5].putExtra("intkey", 2);

        intent.collectExtraIntentKeys();

        // collect all actions of nested intents.
        final List<String> actions = new ArrayList<>();
        intent.forEachNestedCreatorToken(intent1 -> {
            actions.add(intent1.getAction());
        });
        assertThat(actions).hasSize(10);
        for (int i = 0; i < intents.length; i++) {
            assertThat(actions).contains("action" + i);
        }
    }

}
