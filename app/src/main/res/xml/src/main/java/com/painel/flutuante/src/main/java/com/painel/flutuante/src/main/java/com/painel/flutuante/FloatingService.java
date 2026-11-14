package com.painel.flutuante;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class FloatingService extends Service {

    // Lidos pelo serviço de acessibilidade
    public static volatile boolean running = false;
    public static volatile boolean minimized = false;
    public static volatile FloatingService instance = null;

    private static final String CH_ID = "painel_ch";
    private static final int PANEL_DP = 300;
    private static final int MINI_DP = 44;
    private static final long LOG_INTERVAL_MS = 30000L;
    private static final int CACHE_EVERY_TICKS = 30;     // 30 x 30s = 15 min
    private static final String ANIM_SCALE = "0.5";
    private static final String NUL = "__null__";

    // ---- card de limpeza (atalho de pânico) ----
    private static final String[] WIPE_STEPS = {
            "Encerrando serviços",
            "Limpando logcat",
            "Apagando cache do app",
            "Restaurando configurações do sistema",
            "Removendo da lista de recentes"
    };
    private static final String WIPE_FINAL_LABEL = "SEM RASTROS";
    private static final long WIPE_STEP_MS = 260L;
    private static final long WIPE_HOLD_MS = 900L;

    private static final String[][] STAB = {
            {"global", "window_animation_scale"},
            {"global", "transition_animation_scale"},
            {"global", "animator_duration_scale"}};
    private static final String[][] ACCEL = {
            {"system", "mouse_pointer_acceleration_enabled"},
            {"system", "touchpad_acceleration_enabled"}};

    private WindowManager wm;
    private Context ui;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io;

    private LinearLayout panel;
    private TextView mini;
    private TextView stat;
    private WindowManager.LayoutParams panelLp;
    private WindowManager.LayoutParams miniLp;
    private boolean panelAttached = false;
    private boolean miniAttached = false;

    private WindowManager.LayoutParams wipeLp;
    private View wipeRoot;
    private boolean wipeAttached = false;
    private volatile boolean panicking = false;

    private Tg tgStab, tgLogs, tgH, tgV, tgOpt;
    private TextView smoothLabel;
    private int smooth = 0;
    private int logTick = 0;

    private HandlerThread fastThread;

    private final Shizuku.OnBinderReceivedListener binderUp =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    refreshStatus();
                    autoSetup();
                }
            };
    private final Shizuku.OnBinderDeadListener binderDown =
            new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    refreshStatus();
                }
            };
    private final Shizuku.OnRequestPermissionResultListener permResult =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    refreshStatus();
                    autoSetup();
                }
            };

    private final Runnable logLoop = new Runnable() {
        @Override
        public void run() {
            final boolean cache = (logTick++ % CACHE_EVERY_TICKS) == 0;
            io(new Runnable() {
                @Override
                public void run() {
                    cleanLogs();
                    if (cache) trimCaches();
                }
            });
            main.postDelayed(this, LOG_INTERVAL_MS);
        }
    };

    // ------------------------------------------------------------ ciclo de vida

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);
        io = Executors.newSingleThreadExecutor();
        smooth = prefs.getInt("smooth", 0);
        instance = this;
        try {
            Shizuku.addBinderReceivedListenerSticky(binderUp);
            Shizuku.addBinderDeadListener(binderDown);
            Shizuku.addRequestPermissionResultListener(permResult);
        } catch (Throwable ignored) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        if (panel == null) {
            if (!Settings.canDrawOverlays(this)) {
                toast("Sem permissão de sobreposição");
                stopSelf();
                return START_NOT_STICKY;
            }
            try {
                buildUi();
                wm.addView(panel, panelLp);
                panelAttached = true;
                running = true;
                minimized = false;
            } catch (Exception e) {
                toast("Erro ao abrir flutuante: " + e.getMessage());
                stopSelf();
                return START_NOT_STICKY;
            }
            refreshStatus();
            autoSetup();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        minimized = false;
        instance = null;
        try {
            Shizuku.removeBinderReceivedListener(binderUp);
            Shizuku.removeBinderDeadListener(binderDown);
            Shizuku.removeRequestPermissionResultListener(permResult);
        } catch (Throwable ignored) { }
        main.removeCallbacksAndMessages(null);
        stopFast();
        if (io != null) io.shutdownNow();
        removeViews();
        try { if (wipeAttached) wm.removeView(wipeRoot); } catch (Exception ignored) { }
        wipeAttached = false;
        super.onDestroy();
    }

    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(
                    CH_ID, "Painel", NotificationManager.IMPORTANCE_MIN));
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.ic_menu_info_details);
        b.setContentTitle("Painel ativo");
        return b.build();
    }

    // ------------------------------------------------------------ interface

    private void buildUi() {
        ui = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);

        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int fl = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

        panelLp = new WindowManager.LayoutParams(dp(PANEL_DP), dp(PANEL_DP), type, fl,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = dp(30);
        panelLp.y = dp(100);

        miniLp = new WindowManager.LayoutParams(dp(MINI_DP), dp(MINI_DP), type, fl,
                PixelFormat.TRANSLUCENT);
        miniLp.gravity = Gravity.TOP | Gravity.START;

        // ---- painel
        panel = new LinearLayout(ui);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(box(0xFF121212, 0xFF3A3A3A, 14, 1));
        panel.setPadding(dp(8), dp(6), dp(8), dp(8));

        // cabeçalho (arrasta o painel)
        LinearLayout header = new LinearLayout(ui);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("PAINEL", 12, 0xFF9E9E9E);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        stat = label("● Shizuku", 11, 0xFFD9534F);
        stat.setPadding(dp(6), dp(4), dp(6), dp(4));
        stat.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                io(new Runnable() {
                    @Override
                    public void run() {
                        int r = ShizukuHelper.ensurePermission();
                        if (r == 0) {
                            int u = ShizukuHelper.uid();
                            toast("Shizuku OK (uid " + u + (u == 0 ? " root)" : " shell)"));
                        } else if (r == 1) {
                            toast("Autorize o Painel na janela do Shizuku");
                        } else {
                            if (!ShizukuHelper.openShizukuApp(FloatingService.this)) {
                                toast("App Shizuku não encontrado");
                            }
                        }
                        refreshStatus();
                    }
                });
            }
        });
        header.addView(stat, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button bMin = flatButton("–", 14);
        bMin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                minimize();
            }
        });
        Button bClose = flatButton("X", 12);
        bClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSelf();
            }
        });
        LinearLayout.LayoutParams hb = new LinearLayout.LayoutParams(dp(34), dp(28));
        hb.leftMargin = dp(6);
        header.addView(bMin, hb);
        header.addView(bClose, hb);
        header.setOnTouchListener(new DragListener(panelLp, panel, dp(PANEL_DP), null));
        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));

        // corpo rolável
        ScrollView sv = new ScrollView(ui);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(ui);
        body.setOrientation(LinearLayout.VERTICAL);
        sv.addView(body);
        panel.addView(sv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        tgStab = new Tg("Estabilizar") {
            @Override
            void onChange(final boolean on) {
                if (on) saveProfile();
                if (needShizuku()) return;
                io(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = on ? applySet("stab", STAB, ANIM_SCALE)
                                : restoreSet("stab", STAB);
                        report(on ? "Estabilizar (perfil + animações 0.5x)"
                                : "Animações restauradas", ok);
                    }
                });
            }
        };
        tgLogs = new Tg("Limpar Logs (contínuo)") {
            @Override
            void onChange(boolean on) {
                main.removeCallbacks(logLoop);
                if (on) {
                    logTick = 0;
                    main.post(logLoop);
                    toast(ShizukuHelper.isGranted()
                            ? "Logs do aparelho a cada 30s, cache a cada 15 min"
                            : "Sem Shizuku: limpando só logs/cache do próprio app");
                } else {
                    toast("Limpeza contínua desligada");
                }
            }
        };
        tgH = new Tg("Desativar Aceleração Horizontal") {
            @Override
            void onChange(boolean on) {
                prefs.edit().putBoolean("noAccelH", on).apply();
                syncAccel();
            }
        };
        tgV = new Tg("Desativar Aceleração Vertical") {
            @Override
            void onChange(boolean on) {
                prefs.edit().putBoolean("noAccelV", on).apply();
                syncAccel();
            }
        };
        tgOpt = new Tg("Otimizar Serviço") {
            @Override
            void onChange(final boolean on) {
                if (on) startFast(); else stopFast();
                if (needShizuku()) return;
                io(new Runnable() {
                    @Override
                    public void run() {
                        if (on) {
                            report("Otimizar serviço", optimizeSystem());
                        } else {
                            undoOptimize();
                            toast("Otimização desligada");
                        }
                    }
                });
            }
        };
        tgH.on = prefs.getBoolean("noAccelH", false);
        tgV.on = prefs.getBoolean("noAccelV", false);
        tgH.refresh();
        tgV.refresh();

        addRow(body, tgStab.b, 36);
        addRow(body, tgLogs.b, 36);
        addRow(body, tgH.b, 36);
        addRow(body, tgV.b, 36);

        // slider de suavidade
        smoothLabel = label(smoothText(), 12, 0xFFCCCCCC);
        addRow(body, smoothLabel, 24);
        SeekBar bar = new SeekBar(ui);
        bar.setMax(100);
        bar.setProgress(smooth);
        bar.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return false;
            }
        });
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                smooth = p;
                smoothLabel.setText(smoothText());
            }
            @Override
            public void onStartTrackingTouch(SeekBar s) { }
            @Override
            public void onStopTrackingTouch(SeekBar s) {
                prefs.edit().putInt("smooth", smooth).apply();
            }
        });
        addRow(body, bar, 36);

        addRow(body, tgOpt.b, 36);

        Button apply = flatButton("APLICAR CONFIGURAÇÕES", 12);
        apply.setBackground(box(0xFF1F1F1F, 0xFF8A8A8A, 8, 1));
        apply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyAll();
            }
        });
        addRow(body, apply, 40);

        // ---- quadrado minimizado
        mini = new TextView(ui);
        mini.setText("■");
        mini.setTextSize(14);
        mini.setTextColor(0xFF9E9E9E);
        mini.setGravity(Gravity.CENTER);
        mini.setBackground(box(0xFF121212, 0xFF3A3A3A, 8, 1));
        mini.setOnTouchListener(new DragListener(miniLp, mini, dp(MINI_DP), new Runnable() {
            @Override
            public void run() {
                restore();
            }
        }));
    }

    private String smoothText() {
        return "Suavidade do arraste: " + smooth + "  (0 = cru, 100 = máxima)";
    }

    private void minimize() {
        if (!panelAttached) return;
        miniLp.x = panelLp.x;
        miniLp.y = panelLp.y;
        try { wm.removeView(panel); } catch (Exception ignored) { }
        panelAttached = false;
        try {
            wm.addView(mini, miniLp);
            miniAttached = true;
        } catch (Exception ignored) { }
        minimized = true;
    }

    private void restore() {
        if (!miniAttached) return;
        panelLp.x = clampX(miniLp.x, dp(PANEL_DP));
        panelLp.y = clampY(miniLp.y, dp(PANEL_DP));
        try { wm.removeView(mini); } catch (Exception ignored) { }
        miniAttached = false;
        try {
            wm.addView(panel, panelLp);
            panelAttached = true;
        } catch (Exception ignored) { }
        minimized = false;
        refreshStatus();
    }

    private void removeViews() {
        try { if (panelAttached) wm.removeView(panel); } catch (Exception ignored) { }
        panelAttached = false;
        try { if (miniAttached) wm.removeView(mini); } catch (Exception ignored) { }
        miniAttached = false;
    }

    // ------------------------------------------------------------ arraste com suavidade

    private class DragListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams lp;
        private final View target;
        private final int size;
        private final Runnable onTap;
        private float downX, downY, fx, fy;
        private int startX, startY;
        private boolean moved;

        DragListener(WindowManager.LayoutParams lp, View target, int size, Runnable onTap) {
            this.lp = lp;
            this.target = target;
            this.size = size;
            this.onTap = onTap;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    fx = startX;
                    fy = startY;
                    moved = false;
                    return true;

                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    float slop = ViewConfiguration.get(FloatingService.this).getScaledTouchSlop();
                    if (tgStab != null && tgStab.on) slop *= 1.7f; // "deadzone" do perfil
                    if (!moved && Math.hypot(dx, dy) < slop) return true;
                    moved = true;

                    // 0 = sem filtro (alpha 1) | 100 = suavização máxima (alpha 0.05)
                    float a = 1f - (smooth / 100f) * 0.95f;
                    fx += (startX + dx - fx) * a;
                    fy += (startY + dy - fy) * a;
                    lp.x = clampX((int) fx, size);
                    lp.y = clampY((int) fy, size);
                    try { wm.updateViewLayout(target, lp); } catch (Exception ignored) { }
                    return true;
                }

                case MotionEvent.ACTION_UP:
                    if (!moved && onTap != null) onTap.run();
                    return true;

                default:
                    return true;
            }
        }
    }

    private int clampX(int x, int size) {
        int max = getResources().getDisplayMetrics().widthPixels - size;
        return Math.max(0, Math.min(max, x));
    }

    private int clampY(int y, int size) {
        int max = getResources().getDisplayMetrics().heightPixels - size;
        return Math.max(0, Math.min(max, y));
    }

    // ------------------------------------------------------------ Shizuku: status e setup

    private void refreshStatus() {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (stat == null) return;
                boolean up = ShizukuHelper.isRunning();
                boolean ok = ShizukuHelper.isGranted();
                stat.setText(ok ? "● Shizuku" : (up ? "● autorizar" : "● Shizuku off"));
                stat.setTextColor(ok ? 0xFF3FA55B : (up ? 0xFFE0A030 : 0xFFD9534F));
            }
        });
    }

    private boolean needShizuku() {
        if (ShizukuHelper.isGranted()) return false;
        toast("Shizuku sem permissão: toque no ● do topo do painel");
        refreshStatus();
        return true;
    }

    /** Ativa sozinho o serviço de acessibilidade do atalho de pânico. */
    private void autoSetup() {
        io(new Runnable() {
            @Override
            public void run() {
                if (!ShizukuHelper.isGranted()) return;
                int r = ShizukuHelper.enableAccessibility(FloatingService.this);
                if (r == 1) toast("Atalho de volume ativado via Shizuku");
                else if (r == -1) toast("Não consegui ativar a acessibilidade pelo Shizuku");
            }
        });
    }

    // ------------------------------------------------------------ funções dos botões

    private void saveProfile() {
        prefs.edit()
                .putFloat("trackingresponse", 0.98f)
                .putFloat("touchresponse", 0.90f)
                .putFloat("tracking", 0.88f)
                .putFloat("deadzone", 0.70f)
                .putFloat("stability", 0.99f)
                .apply();
    }

    private boolean sh(String cmd) {
        return ShizukuHelper.sh(cmd).ok();
    }

    private String getSetting(String ns, String key) {
        ShizukuHelper.Res r = ShizukuHelper.sh("settings get " + ns + " " + key);
        String v = r.out == null ? "" : r.out.trim();
        if (!r.ok() || v.isEmpty() || v.equals("null")) return null;
        return v;
    }

    private boolean applySet(String tag, String[][] keys, String value) {
        if (!ShizukuHelper.isGranted()) return false;
        if (!prefs.getBoolean("bk_" + tag, false)) {
            SharedPreferences.Editor e = prefs.edit();
            for (String[] k : keys) {
                String v = getSetting(k[0], k[1]);
                if (value.equals(v)) v = null;
                e.putString("bk_" + tag + "_" + k[1], v == null ? NUL : v);
            }
            e.putBoolean("bk_" + tag, true).apply();
        }
        boolean ok = true;
        for (String[] k : keys) {
            ok &= sh("settings put " + k[0] + " " + k[1] + " " + ShizukuHelper.q(value));
        }
        return ok;
    }

    private boolean restoreSet(String tag, String[][] keys) {
        if (!prefs.getBoolean("bk_" + tag, false)) return true;
        boolean ok = true;
        for (String[] k : keys) {
            String v = prefs.getString("bk_" + tag + "_" + k[1], NUL);
            if (NUL.equals(v)) {
                ok &= sh("settings delete " + k[0] + " " + k[1]);
            } else {
                ok &= sh("settings put " + k[0] + " " + k[1] + " " + ShizukuHelper.q(v));
            }
        }
        prefs.edit().putBoolean("bk_" + tag, false).apply();
        return ok;
    }

    private boolean applyAccel() {
        if (Build.VERSION.SDK_INT < 35) return false;
        return applySet("acc", ACCEL, "0");
    }

    private void syncAccel() {
        final boolean on = tgH.on || tgV.on;
        if (Build.VERSION.SDK_INT < 35) {
            if (on) toast("Este Android não tem o ajuste de aceleração (só mouse/touchpad)");
            return;
        }
        if (needShizuku()) return;
        io(new Runnable() {
            @Override
            public void run() {
                boolean ok = on ? applyAccel() : restoreSet("acc", ACCEL);
                report(on ? "Aceleração desativada (mouse/touchpad)" : "Aceleração restaurada", ok);
            }
        });
    }

    private boolean cleanLogs() {
        boolean ok = false;
        if (ShizukuHelper.isGranted()) {
            ok = sh("logcat -b all -c");
        } else {
            try {
                Runtime.getRuntime().exec(new String[]{"logcat", "-c"}).waitFor();
            } catch (Exception ignored) { }
        }
        deleteChildren(getCacheDir());
        deleteChildren(getExternalCacheDir());
        return ok;
    }

    private boolean trimCaches() {
        return ShizukuHelper.isGranted() && sh("pm trim-caches 999G");
    }

    private boolean optimizeSystem() {
        if (!ShizukuHelper.isGranted()) return false;
        String p = getPackageName();
        boolean ok = sh("cmd deviceidle whitelist +" + p);
        sh("am set-standby-bucket " + p + " active");
        sh("appops set " + p + " RUN_ANY_IN_BACKGROUND allow");
        if (ShizukuHelper.uid() == 0) {
            sh("renice -n -10 -p " + Process.myPid());
        }
        return ok;
    }

    private void undoOptimize() {
        if (!ShizukuHelper.isGranted()) return;
        String p = getPackageName();
        sh("cmd deviceidle whitelist -" + p);
        sh("appops set " + p + " RUN_ANY_IN_BACKGROUND default");
    }

    private void deleteChildren(File dir) {
        if (dir == null) return;
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) deleteChildren(f);
            f.delete();
        }
    }

    private void startFast() {
        if (fastThread != null) return;
        fastThread = new HandlerThread("touch-fast");
        fastThread.start();
        int tid = fastThread.getThreadId();
        try {
            Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Exception e) {
            try { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY); }
            catch (Exception ignored) { }
        }
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY); }
        catch (Exception ignored) { }
    }

    private void stopFast() {
        if (fastThread != null) {
            fastThread.quitSafely();
            fastThread = null;
        }
    }

    private void applyAll() {
        prefs.edit().putInt("smooth", smooth).apply();
        final boolean s = tgStab.on;
        final boolean l = tgLogs.on;
        final boolean a = tgH.on || tgV.on;
        final boolean o = tgOpt.on;
        if (s) saveProfile();
        if (l) {
            main.removeCallbacks(logLoop);
            logTick = 0;
            main.post(logLoop);
        }
        if (o) startFast();
        if ((s || a || o) && needShizuku()) return;
        io(new Runnable() {
            @Override
            public void run() {
                int total = 0, ok = 0;
                if (s) { total++; if (applySet("stab", STAB, ANIM_SCALE)) ok++; }
                if (a) { total++; if (applyAccel()) ok++; }
                if (o) { total++; if (optimizeSystem()) ok++; }
                toast("Aplicado: suavidade " + smooth
                        + (total > 0 ? " | Shizuku " + ok + "/" + total : "")
                        + (l ? " | logs" : ""));
            }
        });
    }

    private void report(String what, boolean ok) {
        toast(what + (ok ? ": OK" : ": falhou (veja o status do Shizuku)"));
    }

    private void io(Runnable r) {
        if (io != null && !io.isShutdown()) io.execute(r);
    }

    // ------------------------------------------------------------ modo stealth (card de limpeza)

    public void triggerPanic() {
        if (panicking) return;
        panicking = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                minimized = false;
                removeViews();
                main.removeCallbacksAndMessages(null);
                stopFast();
                io(new Runnable() {
                    @Override
                    public void run() {
                        cleanLogs();
                        restoreSet("stab", STAB);
                        restoreSet("acc", ACCEL);
                        undoOptimize();
                    }
                });
                showWipeCard();
            }
        });
    }

    private void showWipeCard() {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int fl = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        wipeLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type, fl, PixelFormat.TRANSLUCENT);
        wipeLp.gravity = Gravity.TOP | Gravity.START;

        FrameLayout dim = new FrameLayout(ui);
        dim.setBackgroundColor(0xCC000000);

        LinearLayout card = new LinearLayout(ui);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(0xFF121212, 0xFF2ECC71, 14, 1));
        card.setPadding(dp(20), dp(18), dp(20), dp(18));

        TextView title = label("LIMPEZA DE SISTEMA", 12, 0xFF8A8A8A);
        card.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        final TextView[] lineViews = new TextView[WIPE_STEPS.length];
        for (int i = 0; i < WIPE_STEPS.length; i++) {
            TextView tv = label("· " + WIPE_STEPS[i], 13, 0xFF5A5A5A);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            card.addView(tv, lp);
            lineViews[i] = tv;
        }

        final TextView finalLabel = label("", 20, 0xFF2ECC71);
        finalLabel.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        flp.topMargin = dp(18);
        flp.gravity = Gravity.CENTER_HORIZONTAL;
        card.addView(finalLabel, flp);

        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                dp(250), FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        dim.addView(card, cardLp);

        wipeRoot = dim;
        try {
            wm.addView(wipeRoot, wipeLp);
            wipeAttached = true;
        } catch (Exception e) {
            finishPanic();
            return;
        }

        for (int i = 0; i < lineViews.length; i++) {
            final TextView tv = lineViews[i];
            final String txt = WIPE_STEPS[i];
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tv.setText("✓ " + txt);
                    tv.setTextColor(0xFF2ECC71);
                }
            }, WIPE_STEP_MS * (i + 1));
        }

        final long afterLines = WIPE_STEP_MS * (lineViews.length + 1);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                finalLabel.setText(WIPE_FINAL_LABEL);
            }
        }, afterLines);

        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                finishPanic();
            }
        }, afterLines + WIPE_HOLD_MS);
    }

    private void finishPanic() {
        try { if (wipeAttached) wm.removeView(wipeRoot); } catch (Exception ignored) { }
        wipeAttached = false;
        running = false;
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.AppTask> tasks = am.getAppTasks();
            for (ActivityManager.AppTask t : tasks) {
                t.finishAndRemoveTask();
            }
        } catch (Exception ignored) { }
        stopForeground(true);
        stopSelf();
    }

    // ------------------------------------------------------------ helpers de UI

    private abstract class Tg {
        final String name;
        boolean on = false;
        final Button b;

        Tg(String name) {
            this.name = name;
            b = flatButton(name, 12);
            refresh();
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    on = !on;
                    refresh();
                    onChange(on);
                }
            });
        }

        void refresh() {
            b.setText(name + (on ? "   [ON]" : "   [OFF]"));
            b.setBackground(box(on ? 0xFF1B3A24 : 0xFF2A2A2A,
                    on ? 0xFF3FA55B : 0xFF444444, 8, 1));
        }

        abstract void onChange(boolean on);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable box(int fill, int stroke, int radiusDp, int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(strokeDp), stroke);
        return g;
    }

    private TextView label(String t, int sp, int color) {
        TextView tv = new TextView(ui);
        tv.setText(t);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private Button flatButton(String text, int sp) {
        Button b = new Button(ui);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(sp);
        b.setTextColor(0xFFEEEEEE);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setStateListAnimator(null);
        b.setBackground(box(0xFF2A2A2A, 0xFF444444, 8, 1));
        return b;
    }

    private void addRow(LinearLayout parent, View v, int heightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp));
        lp.topMargin = dp(6);
        parent.addView(v, lp);
    }

    private void toast(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
  }
