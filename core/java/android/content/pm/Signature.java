/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.TypedXmlSerializer;

import com.android.internal.util.ArrayUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/**
 * Opaque, immutable representation of a signing certificate associated with an
 * application package.
 * <p>
 * This class name is slightly misleading, since it's not actually a signature.
 */
public class Signature implements Parcelable {
    private final byte[] mSignature;
    private int mHashCode;
    private boolean mHaveHashCode;
    private SoftReference<String> mStringRef;
    private Certificate[] mCertificateChain;
    /**
     * APK Signature Scheme v3 includes support for adding a proof-of-rotation record that
     * contains two pieces of information:
     *   1) the past signing certificates
     *   2) the flags that APK wants to assign to each of the past signing certificates.
     *
     * These flags represent the second piece of information and are viewed as capabilities.
     * They are an APK's way of telling the platform: "this is how I want to trust my old certs,
     * please enforce that." This is useful for situation where this app itself is using its
     * signing certificate as an authorization mechanism, like whether or not to allow another
     * app to have its SIGNATURE permission.  An app could specify whether to allow other apps
     * signed by its old cert 'X' to still get a signature permission it defines, for example.
     */
    private int mFlags;

    /**
     * Create Signature from an existing raw byte array.
     */
    public Signature(byte[] signature) {
        mSignature = signature.clone();
        mCertificateChain = null;
    }

    /**
     * Create signature from a certificate chain. Used for backward
     * compatibility.
     *
     * @throws CertificateEncodingException
     * @hide
     */
    public Signature(Certificate[] certificateChain) throws CertificateEncodingException {
        mSignature = certificateChain[0].getEncoded();
        if (certificateChain.length > 1) {
            mCertificateChain = Arrays.copyOfRange(certificateChain, 1, certificateChain.length);
        }
    }

    private static final int parseHexDigit(int nibble) {
        if ('0' <= nibble && nibble <= '9') {
            return nibble - '0';
        } else if ('a' <= nibble && nibble <= 'f') {
            return nibble - 'a' + 10;
        } else if ('A' <= nibble && nibble <= 'F') {
            return nibble - 'A' + 10;
        } else {
            throw new IllegalArgumentException("Invalid character " + nibble + " in hex string");
        }
    }

    /**
     * Create Signature from a text representation previously returned by
     * {@link #toChars} or {@link #toCharsString()}. Signatures are expected to
     * be a hex-encoded ASCII string.
     *
     * @param text hex-encoded string representing the signature
     * @throws IllegalArgumentException when signature is odd-length
     */
    public Signature(String text) {
        final byte[] input = text.getBytes();
        final int N = input.length;

        if (N % 2 != 0) {
            throw new IllegalArgumentException("text size " + N + " is not even");
        }

        final byte[] sig = new byte[N / 2];
        int sigIndex = 0;

        for (int i = 0; i < N;) {
            final int hi = parseHexDigit(input[i++]);
            final int lo = parseHexDigit(input[i++]);
            sig[sigIndex++] = (byte) ((hi << 4) | lo);
        }

        mSignature = sig;
    }

    /**
     * Copy constructor that creates a new instance from the provided {@code other} Signature.
     *
     * @hide
     */
    public Signature(Signature other) {
        mSignature = other.mSignature.clone();
        Certificate[] otherCertificateChain = other.mCertificateChain;
        if (otherCertificateChain != null && otherCertificateChain.length > 1) {
            mCertificateChain = Arrays.copyOfRange(otherCertificateChain, 1,
                    otherCertificateChain.length);
        }
        mFlags = other.mFlags;
    }

    /**
     * Sets the flags representing the capabilities of the past signing certificate.
     * @hide
     */
    public void setFlags(int flags) {
        this.mFlags = flags;
    }

    /**
     * Returns the flags representing the capabilities of the past signing certificate.
     * @hide
     */
    public int getFlags() {
        return mFlags;
    }

    /**
     * Encode the Signature as ASCII text.
     */
    public char[] toChars() {
        return toChars(null, null);
    }

    /**
     * Encode the Signature as ASCII text in to an existing array.
     *
     * @param existingArray Existing char array or null.
     * @param outLen Output parameter for the number of characters written in
     * to the array.
     * @return Returns either <var>existingArray</var> if it was large enough
     * to hold the ASCII representation, or a newly created char[] array if
     * needed.
     */
    public char[] toChars(char[] existingArray, int[] outLen) {
        byte[] sig = mSignature;
        final int N = sig.length;
        final int N2 = N*2;
        char[] text = existingArray == null || N2 > existingArray.length
                ? new char[N2] : existingArray;
        for (int j=0; j<N; j++) {
            byte v = sig[j];
            int d = (v>>4)&0xf;
            text[j*2] = (char)(d >= 10 ? ('a' + d - 10) : ('0' + d));
            d = v&0xf;
            text[j*2+1] = (char)(d >= 10 ? ('a' + d - 10) : ('0' + d));
        }
        if (outLen != null) outLen[0] = N;
        return text;
    }

    /**
     * Return the result of {@link #toChars()} as a String.
     */
    public String toCharsString() {
        String str = mStringRef == null ? null : mStringRef.get();
        if (str != null) {
            return str;
        }
        str = new String(toChars());
        mStringRef = new SoftReference<String>(str);
        return str;
    }

    /**
     * @return the contents of this signature as a byte array.
     */
    public byte[] toByteArray() {
        byte[] bytes = new byte[mSignature.length];
        System.arraycopy(mSignature, 0, bytes, 0, mSignature.length);
        return bytes;
    }

    /**
     * Returns the public key for this signature.
     *
     * @throws CertificateException when Signature isn't a valid X.509
     *             certificate; shouldn't happen.
     * @hide
     */
    @UnsupportedAppUsage
    public PublicKey getPublicKey() throws CertificateException {
        final CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
        final ByteArrayInputStream bais = new ByteArrayInputStream(mSignature);
        final Certificate cert = certFactory.generateCertificate(bais);
        return cert.getPublicKey();
    }

    /**
     * Used for compatibility code that needs to check the certificate chain
     * during upgrades.
     *
     * @throws CertificateEncodingException
     * @hide
     */
    public Signature[] getChainSignatures() throws CertificateEncodingException {
        if (mCertificateChain == null) {
            return new Signature[] { this };
        }

        Signature[] chain = new Signature[1 + mCertificateChain.length];
        chain[0] = this;

        int i = 1;
        for (Certificate c : mCertificateChain) {
            chain[i++] = new Signature(c.getEncoded());
        }

        return chain;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        try {
            if (obj != null) {
                Signature other = (Signature)obj;
                // Note, some classes, such as SigningDetails, rely on equals
                // only comparing the mSignature arrays without the flags.
                return this == other || Arrays.equals(mSignature, other.mSignature);
            }
        } catch (ClassCastException e) {
        }
        return false;
    }

    @Override
    public int hashCode() {
        if (mHaveHashCode) {
            return mHashCode;
        }
        // Note, similar to equals some classes rely on the hash code not including
        // the flags for Set membership checks.
        mHashCode = Arrays.hashCode(mSignature);
        mHaveHashCode = true;
        return mHashCode;
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeByteArray(mSignature);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<Signature> CREATOR
            = new Parcelable.Creator<Signature>() {
        public Signature createFromParcel(Parcel source) {
            return new Signature(source);
        }

        public Signature[] newArray(int size) {
            return new Signature[size];
        }
    };

    /** {@hide} */
    public void writeToXmlAttributeBytesHex(@NonNull TypedXmlSerializer out,
            @Nullable String namespace, @NonNull String name) throws IOException {
        out.attributeBytesHex(namespace, name, mSignature);
    }

    private Signature(Parcel source) {
        mSignature = source.createByteArray();
    }

    /**
     * Test if given {@link Signature} sets are exactly equal.
     *
     * @hide
     */
    public static boolean areExactMatch(Signature[] a, Signature[] b) {
        return (a.length == b.length) && ArrayUtils.containsAll(a, b)
                && ArrayUtils.containsAll(b, a);
    }

    /**
     * Test if given {@link Signature} sets are effectively equal. In rare
     * cases, certificates can have slightly malformed encoding which causes
     * exact-byte checks to fail.
     * <p>
     * To identify effective equality, we bounce the certificates through an
     * decode/encode pass before doing the exact-byte check. To reduce attack
     * surface area, we only allow a byte size delta of a few bytes.
     *
     * @throws CertificateException if the before/after length differs
     *             substantially, usually a signal of something fishy going on.
     * @hide
     */
    public static boolean areEffectiveMatch(Signature[] a, Signature[] b)
            throws CertificateException {
        final CertificateFactory cf = CertificateFactory.getInstance("X.509");

        final Signature[] aPrime = new Signature[a.length];
        for (int i = 0; i < a.length; i++) {
            aPrime[i] = bounce(cf, a[i]);
        }
        final Signature[] bPrime = new Signature[b.length];
        for (int i = 0; i < b.length; i++) {
            bPrime[i] = bounce(cf, b[i]);
        }

        return areExactMatch(aPrime, bPrime);
    }

    /**
     * Test if given {@link Signature} objects are effectively equal. In rare
     * cases, certificates can have slightly malformed encoding which causes
     * exact-byte checks to fail.
     * <p>
     * To identify effective equality, we bounce the certificates through an
     * decode/encode pass before doing the exact-byte check. To reduce attack
     * surface area, we only allow a byte size delta of a few bytes.
     *
     * @throws CertificateException if the before/after length differs
     *             substantially, usually a signal of something fishy going on.
     * @hide
     */
    public static boolean areEffectiveMatch(Signature a, Signature b)
            throws CertificateException {
        final CertificateFactory cf = CertificateFactory.getInstance("X.509");

        final Signature aPrime = bounce(cf, a);
        final Signature bPrime = bounce(cf, b);

        return aPrime.equals(bPrime);
    }

    /**
     * Bounce the given {@link Signature} through a decode/encode cycle.
     *
     * @throws CertificateException if the before/after length differs
     *             substantially, usually a signal of something fishy going on.
     * @hide
     */
    public static Signature bounce(CertificateFactory cf, Signature s) throws CertificateException {
        final InputStream is = new ByteArrayInputStream(s.mSignature);
        final X509Certificate cert = (X509Certificate) cf.generateCertificate(is);
        final Signature sPrime = new Signature(cert.getEncoded());

        if (Math.abs(sPrime.mSignature.length - s.mSignature.length) > 2) {
            throw new CertificateException("Bounced cert length looks fishy; before "
                    + s.mSignature.length + ", after " + sPrime.mSignature.length);
        }

        return sPrime;
    }
}