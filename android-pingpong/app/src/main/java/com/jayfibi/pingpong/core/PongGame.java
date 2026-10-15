package com.jayfibi.pingpong.core;

/**
 * A deterministic, dependency-free Pong simulation in a fixed logical
 * coordinate space: (0,0) is the top-left corner, x grows right, y grows
 * down. The player paddle sits at the bottom and is positioned directly by
 * touch input; a simple AI paddle sits at the top and tracks the ball at a
 * capped speed so it can be beaten. The first player to 11 points wins the
 * match. The view layer only scales these units for the screen, so the whole
 * simulation can be exercised by plain JVM tests.
 */
public final class PongGame {

    public enum State { READY, PLAYING, GAME_OVER }

    public static final float FIELD_WIDTH = 100f;
    public static final float FIELD_HEIGHT = 160f;
    public static final float PADDLE_WIDTH = 20f;
    public static final float PADDLE_HEIGHT = 4f;
    public static final float BALL_RADIUS = 2.5f;
    public static final int WINNING_SCORE = 11;

    public static final float BALL_START_SPEED = 48f;
    public static final float BALL_MAX_SPEED = 90f;
    public static final float BALL_SPEED_GAIN = 1.05f;
    public static final float MAX_BOUNCE_ANGLE = (float) (Math.PI / 3.0); // 60 degrees
    static final float SERVE_DELAY = 1.0f;
    static final float SERVE_ANGLE_RATIO = 0.35f;
    static final float AI_SPEED = 42f;
    static final float AI_DEADZONE = 0.35f;
    static final float MAX_FRAME_DELTA = 0.1f;

    private static final float AI_PADDLE_Y = 10f;
    private static final float PLAYER_PADDLE_Y = FIELD_HEIGHT - 10f;

    private State state = State.READY;
    private int playerScore;
    private int aiScore;
    private boolean playerWon;
    private float ballX = FIELD_WIDTH / 2f;
    private float ballY = FIELD_HEIGHT / 2f;
    private float ballVX;
    private float ballVY;
    private float playerPaddleX = FIELD_WIDTH / 2f;
    private float aiPaddleX = FIELD_WIDTH / 2f;
    private float serveDelay;
    private int serveSign = 1;
    private boolean concededByPlayer;

    /** Clears scores and returns to the ready state without starting a match. */
    public void reset() {
        state = State.READY;
        playerScore = 0;
        aiScore = 0;
        playerWon = false;
        ballX = FIELD_WIDTH / 2f;
        ballY = FIELD_HEIGHT / 2f;
        ballVX = 0f;
        ballVY = 0f;
        playerPaddleX = FIELD_WIDTH / 2f;
        aiPaddleX = FIELD_WIDTH / 2f;
        serveDelay = 0f;
        serveSign = 1;
        concededByPlayer = false;
    }

    /**
     * Starts a new match from the ready or game-over state: scores reset,
     * paddles recenter, and the first serve is prepared. Does nothing while a
     * match is already in progress.
     *
     * @return true if a new match was started
     */
    public boolean start() {
        if (state == State.PLAYING) {
            return false;
        }
        reset();
        state = State.PLAYING;
        concededByPlayer = false; // first serve travels up toward the AI
        serveDelay = SERVE_DELAY;
        return true;
    }

    /**
     * Advances the simulation. Non-positive and NaN deltas are ignored. The
     * serve countdown consumes the full delta; physics is clamped and
     * sub-stepped so a long pause or slow frame cannot tunnel the ball
     * through a paddle.
     */
    public void update(float deltaSeconds) {
        if (!(deltaSeconds > 0f) || state != State.PLAYING) {
            return;
        }
        if (serveDelay > 0f) {
            serveDelay -= deltaSeconds;
            if (serveDelay > 0f) {
                return;
            }
            serveDelay = 0f;
            launchServe();
            return;
        }
        float remaining = Math.min(deltaSeconds, MAX_FRAME_DELTA);
        while (remaining > 0f) {
            float speed = currentSpeed();
            float step = speed > 0f ? Math.min(remaining, BALL_RADIUS / speed) : remaining;
            stepPhysics(step);
            remaining -= step;
        }
    }

    /**
     * Moves the player paddle so its center is at the given field x, clamped
     * to the field. Ignored for NaN input.
     */
    public void movePlayerTo(float x) {
        if (Float.isNaN(x)) {
            return;
        }
        playerPaddleX = clampPaddle(x);
    }

    public State getState() {
        return state;
    }

    public int getPlayerScore() {
        return playerScore;
    }

    public int getAiScore() {
        return aiScore;
    }

    /** Only meaningful in the game-over state. */
    public boolean isPlayerWon() {
        return playerWon;
    }

    public float getBallX() {
        return ballX;
    }

    public float getBallY() {
        return ballY;
    }

    public float getBallVX() {
        return ballVX;
    }

    public float getBallVY() {
        return ballVY;
    }

    public float getPlayerPaddleX() {
        return playerPaddleX;
    }

    public float getAiPaddleX() {
        return aiPaddleX;
    }

    public float getPlayerPaddleY() {
        return PLAYER_PADDLE_Y;
    }

    public float getAiPaddleY() {
        return AI_PADDLE_Y;
    }

    private void stepPhysics(float dt) {
        updateAi(dt);
        ballX += ballVX * dt;
        if (ballX - BALL_RADIUS < 0f) {
            ballX = BALL_RADIUS;
            ballVX = Math.abs(ballVX);
        } else if (ballX + BALL_RADIUS > FIELD_WIDTH) {
            ballX = FIELD_WIDTH - BALL_RADIUS;
            ballVX = -Math.abs(ballVX);
        }
        float previousY = ballY;
        ballY += ballVY * dt;
        float aiFace = AI_PADDLE_Y + PADDLE_HEIGHT / 2f;
        if (ballVY < 0f && previousY - BALL_RADIUS >= aiFace && ballY - BALL_RADIUS <= aiFace
                && Math.abs(ballX - aiPaddleX) <= PADDLE_WIDTH / 2f + BALL_RADIUS) {
            ballY = aiFace + BALL_RADIUS;
            bounceOffPaddle(aiPaddleX, 1f); // reflect down toward the player
        }
        float playerFace = PLAYER_PADDLE_Y - PADDLE_HEIGHT / 2f;
        if (ballVY > 0f && previousY + BALL_RADIUS <= playerFace && ballY + BALL_RADIUS >= playerFace
                && Math.abs(ballX - playerPaddleX) <= PADDLE_WIDTH / 2f + BALL_RADIUS) {
            ballY = playerFace - BALL_RADIUS;
            bounceOffPaddle(playerPaddleX, -1f); // reflect up toward the AI
        }
        if (ballY < -BALL_RADIUS) {
            scorePoint(true);
        } else if (ballY > FIELD_HEIGHT + BALL_RADIUS) {
            scorePoint(false);
        }
    }

    /**
     * Reflects the ball off a paddle. {@code towardField} is -1 to send the
     * ball up or +1 to send it down; the horizontal angle comes from where the
     * ball struck the paddle relative to its center, and each rally hit
     * slightly increases speed up to a cap.
     */
    private void bounceOffPaddle(float paddleX, float towardField) {
        float speed = Math.min(currentSpeed() * BALL_SPEED_GAIN, BALL_MAX_SPEED);
        float offset = clamp((ballX - paddleX) / (PADDLE_WIDTH / 2f), -1f, 1f);
        float angle = offset * MAX_BOUNCE_ANGLE;
        ballVX = speed * (float) Math.sin(angle);
        ballVY = towardField * speed * (float) Math.cos(angle);
    }

    private float currentSpeed() {
        return (float) Math.sqrt(ballVX * ballVX + ballVY * ballVY);
    }

    private void updateAi(float dt) {
        float target;
        if (ballVY < 0f) {
            target = ballX; // ball approaching: chase it
        } else {
            target = FIELD_WIDTH / 2f; // ball leaving: drift back to center
        }
        float delta = target - aiPaddleX;
        if (Math.abs(delta) <= AI_DEADZONE) {
            return;
        }
        float limit = AI_SPEED * dt;
        if (Math.abs(delta) <= limit) {
            aiPaddleX = target;
        } else {
            aiPaddleX += Math.copySign(limit, delta);
        }
        aiPaddleX = clampPaddle(aiPaddleX);
    }

    private void launchServe() {
        float speed = BALL_START_SPEED;
        float vx = serveSign * SERVE_ANGLE_RATIO * speed;
        serveSign = -serveSign;
        ballX = FIELD_WIDTH / 2f;
        ballY = FIELD_HEIGHT / 2f;
        ballVX = vx;
        ballVY = (concededByPlayer ? 1f : -1f)
                * (float) Math.sqrt(speed * speed - vx * vx);
    }

    private void scorePoint(boolean playerScored) {
        if (playerScored) {
            playerScore++;
        } else {
            aiScore++;
        }
        ballX = FIELD_WIDTH / 2f;
        ballY = FIELD_HEIGHT / 2f;
        ballVX = 0f;
        ballVY = 0f;
        if (playerScore >= WINNING_SCORE || aiScore >= WINNING_SCORE) {
            state = State.GAME_OVER;
            playerWon = playerScore >= WINNING_SCORE;
            serveDelay = 0f;
            return;
        }
        concededByPlayer = !playerScored; // serve travels toward the player who lost the point
        serveDelay = SERVE_DELAY;
    }

    private static float clampPaddle(float x) {
        float half = PADDLE_WIDTH / 2f;
        return clamp(x, half, FIELD_WIDTH - half);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
