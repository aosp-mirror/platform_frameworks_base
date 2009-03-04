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

#include <stdio.h>
#include <utils/Atomic.h>
#include <utils/Debug.h>
#include <utils/String8.h>
#include <ui/Region.h>

namespace android {

// ----------------------------------------------------------------------------

Region::Region()
{
}

Region::Region(const Region& rhs)
    : mRegion(rhs.mRegion)
{
}

Region::Region(const SkRegion& rhs)
    : mRegion(rhs)
{
}

Region::~Region()
{
}

Region::Region(const Rect& rhs)
{
    set(rhs);
}

Region::Region(const Parcel& parcel)
{
    read(parcel);
}

Region::Region(const void* buffer)
{
    read(buffer);
}

Region& Region::operator = (const Region& rhs)
{
    mRegion = rhs.mRegion;
    return *this;
}

const SkRegion& Region::toSkRegion() const
{
    return mRegion;
}

Rect Region::bounds() const
{
    const SkIRect& b(mRegion.getBounds());
    return Rect(b.fLeft, b.fTop, b.fRight, b.fBottom);
}

void Region::clear()
{
    mRegion.setEmpty();
}

void Region::set(const Rect& r)
{
    SkIRect ir;
    ir.set(r.left, r.top, r.right, r.bottom);
    mRegion.setRect(ir);
}

// ----------------------------------------------------------------------------

Region& Region::orSelf(const Rect& r)
{
    SkIRect ir;
    ir.set(r.left, r.top, r.right, r.bottom);
    mRegion.op(ir, SkRegion::kUnion_Op);
    return *this;
}

Region& Region::andSelf(const Rect& r)
{
    SkIRect ir;
    ir.set(r.left, r.top, r.right, r.bottom);
    mRegion.op(ir, SkRegion::kIntersect_Op);
    return *this;
}

// ----------------------------------------------------------------------------

Region& Region::orSelf(const Region& rhs) {
    mRegion.op(rhs.mRegion, SkRegion::kUnion_Op);
    return *this;
}

Region& Region::andSelf(const Region& rhs) {
    mRegion.op(rhs.mRegion, SkRegion::kIntersect_Op);
    return *this;
}

Region& Region::subtractSelf(const Region& rhs) {
    mRegion.op(rhs.mRegion, SkRegion::kDifference_Op);
    return *this;
}

Region& Region::translateSelf(int x, int y) {
    if (x|y) mRegion.translate(x, y);
    return *this;
}

Region Region::merge(const Region& rhs) const {
    Region result;
    result.mRegion.op(mRegion, rhs.mRegion, SkRegion::kUnion_Op);
    return result;
}

Region Region::intersect(const Region& rhs) const {
    Region result;
    result.mRegion.op(mRegion, rhs.mRegion, SkRegion::kIntersect_Op);
    return result;
}

Region Region::subtract(const Region& rhs) const {
    Region result;
    result.mRegion.op(mRegion, rhs.mRegion, SkRegion::kDifference_Op);
    return result;
}

Region Region::translate(int x, int y) const {
    Region result;
    mRegion.translate(x, y, &result.mRegion);
    return result;
}

// ----------------------------------------------------------------------------

Region& Region::orSelf(const Region& rhs, int dx, int dy) {
    SkRegion r(rhs.mRegion);
    r.translate(dx, dy);
    mRegion.op(r, SkRegion::kUnion_Op);
    return *this;
}

Region& Region::andSelf(const Region& rhs, int dx, int dy) {
    SkRegion r(rhs.mRegion);
    r.translate(dx, dy);
    mRegion.op(r, SkRegion::kIntersect_Op);
    return *this;
}

Region& Region::subtractSelf(const Region& rhs, int dx, int dy) {
    SkRegion r(rhs.mRegion);
    r.translate(dx, dy);
    mRegion.op(r, SkRegion::kDifference_Op);
    return *this;
}

Region Region::merge(const Region& rhs, int dx, int dy) const {
    Region result;
    SkRegion r(rhs.mRegion);
    r.translate(dx, dy);
    result.mRegion.op(mRegion, r, SkRegion::kUnion_Op);
    return result;
}

Region Region::intersect(const Region& rhs, int dx, int dy) const {
    Region result;
    SkRegion r(rhs.mRegion);
    r.translate(dx, dy);
    result.mRegion.op(mRegion, r, SkRegion::kIntersect_Op);
    return result;
}

Region Region::subtract(const Region& rhs, int dx, int dy) const {
    Region result;
    SkRegion r(rhs.mRegion);
    r.translate(dx, dy);
    result.mRegion.op(mRegion, r, SkRegion::kDifference_Op);
    return result;
}

// ----------------------------------------------------------------------------

Region::iterator::iterator(const Region& r)
    : mIt(r.mRegion)
{
}

int Region::iterator::iterate(Rect* rect)
{
    if (mIt.done())
        return 0;
    const SkIRect& r(mIt.rect());
    rect->left  = r.fLeft;
    rect->top   = r.fTop;
    rect->right = r.fRight;
    rect->bottom= r.fBottom;
    mIt.next();
    return 1;
}

// ----------------------------------------------------------------------------

// we write a 4byte size ahead of the actual region, so we know how much we'll need for reading

status_t Region::write(Parcel& parcel) const
{
    int32_t size = mRegion.flatten(NULL);
    parcel.writeInt32(size);
    mRegion.flatten(parcel.writeInplace(size));
    return NO_ERROR;
}

status_t Region::read(const Parcel& parcel)
{
    size_t size = parcel.readInt32();
    mRegion.unflatten(parcel.readInplace(size));
    return NO_ERROR;
}

ssize_t Region::write(void* buffer, size_t size) const
{
    size_t sizeNeeded = mRegion.flatten(NULL);
    if (sizeNeeded > size) return NO_MEMORY;
    return mRegion.flatten(buffer);
}

ssize_t Region::read(const void* buffer)
{
    return mRegion.unflatten(buffer);
}

ssize_t Region::writeEmpty(void* buffer, size_t size)
{
    if (size < 4) return NO_MEMORY;
    // this needs to stay in sync with SkRegion
    *static_cast<int32_t*>(buffer) = -1;
    return 4;
}

bool Region::isEmpty(void* buffer)
{
    // this needs to stay in sync with SkRegion
    return *static_cast<int32_t*>(buffer) == -1;
}

size_t Region::rects(Vector<Rect>& rectList) const
{
    rectList.clear();
    if (!isEmpty()) {
        SkRegion::Iterator iterator(mRegion);
        while( !iterator.done() ) {
            const SkIRect& ir(iterator.rect());
            rectList.push(Rect(ir.fLeft, ir.fTop, ir.fRight, ir.fBottom));
            iterator.next();
        }
    }
    return rectList.size();
}

void Region::dump(String8& out, const char* what, uint32_t flags) const
{
    (void)flags;
    Vector<Rect> r;
    rects(r);
    
    size_t SIZE = 256;
    char buffer[SIZE];
    
    snprintf(buffer, SIZE, "  Region %s (this=%p, count=%d)\n", what, this, r.size());
    out.append(buffer);
    for (size_t i=0 ; i<r.size() ; i++) {
        snprintf(buffer, SIZE, "    [%3d, %3d, %3d, %3d]\n",
            r[i].left, r[i].top,r[i].right,r[i].bottom);
        out.append(buffer);
    }
}

void Region::dump(const char* what, uint32_t flags) const
{
    (void)flags;
    Vector<Rect> r;
    rects(r);
    LOGD("  Region %s (this=%p, count=%d)\n", what, this, r.size());
    for (size_t i=0 ; i<r.size() ; i++) {
        LOGD("    [%3d, %3d, %3d, %3d]\n",
            r[i].left, r[i].top,r[i].right,r[i].bottom);
    }
}

// ----------------------------------------------------------------------------

}; // namespace android
