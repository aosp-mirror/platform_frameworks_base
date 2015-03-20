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
 * @hide
 */
public class NetworkSecurityPolicy {

  private static final NetworkSecurityPolicy INSTANCE = new NetworkSecurityPolicy();

  private boolean mCleartextTrafficPermitted = true;

  private NetworkSecurityPolicy() {}

  /**
   * Gets the policy.
   */
  public static NetworkSecurityPolicy getInstance() {
    return INSTANCE;
  }

  /**
   * Checks whether cleartext network traffic (e.g., HTTP, WebSockets, XMPP, IMAP, SMTP -- without
   * TLS or STARTTLS) is permitted for this process.
   *
   * <p>When cleartext network traffic is not permitted, the platform's components (e.g., HTTP
   * stacks, {@code WebView}, {@code MediaPlayer}) will refuse this process's requests to use
   * cleartext traffic. Third-party libraries are encouraged to honor this setting as well.
   */
  public boolean isCleartextTrafficPermitted() {
    synchronized (this) {
      return mCleartextTrafficPermitted;
    }
  }

  /**
   * Sets whether cleartext network traffic is permitted for this process.
   *
   * <p>This method is used by the platform early on in the application's initialization to set the
   * policy.
   *
   * @hide
   */
  public void setCleartextTrafficPermitted(boolean permitted) {
    synchronized (this) {
      mCleartextTrafficPermitted = permitted;
    }
  }
}
