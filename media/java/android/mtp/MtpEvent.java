/*
 * Copyright (C) 2015 The Android Open Source Project
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

package android.mtp;

/**
 * This class encapsulates information about a MTP event.
 * Event constants are defined by the USB-IF MTP specification.
 */
public class MtpEvent {
    public static final int EVENT_UNDEFINED = 0x4000;
    public static final int EVENT_CANCEL_TRANSACTION = 0x4001;
    public static final int EVENT_OBJECT_ADDED = 0x4002;
    public static final int EVENT_OBJECT_REMOVED = 0x4003;
    public static final int EVENT_STORE_ADDED = 0x4004;
    public static final int EVENT_STORE_REMOVED = 0x4005;
    public static final int EVENT_DEVICE_PROP_CHANGED = 0x4006;
    public static final int EVENT_OBJECT_INFO_CHANGED = 0x4007;
    public static final int EVENT_DEVICE_INFO_CHANGED = 0x4008;
    public static final int EVENT_REQUEST_OBJECT_TRANSFER = 0x4009;
    public static final int EVENT_STORE_FULL = 0x400A;
    public static final int EVENT_DEVICE_RESET = 0x400B;
    public static final int EVENT_STORAGE_INFO_CHANGED = 0x400C;
    public static final int EVENT_CAPTURE_COMPLETE = 0x400D;
    public static final int EVENT_UNREPORTED_STATUS = 0x400E;
    public static final int EVENT_OBJECT_PROP_CHANGED = 0xC801;
    public static final int EVENT_OBJECT_PROP_DESC_CHANGED = 0xC802;
    public static final int EVENT_OBJECT_REFERENCES_CHANGED = 0xC803;

    private int mEventCode = EVENT_UNDEFINED;

    /**
     * Returns event code of MTP event.
     * See the USB-IF MTP specification for the details of event constants.
     * @return event code
     */
    public int getEventCode() { return mEventCode; }
}
