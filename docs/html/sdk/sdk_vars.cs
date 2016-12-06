<?cs
set:ndk.mac64_download='android-ndk-r12b-darwin-x86_64.zip' ?><?cs
set:ndk.mac64_bytes='734135279' ?><?cs
set:ndk.mac64_checksum='e257fe12f8947be9f79c10c3fffe87fb9406118a' ?><?cs

set:ndk.linux64_download='android-ndk-r12b-linux-x86_64.zip' ?><?cs
set:ndk.linux64_bytes='755551010' ?><?cs
set:ndk.linux64_checksum='170a119bfa0f0ce5dc932405eaa3a7cc61b27694' ?><?cs

set:ndk.win32_download='android-ndk-r12b-windows-x86.zip' ?><?cs
set:ndk.win32_bytes='706453972' ?><?cs
set:ndk.win32_checksum='8e6eef0091dac2f3c7a1ecbb7070d4fa22212c04' ?><?cs

set:ndk.win64_download='android-ndk-r12b-windows-x86_64.zip' ?><?cs
set:ndk.win64_bytes='749567353' ?><?cs
set:ndk.win64_checksum='337746d8579a1c65e8a69bf9cbdc9849bcacf7f5'
?>
<?cs
def:size_in_mb(bytes)
  ?><?cs set:mb = bytes / 1024 / 1024
  ?><?cs var:mb ?><?cs
/def ?>