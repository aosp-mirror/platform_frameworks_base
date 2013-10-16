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

package androidx.media.filterpacks.text;

import android.view.View;
import android.widget.TextView;

import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.FrameValue;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.Signature;
import androidx.media.filterfw.ViewFilter;

public class TextViewTarget extends ViewFilter {

    private TextView mTextView = null;

    public TextViewTarget(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public void onBindToView(View view) {
        if (view instanceof TextView) {
            mTextView = (TextView)view;
        } else {
            throw new IllegalArgumentException("View must be a TextView!");
        }
    }

    @Override
    public Signature getSignature() {
        return new Signature()
            .addInputPort("text", Signature.PORT_REQUIRED, FrameType.single(String.class))
            .disallowOtherPorts();
    }

    @Override
    protected void onProcess() {
        FrameValue textFrame = getConnectedInputPort("text").pullFrame().asFrameValue();
        final String text = (String)textFrame.getValue();
        if (mTextView != null) {
            mTextView.post(new Runnable() {
                @Override
                public void run() {
                    mTextView.setText(text);
                }
            });
        }
    }
}

