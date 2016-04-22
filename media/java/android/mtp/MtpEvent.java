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
 * This corresponds to the events described in appendix G of the MTP specification.
 */
public class MtpEvent {
    /** Event code for UNDEFINED event */
    public static final int EVENT_UNDEFINED = 0x4000;
    /** Event code for CANCEL_TRANSACTION event */
    public static final int EVENT_CANCEL_TRANSACTION = 0x4001;
    /** Event code for OBJECT_ADDED event */
    public static final int EVENT_OBJECT_ADDED = 0x4002;
    /** Event code for OBJECT_REMOVED event */
    public static final int EVENT_OBJECT_REMOVED = 0x4003;
    /** Event code for STORE_ADDED event */
    public static final int EVENT_STORE_ADDED = 0x4004;
    /** Event code for STORE_REMOVED event */
    public static final int EVENT_STORE_REMOVED = 0x4005;
    /** Event code for DEVICE_PROP_CHANGED event */
    public static final int EVENT_DEVICE_PROP_CHANGED = 0x4006;
    /** Event code for OBJECT_INFO_CHANGED event */
    public static final int EVENT_OBJECT_INFO_CHANGED = 0x4007;
    /** Event code for DEVICE_INFO_CHANGED event */
    public static final int EVENT_DEVICE_INFO_CHANGED = 0x4008;
    /** Event code for REQUEST_OBJECT_TRANSFER event */
    public static final int EVENT_REQUEST_OBJECT_TRANSFER = 0x4009;
    /** Event code for STORE_FULL event */
    public static final int EVENT_STORE_FULL = 0x400A;
    /** Event code for DEVICE_RESET event */
    public static final int EVENT_DEVICE_RESET = 0x400B;
    /** Event code for STORAGE_INFO_CHANGED event */
    public static final int EVENT_STORAGE_INFO_CHANGED = 0x400C;
    /** Event code for CAPTURE_COMPLETE event */
    public static final int EVENT_CAPTURE_COMPLETE = 0x400D;
    /** Event code for UNREPORTED_STATUS event */
    public static final int EVENT_UNREPORTED_STATUS = 0x400E;
    /** Event code for OBJECT_PROP_CHANGED event */
    public static final int EVENT_OBJECT_PROP_CHANGED = 0xC801;
    /** Event code for OBJECT_PROP_DESC_CHANGED event */
    public static final int EVENT_OBJECT_PROP_DESC_CHANGED = 0xC802;
    /** Event code for OBJECT_REFERENCES_CHANGED event */
    public static final int EVENT_OBJECT_REFERENCES_CHANGED = 0xC803;

    private int mEventCode = EVENT_UNDEFINED;

    // Parameters for event. The interpretation of event parameters depends upon mEventCode.
    private int mParameter1;
    private int mParameter2;
    private int mParameter3;

    /**
     * MtpEvent is instantiated by JNI.
     */
    private MtpEvent() {}

    /**
     * Returns event code of MTP event.
     * See the USB-IF MTP specification for the details of event constants.
     * @return event code
     */
    public int getEventCode() { return mEventCode; }

    /**
     * Obtains the first event parameter.
     */
    public int getParameter1() { return mParameter1; }

    /**
     * Obtains the second event parameter.
     */
    public int getParameter2() { return mParameter2; }

    /**
     * Obtains the third event parameter.
     */
    public int getParameter3() { return mParameter3; }

    /**
     * Obtains objectHandle event parameter.
     *
     * @see #EVENT_OBJECT_ADDED
     * @see #EVENT_OBJECT_REMOVED
     * @see #EVENT_OBJECT_INFO_CHANGED
     * @see #EVENT_REQUEST_OBJECT_TRANSFER
     * @see #EVENT_OBJECT_PROP_CHANGED
     * @see #EVENT_OBJECT_REFERENCES_CHANGED
     */
    public int getObjectHandle() {
        switch (mEventCode) {
            case EVENT_OBJECT_ADDED:
                return mParameter1;
            case EVENT_OBJECT_REMOVED:
                return mParameter1;
            case EVENT_OBJECT_INFO_CHANGED:
                return mParameter1;
            case EVENT_REQUEST_OBJECT_TRANSFER:
                return mParameter1;
            case EVENT_OBJECT_PROP_CHANGED:
                return mParameter1;
            case EVENT_OBJECT_REFERENCES_CHANGED:
                return mParameter1;
            default:
                throw new IllegalParameterAccess("objectHandle", mEventCode);
        }
    }

    /**
     * Obtains storageID event parameter.
     *
     * @see #EVENT_STORE_ADDED
     * @see #EVENT_STORE_REMOVED
     * @see #EVENT_STORE_FULL
     * @see #EVENT_STORAGE_INFO_CHANGED
     */
    public int getStorageId() {
        switch (mEventCode) {
            case EVENT_STORE_ADDED:
                return mParameter1;
            case EVENT_STORE_REMOVED:
                return mParameter1;
            case EVENT_STORE_FULL:
                return mParameter1;
            case EVENT_STORAGE_INFO_CHANGED:
                return mParameter1;
            default:
                throw new IllegalParameterAccess("storageID", mEventCode);
        }
    }

    /**
     * Obtains devicePropCode event parameter.
     *
     * @see #EVENT_DEVICE_PROP_CHANGED
     */
    public int getDevicePropCode() {
        switch (mEventCode) {
            case EVENT_DEVICE_PROP_CHANGED:
                return mParameter1;
            default:
                throw new IllegalParameterAccess("devicePropCode", mEventCode);
        }
    }

    /**
     * Obtains transactionID event parameter.
     *
     * @see #EVENT_CAPTURE_COMPLETE
     */
    public int getTransactionId() {
        switch (mEventCode) {
            case EVENT_CAPTURE_COMPLETE:
                return mParameter1;
            default:
                throw new IllegalParameterAccess("transactionID", mEventCode);
        }
    }

    /**
     * Obtains objectPropCode event parameter.
     *
     * @see #EVENT_OBJECT_PROP_CHANGED
     * @see #EVENT_OBJECT_PROP_DESC_CHANGED
     */
    public int getObjectPropCode() {
        switch (mEventCode) {
            case EVENT_OBJECT_PROP_CHANGED:
                return mParameter2;
            case EVENT_OBJECT_PROP_DESC_CHANGED:
                return mParameter1;
            default:
                throw new IllegalParameterAccess("objectPropCode", mEventCode);
        }
    }

    /**
     * Obtains objectFormatCode event parameter.
     *
     * @see #EVENT_OBJECT_PROP_DESC_CHANGED
     */
    public int getObjectFormatCode() {
        switch (mEventCode) {
            case EVENT_OBJECT_PROP_DESC_CHANGED:
                return mParameter2;
            default:
                throw new IllegalParameterAccess("objectFormatCode", mEventCode);
        }
    }

    private static class IllegalParameterAccess extends UnsupportedOperationException {
        public IllegalParameterAccess(String propertyName, int eventCode) {
            super("Cannot obtain " + propertyName + " for the event: " + eventCode + ".");
        }
    }
}
