/* Guard the header against multiple inclusion. */
#ifndef __ARM_COMM_VERSION_H__
#define __ARM_COMM_VERSION_H__


/* The following line should be in omxtypes.h but hasn't been approved by OpenMAX yet */
#define OMX_VERSION 102

/* We need to define these macros in order to convert a #define number into a #define string. */
#define ARM_QUOTE(a) #a
#define ARM_INDIRECT(A) ARM_QUOTE(A)

/* Convert the OMX_VERSION number into a string that can be used, for example, to print it out. */
#define ARM_VERSION_STRING ARM_INDIRECT(OMX_VERSION)


/* Define this in order to turn on ARM version/release/build strings in each domain */
#define ARM_INCLUDE_VERSION_DESCRIPTIONS

#ifdef ARM_INCLUDE_VERSION_DESCRIPTIONS
  extern const char * const omxAC_VersionDescription;
  extern const char * const omxIC_VersionDescription;
  extern const char * const omxIP_VersionDescription;
  extern const char * const omxSP_VersionDescription;
  extern const char * const omxVC_VersionDescription;
#endif /* ARM_INCLUDE_VERSION_DESCRIPTIONS */


/* The following entries should be automatically updated by the release script */
/* They are used in the ARM version strings defined for each domain.             */

/* The release tag associated with this release of the library. - used for source and object releases */
#define OMX_ARM_RELEASE_TAG  "r0p0-00bet1"

/* The ARM architecture used to build any objects or executables in this release. */
#define OMX_ARM_BUILD_ARCHITECTURE "ARM Architecture V6"

/* The ARM Toolchain used to build any objects or executables in this release. */
#define OMX_ARM_BUILD_TOOLCHAIN    "ARM RVCT 3.1"


#endif /* __ARM_COMM_VERSION_H__ */

