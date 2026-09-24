package com.danceworldquest.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.Image;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseDetection;
import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PoseActivity extends ComponentActivity {
    public static final String EXTRA_MODE = "dwq_pose_mode";
    public static final String PREFS = "dwq_pose";
    public static final String KEY_LAST_RESULT = "last_result";

    private static final int REQ_CAMERA = 2401;
    private static final int PASS_SCORE = 75;
    private static final long HOLD_MS = 5000L;

    private PreviewView previewView;
    private PoseOverlay overlay;
    private TextView scoreText;
    private TextView feedbackText;
    private TextView holdText;
    private PoseDetector detector;
    private ExecutorService cameraExecutor;
    private String mode = "free";
    private long holdStarted = -1L;
    private float smoothedScore = -1f;
    private boolean completed = false;
    private boolean frontCamera = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getIntent().getStringExtra(EXTRA_MODE);
        if (mode == null || mode.trim().isEmpty()) mode = "free";

        detector = PoseDetection.getClient(
                new PoseDetectorOptions.Builder()
                        .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                        .build()
        );
        cameraExecutor = Executors.newSingleThreadExecutor();

        buildUi();

        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        overlay = new PoseOverlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        LinearLayout hud = new LinearLayout(this);
        hud.setOrientation(LinearLayout.VERTICAL);
        hud.setPadding(28, 28, 28, 28);
        hud.setBackgroundColor(0x99000000);

        TextView title = text("AI COACH β • " + mode.toUpperCase(Locale.ROOT), 18, true);
        scoreText = text("Score —", 32, true);
        feedbackText = text("Move into frame. I need your full body.", 16, false);
        holdText = text("ML Kit STREAM_MODE • 33 landmarks", 13, false);

        hud.addView(title);
        hud.addView(scoreText);
        hud.addView(feedbackText);
        hud.addView(holdText);

        FrameLayout.LayoutParams hudLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        hudLp.gravity = Gravity.TOP;
        hudLp.setMargins(18, 18, 18, 0);
        root.addView(hud, hudLp);

        Button close = new Button(this);
        close.setText("×");
        close.setTextSize(24f);
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(150, 120);
        closeLp.gravity = Gravity.BOTTOM | Gravity.END;
        closeLp.setMargins(0, 0, 24, 30);
        root.addView(close, closeLp);

        setContentView(root);
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setPadding(0, 4, 0, 4);
        return t;
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                bindCamera(provider, CameraSelector.DEFAULT_FRONT_CAMERA, true);
            } catch (Exception frontError) {
                try {
                    ProcessCameraProvider provider = future.get();
                    bindCamera(provider, CameraSelector.DEFAULT_BACK_CAMERA, false);
                } catch (Exception e) {
                    feedbackText.setText("Camera unavailable on this device.");
                }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindCamera(ProcessCameraProvider provider, CameraSelector selector, boolean isFront) {
        provider.unbindAll();
        frontCamera = isFront;

        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetResolution(new Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        analysis.setAnalyzer(cameraExecutor, this::analyzeImage);
        provider.bindToLifecycle(this, selector, preview, analysis);
    }

    @ExperimentalGetImage
    private void analyzeImage(@NonNull ImageProxy imageProxy) {
        Image mediaImage = imageProxy.getImage();
        if (mediaImage == null || completed) {
            imageProxy.close();
            return;
        }

        int rotation = imageProxy.getImageInfo().getRotationDegrees();
        InputImage input = InputImage.fromMediaImage(mediaImage, rotation);
        int imageW = imageProxy.getWidth();
        int imageH = imageProxy.getHeight();
        if (rotation == 90 || rotation == 270) {
            int tmp = imageW;
            imageW = imageH;
            imageH = tmp;
        }
        final int finalW = imageW;
        final int finalH = imageH;

        detector.process(input)
                .addOnSuccessListener(pose -> onPose(pose, finalW, finalH))
                .addOnFailureListener(e -> runOnUiThread(() ->
                        feedbackText.setText("Pose detection paused. Reposition and try again.")))
                .addOnCompleteListener(task -> imageProxy.close());
    }

    private void onPose(Pose pose, int imageW, int imageH) {
        PoseScorer.Result result = PoseScorer.evaluate(pose, mode);
        if (smoothedScore < 0f) smoothedScore = result.score;
        else smoothedScore = 0.78f * smoothedScore + 0.22f * result.score;

        int displayScore = Math.round(smoothedScore);
        long now = SystemClock.elapsedRealtime();

        if (result.visibleEnough && displayScore >= PASS_SCORE) {
            if (holdStarted < 0L) holdStarted = now;
        } else {
            holdStarted = -1L;
        }

        long held = holdStarted < 0L ? 0L : now - holdStarted;
        boolean passed = held >= HOLD_MS && !completed;

        runOnUiThread(() -> {
            overlay.setPose(pose, imageW, imageH, frontCamera);
            scoreText.setText("Score " + displayScore + "/100");
            feedbackText.setText(result.feedback);
            if (holdStarted >= 0L) {
                holdText.setText(String.format(Locale.US, "Hold %.1f / 5.0 s", Math.min(5.0, held / 1000.0)));
            } else {
                holdText.setText("Target: 75+ for 5 seconds");
            }
            if (passed) complete(displayScore, result.feedback);
        });
    }

    private void complete(int score, String feedback) {
        if (completed) return;
        completed = true;
        scoreText.setText("Validated ✓  " + score + "/100");
        holdText.setText("5 seconds held. Bravo.");

        try {
            JSONObject json = new JSONObject();
            json.put("mode", mode);
            json.put("score", score);
            json.put("feedback", feedback);
            json.put("timestamp", System.currentTimeMillis());
            json.put("engine", "mlkit-stream");
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_RESULT, json.toString())
                    .apply();
        } catch (Exception ignored) {}

        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 900L);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                feedbackText.setText("Camera permission is required for AI Coach.");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (detector != null) detector.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
