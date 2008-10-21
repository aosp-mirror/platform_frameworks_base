/*---------------------------------------------------------------------------*
 *  VoicetagItemListener.java                                                 *
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
 * Listens for VoicetagItem events.
 */
public interface VoicetagItemListener
{
   /**
   * Invoked after the Voicetag is saved.
   *
   * @param path the path the Voicetag was saved to
   */
  void onSaved(String path);
  
   /**
   * Invoked after the Voicetag is loaded.
   */
  void onLoaded();
  
  /**
   * Invoked when a grammar operation fails.
   *
   * @param e the cause of the failure.<br/>
   * {@link java.io.IOException} if the Voicetag could not be loaded or
   * saved.</p>
   * {@link java.io.FileNotFoundException} if the specified file could not be found
   */
  void onError(Exception e);
  
}
