// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// memblock.cc
//
//	Allocated memory block.
//

#include "fstream.h"
#include "mistream.h"
#include "memblock.h"
#include "ualgo.h"
#include "uassert.h"
#include "umemory.h"

#include <errno.h>

namespace ustl {

/// Allocates 0 bytes for the internal block.
memblock::memblock (void)
: memlink (),
  m_Capacity (0)
{
}

/// Allocates \p n bytes for the internal block.
memblock::memblock (size_type n)
: memlink (),
  m_Capacity (0)
{
    resize (n);
}

/// links to \p p, \p n. Data can not be modified and will not be freed.
memblock::memblock (const void* p, size_type n)
: memlink (),
  m_Capacity (0)
{
    assign (p, n);
}

/// Links to what \p b is linked to.
memblock::memblock (const cmemlink& b)
: memlink (),
  m_Capacity (0)
{
    assign (b);
}

/// Links to what \p b is linked to.
memblock::memblock (const memlink& b)
: memlink (),
  m_Capacity (0)
{
    assign (b);
}

/// Links to what \p b is linked to.
memblock::memblock (const memblock& b)
: memlink (),
  m_Capacity (0)
{
    assign (b);
}

/// Frees internal data, if appropriate
/// Only if the block was allocated using resize, or linked to using Manage,
/// will it be freed. Also, Derived classes should call DestructBlock from
/// their destructor, because upstream virtual functions are unavailable at
/// this point and will not be called automatically.
///
memblock::~memblock (void)
{
    if (!is_linked())
	deallocate();
}

/// resizes the block to \p newSize bytes, reallocating if necessary.
void memblock::resize (size_type newSize, bool bExact)
{
    if (m_Capacity < newSize + minimumFreeCapacity())
	reserve (newSize, bExact);
    memlink::resize (newSize);
}

/// Frees internal data.
void memblock::deallocate (void) throw()
{
    if (m_Capacity) {
	assert (cdata() && "Internal error: space allocated, but the pointer is NULL");
	assert (data() && "Internal error: read-only block is marked as allocated space");
	free (data());
    }
    unlink();
}

/// Assumes control of the memory block \p p of size \p n.
/// The block assigned using this function will be freed in the destructor.
void memblock::manage (void* p, size_type n)
{
    assert (p || !n);
    assert (!m_Capacity && "Already managing something. deallocate or unlink first.");
    link (p, n);
    m_Capacity = n;
}

/// "Instantiate" a linked block by allocating and copying the linked data.
void memblock::copy_link (void)
{
    const cmemlink l (*this);
    if (is_linked())
	unlink();
    assign (l);
}
 
/// Copies data from \p p, \p n.
void memblock::assign (const void* p, size_type n)
{
    assert ((p != (const void*) cdata() || size() == n) && "Self-assignment can not resize");
    resize (n);
    copy (p, n);
}

/// \brief Reallocates internal block to hold at least \p newSize bytes.
///
/// Additional memory may be allocated, but for efficiency it is a very
/// good idea to call reserve before doing byte-by-byte edit operations.
/// The block size as returned by size() is not altered. reserve will not
/// reduce allocated memory. If you think you are wasting space, call
/// deallocate and start over. To avoid wasting space, use the block for
/// only one purpose, and try to get that purpose to use similar amounts
/// of memory on each iteration.
///
void memblock::reserve (size_type newSize, bool bExact)
{
    if ((newSize += minimumFreeCapacity()) <= m_Capacity)
	return;
    void* oldBlock (is_linked() ? NULL : data());
    if (!bExact)
	newSize = Align (newSize, c_PageSize);
    pointer newBlock = (pointer) realloc (oldBlock, newSize);
    if (!newBlock)
#if PLATFORM_ANDROID
        printf("bad_alloc\n");
#else
	throw bad_alloc (newSize);
#endif
    if (!oldBlock && cdata())
	copy_n (cdata(), min (size() + 1, newSize), newBlock);
    link (newBlock, size());
    m_Capacity = newSize;
}

/// Swaps the contents with \p l
void memblock::swap (memblock& l)
{
    memlink::swap (l);
    ::ustl::swap (m_Capacity, l.m_Capacity);
}

/// Shifts the data in the linked block from \p start to \p start + \p n.
memblock::iterator memblock::insert (iterator start, size_type n)
{
    const uoff_t ip = start - begin();
    assert (ip <= size());
    resize (size() + n, false);
    memlink::insert (iat(ip), n);
    return (iat (ip));
}

/// Shifts the data in the linked block from \p start + \p n to \p start.
memblock::iterator memblock::erase (iterator start, size_type n)
{
    const uoff_t ep = start - begin();
    assert (ep + n <= size());
    memlink::erase (start, n);
    memlink::resize (size() - n);
    return (iat (ep));
}

/// Unlinks object.
void memblock::unlink (void)
{
    memlink::unlink();
    m_Capacity = 0;
}

/// Reads the object from stream \p s
void memblock::read (istream& is)
{
    written_size_type n;
    is >> n;
    is.verify_remaining ("read", "ustl::memblock", n);
    resize (n);
    is.read (data(), writable_size());
    is.align (alignof (n));
}

/// Reads the entire file \p "filename".
void memblock::read_file (const char* filename)
{
    fstream f;
    f.exceptions (fstream::allbadbits);
    f.open (filename, fstream::in);
    const off_t fsize (f.size());
    reserve (fsize);
    f.read (data(), fsize);
    f.close();
    resize (fsize);
}

} // namespace ustl

