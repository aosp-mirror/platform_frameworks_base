/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.media;

import static android.media.MediaRoute2Info.FEATURE_LIVE_AUDIO;
import static android.media.MediaRoute2Info.FEATURE_LOCAL_PLAYBACK;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRoute2Info;
import android.media.audiopolicy.AudioProductStrategy;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.server.media.BluetoothRouteController.NoOpBluetoothRouteController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Maintains a list of all available routes and supports transfers to any of them.
 *
 * <p>This implementation is intended for use in conjunction with {@link
 * NoOpBluetoothRouteController}, as it manages bluetooth devices directly.
 *
 * <p>This implementation obtains and manages all routes via {@link AudioManager}, with the
 * exception of {@link AudioManager#handleBluetoothActiveDeviceChanged inactive bluetooth} routes
 * which are managed by {@link BluetoothDeviceRoutesManager}, which depends on the bluetooth stack
 * ({@link BluetoothAdapter} and related classes).
 *
 * <p>This class runs as part of the system_server process, but depends on classes that may
 * communicate with other processes, like bluetooth or audio server. And these other processes may
 * require binder threads from system server. As a result, there are a few threading considerations
 * to keep in mind:
 *
 * <ul>
 *   <li>Some of this class' internal state is synchronized using {@code this} as lock.
 *   <li>Binder threads may call into this class and run synchronized code.
 *   <li>As a result the above, in order to avoid deadlocks, calls to components that may call into
 *       other processes (like {@link AudioManager} or {@link BluetoothDeviceRoutesManager}) must
 *       not be synchronized nor occur on a binder thread.
 * </ul>
 */
/* package */ final class AudioManagerRouteController implements DeviceRouteController {
    private static final String TAG = SystemMediaRoute2Provider.TAG;

    @NonNull
    private static final AudioAttributes MEDIA_USAGE_AUDIO_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();

    @NonNull
    private static final SparseArray<SystemRouteInfo> AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO =
            new SparseArray<>();

    @NonNull private final Context mContext;
    @NonNull private final AudioManager mAudioManager;
    @NonNull private final Handler mHandler;
    @NonNull private final OnDeviceRouteChangedListener mOnDeviceRouteChangedListener;
    @NonNull private final BluetoothDeviceRoutesManager mBluetoothRouteController;

    @NonNull private final AudioProductStrategy mStrategyForMedia;

    @NonNull private final AudioDeviceCallback mAudioDeviceCallback = new AudioDeviceCallbackImpl();

    @MediaRoute2Info.SuitabilityStatus private final int mBuiltInSpeakerSuitabilityStatus;

    @NonNull
    private final AudioManager.OnDevicesForAttributesChangedListener
            mOnDevicesForAttributesChangedListener = this::onDevicesForAttributesChangedListener;

    @GuardedBy("this")
    @NonNull
    private final Map<String, MediaRoute2InfoHolder> mRouteIdToAvailableDeviceRoutes =
            new HashMap<>();

    @GuardedBy("this")
    @NonNull
    private MediaRoute2Info mSelectedRoute;

    // TODO: b/305199571 - Support nullable btAdapter and strategyForMedia which, when null, means
    // no support for transferring to inactive bluetooth routes and transferring to any routes
    // respectively.
    @RequiresPermission(
            anyOf = {
                Manifest.permission.MODIFY_AUDIO_ROUTING,
                Manifest.permission.QUERY_AUDIO_STATE
            })
    /* package */ AudioManagerRouteController(
            @NonNull Context context,
            @NonNull AudioManager audioManager,
            @NonNull Looper looper,
            @NonNull AudioProductStrategy strategyForMedia,
            @NonNull BluetoothAdapter btAdapter,
            @NonNull OnDeviceRouteChangedListener onDeviceRouteChangedListener) {
        mContext = Objects.requireNonNull(context);
        mAudioManager = Objects.requireNonNull(audioManager);
        mHandler = new Handler(Objects.requireNonNull(looper));
        mStrategyForMedia = Objects.requireNonNull(strategyForMedia);
        mOnDeviceRouteChangedListener = Objects.requireNonNull(onDeviceRouteChangedListener);

        mBuiltInSpeakerSuitabilityStatus =
                DeviceRouteController.getBuiltInSpeakerSuitabilityStatus(mContext);

        mBluetoothRouteController =
                new BluetoothDeviceRoutesManager(
                        mContext, mHandler, btAdapter, this::rebuildAvailableRoutesAndNotify);
        // Just build routes but don't notify. The caller may not expect the listener to be invoked
        // before this constructor has finished executing.
        rebuildAvailableRoutes();
    }

    @RequiresPermission(
            anyOf = {
                Manifest.permission.MODIFY_AUDIO_ROUTING,
                Manifest.permission.QUERY_AUDIO_STATE
            })
    @Override
    public void start(UserHandle mUser) {
        mBluetoothRouteController.start(mUser);
        mAudioManager.registerAudioDeviceCallback(mAudioDeviceCallback, mHandler);
        mAudioManager.addOnDevicesForAttributesChangedListener(
                AudioRoutingUtils.ATTRIBUTES_MEDIA,
                new HandlerExecutor(mHandler),
                mOnDevicesForAttributesChangedListener);
    }

    @RequiresPermission(
            anyOf = {
                Manifest.permission.MODIFY_AUDIO_ROUTING,
                Manifest.permission.QUERY_AUDIO_STATE
            })
    @Override
    public void stop() {
        mAudioManager.removeOnDevicesForAttributesChangedListener(
                mOnDevicesForAttributesChangedListener);
        mAudioManager.unregisterAudioDeviceCallback(mAudioDeviceCallback);
        mBluetoothRouteController.stop();
        mHandler.removeCallbacksAndMessages(/* token= */ null);
    }

    @Override
    @NonNull
    public synchronized MediaRoute2Info getSelectedRoute() {
        return mSelectedRoute;
    }

    @Override
    @NonNull
    public synchronized List<MediaRoute2Info> getAvailableRoutes() {
        return mRouteIdToAvailableDeviceRoutes.values().stream()
                .map(it -> it.mMediaRoute2Info)
                .toList();
    }

    @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
    @Override
    public void transferTo(@Nullable String routeId) {
        if (routeId == null) {
            // This should never happen: This branch should only execute when the matching bluetooth
            // route controller is not the no-op one.
            // TODO: b/305199571 - Make routeId non-null and remove this branch once we remove the
            // legacy route controller implementations.
            Slog.e(TAG, "Unexpected call to AudioPoliciesDeviceRouteController#transferTo(null)");
            return;
        }
        MediaRoute2InfoHolder mediaRoute2InfoHolder;
        synchronized (this) {
            mediaRoute2InfoHolder = mRouteIdToAvailableDeviceRoutes.get(routeId);
        }
        if (mediaRoute2InfoHolder == null) {
            Slog.w(TAG, "transferTo: Ignoring transfer request to unknown route id : " + routeId);
            return;
        }
        Runnable transferAction = getTransferActionForRoute(mediaRoute2InfoHolder);
        Runnable guardedTransferAction =
                () -> {
                    try {
                        transferAction.run();
                    } catch (Throwable throwable) {
                        // We swallow the exception to avoid crashing system_server, since this
                        // doesn't run on a binder thread.
                        Slog.e(
                                TAG,
                                "Unexpected exception while transferring to route id: " + routeId,
                                throwable);
                        mHandler.post(this::rebuildAvailableRoutesAndNotify);
                    }
                };
        // We post the transfer operation to the handler to avoid making these calls on a binder
        // thread. See class javadoc for details.
        mHandler.post(guardedTransferAction);
    }

    @RequiresPermission(
            anyOf = {
                Manifest.permission.MODIFY_AUDIO_ROUTING,
                Manifest.permission.QUERY_AUDIO_STATE
            })
    @Override
    public boolean updateVolume(int volume) {
        // TODO: b/305199571 - Optimize so that we only update the volume of the selected route. We
        // don't need to rebuild all available routes.
        rebuildAvailableRoutesAndNotify();
        return true;
    }

    private Runnable getTransferActionForRoute(MediaRoute2InfoHolder mediaRoute2InfoHolder) {
        if (mediaRoute2InfoHolder.mCorrespondsToInactiveBluetoothRoute) {
            String deviceAddress = mediaRoute2InfoHolder.mMediaRoute2Info.getAddress();
            return () -> {
                // By default, the last connected device is the active route so we don't
                // need to apply a routing audio policy.
                mBluetoothRouteController.activateBluetoothDeviceWithAddress(deviceAddress);
                mAudioManager.removePreferredDeviceForStrategy(mStrategyForMedia);
            };

        } else {
            AudioDeviceAttributes deviceAttributes =
                    new AudioDeviceAttributes(
                            AudioDeviceAttributes.ROLE_OUTPUT,
                            mediaRoute2InfoHolder.mAudioDeviceInfoType,
                            /* address= */ ""); // This is not a BT device, hence no address needed.
            return () ->
                    mAudioManager.setPreferredDeviceForStrategy(
                            mStrategyForMedia, deviceAttributes);
        }
    }

    @RequiresPermission(
            anyOf = {
                Manifest.permission.MODIFY_AUDIO_ROUTING,
                Manifest.permission.QUERY_AUDIO_STATE
            })
    private void onDevicesForAttributesChangedListener(
            AudioAttributes attributes, List<AudioDeviceAttributes> unusedAudioDeviceAttributes) {
        if (attributes.getUsage() == AudioAttributes.USAGE_MEDIA) {
            // We only care about the media usage. Ignore everything else.
            rebuildAvailableRoutesAndNotify();
        }
    }

    @RequiresPermission(
            anyOf = {
                Manifest.permission.MODIFY_AUDIO_ROUTING,
                Manifest.permission.QUERY_AUDIO_STATE
            })
    private void rebuildAvailableRoutesAndNotify() {
        rebuildAvailableRoutes();
        mOnDeviceRouteChangedListener.onDeviceRouteChanged();
    }

    @RequiresPermission(
            anyOf = {
                Manifest.permission.MODIFY_AUDIO_ROUTING,
                Manifest.permission.QUERY_AUDIO_STATE
            })
    private void rebuildAvailableRoutes() {
        List<AudioDeviceAttributes> attributesOfSelectedOutputDevices =
                mAudioManager.getDevicesForAttributes(MEDIA_USAGE_AUDIO_ATTRIBUTES);
        int selectedDeviceAttributesType;
        if (attributesOfSelectedOutputDevices.isEmpty()) {
            Slog.e(
                    TAG,
                    "Unexpected empty list of output devices for media. Using built-in speakers.");
            selectedDeviceAttributesType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        } else {
            if (attributesOfSelectedOutputDevices.size() > 1) {
                Slog.w(
                        TAG,
                        "AudioManager.getDevicesForAttributes returned more than one element. Using"
                                + " the first one.");
            }
            selectedDeviceAttributesType = attributesOfSelectedOutputDevices.get(0).getType();
        }

        updateAvailableRoutes(
                selectedDeviceAttributesType,
                /* audioDeviceInfos= */ mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS),
                /* availableBluetoothRoutes= */ mBluetoothRouteController
                        .getAvailableBluetoothRoutes(),
                /* musicVolume= */ mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                /* musicMaxVolume= */ mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                /* isVolumeFixed= */ mAudioManager.isVolumeFixed());
    }

    /**
     * Updates route and session info using the given information from {@link AudioManager}.
     *
     * <p>Synchronization is limited to this method in order to avoid calling into {@link
     * AudioManager} or {@link BluetoothDeviceRoutesManager} while holding a lock that may also be
     * acquired by binder threads. See class javadoc for more details.
     *
     * @param selectedDeviceAttributesType The {@link AudioDeviceInfo#getType() type} that
     *     corresponds to the currently selected route.
     * @param audioDeviceInfos The available audio outputs as obtained from {@link
     *     AudioManager#getDevices}.
     * @param availableBluetoothRoutes The available bluetooth routes as obtained from {@link
     *     BluetoothDeviceRoutesManager#getAvailableBluetoothRoutes()}.
     * @param musicVolume The volume of the music stream as obtained from {@link
     *     AudioManager#getStreamVolume}.
     * @param musicMaxVolume The max volume of the music stream as obtained from {@link
     *     AudioManager#getStreamMaxVolume}.
     * @param isVolumeFixed Whether the volume is fixed as obtained from {@link
     *     AudioManager#isVolumeFixed()}.
     */
    private synchronized void updateAvailableRoutes(
            int selectedDeviceAttributesType,
            AudioDeviceInfo[] audioDeviceInfos,
            List<MediaRoute2Info> availableBluetoothRoutes,
            int musicVolume,
            int musicMaxVolume,
            boolean isVolumeFixed) {
        mRouteIdToAvailableDeviceRoutes.clear();
        MediaRoute2InfoHolder newSelectedRouteHolder = null;
        for (AudioDeviceInfo audioDeviceInfo : audioDeviceInfos) {
            MediaRoute2Info mediaRoute2Info =
                    createMediaRoute2InfoFromAudioDeviceInfo(audioDeviceInfo);
            // Null means audioDeviceInfo is not a supported media output, like a phone's builtin
            // earpiece. We ignore those.
            if (mediaRoute2Info != null) {
                int audioDeviceInfoType = audioDeviceInfo.getType();
                MediaRoute2InfoHolder newHolder =
                        MediaRoute2InfoHolder.createForAudioManagerRoute(
                                mediaRoute2Info, audioDeviceInfoType);
                mRouteIdToAvailableDeviceRoutes.put(mediaRoute2Info.getId(), newHolder);
                if (selectedDeviceAttributesType == audioDeviceInfoType) {
                    newSelectedRouteHolder = newHolder;
                }
            }
        }

        if (mRouteIdToAvailableDeviceRoutes.isEmpty()) {
            // Due to an unknown reason (possibly an audio server crash), we ended up with an empty
            // list of routes. Our entire codebase assumes at least one system route always exists,
            // so we create a placeholder route represented as a built-in speaker for
            // user-presentation purposes.
            Slog.e(TAG, "Ended up with an empty list of routes. Creating a placeholder route.");
            MediaRoute2InfoHolder placeholderRouteHolder = createPlaceholderBuiltinSpeakerRoute();
            String placeholderRouteId = placeholderRouteHolder.mMediaRoute2Info.getId();
            mRouteIdToAvailableDeviceRoutes.put(placeholderRouteId, placeholderRouteHolder);
        }

        if (newSelectedRouteHolder == null) {
            Slog.e(
                    TAG,
                    "Could not map this selected device attribute type to an available route: "
                            + selectedDeviceAttributesType);
            // We know mRouteIdToAvailableDeviceRoutes is not empty.
            newSelectedRouteHolder = mRouteIdToAvailableDeviceRoutes.values().iterator().next();
        }
        MediaRoute2InfoHolder selectedRouteHolderWithUpdatedVolumeInfo =
                newSelectedRouteHolder.copyWithVolumeInfo(
                        musicVolume, musicMaxVolume, isVolumeFixed);
        mRouteIdToAvailableDeviceRoutes.put(
                newSelectedRouteHolder.mMediaRoute2Info.getId(),
                selectedRouteHolderWithUpdatedVolumeInfo);
        mSelectedRoute = selectedRouteHolderWithUpdatedVolumeInfo.mMediaRoute2Info;

        // We only add those BT routes that we have not already obtained from audio manager (which
        // are active).
        availableBluetoothRoutes.stream()
                .filter(it -> !mRouteIdToAvailableDeviceRoutes.containsKey(it.getId()))
                .map(MediaRoute2InfoHolder::createForInactiveBluetoothRoute)
                .forEach(
                        it -> mRouteIdToAvailableDeviceRoutes.put(it.mMediaRoute2Info.getId(), it));
    }

    private MediaRoute2InfoHolder createPlaceholderBuiltinSpeakerRoute() {
        int type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        return MediaRoute2InfoHolder.createForAudioManagerRoute(
                createMediaRoute2Info(
                        /* routeId= */ null, type, /* productName= */ null, /* address= */ null),
                type);
    }

    @Nullable
    private MediaRoute2Info createMediaRoute2InfoFromAudioDeviceInfo(
            AudioDeviceInfo audioDeviceInfo) {
        String address = audioDeviceInfo.getAddress();

        // Passing a null route id means we want to get the default id for the route. Generally, we
        // only expect to pass null for non-Bluetooth routes.
        String routeId = null;

        // We use the name from the port instead AudioDeviceInfo#getProductName because the latter
        // replaces empty names with the name of the device (example: Pixel 8). In that case we want
        // to derive a name ourselves from the type instead.
        String deviceName = audioDeviceInfo.getPort().name();

        if (!TextUtils.isEmpty(address)) {
            routeId = mBluetoothRouteController.getRouteIdForBluetoothAddress(address);
            deviceName = mBluetoothRouteController.getNameForBluetoothAddress(address);
        }
        return createMediaRoute2Info(routeId, audioDeviceInfo.getType(), deviceName, address);
    }

    /**
     * Creates a new {@link MediaRoute2Info} using the provided information.
     *
     * @param routeId A route id, or null to use an id pre-defined for the given {@code type}.
     * @param audioDeviceInfoType The type as obtained from {@link AudioDeviceInfo#getType}.
     * @param deviceName A human readable name to populate the route's {@link
     *     MediaRoute2Info#getName name}, or null to use a predefined name for the given {@code
     *     type}.
     * @param address The type as obtained from {@link AudioDeviceInfo#getAddress()} or {@link
     *     BluetoothDevice#getAddress()}.
     * @return The new {@link MediaRoute2Info}.
     */
    @Nullable
    private MediaRoute2Info createMediaRoute2Info(
            @Nullable String routeId,
            int audioDeviceInfoType,
            @Nullable CharSequence deviceName,
            @Nullable String address) {
        SystemRouteInfo systemRouteInfo =
                AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.get(audioDeviceInfoType);
        if (systemRouteInfo == null) {
            // Device type that's intentionally unsupported for media output, like the built-in
            // earpiece.
            return null;
        }
        CharSequence humanReadableName = deviceName;
        if (TextUtils.isEmpty(humanReadableName)) {
            humanReadableName = mContext.getResources().getText(systemRouteInfo.mNameResource);
        }
        if (routeId == null) {
            // The caller hasn't provided an id, so we use a pre-defined one. This happens when we
            // are creating a non-BT route, or we are creating a BT route but a race condition
            // caused AudioManager to expose the BT route before BluetoothAdapter, preventing us
            // from getting an id using BluetoothRouteController#getRouteIdForBluetoothAddress.
            routeId = systemRouteInfo.mDefaultRouteId;
        }
        MediaRoute2Info.Builder builder = new MediaRoute2Info.Builder(routeId, humanReadableName)
                .setType(systemRouteInfo.mMediaRoute2InfoType)
                .setAddress(address)
                .setSystemRoute(true)
                .addFeature(FEATURE_LIVE_AUDIO)
                .addFeature(FEATURE_LOCAL_PLAYBACK)
                .setConnectionState(MediaRoute2Info.CONNECTION_STATE_CONNECTED);

        if (systemRouteInfo.mMediaRoute2InfoType == MediaRoute2Info.TYPE_BUILTIN_SPEAKER) {
            builder.setSuitabilityStatus(mBuiltInSpeakerSuitabilityStatus);
        }

        return builder.build();
    }

    /**
     * Holds a {@link MediaRoute2Info} and associated information that we don't want to put in the
     * {@link MediaRoute2Info} class because it's solely necessary for the implementation of this
     * class.
     */
    private static class MediaRoute2InfoHolder {

        public final MediaRoute2Info mMediaRoute2Info;
        public final int mAudioDeviceInfoType;
        public final boolean mCorrespondsToInactiveBluetoothRoute;

        public static MediaRoute2InfoHolder createForAudioManagerRoute(
                MediaRoute2Info mediaRoute2Info, int audioDeviceInfoType) {
            return new MediaRoute2InfoHolder(
                    mediaRoute2Info,
                    audioDeviceInfoType,
                    /* correspondsToInactiveBluetoothRoute= */ false);
        }

        public static MediaRoute2InfoHolder createForInactiveBluetoothRoute(
                MediaRoute2Info mediaRoute2Info) {
            // There's no corresponding audio device info, hence the audio device info type is
            // unknown.
            return new MediaRoute2InfoHolder(
                    mediaRoute2Info,
                    /* audioDeviceInfoType= */ AudioDeviceInfo.TYPE_UNKNOWN,
                    /* correspondsToInactiveBluetoothRoute= */ true);
        }

        private MediaRoute2InfoHolder(
                MediaRoute2Info mediaRoute2Info,
                int audioDeviceInfoType,
                boolean correspondsToInactiveBluetoothRoute) {
            mMediaRoute2Info = mediaRoute2Info;
            mAudioDeviceInfoType = audioDeviceInfoType;
            mCorrespondsToInactiveBluetoothRoute = correspondsToInactiveBluetoothRoute;
        }

        public MediaRoute2InfoHolder copyWithVolumeInfo(
                int musicVolume, int musicMaxVolume, boolean isVolumeFixed) {
            MediaRoute2Info routeInfoWithVolumeInfo =
                    new MediaRoute2Info.Builder(mMediaRoute2Info)
                            .setVolumeHandling(
                                    isVolumeFixed
                                            ? MediaRoute2Info.PLAYBACK_VOLUME_FIXED
                                            : MediaRoute2Info.PLAYBACK_VOLUME_VARIABLE)
                            .setVolume(musicVolume)
                            .setVolumeMax(musicMaxVolume)
                            .build();
            return new MediaRoute2InfoHolder(
                    routeInfoWithVolumeInfo,
                    mAudioDeviceInfoType,
                    mCorrespondsToInactiveBluetoothRoute);
        }
    }

    /**
     * Holds route information about an {@link AudioDeviceInfo#getType() audio device info type}.
     */
    private static class SystemRouteInfo {
        /** The type to use for {@link MediaRoute2Info#getType()}. */
        public final int mMediaRoute2InfoType;

        /**
         * Holds the route id to use if no other id is provided.
         *
         * <p>We only expect this id to be used for non-bluetooth routes. For bluetooth routes, in a
         * normal scenario, the id is generated from the device information (like address, or
         * hiSyncId), and this value is ignored. A non-normal scenario may occur when there's race
         * condition between {@link BluetoothAdapter} and {@link AudioManager}, who are not
         * synchronized.
         */
        public final String mDefaultRouteId;

        /**
         * The name to use for {@link MediaRoute2Info#getName()}.
         *
         * <p>Usually replaced by the UI layer with a localized string.
         */
        public final int mNameResource;

        private SystemRouteInfo(int mediaRoute2InfoType, String defaultRouteId, int nameResource) {
            mMediaRoute2InfoType = mediaRoute2InfoType;
            mDefaultRouteId = defaultRouteId;
            mNameResource = nameResource;
        }
    }

    private class AudioDeviceCallbackImpl extends AudioDeviceCallback {
        @RequiresPermission(Manifest.permission.MODIFY_AUDIO_ROUTING)
        @Override
        public void onAudioDevicesAdded(AudioDeviceInfo[] addedDevices) {
            for (AudioDeviceInfo deviceInfo : addedDevices) {
                if (AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.contains(deviceInfo.getType())) {
                    // When a new valid media output is connected, we clear any routing policies so
                    // that the default routing logic from the audio framework kicks in. As a result
                    // of this, when the user connects a bluetooth device or a wired headset, the
                    // new device becomes the active route, which is the traditional behavior.
                    mAudioManager.removePreferredDeviceForStrategy(mStrategyForMedia);
                    rebuildAvailableRoutesAndNotify();
                    break;
                }
            }
        }

        @RequiresPermission(
                anyOf = {
                    Manifest.permission.MODIFY_AUDIO_ROUTING,
                    Manifest.permission.QUERY_AUDIO_STATE
                })
        @Override
        public void onAudioDevicesRemoved(AudioDeviceInfo[] removedDevices) {
            for (AudioDeviceInfo deviceInfo : removedDevices) {
                if (AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.contains(deviceInfo.getType())) {
                    rebuildAvailableRoutesAndNotify();
                    break;
                }
            }
        }
    }

    static {
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_BUILTIN_SPEAKER,
                        /* defaultRouteId= */ "ROUTE_ID_BUILTIN_SPEAKER",
                        /* nameResource= */ R.string.default_audio_route_name));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_WIRED_HEADSET,
                        /* defaultRouteId= */ "ROUTE_ID_WIRED_HEADSET",
                        /* nameResource= */ R.string.default_audio_route_name_headphones));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_WIRED_HEADPHONES,
                        /* defaultRouteId= */ "ROUTE_ID_WIRED_HEADPHONES",
                        /* nameResource= */ R.string.default_audio_route_name_headphones));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_BLUETOOTH_A2DP,
                        /* defaultRouteId= */ "ROUTE_ID_BLUETOOTH_A2DP",
                        /* nameResource= */ R.string.bluetooth_a2dp_audio_route_name));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_HDMI,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_HDMI,
                        /* defaultRouteId= */ "ROUTE_ID_HDMI",
                        /* nameResource= */ R.string.default_audio_route_name_external_device));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_DOCK,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_DOCK,
                        /* defaultRouteId= */ "ROUTE_ID_DOCK",
                        /* nameResource= */ R.string.default_audio_route_name_dock_speakers));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_USB_DEVICE,
                        /* defaultRouteId= */ "ROUTE_ID_USB_DEVICE",
                        /* nameResource= */ R.string.default_audio_route_name_usb));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_USB_HEADSET,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_USB_HEADSET,
                        /* defaultRouteId= */ "ROUTE_ID_USB_HEADSET",
                        /* nameResource= */ R.string.default_audio_route_name_usb));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_HDMI_ARC,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_HDMI_ARC,
                        /* defaultRouteId= */ "ROUTE_ID_HDMI_ARC",
                        /* nameResource= */ R.string.default_audio_route_name_external_device));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_HDMI_EARC,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_HDMI_EARC,
                        /* defaultRouteId= */ "ROUTE_ID_HDMI_EARC",
                        /* nameResource= */ R.string.default_audio_route_name_external_device));
        // TODO: b/305199571 - Add a proper type constants and human readable names for AUX_LINE,
        // LINE_ANALOG, LINE_DIGITAL, BLE_BROADCAST, BLE_SPEAKER, BLE_HEADSET, and HEARING_AID.
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_HEARING_AID,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_HEARING_AID,
                        /* defaultRouteId= */ "ROUTE_ID_HEARING_AID",
                        /* nameResource= */ R.string.bluetooth_a2dp_audio_route_name));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_BLE_HEADSET,
                        /* defaultRouteId= */ "ROUTE_ID_BLE_HEADSET",
                        /* nameResource= */ R.string.bluetooth_a2dp_audio_route_name));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_BLE_HEADSET, // TODO: b/305199571 - Make a new type.
                        /* defaultRouteId= */ "ROUTE_ID_BLE_SPEAKER",
                        /* nameResource= */ R.string.bluetooth_a2dp_audio_route_name));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_BLE_BROADCAST,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_BLE_HEADSET,
                        /* defaultRouteId= */ "ROUTE_ID_BLE_BROADCAST",
                        /* nameResource= */ R.string.bluetooth_a2dp_audio_route_name));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_UNKNOWN,
                        /* defaultRouteId= */ "ROUTE_ID_LINE_DIGITAL",
                        /* nameResource= */ R.string.default_audio_route_name_external_device));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_UNKNOWN,
                        /* defaultRouteId= */ "ROUTE_ID_LINE_ANALOG",
                        /* nameResource= */ R.string.default_audio_route_name_external_device));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_AUX_LINE,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_UNKNOWN,
                        /* defaultRouteId= */ "ROUTE_ID_AUX_LINE",
                        /* nameResource= */ R.string.default_audio_route_name_external_device));
        AUDIO_DEVICE_INFO_TYPE_TO_ROUTE_INFO.put(
                AudioDeviceInfo.TYPE_DOCK_ANALOG,
                new SystemRouteInfo(
                        MediaRoute2Info.TYPE_DOCK,
                        /* defaultRouteId= */ "ROUTE_ID_DOCK_ANALOG",
                        /* nameResource= */ R.string.default_audio_route_name_dock_speakers));
    }
}
