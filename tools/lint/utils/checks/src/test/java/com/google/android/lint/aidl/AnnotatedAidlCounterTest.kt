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

package com.google.android.lint.aidl

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class AnnotatedAidlCounterTest : LintDetectorTest() {
    override fun getDetector(): Detector = AnnotatedAidlCounter()

    override fun getIssues(): List<Issue> = listOf(
        AnnotatedAidlCounter.ISSUE_ANNOTATED_AIDL_COUNTER,
    )

    override fun lint(): TestLintTask = super.lint().allowMissingSdk(true)

    /** No issue scenario */

    fun testDoesNotDetectIssuesCorrectAnnotationOnMethod() {
        lint().files(java(
            """
            package test.pkg;
            import android.annotation.EnforcePermission;
            public class TestClass2 extends IFooMethod.Stub {
                @Override
                @EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
                public void testMethod() {}
            }
            """).indented(),
                *stubs
        )
        .run()
        .expect("""
        app: Information: module app => Stat(unannotated=0, enforced=1, notRequired=0) [AnnotatedAidlCounter]
        0 errors, 0 warnings
        """)
    }

    // A service with permission annotation on the method.
    private val interfaceIFooMethodStub: TestFile = java(
        """
        public interface IFooMethod extends android.os.IInterface {
         public static abstract class Stub extends android.os.Binder implements IFooMethod {}
          @android.annotation.EnforcePermission(android.Manifest.permission.READ_PHONE_STATE)
          public void testMethod();
        }
        """
    ).indented()

    // A service without any permission annotation.
    private val interfaceIBarStub: TestFile = java(
        """
        public interface IBar extends android.os.IInterface {
         public static abstract class Stub extends android.os.Binder implements IBar {
            @Override
            public void testMethod() {}
          }
          public void testMethod();
        }
        """
    ).indented()

    private val manifestPermissionStub: TestFile = java(
        """
        package android.Manifest;
        class permission {
          public static final String READ_PHONE_STATE = "android.permission.READ_PHONE_STATE";
        }
        """
    ).indented()

    private val enforcePermissionAnnotationStub: TestFile = java(
        """
        package android.annotation;
        public @interface EnforcePermission {}
        """
    ).indented()

    private val stubs = arrayOf(interfaceIFooMethodStub, interfaceIBarStub,
            manifestPermissionStub, enforcePermissionAnnotationStub)
}
