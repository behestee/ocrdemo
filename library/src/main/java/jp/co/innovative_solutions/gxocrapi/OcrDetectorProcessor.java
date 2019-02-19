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

import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;

import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.text.TextBlock;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 検出されたTextBlockを受け取り、
 * それらをオーバーレイにOcrGraphicsとして追加する非常に単純なプロセッサ
 */
public class OcrDetectorProcessor implements Detector.Processor<TextBlock> {

    private static final String TAG = "OcrCapture";
    private GraphicOverlay<OcrGraphic> mGraphicOverlay;
    private DetectorResultInterface detectorResultInterface;
    private int width, height;


    OcrDetectorProcessor(GraphicOverlay<OcrGraphic> ocrGraphicOverlay) {
        mGraphicOverlay = ocrGraphicOverlay;
    }

    OcrDetectorProcessor(GraphicOverlay<OcrGraphic> ocrGraphicOverlay, int width, int height, DetectorResultInterface detectorResultInterface) {
        mGraphicOverlay = ocrGraphicOverlay;
        this.width = width;
        this.height = height;
        this.detectorResultInterface = detectorResultInterface;
    }

    /**
     * 検出結果を提供するために検出器によって呼び出されます。
     * アプリケーションで呼び出された場合は、
     * 以前のフレームと同じ場所や内容のTextBlockを追跡して同等の検出を確認したり、
     * 複数の検出で保持されていないTextBlockを削除してノイズを減らすことができます。
     * この検出プロセッサに関連するリソースを解放します。
     */
    @Override
    public void receiveDetections(Detector.Detections<TextBlock> detections) {

        mGraphicOverlay.clear();
        SparseArray<TextBlock> items = detections.getDetectedItems();

        // ignore if there is no items for safety
        if (items.size() <= 0) return;

        // Creating rectangle for defining the area to be considered
        Rect scanAreaRect = new Rect(0, (height - OcrCaptureActivity.scannedAreaHeight) / 2, width, (height + OcrCaptureActivity.scannedAreaHeight) / 2);

        for (int i = 0; i < items.size(); ++i) {

            Log.i(TAG, " found at: " + items.valueAt(i).getBoundingBox().toString());

            String textValue = "";
            TextBlock item = items.valueAt(i);
            if (item != null && item.getValue() != null) {

                OcrGraphic graphic = new OcrGraphic(mGraphicOverlay, item);

                // Defining item area by transforming with screen resolution other dependencies
                int left = (int)graphic.translateX(items.valueAt(i).getBoundingBox().left);
                int right = (int)graphic.translateX(items.valueAt(i).getBoundingBox().right);
                int top = (int)graphic.translateY(items.valueAt(i).getBoundingBox().top);
                int bottom = (int)graphic.translateY(items.valueAt(i).getBoundingBox().bottom);
                Rect itemRect = new Rect(left, top, right, bottom);

                // Ignoring if item was not in considering area
                if (!scanAreaRect.contains(itemRect)) {
                    Log.i(TAG, "Not found in scan area:"+ items.valueAt(i).getValue()+ " - " + scanAreaRect.contains(itemRect));
                    continue;
                }

                Log.d(TAG, item.getValue());

                // Alphabet x 3 + Numeric x 2 + (Alphabet or Numeric) x 1 + Numeric x 3 + Alphabet x 1
                String regex = "([a-zA-Z]{3})([0-9]{2})([a-zA-Z0-9])([0-9]{3})([a-zA-Z])";
                Pattern regexPattern = Pattern.compile( regex);
                Matcher matcher = regexPattern.matcher(item.getValue());

                // Parsing necessary data
                if(matcher.find()){
                    textValue = matcher.group(0);
                    // Adding if found in defined area
                    mGraphicOverlay.add(new OcrGraphic(mGraphicOverlay, item, textValue));
                }

                // Showing and sending as automatic captured, returning to initiator
                if (!textValue.isEmpty()) {
                    Log.d(TAG, textValue);
                    detectorResultInterface.onMatchFound(textValue);
                    break;
                }
            }
        }
    }

    /**
     * Frees the resources associated with this detection processor.
     */
    @Override
    public void release() {
        mGraphicOverlay.clear();
    }
}
