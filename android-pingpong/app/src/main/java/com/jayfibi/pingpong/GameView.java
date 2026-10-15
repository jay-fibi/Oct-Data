package com.jayfibi.pingpong;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import com.jayfibi.pingpong.core.PongGame;

/**
 * Renders the Pong simulation on a Canvas and drives it with a
 * Choreographer frame callback. The view maps the fixed 100x160 logical
 * field onto its measured size while preserving aspect ratio; touch x is
 * mapped back to field coordinates to position the player paddle.
 */
public final class GameView extends View implements Choreographer.FrameCallback {
    private final PongGame game = new PongGame();
    private final Paint backgroundPaint = new Paint();
    private final Paint netPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playerPaddlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint aiPaddlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ballPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint messagePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path netPath = new Path();

    private boolean running;
    private long lastFrameNanos;
    private float offsetX;
    private float offsetY;
    private float scale;

    public GameView(Context context) {
        super(context);
        backgroundPaint.setColor(Color.parseColor("#10131B"));
        netPaint.setColor(Color.parseColor("#2C354B"));
        netPaint.setStyle(Paint.Style.STROKE);
        netPaint.setStrokeWidth(0.6f); // field units; drawing happens pre-scale
        playerPaddlePaint.setColor(Color.parseColor("#A5B4FC"));
        aiPaddlePaint.setColor(Color.parseColor("#F59E0B"));
        ballPaint.setColor(Color.parseColor("#F3F5FF"));
        scorePaint.setColor(Color.parseColor("#3A4358"));
        scorePaint.setTextAlign(Paint.Align.CENTER);
        scorePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        messagePaint.setColor(Color.parseColor("#BAC3D9"));
        messagePaint.setTextAlign(Paint.Align.CENTER);
        setKeepScreenOn(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        running = true;
        lastFrameNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!running) {
            return;
        }
        if (lastFrameNanos != 0) {
            float deltaSeconds = (frameTimeNanos - lastFrameNanos) * 1e-9f;
            game.update(deltaSeconds);
        }
        lastFrameNanos = frameTimeNanos;
        invalidate();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        updateTransform(width, height);
    }

    private void updateTransform(float width, float height) {
        if (width <= 0f || height <= 0f) {
            return;
        }
        scale = Math.min(width / PongGame.FIELD_WIDTH, height / PongGame.FIELD_HEIGHT);
        offsetX = (width - PongGame.FIELD_WIDTH * scale) / 2f;
        offsetY = (height - PongGame.FIELD_HEIGHT * scale) / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (scale == 0f) {
            updateTransform(getWidth(), getHeight());
            if (scale == 0f) {
                return;
            }
        }
        canvas.drawRect(0f, 0f, getWidth(), getHeight(), backgroundPaint);
        canvas.save();
        canvas.translate(offsetX, offsetY);
        canvas.scale(scale, scale);
        drawNet(canvas);
        drawScores(canvas);
        drawPaddle(canvas, game.getAiPaddleX(), game.getAiPaddleY(), aiPaddlePaint);
        drawPaddle(canvas, game.getPlayerPaddleX(), game.getPlayerPaddleY(), playerPaddlePaint);
        canvas.drawCircle(game.getBallX(), game.getBallY(), PongGame.BALL_RADIUS, ballPaint);
        drawMessage(canvas);
        canvas.restore();
    }

    private void drawNet(Canvas canvas) {
        netPath.reset();
        float mid = PongGame.FIELD_WIDTH / 2f;
        for (float y = 2f; y < PongGame.FIELD_HEIGHT; y += 6f) {
            netPath.moveTo(mid, y);
            netPath.lineTo(mid, Math.min(y + 3f, PongGame.FIELD_HEIGHT));
        }
        canvas.drawPath(netPath, netPaint);
    }

    private void drawScores(Canvas canvas) {
        float previousSize = scorePaint.getTextSize();
        scorePaint.setTextSize(18f);
        canvas.drawText(String.valueOf(game.getAiScore()),
                PongGame.FIELD_WIDTH / 2f - 14f, 24f, scorePaint);
        canvas.drawText(String.valueOf(game.getPlayerScore()),
                PongGame.FIELD_WIDTH / 2f + 14f, 24f, scorePaint);
        scorePaint.setTextSize(previousSize);
    }

    private void drawPaddle(Canvas canvas, float centerX, float centerY, Paint paint) {
        canvas.drawRoundRect(centerX - PongGame.PADDLE_WIDTH / 2f,
                centerY - PongGame.PADDLE_HEIGHT / 2f,
                centerX + PongGame.PADDLE_WIDTH / 2f,
                centerY + PongGame.PADDLE_HEIGHT / 2f,
                1.5f, 1.5f, paint);
    }

    private void drawMessage(Canvas canvas) {
        String text;
        if (game.getState() == PongGame.State.READY) {
            text = "Tap to play";
        } else if (game.getState() == PongGame.State.GAME_OVER) {
            text = (game.isPlayerWon() ? "You win" : "CPU wins") + " — tap to play again";
        } else {
            return;
        }
        float previousSize = messagePaint.getTextSize();
        messagePaint.setTextSize(6f);
        canvas.drawText(text, PongGame.FIELD_WIDTH / 2f, PongGame.FIELD_HEIGHT / 2f, messagePaint);
        messagePaint.setTextSize(previousSize);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (game.getState() != PongGame.State.PLAYING) {
                game.start();
            }
            float fieldX = (event.getX() - offsetX) / scale;
            game.movePlayerTo(fieldX);
            performClick();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
