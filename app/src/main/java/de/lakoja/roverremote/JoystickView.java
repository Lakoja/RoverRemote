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
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

public class JoystickView extends View {

    public interface PositionChangeListener {
        public void onPositionChange(Direction newDirection);
    }

    private Paint redForeground;
    private Paint whiteForeground;
    private PointF lastTouch = new PointF(-1, -1);
    private boolean validTouch = false;
    private long lastReportTime = -100;

    private PositionChangeListener changeListener = null;

    public JoystickView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        setBackgroundColor(Color.LTGRAY);

        redForeground = new Paint();
        redForeground.setColor(Color.RED);
        redForeground.setStrokeWidth(3.0f); // TODO width device independent
        redForeground.setStyle(Paint.Style.STROKE);

        whiteForeground = new Paint(redForeground);
        whiteForeground.setColor(Color.WHITE);
    }

    public void setPositionChangeListener(PositionChangeListener pl) {
        changeListener = pl;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float radius = getControlRadius();
        float w = getWidth();
        float h = getHeight();
        
        if (lastTouch.x == -1) {
            lastTouch.x = w/2;
            lastTouch.y = h/2;
        }

        //Log.i(getClass().getName(), "draw at "+lastTouch);

        canvas.drawCircle(lastTouch.x, lastTouch.y, radius, redForeground);
        canvas.drawLine(lastTouch.x - radius, lastTouch.y, lastTouch.x + radius, lastTouch.y, redForeground);
        canvas.drawLine(lastTouch.x, lastTouch.y - radius, lastTouch.x, lastTouch.y + radius, redForeground);

        canvas.drawCircle(w/2, h/2, getOuterRadius(), whiteForeground);
    }

    // TODO make static /only depends on size
    private float getControlRadius() {
        int w = getWidth();
        int h = getHeight();
        return Math.max(Math.min(h/6, w/6), 100);
    }

    private float getOuterRadius() {
        int w = getWidth();
        int h = getHeight();
        return Math.min(w/2, h/2) - 2;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        //super.onTouchEvent(event);

        float radius = getControlRadius();
        float w = getWidth();
        float h = getHeight();

        PointF thisTouch = new PointF(event.getX(), event.getY());
        float distance = getDistanceToLast(thisTouch);
        boolean isUp = event.getAction() == MotionEvent.ACTION_UP;
        boolean isDown = event.getAction() == MotionEvent.ACTION_DOWN;

        if (isDown) {
            validTouch = distance <= radius;
        }

        float outerRadius = getOuterRadius();
        if (!isUp && getDistanceToCenter(thisTouch) > outerRadius) {
            validTouch = false;

            // TODO also vibrate on this?
        }
        
        if (validTouch || isUp) {
            if (isUp) {
                thisTouch.x = w / 2;
                thisTouch.y = h / 2;
            }

            if (isUp || distance >= 2) {

                RectF changeRect = new RectF(lastTouch.x - radius, lastTouch.y - radius, lastTouch.x + radius, lastTouch.y + radius);
                RectF newRect = new RectF(thisTouch.x - radius, thisTouch.y - radius, thisTouch.x + radius, thisTouch.y + radius);
                changeRect.union(newRect);

                Rect changeRectI = new Rect();
                changeRect.roundOut(changeRectI);
                invalidate(changeRectI);

                // TODO limit to circle properly (point on circle if outside)
                lastTouch = thisTouch;

                //Log.i(getClass().getName(), "joy at " + lastTouch);

                if (changeListener != null) {
                    long now = System.currentTimeMillis();
                    if (isUp || isDown || now - lastReportTime >= 100) { // TODO maybe shorter or consider lastReportDistance?
                        Log.i(getClass().getName(), "reporting at " + lastTouch);

                        // TODO consider out of region (also see above)
                        float r = (lastTouch.x - w/2) / outerRadius;
                        float f = -1 * (lastTouch.y - h/2) / outerRadius;

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

    private float getDistanceToLast(PointF touchPoint) {
        if (lastTouch.x == -1) {
            return 0;
        }
        
        return (float)Math.sqrt((lastTouch.x - touchPoint.x)*(lastTouch.x - touchPoint.x) + (lastTouch.y - touchPoint.y)*(lastTouch.y - touchPoint.y));
    }

    private float getDistanceToCenter(PointF touchPoint) {
        float centerX = getWidth() / 2;
        float centerY = getHeight() / 2;

        return (float)Math.sqrt((centerX - touchPoint.x)*(centerX - touchPoint.x) + (centerY - touchPoint.y)*(centerY - touchPoint.y));
    }

    @Override
    public boolean performClick() {
        // TODO what does this or should it do?
        return super.performClick();
    }
}
