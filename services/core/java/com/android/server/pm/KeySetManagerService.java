/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.pm.KeySet;
import android.content.pm.PackageParser;
import android.os.Binder;
import android.util.ArraySet;
import android.util.Base64;
import android.util.Slog;
import android.util.LongSparseArray;

import java.io.IOException;
import java.io.PrintWriter;
import java.security.PublicKey;
import java.util.Map;
import java.util.Set;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

/*
 * Manages system-wide KeySet state.
 */
public class KeySetManagerService {

    static final String TAG = "KeySetManagerService";

    /* original keysets implementation had no versioning info, so this is the first */
    public static final int FIRST_VERSION = 1;

    public static final int CURRENT_VERSION = FIRST_VERSION;

    /** Sentinel value returned when a {@code KeySet} is not found. */
    public static final long KEYSET_NOT_FOUND = -1;

    /** Sentinel value returned when public key is not found. */
    protected static final long PUBLIC_KEY_NOT_FOUND = -1;

    private final Object mLockObject = new Object();

    private final LongSparseArray<KeySet> mKeySets;

    private final LongSparseArray<PublicKey> mPublicKeys;

    protected final LongSparseArray<Set<Long>> mKeySetMapping;

    private final Map<String, PackageSetting> mPackages;

    private static long lastIssuedKeySetId = 0;

    private static long lastIssuedKeyId = 0;

    public KeySetManagerService(Map<String, PackageSetting> packages) {
        mKeySets = new LongSparseArray<KeySet>();
        mPublicKeys = new LongSparseArray<PublicKey>();
        mKeySetMapping = new LongSparseArray<Set<Long>>();
        mPackages = packages;
    }

    /**
     * Determine if a package is signed by the given KeySet.
     *
     * Returns false if the package was not signed by all the
     * keys in the KeySet.
     *
     * Returns true if the package was signed by at least the
     * keys in the given KeySet.
     *
     * Note that this can return true for multiple KeySets.
     */
    public boolean packageIsSignedBy(String packageName, KeySet ks) {
        synchronized (mLockObject) {
            PackageSetting pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new NullPointerException("Invalid package name");
            }
            if (pkg.keySetData == null) {
                throw new NullPointerException("Package has no KeySet data");
            }
            long id = getIdByKeySetLocked(ks);
            return pkg.keySetData.packageIsSignedBy(id);
        }
    }

    /**
     * This informs the system that the given package has defined a KeySet
     * in its manifest that a) contains the given keys and b) is named
     * alias by that package.
     */
    public void addDefinedKeySetToPackage(String packageName,
            Set<PublicKey> keys, String alias) {
        if ((packageName == null) || (keys == null) || (alias == null)) {
            Slog.w(TAG, "Got null argument for a defined keyset, ignoring!");
            return;
        }
        synchronized (mLockObject) {
            PackageSetting pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new NullPointerException("Unknown package");
            }
            // Add to KeySets, then to package
            KeySet ks = addKeySetLocked(keys);
            long id = getIdByKeySetLocked(ks);
            pkg.keySetData.addDefinedKeySet(id, alias);
        }
    }

    /**
     * This informs the system that the given package has defined a KeySet
     * alias in its manifest to be an upgradeKeySet.  This must be called
     * after all of the defined KeySets have been added.
     */
    public void addUpgradeKeySetToPackage(String packageName, String alias) {
        if ((packageName == null) || (alias == null)) {
            Slog.w(TAG, "Got null argument for a defined keyset, ignoring!");
            return;
        }
        synchronized (mLockObject) {
            PackageSetting pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new NullPointerException("Unknown package");
            }
            pkg.keySetData.addUpgradeKeySet(alias);
        }
    }

    /**
     * Similar to the above, this informs the system that the given package
     * was signed by the provided KeySet.
     */
    public void addSigningKeySetToPackage(String packageName,
            Set<PublicKey> signingKeys) {
        if ((packageName == null) || (signingKeys == null)) {
            Slog.w(TAG, "Got null argument for a signing keyset, ignoring!");
            return;
        }
        synchronized (mLockObject) {
            // add the signing KeySet
            KeySet ks = addKeySetLocked(signingKeys);
            long id = getIdByKeySetLocked(ks);
            Set<Long> publicKeyIds = mKeySetMapping.get(id);
            if (publicKeyIds == null) {
                throw new NullPointerException("Got invalid KeySet id");
            }

            // attach it to the package
            PackageSetting pkg = mPackages.get(packageName);
            if (pkg == null) {
                throw new NullPointerException("No such package!");
            }
            pkg.keySetData.setProperSigningKeySet(id);
            // for each KeySet which is a subset of the one above, add the
            // KeySet id to the package's signing KeySets
            for (int keySetIndex = 0; keySetIndex < mKeySets.size(); keySetIndex++) {
                long keySetID = mKeySets.keyAt(keySetIndex);
                Set<Long> definedKeys = mKeySetMapping.get(keySetID);
                if (publicKeyIds.containsAll(definedKeys)) {
                    pkg.keySetData.addSigningKeySet(keySetID);
                }
            }
        }
    }

    /**
     * Fetches the stable identifier associated with the given KeySet. Returns
     * {@link #KEYSET_NOT_FOUND} if the KeySet... wasn't found.
     */
    public long getIdByKeySet(KeySet ks) {
        synchronized (mLockObject) {
            return getIdByKeySetLocked(ks);
        }
    }

    private long getIdByKeySetLocked(KeySet ks) {
        for (int keySetIndex = 0; keySetIndex < mKeySets.size(); keySetIndex++) {
            KeySet value = mKeySets.valueAt(keySetIndex);
            if (ks.equals(value)) {
                return mKeySets.keyAt(keySetIndex);
            }
        }
        return KEYSET_NOT_FOUND;
    }

    /**
     * Fetches the KeySet corresponding to the given stable identifier.
     *
     * Returns {@link #KEYSET_NOT_FOUND} if the identifier doesn't
     * identify a {@link KeySet}.
     */
    public KeySet getKeySetById(long id) {
        synchronized (mLockObject) {
            return mKeySets.get(id);
        }
    }

    /**
     * Fetches the {@link KeySet} that a given package refers to by the provided alias.
     *
     * @throws IllegalArgumentException if the package has no keyset data.
     * @throws NullPointerException if the package is unknown.
     */
    public KeySet getKeySetByAliasAndPackageName(String packageName, String alias) {
        synchronized (mLockObject) {
            PackageSetting p = mPackages.get(packageName);
            if (p == null) {
                throw new NullPointerException("Unknown package");
            }
            if (p.keySetData == null) {
                throw new IllegalArgumentException("Package has no keySet data");
            }
            long keySetId = p.keySetData.getAliases().get(alias);
            return mKeySets.get(keySetId);
        }
    }

    /**
     * Fetches the {@link PublicKey public keys} which belong to the specified
     * KeySet id.
     *
     * Returns {@code null} if the identifier doesn't
     * identify a {@link KeySet}.
     */
    public ArraySet<PublicKey> getPublicKeysFromKeySet(long id) {
        synchronized (mLockObject) {
            if(mKeySetMapping.get(id) == null) {
                return null;
            }
            ArraySet<PublicKey> mPubKeys = new ArraySet<PublicKey>();
            for (long pkId : mKeySetMapping.get(id)) {
                mPubKeys.add(mPublicKeys.get(pkId));
            }
            return mPubKeys;
        }
    }

    /**
     * Fetches all the known {@link KeySet KeySets} that signed the given
     * package.
     *
     * @throws IllegalArgumentException if the package has no keyset data.
     * @throws NullPointerException if the package is unknown.
     */
    public Set<KeySet> getSigningKeySetsByPackageName(String packageName) {
        synchronized (mLockObject) {
            Set<KeySet> signingKeySets = new ArraySet<KeySet>();
            PackageSetting p = mPackages.get(packageName);
            if (p == null) {
                throw new NullPointerException("Unknown package");
            }
            if (p.keySetData == null || p.keySetData.getSigningKeySets() == null) {
                throw new IllegalArgumentException("Package has no keySet data");
            }
            for (long l : p.keySetData.getSigningKeySets()) {
                signingKeySets.add(mKeySets.get(l));
            }
            return signingKeySets;
        }
    }

    /**
     * Fetches all the known {@link KeySet KeySets} that may upgrade the given
     * package.
     *
     * @throws IllegalArgumentException if the package has no keyset data.
     * @throws NullPointerException if the package is unknown.
     */
    public ArraySet<KeySet> getUpgradeKeySetsByPackageName(String packageName) {
        synchronized (mLockObject) {
            ArraySet<KeySet> upgradeKeySets = new ArraySet<KeySet>();
            PackageSetting p = mPackages.get(packageName);
            if (p == null) {
                throw new NullPointerException("Unknown package");
            }
            if (p.keySetData == null) {
                throw new IllegalArgumentException("Package has no keySet data");
            }
            if (p.keySetData.isUsingUpgradeKeySets()) {
                for (long l : p.keySetData.getUpgradeKeySets()) {
                    upgradeKeySets.add(mKeySets.get(l));
                }
            }
            return upgradeKeySets;
        }
    }

    /**
     * Creates a new KeySet corresponding to the given keys.
     *
     * If the {@link PublicKey PublicKeys} aren't known to the system, this
     * adds them. Otherwise, they're deduped.
     *
     * If the KeySet isn't known to the system, this adds that and creates the
     * mapping to the PublicKeys. If it is known, then it's deduped.
     *
     * If the KeySet isn't known to the system, this adds it to all appropriate
     * signingKeySets
     *
     * Throws if the provided set is {@code null}.
     */
    private KeySet addKeySetLocked(Set<PublicKey> keys) {
        if (keys == null) {
            throw new NullPointerException("Provided keys cannot be null");
        }
        // add each of the keys in the provided set
        Set<Long> addedKeyIds = new ArraySet<Long>(keys.size());
        for (PublicKey k : keys) {
            long id = addPublicKeyLocked(k);
            addedKeyIds.add(id);
        }

        // check to see if the resulting keyset is new
        long existingKeySetId = getIdFromKeyIdsLocked(addedKeyIds);
        if (existingKeySetId != KEYSET_NOT_FOUND) {
            return mKeySets.get(existingKeySetId);
        }

        // create the KeySet object
        KeySet ks = new KeySet(new Binder());
        // get the first unoccupied slot in mKeySets
        long id = getFreeKeySetIDLocked();
        // add the KeySet object to it
        mKeySets.put(id, ks);
        // add the stable key ids to the mapping
        mKeySetMapping.put(id, addedKeyIds);
        // add this KeySet id to all packages which are signed by it
        for (String pkgName : mPackages.keySet()) {
            PackageSetting p = mPackages.get(pkgName);
            if (p.keySetData != null) {
                long pProperSigning = p.keySetData.getProperSigningKeySet();
                if (pProperSigning != PackageKeySetData.KEYSET_UNASSIGNED) {
                    Set<Long> pSigningKeys = mKeySetMapping.get(pProperSigning);
                    if (pSigningKeys.containsAll(addedKeyIds)) {
                        p.keySetData.addSigningKeySet(id);
                    }
                }
            }
        }
        // go home
        return ks;
    }

    /**
     * Adds the given PublicKey to the system, deduping as it goes.
     */
    private long addPublicKeyLocked(PublicKey key) {
        // check if the public key is new
        long existingKeyId = getIdForPublicKeyLocked(key);
        if (existingKeyId != PUBLIC_KEY_NOT_FOUND) {
            return existingKeyId;
        }
        // if it's new find the first unoccupied slot in the public keys
        long id = getFreePublicKeyIdLocked();
        // add the public key to it
        mPublicKeys.put(id, key);
        // return the stable identifier
        return id;
    }

    /**
     * Finds the stable identifier for a KeySet based on a set of PublicKey stable IDs.
     *
     * Returns KEYSET_NOT_FOUND if there isn't one.
     */
    private long getIdFromKeyIdsLocked(Set<Long> publicKeyIds) {
        for (int keyMapIndex = 0; keyMapIndex < mKeySetMapping.size(); keyMapIndex++) {
            Set<Long> value = mKeySetMapping.valueAt(keyMapIndex);
            if (value.equals(publicKeyIds)) {
                return mKeySetMapping.keyAt(keyMapIndex);
            }
        }
        return KEYSET_NOT_FOUND;
    }

    /**
     * Finds the stable identifier for a PublicKey or PUBLIC_KEY_NOT_FOUND.
     */
    protected long getIdForPublicKey(PublicKey k) {
        synchronized (mLockObject) {
            return getIdForPublicKeyLocked(k);
        }
    }

    /**
     * Finds the stable identifier for a PublicKey or PUBLIC_KEY_NOT_FOUND.
     */
    private long getIdForPublicKeyLocked(PublicKey k) {
        String encodedPublicKey = new String(k.getEncoded());
        for (int publicKeyIndex = 0; publicKeyIndex < mPublicKeys.size(); publicKeyIndex++) {
            PublicKey value = mPublicKeys.valueAt(publicKeyIndex);
            String encodedExistingKey = new String(value.getEncoded());
            if (encodedPublicKey.equals(encodedExistingKey)) {
                return mPublicKeys.keyAt(publicKeyIndex);
            }
        }
        return PUBLIC_KEY_NOT_FOUND;
    }

    /**
     * Gets an unused stable identifier for a KeySet.
     */
    private long getFreeKeySetIDLocked() {
        lastIssuedKeySetId += 1;
        return lastIssuedKeySetId;
    }

    /**
     * Same as above, but for public keys.
     */
    private long getFreePublicKeyIdLocked() {
        lastIssuedKeyId += 1;
        return lastIssuedKeyId;
    }

    public void removeAppKeySetData(String packageName) {
        synchronized (mLockObject) {
            // Get the package's known keys and KeySets
            Set<Long> deletableKeySets = getOriginalKeySetsByPackageNameLocked(packageName);
            Set<Long> deletableKeys = new ArraySet<Long>();
            Set<Long> knownKeys = null;
            for (Long ks : deletableKeySets) {
                knownKeys = mKeySetMapping.get(ks);
                if (knownKeys != null) {
                    deletableKeys.addAll(knownKeys);
                }
            }

            // Now remove the keys and KeySets on which any other package relies
            for (String pkgName : mPackages.keySet()) {
                if (pkgName.equals(packageName)) {
                    continue;
                }
                Set<Long> knownKeySets = getOriginalKeySetsByPackageNameLocked(pkgName);
                deletableKeySets.removeAll(knownKeySets);
                knownKeys = new ArraySet<Long>();
                for (Long ks : knownKeySets) {
                    knownKeys = mKeySetMapping.get(ks);
                    if (knownKeys != null) {
                        deletableKeys.removeAll(knownKeys);
                    }
                }
            }

            // The remaining keys and KeySets are not relied on by any other
            // application and so can be safely deleted.
            for (Long ks : deletableKeySets) {
                mKeySets.delete(ks);
                mKeySetMapping.delete(ks);
            }
            for (Long keyId : deletableKeys) {
                mPublicKeys.delete(keyId);
            }

            // Now remove the deleted KeySets from each package's signingKeySets
            for (String pkgName : mPackages.keySet()) {
                PackageSetting p = mPackages.get(pkgName);
                for (Long ks : deletableKeySets) {
                    p.keySetData.removeSigningKeySet(ks);
                }
            }

            // Finally, remove all KeySets from the original package
            PackageSetting p = mPackages.get(packageName);
            clearPackageKeySetDataLocked(p);
        }
    }

    private void clearPackageKeySetDataLocked(PackageSetting p) {
        p.keySetData.removeAllSigningKeySets();
        p.keySetData.removeAllUpgradeKeySets();
        p.keySetData.removeAllDefinedKeySets();
        return;
    }

    private Set<Long> getOriginalKeySetsByPackageNameLocked(String packageName) {
        PackageSetting p = mPackages.get(packageName);
        if (p == null) {
            throw new NullPointerException("Unknown package");
        }
        if (p.keySetData == null) {
            throw new IllegalArgumentException("Package has no keySet data");
        }
        Set<Long> knownKeySets = new ArraySet<Long>();
        knownKeySets.add(p.keySetData.getProperSigningKeySet());
        if (p.keySetData.isUsingDefinedKeySets()) {
            for (long ks : p.keySetData.getDefinedKeySets()) {
                knownKeySets.add(ks);
            }
        }
        return knownKeySets;
    }

    public String encodePublicKey(PublicKey k) throws IOException {
        return new String(Base64.encode(k.getEncoded(), 0));
    }

    public void dump(PrintWriter pw, String packageName,
            PackageManagerService.DumpState dumpState) {
        synchronized (mLockObject) {
            boolean printedHeader = false;
            for (Map.Entry<String, PackageSetting> e : mPackages.entrySet()) {
                String keySetPackage = e.getKey();
                if (packageName != null && !packageName.equals(keySetPackage)) {
                    continue;
                }
                if (!printedHeader) {
                    if (dumpState.onTitlePrinted())
                        pw.println();
                    pw.println("Key Set Manager:");
                    printedHeader = true;
                }
                PackageSetting pkg = e.getValue();
                pw.print("  ["); pw.print(keySetPackage); pw.println("]");
                if (pkg.keySetData != null) {
                    boolean printedLabel = false;
                    for (Map.Entry<String, Long> entry : pkg.keySetData.getAliases().entrySet()) {
                        if (!printedLabel) {
                            pw.print("      KeySets Aliases: ");
                            printedLabel = true;
                        } else {
                            pw.print(", ");
                        }
                        pw.print(entry.getKey());
                        pw.print('=');
                        pw.print(Long.toString(entry.getValue()));
                    }
                    if (printedLabel) {
                        pw.println("");
                    }
                    printedLabel = false;
                    if (pkg.keySetData.isUsingDefinedKeySets()) {
                        for (long keySetId : pkg.keySetData.getDefinedKeySets()) {
                            if (!printedLabel) {
                                pw.print("      Defined KeySets: ");
                                printedLabel = true;
                            } else {
                                pw.print(", ");
                            }
                            pw.print(Long.toString(keySetId));
                        }
                    }
                    if (printedLabel) {
                        pw.println("");
                    }
                    printedLabel = false;
                    for (long keySetId : pkg.keySetData.getSigningKeySets()) {
                        if (!printedLabel) {
                            pw.print("      Signing KeySets: ");
                            printedLabel = true;
                        } else {
                            pw.print(", ");
                        }
                        pw.print(Long.toString(keySetId));
                    }
                    if (printedLabel) {
                        pw.println("");
                    }
                    printedLabel = false;
                    if (pkg.keySetData.isUsingUpgradeKeySets()) {
                        for (long keySetId : pkg.keySetData.getUpgradeKeySets()) {
                            if (!printedLabel) {
                                pw.print("      Upgrade KeySets: ");
                                printedLabel = true;
                            } else {
                                pw.print(", ");
                            }
                            pw.print(Long.toString(keySetId));
                        }
                    }
                    if (printedLabel) {
                        pw.println("");
                    }
                }
            }
        }
    }

    void writeKeySetManagerServiceLPr(XmlSerializer serializer) throws IOException {
        serializer.startTag(null, "keyset-settings");
        serializer.attribute(null, "version", Integer.toString(CURRENT_VERSION));
        writePublicKeysLPr(serializer);
        writeKeySetsLPr(serializer);
        serializer.startTag(null, "lastIssuedKeyId");
        serializer.attribute(null, "value", Long.toString(lastIssuedKeyId));
        serializer.endTag(null, "lastIssuedKeyId");
        serializer.startTag(null, "lastIssuedKeySetId");
        serializer.attribute(null, "value", Long.toString(lastIssuedKeySetId));
        serializer.endTag(null, "lastIssuedKeySetId");
        serializer.endTag(null, "keyset-settings");
    }

    void writePublicKeysLPr(XmlSerializer serializer) throws IOException {
        serializer.startTag(null, "keys");
        for (int pKeyIndex = 0; pKeyIndex < mPublicKeys.size(); pKeyIndex++) {
            long id = mPublicKeys.keyAt(pKeyIndex);
            PublicKey key = mPublicKeys.valueAt(pKeyIndex);
            String encodedKey = encodePublicKey(key);
            serializer.startTag(null, "public-key");
            serializer.attribute(null, "identifier", Long.toString(id));
            serializer.attribute(null, "value", encodedKey);
            serializer.endTag(null, "public-key");
        }
        serializer.endTag(null, "keys");
    }

    void writeKeySetsLPr(XmlSerializer serializer) throws IOException {
        serializer.startTag(null, "keysets");
        for (int keySetIndex = 0; keySetIndex < mKeySetMapping.size(); keySetIndex++) {
            long id = mKeySetMapping.keyAt(keySetIndex);
            Set<Long> keys = mKeySetMapping.valueAt(keySetIndex);
            serializer.startTag(null, "keyset");
            serializer.attribute(null, "identifier", Long.toString(id));
            for (long keyId : keys) {
                serializer.startTag(null, "key-id");
                serializer.attribute(null, "identifier", Long.toString(keyId));
                serializer.endTag(null, "key-id");
            }
            serializer.endTag(null, "keyset");
        }
        serializer.endTag(null, "keysets");
    }

    void readKeySetsLPw(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int type;
        long currentKeySetId = 0;
        int outerDepth = parser.getDepth();
        String recordedVersion = parser.getAttributeValue(null, "version");
        if (recordedVersion == null || Integer.parseInt(recordedVersion) != CURRENT_VERSION) {
            while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                    && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
                // Our version is different than the one which generated the old keyset data.
                // We don't want any of the old data, but we must advance the parser
                continue;
            }
            // The KeySet information read previously from packages.xml is invalid.
            // Destroy it all.
            for (PackageSetting p : mPackages.values()) {
                clearPackageKeySetDataLocked(p);
            }
            return;
        }
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            final String tagName = parser.getName();
            if (tagName.equals("keys")) {
                readKeysLPw(parser);
            } else if (tagName.equals("keysets")) {
                readKeySetListLPw(parser);
            } else if (tagName.equals("lastIssuedKeyId")) {
                lastIssuedKeyId = Long.parseLong(parser.getAttributeValue(null, "value"));
            } else if (tagName.equals("lastIssuedKeySetId")) {
                lastIssuedKeySetId = Long.parseLong(parser.getAttributeValue(null, "value"));
            }
        }
    }

    void readKeysLPw(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            final String tagName = parser.getName();
            if (tagName.equals("public-key")) {
                readPublicKeyLPw(parser);
            }
        }
    }

    void readKeySetListLPw(XmlPullParser parser)
            throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        long currentKeySetId = 0;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            final String tagName = parser.getName();
            if (tagName.equals("keyset")) {
                currentKeySetId = readIdentifierLPw(parser);
                mKeySets.put(currentKeySetId, new KeySet(new Binder()));
                mKeySetMapping.put(currentKeySetId, new ArraySet<Long>());
            } else if (tagName.equals("key-id")) {
                long id = readIdentifierLPw(parser);
                mKeySetMapping.get(currentKeySetId).add(id);
            }
        }
    }

    long readIdentifierLPw(XmlPullParser parser)
            throws XmlPullParserException {
        return Long.parseLong(parser.getAttributeValue(null, "identifier"));
    }

    void readPublicKeyLPw(XmlPullParser parser)
            throws XmlPullParserException {
        String encodedID = parser.getAttributeValue(null, "identifier");
        long identifier = Long.parseLong(encodedID);
        String encodedPublicKey = parser.getAttributeValue(null, "value");
        PublicKey pub = PackageParser.parsePublicKey(encodedPublicKey);
        if (pub != null) {
            mPublicKeys.put(identifier, pub);
        }
    }
}
