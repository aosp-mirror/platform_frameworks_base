/*
 * Copyright (C) 2007 The Android Open Source Project
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

#define LOG_TAG "Region"

#include <limits.h>

#include <utils/Log.h>
#include <utils/String8.h>

#include <ui/Rect.h>
#include <ui/Region.h>
#include <ui/Point.h>

#include <private/ui/RegionHelper.h>

// ----------------------------------------------------------------------------
#define VALIDATE_REGIONS        (false)
#define VALIDATE_WITH_CORECG    (false)
// ----------------------------------------------------------------------------

#if VALIDATE_WITH_CORECG
#include <core/SkRegion.h>
#endif

namespace android {
// ----------------------------------------------------------------------------

enum {
    op_nand = region_operator<Rect>::op_nand,
    op_and  = region_operator<Rect>::op_and,
    op_or   = region_operator<Rect>::op_or,
    op_xor  = region_operator<Rect>::op_xor
};

// ----------------------------------------------------------------------------

Region::Region()
    : mBounds(0,0)
{
}

Region::Region(const Region& rhs)
    : mBounds(rhs.mBounds), mStorage(rhs.mStorage)
{
#if VALIDATE_REGIONS
    validate(rhs, "rhs copy-ctor");
#endif
}

Region::Region(const Rect& rhs)
    : mBounds(rhs)
{
}

Region::Region(const void* buffer)
{
    status_t err = read(buffer);
    ALOGE_IF(err<0, "error %s reading Region from buffer", strerror(err));
}

Region::~Region()
{
}

Region& Region::operator = (const Region& rhs)
{
#if VALIDATE_REGIONS
    validate(*this, "this->operator=");
    validate(rhs, "rhs.operator=");
#endif
    mBounds = rhs.mBounds;
    mStorage = rhs.mStorage;
    return *this;
}

Region& Region::makeBoundsSelf()
{
    mStorage.clear();
    return *this;
}

void Region::clear()
{
    mBounds.clear();
    mStorage.clear();
}

void Region::set(const Rect& r)
{
    mBounds = r;
    mStorage.clear();
}

void Region::set(uint32_t w, uint32_t h)
{
    mBounds = Rect(int(w), int(h));
    mStorage.clear();
}

// ----------------------------------------------------------------------------

void Region::addRectUnchecked(int l, int t, int r, int b)
{
    mStorage.add(Rect(l,t,r,b));
#if VALIDATE_REGIONS
    validate(*this, "addRectUnchecked");
#endif
}

// ----------------------------------------------------------------------------

Region& Region::orSelf(const Rect& r) {
    return operationSelf(r, op_or);
}
Region& Region::andSelf(const Rect& r) {
    return operationSelf(r, op_and);
}
Region& Region::subtractSelf(const Rect& r) {
    return operationSelf(r, op_nand);
}
Region& Region::operationSelf(const Rect& r, int op) {
    Region lhs(*this);
    boolean_operation(op, *this, lhs, r);
    return *this;
}

// ----------------------------------------------------------------------------

Region& Region::orSelf(const Region& rhs) {
    return operationSelf(rhs, op_or);
}
Region& Region::andSelf(const Region& rhs) {
    return operationSelf(rhs, op_and);
}
Region& Region::subtractSelf(const Region& rhs) {
    return operationSelf(rhs, op_nand);
}
Region& Region::operationSelf(const Region& rhs, int op) {
    Region lhs(*this);
    boolean_operation(op, *this, lhs, rhs);
    return *this;
}

Region& Region::translateSelf(int x, int y) {
    if (x|y) translate(*this, x, y);
    return *this;
}

// ----------------------------------------------------------------------------

const Region Region::merge(const Rect& rhs) const {
    return operation(rhs, op_or);
}
const Region Region::intersect(const Rect& rhs) const {
    return operation(rhs, op_and);
}
const Region Region::subtract(const Rect& rhs) const {
    return operation(rhs, op_nand);
}
const Region Region::operation(const Rect& rhs, int op) const {
    Region result;
    boolean_operation(op, result, *this, rhs);
    return result;
}

// ----------------------------------------------------------------------------

const Region Region::merge(const Region& rhs) const {
    return operation(rhs, op_or);
}
const Region Region::intersect(const Region& rhs) const {
    return operation(rhs, op_and);
}
const Region Region::subtract(const Region& rhs) const {
    return operation(rhs, op_nand);
}
const Region Region::operation(const Region& rhs, int op) const {
    Region result;
    boolean_operation(op, result, *this, rhs);
    return result;
}

const Region Region::translate(int x, int y) const {
    Region result;
    translate(result, *this, x, y);
    return result;
}

// ----------------------------------------------------------------------------

Region& Region::orSelf(const Region& rhs, int dx, int dy) {
    return operationSelf(rhs, dx, dy, op_or);
}
Region& Region::andSelf(const Region& rhs, int dx, int dy) {
    return operationSelf(rhs, dx, dy, op_and);
}
Region& Region::subtractSelf(const Region& rhs, int dx, int dy) {
    return operationSelf(rhs, dx, dy, op_nand);
}
Region& Region::operationSelf(const Region& rhs, int dx, int dy, int op) {
    Region lhs(*this);
    boolean_operation(op, *this, lhs, rhs, dx, dy);
    return *this;
}

// ----------------------------------------------------------------------------

const Region Region::merge(const Region& rhs, int dx, int dy) const {
    return operation(rhs, dx, dy, op_or);
}
const Region Region::intersect(const Region& rhs, int dx, int dy) const {
    return operation(rhs, dx, dy, op_and);
}
const Region Region::subtract(const Region& rhs, int dx, int dy) const {
    return operation(rhs, dx, dy, op_nand);
}
const Region Region::operation(const Region& rhs, int dx, int dy, int op) const {
    Region result;
    boolean_operation(op, result, *this, rhs, dx, dy);
    return result;
}

// ----------------------------------------------------------------------------

// This is our region rasterizer, which merges rects and spans together
// to obtain an optimal region.
class Region::rasterizer : public region_operator<Rect>::region_rasterizer 
{
    Rect& bounds;
    Vector<Rect>& storage;
    Rect* head;
    Rect* tail;
    Vector<Rect> span;
    Rect* cur;
public:
    rasterizer(Region& reg) 
        : bounds(reg.mBounds), storage(reg.mStorage), head(), tail(), cur() {
        bounds.top = bounds.bottom = 0;
        bounds.left   = INT_MAX;
        bounds.right  = INT_MIN;
        storage.clear();
    }

    ~rasterizer() {
        if (span.size()) {
            flushSpan();
        }
        if (storage.size()) {
            bounds.top = storage.itemAt(0).top;
            bounds.bottom = storage.top().bottom;
            if (storage.size() == 1) {
                storage.clear();
            }
        } else {
            bounds.left  = 0;
            bounds.right = 0;
        }
    }
    
    virtual void operator()(const Rect& rect) {
        //ALOGD(">>> %3d, %3d, %3d, %3d",
        //        rect.left, rect.top, rect.right, rect.bottom);
        if (span.size()) {
            if (cur->top != rect.top) {
                flushSpan();
            } else if (cur->right == rect.left) {
                cur->right = rect.right;
                return;
            }
        }
        span.add(rect);
        cur = span.editArray() + (span.size() - 1);
    }
private:
    template<typename T> 
    static inline T min(T rhs, T lhs) { return rhs < lhs ? rhs : lhs; }
    template<typename T> 
    static inline T max(T rhs, T lhs) { return rhs > lhs ? rhs : lhs; }
    void flushSpan() {
        bool merge = false;
        if (tail-head == ssize_t(span.size())) {
            Rect const* p = span.editArray();
            Rect const* q = head;
            if (p->top == q->bottom) {
                merge = true;
                while (q != tail) {
                    if ((p->left != q->left) || (p->right != q->right)) {
                        merge = false;
                        break;
                    }
                    p++, q++;
                }
            }
        }
        if (merge) {
            const int bottom = span[0].bottom;
            Rect* r = head;
            while (r != tail) {
                r->bottom = bottom;
                r++;
            }
        } else {
            bounds.left = min(span.itemAt(0).left, bounds.left);
            bounds.right = max(span.top().right, bounds.right);
            storage.appendVector(span);
            tail = storage.editArray() + storage.size();
            head = tail - span.size();
        }
        span.clear();
    }
};

bool Region::validate(const Region& reg, const char* name)
{
    bool result = true;
    const_iterator cur = reg.begin();
    const_iterator const tail = reg.end();
    const_iterator prev = cur++;
    Rect b(*prev);
    while (cur != tail) {
        b.left   = b.left   < cur->left   ? b.left   : cur->left;
        b.top    = b.top    < cur->top    ? b.top    : cur->top;
        b.right  = b.right  > cur->right  ? b.right  : cur->right;
        b.bottom = b.bottom > cur->bottom ? b.bottom : cur->bottom;
        if (cur->top == prev->top) {
            if (cur->bottom != prev->bottom) {
                ALOGE("%s: invalid span %p", name, cur);
                result = false;
            } else if (cur->left < prev->right) {
                ALOGE("%s: spans overlap horizontally prev=%p, cur=%p",
                        name, prev, cur);
                result = false;
            }
        } else if (cur->top < prev->bottom) {
            ALOGE("%s: spans overlap vertically prev=%p, cur=%p",
                    name, prev, cur);
            result = false;
        }
        prev = cur;
        cur++;
    }
    if (b != reg.getBounds()) {
        result = false;
        ALOGE("%s: invalid bounds [%d,%d,%d,%d] vs. [%d,%d,%d,%d]", name,
                b.left, b.top, b.right, b.bottom,
                reg.getBounds().left, reg.getBounds().top, 
                reg.getBounds().right, reg.getBounds().bottom);
    }
    if (result == false) {
        reg.dump(name);
    }
    return result;
}

void Region::boolean_operation(int op, Region& dst,
        const Region& lhs,
        const Region& rhs, int dx, int dy)
{
#if VALIDATE_REGIONS
    validate(lhs, "boolean_operation (before): lhs");
    validate(rhs, "boolean_operation (before): rhs");
    validate(dst, "boolean_operation (before): dst");
#endif

    size_t lhs_count;
    Rect const * const lhs_rects = lhs.getArray(&lhs_count);

    size_t rhs_count;
    Rect const * const rhs_rects = rhs.getArray(&rhs_count);

    region_operator<Rect>::region lhs_region(lhs_rects, lhs_count);
    region_operator<Rect>::region rhs_region(rhs_rects, rhs_count, dx, dy);
    region_operator<Rect> operation(op, lhs_region, rhs_region);
    { // scope for rasterizer (dtor has side effects)
        rasterizer r(dst);
        operation(r);
    }

#if VALIDATE_REGIONS
    validate(lhs, "boolean_operation: lhs");
    validate(rhs, "boolean_operation: rhs");
    validate(dst, "boolean_operation: dst");
#endif

#if VALIDATE_WITH_CORECG
    SkRegion sk_lhs;
    SkRegion sk_rhs;
    SkRegion sk_dst;
    
    for (size_t i=0 ; i<lhs_count ; i++)
        sk_lhs.op(
                lhs_rects[i].left   + dx,
                lhs_rects[i].top    + dy,
                lhs_rects[i].right  + dx,
                lhs_rects[i].bottom + dy,
                SkRegion::kUnion_Op);
    
    for (size_t i=0 ; i<rhs_count ; i++)
        sk_rhs.op(
                rhs_rects[i].left   + dx,
                rhs_rects[i].top    + dy,
                rhs_rects[i].right  + dx,
                rhs_rects[i].bottom + dy,
                SkRegion::kUnion_Op);
 
    const char* name = "---";
    SkRegion::Op sk_op;
    switch (op) {
        case op_or: sk_op = SkRegion::kUnion_Op; name="OR"; break;
        case op_and: sk_op = SkRegion::kIntersect_Op; name="AND"; break;
        case op_nand: sk_op = SkRegion::kDifference_Op; name="NAND"; break;
    }
    sk_dst.op(sk_lhs, sk_rhs, sk_op);

    if (sk_dst.isEmpty() && dst.isEmpty())
        return;
    
    bool same = true;
    Region::const_iterator head = dst.begin();
    Region::const_iterator const tail = dst.end();
    SkRegion::Iterator it(sk_dst);
    while (!it.done()) {
        if (head != tail) {
            if (
                    head->left != it.rect().fLeft ||     
                    head->top != it.rect().fTop ||     
                    head->right != it.rect().fRight ||     
                    head->bottom != it.rect().fBottom
            ) {
                same = false;
                break;
            }
        } else {
            same = false;
            break;
        }
        head++;
        it.next();
    }
    
    if (head != tail) {
        same = false;
    }
    
    if(!same) {
        ALOGD("---\nregion boolean %s failed", name);
        lhs.dump("lhs");
        rhs.dump("rhs");
        dst.dump("dst");
        ALOGD("should be");
        SkRegion::Iterator it(sk_dst);
        while (!it.done()) {
            ALOGD("    [%3d, %3d, %3d, %3d]",
                it.rect().fLeft,
                it.rect().fTop,
                it.rect().fRight,
                it.rect().fBottom);
            it.next();
        }
    }
#endif
}

void Region::boolean_operation(int op, Region& dst,
        const Region& lhs,
        const Rect& rhs, int dx, int dy)
{
    if (!rhs.isValid()) {
        ALOGE("Region::boolean_operation(op=%d) invalid Rect={%d,%d,%d,%d}",
                op, rhs.left, rhs.top, rhs.right, rhs.bottom);
        return;
    }

#if VALIDATE_WITH_CORECG || VALIDATE_REGIONS
    boolean_operation(op, dst, lhs, Region(rhs), dx, dy);
#else
    size_t lhs_count;
    Rect const * const lhs_rects = lhs.getArray(&lhs_count);

    region_operator<Rect>::region lhs_region(lhs_rects, lhs_count);
    region_operator<Rect>::region rhs_region(&rhs, 1, dx, dy);
    region_operator<Rect> operation(op, lhs_region, rhs_region);
    { // scope for rasterizer (dtor has side effects)
        rasterizer r(dst);
        operation(r);
    }

#endif
}

void Region::boolean_operation(int op, Region& dst,
        const Region& lhs, const Region& rhs)
{
    boolean_operation(op, dst, lhs, rhs, 0, 0);
}

void Region::boolean_operation(int op, Region& dst,
        const Region& lhs, const Rect& rhs)
{
    boolean_operation(op, dst, lhs, rhs, 0, 0);
}

void Region::translate(Region& reg, int dx, int dy)
{
    if (!reg.isEmpty()) {
#if VALIDATE_REGIONS
        validate(reg, "translate (before)");
#endif
        reg.mBounds.translate(dx, dy);
        size_t count = reg.mStorage.size();
        Rect* rects = reg.mStorage.editArray();
        while (count) {
            rects->translate(dx, dy);
            rects++;
            count--;
        }
#if VALIDATE_REGIONS
        validate(reg, "translate (after)");
#endif
    }
}

void Region::translate(Region& dst, const Region& reg, int dx, int dy)
{
    dst = reg;
    translate(dst, dx, dy);
}

// ----------------------------------------------------------------------------

ssize_t Region::write(void* buffer, size_t size) const
{
#if VALIDATE_REGIONS
    validate(*this, "write(buffer)");
#endif
    const size_t count = mStorage.size();
    const size_t sizeNeeded = sizeof(int32_t) + (1+count)*sizeof(Rect);
    if (buffer != NULL) {
        if (sizeNeeded > size) return NO_MEMORY;
        int32_t* const p = static_cast<int32_t*>(buffer);
        *p = count;
        memcpy(p+1, &mBounds, sizeof(Rect));
        if (count) {
            memcpy(p+5, mStorage.array(), count*sizeof(Rect));
        }
    }
    return ssize_t(sizeNeeded);
}

ssize_t Region::read(const void* buffer)
{
    int32_t const* const p = static_cast<int32_t const*>(buffer); 
    const size_t count = *p;
    memcpy(&mBounds, p+1, sizeof(Rect));
    mStorage.clear();
    if (count) {
        mStorage.insertAt(0, count);
        memcpy(mStorage.editArray(), p+5, count*sizeof(Rect));
    }
#if VALIDATE_REGIONS
    validate(*this, "read(buffer)");
#endif
    return ssize_t(sizeof(int32_t) + (1+count)*sizeof(Rect));
}

ssize_t Region::writeEmpty(void* buffer, size_t size)
{
    const size_t sizeNeeded = sizeof(int32_t) + sizeof(Rect);
    if (sizeNeeded > size) return NO_MEMORY;
    int32_t* const p = static_cast<int32_t*>(buffer); 
    memset(p, 0, sizeNeeded);
    return ssize_t(sizeNeeded);
}

bool Region::isEmpty(void* buffer)
{
    int32_t const* const p = static_cast<int32_t const*>(buffer); 
    Rect const* const b = reinterpret_cast<Rect const *>(p+1);
    return b->isEmpty();
}

// ----------------------------------------------------------------------------

Region::const_iterator Region::begin() const {
    return isRect() ? &mBounds : mStorage.array();
}

Region::const_iterator Region::end() const {
    return isRect() ? ((&mBounds) + 1) : (mStorage.array() + mStorage.size());
}

Rect const* Region::getArray(size_t* count) const {
    const_iterator const b(begin());
    const_iterator const e(end());
    if (count) *count = e-b;
    return b;
}

size_t Region::getRects(Vector<Rect>& rectList) const
{
    rectList = mStorage;
    if (rectList.isEmpty()) {
        rectList.clear();
        rectList.add(mBounds);
    }
    return rectList.size();
}

// ----------------------------------------------------------------------------

void Region::dump(String8& out, const char* what, uint32_t flags) const
{
    (void)flags;
    const_iterator head = begin();
    const_iterator const tail = end();

    size_t SIZE = 256;
    char buffer[SIZE];

    snprintf(buffer, SIZE, "  Region %s (this=%p, count=%d)\n",
            what, this, tail-head);
    out.append(buffer);
    while (head != tail) {
        snprintf(buffer, SIZE, "    [%3d, %3d, %3d, %3d]\n",
                head->left, head->top, head->right, head->bottom);
        out.append(buffer);
        head++;
    }
}

void Region::dump(const char* what, uint32_t flags) const
{
    (void)flags;
    const_iterator head = begin();
    const_iterator const tail = end();
    ALOGD("  Region %s (this=%p, count=%d)\n", what, this, tail-head);
    while (head != tail) {
        ALOGD("    [%3d, %3d, %3d, %3d]\n",
                head->left, head->top, head->right, head->bottom);
        head++;
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
