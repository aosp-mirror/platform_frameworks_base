#ifndef AIDL_MACROS_H_
#define AIDL_MACROS_H_

#define DISALLOW_COPY_AND_ASSIGN(TypeName) \
  TypeName(const TypeName&);               \
  void operator=(const TypeName&)

#endif  // AIDL_MACROS_H_
