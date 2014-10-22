/*
 * Copyright 2014 The Android Open Source Project
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

package android.net;

import com.android.org.conscrypt.PSKKeyManager;
import java.net.Socket;
import javax.crypto.SecretKey;
import javax.net.ssl.SSLEngine;

/**
 * Provider of key material for pre-shared key (PSK) key exchange used in TLS-PSK cipher suites.
 *
 * <h3>Overview of TLS-PSK</h3>
 *
 * <p>TLS-PSK is a set of TLS/SSL cipher suites which rely on a symmetric pre-shared key (PSK) to
 * secure the TLS/SSL connection and mutually authenticate its peers. These cipher suites may be
 * a more natural fit compared to conventional public key based cipher suites in some scenarios
 * where communication between peers is bootstrapped via a separate step (for example, a pairing
 * step) and requires both peers to authenticate each other. In such scenarios a symmetric key (PSK)
 * can be exchanged during the bootstrapping step, removing the need to generate and exchange public
 * key pairs and X.509 certificates.</p>
 *
 * <p>When a TLS-PSK cipher suite is used, both peers have to use the same key for the TLS/SSL
 * handshake to succeed. Thus, both peers are implicitly authenticated by a successful handshake.
 * This removes the need to use a {@code TrustManager} in conjunction with this {@code KeyManager}.
 * </p>
 *
 * <h3>Supporting multiple keys</h3>
 *
 * <p>A peer may have multiple keys to choose from. To help choose the right key, during the
 * handshake the server can provide a <em>PSK identity hint</em> to the client, and the client can
 * provide a <em>PSK identity</em> to the server. The contents of these two pieces of information
 * are specific to application-level protocols.</p>
 *
 * <p><em>NOTE: Both the PSK identity hint and the PSK identity are transmitted in cleartext.
 * Moreover, these data are received and processed prior to peer having been authenticated. Thus,
 * they must not contain or leak key material or other sensitive information, and should be
 * treated (e.g., parsed) with caution, as untrusted data.</em></p>
 *
 * <p>The high-level flow leading to peers choosing a key during TLS/SSL handshake is as follows:
 * <ol>
 * <li>Server receives a handshake request from client.
 * <li>Server replies, optionally providing a PSK identity hint to client.</li>
 * <li>Client chooses the key.</li>
 * <li>Client provides a PSK identity of the chosen key to server.</li>
 * <li>Server chooses the key.</li>
 * </ol></p>
 *
 * <p>In the flow above, either peer can signal that they do not have a suitable key, in which case
 * the the handshake will be aborted immediately. This may enable a network attacker who does not
 * know the key to learn which PSK identity hints or PSK identities are supported. If this is a
 * concern then a randomly generated key should be used in the scenario where no key is available.
 * This will lead to the handshake aborting later, due to key mismatch -- same as in the scenario
 * where a key is available -- making it appear to the attacker that all PSK identity hints and PSK
 * identities are supported.</p>
 *
 * <h3>Maximum sizes</h3>
 *
 * <p>The maximum supported sizes are as follows:
 * <ul>
 * <li>256 bytes for keys (see {@link #MAX_KEY_LENGTH_BYTES}),</li>
 * <li>128 bytes for PSK identity and PSK identity hint (in modified UTF-8 representation) (see
 * {@link #MAX_IDENTITY_LENGTH_BYTES} and {@link #MAX_IDENTITY_HINT_LENGTH_BYTES}).</li>
 * </ul></p>
 *
 * <h3>Subclassing</h3>
 * Subclasses should normally provide their own implementation of {@code getKey} because the default
 * implementation returns no key, which aborts the handshake.
 *
 * <h3>Example</h3>
 * The following example illustrates how to create an {@code SSLContext} which enables the use of
 * TLS-PSK in {@code SSLSocket}, {@code SSLServerSocket} and {@code SSLEngine} instances obtained
 * from it.
 * <pre> {@code
 * PskKeyManager pskKeyManager = ...;
 *
 * SSLContext sslContext = SSLContext.getInstance("TLS");
 * sslContext.init(
 *         new KeyManager[] &#123;pskKeyManager&#125;,
 *         new TrustManager[0], // No TrustManagers needed for TLS-PSK
 *         null // Use the default source of entropy
 *         );
 *
 * SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory().createSocket(...);
 * }</pre>
 */
public abstract class PskKeyManager implements PSKKeyManager {
    // IMPLEMENTATION DETAILS: This class exists only because the default implemenetation of the
    // TLS/SSL JSSE provider (currently Conscrypt) cannot depend on Android framework classes.
    // As a result, this framework class simply extends the PSKKeyManager interface from Conscrypt
    // without adding any new methods or fields. Moreover, for technical reasons (Conscrypt classes
    // are "hidden") this class replaces the Javadoc of Conscrypt's PSKKeyManager.

    /**
     * Maximum supported length (in bytes) for PSK identity hint (in modified UTF-8 representation).
     */
    public static final int MAX_IDENTITY_HINT_LENGTH_BYTES =
            PSKKeyManager.MAX_IDENTITY_HINT_LENGTH_BYTES;

    /** Maximum supported length (in bytes) for PSK identity (in modified UTF-8 representation). */
    public static final int MAX_IDENTITY_LENGTH_BYTES = PSKKeyManager.MAX_IDENTITY_LENGTH_BYTES;

    /** Maximum supported length (in bytes) for PSK. */
    public static final int MAX_KEY_LENGTH_BYTES = PSKKeyManager.MAX_KEY_LENGTH_BYTES;

    /**
     * Gets the PSK identity hint to report to the client to help agree on the PSK for the provided
     * socket.
     *
     * <p>
     * The default implementation returns {@code null}.
     *
     * @return PSK identity hint to be provided to the client or {@code null} to provide no hint.
     */
    @Override
    public String chooseServerKeyIdentityHint(Socket socket) {
        return null;
    }

    /**
     * Gets the PSK identity hint to report to the client to help agree on the PSK for the provided
     * engine.
     *
     * <p>
     * The default implementation returns {@code null}.
     *
     * @return PSK identity hint to be provided to the client or {@code null} to provide no hint.
     */
    @Override
    public String chooseServerKeyIdentityHint(SSLEngine engine) {
        return null;
    }

    /**
     * Gets the PSK identity to report to the server to help agree on the PSK for the provided
     * socket.
     *
     * <p>
     * The default implementation returns an empty string.
     *
     * @param identityHint identity hint provided by the server or {@code null} if none provided.
     *
     * @return PSK identity to provide to the server. {@code null} is permitted but will be
     *         converted into an empty string.
     */
    @Override
    public String chooseClientKeyIdentity(String identityHint, Socket socket) {
        return "";
    }

    /**
     * Gets the PSK identity to report to the server to help agree on the PSK for the provided
     * engine.
     *
     * <p>
     * The default implementation returns an empty string.
     *
     * @param identityHint identity hint provided by the server or {@code null} if none provided.
     *
     * @return PSK identity to provide to the server. {@code null} is permitted but will be
     *         converted into an empty string.
     */
    @Override
    public String chooseClientKeyIdentity(String identityHint, SSLEngine engine) {
        return "";
    }

    /**
     * Gets the PSK to use for the provided socket.
     *
     * <p>
     * The default implementation returns {@code null}.
     *
     * @param identityHint identity hint provided by the server to help select the key or
     *        {@code null} if none provided.
     * @param identity identity provided by the client to help select the key.
     *
     * @return key or {@code null} to signal to peer that no suitable key is available and to abort
     *         the handshake.
     */
    @Override
    public SecretKey getKey(String identityHint, String identity, Socket socket) {
        return null;
    }

    /**
     * Gets the PSK to use for the provided engine.
     *
     * <p>
     * The default implementation returns {@code null}.
     *
     * @param identityHint identity hint provided by the server to help select the key or
     *        {@code null} if none provided.
     * @param identity identity provided by the client to help select the key.
     *
     * @return key or {@code null} to signal to peer that no suitable key is available and to abort
     *         the handshake.
     */
    @Override
    public SecretKey getKey(String identityHint, String identity, SSLEngine engine) {
        return null;
    }
}
