package com.betsalel.voixlive;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

public final class VoiceProcessingService extends Service {
    public static final String ACTION_START = "com.betsalel.voixlive.START";
    public static final String ACTION_STOP = "com.betsalel.voixlive.STOP";
    public static final String ACTION_UPDATE = "com.betsalel.voixlive.UPDATE";
    public static final String ACTION_STATUS = "com.betsalel.voixlive.STATUS";
    public static final String EXTRA_STRENGTH = "strength";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_ACTIVE = "active";

    private static final String CHANNEL_ID = "voix_live_listening";
    private static final int NOTIFICATION_ID = 41;
    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_SAMPLES = 32_000;
    private static final String MODEL_ASSET = "iter_model_2s.onnx";

    private static volatile boolean running;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile int attenuationPercent = 100;
    private Thread engineThread;

    public static boolean isRunning() { return running; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEngine("Écoute arrêtée");
            return START_NOT_STICKY;
        }
        if (intent != null) attenuationPercent = clamp(intent.getIntExtra(EXTRA_STRENGTH, attenuationPercent), 50, 120);
        if (ACTION_UPDATE.equals(action)) return START_STICKY;
        if (ACTION_START.equals(action) && !active.get()) {
            startForeground(NOTIFICATION_ID, buildNotification("Chargement du modèle local…"));
            active.set(true);
            running = true;
            broadcast("Chargement du modèle local…", true);
            engineThread = new Thread(this::runEngine, "voix-live-engine");
            engineThread.start();
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        active.set(false);
        running = false;
        super.onDestroy();
    }

    private void runEngine() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRecord recorder = null;
        AudioTrack player = null;
        OrtSession session = null;
        Thread captureThread = null;
        AudioManager audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw new IllegalStateException("Autorisation du micro refusée");
            }
            if (!hasHeadphones(this)) throw new IllegalStateException("Branche des écouteurs avant d’activer");
            float[][] enrollment = VoiceProfileStore.loadFloat(this);
            File model = materializeModel();
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setIntraOpNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1)));
            session = environment.createSession(model.getAbsolutePath(), options);

            recorder = createRecorder();
            player = createPlayer();
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            ArrayBlockingQueue<short[]> frames = new ArrayBlockingQueue<>(4);
            AudioRecord finalRecorder = recorder;
            captureThread = new Thread(() -> capture(finalRecorder, frames), "voix-live-capture");
            recorder.startRecording();
            player.play();
            captureThread.start();
            updateNotification("Écoute filtrée active");
            broadcast("Écoute active · remplissage du tampon…", true);

            int slowFrames = 0;
            long processed = 0;
            while (active.get()) {
                short[] pcm = frames.poll(1, TimeUnit.SECONDS);
                if (pcm == null) continue;
                long started = System.nanoTime();
                short[] filtered = filterFrame(session, pcm, enrollment, attenuationPercent / 100.0f);
                long inferenceMs = (System.nanoTime() - started) / 1_000_000L;
                int written = 0;
                while (active.get() && written < filtered.length) {
                    int count = player.write(filtered, written, filtered.length - written, AudioTrack.WRITE_BLOCKING);
                    if (count < 0) throw new IllegalStateException("Sortie audio impossible : " + count);
                    written += count;
                }
                processed++;
                slowFrames = inferenceMs > 2_100 ? slowFrames + 1 : 0;
                String state = slowFrames >= 3
                        ? "Téléphone trop lent · quelques blocs sont sautés"
                        : "Voix atténuée · délai ≈ " + ((2_000 + inferenceMs) / 100) / 10.0 + " s · bloc " + processed;
                broadcast(state, true);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            broadcast("Erreur : " + friendly(error), false);
        } finally {
            active.set(false);
            running = false;
            if (captureThread != null) captureThread.interrupt();
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
                recorder.release();
            }
            if (player != null) {
                try { player.stop(); } catch (Exception ignored) {}
                player.release();
            }
            if (session != null) {
                try { session.close(); } catch (Exception ignored) {}
            }
            audioManager.abandonAudioFocus(null);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    private void capture(AudioRecord recorder, ArrayBlockingQueue<short[]> frames) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            while (active.get() && !Thread.currentThread().isInterrupted()) {
                short[] frame = new short[FRAME_SAMPLES];
                int offset = 0;
                while (active.get() && offset < frame.length) {
                    int read = recorder.read(frame, offset, frame.length - offset, AudioRecord.READ_BLOCKING);
                    if (read < 0) throw new IllegalStateException("Erreur micro : " + read);
                    offset += read;
                }
                if (!active.get()) return;
                if (!frames.offer(frame)) {
                    frames.poll();
                    frames.offer(frame);
                }
            }
        } catch (Throwable error) {
            active.set(false);
            broadcast("Erreur micro : " + friendly(error), false);
        }
    }

    private short[] filterFrame(OrtSession session, short[] pcm, float[][] enrollment, float strength) throws Exception {
        float[][] mixture = new float[1][FRAME_SAMPLES];
        float mixPeak = 1e-6f;
        for (int i = 0; i < FRAME_SAMPLES; i++) {
            float value = pcm[i] / 32768.0f;
            mixture[0][i] = value;
            mixPeak = Math.max(mixPeak, Math.abs(value));
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OnnxTensor mixTensor = OnnxTensor.createTensor(env, mixture);
             OnnxTensor enrollmentTensor = OnnxTensor.createTensor(env, enrollment);
             OnnxTensor lengthTensor = OnnxTensor.createTensor(env, new float[]{VoiceProfileStore.ENROLLMENT_SAMPLES})) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("mixture", mixTensor);
            inputs.put("enrollment", enrollmentTensor);
            inputs.put("enrollment_length", lengthTensor);
            try (OrtSession.Result result = session.run(inputs)) {
                float[][] target = (float[][]) result.get(0).getValue();
                float targetPeak = 1e-6f;
                for (float value : target[0]) targetPeak = Math.max(targetPeak, Math.abs(value));
                float scale = mixPeak / targetPeak;
                short[] output = new short[FRAME_SAMPLES];
                for (int i = 0; i < FRAME_SAMPLES; i++) {
                    float residual = mixture[0][i] - strength * target[0][i] * scale;
                    output[i] = (short) Math.round(softLimit(residual) * 32767.0f);
                }
                smoothStart(output);
                return output;
            }
        }
    }

    private static float softLimit(float value) {
        if (value > 1f || value < -1f) return (float) Math.tanh(value);
        return value;
    }

    private static void smoothStart(short[] output) {
        int fade = Math.min(160, output.length);
        short first = output[0];
        for (int i = 0; i < fade; i++) {
            float amount = i / (float) fade;
            output[i] = (short) Math.round(first * (1f - amount) + output[i] * amount);
        }
    }

    private AudioRecord createRecorder() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME_SAMPLES * 2));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            throw new IllegalStateException("Le micro 16 kHz n’est pas disponible");
        }
        return record;
    }

    private AudioTrack createPlayer() {
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        AudioTrack track = new AudioTrack(attributes, format, FRAME_SAMPLES * 4,
                AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("Sortie audio 16 kHz indisponible");
        }
        return track;
    }

    private File materializeModel() throws Exception {
        File destination = new File(getFilesDir(), MODEL_ASSET);
        if (destination.isFile() && destination.length() > 40_000_000L) return destination;
        File temporary = new File(getFilesDir(), MODEL_ASSET + ".tmp");
        try (InputStream input = getAssets().open(MODEL_ASSET);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
        }
        if (!temporary.renameTo(destination)) throw new IllegalStateException("Installation du modèle impossible");
        return destination;
    }

    private void stopEngine(String message) {
        active.set(false);
        running = false;
        if (engineThread != null) engineThread.interrupt();
        broadcast(message, false);
    }

    private void broadcast(String message, boolean isActive) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_MESSAGE, message).putExtra(EXTRA_ACTIVE, isActive);
        sendBroadcast(intent);
    }

    private Notification buildNotification(String message) {
        Intent stop = new Intent(this, VoiceProcessingService.class).setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent openIntent = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle("Voix Live").setContentText(message)
                .setOngoing(true).setContentIntent(openIntent)
                .addAction(new Notification.Action.Builder(null, "Arrêter", stopIntent).build()).build();
    }

    private void updateNotification(String message) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Écoute filtrée", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Indique lorsque le micro et le filtre vocal sont actifs");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
    }

    public static boolean hasHeadphones(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        try {
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        || type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET)) return true;
            }
        } catch (SecurityException ignored) {}
        return false;
    }

    private static String friendly(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? error.getClass().getSimpleName() : value;
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
