/*---------------------------------------------------------------------------*
 *  EntryImpl.java                                                           *
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

import android.speech.recognition.NBestRecognitionResult;
import java.util.Enumeration;

/**
 */
public class EntryImpl implements NBestRecognitionResult.Entry, Runnable
{
  private long nativeObject;

  /**
   * This implementation is a work-around to solve Q bug with
   * nested classes.
   *
   * @param nativeObject the native NBestRecognitionResult.Entry object
   */
  public EntryImpl(long nativeObject)
  {
    this.nativeObject = nativeObject;
  }

  public void run()
  {
    dispose();
  }

  public byte getConfidenceScore() throws IllegalStateException
  {
    synchronized (EntryImpl.class)
    {
        if (nativeObject == 0)
          throw new IllegalStateException("Object has been disposed");
        return getConfidenceScoreProxy(nativeObject);
    }
  }

  public String getLiteralMeaning() throws IllegalStateException
  {
    synchronized (EntryImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        return getLiteralMeaningProxy(nativeObject);
    }
  }

  public String getSemanticMeaning() throws IllegalStateException
  {
    synchronized (EntryImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        return getSemanticMeaningProxy(nativeObject);
    }
  }
 
  public String get(String key)
  {
    synchronized (EntryImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        return getProxy(nativeObject,key);
    }
  }

  public Enumeration keys()
  {
    synchronized (EntryImpl.class)
    {
        if (nativeObject == 0)
          throw new IllegalStateException("Object has been disposed");

          return new Enumeration()
          {
            private String[] keys = keysProxy(nativeObject);
            private int indexOfNextRead = 0;

            public boolean hasMoreElements()
            {
              return indexOfNextRead <= keys.length-1;
            }

            public Object nextElement()
            {
              return keys[indexOfNextRead++];
            }
          };
    }
  }


  /**
   * Releases the native resources associated with the object.
   */
  private void dispose()
  {
    synchronized (EntryImpl.class)
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

  private native void deleteNativeObject(long nativeObject);

  private native String getLiteralMeaningProxy(long nativeObject);

  private native String getSemanticMeaningProxy(long nativeObject);

  private native byte getConfidenceScoreProxy(long nativeObject);
  
  private native String getProxy(long nativeObject,String key);

  private native String[] keysProxy(long nativeObject);

}
