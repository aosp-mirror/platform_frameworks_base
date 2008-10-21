/*---------------------------------------------------------------------------*
 *  MicrophoneImpl.java                                                      *
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
import android.speech.recognition.Codec;
import android.speech.recognition.Microphone;
import android.speech.recognition.AudioSourceListener;

/**
 */
public class MicrophoneImpl extends Microphone implements Runnable
{
  private static MicrophoneImpl instance;
  /**
   * Reference to the native object.
   */
  private long nativeObject;

  /**
   * Creates a new MicrophoneImpl.
   *
   * @param nativeObj a reference to the native object
   */
  private MicrophoneImpl()
  {
    System system = System.getInstance();
    nativeObject = initNativeObject();
    if (nativeObject != 0)
      system.register(this);
  }

  public void run()
  {
    dispose();
  }

  /**
   * Returns the singleton instance.
   *
   * @return the singleton instance
   */
  public static MicrophoneImpl getInstance()
  {
    synchronized (MicrophoneImpl.class)
    {
        if (instance == null)
            instance = new MicrophoneImpl();
        return instance;
    }
  }

  /**
   * set the recording codec. This must be called before Start is called.
   * @param recordingCodec the codec in which the samples will be recorded.
   */
  public void setCodec(Codec recordingCodec)
  {
    synchronized (MicrophoneImpl.class)
    {
        if (nativeObject == 0)
          throw new IllegalStateException("Object has been disposed");
        setCodecProxy(nativeObject,recordingCodec);
    }
  }

  /**
   * set the microphone listener.
   * @param listener the microphone listener.
   */
  public void setListener(AudioSourceListener listener)
  {
    synchronized (MicrophoneImpl.class)
    {       
        if (nativeObject == 0)
          throw new IllegalStateException("Object has been disposed");
        setListenerProxy(nativeObject,listener);
    }
  }

  public AudioStream createAudio()
  {
    synchronized (MicrophoneImpl.class)
    {
        if (nativeObject == 0)
          throw new IllegalStateException("Object has been disposed");
        return new AudioStreamImpl(createAudioProxy(nativeObject));
    }
  }

  public void start()
  {
     synchronized (MicrophoneImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        startProxy(nativeObject);
     }
  }

  public void stop()
  {
    synchronized (MicrophoneImpl.class)
    {
        if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");
        stopProxy(nativeObject);
    }
  }

  /**
   * Releases the native resources associated with the object.
   */
  private void dispose()
  {
    synchronized (MicrophoneImpl.class)
    {
        if (nativeObject != 0)
        {
          deleteNativeObject(nativeObject);
          nativeObject = 0;
          instance = null;
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

  private native long initNativeObject();

  private native void setCodecProxy(long nativeObject,Codec recordingCodec);

  private native void setListenerProxy(long nativeObject, AudioSourceListener listener);

  private native long createAudioProxy(long nativeObject);

  private native void startProxy(long nativeObject);

  private native void stopProxy(long nativeObject);

  private native void deleteNativeObject(long nativeObject);
}
