/*
 * Copyright (C) The Android Open Source Project
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
package jp.co.innovative_solutions.gxocrapi;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.google.android.gms.vision.text.TextBlock;

import java.util.List;

/**
 * 関連グラフィックオーバーレイビュー内のTextBlockの位置、サイズ、およびIDを描画するためのグラフィックインスタンス。
 */
public class OcrGraphic extends GraphicOverlay.Graphic {

    private int mId;

    private static final int TEXT_COLOR = Color.WHITE;

    private static Paint sRectPaint;
    private static Paint sTextPaint;
    private TextBlock mText;
    private String cText;

    OcrGraphic(GraphicOverlay overlay, TextBlock text, String caption){
        super(overlay);
        mText = text;
        cText = caption;

        if (sRectPaint == null) {
            sRectPaint = new Paint();
            sRectPaint.setColor(TEXT_COLOR);
            sRectPaint.setStyle(Paint.Style.STROKE);
            sRectPaint.setStrokeWidth(4.0f);
        }

        if (sTextPaint == null) {
            sTextPaint = new Paint();
            sTextPaint.setColor(TEXT_COLOR);
            sTextPaint.setTextSize(54.0f);
        }
        // Redraw the overlay, as this graphic has been added.
        postInvalidate();
    }

    OcrGraphic(GraphicOverlay overlay, TextBlock text) {
        super(overlay);

        mText = text;
        cText = text.getValue();

        if (sRectPaint == null) {
            sRectPaint = new Paint();
            sRectPaint.setColor(TEXT_COLOR);
            sRectPaint.setStyle(Paint.Style.STROKE);
            sRectPaint.setStrokeWidth(4.0f);
        }

        if (sTextPaint == null) {
            sTextPaint = new Paint();
            sTextPaint.setColor(TEXT_COLOR);
            sTextPaint.setTextSize(54.0f);
            sTextPaint.setTextAlign(Paint.Align.CENTER);
        }
        // Redraw the overlay, as this graphic has been added.
        postInvalidate();
    }

    public int getId() {
        return mId;
    }

    public void setId(int id) {
        this.mId = id;
    }

    public TextBlock getTextBlock() {
        return mText;
    }

    /**
     * 点がこのグラフィックの境界ボックス内にあるかどうかをチェックします。
     * 提供されるポイントは、このグラフィックのオーバーレイを含む相対的なものでなければなりません。
     * @param x An x parameter in the relative context of the canvas.
     * @param y A y parameter in the relative context of the canvas.
     * @return True if the provided point is contained within this graphic's bounding box.
     */
    public boolean contains(float x, float y) {
        TextBlock text = mText;
        if (text == null) {
            return false;
        }
        RectF rect = new RectF(text.getBoundingBox());
        rect.left = translateX(rect.left);
        rect.top = translateY(rect.top);
        rect.right = translateX(rect.right);
        rect.bottom = translateY(rect.bottom);
        return (rect.left < x && rect.right > x && rect.top < y && rect.bottom > y);
    }

    /**
     * 指定されたキャンバス上の位置、サイズ、生の値のテキストブロック注釈を描画します。
     */
    @Override
    public void draw(Canvas canvas) {
        TextBlock text = mText;
        if (text == null) {
            return;
        }

        // ターゲットスコープ内の表示のみのため枠線は非表示とする
        // TextBlockの周囲にバウンディングボックスを描画します。
        RectF rect = new RectF(text.getBoundingBox());
        rect.left = translateX(rect.left);
        rect.top = translateY(rect.top);
        rect.right = translateX(rect.right);
        rect.bottom = translateY(rect.bottom);
        canvas.drawRect(rect, sRectPaint);

//        sTextPaint.setTextScaleX((rect.top - rect.bottom) / (rect.right - rect.left) * cText.length());
        sTextPaint.setTextSize((rect.top - rect.bottom) / 100 * 80);

        canvas.drawText(cText, (rect.left + rect.right)/2, rect.bottom - 10, sTextPaint);


        // テキストを複数の行に分割し、それぞれのバウンディングボックスに従って描画します。
//        List<? extends Text> textComponents = text.getComponents();
//        for(Text currentText : textComponents) {
//            float left = translateX(currentText.getBoundingBox().left);
//            float top = translateY(currentText.getBoundingBox().top);
//            float right = translateX(currentText.getBoundingBox().right);
//            float bottom = translateY(currentText.getBoundingBox().bottom);
//			//描画領域を限定します
//            if (left >= 30 && right <= canvas.getWidth()-30 && top >= (canvas.getHeight()-250)/2 && bottom <= (canvas.getHeight()+250)/2) {
//                canvas.drawText(currentText.getValue(), left, bottom, sTextPaint);
//            }
//        }
    }
}
