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
import static android.Manifest.permission.LOCATION_HARDWARE;
import static android.Manifest.permission.WRITE_SECURE_SETTINGS;
import static android.app.AlarmManager.ELAPSED_REALTIME;

import static com.android.internal.util.function.pooled.PooledLambda.obtainRunnable;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.PropertyInvalidatedCache;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.location.ProviderProperties;
import com.android.internal.util.Preconditions;
import com.android.internal.util.function.pooled.PooledRunnable;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/**
 * This class provides access to the system location services. These services allow applications to
 * obtain periodic updates of the device's geographical location, or to be notified when the device
 * enters the proximity of a given geographical location.
 *
 * <p class="note">Unless noted, all Location API methods require the {@link
 * android.Manifest.permission#ACCESS_COARSE_LOCATION} or {@link
 * android.Manifest.permission#ACCESS_FINE_LOCATION} permissions. If your application only has the
 * coarse permission then it will not have access to fine location providers. Other providers will
 * still return location results, but the exact location will be obfuscated to a coarse level of
 * accuracy.
 */
@SuppressWarnings({"deprecation"})
@SystemService(Context.LOCATION_SERVICE)
@RequiresFeature(PackageManager.FEATURE_LOCATION)
public class LocationManager {

    @GuardedBy("mLock")
    private PropertyInvalidatedCache<Integer, Boolean> mLocationEnabledCache =
            new PropertyInvalidatedCache<Integer, Boolean>(
                4,
                CACHE_KEY_LOCATION_ENABLED_PROPERTY) {
                @Override
                protected Boolean recompute(Integer userHandle) {
                    try {
                        return mService.isLocationEnabledForUser(userHandle);
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            };

    private final Object mLock = new Object();

    /**
     * For apps targeting Android R and above, {@link #getProvider(String)} will no longer throw any
     * security exceptions.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
    private static final long GET_PROVIDER_SECURITY_EXCEPTIONS = 150935354L;

    /**
     * For apps targeting Android K and above, supplied {@link PendingIntent}s must be targeted to a
     * specific package.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.JELLY_BEAN)
    private static final long TARGETED_PENDING_INTENT = 148963590L;

    /**
     * For apps targeting Android K and above, incomplete locations may not be passed to
     * {@link #setTestProviderLocation}.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.JELLY_BEAN)
    private static final long INCOMPLETE_LOCATION = 148964793L;

    /**
     * For apps targeting Android S and above, all {@link GpsStatus} API usage must be replaced with
     * {@link GnssStatus} APIs.
     *
     * @hide
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    private static final long GPS_STATUS_USAGE = 144027538L;

    /**
     * Name of the network location provider.
     *
     * <p>This provider determines location based on nearby of cell tower and WiFi access points.
     * Results are retrieved by means of a network lookup.
     */
    public static final String NETWORK_PROVIDER = "network";

    /**
     * Name of the GNSS location provider.
     *
     * <p>This provider determines location using GNSS satellites. Depending on conditions, this
     * provider may take a while to return a location fix. Requires the
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission.
     *
     * <p>The extras Bundle for the GPS location provider can contain the following key/value pairs:
     * <ul>
     * <li> satellites - the number of satellites used to derive the fix
     * </ul>
     */
    public static final String GPS_PROVIDER = "gps";

    /**
     * A special location provider for receiving locations without actually initiating a location
     * fix.
     *
     * <p>This provider can be used to passively receive location updates when other applications or
     * services request them without actually requesting the locations yourself. This provider will
     * only return locations generated by other providers.  You can query the
     * {@link Location#getProvider()} method to determine the actual provider that supplied the
     * location update. Requires the {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission, although there is no guarantee of fine locations.
     */
    public static final String PASSIVE_PROVIDER = "passive";

    /**
     * The fused location provider.
     *
     * <p>This provider combines may combine inputs from several location sources to provide the
     * best possible location fix. It is implicitly used for all API's that involve the
     * {@link LocationRequest} object.
     *
     * @hide
     */
    @TestApi
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
     * @see #requestLocationUpdates(String, long, float, PendingIntent)
     */
    public static final String KEY_PROVIDER_ENABLED = "providerEnabled";

    /**
     * Key used for an extra holding a {@link Location} value when a location change is broadcast
     * using a PendingIntent.
     *
     * @see #requestLocationUpdates(String, long, float, PendingIntent)
     */
    public static final String KEY_LOCATION_CHANGED = "location";

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
    public static final String MODE_CHANGED_ACTION = "android.location.MODE_CHANGED";

    /**
     * Intent extra included with {@link #MODE_CHANGED_ACTION} broadcasts, containing the boolean
     * enabled state of location.
     *
     * @see #MODE_CHANGED_ACTION
     */
    public static final String EXTRA_LOCATION_ENABLED = "android.location.extra.LOCATION_ENABLED";

    /**
     * Broadcast intent action indicating that a high power location requests
     * has either started or stopped being active.  The current state of
     * active location requests should be read from AppOpsManager using
     * {@code OP_MONITOR_HIGH_POWER_LOCATION}.
     *
     * @hide
     */
    public static final String HIGH_POWER_REQUEST_CHANGE_ACTION =
            "android.location.HIGH_POWER_REQUEST_CHANGE";

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

    private static final long GET_CURRENT_LOCATION_MAX_TIMEOUT_MS = 30 * 1000;

    private final Context mContext;

    @UnsupportedAppUsage
    private final ILocationManager mService;

    @GuardedBy("mListeners")
    private final ArrayMap<LocationListener, LocationListenerTransport> mListeners =
            new ArrayMap<>();

    @GuardedBy("mBatchedLocationCallbackManager")
    private final BatchedLocationCallbackManager mBatchedLocationCallbackManager =
            new BatchedLocationCallbackManager();
    private final GnssStatusListenerManager
            mGnssStatusListenerManager = new GnssStatusListenerManager();
    private final GnssMeasurementsListenerManager mGnssMeasurementsListenerManager =
            new GnssMeasurementsListenerManager();
    private final GnssNavigationMessageListenerManager mGnssNavigationMessageListenerTransport =
            new GnssNavigationMessageListenerManager();
    private final GnssAntennaInfoListenerManager mGnssAntennaInfoListenerManager =
            new GnssAntennaInfoListenerManager();

    /**
     * @hide
     */
    public LocationManager(@NonNull Context context, @NonNull ILocationManager service) {
        mService = service;
        mContext = context;
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
     * @hide
     */
    @TestApi
    public @NonNull String[] getIgnoreSettingsWhitelist() {
        try {
            return mService.getIgnoreSettingsWhitelist();
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
            e.rethrowFromSystemServer();
            return null;
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
            return false;
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
            e.rethrowFromSystemServer();
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
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the current enabled/disabled state of location. To listen for changes, see
     * {@link #MODE_CHANGED_ACTION}.
     *
     * @return true if location is enabled and false if location is disabled.
     */
    public boolean isLocationEnabled() {
        return isLocationEnabledForUser(Process.myUserHandle());
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
        synchronized (mLock) {
            if (mLocationEnabledCache != null) {
                return mLocationEnabledCache.query(userHandle.getIdentifier());
            }
        }

        // fallback if cache is disabled
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
    @TestApi
    @RequiresPermission(WRITE_SECURE_SETTINGS)
    public void setLocationEnabledForUser(boolean enabled, @NonNull UserHandle userHandle) {
        try {
            mService.setLocationEnabledForUser(enabled, userHandle.getIdentifier());
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

        return Settings.Secure.putStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.LOCATION_PROVIDERS_ALLOWED,
                (enabled ? "+" : "-") + provider,
                userHandle.getIdentifier());
    }

    /**
     * Gets the last known location from the fused provider, or null if there is no last known
     * location. The returned location may be quite old in some circumstances, so the age of the
     * location should always be checked.
     *
     * @return the last known location, or null if not available
     * @throws SecurityException if no suitable location permission is present
     *
     * @hide
     */
    @Nullable
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public Location getLastLocation() {
        try {
            return mService.getLastLocation(null, mContext.getPackageName(),
                    mContext.getAttributionTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * @return the last known location for the given provider, or null if not available
     * @throws SecurityException if no suitable permission is present
     * @throws IllegalArgumentException if provider is null or doesn't exist
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    @Nullable
    public Location getLastKnownLocation(@NonNull String provider) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, 0, 0, true);

        try {
            return mService.getLastLocation(request, mContext.getPackageName(),
                    mContext.getAttributionTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Asynchronously returns a single current location fix. This may activate sensors in order to
     * compute a new location, unlike {@link #getLastKnownLocation(String)}, which will only return
     * a cached fix if available. The given callback will be invoked once and only once, either with
     * a valid location fix or with a null location fix if the provider was unable to generate a
     * valid location.
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
        getCurrentLocation(LocationRequest.createFromDeprecatedProvider(provider, 0, 0, true),
                cancellationSignal, executor, consumer);
    }

    /**
     * Asynchronously returns a single current location fix based on the given
     * {@link LocationRequest}.
     *
     * <p>See {@link #getCurrentLocation(String, CancellationSignal, Executor, Consumer)} for more
     * information.
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
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void getCurrentLocation(@NonNull LocationRequest locationRequest,
            @Nullable CancellationSignal cancellationSignal,
            @NonNull @CallbackExecutor Executor executor, @NonNull Consumer<Location> consumer) {
        LocationRequest currentLocationRequest = new LocationRequest(locationRequest)
                .setNumUpdates(1);
        if (currentLocationRequest.getExpireIn() > GET_CURRENT_LOCATION_MAX_TIMEOUT_MS) {
            currentLocationRequest.setExpireIn(GET_CURRENT_LOCATION_MAX_TIMEOUT_MS);
        }

        GetCurrentLocationTransport listenerTransport = new GetCurrentLocationTransport(executor,
                consumer);

        if (cancellationSignal != null) {
            cancellationSignal.throwIfCanceled();
        }

        ICancellationSignal remoteCancellationSignal = CancellationSignal.createTransport();

        try {
            if (mService.getCurrentLocation(currentLocationRequest, remoteCancellationSignal,
                    listenerTransport, mContext.getPackageName(), mContext.getAttributionTag())) {
                listenerTransport.register(mContext.getSystemService(AlarmManager.class),
                        remoteCancellationSignal);
                if (cancellationSignal != null) {
                    cancellationSignal.setOnCancelListener(listenerTransport::cancel);
                }
            } else {
                listenerTransport.fail();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
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
        Preconditions.checkArgument(listener != null, "invalid null listener");

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, 0, 0, true);
        request.setExpireIn(GET_CURRENT_LOCATION_MAX_TIMEOUT_MS);
        requestLocationUpdates(request, listener, looper);
    }

    /**
     * Register for a single location update using a Criteria and a callback.
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
        Preconditions.checkArgument(listener != null, "invalid null listener");

        LocationRequest request = LocationRequest.createFromDeprecatedCriteria(
                criteria, 0, 0, true);
        request.setExpireIn(GET_CURRENT_LOCATION_MAX_TIMEOUT_MS);
        requestLocationUpdates(request, listener, looper);
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

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, 0, 0, true);
        request.setExpireIn(GET_CURRENT_LOCATION_MAX_TIMEOUT_MS);
        requestLocationUpdates(request, pendingIntent);
    }

    /**
     * Register for a single location update using a Criteria and pending intent.
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

        LocationRequest request = LocationRequest.createFromDeprecatedCriteria(
                criteria, 0, 0, true);
        request.setExpireIn(GET_CURRENT_LOCATION_MAX_TIMEOUT_MS);
        requestLocationUpdates(request, pendingIntent);
    }

    /**
     * Register for location updates from the given provider with the given arguments. {@link
     * LocationListener} callbacks will take place on the given {@link Looper} or {@link Executor}.
     * If a null {@link Looper} is supplied, the Looper of the calling thread will be used instead.
     * Only one request can be registered for each unique listener, so any subsequent requests with
     * the same listener will overwrite all associated arguments.
     *
     * <p> It may take a while to receive the first location update. If an immediate location is
     * required, applications may use the {@link #getLastKnownLocation(String)} method.
     *
     * <p> The location update interval can be controlled using the minimum time parameter. The
     * elapsed time between location updates will never be less than this parameter, although it may
     * be more depending on location availability and other factors. Choosing a sensible value for
     * the minimum time parameter is important to conserve battery life. Every location update
     * requires power from a variety of sensors. Select a minimum time parameter as high as possible
     * while still providing a reasonable user experience. If your application is not in the
     * foreground and showing location to the user then your application should consider switching
     * to the {@link #PASSIVE_PROVIDER} instead.
     *
     * <p> The minimum distance parameter can also be used to control the frequency of location
     * updates. If it is greater than 0 then the location provider will only send your application
     * an update when the location has changed by at least minDistance meters, AND when the minimum
     * time has elapsed. However it is more difficult for location providers to save power using the
     * minimum distance parameter, so the minimum time parameter should be the primary tool for
     * conserving battery life.
     *
     * <p> If your application wants to passively observe location updates triggered by other
     * applications, but not consume any additional power otherwise, then use the {@link
     * #PASSIVE_PROVIDER}. This provider does not turn on or modify active location providers, so
     * you do not need to be as careful about minimum time and minimum distance parameters. However,
     * if your application performs heavy work on a location update (such as network activity) then
     * you should select non-zero values for the parameters to rate-limit your update frequency in
     * the case another application enables a location provider with extremely fast updates.
     *
     * <p>In case the provider you have selected is disabled, location updates will cease, and a
     * provider availability update will be sent. As soon as the provider is enabled again, another
     * provider availability update will be sent and location updates will immediately resume.
     *
     * <p> When location callbacks are invoked, the system will hold a wakelock on your
     * application's behalf for some period of time, but not indefinitely. If your application
     * requires a long running wakelock within the location callback, you should acquire it
     * yourself.
     *
     * <p class="note"> Prior to Jellybean, the minTime parameter was only a hint, and some location
     * provider implementations ignored it. For Jellybean and onwards however, it is mandatory for
     * Android compatible devices to observe both the minTime and minDistance parameters.
     *
     * <p>To unregister for location updates, use {@link #removeUpdates(LocationListener)}.
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
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(listener != null, "invalid null listener");

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, minTimeMs, minDistanceM, false);
        requestLocationUpdates(request, listener, null);
    }

    /**
     * Register for location updates using the named provider, and a callback on
     * the specified {@link Looper}.
     *
     * <p>See {@link #requestLocationUpdates(String, long, float, LocationListener)}
     * for more detail on how this method works.
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
        Preconditions.checkArgument(provider != null, "invalid null provider");
        Preconditions.checkArgument(listener != null, "invalid null listener");

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, minTimeMs, minDistanceM, false);
        requestLocationUpdates(request, listener, looper);
    }

    /**
     * Register for location updates using the named provider, and a callback on
     * the specified {@link Executor}.
     *
     * <p>See {@link #requestLocationUpdates(String, long, float, LocationListener)}
     * for more detail on how this method works.
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
        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, minTimeMs, minDistanceM, false);
        requestLocationUpdates(request, executor, listener);
    }

    /**
     * Register for location updates using a provider selected through the given Criteria, and a
     * callback on the specified {@link Looper}.
     *
     * <p>See {@link #requestLocationUpdates(String, long, float, LocationListener)}
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
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(long minTimeMs, float minDistanceM,
            @NonNull Criteria criteria, @NonNull LocationListener listener,
            @Nullable Looper looper) {
        Preconditions.checkArgument(criteria != null, "invalid null criteria");
        Preconditions.checkArgument(listener != null, "invalid null listener");

        LocationRequest request = LocationRequest.createFromDeprecatedCriteria(
                criteria, minTimeMs, minDistanceM, false);
        requestLocationUpdates(request, listener, looper);
    }

    /**
     * Register for location updates using a provider selected through the given Criteria, and a
     * callback on the specified {@link Executor}.
     *
     * <p>See {@link #requestLocationUpdates(String, long, float, LocationListener)}
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
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(
            long minTimeMs,
            float minDistanceM,
            @NonNull Criteria criteria,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull LocationListener listener) {
        LocationRequest request = LocationRequest.createFromDeprecatedCriteria(
                criteria, minTimeMs, minDistanceM, false);
        requestLocationUpdates(request, executor, listener);
    }

    /**
     * Register for location updates using the named provider, and callbacks delivered via the
     * provided {@link PendingIntent}.
     *
     * <p>The delivered pending intents will contain extras with the callback information. The keys
     * used for the extras are {@link #KEY_LOCATION_CHANGED} and {@link #KEY_PROVIDER_ENABLED}. See
     * the documentation for each respective extra key for information on the values.
     *
     * <p>To unregister for location updates, use {@link #removeUpdates(PendingIntent)}.
     *
     * <p>See {@link #requestLocationUpdates(String, long, float, LocationListener)}
     * for more detail on how this method works.
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

        LocationRequest request = LocationRequest.createFromDeprecatedProvider(
                provider, minTimeMs, minDistanceM, false);
        requestLocationUpdates(request, pendingIntent);
    }

    /**
     * Register for location updates using a provider selected through the given Criteria, and
     * callbacks delivered via the provided {@link PendingIntent}.
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
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(long minTimeMs, float minDistanceM,
            @NonNull Criteria criteria, @NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(criteria != null, "invalid null criteria");

        LocationRequest request = LocationRequest.createFromDeprecatedCriteria(
                criteria, minTimeMs, minDistanceM, false);
        requestLocationUpdates(request, pendingIntent);
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
     * <p>See {@link #requestLocationUpdates(String, long, float, LocationListener)}
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
     */
    @SystemApi
    @TestApi
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
     * <p>See {@link #requestLocationUpdates(LocationRequest, LocationListener, Looper)} for more
     * detail on how this method works.
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
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(
            @Nullable LocationRequest locationRequest,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull LocationListener listener) {
        synchronized (mListeners) {
            LocationListenerTransport transport = mListeners.get(listener);
            if (transport != null) {
                transport.unregister();
            } else {
                transport = new LocationListenerTransport(listener);
                mListeners.put(listener, transport);
            }
            transport.register(executor);

            boolean registered = false;
            try {
                mService.requestLocationUpdates(locationRequest, transport, null,
                        mContext.getPackageName(), mContext.getAttributionTag());
                registered = true;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            } finally {
                if (!registered) {
                    // allow gc after exception
                    transport.unregister();
                    mListeners.remove(listener);
                }
            }
        }
    }

    /**
     * Register for location updates using a {@link LocationRequest}, and callbacks delivered via
     * the provided {@link PendingIntent}.
     *
     * <p>See {@link #requestLocationUpdates(LocationRequest, LocationListener, Looper)} and
     * {@link #requestLocationUpdates(String, long, float, PendingIntent)} for more detail on how
     * this method works.
     *
     * @param locationRequest the location request containing location parameters
     * @param pendingIntent the pending intent to send location updates
     *
     * @throws IllegalArgumentException if pendingIntent is null
     * @throws SecurityException if no suitable permission is present
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void requestLocationUpdates(
            @Nullable LocationRequest locationRequest,
            @NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(locationRequest != null, "invalid null location request");
        Preconditions.checkArgument(pendingIntent != null, "invalid null pending intent");
        if (Compatibility.isChangeEnabled(TARGETED_PENDING_INTENT)) {
            Preconditions.checkArgument(pendingIntent.isTargetedToPackage(),
                    "pending intent must be targeted to a package");
        }

        try {
            mService.requestLocationUpdates(locationRequest, null, pendingIntent,
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
     * Removes location updates for the specified {@link LocationListener}. Following this call,
     * the listener will no longer receive location updates.
     *
     * @param listener listener that no longer needs location updates
     *
     * @throws IllegalArgumentException if listener is null
     */
    public void removeUpdates(@NonNull LocationListener listener) {
        Preconditions.checkArgument(listener != null, "invalid null listener");

        synchronized (mListeners) {
            LocationListenerTransport transport = mListeners.remove(listener);
            if (transport == null) {
                return;
            }
            transport.unregister();

            try {
                mService.removeUpdates(transport, null);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Removes location updates for the specified {@link PendingIntent}. Following this call, the
     * PendingIntent will no longer receive location updates.
     *
     * @param pendingIntent pending intent that no longer needs location updates
     *
     * @throws IllegalArgumentException if pendingIntent is null
     */
    public void removeUpdates(@NonNull PendingIntent pendingIntent) {
        Preconditions.checkArgument(pendingIntent != null, "invalid null pending intent");

        try {
            mService.removeUpdates(null, pendingIntent);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns a list of the names of all known location providers. All providers are returned,
     * including ones that are not permitted to be accessed by the calling activity or are currently
     * disabled.
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
     * Returns a list of the names of location providers. Only providers that the caller has
     * permission to access will be returned.
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
     * Returns a list of the names of providers that satisfy the given criteria. Only providers that
     * the caller has permission to access will be returned.
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
     */
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
            if (properties == null) {
                return null;
            }
            return new LocationProvider(provider, properties);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns true if the given package name matches a location provider package, and false
     * otherwise.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public boolean isProviderPackage(@NonNull String packageName) {
        try {
            return mService.isProviderPackage(packageName);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

    /**
     * Returns a list of packages associated with the given provider,
     * and an empty list if no package is associated with the provider.
     *
     * @hide
     */
    @TestApi
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    @Nullable
    public List<String> getProviderPackages(@NonNull String provider) {
        try {
            return mService.getProviderPackages(provider);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return Collections.emptyList();
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
            return mService.sendExtraCommand(provider, command, extras);
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
            boolean supportsSpeed, boolean supportsBearing, int powerRequirement, int accuracy) {
        Preconditions.checkArgument(provider != null, "invalid null provider");

        ProviderProperties properties = new ProviderProperties(requiresNetwork,
                requiresSatellite, requiresCell, hasMonetaryCost, supportsAltitude, supportsSpeed,
                supportsBearing, powerRequirement, accuracy);
        try {
            mService.addTestProvider(provider, properties, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
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
                    mContext.getAttributionTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Sets a new location for the given test provider. This location will be identiable as a mock
     * location to all clients via {@link Location#isFromMockProvider()}.
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

        if (Compatibility.isChangeEnabled(INCOMPLETE_LOCATION)) {
            Preconditions.checkArgument(location.isComplete(),
                    "incomplete location object, missing timestamp or accuracy?");
        } else {
            location.makeComplete();
        }

        try {
            mService.setTestProviderLocation(provider, location, mContext.getOpPackageName(),
                    mContext.getAttributionTag());
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
                    mContext.getAttributionTag());
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
     * Get the last list of {@link LocationRequest}s sent to the provider.
     *
     * @hide
     */
    @TestApi
    @NonNull
    public List<LocationRequest> getTestProviderCurrentRequests(String providerName) {
        Preconditions.checkArgument(providerName != null, "invalid null provider");
        try {
            return mService.getTestProviderCurrentRequests(providerName);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set a proximity alert for the location given by the position
     * (latitude, longitude) and the given radius.
     *
     * <p> When the device
     * detects that it has entered or exited the area surrounding the
     * location, the given PendingIntent will be used to create an Intent
     * to be fired.
     *
     * <p> The fired Intent will have a boolean extra added with key
     * {@link #KEY_PROXIMITY_ENTERING}. If the value is true, the device is
     * entering the proximity region; if false, it is exiting.
     *
     * <p> Due to the approximate nature of position estimation, if the
     * device passes through the given area briefly, it is possible
     * that no Intent will be fired.  Similarly, an Intent could be
     * fired if the device passes very close to the given area but
     * does not actually enter it.
     *
     * <p> After the number of milliseconds given by the expiration
     * parameter, the location manager will delete this proximity
     * alert and no longer monitor it.  A value of -1 indicates that
     * there should be no expiration time.
     *
     * <p> Internally, this method uses both {@link #NETWORK_PROVIDER}
     * and {@link #GPS_PROVIDER}.
     *
     * <p>Before API version 17, this method could be used with
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} or
     * {@link android.Manifest.permission#ACCESS_COARSE_LOCATION}.
     * From API version 17 and onwards, this method requires
     * {@link android.Manifest.permission#ACCESS_FINE_LOCATION} permission.
     *
     * @param latitude the latitude of the central point of the
     * alert region
     * @param longitude the longitude of the central point of the
     * alert region
     * @param radius the radius of the central point of the
     * alert region, in meters
     * @param expiration time for this proximity alert, in milliseconds,
     * or -1 to indicate no expiration
     * @param intent a PendingIntent that will be used to generate an Intent to
     * fire when entry to or exit from the alert region is detected
     *
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission is not present
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void addProximityAlert(double latitude, double longitude, float radius, long expiration,
            @NonNull PendingIntent intent) {
        Preconditions.checkArgument(intent != null, "invalid null pending intent");
        if (Compatibility.isChangeEnabled(TARGETED_PENDING_INTENT)) {
            Preconditions.checkArgument(intent.isTargetedToPackage(),
                    "pending intent must be targeted to a package");
        }
        if (expiration < 0) expiration = Long.MAX_VALUE;

        Geofence fence = Geofence.createCircle(latitude, longitude, radius);
        LocationRequest request = new LocationRequest().setExpireIn(expiration);
        try {
            mService.requestGeofence(request, fence, intent, mContext.getPackageName(),
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
        if (Compatibility.isChangeEnabled(TARGETED_PENDING_INTENT)) {
            Preconditions.checkArgument(intent.isTargetedToPackage(),
                    "pending intent must be targeted to a package");
        }

        try {
            mService.removeGeofence(null, intent, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add a geofence with the specified LocationRequest quality of service.
     *
     * <p> When the device
     * detects that it has entered or exited the area surrounding the
     * location, the given PendingIntent will be used to create an Intent
     * to be fired.
     *
     * <p> The fired Intent will have a boolean extra added with key
     * {@link #KEY_PROXIMITY_ENTERING}. If the value is true, the device is
     * entering the proximity region; if false, it is exiting.
     *
     * <p> The geofence engine fuses results from all location providers to
     * provide the best balance between accuracy and power. Applications
     * can choose the quality of service required using the
     * {@link LocationRequest} object. If it is null then a default,
     * low power geo-fencing implementation is used. It is possible to cross
     * a geo-fence without notification, but the system will do its best
     * to detect, using {@link LocationRequest} as a hint to trade-off
     * accuracy and power.
     *
     * <p> The power required by the geofence engine can depend on many factors,
     * such as quality and interval requested in {@link LocationRequest},
     * distance to nearest geofence and current device velocity.
     *
     * @param request quality of service required, null for default low power
     * @param fence a geographical description of the geofence area
     * @param intent pending intent to receive geofence updates
     *
     * @throws IllegalArgumentException if fence is null
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission is not present
     *
     * @hide
     */
    @RequiresPermission(anyOf = {ACCESS_COARSE_LOCATION, ACCESS_FINE_LOCATION})
    public void addGeofence(
            @NonNull LocationRequest request,
            @NonNull Geofence fence,
            @NonNull PendingIntent intent) {
        Preconditions.checkArgument(request != null, "invalid null location request");
        Preconditions.checkArgument(fence != null, "invalid null geofence");
        Preconditions.checkArgument(intent != null, "invalid null pending intent");
        if (Compatibility.isChangeEnabled(TARGETED_PENDING_INTENT)) {
            Preconditions.checkArgument(intent.isTargetedToPackage(),
                    "pending intent must be targeted to a package");
        }

        try {
            mService.requestGeofence(request, fence, intent, mContext.getPackageName(),
                    mContext.getAttributionTag());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove a single geofence.
     *
     * <p>This removes only the specified geofence associated with the
     * specified pending intent. All other geofences remain unchanged.
     *
     * @param fence a geofence previously passed to {@link #addGeofence}
     * @param intent a pending intent previously passed to {@link #addGeofence}
     *
     * @throws IllegalArgumentException if fence is null
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission is not present
     *
     * @hide
     */
    public void removeGeofence(@NonNull Geofence fence, @NonNull PendingIntent intent) {
        Preconditions.checkArgument(fence != null, "invalid null geofence");
        Preconditions.checkArgument(intent != null, "invalid null pending intent");
        if (Compatibility.isChangeEnabled(TARGETED_PENDING_INTENT)) {
            Preconditions.checkArgument(intent.isTargetedToPackage(),
                    "pending intent must be targeted to a package");
        }

        try {
            mService.removeGeofence(fence, intent, mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Remove all geofences registered to the specified pending intent.
     *
     * @param intent a pending intent previously passed to {@link #addGeofence}
     *
     * @throws IllegalArgumentException if intent is null
     * @throws SecurityException if {@link android.Manifest.permission#ACCESS_FINE_LOCATION}
     * permission is not present
     *
     * @hide
     */
    public void removeAllGeofences(@NonNull PendingIntent intent) {
        Preconditions.checkArgument(intent != null, "invalid null pending intent");
        if (Compatibility.isChangeEnabled(TARGETED_PENDING_INTENT)) {
            Preconditions.checkArgument(intent.isTargetedToPackage(),
                    "pending intent must be targeted to a package");
        }

        try {
            mService.removeGeofence(null, intent, mContext.getPackageName());
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
            long gnssCapabilities = mService.getGnssCapabilities();
            if (gnssCapabilities == GnssCapabilities.INVALID_CAPABILITIES) {
                gnssCapabilities = 0L;
            }
            return GnssCapabilities.of(gnssCapabilities);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the model year of the GNSS hardware and software build. More details, such as build
     * date, may be available in {@link #getGnssHardwareModelName()}. May return 0 if the model year
     * is less than 2016.
     */
    public int getGnssYearOfHardware() {
        try {
            return mService.getGnssYearOfHardware();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the Model Name (including Vendor and Hardware/Software Version) of the GNSS hardware
     * driver.
     *
     * <p> No device-specific serial number or ID is returned from this API.
     *
     * <p> Will return null when the GNSS hardware abstraction layer does not support providing
     * this value.
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
        if (Compatibility.isChangeEnabled(GPS_STATUS_USAGE)) {
            throw new UnsupportedOperationException(
                    "GpsStatus APIs not supported, please use GnssStatus APIs instead");
        }

        GnssStatus gnssStatus = mGnssStatusListenerManager.getGnssStatus();
        int ttff = mGnssStatusListenerManager.getTtff();
        if (gnssStatus != null) {
            if (status == null) {
                status = GpsStatus.create(gnssStatus, ttff);
            } else {
                status.setStatus(gnssStatus, ttff);
            }
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
        if (Compatibility.isChangeEnabled(GPS_STATUS_USAGE)) {
            throw new UnsupportedOperationException(
                    "GpsStatus APIs not supported, please use GnssStatus APIs instead");
        }

        try {
            return mGnssStatusListenerManager.addListener(listener, Runnable::run);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
        if (Compatibility.isChangeEnabled(GPS_STATUS_USAGE)) {
            throw new UnsupportedOperationException(
                    "GpsStatus APIs not supported, please use GnssStatus APIs instead");
        }

        try {
            mGnssStatusListenerManager.removeListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a GNSS status callback.
     *
     * @param callback GNSS status callback object to register
     * @return true if the listener was successfully added
     *
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     *
     * @deprecated Use {@link #registerGnssStatusCallback(GnssStatus.Callback, Handler)} or {@link
     * #registerGnssStatusCallback(Executor, GnssStatus.Callback)} instead.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssStatusCallback(@NonNull GnssStatus.Callback callback) {
        return registerGnssStatusCallback(Runnable::run, callback);
    }

    /**
     * Registers a GNSS status callback.
     *
     * @param callback GNSS status callback object to register
     * @param handler  a handler with a looper that the callback runs on
     * @return true if the listener was successfully added
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

        try {
            return mGnssStatusListenerManager.addListener(callback, handler);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a GNSS status callback.
     *
     * @param executor the executor that the callback runs on
     * @param callback GNSS status callback object to register
     * @return true if the listener was successfully added
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssStatusCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssStatus.Callback callback) {
        try {
            return mGnssStatusListenerManager.addListener(callback, executor);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a GNSS status callback.
     *
     * @param callback GNSS status callback object to remove
     */
    public void unregisterGnssStatusCallback(@NonNull GnssStatus.Callback callback) {
        try {
            mGnssStatusListenerManager.removeListener(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * @param listener a {@link OnNmeaMessageListener} object to register
     * @return true if the listener was successfully added
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     * @deprecated Use {@link #addNmeaListener(OnNmeaMessageListener, Handler)} or {@link
     * #addNmeaListener(Executor, OnNmeaMessageListener)} instead.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addNmeaListener(@NonNull OnNmeaMessageListener listener) {
        return addNmeaListener(Runnable::run, listener);
    }

    /**
     * Adds an NMEA listener.
     *
     * @param listener a {@link OnNmeaMessageListener} object to register
     * @param handler  a handler with the looper that the listener runs on.
     * @return true if the listener was successfully added
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
        try {
            return mGnssStatusListenerManager.addListener(listener, handler);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Adds an NMEA listener.
     *
     * @param listener a {@link OnNmeaMessageListener} object to register
     * @param executor the {@link Executor} that the listener runs on.
     * @return true if the listener was successfully added
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean addNmeaListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnNmeaMessageListener listener) {
        try {
            return mGnssStatusListenerManager.addListener(listener, executor);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes an NMEA listener.
     *
     * @param listener a {@link OnNmeaMessageListener} object to remove
     */
    public void removeNmeaListener(@NonNull OnNmeaMessageListener listener) {
        try {
            mGnssStatusListenerManager.removeListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * Registers a GPS Measurement callback which will run on a binder thread.
     *
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to register.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
     * @deprecated Use {@link
     * #registerGnssMeasurementsCallback(GnssMeasurementsEvent.Callback, Handler)} or {@link
     * #registerGnssMeasurementsCallback(Executor, GnssMeasurementsEvent.Callback)} instead.
     */
    @Deprecated
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssMeasurementsCallback(
            @NonNull GnssMeasurementsEvent.Callback callback) {
        return registerGnssMeasurementsCallback(Runnable::run, callback);
    }

    /**
     * Registers a GPS Measurement callback.
     *
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to register.
     * @param handler  the handler that the callback runs on.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
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
        try {
            return mGnssMeasurementsListenerManager.addListener(callback, handler);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a GPS Measurement callback.
     *
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to register.
     * @param executor the executor that the callback runs on.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssMeasurementsCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssMeasurementsEvent.Callback callback) {
        try {
            return mGnssMeasurementsListenerManager.addListener(callback, executor);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a GNSS Measurement callback.
     *
     * @param request  extra parameters to pass to GNSS measurement provider. For example, if {@link
     *                 GnssRequest#isFullTracking()} is true, GNSS chipset switches off duty
     *                 cycling.
     * @param executor the executor that the callback runs on.
     * @param callback a {@link GnssMeasurementsEvent.Callback} object to register.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
     * @throws IllegalArgumentException if request is null
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException        if the ACCESS_FINE_LOCATION permission is not present
     * @hide
     */
    @SystemApi
    @RequiresPermission(allOf = {ACCESS_FINE_LOCATION, LOCATION_HARDWARE})
    public boolean registerGnssMeasurementsCallback(
            @NonNull GnssRequest request,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssMeasurementsEvent.Callback callback) {
        Preconditions.checkArgument(request != null, "invalid null request");
        try {
            return mGnssMeasurementsListenerManager.addListener(request, callback, executor);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Injects GNSS measurement corrections into the GNSS chipset.
     *
     * @param measurementCorrections a {@link GnssMeasurementCorrections} object with the GNSS
     *     measurement corrections to be injected into the GNSS chipset.
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
            mService.injectGnssMeasurementCorrections(
                    measurementCorrections, mContext.getPackageName());
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
        try {
            mGnssMeasurementsListenerManager.removeListener(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a Gnss Antenna Info listener. Only expect results if
     * {@link GnssCapabilities#hasGnssAntennaInfo()} shows that antenna info is supported.
     *
     * @param executor the executor that the listener runs on.
     * @param listener a {@link GnssAntennaInfo.Listener} object to register.
     * @return {@code true} if the listener was added successfully, {@code false} otherwise.
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if listener is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerAntennaInfoListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssAntennaInfo.Listener listener) {
        try {
            return mGnssAntennaInfoListenerManager.addListener(listener, executor);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a GNSS Antenna Info listener.
     *
     * @param listener a {@link GnssAntennaInfo.Listener} object to remove.
     */
    public void unregisterAntennaInfoListener(@NonNull GnssAntennaInfo.Listener listener) {
        try {
            mGnssAntennaInfoListenerManager.removeListener(listener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * Registers a GNSS Navigation Message callback which will run on a binder thread.
     *
     * @param callback a {@link GnssNavigationMessage.Callback} object to register.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
     * @deprecated Use {@link
     * #registerGnssNavigationMessageCallback(GnssNavigationMessage.Callback, Handler)} or {@link
     * #registerGnssNavigationMessageCallback(Executor, GnssNavigationMessage.Callback)} instead.
     */
    @Deprecated
    public boolean registerGnssNavigationMessageCallback(
            @NonNull GnssNavigationMessage.Callback callback) {
        return registerGnssNavigationMessageCallback(Runnable::run, callback);
    }

    /**
     * Registers a GNSS Navigation Message callback.
     *
     * @param callback a {@link GnssNavigationMessage.Callback} object to register.
     * @param handler  the handler that the callback runs on.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
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

        try {
            return mGnssNavigationMessageListenerTransport.addListener(callback, handler);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a GNSS Navigation Message callback.
     *
     * @param callback a {@link GnssNavigationMessage.Callback} object to register.
     * @param executor the looper that the callback runs on.
     * @return {@code true} if the callback was added successfully, {@code false} otherwise.
     *
     * @throws IllegalArgumentException if executor is null
     * @throws IllegalArgumentException if callback is null
     * @throws SecurityException if the ACCESS_FINE_LOCATION permission is not present
     */
    @RequiresPermission(ACCESS_FINE_LOCATION)
    public boolean registerGnssNavigationMessageCallback(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull GnssNavigationMessage.Callback callback) {
        try {
            return mGnssNavigationMessageListenerTransport.addListener(callback, executor);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Unregisters a GNSS Navigation Message callback.
     *
     * @param callback a {@link GnssNavigationMessage.Callback} object to remove.
     */
    public void unregisterGnssNavigationMessageCallback(
            @NonNull GnssNavigationMessage.Callback callback) {
        try {
            mGnssNavigationMessageListenerTransport.removeListener(callback);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the batch size (in number of Location objects) that are supported by the batching
     * interface.
     *
     * @return Maximum number of location objects that can be returned
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public int getGnssBatchSize() {
        try {
            return mService.getGnssBatchSize(mContext.getPackageName());
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
     * @param wakeOnFifoFull True if the hardware batching should flush the locations in a
     *                       a callback to the listener, when it's internal buffer is full.  If
     *                       set to false, the oldest location information is, instead,
     *                       dropped when the buffer is full.
     * @param callback The listener on which to return the batched locations
     * @param handler The handler on which to process the callback
     *
     * @return True if batching was successfully started
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public boolean registerGnssBatchedLocationCallback(long periodNanos, boolean wakeOnFifoFull,
            @NonNull BatchedLocationCallback callback, @Nullable Handler handler) {
        if (handler == null) {
            handler = new Handler();
        }

        synchronized (mBatchedLocationCallbackManager) {
            try {
                if (mBatchedLocationCallbackManager.addListener(callback, handler)) {
                    return mService.startGnssBatch(periodNanos, wakeOnFifoFull,
                            mContext.getPackageName(), mContext.getAttributionTag());
                }
                return false;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Flush the batched GNSS locations.
     * All GNSS locations currently ready in the batch are returned via the callback sent in
     * startGnssBatch(), and the buffer containing the batched locations is cleared.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public void flushGnssBatch() {
        try {
            mService.flushGnssBatch(mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Stop batching locations. This API is primarily used when the AP is
     * asleep and the device can batch locations in the hardware.
     *
     * @param callback the specific callback class to remove from the transport layer
     *
     * @return True if batching was successfully started
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.LOCATION_HARDWARE)
    public boolean unregisterGnssBatchedLocationCallback(
            @NonNull BatchedLocationCallback callback) {
        synchronized (mBatchedLocationCallbackManager) {
            try {
                mBatchedLocationCallbackManager.removeListener(callback);
                mService.stopGnssBatch();
                return true;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private static class GetCurrentLocationTransport extends ILocationListener.Stub implements
            AlarmManager.OnAlarmListener {

        @GuardedBy("this")
        @Nullable
        private Executor mExecutor;

        @GuardedBy("this")
        @Nullable
        private Consumer<Location> mConsumer;

        @GuardedBy("this")
        @Nullable
        private AlarmManager mAlarmManager;

        @GuardedBy("this")
        @Nullable
        private ICancellationSignal mRemoteCancellationSignal;

        private GetCurrentLocationTransport(Executor executor, Consumer<Location> consumer) {
            Preconditions.checkArgument(executor != null, "illegal null executor");
            Preconditions.checkArgument(consumer != null, "illegal null consumer");
            mExecutor = executor;
            mConsumer = consumer;
            mAlarmManager = null;
            mRemoteCancellationSignal = null;
        }

        public synchronized void register(AlarmManager alarmManager,
                ICancellationSignal remoteCancellationSignal) {
            if (mConsumer == null) {
                return;
            }

            mAlarmManager = alarmManager;
            mAlarmManager.set(
                    ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + GET_CURRENT_LOCATION_MAX_TIMEOUT_MS,
                    "GetCurrentLocation",
                    this,
                    null);

            mRemoteCancellationSignal = remoteCancellationSignal;
        }

        public void cancel() {
            ICancellationSignal cancellationSignal;
            synchronized (this) {
                mExecutor = null;
                mConsumer = null;

                if (mAlarmManager != null) {
                    mAlarmManager.cancel(this);
                    mAlarmManager = null;
                }

                // ensure only one cancel event will go through
                cancellationSignal = mRemoteCancellationSignal;
                mRemoteCancellationSignal = null;
            }

            if (cancellationSignal != null) {
                try {
                    cancellationSignal.cancel();
                } catch (RemoteException e) {
                    // ignore
                }
            }
        }

        public void fail() {
            deliverResult(null);
        }

        @Override
        public void onAlarm() {
            synchronized (this) {
                // save ourselves a pointless x-process call to cancel the alarm
                mAlarmManager = null;
            }

            deliverResult(null);
        }

        @Override
        public void onLocationChanged(Location location) {
            synchronized (this) {
                // save ourselves a pointless x-process call to cancel the location request
                mRemoteCancellationSignal = null;
            }

            deliverResult(location);
        }

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {
            // in the event of the provider being disabled it is unlikely that we will get further
            // locations, so fail early so the client isn't left waiting hopelessly
            deliverResult(null);
        }

        @Override
        public void onRemoved() {
            deliverResult(null);
        }

        private synchronized void deliverResult(@Nullable Location location) {
            if (mExecutor == null) {
                return;
            }

            PooledRunnable runnable =
                    obtainRunnable(GetCurrentLocationTransport::acceptResult, this, location)
                            .recycleOnUse();
            try {
                mExecutor.execute(runnable);
            } catch (RejectedExecutionException e) {
                runnable.recycle();
                throw e;
            }
        }

        private void acceptResult(Location location) {
            Consumer<Location> consumer;
            synchronized (this) {
                if (mConsumer == null) {
                    return;
                }
                consumer = mConsumer;
                cancel();
            }

            consumer.accept(location);
        }
    }

    private class LocationListenerTransport extends ILocationListener.Stub {

        private final LocationListener mListener;
        @Nullable private volatile Executor mExecutor = null;

        private LocationListenerTransport(@NonNull LocationListener listener) {
            Preconditions.checkArgument(listener != null, "invalid null listener");
            mListener = listener;
        }

        public LocationListener getKey() {
            return mListener;
        }

        public void register(@NonNull Executor executor) {
            Preconditions.checkArgument(executor != null, "invalid null executor");
            mExecutor = executor;
        }

        public void unregister() {
            mExecutor = null;
        }

        @Override
        public void onLocationChanged(Location location) {
            Executor currentExecutor = mExecutor;
            if (currentExecutor == null) {
                return;
            }

            PooledRunnable runnable =
                    obtainRunnable(LocationListenerTransport::acceptLocation, this, currentExecutor,
                            location).recycleOnUse();
            try {
                currentExecutor.execute(runnable);
            } catch (RejectedExecutionException e) {
                runnable.recycle();
                locationCallbackFinished();
                throw e;
            }
        }

        private void acceptLocation(Executor currentExecutor, Location location) {
            try {
                if (currentExecutor != mExecutor) {
                    return;
                }

                // we may be under the binder identity if a direct executor is used
                long identity = Binder.clearCallingIdentity();
                try {
                    mListener.onLocationChanged(location);
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } finally {
                locationCallbackFinished();
            }
        }

        @Override
        public void onProviderEnabled(String provider) {
            Executor currentExecutor = mExecutor;
            if (currentExecutor == null) {
                return;
            }

            PooledRunnable runnable =
                    obtainRunnable(LocationListenerTransport::acceptProviderChange, this,
                            currentExecutor, provider, true).recycleOnUse();
            try {
                currentExecutor.execute(runnable);
            } catch (RejectedExecutionException e) {
                runnable.recycle();
                locationCallbackFinished();
                throw e;
            }
        }

        @Override
        public void onProviderDisabled(String provider) {
            Executor currentExecutor = mExecutor;
            if (currentExecutor == null) {
                return;
            }

            PooledRunnable runnable =
                    obtainRunnable(LocationListenerTransport::acceptProviderChange, this,
                            currentExecutor, provider, false).recycleOnUse();
            try {
                currentExecutor.execute(runnable);
            } catch (RejectedExecutionException e) {
                runnable.recycle();
                locationCallbackFinished();
                throw e;
            }
        }

        private void acceptProviderChange(Executor currentExecutor, String provider,
                boolean enabled) {
            try {
                if (currentExecutor != mExecutor) {
                    return;
                }

                // we may be under the binder identity if a direct executor is used
                long identity = Binder.clearCallingIdentity();
                try {
                    if (enabled) {
                        mListener.onProviderEnabled(provider);
                    } else {
                        mListener.onProviderDisabled(provider);
                    }
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            } finally {
                locationCallbackFinished();
            }
        }

        @Override
        public void onRemoved() {
            // TODO: onRemoved is necessary to GC hanging listeners, but introduces some interesting
            //  broken edge cases. luckily these edge cases are quite unlikely. consider the
            //  following interleaving for instance:
            //    1) client adds single shot location request (A)
            //    2) client gets removal callback, and schedules it for execution
            //    3) client replaces single shot request with a different location request (B)
            //    4) prior removal callback is executed, removing location request (B) incorrectly
            //  what's needed is a way to identify which listener a callback belongs to. currently
            //  we reuse the same transport object for the same listeners (so that we don't leak
            //  transport objects on the server side). there seem to be two solutions:
            //    1) when reregistering a request, first unregister the current transport, then
            //       register with a new transport object (never reuse transport objects) - the
            //       downside is that this breaks the server's knowledge that the request is the
            //       same object, and thus breaks optimizations such as reusing the same transport
            //       state.
            //    2) pass some other type of marker in addition to the transport (for example an
            //       incrementing integer representing the transport "version"), and pass this
            //       marker back into callbacks so that each callback knows which transport
            //       "version" it belongs to and can not execute itself if the version does not
            //       match.
            //  (1) seems like the preferred solution as it's simpler to implement and the above
            //  mentioned server optimizations are not terribly important (they can be bypassed by
            //  clients that use a new listener every time anyways).

            Executor currentExecutor = mExecutor;
            if (currentExecutor == null) {
                // we've already been unregistered, no work to do anyways
                return;
            }

            // must be executed on the same executor so callback execution cannot be reordered
            currentExecutor.execute(() -> {
                if (currentExecutor != mExecutor) {
                    return;
                }

                unregister();
                synchronized (mListeners) {
                    mListeners.remove(mListener, this);
                }
            });
        }

        private void locationCallbackFinished() {
            try {
                mService.locationCallbackFinished(this);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private static class NmeaAdapter extends GnssStatus.Callback implements OnNmeaMessageListener {

        private final OnNmeaMessageListener mListener;

        private NmeaAdapter(OnNmeaMessageListener listener) {
            mListener = listener;
        }

        @Override
        public void onNmeaMessage(String message, long timestamp) {
            mListener.onNmeaMessage(message, timestamp);
        }
    }

    private class GnssStatusListenerManager extends
            AbstractListenerManager<Void, GnssStatus.Callback> {
        @Nullable
        private IGnssStatusListener mListenerTransport;

        @Nullable
        private volatile GnssStatus mGnssStatus;
        private volatile int mTtff;

        public GnssStatus getGnssStatus() {
            return mGnssStatus;
        }

        public int getTtff() {
            return mTtff;
        }

        public boolean addListener(@NonNull GpsStatus.Listener listener, @NonNull Executor executor)
                throws RemoteException {
            return addInternal(null, listener, executor);
        }

        public boolean addListener(@NonNull OnNmeaMessageListener listener,
                @NonNull Handler handler)
                throws RemoteException {
            return addInternal(null, listener, handler);
        }

        public boolean addListener(@NonNull OnNmeaMessageListener listener,
                @NonNull Executor executor)
                throws RemoteException {
            return addInternal(null, listener, executor);
        }

        @Override
        protected GnssStatus.Callback convertKey(Object listener) {
            if (listener instanceof GnssStatus.Callback) {
                return (GnssStatus.Callback) listener;
            } else if (listener instanceof GpsStatus.Listener) {
                return new GnssStatus.Callback() {
                    private final GpsStatus.Listener mGpsListener = (GpsStatus.Listener) listener;

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
                };
            } else if (listener instanceof OnNmeaMessageListener) {
                return new NmeaAdapter((OnNmeaMessageListener) listener);
            } else {
                throw new IllegalStateException();
            }
        }

        @Override
        protected boolean registerService(Void ignored) throws RemoteException {
            Preconditions.checkState(mListenerTransport == null);

            GnssStatusListener transport = new GnssStatusListener();
            if (mService.registerGnssStatusCallback(transport, mContext.getPackageName(),
                    mContext.getAttributionTag())) {
                mListenerTransport = transport;
                return true;
            } else {
                return false;
            }
        }

        @Override
        protected void unregisterService() throws RemoteException {
            Preconditions.checkState(mListenerTransport != null);

            mService.unregisterGnssStatusCallback(mListenerTransport);
            mListenerTransport = null;
        }

        private class GnssStatusListener extends IGnssStatusListener.Stub {
            @Override
            public void onGnssStarted() {
                execute(GnssStatus.Callback::onStarted);
            }

            @Override
            public void onGnssStopped() {
                execute(GnssStatus.Callback::onStopped);
            }

            @Override
            public void onFirstFix(int ttff) {
                mTtff = ttff;
                execute((callback) -> callback.onFirstFix(ttff));
            }

            @Override
            public void onSvStatusChanged(int svCount, int[] svidWithFlags, float[] cn0s,
                    float[] elevations, float[] azimuths, float[] carrierFreqs,
                    float[] basebandCn0s) {
                GnssStatus localStatus = GnssStatus.wrap(svCount, svidWithFlags, cn0s,
                        elevations, azimuths, carrierFreqs, basebandCn0s);
                mGnssStatus = localStatus;
                execute((callback) -> callback.onSatelliteStatusChanged(localStatus));
            }

            @Override
            public void onNmeaReceived(long timestamp, String nmea) {
                execute((callback) -> {
                    if (callback instanceof NmeaAdapter) {
                        ((NmeaAdapter) callback).onNmeaMessage(nmea, timestamp);
                    }
                });
            }
        }
    }

    private class GnssMeasurementsListenerManager extends
            AbstractListenerManager<GnssRequest, GnssMeasurementsEvent.Callback> {

        @Nullable
        private IGnssMeasurementsListener mListenerTransport;

        @Override
        protected boolean registerService(GnssRequest request) throws RemoteException {
            Preconditions.checkState(mListenerTransport == null);

            GnssMeasurementsListener transport = new GnssMeasurementsListener();
            if (mService.addGnssMeasurementsListener(request, transport, mContext.getPackageName(),
                    mContext.getAttributionTag())) {
                mListenerTransport = transport;
                return true;
            } else {
                return false;
            }
        }

        @Override
        protected void unregisterService() throws RemoteException {
            Preconditions.checkState(mListenerTransport != null);

            mService.removeGnssMeasurementsListener(mListenerTransport);
            mListenerTransport = null;
        }

        @Override
        @Nullable
        protected GnssRequest merge(@NonNull GnssRequest[] requests) {
            Preconditions.checkArgument(requests.length > 0);
            for (GnssRequest request : requests) {
                if (request.isFullTracking()) {
                    return request;
                }
            }
            return requests[0];
        }

        private class GnssMeasurementsListener extends IGnssMeasurementsListener.Stub {
            @Override
            public void onGnssMeasurementsReceived(final GnssMeasurementsEvent event) {
                execute((callback) -> callback.onGnssMeasurementsReceived(event));
            }

            @Override
            public void onStatusChanged(int status) {
                execute((callback) -> callback.onStatusChanged(status));
            }
        }
    }

    private class GnssNavigationMessageListenerManager extends
            AbstractListenerManager<Void, GnssNavigationMessage.Callback> {

        @Nullable
        private IGnssNavigationMessageListener mListenerTransport;

        @Override
        protected boolean registerService(Void ignored) throws RemoteException {
            Preconditions.checkState(mListenerTransport == null);

            GnssNavigationMessageListener transport = new GnssNavigationMessageListener();
            if (mService.addGnssNavigationMessageListener(transport, mContext.getPackageName(),
                    mContext.getAttributionTag())) {
                mListenerTransport = transport;
                return true;
            } else {
                return false;
            }
        }

        @Override
        protected void unregisterService() throws RemoteException {
            Preconditions.checkState(mListenerTransport != null);

            mService.removeGnssNavigationMessageListener(mListenerTransport);
            mListenerTransport = null;
        }

        private class GnssNavigationMessageListener extends IGnssNavigationMessageListener.Stub {
            @Override
            public void onGnssNavigationMessageReceived(GnssNavigationMessage event) {
                execute((listener) -> listener.onGnssNavigationMessageReceived(event));
            }

            @Override
            public void onStatusChanged(int status) {
                execute((listener) -> listener.onStatusChanged(status));
            }
        }
    }

    private class GnssAntennaInfoListenerManager extends
            AbstractListenerManager<Void, GnssAntennaInfo.Listener> {

        @Nullable
        private IGnssAntennaInfoListener mListenerTransport;

        @Override
        protected boolean registerService(Void ignored) throws RemoteException {
            Preconditions.checkState(mListenerTransport == null);

            GnssAntennaInfoListener transport = new GnssAntennaInfoListener();
            if (mService.addGnssAntennaInfoListener(transport, mContext.getPackageName(),
                    mContext.getAttributionTag())) {
                mListenerTransport = transport;
                return true;
            } else {
                return false;
            }
        }

        @Override
        protected void unregisterService() throws RemoteException {
            Preconditions.checkState(mListenerTransport != null);

            mService.removeGnssAntennaInfoListener(mListenerTransport);
            mListenerTransport = null;
        }

        private class GnssAntennaInfoListener extends IGnssAntennaInfoListener.Stub {
            @Override
            public void onGnssAntennaInfoReceived(final List<GnssAntennaInfo> gnssAntennaInfos) {
                execute((callback) -> callback.onGnssAntennaInfoReceived(gnssAntennaInfos));
            }
        }

    }

    private class BatchedLocationCallbackManager extends
            AbstractListenerManager<Void, BatchedLocationCallback> {

        @Nullable
        private IBatchedLocationCallback mListenerTransport;

        @Override
        protected boolean registerService(Void ignored) throws RemoteException {
            Preconditions.checkState(mListenerTransport == null);

            BatchedLocationCallback transport = new BatchedLocationCallback();
            if (mService.addGnssBatchingCallback(transport, mContext.getPackageName(),
                    mContext.getAttributionTag())) {
                mListenerTransport = transport;
                return true;
            } else {
                return false;
            }
        }

        @Override
        protected void unregisterService() throws RemoteException {
            Preconditions.checkState(mListenerTransport != null);

            mService.removeGnssBatchingCallback();
            mListenerTransport = null;
        }

        private class BatchedLocationCallback extends IBatchedLocationCallback.Stub {
            @Override
            public void onLocationBatch(List<Location> locations) {
                execute((listener) -> listener.onLocationBatch(locations));
            }

        }
    }

    /**
     * @hide
     */
    public static final String CACHE_KEY_LOCATION_ENABLED_PROPERTY =
            "cache_key.location_enabled";

    /**
     * @hide
     */
    public static void invalidateLocalLocationEnabledCaches() {
        PropertyInvalidatedCache.invalidateCache(CACHE_KEY_LOCATION_ENABLED_PROPERTY);
    }

    /**
     * @hide
     */
    public void disableLocalLocationEnabledCaches() {
        synchronized (mLock) {
            mLocationEnabledCache = null;
        }
    }
}
