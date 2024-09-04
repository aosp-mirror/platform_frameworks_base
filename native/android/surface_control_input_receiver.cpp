/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <android/choreographer.h>
#include <android/surface_control_input_receiver.h>
#include <binder/Binder.h>
#include <gui/Choreographer.h>
#include <gui/InputTransferToken.h>
#include <input/Input.h>
#include <input/InputConsumerNoResampling.h>

#include "android_view_WindowManagerGlobal.h"

using namespace android;

extern void InputTransferToken_acquire(InputTransferToken* inputTransferToken);

struct AInputReceiverCallbacks {
    AInputReceiverCallbacks(void* context) : context(context) {}
    void* context;
    AInputReceiver_onMotionEvent onMotionEvent = nullptr;
    AInputReceiver_onKeyEvent onKeyEvent = nullptr;
};

class InputReceiver : public InputConsumerCallbacks {
public:
    InputReceiver(const sp<Looper>& looper, const std::shared_ptr<InputChannel>& inputChannel,
                  const sp<IBinder>& clientToken, const sp<InputTransferToken>& inputTransferToken,
                  AInputReceiverCallbacks* callbacks)
          : mCallbacks(callbacks),
            mInputConsumer(inputChannel, looper, *this, nullptr),
            mClientToken(clientToken),
            mInputTransferToken(inputTransferToken) {}

    // The InputConsumer does not keep the InputReceiver alive so the receiver is cleared once the
    // owner releases it.
    ~InputReceiver() {
        remove();
    }

    void onKeyEvent(std::unique_ptr<KeyEvent> event, uint32_t seq) override {
        if (mCallbacks->onKeyEvent != nullptr) {
            const bool handled = mCallbacks->onKeyEvent(mCallbacks->context,
                                                        static_cast<AInputEvent*>(event.release()));
            mInputConsumer.finishInputEvent(seq, handled);
        }
    }

    void onMotionEvent(std::unique_ptr<MotionEvent> event, uint32_t seq) override {
        if (mCallbacks->onMotionEvent != nullptr) {
            const bool handled =
                    mCallbacks->onMotionEvent(mCallbacks->context,
                                              static_cast<AInputEvent*>(event.release()));
            mInputConsumer.finishInputEvent(seq, handled);
        }
    }

    void onFocusEvent(std::unique_ptr<FocusEvent>, uint32_t seq) override {
        mInputConsumer.finishInputEvent(seq, false);
    }
    void onCaptureEvent(std::unique_ptr<CaptureEvent>, uint32_t seq) override {
        mInputConsumer.finishInputEvent(seq, false);
    }
    void onDragEvent(std::unique_ptr<DragEvent>, uint32_t seq) override {
        mInputConsumer.finishInputEvent(seq, false);
    }
    void onTouchModeEvent(std::unique_ptr<TouchModeEvent>, uint32_t seq) override {
        mInputConsumer.finishInputEvent(seq, false);
    }

    virtual void onBatchedInputEventPending(int32_t) override {
        mInputConsumer.consumeBatchedInputEvents(std::nullopt);
    }

    const AInputTransferToken* getInputTransferToken() {
        InputTransferToken_acquire(mInputTransferToken.get());
        return reinterpret_cast<const AInputTransferToken*>(mInputTransferToken.get());
    }

    void remove() {
        removeInputChannel(mClientToken);
    }

    AInputReceiverCallbacks* mCallbacks;

protected:
    InputConsumerNoResampling mInputConsumer;

private:
    const sp<IBinder> mClientToken;
    const sp<InputTransferToken> mInputTransferToken;
};

class BatchedInputReceiver : public InputReceiver {
public:
    BatchedInputReceiver(Choreographer& choreographer,
                         const std::shared_ptr<InputChannel>& inputChannel,
                         const sp<IBinder>& clientToken,
                         const sp<InputTransferToken>& inputTransferToken,
                         AInputReceiverCallbacks* callbacks)
          : InputReceiver(choreographer.getLooper(), inputChannel, clientToken, inputTransferToken,
                          callbacks),
            mChoreographer(choreographer) {}

    static void vsyncCallback(const AChoreographerFrameCallbackData* callbackData, void* data) {
        BatchedInputReceiver* receiver = static_cast<BatchedInputReceiver*>(data);
        receiver->onVsyncCallback(callbackData);
    }

    void onVsyncCallback(const AChoreographerFrameCallbackData* callbackData) {
        int64_t frameTimeNanos = AChoreographerFrameCallbackData_getFrameTimeNanos(callbackData);
        mInputConsumer.consumeBatchedInputEvents(frameTimeNanos);
        mBatchedInputScheduled = false;
    }

    void onBatchedInputEventPending(int32_t) override {
        scheduleBatchedInput();
    }

private:
    Choreographer& mChoreographer;
    bool mBatchedInputScheduled = false;

    void scheduleBatchedInput() {
        if (!mBatchedInputScheduled) {
            mBatchedInputScheduled = true;
            mChoreographer.postFrameCallbackDelayed(nullptr, nullptr, vsyncCallback, this, 0,
                                                    CallbackType::CALLBACK_INPUT);
        }
    }
};

static inline AInputReceiver* InputReceiver_to_AInputReceiver(InputReceiver* inputReceiver) {
    return reinterpret_cast<AInputReceiver*>(inputReceiver);
}

static inline InputReceiver* AInputReceiver_to_InputReceiver(AInputReceiver* aInputReceiver) {
    return reinterpret_cast<InputReceiver*>(aInputReceiver);
}

AInputReceiver* AInputReceiver_createBatchedInputReceiver(AChoreographer* aChoreographer,
                                                          const AInputTransferToken* hostToken,
                                                          const ASurfaceControl* aSurfaceControl,
                                                          AInputReceiverCallbacks* callbacks) {
    // create input channel here through WMS
    sp<IBinder> clientToken = sp<BBinder>::make();
    sp<InputTransferToken> clientInputTransferToken = sp<InputTransferToken>::make();

    std::shared_ptr<InputChannel> inputChannel =
            createInputChannel(clientToken, reinterpret_cast<const InputTransferToken&>(*hostToken),
                               reinterpret_cast<const SurfaceControl&>(*aSurfaceControl),
                               *clientInputTransferToken);
    return InputReceiver_to_AInputReceiver(
            new BatchedInputReceiver(reinterpret_cast<Choreographer&>(*aChoreographer),
                                     inputChannel, clientToken, clientInputTransferToken,
                                     callbacks));
}

AInputReceiver* AInputReceiver_createUnbatchedInputReceiver(ALooper* aLooper,
                                                            const AInputTransferToken* hostToken,
                                                            const ASurfaceControl* aSurfaceControl,
                                                            AInputReceiverCallbacks* callbacks) {
    // create input channel here through WMS
    sp<IBinder> clientToken = sp<BBinder>::make();
    sp<InputTransferToken> clientInputTransferToken = sp<InputTransferToken>::make();

    std::shared_ptr<InputChannel> inputChannel =
            createInputChannel(clientToken, reinterpret_cast<const InputTransferToken&>(*hostToken),
                               reinterpret_cast<const SurfaceControl&>(*aSurfaceControl),
                               *clientInputTransferToken);
    return InputReceiver_to_AInputReceiver(new InputReceiver(reinterpret_cast<Looper*>(aLooper),
                                                             inputChannel, clientToken,
                                                             clientInputTransferToken, callbacks));
}

const AInputTransferToken* AInputReceiver_getInputTransferToken(AInputReceiver* aInputReceiver) {
    return AInputReceiver_to_InputReceiver(aInputReceiver)->getInputTransferToken();
}

void AInputReceiver_release(AInputReceiver* aInputReceiver) {
    InputReceiver* inputReceiver = AInputReceiver_to_InputReceiver(aInputReceiver);
    if (inputReceiver != nullptr) {
        inputReceiver->remove();
    }
    delete inputReceiver;
}

void AInputReceiverCallbacks_setMotionEventCallback(AInputReceiverCallbacks* callbacks,
                                                    AInputReceiver_onMotionEvent onMotionEvent) {
    callbacks->onMotionEvent = onMotionEvent;
}

void AInputReceiverCallbacks_setKeyEventCallback(AInputReceiverCallbacks* callbacks,
                                                 AInputReceiver_onKeyEvent onKeyEvent) {
    callbacks->onKeyEvent = onKeyEvent;
}

AInputReceiverCallbacks* AInputReceiverCallbacks_create(void* context) {
    return new AInputReceiverCallbacks(context);
}

void AInputReceiverCallbacks_release(AInputReceiverCallbacks* callbacks) {
    delete callbacks;
}