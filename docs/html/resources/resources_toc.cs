<ul>
  <li>
    <h2><span class="en">Technical Resources</span>
    </h2>
    <ul>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>resources/browser.html?tag=sample">
            <span class="en">Sample Code</span>
            <span class="de" style="display:none">Beispielcode</span>
            <span class="es" style="display:none">Código de ejemplo</span>
            <span class="fr" style="display:none">Exemple de code</span>
            <span class="it" style="display:none">Codice di esempio</span>
            <span class="ja" style="display:none">サンプル コード</span>
            <span class="zh-CN" style="display:none"></span>
            <span class="zh-TW" style="display:none"></span>
          </a></div>
        <ul id="devdoc-nav-sample-list">
          <li><a href="<?cs var:toroot ?>resources/samples/get.html">
                <span class="en">Getting the Samples</span>
              </a></li>
        </ul>
      </li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>resources/browser.html?tag=article">
               <span class="en">Articles</span>
             </a></div>
        <ul id="devdoc-nav-article-list">
        </ul>
      </li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>resources/browser.html?tag=tutorial">
               <span class="en">Tutorials</span>
               <span class="de" style="display:none">Lernprogramme</span>
               <span class="es" style="display:none">Tutoriales</span>
               <span class="fr" style="display:none">Didacticiels</span>
               <span class="it" style="display:none">Esercitazioni</span>
               <span class="ja" style="display:none">チュートリアル</span>
               <span class="zh-CN" style="display:none"></span>
               <span class="zh-TW" style="display:none"></span>
             </a></div>
        <ul id="devdoc-nav-tutorial-list">
        </ul>
      </li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>resources/topics.html">
               <span class="en">Topics</span>
             </a></div>
        <ul id="devdoc-nav-topic-list">
        </ul>
      </li>
    </ul>
  </li>
  <li>
    <h2><span class="en">Community</span>
               <span style="display:none" class="de"></span>
               <span style="display:none" class="es">Comunidad</span>
               <span style="display:none" class="fr">Communauté</span>
               <span style="display:none" class="it"></span>
               <span style="display:none" class="ja">コミュニティ</span>
               <span style="display:none" class="zh-CN">社区</span>
               <span style="display:none" class="zh-TW">社群</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>resources/community-groups.html">
            <span class="en">Developer Forums</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>resources/community-more.html">
            <span class="en">IRC, Twitter</span>
          </a></li>
    </ul>
  </li>
<?cs
  if:android.whichdoc == "online" ?>
  <li>
    <h2><span class="en">Device Dashboard</span>
    </h2>
    <ul>
      <li><a href="<?cs var:toroot ?>resources/dashboard/platform-versions.html">
            <span class="en">Platform Versions</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>resources/dashboard/screens.html">
            <span class="en">Screen Sizes &amp; Densities</span>
          </a></li>
      <li><a href="<?cs var:toroot ?>resources/dashboard/opengl.html">
            <span class="en">OpenGL ES Versions</span>
          </a></li>
    </ul>
  </li><?cs
  /if
?>

  <li>
   <h2><span class="en">More</span></h2>
    <ul>
      <li><a href="<?cs var:toroot ?>resources/faq/commontasks.html">
            <span class="en">Common Tasks </span>
          </a></li>
      <li><a href="<?cs var:toroot ?>resources/faq/troubleshooting.html">
            <span class="en">Troubleshooting Tips</span>
          </a></li>
      <li class="toggle-list">
        <div><a href="<?cs var:toroot ?>resources/faq/index.html">
               <span class="en">FAQs</span>
             </a></div>
        <ul>
        <li><a href="<?cs var:toroot ?>resources/faq/framework.html">
                <span class="en">App Framework FAQ</span>
                </a></li>
        <li><a href="<?cs var:toroot ?>resources/faq/licensingandoss.html">
                <span class="en">Licensing FAQ</span>
                </a></li>
        <li><a href="<?cs var:toroot ?>resources/faq/security.html">
                <span class="en">Security FAQ</span>
                </a></li>
        </ul>
     </li>
    </ul>
  </li>
</ul>

<script type="text/javascript">
<!--
    buildToggleLists();
    changeNavLang(getLangPref());
//-->
</script>
