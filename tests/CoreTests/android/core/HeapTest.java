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

import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;
import android.test.suitebuilder.annotation.Suppress;
import dalvik.system.VMRuntime;
import junit.framework.TestCase;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.LinkedList;
import java.util.Random;


public class HeapTest extends TestCase {

    private static final String TAG = "HeapTest";

    /**
     * Returns a WeakReference to an object that has no
     * other references.  This is done in a separate method
     * to ensure that the Object's address isn't sitting in
     * a stale local register.
     */
    private WeakReference<Object> newRef() {
        return new WeakReference<Object>(new Object());
    }

    private static void makeRefs(Object objects[], SoftReference<Object> refs[]) {
        for (int i = 0; i < objects.length; i++) {
            objects[i] = (Object) new byte[8 * 1024];
            refs[i] = new SoftReference<Object>(objects[i]);
        }
    }

    private static <T> int checkRefs(SoftReference<T> refs[], int last) {
        int i;
        int numCleared = 0;
        for (i = 0; i < refs.length; i++) {
            Object o = refs[i].get();
            if (o == null) {
                numCleared++;
            }
        }
        if (numCleared != last) {
            Log.i(TAG, "****** " + numCleared + "/" + i + " cleared ******");
        }
        return numCleared;
    }

    private static void clearRefs(Object objects[], int skip) {
        for (int i = 0; i < objects.length; i += skip) {
            objects[i] = null;
        }
    }

    private static void clearRefs(Object objects[]) {
        clearRefs(objects, 1);
    }

    private static <T> void checkRefs(T objects[], SoftReference<T> refs[]) {
        boolean ok = true;

        for (int i = 0; i < objects.length; i++) {
            if (refs[i].get() != objects[i]) {
                ok = false;
            }
        }
        if (!ok) {
            throw new RuntimeException("Test failed: soft refs not cleared");
        }
    }

    @MediumTest
    public void testGcSoftRefs() throws Exception {
        final int NUM_REFS = 128;

        Object objects[] = new Object[NUM_REFS];
        SoftReference<Object> refs[] = new SoftReference[objects.length];

        /* Create a bunch of objects and a parallel array
         * of SoftReferences.
         */
        makeRefs(objects, refs);
        Runtime.getRuntime().gc();

        /* Let go of some of the hard references to the objects so that
         * the references can be cleared.
         */
        clearRefs(objects, 3);

        /* Collect all softly-reachable objects.
         */
        VMRuntime.getRuntime().gcSoftReferences();
        Runtime.getRuntime().runFinalization();

        /* Make sure that the objects were collected.
         */
        checkRefs(objects, refs);

        /* Remove more hard references and re-check.
         */
        clearRefs(objects, 2);
        VMRuntime.getRuntime().gcSoftReferences();
        Runtime.getRuntime().runFinalization();
        checkRefs(objects, refs);

        /* Remove the rest of the references and re-check.
         */
        /* Remove more hard references and re-check.
         */
        clearRefs(objects);
        VMRuntime.getRuntime().gcSoftReferences();
        Runtime.getRuntime().runFinalization();
        checkRefs(objects, refs);
    }

    public void xxtestSoftRefPartialClean() throws Exception {
        final int NUM_REFS = 128;

        Object objects[] = new Object[NUM_REFS];
        SoftReference<Object> refs[] = new SoftReference[objects.length];

        /* Create a bunch of objects and a parallel array
        * of SoftReferences.
        */
        makeRefs(objects, refs);
        Runtime.getRuntime().gc();

        /* Let go of the hard references to the objects so that
        * the references can be cleared.
        */
        clearRefs(objects);

        /* Start creating a bunch of temporary and permanent objects
        * to drive GC.
        */
        final int NUM_OBJECTS = 64 * 1024;
        Object junk[] = new Object[NUM_OBJECTS];
        Random random = new Random();

        int i = 0;
        int mod = 0;
        int totalSize = 0;
        int cleared = -1;
        while (i < junk.length && totalSize < 8 * 1024 * 1024) {
            int r = random.nextInt(64 * 1024) + 128;
            Object o = (Object) new byte[r];
            if (++mod % 16 == 0) {
                junk[i++] = o;
                totalSize += r * 4;
            }
            cleared = checkRefs(refs, cleared);
        }
    }

    private static void makeRefs(Object objects[], WeakReference<Object> refs[]) {
        for (int i = 0; i < objects.length; i++) {
            objects[i] = new Object();
            refs[i] = new WeakReference<Object>(objects[i]);
        }
    }

    private static <T> void checkRefs(T objects[], WeakReference<T> refs[]) {
        boolean ok = true;

        for (int i = 0; i < objects.length; i++) {
            if (refs[i].get() != objects[i]) {
                ok = false;
            }
        }
        if (!ok) {
            throw new RuntimeException("Test failed: " +
                    "weak refs not cleared");
        }
    }

    @MediumTest
    public void testWeakRefs() throws Exception {
        final int NUM_REFS = 16;

        Object objects[] = new Object[NUM_REFS];
        WeakReference<Object> refs[] = new WeakReference[objects.length];

        /* Create a bunch of objects and a parallel array
        * of WeakReferences.
        */
        makeRefs(objects, refs);
        Runtime.getRuntime().gc();
        checkRefs(objects, refs);

        /* Clear out every other strong reference.
        */
        for (int i = 0; i < objects.length; i += 2) {
            objects[i] = null;
        }
        Runtime.getRuntime().gc();
        checkRefs(objects, refs);

        /* Clear out the rest of them.
        */
        for (int i = 0; i < objects.length; i++) {
            objects[i] = null;
        }
        Runtime.getRuntime().gc();
        checkRefs(objects, refs);
    }

    private static void makeRefs(Object objects[], PhantomReference<Object> refs[],
            ReferenceQueue<Object> queue) {
        for (int i = 0; i < objects.length; i++) {
            objects[i] = new Object();
            refs[i] = new PhantomReference<Object>(objects[i], queue);
        }
    }

    static <T> void checkRefs(T objects[], PhantomReference<T> refs[],
            ReferenceQueue<T> queue) {
        boolean ok = true;

        /* Make sure that the reference that should be on
        * the queue are marked as enqueued.  Once we
        * pull them off the queue, they will no longer
        * be marked as enqueued.
        */
        for (int i = 0; i < objects.length; i++) {
            if (objects[i] == null && refs[i] != null) {
                if (!refs[i].isEnqueued()) {
                    ok = false;
                }
            }
        }
        if (!ok) {
            throw new RuntimeException("Test failed: " +
                    "phantom refs not marked as enqueued");
        }

        /* Make sure that all of the references on the queue
        * are supposed to be there.
        */
        PhantomReference<T> ref;
        while ((ref = (PhantomReference<T>) queue.poll()) != null) {
            /* Find the list index that corresponds to this reference.
            */
            int i;
            for (i = 0; i < objects.length; i++) {
                if (refs[i] == ref) {
                    break;
                }
            }
            if (i == objects.length) {
                throw new RuntimeException("Test failed: " +
                        "unexpected ref on queue");
            }
            if (objects[i] != null) {
                throw new RuntimeException("Test failed: " +
                        "reference enqueued for strongly-reachable " +
                        "object");
            }
            refs[i] = null;

            /* TODO: clear doesn't do much, since we're losing the
            * strong ref to the ref object anyway.  move the ref
            * into another list.
            */
            ref.clear();
        }

        /* We've visited all of the enqueued references.
        * Make sure that there aren't any other references
        * that should have been enqueued.
        *
        * NOTE: there is a race condition here;  this assumes
        * that the VM has serviced all outstanding reference
        * enqueue() calls.
        */
        for (int i = 0; i < objects.length; i++) {
            if (objects[i] == null && refs[i] != null) {
//                System.out.println("HeapTest/PhantomRefs: refs[" + i +
//                        "] should be enqueued");
                ok = false;
            }
        }
        if (!ok) {
            throw new RuntimeException("Test failed: " +
                    "phantom refs not enqueued");
        }
    }

    @MediumTest
    public void testPhantomRefs() throws Exception {
        final int NUM_REFS = 16;

        Object objects[] = new Object[NUM_REFS];
        PhantomReference<Object> refs[] = new PhantomReference[objects.length];
        ReferenceQueue<Object> queue = new ReferenceQueue<Object>();

        /* Create a bunch of objects and a parallel array
        * of PhantomReferences.
        */
        makeRefs(objects, refs, queue);
        Runtime.getRuntime().gc();
        checkRefs(objects, refs, queue);

        /* Clear out every other strong reference.
        */
        for (int i = 0; i < objects.length; i += 2) {
            objects[i] = null;
        }
        // System.out.println("HeapTest/PhantomRefs: cleared evens");
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();
        checkRefs(objects, refs, queue);

        /* Clear out the rest of them.
        */
        for (int i = 0; i < objects.length; i++) {
            objects[i] = null;
        }
        // System.out.println("HeapTest/PhantomRefs: cleared all");
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();
        checkRefs(objects, refs, queue);
    }

    private static int sNumFinalized = 0;
    private static final Object sLock = new Object();

    private static class FinalizableObject {
        protected void finalize() {
            // System.out.println("gc from finalize()");
            Runtime.getRuntime().gc();
            synchronized (sLock) {
                sNumFinalized++;
            }
        }
    }

    private static void makeRefs(FinalizableObject objects[],
            WeakReference<FinalizableObject> refs[]) {
        for (int i = 0; i < objects.length; i++) {
            objects[i] = new FinalizableObject();
            refs[i] = new WeakReference<FinalizableObject>(objects[i]);
        }
    }

    @LargeTest
    public void testWeakRefsAndFinalizers() throws Exception {
        final int NUM_REFS = 16;

        FinalizableObject objects[] = new FinalizableObject[NUM_REFS];
        WeakReference<FinalizableObject> refs[] = new WeakReference[objects.length];
        int numCleared;

        /* Create a bunch of objects and a parallel array
        * of WeakReferences.
        */
        makeRefs(objects, refs);
        Runtime.getRuntime().gc();
        checkRefs(objects, refs);

        /* Clear out every other strong reference.
        */
        sNumFinalized = 0;
        numCleared = 0;
        for (int i = 0; i < objects.length; i += 2) {
            objects[i] = null;
            numCleared++;
        }
        // System.out.println("HeapTest/WeakRefsAndFinalizers: cleared evens");
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();
        checkRefs(objects, refs);
        if (sNumFinalized != numCleared) {
            throw new RuntimeException("Test failed: " +
                    "expected " + numCleared + " finalizations, saw " +
                    sNumFinalized);
        }

        /* Clear out the rest of them.
        */
        sNumFinalized = 0;
        numCleared = 0;
        for (int i = 0; i < objects.length; i++) {
            if (objects[i] != null) {
                objects[i] = null;
                numCleared++;
            }
        }
        // System.out.println("HeapTest/WeakRefsAndFinalizers: cleared all");
        Runtime.getRuntime().gc();
        Runtime.getRuntime().runFinalization();
        checkRefs(objects, refs);
        if (sNumFinalized != numCleared) {
            throw new RuntimeException("Test failed: " +
                    "expected " + numCleared + " finalizations, saw " +
                    sNumFinalized);
        }
    }

    // TODO: flaky test
    //@MediumTest
    public void testOomeLarge() throws Exception {
        /* Just shy of the typical max heap size so that it will actually
         * try to allocate it instead of short-circuiting.
         */
        final int SIXTEEN_MB = (16 * 1024 * 1024 - 32);

        Boolean sawEx = false;
        byte a[];

        try {
            a = new byte[SIXTEEN_MB];
        } catch (OutOfMemoryError oom) {
            //Log.i(TAG, "HeapTest/OomeLarge caught " + oom);
            sawEx = true;
        }

        if (!sawEx) {
            throw new RuntimeException("Test failed: " +
                    "OutOfMemoryError not thrown");
        }
    }

    //See bug 1308253 for reasons.
    @Suppress
    public void disableTestOomeSmall() throws Exception {
        final int SIXTEEN_MB = (16 * 1024 * 1024);
        final int LINK_SIZE = 6 * 4; // estimated size of a LinkedList's node

        Boolean sawEx = false;

        LinkedList<Object> list = new LinkedList<Object>();

        /* Allocate progressively smaller objects to fill up the entire heap.
         */
        int objSize = 1 * 1024 * 1024;
        while (objSize >= LINK_SIZE) {
            try {
                for (int i = 0; i < SIXTEEN_MB / objSize; i++) {
                    list.add((Object)new byte[objSize]);
                }
            } catch (OutOfMemoryError oom) {
                sawEx = true;
            }

            if (!sawEx) {
                throw new RuntimeException("Test failed: " +
                        "OutOfMemoryError not thrown while filling heap");
            }
            sawEx = false;

            objSize = (objSize * 4) / 5;
        }
    }
}
