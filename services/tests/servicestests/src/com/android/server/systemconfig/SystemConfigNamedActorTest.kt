/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.systemconfig

import android.content.Context
import androidx.test.InstrumentationRegistry
import com.android.server.SystemConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder

class SystemConfigNamedActorTest {

    companion object {
        private const val NAMESPACE_TEST = "someTestNamespace"
        private const val NAMESPACE_ANDROID = "android"
        private const val ACTOR_ONE = "iconShaper"
        private const val ACTOR_TWO = "colorChanger"
        private const val PACKAGE_ONE = "com.test.actor.one"
        private const val PACKAGE_TWO = "com.test.actor.two"
    }

    private val context: Context = InstrumentationRegistry.getContext()

    @get:Rule
    val tempFolder = TemporaryFolder(context.filesDir)

    @get:Rule
    val expected = ExpectedException.none()

    private var uniqueCounter = 0

    @Test
    fun twoUnique() {
        """
            <config>
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_ONE"
                    package="$PACKAGE_ONE"
                    />
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_TWO"
                    package="$PACKAGE_TWO"
                    />
            </config>
        """.write()

        assertPermissions().containsExactlyEntriesIn(
                mapOf(
                        NAMESPACE_TEST to mapOf(
                            ACTOR_ONE to PACKAGE_ONE,
                            ACTOR_TWO to PACKAGE_TWO
                        )
                )
        )
    }

    @Test
    fun twoSamePackage() {
        """
            <config>
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_ONE"
                    package="$PACKAGE_ONE"
                    />
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_TWO"
                    package="$PACKAGE_ONE"
                    />
            </config>
        """.write()

        assertPermissions().containsExactlyEntriesIn(
                mapOf(
                        NAMESPACE_TEST to mapOf(
                            ACTOR_ONE to PACKAGE_ONE,
                            ACTOR_TWO to PACKAGE_ONE
                        )
                )
        )
    }

    @Test
    fun missingNamespace() {
        """
            <config>
                <named-actor
                    name="$ACTOR_ONE"
                    package="$PACKAGE_ONE"
                    />
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_TWO"
                    package="$PACKAGE_TWO"
                    />
            </config>
        """.write()

        assertPermissions().containsExactlyEntriesIn(
                mapOf(
                        NAMESPACE_TEST to mapOf(
                                ACTOR_TWO to PACKAGE_TWO
                        )
                )
        )
    }

    @Test
    fun missingName() {
        """
            <config>
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    package="$PACKAGE_ONE"
                    />
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_TWO"
                    package="$PACKAGE_TWO"
                    />
            </config>
        """.write()

        assertPermissions().containsExactlyEntriesIn(
                mapOf(
                        NAMESPACE_TEST to mapOf(
                                ACTOR_TWO to PACKAGE_TWO
                        )
                )
        )
    }

    @Test
    fun missingPackage() {
        """
            <config>
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_ONE"
                    />
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_TWO"
                    package="$PACKAGE_TWO"
                    />
            </config>
        """.write()

        assertPermissions().containsExactlyEntriesIn(
                mapOf(
                        NAMESPACE_TEST to mapOf(
                                ACTOR_TWO to PACKAGE_TWO
                        )
                )
        )
    }

    @Test
    fun androidNamespaceThrows() {
        """
            <config>
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_ONE"
                    package="$PACKAGE_ONE"
                    />
                <named-actor
                    namespace="$NAMESPACE_ANDROID"
                    name="$ACTOR_ONE"
                    package="$PACKAGE_ONE"
                    />
            </config>
        """.write()

        expected.expect(IllegalStateException::class.java)
        expected.expectMessage("Defining $ACTOR_ONE as $PACKAGE_ONE " +
                "for the android namespace is not allowed")

        assertPermissions()
    }

    @Test
    fun duplicateActorNameThrows() {
        """
            <config>
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_ONE"
                    package="$PACKAGE_ONE"
                    />
                <named-actor
                    namespace="$NAMESPACE_TEST"
                    name="$ACTOR_ONE"
                    package="$PACKAGE_TWO"
                    />
            </config>
        """.write()

        expected.expect(IllegalStateException::class.java)
        expected.expectMessage("Duplicate actor definition for $NAMESPACE_TEST/$ACTOR_ONE;" +
                " defined as both $PACKAGE_ONE and $PACKAGE_TWO")

        assertPermissions()
    }

    private fun String.write() = tempFolder.root.resolve("${uniqueCounter++}.xml")
            .writeText(this.trimIndent())

    private fun assertPermissions() = SystemConfig(false).apply {
        readPermissions(tempFolder.root, 0)
    }. let { assertThat(it.namedActors) }
}
