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

package com.google.android.lint.parcel

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

@Suppress("UnstableApiUsage")
class SaferParcelCheckerTest : LintDetectorTest() {
    override fun getDetector(): Detector = SaferParcelChecker()

    override fun getIssues(): List<Issue> = listOf(
        SaferParcelChecker.ISSUE_UNSAFE_API_USAGE
    )

    override fun lint(): TestLintTask =
        super.lint()
            .allowMissingSdk(true)
            // We don't do partial analysis in the platform
            .skipTestModes(TestMode.PARTIAL)

    /** Parcel Tests */

    fun testParcelDetectUnsafeReadSerializable() {
        lint()
            .files(
                java(
                    """
                        package test.pkg;
                        import android.os.Parcel;
                        import java.io.Serializable;

                        public class TestClass {
                            private TestClass(Parcel p) {
                                Serializable ans = p.readSerializable();
                            }
                        }
                        """
                ).indented(),
                *includes
            )
            .expectIdenticalTestModeOutput(false)
            .run()
            .expect(
                """
                        src/test/pkg/TestClass.java:7: Warning: Unsafe Parcel.readSerializable() \
                        API usage [UnsafeParcelApi]
                                Serializable ans = p.readSerializable();
                                                   ~~~~~~~~~~~~~~~~~~~~
                        0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testParcelDoesNotDetectSafeReadSerializable() {
        lint()
            .files(
                java(
                    """
                        package test.pkg;
                        import android.os.Parcel;
                        import java.io.Serializable;

                        public class TestClass {
                            private TestClass(Parcel p) {
                                String ans = p.readSerializable(null, String.class);
                            }
                        }
                        """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    fun testParcelDetectUnsafeReadArrayList() {
        lint()
            .files(
                java(
                    """
                        package test.pkg;
                        import android.os.Parcel;

                        public class TestClass {
                            private TestClass(Parcel p) {
                                ArrayList ans = p.readArrayList(null);
                            }
                        }
                        """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                        src/test/pkg/TestClass.java:6: Warning: Unsafe Parcel.readArrayList() API \
                        usage [UnsafeParcelApi]
                                ArrayList ans = p.readArrayList(null);
                                                ~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testParcelDoesNotDetectSafeReadArrayList() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        ArrayList<Intent> ans = p.readArrayList(null, Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    fun testParcelDetectUnsafeReadList() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;
                                import java.util.List;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        List<Intent> list = new ArrayList<Intent>();
                                        p.readList(list, null);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                        src/test/pkg/TestClass.java:9: Warning: Unsafe Parcel.readList() API usage \
                        [UnsafeParcelApi]
                                p.readList(list, null);
                                ~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testDParceloesNotDetectSafeReadList() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;
                                import java.util.List;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        List<Intent> list = new ArrayList<Intent>();
                                        p.readList(list, null, Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    fun testParcelDetectUnsafeReadParcelable() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        Intent ans = p.readParcelable(null);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                        src/test/pkg/TestClass.java:7: Warning: Unsafe Parcel.readParcelable() API \
                        usage [UnsafeParcelApi]
                                Intent ans = p.readParcelable(null);
                                             ~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testParcelDoesNotDetectSafeReadParcelable() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        Intent ans = p.readParcelable(null, Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    fun testParcelDetectUnsafeReadParcelableList() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;
                                import java.util.List;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        List<Intent> list = new ArrayList<Intent>();
                                        List<Intent> ans = p.readParcelableList(list, null);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                        src/test/pkg/TestClass.java:9: Warning: Unsafe Parcel.readParcelableList() \
                        API usage [UnsafeParcelApi]
                                List<Intent> ans = p.readParcelableList(list, null);
                                                   ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testParcelDoesNotDetectSafeReadParcelableList() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;
                                import java.util.List;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        List<Intent> list = new ArrayList<Intent>();
                                        List<Intent> ans =
                                                p.readParcelableList(list, null, Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    fun testParcelDetectUnsafeReadSparseArray() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;
                                import android.util.SparseArray;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        SparseArray<Intent> ans = p.readSparseArray(null);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                        src/test/pkg/TestClass.java:8: Warning: Unsafe Parcel.readSparseArray() API\
                         usage [UnsafeParcelApi]
                                SparseArray<Intent> ans = p.readSparseArray(null);
                                                          ~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testParcelDoesNotDetectSafeReadSparseArray() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;
                                import android.util.SparseArray;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        SparseArray<Intent> ans =
                                                p.readSparseArray(null, Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    fun testParcelDetectUnsafeReadSArray() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        Intent[] ans = p.readArray(null);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                        src/test/pkg/TestClass.java:7: Warning: Unsafe Parcel.readArray() API\
                         usage [UnsafeParcelApi]
                                Intent[] ans = p.readArray(null);
                                               ~~~~~~~~~~~~~~~~~
                        0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testParcelDoesNotDetectSafeReadArray() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        Intent[] ans = p.readArray(null, Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    fun testParcelDetectUnsafeReadParcelableSArray() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        Intent[] ans = p.readParcelableArray(null);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                        src/test/pkg/TestClass.java:7: Warning: Unsafe Parcel.readParcelableArray() API\
                         usage [UnsafeParcelApi]
                                Intent[] ans = p.readParcelableArray(null);
                                               ~~~~~~~~~~~~~~~~~~~~~~~~~~~
                        0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testParcelDoesNotDetectSafeReadParcelableArray() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Parcel;

                                public class TestClass {
                                    private TestClass(Parcel p) {
                                        Intent[] ans = p.readParcelableArray(null, Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    /** Bundle Tests */

    fun testBundleDetectUnsafeGetParcelable() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Bundle;

                                public class TestClass {
                                    private TestClass(Bundle b) {
                                        Intent ans = b.getParcelable("key");
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                    src/test/pkg/TestClass.java:7: Warning: Unsafe Bundle.getParcelable() API usage [UnsafeParcelApi]
                            Intent ans = b.getParcelable("key");
                                         ~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testBundleDoesNotDetectSafeGetParcelable() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Bundle;

                                public class TestClass {
                                    private TestClass(Bundle b) {
                                        Intent ans = b.getParcelable("key", Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    fun testBundleDetectUnsafeGetParcelableArrayList() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Bundle;

                                public class TestClass {
                                    private TestClass(Bundle b) {
                                        ArrayList<Intent> ans = b.getParcelableArrayList("key");
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                    src/test/pkg/TestClass.java:7: Warning: Unsafe Bundle.getParcelableArrayList() API usage [UnsafeParcelApi]
                            ArrayList<Intent> ans = b.getParcelableArrayList("key");
                                                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testBundleDoesNotDetectSafeGetParcelableArrayList() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Bundle;

                                public class TestClass {
                                    private TestClass(Bundle b) {
                                        ArrayList<Intent> ans = b.getParcelableArrayList("key", Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    fun testBundleDetectUnsafeGetParcelableArray() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Bundle;

                                public class TestClass {
                                    private TestClass(Bundle b) {
                                        Intent[] ans = b.getParcelableArray("key");
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                    src/test/pkg/TestClass.java:7: Warning: Unsafe Bundle.getParcelableArray() API usage [UnsafeParcelApi]
                            Intent[] ans = b.getParcelableArray("key");
                                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testBundleDoesNotDetectSafeGetParcelableArray() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Bundle;

                                public class TestClass {
                                    private TestClass(Bundle b) {
                                        Intent[] ans = b.getParcelableArray("key", Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    fun testBundleDetectUnsafeGetSparseParcelableArray() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Bundle;

                                public class TestClass {
                                    private TestClass(Bundle b) {
                                        SparseArray<Intent> ans = b.getSparseParcelableArray("key");
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                    src/test/pkg/TestClass.java:7: Warning: Unsafe Bundle.getSparseParcelableArray() API usage [UnsafeParcelApi]
                            SparseArray<Intent> ans = b.getSparseParcelableArray("key");
                                                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testBundleDoesNotDetectSafeGetSparseParcelableArray() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;
                                import android.os.Bundle;

                                public class TestClass {
                                    private TestClass(Bundle b) {
                                        SparseArray<Intent> ans = b.getSparseParcelableArray("key", Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }

    /** Intent Tests */

    fun testIntentDetectUnsafeGetParcelableExtra() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;

                                public class TestClass {
                                    private TestClass(Intent i) {
                                        Intent ans = i.getParcelableExtra("name");
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect(
                """
                    src/test/pkg/TestClass.java:6: Warning: Unsafe Intent.getParcelableExtra() API usage [UnsafeParcelApi]
                            Intent ans = i.getParcelableExtra("name");
                                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                    0 errors, 1 warnings
                        """.addLineContinuation()
            )
    }

    fun testIntentDoesNotDetectSafeGetParcelableExtra() {
        lint()
            .files(
                java(
                    """
                                package test.pkg;
                                import android.content.Intent;

                                public class TestClass {
                                    private TestClass(Intent i) {
                                        Intent ans = i.getParcelableExtra("name", Intent.class);
                                    }
                                }
                                """
                ).indented(),
                *includes
            )
            .run()
            .expect("No warnings.")
    }


    /** Stubs for classes used for testing */


    private val includes =
        arrayOf(
            manifest().minSdk("Tiramisu"),
            java(
                """
                        package android.os;
                        import java.util.ArrayList;
                        import java.util.List;
                        import java.util.Map;
                        import java.util.HashMap;

                        public final class Parcel {
                            // Deprecated
                            public Object[] readArray(ClassLoader loader) { return null; }
                            public ArrayList readArrayList(ClassLoader loader) { return null; }
                            public HashMap readHashMap(ClassLoader loader) { return null; }
                            public void readList(List outVal, ClassLoader loader) {}
                            public void readMap(Map outVal, ClassLoader loader) {}
                            public <T extends Parcelable> T readParcelable(ClassLoader loader) { return null; }
                            public Parcelable[] readParcelableArray(ClassLoader loader) { return null; }
                            public Parcelable.Creator<?> readParcelableCreator(ClassLoader loader) { return null; }
                            public <T extends Parcelable> List<T> readParcelableList(List<T> list, ClassLoader cl) { return null; }
                            public Serializable readSerializable() { return null; }
                            public <T> SparseArray<T> readSparseArray(ClassLoader loader) { return null; }

                            // Replacements
                            public <T> T[] readArray(ClassLoader loader, Class<T> clazz) { return null; }
                            public <T> ArrayList<T> readArrayList(ClassLoader loader, Class<? extends T> clazz) { return null; }
                            public <K, V> HashMap<K,V> readHashMap(ClassLoader loader, Class<? extends K> clazzKey, Class<? extends V> clazzValue) { return null; }
                            public <T> void readList(List<? super T> outVal, ClassLoader loader, Class<T> clazz) {}
                            public <K, V> void readMap(Map<? super K, ? super V> outVal, ClassLoader loader, Class<K> clazzKey, Class<V> clazzValue) {}
                            public <T> T readParcelable(ClassLoader loader, Class<T> clazz) { return null; }
                            public <T> T[] readParcelableArray(ClassLoader loader, Class<T> clazz) { return null; }
                            public <T> Parcelable.Creator<T> readParcelableCreator(ClassLoader loader, Class<T> clazz) { return null; }
                            public <T> List<T> readParcelableList(List<T> list, ClassLoader cl, Class<T> clazz) { return null; }
                            public <T> T readSerializable(ClassLoader loader, Class<T> clazz) { return null; }
                            public <T> SparseArray<T> readSparseArray(ClassLoader loader, Class<? extends T> clazz) { return null; }
                        }
                        """
            ).indented(),
            java(
                """
                        package android.os;
                        import java.util.ArrayList;
                        import java.util.List;
                        import java.util.Map;
                        import java.util.HashMap;

                        public final class Bundle {
                            // Deprecated
                            public <T extends Parcelable> T getParcelable(String key) { return  null; }
                            public <T extends Parcelable> ArrayList<T> getParcelableArrayList(String key) { return null; }
                            public Parcelable[] getParcelableArray(String key) { return null; }
                            public <T extends Parcelable> SparseArray<T> getSparseParcelableArray(String key) { return null; }

                            // Replacements
                            public <T> T getParcelable(String key, Class<T> clazz) { return  null; }
                            public <T> ArrayList<T> getParcelableArrayList(String key, Class<? extends T> clazz) { return null; }
                            public <T> T[] getParcelableArray(String key, Class<T> clazz) { return null; }
                            public <T> SparseArray<T> getSparseParcelableArray(String key, Class<? extends T> clazz) { return null; }

                        }
                        """
            ).indented(),
            java(
                """
                        package android.os;
                        public interface Parcelable {}
                        """
            ).indented(),
            java(
                """
                        package android.content;
                        public class Intent implements Parcelable, Cloneable {
                            // Deprecated
                            public <T extends Parcelable> T getParcelableExtra(String name) { return null; }

                            // Replacements
                            public <T> T getParcelableExtra(String name, Class<T> clazz) { return null; }

                        }
                        """
            ).indented(),
            java(
                """
                        package android.util;
                        public class SparseArray<E> implements Cloneable {}
                        """
            ).indented(),
        )

    // Substitutes "backslash + new line" with an empty string to imitate line continuation
    private fun String.addLineContinuation(): String = this.trimIndent().replace("\\\n", "")
}
