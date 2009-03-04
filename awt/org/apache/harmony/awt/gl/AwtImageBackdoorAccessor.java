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
 * Created on 23.11.2005
 *
 */


package org.apache.harmony.awt.gl;

import java.awt.Image;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.DataBufferInt;

import org.apache.harmony.awt.gl.image.DataBufferListener;

/**
 * This class give an opportunity to get access to private data of 
 * some java.awt.image classes 
 * Implementation of this class placed in java.awt.image package
 */

public abstract class AwtImageBackdoorAccessor {

    static protected AwtImageBackdoorAccessor inst;

    public static AwtImageBackdoorAccessor getInstance(){
        // First we need to run the static initializer in the DataBuffer class to resolve inst.
        new DataBufferInt(0);
        return inst;
    }

    public abstract Surface getImageSurface(Image image);
    public abstract boolean isGrayPallete(IndexColorModel icm);

    public abstract Object getData(DataBuffer db);
    public abstract int[] getDataInt(DataBuffer db);
    public abstract byte[] getDataByte(DataBuffer db);
    public abstract short[] getDataShort(DataBuffer db);
    public abstract short[] getDataUShort(DataBuffer db);
    public abstract double[] getDataDouble(DataBuffer db);
    public abstract float[] getDataFloat(DataBuffer db);
    public abstract void releaseData(DataBuffer db);
    
    public abstract void addDataBufferListener(DataBuffer db, DataBufferListener listener);
    public abstract void removeDataBufferListener(DataBuffer db);
    public abstract void validate(DataBuffer db);
}
