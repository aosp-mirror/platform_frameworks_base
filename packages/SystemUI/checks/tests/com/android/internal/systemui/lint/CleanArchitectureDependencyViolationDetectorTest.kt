/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class CleanArchitectureDependencyViolationDetectorTest : SystemUILintDetectorTest() {
    override fun getDetector(): Detector {
        return CleanArchitectureDependencyViolationDetector()
    }

    override fun getIssues(): List<Issue> {
        return listOf(
            CleanArchitectureDependencyViolationDetector.ISSUE,
        )
    }

    @Test
    fun noViolations() {
        lint()
            .files(
                *LEGITIMATE_FILES,
            )
            .issues(
                CleanArchitectureDependencyViolationDetector.ISSUE,
            )
            .run()
            .expectWarningCount(0)
    }

    @Test
    fun violation_domainDependsOnUi() {
        lint()
            .files(
                *LEGITIMATE_FILES,
                TestFiles.kotlin(
                    """
                        package test.domain.interactor

                        import test.ui.viewmodel.ViewModel

                        class BadClass(
                            private val viewModel: ViewModel,
                        )
                    """
                        .trimIndent()
                )
            )
            .issues(
                CleanArchitectureDependencyViolationDetector.ISSUE,
            )
            .testModes(TestMode.DEFAULT)
            .run()
            .expectWarningCount(1)
            .expect(
                expectedText =
                    """
                    src/test/domain/interactor/BadClass.kt:3: Warning: The domain layer may not depend on the ui layer. [CleanArchitectureDependencyViolation]
                    import test.ui.viewmodel.ViewModel
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                """,
            )
    }

    @Test
    fun violation_uiDependsOnData() {
        lint()
            .files(
                *LEGITIMATE_FILES,
                TestFiles.kotlin(
                    """
                        package test.ui.viewmodel

                        import test.data.repository.Repository

                        class BadClass(
                            private val repository: Repository,
                        )
                    """
                        .trimIndent()
                )
            )
            .issues(
                CleanArchitectureDependencyViolationDetector.ISSUE,
            )
            .testModes(TestMode.DEFAULT)
            .run()
            .expectWarningCount(1)
            .expect(
                expectedText =
                    """
                    src/test/ui/viewmodel/BadClass.kt:3: Warning: The ui layer may not depend on the data layer. [CleanArchitectureDependencyViolation]
                    import test.data.repository.Repository
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                """,
            )
    }

    @Test
    fun violation_sharedDependsOnAllOtherLayers() {
        lint()
            .files(
                *LEGITIMATE_FILES,
                TestFiles.kotlin(
                    """
                        package test.shared.model

                        import test.data.repository.Repository
                        import test.domain.interactor.Interactor
                        import test.ui.viewmodel.ViewModel

                        class BadClass(
                            private val repository: Repository,
                            private val interactor: Interactor,
                            private val viewmodel: ViewModel,
                        )
                    """
                        .trimIndent()
                )
            )
            .issues(
                CleanArchitectureDependencyViolationDetector.ISSUE,
            )
            .testModes(TestMode.DEFAULT)
            .run()
            .expectWarningCount(3)
            .expect(
                expectedText =
                    """
                    src/test/shared/model/BadClass.kt:3: Warning: The shared layer may not depend on the data layer. [CleanArchitectureDependencyViolation]
                    import test.data.repository.Repository
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    src/test/shared/model/BadClass.kt:4: Warning: The shared layer may not depend on the domain layer. [CleanArchitectureDependencyViolation]
                    import test.domain.interactor.Interactor
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    src/test/shared/model/BadClass.kt:5: Warning: The shared layer may not depend on the ui layer. [CleanArchitectureDependencyViolation]
                    import test.ui.viewmodel.ViewModel
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 3 warnings
                """,
            )
    }

    @Test
    fun violation_dataDependsOnDomain() {
        lint()
            .files(
                *LEGITIMATE_FILES,
                TestFiles.kotlin(
                    """
                        package test.data.repository

                        import test.domain.interactor.Interactor

                        class BadClass(
                            private val interactor: Interactor,
                        )
                    """
                        .trimIndent()
                )
            )
            .issues(
                CleanArchitectureDependencyViolationDetector.ISSUE,
            )
            .testModes(TestMode.DEFAULT)
            .run()
            .expectWarningCount(1)
            .expect(
                expectedText =
                    """
                    src/test/data/repository/BadClass.kt:3: Warning: The data layer may not depend on the domain layer. [CleanArchitectureDependencyViolation]
                    import test.domain.interactor.Interactor
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                """,
            )
    }

    companion object {
        private val MODEL_FILE =
            TestFiles.kotlin(
                """
                    package test.shared.model

                    import test.some.other.thing.SomeOtherThing

                    data class Model(
                        private val name: String,
                    )
                """
                    .trimIndent()
            )
        private val REPOSITORY_FILE =
            TestFiles.kotlin(
                """
                    package test.data.repository

                    import test.shared.model.Model
                    import test.some.other.thing.SomeOtherThing

                    class Repository {
                        private val models = listOf(
                            Model("one"),
                            Model("two"),
                            Model("three"),
                        )

                        fun getModels(): List<Model> {
                            return models
                        }
                    }
                """
                    .trimIndent()
            )
        private val INTERACTOR_FILE =
            TestFiles.kotlin(
                """
                    package test.domain.interactor

                    import test.data.repository.Repository
                    import test.shared.model.Model

                    class Interactor(
                        private val repository: Repository,
                    ) {
                        fun getModels(): List<Model> {
                            return repository.getModels()
                        }
                    }
                """
                    .trimIndent()
            )
        private val VIEW_MODEL_FILE =
            TestFiles.kotlin(
                """
                    package test.ui.viewmodel

                    import test.domain.interactor.Interactor
                    import test.some.other.thing.SomeOtherThing

                    class ViewModel(
                        private val interactor: Interactor,
                    ) {
                        fun getNames(): List<String> {
                            return interactor.getModels().map { model -> model.name }
                        }
                    }
                """
                    .trimIndent()
            )
        private val NON_CLEAN_ARCHITECTURE_FILE =
            TestFiles.kotlin(
                """
                    package test.some.other.thing

                    import test.data.repository.Repository
                    import test.domain.interactor.Interactor
                    import test.ui.viewmodel.ViewModel

                    class SomeOtherThing {
                        init {
                            val viewModel = ViewModel(
                                interactor = Interactor(
                                    repository = Repository(),
                                ),
                            )
                        }
                    }
                """
                    .trimIndent()
            )
        private val LEGITIMATE_FILES =
            arrayOf(
                MODEL_FILE,
                REPOSITORY_FILE,
                INTERACTOR_FILE,
                VIEW_MODEL_FILE,
                NON_CLEAN_ARCHITECTURE_FILE,
            )
    }
}
