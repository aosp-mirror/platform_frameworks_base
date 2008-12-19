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
 * @author Pavel Dolgov
 * @version $Revision$
 */

package java.awt;

/**
 * The Transparency interface defines transparency's general modes.
 * 
 * @since Android 1.0
 */
public interface Transparency {

    /**
     * The Constant OPAQUE represents completely opaque data, all pixels have an
     * alpha value of 1.0.
     */
    public static final int OPAQUE = 1;

    /**
     * The Constant BITMASK represents data which can be either completely
     * opaque, with an alpha value of 1.0, or completely transparent, with an
     * alpha value of 0.0.
     */
    public static final int BITMASK = 2;

    /**
     * The Constant TRANSLUCENT represents data which alpha value can vary
     * between and including 0.0 and 1.0.
     */
    public static final int TRANSLUCENT = 3;

    /**
     * Gets the transparency mode.
     * 
     * @return the transparency mode: OPAQUE, BITMASK or TRANSLUCENT.
     */
    public int getTransparency();

}
