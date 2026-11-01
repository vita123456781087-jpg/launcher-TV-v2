package com.example.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    // ===== НАСТРОЙКИ ВНЕШНЕГО ВИДА =====
    private static final int BG_1 = 0xFF080D1C;          // фон: левый верх
    private static final int BG_2 = 0xFF17123D;          // фон: середина
    private static final int BG_3 = 0xFF0A2C3C;          // фон: правый низ
    private static final int[] WAVE_COLORS = {0xFF4FD1FF, 0xFF8E6BFF, 0xFF3A8DFF}; // цвета волн
    private static final float FOCUS_SCALE = 1.08f;      // увеличение выбранной плитки
    private static final int ALL_COLUMNS = 5;            // колонок в списке «Все приложения»
    // Приложения, которые сами попадут в избранное при первом запуске (если установлены)
    private static final String[] DEFAULT_FAVS = {
            "org.smarttube.stable", "org.smarttube.beta", "com.liskovsoft.smarttubetv.beta",
            "org.xbmc.kodi", "org.videolan.vlc", "com.mxtech.videoplayer.ad",
            "com.esaba.downloader", "com.android.tv.settings", "com.android.settings"
    };
    // ====================================

    private static class App {
        String label;
        String pkg;
        Drawable icon;
        ActivityInfo ai;
        Intent intent;
        int color;
    }

    private final List<App> apps = new ArrayList<App>();
    private SharedPreferences prefs;
    private PackageManager pm;
    private FrameLayout root;
    private HorizontalScrollView rowScroll;
    private LinearLayout row;
    private TextView hint;
    private LinearLayout overlay;
    private float u; // 1 условная единица = 1% ширины экрана (всё масштабируется под экран)
    private String lastSig = "";
    private boolean firstStart = true;
    private volatile boolean loading = false;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        pm = getPackageManager();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        u = dm.widthPixels / 100f;
        buildUi();
        loadApps();
    }

    private int px(float units) { return Math.round(units * u); }

    private TextView text(String s, float sizeU, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeU * u);
        return t;
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.addView(new WaveView(this), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Часы и дата слева сверху
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(px(5f), px(3.5f), 0, 0);

        TextClock clock = new TextClock(this);
        clock.setFormat24Hour("HH:mm");
        clock.setFormat12Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, 8.5f * u);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        top.addView(clock);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d MMMM");
        date.setFormat12Hour("EEEE, d MMMM");
        date.setTextColor(0xB3FFFFFF);
        date.setTextSize(TypedValue.COMPLEX_UNIT_PX, 2.2f * u);
        top.addView(date);

        root.addView(top, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Ряд избранного внизу
        rowScroll = new HorizontalScrollView(this);
        rowScroll.setHorizontalScrollBarEnabled(false);
        rowScroll.setClipChildren(false);
        rowScroll.setClipToPadding(false);
        rowScroll.setPadding(px(5f), px(2f), px(5f), px(2f));
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        rowScroll.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        rp.bottomMargin = px(3.5f);
        root.addView(rowScroll, rp);

        // Подсказка, пока избранное пустое
        hint = text("Откройте «Все приложения» и удерживайте OK на приложении, чтобы добавить его сюда",
                1.6f, 0x99FFFFFF);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.START);
        hp.leftMargin = px(5f);
        hp.bottomMargin = px(1f);
        root.addView(hint, hp);
        hint.setVisibility(View.GONE);

        setContentView(root);
    }

    private void refreshRow(int focusIdx) {
        row.removeAllViews();
        int tw = px(19.5f), th = px(11f), gap = px(1.2f);
        int favCount = 0;

        for (String pkg : getFavs()) {
            final App a = find(pkg);
            if (a == null) continue;
            favCount++;
            View t = tile(a, tw, th, true);
            t.setOnClickListener(v -> launch(a));
            t.setOnLongClickListener(v -> {
                int idx = row.indexOfChild(v);
                toggleFav(a);
                toast("Убрано из избранного: " + a.label);
                refreshRow(idx);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tw, th);
            lp.rightMargin = gap;
            row.addView(t, lp);
        }

        View all = allTile(tw, th);
        all.setOnClickListener(v -> openAll());
        row.addView(all, new LinearLayout.LayoutParams(tw, th));

        hint.setVisibility(favCount == 0 ? View.VISIBLE : View.GONE);

        if (focusIdx >= 0 && overlay == null) {
            View v = row.getChildAt(Math.min(focusIdx, row.getChildCount() - 1));
            if (v != null) v.requestFocus();
        }
    }

    // Плитка приложения. С баннером (если у приложения он есть) или иконка на цветном фоне.
    private FrameLayout tile(App a, int w, int h, boolean wantBanner) {
        FrameLayout f = new FrameLayout(this);
        float rad = 1.1f * u;

        Drawable banner = null;
        if (wantBanner) {
            try { banner = a.ai.loadBanner(pm); } catch (Throwable ignored) { }
        }

        if (banner != null) {
            f.setBackgroundColor(0xFF10162A);
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageDrawable(banner);
            f.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            GradientDrawable bg = new GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    new int[]{mix(a.color, 0xFF141A2B, 0.55f), mix(a.color, 0xFF0B1020, 0.18f)});
            bg.setCornerRadius(rad);
            f.setBackground(bg);

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(px(0.8f), 0, px(0.8f), 0);

            int is = Math.round(h * 0.44f);
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(a.icon);
            col.addView(icon, new LinearLayout.LayoutParams(is, is));

            TextView lb = new TextView(this);
            lb.setText(a.label);
            lb.setTextColor(Color.WHITE);
            lb.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.13f);
            lb.setSingleLine(true);
            lb.setEllipsize(TextUtils.TruncateAt.END);
            lb.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(h * 0.06f);
            col.addView(lb, lp);

            f.addView(col, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        decorate(f, rad);
        return f;
    }

    // Плитка «Все приложения»
    private FrameLayout allTile(int w, int h) {
        FrameLayout f = new FrameLayout(this);
        float rad = 1.1f * u;
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF2E7BFF, 0xFF8E4DFF});
        bg.setCornerRadius(rad);
        f.setBackground(bg);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int is = Math.round(h * 0.36f);
        col.addView(new GridIconView(this), new LinearLayout.LayoutParams(is, is));

        TextView lb = new TextView(this);
        lb.setText("Все приложения");
        lb.setTextColor(Color.WHITE);
        lb.setTextSize(TypedValue.COMPLEX_UNIT_PX, h * 0.13f);
        lb.setSingleLine(true);
        lb.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(h * 0.07f);
        col.addView(lb, lp);

        f.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        decorate(f, rad);
        return f;
    }

    // Скругление, рамка и увеличение при фокусе
    private void decorate(FrameLayout f, final float rad) {
        f.setFocusable(true);
        f.setClickable(true);
        f.setLongClickable(true);
        f.setClipToOutline(true);
        f.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), rad);
            }
        });

        StateListDrawable sl = new StateListDrawable();
        GradientDrawable on = new GradientDrawable();
        on.setColor(Color.TRANSPARENT);
        on.setCornerRadius(rad);
        on.setStroke(Math.max(2, Math.round(0.3f * u)), 0xFFFFFFFF);
        sl.addState(new int[]{android.R.attr.state_focused}, on);
        sl.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        f.setForeground(sl);

        f.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? FOCUS_SCALE : 1f).scaleY(has ? FOCUS_SCALE : 1f)
                    .setDuration(120).start();
            v.setTranslationZ(has ? u * 0.8f : 0f);
        });
    }

    // ------------------------------------------------- Экран «Все приложения»

    private void openAll() {
        if (overlay != null) return;

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setBackgroundColor(0xF0080D1C);
        overlay.setClickable(true);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(px(5f), px(3f), px(5f), px(1f));
        head.addView(text("Все приложения", 3.4f, Color.WHITE));
        head.addView(text("Удерживайте OK на приложении, чтобы добавить или убрать его из избранного",
                1.5f, 0x99FFFFFF));
        overlay.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setPadding(px(4.4f), px(1.5f), px(4.4f), px(3f));

        GridLayout gl = new GridLayout(this);
        gl.setColumnCount(ALL_COLUMNS);
        gl.setClipChildren(false);

        int cw = px(17f), ch = px(10.4f), m = px(0.6f);
        for (final App a : apps) {
            final FrameLayout t = tile(a, cw, ch, false);

            final TextView star = text("★", 2.2f, 0xFFFFD54F);
            FrameLayout.LayoutParams sp2 = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.END);
            sp2.setMargins(0, px(0.4f), px(0.8f), 0);
            t.addView(star, sp2);
            star.setVisibility(isFav(a.pkg) ? View.VISIBLE : View.GONE);

            t.setOnClickListener(v -> launch(a));
            t.setOnLongClickListener(v -> {
                boolean now = toggleFav(a);
                star.setVisibility(now ? View.VISIBLE : View.GONE);
                toast(now ? "Добавлено в избранное: " + a.label
                          : "Убрано из избранного: " + a.label);
                return true;
            });

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cw;
            lp.height = ch;
            lp.setMargins(m, m, m, m);
            gl.addView(t, lp);
        }

        sv.addView(gl, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        overlay.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Пока открыт список, фокус не должен «уходить» в ряд под ним
        rowScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        if (gl.getChildCount() > 0) gl.getChildAt(0).requestFocus();
    }

    private void closeAll() {
        if (overlay == null) return;
        root.removeView(overlay);
        overlay = null; // освобождаем память
        rowScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        refreshRow(Integer.MAX_VALUE); // фокус на плитку «Все приложения»
    }

    // ------------------------------------------------------ Избранное

    private List<String> getFavs() {
        String s = prefs.getString("favs", "");
        List<String> l = new ArrayList<String>();
        if (!s.isEmpty()) {
            for (String x : s.split("\n")) if (!x.isEmpty()) l.add(x);
        }
        return l;
    }

    private void saveFavs(List<String> l) {
        prefs.edit().putString("favs", TextUtils.join("\n", l)).apply();
    }

    private boolean isFav(String pkg) { return getFavs().contains(pkg); }

    private boolean toggleFav(App a) {
        List<String> l = getFavs();
        boolean now;
        if (l.contains(a.pkg)) { l.remove(a.pkg); now = false; }
        else { l.add(a.pkg); now = true; }
        saveFavs(l);
        return now;
    }

    private App find(String pkg) {
        for (App a : apps) if (a.pkg.equals(pkg)) return a;
        return null;
    }

    private void launch(App a) {
        try { startActivity(a.intent); }
        catch (Exception e) { toast("Не удалось открыть " + a.label); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------ Загрузка приложений

    private void loadApps() {
        if (loading) return;
        loading = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final List<App> result = queryApps();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        loading = false;
                        StringBuilder sb = new StringBuilder();
                        for (App a : result) sb.append(a.pkg).append('|').append(a.label).append(';');
                        String sig = sb.toString();
                        if (sig.equals(lastSig)) return; // ничего не изменилось
                        lastSig = sig;
                        apps.clear();
                        apps.addAll(result);

                        if (!prefs.contains("favs")) { // первый запуск: заполняем избранное
                            List<String> favs = new ArrayList<String>();
                            for (String p : DEFAULT_FAVS) if (find(p) != null) favs.add(p);
                            saveFavs(favs);
                        }
                        if (overlay == null) refreshRow(0);
                    }
                });
            }
        }).start();
    }

    private List<App> queryApps() {
        List<App> out = new ArrayList<App>();
        Set<String> seen = new HashSet<String>();
        String[] cats = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};

        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.ai = ri.activityInfo;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                a.color = dominant(a.icon);
                a.intent = new Intent(Intent.ACTION_MAIN)
                        .addCategory(cat)
                        .setComponent(new ComponentName(pkg, ri.activityInfo.name))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                out.add(a);
            }
        }
        final Collator col = Collator.getInstance();
        Collections.sort(out, new Comparator<App>() {
            @Override public int compare(App x, App y) { return col.compare(x.label, y.label); }
        });
        return out;
    }

    // Средний цвет иконки (для подкраски плитки)
    private static int dominant(Drawable d) {
        final int fallback = 0xFF2A3350;
        try {
            Drawable.ConstantState cs = d.getConstantState();
            Drawable c = (cs != null) ? cs.newDrawable().mutate() : d;
            Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            c.setBounds(0, 0, 16, 16);
            c.draw(cv);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int p = b.getPixel(x, y);
                    if (Color.alpha(p) < 128) continue;
                    r += Color.red(p); g += Color.green(p); bl += Color.blue(p); n++;
                }
            }
            b.recycle();
            if (n == 0) return fallback;
            return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static int mix(int color, int base, float t) {
        int r = Math.round(Color.red(color) * t + Color.red(base) * (1 - t));
        int g = Math.round(Color.green(color) * t + Color.green(base) * (1 - t));
        int b = Math.round(Color.blue(color) * t + Color.blue(base) * (1 - t));
        return Color.rgb(r, g, b);
    }

    // --------------------------------------------------- Жизненный цикл

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        if (!firstStart) loadApps(); // вернулись на главный экран: проверим, не менялся ли список
        firstStart = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Нажатие Home: закрыть список и вернуться к началу ряда
        if (overlay != null) closeAll();
        rowScroll.scrollTo(0, 0);
        if (row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) closeAll(); // на главном экране «Назад» ничего не делает
    }

    // ------------------------------------------------ Свои лёгкие View

    // Фон: градиент и три плавные волны. Рисуется один раз, без анимаций.
    private static class WaveView extends View {
        private final Paint bgP = new Paint();
        private final Paint fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lineP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path[] lines = new Path[3];
        private final Path[] fills = new Path[3];
        private final Shader[] fillShaders = new Shader[3];
        private final Shader[] lineShaders = new Shader[3];
        private int vw, vh;

        WaveView(Context c) {
            super(c);
            lineP.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            vw = w;
            vh = h;
            bgP.setShader(new LinearGradient(0, 0, w, h,
                    new int[]{BG_1, BG_2, BG_3}, new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));
            lineP.setStrokeWidth(Math.max(2f, w * 0.0028f));

            for (int i = 0; i < 3; i++) {
                float s = h * (0.60f + 0.075f * i);
                float e = h * (0.30f + 0.09f * i);
                float amp = h * 0.07f * (i % 2 == 0 ? 1 : -1);

                Path p = new Path();
                p.moveTo(0, s);
                p.cubicTo(w * 0.30f, s + amp, w * 0.58f, e - amp, w, e);
                Path f = new Path(p);
                f.lineTo(w, h);
                f.lineTo(0, h);
                f.close();
                lines[i] = p;
                fills[i] = f;

                int rgb = WAVE_COLORS[i] & 0x00FFFFFF;
                fillShaders[i] = new LinearGradient(0, e, 0, h,
                        rgb | 0x30000000, rgb, Shader.TileMode.CLAMP);
                lineShaders[i] = new LinearGradient(0, 0, w, 0,
                        new int[]{rgb, rgb | 0xAA000000, rgb | 0x66000000},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawRect(0, 0, vw, vh, bgP);
            for (int i = 0; i < 3; i++) {
                if (fills[i] == null) continue;
                fillP.setShader(fillShaders[i]);
                c.drawPath(fills[i], fillP);
                lineP.setShader(lineShaders[i]);
                c.drawPath(lines[i], lineP);
            }
        }
    }

    // Иконка «сетка» для плитки «Все приложения»
    private static class GridIconView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridIconView(Context c) {
            super(c);
            p.setColor(0xF2FFFFFF);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float g = w * 0.1f;
            float s = (w - g) / 2f;
            float r = s * 0.24f;
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < 2; j++) {
                    float x = j * (s + g), y = i * (s + g);
                    c.drawRoundRect(x, y, x + s, y + s, r, r, p);
                }
            }
        }
    }
}
