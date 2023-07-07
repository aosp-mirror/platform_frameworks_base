/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.media.remotedisplay;

import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.IRemoteDisplayCallback;
import android.media.IRemoteDisplayProvider;
import android.media.RemoteDisplayState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.ArrayMap;

import java.util.Collection;

/**
 * Base class for remote display providers implemented as unbundled services.
 * <p>
 * To implement your remote display provider service, create a subclass of
 * {@link Service} and override the {@link Service#onBind Service.onBind()} method
 * to return the provider's binder when the {@link #SERVICE_INTERFACE} is requested.
 * </p>
 * <pre>
 *   public class SampleRemoteDisplayProviderService extends Service {
 *       private SampleProvider mProvider;
 *
 *       public IBinder onBind(Intent intent) {
 *           if (intent.getAction().equals(RemoteDisplayProvider.SERVICE_INTERFACE)) {
 *               if (mProvider == null) {
 *                   mProvider = new SampleProvider(this);
 *               }
 *               return mProvider.getBinder();
 *           }
 *           return null;
 *       }
 *
 *       class SampleProvider extends RemoteDisplayProvider {
 *           public SampleProvider() {
 *               super(SampleRemoteDisplayProviderService.this);
 *           }
 *
 *           // --- Implementation goes here ---
 *       }
 *   }
 * </pre>
 * <p>
 * Declare your remote display provider service in your application manifest
 * like this:
 * </p>
 * <pre>
 *   &lt;application>
 *       &lt;uses-library android:name="com.android.media.remotedisplay" />
 *
 *       &lt;service android:name=".SampleRemoteDisplayProviderService"
 *               android:label="@string/sample_remote_display_provider_service"
 *               android:exported="true"
 *               android:permission="android.permission.BIND_REMOTE_DISPLAY">
 *           &lt;intent-filter>
 *               &lt;action android:name="com.android.media.remotedisplay.RemoteDisplayProvider" />
 *           &lt;/intent-filter>
 *       &lt;/service>
 *   &lt;/application>
 * </pre>
 * <p>
 * This object is not thread safe.  It is only intended to be accessed on the
 * {@link Context#getMainLooper main looper thread} of an application.
 * </p><p>
 * IMPORTANT: This class is effectively a public API for unbundled applications, and
 * must remain API stable. See README.txt in the root of this package for more information.
 * </p>
 *
 * @hide
 */
@SystemApi
public abstract class RemoteDisplayProvider {
    private static final int MSG_SET_CALLBACK = 1;
    private static final int MSG_SET_DISCOVERY_MODE = 2;
    private static final int MSG_CONNECT = 3;
    private static final int MSG_DISCONNECT = 4;
    private static final int MSG_SET_VOLUME = 5;
    private static final int MSG_ADJUST_VOLUME = 6;

    private final Context mContext;
    private final ProviderStub mStub;
    private final ProviderHandler mHandler;
    private final ArrayMap<String, RemoteDisplay> mDisplays =
            new ArrayMap<String, RemoteDisplay>();
    private IRemoteDisplayCallback mCallback;
    private int mDiscoveryMode = DISCOVERY_MODE_NONE;

    private PendingIntent mSettingsPendingIntent;

    /**
     * The {@link Intent} that must be declared as handled by the service.
     * Put this in your manifest.
     */
    public static final String SERVICE_INTERFACE = RemoteDisplayState.SERVICE_INTERFACE;

    /**
     * Discovery mode: Do not perform any discovery.
     */
    public static final int DISCOVERY_MODE_NONE = RemoteDisplayState.DISCOVERY_MODE_NONE;

    /**
     * Discovery mode: Passive or low-power periodic discovery.
     * <p>
     * This mode indicates that an application is interested in knowing whether there
     * are any remote displays paired or available but doesn't need the latest or
     * most detailed information.  The provider may scan at a lower rate or rely on
     * knowledge of previously paired devices.
     * </p>
     */
    public static final int DISCOVERY_MODE_PASSIVE = RemoteDisplayState.DISCOVERY_MODE_PASSIVE;

    /**
     * Discovery mode: Active discovery.
     * <p>
     * This mode indicates that the user is actively trying to connect to a route
     * and we should perform continuous scans.  This mode may use significantly more
     * power but is intended to be short-lived.
     * </p>
     */
    public static final int DISCOVERY_MODE_ACTIVE = RemoteDisplayState.DISCOVERY_MODE_ACTIVE;

    /**
     * Creates a remote display provider.
     *
     * @param context The application context for the remote display provider.
     */
    public RemoteDisplayProvider(Context context) {
        mContext = context;
        mStub = new ProviderStub();
        mHandler = new ProviderHandler(context.getMainLooper());
    }

    /**
     * Gets the context of the remote display provider.
     */
    public final Context getContext() {
        return mContext;
    }

    /**
     * Gets the Binder associated with the provider.
     * <p>
     * This is intended to be used for the onBind() method of a service that implements
     * a remote display provider service.
     * </p>
     *
     * @return The IBinder instance associated with the provider.
     */
    public IBinder getBinder() {
        return mStub;
    }

    /**
     * Called when the current discovery mode changes.
     *
     * @param mode The new discovery mode.
     */
    public void onDiscoveryModeChanged(int mode) {
    }

    /**
     * Called when the system would like to connect to a display.
     *
     * @param display The remote display.
     */
    public void onConnect(RemoteDisplay display) {
    }

    /**
     * Called when the system would like to disconnect from a display.
     *
     * @param display The remote display.
     */
    public void onDisconnect(RemoteDisplay display) {
    }

    /**
     * Called when the system would like to set the volume of a display.
     *
     * @param display The remote display.
     * @param volume The desired volume.
     */
    public void onSetVolume(RemoteDisplay display, int volume) {
    }

    /**
     * Called when the system would like to adjust the volume of a display.
     *
     * @param display The remote display.
     * @param delta An increment to add to the current volume, such as +1 or -1.
     */
    public void onAdjustVolume(RemoteDisplay display, int delta) {
    }

    /**
     * Gets the current discovery mode.
     *
     * @return The current discovery mode.
     */
    public int getDiscoveryMode() {
        return mDiscoveryMode;
    }

    /**
     * Gets the current collection of displays.
     *
     * @return The current collection of displays, which must not be modified.
     */
    public Collection<RemoteDisplay> getDisplays() {
        return mDisplays.values();
    }

    /**
     * Adds the specified remote display and notifies the system.
     *
     * @param display The remote display that was added.
     * @throws IllegalStateException if the argument is null, or if there is already a display with
     *         the same id.
     */
    public void addDisplay(RemoteDisplay display) {
        if (display == null) {
            throw new IllegalArgumentException("display cannot be null");
        }
        String displayId = display.getId();
        if (mDisplays.containsKey(displayId)) {
            throw new IllegalArgumentException("display already exists with id: " + displayId);
        }
        mDisplays.put(displayId, display);
        publishState();
    }

    /**
     * Updates information about the specified remote display and notifies the system.
     *
     * @param display The remote display that was added.
     * @throws IllegalStateException if the argument is null, or if the provider is not aware of the
     *         display.
     */
    public void updateDisplay(RemoteDisplay display) {
        if (display == null) {
            throw new IllegalArgumentException("display cannot be null");
        }
        String displayId = display.getId();
        if (mDisplays.get(displayId) != display) {
            throw new IllegalArgumentException("unexpected display with id: " + displayId);
        }
        publishState();
    }

    /**
     * Removes the specified remote display and tells the system about it.
     *
     * @param display The remote display that was removed.
     * @throws IllegalStateException if the argument is null, or if the provider is not aware of the
     *         display.
     */
    public void removeDisplay(RemoteDisplay display) {
        if (display == null) {
            throw new IllegalArgumentException("display cannot be null");
        }
        String displayId = display.getId();
        if (mDisplays.get(displayId) != display) {
            throw new IllegalArgumentException("unexpected display with id: " + displayId);
        }
        mDisplays.remove(displayId);
        publishState();
    }

    /**
     * Finds the remote display with the specified id, returns null if not found.
     *
     * @param id Id of the remote display.
     * @return The display, or null if none.
     */
    public RemoteDisplay findRemoteDisplay(String id) {
        return mDisplays.get(id);
    }

    /**
     * Gets a pending intent to launch the remote display settings activity.
     *
     * @return A pending intent to launch the settings activity.
     */
    public PendingIntent getSettingsPendingIntent() {
        if (mSettingsPendingIntent == null) {
            Intent settingsIntent = new Intent(Settings.ACTION_CAST_SETTINGS);
            settingsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mSettingsPendingIntent = PendingIntent.getActivity(
                    mContext, 0, settingsIntent, PendingIntent.FLAG_IMMUTABLE, null);
        }
        return mSettingsPendingIntent;
    }

    void setCallback(IRemoteDisplayCallback callback) {
        mCallback = callback;
        publishState();
    }

    void setDiscoveryMode(int mode) {
        if (mDiscoveryMode != mode) {
            mDiscoveryMode = mode;
            onDiscoveryModeChanged(mode);
        }
    }

    void publishState() {
        if (mCallback != null) {
            RemoteDisplayState state = new RemoteDisplayState();
            final int count = mDisplays.size();
            for (int i = 0; i < count; i++) {
                final RemoteDisplay display = mDisplays.valueAt(i);
                state.displays.add(display.getInfo());
            }
            try {
                mCallback.onStateChanged(state);
            } catch (RemoteException ex) {
                // system server died?
            }
        }
    }

    final class ProviderStub extends IRemoteDisplayProvider.Stub {
        @Override
        public void setCallback(IRemoteDisplayCallback callback) {
            mHandler.obtainMessage(MSG_SET_CALLBACK, callback).sendToTarget();
        }

        @Override
        public void setDiscoveryMode(int mode) {
            mHandler.obtainMessage(MSG_SET_DISCOVERY_MODE, mode, 0).sendToTarget();
        }

        @Override
        public void connect(String id) {
            mHandler.obtainMessage(MSG_CONNECT, id).sendToTarget();
        }

        @Override
        public void disconnect(String id) {
            mHandler.obtainMessage(MSG_DISCONNECT, id).sendToTarget();
        }

        @Override
        public void setVolume(String id, int volume) {
            mHandler.obtainMessage(MSG_SET_VOLUME, volume, 0, id).sendToTarget();
        }

        @Override
        public void adjustVolume(String id, int delta) {
            mHandler.obtainMessage(MSG_ADJUST_VOLUME, delta, 0, id).sendToTarget();
        }
    }

    final class ProviderHandler extends Handler {
        public ProviderHandler(Looper looper) {
            super(looper, null, true);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_CALLBACK: {
                    setCallback((IRemoteDisplayCallback)msg.obj);
                    break;
                }
                case MSG_SET_DISCOVERY_MODE: {
                    setDiscoveryMode(msg.arg1);
                    break;
                }
                case MSG_CONNECT: {
                    RemoteDisplay display = findRemoteDisplay((String)msg.obj);
                    if (display != null) {
                        onConnect(display);
                    }
                    break;
                }
                case MSG_DISCONNECT: {
                    RemoteDisplay display = findRemoteDisplay((String)msg.obj);
                    if (display != null) {
                        onDisconnect(display);
                    }
                    break;
                }
                case MSG_SET_VOLUME: {
                    RemoteDisplay display = findRemoteDisplay((String)msg.obj);
                    if (display != null) {
                        onSetVolume(display, msg.arg1);
                    }
                    break;
                }
                case MSG_ADJUST_VOLUME: {
                    RemoteDisplay display = findRemoteDisplay((String)msg.obj);
                    if (display != null) {
                        onAdjustVolume(display, msg.arg1);
                    }
                    break;
                }
            }
        }
    }
}
