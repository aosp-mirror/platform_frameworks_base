/*---------------------------------------------------------------------------*
 *  SrecGrammar.java                                                         *
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
import java.util.Vector;

/**
 * Grammar on an SREC recognizer.
 */
public interface SrecGrammar extends EmbeddedGrammar
{
  /**
  * SrecGrammar Item
  */
  public class Item
  {
      public SlotItem _item;
      public int _weight;
      public String _semanticMeaning;
      
       /**
       * Creates a grammar item.
       *
       * @param item the Slotitem. 
       * @param weight the weight of the item. Smaller values are more likely to get recognized.  This should be >= 0.
       * @param semanticMeaning the value that will be returned if this item is recognized.
       * @throws IllegalArgumentException if item or semanticMeaning are null; if semanticMeaning is empty."
       */
       public Item(SlotItem item, int weight, String semanticMeaning)
             throws IllegalArgumentException
      {
         if (item == null)
            throw new IllegalArgumentException("Item(): item can't be null.");
         if (semanticMeaning == null || semanticMeaning.length()==0)
            throw new IllegalArgumentException("Item(): semanticMeaning is null or empty.");
          _item = item;
          _weight = weight;
          _semanticMeaning = semanticMeaning;
          
      }
  }
  
  /**
   * Adds an item to a slot.
   *
   * @param slotName the name of the slot
   * @param item the item to add to the slot.
   * @param weight the weight of the item. Smaller values are more likely to get recognized.  This should be >= 0.
   * @param semanticMeaning the value that will be returned if this item is recognized.
   * @throws IllegalArgumentException if slotName, item or semanticMeaning are null; if semanticMeaning is not of the format "V=&#039;Jen_Parker&#039;"
   */
  public void addItem(String slotName, SlotItem item, int weight,
    String semanticMeaning) throws IllegalArgumentException;
  
  /**
   * Add a list of item to a slot.
   *
   * @param slotName the name of the slot
   * @param items the vector of SrecGrammar.Item to add to the slot.
   * @throws IllegalArgumentException if slotName,items are null or any element in the items(_item, _semanticMeaning) is null; if any semanticMeaning of the list is not of the format "key=&#039;value&#039"
   */
  public void addItemList(String slotName, Vector<Item> items)
          throws IllegalArgumentException;
  
}
