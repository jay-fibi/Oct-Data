package com.jayfibi.pingpong;

import android.app.Activity;
import android.os.Bundle;

/** Single fullscreen activity hosting the ping pong game view. */
public final class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new GameView(this));
    }
}
