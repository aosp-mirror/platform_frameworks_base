<?cs
set:ndk.mac64_download='android-ndk-r12-darwin-x86_64.zip' ?><?cs
set:ndk.mac64_bytes='734014148' ?><?cs
set:ndk.mac64_checksum='708d4025142924f7097a9f44edf0a35965706737' ?><?cs

set:ndk.linux64_download='android-ndk-r12-linux-x86_64.zip' ?><?cs
set:ndk.linux64_bytes='755431993' ?><?cs
set:ndk.linux64_checksum='b7e02dc733692447366a2002ad17e87714528b39' ?><?cs

set:ndk.win64_download='android-ndk-r12-windows-x86.zip' ?><?cs
set:ndk.win64_bytes='706332762' ?><?cs
set:ndk.win64_checksum='37fcd7acf6012d0068a57c1524edf24b0fef69c9' ?><?cs

set:ndk.win32_download='android-ndk-r12-windows-x86_64.zip' ?><?cs
set:ndk.win32_bytes='749444245' ?><?cs
set:ndk.win32_checksum='80d64a77aab52df867ac55cec1e976663dd3326f'
?>
<?cs
def:size_in_mb(bytes)
  ?><?cs set:mb = bytes / 1024 / 1024
  ?><?cs var:mb ?><?cs
/def ?>
