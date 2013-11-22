/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package androidx.media.filterfw;

import java.util.Vector;

class FrameQueue {

    public static class Builder {

        private FrameType mReadType = null;
        private FrameType mWriteType = null;

        private Vector<FrameQueue> mAttachedQueues = new Vector<FrameQueue>();

        public Builder() {}

        public void setWriteType(FrameType type) {
            mWriteType = type;
        }

        public void setReadType(FrameType type) {
            mReadType = type;
        }

        public void attachQueue(FrameQueue queue) {
            mAttachedQueues.add(queue);
        }

        public FrameQueue build(String name) {
            FrameType type = buildType();
            // TODO: This currently does not work correctly (Try camera -> branch -> target-slot)
            //validateType(type, name);
            FrameQueue result = new FrameQueue(type, name);
            buildQueueImpl(result);
            return result;
        }

        private void buildQueueImpl(FrameQueue queue) {
            QueueImpl queueImpl = queue.new SingleFrameQueueImpl();
            queue.mQueueImpl = queueImpl;
        }

        private FrameType buildType() {
            FrameType result = FrameType.merge(mWriteType, mReadType);
            for (FrameQueue queue : mAttachedQueues) {
                result = FrameType.merge(result, queue.mType);
            }
            return result;
        }

        /*
        private void validateType(FrameType type, String queueName) {
            if (!type.isSpecified()) {
                throw new RuntimeException("Cannot build connection queue '" + queueName + "' as "
                        + "its type (" + type + ") is underspecified!");
            }
        }
         */
    }

    private interface QueueImpl {
        public boolean canPull();

        public boolean canPush();

        public Frame pullFrame();

        public Frame fetchAvailableFrame(int[] dimensions);

        public Frame peek();

        public void pushFrame(Frame frame);

        public void clear();
    }

    private class SingleFrameQueueImpl implements QueueImpl {
        private Frame mFrame = null;

        @Override
        public boolean canPull() {
            return mFrame != null;
        }

        @Override
        public boolean canPush() {
            return mFrame == null;
        }

        @Override
        public Frame pullFrame() {
            Frame result = mFrame;
            mFrame = null;
            return result;
        }

        @Override
        public Frame peek() {
            return mFrame;
        }

        @Override
        public Frame fetchAvailableFrame(int[] dimensions) {
            // Note that we cannot use a cached frame here, as we do not know where that cached
            // instance would end up.
            FrameManager manager = FrameManager.current();
            return new Frame(mType, dimensions, manager);
        }

        @Override
        public void pushFrame(Frame frame) {
            mFrame = frame.retain();
            mFrame.setReadOnly(true);
        }

        @Override
        public void clear() {
            if (mFrame != null) {
                mFrame.release();
                mFrame = null;
            }
        }
    }

    private QueueImpl mQueueImpl;
    private FrameType mType;
    private String mName;

    public FrameType getType() {
        return mType;
    }

    public boolean canPull() {
        return mQueueImpl.canPull();
    }

    public boolean canPush() {
        return mQueueImpl.canPush();
    }

    public Frame pullFrame() {
        return mQueueImpl.pullFrame();
    }

    public Frame fetchAvailableFrame(int[] dimensions) {
        return mQueueImpl.fetchAvailableFrame(dimensions);
    }

    public void pushFrame(Frame frame) {
        mQueueImpl.pushFrame(frame);
    }

    public Frame peek() {
        return mQueueImpl.peek();
    }

    @Override
    public String toString() {
        return mName;
    }

    public void clear() {
        mQueueImpl.clear();
    }

    private FrameQueue(FrameType type, String name) {
        mType = type;
        mName = name;
    }

}
