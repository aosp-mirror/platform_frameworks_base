// Copyright 2012 Google Inc. All Rights Reserved.

package androidx.media.filterpacks.base;

import androidx.media.filterfw.Filter;
import androidx.media.filterfw.Frame;
import androidx.media.filterfw.FrameType;
import androidx.media.filterfw.MffContext;
import androidx.media.filterfw.Signature;

public class GraphInputSource extends Filter {

    private Frame mFrame = null;

    public GraphInputSource(MffContext context, String name) {
        super(context, name);
    }

    @Override
    public Signature getSignature() {
        return new Signature()
            .addOutputPort("frame", Signature.PORT_REQUIRED, FrameType.any())
            .disallowOtherInputs();
    }

    public void pushFrame(Frame frame) {
        if (mFrame != null) {
            mFrame.release();
        }
        if (frame == null) {
            throw new RuntimeException("Attempting to assign null-frame!");
        }
        mFrame = frame.retain();
    }

    @Override
    protected void onProcess() {
        if (mFrame != null) {
            getConnectedOutputPort("frame").pushFrame(mFrame);
            mFrame.release();
            mFrame = null;
        }
    }

    @Override
    protected void onTearDown() {
        if (mFrame != null) {
            mFrame.release();
            mFrame = null;
        }
    }

    @Override
    protected boolean canSchedule() {
        return super.canSchedule() && mFrame != null;
    }

}
