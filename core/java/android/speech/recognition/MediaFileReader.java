/*---------------------------------------------------------------------------*
 *  MediaFileReader.java                                                     *
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

import android.speech.recognition.impl.MediaFileReaderImpl;

/**
 * Reads audio from a file.
 */
public abstract class MediaFileReader implements AudioSource
{
  /**
   * Reading mode
   */
  public static class Mode
  {
    /**
     * Read the file in "real time".
     */
    public static Mode REAL_TIME = new Mode("real-time");
    /**
     * Read the file all at once.
     */
    public static Mode ALL_AT_ONCE = new Mode("all at once");
    private String message;

    /**
     * Creates a new Mode.
     *
     * @param message the message associated with the reading mode.
     */
    private Mode(String message)
    {
      this.message = message;
    }
  }

  /**
   * Creates a new MediaFileReader to read audio samples from a file.
   *
   * @param filename the name of the file to read from Note: The file MUST be of type Microsoft WAVE RIFF
   * format (PCM 16 bits 8000 Hz or PCM 16 bits 11025 Hz).
   * @param listener listens for MediaFileReader events
   * @return a new MediaFileReader
   * @throws IllegalArgumentException if filename is null or is an empty string. Or if offset > file length. Or if codec is null or invalid
   */
  public static MediaFileReader create(String filename, AudioSourceListener listener) throws IllegalArgumentException
  {
    return new MediaFileReaderImpl(filename, listener);
  }

  /**
   * Sets the reading mode.
   *
   * @param mode the reading mode
   */
  public abstract void setMode(Mode mode);

  /**
   * Creates an audio source.
   */
  public abstract AudioStream createAudio();

  /**
   * Starts collecting audio samples.
   */
  public abstract void start();

  /**
   * Stops collecting audio samples.
   */
  public abstract void stop();
}
