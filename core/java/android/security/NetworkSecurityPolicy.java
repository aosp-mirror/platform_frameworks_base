/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.security;

/**
 * Network security policy.
 *
 * <p>Network stacks/components should honor this policy to make it possible to centrally control
 * the relevant aspects of network security behavior.
 *
 * <p>The policy currently consists of a single flag: whether cleartext network traffic is
 * permitted. See {@link #isCleartextTrafficPermitted()}.
 */
public class NetworkSecurityPolicy {

    private static final NetworkSecurityPolicy INSTANCE = new NetworkSecurityPolicy();

    private NetworkSecurityPolicy() {}

    /**
     * Gets the policy for this process.
     *
     * <p>It's fine to cache this reference. Any changes to the policy will be immediately visible
     * through the reference.
     */
    public static NetworkSecurityPolicy getInstance() {
        return INSTANCE;
    }

    /**
     * Returns whether cleartext network traffic (e.g. HTTP, FTP, WebSockets, XMPP, IMAP, SMTP --
     * without TLS or STARTTLS) is permitted for this process.
     *
     * <p>When cleartext network traffic is not permitted, the platform's components (e.g. HTTP and
     * FTP stacks, {@link android.app.DownloadManager}, {@link android.media.MediaPlayer}) will
     * refuse this process's requests to use cleartext traffic. Third-party libraries are strongly
     * encouraged to honor this setting as well.
     *
     * <p>This flag is honored on a best effort basis because it's impossible to prevent all
     * cleartext traffic from Android applications given the level of access provided to them. For
     * example, there's no expectation that the {@link java.net.Socket} API will honor this flag
     * because it cannot determine whether its traffic is in cleartext. However, most network
     * traffic from applications is handled by higher-level network stacks/components which can
     * honor this aspect of the policy.
     *
     * <p>NOTE: {@link android.webkit.WebView} does not honor this flag.
     */
    public boolean isCleartextTrafficPermitted() {
        return libcore.net.NetworkSecurityPolicy.isCleartextTrafficPermitted();
    }

    /**
     * Sets whether cleartext network traffic is permitted for this process.
     *
     * <p>This method is used by the platform early on in the application's initialization to set
     * the policy.
     *
     * @hide
     */
    public void setCleartextTrafficPermitted(boolean permitted) {
        libcore.net.NetworkSecurityPolicy.setCleartextTrafficPermitted(permitted);
    }
}
