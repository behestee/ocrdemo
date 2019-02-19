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

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.PorterDuffXfermode;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.google.android.gms.vision.CameraSource;

import java.util.HashSet;
import java.util.Set;

/**
 * 関連するプレビュー（つまり、カメラプレビュー）の上に重ねて表示される一連のカスタムグラフィックスをレンダリングするビュー。
 * 作成者は、グラフィックスオブジェクトを追加したり、オブジェクトを更新したり、それらを削除したりして、ビュー内で適切な描画と無効化をトリガすることができます。
 * グラフィックスのスケーリングとミラーリングをカメラのプレビュープロパティに関連してサポートします。
 * アイデアは、検出項目はプレビューサイズで表現されますが、フルビューサイズまで拡大する必要があり、前面に向いているカメラの場合にもミラーリングされるということです。
 * 関連付けられた{@link Graphic}アイテムは、描画されるグラフィックスのビュー座標に変換するために、以下のメソッドを使用する必要があります：
 * </ li> <li>プレビュースケールからビュースケールまで、指定された値のサイズを調整する{@ link Graphic＃scaleX（float）}および{@link Graphic＃scaleY（float）
 * <li> {@ link Graphic＃translateX（float）}と{@link Graphic＃translateY（float）}プレビューの座標系からビュー座標系への座標を調整します。</ li>
 */
public class GraphicOverlay<T extends GraphicOverlay.Graphic> extends View {

    private static final String TAG = "GraphicOverlay";

    private final Object mLock = new Object();
    private int mPreviewWidth;
    private float mWidthScaleFactor = 1.0f;
    private int mPreviewHeight;
    private float mHeightScaleFactor = 1.0f;
    private int mFacing = CameraSource.CAMERA_FACING_BACK;
    private Set<T> mGraphics = new HashSet<>();

//    public static final int scannedAreaHeight = 330;

    /**
     * グラフィックオーバーレイ内でレンダリングされるカスタムグラフィックスオブジェクトの基本クラス。
     * これをサブクラス化し、Graphics要素を定義するためのGraphic＃draw（Canvas）メソッドを実装します。
     * GraphicOverlay＃add（Graphic）}を使用してオーバーレイにインスタンスを追加します。
     */
    public static abstract class Graphic {
        private GraphicOverlay mOverlay;

        public Graphic(GraphicOverlay overlay) {
            mOverlay = overlay;
        }

        /**
         * 提供されたキャンバスにグラフィックを描画します。描画では、描画されるグラフィックスの表示座標に変換するために、次のメソッドを使用する必要があります。
         * <li>プレビュースケールからビュースケールまで、指定された値のサイズを調整する{@ link Graphic＃scaleX（float）}および{@link Graphic＃scaleY（float）</li>
         * <li> {@ link Graphic＃translateX（float）}と{@link Graphic＃translateY（float）}プレビューの座標系からビュー座標系への座標を調整します。</ li>
         * @param canvas drawing canvas
         */
        public abstract void draw(Canvas canvas);

        /**
         * 指定された座標がこのグラフィック内にある場合はtrueを返します。
         */
        public abstract boolean contains(float x, float y);

        /**
         * 指定された値の水平値をプレビュースケールからビュースケールに調整します。
         */
        public float scaleX(float horizontal) {
            return horizontal * mOverlay.mWidthScaleFactor;
        }

        /**
         * 指定された値の垂直方向の値をプレビュースケールからビュースケールに調整します。
         */
        public float scaleY(float vertical) {
            return vertical * mOverlay.mHeightScaleFactor;
        }

        /**
         * プレビューの座標系からビュー座標系へのx座標を調整します。
         */
        public float translateX(float x) {
            if (mOverlay.mFacing == CameraSource.CAMERA_FACING_FRONT) {
                return mOverlay.getWidth() - scaleX(x);
            } else {
                return scaleX(x);
            }
        }

        /**
         * プレビューの座標系からビュー座標系へのy座標を調整します。
         */
        public float translateY(float y) {
            return scaleY(y);
        }

        public void postInvalidate() {
            mOverlay.postInvalidate();
        }
    }

    public GraphicOverlay(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * オーバーレイからすべてのグラフィックスを削除します。
     */
    public void clear() {
        synchronized (mLock) {
            mGraphics.clear();
        }
        postInvalidate();
    }

    /**
     * オーバーレイにグラフィックを追加します。
     */
    public void add(T graphic) {
        synchronized (mLock) {
            mGraphics.add(graphic);
        }
        postInvalidate();
    }

    /**
     * オーバーレイからグラフィックを削除します。
     */
    public void remove(T graphic) {
        synchronized (mLock) {
            mGraphics.remove(graphic);
        }
        postInvalidate();
    }

    /**
     * 指定された絶対スクリーン座標に存在する最初のグラフィック（存在する場合）を返します。
     * これらの座標は、このビューの相対スクリーン位置によってオフセットされます。
     * @return 点を含む最初の図形。テキストが検出されない場合はnull。
     */
    public T getGraphicAtLocation(float rawX, float rawY) {
        synchronized (mLock) {
            // このビューの位置を取得することで、ビューに対して相対位置をオフセットすることができます。
            int[] location = new int[2];
            this.getLocationOnScreen(location);
            for (T graphic : mGraphics) {
                if (graphic.contains(rawX - location[0], rawY - location[1])) {
                    return graphic;
                }
            }
            return null;
        }
    }

    /**
     * 後で画像座標をどのように変換するかを通知する、サイズと向きの方向のカメラ属性を設定します。
     */
    public void setCameraInfo(int previewWidth, int previewHeight, int facing) {
        synchronized (mLock) {
            mPreviewWidth = previewWidth;
            mPreviewHeight = previewHeight;
            mFacing = facing;
        }
        postInvalidate();
    }

    /**
     * 関連付けられたグラフィックオブジェクトでオーバーレイを描画します。
     */
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // ここでターゲットスコープの表示を行う
        canvas.drawARGB(128,0,0,0);
        Paint p = new Paint();
        p.setColor(Color.argb(255,255,255,255));
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        canvas.drawRect(0,(canvas.getHeight() - (mHeightScaleFactor * OcrCaptureActivity.scannedAreaHeight))/2,
                canvas.getWidth(),(canvas.getHeight() + (mHeightScaleFactor *  OcrCaptureActivity.scannedAreaHeight))/2,p);



        synchronized (mLock) {
            if ((mPreviewWidth != 0) && (mPreviewHeight != 0)) {
                mWidthScaleFactor = (float) canvas.getWidth() / (float) mPreviewWidth;
                mHeightScaleFactor = (float) canvas.getHeight() / (float) mPreviewHeight;
            }

            for (Graphic graphic : mGraphics) {
                graphic.draw(canvas);
            }
        }
    }
}
