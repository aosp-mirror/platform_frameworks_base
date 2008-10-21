/*---------------------------------------------------------------------------*
 *  GrammarImpl.java                                                         *
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

import android.speech.recognition.Grammar;

/**
 */
public class GrammarImpl implements Grammar, Runnable
{
  /**
   * Reference to the native object.
   */
  protected long nativeObject;

  /**
   * Creates a new GrammarImpl.
   *
   * @param nativeObj a reference to the native object
   */
  public GrammarImpl(long nativeObj)
  {
    nativeObject = nativeObj;
  }

  public void run()
  {
    dispose();
  }

  public long getNativeObject() 
  { 
     synchronized (GrammarImpl.class)
     {
        return nativeObject;
     }
  }
  
  /**
   * Indicates that the grammar will be used in the near future.
   */
  public void load()
  {
     synchronized (GrammarImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        loadProxy(nativeObject);
     }
  }

  /**
   * The grammar will be removed from use.
   */
  public void unload()
  {
     synchronized (GrammarImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        unloadProxy(nativeObject);
     }
  }

  /**
   * Releases the native resources associated with the object.
   */
  public void dispose()
  {
    synchronized (GrammarImpl.class)
    {
        if (nativeObject != 0)
        {
            deleteNativeObject(nativeObject);
            nativeObject = 0;
        }
    }
  }

  @Override
  protected void finalize() throws Throwable
  {
    dispose();
    super.finalize();
  }

  /**
   * Deletes a native object.
   *
   * @param nativeObject pointer to the native object
   */
  private native void deleteNativeObject(long nativeObject);

  private native void loadProxy(long nativeObject);

  private native void unloadProxy(long nativeObject);
}
