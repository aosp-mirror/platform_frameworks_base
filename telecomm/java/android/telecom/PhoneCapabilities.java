/*
 * Copyright 2014, The Android Open Source Project
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

package android.telecom;

import android.annotation.SystemApi;

/**
 * Defines capabilities a phone call can support, such as conference calling and video telephony.
 * Also defines properties of a phone call, such as whether it is using VoLTE technology.
 * @hide
 */
@SystemApi
public final class PhoneCapabilities {
    /** Call can currently be put on hold or unheld. */
    public static final int HOLD               = 0x00000001;

    /** Call supports the hold feature. */
    public static final int SUPPORT_HOLD       = 0x00000002;

    /**
     * Calls within a conference can be merged. Some connection services create a conference call
     * only after two calls have been merged.  However, a conference call can also be added the
     * moment there are more than one call. CDMA calls are implemented in this way because the call
     * actions are more limited when more than one call exists. This flag allows merge to be exposed
     * as a capability on the conference call instead of individual calls.
     */
    public static final int MERGE_CONFERENCE   = 0x00000004;

    /** Calls withing a conference can be swapped between foreground and background. */
    public static final int SWAP_CONFERENCE    = 0x00000008;

    /** Call currently supports adding another call to this one. */
    public static final int ADD_CALL           = 0x00000010;

    /** Call supports responding via text option. */
    public static final int RESPOND_VIA_TEXT   = 0x00000020;

    /** Call can be muted. */
    public static final int MUTE               = 0x00000040;

    /**
     * Call supports conference call management. This capability only applies to conference calls
     * which can have other calls as children.
     */
    public static final int MANAGE_CONFERENCE = 0x00000080;

    /**
     * Local device supports video telephony.
     * @hide
     */
    public static final int SUPPORTS_VT_LOCAL  = 0x00000100;

    /**
     * Remote device supports video telephony.
     * @hide
     */
    public static final int SUPPORTS_VT_REMOTE = 0x00000200;

    /**
     * Call is using voice over LTE.
     * @hide
     */
    public static final int VoLTE = 0x00000400;

    /**
     * Call is using voice over WIFI.
     * @hide
     */
    public static final int VoWIFI = 0x00000800;

    /**
     * Call is able to be separated from its parent {@code Conference}, if any.
     */
    public static final int SEPARATE_FROM_CONFERENCE = 0x00001000;

    /**
     * Call is able to be individually disconnected when in a {@code Conference}.
     */
    public static final int DISCONNECT_FROM_CONFERENCE = 0x00002000;

    public static final int ALL = HOLD | SUPPORT_HOLD | MERGE_CONFERENCE | SWAP_CONFERENCE
            | ADD_CALL | RESPOND_VIA_TEXT | MUTE | MANAGE_CONFERENCE | SEPARATE_FROM_CONFERENCE
            | DISCONNECT_FROM_CONFERENCE;

    public static String toString(int capabilities) {
        StringBuilder builder = new StringBuilder();
        builder.append("[Capabilities:");
        if ((capabilities & HOLD) != 0) {
            builder.append(" HOLD");
        }
        if ((capabilities & SUPPORT_HOLD) != 0) {
            builder.append(" SUPPORT_HOLD");
        }
        if ((capabilities & MERGE_CONFERENCE) != 0) {
            builder.append(" MERGE_CONFERENCE");
        }
        if ((capabilities & SWAP_CONFERENCE) != 0) {
            builder.append(" SWAP_CONFERENCE");
        }
        if ((capabilities & ADD_CALL) != 0) {
            builder.append(" ADD_CALL");
        }
        if ((capabilities & RESPOND_VIA_TEXT) != 0) {
            builder.append(" RESPOND_VIA_TEXT");
        }
        if ((capabilities & MUTE) != 0) {
            builder.append(" MUTE");
        }
        if ((capabilities & MANAGE_CONFERENCE) != 0) {
            builder.append(" MANAGE_CONFERENCE");
        }
        if ((capabilities & SUPPORTS_VT_LOCAL) != 0) {
            builder.append(" SUPPORTS_VT_LOCAL");
        }
        if ((capabilities & SUPPORTS_VT_REMOTE) != 0) {
            builder.append(" SUPPORTS_VT_REMOTE");
        }
        if ((capabilities & VoLTE) != 0) {
            builder.append(" VoLTE");
        }
        if ((capabilities & VoWIFI) != 0) {
            builder.append(" VoWIFI");
        }
        builder.append("]");
        return builder.toString();
    }

    private PhoneCapabilities() {}
}
