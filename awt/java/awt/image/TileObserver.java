/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
/**
 * @author Igor V. Stolyarov
 * @version $Revision$
 */

package java.awt.image;

/**
 * An asynchronous update interface for receiving notifications about tile
 * information when tiles of a WritableRenderedImage become modifiable or
 * unmodifiable.
 * 
 * @since Android 1.0
 */
public interface TileObserver {

    /**
     * This method is called when information about a tile update is available.
     * 
     * @param source
     *            the source image.
     * @param tileX
     *            the X index of the tile.
     * @param tileY
     *            the Y index of the tile.
     * @param willBeWritable
     *            parameter which indicates whether the tile will be grabbed for
     *            writing or be released.
     */
    public void tileUpdate(WritableRenderedImage source, int tileX, int tileY,
            boolean willBeWritable);

}
