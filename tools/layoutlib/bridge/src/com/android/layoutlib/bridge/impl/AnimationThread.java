/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.layoutlib.api.LayoutScene;
import com.android.layoutlib.api.SceneResult;
import com.android.layoutlib.api.LayoutScene.IAnimationListener;
import com.android.layoutlib.api.SceneResult.SceneStatus;
import com.android.layoutlib.bridge.Bridge;

import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Handler_Delegate;
import android.os.Message;
import android.os.Handler_Delegate.IHandlerCallback;

import java.util.LinkedList;
import java.util.Queue;

/**
 * Abstract animation thread.
 * <p/>
 * This does not actually start an animation, instead it fakes a looper that will play whatever
 * animation is sending messages to its own {@link Handler}.
 * <p/>
 * Classes should implement {@link #preAnimation()} and {@link #postAnimation()}.
 * <p/>
 * If {@link #preAnimation()} does not start an animation something then the thread doesn't do
 * anything.
 *
 */
public abstract class AnimationThread extends Thread {

    private static class MessageBundle {
        final Handler mTarget;
        final Message mMessage;
        final long mUptimeMillis;

        MessageBundle(Handler target, Message message, long uptimeMillis) {
            mTarget = target;
            mMessage = message;
            mUptimeMillis = uptimeMillis;
        }
    }

    private final LayoutSceneImpl mScene;

    Queue<MessageBundle> mQueue = new LinkedList<MessageBundle>();
    private final IAnimationListener mListener;

    public AnimationThread(LayoutSceneImpl scene, String threadName, IAnimationListener listener) {
        super(threadName);
        mScene = scene;
        mListener = listener;
    }

    public abstract SceneResult preAnimation();
    public abstract void postAnimation();

    @Override
    public void run() {
        Bridge.prepareThread();
        try {
            Handler_Delegate.setCallback(new IHandlerCallback() {
                public void sendMessageAtTime(Handler handler, Message msg, long uptimeMillis) {
                    if (msg.what == ValueAnimator.ANIMATION_START ||
                            msg.what == ValueAnimator.ANIMATION_FRAME) {
                        mQueue.add(new MessageBundle(handler, msg, uptimeMillis));
                    } else {
                        // just ignore.
                    }
                }
            });

            // call out to the pre-animation work, which should start an animation or more.
            SceneResult result = preAnimation();
            if (result.isSuccess() == false) {
                mListener.done(result);
            }

            // loop the animation
            LayoutScene scene = mScene.getScene();
            do {
                // check early.
                if (mListener.isCanceled()) {
                    break;
                }

                // get the next message.
                MessageBundle bundle = mQueue.poll();
                if (bundle == null) {
                    break;
                }

                // sleep enough for this bundle to be on time
                long currentTime = System.currentTimeMillis();
                if (currentTime < bundle.mUptimeMillis) {
                    try {
                        sleep(bundle.mUptimeMillis - currentTime);
                    } catch (InterruptedException e) {
                        // FIXME log/do something/sleep again?
                        e.printStackTrace();
                    }
                }

                // check after sleeping.
                if (mListener.isCanceled()) {
                    break;
                }

                // ready to do the work, acquire the scene.
                result = mScene.acquire(250);
                if (result.isSuccess() == false) {
                    mListener.done(result);
                    return;
                }

                // process the bundle. If the animation is not finished, this will enqueue
                // the next message, so mQueue will have another one.
                try {
                    // check after acquiring in case it took a while.
                    if (mListener.isCanceled()) {
                        break;
                    }

                    bundle.mTarget.handleMessage(bundle.mMessage);
                    if (mScene.render().isSuccess()) {
                        mListener.onNewFrame(scene);
                    }
                } finally {
                    mScene.release();
                }
            } while (mListener.isCanceled() == false && mQueue.size() > 0);

            mListener.done(SceneStatus.SUCCESS.getResult());
        } finally {
            postAnimation();
            Handler_Delegate.setCallback(null);
            Bridge.cleanupThread();
        }
    }
}
