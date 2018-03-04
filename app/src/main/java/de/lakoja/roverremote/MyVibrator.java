package de.lakoja.roverremote;


import android.content.Context;
import android.os.Vibrator;
import android.util.Log;

public class MyVibrator {
    private static final String TAG = MyVibrator.class.getName();

    private Vibrator vibrator;


    // TODO could depend on a setting

    public MyVibrator(Context context) {
        vibrator = (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);

        // TODO find out if vibration is configured off?

        if (vibrator == null || !vibrator.hasVibrator()) {
            Log.e(TAG, "Cannot vibrate");
        }
    }

    public void vibrateOnce(int millis) {
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(millis);
        }
    }

    public void vibratePattern(int first, int pause, int second) {
        if (vibrator != null && vibrator.hasVibrator()) {

            long pattern[] = { 0, first, pause, second };
            vibrator.vibrate(pattern, -1);
        }
    }
}
