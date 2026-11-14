package com.painel.flutuante;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {

    private final Shizuku.OnRequestPermissionResultListener permListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, final int grantResult) {
                    if (requestCode != ShizukuHelper.REQ_CODE) return;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                                abrirFlutuante();
                            } else {
                                toast("Shizuku negado: abrindo sem efeitos reais");
                                iniciar();
                            }
                        }
                    });
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        float d = getResources().getDisplayMetrics().density;

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        Button btn = new Button(this);
        btn.setText("ABRIR FLUTUANTE");
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF121212);
        bg.setCornerRadius(12 * d);
        bg.setStroke((int) (1 * d + 0.5f), 0xFF3A3A3A);
        btn.setBackground(bg);
        int ph = (int) (36 * d), pv = (int) (18 * d);
        btn.setPadding(ph, pv, ph, pv);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                abrirFlutuante();
            }
        });

        root.addView(btn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        setContentView(root);

        try {
            Shizuku.addRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) { }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeRequestPermissionResultListener(permListener);
        } catch (Throwable ignored) { }
        super.onDestroy();
    }

    private void abrirFlutuante() {
        if (!Settings.canDrawOverlays(this)) {
            if (ShizukuHelper.isGranted()) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        ShizukuHelper.grantOverlay(MainActivity.this);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (Settings.canDrawOverlays(MainActivity.this)) {
                                    abrirFlutuante();
                                } else {
                                    pedirOverlayManual();
                                }
                            }
                        });
                    }
                }).start();
            } else {
                pedirOverlayManual();
            }
            return;
        }

        int r = ShizukuHelper.ensurePermission();
        if (r == 1) {
            toast("Autorize o Painel na janela do Shizuku");
            return;
        }
        if (r == 2) {
            toast("Shizuku negado em definitivo: autorize em Shizuku > Painel. Abrindo sem efeitos reais");
        } else if (r == -1) {
            toast("Shizuku parado: abrindo sem efeitos reais");
        }
        iniciar();
    }

    private void pedirOverlayManual() {
        toast("Permita 'Exibir sobre outros apps' e toque de novo");
        startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())));
    }

    private void iniciar() {
        Intent i = new Intent(this, FloatingService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        moveTaskToBack(true);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
