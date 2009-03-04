// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// ustring.h
//

#ifndef USTRING_H_1249CB7A098A9010763AAC6D37B133CF
#define USTRING_H_1249CB7A098A9010763AAC6D37B133CF

#include "memblock.h"
#include "utf8.h"
#include <stdarg.h>	// for va_list, va_start, and va_end (in string::format)

namespace ustl {

/// \class string ustring.h ustl.h
/// \ingroup Sequences
///
/// \brief STL basic_string&lt;char&gt; equivalent.
///
/// An STL container for text string manipulation.
/// Differences from C++ standard:
///	- string is a class, not a template. Wide characters are assumed to be
///		encoded with utf8 at all times except when rendering or editing,
///		where you would use a utf8 iterator.
/// 	- format member function - you can, of course use an \ref ostringstream,
///		which also have format functions, but most of the time this way
///		is more convenient. Because uSTL does not implement locales,
///		format is the only way to create localized strings.
/// 	- const char* cast operator. It is much clearer to use this than having
/// 		to type .c_str() every time.
/// 	- length returns the number of _characters_, not bytes.
///		This function is O(N), so use wisely.
///
class string : public memblock {
public:
    typedef char		value_type;
    typedef value_type*		pointer;
    typedef const value_type*	const_pointer;
    typedef wchar_t		wvalue_type;
    typedef wvalue_type*	wpointer;
    typedef const wvalue_type*	const_wpointer;
    typedef pointer		iterator;
    typedef const_pointer	const_iterator;
    typedef value_type&		reference;
    typedef value_type		const_reference;
    typedef ::ustl::reverse_iterator<iterator>		reverse_iterator;
    typedef ::ustl::reverse_iterator<const_iterator>	const_reverse_iterator;
    typedef utf8in_iterator<const_iterator>		utf8_iterator;
public:
    static const uoff_t npos = static_cast<uoff_t>(-1);			///< Value that means the end of string.
    static const value_type c_Terminator = 0;				///< String terminator
    static const size_type size_Terminator = sizeof(c_Terminator);	///< Most systems terminate strings with '\\0'
    static const char empty_string [size_Terminator];			///< An empty string.
public:
				string (void);
				string (const string& s);
    inline			string (const string& s, uoff_t o, size_type n);
    inline explicit		string (const cmemlink& l);
				string (const_pointer s);
    inline			string (const_pointer s, size_type len);
    inline			string (const_pointer s1, const_pointer s2);
    explicit			string (size_type n, value_type c = c_Terminator);
    inline pointer		data (void)		{ return (string::pointer (memblock::data())); }
    inline const_pointer	c_str (void) const	{ return (string::const_pointer (memblock::cdata())); }
    inline size_type		max_size (void) const	{ size_type s (memblock::max_size()); return (s - !!s); }
    inline size_type		capacity (void) const	{ size_type c (memblock::capacity()); return (c - !!c); }
    void			resize (size_type n);
    inline void			clear (void)		{ resize (0); }
    inline const_iterator	begin (void) const	{ return (const_iterator (memblock::begin())); }
    inline iterator		begin (void)		{ return (iterator (memblock::begin())); }
    inline const_iterator	end (void) const	{ return (const_iterator (memblock::end())); }
    inline iterator		end (void)		{ return (iterator (memblock::end())); }
  inline const_reverse_iterator	rbegin (void) const	{ return (const_reverse_iterator (end())); }
    inline reverse_iterator	rbegin (void)		{ return (reverse_iterator (end())); }
  inline const_reverse_iterator	rend (void) const	{ return (const_reverse_iterator (begin())); }
    inline reverse_iterator	rend (void)		{ return (reverse_iterator (begin())); }
    inline utf8_iterator	utf8_begin (void) const	{ return (utf8_iterator (begin())); }
    inline utf8_iterator	utf8_end (void) const	{ return (utf8_iterator (end())); }
    inline const_reference	at (uoff_t pos) const	{ assert (pos <= size() && begin()); return (begin()[pos]); }
    inline reference		at (uoff_t pos)		{ assert (pos <= size() && begin()); return (begin()[pos]); }
    inline const_iterator	iat (uoff_t pos) const	{ return (begin() + min (pos, size())); }
    inline iterator		iat (uoff_t pos)	{ return (begin() + min (pos, size())); }
    inline size_type		length (void) const	{ return (distance (utf8_begin(), utf8_end())); }
    inline void			append (const_iterator i1, const_iterator i2)	{ append (i1, distance (i1, i2)); }
    void	   		append (const_pointer s, size_type len);
    void	   		append (const_pointer s);
    void			append (size_type n, const_reference c);
    inline void			append (size_type n, wvalue_type c)		{ insert (size(), c, n); }
    inline void			append (const_wpointer s1, const_wpointer s2)	{ insert (size(), s1, s2); }
    inline void			append (const_wpointer s)			{ const_wpointer se (s); for (;se&&*se;++se); append (s, se); }
    inline void			append (const string& s)			{ append (s.begin(), s.end()); }
    inline void			append (const string& s, uoff_t o, size_type n)	{ append (s.iat(o), s.iat(o+n)); }
    inline void			assign (const_iterator i1, const_iterator i2)	{ assign (i1, distance (i1, i2)); }
    void	    		assign (const_pointer s, size_type len);
    void	    		assign (const_pointer s);
    inline void			assign (const_wpointer s1, const_wpointer s2)	{ clear(); append (s1, s2); }
    inline void			assign (const_wpointer s1)			{ clear(); append (s1); }
    inline void			assign (const string& s)			{ assign (s.begin(), s.end()); }
    inline void			assign (const string& s, uoff_t o, size_type n)	{ assign (s.iat(o), s.iat(o+n)); }
    size_type			copyto (pointer p, size_type n, const_iterator start = NULL) const;
    inline int			compare (const string& s) const	{ return (compare (begin(), end(), s.begin(), s.end())); }
    inline int			compare (const_pointer s) const	{ return (compare (begin(), end(), s, s + strlen(s))); }
    static int			compare (const_iterator first1, const_iterator last1, const_iterator first2, const_iterator last2);
    inline			operator const value_type* (void) const;
    inline			operator value_type* (void);
    inline const string&	operator= (const string& s)	{ assign (s.begin(), s.end()); return (*this); }
    inline const string&	operator= (const_reference c)	{ assign (&c, 1); return (*this); }
    inline const string&	operator= (const_pointer s)	{ assign (s); return (*this); }
    inline const string&	operator= (const_wpointer s)	{ assign (s); return (*this); }
    inline const string&	operator+= (const string& s)	{ append (s.begin(), s.size()); return (*this); }
    inline const string&	operator+= (const_reference c)	{ append (1, c); return (*this); }
    inline const string&	operator+= (const_pointer s)	{ append (s); return (*this); }
    inline const string&	operator+= (wvalue_type c)	{ append (1, c); return (*this); }
    inline const string&	operator+= (const_wpointer s)	{ append (s); return (*this); }
    inline string		operator+ (const string& s) const;
    inline bool			operator== (const string& s) const	{ return (memblock::operator== (s)); }
    bool			operator== (const_pointer s) const;
    inline bool			operator== (const_reference c) const	{ return (size() == 1 && c == at(0)); }
    inline bool			operator!= (const string& s) const	{ return (!operator== (s)); }
    inline bool			operator!= (const_pointer s) const	{ return (!operator== (s)); }
    inline bool			operator!= (const_reference c) const	{ return (!operator== (c)); }
    inline bool			operator< (const string& s) const	{ return (0 > compare (s)); }
    inline bool			operator< (const_pointer s) const	{ return (0 > compare (s)); }
    inline bool			operator< (const_reference c) const	{ return (0 > compare (begin(), end(), &c, &c + 1)); }
    inline bool			operator> (const_pointer s) const	{ return (0 < compare (s)); }
    void			insert (const uoff_t ip, wvalue_type c, size_type n = 1);
    void			insert (const uoff_t ip, const_wpointer first, const_wpointer last, const size_type n = 1);
    iterator			insert (iterator start, const_reference c, size_type n = 1);
    iterator			insert (iterator start, const_pointer s, size_type n = 1);
    iterator			insert (iterator start, const_pointer first, const_iterator last, size_type n = 1);
    inline void			insert (uoff_t ip, const_pointer s, size_type nlen)		{ insert (iat(ip), s, s + nlen); }
    inline void			insert (uoff_t ip, size_type n, value_type c)			{ insert (iat(ip), c, n); }
    inline void			insert (uoff_t ip, const string& s, uoff_t sp, size_type slen)	{ insert (iat(ip), s.iat(sp), s.iat(sp + slen)); }
    iterator			erase (iterator start, size_type size = 1);
    void			erase (uoff_t start, size_type size = 1);
    inline iterator		erase (iterator first, const_iterator last)	{ return (erase (first, size_type(distance(first,last)))); }
				OVERLOAD_POINTER_AND_SIZE_T_V2(erase, iterator)
    inline void			push_back (const_reference c)	{ append (1, c); }
    inline void			push_back (wvalue_type c)	{ append (1, c); }
    inline void			pop_back (void)			{ resize (size() - 1); }
    void			replace (iterator first, iterator last, const_pointer s);
    void			replace (iterator first, iterator last, const_pointer i1, const_pointer i2, size_type n = 1);
    inline void			replace (iterator first, iterator last, const string& s)			{ replace (first, last, s.begin(), s.end()); }
    inline void			replace (iterator first, iterator last, const_pointer s, size_type slen)	{ replace (first, last, s, s + slen); }
    inline void			replace (iterator first, iterator last, size_type n, value_type c)		{ replace (first, last, &c, &c + 1, n); }
    inline void			replace (uoff_t rp, size_type n, const string& s)				{ replace (iat(rp), iat(rp + n), s); }
    inline void			replace (uoff_t rp, size_type n, const string& s, uoff_t sp, size_type slen)	{ replace (iat(rp), iat(rp + n), s.iat(sp), s.iat(sp + slen)); }
    inline void			replace (uoff_t rp, size_type n, const_pointer s, size_type slen)		{ replace (iat(rp), iat(rp + n), s, s + slen); }
    inline void			replace (uoff_t rp, size_type n, const_pointer s)				{ replace (iat(rp), iat(rp + n), string(s)); }
    inline void			replace (uoff_t rp, size_type n, size_type count, value_type c)			{ replace (iat(rp), iat(rp + n), count, c); }
    inline string		substr (uoff_t o, size_type n) const	{ return (string (*this, o, n)); }
    uoff_t			find (const_reference c, uoff_t pos = 0) const;
    uoff_t			find (const string& s, uoff_t pos = 0) const;
    uoff_t			rfind (const_reference c, uoff_t pos = npos) const;
    uoff_t			rfind (const string& s, uoff_t pos = npos) const;
    uoff_t			find_first_of (const string& s, uoff_t pos = 0) const;
    uoff_t			find_first_not_of (const string& s, uoff_t pos = 0) const;
    uoff_t			find_last_of (const string& s, uoff_t pos = npos) const;
    uoff_t			find_last_not_of (const string& s, uoff_t pos = npos) const;
    int				vformat (const char* fmt, va_list args);
    int				format (const char* fmt, ...) __attribute__((__format__(__printf__, 2, 3)));
    void			read (istream&);
    void			write (ostream& os) const;
    size_t			stream_size (void) const;
    static hashvalue_t		hash (const char* f1, const char* l1);
private:
    DLL_LOCAL iterator		utf8_iat (uoff_t i);
protected:
    inline virtual size_type	minimumFreeCapacity (void) const { return (size_Terminator); }
};

//----------------------------------------------------------------------

/// Assigns itself the value of string \p s
inline string::string (const cmemlink& s)
: memblock ()
{
    assign (const_iterator (s.begin()), s.size());
}

/// Assigns itself a [o,o+n) substring of \p s.
inline string::string (const string& s, uoff_t o, size_type n)
: memblock()
{
    assign (s, o, n);
}

/// Copies the value of \p s of length \p len into itself.
inline string::string (const_pointer s, size_type len)
: memblock ()
{
    assign (s, len);
}

/// Copies into itself the string data between \p s1 and \p s2
inline string::string (const_pointer s1, const_pointer s2)
: memblock ()
{
    assert (s1 <= s2 && "Negative ranges result in memory allocation errors.");
    assign (s1, s2);
}

/// Returns the pointer to the first character.
inline string::operator const string::value_type* (void) const
{
    assert ((!end() || *end() == c_Terminator) && "This string is linked to data that is not 0-terminated. This may cause serious security problems. Please assign the data instead of linking.");
    return (begin());
}

/// Returns the pointer to the first character.
inline string::operator string::value_type* (void)
{
    assert ((end() && *end() == c_Terminator) && "This string is linked to data that is not 0-terminated. This may cause serious security problems. Please assign the data instead of linking.");
    return (begin());
}

/// Concatenates itself with \p s
inline string string::operator+ (const string& s) const
{
    string result (*this);
    result += s;
    return (result);
}

//----------------------------------------------------------------------
// Operators needed to avoid comparing pointer to pointer

#define PTR_STRING_CMP(op, impl)	\
inline bool op (const char* s1, const string& s2) { return impl; }
PTR_STRING_CMP (operator==, (s2 == s1))
PTR_STRING_CMP (operator!=, (s2 != s1))
PTR_STRING_CMP (operator<,  (s2 >  s1))
PTR_STRING_CMP (operator<=, (s2 >= s1))
PTR_STRING_CMP (operator>,  (s2 <  s1))
PTR_STRING_CMP (operator>=, (s2 <= s1))
#undef PTR_STRING_CMP

//----------------------------------------------------------------------

template <typename T>
inline hashvalue_t hash_value (const T& v)
{ return (string::hash (v.begin(), v.end())); }

template <>
inline hashvalue_t hash_value (const string::const_pointer& v)
{ return (string::hash (v, v + strlen(v))); }

template <>
inline hashvalue_t hash_value (const string::pointer& v)
{ return (string::hash (v, v + strlen(v))); }

//----------------------------------------------------------------------

} // namespace ustl

// Specialization for stream alignment
ALIGNOF (ustl::string, alignof (string::value_type()))

#endif

