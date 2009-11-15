#ifndef __gl2platform_h_
#define __gl2platform_h_

/* $Revision: 7173 $ on $Date:: 2009-01-09 11:18:21 -0800 #$ */

/*
 * This document is licensed under the SGI Free Software B License Version
 * 2.0. For details, see http://oss.sgi.com/projects/FreeB/ .
 */

/* Platform-specific types and definitions for OpenGL ES 2.X  gl2.h
 * Last modified on 2008/12/19
 *
 * Adopters may modify khrplatform.h and this file to suit their platform.
 * You are encouraged to submit all modifications to the Khronos group so that
 * they can be included in future versions of this file.  Please submit changes
 * by sending them to the public Khronos Bugzilla (http://khronos.org/bugzilla)
 * by filing a bug against product "OpenGL-ES" component "Registry".
 */

#include <KHR/khrplatform.h>

#ifndef GL_APICALL
#define GL_APICALL  KHRONOS_APICALL
#endif

#define GL_APIENTRY KHRONOS_APIENTRY

#endif /* __gl2platform_h_ */
