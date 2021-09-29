/*
 * Copyright (C) 2015 The Android Open Source Project
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


import static android.content.pm.parsing.ParsingPackageUtils.parsePublicKey;

import android.content.pm.Signature;
import android.test.AndroidTestCase;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LongSparseArray;

import com.android.internal.util.ArrayUtils;
import com.android.server.utils.WatchedArrayMap;

import java.io.File;
import java.io.IOException;
import java.security.PublicKey;
import java.security.cert.CertificateException;

public class KeySetManagerServiceTest extends AndroidTestCase {

    private WatchedArrayMap<String, PackageSetting> mPackagesMap;
    private KeySetManagerService mKsms;

    public PackageSetting generateFakePackageSetting(String name) {
        return new PackageSettingBuilder()
                .setName(name)
                .setCodePath(new File(mContext.getCacheDir(), "fakeCodePath").getAbsolutePath())
                .build();
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mPackagesMap = new WatchedArrayMap<String, PackageSetting>();
        mKsms = new KeySetManagerService(mPackagesMap);
    }

    public void testPackageKeySetDataConstructorUnassignend() {
        PackageKeySetData pksd = new PackageKeySetData();
        assertEquals(PackageKeySetData.KEYSET_UNASSIGNED, pksd.getProperSigningKeySet());
        assertNull(pksd.getUpgradeKeySets());
        ArrayMap<String, Long> aliases = pksd.getAliases();
        assertNotNull(aliases);
        assertEquals(0, aliases.size());
    }

    /* test equivalence of PackageManager cert encoding and ParsingPackageUtils manifest keys */
    public void testPublicKeyCertReprEquiv() throws CertificateException {
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        PublicKey keyC = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyC);

        Signature sigA = new Signature(KeySetStrings.ctsKeySetCertA);
        Signature sigB = new Signature(KeySetStrings.ctsKeySetCertB);
        Signature sigC = new Signature(KeySetStrings.ctsKeySetCertC);

        assertNotNull(keyA);
        assertNotNull(keyB);
        assertNotNull(keyC);

        assertEquals(keyA, sigA.getPublicKey());
        assertEquals(keyB, sigB.getPublicKey());
        assertEquals(keyC, sigC.getPublicKey());

        byte[] bArrayPk = keyA.getEncoded();
        byte[] bArrayCert = sigA.getPublicKey().getEncoded();
        assertEquals(bArrayPk.length, bArrayCert.length);
        assertEquals(true, ArrayUtils.equals(bArrayPk, bArrayCert, bArrayPk.length));

        bArrayPk = keyB.getEncoded();
        bArrayCert = sigB.getPublicKey().getEncoded();
        assertEquals(bArrayPk.length, bArrayCert.length);
        assertEquals(true, ArrayUtils.equals(bArrayPk, bArrayCert, bArrayPk.length));

        bArrayPk = keyC.getEncoded();
        bArrayCert = sigC.getPublicKey().getEncoded();
        assertEquals(bArrayPk.length, bArrayCert.length);
        assertEquals(true, ArrayUtils.equals(bArrayPk, bArrayCert, bArrayPk.length));
    }

    public void testEncodePublicKey() throws IOException {
        WatchedArrayMap<String, PackageSetting> packagesMap =
                new WatchedArrayMap<String, PackageSetting>();
        KeySetManagerService ksms = new KeySetManagerService(packagesMap);

        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        PublicKey keyC = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyC);

        assertEquals(ksms.encodePublicKey(keyA), KeySetStrings.ctsKeySetPublicKeyA);
        assertEquals(ksms.encodePublicKey(keyB), KeySetStrings.ctsKeySetPublicKeyB);
        assertEquals(ksms.encodePublicKey(keyC), KeySetStrings.ctsKeySetPublicKeyC);
    }

    /*
     * Add the keyset information for a package to a system w/no previous keysets
     */
    public void testAddSigningKSToPackageEmpty() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect signing key and add */
        ArraySet<PublicKey> signingKeys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        signingKeys.add(keyA);
        mKsms.addSigningKeySetToPackageLPw(ps, signingKeys);

        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(keyA, KeySetUtils.getPubKey(mKsms, 1));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(1);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        assertEquals(1, ps.getKeySetData().getProperSigningKeySet());
    }

    /*
     * upgrade an app (same packagename) with same keyset and verify that
     * nothing changed.
     */
    public void testAddSigningKSToPackageUpgradeSame() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect signing key and add */
        ArraySet<PublicKey> signingKeys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        signingKeys.add(keyA);
        mKsms.addSigningKeySetToPackageLPw(ps, signingKeys);

        /* add again, to represent upgrade of package */
        mKsms.addSigningKeySetToPackageLPw(ps, signingKeys);

        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(keyA, KeySetUtils.getPubKey(mKsms, 1));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(1);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        assertEquals(1, ps.getKeySetData().getProperSigningKeySet());
    }

    /*
     * upgrade an app (same packagename) with different unique keyset and verify
     * that the old was removed.
     */
    public void testAddSigningKSToPackageUpgradeDiff() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect signing key and add */
        ArraySet<PublicKey> signingKeys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        signingKeys.add(keyA);
        mKsms.addSigningKeySetToPackageLPw(ps, signingKeys);

        /* now upgrade with new key */
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        signingKeys.removeAt(0);
        signingKeys.add(keyB);
        mKsms.addSigningKeySetToPackageLPw(ps, signingKeys);

        assertEquals(0, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 2));
        assertEquals(0, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 2));
        assertEquals(keyB, KeySetUtils.getPubKey(mKsms, 2));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(2);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(2)));
        assertEquals(2, ps.getKeySetData().getProperSigningKeySet());
    }

    /*
     * upgrade an app (same packagename) with different keyset and verify
     * that the old had its ref count reduced due to reference by other ps.
     */
    public void testAddSigningKSToPackageUpgradeDiff2() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps1 = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps1.getPackageName(), ps1);
        PackageSetting ps2 = generateFakePackageSetting("packageB");
        mPackagesMap.put(ps2.getPackageName(), ps2);

        /* collect signing key and add */
        ArraySet<PublicKey> signingKeys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        signingKeys.add(keyA);
        mKsms.addSigningKeySetToPackageLPw(ps1, signingKeys);
        mKsms.addSigningKeySetToPackageLPw(ps2, signingKeys);

        /* now upgrade with new key */
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        signingKeys.removeAt(0);
        signingKeys.add(keyB);
        mKsms.addSigningKeySetToPackageLPw(ps1, signingKeys);

        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 2));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 2));
        assertEquals(keyA, KeySetUtils.getPubKey(mKsms, 1));
        assertEquals(keyB, KeySetUtils.getPubKey(mKsms, 2));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(2, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(1);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        mapping = ksMapping.get(2);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(2)));
        assertEquals(2, ps1.getKeySetData().getProperSigningKeySet());
        assertEquals(1, ps2.getKeySetData().getProperSigningKeySet());
    }

    /*
     * Add orthoganal keyset info to system and ensure previous keysets are
     * unmodified.
     */
    public void testAddSigningKSToPackageNewOrtho() throws ReflectiveOperationException {

        /* create PackageSettings and add to Settings mPackages */
        PackageSetting ps1 = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps1.getPackageName(), ps1);
        PackageSetting ps2 = generateFakePackageSetting("packageB");
        mPackagesMap.put(ps2.getPackageName(), ps2);

        /* collect signing key and add */
        ArraySet<PublicKey> signingKeys1 = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        signingKeys1.add(keyA);
        mKsms.addSigningKeySetToPackageLPw(ps1, signingKeys1);

        /* collect second signing key and add */
        ArraySet<PublicKey> signingKeys2 = new ArraySet<PublicKey>();
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        signingKeys2.add(keyB);
        mKsms.addSigningKeySetToPackageLPw(ps2, signingKeys2);

        /* verify first is unchanged */
        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(keyA, KeySetUtils.getPubKey(mKsms, 1));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(2, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(1);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        assertEquals(1, ps1.getKeySetData().getProperSigningKeySet());

        /* verify second */
        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 2));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 2));
        assertEquals(keyB, KeySetUtils.getPubKey(mKsms, 2));
        mapping = ksMapping.get(2);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new  Long(2)));
        assertEquals(2, ps2.getKeySetData().getProperSigningKeySet());
    }

    /*
     * Add identical keyset info to system via new package and ensure previous
     * keysets has reference count incremented
     */
    public void testAddSigningKSToPackageNewSame() throws ReflectiveOperationException {

        /* create PackageSettings and add to Settings mPackages */
        PackageSetting ps1 = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps1.getPackageName(), ps1);
        PackageSetting ps2 = generateFakePackageSetting("packageB");
        mPackagesMap.put(ps2.getPackageName(), ps2);

        /* collect signing key and add */
        ArraySet<PublicKey> signingKeys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        signingKeys.add(keyA);
        mKsms.addSigningKeySetToPackageLPw(ps1, signingKeys);

        /* add again for second package */
        mKsms.addSigningKeySetToPackageLPw(ps2, signingKeys);

        assertEquals(2, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(keyA, KeySetUtils.getPubKey(mKsms, 1));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(1);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        assertEquals(1, ps1.getKeySetData().getProperSigningKeySet());
        assertEquals(1, ps2.getKeySetData().getProperSigningKeySet());
    }

    /*
     * add a package which is signed by a keyset which contains a previously seen
     * public key and make sure its refernces are incremented.
     */
    public void testAddSigningKSToPackageSuper() throws ReflectiveOperationException {

        /* create PackageSettings and add to Settings mPackages */
        PackageSetting ps1 = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps1.getPackageName(), ps1);
        PackageSetting ps2 = generateFakePackageSetting("packageB");
        mPackagesMap.put(ps2.getPackageName(), ps2);

        /* collect signing key and add */
        ArraySet<PublicKey> signingKeys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        signingKeys.add(keyA);
        mKsms.addSigningKeySetToPackageLPw(ps1, signingKeys);

        /* give ps2 a superset (add keyB) */
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        signingKeys.add(keyB);
        mKsms.addSigningKeySetToPackageLPw(ps2, signingKeys);

        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 2));
        assertEquals(2, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 2));
        assertEquals(keyA, KeySetUtils.getPubKey(mKsms, 1));
        assertEquals(keyB, KeySetUtils.getPubKey(mKsms, 2));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(2, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(1);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        mapping = ksMapping.get(2);
        assertEquals(2, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        assertTrue(mapping.contains(new Long(2)));
        assertEquals(1, ps1.getKeySetData().getProperSigningKeySet());
        assertEquals(2, ps2.getKeySetData().getProperSigningKeySet());
    }

    /*
     * Upgrade an app (same pkgName) with different keyset which contains a public
     * key from the previous keyset.  Verify old keyset removed and pub key ref
     * count is accurate.
     */
    public void testAddSigningKSToPackageUpgradeDiffSuper() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect signing key and add */
        ArraySet<PublicKey> signingKeys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        signingKeys.add(keyA);
        mKsms.addSigningKeySetToPackageLPw(ps, signingKeys);

        /* now with additional key */
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        signingKeys.add(keyB);
        mKsms.addSigningKeySetToPackageLPw(ps, signingKeys);

        assertEquals(0, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 2));
        assertEquals(0, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 2));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 3));

        /* the pub key is removed w/prev keyset and may be either 2 or 3 */
        assertTrue(keyA.equals(KeySetUtils.getPubKey(mKsms, 2)) || keyA.equals(KeySetUtils.getPubKey(mKsms, 3)));
        assertTrue(keyB.equals(KeySetUtils.getPubKey(mKsms, 2)) || keyB.equals(KeySetUtils.getPubKey(mKsms, 3)));
        assertFalse(KeySetUtils.getPubKey(mKsms, 2).equals(KeySetUtils.getPubKey(mKsms, 3)));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(2);
        assertEquals(2, mapping.size());
        assertTrue(mapping.contains(new Long(2)));
        assertTrue(mapping.contains(new Long(3)));
        assertEquals(2, ps.getKeySetData().getProperSigningKeySet());
    }

    /* add a defined keyset make sure it shows up */
    public void testAddDefinedKSToPackageEmpty() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect key and add */
        ArrayMap<String, ArraySet<PublicKey>> definedKS = new ArrayMap<String, ArraySet<PublicKey>>();
        ArraySet<PublicKey> keys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        keys.add(keyA);
        definedKS.put("aliasA", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(keyA, KeySetUtils.getPubKey(mKsms, 1));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(1);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        assertNotNull(ps.getKeySetData().getAliases().get("aliasA"));
        assertEquals(new Long(1), ps.getKeySetData().getAliases().get("aliasA"));
    }

    /* add 2 defined keysets which refer to same keyset and make sure ref-ct is 2 */
    public void testAddDefinedKSToPackageDoubleAlias() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect key and add */
        ArrayMap<String, ArraySet<PublicKey>> definedKS = new ArrayMap<String, ArraySet<PublicKey>>();
        ArraySet<PublicKey> keys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        keys.add(keyA);
        definedKS.put("aliasA", keys);
        definedKS.put("aliasA2", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        assertEquals(2, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(keyA, KeySetUtils.getPubKey(mKsms, 1));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(1);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(1)));
        assertNotNull(ps.getKeySetData().getAliases().get("aliasA"));
        assertEquals(new Long(1), ps.getKeySetData().getAliases().get("aliasA"));
        assertNotNull(ps.getKeySetData().getAliases().get("aliasA2"));
        assertEquals(new Long(1), ps.getKeySetData().getAliases().get("aliasA2"));
    }

    /* upgrd defined keyset ortho (make sure previous is removed for pkg) */
    public void testAddDefinedKSToPackageOrthoUpgr() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect key and add */
        ArrayMap<String, ArraySet<PublicKey>> definedKS = new ArrayMap<String, ArraySet<PublicKey>>();
        ArraySet<PublicKey> keys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        keys.add(keyA);
        definedKS.put("aliasA", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        /* now upgrade to different defined key-set */
        keys = new ArraySet<PublicKey>();
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        keys.add(keyB);
        definedKS.remove("aliasA");
        definedKS.put("aliasB", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        assertEquals(0, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(0, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 2));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 2));
        assertEquals(keyB, KeySetUtils.getPubKey(mKsms, 2));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(2);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(2)));
        assertNull(ps.getKeySetData().getAliases().get("aliasA"));
        assertNotNull(ps.getKeySetData().getAliases().get("aliasB"));
        assertEquals(new Long(2), ps.getKeySetData().getAliases().get("aliasB"));
    }

    /* upgrd defined keyset ortho but reuse alias (make sure old is removed and
     * alias points to new keyset)
     */
    public void testAddDefinedKSToPackageOrthoUpgrAliasSame() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect key and add */
        ArrayMap<String, ArraySet<PublicKey>> definedKS = new ArrayMap<String, ArraySet<PublicKey>>();
        ArraySet<PublicKey> keys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        keys.add(keyA);
        definedKS.put("aliasA", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        /* now upgrade to different set w/same alias as before */
        keys = new ArraySet<PublicKey>();
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        keys.add(keyB);
        definedKS.put("aliasA", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        assertEquals(0, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(0, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 2));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 2));
        assertEquals(keyB, KeySetUtils.getPubKey(mKsms, 2));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(2);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(2)));
        assertNotNull(ps.getKeySetData().getAliases().get("aliasA"));
        assertEquals(new Long(2), ps.getKeySetData().getAliases().get("aliasA"));
    }

     /* Start with defined ks of (A, B) and upgrade to (B, C).  Make sure B is
      * unchanged. */
    public void testAddDefinedKSToPackageOverlapUpgr() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect keys A and B and add */
        ArrayMap<String, ArraySet<PublicKey>> definedKS = new ArrayMap<String, ArraySet<PublicKey>>();
        ArraySet<PublicKey> keys1 = new ArraySet<PublicKey>();
        ArraySet<PublicKey> keys2 = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        keys1.add(keyA);
        keys2.add(keyB);
        definedKS.put("aliasA", keys1);
        definedKS.put("aliasB", keys2);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        /* now upgrade to different set (B, C) */
        keys1 = new ArraySet<PublicKey>();
        PublicKey keyC = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyC);
        keys1.add(keyC);
        definedKS.remove("aliasA");
        definedKS.put("aliasC", keys1);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 3));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 3));
        assertEquals(keyC, KeySetUtils.getPubKey(mKsms, 3));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(2, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(3);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(3)));
        assertEquals(new Long(3), ps.getKeySetData().getAliases().get("aliasC"));

        /* either keyset w/keyA or w/keyB was added first, address both cases */
        if (1 == KeySetUtils.getKeySetRefCount(mKsms, 1)) {

            /* keyB was added first and should have keyset 1 and pub-key 1 */
            assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 1));
            assertEquals(0, KeySetUtils.getKeySetRefCount(mKsms, 2));
            assertEquals(0, KeySetUtils.getPubKeyRefCount(mKsms, 2));
            assertEquals(keyB, KeySetUtils.getPubKey(mKsms, 1));
            mapping = ksMapping.get(1);
            assertEquals(1, mapping.size());
            assertTrue(mapping.contains(new Long(1)));
            assertEquals(new Long(1), ps.getKeySetData().getAliases().get("aliasB"));
        } else {

            /* keyA was added first and keyB has id 2 */
            assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 2));
            assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 2));
            assertEquals(0, KeySetUtils.getKeySetRefCount(mKsms, 1));
            assertEquals(0, KeySetUtils.getPubKeyRefCount(mKsms, 1));
            assertEquals(keyB, KeySetUtils.getPubKey(mKsms, 2));
            mapping = ksMapping.get(2);
            assertEquals(1, mapping.size());
            assertTrue(mapping.contains(new Long(2)));
            assertEquals(new Long(2), ps.getKeySetData().getAliases().get("aliasB"));
        }
        assertNull(ps.getKeySetData().getAliases().get("aliasA"));
    }

    /* add defined keyset, remove it, add again and make sure diff id. */
    public void testAddDefinedKSToPackageThree() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect key and add */
        ArrayMap<String, ArraySet<PublicKey>> definedKS = new ArrayMap<String, ArraySet<PublicKey>>();
        ArraySet<PublicKey> keys1 = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        keys1.add(keyA);
        definedKS.put("aliasA", keys1);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        /* now upgrade to different set */
        ArraySet<PublicKey> keys2 = new ArraySet<PublicKey>();
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        keys2.add(keyB);
        definedKS.remove("aliasA");
        definedKS.put("aliasB", keys2);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        /* upgrade back to original */
        definedKS.remove("aliasB");
        definedKS.put("aliasA", keys1);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);

        assertEquals(0, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(0, KeySetUtils.getKeySetRefCount(mKsms, 2));
        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 3));
        assertEquals(0, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        assertEquals(0, KeySetUtils.getPubKeyRefCount(mKsms, 2));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 3));
        assertEquals(keyA, KeySetUtils.getPubKey(mKsms, 3));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        ArraySet<Long> mapping = ksMapping.get(3);
        assertEquals(1, mapping.size());
        assertTrue(mapping.contains(new Long(3)));
        assertEquals(new Long(3), ps.getKeySetData().getAliases().get("aliasA"));
    }

    /* add upgrade keyset for existing defined keyset and check that it is recorded */
    public void testAddUpgradeKSToPackageEmpty() {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect key and add, and denote as an upgrade keyset */
        ArrayMap<String, ArraySet<PublicKey>> definedKS = new ArrayMap<String, ArraySet<PublicKey>>();
        ArraySet<PublicKey> keys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        keys.add(keyA);
        definedKS.put("aliasA", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);
        ArraySet<String> upgradeKS = new ArraySet<String>();
        upgradeKS.add("aliasA");
        mKsms.addUpgradeKeySetsToPackageLPw(ps, upgradeKS);

        assertEquals(1, ps.getKeySetData().getUpgradeKeySets().length);
        assertEquals(1, ps.getKeySetData().getUpgradeKeySets()[0]);
    }

    /* add upgrade keyset for non-existing defined and check that it compains */
    public void testAddUpgradeKSToPackageWrong() {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect key and add and try to specify bogus upgrade keyset */
        ArrayMap<String, ArraySet<PublicKey>> definedKS = new ArrayMap<String, ArraySet<PublicKey>>();
        ArraySet<PublicKey> keys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        keys.add(keyA);
        definedKS.put("aliasA", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);
        ArraySet<String> upgradeKS = new ArraySet<String>();
        upgradeKS.add("aliasB");
        try {
            mKsms.addUpgradeKeySetsToPackageLPw(ps, upgradeKS);
        } catch (IllegalArgumentException e) {

            /* should have been caught in packagemanager, so exception thrown */
            return;
        }
        fail("Expected IllegalArgumentException when adding undefined upgrade keyset!!");
    }

    /* upgrade from defined keysets w/upgrade to different defined keysets and
     * make sure the previously specified upgrade keyset has been removed. */
    public void testAddUpgradeKSToPackageDisappear() {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect key and add */
        ArrayMap<String, ArraySet<PublicKey>> definedKS = new ArrayMap<String, ArraySet<PublicKey>>();
        ArraySet<PublicKey> keys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        keys.add(keyA);
        definedKS.put("aliasA", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);
        ArraySet<String> upgradeKS = new ArraySet<String>();
        upgradeKS.add("aliasA");
        mKsms.addUpgradeKeySetsToPackageLPw(ps, upgradeKS);

        keys = new ArraySet<PublicKey>();
        PublicKey keyB = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyB);
        keys.add(keyB);
        definedKS.remove("aliasA");
        definedKS.put("aliasB", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);
        assertNull(ps.getKeySetData().getUpgradeKeySets());
    }

    /* remove package and validate that keyset and public keys are removed */
    public void testRemoveAppKSDataUnique() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect signing key and add */
        ArraySet<PublicKey> signingKeys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        signingKeys.add(keyA);
        mKsms.addSigningKeySetToPackageLPw(ps, signingKeys);

        /* remove its references */
        mKsms.removeAppKeySetDataLPw(ps.getPackageName());
        assertEquals(0, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(0, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(0, ksMapping.size());
        assertEquals(PackageKeySetData.KEYSET_UNASSIGNED,
                ps.getKeySetData().getProperSigningKeySet());
    }

    /* remove package and validate that keysets remain if defined elsewhere but
     * have refcounts decreased. */
    public void testRemoveAppKSDataDup() throws ReflectiveOperationException {

        /* create PackageSettings and add to Settings mPackages */
        PackageSetting ps1 = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps1.getPackageName(), ps1);
        PackageSetting ps2 = generateFakePackageSetting("packageB");
        mPackagesMap.put(ps2.getPackageName(), ps2);

        /* collect signing key and add for both packages */
        ArraySet<PublicKey> signingKeys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        signingKeys.add(keyA);
        mKsms.addSigningKeySetToPackageLPw(ps1, signingKeys);
        mKsms.addSigningKeySetToPackageLPw(ps2, signingKeys);

        /* remove references from first package */
        mKsms.removeAppKeySetDataLPw(ps1.getPackageName());

        assertEquals(1, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(1, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(1, ksMapping.size());
        assertEquals(PackageKeySetData.KEYSET_UNASSIGNED,
                ps1.getKeySetData().getProperSigningKeySet());
        assertEquals(1, ps2.getKeySetData().getProperSigningKeySet());
    }

    /* remove package which used defined and upgrade keysets and ensure  removed */
    public void testRemoveAppKSDataDefined() throws ReflectiveOperationException {

        /* create PackageSetting and add to Settings mPackages */
        PackageSetting ps = generateFakePackageSetting("packageA");
        mPackagesMap.put(ps.getPackageName(), ps);

        /* collect key and add */
        ArrayMap<String, ArraySet<PublicKey>> definedKS = new ArrayMap<String, ArraySet<PublicKey>>();
        ArraySet<PublicKey> keys = new ArraySet<PublicKey>();
        PublicKey keyA = parsePublicKey(KeySetStrings.ctsKeySetPublicKeyA);
        keys.add(keyA);

        /* removal requires signing keyset to be specified (since all apps are
         * assumed to have it).  We skipped this in the defined tests, but can't
         * here. */
        mKsms.addSigningKeySetToPackageLPw(ps, keys);

        definedKS.put("aliasA", keys);
        mKsms.addDefinedKeySetsToPackageLPw(ps, definedKS);
        ArraySet<String> upgradeKS = new ArraySet<String>();
        upgradeKS.add("aliasA");
        mKsms.addUpgradeKeySetsToPackageLPw(ps, upgradeKS);
        mKsms.removeAppKeySetDataLPw(ps.getPackageName());

        assertEquals(0, KeySetUtils.getKeySetRefCount(mKsms, 1));
        assertEquals(0, KeySetUtils.getPubKeyRefCount(mKsms, 1));
        LongSparseArray<ArraySet<Long>> ksMapping = KeySetUtils.getKeySetMapping(mKsms);
        assertEquals(0, ksMapping.size());
        assertEquals(PackageKeySetData.KEYSET_UNASSIGNED,
                ps.getKeySetData().getProperSigningKeySet());
        assertEquals(0, ps.getKeySetData().getAliases().size());
        assertNull(ps.getKeySetData().getUpgradeKeySets());
    }
}
