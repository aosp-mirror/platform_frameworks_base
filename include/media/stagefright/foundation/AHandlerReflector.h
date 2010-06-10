#ifndef A_HANDLER_REFLECTOR_H_

#define A_HANDLER_REFLECTOR_H_

#include <media/stagefright/foundation/AHandler.h>

namespace android {

template<class T>
struct AHandlerReflector : public AHandler {
    AHandlerReflector(T *target)
        : mTarget(target) {
    }

protected:
    virtual void onMessageReceived(const sp<AMessage> &msg) {
        sp<T> target = mTarget.promote();
        if (target != NULL) {
            target->onMessageReceived(msg);
        }
    }

private:
    wp<T> mTarget;

    AHandlerReflector(const AHandlerReflector<T> &);
    AHandlerReflector<T> &operator=(const AHandlerReflector<T> &);
};

}  // namespace android

#endif  // A_HANDLER_REFLECTOR_H_
