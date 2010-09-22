/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.util.Map;

/**
 * Interface used to get feedback about a {@link android.net.LinkSocket}.  Instance is optionally
 * passed when a LinkSocket is constructed.  Multiple LinkSockets may use the same notifier.
 * @hide
 */
public interface LinkSocketNotifier {
    /**
     * This callback function will be called if a better link
     * becomes available.
     * TODO - this shouldn't be checked for all cases - what's the conditional
     *        flag?
     * If the duplicate socket is accepted, the original will be marked invalid
     * and additional use will throw exceptions.
     * @param original the original LinkSocket
     * @param duplicate the new LinkSocket that better meets the application
     *                  requirements
     * @return {@code true} if the application intends to use this link
     *
     * REM
     * TODO - how agressive should we be?
     * At a minimum CS tracks which LS have this turned on and tracks the requirements
     * When a new link becomes available, automatically check if any of the LinkSockets
     *   will care.
     * If found, grab a refcount on the link so it doesn't go away and send notification
     * Optionally, periodically setup connection on available networks to check for better links
     * Maybe pass this info into the LinkFactories so condition changes can be acted on more quickly
     */
    public boolean onBetterLinkAvailable(LinkSocket original, LinkSocket duplicate);

    /**
     * This callback function will be called when a LinkSocket no longer has
     * an active link.
     * @param socket the LinkSocket that lost its link
     *
     * REM
     * NetworkStateTracker tells us it is disconnected
     * CS checks the table for LS on that link
     * CS calls each callback (need to work out p2p cross process callback)
     */
    public void onLinkLost(LinkSocket socket);

    /**
     * This callback function will be called when an application calls
     * requestNewLink on a LinkSocket but the LinkSocket is unable to find
     * a suitable new link.
     * @param socket the LinkSocket for which a new link was not found
     * TODO - why the diff between initial request (sync) and requestNewLink?
     *
     * REM
     * CS process of trying to find a new link must track the LS that started it
     * on failure, call callback
     */
    public void onNewLinkUnavailable(LinkSocket socket);

    /**
     * This callback function will be called when any of the notification-marked
     * capabilities of the LinkSocket (e.g. upstream bandwidth) have changed.
     * @param socket the linkSocet for which capabilities have changed
     * @param changedCapabilities the set of capabilities that the application
     *                            is interested in and have changed (with new values)
     *
     * REM
     * Maybe pass the interesting capabilities into the Links.
     * Get notified of every capability change
     * check for LinkSockets on that Link that are interested in that Capability - call them
     */
    public void onCapabilitiesChanged(LinkSocket socket, LinkCapabilities changedCapabilities);
}
