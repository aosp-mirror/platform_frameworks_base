/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *            http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.android.internal.systemui.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class ShadeDisplayAwareDetectorTest : SystemUILintDetectorTest() {
    override fun getDetector(): Detector = ShadeDisplayAwareDetector()

    override fun getIssues(): List<Issue> = listOf(ShadeDisplayAwareDetector.ISSUE)

    private val qsContext: TestFile =
        java(
                """
            package com.android.systemui.qs.dagger;

            import static java.lang.annotation.RetentionPolicy.RUNTIME;

            import java.lang.annotation.Retention;

            @Retention(RUNTIME) public @interface QSThemedContext {}
            """
            )
            .indented()

    private val injectStub: TestFile =
        kotlin(
                """
                package javax.inject

                @Retention(AnnotationRetention.RUNTIME) annotation class Inject
                """
            )
            .indented()

    private val shadeDisplayAwareStub: TestFile =
        kotlin(
                """
                package com.android.systemui.shade

                @Retention(AnnotationRetention.RUNTIME) annotation class ShadeDisplayAware
                """
            )
            .indented()

    private val applicationStub: TestFile =
        kotlin(
                """
                package com.android.systemui.dagger.qualifiers

                @Retention(AnnotationRetention.RUNTIME) annotation class Application
                """
            )
            .indented()

    private val globalConfigStub: TestFile =
        kotlin(
                """
                package com.android.systemui.common.ui

                @Retention(AnnotationRetention.RUNTIME) annotation class GlobalConfig
                """
            )
            .indented()

    private val configStateStub: TestFile =
        kotlin(
                """
                package com.android.systemui.common.ui

                class ConfigurationState
                """
            )
            .indented()

    private val configControllerStub: TestFile =
        kotlin(
                """
                package com.android.systemui.statusbar.policy

                class ConfigurationController
                """
            )
            .indented()

    private val configInteractorStub: TestFile =
        kotlin(
                """
                package com.android.systemui.common.ui.domain.interactor

                class ConfigurationInteractor
                """
            )
            .indented()

    private val otherStubs =
        arrayOf(
            injectStub,
            qsContext,
            shadeDisplayAwareStub,
            applicationStub,
            globalConfigStub,
            configStateStub,
            configControllerStub,
            configInteractorStub,
        )

    @Test
    fun injectedConstructor_inRelevantPackage_withRelevantParameter_withoutAnnotation() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package com.android.systemui.shade.example

                        import javax.inject.Inject
                        import android.content.Context

                        class ExampleClass
                            @Inject
                            constructor(private val context: Context)
                    """
                        .trimIndent()
                ),
                *androidStubs,
                *otherStubs,
            )
            .issues(ShadeDisplayAwareDetector.ISSUE)
            .testModes(TestMode.DEFAULT)
            .run()
            .expectErrorCount(1)
            .expectContains(errorMsgString(8, "Context"))
            .expectContains("[ShadeDisplayAwareContextChecker]")
            .expectContains(
                "constructor(private val context: Context)\n" +
                    "                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
            )
            .expectContains("1 errors, 0 warnings")
    }

    @Test
    fun injectedConstructor_inRelevantPackage_withMultipleRelevantParameters_withoutAnnotation() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package com.android.systemui.shade.example

                        import javax.inject.Inject
                        import android.content.Context
                        import android.content.res.Resources
                        import android.view.LayoutInflater
                        import android.view.WindowManager
                        import com.android.systemui.common.ui.ConfigurationState
                        import com.android.systemui.common.ui.domain.interactor.ConfigurationInteractor
                        import com.android.systemui.statusbar.policy.ConfigurationController

                        class ExampleClass
                            @Inject
                            constructor(
                                private val context: Context,
                                private val inflater: LayoutInflater,
                                private val windowManager: WindowManager,
                                private val configState: ConfigurationState,
                                private val configController: ConfigurationController,
                                private val configInteractor: ConfigurationInteractor,
                            )
                    """
                        .trimIndent()
                ),
                *androidStubs,
                *otherStubs,
            )
            .issues(ShadeDisplayAwareDetector.ISSUE)
            .testModes(TestMode.DEFAULT)
            .run()
            .expectErrorCount(6)
            .expectContains(errorMsgString(lineNumber = 15, className = "Context"))
            .expectContains(
                "private val context: Context,\n" + "        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
            )
            .expectContains(errorMsgString(lineNumber = 16, className = "LayoutInflater"))
            .expectContains(
                "private val inflater: LayoutInflater,\n" +
                    "        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
            )
            .expectContains(errorMsgString(lineNumber = 17, className = "WindowManager"))
            .expectContains(
                "private val windowManager: WindowManager,\n" +
                    "        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
            )
            .expectContains(errorMsgString(lineNumber = 18, className = "ConfigurationState"))
            .expectContains(
                "private val configState: ConfigurationState,\n" +
                    "        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
            )
            .expectContains(errorMsgString(lineNumber = 19, className = "ConfigurationController"))
            .expectContains(
                "private val configController: ConfigurationController,\n" +
                    "        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
            )
            .expectContains(errorMsgString(lineNumber = 20, className = "ConfigurationInteractor"))
            .expectContains(
                "private val configInteractor: ConfigurationInteractor,\n" +
                    "        ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~"
            )
            .expectContains(" [ShadeDisplayAwareContextChecker]")
    }

    @Test
    fun injectedConstructor_inRelevantPackage_withRelevantParameter_withAnnotation() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package com.android.systemui.shade.example

                        import javax.inject.Inject
                        import android.content.Context
                        import com.android.systemui.shade.ShadeDisplayAware

                        class ExampleClass
                            @Inject
                            constructor(@ShadeDisplayAware private val context: Context)
                    """
                        .trimIndent()
                ),
                *androidStubs,
                *otherStubs,
            )
            .issues(ShadeDisplayAwareDetector.ISSUE)
            .testModes(TestMode.DEFAULT)
            .run()
            .expectClean()
    }

    @Test
    fun injectedConstructor_inRelevantPackage_withoutRelevantParameter_withoutAnnotation() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package com.android.systemui.shade.example

                        import javax.inject.Inject
                        import android.content.ContextWrapper

                        class ExampleClass
                            @Inject
                            constructor(private val contextWrapper: ContextWrapper)
                    """
                        .trimIndent()
                ),
                *androidStubs,
                *otherStubs,
            )
            .issues(ShadeDisplayAwareDetector.ISSUE)
            .testModes(TestMode.DEFAULT)
            .run()
            .expectClean()
    }

    @Test
    fun injectedConstructor_inRelevantPackage_withApplicationAnnotatedContext() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package com.android.systemui.shade.example

                        import javax.inject.Inject
                        import android.content.Context
                        import com.android.systemui.dagger.qualifiers.Application

                        class ExampleClass
                            @Inject
                            constructor(@Application private val context: Context)
                    """
                        .trimIndent()
                ),
                *androidStubs,
                *otherStubs,
            )
            .issues(ShadeDisplayAwareDetector.ISSUE)
            .testModes(TestMode.DEFAULT)
            .run()
            .expectClean()
    }

    @Test
    fun injectedConstructor_inRelevantPackage_withGlobalConfigAnnotatedConfigurationClass() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package com.android.systemui.shade.example

                        import javax.inject.Inject
                        import com.android.systemui.common.ui.ConfigurationState
                        import com.android.systemui.common.ui.GlobalConfig

                        class ExampleClass
                            @Inject
                            constructor(@GlobalConfig private val configState: ConfigurationState)
                    """
                        .trimIndent()
                ),
                *androidStubs,
                *otherStubs,
            )
            .issues(ShadeDisplayAwareDetector.ISSUE)
            .testModes(TestMode.DEFAULT)
            .run()
            .expectClean()
    }

    @Test
    fun injectedConstructor_notInRelevantPackage_withRelevantParameter_withoutAnnotation() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package com.android.systemui.keyboard

                        import javax.inject.Inject
                        import android.content.Context

                        class ExampleClass @Inject constructor(private val context: Context)
                    """
                        .trimIndent()
                ),
                *androidStubs,
                *otherStubs,
            )
            .issues(ShadeDisplayAwareDetector.ISSUE)
            .testModes(TestMode.DEFAULT)
            .run()
            .expectClean()
    }

    @Test
    fun nonInjectedConstructor_inRelevantPackage_withRelevantParameter_withoutAnnotation() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package com.android.systemui.shade.example

                        import android.content.Context

                        class ExampleClass(private val context: Context)
                    """
                        .trimIndent()
                ),
                *androidStubs,
                *otherStubs,
            )
            .issues(ShadeDisplayAwareDetector.ISSUE)
            .testModes(TestMode.DEFAULT)
            .run()
            .expectClean()
    }

    @Test
    fun injectedConstructor_inRelevantPackage_withRelevantParameter_withoutAnnotation_suppressed() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package com.android.systemui.shade.example

                        import javax.inject.Inject
                        import android.content.Context

                        @Suppress("ShadeDisplayAwareContextChecker")
                        class ExampleClass
                            @Inject
                            constructor(
                                private val context: Context
                            )
                    """
                        .trimIndent()
                ),
                *androidStubs,
                *otherStubs,
            )
            .issues(ShadeDisplayAwareDetector.ISSUE)
            .testModes(TestMode.DEFAULT)
            .run()
            .expectClean()
    }

    @Test
    fun injectedConstructor_inExemptPackage_withRelevantParameter_withoutAnnotation() {
        lint()
            .files(
                TestFiles.java(
                    """
                        package com.android.systemui.qs.customize;

                        import javax.inject.Inject;
                        import com.android.systemui.qs.dagger.QSThemedContext;
                        import android.content.Context;

                        public class TileAdapter {
                            @Inject
                            public TileAdapter(@QSThemedContext Context context) {}
                        }
                    """
                        .trimIndent()
                ),
                *androidStubs,
                *otherStubs,
            )
            .issues(ShadeDisplayAwareDetector.ISSUE)
            .testModes(TestMode.DEFAULT)
            .run()
            .expectClean()
    }

    private fun errorMsgString(lineNumber: Int, className: String) =
        "src/com/android/systemui/shade/example/ExampleClass.kt:$lineNumber: Error: UI elements of " +
            "the shade window should use ShadeDisplayAware-annotated $className, as the shade " +
            "might move between windows, and only @ShadeDisplayAware resources are updated with " +
            "the new configuration correctly. Failures to do so might result in wrong dimensions " +
            "for shade window classes (e.g. using the wrong density or theme). If the usage of " +
            "$className is not related to display specific configuration or UI, then there is " +
            "technically no need to use the annotation, and you can annotate the class with " +
            "@SuppressLint(\"ShadeDisplayAwareContextChecker\")" +
            "/@Suppress(\"ShadeDisplayAwareContextChecker\")"
}
