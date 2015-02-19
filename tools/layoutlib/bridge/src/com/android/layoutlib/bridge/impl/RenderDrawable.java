/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.layoutlib.bridge.impl;

import static com.android.ide.common.rendering.api.Result.Status.ERROR_UNKNOWN;

import com.android.ide.common.rendering.api.DrawableParams;
import com.android.ide.common.rendering.api.HardwareConfig;
import com.android.ide.common.rendering.api.ResourceValue;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.Result.Status;
import com.android.layoutlib.bridge.android.BridgeContext;
import com.android.resources.ResourceType;

import android.graphics.Bitmap;
import android.graphics.Bitmap_Delegate;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.AttachInfo_Accessor;
import android.view.View.MeasureSpec;
import android.widget.FrameLayout;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;

/**
 * Action to render a given Drawable provided through {@link DrawableParams#getDrawable()}.
 *
 * The class only provides a simple {@link #render()} method, but the full life-cycle of the
 * action must be respected.
 *
 * @see RenderAction
 *
 */
public class RenderDrawable extends RenderAction<DrawableParams> {

    public RenderDrawable(DrawableParams params) {
        super(new DrawableParams(params));
    }

    public Result render() {
        checkLock();
        // get the drawable resource value
        DrawableParams params = getParams();
        HardwareConfig hardwareConfig = params.getHardwareConfig();
        ResourceValue drawableResource = params.getDrawable();

        // resolve it
        BridgeContext context = getContext();
        drawableResource = context.getRenderResources().resolveResValue(drawableResource);

        if (drawableResource == null ||
                drawableResource.getResourceType() != ResourceType.DRAWABLE) {
            return Status.ERROR_NOT_A_DRAWABLE.createResult();
        }

        // create a simple FrameLayout
        FrameLayout content = new FrameLayout(context);

        // get the actual Drawable object to draw
        Drawable d = ResourceHelper.getDrawable(drawableResource, context);
        content.setBackground(d);

        // set the AttachInfo on the root view.
        AttachInfo_Accessor.setAttachInfo(content);


        // measure
        int w = hardwareConfig.getScreenWidth();
        int h = hardwareConfig.getScreenHeight();
        int w_spec = MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY);
        int h_spec = MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY);
        content.measure(w_spec, h_spec);

        // now do the layout.
        content.layout(0, 0, w, h);

        // preDraw setup
        AttachInfo_Accessor.dispatchOnPreDraw(content);

        // draw into a new image
        BufferedImage image = getImage(w, h);

        // create an Android bitmap around the BufferedImage
        Bitmap bitmap = Bitmap_Delegate.createBitmap(image,
                true /*isMutable*/, hardwareConfig.getDensity());

        // create a Canvas around the Android bitmap
        Canvas canvas = new Canvas(bitmap);
        canvas.setDensity(hardwareConfig.getDensity().getDpiValue());

        // and draw
        content.draw(canvas);

        return Status.SUCCESS.createResult(image);
    }

    protected BufferedImage getImage(int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gc = image.createGraphics();
        gc.setComposite(AlphaComposite.Src);

        gc.setColor(new Color(0x00000000, true));
        gc.fillRect(0, 0, w, h);

        // done
        gc.dispose();

        return image;
    }

}
