/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

@Suppress("UnstableApiUsage")
class BindServiceOnMainThreadDetectorTest : SystemUILintDetectorTest() {

    override fun getDetector(): Detector = BindServiceOnMainThreadDetector()

    override fun getIssues(): List<Issue> = listOf(BindServiceOnMainThreadDetector.ISSUE)

    @Test
    fun testBindService() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;

                    public class TestClass {
                        public void bind(Context context) {
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          context.bindService(intent, null, 0);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BindServiceOnMainThreadDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:7: Warning: This method should be annotated with @WorkerThread because it calls bindService [BindServiceOnMainThread]
                      context.bindService(intent, null, 0);
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testBindServiceAsUser() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.os.UserHandle;

                    public class TestClass {
                        public void bind(Context context) {
                          Intent intent = new Intent(Intent.ACTION_VIEW);
                          context.bindServiceAsUser(intent, null, 0, UserHandle.ALL);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BindServiceOnMainThreadDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:8: Warning: This method should be annotated with @WorkerThread because it calls bindServiceAsUser [BindServiceOnMainThread]
                      context.bindServiceAsUser(intent, null, 0, UserHandle.ALL);
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testUnbindService() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.content.ServiceConnection;

                    public class TestClass {
                        public void unbind(Context context, ServiceConnection connection) {
                          context.unbindService(connection);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BindServiceOnMainThreadDetector.ISSUE)
            .run()
            .expect(
                """
                src/test/pkg/TestClass.java:7: Warning: This method should be annotated with @WorkerThread because it calls unbindService [BindServiceOnMainThread]
                      context.unbindService(connection);
                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    @Test
    fun testSuppressUnbindService() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.content.ServiceConnection;

                    @SuppressLint("BindServiceOnMainThread")
                    public class TestClass {
                        public void unbind(Context context, ServiceConnection connection) {
                          context.unbindService(connection);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BindServiceOnMainThreadDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testWorkerMethod() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.content.ServiceConnection;
                    import androidx.annotation.WorkerThread;

                    public class TestClass {
                        @WorkerThread
                        public void unbind(Context context, ServiceConnection connection) {
                          context.unbindService(connection);
                        }
                    }

                    public class ChildTestClass extends TestClass {
                        @Override
                        public void unbind(Context context, ServiceConnection connection) {
                          context.unbindService(connection);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BindServiceOnMainThreadDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun testWorkerClass() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;
                    import android.content.Context;
                    import android.content.ServiceConnection;
                    import androidx.annotation.WorkerThread;

                    @WorkerThread
                    public class TestClass {
                        public void unbind(Context context, ServiceConnection connection) {
                          context.unbindService(connection);
                        }
                    }

                    public class ChildTestClass extends TestClass {
                        @Override
                        public void unbind(Context context, ServiceConnection connection) {
                          context.unbindService(connection);
                        }

                        public void bind(Context context, ServiceConnection connection) {
                          context.bind(connection);
                        }
                    }
                """
                    )
                    .indented(),
                *stubs
            )
            .issues(BindServiceOnMainThreadDetector.ISSUE)
            .run()
            .expectClean()
    }

    private val stubs = androidStubs
}
