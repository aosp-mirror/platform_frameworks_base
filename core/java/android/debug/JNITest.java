/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.debug;

/**
 * Simple JNI verification test.
 */
public class JNITest {

    public JNITest() {
    }

    public int test(int intArg, double doubleArg, String stringArg) {
        int[] intArray = { 42, 53, 65, 127 };

        return part1(intArg, doubleArg, stringArg, intArray);
    }

    private native int part1(int intArg, double doubleArg, String stringArg,
        int[] arrayArg);

    private int part2(double doubleArg, int fromArray, String stringArg) {
        int result;

        System.out.println(stringArg + " : " + (float) doubleArg + " : " +
            fromArray);
        result = part3(stringArg);

        return result + 6;
    }

    private static native int part3(String stringArg);
}

