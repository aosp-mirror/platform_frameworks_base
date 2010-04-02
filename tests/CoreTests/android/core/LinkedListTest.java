/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.core;

import android.test.PerformanceTestBase;
import android.test.PerformanceTestCase;
import java.util.LinkedList;
import java.util.ListIterator;

/**
 * This class contains performance tests for methods in java.util.LinkedList
 * 
 */
@SuppressWarnings("unchecked")
public class LinkedListTest extends PerformanceTestBase {
    public static final int ITERATIONS = 1000;
    LinkedList<Integer> mLinkedList;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mLinkedList = new LinkedList();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            mLinkedList.add(i);
        }
    }

    @Override
    public int startPerformance(PerformanceTestCase.Intermediates intermediates) {
        intermediates.setInternalIterations(ITERATIONS);
        return 0;
    }

    public void testLinkedListAdd() {
        LinkedList<Integer> list = new LinkedList();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            list.add(i);
            list.add(i);
            list.add(i);
            list.add(i);
            list.add(i);
            list.add(i);
            list.add(i);
            list.add(i);
            list.add(i);
            list.add(i);
        }
    }

    public void testLinkedListAdd1() {
        LinkedList<Integer> list = new LinkedList();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            list.add(0, i);
            list.add(0, i);
            list.add(0, i);
            list.add(0, i);
            list.add(0, i);
            list.add(0, i);
            list.add(0, i);
            list.add(0, i);
            list.add(0, i);
            list.add(0, i);
        }
    }

    public void testLinkedListToArray() {
        Object array;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            array = list.toArray();
            array = list.toArray();
            array = list.toArray();
            array = list.toArray();
            array = list.toArray();
            array = list.toArray();
            array = list.toArray();
            array = list.toArray();
            array = list.toArray();
            array = list.toArray();
        }
    }

    public void testLinkedListSize() {
        LinkedList<Integer> list = mLinkedList;
        int len;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            len = list.size();
            len = list.size();
            len = list.size();
            len = list.size();
            len = list.size();
            len = list.size();
            len = list.size();
            len = list.size();
            len = list.size();
            len = list.size();
        }
    }

    public void testLinkedListGet() {
        int element;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            element = list.get(i);
            element = list.get(i);
            element = list.get(i);
            element = list.get(i);
            element = list.get(i);
            element = list.get(i);
            element = list.get(i);
            element = list.get(i);
            element = list.get(i);
            element = list.get(i);
        }
    }

    public void testLinkedListContains() {
        boolean flag;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            flag = list.contains(i);
            flag = list.contains(i);
            flag = list.contains(i);
            flag = list.contains(i);
            flag = list.contains(i);
            flag = list.contains(i);
            flag = list.contains(i);
            flag = list.contains(i);
            flag = list.contains(i);
            flag = list.contains(i);
        }
    }

    public void testLinkedListToArray1() {
        Integer[] rArray = new Integer[100];
        Integer[] array;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            array = list.toArray(rArray);
            array = list.toArray(rArray);
            array = list.toArray(rArray);
            array = list.toArray(rArray);
            array = list.toArray(rArray);
            array = list.toArray(rArray);
            array = list.toArray(rArray);
            array = list.toArray(rArray);
            array = list.toArray(rArray);
            array = list.toArray(rArray);
        }
    }

    public void testLinkedListSet() {
        LinkedList<Integer> list = mLinkedList;
        int value1 = 500, value2 = 0;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            list.set(value1, value2);
            list.set(value1, value2);
            list.set(value1, value2);
            list.set(value1, value2);
            list.set(value1, value2);
            list.set(value1, value2);
            list.set(value1, value2);
            list.set(value1, value2);
            list.set(value1, value2);
            list.set(value1, value2);
        }
    }

    public void testLinkedListIndexOf() {
        int index;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            index = list.indexOf(0);
            index = list.indexOf(0);
            index = list.indexOf(0);
            index = list.indexOf(0);
            index = list.indexOf(0);
            index = list.indexOf(0);
            index = list.indexOf(0);
            index = list.indexOf(0);
            index = list.indexOf(0);
            index = list.indexOf(0);

        }
    }

    public void testLinkedListLastIndexOf() {
        int index;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            index = list.lastIndexOf(0);
            index = list.lastIndexOf(0);
            index = list.lastIndexOf(0);
            index = list.lastIndexOf(0);
            index = list.lastIndexOf(0);
            index = list.lastIndexOf(0);
            index = list.lastIndexOf(0);
            index = list.lastIndexOf(0);
            index = list.lastIndexOf(0);
            index = list.lastIndexOf(0);
        }
    }

    public void testLinkedListRemove() {
        int index;
        LinkedList<Integer> list = new LinkedList(mLinkedList);
        for (int i = 10; i > 0; i--) {
            index = list.remove();
            index = list.remove();
            index = list.remove();
            index = list.remove();
            index = list.remove();
            index = list.remove();
            index = list.remove();
            index = list.remove();
            index = list.remove();
            index = list.remove();
        }
    }

    public void testLinkedListRemove1() {
        int index;
        LinkedList<Integer> list = new LinkedList(mLinkedList);
        for (int i = 10; i > 0; i--) {
            index = list.remove(0);
            index = list.remove(0);
            index = list.remove(0);
            index = list.remove(0);
            index = list.remove(0);
            index = list.remove(0);
            index = list.remove(0);
            index = list.remove(0);
            index = list.remove(0);
            index = list.remove(0);
        }
    }

    public void testLinkedListRemoveFirst() {
        int index;
        LinkedList<Integer> list = new LinkedList(mLinkedList);
        for (int i = 10; i > 0; i--) {
            index = list.removeFirst();
            index = list.removeFirst();
            index = list.removeFirst();
            index = list.removeFirst();
            index = list.removeFirst();
            index = list.removeFirst();
            index = list.removeFirst();
            index = list.removeFirst();
            index = list.removeFirst();
            index = list.removeFirst();
        }
    }

    public void testLinkedListRemoveLast() {
        int index;
        LinkedList<Integer> list = new LinkedList(mLinkedList);
        for (int i = 10; i > 0; i--) {
            index = list.removeLast();
            index = list.removeLast();
            index = list.removeLast();
            index = list.removeLast();
            index = list.removeLast();
            index = list.removeLast();
            index = list.removeLast();
            index = list.removeLast();
            index = list.removeLast();
            index = list.removeLast();
        }
    }

    public void testLinkedListAddAll() {
        LinkedList<Integer> mList = mLinkedList;
        boolean flag;
        LinkedList<Integer> list = new LinkedList();
        for (int i = 10; i > 0; i--) {
            flag = list.addAll(mList);
            flag = list.addAll(mList);
            flag = list.addAll(mList);
            flag = list.addAll(mList);
            flag = list.addAll(mList);
            flag = list.addAll(mList);
            flag = list.addAll(mList);
            flag = list.addAll(mList);
            flag = list.addAll(mList);
            flag = list.addAll(mList);
        }
    }

    public void testLinkedListRemove2() {
        LinkedList<String> list;
        String s = new String("a");
        list = new LinkedList();
        for (int j = 1000; j > 0; j--) {
            list.add("a");
            list.add("b");
        }
        boolean flag;
        for (int i = 10; i > 0; i--) {
            flag = list.remove(s);
            flag = list.remove(s);
            flag = list.remove(s);
            flag = list.remove(s);
            flag = list.remove(s);
            flag = list.remove(s);
            flag = list.remove(s);
            flag = list.remove(s);
            flag = list.remove(s);
            flag = list.remove(s);
        }
    }

    public void testLinkedListAddAll1() {
        LinkedList<Integer> mList = new LinkedList();
        int pos = 0;
        boolean flag;
        LinkedList<Integer> list = mLinkedList;
        for (int i = 0; i < 10; i++) {
            flag = mList.addAll(pos, list);
            flag = mList.addAll(pos, list);
            flag = mList.addAll(pos, list);
            flag = mList.addAll(pos, list);
            flag = mList.addAll(pos, list);
            flag = mList.addAll(pos, list);
            flag = mList.addAll(pos, list);
            flag = mList.addAll(pos, list);
            flag = mList.addAll(pos, list);
            flag = mList.addAll(pos, list);
        }
    }

    public void testLinkedListClone() {
        Object rObj;
        LinkedList<Integer> list = mLinkedList;
        for (int i = 100; i > 0; i--) {
            rObj = list.clone();
            rObj = list.clone();
            rObj = list.clone();
            rObj = list.clone();
            rObj = list.clone();
            rObj = list.clone();
            rObj = list.clone();
            rObj = list.clone();
            rObj = list.clone();
            rObj = list.clone();
        }
    }

    public void testLinkedListHashcode() {
        int element;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            element = list.hashCode();
            element = list.hashCode();
            element = list.hashCode();
            element = list.hashCode();
            element = list.hashCode();
            element = list.hashCode();
            element = list.hashCode();
            element = list.hashCode();
            element = list.hashCode();
            element = list.hashCode();
        }
    }

    public void testLinkedListElement() {
        int element;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            element = list.element();
            element = list.element();
            element = list.element();
            element = list.element();
            element = list.element();
            element = list.element();
            element = list.element();
            element = list.element();
            element = list.element();
            element = list.element();
        }
    }

    public void testLinkedListToString() {
        String str;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            str = list.toString();
            str = list.toString();
            str = list.toString();
            str = list.toString();
            str = list.toString();
            str = list.toString();
            str = list.toString();
            str = list.toString();
            str = list.toString();
            str = list.toString();
        }
    }

    public void testLinkedListIsEmpty() {
        boolean flag;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            flag = list.isEmpty();
            flag = list.isEmpty();
            flag = list.isEmpty();
            flag = list.isEmpty();
            flag = list.isEmpty();
            flag = list.isEmpty();
            flag = list.isEmpty();
            flag = list.isEmpty();
            flag = list.isEmpty();
            flag = list.isEmpty();
        }
    }

    public void testLinkedListOffer() {
        LinkedList<Integer> list = new LinkedList();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            list.offer(i);
            list.offer(i);
            list.offer(i);
            list.offer(i);
            list.offer(i);
            list.offer(i);
            list.offer(i);
            list.offer(i);
            list.offer(i);
            list.offer(i);
        }
    }

    public void testLinkedListPeek() {
        int element;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            element = list.peek();
            element = list.peek();
            element = list.peek();
            element = list.peek();
            element = list.peek();
            element = list.peek();
            element = list.peek();
            element = list.peek();
            element = list.peek();
            element = list.peek();
        }
    }

    public void testLinkedListPoll() {
        int element;
        LinkedList<Integer> list = new LinkedList(mLinkedList);
        for (int i = 10; i > 0; i--) {
            element = list.poll();
            element = list.poll();
            element = list.poll();
            element = list.poll();
            element = list.poll();
            element = list.poll();
            element = list.poll();
            element = list.poll();
            element = list.poll();
            element = list.poll();
        }
    }

    public void testLinkedListAddLast() {
        LinkedList<Integer> list = new LinkedList();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            list.addLast(i);
            list.addLast(i);
            list.addLast(i);
            list.addLast(i);
            list.addLast(i);
            list.addLast(i);
            list.addLast(i);
            list.addLast(i);
            list.addLast(i);
            list.addLast(i);
        }
    }

    public void testLinkedListAddFirst() {
        LinkedList<Integer> list = new LinkedList();
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            list.addFirst(i);
            list.addFirst(i);
            list.addFirst(i);
            list.addFirst(i);
            list.addFirst(i);
            list.addFirst(i);
            list.addFirst(i);
            list.addFirst(i);
            list.addFirst(i);
            list.addFirst(i);
        }
    }

    public void testLinkedListIterator() {
        ListIterator iterator;
        LinkedList<Integer> list = mLinkedList;
        for (int i = ITERATIONS - 1; i >= 0; i--) {
            iterator = list.listIterator();
            iterator = list.listIterator();
            iterator = list.listIterator();
            iterator = list.listIterator();
            iterator = list.listIterator();
            iterator = list.listIterator();
            iterator = list.listIterator();
            iterator = list.listIterator();
            iterator = list.listIterator();
            iterator = list.listIterator();
        }
    }
}
