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

#include <media/stagefright/foundation/AHierarchicalStateMachine.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <utils/Vector.h>

namespace android {

AState::AState(const sp<AState> &parentState)
    : mParentState(parentState) {
}

AState::~AState() {
}

sp<AState> AState::parentState() {
    return mParentState;
}

void AState::stateEntered() {
}

void AState::stateExited() {
}

////////////////////////////////////////////////////////////////////////////////

AHierarchicalStateMachine::AHierarchicalStateMachine() {
}

AHierarchicalStateMachine::~AHierarchicalStateMachine() {
}

void AHierarchicalStateMachine::onMessageReceived(const sp<AMessage> &msg) {
    sp<AState> save = mState;

    sp<AState> cur = mState;
    while (cur != NULL && !cur->onMessageReceived(msg)) {
        // If you claim not to have handled the message you shouldn't
        // have called setState...
        CHECK(save == mState);

        cur = cur->parentState();
    }

    if (cur != NULL) {
        return;
    }

    ALOGW("Warning message %s unhandled in root state.",
         msg->debugString().c_str());
}

void AHierarchicalStateMachine::changeState(const sp<AState> &state) {
    if (state == mState) {
        // Quick exit for the easy case.
        return;
    }

    Vector<sp<AState> > A;
    sp<AState> cur = mState;
    for (;;) {
        A.push(cur);
        if (cur == NULL) {
            break;
        }
        cur = cur->parentState();
    }

    Vector<sp<AState> > B;
    cur = state;
    for (;;) {
        B.push(cur);
        if (cur == NULL) {
            break;
        }
        cur = cur->parentState();
    }

    // Remove the common tail.
    while (A.size() > 0 && B.size() > 0 && A.top() == B.top()) {
        A.pop();
        B.pop();
    }

    mState = state;

    for (size_t i = 0; i < A.size(); ++i) {
        A.editItemAt(i)->stateExited();
    }

    for (size_t i = B.size(); i-- > 0;) {
        B.editItemAt(i)->stateEntered();
    }
}

}  // namespace android
