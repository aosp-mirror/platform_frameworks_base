package com.android.systemui.media.nearby

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.media.INearbyMediaDevicesProvider
import com.android.systemui.shared.media.INearbyMediaDevicesService
import com.android.systemui.shared.media.INearbyMediaDevicesUpdateCallback
import com.android.systemui.shared.media.INearbyMediaDevicesUpdateCallback.RANGE_LONG
import com.android.systemui.shared.media.INearbyMediaDevicesUpdateCallback.RANGE_WITHIN_REACH
import com.android.systemui.shared.media.NearbyDevice
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

@SmallTest
class NearbyMediaDevicesServiceTest : SysuiTestCase() {

    private lateinit var service: NearbyMediaDevicesService
    private lateinit var binderInterface: INearbyMediaDevicesService

    @Before
    fun setUp() {
        service = NearbyMediaDevicesService()
        binderInterface = INearbyMediaDevicesService.Stub.asInterface(service.onBind(null))
    }

    @Test
    fun getCurrentNearbyDevices_noProviderRegistered_returnsEmptyList() {
        assertThat(service.getCurrentNearbyDevices()).isEmpty()
    }

    @Test
    fun getCurrentNearbyDevices_providerRegistered_returnsProviderInfo() {
        val nearbyDevice1 = NearbyDevice("routeId1", RANGE_LONG)
        val nearbyDevice2 = NearbyDevice("routeId2", RANGE_WITHIN_REACH)
        val provider = object : INearbyMediaDevicesProvider.Stub() {
            override fun getCurrentNearbyDevices(): List<NearbyDevice> {
                return listOf(nearbyDevice1, nearbyDevice2)
            }

            override fun registerNearbyDevicesCallback(
                callback: INearbyMediaDevicesUpdateCallback?
            ) {}
            override fun unregisterNearbyDevicesCallback(
                callback: INearbyMediaDevicesUpdateCallback?
            ) {}
        }
        binderInterface.registerProvider(provider)

        val returnedNearbyDevices = service.getCurrentNearbyDevices()

        assertThat(returnedNearbyDevices).isEqualTo(listOf(nearbyDevice1, nearbyDevice2))
    }

    @Test
    fun registerNearbyDevicesCallback_noProviderRegistered_noCrash() {
        // No assert, just needs no crash
        service.registerNearbyDevicesCallback(object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun nearbyDeviceUpdate(routeId: String?, rangeZone: Int) {}
        })
    }

    @Test
    fun registerNearbyDevicesCallback_providerRegistered_providerReceivesCallback() {
        val provider = object : INearbyMediaDevicesProvider.Stub() {
            var registeredCallback: INearbyMediaDevicesUpdateCallback? = null
            override fun getCurrentNearbyDevices(): List<NearbyDevice> = listOf()

            override fun registerNearbyDevicesCallback(
                callback: INearbyMediaDevicesUpdateCallback?
            ) {
                registeredCallback = callback
            }

            override fun unregisterNearbyDevicesCallback(
                callback: INearbyMediaDevicesUpdateCallback?
            ) {}
        }
        binderInterface.registerProvider(provider)

        val callback = object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun nearbyDeviceUpdate(routeId: String?, rangeZone: Int) {}
        }

        service.registerNearbyDevicesCallback(callback)

        assertThat(provider.registeredCallback).isEqualTo(callback)
    }

    @Test
    fun unregisterNearbyDevicesCallback_noProviderRegistered_noCrash() {
        // No assert, just needs no crash
        service.unregisterNearbyDevicesCallback(object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun nearbyDeviceUpdate(routeId: String?, rangeZone: Int) {}
        })
    }

    @Test
    fun unregisterNearbyDevicesCallback_providerRegistered_providerReceivesCallback() {
        val provider = object : INearbyMediaDevicesProvider.Stub() {
            var unregisteredCallback: INearbyMediaDevicesUpdateCallback? = null
            override fun getCurrentNearbyDevices(): List<NearbyDevice> = listOf()

            override fun registerNearbyDevicesCallback(
                callback: INearbyMediaDevicesUpdateCallback?
            ) {}

            override fun unregisterNearbyDevicesCallback(
                callback: INearbyMediaDevicesUpdateCallback?
            ) {
                unregisteredCallback = callback
            }
        }
        binderInterface.registerProvider(provider)

        val callback = object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun nearbyDeviceUpdate(routeId: String?, rangeZone: Int) {}
        }

        service.unregisterNearbyDevicesCallback(callback)

        assertThat(provider.unregisteredCallback).isEqualTo(callback)
    }
}
