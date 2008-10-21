/*---------------------------------------------------------------------------*
 *  EmbeddedGrammarListener.java                                             *
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
 * Listens for EmbeddedGrammar events.
 */
public interface EmbeddedGrammarListener extends GrammarListener
{
  /**
   * Invoked after the grammar is saved.
   *
   * @param path the path the grammar was saved to
   */
  void onSaved(String path);

  /**
   * Invoked when a grammar operation fails.
   *
   * @param e the cause of the failure.<br/>
   * {@link GrammarOverflowException} if the grammar slot is full and no
   * further items may be added to it.<br/>
   * {@link java.lang.UnsupportedOperationException} if different words with
   * the same pronunciation are added.<br/>
   * {@link java.lang.IllegalStateException} if reseting or compiling the
   * slots fails.<br/>
   * {@link java.io.IOException} if the grammar could not be loaded or
   * saved.</p>
   */
  void onError(Exception e);

  /**
   * Invokes after all grammar slots have been compiled.
   */
  void onCompileAllSlots();

  /**
   * Invokes after all grammar slots have been reset.
   */
  void onResetAllSlots();
}
