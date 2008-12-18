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

#ifndef ANDROID_UI_REGION_H
#define ANDROID_UI_REGION_H

#include <stdint.h>
#include <sys/types.h>

#include <utils/Vector.h>
#include <utils/Parcel.h>

#include <ui/Rect.h>

#include <hardware/copybit.h>

#include <corecg/SkRegion.h>

namespace android {
// ---------------------------------------------------------------------------

class String8;

// ---------------------------------------------------------------------------
class Region
{
public:
                        Region();
                        Region(const Region& rhs);
    explicit            Region(const SkRegion& rhs);
    explicit            Region(const Rect& rhs);
    explicit            Region(const Parcel& parcel);
    explicit            Region(const void* buffer);
                        ~Region();
                        
        Region& operator = (const Region& rhs);

    inline  bool        isEmpty() const     { return mRegion.isEmpty(); }
    inline  bool        isRect() const      { return mRegion.isRect(); }

            Rect        bounds() const;

            const SkRegion& toSkRegion() const;

            void        clear();
            void        set(const Rect& r);
        
            Region&     orSelf(const Rect& rhs);
            Region&     andSelf(const Rect& rhs);

            // boolean operators, applied on this
            Region&     orSelf(const Region& rhs);
            Region&     andSelf(const Region& rhs);
            Region&     subtractSelf(const Region& rhs);

            // these translate rhs first
            Region&     translateSelf(int dx, int dy);
            Region&     orSelf(const Region& rhs, int dx, int dy);
            Region&     andSelf(const Region& rhs, int dx, int dy);
            Region&     subtractSelf(const Region& rhs, int dx, int dy);

            // boolean operators
            Region      merge(const Region& rhs) const;
            Region      intersect(const Region& rhs) const;
            Region      subtract(const Region& rhs) const;

            // these translate rhs first
            Region      translate(int dx, int dy) const;
            Region      merge(const Region& rhs, int dx, int dy) const;
            Region      intersect(const Region& rhs, int dx, int dy) const;
            Region      subtract(const Region& rhs, int dx, int dy) const;

    // convenience operators overloads
    inline  Region      operator | (const Region& rhs) const;
    inline  Region      operator & (const Region& rhs) const;
    inline  Region      operator - (const Region& rhs) const;
    inline  Region      operator + (const Point& pt) const;

    inline  Region&     operator |= (const Region& rhs);
    inline  Region&     operator &= (const Region& rhs);
    inline  Region&     operator -= (const Region& rhs);
    inline  Region&     operator += (const Point& pt);

    class iterator {
        SkRegion::Iterator  mIt;
    public:
        iterator(const Region& r);
        inline operator bool () const { return !done(); }
        int iterate(Rect* rect);
    private:
        inline bool done() const {
            return const_cast<SkRegion::Iterator&>(mIt).done();
        }
    };

            size_t      rects(Vector<Rect>& rectList) const;

            // flatten/unflatten a region to/from a Parcel
            status_t    write(Parcel& parcel) const;
            status_t    read(const Parcel& parcel);

            // flatten/unflatten a region to/from a raw buffer
            ssize_t     write(void* buffer, size_t size) const;
    static  ssize_t     writeEmpty(void* buffer, size_t size);

            ssize_t     read(const void* buffer);
    static  bool        isEmpty(void* buffer);

    void        dump(String8& out, const char* what, uint32_t flags=0) const;
    void        dump(const char* what, uint32_t flags=0) const;

private:
    SkRegion    mRegion;
};


Region Region::operator | (const Region& rhs) const {
    return merge(rhs);
}
Region Region::operator & (const Region& rhs) const {
    return intersect(rhs);
}
Region Region::operator - (const Region& rhs) const {
    return subtract(rhs);
}
Region Region::operator + (const Point& pt) const {
    return translate(pt.x, pt.y);
}


Region& Region::operator |= (const Region& rhs) {
    return orSelf(rhs);
}
Region& Region::operator &= (const Region& rhs) {
    return andSelf(rhs);
}
Region& Region::operator -= (const Region& rhs) {
    return subtractSelf(rhs);
}
Region& Region::operator += (const Point& pt) {
    return translateSelf(pt.x, pt.y);
}

// ---------------------------------------------------------------------------

struct region_iterator : public copybit_region_t {
    region_iterator(const Region& region) : i(region) {
        this->next = iterate;
    }
private:
    static int iterate(copybit_region_t const * self, copybit_rect_t* rect) {
        return static_cast<const region_iterator*>(self)
        ->i.iterate(reinterpret_cast<Rect*>(rect));
    }
    mutable Region::iterator i;
};
// ---------------------------------------------------------------------------
}; // namespace android

#endif // ANDROID_UI_REGION_H

