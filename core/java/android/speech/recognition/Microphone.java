/*---------------------------------------------------------------------------*
 *  Microphone.java                                                          *
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

import android.speech.recognition.impl.MicrophoneImpl;

/**
 * Records live audio.
 */
public abstract class Microphone implements AudioSource
{
  private static Microphone instance;

  /**
   * Returns the microphone instance
   *
   * @return an instance of a Microphone class.
   */
  public static Microphone getInstance()
  {
    instance = MicrophoneImpl.getInstance();
    return instance;
  }

  /**
   * Sets the recording codec. This must be called before start() is called.
   *
   * @param recordingCodec the codec in which the samples will be recorded.
   * @throws IllegalStateException if Microphone is started
   * @throws IllegalArgumentException if codec is not supported
   */
  public abstract void setCodec(Codec recordingCodec) throws IllegalStateException,
    IllegalArgumentException;

  /**
   * Sets the microphone listener.
   *
   * @param listener the microphone listener.
   * @throws IllegalStateException if Microphone is started
   */
  public abstract void setListener(AudioSourceListener listener) throws IllegalStateException;

  /**
   * Creates an audio source
   */
  public abstract AudioStream createAudio();

  /**
   * Start recording audio.
   *
   * @throws IllegalStateException if Microphone is already started
   */
  public abstract void start() throws IllegalStateException;

  /**
   * Stops recording audio.
   */
  public abstract void stop();
}
