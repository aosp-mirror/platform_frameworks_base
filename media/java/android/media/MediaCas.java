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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Context;
import android.hardware.cas.V1_0.HidlCasPluginDescriptor;
import android.hardware.cas.V1_0.ICas;
import android.hardware.cas.V1_0.IMediaCasService;
import android.hardware.cas.V1_2.ICasListener;
import android.hardware.cas.V1_2.Status;
import android.media.MediaCasException.*;
import android.media.tv.TvInputService.PriorityHintUseCaseType;
import android.media.tv.tunerresourcemanager.CasSessionRequest;
import android.media.tv.tunerresourcemanager.ResourceClientProfile;
import android.media.tv.tunerresourcemanager.TunerResourceManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IHwBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.util.Singleton;

import com.android.internal.util.FrameworkStatsLog;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
    private android.hardware.cas.V1_1.ICas mICasV11;
    private android.hardware.cas.V1_2.ICas mICasV12;
    private EventListener mListener;
    private HandlerThread mHandlerThread;
    private EventHandler mEventHandler;
    private @PriorityHintUseCaseType int mPriorityHint;
    private String mTvInputServiceSessionId;
    private int mClientId;
    private int mCasSystemId;
    private int mUserId;
    private TunerResourceManager mTunerResourceManager = null;
    private final Map<Session, Integer> mSessionMap = new HashMap<>();

    /**
     * Scrambling modes used to open cas sessions.
     *
     * @hide
     */
    @IntDef(prefix = "SCRAMBLING_MODE_",
            value = {SCRAMBLING_MODE_RESERVED, SCRAMBLING_MODE_DVB_CSA1, SCRAMBLING_MODE_DVB_CSA2,
            SCRAMBLING_MODE_DVB_CSA3_STANDARD,
            SCRAMBLING_MODE_DVB_CSA3_MINIMAL, SCRAMBLING_MODE_DVB_CSA3_ENHANCE,
            SCRAMBLING_MODE_DVB_CISSA_V1, SCRAMBLING_MODE_DVB_IDSA,
            SCRAMBLING_MODE_MULTI2, SCRAMBLING_MODE_AES128, SCRAMBLING_MODE_AES_ECB,
            SCRAMBLING_MODE_AES_SCTE52, SCRAMBLING_MODE_TDES_ECB, SCRAMBLING_MODE_TDES_SCTE52})
    @Retention(RetentionPolicy.SOURCE)
    public @interface ScramblingMode {}

    /**
     * DVB (Digital Video Broadcasting) reserved mode.
     */
    public static final int SCRAMBLING_MODE_RESERVED =
            android.hardware.cas.V1_2.ScramblingMode.RESERVED;
    /**
     * DVB (Digital Video Broadcasting) Common Scrambling Algorithm (CSA) 1.
     */
    public static final int SCRAMBLING_MODE_DVB_CSA1 =
            android.hardware.cas.V1_2.ScramblingMode.DVB_CSA1;
    /**
     * DVB CSA 2.
     */
    public static final int SCRAMBLING_MODE_DVB_CSA2 =
            android.hardware.cas.V1_2.ScramblingMode.DVB_CSA2;
    /**
     * DVB CSA 3 in standard mode.
     */
    public static final int SCRAMBLING_MODE_DVB_CSA3_STANDARD =
            android.hardware.cas.V1_2.ScramblingMode.DVB_CSA3_STANDARD;
    /**
     * DVB CSA 3 in minimally enhanced mode.
     */
    public static final int SCRAMBLING_MODE_DVB_CSA3_MINIMAL =
            android.hardware.cas.V1_2.ScramblingMode.DVB_CSA3_MINIMAL;
    /**
     * DVB CSA 3 in fully enhanced mode.
     */
    public static final int SCRAMBLING_MODE_DVB_CSA3_ENHANCE =
            android.hardware.cas.V1_2.ScramblingMode.DVB_CSA3_ENHANCE;
    /**
     * DVB Common IPTV Software-oriented Scrambling Algorithm (CISSA) Version 1.
     */
    public static final int SCRAMBLING_MODE_DVB_CISSA_V1 =
            android.hardware.cas.V1_2.ScramblingMode.DVB_CISSA_V1;
    /**
     * ATIS-0800006 IIF Default Scrambling Algorithm (IDSA).
     */
    public static final int SCRAMBLING_MODE_DVB_IDSA =
            android.hardware.cas.V1_2.ScramblingMode.DVB_IDSA;
    /**
     * A symmetric key algorithm.
     */
    public static final int SCRAMBLING_MODE_MULTI2 =
            android.hardware.cas.V1_2.ScramblingMode.MULTI2;
    /**
     * Advanced Encryption System (AES) 128-bit Encryption mode.
     */
    public static final int SCRAMBLING_MODE_AES128 =
            android.hardware.cas.V1_2.ScramblingMode.AES128;
    /**
     * Advanced Encryption System (AES) Electronic Code Book (ECB) mode.
     */
    public static final int SCRAMBLING_MODE_AES_ECB =
            android.hardware.cas.V1_2.ScramblingMode.AES_ECB;
    /**
     * Advanced Encryption System (AES) Society of Cable Telecommunications Engineers (SCTE) 52
     * mode.
     */
    public static final int SCRAMBLING_MODE_AES_SCTE52 =
            android.hardware.cas.V1_2.ScramblingMode.AES_SCTE52;
    /**
     * Triple Data Encryption Algorithm (TDES) Electronic Code Book (ECB) mode.
     */
    public static final int SCRAMBLING_MODE_TDES_ECB =
            android.hardware.cas.V1_2.ScramblingMode.TDES_ECB;
    /**
     * Triple Data Encryption Algorithm (TDES) Society of Cable Telecommunications Engineers (SCTE)
     * 52 mode.
     */
    public static final int SCRAMBLING_MODE_TDES_SCTE52 =
            android.hardware.cas.V1_2.ScramblingMode.TDES_SCTE52;

    /**
     * Usages used to open cas sessions.
     *
     * @hide
     */
    @IntDef(prefix = "SESSION_USAGE_",
            value = {SESSION_USAGE_LIVE, SESSION_USAGE_PLAYBACK, SESSION_USAGE_RECORD,
            SESSION_USAGE_TIMESHIFT})
    @Retention(RetentionPolicy.SOURCE)
    public @interface SessionUsage {}
    /**
     * Cas session is used to descramble live streams.
     */
    public static final int SESSION_USAGE_LIVE = android.hardware.cas.V1_2.SessionIntent.LIVE;
    /**
     * Cas session is used to descramble recoreded streams.
     */
    public static final int SESSION_USAGE_PLAYBACK =
            android.hardware.cas.V1_2.SessionIntent.PLAYBACK;
    /**
     * Cas session is used to descramble live streams and encrypt local recorded content
     */
    public static final int SESSION_USAGE_RECORD = android.hardware.cas.V1_2.SessionIntent.RECORD;
    /**
     * Cas session is used to descramble live streams , encrypt local recorded content and playback
     * local encrypted content.
     */
    public static final int SESSION_USAGE_TIMESHIFT =
            android.hardware.cas.V1_2.SessionIntent.TIMESHIFT;

    /**
     * Plugin status events sent from cas system.
     *
     * @hide
     */
    @IntDef(prefix = "PLUGIN_STATUS_",
            value = {PLUGIN_STATUS_PHYSICAL_MODULE_CHANGED, PLUGIN_STATUS_SESSION_NUMBER_CHANGED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface PluginStatus {}

    /**
     * The event to indicate that the status of CAS system is changed by the removal or insertion of
     * physical CAS modules.
     */
    public static final int PLUGIN_STATUS_PHYSICAL_MODULE_CHANGED =
            android.hardware.cas.V1_2.StatusEvent.PLUGIN_PHYSICAL_MODULE_CHANGED;
    /**
     * The event to indicate that the number of CAS system's session is changed.
     */
    public static final int PLUGIN_STATUS_SESSION_NUMBER_CHANGED =
            android.hardware.cas.V1_2.StatusEvent.PLUGIN_SESSION_NUMBER_CHANGED;

    private static final Singleton<IMediaCasService> sService = new Singleton<IMediaCasService>() {
        @Override
        protected IMediaCasService create() {
            try {
                Log.d(TAG, "Trying to get cas@1.2 service");
                android.hardware.cas.V1_2.IMediaCasService serviceV12 =
                        android.hardware.cas.V1_2.IMediaCasService.getService(true /*wait*/);
                if (serviceV12 != null) {
                    return serviceV12;
                }
            } catch (Exception eV1_2) {
                Log.d(TAG, "Failed to get cas@1.2 service");
            }

            try {
                    Log.d(TAG, "Trying to get cas@1.1 service");
                    android.hardware.cas.V1_1.IMediaCasService serviceV11 =
                            android.hardware.cas.V1_1.IMediaCasService.getService(true /*wait*/);
                    if (serviceV11 != null) {
                        return serviceV11;
                    }
            } catch (Exception eV1_1) {
                Log.d(TAG, "Failed to get cas@1.1 service");
            }

            try {
                Log.d(TAG, "Trying to get cas@1.0 service");
                return IMediaCasService.getService(true /*wait*/);
            } catch (Exception eV1_0) {
                Log.d(TAG, "Failed to get cas@1.0 service");
            }

            return null;
        }
    };

    static IMediaCasService getService() {
        return sService.get();
    }

    private void validateInternalStates() {
        if (mICas == null) {
            throw new IllegalStateException();
        }
    }

    private void cleanupAndRethrowIllegalState() {
        mICas = null;
        mICasV11 = null;
        mICasV12 = null;
        throw new IllegalStateException();
    }

    private class EventHandler extends Handler {

        private static final int MSG_CAS_EVENT = 0;
        private static final int MSG_CAS_SESSION_EVENT = 1;
        private static final int MSG_CAS_STATUS_EVENT = 2;
        private static final int MSG_CAS_RESOURCE_LOST = 3;
        private static final String SESSION_KEY = "sessionId";
        private static final String DATA_KEY = "data";

        public EventHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_CAS_EVENT) {
                mListener.onEvent(MediaCas.this, msg.arg1, msg.arg2,
                        toBytes((ArrayList<Byte>) msg.obj));
            } else if (msg.what == MSG_CAS_SESSION_EVENT) {
                Bundle bundle = msg.getData();
                ArrayList<Byte> sessionId = toByteArray(bundle.getByteArray(SESSION_KEY));
                mListener.onSessionEvent(MediaCas.this,
                        createFromSessionId(sessionId), msg.arg1, msg.arg2,
                        bundle.getByteArray(DATA_KEY));
            } else if (msg.what == MSG_CAS_STATUS_EVENT) {
                if ((msg.arg1 == PLUGIN_STATUS_SESSION_NUMBER_CHANGED)
                        && (mTunerResourceManager != null)) {
                    mTunerResourceManager.updateCasInfo(mCasSystemId, msg.arg2);
                }
                mListener.onPluginStatusUpdate(MediaCas.this, msg.arg1, msg.arg2);
            } else if (msg.what == MSG_CAS_RESOURCE_LOST) {
                mListener.onResourceLost(MediaCas.this);
            }
        }
    }

    private final ICasListener.Stub mBinder = new ICasListener.Stub() {
        @Override
        public void onEvent(int event, int arg, @Nullable ArrayList<Byte> data)
                throws RemoteException {
            if (mEventHandler != null) {
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                    EventHandler.MSG_CAS_EVENT, event, arg, data));
            }
        }
        @Override
        public void onSessionEvent(@NonNull ArrayList<Byte> sessionId,
                int event, int arg, @Nullable ArrayList<Byte> data)
                throws RemoteException {
            if (mEventHandler != null) {
                Message msg = mEventHandler.obtainMessage();
                msg.what = EventHandler.MSG_CAS_SESSION_EVENT;
                msg.arg1 = event;
                msg.arg2 = arg;
                Bundle bundle = new Bundle();
                bundle.putByteArray(EventHandler.SESSION_KEY, toBytes(sessionId));
                bundle.putByteArray(EventHandler.DATA_KEY, toBytes(data));
                msg.setData(bundle);
                mEventHandler.sendMessage(msg);
            }
        }
        @Override
        public void onStatusUpdate(byte status, int arg)
                throws RemoteException {
            if (mEventHandler != null) {
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                    EventHandler.MSG_CAS_STATUS_EVENT, status, arg));
            }
        }
    };

    private final TunerResourceManager.ResourcesReclaimListener mResourceListener =
            new TunerResourceManager.ResourcesReclaimListener() {
            @Override
            public void onReclaimResources() {
                synchronized (mSessionMap) {
                    List<Session> sessionList = new ArrayList<>(mSessionMap.keySet());
                    for (Session casSession: sessionList) {
                        casSession.close();
                    }
                }
                mEventHandler.sendMessage(mEventHandler.obtainMessage(
                        EventHandler.MSG_CAS_RESOURCE_LOST));
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
        boolean mIsClosed = false;

        Session(@NonNull ArrayList<Byte> sessionId) {
            mSessionId = new ArrayList<Byte>(sessionId);
        }

        private void validateSessionInternalStates() {
            if (mICas == null) {
                throw new IllegalStateException();
            }
            if (mIsClosed) {
                MediaCasStateException.throwExceptionIfNeeded(Status.ERROR_CAS_SESSION_NOT_OPENED);
            }
        }

        /**
         * Query if an object equal current Session object.
         *
         * @param obj an object to compare to current Session object.
         *
         * @return Whether input object equal current Session object.
         */
        public boolean equals(Object obj) {
            if (obj instanceof Session) {
                return mSessionId.equals(((Session) obj).mSessionId);
            }
            return false;
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
            validateSessionInternalStates();

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
            validateSessionInternalStates();

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
         * Send a session event to a CA system. The format of the event is
         * scheme-specific and is opaque to the framework.
         *
         * @param event an integer denoting a scheme-specific event to be sent.
         * @param arg a scheme-specific integer argument for the event.
         * @param data a byte array containing scheme-specific data for the event.
         *
         * @throws IllegalStateException if the MediaCas instance is not valid.
         * @throws MediaCasException for CAS-specific errors.
         * @throws MediaCasStateException for CAS-specific state exceptions.
         */
        public void sendSessionEvent(int event, int arg, @Nullable byte[] data)
                throws MediaCasException {
            validateSessionInternalStates();

            if (mICasV11 == null) {
                Log.d(TAG, "Send Session Event isn't supported by cas@1.0 interface");
                throw new UnsupportedCasException("Send Session Event is not supported");
            }

            try {
                MediaCasException.throwExceptionIfNeeded(
                        mICasV11.sendSessionEvent(mSessionId, event, arg, toByteArray(data)));
            } catch (RemoteException e) {
                cleanupAndRethrowIllegalState();
            }
        }

        /**
         * Get Session Id.
         *
         * @return session Id of the session.
         *
         * @throws IllegalStateException if the MediaCas instance is not valid.
         */
        @NonNull
        public byte[] getSessionId() {
            validateSessionInternalStates();
            return toBytes(mSessionId);
        }

        /**
         * Close the session.
         *
         * @throws IllegalStateException if the MediaCas instance is not valid.
         * @throws MediaCasStateException for CAS-specific state exceptions.
         */
        @Override
        public void close() {
            validateSessionInternalStates();
            try {
                MediaCasStateException.throwExceptionIfNeeded(
                        mICas.closeSession(mSessionId));
                mIsClosed = true;
                removeSessionFromResourceMap(this);
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

    private void createPlugin(int casSystemId) throws UnsupportedCasException {
        try {
            mCasSystemId = casSystemId;
            mUserId = Process.myUid();
            IMediaCasService service = getService();
            android.hardware.cas.V1_2.IMediaCasService serviceV12 =
                    android.hardware.cas.V1_2.IMediaCasService.castFrom(service);
            if (serviceV12 == null) {
                android.hardware.cas.V1_1.IMediaCasService serviceV11 =
                    android.hardware.cas.V1_1.IMediaCasService.castFrom(service);
                if (serviceV11 == null) {
                    Log.d(TAG, "Used cas@1_0 interface to create plugin");
                    mICas = service.createPlugin(casSystemId, mBinder);
                } else {
                    Log.d(TAG, "Used cas@1.1 interface to create plugin");
                    mICas = mICasV11 = serviceV11.createPluginExt(casSystemId, mBinder);
                }
            } else {
                Log.d(TAG, "Used cas@1.2 interface to create plugin");
                mICas = mICasV11 = mICasV12 =
                    android.hardware.cas.V1_2.ICas
                        .castFrom(serviceV12.createPluginExt(casSystemId, mBinder));
            }
        } catch(Exception e) {
            Log.e(TAG, "Failed to create plugin: " + e);
            mICas = null;
        } finally {
            if (mICas == null) {
                throw new UnsupportedCasException(
                    "Unsupported casSystemId " + casSystemId);
            }
        }
    }

    private void registerClient(@NonNull Context context,
            @Nullable String tvInputServiceSessionId,  @PriorityHintUseCaseType int priorityHint)  {

        mTunerResourceManager = (TunerResourceManager)
            context.getSystemService(Context.TV_TUNER_RESOURCE_MGR_SERVICE);
        if (mTunerResourceManager != null) {
            int[] clientId = new int[1];
            ResourceClientProfile profile = new ResourceClientProfile();
            profile.tvInputSessionId = tvInputServiceSessionId;
            profile.useCase = priorityHint;
            mTunerResourceManager.registerClientProfile(
                    profile, context.getMainExecutor(), mResourceListener, clientId);
            mClientId = clientId[0];
        }
    }
    /**
     * Instantiate a CA system of the specified system id.
     *
     * @param casSystemId The system id of the CA system.
     *
     * @throws UnsupportedCasException if the device does not support the
     * specified CA system.
     */
    public MediaCas(int casSystemId) throws UnsupportedCasException {
        createPlugin(casSystemId);
    }

    /**
     * Instantiate a CA system of the specified system id.
     *
     * @param context the context of the caller.
     * @param casSystemId The system id of the CA system.
     * @param tvInputServiceSessionId The Id of the session opened in TV Input Service (TIS)
     *        {@link android.media.tv.TvInputService#onCreateSession(String, String)}
     * @param priorityHint priority hint from the use case type for new created CAS system.
     *
     * @throws UnsupportedCasException if the device does not support the
     * specified CA system.
     */
    public MediaCas(@NonNull Context context, int casSystemId,
            @Nullable String tvInputServiceSessionId,
            @PriorityHintUseCaseType int priorityHint) throws UnsupportedCasException {
        Objects.requireNonNull(context, "context must not be null");
        createPlugin(casSystemId);
        registerClient(context, tvInputServiceSessionId, priorityHint);
    }
    /**
     * Instantiate a CA system of the specified system id with EvenListener.
     *
     * @param context the context of the caller.
     * @param casSystemId The system id of the CA system.
     * @param tvInputServiceSessionId The Id of the session opened in TV Input Service (TIS)
     *        {@link android.media.tv.TvInputService#onCreateSession(String, String)}
     * @param priorityHint priority hint from the use case type for new created CAS system.
     * @param listener the event listener to be set.
     * @param handler the handler whose looper the event listener will be called on.
     * If handler is null, we'll try to use current thread's looper, or the main
     * looper. If neither are available, an internal thread will be created instead.
     *
     * @throws UnsupportedCasException if the device does not support the
     * specified CA system.
     */
    public MediaCas(@NonNull Context context, int casSystemId,
            @Nullable String tvInputServiceSessionId,
            @PriorityHintUseCaseType int priorityHint,
            @Nullable Handler handler, @Nullable EventListener listener)
            throws UnsupportedCasException {
        Objects.requireNonNull(context, "context must not be null");
        setEventListener(listener, handler);
        createPlugin(casSystemId);
        registerClient(context, tvInputServiceSessionId, priorityHint);
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
         * @param mediaCas the MediaCas object to receive this event.
         * @param event an integer whose meaning is scheme-specific.
         * @param arg an integer whose meaning is scheme-specific.
         * @param data a byte array of data whose format and meaning are
         * scheme-specific.
         */
        void onEvent(@NonNull MediaCas mediaCas, int event, int arg, @Nullable byte[] data);

        /**
         * Notify the listener of a scheme-specific session event from CA system.
         *
         * @param mediaCas the MediaCas object to receive this event.
         * @param session session object which the event is for.
         * @param event an integer whose meaning is scheme-specific.
         * @param arg an integer whose meaning is scheme-specific.
         * @param data a byte array of data whose format and meaning are
         * scheme-specific.
         */
        default void onSessionEvent(@NonNull MediaCas mediaCas, @NonNull Session session,
                int event, int arg, @Nullable byte[] data) {
            Log.d(TAG, "Received MediaCas Session event");
        }

        /**
         * Notify the listener that the cas plugin status is updated.
         *
         * @param mediaCas the MediaCas object to receive this event.
         * @param status the plugin status which is updated.
         * @param arg an integer whose meaning is specific to the status to be updated.
         */
        default void onPluginStatusUpdate(@NonNull MediaCas mediaCas, @PluginStatus int status,
                int arg) {
            Log.d(TAG, "Received MediaCas Plugin Status event");
        }

        /**
         * Notify the listener that the session resources was lost.
         *
         * @param mediaCas the MediaCas object to receive this event.
         */
        default void onResourceLost(@NonNull MediaCas mediaCas) {
            Log.d(TAG, "Received MediaCas Resource Reclaim event");
        }
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

    private class OpenSessionCallback implements android.hardware.cas.V1_1.ICas.openSessionCallback{
        public Session mSession;
        public int mStatus;
        @Override
        public void onValues(int status, ArrayList<Byte> sessionId) {
            mStatus = status;
            mSession = createFromSessionId(sessionId);
        }
    }

    private class OpenSession_1_2_Callback implements
            android.hardware.cas.V1_2.ICas.openSession_1_2Callback {

        public Session mSession;
        public int mStatus;

        @Override
        public void onValues(int status, ArrayList<Byte> sessionId) {
            mStatus = status;
            mSession = createFromSessionId(sessionId);
        }
    }

    private int getSessionResourceHandle() throws MediaCasException {
        validateInternalStates();

        int[] sessionResourceHandle = new int[1];
        sessionResourceHandle[0] = -1;
        if (mTunerResourceManager != null) {
            CasSessionRequest casSessionRequest = new CasSessionRequest();
            casSessionRequest.clientId = mClientId;
            casSessionRequest.casSystemId = mCasSystemId;
            if (!mTunerResourceManager
                    .requestCasSession(casSessionRequest, sessionResourceHandle)) {
                throw new MediaCasException.InsufficientResourceException(
                    "insufficient resource to Open Session");
            }
        }
        return sessionResourceHandle[0];
    }

    private void addSessionToResourceMap(Session session, int sessionResourceHandle) {

        if (sessionResourceHandle != TunerResourceManager.INVALID_RESOURCE_HANDLE) {
            synchronized (mSessionMap) {
                mSessionMap.put(session, sessionResourceHandle);
            }
        }
    }

    private void removeSessionFromResourceMap(Session session) {

        synchronized (mSessionMap) {
            if (mSessionMap.get(session) != null) {
                mTunerResourceManager.releaseCasSession(mSessionMap.get(session), mClientId);
                mSessionMap.remove(session);
            }
        }
    }

    /**
     * Open a session to descramble one or more streams scrambled by the
     * conditional access system.
     *
     * <p>Tuner resource manager (TRM) uses the client priority value to decide whether it is able
     * to get cas session resource if cas session resources is limited. If the client can't get the
     * resource, this call returns {@link MediaCasException.InsufficientResourceException }.
     *
     * @return session the newly opened session.
     *
     * @throws IllegalStateException if the MediaCas instance is not valid.
     * @throws MediaCasException for CAS-specific errors.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    public Session openSession() throws MediaCasException {
        int sessionResourceHandle = getSessionResourceHandle();

        try {
            OpenSessionCallback cb = new OpenSessionCallback();
            mICas.openSession(cb);
            MediaCasException.throwExceptionIfNeeded(cb.mStatus);
            addSessionToResourceMap(cb.mSession, sessionResourceHandle);
            Log.d(TAG, "Write Stats Log for succeed to Open Session.");
            FrameworkStatsLog
                    .write(FrameworkStatsLog.TV_CAS_SESSION_OPEN_STATUS, mUserId, mCasSystemId,
                        FrameworkStatsLog.TV_CAS_SESSION_OPEN_STATUS__STATE__SUCCEEDED);
            return cb.mSession;
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
        Log.d(TAG, "Write Stats Log for fail to Open Session.");
        FrameworkStatsLog
                .write(FrameworkStatsLog.TV_CAS_SESSION_OPEN_STATUS, mUserId, mCasSystemId,
                    FrameworkStatsLog.TV_CAS_SESSION_OPEN_STATUS__STATE__FAILED);
        return null;
    }

    /**
     * Open a session with usage and scrambling information, so that descrambler can be configured
     * to descramble one or more streams scrambled by the conditional access system.
     *
     * <p>Tuner resource manager (TRM) uses the client priority value to decide whether it is able
     * to get cas session resource if cas session resources is limited. If the client can't get the
     * resource, this call returns {@link MediaCasException.InsufficientResourceException}.
     *
     * @param sessionUsage used for the created session.
     * @param scramblingMode used for the created session.
     *
     * @return session the newly opened session.
     *
     * @throws IllegalStateException if the MediaCas instance is not valid.
     * @throws MediaCasException for CAS-specific errors.
     * @throws MediaCasStateException for CAS-specific state exceptions.
     */
    @Nullable
    public Session openSession(@SessionUsage int sessionUsage, @ScramblingMode int scramblingMode)
            throws MediaCasException {
        int sessionResourceHandle = getSessionResourceHandle();

        if (mICasV12 == null) {
            Log.d(TAG, "Open Session with scrambling mode is only supported by cas@1.2+ interface");
            throw new UnsupportedCasException("Open Session with scrambling mode is not supported");
        }

        try {
            OpenSession_1_2_Callback cb = new OpenSession_1_2_Callback();
            mICasV12.openSession_1_2(sessionUsage, scramblingMode, cb);
            MediaCasException.throwExceptionIfNeeded(cb.mStatus);
            addSessionToResourceMap(cb.mSession, sessionResourceHandle);
            Log.d(TAG, "Write Stats Log for succeed to Open Session.");
            FrameworkStatsLog
                    .write(FrameworkStatsLog.TV_CAS_SESSION_OPEN_STATUS, mUserId, mCasSystemId,
                        FrameworkStatsLog.TV_CAS_SESSION_OPEN_STATUS__STATE__SUCCEEDED);
            return cb.mSession;
        } catch (RemoteException e) {
            cleanupAndRethrowIllegalState();
        }
        Log.d(TAG, "Write Stats Log for fail to Open Session.");
        FrameworkStatsLog
                .write(FrameworkStatsLog.TV_CAS_SESSION_OPEN_STATUS, mUserId, mCasSystemId,
                    FrameworkStatsLog.TV_CAS_SESSION_OPEN_STATUS__STATE__FAILED);
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

    /**
     * Release Cas session. This is primarily used as a test API for CTS.
     * @hide
     */
    @TestApi
    public void forceResourceLost() {
        if (mResourceListener != null) {
            mResourceListener.onReclaimResources();
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

        if (mTunerResourceManager != null) {
            mTunerResourceManager.unregisterClientProfile(mClientId);
            mTunerResourceManager = null;
        }

        if (mHandlerThread != null) {
            mHandlerThread.quit();
            mHandlerThread = null;
        }
    }

    @Override
    protected void finalize() {
        close();
    }
}
