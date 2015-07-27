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

package com.android.mtp;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.test.AndroidTestCase;
import android.test.mock.MockContentResolver;
import android.test.suitebuilder.annotation.SmallTest;

import java.io.IOException;

@SmallTest
public class MtpDocumentsProviderTest extends AndroidTestCase {
    public void testOpenAndCloseDevice() throws Exception {
        final ContentResolver resolver = new ContentResolver();
        final MtpDocumentsProvider provider = new MtpDocumentsProvider();
        provider.onCreateForTesting(new MtpManagerMock(getContext()), resolver);

        provider.openDevice(MtpManagerMock.SUCCESS_DEVICE_ID);
        assertEquals(1, resolver.changeCount);
        provider.closeDevice(MtpManagerMock.SUCCESS_DEVICE_ID);
        assertEquals(2, resolver.changeCount);

        provider.openDevice(MtpManagerMock.FAILURE_DEVICE_ID);
        assertEquals(2, resolver.changeCount);
        provider.closeDevice(MtpManagerMock.FAILURE_DEVICE_ID);
        assertEquals(2, resolver.changeCount);
    }

    public void testCloseAllDevices() {
        final ContentResolver resolver = new ContentResolver();
        final MtpDocumentsProvider provider = new MtpDocumentsProvider();
        provider.onCreateForTesting(new MtpManagerMock(getContext()), resolver);

        provider.closeAllDevices();
        assertEquals(0, resolver.changeCount);

        provider.openDevice(MtpManagerMock.SUCCESS_DEVICE_ID);
        assertEquals(1, resolver.changeCount);

        provider.closeAllDevices();
        assertEquals(2, resolver.changeCount);
    }

    private static class MtpManagerMock extends MtpManager {
        final static int SUCCESS_DEVICE_ID = 1;
        final static int FAILURE_DEVICE_ID = 2;

        private boolean opened = false;

        MtpManagerMock(Context context) {
            super(context);
        }

        @Override
        void openDevice(int deviceId) throws IOException {
            if (deviceId == SUCCESS_DEVICE_ID) {
                opened = true;
            } else {
                throw new IOException();
            }
        }

        @Override
        void closeDevice(int deviceId) throws IOException {
            if (opened && deviceId == SUCCESS_DEVICE_ID) {
                opened = false;
            } else {
                throw new IOException();
            }
        }

        @Override
        int[] getOpenedDeviceIds() {
            if (opened) {
                return new int[] { SUCCESS_DEVICE_ID };
            } else {
                return new int[0];
            }
        }
    }

    private static class ContentResolver extends MockContentResolver {
        int changeCount = 0;

        @Override
        public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork) {
            changeCount++;
        }
    }
}
