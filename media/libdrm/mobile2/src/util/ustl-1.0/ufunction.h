// This file is part of the ustl library, an STL implementation.
//
// Copyright (C) 2005 by Mike Sharov <msharov@users.sourceforge.net>
// This file is free software, distributed under the MIT License.
//
// \file ufunction.h
//
// \brief Implements STL standard functors.
//
// See STL specification and bvts for usage of these. The only
// extension is the mem_var functors for member variable access:
// \code
//	f = find_if (ctr, mem_var_equal_to(&MyClass::m_Var, matchVar));
//	f = find_if (ctr, mem_var_less(&MyClass::m_Var, matchVar));
// \endcode
// There are a couple of others but the syntax is much harder to grasp.
// See bvt10.cc for more examples.
//

#ifndef UFUNCTION_H_221ABA8551801799263C927234C085F3
#define UFUNCTION_H_221ABA8551801799263C927234C085F3

namespace ustl {

//----------------------------------------------------------------------
// Standard functors
//----------------------------------------------------------------------

/// \brief void-returning function abstract interface.
/// \ingroup FunctorObjects
template <typename Result>
struct void_function {
    typedef Result	result_type;
};

/// \brief \p Result f (\p Arg) function abstract interface.
/// \ingroup FunctorObjects
template <typename Arg, typename Result>
struct unary_function {
    typedef Arg		argument_type;
    typedef Result	result_type;
};

/// \brief \p Result f (\p Arg1, \p Arg2) function abstract interface.
/// \ingroup FunctorObjects
template <typename Arg1, typename Arg2, typename Result>
struct binary_function {
    typedef Arg1	first_argument_type;
    typedef Arg2	second_argument_type;
    typedef Result	result_type;
};

#ifndef DOXYGEN_SHOULD_SKIP_THIS

#define STD_BINARY_FUNCTOR(name, rv, func)	\
template <class T> struct name : public binary_function<T,T,rv> \
{ inline rv operator()(const T& a, const T& b) const { return func; } };
#define STD_UNARY_FUNCTOR(name, rv, func)	\
template <class T> struct name : public unary_function<T,rv> \
{ inline rv operator()(const T& a) const { return func; } };
#define STD_CONVERSION_FUNCTOR(name, func)	\
template <class S, class D> struct name : public unary_function<S,D> \
{ inline D operator()(const S& a) const { return func; } };

STD_BINARY_FUNCTOR (plus,		T,	(a + b))
STD_BINARY_FUNCTOR (minus,		T,	(a - b))
STD_BINARY_FUNCTOR (divides,		T,	(a / b))
STD_BINARY_FUNCTOR (modulus,		T,	(a % b))
STD_BINARY_FUNCTOR (multiplies,		T,	(a * b))
STD_BINARY_FUNCTOR (logical_and,	T,	(a && b))
STD_BINARY_FUNCTOR (logical_or,		T,	(a || b))
STD_UNARY_FUNCTOR  (logical_not,	T,	(!a))
STD_BINARY_FUNCTOR (bitwise_or,		T,	(a | b))
STD_BINARY_FUNCTOR (bitwise_and,	T,	(a & b))
STD_BINARY_FUNCTOR (bitwise_xor,	T,	(a ^ b))
STD_UNARY_FUNCTOR  (bitwise_not,	T,	(~a))
STD_UNARY_FUNCTOR  (negate,		T,	(-a))
STD_BINARY_FUNCTOR (equal_to,		bool,	(a == b))
STD_BINARY_FUNCTOR (not_equal_to,	bool,	(!(a == b)))
STD_BINARY_FUNCTOR (greater,		bool,	(b < a))
STD_BINARY_FUNCTOR (less,		bool,	(a < b))
STD_BINARY_FUNCTOR (greater_equal,	bool,	(!(a < b)))
STD_BINARY_FUNCTOR (less_equal,		bool,	(!(b < a)))
STD_BINARY_FUNCTOR (compare,		int,	(a < b ? -1 : (b < a)))
STD_UNARY_FUNCTOR  (identity,		T,	(a))

#endif // DOXYGEN_SHOULD_SKIP_THIS

/// \brief Selects and returns the first argument.
/// \ingroup FunctorObjects
template <class T1, class T2> struct project1st	: public binary_function<T1,T2,T1>    { inline const T1& operator()(const T1& a, const T2&) const { return (a); } };
/// \brief Selects and returns the second argument.
/// \ingroup FunctorObjects
template <class T1, class T2> struct project2nd	: public binary_function<T1,T2,T2>    { inline const T2& operator()(const T1&, const T2& a) const { return (a); } };

//----------------------------------------------------------------------
// Generic function to functor converters.
//----------------------------------------------------------------------

/// \brief Wrapper object for unary function pointers.
/// Use the \ref ptr_fun accessor to create this object.
/// \ingroup FunctorObjects
template <typename Arg, typename Result>
class pointer_to_unary_function : public unary_function<Arg,Result> {
public:
    typedef Arg		argument_type;
    typedef Result	result_type;
    typedef Result	(*pfunc_t)(Arg);
public:
    explicit inline	pointer_to_unary_function (pfunc_t pfn) : m_pfn (pfn) {}
    inline result_type	operator() (argument_type v) const { return (m_pfn(v)); }
private:
    pfunc_t		m_pfn;	///< Pointer to the wrapped function.
};

/// \brief Wrapper object for binary function pointers.
/// Use the \ref ptr_fun accessor to create this object.
/// \ingroup FunctorObjects
template <typename Arg1, typename Arg2, typename Result>
class pointer_to_binary_function : public binary_function<Arg1,Arg2,Result> {
public:
    typedef Arg1	first_argument_type;
    typedef Arg2	second_argument_type;
    typedef Result	result_type;
    typedef Result	(*pfunc_t)(Arg1, Arg2);
public:
    explicit inline	pointer_to_binary_function (pfunc_t pfn) : m_pfn (pfn) {}
    inline result_type	operator() (first_argument_type v1, second_argument_type v2) const { return (m_pfn(v1, v2)); }
private:
    pfunc_t		m_pfn;	///< Pointer to the wrapped function.
};

/// ptr_fun(pfn) wraps function pointer pfn into a functor class that calls it.
/// \ingroup FunctorAccessors
template <typename Arg, typename Result>
inline pointer_to_unary_function<Arg,Result> ptr_fun (Result (*pfn)(Arg))
{
    return (pointer_to_unary_function<Arg,Result> (pfn));
}

/// ptr_fun(pfn) wraps function pointer pfn into a functor class that calls it.
/// \ingroup FunctorAccessors
template <typename Arg1, typename Arg2, typename Result>
inline pointer_to_binary_function<Arg1,Arg2,Result> ptr_fun (Result (*pfn)(Arg1,Arg2))
{
    return (pointer_to_binary_function<Arg1,Arg2,Result> (pfn));
}

//----------------------------------------------------------------------
// Negators.
//----------------------------------------------------------------------

/// \brief Wraps a unary function to return its logical negative.
/// Use the \ref unary_negator accessor to create this object.
/// \ingroup FunctorObjects
template <class UnaryFunction>
class unary_negate : public unary_function<typename UnaryFunction::argument_type,
					   typename UnaryFunction::result_type> {
public:
    typedef typename UnaryFunction::argument_type	argument_type;
    typedef typename UnaryFunction::result_type		result_type;
public:
    explicit inline unary_negate (UnaryFunction pfn) : m_pfn (pfn) {}
    inline result_type operator() (argument_type v) const { return (!m_pfn(v)); }
private:
    UnaryFunction	m_pfn;
};

/// Returns the functor that negates the result of *pfn().
/// \ingroup FunctorAccessors
template <class UnaryFunction>
inline unary_negate<UnaryFunction> unary_negator (UnaryFunction pfn)
{
    return (unary_negate<UnaryFunction>(pfn));
}

//----------------------------------------------------------------------
// Argument binders
//----------------------------------------------------------------------

/// \brief Converts a binary function to a unary function
/// by binding a constant value to the first argument.
/// Use the \ref bind1st accessor to create this object.
/// \ingroup FunctorObjects
template <class BinaryFunction> 
class binder1st : public unary_function<typename BinaryFunction::second_argument_type,
					typename BinaryFunction::result_type> {
public:
    typedef typename BinaryFunction::first_argument_type	arg1_t;
    typedef typename BinaryFunction::second_argument_type	arg2_t;
    typedef typename BinaryFunction::result_type		result_t;
public:
    inline binder1st (const BinaryFunction& pfn, const arg1_t& v) : m_pfn (pfn), m_Value(v) {}
    inline result_t operator()(arg2_t v2) const { return (m_pfn (m_Value, v2)); }
protected:
    BinaryFunction	m_pfn;
    arg1_t		m_Value;
};

/// \brief Converts a binary function to a unary function
/// by binding a constant value to the second argument.
/// Use the \ref bind2nd accessor to create this object.
/// \ingroup FunctorObjects
template <class BinaryFunction> 
class binder2nd : public unary_function<typename BinaryFunction::first_argument_type,
					typename BinaryFunction::result_type> {
public:
    typedef typename BinaryFunction::first_argument_type	arg1_t;
    typedef typename BinaryFunction::second_argument_type	arg2_t;
    typedef typename BinaryFunction::result_type		result_t;
public:
    inline binder2nd (const BinaryFunction& pfn, const arg2_t& v) : m_pfn (pfn), m_Value(v) {}
    inline result_t operator()(arg1_t v1) const { return (m_pfn (v1, m_Value)); }
protected:
    BinaryFunction	m_pfn;
    arg2_t		m_Value;
};

/// Converts \p pfn into a unary function by binding the first argument to \p v.
/// \ingroup FunctorAccessors
template <typename BinaryFunction>
inline binder1st<BinaryFunction>
bind1st (BinaryFunction pfn, typename BinaryFunction::first_argument_type v) 
{
    return (binder1st<BinaryFunction> (pfn, v));
}

/// Converts \p pfn into a unary function by binding the second argument to \p v.
/// \ingroup FunctorAccessors
template <typename BinaryFunction>
inline binder2nd<BinaryFunction>
bind2nd (BinaryFunction pfn, typename BinaryFunction::second_argument_type v) 
{
    return (binder2nd<BinaryFunction> (pfn, v));
}

//----------------------------------------------------------------------
// Composition adapters
//----------------------------------------------------------------------

/// \brief Chains two unary functions together.
///
/// When f(x) and g(x) are composed, the result is function c(x)=f(g(x)).
/// Use the \ref compose1 accessor to create this object.
/// This template is an extension, implemented by SGI STL and uSTL.
/// \ingroup FunctorObjects
///
template <typename Operation1, typename Operation2>
class unary_compose : public unary_function<typename Operation2::argument_type,
					    typename Operation1::result_type> {
public:
    typedef typename Operation2::argument_type	arg_t;
    typedef const arg_t&			rcarg_t;
    typedef typename Operation1::result_type	result_t;
public:
    inline unary_compose (const Operation1& f, const Operation2& g) : m_f(f), m_g(g) {}
    inline result_t operator() (rcarg_t x) const { return m_f(m_g(x)); }
protected:
    Operation1	m_f;	///< f(x), if c(x) = f(g(x))
    Operation2	m_g;	///< g(x), if c(x) = f(g(x))
};

/// Creates a \ref unary_compose object whose function c(x)=f(g(x))
/// \ingroup FunctorAccessors
template <typename Operation1, typename Operation2>
inline unary_compose<Operation1, Operation2>
compose1 (const Operation1& f, const Operation2& g)
{ return unary_compose<Operation1,Operation2>(f, g); }

/// \brief Chains two unary functions through a binary function.
///
/// When f(x,y), g(x), and h(x) are composed, the result is function
/// c(x)=f(g(x),h(x)). Use the \ref compose2 accessor to create this
/// object. This template is an extension, implemented by SGI STL and uSTL.
/// \ingroup FunctorObjects
///
template <typename Operation1, typename Operation2, typename Operation3>
class binary_compose : public unary_function<typename Operation2::argument_type,
					    typename Operation1::result_type> {
public:
    typedef typename Operation2::argument_type	arg_t;
    typedef const arg_t&			rcarg_t;
    typedef typename Operation1::result_type	result_t;
public:
    inline binary_compose (const Operation1& f, const Operation2& g, const Operation3& h) : m_f(f), m_g(g), m_h(h) {}
    inline result_t operator() (rcarg_t x) const { return m_f(m_g(x), m_h(x)); }
protected:
    Operation1	m_f;	///< f(x,y), if c(x) = f(g(x),h(x))
    Operation2	m_g;	///< g(x), if c(x) = f(g(x),h(x))
    Operation3	m_h;	///< h(x), if c(x) = f(g(x),h(x))
};

/// Creates a \ref binary_compose object whose function c(x)=f(g(x),h(x))
/// \ingroup FunctorAccessors
template <typename Operation1, typename Operation2, typename Operation3>
inline binary_compose<Operation1, Operation2, Operation3>
compose2 (const Operation1& f, const Operation2& g, const Operation3& h)
{ return binary_compose<Operation1, Operation2, Operation3> (f, g, h); }

//----------------------------------------------------------------------
// Member function adaptors
//----------------------------------------------------------------------

#ifndef DOXYGEN_SHOULD_SKIP_THIS

#define MEM_FUN_T(WrapperName, ClassName, ArgType, FuncType, CallType)				\
    template <typename Ret, class T>								\
    class ClassName : public unary_function<ArgType,Ret> {					\
    public:											\
	typedef Ret (T::*func_t) FuncType;							\
    public:											\
	explicit inline	ClassName (func_t pf) : m_pf (pf) {}					\
	inline Ret	operator() (ArgType p) const { return ((p CallType m_pf)()); }		\
    private:											\
	func_t	m_pf;										\
    };	\
	\
    template <class Ret, typename T>		\
    inline ClassName<Ret,T> WrapperName (Ret (T::*pf) FuncType)	\
    {						\
	return (ClassName<Ret,T> (pf));		\
    }

MEM_FUN_T(mem_fun,	mem_fun_t, 		T*,		(void),		->*)
MEM_FUN_T(mem_fun,	const_mem_fun_t, 	const T*,	(void) const,	->*)
MEM_FUN_T(mem_fun_ref,	mem_fun_ref_t,		T&,		(void),		.*)
MEM_FUN_T(mem_fun_ref,	const_mem_fun_ref_t, 	const T&,	(void) const,	.*)

#define EXT_MEM_FUN_T(ClassName, HostType, FuncType) \
    template <class T, typename Ret, typename V> \
    class ClassName : public unary_function<V,void> { \
    public: \
	typedef Ret (T::*func_t)(V) FuncType; \
    public: \
	inline		ClassName (HostType t, func_t pf) : m_t (t), m_pf (pf) {} \
	inline Ret	operator() (V v) const { return ((m_t->*m_pf)(v)); } \
    private: \
	HostType	m_t; \
	func_t		m_pf; \
    };	\
	\
    template <class T, typename Ret, typename V>					\
    inline ClassName<T,Ret,V> mem_fun (HostType p, Ret (T::*pf)(V) FuncType)	\
    {											\
	return (ClassName<T,Ret,V> (p, pf));						\
    }

EXT_MEM_FUN_T(ext_mem_fun_t,		T*,		)
EXT_MEM_FUN_T(const_ext_mem_fun_t,	const T*,	const)

#endif // DOXYGEN_SHOULD_SKIP_THIS

//----------------------------------------------------------------------
// Member variable adaptors (uSTL extension)
//----------------------------------------------------------------------

#ifndef DOXYGEN_SHOULD_SKIP_THIS

#define MEM_VAR_T(FunctorName, ArgType, VarType, BaseClass, CallImpl)			\
    template <typename Function, class T, typename VT>					\
    class FunctorName##_t : public BaseClass {						\
    public:										\
	typedef ArgType				argument_type;				\
	typedef typename Function::result_type	result_type;				\
	typedef VarType				mem_var_ptr_t;				\
    public:										\
	inline FunctorName##_t (mem_var_ptr_t pv, Function pfn) : m_pv(pv), m_pfn(pfn) {}	\
	inline result_type operator() CallImpl						\
    private:										\
	mem_var_ptr_t	m_pv;								\
	Function	m_pfn;								\
    };											\
											\
    template <typename Function, class T, typename VT>					\
    inline FunctorName##_t<Function, T, VT>						\
    FunctorName (VT T::*mvp, Function pfn)						\
    {											\
	return (FunctorName##_t<Function,T,VT> (mvp, pfn));				\
    }

#define FUNCTOR_UNARY_BASE(ArgType)	unary_function<ArgType, typename Function::result_type>
#define FUNCTOR_BINARY_BASE(ArgType)	binary_function<ArgType, ArgType, typename Function::result_type>

#define MEM_VAR_UNARY_ARGS		(argument_type p) const \
					{ return (m_pfn(p.*m_pv)); }
#define MEM_VAR_BINARY_ARGS		(argument_type p1, argument_type p2) const \
					{ return (m_pfn(p1.*m_pv, p2.*m_pv)); }

MEM_VAR_T(mem_var1,		T&, VT T::*,		FUNCTOR_UNARY_BASE(T&),  MEM_VAR_UNARY_ARGS)
MEM_VAR_T(const_mem_var1, const T&, const VT T::*,	FUNCTOR_UNARY_BASE(T&),  MEM_VAR_UNARY_ARGS)
MEM_VAR_T(mem_var2,		T&, VT T::*,		FUNCTOR_BINARY_BASE(T&), MEM_VAR_BINARY_ARGS)
MEM_VAR_T(const_mem_var2, const T&, const VT T::*,	FUNCTOR_BINARY_BASE(T&), MEM_VAR_BINARY_ARGS)

#undef MEM_VAR_UNARY_ARGS
#undef MEM_VAR_BINARY_ARGS

#endif // DOXYGEN_SHOULD_SKIP_THIS

/// Returned functor passes member variable \p mvp reference of given object to equal\<VT\>.
/// \ingroup FunctorAccessors
template <class T, typename VT>
inline const_mem_var1_t<binder2nd<equal_to<VT> >, T, VT>
mem_var_equal_to (const VT T::*mvp, const VT& v)
{
    return (const_mem_var1_t<binder2nd<equal_to<VT> >,T,VT> (mvp, bind2nd(equal_to<VT>(), v)));
}

/// Returned functor passes member variable \p mvp reference of given object to less\<VT\>.
/// \ingroup FunctorAccessors
template <class T, typename VT>
inline const_mem_var1_t<binder2nd<less<VT> >, T, VT>
mem_var_less (const VT T::*mvp, const VT& v)
{
    return (const_mem_var1_t<binder2nd<less<VT> >,T,VT> (mvp, bind2nd(less<VT>(), v)));
}

/// Returned functor passes member variable \p mvp reference of given object to equal\<VT\>.
/// \ingroup FunctorAccessors
template <class T, typename VT>
inline const_mem_var2_t<equal_to<VT>, T, VT>
mem_var_equal_to (const VT T::*mvp)
{
    return (const_mem_var2_t<equal_to<VT>,T,VT> (mvp, equal_to<VT>()));
}

/// Returned functor passes member variable \p mvp reference of given object to less\<VT\>.
/// \ingroup FunctorAccessors
template <class T, typename VT>
inline const_mem_var2_t<less<VT>, T, VT>
mem_var_less (const VT T::*mvp)
{
    return (const_mem_var2_t<less<VT>,T,VT> (mvp, less<VT>()));
}

//----------------------------------------------------------------------
// Dereference adaptors (uSTL extension)
//----------------------------------------------------------------------

#ifndef DOXYGEN_SHOULD_SKIP_THIS

#define DEREFERENCER_T(ClassName, ArgType, BaseClass, CallImpl, FunctorKey)	\
    template <typename T, typename Function>					\
    class ClassName : public BaseClass {					\
    public:									\
	typedef ArgType*			argument_type;			\
	typedef typename Function::result_type	result_type;			\
    public:									\
	inline			ClassName (Function pfn) : m_pfn (pfn) {}	\
	inline result_type	operator() CallImpl				\
    private:									\
	Function		m_pfn;						\
    };										\
										\
    template <typename T, typename Function>					\
    inline ClassName<T,Function> _dereference (Function pfn, FunctorKey)	\
    {										\
	return (ClassName<T,Function> (pfn));					\
    }

#define DEREF_UNARY_ARGS		(argument_type p) const \
					{ return (m_pfn(*p)); }
#define DEREF_BINARY_ARGS		(argument_type p1, argument_type p2) const \
					{ return (m_pfn(*p1, *p2)); }

DEREFERENCER_T(deref1_t,	T, 		FUNCTOR_UNARY_BASE(T*),		DEREF_UNARY_ARGS,	FUNCTOR_UNARY_BASE(T))
DEREFERENCER_T(const_deref1_t,	const T, 	FUNCTOR_UNARY_BASE(const T*),	DEREF_UNARY_ARGS,	FUNCTOR_UNARY_BASE(const T))
DEREFERENCER_T(deref2_t,	T, 		FUNCTOR_BINARY_BASE(T*),	DEREF_BINARY_ARGS,	FUNCTOR_BINARY_BASE(T))
DEREFERENCER_T(const_deref2_t,	const T, 	FUNCTOR_BINARY_BASE(const T*),	DEREF_BINARY_ARGS,	FUNCTOR_BINARY_BASE(const T))

#define dereference(f) _dereference(f,f)

#undef DEREF_UNARY_ARGS
#undef DEREF_BINARY_ARGS

#endif

} // namespace ustl

#endif

