/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.layoutlib.bridge;import com.android.ide.common.rendering.api.RenderSession;
import com.android.ide.common.rendering.api.Result;
import com.android.ide.common.rendering.api.Result.Status;
import com.android.ide.common.rendering.api.SessionParams;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Legacy Bridge used in the SDK version of layoutlib
 */
public final class Bridge extends com.android.ide.common.rendering.api.Bridge {
    private static final String SDK_NOT_SUPPORTED = "The SDK layoutlib version is not supported";
    private static final Result NOT_SUPPORTED_RESULT =
            Status.NOT_IMPLEMENTED.createResult(SDK_NOT_SUPPORTED);
    private static BufferedImage sImage;

    private static class BridgeRenderSession extends RenderSession {

        @Override
        public synchronized BufferedImage getImage() {
            if (sImage == null) {
                sImage = new BufferedImage(500, 500, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = sImage.createGraphics();
                g.clearRect(0, 0, 500, 500);
                g.drawString(SDK_NOT_SUPPORTED, 20, 20);
                g.dispose();
            }

            return sImage;
        }

        @Override
        public Result render(long timeout, boolean forceMeasure) {
            return NOT_SUPPORTED_RESULT;
        }

        @Override
        public Result measure(long timeout) {
            return NOT_SUPPORTED_RESULT;
        }

        @Override
        public Result getResult() {
            return NOT_SUPPORTED_RESULT;
        }
    }


    @Override
    public RenderSession createSession(SessionParams params) {
        return new BridgeRenderSession();
    }

    @Override
    public int getApiLevel() {
        return 0;
    }
}
