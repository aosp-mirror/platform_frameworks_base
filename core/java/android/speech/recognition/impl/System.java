/*---------------------------------------------------------------------------*
 *  System.java                                                              *
 *                                                                           *
 *  Copyright 2007, 2008 Nuance Communciations, Inc.                               *
 *                                                                           *
 *  Licensed under the Apache License, Version 2.0 (the 'License');          *
 *  you may not use this file except in compliance with the License.         *
 *                                                                           *
 *  You may obtain a copy of the License at                                  *
 *      http://www.apache.org/licenses/LICENSE-2.0                           *
 *                                                                           *
 *  Unless required by applicable law or agreed to in writing, software      *
 *  distributed under the License is distributed on an 'AS IS' BASIS,        *
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. *
 *  See the License for the specific language governing permissions and      *
 *  limitations under the License.                                           *
 *                                                                           *
 *---------------------------------------------------------------------------*/

package android.speech.recognition.impl;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;


/**
 */
public class System
{
  private static boolean libraryLoaded;
  private static System instance;
  private static WeakHashMap<Object, WeakReference> registerMap;
  /**
   * Reference to the native object.
   */
  private long nativeObject;
  private boolean shutdownRequested;

  /**
   * Creates a new instance of System
   */
  private System()
  {
    shutdownRequested = false;
    registerMap =
      new WeakHashMap<Object, WeakReference>();
    initLibrary();
    nativeObject = initNativeObject();
        Runtime.getRuntime().
      addShutdownHook(new Thread()
    {
      @Override
      public void run()
      {
        try
        {
          dispose();
        }
        catch (Exception e)
        {
          e.printStackTrace();
        }
      }
    });

  }

  /**
   * Returns the singleton instance.
   *
   * @return the singleton instance
   */
  public static System getInstance()
  {
    synchronized (System.class)
    {
      if (instance == null)
        instance = new System();
      return instance;
    }
  }

  /**
   * Loads the native library if necessary.
   */
  private void initLibrary()
  {
    if (!libraryLoaded)
    {
      java.lang.System.loadLibrary("UAPI_jni");
      libraryLoaded = true;
    }
  }

  /**
   * Registers an object for shutdown when System.dispose() is invoked.
   *
   * @param r the code to run on shutdown
   * @throws IllegalStateException if the System is shutting down
   */
  public void register(Runnable r) throws IllegalStateException
  {
    synchronized (System.class)
    {
      if (shutdownRequested)
        throw new IllegalStateException("System is shutting down");
      registerMap.put(r,
        new WeakReference<Runnable>(r));
    }
  }

  /**
   * Registers an object for shutdown when System.dispose() is invoked.
   *
   * @param r the code to run on shutdown
   */
  public void unregister(Runnable r)
  {
    synchronized (System.class)
    {
      if (shutdownRequested)
      {
        // System.dispose() will end up removing all entries
        return;
      }
      if (r!=null) registerMap.remove(r);
    }
  }

  /**
   * Releases the native resources associated with the object.
   *
   * @throws java.util.concurrent.TimeoutException if the operation timeouts
   * @throws IllegalThreadStateException if a native thread error occurs
   */
  public void dispose() throws java.util.concurrent.TimeoutException,
    IllegalThreadStateException
  {
    synchronized (System.class)
    {
      if (nativeObject == 0)
        return;
      shutdownRequested = true;
    }

    // Traverse the list of WeakReferences
    // cast to a Runnable object if the weakrerefence is not null
    // then call the run method.
    for (Object o: registerMap.keySet())
    {
      WeakReference weakReference = registerMap.get(o);
      Runnable r = (Runnable) weakReference.get();
      if (r != null)
        r.run();
    }
    registerMap.clear();

    // Call the native dispose method
    disposeProxy();
    synchronized (System.class)
    {
      nativeObject = 0;
      instance = null;
    }
  }

  @Override
  protected void finalize() throws Throwable
  {
    dispose();
    super.finalize();
  }

  public static native String getAPIVersion();
  
  private static native long initNativeObject();

  private static native void disposeProxy();
}
