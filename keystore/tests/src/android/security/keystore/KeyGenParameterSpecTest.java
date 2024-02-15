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

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertThrows;

import android.security.ParcelableKeyGenParameterSpecTest;

import androidx.test.runner.AndroidJUnit4;

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
                copiedSpec, spec.getNamespace(), spec.getKeystoreAlias());
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

    @Test
    public void testMgf1DigestsNotSpecifiedByDefault() {
        KeyGenParameterSpec spec = ParcelableKeyGenParameterSpecTest.configureDefaultSpec();
        assertThat(spec.isMgf1DigestsSpecified(), is(false));
        assertThrows(IllegalStateException.class, () -> {
            spec.getMgf1Digests();
        });
    }

    @Test
    public void testMgf1DigestsCanBeSpecified() {
        String[] mgf1Digests =
                new String[] {KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256};
        KeyGenParameterSpec spec =  new KeyGenParameterSpec.Builder(ALIAS, KEY_PURPOSES)
                .setMgf1Digests(mgf1Digests)
                .build();
        assertThat(spec.isMgf1DigestsSpecified(), is(true));
        assertThat(spec.getMgf1Digests(), containsInAnyOrder(mgf1Digests));

        KeyGenParameterSpec copiedSpec = new KeyGenParameterSpec.Builder(spec).build();
        assertThat(copiedSpec.isMgf1DigestsSpecified(), is(true));
        assertThat(copiedSpec.getMgf1Digests(), containsInAnyOrder(mgf1Digests));
    }

    @Test
    public void testMgf1DigestsAreNotModified() {
        String[] mgf1Digests =
                new String[] {KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256};
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(ALIAS, KEY_PURPOSES)
                .setMgf1Digests(mgf1Digests);

        KeyGenParameterSpec firstSpec =  builder.build();
        assertArrayEquals(mgf1Digests, firstSpec.getMgf1Digests().toArray());

        String[] otherDigests = new String[] {KeyProperties.DIGEST_SHA224};
        KeyGenParameterSpec secondSpec =  builder.setMgf1Digests(otherDigests).build();
        assertThat(secondSpec.getMgf1Digests(), containsInAnyOrder(otherDigests));

        // Now check that the first spec created hasn't changed.
        assertThat(firstSpec.getMgf1Digests(), containsInAnyOrder(mgf1Digests));
    }

    @Test
    public void testEmptyMgf1DigestsCanBeSet() {
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS, KEY_PURPOSES)
                .setMgf1Digests(new String[] {}).build();

        assertThat(spec.isMgf1DigestsSpecified(), is(false));
    }
}
