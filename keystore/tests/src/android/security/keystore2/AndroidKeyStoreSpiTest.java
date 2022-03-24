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

package android.security.keystore2;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.security.KeyStore2;
import android.security.KeyStoreException;
import android.security.KeyStoreSecurityLevel;
import android.system.keystore2.Authorization;
import android.system.keystore2.Domain;
import android.system.keystore2.KeyDescriptor;
import android.system.keystore2.KeyMetadata;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class AndroidKeyStoreSpiTest {

    @Mock
    private KeyStore2 mKeystore2;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testEngineAliasesReturnsEmptySetOnKeyStoreError() throws Exception {
        when(mKeystore2.list(anyInt(), anyLong()))
                .thenThrow(new KeyStoreException(6, "Some Error"));
        AndroidKeyStoreSpi spi = new AndroidKeyStoreSpi();
        spi.initForTesting(mKeystore2);

        assertThat("Empty collection expected", !spi.engineAliases().hasMoreElements());

        verify(mKeystore2).list(anyInt(), anyLong());
    }

    @Mock
    private KeyStoreSecurityLevel mKeystoreSecurityLevel;

    private static KeyDescriptor descriptor() {
        final KeyDescriptor keyDescriptor = new KeyDescriptor();
        keyDescriptor.alias = "key";
        keyDescriptor.blob = null;
        keyDescriptor.domain = Domain.APP;
        keyDescriptor.nspace = -1;
        return keyDescriptor;
    }

    private static KeyMetadata metadata(byte[] cert, byte[] certChain) {
        KeyMetadata metadata = new KeyMetadata();
        metadata.authorizations = new Authorization[0];
        metadata.certificate = cert;
        metadata.certificateChain = certChain;
        metadata.key = descriptor();
        metadata.modificationTimeMs = 0;
        metadata.keySecurityLevel = 1;
        return metadata;
    }

    private static byte[] bytes(String string) {
        return string.getBytes();
    }

    class MyPublicKey extends AndroidKeyStorePublicKey {
        MyPublicKey(String cert, String chain, KeyStoreSecurityLevel securityLevel) {
            super(descriptor(), metadata(cert.getBytes(), chain.getBytes()), "N/A".getBytes(),
                    "RSA", securityLevel);
        }

        @Override
        AndroidKeyStorePrivateKey getPrivateKey() {
            return null;
        }
    }

    private AndroidKeyStorePublicKey makePrivateKeyObject(String cert, String chain) {
        return new MyPublicKey(cert, chain, mKeystoreSecurityLevel);
    }

    @Test
    public void testKeystoreKeysAdhereToContractBetweenEqualsAndHashCode() throws Exception {
        AndroidKeyStoreKey key1 = new AndroidKeyStoreKey(descriptor(), 1, new Authorization[0],
                "RSA", mKeystoreSecurityLevel);
        AndroidKeyStoreKey key2 = new AndroidKeyStoreKey(descriptor(), 2, new Authorization[0],
                "RSA", mKeystoreSecurityLevel);
        AndroidKeyStoreKey key1_clone = new AndroidKeyStoreKey(descriptor(), 1,
                new Authorization[0], "RSA", mKeystoreSecurityLevel);

        assertThat("Identity should yield true", key1.equals(key1));
        Assert.assertEquals("Identity should yield same hash codes",
                key1.hashCode(), key1.hashCode());
        assertThat("Identity should yield true", key2.equals(key2));
        Assert.assertEquals("Identity should yield same hash codes",
                key2.hashCode(), key2.hashCode());
        assertThat("Different keys should differ", !key1.equals(key2));
        Assert.assertNotEquals("Different keys should have different hash codes",
                key1.hashCode(), key2.hashCode());

        assertThat("Same keys should yield true", key1.equals(key1_clone));
        assertThat("Same keys should yield true", key1_clone.equals(key1));
        Assert.assertEquals("Same keys should yield same hash codes",
                key1.hashCode(), key1_clone.hashCode());

        assertThat("anything.equal(null) should yield false", !key1.equals(null));
        assertThat("anything.equal(null) should yield false", !key2.equals(null));
        assertThat("anything.equal(null) should yield false", !key1_clone.equals(null));
    }

    @Test
    public void testKeystorePublicKeysAdhereToContractBetweenEqualsAndHashCode() throws Exception {
        AndroidKeyStorePublicKey key1 = makePrivateKeyObject("myCert1", "myChain1");
        AndroidKeyStorePublicKey key2 = makePrivateKeyObject("myCert2", "myChain1");
        AndroidKeyStorePublicKey key3 = makePrivateKeyObject("myCert1", "myChain3");
        AndroidKeyStorePublicKey key1_clone = makePrivateKeyObject("myCert1", "myChain1");

        assertThat("Identity should yield true", key1.equals(key1));
        Assert.assertEquals("Identity should yield same hash codes",
                key1.hashCode(), key1.hashCode());
        assertThat("Identity should yield true", key2.equals(key2));
        Assert.assertEquals("Identity should yield same hash codes",
                key2.hashCode(), key2.hashCode());
        assertThat("Identity should yield true", key3.equals(key3));
        Assert.assertEquals("Identity should yield same hash codes",
                key3.hashCode(), key3.hashCode());
        assertThat("Different keys should differ", !key1.equals(key2));
        Assert.assertNotEquals("Different keys should have different hash codes",
                key1.hashCode(), key2.hashCode());
        assertThat("Different keys should differ", !key1.equals(key3));
        Assert.assertNotEquals("Different keys should have different hash codes",
                key1.hashCode(), key3.hashCode());
        assertThat("Different keys should differ", !key2.equals(key3));
        Assert.assertNotEquals("Different keys should have different hash codes",
                key2.hashCode(), key3.hashCode());

        assertThat("Same keys should yield true", key1.equals(key1_clone));
        assertThat("Same keys should yield true", key1_clone.equals(key1));
        Assert.assertEquals("Same keys should yield same hash codes",
                key1.hashCode(), key1_clone.hashCode());

        assertThat("anything.equal(null) should yield false", !key1.equals(null));
        assertThat("anything.equal(null) should yield false", !key2.equals(null));
        assertThat("anything.equal(null) should yield false", !key3.equals(null));
        assertThat("anything.equal(null) should yield false", !key1_clone.equals(null));
    }
}
