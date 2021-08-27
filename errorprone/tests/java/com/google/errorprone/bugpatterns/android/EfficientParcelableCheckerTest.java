/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.google.errorprone.bugpatterns.android;

import com.google.errorprone.CompilationTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class EfficientParcelableCheckerTest {
    private CompilationTestHelper compilationHelper;

    @Before
    public void setUp() {
        compilationHelper = CompilationTestHelper.newInstance(
                EfficientParcelableChecker.class, getClass());
    }

    @Test
    public void testString() {
        compilationHelper
                .addSourceFile("/android/os/Parcel.java")
                .addSourceFile("/android/os/Parcelable.java")
                .addSourceLines("FooInfo.java",
                        "import android.os.Parcel;",
                        "import android.os.Parcelable;",
                        "public class FooInfo implements Parcelable {",
                        "  public void writeToParcel(Parcel dest, int flags) {",
                        "    // BUG: Diagnostic contains:",
                        "    dest.writeString(toString());",
                        "    dest.writeString8(toString());",
                        "    // BUG: Diagnostic contains:",
                        "    dest.writeStringArray(new String[0]);",
                        "    dest.writeString8Array(new String[0]);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testSingle() {
        compilationHelper
                .addSourceFile("/android/os/Parcel.java")
                .addSourceFile("/android/os/Parcelable.java")
                .addSourceLines("FooInfo.java",
                        "import android.os.Parcel;",
                        "import android.os.Parcelable;",
                        "public class FooInfo implements Parcelable {",
                        "  public void writeToParcel(Parcel dest, int flags) {",
                        "    // BUG: Diagnostic contains:",
                        "    dest.writeValue(this);",
                        "    this.writeToParcel(dest, flags);",
                        "    // BUG: Diagnostic contains:",
                        "    dest.writeParcelable(this, flags);",
                        "    this.writeToParcel(dest, flags);",
                        "  }",
                        "}")
                .doTest();
    }

    @Test
    public void testList() {
        compilationHelper
                .addSourceFile("/android/os/Parcel.java")
                .addSourceFile("/android/os/Parcelable.java")
                .addSourceLines("FooInfo.java",
                        "import android.os.Parcel;",
                        "import android.os.Parcelable;",
                        "import java.util.List;",
                        "import java.util.ArrayList;",
                        "public class FooInfo implements Parcelable {",
                        "  public void writeToParcel(Parcel dest, int flags) {",
                        "    List<Parcelable> list = new ArrayList<Parcelable>();",
                        "    Parcelable[] array = new Parcelable[0];",
                        "    // BUG: Diagnostic contains:",
                        "    dest.writeList(list);",
                        "    dest.writeTypedList(list, flags);",
                        "    // BUG: Diagnostic contains:",
                        "    dest.writeParcelableList(list, flags);",
                        "    dest.writeTypedList(list, flags);",
                        "    // BUG: Diagnostic contains:",
                        "    dest.writeParcelableArray(array, flags);",
                        "    dest.writeTypedArray(array, flags);",
                        "  }",
                        "}")
                .doTest();
    }
}
