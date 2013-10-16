// Copyright 2012 Google Inc. All Rights Reserved.

package androidx.media.filterpacks.base;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.Signature;

public class GraphOutputTarget extends Filter {

    private Frame mFrame = null;
    private FrameType mType = FrameType.any();

    public GraphOutputTarget(MffContext context, String name) {
        super(context, name);
    }

    // TODO: During initialization only?
    public void setType(FrameType type) {
        mType = type;
    }

    public FrameType getType() {
        return mType;
    }

    @Override
    public Signature getSignature() {
        return new Signature()
            .addInputPort("frame", Signature.PORT_REQUIRED, mType)
            .disallowOtherInputs();
    }

    // Returns a retained frame!
    public Frame pullFrame() {
        Frame result = null;
        if (mFrame != null) {
            result = mFrame;
            mFrame = null;
        }
        return result;
    }

    @Override
    protected void onProcess() {
        Frame frame = getConnectedInputPort("frame").pullFrame();
        if (mFrame != null) {
            mFrame.release();
        }
        mFrame = frame.retain();
    }

    @Override
    protected boolean canSchedule() {
        return super.canSchedule() && mFrame == null;
    }

}
