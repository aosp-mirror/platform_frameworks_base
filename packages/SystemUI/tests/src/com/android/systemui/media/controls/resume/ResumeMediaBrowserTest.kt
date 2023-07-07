/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.media.controls.resume

import android.content.ComponentName
import android.content.Context
import android.media.MediaDescription
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSession
import android.service.media.MediaBrowserService
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.mockito.mock
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

private const val PACKAGE_NAME = "package"
private const val CLASS_NAME = "class"
private const val TITLE = "song title"
private const val MEDIA_ID = "media ID"
private const val ROOT = "media browser root"

private fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()

private fun <T> eq(value: T): T = Mockito.eq(value) ?: value

private fun <T> any(): T = Mockito.any<T>()

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
public class ResumeMediaBrowserTest : SysuiTestCase() {

    private lateinit var resumeBrowser: TestableResumeMediaBrowser
    private val component = ComponentName(PACKAGE_NAME, CLASS_NAME)
    private val description =
        MediaDescription.Builder().setTitle(TITLE).setMediaId(MEDIA_ID).build()

    @Mock lateinit var callback: ResumeMediaBrowser.Callback
    @Mock lateinit var listener: MediaResumeListener
    @Mock lateinit var service: MediaBrowserService
    @Mock lateinit var logger: ResumeMediaBrowserLogger
    @Mock lateinit var browserFactory: MediaBrowserFactory
    @Mock lateinit var browser: MediaBrowser
    @Mock lateinit var token: MediaSession.Token
    @Mock lateinit var mediaController: MediaController
    @Mock lateinit var transportControls: MediaController.TransportControls

    @Captor lateinit var connectionCallback: ArgumentCaptor<MediaBrowser.ConnectionCallback>
    @Captor lateinit var subscriptionCallback: ArgumentCaptor<MediaBrowser.SubscriptionCallback>
    @Captor lateinit var mediaControllerCallback: ArgumentCaptor<MediaController.Callback>

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(browserFactory.create(any(), capture(connectionCallback), any()))
            .thenReturn(browser)

        whenever(mediaController.transportControls).thenReturn(transportControls)
        whenever(mediaController.sessionToken).thenReturn(token)

        resumeBrowser =
            TestableResumeMediaBrowser(
                context,
                callback,
                component,
                browserFactory,
                logger,
                mediaController
            )
    }

    @Test
    fun testConnection_connectionFails_callsOnError() {
        // When testConnection cannot connect to the service
        setupBrowserFailed()
        resumeBrowser.testConnection()

        // Then it calls onError and disconnects
        verify(callback).onError()
        verify(browser).disconnect()
    }

    @Test
    fun testConnection_connects_onConnected() {
        // When testConnection can connect to the service
        setupBrowserConnection()
        resumeBrowser.testConnection()

        // Then it calls onConnected
        verify(callback).onConnected()
    }

    @Test
    fun testConnection_noValidMedia_error() {
        // When testConnection can connect to the service, and does not find valid media
        setupBrowserConnectionNoResults()
        resumeBrowser.testConnection()

        // Then it calls onError and disconnects
        verify(callback).onError()
        verify(browser).disconnect()
    }

    @Test
    fun testConnection_hasValidMedia_addTrack() {
        // When testConnection can connect to the service, and finds valid media
        setupBrowserConnectionValidMedia()
        resumeBrowser.testConnection()

        // Then it calls addTrack
        verify(callback).onConnected()
        verify(callback).addTrack(eq(description), eq(component), eq(resumeBrowser))
    }

    @Test
    fun testConnection_thenSessionDestroyed_disconnects() {
        // When testConnection is called and we connect successfully
        setupBrowserConnection()
        resumeBrowser.testConnection()
        verify(mediaController).registerCallback(mediaControllerCallback.capture())
        reset(browser)

        // And a sessionDestroyed event is triggered
        mediaControllerCallback.value.onSessionDestroyed()

        // Then we disconnect the browser and unregister the callback
        verify(browser).disconnect()
        verify(mediaController).unregisterCallback(mediaControllerCallback.value)
    }

    @Test
    fun testConnection_calledTwice_oldBrowserDisconnected() {
        val oldBrowser = mock<MediaBrowser>()
        whenever(browserFactory.create(any(), any(), any())).thenReturn(oldBrowser)

        // When testConnection can connect to the service
        setupBrowserConnection()
        resumeBrowser.testConnection()

        // And testConnection is called again
        val newBrowser = mock<MediaBrowser>()
        whenever(browserFactory.create(any(), any(), any())).thenReturn(newBrowser)
        resumeBrowser.testConnection()

        // Then we disconnect the old browser
        verify(oldBrowser).disconnect()
    }

    @Test
    fun testFindRecentMedia_connectionFails_error() {
        // When findRecentMedia is called and we cannot connect
        setupBrowserFailed()
        resumeBrowser.findRecentMedia()

        // Then it calls onError and disconnects
        verify(callback).onError()
        verify(browser).disconnect()
    }

    @Test
    fun testFindRecentMedia_noRoot_error() {
        // When findRecentMedia is called and does not get a valid root
        setupBrowserConnection()
        whenever(browser.getRoot()).thenReturn(null)
        resumeBrowser.findRecentMedia()

        // Then it calls onError and disconnects
        verify(callback).onError()
        verify(browser).disconnect()
    }

    @Test
    fun testFindRecentMedia_connects_onConnected() {
        // When findRecentMedia is called and we connect
        setupBrowserConnection()
        resumeBrowser.findRecentMedia()

        // Then it calls onConnected
        verify(callback).onConnected()
    }

    @Test
    fun testFindRecentMedia_thenSessionDestroyed_disconnects() {
        // When findRecentMedia is called and we connect successfully
        setupBrowserConnection()
        resumeBrowser.findRecentMedia()
        verify(mediaController).registerCallback(mediaControllerCallback.capture())
        reset(browser)

        // And a sessionDestroyed event is triggered
        mediaControllerCallback.value.onSessionDestroyed()

        // Then we disconnect the browser and unregister the callback
        verify(browser).disconnect()
        verify(mediaController).unregisterCallback(mediaControllerCallback.value)
    }

    @Test
    fun testFindRecentMedia_calledTwice_oldBrowserDisconnected() {
        val oldBrowser = mock<MediaBrowser>()
        whenever(browserFactory.create(any(), any(), any())).thenReturn(oldBrowser)

        // When findRecentMedia is called and we connect
        setupBrowserConnection()
        resumeBrowser.findRecentMedia()

        // And findRecentMedia is called again
        val newBrowser = mock<MediaBrowser>()
        whenever(browserFactory.create(any(), any(), any())).thenReturn(newBrowser)
        resumeBrowser.findRecentMedia()

        // Then we disconnect the old browser
        verify(oldBrowser).disconnect()
    }

    @Test
    fun testFindRecentMedia_noChildren_error() {
        // When findRecentMedia is called and we connect, but do not get any results
        setupBrowserConnectionNoResults()
        resumeBrowser.findRecentMedia()

        // Then it calls onError and disconnects
        verify(callback).onError()
        verify(browser).disconnect()
    }

    @Test
    fun testFindRecentMedia_notPlayable_error() {
        // When findRecentMedia is called and we connect, but do not get a playable child
        setupBrowserConnectionNotPlayable()
        resumeBrowser.findRecentMedia()

        // Then it calls onError and disconnects
        verify(callback).onError()
        verify(browser).disconnect()
    }

    @Test
    fun testFindRecentMedia_hasValidMedia_addTrack() {
        // When findRecentMedia is called and we can connect and get playable media
        setupBrowserConnectionValidMedia()
        resumeBrowser.findRecentMedia()

        // Then it calls addTrack
        verify(callback).addTrack(eq(description), eq(component), eq(resumeBrowser))
    }

    @Test
    fun testRestart_connectionFails_error() {
        // When restart is called and we cannot connect
        setupBrowserFailed()
        resumeBrowser.restart()

        // Then it calls onError and disconnects
        verify(callback).onError()
        verify(browser).disconnect()
    }

    @Test
    fun testRestart_connects() {
        // When restart is called and we connect successfully
        setupBrowserConnection()
        resumeBrowser.restart()
        verify(callback).onConnected()

        // Then it creates a new controller and sends play command
        verify(transportControls).prepare()
        verify(transportControls).play()
    }

    @Test
    fun testRestart_thenSessionDestroyed_disconnects() {
        // When restart is called and we connect successfully
        setupBrowserConnection()
        resumeBrowser.restart()
        verify(mediaController).registerCallback(mediaControllerCallback.capture())
        reset(browser)

        // And a sessionDestroyed event is triggered
        mediaControllerCallback.value.onSessionDestroyed()

        // Then we disconnect the browser and unregister the callback
        verify(browser).disconnect()
        verify(mediaController).unregisterCallback(mediaControllerCallback.value)
    }

    @Test
    fun testRestart_calledTwice_oldBrowserDisconnected() {
        val oldBrowser = mock<MediaBrowser>()
        whenever(browserFactory.create(any(), any(), any())).thenReturn(oldBrowser)

        // When restart is called and we connect successfully
        setupBrowserConnection()
        resumeBrowser.restart()

        // And restart is called again
        val newBrowser = mock<MediaBrowser>()
        whenever(browserFactory.create(any(), any(), any())).thenReturn(newBrowser)
        resumeBrowser.restart()

        // Then we disconnect the old browser
        verify(oldBrowser).disconnect()
    }

    /** Helper function to mock a failed connection */
    private fun setupBrowserFailed() {
        whenever(browser.connect()).thenAnswer { connectionCallback.value.onConnectionFailed() }
    }

    /** Helper function to mock a successful connection only */
    private fun setupBrowserConnection() {
        whenever(browser.connect()).thenAnswer { connectionCallback.value.onConnected() }
        whenever(browser.isConnected()).thenReturn(true)
        whenever(browser.getRoot()).thenReturn(ROOT)
        whenever(browser.sessionToken).thenReturn(token)
    }

    /** Helper function to mock a successful connection, but no media results */
    private fun setupBrowserConnectionNoResults() {
        setupBrowserConnection()
        whenever(browser.subscribe(any(), capture(subscriptionCallback))).thenAnswer {
            subscriptionCallback.value.onChildrenLoaded(ROOT, emptyList())
        }
    }

    /** Helper function to mock a successful connection, but no playable results */
    private fun setupBrowserConnectionNotPlayable() {
        setupBrowserConnection()

        val child = MediaBrowser.MediaItem(description, 0)

        whenever(browser.subscribe(any(), capture(subscriptionCallback))).thenAnswer {
            subscriptionCallback.value.onChildrenLoaded(ROOT, listOf(child))
        }
    }

    /** Helper function to mock a successful connection with playable media */
    private fun setupBrowserConnectionValidMedia() {
        setupBrowserConnection()

        val child = MediaBrowser.MediaItem(description, MediaBrowser.MediaItem.FLAG_PLAYABLE)

        whenever(browser.serviceComponent).thenReturn(component)
        whenever(browser.subscribe(any(), capture(subscriptionCallback))).thenAnswer {
            subscriptionCallback.value.onChildrenLoaded(ROOT, listOf(child))
        }
    }

    /** Override so media controller use is testable */
    private class TestableResumeMediaBrowser(
        context: Context,
        callback: Callback,
        componentName: ComponentName,
        browserFactory: MediaBrowserFactory,
        logger: ResumeMediaBrowserLogger,
        private val fakeController: MediaController
    ) : ResumeMediaBrowser(context, callback, componentName, browserFactory, logger) {

        override fun createMediaController(token: MediaSession.Token): MediaController {
            return fakeController
        }
    }
}
