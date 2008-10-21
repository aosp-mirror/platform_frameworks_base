/*---------------------------------------------------------------------------*
 *  Grammar.java                                                             *
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
 * Speech recognition grammar.
 */
public interface Grammar {
    /**
     * Load the grammar sets the grammar state to active, indicating that can be used in a recognition process.
     * Multiple grammars can be loaded, but only one at a time can be used by the recognizer.
     *
     */
    void load();
    
    /**
     * Unload the grammar sets the grammar state to inactive (inactive grammars can not be used as a parameter of a recognition).
     */
    void unload();
    
    /**
     * (Optional operation) Releases resources associated with the object. The
     * grammar may not be used past this point.
     */
    void dispose();
}
