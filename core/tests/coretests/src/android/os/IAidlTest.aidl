/* //device/apps/AndroidTests/src/com.android.unit_tests/IAidlTest.aidl
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

package android.os;

import android.os.AidlTest;

interface IAidlTest {
    int intMethod(int a);

    AidlTest.TestParcelable parcelableIn(in AidlTest.TestParcelable p);
    AidlTest.TestParcelable parcelableOut(out AidlTest.TestParcelable p);
    AidlTest.TestParcelable parcelableInOut(inout AidlTest.TestParcelable p);

    AidlTest.TestParcelable listParcelableLonger(
                    inout List<AidlTest.TestParcelable> list, int index);
    int listParcelableShorter(
                    inout List<AidlTest.TestParcelable> list, int index);

    boolean[] booleanArray(in boolean[] a0, out boolean[] a1, inout boolean[] a2);
    char[] charArray(in char[] a0, out char[] a1, inout char[] a2);
    int[] intArray(in int[] a0, out int[] a1, inout int[] a2);
    long[] longArray(in long[] a0, out long[] a1, inout long[] a2);
    float[] floatArray(in float[] a0, out float[] a1, inout float[] a2);
    double[] doubleArray(in double[] a0, out double[] a1, inout double[] a2);
    String[] stringArray(in String[] a0, out String[] a1, inout String[] a2);
    AidlTest.TestParcelable[] parcelableArray(in AidlTest.TestParcelable[] a0,
                                          out AidlTest.TestParcelable[] a1,
                                          inout AidlTest.TestParcelable[] a2);
                                          
    void voidSecurityException();
    int intSecurityException();
}
