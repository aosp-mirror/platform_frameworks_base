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

package android.content.pm;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.PackageUtils;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DataClass;

import libcore.util.HexEncoding;

import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A container for signing-related data of an application package.
 *
 * @hide
 */
@DataClass(genConstructor = false, genConstDefs = false, genParcelable = true, genAidl = false)
public final class SigningDetails implements Parcelable {

    private static final String TAG = "SigningDetails";

    @IntDef({SignatureSchemeVersion.UNKNOWN,
            SignatureSchemeVersion.JAR,
            SignatureSchemeVersion.SIGNING_BLOCK_V2,
            SignatureSchemeVersion.SIGNING_BLOCK_V3,
            SignatureSchemeVersion.SIGNING_BLOCK_V4})
    public @interface SignatureSchemeVersion {
        int UNKNOWN = 0;
        int JAR = 1;
        int SIGNING_BLOCK_V2 = 2;
        int SIGNING_BLOCK_V3 = 3;
        int SIGNING_BLOCK_V4 = 4;
    }

    /** The signing certificates associated with this application package. */
    private final @Nullable Signature[] mSignatures;

    /** The signature scheme version for this application package. */
    private final @SignatureSchemeVersion int mSignatureSchemeVersion;

    /** The public keys set for the certificates. */
    private final @Nullable ArraySet<PublicKey> mPublicKeys;

    /**
     * APK Signature Scheme v3 includes support for adding a proof-of-rotation record that
     * contains two pieces of information:
     *   1) the past signing certificates
     *   2) the flags that APK wants to assign to each of the past signing certificates.
     *
     * This collection of {@code Signature} objects, each of which is formed from a former
     * signing certificate of this APK before it was changed by signing certificate rotation,
     * represents the first piece of information.  It is the APK saying to the rest of the
     * world: "hey if you trust the old cert, you can trust me!"  This is useful, if for
     * instance, the platform would like to determine whether or not to allow this APK to do
     * something it would've allowed it to do under the old cert (like upgrade).
     */
    private final @Nullable Signature[] mPastSigningCertificates;

    /** special value used to see if cert is in package - not exposed to callers */
    private static final int PAST_CERT_EXISTS = 0;

    @IntDef(flag = true,
            value = {CertCapabilities.INSTALLED_DATA,
                    CertCapabilities.SHARED_USER_ID,
                    CertCapabilities.PERMISSION,
                    CertCapabilities.ROLLBACK})
    public @interface CertCapabilities {

        /** accept data from already installed pkg with this cert */
        int INSTALLED_DATA = 1;

        /** accept sharedUserId with pkg with this cert */
        int SHARED_USER_ID = 2;

        /** grant SIGNATURE permissions to pkgs with this cert */
        int PERMISSION = 4;

        /** allow pkg to update to one signed by this certificate */
        int ROLLBACK = 8;

        /** allow pkg to continue to have auth access gated by this cert */
        int AUTH = 16;
    }

    @IntDef(value = {CapabilityMergeRule.MERGE_SELF_CAPABILITY,
                    CapabilityMergeRule.MERGE_OTHER_CAPABILITY,
                    CapabilityMergeRule.MERGE_RESTRICTED_CAPABILITY})
    public @interface CapabilityMergeRule {
        /**
         * When capabilities are different for a common signer in the lineage, use the capabilities
         * from this instance.
         */
        int MERGE_SELF_CAPABILITY = 0;

        /**
         * When capabilities are different for a common signer in the lineage, use the capabilites
         * from the other instance.
         */
        int MERGE_OTHER_CAPABILITY = 1;

        /**
         * When capabilities are different for a common signer in the lineage, use the most
         * restrictive between the two signers.
         */
        int MERGE_RESTRICTED_CAPABILITY = 2;
    }

    /** A representation of unknown signing details. Use instead of null. */
    public static final SigningDetails UNKNOWN = new SigningDetails(/* signatures */ null,
            SignatureSchemeVersion.UNKNOWN, /* keys */ null, /* pastSigningCertificates */ null);

    @VisibleForTesting
    public SigningDetails(@Nullable Signature[] signatures,
            @SignatureSchemeVersion int signatureSchemeVersion,
            @Nullable ArraySet<PublicKey> keys, @Nullable Signature[] pastSigningCertificates) {
        mSignatures = signatures;
        mSignatureSchemeVersion = signatureSchemeVersion;
        mPublicKeys = keys;
        mPastSigningCertificates = pastSigningCertificates;
    }

    public SigningDetails(@Nullable Signature[] signatures,
            @SignatureSchemeVersion int signatureSchemeVersion,
            @Nullable Signature[] pastSigningCertificates)
            throws CertificateException {
        this(signatures, signatureSchemeVersion, toSigningKeys(signatures),
                pastSigningCertificates);
    }

    public SigningDetails(@Nullable Signature[] signatures,
            @SignatureSchemeVersion int signatureSchemeVersion)
            throws CertificateException {
        this(signatures, signatureSchemeVersion, /* pastSigningCertificates */ null);
    }

    public SigningDetails(@Nullable SigningDetails orig) {
        if (orig != null) {
            if (orig.mSignatures != null) {
                mSignatures = orig.mSignatures.clone();
            } else {
                mSignatures = null;
            }
            mSignatureSchemeVersion = orig.mSignatureSchemeVersion;
            mPublicKeys = new ArraySet<>(orig.mPublicKeys);
            if (orig.mPastSigningCertificates != null) {
                mPastSigningCertificates = orig.mPastSigningCertificates.clone();
            } else {
                mPastSigningCertificates = null;
            }
        } else {
            mSignatures = null;
            mSignatureSchemeVersion = SignatureSchemeVersion.UNKNOWN;
            mPublicKeys = null;
            mPastSigningCertificates = null;
        }
    }

    /**
     * Merges the signing lineage of this instance with the lineage in the provided {@code
     * otherSigningDetails} using {@link CapabilityMergeRule#MERGE_OTHER_CAPABILITY} as the merge
     * rule.
     *
     * @param otherSigningDetails the {@code SigningDetails} with which to merge
     * @return Merged {@code SigningDetails} instance when one has the same or an ancestor signer
     *         of the other. If neither instance has a lineage, or if neither has the same or an
     *         ancestor signer then this instance is returned.
     * @see #mergeLineageWith(SigningDetails, int)
     */
    public @NonNull SigningDetails mergeLineageWith(@NonNull SigningDetails otherSigningDetails) {
        return mergeLineageWith(otherSigningDetails, CapabilityMergeRule.MERGE_OTHER_CAPABILITY);
    }

    /**
     * Merges the signing lineage of this instance with the lineage in the provided {@code
     * otherSigningDetails} when one has the same or an ancestor signer of the other using the
     * provided {@code mergeRule} to handle differences in capabilities for shared signers.
     *
     * <p>Merging two signing lineages will result in a new {@code SigningDetails} instance
     * containing the longest common lineage with differences in capabilities for shared signers
     * resolved using the provided {@code mergeRule}. If the two lineages contain the same signers
     * with the same capabilities then the instance on which this was invoked is returned without
     * any changes. Similarly if neither instance has a lineage, or if neither has the same or an
     * ancestor signer then this instance is returned.
     *
     * Following are some example results of this method for lineages with signers A, B, C, D:
     * <ul>
     *     <li>lineage B merged with lineage A -> B returns lineage A -> B.
     *     <li>lineage A -> B merged with lineage B -> C returns lineage A -> B -> C
     *     <li>lineage A -> B with the {@code PERMISSION} capability revoked for A merged with
     *     lineage A -> B with the {@code SHARED_USER_ID} capability revoked for A returns the
     *     following based on the {@code mergeRule}:
     *     <ul>
     *         <li>{@code MERGE_SELF_CAPABILITY} - lineage A -> B with {@code PERMISSION} revoked
     *         for A.
     *         <li>{@code MERGE_OTHER_CAPABILITY} - lineage A -> B with {@code SHARED_USER_ID}
     *         revoked for A.
     *         <li>{@code MERGE_RESTRICTED_CAPABILITY} - lineage A -> B with {@code PERMISSION} and
     *         {@code SHARED_USER_ID} revoked for A.
     *     </ul>
     *     <li>lineage A -> B -> C merged with lineage A -> B -> D would return the original lineage
     *     A -> B -> C since the current signer of both instances is not the same or in the
     *     lineage of the other.
     * </ul>
     *
     * @param otherSigningDetails the {@code SigningDetails} with which to merge
     * @param mergeRule the {@link CapabilityMergeRule} to use when resolving differences in
     *                  capabilities for shared signers
     * @return Merged {@code SigningDetails} instance when one has the same or an ancestor signer
     *         of the other. If neither instance has a lineage, or if neither has the same or an
     *         ancestor signer then this instance is returned.
     */
    public @NonNull SigningDetails mergeLineageWith(@NonNull SigningDetails otherSigningDetails,
            @CapabilityMergeRule int mergeRule) {
        if (!hasPastSigningCertificates()) {
            return otherSigningDetails.hasPastSigningCertificates()
                    && otherSigningDetails.hasAncestorOrSelf(this) ? otherSigningDetails : this;
        }
        if (!otherSigningDetails.hasPastSigningCertificates()) {
            return this;
        }
        // Use the utility method to determine which SigningDetails instance is the descendant
        // and to confirm that the signing lineage does not diverge.
        SigningDetails descendantSigningDetails = getDescendantOrSelf(otherSigningDetails);
        if (descendantSigningDetails == null) {
            return this;
        }
        SigningDetails mergedDetails = this;
        if (descendantSigningDetails == this) {
            // If this instance is the descendant then the merge will also be invoked against this
            // instance and the provided mergeRule can be used as is.
            mergedDetails = mergeLineageWithAncestorOrSelf(otherSigningDetails, mergeRule);
        } else {
            // If the provided instance is the descendant then the merge will be invoked against the
            // other instance and a self or other merge rule will need to be flipped.
            switch (mergeRule) {
                case CapabilityMergeRule.MERGE_SELF_CAPABILITY:
                    mergedDetails = otherSigningDetails.mergeLineageWithAncestorOrSelf(this,
                            CapabilityMergeRule.MERGE_OTHER_CAPABILITY);
                    break;
                case CapabilityMergeRule.MERGE_OTHER_CAPABILITY:
                    mergedDetails = otherSigningDetails.mergeLineageWithAncestorOrSelf(this,
                            CapabilityMergeRule.MERGE_SELF_CAPABILITY);
                    break;
                case CapabilityMergeRule.MERGE_RESTRICTED_CAPABILITY:
                    mergedDetails = otherSigningDetails.mergeLineageWithAncestorOrSelf(this,
                            mergeRule);
                    break;
            }
        }
        return mergedDetails;
    }

    /**
     * Merges the signing lineage of this instance with the lineage of the ancestor (or same)
     * signer in the provided {@code otherSigningDetails}.
     *
     * @param otherSigningDetails the {@code SigningDetails} with which to merge
     * @param mergeRule the {@link CapabilityMergeRule} to use when resolving differences in
     *                  capabilities for shared signers
     * @return Merged {@code SigningDetails} instance.
     */
    private @NonNull SigningDetails mergeLineageWithAncestorOrSelf(
            @NonNull SigningDetails otherSigningDetails, @CapabilityMergeRule int mergeRule) {
        // This method should only be called with instances that contain lineages.
        int index = mPastSigningCertificates.length - 1;
        int otherIndex = otherSigningDetails.mPastSigningCertificates.length - 1;
        if (index < 0 || otherIndex < 0) {
            return this;
        }

        List<Signature> mergedSignatures = new ArrayList<>();
        boolean capabilitiesModified = false;
        // If this is a descendant lineage then add all of the descendant signer(s) to the
        // merged lineage until the ancestor signer is reached.
        while (index >= 0 && !mPastSigningCertificates[index].equals(
                otherSigningDetails.mPastSigningCertificates[otherIndex])) {
            mergedSignatures.add(new Signature(mPastSigningCertificates[index--]));
        }
        // If the signing lineage was exhausted then the provided ancestor is not actually an
        // ancestor of this lineage.
        if (index < 0) {
            return this;
        }

        do {
            // Add the common signer to the merged lineage and resolve any differences in
            // capabilites with the merge rule.
            Signature signature = mPastSigningCertificates[index--];
            Signature ancestorSignature =
                    otherSigningDetails.mPastSigningCertificates[otherIndex--];
            Signature mergedSignature = new Signature(signature);
            if (signature.getFlags() != ancestorSignature.getFlags()) {
                capabilitiesModified = true;
                switch (mergeRule) {
                    case CapabilityMergeRule.MERGE_SELF_CAPABILITY:
                        mergedSignature.setFlags(signature.getFlags());
                        break;
                    case CapabilityMergeRule.MERGE_OTHER_CAPABILITY:
                        mergedSignature.setFlags(ancestorSignature.getFlags());
                        break;
                    case CapabilityMergeRule.MERGE_RESTRICTED_CAPABILITY:
                        mergedSignature.setFlags(
                                signature.getFlags() & ancestorSignature.getFlags());
                        break;
                }
            }
            mergedSignatures.add(mergedSignature);
        } while (index >= 0 && otherIndex >= 0 && mPastSigningCertificates[index].equals(
                otherSigningDetails.mPastSigningCertificates[otherIndex]));

        // If both lineages still have elements then their lineages have diverged; since this is
        // not supported return the invoking instance.
        if (index >= 0 && otherIndex >= 0) {
            return this;
        }

        // Add any remaining elements from either lineage that is not yet exhausted to the
        // the merged lineage.
        while (otherIndex >= 0) {
            mergedSignatures.add(new Signature(
                    otherSigningDetails.mPastSigningCertificates[otherIndex--]));
        }
        while (index >= 0) {
            mergedSignatures.add(new Signature(mPastSigningCertificates[index--]));
        }

        // if this lineage already contains all the elements in the ancestor and none of the
        // capabilities were changed then just return this instance.
        if (mergedSignatures.size() == mPastSigningCertificates.length
                && !capabilitiesModified) {
            return this;
        }
        // Since the signatures were added to the merged lineage from newest to oldest reverse
        // the list to ensure the oldest signer is at index 0.
        Collections.reverse(mergedSignatures);
        try {
            return new SigningDetails(new Signature[]{new Signature(mSignatures[0])},
                    mSignatureSchemeVersion, mergedSignatures.toArray(new Signature[0]));
        } catch (CertificateException e) {
            Slog.e(TAG, "Caught an exception creating the merged lineage: ", e);
            return this;
        }
    }

    /**
     * Returns whether this and the provided {@code otherSigningDetails} share a common
     * ancestor.
     *
     * <p>The two SigningDetails have a common ancestor if any of the following conditions are
     * met:
     * - If neither has a lineage and their current signer(s) are equal.
     * - If only one has a lineage and the signer of the other is the same or in the lineage.
     * - If both have a lineage and their current signers are the same or one is in the lineage
     * of the other, and their lineages do not diverge to different signers.
     */
    public boolean hasCommonAncestor(@NonNull SigningDetails otherSigningDetails) {
        if (!hasPastSigningCertificates()) {
            // If this instance does not have a lineage then it must either be in the ancestry
            // of or the same signer of the otherSigningDetails.
            return otherSigningDetails.hasAncestorOrSelf(this);
        }
        if (!otherSigningDetails.hasPastSigningCertificates()) {
            return hasAncestorOrSelf(otherSigningDetails);
        }
        // If both have a lineage then use getDescendantOrSelf to obtain the descendant signing
        // details; a null return from that method indicates there is no common lineage between
        // the two or that they diverge at a point in the lineage.
        return getDescendantOrSelf(otherSigningDetails) != null;
    }

    /**
     * Returns whether this instance is currently signed, or has ever been signed, with a
     * signing certificate from the provided {@link Set} of {@code certDigests}.
     *
     * <p>The provided {@code certDigests} should contain the SHA-256 digest of the DER encoding
     * of each trusted certificate with the digest characters in upper case. If this instance
     * has multiple signers then all signers must be in the provided {@code Set}. If this
     * instance has a signing lineage then this method will return true if any of the previous
     * signers in the lineage match one of the entries in the {@code Set}.
     */
    public boolean hasAncestorOrSelfWithDigest(@Nullable Set<String> certDigests) {
        if (this == UNKNOWN || certDigests == null || certDigests.size() == 0) {
            return false;
        }
        // If an app is signed by multiple signers then all of the signers must be in the Set.
        if (mSignatures.length > 1) {
            // If the Set has less elements than the number of signatures then immediately
            // return false as there's no way to satisfy the requirement of all signatures being
            // in the Set.
            if (certDigests.size() < mSignatures.length) {
                return false;
            }
            for (Signature signature : mSignatures) {
                String signatureDigest = PackageUtils.computeSha256Digest(
                        signature.toByteArray());
                if (!certDigests.contains(signatureDigest)) {
                    return false;
                }
            }
            return true;
        }

        String signatureDigest = PackageUtils.computeSha256Digest(mSignatures[0].toByteArray());
        if (certDigests.contains(signatureDigest)) {
            return true;
        }
        if (hasPastSigningCertificates()) {
            // The last element in the pastSigningCertificates array is the current signer;
            // since that was verified above just check all the signers in the lineage.
            for (int i = 0; i < mPastSigningCertificates.length - 1; i++) {
                signatureDigest = PackageUtils.computeSha256Digest(
                        mPastSigningCertificates[i].toByteArray());
                if (certDigests.contains(signatureDigest)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the SigningDetails with a descendant (or same) signer after verifying the
     * descendant has the same, a superset, or a subset of the lineage of the ancestor.
     *
     * <p>If this instance and the provided {@code otherSigningDetails} do not share an
     * ancestry, or if their lineages diverge then null is returned to indicate there is no
     * valid descendant SigningDetails.
     */
    private @Nullable SigningDetails getDescendantOrSelf(
            @NonNull SigningDetails otherSigningDetails) {
        final SigningDetails descendantSigningDetails;
        final SigningDetails ancestorSigningDetails;
        if (hasAncestorOrSelf(otherSigningDetails)) {
            // If the otherSigningDetails has the same signer or a signer in the lineage of this
            // instance then treat this instance as the descendant.
            descendantSigningDetails = this;
            ancestorSigningDetails = otherSigningDetails;
        } else if (otherSigningDetails.hasAncestor(this)) {
            // The above check confirmed that the two instances do not have the same signer and
            // the signer of otherSigningDetails is not in this instance's lineage; if this
            // signer is in the otherSigningDetails lineage then treat this as the ancestor.
            descendantSigningDetails = otherSigningDetails;
            ancestorSigningDetails = this;
        } else {
            // The signers are not the same and neither has the current signer of the other in
            // its lineage; return null to indicate there is no descendant signer.
            return null;
        }
        // Once the descent (or same) signer is identified iterate through the ancestry until
        // the current signer of the ancestor is found.
        int descendantIndex = descendantSigningDetails.mPastSigningCertificates.length - 1;
        int ancestorIndex = ancestorSigningDetails.mPastSigningCertificates.length - 1;
        while (descendantIndex >= 0
                && !descendantSigningDetails.mPastSigningCertificates[descendantIndex].equals(
                ancestorSigningDetails.mPastSigningCertificates[ancestorIndex])) {
            descendantIndex--;
        }
        // Since the ancestry was verified above the descendant lineage should never be
        // exhausted, but if for some reason the ancestor signer is not found then return null.
        if (descendantIndex < 0) {
            return null;
        }
        // Once the common ancestor (or same) signer is found iterate over the lineage of both
        // to ensure that they are either the same or one is a subset of the other.
        do {
            descendantIndex--;
            ancestorIndex--;
        } while (descendantIndex >= 0 && ancestorIndex >= 0
                && descendantSigningDetails.mPastSigningCertificates[descendantIndex].equals(
                ancestorSigningDetails.mPastSigningCertificates[ancestorIndex]));

        // If both lineages still have elements then they diverge and cannot be considered a
        // valid common lineage.
        if (descendantIndex >= 0 && ancestorIndex >= 0) {
            return null;
        }
        // Since one or both of the lineages was exhausted they are either the same or one is a
        // subset of the other; return the valid descendant.
        return descendantSigningDetails;
    }

    /** Returns true if the signing details have one or more signatures. */
    public boolean hasSignatures() {
        return mSignatures != null && mSignatures.length > 0;
    }

    /** Returns true if the signing details have past signing certificates. */
    public boolean hasPastSigningCertificates() {
        return mPastSigningCertificates != null && mPastSigningCertificates.length > 0;
    }

    /**
     * Determines if the provided {@code oldDetails} is an ancestor of or the same as this one.
     * If the {@code oldDetails} signing certificate appears in our pastSigningCertificates,
     * then that means it has authorized a signing certificate rotation, which eventually leads
     * to our certificate, and thus can be trusted. If this method evaluates to true, this
     * SigningDetails object should be trusted if the previous one is.
     */
    public boolean hasAncestorOrSelf(@NonNull SigningDetails oldDetails) {
        if (this == UNKNOWN || oldDetails == UNKNOWN) {
            return false;
        }
        if (oldDetails.mSignatures.length > 1) {
            // multiple-signer packages cannot rotate signing certs, so we just compare current
            // signers for an exact match
            return signaturesMatchExactly(oldDetails);
        } else {
            // we may have signing certificate rotation history, check to see if the oldDetails
            // was one of our old signing certificates
            return hasCertificate(oldDetails.mSignatures[0]);
        }
    }

    /**
     * Similar to {@code hasAncestorOrSelf}. Returns true only if this {@code SigningDetails}
     * is a descendant of {@code oldDetails}, not if they're the same. This is used to
     * determine if this object is newer than the provided one.
     */
    public boolean hasAncestor(@NonNull SigningDetails oldDetails) {
        if (this == UNKNOWN || oldDetails == UNKNOWN) {
            return false;
        }
        if (hasPastSigningCertificates() && oldDetails.mSignatures.length == 1) {
            // the last entry in pastSigningCertificates is the current signer, ignore it
            for (int i = 0; i < mPastSigningCertificates.length - 1; i++) {
                if (mPastSigningCertificates[i].equals(oldDetails.mSignatures[0])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns whether this {@code SigningDetails} has a signer in common with the provided
     * {@code otherDetails} with the specified {@code flags} capabilities provided by this
     * signer.
     *
     * <p>Note this method allows for the signing lineage to diverge, so this should only be
     * used for instances where the only requirement is a common signer in the lineage with
     * the specified capabilities. If the current signer of this instance is an ancestor of
     * {@code otherDetails} then {@code true} is immediately returned since the current signer
     * has all capabilities granted.
     */
    public boolean hasCommonSignerWithCapability(@NonNull SigningDetails otherDetails,
            @CertCapabilities int flags) {
        if (this == UNKNOWN || otherDetails == UNKNOWN) {
            return false;
        }
        // If either is signed with more than one signer then both must be signed by the same
        // signers to consider the capabilities granted.
        if (mSignatures.length > 1 || otherDetails.mSignatures.length > 1) {
            return signaturesMatchExactly(otherDetails);
        }
        // The Signature class does not use the granted capabilities in the hashCode
        // computation, so a Set can be used to check for a common signer.
        Set<Signature> otherSignatures = new ArraySet<>();
        if (otherDetails.hasPastSigningCertificates()) {
            otherSignatures.addAll(Arrays.asList(otherDetails.mPastSigningCertificates));
        } else {
            otherSignatures.addAll(Arrays.asList(otherDetails.mSignatures));
        }
        // If the current signer of this instance is an ancestor of the other than return true
        // since all capabilities are granted to the current signer.
        if (otherSignatures.contains(mSignatures[0])) {
            return true;
        }
        if (hasPastSigningCertificates()) {
            // Since the current signer was checked above and the last signature in the
            // pastSigningCertificates is the current signer skip checking the last element.
            for (int i = 0; i < mPastSigningCertificates.length - 1; i++) {
                if (otherSignatures.contains(mPastSigningCertificates[i])) {
                    // If the caller specified multiple capabilities ensure all are set.
                    if ((mPastSigningCertificates[i].getFlags() & flags) == flags) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Determines if the provided {@code oldDetails} is an ancestor of this one, and whether or
     * not this one grants it the provided capability, represented by the {@code flags}
     * parameter.  In the event of signing certificate rotation, a package may still interact
     * with entities signed by its old signing certificate and not want to break previously
     * functioning behavior.  The {@code flags} value determines which capabilities the app
     * signed by the newer signing certificate would like to continue to give to its previous
     * signing certificate(s).
     */
    public boolean checkCapability(@NonNull SigningDetails oldDetails,
            @CertCapabilities int flags) {
        if (this == UNKNOWN || oldDetails == UNKNOWN) {
            return false;
        }
        if (oldDetails.mSignatures.length > 1) {
            // multiple-signer packages cannot rotate signing certs, so we must have an exact
            // match, which also means all capabilities are granted
            return signaturesMatchExactly(oldDetails);
        } else {
            // we may have signing certificate rotation history, check to see if the oldDetails
            // was one of our old signing certificates, and if we grant it the capability it's
            // requesting
            return hasCertificate(oldDetails.mSignatures[0], flags);
        }
    }

    /**
     * A special case of {@code checkCapability} which re-encodes both sets of signing
     * certificates to counteract a previous re-encoding.
     */
    public boolean checkCapabilityRecover(@NonNull SigningDetails oldDetails,
            @CertCapabilities int flags) throws CertificateException {
        if (oldDetails == UNKNOWN || this == UNKNOWN) {
            return false;
        }
        if (hasPastSigningCertificates() && oldDetails.mSignatures.length == 1) {
            // signing certificates may have rotated, check entire history for effective match
            for (int i = 0; i < mPastSigningCertificates.length; i++) {
                if (Signature.areEffectiveMatch(
                        oldDetails.mSignatures[0],
                        mPastSigningCertificates[i])
                        && mPastSigningCertificates[i].getFlags() == flags) {
                    return true;
                }
            }
        } else {
            return Signature.areEffectiveMatch(oldDetails.mSignatures, mSignatures);
        }
        return false;
    }

    /**
     * Determine if {@code signature} is in this SigningDetails' signing certificate history,
     * including the current signer.  Automatically returns false if this object has multiple
     * signing certificates, since rotation is only supported for single-signers; this is
     * enforced by {@code hasCertificateInternal}.
     */
    public boolean hasCertificate(@NonNull Signature signature) {
        return hasCertificateInternal(signature, PAST_CERT_EXISTS);
    }

    /**
     * Determine if {@code signature} is in this SigningDetails' signing certificate history,
     * including the current signer, and whether or not it has the given permission.
     * Certificates which match our current signer automatically get all capabilities.
     * Automatically returns false if this object has multiple signing certificates, since
     * rotation is only supported for single-signers.
     */
    public boolean hasCertificate(@NonNull Signature signature, @CertCapabilities int flags) {
        return hasCertificateInternal(signature, flags);
    }

    /** Convenient wrapper for calling {@code hasCertificate} with certificate's raw bytes. */
    public boolean hasCertificate(byte[] certificate) {
        Signature signature = new Signature(certificate);
        return hasCertificate(signature);
    }

    private boolean hasCertificateInternal(@NonNull Signature signature, int flags) {
        if (this == UNKNOWN) {
            return false;
        }

        // only single-signed apps can have pastSigningCertificates
        if (hasPastSigningCertificates()) {
            // check all past certs, except for the current one, which automatically gets all
            // capabilities, since it is the same as the current signature
            for (int i = 0; i < mPastSigningCertificates.length - 1; i++) {
                if (mPastSigningCertificates[i].equals(signature)) {
                    if (flags == PAST_CERT_EXISTS
                            || (flags & mPastSigningCertificates[i].getFlags()) == flags) {
                        return true;
                    }
                }
            }
        }

        // not in previous certs signing history, just check the current signer and make sure
        // we are singly-signed
        return mSignatures.length == 1 && mSignatures[0].equals(signature);
    }

    /**
     * Determines if the provided {@code sha256String} is an ancestor of this one, and whether
     * or not this one grants it the provided capability, represented by the {@code flags}
     * parameter.  In the event of signing certificate rotation, a package may still interact
     * with entities signed by its old signing certificate and not want to break previously
     * functioning behavior.  The {@code flags} value determines which capabilities the app
     * signed by the newer signing certificate would like to continue to give to its previous
     * signing certificate(s).
     *
     * @param sha256String A hex-encoded representation of a sha256 digest. In the case of an
     *                     app with multiple signers, this represents the hex-encoded sha256
     *                     digest of the combined hex-encoded sha256 digests of each individual
     *                     signing certificate according to {@link
     *                     PackageUtils#computeSignaturesSha256Digest(Signature[])}
     */
    public boolean checkCapability(@Nullable String sha256String, @CertCapabilities int flags) {
        if (this == UNKNOWN || TextUtils.isEmpty(sha256String)) {
            return false;
        }

        // first see if the hash represents a single-signer in our signing history
        final byte[] sha256Bytes = HexEncoding.decode(sha256String, false /* allowSingleChar */);
        if (hasSha256Certificate(sha256Bytes, flags)) {
            return true;
        }

        // Not in signing history, either represents multiple signatures or not a match.
        // Multiple signers can't rotate, so no need to check flags, just see if the SHAs match.
        // We already check the single-signer case above as part of hasSha256Certificate, so no
        // need to verify we have multiple signers, just run the old check
        // just consider current signing certs
        final String[] mSignaturesSha256Digests =
                PackageUtils.computeSignaturesSha256Digests(mSignatures);
        final String mSignaturesSha256Digest =
                PackageUtils.computeSignaturesSha256Digest(mSignaturesSha256Digests);
        return mSignaturesSha256Digest.equals(sha256String);
    }

    /**
     * Determine if the {@code sha256Certificate} is in this SigningDetails' signing certificate
     * history, including the current signer.  Automatically returns false if this object has
     * multiple signing certificates, since rotation is only supported for single-signers.
     */
    public boolean hasSha256Certificate(byte[] sha256Certificate) {
        return hasSha256CertificateInternal(sha256Certificate, PAST_CERT_EXISTS);
    }

    /**
     * Determine if the {@code sha256Certificate} certificate hash corresponds to a signing
     * certificate in this SigningDetails' signing certificate history, including the current
     * signer, and whether or not it has the given permission.  Certificates which match our
     * current signer automatically get all capabilities. Automatically returns false if this
     * object has multiple signing certificates, since rotation is only supported for
     * single-signers.
     */
    public boolean hasSha256Certificate(byte[] sha256Certificate, @CertCapabilities int flags) {
        return hasSha256CertificateInternal(sha256Certificate, flags);
    }

    private boolean hasSha256CertificateInternal(byte[] sha256Certificate, int flags) {
        if (this == UNKNOWN) {
            return false;
        }
        if (hasPastSigningCertificates()) {
            // check all past certs, except for the last one, which automatically gets all
            // capabilities, since it is the same as the current signature, and is checked below
            for (int i = 0; i < mPastSigningCertificates.length - 1; i++) {
                byte[] digest = PackageUtils.computeSha256DigestBytes(
                        mPastSigningCertificates[i].toByteArray());
                if (Arrays.equals(sha256Certificate, digest)) {
                    if (flags == PAST_CERT_EXISTS
                            || (flags & mPastSigningCertificates[i].getFlags()) == flags) {
                        return true;
                    }
                }
            }
        }

        // not in previous certs signing history, just check the current signer
        if (mSignatures.length == 1) {
            byte[] digest = PackageUtils.computeSha256DigestBytes(mSignatures[0].toByteArray());
            return Arrays.equals(sha256Certificate, digest);
        }
        return false;
    }

    /** Returns true if the signatures in this and other match exactly. */
    public boolean signaturesMatchExactly(@NonNull SigningDetails other) {
        return Signature.areExactMatch(mSignatures, other.mSignatures);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        boolean isUnknown = UNKNOWN == this;
        dest.writeBoolean(isUnknown);
        if (isUnknown) {
            return;
        }
        dest.writeTypedArray(mSignatures, flags);
        dest.writeInt(mSignatureSchemeVersion);
        dest.writeArraySet(mPublicKeys);
        dest.writeTypedArray(mPastSigningCertificates, flags);
    }

    protected SigningDetails(@NonNull Parcel in) {
        final ClassLoader boot = Object.class.getClassLoader();
        mSignatures = in.createTypedArray(Signature.CREATOR);
        mSignatureSchemeVersion = in.readInt();
        mPublicKeys = (ArraySet<PublicKey>) in.readArraySet(boot);
        mPastSigningCertificates = in.createTypedArray(Signature.CREATOR);
    }

    public static final @NonNull Parcelable.Creator<SigningDetails> CREATOR =
            new Creator<SigningDetails>() {
                @Override
                public SigningDetails createFromParcel(@NonNull Parcel source) {
                    if (source.readBoolean()) {
                        return UNKNOWN;
                    }
                    return new SigningDetails(source);
                }

                @Override
                public SigningDetails[] newArray(int size) {
                    return new SigningDetails[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) return true;
        if (!(o instanceof SigningDetails)) return false;

        final SigningDetails that = (SigningDetails) o;

        if (mSignatureSchemeVersion != that.mSignatureSchemeVersion) return false;
        if (!Signature.areExactMatch(mSignatures, that.mSignatures)) return false;
        if (mPublicKeys != null) {
            if (!mPublicKeys.equals((that.mPublicKeys))) {
                return false;
            }
        } else if (that.mPublicKeys != null) {
            return false;
        }

        // can't use Signature.areExactMatch() because order matters with the past signing certs
        if (!Arrays.equals(mPastSigningCertificates, that.mPastSigningCertificates)) {
            return false;
        }
        // The capabilities for the past signing certs must match as well.
        for (int i = 0; i < mPastSigningCertificates.length; i++) {
            if (mPastSigningCertificates[i].getFlags()
                    != that.mPastSigningCertificates[i].getFlags()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = +Arrays.hashCode(mSignatures);
        result = 31 * result + mSignatureSchemeVersion;
        result = 31 * result + (mPublicKeys != null ? mPublicKeys.hashCode() : 0);
        result = 31 * result + Arrays.hashCode(mPastSigningCertificates);
        return result;
    }

    /**
     * Builder of {@code SigningDetails} instances.
     */
    public static class Builder {
        private @NonNull Signature[] mSignatures;
        private @SignatureSchemeVersion int mSignatureSchemeVersion =
                SignatureSchemeVersion.UNKNOWN;
        private @Nullable Signature[] mPastSigningCertificates;

        public Builder() {
        }

        /** get signing certificates used to sign the current APK */
        public SigningDetails.Builder setSignatures(@NonNull Signature[] signatures) {
            mSignatures = signatures;
            return this;
        }

        /** set the signature scheme version used to sign the APK */
        public SigningDetails.Builder setSignatureSchemeVersion(
                @SignatureSchemeVersion int signatureSchemeVersion) {
            mSignatureSchemeVersion = signatureSchemeVersion;
            return this;
        }

        /** set the signing certificates by which the APK proved it can be authenticated */
        public SigningDetails.Builder setPastSigningCertificates(
                @Nullable Signature[] pastSigningCertificates) {
            mPastSigningCertificates = pastSigningCertificates;
            return this;
        }

        private void checkInvariants() {
            // must have signatures and scheme version set
            if (mSignatures == null) {
                throw new IllegalStateException("SigningDetails requires the current signing"
                        + " certificates.");
            }
        }
        /** build a {@code SigningDetails} object */
        public SigningDetails build()
                throws CertificateException {
            checkInvariants();
            return new SigningDetails(mSignatures, mSignatureSchemeVersion,
                    mPastSigningCertificates);
        }
    }

    /** Parses the public keys from the set of signatures. */
    public static ArraySet<PublicKey> toSigningKeys(@NonNull Signature[] signatures)
            throws CertificateException {
        final ArraySet<PublicKey> keys = new ArraySet<>(signatures.length);
        for (int i = 0; i < signatures.length; i++) {
            keys.add(signatures[i].getPublicKey());
        }
        return keys;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/SigningDetails.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * The signing certificates associated with this application package.
     */
    @DataClass.Generated.Member
    public @Nullable Signature[] getSignatures() {
        return mSignatures;
    }

    /**
     * The signature scheme version for this application package.
     */
    @DataClass.Generated.Member
    public @SignatureSchemeVersion int getSignatureSchemeVersion() {
        return mSignatureSchemeVersion;
    }

    /**
     * The public keys set for the certificates.
     */
    @DataClass.Generated.Member
    public @Nullable ArraySet<PublicKey> getPublicKeys() {
        return mPublicKeys;
    }

    /**
     * APK Signature Scheme v3 includes support for adding a proof-of-rotation record that
     * contains two pieces of information:
     *   1) the past signing certificates
     *   2) the flags that APK wants to assign to each of the past signing certificates.
     *
     * This collection of {@code Signature} objects, each of which is formed from a former
     * signing certificate of this APK before it was changed by signing certificate rotation,
     * represents the first piece of information.  It is the APK saying to the rest of the
     * world: "hey if you trust the old cert, you can trust me!"  This is useful, if for
     * instance, the platform would like to determine whether or not to allow this APK to do
     * something it would've allowed it to do under the old cert (like upgrade).
     */
    @DataClass.Generated.Member
    public @Nullable Signature[] getPastSigningCertificates() {
        return mPastSigningCertificates;
    }

    @DataClass.Generated(
            time = 1650058974710L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/content/pm/SigningDetails.java",
            inputSignatures = "private static final  java.lang.String TAG\nprivate final @android.annotation.Nullable android.content.pm.Signature[] mSignatures\nprivate final @android.content.pm.SigningDetails.SignatureSchemeVersion int mSignatureSchemeVersion\nprivate final @android.annotation.Nullable android.util.ArraySet<java.security.PublicKey> mPublicKeys\nprivate final @android.annotation.Nullable android.content.pm.Signature[] mPastSigningCertificates\nprivate static final  int PAST_CERT_EXISTS\npublic static final  android.content.pm.SigningDetails UNKNOWN\npublic static final @android.annotation.NonNull android.os.Parcelable.Creator<android.content.pm.SigningDetails> CREATOR\npublic @android.annotation.NonNull android.content.pm.SigningDetails mergeLineageWith(android.content.pm.SigningDetails)\npublic @android.annotation.NonNull android.content.pm.SigningDetails mergeLineageWith(android.content.pm.SigningDetails,int)\nprivate @android.annotation.NonNull android.content.pm.SigningDetails mergeLineageWithAncestorOrSelf(android.content.pm.SigningDetails,int)\npublic  boolean hasCommonAncestor(android.content.pm.SigningDetails)\npublic  boolean hasAncestorOrSelfWithDigest(java.util.Set<java.lang.String>)\nprivate @android.annotation.Nullable android.content.pm.SigningDetails getDescendantOrSelf(android.content.pm.SigningDetails)\npublic  boolean hasSignatures()\npublic  boolean hasPastSigningCertificates()\npublic  boolean hasAncestorOrSelf(android.content.pm.SigningDetails)\npublic  boolean hasAncestor(android.content.pm.SigningDetails)\npublic  boolean hasCommonSignerWithCapability(android.content.pm.SigningDetails,int)\npublic  boolean checkCapability(android.content.pm.SigningDetails,int)\npublic  boolean checkCapabilityRecover(android.content.pm.SigningDetails,int)\npublic  boolean hasCertificate(android.content.pm.Signature)\npublic  boolean hasCertificate(android.content.pm.Signature,int)\npublic  boolean hasCertificate(byte[])\nprivate  boolean hasCertificateInternal(android.content.pm.Signature,int)\npublic  boolean checkCapability(java.lang.String,int)\npublic  boolean hasSha256Certificate(byte[])\npublic  boolean hasSha256Certificate(byte[],int)\nprivate  boolean hasSha256CertificateInternal(byte[],int)\npublic  boolean signaturesMatchExactly(android.content.pm.SigningDetails)\npublic @java.lang.Override int describeContents()\npublic @java.lang.Override void writeToParcel(android.os.Parcel,int)\npublic @java.lang.Override boolean equals(java.lang.Object)\npublic @java.lang.Override int hashCode()\npublic static  android.util.ArraySet<java.security.PublicKey> toSigningKeys(android.content.pm.Signature[])\nclass SigningDetails extends java.lang.Object implements [android.os.Parcelable]\nprivate @android.annotation.NonNull android.content.pm.Signature[] mSignatures\nprivate @android.content.pm.SigningDetails.SignatureSchemeVersion int mSignatureSchemeVersion\nprivate @android.annotation.Nullable android.content.pm.Signature[] mPastSigningCertificates\npublic  android.content.pm.SigningDetails.Builder setSignatures(android.content.pm.Signature[])\npublic  android.content.pm.SigningDetails.Builder setSignatureSchemeVersion(int)\npublic  android.content.pm.SigningDetails.Builder setPastSigningCertificates(android.content.pm.Signature[])\nprivate  void checkInvariants()\npublic  android.content.pm.SigningDetails build()\nclass Builder extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genConstructor=false, genConstDefs=false, genParcelable=true, genAidl=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
