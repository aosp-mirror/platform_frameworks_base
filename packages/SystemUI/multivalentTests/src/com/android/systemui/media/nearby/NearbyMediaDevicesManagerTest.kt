package com.android.systemui.media.nearby

import android.media.INearbyMediaDevicesProvider
import android.media.INearbyMediaDevicesUpdateCallback
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import android.media.NearbyDevice
import android.os.IBinder
import com.android.systemui.statusbar.CommandQueue
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class NearbyMediaDevicesManagerTest : SysuiTestCase() {

    private lateinit var manager: NearbyMediaDevicesManager
    @Mock
    private lateinit var logger: NearbyMediaDevicesLogger
    @Mock
    private lateinit var commandQueue: CommandQueue
    private lateinit var commandQueueCallbacks: CommandQueue.Callbacks

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        manager = NearbyMediaDevicesManager(commandQueue, logger)
        manager.start()

        val callbackCaptor = ArgumentCaptor.forClass(CommandQueue.Callbacks::class.java)
        verify(commandQueue).addCallback(callbackCaptor.capture())
        commandQueueCallbacks = callbackCaptor.value!!
    }

    @Test
    fun registerNearbyDevicesCallback_noProviderRegistered_noCrash() {
        // No assert, just needs no crash
        manager.registerNearbyDevicesCallback(object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun onDevicesUpdated(nearbyDevices: List<NearbyDevice>) {}
        })
    }

    @Test
    fun registerNearbyDevicesCallback_providerRegistered_providerReceivesCallback() {
        val provider = TestProvider()
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider)

        val callback = object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun onDevicesUpdated(nearbyDevices: List<NearbyDevice>) {}
        }

        manager.registerNearbyDevicesCallback(callback)

        assertThat(provider.lastRegisteredCallback).isEqualTo(callback)
    }

    @Test
    fun registerNearbyDevicesCallback_multipleProviders_allProvidersReceiveCallback() {
        val provider1 = TestProvider()
        val provider2 = TestProvider()
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider1)
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider2)

        val callback = object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun onDevicesUpdated(nearbyDevices: List<NearbyDevice>) {}
        }

        manager.registerNearbyDevicesCallback(callback)

        assertThat(provider1.lastRegisteredCallback).isEqualTo(callback)
        assertThat(provider2.lastRegisteredCallback).isEqualTo(callback)
    }

    @Test
    fun unregisterNearbyDevicesCallback_noProviderRegistered_noCrash() {
        // No assert, just needs no crash
        manager.unregisterNearbyDevicesCallback(object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun onDevicesUpdated(nearbyDevices: List<NearbyDevice>) {}
        })
    }

    @Test
    fun unregisterNearbyDevicesCallback_providerRegistered_providerReceivesCallback() {
        val provider = TestProvider()
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider)

        val callback = object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun onDevicesUpdated(nearbyDevices: List<NearbyDevice>) {}
        }

        manager.unregisterNearbyDevicesCallback(callback)

        assertThat(provider.lastUnregisteredCallback).isEqualTo(callback)
    }

    @Test
    fun unregisterNearbyDevicesCallback_multipleProviders_allProvidersReceiveCallback() {
        val provider1 = TestProvider()
        val provider2 = TestProvider()
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider1)
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider2)

        val callback = object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun onDevicesUpdated(nearbyDevices: List<NearbyDevice>) {}
        }

        manager.unregisterNearbyDevicesCallback(callback)

        assertThat(provider1.lastUnregisteredCallback).isEqualTo(callback)
        assertThat(provider2.lastUnregisteredCallback).isEqualTo(callback)
    }

    @Test
    fun newProviderRegisteredAfterCallbacksRegistered_providerGetsPreviouslyRegisteredCallbacks() {
        // Start off with an existing provider and callback
        val provider1 = TestProvider()
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider1)
        val callback = object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun onDevicesUpdated(nearbyDevices: List<NearbyDevice>) {}
        }
        manager.registerNearbyDevicesCallback(callback)

        // Add a new provider
        val provider2 = TestProvider()
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider2)

        // Verify the new provider received the previously-registered callbacks
        assertThat(provider2.lastRegisteredCallback).isEqualTo(callback)
    }

    @Test
    fun providerUnregistered_doesNotReceiveNewCallback() {
        val provider = TestProvider()
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider)
        commandQueueCallbacks.unregisterNearbyMediaDevicesProvider(provider)

        val callback = object : INearbyMediaDevicesUpdateCallback.Stub() {
            override fun onDevicesUpdated(nearbyDevices: List<NearbyDevice>) {}
        }
        manager.registerNearbyDevicesCallback(callback)

        assertThat(provider.lastRegisteredCallback).isEqualTo(null)
    }

    @Test
    fun providerRegistered_isLogged() {
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(TestProvider())

        verify(logger).logProviderRegistered(numProviders = 1)
    }

    @Test
    fun providerRegisteredTwice_onlyLoggedOnce() {
        val provider = TestProvider()

        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider)
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider)

        verify(logger, times(1)).logProviderRegistered(numProviders = 1)
    }

    @Test
    fun multipleProvidersRegistered_isLogged() {
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(TestProvider())
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(TestProvider())
        reset(logger)

        commandQueueCallbacks.registerNearbyMediaDevicesProvider(TestProvider())

        verify(logger).logProviderRegistered(numProviders = 3)
    }

    @Test
    fun providerUnregistered_isLogged() {
        val provider = TestProvider()
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider)

        commandQueueCallbacks.unregisterNearbyMediaDevicesProvider(provider)

        verify(logger).logProviderUnregistered(numProviders = 0)
    }

    @Test
    fun multipleProvidersRegisteredThenUnregistered_isLogged() {
        val provider = TestProvider()
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider)
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(TestProvider())
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(TestProvider())

        commandQueueCallbacks.unregisterNearbyMediaDevicesProvider(provider)

        verify(logger).logProviderUnregistered(numProviders = 2)
    }

    @Test
    fun providerUnregisteredButNeverRegistered_notLogged() {
        commandQueueCallbacks.unregisterNearbyMediaDevicesProvider(TestProvider())

        verify(logger, never()).logProviderRegistered(anyInt())
    }

    @Test
    fun providerBinderDied_isLogged() {
        val provider = TestProvider()
        commandQueueCallbacks.registerNearbyMediaDevicesProvider(provider)

        provider.deathRecipient!!.binderDied(provider)

        verify(logger).logProviderBinderDied(numProviders = 0)
    }

    private class TestProvider : INearbyMediaDevicesProvider.Stub() {
        var lastRegisteredCallback: INearbyMediaDevicesUpdateCallback? = null
        var lastUnregisteredCallback: INearbyMediaDevicesUpdateCallback? = null
        var deathRecipient: IBinder.DeathRecipient? = null

        override fun registerNearbyDevicesCallback(
            callback: INearbyMediaDevicesUpdateCallback
        ) {
            lastRegisteredCallback = callback
        }

        override fun unregisterNearbyDevicesCallback(
            callback: INearbyMediaDevicesUpdateCallback
        ) {
            lastUnregisteredCallback = callback
        }

        override fun asBinder(): IBinder {
            return this
        }

        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {
            deathRecipient = recipient
        }
    }
}
