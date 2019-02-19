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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Point;
import android.hardware.Camera;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.vision.text.TextBlock;
import com.google.android.gms.vision.text.TextRecognizer;

import java.io.IOException;

/**
 * マルチトラッカーアプリのアクティビティこのアプリは、テキストを検出し、リア側のカメラで値を表示します。
 * 検出中に、各TextBlockの位置、サイズ、および内容を示すために、オーバーレイグラフィックが描画されます。
 */
public final class OcrCaptureActivity extends AppCompatActivity {
    private static final String TAG = "OcrCapture";

    // 必要に応じて再生サービスの更新を処理するための意図要求コード。
    private static final int RC_HANDLE_GMS = 9001;

    // 許可要求コードは<256
    private static final int RC_HANDLE_CAMERA_PERM = 2;

    // インテントで余分なデータを渡すために使用される定数
    public static final String TextBlockObject = "String";
    public static final int scannedAreaHeight = 330;

    private CameraSource mCameraSource;
    private CameraSourcePreview mPreview;
    private TextView mLabel;
    private GraphicOverlay<OcrGraphic> mGraphicOverlay;

    // タップとピンチを検出するヘルパーオブジェクト。
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetector gestureDetector;

    int width,height, actionBarHeight;

    /**
     * UIを初期化し、検出器のパイプラインを作成します。
     */
    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.ocr_capture);



        mPreview = (CameraSourcePreview) findViewById(R.id.preview);
        mGraphicOverlay = (GraphicOverlay<OcrGraphic>) findViewById(R.id.graphicOverlay);
        mLabel = (TextView) findViewById(R.id.mLabel);

        // 変更箇所（メッセージを常に表示しておく）
        mLabel.setText("発行コードまたは認証番号をスキャンして下さい");


        // Calculate ActionBar height
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true))
        {
            actionBarHeight = TypedValue.complexToDimensionPixelSize(tv.data,getResources().getDisplayMetrics());
        }

        // Defining screen size for making compatible with different screen size and resolutions
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        width = size.x;
        height = size.y - actionBarHeight;


        // カメラにアクセスする前に、カメラの許可を確認してください。許可がまだ与えられていない場合は、許可を要求してください。
        int rc = ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA);
        if (rc == PackageManager.PERMISSION_GRANTED) {
            createCameraSource(true, false);
        } else {
            requestCameraPermission();
        }


        gestureDetector = new GestureDetector(this, new CaptureGestureListener());
        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleListener());

    }

    /**
     * カメラ権限の要求を処理します。これには、許可が必要な理由の「スナックバー」メッセージを表示し、要求を送信することが含まれます。
     */
    private void requestCameraPermission() {
        Log.w(TAG, "Camera permission is not granted. Requesting permission");

        final String[] permissions = new String[]{Manifest.permission.CAMERA};
        if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
            ActivityCompat.requestPermissions(this, permissions, RC_HANDLE_CAMERA_PERM);
            return;
        }

        final Activity thisActivity = this;

        View.OnClickListener listener = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ActivityCompat.requestPermissions(thisActivity, permissions,
                        RC_HANDLE_CAMERA_PERM);
            }
        };

        Snackbar.make(mGraphicOverlay, R.string.permission_camera_rationale,
                Snackbar.LENGTH_INDEFINITE)
                .setAction(R.string.ok, listener)
                .show();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean b = scaleGestureDetector.onTouchEvent(e);

        boolean c = gestureDetector.onTouchEvent(e);

        return b || c || super.onTouchEvent(e);
    }

    /**
     * カメラを作成して開始します。これは、ocr検出器が長距離の小さなテキストサンプルを検出することを可能にするために、
     * 他の検出例と比較してより高い分解能を使用することに留意されたい。
     * InlinedApiを抑制するには、定数を使用する前に最小限のバージョンが満たされているかどうかチェックする必要があります。
     */
    @SuppressLint("InlinedApi")
    private void createCameraSource(boolean autoFocus, boolean useFlash) {
        Context context = getApplicationContext();

        // テキスト認識ツールが作成され、テキストが検索されます。
        // 関連付けられたプロセッサインスタンスは、テキスト認識結果を受け取り、
        // スクリーン上の各テキストブロックのグラフィックスを表示するように設定される。
        TextRecognizer textRecognizer = new TextRecognizer.Builder(context).build();
        // Defining with callback listener when detected necessary one
        textRecognizer.setProcessor(new OcrDetectorProcessor(mGraphicOverlay, width, height, new DetectorResultInterface() {
            @Override
            public void onMatchFound(String matchedItem) {
                if (matchedItem != null) {

                    Log.i(TAG, "______________________________________________"+matchedItem);
                    Intent data = new Intent();
                    data.putExtra(TextBlockObject, matchedItem);
                    setResult(Activity.RESULT_OK, data);
                    finish();
                }
            }

            @Override
            public void onMatchError(String ErrorMsg) {

            }
        }));

        if (!textRecognizer.isOperational()) {
            // 注：Vision APIを使用するアプリケーションが初めてデバイスにインストールされた場合、
            // GMSは検出を行うためにデバイスにネイティブライブラリをダウンロードします。
            // 通常、これはアプリが初めて実行される前に完了します。
            // しかし、そのダウンロードがまだ完了していなければ、上記の呼び出しはテキスト、バーコード、または顔を検出しません。
            // isOperational（）を使用して、必要なネイティブライブラリが現在利用可能かどうかを確認できます。
            // ライブラリーのダウンロードがデバイスで完了すると、検出器は自動的に使用可能になります。
            Log.w(TAG, "Detector dependencies are not yet available.");

            // 保存容量が少ないかどうかを確認します。保存容量が少ない場合、ネイティブライブラリはダウンロードされないため、検出は機能しなくなります。
            IntentFilter lowstorageFilter = new IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW);
            boolean hasLowStorage = registerReceiver(null, lowstorageFilter) != null;

            if (hasLowStorage) {
                Toast.makeText(this, R.string.low_storage_error, Toast.LENGTH_LONG).show();
                Log.w(TAG, getString(R.string.low_storage_error));
            }
        }

        // カメラを作成して開始します。これは、他の検出例と比較してより高い解像度を使用して、
        // テキスト認識装置が小さなテキスト部分を検出できるようにすることに留意されたい。
        mCameraSource =
                new CameraSource.Builder(getApplicationContext(), textRecognizer)
                        .setFacing(CameraSource.CAMERA_FACING_BACK)
                        .setRequestedPreviewSize(width, height)
                        .setRequestedFps(2.0f)
                        .setFlashMode(useFlash ? Camera.Parameters.FLASH_MODE_TORCH : null)
                        .setFocusMode(autoFocus ? Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE : null)
                        .build();
    }

    /**
     * Restarts the camera.
     */
    @Override
    protected void onResume() {
        super.onResume();
        startCameraSource();
    }

    /**
     * Stops the camera.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (mPreview != null) {
            mPreview.stop();
        }
    }

    /**
     * カメラソース、関連する検出器、およびその他の処理パイプラインに関連するリソースを解放します。
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mPreview != null) {
            mPreview.release();
        }
    }

    /**
     * アクセス許可を要求した結果のコールバック。
     * This method is invoked for every call on {@link #requestPermissions(String[], int)}.
     * <p>
     * <strong>Note:</strong>
     * ユーザーとの対話要求が中断される可能性があります。
     * この場合、空のパーミッションが返され、配列はキャンセルとして扱われます。
     * </p>
     *
     * @param requestCode  The request code passed in {@link #requestPermissions(String[], int)}.
     * @param permissions  The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *                     which is either {@link PackageManager#PERMISSION_GRANTED}
     *                     or {@link PackageManager#PERMISSION_DENIED}. Never null.
     * @see #requestPermissions(String[], int)
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        if (requestCode != RC_HANDLE_CAMERA_PERM) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        if (grantResults.length != 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Camera permission granted - initialize the camera source");
            // We have permission, so create the camerasource
            createCameraSource(true, false);
            return;
        }

        Log.e(TAG, "Permission not granted: results len = " + grantResults.length +
                " Result code = " + (grantResults.length > 0 ? grantResults[0] : "(empty)"));

        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                finish();
            }
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Multitracker sample")
                .setMessage(R.string.no_camera_permission)
                .setPositiveButton(R.string.ok, listener)
                .show();
    }

    /**
     * 存在する場合、カメラソースを開始または再開します。
     * カメラソースがまだ存在しない場合（たとえば、カメラソースが作成される前にonResumeが呼び出されたため）、
     * カメラソースが作成されると、これが再び呼び出されます。
     */
    private void startCameraSource() throws SecurityException {
        // デバイスに再生サービスが利用可能であることを確認します。
        int code = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                getApplicationContext());
        if (code != ConnectionResult.SUCCESS) {
            Dialog dlg =
                    GoogleApiAvailability.getInstance().getErrorDialog(this, code, RC_HANDLE_GMS);
            dlg.show();
        }

        if (mCameraSource != null) {
            try {
                mPreview.start(mCameraSource, mGraphicOverlay);
            } catch (IOException e) {
                Log.e(TAG, "Unable to start camera source.", e);
                mCameraSource.release();
                mCameraSource = null;
            }
        }
    }

    /**
     * onTapは、タップ位置の下にある最初のTextBlockを取得し、初期化アクティビティに戻すために呼び出されます。
     *
     * @param rawX - the raw position of the tap
     * @param rawY - the raw position of the tap.
     * @return true if the activity is ending.
     */
    private boolean onTap(float rawX, float rawY) {
        OcrGraphic graphic = mGraphicOverlay.getGraphicAtLocation(rawX, rawY);
        TextBlock text = null;
        if (graphic != null) {
            text = graphic.getTextBlock();
            if (text != null && text.getValue() != null) {
                Intent data = new Intent();
                data.putExtra(TextBlockObject, text.getValue());
                setResult(Activity.RESULT_OK, data);
                finish();
            }
            else {
                Log.d(TAG, "text data is null");
            }
        }
        else {
            Log.d(TAG,"no text detected");
        }
        return text != null;
    }

    private class CaptureGestureListener extends GestureDetector.SimpleOnGestureListener {

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            return onTap(e.getRawX(), e.getRawY()) || super.onSingleTapConfirmed(e);
        }
    }

    private class ScaleListener implements ScaleGestureDetector.OnScaleGestureListener {

        /**
         * 進行中のジェスチャーのスケーリングイベントに応答します。
         * ポインタの動きによって報告される。
         *
         * @param detector イベントを報告する検出器 - これを使用して、イベント状態に関する拡張情報を取得します。
         * @return 検出器がこのイベントを処理されたものとみなすべきかどうか。
         * 事象が処理されなかった場合、事象が処理されるまで検出器は動きを蓄積し続ける。
         * これは、たとえば、アプリケーションが0.01より大きい場合にスケーリング係数を更新するだけの場合に便利です。
         */
        @Override
        public boolean onScale(ScaleGestureDetector detector) {
            return false;
        }

        /**
         * スケーリングジェスチャーの開始に応答します。新しいポインタが下がったことで報告されます。
         *
         * @param detector イベントを報告する検出器 - これを使用して、イベント状態に関する拡張情報を取得します。
         * @return 検出器がこのジェスチャを認識し続けるかどうか。
         * たとえば、ジェスチャが合理的な領域外の焦点で始まっている場合、
         * onScaleBegin（）はfalseを返し、残りのジェスチャを無視します。
         */
        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            return true;
        }

        /**
         * スケールジェスチャーの終了に応答します。既存のポインタによって報告される。
         * <p/>
         * Once a scale has ended, {@link ScaleGestureDetector#getFocusX()}
         * and {@link ScaleGestureDetector#getFocusY()} 画面に残っているポインタの焦点を返します。
         * @param detector イベントを報告する検出器 - これを使用して、イベント状態に関する拡張情報を取得します。
         */
        @Override
        public void onScaleEnd(ScaleGestureDetector detector) {
            mCameraSource.doZoom(detector.getScaleFactor());
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if(keyCode == KeyEvent.KEYCODE_BACK) {
            setResult(Activity.RESULT_OK, new Intent());
            finish();
            return true;
        }
        return false;
    }
}
