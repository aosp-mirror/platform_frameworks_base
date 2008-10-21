/*---------------------------------------------------------------------------*
 *  DeviceSpeaker.java                                                       *
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

package android.speech.recognition;

import android.speech.recognition.impl.DeviceSpeakerImpl;

/**
 * A device for transforming electric signals into audible sound, most
 * frequently used to reproduce speech and music.
 */
public abstract class DeviceSpeaker
{
  private static DeviceSpeaker instance;

  /**
   * Returns the device speaker instance.
   *
   * @return an instance of a DeviceSpeaker class.
   */
  public static DeviceSpeaker getInstance()
  {
    instance = DeviceSpeakerImpl.getInstance();
    return instance;
  }

  /**
   * Starts the audio playback.
   *
   * @param source the audio to play
   * @throws IllegalStateException if the component is already started
   * @throws IllegalArgumentException if source audio is null, in-use by 
   * another component or is empty.
   *
   */
  public abstract void start(AudioStream source) throws IllegalStateException,
    IllegalArgumentException;

  /**
   * Stops audio playback.
   */
  public abstract void stop();

  /**
   * Sets the playback codec. This must be called before start() is called.
   *
   * @param playbackCodec the codec to use for the playback operation.
   * @throws IllegalStateException if the component is already stopped
   * @throws IllegalArgumentException if the specified codec is not supported
   */
  public abstract void setCodec(Codec playbackCodec) throws IllegalStateException,
    IllegalArgumentException;

  /**
   * Sets the microphone listener.
   *
   * @param listener the device speaker listener.
   * @throws IllegalStateException if the component is started
   */
  public abstract void setListener(DeviceSpeakerListener listener) throws IllegalStateException;
}
