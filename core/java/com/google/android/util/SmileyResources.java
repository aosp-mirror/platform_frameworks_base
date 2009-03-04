/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.util;

import com.google.android.util.AbstractMessageParser.TrieNode;

import java.util.HashMap;
import java.util.Set;

/**
 * Resources for smiley parser.
 */
public class SmileyResources implements AbstractMessageParser.Resources {
    private HashMap<String, Integer> mSmileyToRes = new HashMap<String, Integer>();

    /**
     * 
     * @param smilies Smiley text, e.g. ":)", "8-)"
     * @param smileyResIds Resource IDs associated with the smileys.
     */
    public SmileyResources(String[] smilies, int[] smileyResIds) {
        for (int i = 0; i < smilies.length; i++) {
            TrieNode.addToTrie(smileys, smilies[i], "");
            mSmileyToRes.put(smilies[i], smileyResIds[i]);
        }
    }

    /**
     * Looks up the resource id of a given smiley. 
     * @param smiley The smiley to look up.
     * @return the resource id of the specified smiley, or -1 if no resource
     *         id is associated with it.  
     */
    public int getSmileyRes(String smiley) {
        Integer i = mSmileyToRes.get(smiley);
        if (i == null) {
            return -1;
        }
        return i.intValue();
    }

    private final TrieNode smileys = new TrieNode();

    public Set<String> getSchemes() {
        return null;
    }

    public TrieNode getDomainSuffixes() {
        return null;
    }

    public TrieNode getSmileys() {
        return smileys;
    }

    public TrieNode getAcronyms() {
        return null;
    }

}
