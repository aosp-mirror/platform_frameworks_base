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

package com.google.android.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class RegisterReceiverFlagDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = RegisterReceiverFlagDetector()

    override fun getIssues(): List<Issue> = listOf(
            RegisterReceiverFlagDetector.ISSUE_RECEIVER_EXPORTED_FLAG
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    fun testProtectedBroadcast() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.Intent;
                    import android.content.IntentFilter;
                    public class TestClass1 {
                        public void testMethod(Context context, BroadcastReceiver receiver) {
                            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                            context.registerReceiver(receiver, filter);
                        }
                    }
                   """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    fun testProtectedBroadcastCreate() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.Intent;
                    import android.content.IntentFilter;
                    public class TestClass1 {
                        public void testMethod(Context context, BroadcastReceiver receiver) {
                            IntentFilter filter =
                                    IntentFilter.create(Intent.ACTION_BATTERY_CHANGED, "foo/bar");
                            context.registerReceiver(receiver, filter);
                        }
                    }
                   """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    fun testMultipleProtectedBroadcasts() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.Intent;
                    import android.content.IntentFilter;
                    public class TestClass1 {
                        public void testMethod(Context context, BroadcastReceiver receiver) {
                            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                            filter.addAction(Intent.ACTION_BATTERY_LOW);
                            filter.addAction(Intent.ACTION_BATTERY_OKAY);
                            context.registerReceiver(receiver, filter);
                        }
                    }
                   """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    // TODO(b/267510341): Reenable this test
    // fun testSubsequentFilterModification() {
    //     lint().files(
    //             java(
    //                     """
    //                 package test.pkg;
    //                 import android.content.BroadcastReceiver;
    //                 import android.content.Context;
    //                 import android.content.Intent;
    //                 import android.content.IntentFilter;
    //                 public class TestClass1 {
    //                     public void testMethod(Context context, BroadcastReceiver receiver) {
    //                         IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    //                         filter.addAction(Intent.ACTION_BATTERY_LOW);
    //                         filter.addAction(Intent.ACTION_BATTERY_OKAY);
    //                         context.registerReceiver(receiver, filter);
    //                         filter.addAction("querty");
    //                         context.registerReceiver(receiver, filter);
    //                     }
    //                 }
    //                """
    //             ).indented(),
    //             *stubs
    //     )
    //             .run()
    //             .expect("""
    //             src/test/pkg/TestClass1.java:13: Warning: Missing RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag [UnspecifiedRegisterReceiverFlag]
    //                     context.registerReceiver(receiver, filter);
    //                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //             0 errors, 1 warnings
    //         """.trimIndent())
    // }

    fun testNullReceiver() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.Intent;
                    import android.content.IntentFilter;
                    public class TestClass1 {
                        public void testMethod(Context context) {
                            IntentFilter filter = new IntentFilter("qwerty");
                            context.registerReceiver(null, filter);
                        }
                    }
                   """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    fun testExportedFlagPresent() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.Intent;
                    import android.content.IntentFilter;
                    public class TestClass1 {
                        public void testMethod(Context context, BroadcastReceiver receiver) {
                            IntentFilter filter = new IntentFilter("qwerty");
                            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
                        }
                    }
                   """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    fun testNotExportedFlagPresent() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.Intent;
                    import android.content.IntentFilter;
                    public class TestClass1 {
                        public void testMethod(Context context, BroadcastReceiver receiver) {
                            IntentFilter filter = new IntentFilter("qwerty");
                            context.registerReceiver(receiver, filter,
                                    Context.RECEIVER_NOT_EXPORTED);
                        }
                    }
                   """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    // TODO(b/267510341): Reenable this test
    // fun testFlagArgumentAbsent() {
    //     lint().files(
    //             java(
    //                     """
    //                 package test.pkg;
    //                 import android.content.BroadcastReceiver;
    //                 import android.content.Context;
    //                 import android.content.Intent;
    //                 import android.content.IntentFilter;
    //                 public class TestClass1 {
    //                     public void testMethod(Context context, BroadcastReceiver receiver) {
    //                         IntentFilter filter = new IntentFilter("qwerty");
    //                         context.registerReceiver(receiver, filter);
    //                     }
    //                 }
    //                """
    //             ).indented(),
    //             *stubs
    //     )
    //             .run()
    //             .expect("""
    //             src/test/pkg/TestClass1.java:9: Warning: Missing RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag [UnspecifiedRegisterReceiverFlag]
    //                     context.registerReceiver(receiver, filter);
    //                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //             0 errors, 1 warnings
    //         """.trimIndent())
    // }

    // TODO(b/267510341): Reenable this test
    // fun testExportedFlagsAbsent() {
    //     lint().files(
    //             java(
    //                     """
    //                 package test.pkg;
    //                 import android.content.BroadcastReceiver;
    //                 import android.content.Context;
    //                 import android.content.Intent;
    //                 import android.content.IntentFilter;
    //                 public class TestClass1 {
    //                     public void testMethod(Context context, BroadcastReceiver receiver) {
    //                         IntentFilter filter = new IntentFilter("qwerty");
    //                         context.registerReceiver(receiver, filter, 0);
    //                     }
    //                 }
    //                """
    //             ).indented(),
    //             *stubs
    //     )
    //             .run()
    //             .expect("""
    //             src/test/pkg/TestClass1.java:9: Warning: Missing RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag [UnspecifiedRegisterReceiverFlag]
    //                     context.registerReceiver(receiver, filter, 0);
    //                                                                ~
    //             0 errors, 1 warnings
    //         """.trimIndent())
    // }

    fun testExportedFlagVariable() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.Intent;
                    import android.content.IntentFilter;
                    public class TestClass1 {
                        public void testMethod(Context context, BroadcastReceiver receiver) {
                            IntentFilter filter = new IntentFilter("qwerty");
                            var flags = Context.RECEIVER_EXPORTED;
                            context.registerReceiver(receiver, filter, flags);
                        }
                    }
                   """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    // TODO(b/267510341): Reenable this test
    // fun testUnknownFilter() {
    //     lint().files(
    //             java(
    //                     """
    //                 package test.pkg;
    //                 import android.content.BroadcastReceiver;
    //                 import android.content.Context;
    //                 import android.content.Intent;
    //                 import android.content.IntentFilter;
    //                 public class TestClass1 {
    //                     public void testMethod(Context context, BroadcastReceiver receiver,
    //                             IntentFilter filter) {
    //                         context.registerReceiver(receiver, filter);
    //                     }
    //                 }
    //                """
    //             ).indented(),
    //             *stubs
    //     )
    //             .run()
    //             .expect("""
    //             src/test/pkg/TestClass1.java:9: Warning: Missing RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag [UnspecifiedRegisterReceiverFlag]
    //                     context.registerReceiver(receiver, filter);
    //                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //             0 errors, 1 warnings
    //         """.trimIndent())
    // }

    // TODO(b/267510341): Reenable this test
    // fun testFilterEscapes() {
    //     lint().files(
    //             java(
    //                     """
    //                 package test.pkg;
    //                 import android.content.BroadcastReceiver;
    //                 import android.content.Context;
    //                 import android.content.Intent;
    //                 import android.content.IntentFilter;
    //                 public class TestClass1 {
    //                     public void testMethod(Context context, BroadcastReceiver receiver) {
    //                         IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
    //                         updateFilter(filter);
    //                         context.registerReceiver(receiver, filter);
    //                     }
    //                 }
    //                """
    //             ).indented(),
    //             *stubs
    //     )
    //             .run()
    //             .expect("""
    //             src/test/pkg/TestClass1.java:10: Warning: Missing RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag [UnspecifiedRegisterReceiverFlag]
    //                     context.registerReceiver(receiver, filter);
    //                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //             0 errors, 1 warnings
    //         """.trimIndent())
    // }

    fun testInlineFilter() {
        lint().files(
                java(
                        """
                    package test.pkg;
                    import android.content.BroadcastReceiver;
                    import android.content.Context;
                    import android.content.Intent;
                    import android.content.IntentFilter;
                    public class TestClass1 {
                        public void testMethod(Context context, BroadcastReceiver receiver) {
                            context.registerReceiver(receiver,
                                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                        }
                    }
                   """
                ).indented(),
                *stubs
        )
                .run()
                .expectClean()
    }

    // TODO(b/267510341): Reenable this test
    // fun testInlineFilterApply() {
    //     lint().files(
    //             kotlin(
    //                     """
    //                 package test.pkg
    //                 import android.content.BroadcastReceiver
    //                 import android.content.Context
    //                 import android.content.Intent
    //                 import android.content.IntentFilter
    //                 class TestClass1 {
    //                     fun test(context: Context, receiver: BroadcastReceiver) {
    //                         context.registerReceiver(receiver,
    //                                 IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
    //                                     addAction("qwerty")
    //                                 })
    //                     }
    //                 }
    //                """
    //             ).indented(),
    //             *stubs
    //     )
    //             .run()
    //             .expect("""
    //             src/test/pkg/TestClass1.kt:8: Warning: Missing RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag [UnspecifiedRegisterReceiverFlag]
    //                     context.registerReceiver(receiver,
    //                     ^
    //             0 errors, 1 warnings
    //         """.trimIndent())
    // }

    // TODO(b/267510341): Reenable this test
    // fun testFilterVariableApply() {
    //     lint().files(
    //             kotlin(
    //                     """
    //                 package test.pkg
    //                 import android.content.BroadcastReceiver
    //                 import android.content.Context
    //                 import android.content.Intent
    //                 import android.content.IntentFilter
    //                 class TestClass1 {
    //                     fun test(context: Context, receiver: BroadcastReceiver) {
    //                         val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
    //                             addAction("qwerty")
    //                         }
    //                         context.registerReceiver(receiver, filter)
    //                     }
    //                 }
    //                """
    //             ).indented(),
    //             *stubs
    //     )
    //             .run()
    //             .expect("""
    //             src/test/pkg/TestClass1.kt:11: Warning: Missing RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag [UnspecifiedRegisterReceiverFlag]
    //                     context.registerReceiver(receiver, filter)
    //                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //             0 errors, 1 warnings
    //         """.trimIndent())
    // }

    // TODO(b/267510341): Reenable this test
    // fun testFilterVariableApply2() {
    //     lint().files(
    //             kotlin(
    //                     """
    //                 package test.pkg
    //                 import android.content.BroadcastReceiver
    //                 import android.content.Context
    //                 import android.content.Intent
    //                 import android.content.IntentFilter
    //                 class TestClass1 {
    //                     fun test(context: Context, receiver: BroadcastReceiver) {
    //                         val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
    //                             addAction(Intent.ACTION_BATTERY_OKAY)
    //                         }
    //                         context.registerReceiver(receiver, filter.apply {
    //                             addAction("qwerty")
    //                         })
    //                     }
    //                 }
    //                """
    //             ).indented(),
    //             *stubs
    //     )
    //             .run()
    //             .expect("""
    //             src/test/pkg/TestClass1.kt:11: Warning: Missing RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag [UnspecifiedRegisterReceiverFlag]
    //                     context.registerReceiver(receiver, filter.apply {
    //                     ^
    //             0 errors, 1 warnings
    //         """.trimIndent())
    // }

    // TODO(b/267510341): Reenable this test
    // fun testFilterComplexChain() {
    //     lint().files(
    //             kotlin(
    //                     """
    //                 package test.pkg
    //                 import android.content.BroadcastReceiver
    //                 import android.content.Context
    //                 import android.content.Intent
    //                 import android.content.IntentFilter
    //                 class TestClass1 {
    //                     fun test(context: Context, receiver: BroadcastReceiver) {
    //                         val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
    //                             addAction(Intent.ACTION_BATTERY_OKAY)
    //                         }
    //                         val filter2 = filter
    //                         val filter3 = filter2.apply {
    //                             addAction(Intent.ACTION_BATTERY_LOW)
    //                         }
    //                         context.registerReceiver(receiver, filter3)
    //                         val filter4 = filter3.apply {
    //                             addAction("qwerty")
    //                         }
    //                         context.registerReceiver(receiver, filter4)
    //                     }
    //                 }
    //                """
    //             ).indented(),
    //             *stubs
    //     )
    //             .run()
    //             .expect("""
    //             src/test/pkg/TestClass1.kt:19: Warning: Missing RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED flag [UnspecifiedRegisterReceiverFlag]
    //                     context.registerReceiver(receiver, filter4)
    //                     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
    //             0 errors, 1 warnings
    //         """.trimIndent())
    // }

    private val broadcastReceiverStub: TestFile = java(
            """
            package android.content;
            public class BroadcastReceiver {
                // Stub
            }
            """
    ).indented()

    private val contextStub: TestFile = java(
            """
            package android.content;
            public class Context {
                public static final int RECEIVER_EXPORTED = 0x2;
                public static final int RECEIVER_NOT_EXPORTED = 0x4;
                @Nullable
                public abstract Intent registerReceiver(@Nullable BroadcastReceiver receiver,
                                                        IntentFilter filter,
                                                        @RegisterReceiverFlags int flags);
            }
            """
    ).indented()

    private val intentStub: TestFile = java(
            """
            package android.content;
            public class Intent {
                public static final String ACTION_BATTERY_CHANGED =
                        "android.intent.action.BATTERY_CHANGED";
                public static final String ACTION_BATTERY_LOW = "android.intent.action.BATTERY_LOW";
                public static final String ACTION_BATTERY_OKAY =
                        "android.intent.action.BATTERY_OKAY";
            }
            """
    ).indented()

    private val intentFilterStub: TestFile = java(
            """
            package android.content;
            public class IntentFilter {
                public IntentFilter() {
                    // Stub
                }
                public IntentFilter(String action) {
                    // Stub
                }
                public IntentFilter(String action, String dataType) {
                    // Stub
                }
                public static IntentFilter create(String action, String dataType) {
                    return null;
                }
                public final void addAction(String action) {
                    // Stub
                }
            }
            """
    ).indented()

    private val stubs = arrayOf(broadcastReceiverStub, contextStub, intentStub, intentFilterStub)
}
