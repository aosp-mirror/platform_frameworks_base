/*
 * Copyright (C) 2009, The Android Open Source Project
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

package android.net.vpn;

/**
 * Enumeration of all VPN states.
 *
 * A normal VPN connection lifetime starts in {@link IDLE}. When a new
 * connection is about to be set up, it goes to {@link CONNECTING} and then
 * {@link CONNECTED} if successful; back to {@link IDLE} if failed.
 * When the connection is about to be torn down, it goes to
 * {@link DISCONNECTING} and then {@link IDLE}.
 * {@link CANCELLED} is a state when a VPN connection attempt is aborted, and
 * is in transition to {@link IDLE}.
 * The {@link UNUSABLE} state indicates that the profile is not in a state for
 * connecting due to possibly the integrity of the fields or another profile is
 * connecting etc.
 * The {@link UNKNOWN} state indicates that the profile state is to be
 * determined.
 * {@hide}
 */
public enum VpnState {
    CONNECTING, DISCONNECTING, CANCELLED, CONNECTED, IDLE, UNUSABLE, UNKNOWN
}
