/*---------------------------------------------------------------------------*
 *  SrecGrammarImpl.java                                                     *
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

package android.speech.recognition.impl;

import android.speech.recognition.SrecGrammar;
import android.speech.recognition.SlotItem;
import android.speech.recognition.VoicetagItem;
import android.speech.recognition.WordItem;

import java.util.Vector;

/**
 */
public class SrecGrammarImpl extends EmbeddedGrammarImpl implements SrecGrammar
{
  /**
   * Creates a new SrecGrammarImpl.
   *
   * @param nativeObject the native object
   */
  public SrecGrammarImpl(long nativeObject)
  {
    super(nativeObject);
  }

  public void addItem(String slotName, SlotItem item, int weight,
    String semanticValue)
  {
     synchronized (GrammarImpl.class)
     {
          if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");

        if (slotName == null || slotName.length()==0)
             throw new IllegalArgumentException("addItem() - Slot name is null or empty.");
        if (item == null)
             throw new IllegalArgumentException("addItem() - item can't be null.");
        if (semanticValue == null || semanticValue.length()==0)
             throw new IllegalArgumentException("addItem() - semanticValue is null or empty.");

        long itemNativeObject = 0;
        if  (item instanceof VoicetagItem)
            itemNativeObject = ((VoicetagItemImpl)item).getNativeObject();
        else if  (item instanceof WordItem)
            itemNativeObject = ((WordItemImpl)item).getNativeObject();
        else
           throw new IllegalArgumentException("SlotItem - should be a WordItem or a VoicetagItem object.");

        addItemProxy(nativeObject, slotName, itemNativeObject, weight, semanticValue);
     }
  }
 
  public void addItemList(String slotName, Vector<Item> items)
  {
     synchronized (GrammarImpl.class)
     {
          if (nativeObject == 0)
            throw new IllegalStateException("Object has been disposed");

          if (slotName == null || slotName.length()==0)
                  throw new IllegalArgumentException("addItemList - Slot name is null or empty.");
          if (items == null || items.isEmpty() == true)
                throw new IllegalArgumentException("addItemList - Items is null or empty.");

          int itemsCount = items.size();

          long[]     nativeSlots    = new long[itemsCount];
          int[]      nativeWeights  = new int[itemsCount];
          String[]   nativeSemantic = new String[itemsCount];

          Item element = null;
          long itemNativeObject = 0;
          SlotItem item = null;
          for (int i = 0; i < itemsCount; ++i)
          {
            element = items.get(i);

            item = element._item;
            if  (item instanceof VoicetagItem)
                itemNativeObject = ((VoicetagItemImpl)item).getNativeObject();
            else if  (item instanceof WordItem)
                itemNativeObject = ((WordItemImpl)item).getNativeObject();
            else
            {
               throw new IllegalArgumentException("SlotItem ["+i+"] - should be a WordItem or a VoicetagItem object.");
            }
            nativeSlots[i] = itemNativeObject;
            nativeWeights[i] = element._weight;
            nativeSemantic[i]= element._semanticMeaning;
            itemNativeObject = 0;
            item = null;
          }
          addItemListProxy(nativeObject, slotName,nativeSlots,nativeWeights,nativeSemantic);
     }
  }
  
  private native void addItemProxy(long nativeObject, String slotName, long item, int weight,
    String semanticValue);
  
  private native void addItemListProxy(long nativeObject, String slotName, long[] items,
          int[] weights, String[] semanticValues);
  
}
