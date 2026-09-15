package com.betsalel.voixlive;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQUEST_PERMISSIONS = 1001;
    private static final int SAMPLE_RATE = 16_000;
    private static final int PROFILE_SECONDS = 10;

    private final int bg = Color.rgb(10, 13, 18);
    private final int card = Color.rgb(23, 28, 36);
    private final int text = Color.rgb(244, 246, 250);
    private final int muted = Color.rgb(159, 169, 185);
    private final int accent = Color.rgb(112, 232, 190);
    private final int danger = Color.rgb(255, 115, 128);

    private TextView profileStatus;
    private TextView liveStatus;
    private TextView strengthLabel;
    private TextView routeStatus;
    private Button recordButton;
    private Button liveButton;
    private SeekBar strengthSeek;
    private volatile boolean recording;

    private final BroadcastReceiver serviceReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(VoiceProcessingService.EXTRA_MESSAGE);
            boolean active = intent.getBooleanExtra(VoiceProcessingService.EXTRA_ACTIVE, false);
            if (message != null) liveStatus.setText(message);
            liveButton.setText(active ? "ARRÊTER L’ÉCOUTE" : "ACTIVER L’ÉCOUTE");
            liveButton.setBackground(makeBackground(active ? danger : accent, 24));
            liveButton.setTextColor(Color.rgb(8, 12, 15));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(bg);
        window.setNavigationBarColor(bg);
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildUi());
        requestNeededPermissions();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(VoiceProcessingService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(serviceReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(serviceReceiver, filter);
        refreshState();
    }

    @Override
    protected void onStop() {
        unregisterReceiver(serviceReceiver);
        super.onStop();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(bg);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(32), dp(22), dp(30));
        scroll.addView(root, new ScrollView.LayoutParams(-1, -2));

        TextView eyebrow = label("VOIX LIVE · LOCAL", 13, accent);
        eyebrow.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(eyebrow);
        TextView titleView = label("Entendre le monde,\nsans ta propre voix.", 34, text);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setLineSpacing(dp(2), 1f);
        root.addView(titleView, margins(-1, -2, 0, 10, 0, 0));
        TextView intro = label("Ton téléphone reconnaît ta voix et la retire du son envoyé à tes écouteurs. Le traitement reste sur l’appareil.", 16, muted);
        intro.setLineSpacing(dp(3), 1f);
        root.addView(intro, margins(-1, -2, 0, 0, 0, 24));

        LinearLayout profileCard = cardLayout();
        profileCard.addView(sectionTitle("1 · PROFIL VOCAL"));
        profileStatus = label("Aucun profil enregistré", 16, text);
        profileCard.addView(profileStatus, margins(-1, -2, 0, 8, 0, 14));
        recordButton = actionButton("ENREGISTRER MA VOIX · 10 S", false);
        recordButton.setOnClickListener(v -> toggleProfileRecording());
        profileCard.addView(recordButton);
        TextView profileHint = label("Parle naturellement, seul et dans une pièce calme. L’app gardera automatiquement les 4 secondes les plus nettes.", 13, muted);
        profileHint.setLineSpacing(dp(2), 1f);
        profileCard.addView(profileHint, margins(-1, -2, 0, 12, 0, 0));
        root.addView(profileCard, margins(-1, -2, 0, 0, 0, 14));

        LinearLayout liveCard = cardLayout();
        liveCard.addView(sectionTitle("2 · ÉCOUTE FILTRÉE"));
        routeStatus = label("🎧 Branche des écouteurs", 15, muted);
        liveCard.addView(routeStatus, margins(-1, -2, 0, 8, 0, 8));
        strengthLabel = label("Atténuation : 100 %", 15, text);
        liveCard.addView(strengthLabel);
        strengthSeek = new SeekBar(this);
        strengthSeek.setMax(70);
        strengthSeek.setProgress(50);
        strengthSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = progress + 50;
                strengthLabel.setText(String.format(Locale.FRANCE, "Atténuation : %d %%", value));
                if (fromUser && VoiceProcessingService.isRunning()) sendServiceAction(VoiceProcessingService.ACTION_UPDATE, value);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        liveCard.addView(strengthSeek, margins(-1, dp(42), 0, 0, 0, 10));
        liveButton = actionButton("ACTIVER L’ÉCOUTE", true);
        liveButton.setOnClickListener(v -> toggleLive());
        liveCard.addView(liveButton);
        liveStatus = label("Prêt après l’enregistrement du profil", 14, muted);
        liveStatus.setGravity(Gravity.CENTER_HORIZONTAL);
        liveCard.addView(liveStatus, margins(-1, -2, 0, 12, 0, 0));
        root.addView(liveCard, margins(-1, -2, 0, 0, 0, 18));

        TextView reality = label("À savoir · Le modèle travaille par blocs de 2 secondes. Le premier son filtré arrive donc après environ 3 secondes. Il retire ta voix du flux électronique des écouteurs, mais pas la voix que tu entends naturellement par l’air et les os du crâne.", 13, muted);
        reality.setLineSpacing(dp(2), 1f);
        root.addView(reality);
        return scroll;
    }

    private void toggleProfileRecording() {
        if (recording) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            return;
        }
        if (VoiceProcessingService.isRunning()) {
            Toast.makeText(this, "Arrête d’abord l’écoute.", Toast.LENGTH_SHORT).show();
            return;
        }
        recording = true;
        recordButton.setEnabled(false);
        new Thread(this::recordProfile, "voice-profile-recorder").start();
    }

    private void recordProfile() {
        int total = SAMPLE_RATE * PROFILE_SECONDS;
        short[] samples = new short[total];
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord recorder = null;
        int offset = 0;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("Micro indisponible");
            recorder.startRecording();
            while (offset < total) {
                int read = recorder.read(samples, offset, Math.min(2048, total - offset), AudioRecord.READ_BLOCKING);
                if (read < 0) throw new IllegalStateException("Lecture du micro impossible : " + read);
                offset += read;
                final int secondsLeft = Math.max(0, PROFILE_SECONDS - offset / SAMPLE_RATE);
                runOnUiThread(() -> {
                    profileStatus.setText("Parle maintenant… " + secondsLeft + " s");
                    recordButton.setText("ENREGISTREMENT EN COURS");
                });
            }
            VoiceProfileStore.saveBestSegment(this, samples);
            runOnUiThread(() -> {
                Toast.makeText(this, "Profil vocal enregistré", Toast.LENGTH_SHORT).show();
                refreshState();
            });
        } catch (Exception error) {
            runOnUiThread(() -> {
                profileStatus.setText("Échec : " + error.getMessage());
                profileStatus.setTextColor(danger);
            });
        } finally {
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
            }
            recording = false;
            runOnUiThread(() -> {
                recordButton.setEnabled(true);
                recordButton.setText("RÉENREGISTRER MA VOIX · 10 S");
            });
        }
    }

    private void toggleLive() {
        if (VoiceProcessingService.isRunning()) {
            sendServiceAction(VoiceProcessingService.ACTION_STOP, currentStrength());
            return;
        }
        if (!VoiceProfileStore.exists(this)) {
            Toast.makeText(this, "Enregistre d’abord ta voix pendant 10 secondes.", Toast.LENGTH_LONG).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestNeededPermissions();
            return;
        }
        sendServiceAction(VoiceProcessingService.ACTION_START, currentStrength());
        liveStatus.setText("Chargement du modèle local…");
    }

    private void sendServiceAction(String action, int strength) {
        Intent intent = new Intent(this, VoiceProcessingService.class).setAction(action)
                .putExtra(VoiceProcessingService.EXTRA_STRENGTH, strength);
        if (Build.VERSION.SDK_INT >= 26 && VoiceProcessingService.ACTION_START.equals(action)) startForegroundService(intent);
        else startService(intent);
    }

    private int currentStrength() { return strengthSeek.getProgress() + 50; }

    private void refreshState() {
        boolean hasProfile = VoiceProfileStore.exists(this);
        profileStatus.setText(hasProfile ? "✓ Profil enregistré sur ce téléphone" : "Aucun profil enregistré");
        profileStatus.setTextColor(hasProfile ? accent : text);
        recordButton.setText(hasProfile ? "RÉENREGISTRER MA VOIX · 10 S" : "ENREGISTRER MA VOIX · 10 S");
        boolean active = VoiceProcessingService.isRunning();
        liveButton.setText(active ? "ARRÊTER L’ÉCOUTE" : "ACTIVER L’ÉCOUTE");
        liveButton.setBackground(makeBackground(active ? danger : accent, 24));
        boolean headphones = VoiceProcessingService.hasHeadphones(this);
        routeStatus.setText(headphones ? "🎧 Écouteurs détectés" : "🎧 Branche des écouteurs avant d’activer");
        routeStatus.setTextColor(headphones ? accent : muted);
    }

    private void requestNeededPermissions() {
        List<String> wanted = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) wanted.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!wanted.isEmpty()) requestPermissions(wanted.toArray(new String[0]), REQUEST_PERMISSIONS);
    }

    private LinearLayout cardLayout() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18), dp(18), dp(18), dp(18));
        layout.setBackground(makeBackground(card, 22));
        return layout;
    }

    private TextView sectionTitle(String value) {
        TextView view = label(value, 13, accent);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private Button actionButton(String value, boolean primary) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(14), dp(4), dp(14), dp(4));
        button.setMinHeight(dp(58));
        button.setTextColor(primary ? Color.rgb(8, 12, 15) : text);
        button.setBackground(makeBackground(primary ? accent : Color.rgb(43, 51, 63), 18));
        return button;
    }

    private TextView label(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    private GradientDrawable makeBackground(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private LinearLayout.LayoutParams margins(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
