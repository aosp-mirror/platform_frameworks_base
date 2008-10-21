/*---------------------------------------------------------------------------*
 *  MediaFileReaderImpl.java                                                 *
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

import android.speech.recognition.MediaFileReader;
import android.speech.recognition.AudioStream;
import android.speech.recognition.Codec;
import android.speech.recognition.AudioSourceListener;

/**
 */
public class MediaFileReaderImpl extends MediaFileReader implements Runnable
{
  /**
   * Reference to the native object.
   */
  private long nativeObject;

  /**
   * Creates a new MediaFileReaderImpl.
   *
   * @param filename the name of the file to read from
   * @param listener listens for MediaFileReader events
   */
  public MediaFileReaderImpl(String filename, AudioSourceListener listener)
  {
    System system = System.getInstance();
    nativeObject =
      createMediaFileReaderProxy(filename, listener);
    if (nativeObject != 0)
      system.register(this);
  }

  public void run()
  {
    dispose();
  }

  /**
   * Set the reading mode
   */
  public void setMode(Mode mode)
  {
    synchronized (MediaFileReaderImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        setModeProxy(nativeObject,mode);
    }
  }

  /**
   * Creates an audioStream source
   */
  public AudioStream createAudio()
  {
    synchronized (MediaFileReaderImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        return new AudioStreamImpl(createAudioProxy(nativeObject));
    }
  }

  /**
   * Tells the audio source to start collecting audio samples.
   */
  public void start()
  {
     synchronized (MediaFileReaderImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        startProxy(nativeObject);
     }
  }

  /**
   * Stops this source from collecting audio samples.
   */
  public void stop()
  {
    synchronized (MediaFileReaderImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        stopProxy(nativeObject);
    }
  }

  /**
   * Releases the native resources associated with the object.
   */
  public void dispose()
  {
    synchronized (MediaFileReaderImpl.class)
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
   * Deletes a native object.
   *
   * @param nativeObject pointer to the native object
   */
  private native void deleteNativeObject(long nativeObject);

  /**
   * Creates a native MediaFileReader.
   *
   * @param filename the name of the file to read from
   * @param offset the offset to begin reading from
   * @param codec the file audio format
   * @param listener listens for MediaFileReader events
   * @return a reference to the native object
   */
  private native long createMediaFileReaderProxy(String filename, AudioSourceListener listener);

  private native void setModeProxy(long nativeObject,Mode mode);

  private native long createAudioProxy(long nativeObject);

  private native void startProxy(long nativeObject);

  private native void stopProxy(long nativeObject);
}
