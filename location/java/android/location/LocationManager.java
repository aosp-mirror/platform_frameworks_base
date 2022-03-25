/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.location;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.Manifest.permission.LOCATION_BYPASS;
import static android.Manifest.permission.LOCATION_HARDWARE;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.location.LocationRequest.createFromDeprecatedCriteria;
import static android.location.LocationRequest.createFromDeprecatedProvider;

import static com.android.internal.util.ConcurrentUtils.DIRECT_EXECUTOR;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.PropertyInvalidatedCache;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.provider.IProviderRequestListener;
import android.location.provider.ProviderProperties;
import android.location.provider.ProviderRequest;
import android.location.provider.ProviderRequest.ChangedListener;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.ICancellationSignal;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.PackageTagsList;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.listeners.ListenerExecutor;
import com.android.internal.listeners.ListenerTransport;
import com.android.internal.listeners.ListenerTransportManager;
import com.android.internal.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * This class provides access to the system location services. These services allow applications to
 * obtain periodic updates of the device's geographical location, or to be notified when the device
 * enters the proximity of a given geographical location.
 *
 * <p class="note">Unless otherwise noted, all Location API methods require the
 * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION} or
 * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permissions. If your application only
 * has the coarse permission then providers will still return location results, but the exact
 * location will be obfuscated to a coarse level of accuracy.
 */
@SuppressWarnings({"deprecation"})
@SystemService(Context.LOCATION_SERVICE)
@RequiresFeature(PackageManager.FEATURE_LOCATION)
public class LocationManager {

    /**
     * For apps targeting Android S and above, immutable PendingIntents passed into location APIs
     * will generate an IllegalArgumentException.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    public static final long BLOCK_IMMUTABLE_PENDING_INTENTS = 171317480L;

    /**
     * For apps targeting Android S and above, LocationRequest system APIs may not be used with
     * PendingIntent location requests.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    public static final long BLOCK_PENDING_INTENT_SYSTEM_API_USAGE = 169887240L;

    /**
     * For apps targeting Android S and above, location clients may receive historical locations
     * (from before the present time) under some circumstances.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    public static final long DELIVER_HISTORICAL_LOCATIONS = 73144566L;

    /**
     * For apps targeting Android R and above, {@link #getProvider(String)} will no longer throw any
     * security exceptions.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    public static final long GET_PROVIDER_SECURITY_EXCEPTIONS = 150935354L;

    /**
     * For apps targeting Android K and above, supplied {@link PendingIntent}s must be targeted to a
     * specific package.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.JELLY_BEAN)
    public static final long BLOCK_UNTARGETED_PENDING_INTENTS = 148963590L;

    /**
     * For apps targeting Android K and above, incomplete locations may not be passed to
     * {@link #setTestProviderLocation}.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.JELLY_BEAN)
    public static final long BLOCK_INCOMPLETE_LOCATIONS = 148964793L;

    /**
     * For apps targeting Android S and above, all {@link GpsStatus} API usage must be replaced with
     * {@link GnssStatus} APIs.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    public static final long BLOCK_GPS_STATUS_USAGE = 144027538L;

    /**
     * Standard name of the network location provider.
     *
     * <p>If present, this provider determines location based on nearby of cell tower and WiFi
     * access points. Operation of this provider may require a data connection.
     */
    public static final String NETWORK_PROVIDER = "network";

    /**
     * Standard name of the GNSS location provider.
     *
     * <p>If present, this provider determines location using GNSS satellites. The responsiveness
     * and accuracy of location fixes may depend on GNSS signal conditions.
     *
     * <p>Locations returned from this provider are with respect to the primary GNSS antenna
     * position within the device. {@link #getGnssAntennaInfos()} may be used to determine the GNSS
     * antenna position with respect to the Android Coordinate System, and convert between them if
     * necessary. This is generally only necessary for high accuracy applications.
     *
     * <p>The extras Bundle for locations derived by this location provider may contain the
     * following key/value pairs:
     * <ul>
     * <li> satellites - the number of satellites used to derive the fix
     * </ul>
     */
    public static final String GPS_PROVIDER = "gps";

    /**
     * A special location provider for receiving locations without actively initiating a location
     * fix. This location provider is always present.
     *
     * <p>This provider can be used to passively receive location updates when other applications or
     * services request them without actually requesting the locations yourself. This provider will
     * only return locations generated by other providers.
     */
    public static final String PASSIVE_PROVIDER = "passive";

    /**
     * Standard name of the fused location provider.
     *
     * <p>If present, this provider may combine inputs from several other location providers to
     * provide the best possible location fix. It is implicitly used for all requestLocationUpdates
     * APIs that involve a {@link Criteria}.
     */
    public static final String FUSED_PROVIDER = "fused";

    /**
     * Key used for the Bundle extra holding a boolean indicating whether
     * a proximity alert is entering (true) or exiting (false)..
     */
    public static final String KEY_PROXIMITY_ENTERING = "entering";

    /**
     * This key is no longer in use.
     *
     * <p>Key used for a Bundle extra holding an Integer status value when a status change is
     * broadcast using a PendingIntent.
     *
     * @deprecated Status changes are deprecated and no longer broadcast from Android Q onwards.
     */
    @Deprecated
    public static final String KEY_STATUS_CHANGED = "status";

    /**
     * Key used for an extra holding a boolean enabled/disabled status value when a provider
     * enabled/disabled event is broadcast using a PendingIntent.
     *
     * @see #requestLocationUpdates(String, LocationRequest, PendingIntent)
     */
    public static final String KEY_PROVIDER_ENABLED = "providerEnabled";

    /**
     * Key used for an extra holding a {@link Location} value when a location change is sent using
     * a PendingIntent. If the location change includes a list of batched locations via
     * {@link #KEY_LOCATIONS} then this key will still be present, and will hold the last location
     * in the batch. Use {@link Intent#getParcelableExtra(String)} to retrieve the location.
     *
     * @see #requestLocationUpdates(String, LocationRequest, PendingIntent)
     */
    public static final String KEY_LOCATION_CHANGED = "location";

    /**
     * Key used for an extra holding a array of {@link Location}s when a location change is sent
     * using a PendingIntent. This key will only be present if the location change includes
     * multiple (ie, batched) locations, otherwise only {@link #KEY_LOCATION_CHANGED} will be
     * present. Use {@link Intent#getParcelableArrayExtra(String)} to retrieve the locations.
     *
     * <p>The array of locations will never be empty, and will ordered from earliest location to
     * latest location, the same as with {@link LocationListener#onLocationChanged(List)}.
     *
     * @see #requestLocationUpdates(String, LocationRequest, PendingIntent)
     */
    public static final String KEY_LOCATIONS = "locations";

    /**
     * Key used for an extra holding an integer request code when location flush completion is sent
     * using a PendingIntent.
     *
     * @see #requestFlush(String, PendingIntent, int)
     */
    public static final String KEY_FLUSH_COMPLETE = "flushComplete";

    /**
     * Broadcast intent action when the set of enabled location providers changes. To check the
     * status of a provider, use {@link #isProviderEnabled(String)}. From Android Q and above, will
     * include a string intent extra, {@link #EXTRA_PROVIDER_NAME}, with the name of the provider
     * whose state has changed. From Android R and above, will include a boolean intent extra,
     * {@link #EXTRA_PROVIDER_ENABLED}, with the enabled state of the provider.
     *
     * @see #EXTRA_PROVIDER_NAME
     * @see #EXTRA_PROVIDER_ENABLED
     * @see #isProviderEnabled(String)
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String PROVIDERS_CHANGED_ACTION = "android.location.PROVIDERS_CHANGED";

    /**
     * Intent extra included with {@link #PROVIDERS_CHANGED_ACTION} broadcasts, containing the name
     * of the location provider that has changed.
     *
     * @see #PROVIDERS_CHANGED_ACTION
     * @see #EXTRA_PROVIDER_ENABLED
     */
    public static final String EXTRA_PROVIDER_NAME = "android.location.extra.PROVIDER_NAME";

    /**
     * Intent extra included with {@link #PROVIDERS_CHANGED_ACTION} broadcasts, containing the
     * boolean enabled state of the location provider that has changed.
     *
     * @see #PROVIDERS_CHANGED_ACTION
     * @see #EXTRA_PROVIDER_NAME
     */
    public static final String EXTRA_PROVIDER_ENABLED = "android.location.extra.PROVIDER_ENABLED";

    /**
     * Broadcast intent action when the device location enabled state changes. From Android R and
     * above, will include a boolean intent extra, {@link #EXTRA_LOCATION_ENABLED}, with the enabled
     * state of location.
     *
     * @see #EXTRA_LOCATION_ENABLED
     * @see #isLocationEnabled()
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String MODE_CHANGED_ACTION = "android.location.MODE_CHANGED";

    /**
     * Intent extra included with {@link #MODE_CHANGED_ACTION} broadcasts, containing the boolean
     * enabled state of location.
     *
     * @see #MODE_CHANGED_ACTION
     */
    public static final String EXTRA_LOCATION_ENABLED = "android.location.extra.LOCATION_ENABLED";

    /**
     * Broadcast intent action when the ADAS (Advanced Driving Assistance Systems) GNSS location
     * enabled state changes. Includes a boolean intent extra, {@link #EXTRA_ADAS_GNSS_ENABLED},
     * with the enabled state of ADAS GNSS location. This broadcast only has meaning on automotive
     * devices.
     *
     * @see #EXTRA_ADAS_GNSS_ENABLED
     * @see #isAdasGnssLocationEnabled()
     *
     * @hide
     */
    @SystemApi
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_ADAS_GNSS_ENABLED_CHANGED =
            "android.location.action.ADAS_GNSS_ENABLED_CHANGED";

    /**
     * Intent extra included with {@link #ACTION_ADAS_GNSS_ENABLED_CHANGED} broadcasts, containing
     * the boolean enabled state of ADAS GNSS location.
     *
     * @see #ACTION_ADAS_GNSS_ENABLED_CHANGED
     *
     * @hide
     */
    @SystemApi
    public static final String EXTRA_ADAS_GNSS_ENABLED = "android.location.extra.ADAS_GNSS_ENABLED";

    /**
     * Broadcast intent action indicating that a high power location requests
     * has either started or stopped being active.  The current state of
     * active location requests should be read from AppOpsManager using
     * {@code OP_MONITOR_HIGH_POWER_LOCATION}.
     *
     * @hide
     * @deprecated This action is unnecessary from Android S forward.
     */
    @Deprecated
    public static final String HIGH_POWER_REQUEST_CHANGE_ACTION =
            "android.location.HIGH_POWER_REQUEST_CHANGE";

    /**
     * Broadcast intent action when GNSS capabilities change. This is most common at boot time as
     * GNSS capabilities are queried from the chipset. Includes an intent extra,
     * {@link #EXTRA_GNSS_CAPABILITIES}, with the new {@link GnssCapabilities}.
     *
     * @see #EXTRA_GNSS_CAPABILITIES
     * @see #getGnssCapabilities()
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_GNSS_CAPABILITIES_CHANGED =
            "android.location.action.GNSS_CAPABILITIES_CHANGED";

    /**
     * Intent extra included with {@link #ACTION_GNSS_CAPABILITIES_CHANGED} broadcasts, containing
     * the new {@link GnssCapabilities}.
     *
     * @see #ACTION_GNSS_CAPABILITIES_CHANGED
     */
    public static final String EXTRA_GNSS_CAPABILITIES = "android.location.extra.GNSS_CAPABILITIES";

    /**
     * Broadcast intent action for Settings app to inject a footer at the bottom of location
     * settings. This is for use only by apps that are included in the system image.
     *
     * <p>To inject a footer to location settings, you must declare a broadcast receiver for
     * this action in the manifest:
     * <pre>
     *     &lt;receiver android:name="com.example.android.footer.MyFooterInjector"&gt;
     *         &lt;intent-filter&gt;
     *             &lt;action android:name="com.android.settings.location.INJECT_FOOTER" /&gt;
     *         &lt;/intent-filter&gt;
     *         &lt;meta-data
     *             android:name="com.android.settings.location.FOOTER_STRING"
     *             android:resource="@string/my_injected_footer_string" /&gt;
     *     &lt;/receiver&gt;
     * </pre>
     *
     * <p>This broadcast receiver will never actually be invoked. See also
     * {#METADATA_SETTINGS_FOOTER_STRING}.
     *
     * @hide
     */
    public static final String SETTINGS_FOOTER_DISPLAYED_ACTION =
            "com.android.settings.location.DISPLAYED_FOOTER";

    /**
     * Metadata name for {@link LocationManager#SETTINGS_FOOTER_DISPLAYED_ACTION} broadcast
     * receivers to specify a string resource id as location settings footer text. This is for use
     * only by apps that are included in the system image.
     *
     * <p>See {@link #SETTINGS_FOOTER_DISPLAYED_ACTION} for more detail on how to use.
     *
     * @hide
     */
    public static final String METADATA_SETTINGS_FOOTER_STRING =
            "com.android.settings.location.FOOTER_STRING";

    private static final long MAX_SINGLE_LOCATION_TIMEOUT_MS = 30 * 1000;

    private static final String CACHE_KEY_LOCATION_ENABLED_PROPERTY =
            "cache_key.location_enabled";

    static ILocationManager getService() throws RemoteException {
        try {
            return ILocationManager.Stub.asInterface(
                    ServiceManager.getServiceOrThrow(Context.LOCATION_SERVICE));
        } catch (ServiceManager.ServiceNotFoundException e) {
            throw new RemoteException(e);
        }
    }

    private static volatile LocationEnabledCache sLocationEnabledCache =
            new LocationEnabledCache(4);

    @GuardedBy("sLocationListeners")
    private static final WeakHashMap<LocationListener, WeakReference<LocationListenerTransport>>
            sLocationListeners = new WeakHashMap<>();

    // allows lazy instantiation since most processes do not use GNSS APIs
    private static class GnssLazyLoader {
        static final GnssStatusTransportManager sGnssStatusListeners =
                new GnssStatusTransportManager();
        static final GnssNmeaTransportManager sGnssNmeaListeners =
                new GnssNmeaTransportManager();
        static final GnssMeasurementsTransportManager sGnssMeasurementsListeners =
                new GnssMeasurementsTransportManager();
        static final GnssAntennaTransportManager sGnssAntennaInfoListeners =
                new GnssAntennaTransportManager();
        static final GnssNavigationTransportManager sGnssNavigationListeners =
                new GnssNavigationTransportManager();
    }

    private static class ProviderRequestLazyLoader {
        static final ProviderRequestTransportManager sProviderRequestListeners =
                new ProviderRequestTransportManager();
    }

    final Context mContext;
    final ILocationManager mService;

    /**
     * @hide
     */
    public LocationManager(@NonNull Context context, @NonNull ILocationManager service) {
        mContext = Objects.requireNonNull(context);
        mService = Objects.requireNonNull(service);
    }

    /**
     * @hide
     */
    @TestApi
    public @NonNull String[] getBackgroundThrottlingWhitelist() {
        try {
            return mService.getBackgroundThrottlingWhitelist();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @deprecated Do not use.
     * @hide
     */
    @Deprecated
    @TestApi
    public @NonNull String[] getIgnoreSettingsWhitelist() {
        return new String[0];
    }

    /**
     * For testing purposes only.
     * @hide
     */
    @TestApi
    public @NonNull PackageTagsList getIgnoreSettingsAllowlist() {
        try {
            return mService.getIgnoreSettingsAllowlist();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the extra location controller package on the device.
     *
     * @hide
     */
    @SystemApi
    public @Nullable String getExtraLocationControllerPackage() {
        try {
            return mService.getExtraLocationControllerPackage();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the extra location controller package for location services on the device.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void setExtraLocationControllerPackage(@Nullable String packageName) {
        try {
            mService.setExtraLocationControllerPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether the extra location controller package is currently enabled on the device.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void setExtraLocationControllerPackageEnabled(boolean enabled) {
        try {
            mService.setExtraLocationControllerPackageEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether extra location controller package is currently enabled on the device.
     *
     * @hide
     */
    @SystemApi
    public boolean isExtraLocationControllerPackageEnabled() {
        try {
            return mService.isExtraLocationControllerPackageEnabled();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the extra location controller package for location services on the device.
     *
     * @removed
     * @deprecated Use {@link #setExtraLocationControllerPackage} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void setLocationControllerExtraPackage(String packageName) {
        try {
            mService.setExtraLocationControllerPackage(packageName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set whether the extra location controller package is currently enabled on the device.
     *
     * @removed
     * @deprecated Use {@link #setExtraLocationControllerPackageEnabled} instead.
     * @hide
     */
    @SystemApi
    @Deprecated
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void setLocationControllerExtraPackageEnabled(boolean enabled) {
        try {
            mService.setExtraLocationControllerPackageEnabled(enabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current enabled/disabled state of location. To listen for changes, see
     * {@link #MODE_CHANGED_ACTION}.
     *
     * @return true if location is enabled and false if location is disabled.
     */
    public boolean isLocationEnabled() {
        return isLocationEnabledForUser(mContext.getUser());
    }

    /**
     * Returns the current enabled/disabled state of location for the given user.
     *
     * @param userHandle the user to query
     * @return true if location is enabled and false if location is disabled.
     *
     * @hide
     */
    @SystemApi
    public boolean isLocationEnabledForUser(@NonNull UserHandle userHandle) {
        // skip the cache for any "special" user ids - special ids like CURRENT_USER may change
        // their meaning over time and should never be in the cache. we could resolve the special
        // user ids here, but that would require an x-process call anyways, and the whole point of
        // the cache is to avoid x-process calls.
        if (userHandle.getIdentifier() >= 0) {
            PropertyInvalidatedCache<Integer, Boolean> cache = sLocationEnabledCache;
            if (cache != null) {
                return cache.query(userHandle.getIdentifier());
            }
        }

        try {
            return mService.isLocationEnabledForUser(userHandle.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables location for the given user.
     *
     * @param enabled true to enable location and false to disable location.
     * @param userHandle the user to set
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(WRITE_SECURE_SETTINGS)
    public void setLocationEnabledForUser(boolean enabled, @NonNull UserHandle userHandle) {
        try {
            mService.setLocationEnabledForUser(enabled, userHandle.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current enabled/disabled state of ADAS (Advanced Driving Assistance Systems)
     * GNSS location access for the given user. This controls safety critical automotive access to
     * GNSS location. This only has meaning on automotive devices.
     *
     * @return true if ADAS location is enabled and false if ADAS location is disabled.
     *
     * @hide
     */
    @SystemApi
    public boolean isAdasGnssLocationEnabled() {
        try {
            return mService.isAdasGnssLocationEnabledForUser(mContext.getUser().getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Enables or disables ADAS (Advanced Driving Assistance Systems) GNSS location access for the
     * given user. This only has meaning on automotive devices.
     *
     * @param enabled true to enable ADAS location and false to disable ADAS location.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(LOCATION_BYPASS)
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    public void setAdasGnssLocationEnabled(boolean enabled) {
        try {
            mService.setAdasGnssLocationEnabledForUser(enabled, mContext.getUser().getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current enabled/disabled status of the given provider. To listen for changes, see
     * {@link #PROVIDERS_CHANGED_ACTION}.
     *
     * Before API version {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this method would throw
     * {@link SecurityException} if the location permissions were not sufficient to use the
     * specified provider.
     *
     * @param provider a provider listed by {@link #getAllProviders()}
     * @return true if the provider exists and is enabled
     *
     * @throws IllegalArgumentException if provider is null
     */
    public boolean isProviderEnabled(@NonNull String provider) {
        return isProviderEnabledForUser(provider, Process.myUserHandle());
    }

    /**
     * Returns the current enabled/disabled status of the given provider and user. Callers should
     * prefer {@link #isLocationEnabledForUser(UserHandle)} unless they depend on provider-specific
     * APIs.
     *
     * Before API version {@link android.os.Build.VERSION_CODES#LOLLIPOP}, this method would throw
     * {@link SecurityException} if the location permissions were not sufficient to use the
     * specified provider.
     *
     * @param provider a provider listed by {@link #getAllProviders()}
     * @param userHandle the user to query
     * @return true if the provider exists and is enabled
     *
     * @throws IllegalArgumentException if provider is null
     * @hide
     */
    @SystemApi
    public boolean isProviderEnabledForUser(
            @NonNull String provider, @NonNull UserHandle userHandle) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        try {
            return mService.isProviderEnabledForUser(provider, userHandle.getIdentifier());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Method for enabling or disabling a single location provider. This method is deprecated and
     * functions as a best effort. It should not be relied on in any meaningful sense as providers
     * may no longer be enabled or disabled by clients.
     *
     * @param provider a provider listed by {@link #getAllProviders()}
     * @param enabled whether to enable or disable the provider
     * @param userHandle the user to set
     * @return true if the value was set, false otherwise
     *
     * @throws IllegalArgumentException if provider is null
     * @deprecated Do not manipulate providers individually, use
     * {@link #setLocationEnabledForUser(boolean, UserHandle)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(WRITE_SECURE_SETTINGS)
    public boolean setProviderEnabledForUser(
            @NonNull String provider, boolean enabled, @NonNull UserHandle userHandle) {
        Preconditions.checkArgument(provider != null, "invalid null provider");
        return false;
    }

    /**
     * Set whether GNSS requests are suspended on the automotive device.
     *
     * For devices where GNSS prevents the system from going into a low power state, GNSS should
     * be suspended right before going into the lower power state and resumed right after the device
     * wakes up.
     *
     * This method disables GNSS and should only be used for power management use cases such as
     * suspend-to-RAM or suspend-to-disk.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    @RequiresPermission(android.Manifest.permission.CONTROL_AUTOMOTIVE_GNSS)
    public void setAutomotiveGnssSuspended(boolean suspended) {
        try {
            mService.setAutomotiveGnssSuspended(suspended);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Return whether GNSS requests are suspended on the automotive device.
     *
     * @return true if GNSS requests are suspended and false if they aren't.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresFeature(PackageManager.FEATURE_AUTOMOTIVE)
    @RequiresPermission(android.Manifest.permission.CONTROL_AUTOMOTIVE_GNSS)
    public boolean isAutomotiveGnssSuspended() {
        try {
            return mService.isAutomotiveGnssSuspended();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the last known location from the fused provider, or null if there is no last known
     * location. The returned location may be quite old in some circumstances, so the age of the
     * location should always be checked.
     *
     * @return the last known location, or null if not available
     *
     * @throws SecurityException if no suitable location permission is present
     *
     * @hide
     */
    @Nullable
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public Location getLastLocation() {
        return getLastKnownLocation(FUSED_PROVIDER);
    }

    /**
     * Gets the last known location from the given provider, or null if there is no last known
     * location. The returned location may be quite old in some circumstances, so the age of the
     * location should always be checked.
     *
     * <p>This will never activate sensors to compute a new location, and will only ever return a
     * cached location.
     *
     * <p>See also {@link #getCurrentLocation(String, CancellationSignal, Executor, Consumer)} which
     * will always attempt to return a current location, but will potentially use additional power
     * in the course of the attempt as compared to this method.
     *
     * @param provider a provider listed by {@link #getAllProviders()}
     *
     * @return the last known location for the given provider, or null if not available
     *
     * @throws SecurityException if no suitable permission is present
     * @throws IllegalArgumentException if provider is null or doesn't exist
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    @Nullable
    public Location getLastKnownLocation(@NonNull String provider) {
        return getLastKnownLocation(provider, new LastLocationRequest.Builder().build());
    }

    /**
     * Gets the last known location from the given provider, or null if there is no last known
     * location.
     *
     * <p>See {@link LastLocationRequest} documentation for an explanation of various request
     * parameters and how they can affect the returned location.
     *
     * <p>See {@link #getLastKnownLocation(String)} for more detail on how this method works.
     *
     * @param provider            a provider listed by {@link #getAllProviders()}
     * @param lastLocationRequest the last location request containing location parameters
     *
     * @return the last known location for the given provider, or null if not available
     *
     * @throws SecurityException if no suitable permission is present
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if lastLocationRequest is null
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    @Nullable
    public Location getLastKnownLocation(@NonNull String provider,
            @NonNull LastLocationRequest lastLocationRequest) {
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(lastLocationRequest != null,
                "invalid null last location request");

        try {
            return mService.getLastLocation(provider, lastLocationRequest,
                    mContext.getPackageName(), mContext.getAttributionTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Asynchronously returns a single current location fix from the given provider.
     *
     * <p>See
     * {@link #getCurrentLocation(String, LocationRequest, CancellationSignal, Executor, Consumer)}
     * for more information.
     *
     * @param provider           a provider listed by {@link #getAllProviders()}
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor           the callback will take place on this {@link Executor}
     * @param consumer           the callback invoked with either a {@link Location} or null
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if consumer is null
     * @throws SecurityException        if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void getCurrentLocation(@NonNull String provider,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Location> consumer) {
        getCurrentLocation(
                provider,
                new LocationRequest.Builder(0).build(),
                cancellationSignal, executor, consumer);
    }

    /**
     * Asynchronously returns a single current location fix from the given provider based on the
     * given {@link LocationRequest}.
     *
     * <p>See
     * {@link #getCurrentLocation(String, LocationRequest, CancellationSignal, Executor, Consumer)}
     * for more information.
     *
     * @param locationRequest    the location request containing location parameters
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor           the callback will take place on this {@link Executor}
     * @param consumer           the callback invoked with either a {@link Location} or null
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if consumer is null
     * @throws SecurityException        if no suitable permission is present
     * @hide
     * @deprecated Use
     * {@link #getCurrentLocation(String, LocationRequest, CancellationSignal, Executor, Consumer)}
     * instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void getCurrentLocation(@NonNull LocationRequest locationRequest,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Location> consumer) {
        Preconditions.checkArgument(locationRequest.getProvider() != null);
        getCurrentLocation(locationRequest.getProvider(), locationRequest, cancellationSignal,
                executor, consumer);
    }

    /**
     * Asynchronously returns a single current location fix from the given provider based on the
     * given {@link LocationRequest}. This may activate sensors in order to compute a new location,
     * unlike {@link #getLastKnownLocation(String)}, which will only return a cached fix if
     * available. The given callback will be invoked once and only once, either with a valid
     * location or with a null location if the provider was unable to generate a valid location.
     *
     * <p>A client may supply an optional {@link CancellationSignal}. If this is used to cancel the
     * operation, no callback should be expected after the cancellation.
     *
     * <p>This method may return locations from the very recent past (on the order of several
     * seconds), but will never return older locations (for example, several minutes old or older).
     * Clients may rely upon the guarantee that if this method returns a location, it will represent
     * the best estimation of the location of the device in the present moment.
     *
     * <p>Clients calling this method from the background may notice that the method fails to
     * determine a valid location fix more often than while in the foreground. Background
     * applications may be throttled in their location accesses to some degree.
     *
     * The given location request may be used to provide hints on how a fresh location is computed
     * if necessary. In particular {@link LocationRequest#getDurationMillis()} can be used to
     * provide maximum duration allowed before failing. The system will always cap the maximum
     * amount of time a request for current location may run to some reasonable value (less than a
     * minute for example) before the request is failed.
     *
     * @param provider           a provider listed by {@link #getAllProviders()}
     * @param locationRequest    the location request containing location parameters
     * @param cancellationSignal an optional signal that allows for cancelling this call
     * @param executor           the callback will take place on this {@link Executor}
     * @param consumer           the callback invoked with either a {@link Location} or null
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if consumer is null
     * @throws SecurityException        if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void getCurrentLocation(@NonNull String provider,
            @NonNull LocationRequest locationRequest,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Location> consumer) {
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(locationRequest != null, "invalid null location request");

        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();
        }

        GetCurrentLocationTransport transport = new GetCurrentLocationTransport(executor, consumer,
                cancellationSignal);

        ICancellationSignal cancelRemote;
        try {
            cancelRemote = mService.getCurrentLocation(provider,
                    locationRequest, transport, mContext.getPackageName(),
                    mContext.getAttributionTag(), AppOpsManager.toReceiverId(consumer));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        if (cancellationSignal != null) {
            cancellationSignal.setRemote(cancelRemote);
        }
    }

    /**
     * Register for a single location update using the named provider and a callback.
     *
     * <p>See {@link #requestLocationUpdates(String, long, float, LocationListener, Looper)} for
     * more detail on how to use this method.
     *
     * @param provider a provider listed by {@link #getAllProviders()}
     * @param listener the listener to receive location updates
     * @param looper   the looper handling listener callbacks, or null to use the looper of the
     *                 calling thread
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException        if no suitable permission is present
     * @deprecated Use {@link #getCurrentLocation(String, CancellationSignal, Executor, Consumer)}
     * instead as it does not carry a risk of extreme battery drain.
     */
    @Deprecated
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestSingleUpdate(
            @NonNull String provider, @NonNull LocationListener listener, @Nullable Looper looper) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        Handler handler = looper == null ? new Handler() : new Handler(looper);
        requestLocationUpdates(
                provider,
                new LocationRequest.Builder(0)
                        .setMaxUpdates(1)
                        .setDurationMillis(MAX_SINGLE_LOCATION_TIMEOUT_MS)
                        .build(),
                new HandlerExecutor(handler),
                listener);
    }

    /**
     * Register for a single location update using a Criteria and a callback.
     *
     * <p>Note: Since Android KitKat, Criteria requests will always result in using the
     * {@link #FUSED_PROVIDER}.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)} for more detail
     * on how to use this method.
     *
     * @param criteria contains parameters to choose the appropriate provider for location updates
     * @param listener the listener to receive location updates
     * @param looper   the looper handling listener callbacks, or null to use the looper of the
     *                 calling thread
     *
     * @throws IllegalArgumentException if criteria is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException        if no suitable permission is present
     * @deprecated Use {@link #getCurrentLocation(String, CancellationSignal, Executor, Consumer)}
     * instead as it does not carry a risk of extreme battery drain.
     */
    @Deprecated
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestSingleUpdate(
            @NonNull Criteria criteria,
            @NonNull LocationListener listener,
            @Nullable Looper looper) {
        Preconditions.checkArgument(criteria != null, "invalid null criteria");

        Handler handler = looper == null ? new Handler() : new Handler(looper);
        requestLocationUpdates(
                FUSED_PROVIDER,
                new LocationRequest.Builder(0)
                        .setQuality(criteria)
                        .setMaxUpdates(1)
                        .setDurationMillis(MAX_SINGLE_LOCATION_TIMEOUT_MS)
                        .build(),
                new HandlerExecutor(handler),
                listener);
    }

    /**
     * Register for a single location update using a named provider and pending intent.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)} for more detail
     * on how to use this method.
     *
     * @param provider      a provider listed by {@link #getAllProviders()}
     * @param pendingIntent the pending intent to send location updates
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException        if no suitable permission is present
     * @deprecated Use {@link #getCurrentLocation(String, CancellationSignal, Executor, Consumer)}
     * instead as it does not carry a risk of extreme battery drain.
     */
    @Deprecated
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestSingleUpdate(@NonNull String provider,
            @NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        requestLocationUpdates(
                provider,
                new LocationRequest.Builder(0)
                        .setMaxUpdates(1)
                        .setDurationMillis(MAX_SINGLE_LOCATION_TIMEOUT_MS)
                        .build(),
                pendingIntent);
    }

    /**
     * Register for a single location update using a Criteria and pending intent.
     *
     * <p>Note: Since Android KitKat, Criteria requests will always result in using the
     * {@link #FUSED_PROVIDER}.
     *
     * <p>See {@link #requestLocationUpdates(long, float, Criteria, PendingIntent)} for more detail
     * on how to use this method.
     *
     * @param criteria      contains parameters to choose the appropriate provider for location
     *                      updates
     * @param pendingIntent the pending intent to send location updates
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException        if no suitable permission is present
     * @deprecated Use {@link #getCurrentLocation(String, CancellationSignal, Executor, Consumer)}
     * instead as it does not carry a risk of extreme battery drain.
     */
    @Deprecated
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestSingleUpdate(@NonNull Criteria criteria,
            @NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(criteria != null, "invalid null criteria");

        requestLocationUpdates(
                FUSED_PROVIDER,
                new LocationRequest.Builder(0)
                        .setQuality(criteria)
                        .setMaxUpdates(1)
                        .setDurationMillis(MAX_SINGLE_LOCATION_TIMEOUT_MS)
                        .build(),
                pendingIntent);
    }

    /**
     * Register for location updates from the given provider with the given arguments, and a
     * callback on the {@link Looper} of the calling thread.
     *
     * <p>See {@link #requestLocationUpdates(String, LocationRequest, Executor, LocationListener)}
     * for more detail on how this method works.
     *
     * <p class="note"> Prior to Jellybean, the minTime parameter was only a hint, and some location
     * provider implementations ignored it. For Jellybean and onwards however, it is mandatory for
     * Android compatible devices to observe both the minTime and minDistance parameters.
     *
     * @param provider     a provider listed by {@link #getAllProviders()}
     * @param minTimeMs    minimum time interval between location updates in milliseconds
     * @param minDistanceM minimum distance between location updates in meters
     * @param listener     the listener to receive location updates
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if listener is null
     * @throws RuntimeException if the calling thread has no Looper
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(@NonNull String provider, long minTimeMs, float minDistanceM,
            @NonNull LocationListener listener) {
        requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, null);
    }

    /**
     * Register for location updates from the given provider with the given arguments, and a
     * callback on the specified {@link Looper}.
     *
     * <p>See {@link #requestLocationUpdates(String, LocationRequest, Executor, LocationListener)}
     * for more detail on how this method works.
     *
     * <p class="note">Prior to Jellybean, the minTime parameter was only a hint, and some location
     * provider implementations ignored it. For Jellybean and onwards however, it is mandatory for
     * Android compatible devices to observe both the minTime and minDistance parameters.
     *
     * @param provider     a provider listed by {@link #getAllProviders()}
     * @param minTimeMs    minimum time interval between location updates in milliseconds
     * @param minDistanceM minimum distance between location updates in meters
     * @param listener     the listener to receive location updates
     * @param looper       the looper handling listener callbacks, or null to use the looper of the
     *                     calling thread
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(@NonNull String provider, long minTimeMs, float minDistanceM,
            @NonNull LocationListener listener, @Nullable Looper looper) {
        Handler handler = looper == null ? new Handler() : new Handler(looper);
        requestLocationUpdates(provider, minTimeMs, minDistanceM, new HandlerExecutor(handler),
                listener);
    }

    /**
     * Register for location updates using the named provider, and a callback on
     * the specified {@link Executor}.
     *
     * <p>See {@link #requestLocationUpdates(String, LocationRequest, Executor, LocationListener)}
     * for more detail on how this method works.
     *
     * <p class="note">Prior to Jellybean, the minTime parameter was only a hint, and some location
     * provider implementations ignored it. For Jellybean and onwards however, it is mandatory for
     * Android compatible devices to observe both the minTime and minDistance parameters.
     *
     * @param provider     a provider listed by {@link #getAllProviders()}
     * @param minTimeMs    minimum time interval between location updates in milliseconds
     * @param minDistanceM minimum distance between location updates in meters
     * @param executor     the executor handling listener callbacks
     * @param listener     the listener to receive location updates
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(
            @NonNull String provider,
            long minTimeMs,
            float minDistanceM,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull LocationListener listener) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        requestLocationUpdates(
                provider,
                createFromDeprecatedProvider(provider, minTimeMs, minDistanceM, false),
                executor,
                listener);
    }

    /**
     * Register for location updates using a provider selected through the given Criteria, and a
     * callback on the specified {@link Looper}.
     *
     * <p>Note: Since Android KitKat, Criteria requests will always result in using the
     * {@link #FUSED_PROVIDER}.
     *
     * <p>See {@link #requestLocationUpdates(String, LocationRequest, Executor, LocationListener)}
     * for more detail on how this method works.
     *
     * @param minTimeMs minimum time interval between location updates in milliseconds
     * @param minDistanceM minimum distance between location updates in meters
     * @param criteria contains parameters to choose the appropriate provider for location updates
     * @param listener the listener to receive location updates
     *
     * @throws IllegalArgumentException if criteria is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     *
     * @deprecated Use
     * {@link #requestLocationUpdates(String, long, float, LocationListener, Looper)} instead to
     * explicitly select a provider.
     */
    @Deprecated
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(long minTimeMs, float minDistanceM,
            @NonNull Criteria criteria, @NonNull LocationListener listener,
            @Nullable Looper looper) {
        Handler handler = looper == null ? new Handler() : new Handler(looper);
        requestLocationUpdates(minTimeMs, minDistanceM, criteria, new HandlerExecutor(handler),
                listener);
    }

    /**
     * Register for location updates using a provider selected through the given Criteria, and a
     * callback on the specified {@link Executor}.
     *
     * <p>Note: Since Android KitKat, Criteria requests will always result in using the
     * {@link #FUSED_PROVIDER}.
     *
     * <p>See {@link #requestLocationUpdates(String, LocationRequest, Executor, LocationListener)}
     * for more detail on how this method works.
     *
     * @param minTimeMs minimum time interval between location updates in milliseconds
     * @param minDistanceM minimum distance between location updates in meters
     * @param criteria contains parameters to choose the appropriate provider for location updates
     * @param executor the executor handling listener callbacks
     * @param listener the listener to receive location updates
     *
     * @throws IllegalArgumentException if criteria is null
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException        if no suitable permission is present
     *
     * @deprecated Use
     * {@link #requestLocationUpdates(String, long, float, Executor, LocationListener)} instead to
     * explicitly select a provider.
     */
    @Deprecated
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(
            long minTimeMs,
            float minDistanceM,
            @NonNull Criteria criteria,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull LocationListener listener) {
        Preconditions.checkArgument(criteria != null, "invalid null criteria");

        requestLocationUpdates(
                FUSED_PROVIDER,
                createFromDeprecatedCriteria(criteria, minTimeMs, minDistanceM, false),
                executor,
                listener);
    }

    /**
     * Register for location updates using the named provider, and callbacks delivered via the
     * provided {@link PendingIntent}.
     *
     * <p>See {@link #requestLocationUpdates(String, LocationRequest, PendingIntent)} for more
     * detail on how this method works.
     *
     * @param provider      a provider listed by {@link #getAllProviders()}
     * @param minTimeMs     minimum time interval between location updates in milliseconds
     * @param minDistanceM  minimum distance between location updates in meters
     * @param pendingIntent the pending intent to send location updates
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if pendingIntent is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(@NonNull String provider, long minTimeMs, float minDistanceM,
            @NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        requestLocationUpdates(
                provider,
                createFromDeprecatedProvider(provider, minTimeMs, minDistanceM, false),
                pendingIntent);
    }

    /**
     * Register for location updates using a provider selected through the given Criteria, and
     * callbacks delivered via the provided {@link PendingIntent}.
     *
     * <p>Note: Since Android KitKat, Criteria requests will always result in using the
     * {@link #FUSED_PROVIDER}.
     *
     * <p>See {@link #requestLocationUpdates(String, long, float, PendingIntent)} for more detail on
     * how this method works.
     *
     * @param minTimeMs minimum time interval between location updates in milliseconds
     * @param minDistanceM minimum distance between location updates in meters
     * @param criteria contains parameters to choose the appropriate provider for location updates
     * @param pendingIntent the pending intent to send location updates
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if pendingIntent is null
     * @throws SecurityException if no suitable permission is present
     *
     * @deprecated Use {@link #requestLocationUpdates(String, long, float, PendingIntent)} instead
     * to explicitly select a provider.
     */
    @Deprecated
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(long minTimeMs, float minDistanceM,
            @NonNull Criteria criteria, @NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(criteria != null, "invalid null criteria");
        requestLocationUpdates(
                FUSED_PROVIDER,
                createFromDeprecatedCriteria(criteria, minTimeMs, minDistanceM, false),
                pendingIntent);
    }

    /**
     * Register for location updates using a {@link LocationRequest}, and a callback on the
     * specified {@link Looper}.
     *
     * <p>The system will automatically select and enable the best provider based on the given
     * {@link LocationRequest}. The LocationRequest can be null, in which case the system will
     * choose default low power parameters for location updates, but this is heavily discouraged,
     * and an explicit LocationRequest should always be provided.
     *
     * <p>See {@link #requestLocationUpdates(String, LocationRequest, Executor, LocationListener)}
     * for more detail on how this method works.
     *
     * @param locationRequest the location request containing location parameters
     * @param listener the listener to receive location updates
     * @param looper the looper handling listener callbacks, or null to use the looper of the
     *               calling thread
     *
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     *
     * @hide
     * @deprecated Use
     * {@link #requestLocationUpdates(String, LocationRequest, Executor, LocationListener)} instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(
            @Nullable LocationRequest locationRequest,
            @NonNull LocationListener listener,
            @Nullable Looper looper) {
        Handler handler = looper == null ? new Handler() : new Handler(looper);
        requestLocationUpdates(locationRequest, new HandlerExecutor(handler), listener);
    }

    /**
     * Register for location updates using a {@link LocationRequest}, and a callback on the
     * specified {@link Executor}.
     *
     * <p>See {@link #requestLocationUpdates(String, LocationRequest, Executor, LocationListener)}
     * for more detail on how this method works.
     *
     * @param locationRequest the location request containing location parameters
     * @param executor the executor handling listener callbacks
     * @param listener the listener to receive location updates
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     *
     * @hide
     * @deprecated Use
     * {@link #requestLocationUpdates(String, LocationRequest, Executor, LocationListener)} instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(
            @Nullable LocationRequest locationRequest,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull LocationListener listener) {
        if (locationRequest == null) {
            locationRequest = LocationRequest.create();
        }
        Preconditions.checkArgument(locationRequest.getProvider() != null);
        requestLocationUpdates(locationRequest.getProvider(), locationRequest, executor, listener);
    }

    /**
     * Register for location updates using a {@link LocationRequest}, and callbacks delivered via
     * the provided {@link PendingIntent}.
     *
     * <p>See {@link #requestLocationUpdates(String, LocationRequest, PendingIntent)} for more
     * detail on how this method works.
     *
     * @param locationRequest the location request containing location parameters
     * @param pendingIntent the pending intent to send location updates
     *
     * @throws IllegalArgumentException if pendingIntent is null
     * @throws SecurityException if no suitable permission is present
     *
     * @hide
     * @deprecated Use {@link #requestLocationUpdates(String, LocationRequest, PendingIntent)}
     * instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(
            @Nullable LocationRequest locationRequest,
            @NonNull PendingIntent pendingIntent) {
        if (locationRequest == null) {
            locationRequest = LocationRequest.create();
        }
        Preconditions.checkArgument(locationRequest.getProvider() != null);
        requestLocationUpdates(locationRequest.getProvider(), locationRequest, pendingIntent);
    }

    /**
     * Register for location updates from the specified provider, using a {@link LocationRequest},
     * and a callback on the specified {@link Executor}.
     *
     * <p>Only one request can be registered for each unique listener/provider pair, so any
     * subsequent requests with the same provider and listener will overwrite all associated
     * arguments. The same listener may be used across multiple providers with different requests
     * for each provider.
     *
     * <p>It may take some time to receive the first location update depending on the conditions the
     * device finds itself in. In order to take advantage of cached locations, application may
     * consider using {@link #getLastKnownLocation(String)} or {@link #getCurrentLocation(String,
     * LocationRequest, CancellationSignal, Executor, Consumer)} instead.
     *
     * <p>See {@link LocationRequest} documentation for an explanation of various request parameters
     * and how they can affect the received locations.
     *
     * <p>If your application wants to passively observe location updates from all providers, then
     * use the {@link #PASSIVE_PROVIDER}. This provider does not turn on or modify active location
     * providers, so you do not need to be as careful about minimum time and minimum distance
     * parameters. However, if your application performs heavy work on a location update (such as
     * network activity) then you should set an explicit fastest interval on your location request
     * in case another application enables a location provider with extremely fast updates.
     *
     * <p>In case the provider you have selected is disabled, location updates will cease, and a
     * provider availability update will be sent. As soon as the provider is enabled again, another
     * provider availability update will be sent and location updates will resume.
     *
     * <p>Locations returned from {@link #GPS_PROVIDER} are with respect to the primary GNSS antenna
     * position within the device. {@link #getGnssAntennaInfos()} may be used to determine the GNSS
     * antenna position with respect to the Android Coordinate System, and convert between them if
     * necessary. This is generally only necessary for high accuracy applications.
     *
     * <p>When location callbacks are invoked, the system will hold a wakelock on your
     * application's behalf for some period of time, but not indefinitely. If your application
     * requires a long running wakelock within the location callback, you should acquire it
     * yourself.
     *
     * <p>Spamming location requests is a drain on system resources, and the system has preventative
     * measures in place to ensure that this behavior will never result in more locations than could
     * be achieved with a single location request with an equivalent interval that is left in place
     * the whole time. As part of this amelioration, applications that target Android S and above
     * may receive cached or historical locations through their listener. These locations will never
     * be older than the interval of the location request.
     *
     * <p>To unregister for location updates, use {@link #removeUpdates(LocationListener)}.
     *
     * @param provider a provider listed by {@link #getAllProviders()}
     * @param locationRequest the location request containing location parameters
     * @param executor the executor handling listener callbacks
     * @param listener the listener to receive location updates
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if locationRequest is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(@NonNull String provider,
            @NonNull LocationRequest locationRequest,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull LocationListener listener) {
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(locationRequest != null, "invalid null location request");

        try {
            synchronized (sLocationListeners) {
                WeakReference<LocationListenerTransport> reference = sLocationListeners.get(
                        listener);
                LocationListenerTransport transport = reference != null ? reference.get() : null;
                if (transport == null) {
                    transport = new LocationListenerTransport(listener, executor);
                } else {
                    Preconditions.checkState(transport.isRegistered());
                    transport.setExecutor(executor);
                }

                mService.registerLocationListener(provider, locationRequest, transport,
                        mContext.getPackageName(), mContext.getAttributionTag(),
                        AppOpsManager.toReceiverId(listener));

                sLocationListeners.put(listener, new WeakReference<>(transport));
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Register for location updates from the specified provider, using a {@link LocationRequest},
     * and callbacks delivered via the provided {@link PendingIntent}.
     *
     * <p>The delivered pending intents will contain extras with the callback information. The keys
     * used for the extras are {@link #KEY_LOCATION_CHANGED} and {@link #KEY_PROVIDER_ENABLED}. See
     * the documentation for each respective extra key for information on the values.
     *
     * <p>To unregister for location updates, use {@link #removeUpdates(PendingIntent)}.
     *
     * @param provider a provider listed by {@link #getAllProviders()}
     * @param locationRequest the location request containing location parameters
     * @param pendingIntent the pending intent to send location updates
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if locationRequest is null
     * @throws IllegalArgumentException if pendingIntent is null
     * @throws SecurityException if no suitable permission is present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(@NonNull String provider,
            @NonNull LocationRequest locationRequest,
            @NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(locationRequest != null, "invalid null location request");
        Preconditions.checkArgument(pendingIntent != null, "invalid null pending intent");

        if (Compatibility.isChangeEnabled(BLOCK_UNTARGETED_PENDING_INTENTS)) {
            Preconditions.checkArgument(pendingIntent.isTargetedToPackage(),
                    "pending intent must be targeted to a package");
        }

        if (Compatibility.isChangeEnabled(BLOCK_IMMUTABLE_PENDING_INTENTS)) {
            Preconditions.checkArgument(!pendingIntent.isImmutable(),
                    "pending intent must be mutable");
        }

        try {
            mService.registerLocationPendingIntent(provider, locationRequest, pendingIntent,
                    mContext.getPackageName(), mContext.getAttributionTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the last known location with a new location.
     *
     * <p>A privileged client can inject a {@link Location} if it has a better estimate of what
     * the recent location is.  This is especially useful when the device boots up and the GPS
     * chipset is in the process of getting the first fix.  If the client has cached the location,
     * it can inject the {@link Location}, so if an app requests for a {@link Location} from {@link
     * #getLastKnownLocation(String)}, the location information is still useful before getting
     * the first fix.
     *
     * @param location newly available {@link Location} object
     * @return true if the location was injected, false otherwise
     *
     * @throws IllegalArgumentException if location is null
     * @throws SecurityException if permissions are not present
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @RequiresPermission(allOf = {LOCATION_HARDWARE, ACCESS_FINE_LOCATION})
    public boolean injectLocation(@NonNull Location location) {
        Preconditions.checkArgument(location != null, "invalid null location");
        Preconditions.checkArgument(location.isComplete(),
                "incomplete location object, missing timestamp or accuracy?");

        try {
            mService.injectLocation(location);
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Requests that the given provider flush any batched locations to listeners. The given listener
     * (registered with the provider) will have {@link LocationListener#onFlushComplete(int)}
     * invoked with the given result code after any locations that were flushed have been delivered.
     * If {@link #removeUpdates(LocationListener)} is invoked before the flush callback is executed,
     * then the flush callback will never be executed.
     *
     * @param provider    a provider listed by {@link #getAllProviders()}
     * @param listener    a listener registered under the provider
     * @param requestCode an arbitrary integer passed through to
     *                    {@link LocationListener#onFlushComplete(int)}
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if listener is null or is not registered under the provider
     */
    @SuppressLint("SamShouldBeLast")
    public void requestFlush(@NonNull String provider, @NonNull LocationListener listener,
            @SuppressLint("ListenerLast") int requestCode) {
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(listener != null, "invalid null listener");

        synchronized (sLocationListeners) {
            WeakReference<LocationListenerTransport> ref = sLocationListeners.get(listener);
            LocationListenerTransport transport = ref != null ? ref.get() : null;

            Preconditions.checkArgument(transport != null,
                    "unregistered listener cannot be flushed");

            try {
                mService.requestListenerFlush(provider, transport, requestCode);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Requests that the given provider flush any batched locations to listeners. The given
     * PendingIntent (registered with the provider) will be sent with {@link #KEY_FLUSH_COMPLETE}
     * present in the extra keys, and {@code requestCode} as the corresponding value.
     *
     * @param provider      a provider listed by {@link #getAllProviders()}
     * @param pendingIntent a pendingIntent registered under the provider
     * @param requestCode   an arbitrary integer that will be passed back as the extra value for
     *                      {@link #KEY_FLUSH_COMPLETE}
     *
     * @throws IllegalArgumentException if provider is null or doesn't exist
     * @throws IllegalArgumentException if pending intent is null or is not registered under the
     *                                  provider
     */
    public void requestFlush(@NonNull String provider, @NonNull PendingIntent pendingIntent,
            int requestCode) {
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(pendingIntent != null, "invalid null pending intent");

        try {
            mService.requestPendingIntentFlush(provider, pendingIntent, requestCode);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes all location updates for the specified {@link LocationListener}. The given listener
     * is guaranteed not to receive any invocations that <b>happens-after</b> this method is
     * invoked.
     *
     * <p>If the given listener has any batched requests, this method will not flush any incomplete
     * location batches before stopping location updates. If you wish to flush any pending locations
     * before stopping, you must first call {@link #requestFlush(String, LocationListener, int)} and
     * then call this method once the flush is complete. If this method is invoked before the flush
     * is complete, you may not receive the flushed locations.
     *
     * @param listener listener that no longer needs location updates
     *
     * @throws IllegalArgumentException if listener is null
     */
    public void removeUpdates(@NonNull LocationListener listener) {
        Preconditions.checkArgument(listener != null, "invalid null listener");

        try {
            synchronized (sLocationListeners) {
                WeakReference<LocationListenerTransport> ref = sLocationListeners.remove(listener);
                LocationListenerTransport transport = ref != null ? ref.get() : null;
                if (transport != null) {
                    transport.unregister();
                    mService.unregisterLocationListener(transport);
                }
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes location updates for the specified {@link PendingIntent}. Following this call, the
     * PendingIntent will no longer receive location updates.
     *
     * <p>See {@link #removeUpdates(LocationListener)} for more detail on how this method works.
     *
     * @param pendingIntent pending intent that no longer needs location updates
     *
     * @throws IllegalArgumentException if pendingIntent is null
     */
    public void removeUpdates(@NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(pendingIntent != null, "invalid null pending intent");

        try {
            mService.unregisterLocationPendingIntent(pendingIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the given location provider exists on this device, irrespective of whether
     * it is currently enabled or not.
     *
     * @param provider a potential location provider
     * @return true if the location provider exists, false otherwise
     *
     * @throws IllegalArgumentException if provider is null
     */
    public boolean hasProvider(@NonNull String provider) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        try {
            return mService.hasProvider(provider);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of the names of all available location providers. All providers are returned,
     * including those that are currently disabled.
     *
     * @return list of provider names
     */
    public @NonNull List<String> getAllProviders() {
        try {
            return mService.getAllProviders();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of the names of available location providers. If {@code enabledOnly} is false,
     * this is functionally the same as {@link #getAllProviders()}.
     *
     * @param enabledOnly if true then only enabled providers are included
     * @return list of provider names
     */
    public @NonNull List<String> getProviders(boolean enabledOnly) {
        try {
            return mService.getProviders(null, enabledOnly);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of the names of available location providers that satisfy the given criteria.
     *
     * @param criteria the criteria that providers must match
     * @param enabledOnly if true then only enabled providers are included
     * @return list of provider names
     *
     * @throws IllegalArgumentException if criteria is null
     */
    public @NonNull List<String> getProviders(@NonNull Criteria criteria, boolean enabledOnly) {
        Preconditions.checkArgument(criteria != null, "invalid null criteria");

        try {
            return mService.getProviders(criteria, enabledOnly);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the name of the provider that best meets the given criteria. Only providers that are
     * permitted to be accessed by the caller will be returned. If several providers meet the
     * criteria, the one with the best accuracy is returned. If no provider meets the criteria, the
     * criteria are loosened in the following order:
     *
     * <ul>
     * <li> power requirement
     * <li> accuracy
     * <li> bearing
     * <li> speed
     * <li> altitude
     * </ul>
     *
     * <p> Note that the requirement on monetary cost is not removed in this process.
     *
     * @param criteria the criteria that need to be matched
     * @param enabledOnly if true then only enabled providers are included
     * @return name of the provider that best matches the criteria, or null if none match
     *
     * @throws IllegalArgumentException if criteria is null
     */
    public @Nullable String getBestProvider(@NonNull Criteria criteria, boolean enabledOnly) {
        Preconditions.checkArgument(criteria != null, "invalid null criteria");

        try {
            return mService.getBestProvider(criteria, enabledOnly);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the information about the location provider with the given name, or null if no
     * provider exists by that name.
     *
     * @param provider a provider listed by {@link #getAllProviders()}
     * @return location provider information, or null if provider does not exist
     *
     * @throws IllegalArgumentException if provider is null
     *
     * @deprecated This method has no way to indicate that a provider's properties are unknown, and
     * so may return incorrect results on rare occasions. Use {@link #getProviderProperties(String)}
     * instead.
     */
    @Deprecated
    public @Nullable LocationProvider getProvider(@NonNull String provider) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        if (!Compatibility.isChangeEnabled(GET_PROVIDER_SECURITY_EXCEPTIONS)) {
            if (NETWORK_PROVIDER.equals(provider) || FUSED_PROVIDER.equals(provider)) {
                try {
                    mContext.enforcePermission(ACCESS_FINE_LOCATION, Process.myPid(),
                            Process.myUid(), null);
                } catch (SecurityException e) {
                    mContext.enforcePermission(ACCESS_COARSE_LOCATION, Process.myPid(),
                            Process.myUid(), null);
                }
            } else {
                mContext.enforcePermission(ACCESS_FINE_LOCATION, Process.myPid(), Process.myUid(),
                        null);
            }
        }

        try {
            ProviderProperties properties = mService.getProviderProperties(provider);
            return new LocationProvider(provider, properties);
        } catch (IllegalArgumentException e) {
            // provider does not exist
            return null;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the properties of the given provider, or null if the properties are currently
     * unknown. Provider properties may change over time, although this is discouraged, and should
     * be rare. The most common transition is when provider properties go from being unknown to
     * known, which is most common near boot time.
     *
     * @param provider a provider listed by {@link #getAllProviders()}
     * @return location provider properties, or null if properties are currently unknown
     *
     * @throws IllegalArgumentException if provider is null or does not exist
     */
    public @Nullable ProviderProperties getProviderProperties(@NonNull String provider) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        try {
            return mService.getProviderProperties(provider);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the given package name matches a location provider package, and false
     * otherwise.
     *
     * @hide
     * @deprecated Prefer {@link #isProviderPackage(String, String, String)} instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public boolean isProviderPackage(@NonNull String packageName) {
        return isProviderPackage(null, packageName, null);
    }

    /**
     * Returns true if the given provider corresponds to the given package name. If the given
     * provider is null, this will return true if any provider corresponds to the given package
     * name.
     *
     * @param provider a provider listed by {@link #getAllProviders()} or null
     * @param packageName the package name to test if it is a provider
     * @return true if the given arguments correspond to a provider
     *
     * @deprecated Use {@link #isProviderPackage(String, String, String)} instead.
     *
     * @hide
     * @removed
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public boolean isProviderPackage(@Nullable String provider, @NonNull String packageName) {
        return isProviderPackage(provider, packageName, null);
    }

    /**
     * Returns true if the given provider corresponds to the given package name. If the given
     * provider is null, this will return true if any provider corresponds to the given package
     * name and/or attribution tag. If attribution tag is non-null, the provider identity must match
     * both the given package name and attribution tag.
     *
     * @param provider a provider listed by {@link #getAllProviders()} or null
     * @param packageName the package name to test if it is a provider
     * @param attributionTag an optional attribution tag within the given package
     * @return true if the given arguments correspond to a provider
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public boolean isProviderPackage(@Nullable String provider, @NonNull String packageName,
            @Nullable String attributionTag) {
        try {
            return mService.isProviderPackage(provider, Objects.requireNonNull(packageName),
                    attributionTag);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of packages associated with the given provider,
     * and an empty list if no package is associated with the provider.
     *
     * @hide
     * @deprecated Prefer {@link #isProviderPackage(String, String, String)} instead.
     */
    @TestApi
    @Deprecated
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    @Nullable
    @SuppressWarnings("NullableCollection")
    public List<String> getProviderPackages(@NonNull String provider) {
        try {
            return mService.getProviderPackages(provider);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sends additional commands to a location provider. Can be used to support provider specific
     * extensions to the Location Manager API.
     *
     * @param provider a provider listed by {@link #getAllProviders()}
     * @param command  name of the command to send to the provider
     * @param extras   optional arguments for the command, or null
     * @return true always, the return value may be ignored
     */
    public boolean sendExtraCommand(
            @NonNull String provider, @NonNull String command, @Nullable Bundle extras) {
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(command != null, "invalid null command");

        try {
            mService.sendExtraCommand(provider, command, extras);
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Creates a test location provider and adds it to the set of active providers. This provider
     * will replace any provider with the same name that exists prior to this call.
     *
     * @param provider the provider name
     *
     * @throws IllegalArgumentException if provider is null
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     */
    public void addTestProvider(
            @NonNull String provider, boolean requiresNetwork, boolean requiresSatellite,
            boolean requiresCell, boolean hasMonetaryCost, boolean supportsAltitude,
            boolean supportsSpeed, boolean supportsBearing,
            @ProviderProperties.PowerUsage int powerUsage,
            @ProviderProperties.Accuracy int accuracy) {
        addTestProvider(provider, new ProviderProperties.Builder()
                .setHasNetworkRequirement(requiresNetwork)
                .setHasSatelliteRequirement(requiresSatellite)
                .setHasCellRequirement(requiresCell)
                .setHasMonetaryCost(hasMonetaryCost)
                .setHasAltitudeSupport(supportsAltitude)
                .setHasSpeedSupport(supportsSpeed)
                .setHasBearingSupport(supportsBearing)
                .setPowerUsage(powerUsage)
                .setAccuracy(accuracy)
                .build());
    }

    /**
     * Creates a test location provider and adds it to the set of active providers. This provider
     * will replace any provider with the same name that exists prior to this call.
     *
     * @param provider the provider name
     *
     * @throws IllegalArgumentException if provider is null
     * @throws IllegalArgumentException if properties is null
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     */
    public void addTestProvider(@NonNull String provider, @NonNull ProviderProperties properties) {
        addTestProvider(provider, properties, Collections.emptySet());
    }

    /**
     * Creates a test location provider and adds it to the set of active providers. This provider
     * will replace any provider with the same name that exists prior to this call.
     *
     * @param provider the provider name
     * @param properties the provider properties
     * @param extraAttributionTags additional attribution tags associated with this provider
     *
     * @throws IllegalArgumentException if provider is null
     * @throws IllegalArgumentException if properties is null
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     */
    public void addTestProvider(@NonNull String provider, @NonNull ProviderProperties properties,
            @NonNull Set<String> extraAttributionTags) {
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(properties != null, "invalid null properties");
        Preconditions.checkArgument(extraAttributionTags != null,
                "invalid null extra attribution tags");

        try {
            mService.addTestProvider(provider, properties, new ArrayList<>(extraAttributionTags),
                    mContext.getOpPackageName(), mContext.getFeatureId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes the test location provider with the given name or does nothing if no such test
     * location provider exists.
     *
     * @param provider the provider name
     *
     * @throws IllegalArgumentException if provider is null
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     */
    public void removeTestProvider(@NonNull String provider) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        try {
            mService.removeTestProvider(provider, mContext.getOpPackageName(),
                    mContext.getFeatureId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets a new location for the given test provider. This location will be identiable as a mock
     * location to all clients via {@link Location#isMock()}.
     *
     * <p>The location object must have a minimum number of fields set to be considered valid, as
     * per documentation on {@link Location} class.
     *
     * @param provider the provider name
     * @param location the mock location
     *
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     * @throws IllegalArgumentException if the provider is null or not a test provider
     * @throws IllegalArgumentException if the location is null or incomplete
     */
    public void setTestProviderLocation(@NonNull String provider, @NonNull Location location) {
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(location != null, "invalid null location");

        if (Compatibility.isChangeEnabled(BLOCK_INCOMPLETE_LOCATIONS)) {
            Preconditions.checkArgument(location.isComplete(),
                    "incomplete location object, missing timestamp or accuracy?");
        } else {
            location.makeComplete();
        }

        try {
            mService.setTestProviderLocation(provider, location, mContext.getOpPackageName(),
                    mContext.getFeatureId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Does nothing.
     *
     * @deprecated This method has always been a no-op, and may be removed in the future.
     */
    @Deprecated
    public void clearTestProviderLocation(@NonNull String provider) {}

    /**
     * Sets the given test provider to be enabled or disabled.
     *
     * @param provider the provider name
     * @param enabled the mock enabled value
     *
     * @throws SecurityException if {@link android.app.AppOpsManager#OPSTR_MOCK_LOCATION
     * mock location app op} is not set to {@link android.app.AppOpsManager#MODE_ALLOWED
     * allowed} for your app.
     * @throws IllegalArgumentException if provider is null or not a test provider
     */
    public void setTestProviderEnabled(@NonNull String provider, boolean enabled) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        try {
            mService.setTestProviderEnabled(provider, enabled, mContext.getOpPackageName(),
                    mContext.getFeatureId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Equivalent to calling {@link #setTestProviderEnabled(String, boolean)} to disable a test
     * provider.
     *
     * @deprecated Use {@link #setTestProviderEnabled(String, boolean)} instead.
     */
    @Deprecated
    public void clearTestProviderEnabled(@NonNull String provider) {
        setTestProviderEnabled(provider, false);
    }

    /**
     * This method has no effect as provider status has been deprecated and is no longer supported.
     *
     * @deprecated This method has no effect.
     */
    @Deprecated
    public void setTestProviderStatus(
            @NonNull String provider, int status, @Nullable Bundle extras, long updateTime) {}

    /**
     * This method has no effect as provider status has been deprecated and is no longer supported.
     *
     * @deprecated This method has no effect.
     */
    @Deprecated
    public void clearTestProviderStatus(@NonNull String provider) {}

    /**
     * Sets a proximity alert for the location given by the position (latitude, longitude) and the
     * given radius.
     *
     * <p>When the device detects that it has entered or exited the area surrounding the location,
     * the given PendingIntent will be fired.
     *
     * <p>The fired intent will have a boolean extra added with key {@link #KEY_PROXIMITY_ENTERING}.
     * If the value is true, the device is entering the proximity region; if false, it is exiting.
     *
     * <p>Due to the approximate nature of position estimation, if the device passes through the
     * given area briefly, it is possible that no Intent will be fired. Similarly, an intent could
     * be fired if the device passes very close to the given area but does not actually enter it.
     *
     * <p>Before API version 17, this method could be used with
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}. From API version 17 and onwards,
     * this method requires {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission.
     *
     * @param latitude      the latitude of the central point of the alert region
     * @param longitude     the longitude of the central point of the alert region
     * @param radius        the radius of the central point of the alert region in meters
     * @param expiration    expiration realtime for this proximity alert in milliseconds, or -1 to
     *                      indicate no expiration
     * @param pendingIntent a {@link PendingIntent} that will sent when entry to or exit from the
     *                      alert region is detected
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     *                           permission is not present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void addProximityAlert(double latitude, double longitude, float radius, long expiration,
            @NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(pendingIntent != null, "invalid null pending intent");

        if (Compatibility.isChangeEnabled(BLOCK_UNTARGETED_PENDING_INTENTS)) {
            Preconditions.checkArgument(pendingIntent.isTargetedToPackage(),
                    "pending intent must be targeted to a package");
        }

        if (Compatibility.isChangeEnabled(BLOCK_IMMUTABLE_PENDING_INTENTS)) {
            Preconditions.checkArgument(!pendingIntent.isImmutable(),
                    "pending intent must be mutable");
        }

        if (expiration < 0) {
            expiration = Long.MAX_VALUE;
        }

        try {
            Geofence fence = Geofence.createCircle(latitude, longitude, radius, expiration);
            mService.requestGeofence(fence, pendingIntent, mContext.getPackageName(),
                    mContext.getAttributionTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes the proximity alert with the given PendingIntent.
     *
     * <p>Before API version 17, this method could be used with
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}.
     * From API version 17 and onwards, this method requires
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission.
     *
     * @param intent the PendingIntent that no longer needs to be notified of
     * proximity alerts
     *
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission is not present
     */
    public void removeProximityAlert(@NonNull PendingIntent intent) {
        Preconditions.checkArgument(intent != null, "invalid null pending intent");

        try {
            mService.removeGeofence(intent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // ================= GNSS APIs =================

    /**
     * Returns the supported capabilities of the GNSS chipset.
     */
    public @NonNull GnssCapabilities getGnssCapabilities() {
        try {
            return mService.getGnssCapabilities();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the model year of the GNSS hardware and software build, or 0 if the model year
     * is before 2016.
     */
    public int getGnssYearOfHardware() {
        try {
            return mService.getGnssYearOfHardware();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the model name (including vendor and hardware/software version) of the GNSS hardware
     * driver, or null if this information is not available.
     *
     * <p>No device-specific serial number or ID is returned from this API.
     */
    @Nullable
    public String getGnssHardwareModelName() {
        try {
            return mService.getGnssHardwareModelName();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current list of GNSS antenna infos, or null if unknown or unsupported.
     *
     * @see #getGnssCapabilities()
     */
    @Nullable
    @SuppressLint("NullableCollection")
    public List<GnssAntennaInfo> getGnssAntennaInfos() {
        try {
            return mService.getGnssAntennaInfos();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Retrieves information about the current status of the GPS engine. This should only be called
     * from within the {@link GpsStatus.Listener#onGpsStatusChanged} callback to ensure that the
     * data is copied atomically.
     *
     * The caller may either pass in an existing {@link GpsStatus} object to be overwritten, or pass
     * null to create a new {@link GpsStatus} object.
     *
     * @param status object containing GPS status details, or null.
     * @return status object containing updated GPS status.
     *
     * @deprecated GpsStatus APIs are deprecated, use {@link GnssStatus} APIs instead. No longer
     * supported in apps targeting S and above.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public @Nullable GpsStatus getGpsStatus(@Nullable GpsStatus status) {
        if (Compatibility.isChangeEnabled(BLOCK_GPS_STATUS_USAGE)) {
            throw new UnsupportedOperationException(
                    "GpsStatus APIs not supported, please use GnssStatus APIs instead");
        }

        GnssStatus gnssStatus = GpsStatusTransport.sGnssStatus;
        int ttff = GpsStatusTransport.sTtff;
        if (gnssStatus != null) {
            if (status == null) {
                status = GpsStatus.create(gnssStatus, ttff);
            } else {
                status.setStatus(gnssStatus, ttff);
            }
        } else if (status == null) {
            // even though this method is marked as nullable, legacy behavior was to never return
            // a null result, and there are applications that rely on this behavior.
            status = GpsStatus.createEmpty();
        }
        return status;
    }

    /**
     * Adds a GPS status listener.
     *
     * @param listener GPS status listener object to register
     * @return true if the listener was successfully added
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     *
     * @deprecated use {@link #registerGnssStatusCallback(GnssStatus.Callback)} instead. No longer
     * supported in apps targeting S and above.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addGpsStatusListener(GpsStatus.Listener listener) {
        if (Compatibility.isChangeEnabled(BLOCK_GPS_STATUS_USAGE)) {
            throw new UnsupportedOperationException(
                    "GpsStatus APIs not supported, please use GnssStatus APIs instead");
        }

        GnssLazyLoader.sGnssStatusListeners.addListener(listener,
                new GpsStatusTransport(new HandlerExecutor(new Handler()), mContext, listener));
        return true;
    }

    /**
     * Removes a GPS status listener.
     *
     * @param listener GPS status listener object to remove
     *
     * @deprecated use {@link #unregisterGnssStatusCallback(GnssStatus.Callback)} instead. No longer
     * supported in apps targeting S and above.
     */
    @Deprecated
    public void removeGpsStatusListener(GpsStatus.Listener listener) {
        if (Compatibility.isChangeEnabled(BLOCK_GPS_STATUS_USAGE)) {
            throw new UnsupportedOperationException(
                    "GpsStatus APIs not supported, please use GnssStatus APIs instead");
        }

        GnssLazyLoader.sGnssStatusListeners.removeListener(listener);
    }

    /**
     * Registers a GNSS status callback. This method must be called from a {@link Looper} thread,
     * and callbacks will occur on that looper.
     *
     * <p>See {@link #registerGnssStatusCallback(Executor, GnssStatus.Callback)} for more detail on
     * how this method works.
     *
     * @param callback the callback to register
     * @return {@code true} always
     *
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     *
     * @deprecated Use {@link #registerGnssStatusCallback(GnssStatus.Callback, Handler)} or {@link
     * #registerGnssStatusCallback(Executor, GnssStatus.Callback)} instead.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssStatusCallback(@NonNull GnssStatus.Callback callback) {
        return registerGnssStatusCallback(callback, null);
    }

    /**
     * Registers a GNSS status callback.
     *
     * <p>See {@link #registerGnssStatusCallback(Executor, GnssStatus.Callback)} for more detail on
     * how this method works.
     *
     * @param callback the callback to register
     * @param handler  the handler the callback runs on
     * @return {@code true} always
     *
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssStatusCallback(
            @NonNull GnssStatus.Callback callback, @Nullable Handler handler) {
        if (handler == null) {
            handler = new Handler();
        }

        return registerGnssStatusCallback(new HandlerExecutor(handler), callback);
    }

    /**
     * Registers a GNSS status callback. GNSS status information will only be received while the
     * {@link #GPS_PROVIDER} is enabled, and while the client app is in the foreground.
     *
     * @param executor the executor that the callback runs on
     * @param callback the callback to register
     * @return {@code true} always
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssStatusCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssStatus.Callback callback) {
        GnssLazyLoader.sGnssStatusListeners.addListener(callback,
                new GnssStatusTransport(executor, mContext, callback));
        return true;
    }

    /**
     * Removes a GNSS status callback.
     *
     * @param callback GNSS status callback object to remove
     */
    public void unregisterGnssStatusCallback(@NonNull GnssStatus.Callback callback) {
        GnssLazyLoader.sGnssStatusListeners.removeListener(callback);
    }

    /**
     * No-op method to keep backward-compatibility.
     *
     * @deprecated Use {@link #addNmeaListener} instead.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addNmeaListener(@NonNull GpsStatus.NmeaListener listener) {
        return false;
    }

    /**
     * No-op method to keep backward-compatibility.
     *
     * @deprecated Use {@link #removeNmeaListener(OnNmeaMessageListener)} instead.
     */
    @Deprecated
    public void removeNmeaListener(@NonNull GpsStatus.NmeaListener listener) {}

    /**
     * Adds an NMEA listener.
     *
     * <p>See {@link #addNmeaListener(Executor, OnNmeaMessageListener)} for more detail on how this
     * method works.
     *
     * @param listener the listener to register
     * @return {@code true} always
     *
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     * @deprecated Use {@link #addNmeaListener(OnNmeaMessageListener, Handler)} or {@link
     * #addNmeaListener(Executor, OnNmeaMessageListener)} instead.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addNmeaListener(@NonNull OnNmeaMessageListener listener) {
        return addNmeaListener(listener, null);
    }

    /**
     * Adds an NMEA listener.
     *
     * <p>See {@link #addNmeaListener(Executor, OnNmeaMessageListener)} for more detail on how this
     * method works.
     *
     * @param listener the listener to register
     * @param handler  the handler that the listener runs on
     * @return {@code true} always
     *
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addNmeaListener(
            @NonNull OnNmeaMessageListener listener, @Nullable Handler handler) {
        if (handler == null) {
            handler = new Handler();
        }

        return addNmeaListener(new HandlerExecutor(handler), listener);
    }

    /**
     * Adds an NMEA listener. GNSS NMEA information will only be received while the
     * {@link #GPS_PROVIDER} is enabled, and while the client app is in the foreground.
     *
     * @param listener the listener to register
     * @param executor the executor that the listener runs on
     * @return {@code true} always
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addNmeaListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnNmeaMessageListener listener) {
        GnssLazyLoader.sGnssNmeaListeners.addListener(listener,
                new GnssNmeaTransport(executor, mContext, listener));
        return true;
    }

    /**
     * Removes an NMEA listener.
     *
     * @param listener a {@link OnNmeaMessageListener} object to remove
     */
    public void removeNmeaListener(@NonNull OnNmeaMessageListener listener) {
        GnssLazyLoader.sGnssNmeaListeners.removeListener(listener);
    }

    /**
     * No-op method to keep backward-compatibility.
     *
     * @hide
     * @deprecated Use {@link #registerGnssMeasurementsCallback} instead.
     * @removed
     */
    @Deprecated
    @SystemApi
    public boolean addGpsMeasurementListener(GpsMeasurementsEvent.Listener listener) {
        return false;
    }

    /**
     * No-op method to keep backward-compatibility.
     *
     * @hide
     * @deprecated Use {@link #unregisterGnssMeasurementsCallback} instead.
     * @removed
     */
    @Deprecated
    @SystemApi
    public void removeGpsMeasurementListener(GpsMeasurementsEvent.Listener listener) {}

    /**
     * Registers a GNSS measurements callback which will run on a binder thread.
     *
     * <p>See {@link #registerGnssMeasurementsCallback(Executor, GnssMeasurementsEvent.Callback)
     * for more detail on how this method works.
     *
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to register
     * @return {@code true} always
     *
     * @deprecated Use {@link
     * #registerGnssMeasurementsCallback(GnssMeasurementsEvent.Callback, Handler)} or {@link
     * #registerGnssMeasurementsCallback(Executor, GnssMeasurementsEvent.Callback)} instead.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssMeasurementsCallback(
            @NonNull GnssMeasurementsEvent.Callback callback) {
        return registerGnssMeasurementsCallback(DIRECT_EXECUTOR, callback);
    }

    /**
     * Registers a GNSS measurements callback.
     *
     * <p>See {@link #registerGnssMeasurementsCallback(Executor, GnssMeasurementsEvent.Callback)
     * for more detail on how this method works.
     *
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to register
     * @param handler  the handler that the callback runs on
     * @return {@code true} always
     *
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssMeasurementsCallback(
            @NonNull GnssMeasurementsEvent.Callback callback, @Nullable Handler handler) {
        if (handler == null) {
            handler = new Handler();
        }

        return registerGnssMeasurementsCallback(new GnssMeasurementRequest.Builder().build(),
                new HandlerExecutor(handler), callback);
    }

    /**
     * Registers a GNSS measurements callback. GNSS measurements information will only be received
     * while the {@link #GPS_PROVIDER} is enabled, and while the client app is in the foreground.
     *
     * <p>Not all GNSS chipsets support measurements updates, see {@link #getGnssCapabilities()}.
     *
     * @param executor the executor that the callback runs on
     * @param callback the callback to register
     * @return {@code true} always
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssMeasurementsCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssMeasurementsEvent.Callback callback) {
        return registerGnssMeasurementsCallback(new GnssMeasurementRequest.Builder().build(),
                executor, callback);
    }

    /**
     * Registers a GNSS Measurement callback.
     *
     * @param request  the gnss measurement request containgin measurement parameters
     * @param executor the executor that the callback runs on
     * @param callback the callack to register
     * @return {@code true} always
     *
     * @throws IllegalArgumentException if request is null
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException        if the ACCESS_FINE_LOCATION permission is not present
     * @hide
     * @deprecated Use {@link #registerGnssMeasurementsCallback(GnssMeasurementRequest, Executor,
     * GnssMeasurementsEvent.Callback)} instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssMeasurementsCallback(
            @NonNull GnssRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssMeasurementsEvent.Callback callback) {
        return registerGnssMeasurementsCallback(request.toGnssMeasurementRequest(), executor,
                callback);
    }

    /**
     * Registers a GNSS measurement callback.
     *
     * @param request  extra parameters to pass to GNSS measurement provider. For example, if {@link
     *                 GnssMeasurementRequest#isFullTracking()} is true, GNSS chipset switches off
     *                 duty cycling.
     * @param executor the executor that the callback runs on
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to register.
     * @return {@code true} always if the callback was added successfully, {@code false} otherwise.
     * @throws IllegalArgumentException if request is null
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException        if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssMeasurementsCallback(
            @NonNull GnssMeasurementRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssMeasurementsEvent.Callback callback) {
        GnssLazyLoader.sGnssMeasurementsListeners.addListener(callback,
                new GnssMeasurementsTransport(executor, mContext, request, callback));
        return true;
    }

    /**
     * Injects GNSS measurement corrections into the GNSS chipset.
     *
     * @param measurementCorrections measurement corrections to be injected into the chipset
     *
     * @throws IllegalArgumentException if measurementCorrections is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     * @hide
     */
    @SystemApi
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public void injectGnssMeasurementCorrections(
            @NonNull GnssMeasurementCorrections measurementCorrections) {
        Preconditions.checkArgument(measurementCorrections != null);
        try {
            mService.injectGnssMeasurementCorrections(measurementCorrections);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a GPS Measurement callback.
     *
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to remove.
     */
    public void unregisterGnssMeasurementsCallback(
            @NonNull GnssMeasurementsEvent.Callback callback) {
        GnssLazyLoader.sGnssMeasurementsListeners.removeListener(callback);
    }

    /**
     * Registers a GNSS antenna info listener that will receive all changes to antenna info. Use
     * {@link #getGnssAntennaInfos()} to get current antenna info.
     *
     * <p>Not all GNSS chipsets support antenna info updates, see {@link #getGnssCapabilities()}. If
     * unsupported, the listener will never be invoked.
     *
     * <p>Prior to Android S, this requires the {@link Manifest.permission#ACCESS_FINE_LOCATION}
     * permission.
     *
     * @param executor the executor that the listener runs on
     * @param listener the listener to register
     * @return {@code true} always
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if listener is null
     */
    public boolean registerAntennaInfoListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssAntennaInfo.Listener listener) {
        GnssLazyLoader.sGnssAntennaInfoListeners.addListener(listener,
                new GnssAntennaInfoTransport(executor, mContext, listener));
        return true;
    }

    /**
     * Unregisters a GNSS antenna info listener.
     *
     * @param listener a {@link GnssAntennaInfo.Listener} object to remove
     */
    public void unregisterAntennaInfoListener(@NonNull GnssAntennaInfo.Listener listener) {
        GnssLazyLoader.sGnssAntennaInfoListeners.removeListener(listener);
    }

    /**
     * No-op method to keep backward-compatibility.
     *
     * @hide
     * @deprecated Use {@link #registerGnssNavigationMessageCallback} instead.
     * @removed
     */
    @Deprecated
    @SystemApi
    public boolean addGpsNavigationMessageListener(GpsNavigationMessageEvent.Listener listener) {
        return false;
    }

    /**
     * No-op method to keep backward-compatibility.
     *
     * @hide
     * @deprecated Use {@link #unregisterGnssNavigationMessageCallback} instead.
     * @removed
     */
    @Deprecated
    @SystemApi
    public void removeGpsNavigationMessageListener(GpsNavigationMessageEvent.Listener listener) {}

    /**
     * Registers a GNSS navigation message callback which will run on a binder thread.
     *
     * <p>See
     * {@link #registerGnssNavigationMessageCallback(Executor, GnssNavigationMessage.Callback)} for
     * more detail on how this method works.
     *
     * @param callback the callback to register
     * @return {@code true} always
     *
     * @deprecated Use {@link
     * #registerGnssNavigationMessageCallback(GnssNavigationMessage.Callback, Handler)} or {@link
     * #registerGnssNavigationMessageCallback(Executor, GnssNavigationMessage.Callback)} instead.
     */
    @Deprecated
    public boolean registerGnssNavigationMessageCallback(
            @NonNull GnssNavigationMessage.Callback callback) {
        return registerGnssNavigationMessageCallback(DIRECT_EXECUTOR, callback);
    }

    /**
     * Registers a GNSS navigation message callback.
     *
     * <p>See
     * {@link #registerGnssNavigationMessageCallback(Executor, GnssNavigationMessage.Callback)} for
     * more detail on how this method works.
     *
     * @param callback the callback to register
     * @param handler  the handler that the callback runs on
     * @return {@code true} always
     *
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssNavigationMessageCallback(
            @NonNull GnssNavigationMessage.Callback callback, @Nullable Handler handler) {
        if (handler == null) {
            handler = new Handler();
        }

        return registerGnssNavigationMessageCallback(new HandlerExecutor(handler), callback);
    }

    /**
     * Registers a GNSS navigation message callback. GNSS navigation messages will only be received
     * while the {@link #GPS_PROVIDER} is enabled, and while the client app is in the foreground.
     *
     * <p>Not all GNSS chipsets support navigation message updates, see
     * {@link #getGnssCapabilities()}.
     *
     * @param executor the executor that the callback runs on
     * @param callback the callback to register
     * @return {@code true} always
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssNavigationMessageCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssNavigationMessage.Callback callback) {
        GnssLazyLoader.sGnssNavigationListeners.addListener(callback,
                new GnssNavigationTransport(executor, mContext, callback));
        return true;
    }

    /**
     * Unregisters a GNSS Navigation Message callback.
     *
     * @param callback a {@link GnssNavigationMessage.Callback} object to remove.
     */
    public void unregisterGnssNavigationMessageCallback(
            @NonNull GnssNavigationMessage.Callback callback) {
        GnssLazyLoader.sGnssNavigationListeners.removeListener(callback);
    }

    /**
     * Adds a {@link ProviderRequest.ChangedListener} for listening to all providers'
     * {@link ProviderRequest} changed events.
     *
     * @param executor the executor that the callback runs on
     * @param listener the listener to register
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void addProviderRequestChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull ChangedListener listener) {
        ProviderRequestLazyLoader.sProviderRequestListeners.addListener(listener,
                new ProviderRequestTransport(executor, listener));
    }

    /**
     * Removes a {@link ProviderRequest.ChangedListener} that has been added.
     *
     * @param listener the listener to remove.
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void removeProviderRequestChangedListener(
            @NonNull ProviderRequest.ChangedListener listener) {
        ProviderRequestLazyLoader.sProviderRequestListeners.removeListener(listener);
    }

    /**
     * Returns the batch size (in number of Location objects) that are supported by the batching
     * interface.
     *
     * Prior to Android S this call requires the {@link Manifest.permission#LOCATION_HARDWARE}
     * permission.
     *
     * @return Maximum number of location objects that can be returned
     * @deprecated Do not use
     * @hide
     */
    @Deprecated
    @SystemApi
    public int getGnssBatchSize() {
        try {
            return mService.getGnssBatchSize();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Start hardware-batching of GNSS locations. This API is primarily used when the AP is
     * asleep and the device can batch GNSS locations in the hardware.
     *
     * Note this is designed (as was the fused location interface before it) for a single user
     * SystemApi - requests are not consolidated.  Care should be taken when the System switches
     * users that may have different batching requests, to stop hardware batching for one user, and
     * restart it for the next.
     *
     * @param periodNanos Time interval, in nanoseconds, that the GNSS locations are requested
     *                    within the batch
     * @param wakeOnFifoFull ignored
     * @param callback The listener on which to return the batched locations
     * @param handler The handler on which to process the callback
     *
     * @return True always
     * @deprecated Use {@link LocationRequest.Builder#setMaxUpdateDelayMillis(long)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(allOf = {Manifest.permission.LOCATION_HARDWARE,
            Manifest.permission.UPDATE_APP_OPS_STATS})
    public boolean registerGnssBatchedLocationCallback(long periodNanos, boolean wakeOnFifoFull,
            @NonNull BatchedLocationCallback callback, @Nullable Handler handler) {
        if (handler == null) {
            handler = new Handler();
        }

        try {
            mService.startGnssBatch(
                    periodNanos,
                    new BatchedLocationCallbackTransport(callback, handler),
                    mContext.getPackageName(),
                    mContext.getAttributionTag(),
                    AppOpsManager.toReceiverId(callback));
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Flush the batched GNSS locations. All GNSS locations currently ready in the batch are
     * returned via the callback sent in startGnssBatch(), and the buffer containing the batched
     * locations is cleared.
     *
     * @hide
     * @deprecated Use {@link #requestFlush(String, LocationListener, int)} or
     *             {@link #requestFlush(String, PendingIntent, int)} instead.
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void flushGnssBatch() {
        try {
            mService.flushGnssBatch();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop batching locations. This API is primarily used when the AP is asleep and the device can
     * batch locations in the hardware.
     *
     * @param callback ignored
     *
     * @return True always
     * @deprecated Use {@link LocationRequest.Builder#setMaxUpdateDelayMillis(long)} instead.
     * @hide
     */
    @Deprecated
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public boolean unregisterGnssBatchedLocationCallback(
            @NonNull BatchedLocationCallback callback) {
        try {
            mService.stopGnssBatch();
            return true;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private static class GnssStatusTransportManager extends
            ListenerTransportManager<GnssStatusTransport> {

        GnssStatusTransportManager() {
            super(false);
        }

        @Override
        protected void registerTransport(GnssStatusTransport transport)
                            throws RemoteException {
            getService().registerGnssStatusCallback(transport, transport.getPackage(),
                    transport.getAttributionTag(),
                    AppOpsManager.toReceiverId(transport.getListener()));
        }

        @Override
        protected void unregisterTransport(GnssStatusTransport transport)
                            throws RemoteException {
            getService().unregisterGnssStatusCallback(transport);
        }
    }

    private static class GnssNmeaTransportManager extends
            ListenerTransportManager<GnssNmeaTransport> {

        GnssNmeaTransportManager() {
            super(false);
        }

        @Override
        protected void registerTransport(GnssNmeaTransport transport)
                            throws RemoteException {
            getService().registerGnssNmeaCallback(transport, transport.getPackage(),
                    transport.getAttributionTag(),
                    AppOpsManager.toReceiverId(transport.getListener()));
        }

        @Override
        protected void unregisterTransport(GnssNmeaTransport transport)
                            throws RemoteException {
            getService().unregisterGnssNmeaCallback(transport);
        }
    }

    private static class GnssMeasurementsTransportManager extends
            ListenerTransportManager<GnssMeasurementsTransport> {

        GnssMeasurementsTransportManager() {
            super(false);
        }

        @Override
        protected void registerTransport(GnssMeasurementsTransport transport)
                            throws RemoteException {
            getService().addGnssMeasurementsListener(transport.getRequest(), transport,
                    transport.getPackage(), transport.getAttributionTag(),
                    AppOpsManager.toReceiverId(transport.getListener()));
        }

        @Override
        protected void unregisterTransport(GnssMeasurementsTransport transport)
                            throws RemoteException {
            getService().removeGnssMeasurementsListener(transport);
        }
    }

    private static class GnssAntennaTransportManager extends
            ListenerTransportManager<GnssAntennaInfoTransport> {

        GnssAntennaTransportManager() {
            super(false);
        }

        @Override
        protected void registerTransport(GnssAntennaInfoTransport transport)
                throws RemoteException {
            getService().addGnssAntennaInfoListener(transport, transport.getPackage(),
                    transport.getAttributionTag(),
                    AppOpsManager.toReceiverId(transport.getListener()));
        }

        @Override
        protected void unregisterTransport(GnssAntennaInfoTransport transport)
                throws RemoteException {
            getService().removeGnssAntennaInfoListener(transport);
        }
    }

    private static class GnssNavigationTransportManager extends
            ListenerTransportManager<GnssNavigationTransport> {

        GnssNavigationTransportManager() {
            super(false);
        }

        @Override
        protected void registerTransport(GnssNavigationTransport transport)
                            throws RemoteException {
            getService().addGnssNavigationMessageListener(transport,
                    transport.getPackage(), transport.getAttributionTag(),
                    AppOpsManager.toReceiverId(transport.getListener()));
        }

        @Override
        protected void unregisterTransport(GnssNavigationTransport transport)
                            throws RemoteException {
            getService().removeGnssNavigationMessageListener(transport);
        }
    }

    private static class ProviderRequestTransportManager extends
            ListenerTransportManager<ProviderRequestTransport> {

        ProviderRequestTransportManager() {
            super(false);
        }

        @Override
        protected void registerTransport(ProviderRequestTransport transport)
                throws RemoteException {
            getService().addProviderRequestListener(transport);
        }

        @Override
        protected void unregisterTransport(ProviderRequestTransport transport)
                throws RemoteException {
            getService().removeProviderRequestListener(transport);
        }
    }

    private static class GetCurrentLocationTransport extends ILocationCallback.Stub implements
            ListenerExecutor, CancellationSignal.OnCancelListener {

        private final Executor mExecutor;
        volatile @Nullable Consumer<Location> mConsumer;

        GetCurrentLocationTransport(Executor executor, Consumer<Location> consumer,
                @Nullable CancellationSignal cancellationSignal) {
            Preconditions.checkArgument(executor != null, "illegal null executor");
            Preconditions.checkArgument(consumer != null, "illegal null consumer");
            mExecutor = executor;
            mConsumer = consumer;

            if (cancellationSignal != null) {
                cancellationSignal.setOnCancelListener(this);
            }
        }

        @Override
        public void onCancel() {
            mConsumer = null;
        }

        @Override
        public void onLocation(@Nullable Location location) {
            executeSafely(mExecutor, () -> mConsumer, new ListenerOperation<Consumer<Location>>() {
                @Override
                public void operate(Consumer<Location> consumer) {
                    consumer.accept(location);
                }

                @Override
                public void onPostExecute(boolean success) {
                    mConsumer = null;
                }
            });
        }
    }

    private static class LocationListenerTransport extends ILocationListener.Stub implements
            ListenerExecutor {

        private Executor mExecutor;
        private volatile @Nullable LocationListener mListener;

        LocationListenerTransport(LocationListener listener, Executor executor) {
            Preconditions.checkArgument(listener != null, "invalid null listener");
            mListener = listener;
            setExecutor(executor);
        }

        void setExecutor(Executor executor) {
            Preconditions.checkArgument(executor != null, "invalid null executor");
            mExecutor = executor;
        }

        boolean isRegistered() {
            return mListener != null;
        }

        void unregister() {
            mListener = null;
        }

        @Override
        public void onLocationChanged(List<Location> locations,
                @Nullable IRemoteCallback onCompleteCallback) {
            executeSafely(mExecutor, () -> mListener, new ListenerOperation<LocationListener>() {
                @Override
                public void operate(LocationListener listener) {
                    listener.onLocationChanged(locations);
                }

                @Override
                public void onComplete(boolean success) {
                    if (onCompleteCallback != null) {
                        try {
                            onCompleteCallback.sendResult(null);
                        } catch (RemoteException e) {
                            throw e.rethrowFromSystemServer();
                        }
                    }
                }
            });
        }

        @Override
        public void onFlushComplete(int requestCode) {
            executeSafely(mExecutor, () -> mListener,
                    listener -> listener.onFlushComplete(requestCode));
        }

        @Override
        public void onProviderEnabledChanged(String provider, boolean enabled) {
            executeSafely(mExecutor, () -> mListener, listener -> {
                if (enabled) {
                    listener.onProviderEnabled(provider);
                } else {
                    listener.onProviderDisabled(provider);
                }
            });
        }
    }

    /** @deprecated */
    @Deprecated
    private static class GpsAdapter extends GnssStatus.Callback {

        private final GpsStatus.Listener mGpsListener;

        GpsAdapter(GpsStatus.Listener gpsListener) {
            mGpsListener = gpsListener;
        }

        @Override
        public void onStarted() {
            mGpsListener.onGpsStatusChanged(GpsStatus.GPS_EVENT_STARTED);
        }

        @Override
        public void onStopped() {
            mGpsListener.onGpsStatusChanged(GpsStatus.GPS_EVENT_STOPPED);
        }

        @Override
        public void onFirstFix(int ttffMillis) {
            mGpsListener.onGpsStatusChanged(GpsStatus.GPS_EVENT_FIRST_FIX);
        }

        @Override
        public void onSatelliteStatusChanged(GnssStatus status) {
            mGpsListener.onGpsStatusChanged(GpsStatus.GPS_EVENT_SATELLITE_STATUS);
        }
    }

    private static class GnssStatusTransport extends IGnssStatusListener.Stub implements
            ListenerTransport<GnssStatus.Callback> {

        private final Executor mExecutor;
        private final String mPackageName;
        private final String mAttributionTag;

        private volatile @Nullable GnssStatus.Callback mListener;

        GnssStatusTransport(Executor executor, Context context, GnssStatus.Callback listener) {
            Preconditions.checkArgument(executor != null, "invalid null executor");
            Preconditions.checkArgument(listener != null, "invalid null callback");
            mExecutor = executor;
            mPackageName = context.getPackageName();
            mAttributionTag = context.getAttributionTag();
            mListener = listener;
        }

        public String getPackage() {
            return mPackageName;
        }

        public String getAttributionTag() {
            return mAttributionTag;
        }

        @Override
        public void unregister() {
            mListener = null;
        }

        @Override
        public @Nullable GnssStatus.Callback getListener() {
            return mListener;
        }

        @Override
        public void onGnssStarted() {
            execute(mExecutor, GnssStatus.Callback::onStarted);
        }

        @Override
        public void onGnssStopped() {
            execute(mExecutor, GnssStatus.Callback::onStopped);
        }

        @Override
        public void onFirstFix(int ttff) {
            execute(mExecutor, listener -> listener.onFirstFix(ttff));

        }

        @Override
        public void onSvStatusChanged(GnssStatus gnssStatus) {
            execute(mExecutor, listener -> listener.onSatelliteStatusChanged(gnssStatus));
        }
    }

    /** @deprecated */
    @Deprecated
    private static class GpsStatusTransport extends GnssStatusTransport {

        static volatile int sTtff;
        static volatile GnssStatus sGnssStatus;

        GpsStatusTransport(Executor executor, Context context, GpsStatus.Listener listener) {
            super(executor, context, new GpsAdapter(listener));
        }

        @Override
        public void onFirstFix(int ttff) {
            sTtff = ttff;
            super.onFirstFix(ttff);
        }

        @Override
        public void onSvStatusChanged(GnssStatus gnssStatus) {
            sGnssStatus = gnssStatus;
            super.onSvStatusChanged(gnssStatus);
        }
    }

    private static class GnssNmeaTransport extends IGnssNmeaListener.Stub implements
            ListenerTransport<OnNmeaMessageListener> {

        private final Executor mExecutor;
        private final String mPackageName;
        private final String mAttributionTag;

        private volatile @Nullable OnNmeaMessageListener mListener;

        GnssNmeaTransport(Executor executor, Context context, OnNmeaMessageListener listener) {
            Preconditions.checkArgument(executor != null, "invalid null executor");
            Preconditions.checkArgument(listener != null, "invalid null listener");
            mExecutor = executor;
            mPackageName = context.getPackageName();
            mAttributionTag = context.getAttributionTag();
            mListener = listener;
        }

        public String getPackage() {
            return mPackageName;
        }

        public String getAttributionTag() {
            return mAttributionTag;
        }

        @Override
        public void unregister() {
            mListener = null;
        }

        @Override
        public @Nullable OnNmeaMessageListener getListener() {
            return mListener;
        }

        @Override
        public void onNmeaReceived(long timestamp, String nmea) {
            execute(mExecutor, callback -> callback.onNmeaMessage(nmea, timestamp));
        }
    }

    private static class GnssMeasurementsTransport extends IGnssMeasurementsListener.Stub implements
            ListenerTransport<GnssMeasurementsEvent.Callback> {

        private final Executor mExecutor;
        private final String mPackageName;
        private final String mAttributionTag;
        private final GnssMeasurementRequest mRequest;

        private volatile @Nullable GnssMeasurementsEvent.Callback mListener;

        GnssMeasurementsTransport(Executor executor, Context context,
                GnssMeasurementRequest request, GnssMeasurementsEvent.Callback listener) {
            Preconditions.checkArgument(executor != null, "invalid null executor");
            Preconditions.checkArgument(listener != null, "invalid null callback");
            Preconditions.checkArgument(request != null, "invalid null request");
            mExecutor = executor;
            mPackageName = context.getPackageName();
            mAttributionTag = context.getAttributionTag();
            mRequest = request;
            mListener = listener;
        }

        public String getPackage() {
            return mPackageName;
        }

        public String getAttributionTag() {
            return mAttributionTag;
        }

        public GnssMeasurementRequest getRequest() {
            return mRequest;
        }

        @Override
        public void unregister() {
            mListener = null;
        }

        @Override
        public @Nullable GnssMeasurementsEvent.Callback getListener() {
            return mListener;
        }

        @Override
        public void onGnssMeasurementsReceived(GnssMeasurementsEvent event) {
            execute(mExecutor, callback -> callback.onGnssMeasurementsReceived(event));
        }

        @Override
        public void onStatusChanged(int status) {
            execute(mExecutor, callback -> callback.onStatusChanged(status));
        }
    }

    private static class GnssAntennaInfoTransport extends IGnssAntennaInfoListener.Stub implements
            ListenerTransport<GnssAntennaInfo.Listener> {

        private final Executor mExecutor;
        private final String mPackageName;
        private final String mAttributionTag;

        private volatile @Nullable GnssAntennaInfo.Listener mListener;

        GnssAntennaInfoTransport(Executor executor, Context context,
                GnssAntennaInfo.Listener listener) {
            Preconditions.checkArgument(executor != null, "invalid null executor");
            Preconditions.checkArgument(listener != null, "invalid null listener");
            mExecutor = executor;
            mPackageName = context.getPackageName();
            mAttributionTag = context.getAttributionTag();
            mListener = listener;
        }

        public String getPackage() {
            return mPackageName;
        }

        public String getAttributionTag() {
            return mAttributionTag;
        }

        @Override
        public void unregister() {
            mListener = null;
        }

        @Override
        public @Nullable GnssAntennaInfo.Listener getListener() {
            return mListener;
        }

        @Override
        public void onGnssAntennaInfoChanged(List<GnssAntennaInfo> antennaInfos) {
            execute(mExecutor, callback -> callback.onGnssAntennaInfoReceived(antennaInfos));
        }
    }

    private static class GnssNavigationTransport extends IGnssNavigationMessageListener.Stub
            implements ListenerTransport<GnssNavigationMessage.Callback> {

        private final Executor mExecutor;
        private final String mPackageName;
        private final String mAttributionTag;

        private volatile @Nullable GnssNavigationMessage.Callback mListener;

        GnssNavigationTransport(Executor executor, Context context,
                GnssNavigationMessage.Callback listener) {
            Preconditions.checkArgument(executor != null, "invalid null executor");
            Preconditions.checkArgument(listener != null, "invalid null callback");
            mExecutor = executor;
            mPackageName = context.getPackageName();
            mAttributionTag = context.getAttributionTag();
            mListener = listener;
        }

        public String getPackage() {
            return mPackageName;
        }

        public String getAttributionTag() {
            return mAttributionTag;
        }

        @Override
        public void unregister() {
            mListener = null;
        }

        @Override
        public @Nullable GnssNavigationMessage.Callback getListener() {
            return mListener;
        }

        @Override
        public void onGnssNavigationMessageReceived(GnssNavigationMessage event) {
            execute(mExecutor, listener -> listener.onGnssNavigationMessageReceived(event));
        }

        @Override
        public void onStatusChanged(int status) {
            execute(mExecutor, listener -> listener.onStatusChanged(status));
        }
    }

    private static class ProviderRequestTransport extends IProviderRequestListener.Stub
            implements ListenerTransport<ChangedListener> {

        private final Executor mExecutor;

        private volatile @Nullable ProviderRequest.ChangedListener mListener;

        ProviderRequestTransport(Executor executor, ChangedListener listener) {
            Preconditions.checkArgument(executor != null, "invalid null executor");
            Preconditions.checkArgument(listener != null, "invalid null callback");
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void unregister() {
            mListener = null;
        }

        @Override
        public @Nullable ProviderRequest.ChangedListener getListener() {
            return mListener;
        }

        @Override
        public void onProviderRequestChanged(String provider, ProviderRequest request) {
            execute(mExecutor, listener -> listener.onProviderRequestChanged(provider, request));
        }
    }

    /** @deprecated */
    @Deprecated
    private static class BatchedLocationCallbackWrapper implements LocationListener {

        private final BatchedLocationCallback mCallback;

        BatchedLocationCallbackWrapper(BatchedLocationCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onLocationChanged(@NonNull Location location) {
            mCallback.onLocationBatch(Collections.singletonList(location));
        }

        @Override
        public void onLocationChanged(@NonNull List<Location> locations) {
            mCallback.onLocationBatch(locations);
        }
    }

    /** @deprecated */
    @Deprecated
    private static class BatchedLocationCallbackTransport extends LocationListenerTransport {

        BatchedLocationCallbackTransport(BatchedLocationCallback callback, Handler handler) {
            super(new BatchedLocationCallbackWrapper(callback), new HandlerExecutor(handler));
        }
    }

    private static class LocationEnabledCache extends PropertyInvalidatedCache<Integer, Boolean> {

        // this is not loaded immediately because this class is created as soon as LocationManager
        // is referenced for the first time, and within the system server, the ILocationManager
        // service may not have been loaded yet at that time.
        private @Nullable ILocationManager mManager;

        LocationEnabledCache(int numEntries) {
            super(numEntries, CACHE_KEY_LOCATION_ENABLED_PROPERTY);
        }

        @Override
        public Boolean recompute(Integer userId) {
            Preconditions.checkArgument(userId >= 0);

            if (mManager == null) {
                try {
                    mManager = getService();
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
            }

            try {
                return mManager.isLocationEnabledForUser(userId);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * @hide
     */
    public static void invalidateLocalLocationEnabledCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_LOCATION_ENABLED_PROPERTY);
    }

    /**
     * @hide
     */
    public static void disableLocalLocationEnabledCaches() {
        sLocationEnabledCache = null;
    }
}
