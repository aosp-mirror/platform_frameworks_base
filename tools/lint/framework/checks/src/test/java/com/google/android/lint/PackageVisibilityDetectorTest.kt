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

package com.google.android.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class PackageVisibilityDetectorTest : LintDetectorTest() {
    override fun getDetector(): Detector = PackageVisibilityDetector()

    override fun getIssues(): MutableList<Issue> = mutableListOf(
        PackageVisibilityDetector.ISSUE_PACKAGE_NAME_NO_PACKAGE_VISIBILITY_FILTERS
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    fun testDetectIssuesParameterDoesNotApplyPackageVisibilityFilters() {
        lint().files(java(
            """
            package com.android.server.lint.test;
            import android.internal.test.IFoo;

            public class TestClass extends IFoo.Stub {
                @Override
                public boolean hasPackage(String packageName) {
                    return packageName != null;
                }
            }
            """).indented(), *stubs
        ).run().expect(
                """
                src/com/android/server/lint/test/TestClass.java:6: Warning: \
                Api: hasPackage contains a package name parameter: packageName does not apply \
                package visibility filtering rules. \
                [ApiMightLeakAppVisibility]
                    public boolean hasPackage(String packageName) {
                                              ~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """.addLineContinuation()
        )
    }

    fun testDoesNotDetectIssuesApiInvokesAppOps() {
        lint().files(java(
            """
            package com.android.server.lint.test;
            import android.app.AppOpsManager;
            import android.os.Binder;
            import android.internal.test.IFoo;

            public class TestClass extends IFoo.Stub {
                private AppOpsManager mAppOpsManager;

                @Override
                public boolean hasPackage(String packageName) {
                    checkPackage(packageName);
                    return packageName != null;
                }

                private void checkPackage(String packageName) {
                    mAppOpsManager.checkPackage(Binder.getCallingUid(), packageName);
                }
            }
            """
        ).indented(), *stubs).run().expectClean()
    }

    fun testDoesNotDetectIssuesApiInvokesEnforcePermission() {
        lint().files(java(
            """
            package com.android.server.lint.test;
            import android.content.Context;
            import android.internal.test.IFoo;

            public class TestClass extends IFoo.Stub {
                private Context mContext;

                @Override
                public boolean hasPackage(String packageName) {
                    enforcePermission();
                    return packageName != null;
                }

                private void enforcePermission() {
                    mContext.checkCallingPermission(
                            android.Manifest.permission.ACCESS_INPUT_FLINGER);
                }
            }
            """
        ).indented(), *stubs).run().expectClean()
    }

    fun testDoesNotDetectIssuesApiInvokesPackageManager() {
        lint().files(java(
            """
            package com.android.server.lint.test;
            import android.content.pm.PackageInfo;
            import android.content.pm.PackageManager;
            import android.internal.test.IFoo;

            public class TestClass extends IFoo.Stub {
                private PackageManager mPackageManager;

                @Override
                public boolean hasPackage(String packageName) {
                    return getPackageInfo(packageName) != null;
                }

                private PackageInfo getPackageInfo(String packageName) {
                    try {
                        return mPackageManager.getPackageInfo(packageName, 0);
                    } catch (PackageManager.NameNotFoundException e) {
                        return null;
                    }
                }
            }
            """
        ).indented(), *stubs).run().expectClean()
    }

    fun testDetectIssuesApiInvokesPackageManagerAndClearCallingIdentify() {
        lint().files(java(
            """
            package com.android.server.lint.test;
            import android.content.pm.PackageInfo;
            import android.content.pm.PackageManager;
            import android.internal.test.IFoo;import android.os.Binder;

            public class TestClass extends IFoo.Stub {
                private PackageManager mPackageManager;

                @Override
                public boolean hasPackage(String packageName) {
                    return getPackageInfo(packageName) != null;
                }

                private PackageInfo getPackageInfo(String packageName) {
                    long token = Binder.clearCallingIdentity();
                    try {
                        try {
                            return mPackageManager.getPackageInfo(packageName, 0);
                        } catch (PackageManager.NameNotFoundException e) {
                            return null;
                        }
                    } finally{
                        Binder.restoreCallingIdentity(token);
                    }
                }
            }
            """).indented(), *stubs
        ).run().expect(
                """
                src/com/android/server/lint/test/TestClass.java:10: Warning: \
                Api: hasPackage contains a package name parameter: packageName does not apply \
                package visibility filtering rules. \
                [ApiMightLeakAppVisibility]
                    public boolean hasPackage(String packageName) {
                                              ~~~~~~~~~~~~~~~~~~
                0 errors, 1 warnings
                """.addLineContinuation()
        )
    }

    fun testDoesNotDetectIssuesApiNotSystemPackagePrefix() {
        lint().files(java(
            """
            package com.test.not.system.prefix;
            import android.internal.test.IFoo;

            public class TestClass extends IFoo.Stub {
                @Override
                public boolean hasPackage(String packageName) {
                    return packageName != null;
                }
            }
            """
        ).indented(), *stubs).run().expectClean()
    }

    private val contextStub: TestFile = java(
        """
        package android.content;

        public abstract class Context {
            public abstract int checkCallingPermission(String permission);
        }
        """
    ).indented()

    private val appOpsManagerStub: TestFile = java(
        """
        package android.app;

        public class AppOpsManager {
            public void checkPackage(int uid, String packageName) {
            }
        }
        """
    ).indented()

    private val packageManagerStub: TestFile = java(
        """
        package android.content.pm;
        import android.content.pm.PackageInfo;

        public abstract class PackageManager {
            public static class NameNotFoundException extends AndroidException {
            }

            public abstract PackageInfo getPackageInfo(String packageName, int flags)
                    throws NameNotFoundException;
        }
        """
    ).indented()

    private val packageInfoStub: TestFile = java(
        """
        package android.content.pm;
        public class PackageInfo {}
        """
    ).indented()

    private val binderStub: TestFile = java(
        """
        package android.os;

        public class Binder {
            public static final native long clearCallingIdentity();
            public static final native void restoreCallingIdentity(long token);
            public static final native int getCallingUid();
        }
        """
    ).indented()

    private val interfaceIFooStub: TestFile = java(
        """
        package android.internal.test;
        import android.os.Binder;

        public interface IFoo {
            boolean hasPackage(String packageName);
            public abstract static class Stub extends Binder implements IFoo {
            }
        }
        """
    ).indented()

    private val stubs = arrayOf(contextStub, appOpsManagerStub, packageManagerStub,
        packageInfoStub, binderStub, interfaceIFooStub)

    // Substitutes "backslash + new line" with an empty string to imitate line continuation
    private fun String.addLineContinuation(): String = this.trimIndent().replace("\\\n", "")
}
