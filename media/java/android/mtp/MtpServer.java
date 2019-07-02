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

package android.mtp;

import android.content.Context;
import android.content.SharedPreferences;

import com.android.internal.util.Preconditions;

import libcore.util.HexEncoding;

import java.io.FileDescriptor;
import java.util.Random;

/**
 * Java wrapper for MTP/PTP support as USB responder.
 * {@hide}
 */
public class MtpServer implements Runnable {

    private long mNativeContext; // accessed by native methods
    private final MtpDatabase mDatabase;
    private final Runnable mOnTerminate;
    private final Context mContext;

// It requires "exactly 32 characters, including any leading 0s" in MTP spec
// (5.1.1.14 Serial Number)
    private static final int sID_LEN_BYTES = 16;
    private static final int sID_LEN_STR = (sID_LEN_BYTES * 2);

    static {
        System.loadLibrary("media_jni");
    }

    public MtpServer(
            MtpDatabase database,
            FileDescriptor controlFd,
            boolean usePtp,
            Runnable onTerminate,
            String deviceInfoManufacturer,
            String deviceInfoModel,
            String deviceInfoDeviceVersion) {
        mDatabase = Preconditions.checkNotNull(database);
        mOnTerminate = Preconditions.checkNotNull(onTerminate);
        mContext = mDatabase.getContext();

        final String strID_PREFS_NAME = "mtp-cfg";
        final String strID_PREFS_KEY = "mtp-id";
        String strRandomId = null;
        String deviceInfoSerialNumber;

        SharedPreferences sharedPref =
                mContext.getSharedPreferences(strID_PREFS_NAME, Context.MODE_PRIVATE);
        if (sharedPref.contains(strID_PREFS_KEY)) {
            strRandomId = sharedPref.getString(strID_PREFS_KEY, null);

            // Check for format consistence (regenerate upon corruption)
            if (strRandomId.length() != sID_LEN_STR) {
                strRandomId = null;
            } else {
                // Only accept hex digit
                for (int ii = 0; ii < strRandomId.length(); ii++)
                    if (Character.digit(strRandomId.charAt(ii), 16) == -1) {
                        strRandomId = null;
                        break;
                    }
            }
        }

        if (strRandomId == null) {
            strRandomId = getRandId();
            sharedPref.edit().putString(strID_PREFS_KEY, strRandomId).apply();
        }

        deviceInfoSerialNumber = strRandomId;

        native_setup(
                database,
                controlFd,
                usePtp,
                deviceInfoManufacturer,
                deviceInfoModel,
                deviceInfoDeviceVersion,
                deviceInfoSerialNumber);
        database.setServer(this);
    }

    private String getRandId() {
        Random randomVal = new Random();
        byte[] randomBytes = new byte[sID_LEN_BYTES];

        randomVal.nextBytes(randomBytes);
        return HexEncoding.encodeToString(randomBytes);
    }

    public void start() {
        Thread thread = new Thread(this, "MtpServer");
        thread.start();
    }

    @Override
    public void run() {
        native_run();
        native_cleanup();
        mDatabase.close();
        mOnTerminate.run();
    }

    public void sendObjectAdded(int handle) {
        native_send_object_added(handle);
    }

    public void sendObjectRemoved(int handle) {
        native_send_object_removed(handle);
    }

    public void sendObjectInfoChanged(int handle) {
        native_send_object_info_changed(handle);
    }

    public void sendDevicePropertyChanged(int property) {
        native_send_device_property_changed(property);
    }

    public void addStorage(MtpStorage storage) {
        native_add_storage(storage);
    }

    public void removeStorage(MtpStorage storage) {
        native_remove_storage(storage.getStorageId());
    }

    public static void configure(boolean usePtp) {
        native_configure(usePtp);
    }

    public static native final void native_configure(boolean usePtp);
    private native final void native_setup(
            MtpDatabase database,
            FileDescriptor controlFd,
            boolean usePtp,
            String deviceInfoManufacturer,
            String deviceInfoModel,
            String deviceInfoDeviceVersion,
            String deviceInfoSerialNumber);
    private native final void native_run();
    private native final void native_cleanup();
    private native final void native_send_object_added(int handle);
    private native final void native_send_object_removed(int handle);
    private native final void native_send_object_info_changed(int handle);
    private native final void native_send_device_property_changed(int property);
    private native final void native_add_storage(MtpStorage storage);
    private native final void native_remove_storage(int storageId);
}
