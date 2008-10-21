/*---------------------------------------------------------------------------*
 *  SrecGrammarListener.java                                                 *
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
 * Listens for SrecGrammar events.
 */
public interface SrecGrammarListener extends EmbeddedGrammarListener {
    
   /**
   * Invokes after all items of the list have been added.
   */
   void onAddItemList();
   
   /**
   * Invoked when adding a SlotItem from a list fails. 
   * This callback will be trigger for each element in the list that fails to be
   * add in the slot, unless there is a grammar fail operation, which will be
   * reported in the onError callback.
   * @param index of the list that could not be added to the slot
   * @param e the cause of the failure.
   */
   void onAddItemListFailure(int index, Exception e);

    
   /**
   * Invoked when a grammar related operation fails.
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
  
}
