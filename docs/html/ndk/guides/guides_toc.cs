<?cs # Table of contents for Dev Guide.

       For each document available in translation, add an localized title to this TOC.
       Do not add localized title for docs not available in translation.
       Below are template spans for adding localized doc titles. Please ensure that
       localized titles are added in the language order specified below.
?>

<ul id="nav">
   <li class="nav-section">
      <div class="nav-section-header"><a href="<?cs var:toroot ?>ndk/guides/index.html">
      <span class="en">Getting Started</span></a></div>
      <ul>
         <li><a href="<?cs var:toroot ?>ndk/guides/setup.html">Setup</a></li>
         <li><a href="<?cs var:toroot ?>ndk/guides/concepts.html">Concepts</a></li>
      </ul>
   </li>

   <li class="nav-section">
      <div class="nav-section-header"><a href="<?cs var:toroot ?>ndk/guides/build.html">
      <span class="en">
      Building</span></a></div>
      <ul>
         <li><a href="<?cs var:toroot ?>ndk/guides/android_mk.html">Android.mk</a></li>
         <li><a href="<?cs var:toroot ?>ndk/guides/application_mk.html">Application.mk</a></li>
         <li><a href="<?cs var:toroot ?>ndk/guides/ndk-build.html">ndk-build</a></li>
         <li><a href="<?cs var:toroot ?>ndk/guides/standalone_toolchain.html">Standalone Toolchain
         </a></li>
      </ul>
   </li>

   <li class="nav-section">
      <div class="nav-section-header"><a href="<?cs var:toroot ?>ndk/guides/arch.html">
      <span class="en">Architectures and CPUs</span></a></div>
      <ul>
         <li><a href="<?cs var:toroot ?>ndk/guides/abis.html">ABI Management</a></li>
         <li><a href="<?cs var:toroot ?>ndk/guides/cpu-arm-neon.html">NEON</a></li>
         <li><a href="<?cs var:toroot ?>ndk/guides/x86.html">x86</a></li>
         <li><a href="<?cs var:toroot ?>ndk/guides/x86-64.html">x86-64</a></li>
         <li><a href="<?cs var:toroot ?>ndk/guides/mips.html">MIPS</a></li>
         <li><a href="<?cs var:toroot ?>ndk/guides/cpu-features.html">The cpufeatures Library</a>
         </li>
      </ul>
   </li>

   <li class="nav-section">
      <div class="nav-section-header"><a href="<?cs var:toroot ?>ndk/guides/debug.html">
      <span class="en">Debugging</span></a></div>
      <ul>
         <li><a href="<?cs var:toroot ?>ndk/guides/ndk-gdb.html">ndk-gdb</a></li>
         <li><a href="<?cs var:toroot ?>ndk/guides/ndk-stack.html">ndk-stack</a></li>
      </ul>
   </li>

   <li class="nav-section">
      <div class="nav-section-header"><a href="<?cs var:toroot ?>ndk/guides/libs.html">
      <span class="en">Libraries</span></a></div>
      <ul>
      <li><a href="<?cs var:toroot ?>ndk/guides/prebuilts.html">Prebuilt Libraries</a></li>
      <li><a href="<?cs var:toroot ?>ndk/guides/cpp-support.html">C++ Support</a></li>
      <li><a href="<?cs var:toroot ?>ndk/guides/stable_apis.html">Stable APIs</a></li>

      </ul>
   </li>

</ul>


<script type="text/javascript">
<!--
    buildToggleLists();
    changeNavLang(getLangPref());
//-->
</script>

