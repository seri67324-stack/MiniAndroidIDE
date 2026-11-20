package com.miniide.app.project

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader

/**
 * Prompt 9:
 * 1. 5 adet derlenebilir proje şablonu (Boş Activity, Liste, Çok Ekranlı Activity+Fragment,
 *    Servis/BroadcastReceiver, Basit Oyun Canvas/SurfaceView) ve minSdk seçimi
 * 2. Yeni bileşen üretici (Activity, Fragment, Layout, Sınıf, Arayüz, String kaynağı)
 * 3. Manifest düzenleyici (izin ekle/kaldır, Activity ekle, launcher seç)
 * 4. Cihaz içi XML düzen önizleyici (XmlPullParser tabanlı güvenli şişirme)
 * 5. Kaynak tablo yönetimi ve basit adaptif ikon üretici
 */
object ProjectTemplatesAndTools {

    enum class ProjectTemplate(val titleTr: String, val descriptionTr: String) {
        EMPTY_ACTIVITY("Boş Activity", "Temiz Material kartlı başlangıç ekranı"),
        LIST_ACTIVITY("Liste Uygulaması", "Özel adaptörlü ListView ve öğe ekleme örneği"),
        MULTI_SCREEN("Çok Ekranlı (Activity + Fragment)", "Fragment geçişli iki ekranlı mimari"),
        SERVICE_RECEIVER("Servis + BroadcastReceiver", "Arka plan servisi ve yayın alıcı şablonu"),
        CANVAS_GAME("Basit Oyun (Canvas / SurfaceView)", "60 FPS dokunmatik zıplayan top Canvas oyunu")
    }

    fun isValidPackageName(pkg: String): Boolean =
        Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$").matches(pkg.trim())

    /** Uygulama adından paket adı önerir: "Not Defteri" -> com.ornek.notdefteri (Türkçe karakterler dönüştürülür). */
    fun suggestPackageName(appName: String): String {
        val trMap = mapOf(
            'ı' to 'i', 'İ' to 'i',
            'ğ' to 'g', 'Ğ' to 'g',
            'ü' to 'u', 'Ü' to 'u',
            'ş' to 's', 'Ş' to 's',
            'ö' to 'o', 'Ö' to 'o',
            'ç' to 'c', 'Ç' to 'c'
        )
        val mapped = buildString {
            for (ch in appName.trim()) {
                val c = (trMap[ch] ?: ch).lowercaseChar()
                if (c in 'a'..'z' || c in '0'..'9') append(c)
            }
        }
        val slug = when {
            mapped.isEmpty() -> "uygulama"
            mapped[0].isDigit() -> "app$mapped"
            else -> mapped
        }
        return "com.ornek.$slug"
    }

    fun isValidJavaClassName(name: String): Boolean =
        Regex("^[A-Z][A-Za-z0-9_]*$").matches(name.trim())

    fun isValidResourceName(name: String): Boolean =
        Regex("^[a-z][a-z0-9_]*$").matches(name.trim())

    /** Seçilen şablon ve minSdk ile tam derlenebilir Android projesi üretir. */
    fun createProjectFromTemplate(
        projectsRoot: File,
        appName: String,
        packageName: String,
        template: ProjectTemplate,
        minSdk: Int = 21
    ): File {
        val cleanApp = appName.trim().ifBlank { "YeniProje" }
        val pkg = packageName.trim().lowercase()
        require(isValidPackageName(pkg)) { "Geçersiz paket adı: $pkg" }

        val folderBase = cleanApp.replace(Regex("[^\\w.\\-]"), "").ifBlank { "YeniProje" }
        var projectDir = File(projectsRoot, folderBase)
        var n = 2
        while (projectDir.exists()) { projectDir = File(projectsRoot, "$folderBase-$n"); n++ }

        val pkgDir = File(projectDir, "src/" + pkg.replace('.', '/')).apply { mkdirs() }
        val layoutDir = File(projectDir, "res/layout").apply { mkdirs() }
        val valuesDir = File(projectDir, "res/values").apply { mkdirs() }
        val drawableDir = File(projectDir, "res/drawable").apply { mkdirs() }
        File(projectDir, "assets").mkdirs()
        File(projectDir, "libs").mkdirs()

        File(valuesDir, "strings.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">${escapeXml(cleanApp)}</string>
</resources>
"""
        )
        File(valuesDir, "colors.xml").writeText(
            """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="primary">#2563EB</color>
    <color name="bg">#F8FAFC</color>
</resources>
"""
        )
        File(drawableDir, "ic_launcher.xml").writeText(generateAdaptiveIconXml("#2563EB", "#FFFFFF"))

        var manifest = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="$pkg"
    android:versionCode="1"
    android:versionName="1.0">

    <uses-sdk
        android:minSdkVersion="${minSdk.coerceIn(21, 34)}"
        android:targetSdkVersion="34" />

    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.Material.Light.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>
</manifest>
"""

        when (template) {
            ProjectTemplate.EMPTY_ACTIVITY -> {
                File(layoutDir, "activity_main.xml").writeText(
                    """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">
    <TextView
        android:id="@+id/tvHello"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/app_name"
        android:textSize="22sp"
        android:textStyle="bold" />
</LinearLayout>
"""
                )
                File(pkgDir, "MainActivity.java").writeText(
                    """package $pkg;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
"""
                )
            }
            ProjectTemplate.LIST_ACTIVITY -> {
                File(layoutDir, "activity_main.xml").writeText(
                    """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="12dp">
    <Button
        android:id="@+id/btnAdd"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="+ Öğe Ekle" />
    <ListView
        android:id="@+id/listView"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />
</LinearLayout>
"""
                )
                File(pkgDir, "MainActivity.java").writeText(
                    """package $pkg;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private final ArrayList<String> items = new ArrayList<String>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        items.add("Öğe 1");
        items.add("Öğe 2");
        ListView lv = findViewById(R.id.listView);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items);
        lv.setAdapter(adapter);
        Button btnAdd = findViewById(R.id.btnAdd);
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                items.add("Öğe " + (items.size() + 1));
                adapter.notifyDataSetChanged();
            }
        });
    }
}
"""
                )
            }
            ProjectTemplate.MULTI_SCREEN -> {
                File(layoutDir, "activity_main.xml").writeText(
                    """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">
    <FrameLayout
        android:id="@+id/fragmentContainer"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1" />
</LinearLayout>
"""
                )
                File(pkgDir, "HomeFragment.java").writeText(
                    """package $pkg;

import android.app.Fragment;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

public class HomeFragment extends Fragment {
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        TextView tv = new TextView(getActivity());
        tv.setText("Ana Fragment Ekranı");
        tv.setTextSize(20f);
        tv.setPadding(32, 32, 32, 32);
        return tv;
    }
}
"""
                )
                File(pkgDir, "MainActivity.java").writeText(
                    """package $pkg;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (savedInstanceState == null) {
            getFragmentManager().beginTransaction()
                .replace(R.id.fragmentContainer, new HomeFragment())
                .commit();
        }
    }
}
"""
                )
            }
            ProjectTemplate.SERVICE_RECEIVER -> {
                manifest = manifest.replace(
                    "</application>",
                    "        <service android:name=\".DemoService\" android:exported=\"false\" />\n" +
                    "        <receiver android:name=\".DemoReceiver\" android:exported=\"false\" />\n    </application>"
                )
                File(layoutDir, "activity_main.xml").writeText(
                    """<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:gravity="center"
    android:orientation="vertical"
    android:padding="24dp">
    <Button
        android:id="@+id/btnStartService"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="Servisi Başlat" />
</LinearLayout>
"""
                )
                File(pkgDir, "DemoService.java").writeText(
                    """package $pkg;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class DemoService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelf();
        return START_NOT_STICKY;
    }
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
"""
                )
                File(pkgDir, "DemoReceiver.java").writeText(
                    """package $pkg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class DemoReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) { }
}
"""
                )
                File(pkgDir, "MainActivity.java").writeText(
                    """package $pkg;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.btnStartService).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startService(new Intent(MainActivity.this, DemoService.class));
                Toast.makeText(MainActivity.this, "Servis tetiklendi", Toast.LENGTH_SHORT).show();
            }
        });
    }
}
"""
                )
            }
            ProjectTemplate.CANVAS_GAME -> {
                File(pkgDir, "GameView.java").writeText(
                    """package $pkg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

public class GameView extends View {
    private float bx = 200f, by = 200f, vx = 8f, vy = 10f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GameView(Context ctx) {
        super(ctx);
        paint.setColor(Color.rgb(56, 189, 248));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(15, 23, 42));
        bx += vx; by += vy;
        if (bx < 40 || bx > getWidth() - 40) vx = -vx;
        if (by < 40 || by > getHeight() - 40) vy = -vy;
        canvas.drawCircle(bx, by, 40f, paint);
        postInvalidateOnAnimation();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            bx = event.getX(); by = event.getY();
        }
        return true;
    }
}
"""
                )
                File(pkgDir, "MainActivity.java").writeText(
                    """package $pkg;

import android.app.Activity;
import android.os.Bundle;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new GameView(this));
    }
}
"""
                )
            }
        }

        File(projectDir, "AndroidManifest.xml").writeText(manifest)
        return projectDir
    }

    // ---------- 3. Manifest Düzenleyici (Saf XML İşlemleri) ----------

    object ManifestEditor {
        fun listPermissions(manifestXml: String): List<String> =
            Regex("""<uses-permission\s+[^>]*android:name\s*=\s*"([^"]+)"[^>]*/?>""")
                .findAll(manifestXml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

        fun addPermission(manifestXml: String, permission: String): String {
            val clean = permission.trim()
            if (clean.isEmpty() || manifestXml.contains("\"$clean\"")) return manifestXml
            val line = "    <uses-permission android:name=\"$clean\" />\n"
            val appIdx = manifestXml.indexOf("<application")
            return if (appIdx >= 0) {
                manifestXml.substring(0, appIdx) + line + manifestXml.substring(appIdx)
            } else manifestXml
        }

        fun removePermission(manifestXml: String, permission: String): String {
            val esc = Regex.escape(permission.trim())
            return manifestXml.replace(Regex("""[ \t]*<uses-permission\s+[^>]*android:name\s*=\s*"$esc"[^>]*/?>\r?\n?"""), "")
        }

        fun listActivities(manifestXml: String): List<String> =
            Regex("""<activity\s+[^>]*android:name\s*=\s*"([^"]+)"""")
                .findAll(manifestXml)
                .map { it.groupValues[1] }
                .distinct()
                .toList()

        fun addActivity(manifestXml: String, activityName: String, exported: Boolean = false): String {
            val name = if (activityName.startsWith(".")) activityName else ".$activityName"
            if (manifestXml.contains("android:name=\"$name\"")) return manifestXml
            val tag = "        <activity android:name=\"$name\" android:exported=\"$exported\" />\n    </application>"
            return manifestXml.replace("</application>", tag)
        }

        fun setLauncherActivity(manifestXml: String, activityName: String): String {
            val name = if (activityName.startsWith(".")) activityName else ".$activityName"
            // Mevcut MAIN/LAUNCHER intent-filter'ı kaldır
            val stripped = manifestXml.replace(
                Regex("""<intent-filter>\s*<action\s+android:name="android\.intent\.action\.MAIN"\s*/>\s*<category\s+android:name="android\.intent\.category\.LAUNCHER"\s*/>\s*</intent-filter>"""),
                ""
            )
            // Self-closing <activity android:name=".Foo" ... /> ise açık etikete çevirip intent-filter ekle
            val selfClosing = Regex("""<activity([^>]*android:name="$name"[^>]*)/>""")
            val launcherFilter = """
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>"""
            return if (selfClosing.containsMatchIn(stripped)) {
                selfClosing.replaceFirst(stripped, "<activity$1>$launcherFilter")
            } else {
                val openActivity = Regex("""(<activity[^>]*android:name="$name"[^>]*>)""")
                openActivity.replaceFirst(stripped, "$1$launcherFilter")
            }
        }
    }

    // ---------- 4. Cihaz İçi XML Düzen Önizleyici ----------

    object XmlLayoutPreviewer {
        fun inflatePreview(context: Context, xmlContent: String): Result<View> {
            return try {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(xmlContent))
                val stack = ArrayDeque<ViewGroup>()
                var rootView: View? = null
                val dp = context.resources.displayMetrics.density

                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        val tag = parser.name.substringAfterLast('.')
                        val view = createPreviewView(context, tag, parser, dp)
                        if (rootView == null) {
                            rootView = view
                        } else {
                            stack.lastOrNull()?.addView(view)
                        }
                        if (view is ViewGroup) {
                            stack.addLast(view)
                        }
                    } else if (event == XmlPullParser.END_TAG) {
                        val tag = parser.name.substringAfterLast('.')
                        if (stack.isNotEmpty() && isViewGroupTag(tag)) {
                            stack.removeLast()
                        }
                    }
                    event = parser.next()
                }
                if (rootView == null) Result.failure(IllegalArgumentException("XML içinde geçerli bir kök görünüm bulunamadı"))
                else Result.success(rootView)
            } catch (e: Exception) {
                Result.failure(IllegalArgumentException("XML önizleme hatası: ${e.message}"))
            }
        }

        private fun isViewGroupTag(tag: String): Boolean =
            tag in setOf("LinearLayout", "FrameLayout", "RelativeLayout", "ScrollView", "HorizontalScrollView")

        private fun createPreviewView(context: Context, tag: String, parser: XmlPullParser, dp: Float): View {
            fun attr(name: String): String? =
                (0 until parser.attributeCount).firstOrNull {
                    parser.getAttributeName(it).substringAfter(':') == name
                }?.let { parser.getAttributeValue(it) }

            val view: View = when (tag) {
                "LinearLayout" -> LinearLayout(context).apply {
                    orientation = if (attr("orientation") == "horizontal") LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    if (attr("gravity") == "center") gravity = Gravity.CENTER
                }
                "FrameLayout", "RelativeLayout" -> FrameLayout(context)
                "ScrollView" -> ScrollView(context)
                "TextView" -> TextView(context).apply {
                    text = attr("text") ?: "TextView"
                    textSize = attr("textSize")?.removeSuffix("sp")?.toFloatOrNull() ?: 14f
                }
                "Button", "MaterialButton" -> Button(context).apply {
                    text = attr("text") ?: "Düğme"
                }
                "EditText" -> EditText(context).apply {
                    hint = attr("hint") ?: "Metin girişi"
                }
                "CheckBox" -> CheckBox(context).apply {
                    text = attr("text") ?: "Seçenek"
                }
                "ProgressBar" -> ProgressBar(context)
                else -> TextView(context).apply {
                    text = "<$tag>"
                    setBackgroundColor(Color.argb(30, 59, 130, 246))
                    setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                }
            }

            val pad = attr("padding")?.removeSuffix("dp")?.toIntOrNull()
            if (pad != null) {
                val p = (pad * dp).toInt()
                view.setPadding(p, p, p, p)
            }
            val w = if (attr("layout_width") == "match_parent") ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            val h = if (attr("layout_height") == "match_parent") ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
            view.layoutParams = LinearLayout.LayoutParams(w, h)
            return view
        }
    }

    // ---------- 5. Adaptif İkon & String Kaynağı Yardımcısı ----------

    fun generateAdaptiveIconXml(bgHex: String = "#2563EB", fgHex: String = "#FFFFFF"): String = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="72dp"
    android:height="72dp"
    android:viewportWidth="72"
    android:viewportHeight="72">
    <path android:fillColor="$bgHex" android:pathData="M16,0h40c8.8,0 16,7.2 16,16v40c0,8.8 -7.2,16 -16,16H16C7.2,72 0,64.8 0,56V16C0,7.2 7.2,0 16,0z" />
    <path android:fillColor="$fgHex" android:pathData="M28,24L16,36L28,48L31,45L22,36L31,27Z" />
    <path android:fillColor="$fgHex" android:pathData="M44,24L41,27L50,36L41,45L44,48L56,36Z" />
</vector>
"""

    fun addStringResource(stringsXml: String, key: String, value: String): String {
        require(isValidResourceName(key)) { "Geçersiz kaynak adı: $key" }
        val line = "    <string name=\"$key\">${escapeXml(value)}</string>\n</resources>"
        return stringsXml.replace("</resources>", line)
    }

    private fun escapeXml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "\\'")
}
