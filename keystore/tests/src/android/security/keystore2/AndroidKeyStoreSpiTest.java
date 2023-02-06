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
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.NoSuchElementException;

public class AndroidKeyStoreSpiTest {

    @Mock
    private KeyStore2 mKeystore2;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testEngineAliasesReturnsEmptySetOnKeyStoreError() throws Exception {
        when(mKeystore2.listBatch(anyInt(), anyLong(), isNull()))
                .thenThrow(new KeyStoreException(6, "Some Error"));
        AndroidKeyStoreSpi spi = new AndroidKeyStoreSpi();
        spi.initForTesting(mKeystore2);

        assertThat("Empty collection expected", !spi.engineAliases().hasMoreElements());

        verify(mKeystore2).listBatch(anyInt(), anyLong(), isNull());
    }

    @Test
    public void testEngineAliasesCorrectlyListsZeroEntriesEmptyArray() throws Exception {
        when(mKeystore2.listBatch(anyInt(), anyLong(), anyString()))
                .thenReturn(new KeyDescriptor[0]);
        AndroidKeyStoreSpi spi = new AndroidKeyStoreSpi();
        spi.initForTesting(mKeystore2);

        Enumeration<String> aliases = spi.engineAliases();
        assertThat("Should not have any elements", !aliases.hasMoreElements());
        assertThrows("Should have no elements to return", NoSuchElementException.class,
                () -> aliases.nextElement());
        verify(mKeystore2).listBatch(anyInt(), anyLong(), isNull());
    }

    @Test
    public void testEngineAliasesCorrectlyListsZeroEntriesNullArray() throws Exception {
        when(mKeystore2.listBatch(anyInt(), anyLong(), anyString()))
                .thenReturn(null);
        AndroidKeyStoreSpi spi = new AndroidKeyStoreSpi();
        spi.initForTesting(mKeystore2);

        Enumeration<String> aliases = spi.engineAliases();
        assertThat("Should not have any elements", !aliases.hasMoreElements());
        assertThrows("Should have no elements to return", NoSuchElementException.class,
                () -> aliases.nextElement());
        verify(mKeystore2).listBatch(anyInt(), anyLong(), isNull());
    }

    private static KeyDescriptor newKeyDescriptor(String alias) {
        KeyDescriptor result = new KeyDescriptor();
        result.alias = alias;
        return result;
    }

    private static KeyDescriptor[] createKeyDescriptorsArray(int numEntries) {
        KeyDescriptor[] kds = new KeyDescriptor[numEntries];
        for (int i = 0; i < kds.length; i++) {
            kds[i] = newKeyDescriptor(String.format("alias-%d", i));
        }

        return kds;
    }

    private static void assertAliasListsEqual(
            KeyDescriptor[] keyDescriptors, Enumeration<String> aliasesEnumerator) {
        List<String> aliases = Collections.list(aliasesEnumerator);
        Assert.assertArrayEquals(Arrays.stream(keyDescriptors).map(kd -> kd.alias).toArray(),
                aliases.toArray());
    }

    @Test
    public void testEngineAliasesCorrectlyListsEntriesInASingleBatch() throws Exception {
        final String alias1 = "testAlias1";
        final String alias2 = "testAlias2";
        final String alias3 = "testAlias3";
        KeyDescriptor[] kds = {newKeyDescriptor(alias1),
                newKeyDescriptor(alias2), newKeyDescriptor(alias3)};
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq(null)))
                .thenReturn(kds);
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq("testAlias3")))
                .thenReturn(null);

        AndroidKeyStoreSpi spi = new AndroidKeyStoreSpi();
        spi.initForTesting(mKeystore2);

        Enumeration<String> aliases = spi.engineAliases();
        assertThat("Should have more elements before first.", aliases.hasMoreElements());
        Assert.assertEquals(aliases.nextElement(), alias1);
        assertThat("Should have more elements before second.", aliases.hasMoreElements());
        Assert.assertEquals(aliases.nextElement(), alias2);
        assertThat("Should have more elements before third.", aliases.hasMoreElements());
        Assert.assertEquals(aliases.nextElement(), alias3);
        assertThat("Should have no more elements after third.", !aliases.hasMoreElements());
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq(null));
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq("testAlias3"));
        verifyNoMoreInteractions(mKeystore2);
    }

    @Test
    public void testEngineAliasesCorrectlyListsEntriesInMultipleBatches() throws Exception {
        final int numEntries = 2500;
        KeyDescriptor[] kds = createKeyDescriptorsArray(numEntries);
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq(null)))
                .thenReturn(Arrays.copyOfRange(kds, 0, 1000));
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq("alias-999")))
                .thenReturn(Arrays.copyOfRange(kds, 1000, 2000));
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq("alias-1999")))
                .thenReturn(Arrays.copyOfRange(kds, 2000, 2500));
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq("alias-2499")))
                .thenReturn(null);

        AndroidKeyStoreSpi spi = new AndroidKeyStoreSpi();
        spi.initForTesting(mKeystore2);

        assertAliasListsEqual(kds, spi.engineAliases());
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq(null));
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq("alias-999"));
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq("alias-1999"));
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq("alias-2499"));
        verifyNoMoreInteractions(mKeystore2);
    }

    @Test
    public void testEngineAliasesCorrectlyListsEntriesWhenNumEntriesIsExactlyOneBatchSize()
            throws Exception {
        final int numEntries = 1000;
        KeyDescriptor[] kds = createKeyDescriptorsArray(numEntries);
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq(null)))
                .thenReturn(kds);
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq("alias-999")))
                .thenReturn(null);

        AndroidKeyStoreSpi spi = new AndroidKeyStoreSpi();
        spi.initForTesting(mKeystore2);

        assertAliasListsEqual(kds, spi.engineAliases());
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq(null));
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq("alias-999"));
        verifyNoMoreInteractions(mKeystore2);
    }

    @Test
    public void testEngineAliasesCorrectlyListsEntriesWhenNumEntriesIsAMultiplyOfBatchSize()
            throws Exception {
        final int numEntries = 2000;
        KeyDescriptor[] kds = createKeyDescriptorsArray(numEntries);
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq(null)))
                .thenReturn(Arrays.copyOfRange(kds, 0, 1000));
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq("alias-999")))
                .thenReturn(Arrays.copyOfRange(kds, 1000, 2000));
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq("alias-1999")))
                .thenReturn(null);

        AndroidKeyStoreSpi spi = new AndroidKeyStoreSpi();
        spi.initForTesting(mKeystore2);

        assertAliasListsEqual(kds, spi.engineAliases());
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq(null));
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq("alias-999"));
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq("alias-1999"));
        verifyNoMoreInteractions(mKeystore2);
    }

    @Test
    public void testEngineAliasesCorrectlyListsEntriesWhenReturningLessThanBatchSize()
            throws Exception {
        final int numEntries = 500;
        KeyDescriptor[] kds = createKeyDescriptorsArray(numEntries);
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq(null)))
                .thenReturn(kds);
        when(mKeystore2.listBatch(anyInt(), anyLong(), eq("alias-499")))
                .thenReturn(new KeyDescriptor[0]);

        AndroidKeyStoreSpi spi = new AndroidKeyStoreSpi();
        spi.initForTesting(mKeystore2);

        assertAliasListsEqual(kds, spi.engineAliases());
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq(null));
        verify(mKeystore2).listBatch(anyInt(), anyLong(), eq("alias-499"));
        verifyNoMoreInteractions(mKeystore2);
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
