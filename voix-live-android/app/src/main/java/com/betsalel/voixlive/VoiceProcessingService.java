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
    // V1.2 favours extraction quality and sustainable phone load: every sample
    // is analysed exactly once in a full four-second iter_model window. Two
    // processed windows are queued before playback to absorb compute jitter.
    private static final int WINDOW_SAMPLES = 64_000;
    private static final int HOP_SAMPLES = 64_000;
    private static final int OUTPUT_OFFSET_SAMPLES = 0;
    private static final int PLAYBACK_PREBUFFER_CHUNKS = 2;
    private static final int MAX_ALIGNMENT_SAMPLES = 1_920; // 120 ms at 16 kHz
    private static final String MODEL_ASSET = "iter_model_4s_10s.onnx";

    private static volatile boolean running;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile int attenuationPercent = 100;
    private volatile int lastAlignmentMs;
    private volatile float lastGain = 1.0f;
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
        Thread playbackThread = null;
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
            ArrayBlockingQueue<short[]> capturedHops = new ArrayBlockingQueue<>(8);
            ArrayBlockingQueue<short[]> filteredHops = new ArrayBlockingQueue<>(6);
            AudioRecord finalRecorder = recorder;
            AudioTrack finalPlayer = player;
            captureThread = new Thread(() -> capture(finalRecorder, capturedHops), "voix-live-capture");
            playbackThread = new Thread(() -> playback(finalPlayer, filteredHops), "voix-live-playback");
            recorder.startRecording();
            captureThread.start();
            playbackThread.start();
            updateNotification("Écoute filtrée continue");
            broadcast("Écoute active · préparation du premier passage de 4 s…", true);

            short[] window = new short[WINDOW_SAMPLES];
            int filledSamples = 0;
            int slowWindows = 0;
            while (active.get()) {
                short[] hop = capturedHops.poll(2, TimeUnit.SECONDS);
                if (hop == null) continue;

                if (filledSamples < WINDOW_SAMPLES) {
                    System.arraycopy(hop, 0, window, filledSamples, HOP_SAMPLES);
                    filledSamples += HOP_SAMPLES;
                    if (filledSamples < WINDOW_SAMPLES) {
                        broadcast("Écoute active · préparation du tampon…", true);
                        continue;
                    }
                } else {
                    System.arraycopy(window, HOP_SAMPLES, window, 0, WINDOW_SAMPLES - HOP_SAMPLES);
                    System.arraycopy(hop, 0, window, WINDOW_SAMPLES - HOP_SAMPLES, HOP_SAMPLES);
                }

                long started = System.nanoTime();
                short[] filteredWindow = filterWindow(session, window, enrollment, attenuationPercent / 100.0f);
                long inferenceMs = (System.nanoTime() - started) / 1_000_000L;

                short[] stableCentre = new short[HOP_SAMPLES];
                System.arraycopy(filteredWindow, OUTPUT_OFFSET_SAMPLES, stableCentre, 0, HOP_SAMPLES);
                if (!filteredHops.offer(stableCentre, 2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("La sortie audio n’arrive pas à suivre");
                }

                slowWindows = inferenceMs > 3_900 ? slowWindows + 1 : 0;
                String state = slowWindows >= 3
                        ? "Téléphone trop lent pour maintenir le flux continu"
                        : "Flux filtré continu · retard ≈ 8–12 s · calcul " + inferenceMs
                        + " ms · alignement " + lastAlignmentMs + " ms · gain "
                        + Math.round(lastGain * 100f) / 100f;
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
            if (playbackThread != null) playbackThread.interrupt();
            if (recorder != null) {
                try { recorder.stop(); } catch (Exception ignored) {}
            }
            if (player != null) {
                try { player.stop(); } catch (Exception ignored) {}
            }
            joinQuietly(captureThread);
            joinQuietly(playbackThread);
            if (recorder != null) recorder.release();
            if (player != null) {
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

    private void capture(AudioRecord recorder, ArrayBlockingQueue<short[]> hops) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            while (active.get() && !Thread.currentThread().isInterrupted()) {
                short[] hop = new short[HOP_SAMPLES];
                int offset = 0;
                while (active.get() && offset < hop.length) {
                    int read = recorder.read(hop, offset, hop.length - offset, AudioRecord.READ_BLOCKING);
                    if (read < 0) throw new IllegalStateException("Erreur micro : " + read);
                    offset += read;
                }
                if (!active.get()) return;
                if (!hops.offer(hop, 1500, TimeUnit.MILLISECONDS))
                    throw new IllegalStateException("Le traitement ne suit plus le microphone");
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            active.set(false);
            broadcast("Erreur micro : " + friendly(error), false);
        }
    }

    private void playback(AudioTrack player, ArrayBlockingQueue<short[]> filteredHops) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            short[][] prebuffer = new short[PLAYBACK_PREBUFFER_CHUNKS][];
            for (int i = 0; i < prebuffer.length; i++) {
                prebuffer[i] = filteredHops.poll(10, TimeUnit.SECONDS);
                if (prebuffer[i] == null || !active.get()) {
                    if (active.get()) {
                        active.set(false);
                        broadcast("Le tampon filtré n’a pas pu démarrer", false);
                    }
                    return;
                }
            }
            player.play();
            for (short[] hop : prebuffer) writeAll(player, hop);
            while (active.get() && !Thread.currentThread().isInterrupted()) {
                short[] hop = filteredHops.poll(2, TimeUnit.SECONDS);
                if (hop != null) writeAll(player, hop);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            active.set(false);
            broadcast("Erreur écouteurs : " + friendly(error), false);
        }
    }

    private void writeAll(AudioTrack player, short[] pcm) {
        int written = 0;
        while (active.get() && written < pcm.length) {
            int count = player.write(pcm, written, pcm.length - written, AudioTrack.WRITE_BLOCKING);
            if (count < 0) throw new IllegalStateException("Sortie audio impossible : " + count);
            written += count;
        }
    }

    private short[] filterWindow(OrtSession session, short[] pcm, float[][] enrollment, float strength) throws Exception {
        float[][] mixture = new float[1][WINDOW_SAMPLES];
        for (int i = 0; i < WINDOW_SAMPLES; i++) {
            float value = pcm[i] / 32768.0f;
            mixture[0][i] = value;
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
                int lag = bestLag(mixture[0], target[0]);
                float gain = optimalGain(mixture[0], target[0], lag);
                lastAlignmentMs = Math.round(lag * 1000f / SAMPLE_RATE);
                lastGain = gain;
                short[] output = new short[WINDOW_SAMPLES];
                for (int i = 0; i < WINDOW_SAMPLES; i++) {
                    int targetIndex = i + lag;
                    float targetSample = targetIndex >= 0 && targetIndex < target[0].length
                            ? target[0][targetIndex] : 0f;
                    float residual = mixture[0][i] - strength * gain * targetSample;
                    output[i] = (short) Math.round(softLimit(residual) * 32767.0f);
                }
                return output;
            }
        }
    }

    // Same alignment and least-squares gain calculation as the recorded web
    // prototype which passed the listening test.
    static int bestLag(float[] mixture, float[] target) {
        int stride = Math.max(1, SAMPLE_RATE / 1_000);
        int maximumLag = MAX_ALIGNMENT_SAMPLES / stride;
        int count = Math.min(mixture.length / stride, target.length / stride);
        if (count < 100) return 0;
        int best = 0;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int lag = -maximumLag; lag <= maximumLag; lag++) {
            double xy = 0, xx = 0, yy = 0;
            int start = Math.max(0, -lag);
            int end = Math.min(count, count - lag);
            int used = 0;
            for (int i = start; i < end; i += 2) {
                float x = mixture[i * stride];
                float y = target[(i + lag) * stride];
                xy += x * y;
                xx += x * x;
                yy += y * y;
                used++;
            }
            double score = used > 20 ? Math.abs(xy / Math.sqrt((xx + 1e-12) * (yy + 1e-12))) : 0;
            if (score > bestScore) {
                bestScore = score;
                best = lag;
            }
        }
        return best * stride;
    }

    static float optimalGain(float[] mixture, float[] target, int lag) {
        double numerator = 0;
        double denominator = 0;
        for (int i = 0; i < mixture.length; i++) {
            int targetIndex = i + lag;
            if (targetIndex >= 0 && targetIndex < target.length) {
                float value = target[targetIndex];
                numerator += mixture[i] * value;
                denominator += value * value;
            }
        }
        if (denominator <= 1e-9) return 0f;
        return Math.max(0f, Math.min(3f, (float) (numerator / denominator)));
    }

    private static float softLimit(float value) {
        if (value > 1f || value < -1f) return (float) Math.tanh(value);
        return value;
    }

    private AudioRecord createRecorder() {
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, WINDOW_SAMPLES * 2));
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
        AudioTrack track = new AudioTrack(attributes, format, WINDOW_SAMPLES * 4,
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

    private static void joinQuietly(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(1_000); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
