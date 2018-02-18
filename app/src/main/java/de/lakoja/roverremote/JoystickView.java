/*
 * Copyright (C) 2018 Lakoja on github.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.lakoja.roverremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Vibrator;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class JoystickView extends View {

    private static final String TAG = JoystickView.class.getName();
    private static final int OUTSIDE_VIBRATE = 15; // TODO could depend on dp?

    public interface PositionChangeListener {
        void onPositionChange(Direction newDirection);
    }

    private Paint redForeground;
    private Paint whiteForeground;
    private Paint whiteForegroundHair;
    private PointF lastTouch = new PointF(-1, -1);
    private boolean validTouch = false;
    private PointF centerPoint;
    private float controllerRadius = 1;
    private float outerRadius = 2;
    private long lastReportTime = -100;
    private PositionChangeListener changeListener = null;
    private Vibrator vibrator;

    public JoystickView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        setBackgroundColor(Color.rgb(0xa0, 0xa0, 0xa0));

        redForeground = new Paint();
        redForeground.setColor(Color.RED);
        redForeground.setStrokeWidth(5.0f); // TODO width device independent
        redForeground.setStyle(Paint.Style.STROKE);

        whiteForeground = new Paint(redForeground);
        whiteForeground.setColor(Color.WHITE);

        whiteForegroundHair = new Paint(whiteForeground);
        whiteForegroundHair.setStrokeWidth(1.0f);

        // TODO could depend on a setting
        vibrator = (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void setPositionChangeListener(PositionChangeListener pl) {
        changeListener = pl;
    }

    @Override
    protected void onSizeChanged(int wi, int hi, int oldw, int oldh) {
        super.onSizeChanged(wi, hi, oldw, oldh);

        float w = getWidth();
        float h = getHeight();
        centerPoint = new PointF(w / 2, h / 2);

        controllerRadius = Math.max(Math.min(h / 6, w / 6), 100);
        outerRadius = Math.min(centerPoint.x, centerPoint.y) - 2;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float radius = controllerRadius;
        
        if (lastTouch.x == -1) {
            lastTouch = centerPoint;
        }

        canvas.drawCircle(centerPoint.x, centerPoint.y, outerRadius, whiteForeground);
        canvas.drawLine(0, centerPoint.y, getWidth(), centerPoint.y, whiteForegroundHair);
        canvas.drawLine(centerPoint.x, 0, centerPoint.x, getHeight(), whiteForegroundHair);

        canvas.drawCircle(lastTouch.x, lastTouch.y, radius, redForeground);
        canvas.drawCircle(lastTouch.x, lastTouch.y, 8, redForeground);
        canvas.drawLine(lastTouch.x - radius, lastTouch.y, lastTouch.x + radius, lastTouch.y, redForeground);
        canvas.drawLine(lastTouch.x, lastTouch.y - radius, lastTouch.x, lastTouch.y + radius, redForeground);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        //super.onTouchEvent(event);

        float radius = controllerRadius;

        PointF thisTouch = new PointF(event.getX(), event.getY());
        float distance = getDistanceToLast(thisTouch);
        boolean isUp = event.getAction() == MotionEvent.ACTION_UP;
        boolean isDown = event.getAction() == MotionEvent.ACTION_DOWN;

        if (isDown) {
            validTouch = distance <= radius;
        }

        if (!isUp && validTouch && getDistanceToCenter(thisTouch) > outerRadius) {
            if (vibrator != null) {
                vibrator.vibrate(20);
            }

            thisTouch = calculatePointOnCircle(thisTouch, outerRadius);
        }
        
        if (validTouch || isUp) {
            if (isUp) {
                thisTouch = centerPoint;
            }

            if (isUp || distance >= 2) {
                RectF changeRect = new RectF(lastTouch.x - radius, lastTouch.y - radius, lastTouch.x + radius, lastTouch.y + radius);
                RectF newRect = new RectF(thisTouch.x - radius, thisTouch.y - radius, thisTouch.x + radius, thisTouch.y + radius);
                changeRect.union(newRect);

                Rect changeRectI = new Rect();
                changeRect.roundOut(changeRectI);
                invalidate(changeRectI);

                if (!isUp && vibrator != null) {
                    if (wasOutIsNowInX(lastTouch, thisTouch)) {
                        vibrator.vibrate(200);
                    }
                    if (wasOutIsNowInY(lastTouch, thisTouch)) {
                        vibrator.vibrate(300);
                    }
                    // TODO snap on 0?
                }

                lastTouch = thisTouch;

                if (changeListener != null) {
                    long now = System.currentTimeMillis();
                    if (isUp || isDown || now - lastReportTime >= 100) { // TODO maybe shorter or consider lastReportDistance?
                        //Log.i(TAG, "reporting at " + lastTouch);

                        // TODO consider out of region (also see above)
                        float r = (lastTouch.x - centerPoint.x) / outerRadius;
                        float f = -1 * (lastTouch.y - centerPoint.y) / outerRadius;

                        changeListener.onPositionChange(new Direction(f, r));
                        lastReportTime = now;
                    }
                }
            }
        }

        if (isUp) {
            validTouch = false;

            super.performClick();
        }

        return true;
    }

    private PointF calculatePointOnCircle(PointF thisTouch, float outerRadius) {
        float touchToCenterX = thisTouch.x - centerPoint.x;
        float touchToCenterY = thisTouch.y - centerPoint.y;
        float x = centerPoint.x + (touchToCenterX < 0 ? -1 : 1) * outerRadius;
        if (touchToCenterY != 0) {
            float v = touchToCenterX / touchToCenterY;
            x = (float) Math.sqrt((outerRadius * outerRadius) / (1 + 1 / (v * v)));
        }
        float y = (float)Math.sqrt((outerRadius * outerRadius) - (x * x));

        //Log.w(TAG, "Calculated "+x+","+y+" for bogus "+thisTouch.x+","+thisTouch.y+ " center "+(w/2)+","+(h/2)+" outer radius "+outerRadius);

        return new PointF(centerPoint.x + (touchToCenterX < 0 ? -1 : 1) * x, centerPoint.y + (touchToCenterY < 0 ? -1 : 1) * y);
    }

    private float getDistanceToLast(PointF touchPoint) {
        if (lastTouch.x == -1) {
            return 0;
        }
        
        return (float)Math.sqrt((lastTouch.x - touchPoint.x)*(lastTouch.x - touchPoint.x) + (lastTouch.y - touchPoint.y)*(lastTouch.y - touchPoint.y));
    }

    private float getDistanceToCenter(PointF touchPoint) {
        return (float)Math.sqrt((centerPoint.x - touchPoint.x)*(centerPoint.x - touchPoint.x) + (centerPoint.y - touchPoint.y)*(centerPoint.y - touchPoint.y));
    }

    private boolean wasOutIsNowInX(PointF lastTouch, PointF thisTouch) {
        return (Math.abs(lastTouch.x - centerPoint.x) >= OUTSIDE_VIBRATE && Math.abs(thisTouch.x - centerPoint.x) < OUTSIDE_VIBRATE);
    }

    private boolean wasOutIsNowInY(PointF lastTouch, PointF thisTouch) {
        return (Math.abs(lastTouch.y - centerPoint.y) >= OUTSIDE_VIBRATE && Math.abs(thisTouch.y - centerPoint.y) < OUTSIDE_VIBRATE);
    }

    @Override
    public boolean performClick() {
        // TODO what does this or should it do?
        return super.performClick();
    }
}
