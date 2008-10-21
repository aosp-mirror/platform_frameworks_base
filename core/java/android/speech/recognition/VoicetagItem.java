/*---------------------------------------------------------------------------*
 *  VoicetagItem.java                                                        *
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

import android.speech.recognition.impl.VoicetagItemImpl;
import java.io.FileNotFoundException;
import java.io.IOException;
/**
 * Voicetag that may be inserted into an embedded grammar slot.
 */
public abstract class VoicetagItem implements SlotItem
{
   /**
   * Creates a VoicetagItem from a file
   *
   * @param filename filename for Voicetag
   * @param listener listens for Voicetag events
   * @return the resulting VoicetagItem
   * @throws IllegalArgumentException if filename is null or an empty string.
   * @throws FileNotFoundException if the specified filename could not be found
   * @throws IOException if the specified filename could not be opened
   */
  public static VoicetagItem create(String filename, VoicetagItemListener listener) throws IllegalArgumentException,FileNotFoundException,IOException
  {
        return VoicetagItemImpl.create(filename,listener);
  }
  /**
   * Returns the audio used to construct the VoicetagItem.
   * The audio is in PCM format and is start-pointed and end-pointed. The audio
   * is only generated if the enableGetWaveform recognition parameter
   * is set prior to recognition.
   *
   * @throws IllegalStateException if the recognition parameter 'enableGetWaveform' is not set
   * @return the audio used to construct the VoicetagItem.
   */
  public abstract byte[] getAudio() throws IllegalStateException;

  /**
   * Sets the audio used to construct the Voicetag. The
   * audio is in PCM format and is start-pointed and end-pointed. The audio is
   * only generated if the enableGetWaveform recognition parameter is set
   * prior to recognition.
   *
   * @param waveform the endpointed waveform
   * @throws IllegalArgumentException if waveform is null or empty.
   * @throws IllegalStateException if the recognition parameter 'enableGetWaveform' is not set
   */
  public abstract void setAudio(byte[] waveform) throws IllegalArgumentException,IllegalStateException;
 
   /**
   * Save the Voicetag Item.
   *
   * @param path where the Voicetag will be saved. We strongly recommend to set the filename with the same value of the VoicetagId.
   * @throws IllegalArgumentException if path is null or an empty string.
   */
   public abstract void save(String path) throws IllegalArgumentException,IllegalStateException;
  
   /**
   * Load a Voicetag Item.
   *
   * @throws IllegalStateException if voicetag has not been created from a file.
   */
   public abstract void load() throws IllegalStateException;

}
