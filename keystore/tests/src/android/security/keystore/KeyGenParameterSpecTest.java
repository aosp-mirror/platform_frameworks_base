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

package android.security.keystore;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.ParcelableKeyGenParameterSpecTest;
import android.support.test.runner.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link KeyGenParameterSpec}. */
@RunWith(AndroidJUnit4.class)
public final class KeyGenParameterSpecTest {
    static final String ALIAS = "keystore-alias";
    static final int KEY_PURPOSES = KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY;

    @Test
    public void testBuilderCopyingValues() {
        KeyGenParameterSpec spec = ParcelableKeyGenParameterSpecTest.configureDefaultSpec();
        KeyGenParameterSpec copiedSpec =
                new KeyGenParameterSpec.Builder(spec).build();
        ParcelableKeyGenParameterSpecTest.validateSpecValues(
                copiedSpec, spec.getUid(), spec.getKeystoreAlias());
    }

    @Test
    public void testBuilderCopyingEmptyValues() {
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS, KEY_PURPOSES).build();
        KeyGenParameterSpec copiedSpec = new KeyGenParameterSpec.Builder(spec).build();

        assertThat(copiedSpec.getKeystoreAlias(), is(ALIAS));
        assertThat(copiedSpec.getPurposes(), is(KEY_PURPOSES));
    }

    @Test
    public void testCanModifyValuesInCopiedBuilder() {
        KeyGenParameterSpec spec = ParcelableKeyGenParameterSpecTest.configureDefaultSpec();
        KeyGenParameterSpec copiedSpec =
                new KeyGenParameterSpec.Builder(spec)
                .setAttestationChallenge(null)
                .build();

        assertEquals(copiedSpec.getAttestationChallenge(), null);
    }
}
