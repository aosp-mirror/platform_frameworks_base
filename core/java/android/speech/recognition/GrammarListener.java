/*---------------------------------------------------------------------------*
 *  GrammarListener.java                                                     *
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
 * Listens for Grammar events.
 */
public interface GrammarListener
{
  /**
   * Invoked after the Grammar is loaded.
   */
  void onLoaded();

  /**
   * Invoked after the Grammar is unloaded.
   */
  void onUnloaded();

  /**
   * Invoked when a grammar operation fails.
   *
   * @param e the cause of the failure.<br/>
   * {@link java.io.IOException} if the grammar could not be loaded or
   * saved.</p>
   */
  void onError(Exception e);
}
