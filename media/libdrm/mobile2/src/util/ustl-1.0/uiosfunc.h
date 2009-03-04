// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// uiosfunc.h
//

#ifndef UIOSFUNC_H_730C16E316F7650E3A02E1C6611B789A
#define UIOSFUNC_H_730C16E316F7650E3A02E1C6611B789A

#include "sostream.h"

namespace ustl {

class ios : public ios_base {
public:
    /// \class align uiosfunc.h ustl.h
    /// \ingroup StreamFunctors
    /// \brief Stream functor to allow inline align() calls.
    ///
    /// Example: os << ios::align(sizeof(uint16_t));
    ///
    class align {
    public:
	inline explicit		align (size_t grain = c_DefaultAlignment) : m_Grain(grain) {}
	inline istream&		apply (istream& is) const { is.align (m_Grain); return (is); }
	inline ostream&		apply (ostream& os) const { os.align (m_Grain); return (os); }
	inline size_t		stream_size (void) const  { return (m_Grain - 1); }
    private:
	const size_t		m_Grain;
    };

    /// \class talign uiosfunc.h ustl.h
    /// \ingroup StreamFunctors
    /// \brief Stream functor to allow type-based alignment.
    template <typename T>
    class talign : public align {
    public:
	inline explicit		talign (void) : align (alignof (T())) {}
    };

    /// \class skip uiosfunc.h ustl.h
    /// \ingroup StreamFunctors
    /// \brief Stream functor to allow inline skip() calls.
    ///
    /// Example: os << ios::skip(sizeof(uint16_t));
    ///
    class skip {
    public:
	inline explicit 	skip (size_t nBytes) : m_nBytes(nBytes) {}
	inline istream&		apply (istream& is) const { is.skip (m_nBytes); return (is); }
	inline ostream&		apply (ostream& os) const { os.skip (m_nBytes); return (os); }
	inline size_t		stream_size (void) const  { return (m_nBytes); }
    private:
	const size_t		m_nBytes;
    };

    /// \class width uiosfunc.h ustl.h
    /// \ingroup StreamFunctors
    /// \brief Stream functor to allow inline set_width() calls.
    ///
    /// Example: os << ios::width(15);
    ///
    class width {
    public:
	inline explicit		width (size_t nBytes) : m_nBytes(nBytes) {}
	inline ostringstream&	apply (ostringstream& os) const { os.set_width (m_nBytes); return (os); }
    private:
	const size_t		m_nBytes;
    };

    /// \class base uiosfunc.h ustl.h
    /// \ingroup StreamFunctors
    /// \brief Stream functor to allow inline set_base() calls.
    ///
    /// Example: os << ios::base(15);
    ///
    class base {
    public:
	inline explicit		base (size_t n) : m_Base(n) {}
	inline ostringstream&	apply (ostringstream& os) const { os.set_base (m_Base); return (os); }
    private:
	const size_t		m_Base;
    };
};

inline istream& operator>> (istream& is, const ios::skip& op)	{ return (op.apply (is)); }
inline ostream& operator<< (ostream& os, const ios::skip& op)	{ return (op.apply (os)); }
inline size_t stream_size_of (const ios::skip& op)		{ return (op.stream_size()); }
inline istream& operator>> (istream& is, const ios::align& op)	{ return (op.apply (is)); }
inline ostream& operator<< (ostream& os, const ios::align& op)	{ return (op.apply (os)); }
inline size_t stream_size_of (const ios::align& op)		{ return (op.stream_size()); }
inline ostringstream& operator<< (ostringstream& os, const ios::width& op)	{ return (op.apply (os)); }
inline ostringstream& operator<< (ostringstream& os, const ios::base& op)	{ return (op.apply (os)); }

} // namespace ustl

CAST_STREAMABLE(ustl::ios::fmtflags, uint32_t)
CAST_STREAMABLE(ustl::ios::seekdir, uint32_t)

#endif

