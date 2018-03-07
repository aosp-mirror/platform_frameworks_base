/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.MediaCasException.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.ServiceSpecificException;
import android.util.EventLog;
import android.util.Log;
import android.util.Singleton;

/**
 * MediaCas can be used to obtain keys for descrambling protected media streams, in
 * conjunction with {@link android.media.MediaDescrambler}. The MediaCas APIs are
 * designed to support conditional access such as those in the ISO/IEC13818-1.
 * The CA system is identified by a 16-bit integer CA_system_id. The scrambling
 * algorithms are usually proprietary and implemented by vendor-specific CA plugins
 * installed on the device.
 * <p>
 * The app is responsible for constructing a MediaCas object for the CA system it
 * intends to use. The app can query if a certain CA system is supported using static
 * method {@link #isSystemIdSupported}. It can also obtain the entire list of supported
 * CA systems using static method {@link #enumeratePlugins}.
 * <p>
 * Once the MediaCas object is constructed, the app should properly provision it by
 * using method {@link #provision} and/or {@link #processEmm}. The EMMs (Entitlement
 * management messages) can be distributed out-of-band, or in-band with the stream.
 * <p>
 * To descramble elementary streams, the app first calls {@link #openSession} to
 * generate a {@link Session} object that will uniquely identify a session. A session
 * provides a context for subsequent key updates and descrambling activities. The ECMs
 * (Entitlement control messages) are sent to the session via method
 * {@link Session#processEcm}.
 * <p>
 * The app next constructs a MediaDescrambler object, and initializes it with the
 * session using {@link MediaDescrambler#setMediaCasSession}. This ties the
 * descrambler to the session, and the descrambler can then be used to descramble
 * content secured with the session's key, either during extraction, or during decoding
 * with {@link android.media.MediaCodec}.
 * <p>
 * If the app handles sample extraction using its own extractor, it can use
 * MediaDescrambler to descramble samples into clear buffers (if the session's license
 * doesn't require secure decoders), or descramble a small amount of data to retrieve
 * information necessary for the downstream pipeline to process the sample (if the
 * session's license requires secure decoders).
 * <p>
 * If the session requires a secure decoder, a MediaDescrambler needs to be provided to
 * MediaCodec to descramble samples queued by {@link MediaCodec#queueSecureInputBuffer}
 * into protected buffers. The app should use {@link MediaCodec#configure(MediaFormat,
 * android.view.Surface, int, MediaDescrambler)} instead of the normal {@link
 * MediaCodec#configure(MediaFormat, android.view.Surface, MediaCrypto, int)} method
 * to configure MediaCodec.
 * <p>
 * <h3>Using Android's MediaExtractor</h3>
 * <p>
 * If the app uses {@link MediaExtractor}, it can delegate the CAS session
 * management to MediaExtractor by calling {@link MediaExtractor#setMediaCas}.
 * MediaExtractor will take over and call {@link #openSession}, {@link #processEmm}
 * and/or {@link Session#processEcm}, etc.. if necessary.
 * <p>
 * When using {@link MediaExtractor}, the app would still need a MediaDescrambler
 * to use with {@link MediaCodec} if the licensing requires a secure decoder. The
 * session associated with the descrambler of a track can be retrieved by calling
 * {@link MediaExtractor#getCasInfo}, and used to initialize a MediaDescrambler
 * object for MediaCodec.
 * <p>
 * <h3>Listeners</h3>
 * <p>The app may register a listener to receive events from the CA system using
 * method {@link #setEventListener}. The exact format of the event is scheme-specific
 * and is not specified by this API.
 */
public final class MediaCas implements AutoCloseable {
    private static final String TAG = "MediaCas";
    private final ParcelableCasData mCasData = new ParcelableCasData();
    private ICas mICas;
    private EventListener mListener;
    private HandlerThread mHandlerThread;
    private EventHandler mEventHandler;

    private static final Singleton<IMediaCasService> gDefault =
            new Singleton<IMediaCasService>() {
        @Override
        protected IMediaCasService create() {
            return IMediaCasService.Stub.asInterface(
                    ServiceManager.getService("media.cas"));
        }
    };

    static IMediaCasService getService() {
        return gDefault.get();
    }

    private void validateInternalStates() {
        if (mICas == null) {
            throw new IllegalStateException();
        }
    }

    private void cleanupAndRethrowIllegalState() {
        mICas = null;
        throw new IllegalStateException();
    }

    private class EventHandler extends Handler
    {
        private static final int MSG_CAS_EVENT = 0;

        public EventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_CAS_EVENT) {
                mListener.onEvent(MediaCas.this, msg.arg1, msg.arg2, (byte[]) msg.obj);
            }
        }
    }

    private final ICasListener.Stub mBinder = new ICasListener.Stub() {
        @Override
        public void onEvent(int event, int arg, @Nullable byte[] data)
                throws RemoteException {
            mEventHandler.sendMessage(mEventHandler.obtainMessage(
                    EventHandler.MSG_CAS_EVENT, event, arg, data));
        }
    };

    /**
     * Class for parceling byte array data over ICas binder.
     */
    static class ParcelableCasData implements Parcelable {
        private byte[] mData;
        private int mOffset;
        private int mLength;

        ParcelableCasData() {
            mData = null;
            mOffset = mLength = 0;
        }

        private ParcelableCasData(Parcel in) {
            EventLog.writeEvent(0x534e4554, "b/73085795", -1, "");

            mData = in.createByteArray();
            mOffset = 0;
            mLength = (mData == null) ? 0 : mData.length;
        }

        void set(@NonNull byte[] data, int offset, int length) {
            mData = data;
            mOffset = offset;
            mLength = length;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeByteArray(mData, mOffset, mLength);
        }

        public static final Parcelable.Creator<ParcelableCasData> CREATOR
                = new Parcelable.Creator<ParcelableCasData>() {
            public ParcelableCasData createFromParcel(Parcel in) {
                return new ParcelableCasData(in);
            }

            public ParcelableCasData[] newArray(int size) {
                return new ParcelableCasData[size];
            }
        };
    }

    /**
     * Describe a CAS plugin with its CA_system_ID and string name.
     *
     * Returned as results of {@link #enumeratePlugins}.
     *
     */
    public static class PluginDescriptor {
        private final int mCASystemId;
        private final String mName;

        private PluginDescriptor() {
            mCASystemId = 0xffff;
            mName = null;
        }

        PluginDescriptor(int CA_system_id, String name) {
            mCASystemId = CA_system_id;
            mName = name;
        }

        public int getSystemId() {
            return mCASystemId;
        }

        @NonNull
        public String getName() {
            return mName;
        }

        @Override
        public String toString() {
            return "PluginDescriptor {" + mCASystemId + ", " + mName + "}";
        }
    }

    /**
     * Class for an open session with the CA system.
     */
    public final class Session implements AutoCloseable {
        final byte[] mSessionId;

        Session(@NonNull byte[] sessionId) {
            mSessionId = sessionId;
        }

        /**
         * Set the private data for a session.
         *
         * @param data byte array of the private data.
         *
         * @throws IllegalStateException if the MediaCas instance is not valid.
         * @throws MediaCasException for CAS-specific errors.
         * @throws MediaCasStateException for CAS-specific state exceptions.
         */
        public void setPrivateData(@NonNull byte[] data)
                throws MediaCasException {
            validateInternalStates();

            try {
                mICas.setSessionPrivateData(mSessionId, data);
            } catch (ServiceSpecificException e) {
                MediaCasException.throwExceptions(e);
            } catch (RemoteException e) {
                cleanupAndRethrowIllegalState();
            }
        }


        /**
         * Send a received ECM packet to the specified session of the CA system.
         *
         * @param data byte array of the ECM data.
         * @param offset position within data where the ECM data begins.
         * @param length length of the data (starting from offset).
         *
         * @throws IllegalStateException if the MediaCas instance is not valid.
         * @throws MediaCasException for CAS-specific errors.
         * @throws MediaCasStateException for CAS-specific state exceptions.
         */
        public void processEcm(@NonNull byte[] data, int offset, int length)
                throws MediaCasException {
            validateInternalStates();

            try {
                mCasData.set(data, offset, length);
                mICas.processEcm(mSessionId, mCasData);
            } catch (ServiceSpecificException e) {
                MediaCasException.throwExceptions(e);
            } catch (RemoteException e) {
                cleanupAndRethrowIllegalState();
            }
        }

        /**
         * Send a received ECM packet to the specified session of the CA system.
         * This is similar to {@link Session#processEcm(byte[], int, int)}
         * except that the entire byte array is sent.
         *
         * @param data byte array of the ECM data.
         *
         * @throws IllegalStateException if the MediaCas instance is not valid.
         * @throws MediaCasException for CAS-specific errors.
         * @throws MediaCasStateException for CAS-specific state exceptions.
         */
        public void processEcm(@NonNull byte[] data) throws MediaCasException {
            processEcm(data, 0, data.length);
        }

        /**
         * Close the session.
         *
         * @throws IllegalStateException if the MediaCas instance is not valid.
         * @throws MediaCasStateException for CAS-specific state exceptions.
         */
        @Override
        public void close() {
            validateInternalStates();

            try {
                mICas.closeSession(mSessionId);
            } catch (ServiceSpecificException e) {
                MediaCasStateException.throwExceptions(e);
            } catch (RemoteException e) {
                cleanupAndRethrowIllegalState();
            }
        }
    }

    Session createFromSessionId(byte[] sessionId) {
        if (sessionId == null || sessionId.length == 0) {
            return null;
        }
        return new Session(sessionId);
    }

    /**
     * Class for parceling CAS plugin descriptors over IMediaCasService binder.
     */
    static class ParcelableCasPluginDescriptor
        extends PluginDescriptor implements Parcelable {

        private ParcelableCasPluginDescriptor(int CA_system_id, String name) {
            super(CA_system_id, name);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            Log.w(TAG, "ParcelableCasPluginDescriptor.writeToParcel shouldn't be called!");
        }

        public static final Parcelable.Creator<ParcelableCasPluginDescriptor> CREATOR
                = new Parcelable.Creator<ParcelableCasPluginDescriptor>() {
            public ParcelableCasPluginDescriptor createFromParcel(Parcel in) {
                int CA_system_id = in.readInt();
                String name = in.readString();
                return new ParcelableCasPluginDescriptor(CA_system_id, name);
            }

            public ParcelableCasPluginDescriptor[] newArray(int size) {
                return new ParcelableCasPluginDescriptor[size];
            }
        };
    }

    /**
     * Query if a certain CA system is supported on this device.
     *
     * @param CA_system_id the id of the CA system.
     *
     * @return Whether the specified CA system is supported on this device.
     */
    public static boolean isSystemIdSupported(int CA_system_id) {
        IMediaCasService service = getService();

        if (service != null) {
            try {
                return service.isSystemIdSupported(CA_system_id);
            } catch (RemoteException e) {
            }
        }
        return false;
    }

    /**
     * List all available CA plugins on the device.
     *
     * @return an array of descriptors for the available CA plugins.
     */
    public static PluginDescriptor[] enumeratePlugins() {
        IMediaCasService service = getService();

        if (service != null) {
            try {
                ParcelableCasPluginDescriptor[] descriptors = service.enumeratePlugins();
                if (descriptors.length == 0) {
                    return null;
                }
                PluginDescriptor[] results = new PluginDescriptor[descriptors.length];
                for (int i = 0; i < results.length; i++) {
                    results[i] = descriptors[i];
                }
                return results;
            } catch (RemoteException e) {
            }
        }
        return null;
    }

    /**
     * Instantiate a CA system of the specified system id.
     *
     * @param CA_system_id The system id of the CA system.
     *
     * @throws UnsupportedCasException if the device does not support the
     * specified CA system.
     */
    public MediaCas(int CA_system_id) throws UnsupportedCasException {
        try {
            mICas = getService().createPlugin(CA_system_id, mBinder);
        } catch(Exception e) {
            Log.e(TAG, "Failed to create plugin: " + e);
            mICas = null;
        } finally {
            if (mICas == null) {
                throw new UnsupportedCasException(
                        "Unsupported CA_system_id " + CA_system_id);
            }
        }
    }

    IBinder getBinder() {
        validateInternalStates();

        return mICas.asBinder();
    }

    /**
     * An interface registered by the caller to {@link #setEventListener}
     * to receives scheme-specific notifications from a MediaCas instance.
     */
    public interface EventListener {
        /**
         * Notify the listener of a scheme-specific event from the CA system.
         *
         * @param MediaCas the MediaCas object to receive this event.
         * @param event an integer whose meaning is scheme-specific.
         * @param arg an integer whose meaning is scheme-specific.
         * @param data a byte array of data whose format and meaning are
         * scheme-specific.
         */
        void onEvent(MediaCas MediaCas, int event, int arg, @Nullable byte[] data);
    }

    /**
     * Set an event listener to receive notifications from the MediaCas instance.
     *
     * @param listener the event listener to be set.
     * @param handler the handler whose looper the event listener will be called on.
     * If handler is null, we'll try to use current thread's looper, or the main
     * looper. If neither are available, an internal thread will be created instead.
     */
    public void setEventListener(
            @Nullable EventListener listener, @Nullable Handler handler) {
        mListener = listener;

        if (mListener == null) {
            mEventHandler = null;
            return;
        }

        Looper looper = (handler != null) ? handler.getLooper() : null;
        if (looper == null
                && (looper = Looper.myLooper()) == null
                && (looper = Looper.getMainLooper()) == null) {
            if (mHandlerThread == null || !mHandlerThread.isAlive()) {
                mHandlerThread = new HandlerThread("MediaCasEventThread",
                        Process.THREAD_PRIORITY_FOREGROUND);
                mHandlerThread.start();
            }
            looper = mHandlerThread.getLooper();
        }
        mEventHandler = new EventHandler(looper);
    }

    /**
     * Send the private data for the CA system.
     *
     * @param data byte array of the private data.
     *
     * @throws IllegalStateException if the MediaCas instance is not valid.
     * @throws MediaCasException for CAS-specific errors.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    public void setPrivateData(@NonNull byte[] data) throws MediaCasException {
        validateInternalStates();

        try {
            mICas.setPrivateData(data);
        } catch (ServiceSpecificException e) {
            MediaCasException.throwExceptions(e);
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
    }

    /**
     * Open a session to descramble one or more streams scrambled by the
     * conditional access system.
     *
     * @return session the newly opened session.
     *
     * @throws IllegalStateException if the MediaCas instance is not valid.
     * @throws MediaCasException for CAS-specific errors.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    public Session openSession() throws MediaCasException {
        validateInternalStates();

        try {
            return createFromSessionId(mICas.openSession());
        } catch (ServiceSpecificException e) {
            MediaCasException.throwExceptions(e);
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
        return null;
    }

    /**
     * Send a received EMM packet to the CA system.
     *
     * @param data byte array of the EMM data.
     * @param offset position within data where the EMM data begins.
     * @param length length of the data (starting from offset).
     *
     * @throws IllegalStateException if the MediaCas instance is not valid.
     * @throws MediaCasException for CAS-specific errors.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    public void processEmm(@NonNull byte[] data, int offset, int length)
            throws MediaCasException {
        validateInternalStates();

        try {
            mCasData.set(data, offset, length);
            mICas.processEmm(mCasData);
        } catch (ServiceSpecificException e) {
            MediaCasException.throwExceptions(e);
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
    }

    /**
     * Send a received EMM packet to the CA system. This is similar to
     * {@link #processEmm(byte[], int, int)} except that the entire byte
     * array is sent.
     *
     * @param data byte array of the EMM data.
     *
     * @throws IllegalStateException if the MediaCas instance is not valid.
     * @throws MediaCasException for CAS-specific errors.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    public void processEmm(@NonNull byte[] data) throws MediaCasException {
        processEmm(data, 0, data.length);
    }

    /**
     * Send an event to a CA system. The format of the event is scheme-specific
     * and is opaque to the framework.
     *
     * @param event an integer denoting a scheme-specific event to be sent.
     * @param arg a scheme-specific integer argument for the event.
     * @param data a byte array containing scheme-specific data for the event.
     *
     * @throws IllegalStateException if the MediaCas instance is not valid.
     * @throws MediaCasException for CAS-specific errors.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    public void sendEvent(int event, int arg, @Nullable byte[] data)
            throws MediaCasException {
        validateInternalStates();

        try {
            mICas.sendEvent(event, arg, data);
        } catch (ServiceSpecificException e) {
            MediaCasException.throwExceptions(e);
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
    }

    /**
     * Initiate a provisioning operation for a CA system.
     *
     * @param provisionString string containing information needed for the
     * provisioning operation, the format of which is scheme and implementation
     * specific.
     *
     * @throws IllegalStateException if the MediaCas instance is not valid.
     * @throws MediaCasException for CAS-specific errors.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    public void provision(@NonNull String provisionString) throws MediaCasException {
        validateInternalStates();

        try {
            mICas.provision(provisionString);
        } catch (ServiceSpecificException e) {
            MediaCasException.throwExceptions(e);
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
    }

    /**
     * Notify the CA system to refresh entitlement keys.
     *
     * @param refreshType the type of the refreshment.
     * @param refreshData private data associated with the refreshment.
     *
     * @throws IllegalStateException if the MediaCas instance is not valid.
     * @throws MediaCasException for CAS-specific errors.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    public void refreshEntitlements(int refreshType, @Nullable byte[] refreshData)
            throws MediaCasException {
        validateInternalStates();

        try {
            mICas.refreshEntitlements(refreshType, refreshData);
        } catch (ServiceSpecificException e) {
            MediaCasException.throwExceptions(e);
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
    }

    @Override
    public void close() {
        if (mICas != null) {
            try {
                mICas.release();
            } catch (RemoteException e) {
            } finally {
                mICas = null;
            }
        }
    }

    @Override
    protected void finalize() {
        close();
    }
}
