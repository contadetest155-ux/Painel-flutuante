package com.painel.flutuante;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

public final class ShizukuHelper {

    public static final int REQ_CODE = 7001;
    public static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";

    private ShizukuHelper() { }

    public static final class Res {
        public final int code;
        public final String out;

        Res(int code, String out) {
            this.code = code;
            this.out = out;
        }

        public boolean ok() {
            return code == 0;
        }
    }

    public static boolean isRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isGranted() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 0 = já concedido | 1 = pedido enviado (resposta vem por listener)
     * 2 = negado em definitivo (abrir o app Shizuku) | -1 = Shizuku parado/indisponível
     */
    public static int ensurePermission() {
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) return -1;
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return 0;
            if (Shizuku.shouldShowRequestPermissionRationale()) return 2;
            Shizuku.requestPermission(REQ_CODE);
            return 1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 0 = root, 2000 = shell (adb), -1 = indisponível */
    public static int uid() {
        try {
            return Shizuku.getUid();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean openShizukuApp(Context c) {
        Intent i = c.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
        return true;
    }

    /** Escapa texto para uso entre aspas simples no shell. */
    public static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Executa um comando com a identidade do Shizuku. NUNCA chamar na thread principal. */
    public static Res sh(String cmd) {
        if (!isGranted()) return new Res(-1, "Shizuku sem permissão");
        Process p = null;
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess",
                    String[].class, String[].class, String.class);
            m.setAccessible(true);
            p = (Process) m.invoke(null, new Object[]{
                    new String[]{"sh", "-c", "(" + cmd + ") 2>&1"}, null, null});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            int code = p.waitFor();
            return new Res(code, sb.toString().trim());
        } catch (Throwable t) {
            return new Res(-2, String.valueOf(t));
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) { }
            }
        }
    }

    /** Concede "Exibir sobre outros apps" sem abrir a tela de configurações. */
    public static boolean grantOverlay(Context c) {
        return sh("appops set " + c.getPackageName() + " SYSTEM_ALERT_WINDOW allow").ok();
    }

    /** 0 = já estava ativo | 1 = ativado agora | -1 = falhou */
    public static int enableAccessibility(Context c) {
        String pkg = c.getPackageName();
        String full = pkg + "/" + PanicAccessibilityService.class.getName();
        String shortForm = pkg + "/." + PanicAccessibilityService.class.getSimpleName();
        String cur = Settings.Secure.getString(c.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (cur != null && (cur.contains(full) || cur.contains(shortForm))) return 0;
        String next = (cur == null || cur.isEmpty()) ? full : cur + ":" + full;
        boolean a = sh("settings put secure enabled_accessibility_services " + q(next)).ok();
        boolean b = sh("settings put secure accessibility_enabled 1").ok();
        return (a && b) ? 1 : -1;
    }
              }
