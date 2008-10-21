/*---------------------------------------------------------------------------*
 *  MediaFileWriterImpl.java                                                 *
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

import android.speech.recognition.AudioStream;
import android.speech.recognition.MediaFileWriter;
import android.speech.recognition.MediaFileWriterListener;

/**
 */
public class MediaFileWriterImpl extends MediaFileWriter implements Runnable
{
  /**
   * Reference to the native object.
   */
  private long nativeObject;

  /**
   * Creates a new MediaFileWriterImpl.
   *
   * @param listener listens for MediaFileWriter events
   */
  public MediaFileWriterImpl(MediaFileWriterListener listener)
  {
    System system = System.getInstance();
    nativeObject = createMediaFileWriterProxy(listener);
    if (nativeObject != 0)
      system.register(this);
  }

  public void run()
  {
    dispose();
  }

  public void save(AudioStream source, String filename)
  {
    synchronized (MediaFileWriterImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        saveProxy(nativeObject,((AudioStreamImpl)source).getNativeObject(), filename);
    }
  }

  /**
   * Releases the native resources associated with the object.
   */
  public synchronized void dispose()
  { 
     synchronized (MediaFileWriterImpl.class)
     {
        if (nativeObject != 0)
        {
          deleteNativeObject(nativeObject);
          nativeObject = 0;
          System.getInstance().unregister(this);
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
   * Creates a native MediaFileWriter.
   *
   * @param listener listens for MediaFileReader events
   * @return a reference to the native object
   */
  private native long createMediaFileWriterProxy(MediaFileWriterListener listener);

  /**
   * Deletes a native object.
   *
   * @param nativeObject pointer to the native object
   */
  private native void deleteNativeObject(long nativeObject);

  private native void saveProxy(long nativeObject, long audioNativeObject, String filename);
}
