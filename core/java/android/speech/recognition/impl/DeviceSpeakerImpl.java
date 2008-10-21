/*---------------------------------------------------------------------------*
 *  DeviceSpeakerImpl.java                                                   *
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
import android.speech.recognition.DeviceSpeaker;
import android.speech.recognition.DeviceSpeakerListener;

/**
 */
public class DeviceSpeakerImpl extends DeviceSpeaker implements Runnable
{
  private static DeviceSpeakerImpl instance;
  /**
   * Reference to the native object.
   */
  private long nativeObject;
  private DeviceSpeakerListener locallistener;

  /**
   * Private constructor
   */
  private DeviceSpeakerImpl()
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
  public static DeviceSpeakerImpl getInstance()
  {
     synchronized (DeviceSpeakerImpl.class)
     {
        if (instance == null)
            instance = new DeviceSpeakerImpl();
        return instance;
     }
  }

  /**
   * Start audio playback.
   *
   * @param source the audio to play
   */
  public void start(AudioStream source)
  {
     synchronized (DeviceSpeakerImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        AudioStreamImpl src = (AudioStreamImpl)source;
        startProxy(nativeObject,src.getNativeObject());
        src = null;
     }
  }

  /**
   * Stops audio playback.
   */
  public void stop()
  {
     synchronized (DeviceSpeakerImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        stopProxy(nativeObject);
     }
  }

  /**
   * Set the playback codec. This must be called before start is called.
   * @param playbackCodec the codec to use for the playback operation.
   */
  public void setCodec(Codec playbackCodec)
  {
     synchronized (DeviceSpeakerImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        setCodecProxy(nativeObject,playbackCodec);
     }
  }

  /**
   * set the microphone listener.
   * @param listener the device speaker listener.
   */
  public void setListener(DeviceSpeakerListener listener)
  {
     synchronized (DeviceSpeakerImpl.class)
     {
        if (nativeObject == 0)
            throw new IllegalStateException("Object was destroyed.");
        locallistener = listener;
        setListenerProxy(nativeObject,listener);
     }
  }

  /**
   * Releases the native resources associated with the object.
   */
  private void dispose()
  {
     synchronized (DeviceSpeakerImpl.class)
     {
        if (nativeObject != 0)
        {
            deleteNativeObject(nativeObject);
            nativeObject = 0;
            instance = null;
            locallistener = null;
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

  private native void startProxy(long nativeObject, long audioNativeObject);

  private native void stopProxy(long nativeObject);

  private native void setCodecProxy(long nativeObject,Codec playbackCodec);

  private native void setListenerProxy(long nativeObject,DeviceSpeakerListener listener);

  private native void deleteNativeObject(long nativeObject);
}
