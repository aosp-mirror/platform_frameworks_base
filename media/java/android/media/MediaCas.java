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
import android.hardware.cas.V1_0.*;
import android.media.MediaCasException.*;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.Singleton;

import java.util.ArrayList;

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
    private ICas mICas;
    private EventListener mListener;
    private HandlerThread mHandlerThread;
    private EventHandler mEventHandler;

    private static final Singleton<IMediaCasService> gDefault =
            new Singleton<IMediaCasService>() {
        @Override
        protected IMediaCasService create() {
            try {
                return IMediaCasService.getService();
            } catch (RemoteException e) {}
            return null;
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
                mListener.onEvent(MediaCas.this, msg.arg1, msg.arg2,
                        toBytes((ArrayList<Byte>) msg.obj));
            }
        }
    }

    private final ICasListener.Stub mBinder = new ICasListener.Stub() {
        @Override
        public void onEvent(int event, int arg, @Nullable ArrayList<Byte> data)
                throws RemoteException {
            mEventHandler.sendMessage(mEventHandler.obtainMessage(
                    EventHandler.MSG_CAS_EVENT, event, arg, data));
        }
    };

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

        PluginDescriptor(@NonNull HidlCasPluginDescriptor descriptor) {
            mCASystemId = descriptor.caSystemId;
            mName = descriptor.name;
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

    private ArrayList<Byte> toByteArray(@NonNull byte[] data, int offset, int length) {
        ArrayList<Byte> byteArray = new ArrayList<Byte>(length);
        for (int i = 0; i < length; i++) {
            byteArray.add(Byte.valueOf(data[offset + i]));
        }
        return byteArray;
    }

    private ArrayList<Byte> toByteArray(@Nullable byte[] data) {
        if (data == null) {
            return new ArrayList<Byte>();
        }
        return toByteArray(data, 0, data.length);
    }

    private byte[] toBytes(@NonNull ArrayList<Byte> byteArray) {
        byte[] data = null;
        if (byteArray != null) {
            data = new byte[byteArray.size()];
            for (int i = 0; i < data.length; i++) {
                data[i] = byteArray.get(i);
            }
        }
        return data;
    }
    /**
     * Class for an open session with the CA system.
     */
    public final class Session implements AutoCloseable {
        final ArrayList<Byte> mSessionId;

        Session(@NonNull ArrayList<Byte> sessionId) {
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
                MediaCasException.throwExceptionIfNeeded(
                        mICas.setSessionPrivateData(mSessionId, toByteArray(data, 0, data.length)));
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
                MediaCasException.throwExceptionIfNeeded(
                        mICas.processEcm(mSessionId, toByteArray(data, offset, length)));
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
                MediaCasStateException.throwExceptionIfNeeded(
                        mICas.closeSession(mSessionId));
            } catch (RemoteException e) {
                cleanupAndRethrowIllegalState();
            }
        }
    }

    Session createFromSessionId(@NonNull ArrayList<Byte> sessionId) {
        if (sessionId == null || sessionId.size() == 0) {
            return null;
        }
        return new Session(sessionId);
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
                ArrayList<HidlCasPluginDescriptor> descriptors =
                        service.enumeratePlugins();
                if (descriptors.size() == 0) {
                    return null;
                }
                PluginDescriptor[] results = new PluginDescriptor[descriptors.size()];
                for (int i = 0; i < results.length; i++) {
                    results[i] = new PluginDescriptor(descriptors.get(i));
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

    IHwBinder getBinder() {
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
            MediaCasException.throwExceptionIfNeeded(
                    mICas.setPrivateData(toByteArray(data, 0, data.length)));
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
    }

    private class OpenSessionCallback implements ICas.openSessionCallback {
        public Session mSession;
        public int mStatus;
        @Override
        public void onValues(int status, ArrayList<Byte> sessionId) {
            mStatus = status;
            mSession = createFromSessionId(sessionId);
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
            OpenSessionCallback cb = new OpenSessionCallback();
            mICas.openSession(cb);
            MediaCasException.throwExceptionIfNeeded(cb.mStatus);
            return cb.mSession;
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
            MediaCasException.throwExceptionIfNeeded(
                    mICas.processEmm(toByteArray(data, offset, length)));
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
            MediaCasException.throwExceptionIfNeeded(
                    mICas.sendEvent(event, arg, toByteArray(data)));
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
            MediaCasException.throwExceptionIfNeeded(
                    mICas.provision(provisionString));
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
            MediaCasException.throwExceptionIfNeeded(
                    mICas.refreshEntitlements(refreshType, toByteArray(refreshData)));
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