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

package android.media;

import android.database.AbstractWindowedCursor;
import android.database.CursorWindow;
import android.provider.Mtp;
import android.util.Log;

import java.util.HashMap;

/**
  * Cursor class for MTP content provider
  * @hide
  */
public final class MtpCursor extends AbstractWindowedCursor {
    static final String TAG = "MtpCursor";
    static final int NO_COUNT = -1;

    /* constants for mQueryType */
    public static final int DEVICE              = 1;
    public static final int DEVICE_ID           = 2;
    public static final int STORAGE             = 3;
    public static final int STORAGE_ID          = 4;
    public static final int OBJECT              = 5;
    public static final int OBJECT_ID           = 6;
    public static final int STORAGE_CHILDREN    = 7;
    public static final int OBJECT_CHILDREN     = 8;

    private int mQueryType;
    private int mDeviceID;
    private int mStorageID;
    private int mQbjectID;

    /** The names of the columns in the projection */
    private String[] mColumns;

    /** The number of rows in the cursor */
    private int mCount = NO_COUNT;

    private final MtpClient mClient;

    public MtpCursor(MtpClient client, int queryType, int deviceID, int storageID, int objectID,
            String[] projection) {

        mClient = client;
        mQueryType = queryType;
        mDeviceID = deviceID;
        mStorageID = storageID;
        mQbjectID = objectID;
        mColumns = projection;

        HashMap<String, Integer> map;
        switch (queryType) {
            case DEVICE:
            case DEVICE_ID:
                map = sDeviceProjectionMap;
                break;
            case STORAGE:
            case STORAGE_ID:
                map = sStorageProjectionMap;
                break;
            case OBJECT:
            case OBJECT_ID:
            case STORAGE_CHILDREN:
            case OBJECT_CHILDREN:
                map = sObjectProjectionMap;
                break;
            default:
                throw new IllegalArgumentException("unknown query type "  + queryType);
        }

        int[] columns = new int[projection.length];
        for (int i = 0; i < projection.length; i++) {
            Integer id = map.get(projection[i]);
            if (id == null) {
                throw new IllegalArgumentException("unknown column "  + projection[i]);
            }
            columns[i] = id.intValue();
        }
        native_setup(client, queryType, deviceID, storageID, objectID, columns);
    }

    @Override
    protected void finalize() {
        native_finalize();
    }

    @Override
    public int getCount() {
        if (mCount == NO_COUNT) {
            fillWindow(0);
        }
        return mCount;
    }

    private void fillWindow(int startPos) {
        if (mWindow == null) {
            // If there isn't a window set already it will only be accessed locally
            mWindow = new CursorWindow(true /* the window is local only */);
        } else {
                mWindow.clear();
        }
        mWindow.setStartPosition(startPos);
        mCount = native_fill_window(mWindow, startPos);
    }

    @Override
    public String[] getColumnNames() {
        Log.d(TAG, "getColumnNames returning " + mColumns);
        return mColumns;
    }

    /* Device Column IDs */
    private static final int DEVICE_ROW_ID          = 1;
    private static final int DEVICE_MANUFACTURER    = 2;
    private static final int DEVICE_MODEL           = 3;

    /* Storage Column IDs */
    private static final int STORAGE_ROW_ID         = 101;
    private static final int STORAGE_IDENTIFIER     = 102;
    private static final int STORAGE_DESCRIPTION    = 103;

    /* Object Column IDs */
    private static final int OBJECT_ROW_ID          = 201;
    private static final int OBJECT_NAME            = 202;

    private static HashMap<String, Integer> sDeviceProjectionMap;
    private static HashMap<String, Integer> sStorageProjectionMap;
    private static HashMap<String, Integer> sObjectProjectionMap;

    static {
        sDeviceProjectionMap = new HashMap<String, Integer>();
        sDeviceProjectionMap.put(Mtp.Device._ID, new Integer(DEVICE_ROW_ID));
        sDeviceProjectionMap.put(Mtp.Device.MANUFACTURER, new Integer(DEVICE_MANUFACTURER));
        sDeviceProjectionMap.put(Mtp.Device.MODEL, new Integer(DEVICE_MODEL));

        sStorageProjectionMap = new HashMap<String, Integer>();
        sStorageProjectionMap.put(Mtp.Storage._ID, new Integer(STORAGE_ROW_ID));
        sStorageProjectionMap.put(Mtp.Storage.IDENTIFIER, new Integer(STORAGE_IDENTIFIER));
        sStorageProjectionMap.put(Mtp.Storage.DESCRIPTION, new Integer(STORAGE_DESCRIPTION));

        sObjectProjectionMap = new HashMap<String, Integer>();
        sObjectProjectionMap.put(Mtp.Object._ID, new Integer(OBJECT_ROW_ID));
        sObjectProjectionMap.put(Mtp.Object.NAME, new Integer(OBJECT_NAME));
    }

    // used by the JNI code
    private int mNativeContext;

    private native final void native_setup(MtpClient client, int queryType,
            int deviceID, int storageID, int objectID, int[] columns);
    private native final void native_finalize();
    private native void native_wait_for_event();
    private native int native_fill_window(CursorWindow window, int startPos);
}
