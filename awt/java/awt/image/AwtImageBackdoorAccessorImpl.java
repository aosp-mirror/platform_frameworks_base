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
package java.awt.image;

import java.awt.Image;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferDouble;
import java.awt.image.DataBufferFloat;
import java.awt.image.DataBufferInt;
import java.awt.image.DataBufferShort;
import java.awt.image.DataBufferUShort;

import org.apache.harmony.awt.gl.AwtImageBackdoorAccessor;
import org.apache.harmony.awt.gl.GLVolatileImage;
import org.apache.harmony.awt.gl.Surface;
import org.apache.harmony.awt.gl.image.DataBufferListener;
import org.apache.harmony.awt.internal.nls.Messages;

/**
 * This class not part of public API. It useful for receiving package private
 * data from other packages.
 */
class AwtImageBackdoorAccessorImpl extends AwtImageBackdoorAccessor {

    static void init(){
        inst = new AwtImageBackdoorAccessorImpl();
    }

    @Override
    public Surface getImageSurface(Image image) {
        if (image instanceof BufferedImage){
            return ((BufferedImage)image).getImageSurface();
        } else if (image instanceof GLVolatileImage){
            return ((GLVolatileImage)image).getImageSurface();
        }
        return null;
    }

    @Override
    public boolean isGrayPallete(IndexColorModel icm){
        return icm.isGrayPallete();
    }

    @Override
    public Object getData(DataBuffer db) {
        if (db instanceof DataBufferByte){
            return ((DataBufferByte)db).getData();
        } else if (db instanceof DataBufferUShort){
            return ((DataBufferUShort)db).getData();
        } else if (db instanceof DataBufferShort){
            return ((DataBufferShort)db).getData();
        } else if (db instanceof DataBufferInt){
            return ((DataBufferInt)db).getData();
        } else if (db instanceof DataBufferFloat){
            return ((DataBufferFloat)db).getData();
        } else if (db instanceof DataBufferDouble){
            return ((DataBufferDouble)db).getData();
        } else {
            // awt.235=Wrong Data Buffer type : {0}
            throw new IllegalArgumentException(Messages.getString("awt.235", //$NON-NLS-1$
                    db.getClass()));
        }
    }

    @Override
    public int[] getDataInt(DataBuffer db) {
        if (db instanceof DataBufferInt){
            return ((DataBufferInt)db).getData();
        }
        return null;
    }

    @Override
    public byte[] getDataByte(DataBuffer db) {
        if (db instanceof DataBufferByte){
            return ((DataBufferByte)db).getData();
        }
        return null;
    }

    @Override
    public short[] getDataShort(DataBuffer db) {
        if (db instanceof DataBufferShort){
            return ((DataBufferShort)db).getData();
        }
        return null;
    }

    @Override
    public short[] getDataUShort(DataBuffer db) {
        if (db instanceof DataBufferUShort){
            return ((DataBufferUShort)db).getData();
        }
        return null;
    }

    @Override
    public double[] getDataDouble(DataBuffer db) {
        if (db instanceof DataBufferDouble){
            return ((DataBufferDouble)db).getData();
        }
        return null;
    }

    @Override
    public float[] getDataFloat(DataBuffer db) {
        if (db instanceof DataBufferFloat){
            return ((DataBufferFloat)db).getData();
        }
        return null;
    }

    @Override
    public void addDataBufferListener(DataBuffer db, DataBufferListener listener) {
        db.addDataBufferListener(listener);
    }

    @Override
    public void removeDataBufferListener(DataBuffer db) {
        db.removeDataBufferListener();
    }

    @Override
    public void validate(DataBuffer db) {
        db.validate();
    }

    @Override
    public void releaseData(DataBuffer db) {
        db.releaseData();
    }
}
