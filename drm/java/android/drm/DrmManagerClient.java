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
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Interface of DRM Framework.
 * Java application will instantiate this class
 * to access DRM agent through DRM Framework.
 *
 */
public class DrmManagerClient {
    /**
     * Constant field signifies the success or no error occurred
     */
    public static final int ERROR_NONE = 0;
    /**
     * Constant field signifies that error occurred and the reason is not known
     */
    public static final int ERROR_UNKNOWN = -2000;

    private static final String TAG = "DrmManagerClient";

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

    /**
     * Interface definition of a callback to be invoked to communicate
     * the result of time consuming APIs asynchronously
     */
    public interface OnEventListener {
        /**
         * Called to indicate the result of asynchronous APIs.
         *
         * @param client DrmManagerClient instance
         * @param event instance which wraps type and message
         * @param attributes resultant values in key and value pair.
         */
        public void onEvent(DrmManagerClient client, DrmEvent event,
                HashMap<String, Object> attributes);
    }

    /**
     * Interface definition of a callback to be invoked to communicate
     * the error occurred
     */
    public interface OnErrorListener {
        /**
         * Called to indicate the error occurred.
         *
         * @param client DrmManagerClient instance
         * @param event instance which wraps error type and message
         */
        public void onError(DrmManagerClient client, DrmErrorEvent event);
    }

    private static final int STATE_UNINITIALIZED = 0;
    private static final int STATE_INITIALIZED = 1;

    private static final int ACTION_INITIALIZE = 1000;
    private static final int ACTION_FINALIZE = 1001;
    private static final int ACTION_REMOVE_ALL_RIGHTS = 1002;
    private static final int ACTION_ACQUIRE_DRM_INFO = 1003;
    private static final int ACTION_PROCESS_DRM_INFO = 1004;

    private int mUniqueId;
    private int mNativeContext;
    private Context mContext;
    private InfoHandler mInfoHandler;
    private EventHandler mEventHandler;
    private OnInfoListener mOnInfoListener;
    private OnEventListener mOnEventListener;
    private OnErrorListener mOnErrorListener;
    private int mCurrentState = STATE_UNINITIALIZED;

    private class EventHandler extends Handler {

        public EventHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            DrmEvent event = null;
            DrmErrorEvent error = null;
            HashMap<String, Object> attributes = new HashMap<String, Object>();

            switch(msg.what) {
            case ACTION_INITIALIZE: {
                if (ERROR_NONE == _loadPlugIns(mUniqueId, msg.obj)) {
                    mCurrentState = STATE_INITIALIZED;
                    event = new DrmEvent(mUniqueId, DrmEvent.TYPE_INITIALIZED, null);
                } else {
                    error = new DrmErrorEvent(mUniqueId,
                            DrmErrorEvent.TYPE_INITIALIZE_FAILED, null);
                }
                break;
            }
            case ACTION_ACQUIRE_DRM_INFO: {
                final DrmInfoRequest request = (DrmInfoRequest) msg.obj;
                DrmInfo drmInfo = _acquireDrmInfo(mUniqueId, request);
                if (null != drmInfo) {
                    attributes.put(DrmEvent.DRM_INFO_OBJECT, drmInfo);
                    event = new DrmEvent(mUniqueId, DrmEvent.TYPE_DRM_INFO_ACQUIRED, null);
                } else {
                    error = new DrmErrorEvent(mUniqueId,
                            DrmErrorEvent.TYPE_DRM_INFO_ACQUISITION_FAILED, null);
                }
                break;
            }
            case ACTION_PROCESS_DRM_INFO: {
                final DrmInfo drmInfo = (DrmInfo) msg.obj;
                DrmInfoStatus status = _processDrmInfo(mUniqueId, drmInfo);
                if (null != status && DrmInfoStatus.STATUS_OK == status.statusCode) {
                    attributes.put(DrmEvent.DRM_INFO_STATUS_OBJECT, status);
                    event = new DrmEvent(mUniqueId, getEventType(drmInfo.getInfoType()), null);
                } else {
                    error = new DrmErrorEvent(mUniqueId,
                            getErrorType(drmInfo.getInfoType()), null);
                }
                break;
            }
            case ACTION_REMOVE_ALL_RIGHTS: {
                if (ERROR_NONE == _removeAllRights(mUniqueId)) {
                    event = new DrmEvent(mUniqueId, DrmEvent.TYPE_ALL_RIGHTS_REMOVED, null);
                } else {
                    error = new DrmErrorEvent(mUniqueId,
                            DrmErrorEvent.TYPE_REMOVE_ALL_RIGHTS_FAILED, null);
                }
                break;
            }
            case ACTION_FINALIZE: {
                if (ERROR_NONE == _unloadPlugIns(mUniqueId)) {
                    mCurrentState = STATE_UNINITIALIZED;
                    event = new DrmEvent(mUniqueId, DrmEvent.TYPE_FINALIZED, null);
                } else {
                    error = new DrmErrorEvent(mUniqueId,
                            DrmErrorEvent.TYPE_FINALIZE_FAILED, null);
                }
                break;
            }
            default:
                Log.e(TAG, "Unknown message type " + msg.what);
                return;
            }
            if (null != mOnEventListener && null != event) {
                mOnEventListener.onEvent(DrmManagerClient.this, event, attributes);
            }
            if (null != mOnErrorListener && null != error) {
                mOnErrorListener.onError(DrmManagerClient.this, error);
            }
        }
    }

    /**
     * {@hide}
     */
    public static void notify(
            Object thisReference, int uniqueId, int infoType, String message) {
        DrmManagerClient instance = (DrmManagerClient)((WeakReference)thisReference).get();

        if (null != instance && null != instance.mInfoHandler) {
            Message m = instance.mInfoHandler.obtainMessage(
                InfoHandler.INFO_EVENT_TYPE, uniqueId, infoType, message);
            instance.mInfoHandler.sendMessage(m);
        }
    }

    private class InfoHandler extends Handler {
        public static final int INFO_EVENT_TYPE = 1;

        public InfoHandler(Looper looper) {
            super(looper);
        }

        public void handleMessage(Message msg) {
            DrmInfoEvent event = null;
            DrmErrorEvent error = null;

            switch (msg.what) {
            case InfoHandler.INFO_EVENT_TYPE:
                int uniqueId = msg.arg1;
                int infoType = msg.arg2;
                String message = msg.obj.toString();

                switch (infoType) {
                case DrmInfoEvent.TYPE_REMOVE_RIGHTS: {
                    try {
                        DrmUtils.removeFile(message);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    event = new DrmInfoEvent(uniqueId, infoType, message);
                    break;
                }
                case DrmInfoEvent.TYPE_ALREADY_REGISTERED_BY_ANOTHER_ACCOUNT: {
                    event = new DrmInfoEvent(uniqueId, infoType, message);
                    break;
                }
                default:
                    error = new DrmErrorEvent(uniqueId, infoType, message);
                    break;
                }

                if (null != mOnInfoListener && null != event) {
                    mOnInfoListener.onInfo(DrmManagerClient.this, event);
                }
                if (null != mOnErrorListener && null != error) {
                    mOnErrorListener.onError(DrmManagerClient.this, error);
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
        mContext = context;
        Looper looper;

        if (null != (looper = Looper.myLooper())) {
            mInfoHandler = new InfoHandler(looper);
        } else if (null != (looper = Looper.getMainLooper())) {
            mInfoHandler = new InfoHandler(looper);
        } else {
            mInfoHandler = null;
        }

        HandlerThread thread = new HandlerThread("DrmManagerClient.EventHandler");
        thread.start();
        mEventHandler = new EventHandler(thread.getLooper());

        // save the unique id
        mUniqueId = hashCode();
    }

    /**
     * Register a callback to be invoked when the caller required to receive
     * supplementary information.
     *
     * @param infoListener
     */
    public synchronized void setOnInfoListener(OnInfoListener infoListener) {
        if (null != infoListener) {
            mOnInfoListener = infoListener;
        }
    }

    /**
     * Register a callback to be invoked when the caller required to receive
     * the result of asynchronous APIs.
     *
     * @param eventListener
     */
    public synchronized void setOnEventListener(OnEventListener eventListener) {
        if (null != eventListener) {
            mOnEventListener = eventListener;
        }
    }

    /**
     * Register a callback to be invoked when the caller required to receive
     * error result of asynchronous APIs.
     *
     * @param errorListener
     */
    public synchronized void setOnErrorListener(OnErrorListener errorListener) {
        if (null != errorListener) {
            mOnErrorListener = errorListener;
        }
    }

    /**
     * Initializes DrmFramework, which loads all available plug-ins
     * in the default plug-in directory path
     *
     * @return
     *     ERROR_NONE for success
     *     ERROR_UNKNOWN for failure
     */
    public int loadPlugIns() {
        int result = ERROR_UNKNOWN;
        if (STATE_UNINITIALIZED == getState()) {
            if (null != mEventHandler) {
                Message msg = mEventHandler.obtainMessage(
                        ACTION_INITIALIZE, new WeakReference<DrmManagerClient>(this));
                result = (mEventHandler.sendMessage(msg)) ? ERROR_NONE : result;
            }
        } else {
            result = ERROR_NONE;
        }
        return result;
    }

    /**
     * Finalize DrmFramework, which release resources associated with each plug-in
     * and unload all plug-ins.
     *
     * @return
     *     ERROR_NONE for success
     *     ERROR_UNKNOWN for failure
     */
    public int unloadPlugIns() {
        int result = ERROR_UNKNOWN;
        if (STATE_INITIALIZED == getState()) {
            if (null != mEventHandler) {
                Message msg = mEventHandler.obtainMessage(ACTION_FINALIZE);
                result = (mEventHandler.sendMessage(msg)) ? ERROR_NONE : result;
            }
        } else {
            result = ERROR_NONE;
        }
        return result;
    }

    /**
     * Retrieves informations about all the plug-ins registered with DrmFramework.
     *
     * @return Array of DrmEngine plug-in strings
     */
    public String[] getAvailableDrmEngines() {
        if (STATE_UNINITIALIZED == getState()) {
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
        } else if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _getConstraints(mUniqueId, path, action);
    }

    /**
     * Get constraints information evaluated from DRM content
     *
     * @param uri The Content URI of the data
     * @param action Actions defined in {@link DrmStore.Action}
     * @return ContentValues instance in which constraints key-value pairs are embedded
     *         or null in case of failure
     */
    public ContentValues getConstraints(Uri uri, int action) {
        return getConstraints(convertUriToPath(uri), action);
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
     * @return
     *     ERROR_NONE for success
     *     ERROR_UNKNOWN for failure
     * @throws IOException if failed to save rights information in the given path
     */
    public int saveRights(
            DrmRights drmRights, String rightsPath, String contentPath) throws IOException {
        if (null == drmRights || !drmRights.isValid()) {
            throw new IllegalArgumentException("Given drmRights or contentPath is not valid");
        } else if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        if (null != rightsPath && !rightsPath.equals("")) {
            DrmUtils.writeToFile(rightsPath, drmRights.getData());
        }
        return _saveRights(mUniqueId, drmRights, rightsPath, contentPath);
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
        } else if (STATE_UNINITIALIZED == getState()) {
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
     *        true - if the given mimeType or path can be handled
     *        false - cannot be handled. false will be return in case
     *        the state is uninitialized
     */
    public boolean canHandle(String path, String mimeType) {
        if ((null == path || path.equals("")) && (null == mimeType || mimeType.equals(""))) {
            throw new IllegalArgumentException("Path or the mimetype should be non null");
        } else if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _canHandle(mUniqueId, path, mimeType);
    }

    /**
     * Check whether the given mimetype or uri can be handled.
     *
     * @param uri The content URI of the data
     * @param mimeType Mimetype of the object to be handled
     * @return
     *        true - if the given mimeType or path can be handled
     *        false - cannot be handled. false will be return in case
     *        the state is uninitialized
     */
    public boolean canHandle(Uri uri, String mimeType) {
        if ((null == uri || Uri.EMPTY == uri) && (null == mimeType || mimeType.equals(""))) {
            throw new IllegalArgumentException("Uri or the mimetype should be non null");
        }
        return canHandle(convertUriToPath(uri), mimeType);
    }

    /**
     * Executes given drm information based on its type
     *
     * @param drmInfo Information needs to be processed
     * @return
     *     ERROR_NONE for success
     *     ERROR_UNKNOWN for failure
     */
    public int processDrmInfo(DrmInfo drmInfo) {
        if (null == drmInfo || !drmInfo.isValid()) {
            throw new IllegalArgumentException("Given drmInfo is invalid/null");
        } else if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        int result = ERROR_UNKNOWN;
        if (null != mEventHandler) {
            Message msg = mEventHandler.obtainMessage(ACTION_PROCESS_DRM_INFO, drmInfo);
            result = (mEventHandler.sendMessage(msg)) ? ERROR_NONE : result;
        }
        return result;
    }

    /**
     * Retrieves necessary information for register, unregister or rights acquisition.
     *
     * @param drmInfoRequest Request information to retrieve drmInfo
     * @return
     *     ERROR_NONE for success
     *     ERROR_UNKNOWN for failure
     */
    public int acquireDrmInfo(DrmInfoRequest drmInfoRequest) {
        if (null == drmInfoRequest || !drmInfoRequest.isValid()) {
            throw new IllegalArgumentException("Given drmInfoRequest is invalid/null");
        } else if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        int result = ERROR_UNKNOWN;
        if (null != mEventHandler) {
            Message msg = mEventHandler.obtainMessage(ACTION_ACQUIRE_DRM_INFO, drmInfoRequest);
            result = (mEventHandler.sendMessage(msg)) ? ERROR_NONE : result;
        }
        return result;
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
        } else if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _getDrmObjectType(mUniqueId, path, mimeType);
    }

    /**
     * Retrieves the type of the protected object (content, rights, etc..)
     * using specified uri or mimetype. At least one parameter should be non null
     * to retrieve DRM object type
     *
     * @param uri The content URI of the data
     * @param mimeType Mimetype of the content or null.
     * @return Type of the DRM content.
     * @see DrmStore.DrmObjectType
     */
    public int getDrmObjectType(Uri uri, String mimeType) {
        if ((null == uri || Uri.EMPTY == uri) && (null == mimeType || mimeType.equals(""))) {
            throw new IllegalArgumentException("Uri or the mimetype should be non null");
        }
        String path = "";
        try {
            path = convertUriToPath(uri);
        } catch (Exception e) {
            // Even uri is invalid the mimetype shall be valid, so allow to proceed further.
            Log.w(TAG, "Given Uri could not be found in media store");
        }
        return getDrmObjectType(path, mimeType);
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
        } else if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _getOriginalMimeType(mUniqueId, path);
    }

    /**
     * Retrieves the mime type embedded inside the original content
     *
     * @param uri The content URI of the data
     * @return Mimetype of the original content, such as "video/mpeg"
     */
    public String getOriginalMimeType(Uri uri) {
        if (null == uri || Uri.EMPTY == uri) {
            throw new IllegalArgumentException("Given uri is not valid");
        }
        return getOriginalMimeType(convertUriToPath(uri));
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
     * Check whether the given content has valid rights or not
     *
     * @param uri The content URI of the data
     * @return Status of the rights for the protected content
     * @see DrmStore.RightsStatus
     */
    public int checkRightsStatus(Uri uri) {
        if (null == uri || Uri.EMPTY == uri) {
            throw new IllegalArgumentException("Given uri is not valid");
        }
        return checkRightsStatus(convertUriToPath(uri));
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
        } else if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _checkRightsStatus(mUniqueId, path, action);
    }

    /**
     * Check whether the given content has valid rights or not for specified action.
     *
     * @param uri The content URI of the data
     * @param action Action to perform
     * @return Status of the rights for the protected content
     * @see DrmStore.RightsStatus
     */
    public int checkRightsStatus(Uri uri, int action) {
        if (null == uri || Uri.EMPTY == uri) {
            throw new IllegalArgumentException("Given uri is not valid");
        }
        return checkRightsStatus(convertUriToPath(uri), action);
    }

    /**
     * Removes the rights associated with the given protected content
     *
     * @param path Path of the protected content
     * @return
     *     ERROR_NONE for success
     *     ERROR_UNKNOWN for failure
     */
    public int removeRights(String path) {
        if (null == path || path.equals("")) {
            throw new IllegalArgumentException("Given path should be non null");
        } else if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _removeRights(mUniqueId, path);
    }

    /**
     * Removes the rights associated with the given protected content
     *
     * @param uri The content URI of the data
     * @return
     *     ERROR_NONE for success
     *     ERROR_UNKNOWN for failure
     */
    public int removeRights(Uri uri) {
        if (null == uri || Uri.EMPTY == uri) {
            throw new IllegalArgumentException("Given uri is not valid");
        }
        return removeRights(convertUriToPath(uri));
    }

    /**
     * Removes all the rights information of every plug-in associated with
     * DRM framework. Will be used in master reset
     *
     * @return
     *     ERROR_NONE for success
     *     ERROR_UNKNOWN for failure
     */
    public int removeAllRights() {
        if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        int result = ERROR_UNKNOWN;
        if (null != mEventHandler) {
            Message msg = mEventHandler.obtainMessage(ACTION_REMOVE_ALL_RIGHTS);
            result = (mEventHandler.sendMessage(msg)) ? ERROR_NONE : result;
        }
        return result;
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
        } else if (STATE_UNINITIALIZED == getState()) {
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
        } else if (STATE_UNINITIALIZED == getState()) {
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
        if (STATE_UNINITIALIZED == getState()) {
            throw new IllegalStateException("Not Initialized yet");
        }
        return _closeConvertSession(mUniqueId, convertId);
    }

    private int getState() {
        return mCurrentState;
    }

    private int getEventType(int infoType) {
        int eventType = -1;

        switch (infoType) {
        case DrmInfoRequest.TYPE_REGISTRATION_INFO:
            eventType = DrmEvent.TYPE_REGISTERED;
            break;
        case DrmInfoRequest.TYPE_UNREGISTRATION_INFO:
            eventType = DrmEvent.TYPE_UNREGISTERED;
            break;
        case DrmInfoRequest.TYPE_RIGHTS_ACQUISITION_INFO:
            eventType = DrmEvent.TYPE_RIGHTS_ACQUIRED;
            break;
        }
        return eventType;
    }

    private int getErrorType(int infoType) {
        int error = -1;

        switch (infoType) {
        case DrmInfoRequest.TYPE_REGISTRATION_INFO:
            error = DrmErrorEvent.TYPE_REGISTRATION_FAILED;
            break;
        case DrmInfoRequest.TYPE_UNREGISTRATION_INFO:
            error = DrmErrorEvent.TYPE_UNREGISTRATION_FAILED;
            break;
        case DrmInfoRequest.TYPE_RIGHTS_ACQUISITION_INFO:
            error = DrmErrorEvent.TYPE_RIGHTS_ACQUISITION_FAILED;
            break;
        }
        return error;
    }

    /**
     * This method expects uri in the following format
     *     content://media/<table_name>/<row_index> (or)
     *     file://sdcard/test.mp4
     *
     * Here <table_name> shall be "video" or "audio" or "images"
     * <row_index> the index of the content in given table
     */
    private String convertUriToPath(Uri uri) {
        String scheme = uri.getScheme();
        if (null == scheme || scheme.equals("file")) {
            return uri.getPath();
        }
        String[] projection = new String[] {MediaStore.MediaColumns.DATA};
        Cursor cursor = mContext.getContentResolver().query(uri, projection, null, null, null);
        if (null == cursor || 0 == cursor.getCount() || !cursor.moveToFirst()) {
            throw new IllegalArgumentException("Given Uri could not be found in media store");
        }
        int pathIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA);
        String path = cursor.getString(pathIndex);
        cursor.close();
        return path;
    }

    // private native interfaces
    private native int _loadPlugIns(int uniqueId, Object weak_this);

    private native int _unloadPlugIns(int uniqueId);

    private native void _installDrmEngine(int uniqueId, String engineFilepath);

    private native ContentValues _getConstraints(int uniqueId, String path, int usage);

    private native boolean _canHandle(int uniqueId, String path, String mimeType);

    private native DrmInfoStatus _processDrmInfo(int uniqueId, DrmInfo drmInfo);

    private native DrmInfo _acquireDrmInfo(int uniqueId, DrmInfoRequest drmInfoRequest);

    private native int _saveRights(
            int uniqueId, DrmRights drmRights, String rightsPath, String contentPath);

    private native int _getDrmObjectType(int uniqueId, String path, String mimeType);

    private native String _getOriginalMimeType(int uniqueId, String path);

    private native int _checkRightsStatus(int uniqueId, String path, int action);

    private native int _removeRights(int uniqueId, String path);

    private native int _removeAllRights(int uniqueId);

    private native int _openConvertSession(int uniqueId, String mimeType);

    private native DrmConvertedStatus _convertData(
            int uniqueId, int convertId, byte[] inputData);

    private native DrmConvertedStatus _closeConvertSession(int uniqueId, int convertId);

    private native DrmSupportInfo[] _getAllSupportInfo(int uniqueId);
}

