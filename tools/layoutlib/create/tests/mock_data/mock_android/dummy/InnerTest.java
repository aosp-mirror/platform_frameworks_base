/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package mock_android.dummy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class InnerTest {

    private int mSomeField;
    private MyStaticInnerClass mInnerInstance;
    private MyIntEnum mTheIntEnum;
    private MyGenerics1<int[][], InnerTest, MyIntEnum, float[]> mGeneric1;

    public class NotStaticInner2 extends NotStaticInner1 {

    }

    public class NotStaticInner1 {

        public void someThing() {
            mSomeField = 2;
            mInnerInstance = null;
        }

    }

    private static class MyStaticInnerClass {

    }
    
    private static class DerivingClass extends InnerTest {
        
    }
    
    // enums are a kind of inner static class
    public enum MyIntEnum {
        VALUE0(0),
        VALUE1(1),
        VALUE2(2);

        MyIntEnum(int myInt) {
            this.myInt = myInt;
        }
        final int myInt;
    }
    
    public static class MyGenerics1<T, U, V, W> {
        public MyGenerics1() {
            int a = 1;
        }
    }
    
    public <X> void genericMethod1(X a, X[] b) {
    }

    public <X, Y> void genericMethod2(X a, List<Y> b) {
    }

    public <X, Y extends InnerTest> void genericMethod3(X a, List<Y> b) {
    }

    public <T extends InnerTest> void genericMethod4(T[] a, Collection<T> b, Collection<?> c) {
        Iterator<T> i = b.iterator();
    }

    public void someMethod(InnerTest self) {
        mSomeField = self.mSomeField;
        MyStaticInnerClass m = new MyStaticInnerClass();
        mInnerInstance = m;
        mTheIntEnum = null;
        mGeneric1 = new MyGenerics1();
        genericMethod4(new DerivingClass[0], new ArrayList<DerivingClass>(), new ArrayList<InnerTest>());
    }
}
