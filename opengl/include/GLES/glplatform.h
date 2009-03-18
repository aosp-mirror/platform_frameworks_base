#ifndef __glplatform_h_
#define __glplatform_h_

/* $Revision: 7172 $ on $Date:: 2009-01-09 11:17:41 -0800 #$ */

/*
 * This document is licensed under the SGI Free Software B License Version
 * 2.0. For details, see http://oss.sgi.com/projects/FreeB/ .
 */

/* Platform-specific types and definitions for OpenGL ES 1.X  gl.h
 * Last modified on 2008/12/19
 *
 * Adopters may modify khrplatform.h and this file to suit their platform.
 * You are encouraged to submit all modifications to the Khronos group so that
 * they can be included in future versions of this file.  Please submit changes
 * by sending them to the public Khronos Bugzilla (http://khronos.org/bugzilla)
 * by filing a bug against product "OpenGL-ES" component "Registry".
 */

#include <KHR/khrplatform.h>

#ifndef GL_API
#define GL_API      KHRONOS_APICALL
#endif

#if defined(ANDROID)

#define GL_APIENTRY KHRONOS_APIENTRY

// XXX: this should probably not be here
#define GL_DIRECT_TEXTURE_2D_QUALCOMM               0x7E80

// XXX: not sure how this is intended to be used
#define GL_GLEXT_PROTOTYPES

#endif

#endif /* __glplatform_h_ */
