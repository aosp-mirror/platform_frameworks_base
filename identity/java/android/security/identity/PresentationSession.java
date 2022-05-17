/*
 * Copyright 2021 The Android Open Source Project
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

package android.security.identity;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.PublicKey;

/**
 * Class for presenting multiple documents to a remote verifier.
 *
 * <p>This should be used for all interactions with a remote verifier instead of the now deprecated
 * {@link IdentityCredential#getEntries(byte[], Map, byte[], byte[])} method.
 *
 * Use {@link IdentityCredentialStore#createPresentationSession(int)} to create a {@link
 * PresentationSession} instance.
 */
public abstract class PresentationSession {
    /**
     * @hide
     */
    protected PresentationSession() {}

    /**
     * Gets the ephemeral key pair to use to establish a secure channel with the verifier.
     *
     * <p>Applications should use this key-pair for the communications channel with the verifier
     * using a protocol / cipher-suite appropriate for the application. One example of such a
     * protocol is the one used for Mobile Driving Licenses, see ISO 18013-5.
     *
     * <p>The ephemeral key pair is tied to the {@link PresentationSession} instance so subsequent
     * calls to this method will return the same key-pair.
     *
     * @return ephemeral key pair to use to establish a secure channel with a reader.
     */
    public @NonNull abstract KeyPair getEphemeralKeyPair();

    /**
     * Set the ephemeral public key provided by the verifier.
     *
     * <p>If called, this must be called before any calls to
     * {@link #getCredentialData(String, CredentialDataRequest)}.
     *
     * <p>This method can only be called once per {@link PresentationSession} instance.
     *
     * @param readerEphemeralPublicKey The ephemeral public key provided by the reader to
     *                                 establish a secure session.
     * @throws InvalidKeyException if the given key is invalid.
     */
    public abstract void setReaderEphemeralPublicKey(@NonNull PublicKey readerEphemeralPublicKey)
            throws InvalidKeyException;

    /**
     * Set the session transcript.
     *
     * <p>If called, this must be called before any calls to
     * {@link #getCredentialData(String, CredentialDataRequest)}.
     *
     * <p>The X and Y coordinates of the public part of the key-pair returned by {@link
     * #getEphemeralKeyPair()} must appear somewhere in the bytes of the passed in CBOR.  Each of
     * these coordinates must appear encoded with the most significant bits first and use the exact
     * amount of bits indicated by the key size of the ephemeral keys. For example, if the
     * ephemeral key is using the P-256 curve then the 32 bytes for the X coordinate encoded with
     * the most significant bits first must appear somewhere and ditto for the 32 bytes for the Y
     * coordinate.
     *
     * <p>This method can only be called once per {@link PresentationSession} instance.
     *
     * @param sessionTranscript the session transcript.
     */
    public abstract void setSessionTranscript(@NonNull byte[] sessionTranscript);

    /**
     * Retrieves data from a named credential in the current presentation session.
     *
     * <p>If an access control check fails for one of the requested entries or if the entry
     * doesn't exist, the entry is simply not returned. The application can detect this
     * by using the {@link CredentialDataResult.Entries#getStatus(String, String)} method on
     * each of the requested entries.
     *
     * <p>The application should not make any assumptions on whether user authentication is needed.
     * Instead, the application should request the data elements values first and then examine
     * the returned {@link CredentialDataResult.Entries}. If
     * {@link CredentialDataResult.Entries#STATUS_USER_AUTHENTICATION_FAILED} is returned the
     * application should get a
     * {@link android.hardware.biometrics.BiometricPrompt.CryptoObject} which references this
     * object and use it with a {@link android.hardware.biometrics.BiometricPrompt}. Upon successful
     * authentication the application may call
     * {@link #getCredentialData(String, CredentialDataRequest)} again.
     *
     * <p>It is permissible to call this method multiple times using the same credential name.
     * If this is done the same auth-key will be used.
     *
     * <p>If the reader signature is set in the request parameter (via the
     * {@link CredentialDataRequest.Builder#setReaderSignature(byte[])} method) it must contain
     * the bytes of a {@code COSE_Sign1} structure as defined in RFC 8152. For the payload
     * {@code nil} shall be used and the detached payload is the {@code ReaderAuthenticationBytes}
     * CBOR described below.
     * <pre>
     *     ReaderAuthentication = [
     *       "ReaderAuthentication",
     *       SessionTranscript,
     *       ItemsRequestBytes
     *     ]
     *
     *     ItemsRequestBytes = #6.24(bstr .cbor ItemsRequest)
     *
     *     ReaderAuthenticationBytes = #6.24(bstr .cbor ReaderAuthentication)
     * </pre>
     *
     * <p>where {@code ItemsRequestBytes} are the bytes of the request message set in
     * the request parameter (via the
     * {@link CredentialDataRequest.Builder#setRequestMessage(byte[])} method).
     *
     * <p>The public key corresponding to the key used to make the signature, can be found in the
     * {@code x5chain} unprotected header element of the {@code COSE_Sign1} structure (as as
     * described in
     * <a href="https://tools.ietf.org/html/draft-ietf-cose-x509-08">draft-ietf-cose-x509-08</a>).
     * There will be at least one certificate in said element and there may be more (and if so,
     * each certificate must be signed by its successor).
     *
     * <p>Data elements protected by reader authentication are returned if, and only if,
     * {@code requestMessage} is signed by the top-most certificate in the reader's certificate
     * chain, and the data element is configured with an {@link AccessControlProfile} configured
     * with an X.509 certificate for a key which appear in the certificate chain.
     *
     * <p>Note that the request message CBOR is used only for enforcing reader authentication, it's
     * not used for determining which entries this API will return. The application is expected to
     * have parsed the request message and filtered it according to user preference and/or consent.
     *
     * @param credentialName the name of the credential to retrieve.
     * @param request the data to retrieve from the credential
     * @return If the credential wasn't found, returns null. Otherwise a
     *         {@link CredentialDataResult} object containing entry data organized by namespace and
     *         a cryptographically authenticated representation of the same data, bound to the
     *         current session.
     * @throws NoAuthenticationKeyAvailableException  if authentication keys were never
     *                                                provisioned for the credential or if they
     *                                                are expired or exhausted their use-count.
     * @throws InvalidRequestMessageException         if the requestMessage is malformed.
     * @throws InvalidReaderSignatureException        if the reader signature is invalid, or it
     *                                                doesn't contain a certificate chain, or if
     *                                                the signature failed to validate.
     * @throws EphemeralPublicKeyNotFoundException    if the ephemeral public key was not found in
     *                                                the session transcript.
     */
    public abstract @Nullable CredentialDataResult getCredentialData(
            @NonNull String credentialName, @NonNull CredentialDataRequest request)
            throws NoAuthenticationKeyAvailableException, InvalidReaderSignatureException,
            InvalidRequestMessageException, EphemeralPublicKeyNotFoundException;

    /**
     * Called by android.hardware.biometrics.CryptoObject#getOpId() to get an
     * operation handle.
     *
     * @hide
     */
    public abstract long getCredstoreOperationHandle();
}
