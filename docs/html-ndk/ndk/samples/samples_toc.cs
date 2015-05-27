<?cs # Table of contents for Dev Guide.

       For each document available in translation, add an localized title to this TOC.
       Do not add localized title for docs not available in translation.
       Below are template spans for adding localized doc titles. Please ensure that
       localized titles are added in the language order specified below.
?>

<ul id="nav">

   <li class="nav-section">
      <div class="nav-section-header empty"><a href="<?cs var:toroot ?>ndk/samples/index.html">
      <span class="en">Overview</span></a></div>
   </li>

   <li class="nav-section">
      <div class="nav-section-header">
      <a href="<?cs var:toroot ?>ndk/samples/walkthroughs.html">
      <span class="en">Walkthroughs</span></a></div>
      <ul>
         <li><a href="<?cs var:toroot ?>ndk/samples/sample_hellojni.html">hello-jni</a></li>
         <li><a href="<?cs var:toroot ?>ndk/samples/sample_na.html">native-activity</a></li>
         <li><a href="<?cs var:toroot ?>ndk/samples/sample_teapot.html">Teapot</a></li>
      </ul>
   </li>
</ul>


<script type="text/javascript">
<!--
    buildToggleLists();
    changeNavLang(getLangPref());
//-->
</script>

