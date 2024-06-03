/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class SingletonAndroidComponentDetectorTest : SystemUILintDetectorTest() {
    override fun getDetector(): Detector = SingletonAndroidComponentDetector()

    override fun getIssues(): List<Issue> = listOf(SingletonAndroidComponentDetector.ISSUE)

    @Test
    fun testBindsServiceAsSingleton() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    package test.pkg

                    import android.app.Service
                    import com.android.systemui.dagger.SysUISingleton
                    import dagger.Binds
                    import dagger.Module
                    import dagger.multibindings.ClassKey
                    import dagger.multibindings.IntoMap

                    @Module
                    interface BadModule {
                       @SysUISingleton
                       @Binds
                       @IntoMap
                       @ClassKey(SingletonService::class)
                       fun bindSingletonService(service: SingletonService): Service
                    }
                """
                        .trimIndent()
                ),
                *stubs
            )
            .issues(SingletonAndroidComponentDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/BadModule.kt:12: Error: Do not bind Activities, Services, or BroadcastReceivers as Singleton. [SingletonAndroidComponent]
                   @SysUISingleton
                   ~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }

    @Test
    fun testProvidesBroadcastReceiverAsSingleton() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    package test.pkg

                    import android.content.BroadcastReceiver
                    import com.android.systemui.dagger.SysUISingleton
                    import dagger.Provides
                    import dagger.Module
                    import dagger.multibindings.ClassKey
                    import dagger.multibindings.IntoMap

                    @Module
                    abstract class BadModule {
                       @SysUISingleton
                       @Provides
                       @IntoMap
                       @ClassKey(SingletonBroadcastReceiver::class)
                       fun providesSingletonBroadcastReceiver(br: SingletonBroadcastReceiver): BroadcastReceiver {
                          return br
                       }
                    }
                """
                        .trimIndent()
                ),
                *stubs
            )
            .issues(SingletonAndroidComponentDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/BadModule.kt:12: Error: Do not bind Activities, Services, or BroadcastReceivers as Singleton. [SingletonAndroidComponent]
                   @SysUISingleton
                   ~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }
    @Test
    fun testMarksActivityAsSingleton() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    package test.pkg

                    import android.app.Activity
                    import com.android.systemui.dagger.SysUISingleton

                    @SysUISingleton
                    class BadActivity : Activity() {
                    }
                """
                        .trimIndent()
                ),
                *stubs
            )
            .issues(SingletonAndroidComponentDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/BadActivity.kt:6: Error: Do not mark Activities or Services as Singleton. [SingletonAndroidComponent]
                @SysUISingleton
                ~~~~~~~~~~~~~~~
                1 errors, 0 warnings
                """
                    .trimIndent()
            )
    }
    @Test
    fun testMarksBroadcastReceiverAsSingleton() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    package test.pkg

                    import android.content.BroadcastReceiver
                    import com.android.systemui.dagger.SysUISingleton

                    @SysUISingleton
                    class SingletonReceveiver : BroadcastReceiver() {
                    }
                """
                        .trimIndent()
                ),
                *stubs
            )
            .issues(SingletonAndroidComponentDetector.ISSUE)
            .run()
            .expectClean()
    }

    // Define stubs for Android imports. The tests don't run on Android so
    // they don't "see" any of Android specific classes. We need to define
    // the method parameters for proper resolution.
    private val singletonStub: TestFile =
        java(
            """
        package com.android.systemui.dagger;

        public @interface SysUISingleton {
        }
        """
        )

    private val stubs = arrayOf(singletonStub) + androidStubs
}
