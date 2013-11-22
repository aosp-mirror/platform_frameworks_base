/*
 * Copyright (C) 2012 The Android Open Source Project
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

package androidx.media.filterpacks.numeric;

import android.util.Log;
import androidx.media.filterfw.Filter;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.OutputPort;
import androidx.media.filterfw.Signature;

/**
 * Filter to calculate the 2-norm of the inputs. i.e. sqrt(x^2 + y^2)
 * TODO: Add support for more norms in the future.
 */
public final class NormFilter extends Filter {
   private static final String TAG = "NormFilter";
   private static boolean mLogVerbose = Log.isLoggable(TAG, Log.VERBOSE);

   public NormFilter(MffContext context, String name) {
       super(context, name);
   }

   @Override
   public Signature getSignature() {
       FrameType floatT = FrameType.single(float.class);
       return new Signature()
           .addInputPort("x", Signature.PORT_REQUIRED, floatT)
           .addInputPort("y", Signature.PORT_REQUIRED, floatT)
           .addOutputPort("norm", Signature.PORT_REQUIRED, floatT)
           .disallowOtherPorts();
   }

   @Override
   protected void onProcess() {
     FrameValue xFrameValue = getConnectedInputPort("x").pullFrame().asFrameValue();
     float xValue = ((Float)xFrameValue.getValue()).floatValue();
     FrameValue yFrameValue = getConnectedInputPort("y").pullFrame().asFrameValue();
     float yValue = ((Float)yFrameValue.getValue()).floatValue();

     float norm = (float) Math.hypot(xValue, yValue);
     if (mLogVerbose) Log.v(TAG, "Norm = " + norm);
     OutputPort outPort = getConnectedOutputPort("norm");
     FrameValue outFrame = outPort.fetchAvailableFrame(null).asFrameValue();
     outFrame.setValue(norm);
     outPort.pushFrame(outFrame);
   }
}
