/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.pm.test.verify.domain

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.verify.domain.DomainVerificationManager
import android.content.pm.verify.domain.DomainVerificationRequest
import android.content.pm.verify.domain.DomainVerificationInfo
import android.content.pm.verify.domain.DomainVerificationState
import android.os.Bundle
import android.os.UserHandle
import android.util.ArraySet
import com.android.server.DeviceIdleInternal
import com.android.server.pm.verify.domain.DomainVerificationCollector
import com.android.server.pm.verify.domain.DomainVerificationManagerInternal
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxy
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxyV1
import com.android.server.pm.verify.domain.proxy.DomainVerificationProxyV2
import com.android.server.pm.parsing.pkg.AndroidPackage
import com.android.server.testutils.mockThrowOnUnmocked
import com.android.server.testutils.whenever
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mockito.any
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyLong
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.eq
import org.mockito.Mockito.isNull
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import java.util.UUID

@Suppress("DEPRECATION")
class DomainVerificationProxyTest {

    companion object {
        private const val TEST_PKG_NAME_ONE = "com.test.pkg.one"
        private const val TEST_PKG_NAME_TWO = "com.test.pkg.two"
        private const val TEST_PKG_NAME_TARGET_ONE = "com.test.target.one"
        private const val TEST_PKG_NAME_TARGET_TWO = "com.test.target.two"
        private const val TEST_CALLING_UID_ACCEPT = 40
        private const val TEST_CALLING_UID_REJECT = 41
        private val TEST_UUID_ONE = UUID.fromString("f7fbb7dd-7b5f-4609-a95e-c6c7765fb9cd")
        private val TEST_UUID_TWO = UUID.fromString("4a09b361-a967-43ac-9d18-07a385dff740")
    }

    private val componentOne = ComponentName(TEST_PKG_NAME_ONE, ".ReceiverOne")
    private val componentTwo = ComponentName(TEST_PKG_NAME_TWO, ".ReceiverTwo")
    private val componentThree = ComponentName(TEST_PKG_NAME_TWO, ".ReceiverThree")

    private lateinit var context: Context
    private lateinit var manager: DomainVerificationManagerInternal
    private lateinit var collector: DomainVerificationCollector

    // Must be declared as field to support generics
    @Captor
    lateinit var hostCaptor: ArgumentCaptor<Set<String>>

    @Before
    fun setUpMocks() {
        MockitoAnnotations.initMocks(this)
        context = mockThrowOnUnmocked {
            whenever(sendBroadcastAsUser(any(), any(), any(), any<Bundle>()))
            whenever(
                enforceCallingOrSelfPermission(
                    eq(android.Manifest.permission.INTENT_FILTER_VERIFICATION_AGENT),
                    anyString()
                )
            )
        }
        manager = mockThrowOnUnmocked {
            whenever(getDomainVerificationInfoId(any())) {
                when (val pkgName = arguments[0] as String) {
                    TEST_PKG_NAME_TARGET_ONE -> TEST_UUID_ONE
                    TEST_PKG_NAME_TARGET_TWO -> TEST_UUID_TWO
                    else -> throw IllegalArgumentException("Unexpected package name $pkgName")
                }
            }
            whenever(getDomainVerificationInfo(anyString())) {
                when (val pkgName = arguments[0] as String) {
                    TEST_PKG_NAME_TARGET_ONE -> DomainVerificationInfo(
                        TEST_UUID_ONE, pkgName, mapOf(
                            "example1.com" to DomainVerificationManager.STATE_NO_RESPONSE,
                            "example2.com" to DomainVerificationManager.STATE_NO_RESPONSE
                        )
                    )
                    TEST_PKG_NAME_TARGET_TWO -> DomainVerificationInfo(
                        TEST_UUID_TWO, pkgName, mapOf(
                            "example3.com" to DomainVerificationManager.STATE_NO_RESPONSE,
                            "example4.com" to DomainVerificationManager.STATE_NO_RESPONSE
                        )
                    )
                    else -> throw IllegalArgumentException("Unexpected package name $pkgName")
                }
            }
            whenever(setDomainVerificationStatusInternal(anyInt(), any(), any(), anyInt()))
        }
        collector = mockThrowOnUnmocked {
            whenever(collectValidAutoVerifyDomains(any())) {
                when (val pkgName = (arguments[0] as AndroidPackage).packageName) {
                    TEST_PKG_NAME_TARGET_ONE -> ArraySet(setOf("example1.com", "example2.com"))
                    TEST_PKG_NAME_TARGET_TWO -> ArraySet(setOf("example3.com", "example4.com"))
                    else -> throw IllegalArgumentException("Unexpected package name $pkgName")
                }
            }
        }
    }

    @Test
    fun isCallerVerifierV1() {
        val connection = mockConnection()
        val proxyV1 = DomainVerificationProxy.makeProxy<Connection>(
            componentOne, null, context,
            manager, collector, connection
        )

        assertThat(proxyV1.isCallerVerifier(TEST_CALLING_UID_ACCEPT)).isTrue()
        verify(connection).isCallerPackage(TEST_CALLING_UID_ACCEPT, TEST_PKG_NAME_ONE)
        verifyNoMoreInteractions(connection)
        clearInvocations(connection)

        assertThat(proxyV1.isCallerVerifier(TEST_CALLING_UID_REJECT)).isFalse()
        verify(connection).isCallerPackage(TEST_CALLING_UID_REJECT, TEST_PKG_NAME_ONE)
        verifyNoMoreInteractions(connection)
    }

    @Test
    fun isCallerVerifierV2() {
        val connection = mockConnection()
        val proxyV2 = DomainVerificationProxy.makeProxy<Connection>(
            null, componentTwo, context,
            manager, collector, connection
        )

        assertThat(proxyV2.isCallerVerifier(TEST_CALLING_UID_ACCEPT)).isTrue()
        verify(connection).isCallerPackage(TEST_CALLING_UID_ACCEPT, TEST_PKG_NAME_TWO)
        verifyNoMoreInteractions(connection)
        clearInvocations(connection)

        assertThat(proxyV2.isCallerVerifier(TEST_CALLING_UID_REJECT)).isFalse()
        verify(connection).isCallerPackage(TEST_CALLING_UID_REJECT, TEST_PKG_NAME_TWO)
        verifyNoMoreInteractions(connection)
    }

    @Test
    fun isCallerVerifierBoth() {
        val connection = mockConnection()
        val proxyBoth = DomainVerificationProxy.makeProxy<Connection>(
            componentTwo, componentThree,
            context, manager, collector, connection
        )

        // The combined proxy should only ever call v2 when it succeeds
        assertThat(proxyBoth.isCallerVerifier(TEST_CALLING_UID_ACCEPT)).isTrue()
        verify(connection).isCallerPackage(TEST_CALLING_UID_ACCEPT, TEST_PKG_NAME_TWO)
        verifyNoMoreInteractions(connection)
        clearInvocations(connection)

        val callingUidCaptor = ArgumentCaptor.forClass(Int::class.java)

        // But will call both when v2 fails
        assertThat(proxyBoth.isCallerVerifier(TEST_CALLING_UID_REJECT)).isFalse()
        verify(connection, times(2))
            .isCallerPackage(callingUidCaptor.capture(), eq(TEST_PKG_NAME_TWO))
        verifyNoMoreInteractions(connection)

        assertThat(callingUidCaptor.allValues.toSet()).containsExactly(TEST_CALLING_UID_REJECT)
    }

    @Test
    fun differentPackagesResolvesOnlyV2() {
        assertThat(DomainVerificationProxy.makeProxy<Connection>(
            componentOne, componentTwo,
            context, manager, collector, mockConnection()
        )).isInstanceOf(DomainVerificationProxyV2::class.java)
    }

    private fun prepareProxyV1(): ProxyV1Setup {
        val messages = mutableListOf<Pair<Int, Any?>>()
        val connection = mockConnection {
            whenever(schedule(anyInt(), any())) {
                messages.add((arguments[0] as Int) to arguments[1])
            }
        }

        val proxy = DomainVerificationProxy.makeProxy<Connection>(
            componentOne,
            null,
            context,
            manager,
            collector,
            connection
        )
        return ProxyV1Setup(messages, connection, proxy)
    }

    @Test
    fun sendBroadcastForPackagesV1() {
        val (messages, _, proxy) = prepareProxyV1()

        proxy.sendBroadcastForPackages(setOf(TEST_PKG_NAME_TARGET_ONE, TEST_PKG_NAME_TARGET_TWO))
        messages.forEach { (code, value) -> proxy.runMessage(code, value) }

        val intentCaptor = ArgumentCaptor.forClass(Intent::class.java)

        verify(context, times(2)).sendBroadcastAsUser(
            intentCaptor.capture(), eq(UserHandle.SYSTEM), isNull(), any<Bundle>()
        )
        verifyNoMoreInteractions(context)

        val intents = intentCaptor.allValues
        assertThat(intents).hasSize(2)
        intents.forEach {
            assertThat(it.action).isEqualTo(Intent.ACTION_INTENT_FILTER_NEEDS_VERIFICATION)
            assertThat(it.getStringExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_URI_SCHEME))
                .isEqualTo(IntentFilter.SCHEME_HTTPS)
            assertThat(it.getIntExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_ID, -1))
                .isNotEqualTo(-1)
        }

        intents[0].apply {
            assertThat(getStringExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_PACKAGE_NAME))
                .isEqualTo(TEST_PKG_NAME_TARGET_ONE)
            assertThat(getStringExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_HOSTS))
                .isEqualTo("example1.com example2.com")
        }

        intents[1].apply {
            assertThat(getStringExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_PACKAGE_NAME))
                .isEqualTo(TEST_PKG_NAME_TARGET_TWO)
            assertThat(getStringExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_HOSTS))
                .isEqualTo("example3.com example4.com")
        }
    }

    private fun prepareProxyOnIntentFilterVerifiedV1(): Pair<ProxyV1Setup, Pair<Int, Int>> {
        val (messages, connection, proxy) = prepareProxyV1()

        proxy.sendBroadcastForPackages(setOf(TEST_PKG_NAME_TARGET_ONE, TEST_PKG_NAME_TARGET_TWO))
        messages.forEach { (code, value) -> proxy.runMessage(code, value) }
        messages.clear()

        val intentCaptor = ArgumentCaptor.forClass(Intent::class.java)

        verify(context, times(2)).sendBroadcastAsUser(
            intentCaptor.capture(), eq(UserHandle.SYSTEM), isNull(), any<Bundle>()
        )

        val verificationIds = intentCaptor.allValues.map {
            it.getIntExtra(PackageManager.EXTRA_INTENT_FILTER_VERIFICATION_ID, -1)
        }

        assertThat(verificationIds).doesNotContain(-1)

        return ProxyV1Setup(messages, connection, proxy) to
                (verificationIds[0] to verificationIds[1])
    }

    @Test
    fun proxyOnIntentFilterVerifiedFullSuccessV1() {
        val setup = prepareProxyOnIntentFilterVerifiedV1()
        val (messages, connection, proxy) = setup.first
        val (idOne, idTwo) = setup.second

        DomainVerificationProxyV1.queueLegacyVerifyResult(
            context,
            connection,
            idOne,
            PackageManager.INTENT_FILTER_VERIFICATION_SUCCESS,
            emptyList(),
            TEST_CALLING_UID_ACCEPT
        )

        DomainVerificationProxyV1.queueLegacyVerifyResult(
            context,
            connection,
            idTwo,
            PackageManager.INTENT_FILTER_VERIFICATION_SUCCESS,
            emptyList(),
            TEST_CALLING_UID_ACCEPT
        )

        assertThat(messages).hasSize(2)
        messages.forEach { (code, value) -> proxy.runMessage(code, value) }

        val idCaptor = ArgumentCaptor.forClass(UUID::class.java)

        @Suppress("UNCHECKED_CAST")
        verify(manager, times(2)).setDomainVerificationStatusInternal(
            eq(TEST_CALLING_UID_ACCEPT),
            idCaptor.capture(),
            hostCaptor.capture(),
            eq(DomainVerificationManager.STATE_SUCCESS)
        )

        assertThat(idCaptor.allValues).containsExactly(TEST_UUID_ONE, TEST_UUID_TWO)

        assertThat(hostCaptor.allValues.toSet()).containsExactly(
            setOf("example1.com", "example2.com"),
            setOf("example3.com", "example4.com")
        )
    }

    @Test
    fun proxyOnIntentFilterVerifiedPartialSuccessV1() {
        val setup = prepareProxyOnIntentFilterVerifiedV1()
        val (messages, connection, proxy) = setup.first
        val (idOne, idTwo) = setup.second

        DomainVerificationProxyV1.queueLegacyVerifyResult(
            context,
            connection,
            idOne,
            PackageManager.INTENT_FILTER_VERIFICATION_FAILURE,
            listOf("example1.com"),
            TEST_CALLING_UID_ACCEPT
        )

        DomainVerificationProxyV1.queueLegacyVerifyResult(
            context,
            connection,
            idTwo,
            PackageManager.INTENT_FILTER_VERIFICATION_FAILURE,
            listOf("example3.com"),
            TEST_CALLING_UID_ACCEPT
        )

        messages.forEach { (code, value) -> proxy.runMessage(code, value) }

        val idCaptor = ArgumentCaptor.forClass(UUID::class.java)
        val stateCaptor = ArgumentCaptor.forClass(Int::class.java)

        @Suppress("UNCHECKED_CAST")
        verify(manager, times(4)).setDomainVerificationStatusInternal(
            eq(TEST_CALLING_UID_ACCEPT),
            idCaptor.capture(),
            hostCaptor.capture(),
            stateCaptor.capture()
        )

        assertThat(idCaptor.allValues)
            .containsExactly(TEST_UUID_ONE, TEST_UUID_ONE, TEST_UUID_TWO, TEST_UUID_TWO)

        val hostToStates: Map<Set<*>, Int> = hostCaptor.allValues.zip(stateCaptor.allValues).toMap()
        assertThat(hostToStates).isEqualTo(mapOf(
            setOf("example1.com") to DomainVerificationState.STATE_LEGACY_FAILURE,
            setOf("example2.com") to DomainVerificationState.STATE_SUCCESS,
            setOf("example3.com") to DomainVerificationState.STATE_LEGACY_FAILURE,
            setOf("example4.com") to DomainVerificationState.STATE_SUCCESS,
        ))
    }

    @Test
    fun proxyOnIntentFilterVerifiedFailureV1() {
        val setup = prepareProxyOnIntentFilterVerifiedV1()
        val (messages, connection, proxy) = setup.first
        val (idOne, idTwo) = setup.second

        DomainVerificationProxyV1.queueLegacyVerifyResult(
            context,
            connection,
            idOne,
            PackageManager.INTENT_FILTER_VERIFICATION_FAILURE,
            listOf("example1.com", "example2.com"),
            TEST_CALLING_UID_ACCEPT
        )

        DomainVerificationProxyV1.queueLegacyVerifyResult(
            context,
            connection,
            idTwo,
            PackageManager.INTENT_FILTER_VERIFICATION_FAILURE,
            listOf("example3.com", "example4.com"),
            TEST_CALLING_UID_ACCEPT
        )

        messages.forEach { (code, value) -> proxy.runMessage(code, value) }

        val idCaptor = ArgumentCaptor.forClass(UUID::class.java)

        @Suppress("UNCHECKED_CAST")
        verify(manager, times(2)).setDomainVerificationStatusInternal(
            eq(TEST_CALLING_UID_ACCEPT),
            idCaptor.capture(),
            hostCaptor.capture(),
            eq(DomainVerificationState.STATE_LEGACY_FAILURE)
        )

        assertThat(idCaptor.allValues).containsExactly(TEST_UUID_ONE, TEST_UUID_TWO)

        assertThat(hostCaptor.allValues.toSet()).containsExactly(
            setOf("example1.com", "example2.com"),
            setOf("example3.com", "example4.com")
        )
    }

    @Test
    fun sendBroadcastForPackagesV2() {
        val componentTwo = ComponentName(TEST_PKG_NAME_TWO, ".ReceiverOne")
        val messages = mutableListOf<Pair<Int, Any?>>()

        val connection = mockConnection {
            whenever(schedule(anyInt(), any())) {
                messages.add((arguments[0] as Int) to arguments[1])
            }
        }

        val proxy = DomainVerificationProxy.makeProxy<Connection>(
            null,
            componentTwo,
            context,
            manager,
            collector,
            connection
        )

        proxy.sendBroadcastForPackages(setOf(TEST_PKG_NAME_TARGET_ONE, TEST_PKG_NAME_TARGET_TWO))

        messages.forEach { (code, value) -> proxy.runMessage(code, value) }

        val intentCaptor = ArgumentCaptor.forClass(Intent::class.java)

        verify(context).sendBroadcastAsUser(
            intentCaptor.capture(), eq(UserHandle.SYSTEM), isNull(), any<Bundle>()
        )
        verifyNoMoreInteractions(context)

        val intents = intentCaptor.allValues
        assertThat(intents).hasSize(1)
        intents.single().apply {
            assertThat(this.action).isEqualTo(Intent.ACTION_DOMAINS_NEED_VERIFICATION)
            val request: DomainVerificationRequest? =
                getParcelableExtra(DomainVerificationManager.EXTRA_VERIFICATION_REQUEST)
            assertThat(request?.packageNames).containsExactly(
                TEST_PKG_NAME_TARGET_ONE,
                TEST_PKG_NAME_TARGET_TWO
            )
        }
    }

    private fun mockConnection(block: Connection.() -> Unit = {}) =
        mockThrowOnUnmocked<Connection> {
            whenever(isCallerPackage(TEST_CALLING_UID_ACCEPT, TEST_PKG_NAME_ONE)) { true }
            whenever(isCallerPackage(TEST_CALLING_UID_ACCEPT, TEST_PKG_NAME_TWO)) { true }
            whenever(isCallerPackage(TEST_CALLING_UID_REJECT, TEST_PKG_NAME_ONE)) { false }
            whenever(isCallerPackage(TEST_CALLING_UID_REJECT, TEST_PKG_NAME_TWO)) { false }
            whenever(getPackage(anyString())) { mockPkg(arguments[0] as String) }
            whenever(powerSaveTempWhitelistAppDuration) { 1000 }
            whenever(deviceIdleInternal) {
                mockThrowOnUnmocked<DeviceIdleInternal> {
                    whenever(
                        addPowerSaveTempWhitelistApp(
                            anyInt(), anyString(), anyLong(), anyInt(),
                            anyBoolean(), anyInt(), anyString()
                        )
                    )
                }
            }
            block()
        }

    private fun mockPkg(pkgName: String): AndroidPackage {
        return mockThrowOnUnmocked { whenever(packageName) { pkgName } }
    }

    private data class ProxyV1Setup(
        val messages: MutableList<Pair<Int, Any?>>,
        val connection: Connection,
        val proxy: DomainVerificationProxy
    )

    interface Connection : DomainVerificationProxyV1.Connection,
        DomainVerificationProxyV2.Connection
}
