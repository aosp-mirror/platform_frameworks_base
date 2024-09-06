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

import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

class FlowDetectorTest : SystemUILintDetectorTest() {
    override fun getDetector(): Detector = FlowDetector()

    override fun getIssues(): List<Issue> = listOf(FlowDetector.SHARED_FLOW_CREATION)

    @Test
    fun createSharedFlow_viaCallToMutableSharedFlow() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package test.pkg

                        import kotlinx.coroutines.flow.MutableSharedFlow

                        suspend fun doSomething() {
                            val sharedFlow = MutableSharedFlow<Int>(replay = 2)
                        }
                    """
                        .trimIndent()
                ),
                *androidStubs
            )
            .issues(FlowDetector.SHARED_FLOW_CREATION)
            .run()
            .expect(
                """
src/test/pkg/test.kt:6: Error: MutableSharedFlow() creates a new shared flow, which has poor performance characteristics [SharedFlowCreation]
    val sharedFlow = MutableSharedFlow<Int>(replay = 2)
                     ~~~~~~~~~~~~~~~~~
1 errors, 0 warnings
                """,
            )
    }

    @Test
    fun createSharedFlow_viaCallToShareIn_onFlow() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package test.pkg

                        import kotlinx.coroutines.CoroutineScope
                        import kotlinx.coroutines.flow.Flow
                        import kotlinx.coroutines.flow.SharingStarted
                        import kotlinx.coroutines.flow.shareIn

                        suspend fun doSomething(scope: CoroutineScope, someFlow: Flow<Int>) {
                            someFlow.shareIn(scope, SharingStarted.Eagerly)
                        }
                    """
                        .trimIndent()
                ),
                *androidStubs
            )
            .issues(FlowDetector.SHARED_FLOW_CREATION)
            .run()
            .expect(
                """
src/test/pkg/test.kt:9: Error: shareIn() creates a new shared flow, which has poor performance characteristics [SharedFlowCreation]
    someFlow.shareIn(scope, SharingStarted.Eagerly)
             ~~~~~~~
1 errors, 0 warnings
                """,
            )
    }

    @Test
    fun createSharedFlow_viaCallToShareIn_afterOperationChain() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package test.pkg

                        import kotlinx.coroutines.CoroutineScope
                        import kotlinx.coroutines.flow.Flow
                        import kotlinx.coroutines.flow.SharingStarted
                        import kotlinx.coroutines.flow.shareIn
                        import kotlinx.coroutines.flow.onStart
                        import kotlinx.coroutines.flow.map
                        import kotlinx.coroutines.flow.distinctUntilChanged

                        suspend fun doSomething(scope: CoroutineScope, someFlow: Flow<Int>) {
                            someFlow.onStart { emit(PackageChangeModel.Empty) }
                                .map { reloadComponents(userId, packageManager) }
                                .distinctUntilChanged()
                                .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)
                        }
                    """
                        .trimIndent()
                ),
                *androidStubs
            )
            .issues(FlowDetector.SHARED_FLOW_CREATION)
            .run()
            .expect(
                """
src/test/pkg/test.kt:15: Error: shareIn() creates a new shared flow, which has poor performance characteristics [SharedFlowCreation]
        .shareIn(scope, SharingStarted.WhileSubscribed(), replay = 1)
         ~~~~~~~
1 errors, 0 warnings
                """,
            )
    }

    @Test
    fun createSharedFlow_viaCallToShareIn_onStateFlow() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package test.pkg

                        import kotlinx.coroutines.CoroutineScope
                        import kotlinx.coroutines.flow.Flow
                        import kotlinx.coroutines.flow.StateFlow
                        import kotlinx.coroutines.flow.SharingStarted
                        import kotlinx.coroutines.flow.shareIn
                        import kotlinx.coroutines.flow.onStart
                        import kotlinx.coroutines.flow.map
                        import kotlinx.coroutines.flow.distinctUntilChanged

                        suspend fun doSomething(someScope: CoroutineScope, someFlow: StateFlow<Int>) {
                            someFlow.onStart { emit(1) }
                                .map { someOtherFunction() }
                                .distinctUntilChanged()
                                .shareIn(someScope, SharingStarted.WhileSubscribed())
                            someFlow.shareIn(someScope, SharingStarted.Eagerly)
                        }
                    """
                        .trimIndent()
                ),
                *androidStubs
            )
            .issues(FlowDetector.SHARED_FLOW_CREATION)
            .run()
            .expect(
                """
src/test/pkg/test.kt:16: Error: shareIn() creates a new shared flow, which has poor performance characteristics [SharedFlowCreation]
        .shareIn(someScope, SharingStarted.WhileSubscribed())
         ~~~~~~~
src/test/pkg/test.kt:17: Error: shareIn() creates a new shared flow, which has poor performance characteristics [SharedFlowCreation]
    someFlow.shareIn(someScope, SharingStarted.Eagerly)
             ~~~~~~~
2 errors, 0 warnings
                """,
            )
    }

    @Test
    fun createSharedFlow_viaCallToShareIn_onSharedFlow() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                        package test.pkg

                        import kotlinx.coroutines.CoroutineScope
                        import kotlinx.coroutines.flow.Flow
                        import kotlinx.coroutines.flow.SharedFlow
                        import kotlinx.coroutines.flow.SharingStarted
                        import kotlinx.coroutines.flow.shareIn
                        import kotlinx.coroutines.flow.onStart
                        import kotlinx.coroutines.flow.map
                        import kotlinx.coroutines.flow.distinctUntilChanged

                        suspend fun doSomething(someScope: CoroutineScope, someFlow: SharedFlow<Int>) {
                            someFlow.onStart { emit(1) }
                                .map { someOtherFunction() }
                                .distinctUntilChanged()
                                .shareIn(someScope, SharingStarted.WhileSubscribed())
                            someFlow.shareIn(someScope, SharingStarted.Eagerly)
                        }
                    """
                        .trimIndent()
                ),
                *androidStubs
            )
            .issues(FlowDetector.SHARED_FLOW_CREATION)
            .run()
            .expect(
                """
src/test/pkg/test.kt:16: Error: shareIn() creates a new shared flow, which has poor performance characteristics [SharedFlowCreation]
        .shareIn(someScope, SharingStarted.WhileSubscribed())
         ~~~~~~~
src/test/pkg/test.kt:17: Error: shareIn() creates a new shared flow, which has poor performance characteristics [SharedFlowCreation]
    someFlow.shareIn(someScope, SharingStarted.Eagerly)
             ~~~~~~~
2 errors, 0 warnings
                """,
            )
    }
}
