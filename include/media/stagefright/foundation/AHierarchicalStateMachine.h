#ifndef A_HIERARCHICAL_STATE_MACHINE_H_

#define A_HIERARCHICAL_STATE_MACHINE_H_

#include <media/stagefright/foundation/AHandler.h>

namespace android {

struct AState : public RefBase {
    AState(const sp<AState> &parentState = NULL);

    sp<AState> parentState();

protected:
    virtual ~AState();

    virtual void stateEntered();
    virtual void stateExited();

    virtual bool onMessageReceived(const sp<AMessage> &msg) = 0;

private:
    friend struct AHierarchicalStateMachine;

    sp<AState> mParentState;

    DISALLOW_EVIL_CONSTRUCTORS(AState);
};

struct AHierarchicalStateMachine : public AHandler {
    AHierarchicalStateMachine();

protected:
    virtual ~AHierarchicalStateMachine();

    virtual void onMessageReceived(const sp<AMessage> &msg);

    // Only to be called in response to a message.
    void changeState(const sp<AState> &state);

private:
    sp<AState> mState;

    DISALLOW_EVIL_CONSTRUCTORS(AHierarchicalStateMachine);
};

}  // namespace android

#endif  // A_HIERARCHICAL_STATE_MACHINE_H_
