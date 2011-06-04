echo off
set ASMFLAGS= -checkreglist -CPU ARM1136 -PreDefine "H264DEC_WINASM SETL {TRUE}"
set ASM="D:\Program Files\Microsoft Visual Studio 8\VC\ce\bin\x86_arm\armasm"
echo on

%ASM% %ASMFLAGS% h264bsd_interpolate_chroma_ver.s
%ASM% %ASMFLAGS% h264bsd_interpolate_chroma_hor.s
%ASM% %ASMFLAGS% h264bsd_interpolate_hor_half.s
%ASM% %ASMFLAGS% h264bsd_interpolate_hor_quarter.s
%ASM% %ASMFLAGS% h264bsd_interpolate_hor_ver_quarter.s
%ASM% %ASMFLAGS% h264bsd_interpolate_ver_half.s
%ASM% %ASMFLAGS% h264bsd_interpolate_ver_quarter.s

rem %ASM% %ASMFLAGS% h264bsd_interpolate_chroma_hor_ver.s
rem %ASM% %ASMFLAGS% h264bsd_interpolate_mid_hor.s
