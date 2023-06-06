package com.android.systemui.media.nearby

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.dagger.NearbyMediaDevicesLog
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import javax.inject.Inject

/** Log messages for [NearbyMediaDevicesManager]. */
@SysUISingleton
class NearbyMediaDevicesLogger @Inject constructor(
    @NearbyMediaDevicesLog private val buffer: LogBuffer
) {
    /**
     * Log that a new provider was registered.
     *
     * @param numProviders the total number of providers that are currently registered.
     */
    fun logProviderRegistered(numProviders: Int) = buffer.log(
        TAG,
        LogLevel.DEBUG,
        { int1 = numProviders },
        { "Provider registered; total providers = $int1" }
    )

    /**
     * Log that a new provider was unregistered.
     *
     * @param numProviders the total number of providers that are currently registered.
     */
    fun logProviderUnregistered(numProviders: Int) = buffer.log(
        TAG,
        LogLevel.DEBUG,
        { int1 = numProviders },
        { "Provider unregistered; total providers = $int1" }
    )

    /**
     * Log that a provider's binder has died.
     *
     * @param numProviders the total number of providers that are currently registered.
     */
    fun logProviderBinderDied(numProviders: Int) = buffer.log(
        TAG,
        LogLevel.DEBUG,
        { int1 = numProviders },
        { "Provider binder died; total providers = $int1" }
    )
}

private const val TAG = "NearbyMediaDevices"
