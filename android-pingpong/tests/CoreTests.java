import com.jayfibi.pingpong.core.PongGame;

/** Plain Java assertion suite. No Android, test framework, or external library. */
public final class CoreTests {
    private static int assertions;
    private static int groups;

    private CoreTests() {
    }

    public static void main(String[] args) {
        run("initial state and reset", CoreTests::initialState);
        run("match start and serve timing", CoreTests::serveTiming);
        run("player paddle clamping and NaN handling", CoreTests::paddleClamping);
        run("update ignores invalid deltas", CoreTests::invalidDeltas);
        run("serve direction alternates and targets the conceder", CoreTests::serveDirection);
        run("perfect player wins eleven-nil with bounded physics", CoreTests::perfectPlayerGame);
        run("parked player concedes points and loses", CoreTests::parkedPlayerGame);
        run("game over is terminal until restarted", CoreTests::gameOverTerminal);
        System.out.println("PASS: " + assertions + " assertions in " + groups + " test groups.");
    }

    private static void initialState() {
        PongGame game = new PongGame();
        equal(PongGame.State.READY, game.getState(), "New game is ready");
        equal(0, game.getPlayerScore(), "Player starts at zero");
        equal(0, game.getAiScore(), "AI starts at zero");
        near(PongGame.FIELD_WIDTH / 2f, game.getBallX(), "Ball starts centered horizontally");
        near(PongGame.FIELD_HEIGHT / 2f, game.getBallY(), "Ball starts centered vertically");
        near(0f, game.getBallVX(), "Ball starts without horizontal velocity");
        near(0f, game.getBallVY(), "Ball starts without vertical velocity");
        near(PongGame.FIELD_WIDTH / 2f, game.getPlayerPaddleX(), "Player paddle starts centered");
        near(PongGame.FIELD_WIDTH / 2f, game.getAiPaddleX(), "AI paddle starts centered");
        game.movePlayerTo(20f);
        game.reset();
        equal(PongGame.State.READY, game.getState(), "Reset returns to ready");
        near(PongGame.FIELD_WIDTH / 2f, game.getPlayerPaddleX(), "Reset recenters the player paddle");
    }

    private static void serveTiming() {
        PongGame game = new PongGame();
        check(game.start(), "Start begins a match from ready");
        equal(PongGame.State.PLAYING, game.getState(), "Match is playing");
        check(!game.start(), "Start is a no-op during a match");
        game.update(0.5f);
        near(0f, game.getBallVY(), "Ball waits during the serve countdown");
        game.update(0.6f);
        check(game.getBallVY() < 0f, "First serve travels up toward the AI");
        near(PongGame.FIELD_WIDTH / 2f, game.getBallX(), "Serve starts from center");
        near(PongGame.FIELD_HEIGHT / 2f, game.getBallY(), "Serve starts from center height");
        float speed = length(game.getBallVX(), game.getBallVY());
        near(PongGame.BALL_START_SPEED, speed, "Serve uses the starting speed");
    }

    private static void paddleClamping() {
        PongGame game = new PongGame();
        game.movePlayerTo(-25f);
        near(PongGame.PADDLE_WIDTH / 2f, game.getPlayerPaddleX(), "Paddle clamps to the left edge");
        game.movePlayerTo(PongGame.FIELD_WIDTH + 40f);
        near(PongGame.FIELD_WIDTH - PongGame.PADDLE_WIDTH / 2f, game.getPlayerPaddleX(),
                "Paddle clamps to the right edge");
        game.movePlayerTo(Float.NaN);
        near(PongGame.FIELD_WIDTH - PongGame.PADDLE_WIDTH / 2f, game.getPlayerPaddleX(),
                "NaN input is ignored");
        game.movePlayerTo(30f);
        near(30f, game.getPlayerPaddleX(), "In-field positions are accepted");
    }

    private static void invalidDeltas() {
        PongGame game = new PongGame();
        game.start();
        game.update(1.1f); // run out the serve countdown and launch
        float x = game.getBallX();
        float y = game.getBallY();
        game.update(0f);
        game.update(-1f);
        game.update(Float.NaN);
        near(x, game.getBallX(), "Non-positive and NaN deltas do not move the ball");
        near(y, game.getBallY(), "Non-positive and NaN deltas do not move the ball (y)");
    }

    private static void serveDirection() {
        PongGame game = new PongGame();
        game.start();
        game.update(1.1f); // run out the serve countdown and launch
        check(game.getBallVX() > 0f, "First serve drifts right");
        float firstVx = game.getBallVX();
        parkPlayer(game);
        int total = 0;
        for (int i = 0; i < 200_000 && total == 0; i++) {
            game.update(1f / 60f);
            total = game.getPlayerScore() + game.getAiScore();
        }
        equal(1, total, "A point is scored within the simulation budget");
        boolean playerConceded = game.getAiScore() == 1;
        for (int i = 0; i < 200_000 && game.getBallVY() == 0f; i++) {
            game.update(1f / 60f);
        }
        check(game.getBallVY() != 0f, "The next serve launches");
        if (playerConceded) {
            check(game.getBallVY() > 0f, "Serve travels toward the player who conceded");
        } else {
            check(game.getBallVY() < 0f, "Serve travels toward the AI that conceded");
        }
        check(game.getBallVX() * firstVx < 0f, "Serve angle alternates sides between points");
        near(Math.abs(firstVx), Math.abs(game.getBallVX()), "Serve angle magnitude is unchanged");
    }

    /** Drives a scripted game where the player paddle never moves. */
    private static void parkPlayer(PongGame game) {
        game.movePlayerTo(0f);
    }

    private static void perfectPlayerGame() {
        PongGame game = new PongGame();
        game.start();
        for (int i = 0; i < 400_000 && game.getState() == PongGame.State.PLAYING; i++) {
            // Chase the ball with the paddle shifted left of it so every return
            // is angled; a perfectly centered return would rally forever.
            game.movePlayerTo(game.getBallX() - 8f);
            game.update(1f / 60f);
            assertBounds(game);
        }
        equal(PongGame.State.GAME_OVER, game.getState(), "Match reaches game over");
        check(game.isPlayerWon(), "The player wins a perfectly defended match");
        equal(PongGame.WINNING_SCORE, game.getPlayerScore(), "Player wins by the target score");
        equal(0, game.getAiScore(), "The AI never scores against a perfect defender");
    }

    private static void parkedPlayerGame() {
        PongGame game = new PongGame();
        game.start();
        parkPlayer(game);
        for (int i = 0; i < 400_000 && game.getState() == PongGame.State.PLAYING; i++) {
            game.update(1f / 60f);
            assertBounds(game);
        }
        equal(PongGame.State.GAME_OVER, game.getState(), "An ignored paddle still reaches game over");
        int winner = Math.max(game.getPlayerScore(), game.getAiScore());
        equal(PongGame.WINNING_SCORE, winner, "The match ends on the target score");
        check(game.isPlayerWon() == (game.getPlayerScore() == PongGame.WINNING_SCORE),
                "Winner flag matches the leading score");
    }

    private static void gameOverTerminal() {
        PongGame game = new PongGame();
        game.start();
        parkPlayer(game);
        for (int i = 0; i < 400_000 && game.getState() == PongGame.State.PLAYING; i++) {
            game.update(1f / 60f);
        }
        equal(PongGame.State.GAME_OVER, game.getState(), "Match ended");
        float x = game.getBallX();
        float y = game.getBallY();
        game.update(5f);
        near(x, game.getBallX(), "Ball is frozen after game over");
        near(y, game.getBallY(), "Ball is frozen after game over (y)");
        check(game.start(), "Start begins a rematch from game over");
        equal(0, game.getPlayerScore(), "Rematch resets the player score");
        equal(0, game.getAiScore(), "Rematch resets the AI score");
        near(PongGame.FIELD_WIDTH / 2f, game.getPlayerPaddleX(), "Rematch recenters the player paddle");
    }

    private static void assertBounds(PongGame game) {
        check(game.getBallX() >= PongGame.BALL_RADIUS - 1e-3f
                        && game.getBallX() <= PongGame.FIELD_WIDTH - PongGame.BALL_RADIUS + 1e-3f,
                "Ball stays within the side walls");
        check(game.getBallY() >= -PongGame.BALL_RADIUS - 1e-3f
                        && game.getBallY() <= PongGame.FIELD_HEIGHT + PongGame.BALL_RADIUS + 1e-3f,
                "Ball stays within the vertical bounds");
        check(game.getAiPaddleX() >= PongGame.PADDLE_WIDTH / 2f - 1e-3f
                        && game.getAiPaddleX() <= PongGame.FIELD_WIDTH - PongGame.PADDLE_WIDTH / 2f + 1e-3f,
                "AI paddle stays within the field");
    }

    private static void run(String name, Runnable test) {
        int before = assertions;
        try {
            test.run();
        } catch (AssertionError error) {
            throw new AssertionError("FAIL [" + name + "]: " + error.getMessage(), error);
        }
        groups++;
        System.out.println("ok " + groups + " - " + name + " (" + (assertions - before) + " assertions)");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(int expected, int actual, String message) {
        assertions++;
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void equal(Object expected, Object actual, String message) {
        assertions++;
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void near(float expected, float actual, String message) {
        assertions++;
        if (Math.abs(expected - actual) > 1e-3f) {
            throw new AssertionError(message + ": expected ~" + expected + ", got " + actual);
        }
    }

    private static float length(float x, float y) {
        return (float) Math.sqrt(x * x + y * y);
    }
}
