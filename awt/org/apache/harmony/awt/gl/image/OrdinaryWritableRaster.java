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
/*
 * Created on 30.09.2004
 *
 */
package org.apache.harmony.awt.gl.image;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;

public class OrdinaryWritableRaster extends WritableRaster {

    public OrdinaryWritableRaster(SampleModel sampleModel,
            DataBuffer dataBuffer, Rectangle aRegion,
            Point sampleModelTranslate, WritableRaster parent) {
        super(sampleModel, dataBuffer, aRegion, sampleModelTranslate, parent);
    }

    public OrdinaryWritableRaster(SampleModel sampleModel,
            DataBuffer dataBuffer, Point origin) {
        super(sampleModel, dataBuffer, origin);
    }

    public OrdinaryWritableRaster(SampleModel sampleModel, Point origin) {
        super(sampleModel, origin);
    }

    @Override
    public void setDataElements(int x, int y, Object inData) {
        super.setDataElements(x, y, inData);
    }

    @Override
    public void setDataElements(int x, int y, int w, int h, Object inData) {
        super.setDataElements(x, y, w, h, inData);
    }

    @Override
    public WritableRaster createWritableChild(int parentX, int parentY, int w,
            int h, int childMinX, int childMinY, int[] bandList) {
        return super.createWritableChild(parentX, parentY, w, h, childMinX,
                childMinY, bandList);
    }

    @Override
    public WritableRaster createWritableTranslatedChild(int childMinX,
            int childMinY) {
        return super.createWritableTranslatedChild(childMinX, childMinY);
    }

    @Override
    public WritableRaster getWritableParent() {
        return super.getWritableParent();
    }

    @Override
    public void setRect(Raster srcRaster) {
        super.setRect(srcRaster);
    }

    @Override
    public void setRect(int dx, int dy, Raster srcRaster) {
        super.setRect(dx, dy, srcRaster);
    }

    @Override
    public void setDataElements(int x, int y, Raster inRaster) {
        super.setDataElements(x, y, inRaster);
    }

    @Override
    public void setPixel(int x, int y, int[] iArray) {
        super.setPixel(x, y, iArray);
    }

    @Override
    public void setPixel(int x, int y, float[] fArray) {
        super.setPixel(x, y, fArray);
    }

    @Override
    public void setPixel(int x, int y, double[] dArray) {
        super.setPixel(x, y, dArray);
    }

    @Override
    public void setPixels(int x, int y, int w, int h, int[] iArray) {
        super.setPixels(x, y, w, h, iArray);
    }

    @Override
    public void setPixels(int x, int y, int w, int h, float[] fArray) {
        super.setPixels(x, y, w, h, fArray);
    }

    @Override
    public void setPixels(int x, int y, int w, int h, double[] dArray) {
        super.setPixels(x, y, w, h, dArray);
    }

    @Override
    public void setSamples(int x, int y, int w, int h, int b, int[] iArray) {
        super.setSamples(x, y, w, h, b, iArray);
    }

    @Override
    public void setSamples(int x, int y, int w, int h, int b, float[] fArray) {
        super.setSamples(x, y, w, h, b, fArray);
    }

    @Override
    public void setSamples(int x, int y, int w, int h, int b, double[] dArray) {
        super.setSamples(x, y, w, h, b, dArray);
    }

    @Override
    public void setSample(int x, int y, int b, int s) {
        super.setSample(x, y, b, s);
    }

    @Override
    public void setSample(int x, int y, int b, float s) {
        super.setSample(x, y, b, s);
    }

    @Override
    public void setSample(int x, int y, int b, double s) {
        super.setSample(x, y, b, s);
    }
}