package com.danceworldquest.app;

import android.graphics.PointF;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

import java.util.Locale;

public final class PoseScorer {
    private PoseScorer() {}

    public static final class Result {
        public final int score;
        public final String feedback;
        public final boolean visibleEnough;

        Result(int score, String feedback, boolean visibleEnough) {
            this.score = score;
            this.feedback = feedback;
            this.visibleEnough = visibleEnough;
        }
    }

    public static Result evaluate(Pose pose, String mode) {
        PoseLandmark ls = lm(pose, PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rs = lm(pose, PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark lh = lm(pose, PoseLandmark.LEFT_HIP);
        PoseLandmark rh = lm(pose, PoseLandmark.RIGHT_HIP);
        PoseLandmark lk = lm(pose, PoseLandmark.LEFT_KNEE);
        PoseLandmark rk = lm(pose, PoseLandmark.RIGHT_KNEE);
        PoseLandmark la = lm(pose, PoseLandmark.LEFT_ANKLE);
        PoseLandmark ra = lm(pose, PoseLandmark.RIGHT_ANKLE);
        PoseLandmark lheel = lm(pose, PoseLandmark.LEFT_HEEL);
        PoseLandmark rheel = lm(pose, PoseLandmark.RIGHT_HEEL);
        PoseLandmark lfoot = lm(pose, PoseLandmark.LEFT_FOOT_INDEX);
        PoseLandmark rfoot = lm(pose, PoseLandmark.RIGHT_FOOT_INDEX);

        PoseLandmark[] essential = {ls, rs, lh, rh, lk, rk, la, ra};
        float visibility = 0f;
        int visibleCount = 0;
        for (PoseLandmark p : essential) {
            if (p != null) {
                visibility += p.getInFrameLikelihood();
                if (p.getInFrameLikelihood() >= 0.45f) visibleCount++;
            }
        }
        visibility = essential.length == 0 ? 0f : visibility / essential.length;
        boolean visibleEnough = visibleCount >= 6 && visibility >= 0.42f;
        if (!visibleEnough) {
            return new Result(Math.round(visibility * 55f),
                    "Step back a little so I can see your shoulders, hips, knees and feet.",
                    false);
        }

        float torso = torsoVerticalScore(ls, rs, lh, rh);
        float shoulders = levelScore(ls, rs);
        float hips = levelScore(lh, rh);
        float leftKneeAngle = jointAngle(lh, lk, la);
        float rightKneeAngle = jointAngle(rh, rk, ra);
        float kneeStraight = clamp01(((leftKneeAngle + rightKneeAngle) / 2f - 145f) / 30f) * 100f;
        float plieBend = 100f - Math.min(100f, Math.abs(((leftKneeAngle + rightKneeAngle) / 2f) - 125f) * 2.2f);
        float turnout = turnoutScore(lheel, lfoot, rheel, rfoot);
        float feetClose = feetCloseScore(lh, rh, la, ra);

        String normalized = mode == null ? "free" : mode.toLowerCase(Locale.ROOT);
        float score;
        if (normalized.contains("first") || normalized.contains("premiere") || normalized.contains("1st")) {
            score = 0.15f * (visibility * 100f)
                    + 0.20f * torso
                    + 0.10f * shoulders
                    + 0.10f * hips
                    + 0.20f * kneeStraight
                    + 0.15f * turnout
                    + 0.10f * feetClose;
        } else if (normalized.contains("plie") || normalized.contains("plié")) {
            score = 0.15f * (visibility * 100f)
                    + 0.25f * torso
                    + 0.10f * shoulders
                    + 0.10f * hips
                    + 0.30f * plieBend
                    + 0.10f * turnout;
        } else {
            score = 0.30f * (visibility * 100f)
                    + 0.30f * torso
                    + 0.20f * shoulders
                    + 0.20f * hips;
        }

        int rounded = Math.max(0, Math.min(100, Math.round(score)));
        String feedback;
        if (torso < 62f) {
            feedback = "Lengthen your spine and bring the torso more upright.";
        } else if (shoulders < 58f) {
            feedback = "Level the shoulders and release unnecessary tension.";
        } else if (hips < 55f) {
            feedback = "Keep the pelvis more level and centered.";
        } else if ((normalized.contains("first") || normalized.contains("1st")) && kneeStraight < 65f) {
            feedback = "Straighten both knees without locking them.";
        } else if ((normalized.contains("first") || normalized.contains("plie") || normalized.contains("plié")) && turnout < 52f) {
            feedback = "Open the feet outward a little more. Turnout scoring is still beta.";
        } else if ((normalized.contains("plie") || normalized.contains("plié")) && plieBend < 62f) {
            feedback = "Bend the knees a little more while keeping the torso tall.";
        } else if (rounded >= 88) {
            feedback = "Excellent. Hold it.";
        } else if (rounded >= 75) {
            feedback = "Good. Stay steady and keep refining.";
        } else {
            feedback = "Good start. Find more length and steadiness.";
        }
        return new Result(rounded, feedback, true);
    }

    private static PoseLandmark lm(Pose pose, int type) {
        return pose == null ? null : pose.getPoseLandmark(type);
    }

    private static float torsoVerticalScore(PoseLandmark ls, PoseLandmark rs, PoseLandmark lh, PoseLandmark rh) {
        if (!ok(ls, rs, lh, rh)) return 0f;
        PointF s = midpoint(ls.getPosition(), rs.getPosition());
        PointF h = midpoint(lh.getPosition(), rh.getPosition());
        double dx = Math.abs(s.x - h.x);
        double dy = Math.abs(s.y - h.y);
        double deviation = Math.toDegrees(Math.atan2(dx, Math.max(1.0, dy)));
        return clamp100((float) (100.0 - deviation * 3.0));
    }

    private static float levelScore(PoseLandmark a, PoseLandmark b) {
        if (!ok(a, b)) return 0f;
        PointF p = a.getPosition();
        PointF q = b.getPosition();
        double dx = Math.abs(q.x - p.x);
        double dy = Math.abs(q.y - p.y);
        double angle = Math.toDegrees(Math.atan2(dy, Math.max(1.0, dx)));
        return clamp100((float) (100.0 - angle * 4.0));
    }

    private static float jointAngle(PoseLandmark a, PoseLandmark b, PoseLandmark c) {
        if (!ok(a, b, c)) return 0f;
        PointF p1 = a.getPosition();
        PointF p2 = b.getPosition();
        PointF p3 = c.getPosition();
        double v1x = p1.x - p2.x, v1y = p1.y - p2.y;
        double v2x = p3.x - p2.x, v2y = p3.y - p2.y;
        double dot = v1x * v2x + v1y * v2y;
        double mag = Math.sqrt(v1x * v1x + v1y * v1y) * Math.sqrt(v2x * v2x + v2y * v2y);
        if (mag < 1e-5) return 0f;
        double cos = Math.max(-1.0, Math.min(1.0, dot / mag));
        return (float) Math.toDegrees(Math.acos(cos));
    }

    private static float turnoutScore(PoseLandmark lheel, PoseLandmark lfoot, PoseLandmark rheel, PoseLandmark rfoot) {
        if (!ok(lheel, lfoot, rheel, rfoot)) return 50f;
        float leftOut = lheel.getPosition().x - lfoot.getPosition().x;
        float rightOut = rfoot.getPosition().x - rheel.getPosition().x;
        float span = Math.max(1f, Math.abs(rheel.getPosition().x - lheel.getPosition().x));
        float normalized = (leftOut + rightOut) / span;
        return clamp100(35f + normalized * 150f);
    }

    private static float feetCloseScore(PoseLandmark lh, PoseLandmark rh, PoseLandmark la, PoseLandmark ra) {
        if (!ok(lh, rh, la, ra)) return 50f;
        float hipWidth = distance(lh.getPosition(), rh.getPosition());
        float ankleWidth = distance(la.getPosition(), ra.getPosition());
        if (hipWidth < 1f) return 50f;
        float ratio = ankleWidth / hipWidth;
        if (ratio <= 0.65f) return 100f;
        if (ratio >= 1.6f) return 20f;
        return clamp100(100f - (ratio - 0.65f) * 84f);
    }

    private static boolean ok(PoseLandmark... pts) {
        for (PoseLandmark p : pts) {
            if (p == null || p.getInFrameLikelihood() < 0.25f) return false;
        }
        return true;
    }

    private static PointF midpoint(PointF a, PointF b) {
        return new PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f);
    }

    private static float distance(PointF a, PointF b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private static float clamp100(float x) {
        return Math.max(0f, Math.min(100f, x));
    }

    private static float clamp01(float x) {
        return Math.max(0f, Math.min(1f, x));
    }
}
