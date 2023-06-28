/*
 * Copyright (C) 2023 The Android Open Source Project
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
class DumpableNotRegisteredDetectorTest : SystemUILintDetectorTest() {
    override fun getDetector(): Detector = DumpableNotRegisteredDetector()

    override fun getIssues(): List<Issue> = listOf(DumpableNotRegisteredDetector.ISSUE)

    @Test
    fun classIsNotDumpable_noViolation() {
        lint()
            .files(
                TestFiles.java(
                    """
                    package test.pkg;

                    class SomeClass() {
                    }
                """
                        .trimIndent()
                ),
                *stubs,
            )
            .issues(DumpableNotRegisteredDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun classIsDumpable_andRegisterIsCalled_noViolation() {
        lint()
            .files(
                TestFiles.java(
                    """
                    package test.pkg;

                    import com.android.systemui.Dumpable;
                    import com.android.systemui.dump.DumpManager;

                    public class SomeClass implements Dumpable {
                        SomeClass(DumpManager dumpManager) {
                            dumpManager.registerDumpable(this);
                        }

                        @Override
                        void dump(PrintWriter pw, String[] args) {
                            pw.println("testDump");
                        }
                    }
                """
                        .trimIndent()
                ),
                *stubs,
            )
            .issues(DumpableNotRegisteredDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun classIsDumpable_andRegisterNormalIsCalled_noViolation() {
        lint()
            .files(
                TestFiles.java(
                    """
                    package test.pkg;

                    import com.android.systemui.Dumpable;
                    import com.android.systemui.dump.DumpManager;

                    public class SomeClass implements Dumpable {
                        SomeClass(DumpManager dumpManager) {
                            dumpManager.registerNormalDumpable(this);
                        }

                        @Override
                        void dump(PrintWriter pw, String[] args) {
                            pw.println("testDump");
                        }
                    }
                """
                        .trimIndent()
                ),
                *stubs,
            )
            .issues(DumpableNotRegisteredDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun classIsDumpable_andRegisterCriticalIsCalled_noViolation() {
        lint()
            .files(
                TestFiles.java(
                    """
                    package test.pkg;

                    import com.android.systemui.Dumpable;
                    import com.android.systemui.dump.DumpManager;

                    public class SomeClass implements Dumpable {
                        SomeClass(DumpManager dumpManager) {
                            dumpManager.registerCriticalDumpable(this);
                        }

                        @Override
                        void dump(PrintWriter pw, String[] args) {
                            pw.println("testDump");
                        }
                    }
                """
                        .trimIndent()
                ),
                *stubs,
            )
            .issues(DumpableNotRegisteredDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun classIsDumpable_noRegister_violation() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;

                    import com.android.systemui.Dumpable;

                    public class SomeClass implements Dumpable {
                        @Override
                        public void dump() {
                        }
                    }
                """
                    )
                    .indented(),
                *stubs,
            )
            .issues(DumpableNotRegisteredDetector.ISSUE)
            .run()
            .expect(
                ("""
                src/test/pkg/SomeClass.java:5: Warning: Any class implementing Dumpable must call DumpManager.registerNormalDumpable or DumpManager.registerCriticalDumpable [DumpableNotRegistered]
                public class SomeClass implements Dumpable {
                             ~~~~~~~~~
                0 errors, 1 warnings
                """)
                    .trimIndent()
            )
    }

    @Test
    fun classIsDumpable_usesNotDumpManagerMethod_violation() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;

                    import com.android.systemui.Dumpable;
                    import com.android.systemui.OtherRegistrationObject;

                    public class SomeClass implements Dumpable {
                        public SomeClass(OtherRegistrationObject otherRegistrationObject) {
                            otherRegistrationObject.registerDumpable(this);
                        }
                        @Override
                        public void dump() {
                        }
                    }
                """
                    )
                    .indented(),
                *stubs,
            )
            .issues(DumpableNotRegisteredDetector.ISSUE)
            .run()
            .expect(
                ("""
                src/test/pkg/SomeClass.java:6: Warning: Any class implementing Dumpable must call DumpManager.registerNormalDumpable or DumpManager.registerCriticalDumpable [DumpableNotRegistered]
                public class SomeClass implements Dumpable {
                             ~~~~~~~~~
                0 errors, 1 warnings
                """)
                    .trimIndent()
            )
    }

    @Test
    fun classIsDumpableAndCoreStartable_noRegister_noViolation() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;

                    import com.android.systemui.Dumpable;
                    import com.android.systemui.CoreStartable;

                    public class SomeClass implements Dumpable, CoreStartable {
                        @Override
                        public void start() {
                        }

                        @Override
                        public void dump() {
                        }
                    }
                """
                    )
                    .indented(),
                *stubs,
            )
            .issues(DumpableNotRegisteredDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun classIsAbstract_noRegister_noViolation() {
        lint()
            .files(
                TestFiles.java(
                        """
                    package test.pkg;

                    import com.android.systemui.Dumpable;

                    public abstract class SomeClass implements Dumpable {
                        void abstractMethodHere();

                        @Override
                        public void dump() {
                        }
                    }
                """
                    )
                    .indented(),
                *stubs,
            )
            .issues(DumpableNotRegisteredDetector.ISSUE)
            .run()
            .expectClean()
    }

    companion object {
        private val DUMPABLE_STUB =
            TestFiles.java(
                    """
                    package com.android.systemui;

                    import com.android.systemui.dump.DumpManager;
                    import java.io.PrintWriter;

                    public interface Dumpable {
                        void dump();
                    }
                """
                )
                .indented()

        private val DUMP_MANAGER_STUB =
            TestFiles.java(
                    """
                    package com.android.systemui.dump;

                    public interface DumpManager {
                        void registerDumpable(Dumpable module);
                        void registerNormalDumpable(Dumpable module);
                        void registerCriticalDumpable(Dumpable module);
                    }
                """
                )
                .indented()

        private val OTHER_REGISTRATION_OBJECT_STUB =
            TestFiles.java(
                    """
                    package com.android.systemui;

                    public interface OtherRegistrationObject {
                        void registerDumpable(Dumpable module);
                    }
                """
                )
                .indented()

        private val CORE_STARTABLE_STUB =
            TestFiles.java(
                    """
                    package com.android.systemui;

                    public interface CoreStartable {
                        void start();
                    }
                """
                )
                .indented()

        private val stubs =
            arrayOf(
                DUMPABLE_STUB,
                DUMP_MANAGER_STUB,
                OTHER_REGISTRATION_OBJECT_STUB,
                CORE_STARTABLE_STUB,
            )
    }
}
