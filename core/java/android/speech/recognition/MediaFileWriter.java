/*---------------------------------------------------------------------------*
 *  MediaFileWriter.java                                                     *
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

import android.speech.recognition.impl.MediaFileWriterImpl;

/**
 * Writes audio to a file.
 */
public abstract class MediaFileWriter
{
  /**
   * Creates a new MediaFileWriter to write audio samples into a file.
   *
   * @param listener listens for MediaFileWriter events
   * @return a new MediaFileWriter
   */
  public static MediaFileWriter create(MediaFileWriterListener listener)
  {
    return new MediaFileWriterImpl(listener);
  }

  /**
   * Saves audio to a file.
   *
   * @param source the audio stream to write
   * @param filename the file to write to
   * @throws IllegalArgumentException if source is null, in-use by another 
   * component or contains no data. Or if filename is null or is empty.
   */
  public abstract void save(AudioStream source, String filename) throws IllegalArgumentException;
}
