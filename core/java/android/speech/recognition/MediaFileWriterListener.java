/*---------------------------------------------------------------------------*
 *  MediaFileWriterListener.java                                             *
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
 * Listens for MediaFileWriter events.
 */
public interface MediaFileWriterListener
{
  /**
   * Invoked after the save() operation terminates
   */
  void onStopped();

  /**
   * Invoked when an unexpected error occurs. This is normally followed by
   * onStopped() if the component shuts down successfully.
   *
   * @param e the cause of the failure.<br/>
   * {@link java.io.IOException} if an error occured opening or writing to the file
   */
  void onError(Exception e);
}
