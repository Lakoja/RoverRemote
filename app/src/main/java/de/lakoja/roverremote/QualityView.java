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
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.view.View;

public class QualityView extends View {
    private float quality = 50;
    private Paint redGreenGradient;

    public QualityView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        setBackgroundColor(Color.BLACK);


    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (redGreenGradient == null) {
            redGreenGradient = new Paint();
            // TODO this fixes the height...
            redGreenGradient.setShader(new LinearGradient(0, 0, 0, getHeight(), Color.GREEN, Color.RED, Shader.TileMode.CLAMP));
        }

        float y = getY(this.quality);

        canvas.drawRect(0, y, getWidth(), getHeight(), redGreenGradient);
    }

    public float getQuality() {
        return quality;
    }

    public void setQuality(float newQuality) {
        float oldY = getY(this.quality);
        float newY = getY(newQuality);

        this.quality = newQuality;

        RectF changeRect = new RectF(0, Math.min(oldY, newY), getWidth(), Math.max(oldY, newY));
        Rect changeRectI = new Rect();
        changeRect.roundOut(changeRectI);

        invalidate(changeRectI);
    }

    private float getY(float quality) {
        return ((100 - quality) / 100) * getHeight();
    }
}