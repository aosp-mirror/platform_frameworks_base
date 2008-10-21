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
package org.apache.harmony.awt.gl;

import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

import org.apache.harmony.awt.gl.ImageSurface;
import org.apache.harmony.awt.gl.render.NativeImageBlitter;
import org.apache.harmony.awt.internal.nls.Messages;


/**
 * This class represent implementation of the CompositeContext interface
 */
public class ICompositeContext implements CompositeContext {
    Composite composite;
    ColorModel srcCM, dstCM;
    ImageSurface srcSurf, dstSurf;

    public ICompositeContext(Composite comp, ColorModel src, ColorModel dst){
        composite = comp;
        srcCM = src;
        dstCM = dst;
    }

    public void dispose() {
        srcSurf.dispose();
        dstSurf.dispose();
    }

    public void compose(Raster srcIn, Raster dstIn, WritableRaster dstOut) {

        if(!srcCM.isCompatibleRaster(srcIn)) {
            // awt.48=The srcIn raster is incompatible with src ColorModel
            throw new IllegalArgumentException(Messages.getString("awt.48")); //$NON-NLS-1$
        }

        if(!dstCM.isCompatibleRaster(dstIn)) {
            // awt.49=The dstIn raster is incompatible with dst ColorModel
            throw new IllegalArgumentException(Messages.getString("awt.49")); //$NON-NLS-1$
        }

        if(dstIn != dstOut){
            if(!dstCM.isCompatibleRaster(dstOut)) {
                // awt.4A=The dstOut raster is incompatible with dst ColorModel
                throw new IllegalArgumentException(Messages.getString("awt.4A")); //$NON-NLS-1$
            }
            dstOut.setDataElements(0, 0, dstIn);
        }
        WritableRaster src;
        if(srcIn instanceof WritableRaster){
            src = (WritableRaster) srcIn;
        }else{
            src = srcIn.createCompatibleWritableRaster();
            src.setDataElements(0, 0, srcIn);
        }
        srcSurf = new ImageSurface(srcCM, src);
        dstSurf = new ImageSurface(dstCM, dstOut);

        int w = Math.min(srcIn.getWidth(), dstOut.getWidth());
        int h = Math.min(srcIn.getHeight(), dstOut.getHeight());

        NativeImageBlitter.getInstance().blit(0, 0, srcSurf, 0, 0, dstSurf,
                w, h, composite, null, null);

    }

}
