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

import com.android.layoutlib.api.SceneResult;
import com.android.layoutlib.api.LayoutScene.IAnimationListener;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Handler_Delegate;
import android.os.Message;
import android.os.Handler_Delegate.IHandlerCallback;

import java.util.LinkedList;
import java.util.Queue;

public class AnimationThread extends Thread {

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
    private final Animator mAnimator;

    Queue<MessageBundle> mQueue = new LinkedList<MessageBundle>();
    private final IAnimationListener mListener;

    public AnimationThread(LayoutSceneImpl scene, Animator animator, IAnimationListener listener) {
        mScene = scene;
        mAnimator = animator;
        mListener = listener;
    }

    @Override
    public void run() {
        mScene.prepareThread();
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

            // start the animation. This will send a message to the handler right away, so
            // mQueue is filled when this method returns.
            mAnimator.start();

            // loop the animation
            do {
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
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }

                // ready to do the work, acquire the scene.
                SceneResult result = mScene.acquire(250);
                if (result != SceneResult.SUCCESS) {
                    mListener.done(result);
                    return;
                }

                // process the bundle. If the animation is not finished, this will enqueue
                // the next message, so mQueue will have another one.
                try {
                    bundle.mTarget.handleMessage(bundle.mMessage);
                    if (mScene.render() == SceneResult.SUCCESS) {
                        mListener.onNewFrame(mScene.getImage());
                    }
                } finally {
                    mScene.release();
                }
            } while (mQueue.size() > 0);

            mListener.done(SceneResult.SUCCESS);
        } finally {
            Handler_Delegate.setCallback(null);
            mScene.cleanupThread();
        }
    }
}
