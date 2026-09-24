package com.danceworldquest.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.View;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

public final class PoseOverlay extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Pose pose;
    private int imageWidth = 1;
    private int imageHeight = 1;
    private boolean mirror = true;

    private static final int[][] CONNECTIONS = {
            {PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER},
            {PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW},
            {PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST},
            {PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW},
            {PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST},
            {PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP},
            {PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP},
            {PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP},
            {PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE},
            {PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE},
            {PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE},
            {PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE},
            {PoseLandmark.LEFT_ANKLE, PoseLandmark.LEFT_HEEL},
            {PoseLandmark.LEFT_HEEL, PoseLandmark.LEFT_FOOT_INDEX},
            {PoseLandmark.RIGHT_ANKLE, PoseLandmark.RIGHT_HEEL},
            {PoseLandmark.RIGHT_HEEL, PoseLandmark.RIGHT_FOOT_INDEX}
    };

    public PoseOverlay(Context context) {
        super(context);
        linePaint.setColor(Color.rgb(0, 255, 190));
        linePaint.setStrokeWidth(7f);
        linePaint.setStyle(Paint.Style.STROKE);
        pointPaint.setColor(Color.WHITE);
        pointPaint.setStyle(Paint.Style.FILL);
        setBackgroundColor(Color.TRANSPARENT);
    }

    public void setPose(Pose pose, int imageWidth, int imageHeight, boolean mirror) {
        this.pose = pose;
        this.imageWidth = Math.max(1, imageWidth);
        this.imageHeight = Math.max(1, imageHeight);
        this.mirror = mirror;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pose == null) return;

        for (int[] pair : CONNECTIONS) {
            PoseLandmark a = pose.getPoseLandmark(pair[0]);
            PoseLandmark b = pose.getPoseLandmark(pair[1]);
            if (!visible(a) || !visible(b)) continue;
            PointF p1 = map(a.getPosition());
            PointF p2 = map(b.getPosition());
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, linePaint);
        }

        for (PoseLandmark lm : pose.getAllPoseLandmarks()) {
            if (!visible(lm)) continue;
            PointF p = map(lm.getPosition());
            canvas.drawCircle(p.x, p.y, 8f, pointPaint);
        }
    }

    private boolean visible(PoseLandmark lm) {
        return lm != null && lm.getInFrameLikelihood() >= 0.35f;
    }

    private PointF map(PointF p) {
        float vw = Math.max(1f, getWidth());
        float vh = Math.max(1f, getHeight());
        float scale = Math.max(vw / imageWidth, vh / imageHeight);
        float displayedW = imageWidth * scale;
        float displayedH = imageHeight * scale;
        float offsetX = (vw - displayedW) / 2f;
        float offsetY = (vh - displayedH) / 2f;

        float x = p.x * scale + offsetX;
        float y = p.y * scale + offsetY;
        if (mirror) x = vw - x;
        return new PointF(x, y);
    }
}
