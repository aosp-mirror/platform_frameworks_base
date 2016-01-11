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
    private int mEventCode = MtpConstants.EVENT_UNDEFINED;

    // Parameters for event. The interpretation of event parameters depends upon mEventCode.
    private int mParameter1;
    private int mParameter2;
    private int mParameter3;

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
     * @see MtpConstants#EVENT_OBJECT_ADDED
     * @see MtpConstants#EVENT_OBJECT_REMOVED
     * @see MtpConstants#EVENT_OBJECT_INFO_CHANGED
     * @see MtpConstants#EVENT_REQUEST_OBJECT_TRANSFER
     * @see MtpConstants#EVENT_OBJECT_PROP_CHANGED
     * @see MtpConstants#EVENT_OBJECT_REFERENCES_CHANGED
     */
    public int getObjectHandle() {
        switch (mEventCode) {
            case MtpConstants.EVENT_OBJECT_ADDED:
                return mParameter1;
            case MtpConstants.EVENT_OBJECT_REMOVED:
                return mParameter1;
            case MtpConstants.EVENT_OBJECT_INFO_CHANGED:
                return mParameter1;
            case MtpConstants.EVENT_REQUEST_OBJECT_TRANSFER:
                return mParameter1;
            case MtpConstants.EVENT_OBJECT_PROP_CHANGED:
                return mParameter1;
            case MtpConstants.EVENT_OBJECT_REFERENCES_CHANGED:
                return mParameter1;
            default:
                throw new IllegalParameterAccess("objectHandle", mEventCode);
        }
    }

    /**
     * Obtains storageID event parameter.
     *
     * @see MtpConstants#EVENT_STORE_ADDED
     * @see MtpConstants#EVENT_STORE_REMOVED
     * @see MtpConstants#EVENT_STORE_FULL
     * @see MtpConstants#EVENT_STORAGE_INFO_CHANGED
     */
    public int getStorageId() {
        switch (mEventCode) {
            case MtpConstants.EVENT_STORE_ADDED:
                return mParameter1;
            case MtpConstants.EVENT_STORE_REMOVED:
                return mParameter1;
            case MtpConstants.EVENT_STORE_FULL:
                return mParameter1;
            case MtpConstants.EVENT_STORAGE_INFO_CHANGED:
                return mParameter1;
            default:
                throw new IllegalParameterAccess("storageID", mEventCode);
        }
    }

    /**
     * Obtains devicePropCode event parameter.
     *
     * @see MtpConstants#EVENT_DEVICE_PROP_CHANGED
     */
    public int getDevicePropCode() {
        switch (mEventCode) {
            case MtpConstants.EVENT_DEVICE_PROP_CHANGED:
                return mParameter1;
            default:
                throw new IllegalParameterAccess("devicePropCode", mEventCode);
        }
    }

    /**
     * Obtains transactionID event parameter.
     *
     * @see MtpConstants#EVENT_CAPTURE_COMPLETE
     */
    public int getTransactionId() {
        switch (mEventCode) {
            case MtpConstants.EVENT_CAPTURE_COMPLETE:
                return mParameter1;
            default:
                throw new IllegalParameterAccess("transactionID", mEventCode);
        }
    }

    /**
     * Obtains objectPropCode event parameter.
     *
     * @see MtpConstants#EVENT_OBJECT_PROP_CHANGED
     * @see MtpConstants#EVENT_OBJECT_PROP_DESC_CHANGED
     */
    public int getObjectPropCode() {
        switch (mEventCode) {
            case MtpConstants.EVENT_OBJECT_PROP_CHANGED:
                return mParameter2;
            case MtpConstants.EVENT_OBJECT_PROP_DESC_CHANGED:
                return mParameter1;
            default:
                throw new IllegalParameterAccess("objectPropCode", mEventCode);
        }
    }

    /**
     * Obtains objectFormatCode event parameter.
     *
     * @see MtpConstants#EVENT_OBJECT_PROP_DESC_CHANGED
     */
    public int getObjectFormatCode() {
        switch (mEventCode) {
            case MtpConstants.EVENT_OBJECT_PROP_DESC_CHANGED:
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
