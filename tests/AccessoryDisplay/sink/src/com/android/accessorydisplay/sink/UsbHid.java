/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.sink;

import java.nio.ByteBuffer;

/**
 * Helper for creating USB HID descriptors and reports.
 */
final class UsbHid {
    private UsbHid() {
    }

    /**
     * Generates basic Windows 7 compatible HID multitouch descriptors and reports
     * that should be supported by recent versions of the Linux hid-multitouch driver.
     */
    public static final class Multitouch {
        private final int mReportId;
        private final int mMaxContacts;
        private final int mWidth;
        private final int mHeight;

        public Multitouch(int reportId, int maxContacts, int width, int height) {
            mReportId = reportId;
            mMaxContacts = maxContacts;
            mWidth = width;
            mHeight = height;
        }

        public void generateDescriptor(ByteBuffer buffer) {
            buffer.put(new byte[] {
                0x05, 0x0d,                         // USAGE_PAGE (Digitizers)
                0x09, 0x04,                         // USAGE (Touch Screen)
                (byte)0xa1, 0x01,                   // COLLECTION (Application)
                (byte)0x85, (byte)mReportId,        //   REPORT_ID (Touch)
                0x09, 0x22,                         //   USAGE (Finger)
                (byte)0xa1, 0x00,                   //   COLLECTION (Physical)
                0x09, 0x55,                         //     USAGE (Contact Count Maximum)
                0x15, 0x00,                         //     LOGICAL_MINIMUM (0)
                0x25, (byte)mMaxContacts,           //     LOGICAL_MAXIMUM (...)
                0x75, 0x08,                         //     REPORT_SIZE (8)
                (byte)0x95, 0x01,                   //     REPORT_COUNT (1)
                (byte)0xb1, (byte)mMaxContacts,     //     FEATURE (Data,Var,Abs)
                0x09, 0x54,                         //     USAGE (Contact Count)
                (byte)0x81, 0x02,                   //     INPUT (Data,Var,Abs)
            });
            byte maxXLsb = (byte)(mWidth - 1);
            byte maxXMsb = (byte)((mWidth - 1) >> 8);
            byte maxYLsb = (byte)(mHeight - 1);
            byte maxYMsb = (byte)((mHeight - 1) >> 8);
            byte[] collection = new byte[] { 
                0x05, 0x0d,                         //     USAGE_PAGE (Digitizers)
                0x09, 0x22,                         //     USAGE (Finger)
                (byte)0xa1, 0x02,                   //     COLLECTION (Logical)
                0x09, 0x42,                         //       USAGE (Tip Switch)
                0x15, 0x00,                         //       LOGICAL_MINIMUM (0)
                0x25, 0x01,                         //       LOGICAL_MAXIMUM (1)
                0x75, 0x01,                         //       REPORT_SIZE (1)
                (byte)0x81, 0x02,                   //       INPUT (Data,Var,Abs)
                0x09, 0x32,                         //       USAGE (In Range)
                (byte)0x81, 0x02,                   //       INPUT (Data,Var,Abs)
                0x09, 0x51,                         //       USAGE (Contact Identifier)
                0x25, 0x3f,                         //       LOGICAL_MAXIMUM (63)
                0x75, 0x06,                         //       REPORT_SIZE (6)
                (byte)0x81, 0x02,                   //       INPUT (Data,Var,Abs)
                0x05, 0x01,                         //       USAGE_PAGE (Generic Desktop)
                0x09, 0x30,                         //       USAGE (X)
                0x26, maxXLsb, maxXMsb,             //       LOGICAL_MAXIMUM (...)
                0x75, 0x10,                         //       REPORT_SIZE (16)
                (byte)0x81, 0x02,                   //       INPUT (Data,Var,Abs)
                0x09, 0x31,                         //       USAGE (Y)
                0x26, maxYLsb, maxYMsb,             //       LOGICAL_MAXIMUM (...)
                (byte)0x81, 0x02,                   //       INPUT (Data,Var,Abs)
                (byte)0xc0,                         //     END_COLLECTION
            };
            for (int i = 0; i < mMaxContacts; i++) {
                buffer.put(collection);
            }
            buffer.put(new byte[] {
                (byte)0xc0,                         //   END_COLLECTION
                (byte)0xc0,                         // END_COLLECTION
            });
        }

        public void generateReport(ByteBuffer buffer, Contact[] contacts, int contactCount) {
            // Report Id
            buffer.put((byte)mReportId);
            // Contact Count
            buffer.put((byte)contactCount);

            for (int i = 0; i < contactCount; i++) {
                final Contact contact = contacts[i];
                // Tip Switch, In Range, Contact Identifier
                buffer.put((byte)((contact.id << 2) | 0x03));
                // X
                buffer.put((byte)contact.x).put((byte)(contact.x >> 8));
                // Y
                buffer.put((byte)contact.y).put((byte)(contact.y >> 8));
            }
            for (int i = contactCount; i < mMaxContacts; i++) {
                buffer.put((byte)0).put((byte)0).put((byte)0).put((byte)0).put((byte)0);
            }
        }

        public int getReportSize() {
            return 2 + mMaxContacts * 5;
        }

        public static final class Contact {
            public int id; // range 0..63
            public int x;
            public int y;
        }
    }
}
