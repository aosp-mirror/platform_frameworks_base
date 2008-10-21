/*---------------------------------------------------------------------------*
 *  AudioSource.java                                                         *
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

/**
 * Generates audio data.
 */
public interface AudioSource
{
  /**
   * Returns an object that contains the audio samples. This object
   * is passed to other components that consumes it, such a Recognizer
   * or a DeviceSpeaker.
   *
   * @return an AudioStream instance
   */
  AudioStream createAudio();

  /**
   * Tells the audio source to start collecting audio samples.
   */
  void start();

  /**
   * Tells the audio source to stop collecting audio samples.
   */
  void stop();
}
