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

package android.drm;

import android.content.ContentValues;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Interface of DRM Framework.
 * Java application will instantiate this class
 * to access DRM agent through DRM Framework.
 *
 */
public class DrmManagerClient {
    private static final String TAG = "DrmManager";

    static {
        // Load the respective library
        System.loadLibrary("drmframework_jni");
    }

    /**
     * Interface definition of a callback to be invoked to communicate
     * some info and/or warning about DrmManagerClient.
     */
    public interface OnInfoListener {
        /**
         * Called to indicate an info or a warning.
         *
         * @param client DrmManagerClient instance
         * @param event instance which wraps reason and necessary information
         */
        public void onInfo(DrmManagerClient client, DrmInfoEvent event);
    }

    private static final int STATE_UNINITIALIZED = 0x00000000;
    private static final int STATE_INITIALIZED = 0x00000001;

    private int mUniqueId;
    private int mNativeContext;
    private EventHandler mEventHandler;
    private OnInfoListener mOnInfoListener;
    private int mCurrentState = STATE_UNINITIALIZED;

    /**
     * {@hide}
     */
    public static void notify(Object thisReference, int uniqueId, int infoType, String message) {
        DrmManagerClient instance = (DrmManagerClient)((WeakReference)thisReference).get();

        if (null != instance && null != instance.mEventHandler) {
            Message m = instance.mEventHandler.obtainMessage(
                EventHandler.INFO_EVENT_TYPE, uniqueId, infoType, message);
            instance.mEventHandler.sendMessage(m);
        }
    }

    private class EventHandler extends Handler {
        public static final int INFO_EVENT_TYPE = 1;

        public EventHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {

            switch (msg.what) {
            case EventHandler.INFO_EVENT_TYPE:
                int uniqueId = msg.arg1;
                int infoType = msg.arg2;
                String message = msg.obj.toString();

                if (infoType == DrmInfoEvent.TYPE_REMOVE_RIGHTS) {
                    try {
                        DrmUtils.removeFile(message);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                if (null != mOnInfoListener) {
                    DrmInfoEvent event = new DrmInfoEvent(uniqueId, infoType, message);
                    mOnInfoListener.onInfo(DrmManagerClient.this, event);
                }
                return;
            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
        }
    }

    /**
     * To instantiate DrmManagerClient
     *
     * @param context context of the caller
     */
    public DrmManagerClient(Context context) {
        Looper looper;

        if (null != (looper = Looper.myLooper())) {
            mEventHandler = new EventHandler(looper);
        } else if (null != (looper = Looper.getMainLooper())) {
            mEventHandler = new EventHandler(looper);
        } else {
            mEventHandler = null;
        }

        // save the unique id
        mUniqueId = hashCode();
    }

    /**
     * Register a callback to be invoked when the caller required to receive
     * necessary information
     *
     * @param infoListener
     */
    public void setOnInfoListener(OnInfoListener infoListener) {
        synchronized(this) {
            if (null != infoListener) {
                mOnInfoListener = infoListener;
            }
        }
    }

    /**
     * Initializes DrmFramework, which loads all available plug-ins
     * in the default plug-in directory path
     *
     */
    public void loadPlugIns() {
        if (getState() == STATE_UNINITIALIZED) {
            _loadPlugIns(mUniqueId, new WeakReference<DrmManagerClient>(this));

            mCurrentState = STATE_INITIALIZED;
        }
    }

    /**
     * Finalize DrmFramework, which release resources associated with each plug-in
     * and unload all plug-ins.
     */
    public void unloadPlugIns() {
        if (getState() == STATE_INITIALIZED) {
            _unloadPlugIns(mUniqueId);

            mCurrentState = STATE_UNINITIALIZED;
        }
    }

    /**
     * Retrieves informations about all the plug-ins registered with DrmFramework.
     *
     * @return Array of DrmEngine plug-in strings
     */
    public String[] getAvailableDrmEngines() {
        if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }

        DrmSupportInfo[] supportInfos = _getAllSupportInfo(mUniqueId);
        ArrayList<String> descriptions = new ArrayList<String>();

        for (int i = 0; i < supportInfos.length; i++) {
            descriptions.add(supportInfos[i].getDescriprition());
        }

        String[] drmEngines = new String[descriptions.size()];
        return descriptions.toArray(drmEngines);
    }

    /**
     * Get constraints information evaluated from DRM content
     *
     * @param path Content path from where DRM constraints would be retrieved.
     * @param action Actions defined in {@link DrmStore.Action}
     * @return ContentValues instance in which constraints key-value pairs are embedded
     *         or null in case of failure
     */
    public ContentValues getConstraints(String path, int action) {
        if (null == path || path.equals("") || !DrmStore.Action.isValid(action)) {
            throw new IllegalArgumentException("Given usage or path is invalid/null");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _getConstraints(mUniqueId, path, action);
    }

    /**
     * Save DRM rights to specified rights path
     * and make association with content path.
     *
     * <p class="note">In case of OMA or WM-DRM, rightsPath and contentPath could be null.</p>
     *
     * @param drmRights DrmRights to be saved
     * @param rightsPath File path where rights to be saved
     * @param contentPath File path where content was saved
     * @throws IOException if failed to save rights information in the given path
     */
    public void saveRights(
            DrmRights drmRights, String rightsPath, String contentPath) throws IOException {
        if (null == drmRights || !drmRights.isValid()
            || null == contentPath || contentPath.equals("")) {
            throw new IllegalArgumentException("Given drmRights or contentPath is not valid");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        if (null != rightsPath && !rightsPath.equals("")) {
            DrmUtils.writeToFile(rightsPath, drmRights.getData());
        }
        _saveRights(mUniqueId, drmRights, rightsPath, contentPath);
    }

    /**
     * Install new DRM Engine Plug-in at the runtime
     *
     * @param engineFilePath Path of the plug-in file to be installed
     * {@hide}
     */
    public void installDrmEngine(String engineFilePath) {
        if (null == engineFilePath || engineFilePath.equals("")) {
            throw new IllegalArgumentException(
                "Given engineFilePath: "+ engineFilePath + "is not valid");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        _installDrmEngine(mUniqueId, engineFilePath);
    }

    /**
     * Check whether the given mimetype or path can be handled.
     *
     * @param path Path of the content to be handled
     * @param mimeType Mimetype of the object to be handled
     * @return
     *        true - if the given mimeType or path can be handled.
     *        false - cannot be handled.  false will be returned in case
     *        the state is uninitialized
     */
    public boolean canHandle(String path, String mimeType) {
        if ((null == path || path.equals("")) && (null == mimeType || mimeType.equals(""))) {
            throw new IllegalArgumentException("Path or the mimetype should be non null");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _canHandle(mUniqueId, path, mimeType);
    }

    /**
     * Executes given drm information based on its type
     *
     * @param drmInfo Information needs to be processed
     * @return DrmInfoStatus Instance as a result of processing given input
     */
    public DrmInfoStatus processDrmInfo(DrmInfo drmInfo) {
        if (null == drmInfo || !drmInfo.isValid()) {
            throw new IllegalArgumentException("Given drmInfo is invalid/null");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _processDrmInfo(mUniqueId, drmInfo);
    }

    /**
     * Retrieves necessary information for register, unregister or rights acquisition.
     *
     * @param drmInfoRequest Request information to retrieve drmInfo
     * @return DrmInfo Instance as a result of processing given input
     */
    public DrmInfo acquireDrmInfo(DrmInfoRequest drmInfoRequest) {
        if (null == drmInfoRequest || !drmInfoRequest.isValid()) {
            throw new IllegalArgumentException("Given drmInfoRequest is invalid/null");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _acquireDrmInfo(mUniqueId, drmInfoRequest);
    }

    /**
     * Retrieves the type of the protected object (content, rights, etc..)
     * using specified path or mimetype. At least one parameter should be non null
     * to retrieve DRM object type
     *
     * @param path Path of the content or null.
     * @param mimeType Mimetype of the content or null.
     * @return Type of the DRM content.
     * @see DrmStore.DrmObjectType
     */
    public int getDrmObjectType(String path, String mimeType) {
        if ((null == path || path.equals("")) && (null == mimeType || mimeType.equals(""))) {
            throw new IllegalArgumentException("Path or the mimetype should be non null");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _getDrmObjectType(mUniqueId, path, mimeType);
    }

    /**
     * Retrieves the mime type embedded inside the original content
     *
     * @param path Path of the protected content
     * @return Mimetype of the original content, such as "video/mpeg"
     */
    public String getOriginalMimeType(String path) {
        if (null == path || path.equals("")) {
            throw new IllegalArgumentException("Given path should be non null");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _getOriginalMimeType(mUniqueId, path);
    }

    /**
     * Check whether the given content has valid rights or not
     *
     * @param path Path of the protected content
     * @return Status of the rights for the protected content
     * @see DrmStore.RightsStatus
     */
    public int checkRightsStatus(String path) {
        return checkRightsStatus(path, DrmStore.Action.DEFAULT);
    }

    /**
     * Check whether the given content has valid rights or not for specified action.
     *
     * @param path Path of the protected content
     * @param action Action to perform
     * @return Status of the rights for the protected content
     * @see DrmStore.RightsStatus
     */
    public int checkRightsStatus(String path, int action) {
        if (null == path || path.equals("") || !DrmStore.Action.isValid(action)) {
            throw new IllegalArgumentException("Given path or action is not valid");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _checkRightsStatus(mUniqueId, path, action);
    }

    /**
     * Removes the rights associated with the given protected content
     *
     * @param path Path of the protected content
     */
    public void removeRights(String path) {
        if (null == path || path.equals("")) {
            throw new IllegalArgumentException("Given path should be non null");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        _removeRights(mUniqueId, path);
    }

    /**
     * Removes all the rights information of every plug-in associated with
     * DRM framework. Will be used in master reset
     */
    public void removeAllRights() {
        if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        _removeAllRights(mUniqueId);
    }

    /**
     * This API is for Forward Lock based DRM scheme.
     * Each time the application tries to download a new DRM file
     * which needs to be converted, then the application has to
     * begin with calling this API.
     *
     * @param mimeType Description/MIME type of the input data packet
     * @return convert ID which will be used for maintaining convert session.
     */
    public int openConvertSession(String mimeType) {
        if (null == mimeType || mimeType.equals("")) {
            throw new IllegalArgumentException("Path or the mimeType should be non null");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _openConvertSession(mUniqueId, mimeType);
    }

    /**
     * Accepts and converts the input data which is part of DRM file.
     * The resultant converted data and the status is returned in the DrmConvertedInfo
     * object. This method will be called each time there are new block
     * of data received by the application.
     *
     * @param convertId Handle for the convert session
     * @param inputData Input Data which need to be converted
     * @return Return object contains the status of the data conversion,
     *         the output converted data and offset. In this case the
     *         application will ignore the offset information.
     */
    public DrmConvertedStatus convertData(int convertId, byte[] inputData) {
        if (null == inputData || 0 >= inputData.length) {
            throw new IllegalArgumentException("Given inputData should be non null");
        } else if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _convertData(mUniqueId, convertId, inputData);
    }

    /**
     * Informs the Drm Agent when there is no more data which need to be converted
     * or when an error occurs. Upon successful conversion of the complete data,
     * the agent will inform that where the header and body signature
     * should be added. This signature appending is needed to integrity
     * protect the converted file.
     *
     * @param convertId Handle for the convert session
     * @return Return object contains the status of the data conversion,
     *     the header and body signature data. It also informs
     *     the application on which offset these signature data should be appended.
     */
    public DrmConvertedStatus closeConvertSession(int convertId) {
        if (getState() == STATE_UNINITIALIZED) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _closeConvertSession(mUniqueId, convertId);
    }

    private int getState() {
        return mCurrentState;
    }

    // private native interfaces
    private native void _loadPlugIns(int uniqueId, Object weak_this);

    private native void _unloadPlugIns(int uniqueId);

    private native void _installDrmEngine(int uniqueId, String engineFilepath);

    private native ContentValues _getConstraints(int uniqueId, String path, int usage);

    private native boolean _canHandle(int uniqueId, String path, String mimeType);

    private native DrmInfoStatus _processDrmInfo(int uniqueId, DrmInfo drmInfo);

    private native DrmInfo _acquireDrmInfo(int uniqueId, DrmInfoRequest drmInfoRequest);

    private native void _saveRights(
            int uniqueId, DrmRights drmRights, String rightsPath, String contentPath);

    private native int _getDrmObjectType(int uniqueId, String path, String mimeType);

    private native String _getOriginalMimeType(int uniqueId, String path);

    private native int _checkRightsStatus(int uniqueId, String path, int action);

    private native void _removeRights(int uniqueId, String path);

    private native void _removeAllRights(int uniqueId);

    private native int _openConvertSession(int uniqueId, String mimeType);

    private native DrmConvertedStatus _convertData(int uniqueId, int convertId, byte[] inputData);

    private native DrmConvertedStatus _closeConvertSession(int uniqueId, int convertId);

    private native DrmSupportInfo[] _getAllSupportInfo(int uniqueId);
}

