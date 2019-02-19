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
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.os.Build;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresPermission;
import android.support.annotation.StringDef;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import com.google.android.gms.common.images.Size;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.Frame;

import java.io.IOException;
import java.lang.Thread.State;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// 注：画像を保存するために間接的なバイトバッファを使用するため、これにはGoogle Playサービス8.1以上が必要です。

/**
 * 基になる{@link com.google.android.gms.vision.Detector}と組み合わせてカメラを管理します。
 * これは、指定されたレートでカメラからプレビューフレームを受信し、それらのフレームを処理できる速度でそれらのフレームを検出器に送る。
 *
 * このカメラソースは、ラグを最小限に抑えながら、できるだけ早くプレビューフレームの処理を管理するために最善の努力をします。
 * このように、検出器がカメラによって生成されたフレームの速度に追いつけない場合、フレームが落とされる可能性があります。
 * 選択したカメラハードウェアと検出器オプションの機能で動作するフレームレートを指定するには、CameraSource.Builder＃setRequestedFps（float）}を使用する必要があります。
 * CPU使用率があなたが望む以上に高い場合は、FPSを減らすことを検討してください。
 * カメラのプレビューまたは検出器の結果があまりにも「ジャッキー」である場合は、FPSを増やすことを検討することをお勧めします。
 * カメラを使用するには、次のAndroidの許可が必要です。
 * <li> android.permissions.CAMERA </ li>
 */
@SuppressWarnings("deprecation")
public class CameraSource {
    @SuppressLint("InlinedApi")
    public static final int CAMERA_FACING_BACK = CameraInfo.CAMERA_FACING_BACK;
    @SuppressLint("InlinedApi")
    public static final int CAMERA_FACING_FRONT = CameraInfo.CAMERA_FACING_FRONT;

    private static final String TAG = "OpenCameraSource";

    /**
     * ダミーサーフェステクスチャには、選択した名前を割り当てる必要があります。 OpenGLコンテキストを使用することはないので、ここでは任意のIDを選択できます。
     */
    private static final int DUMMY_TEXTURE_NAME = 100;

    /**
     * プレビューサイズアスペクト比と画像サイズアスペクト比の絶対差がこの許容値より小さい場合、それらは同じアスペクト比であるとみなされます。
     */
    private static final float ASPECT_RATIO_TOLERANCE = 0.01f;

    @StringDef({
        Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
        Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
        Camera.Parameters.FOCUS_MODE_AUTO,
        Camera.Parameters.FOCUS_MODE_EDOF,
        Camera.Parameters.FOCUS_MODE_FIXED,
        Camera.Parameters.FOCUS_MODE_INFINITY,
        Camera.Parameters.FOCUS_MODE_MACRO
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface FocusMode {}

    @StringDef({
        Camera.Parameters.FLASH_MODE_ON,
        Camera.Parameters.FLASH_MODE_OFF,
        Camera.Parameters.FLASH_MODE_AUTO,
        Camera.Parameters.FLASH_MODE_RED_EYE,
        Camera.Parameters.FLASH_MODE_TORCH
    })
    @Retention(RetentionPolicy.SOURCE)
    private @interface FlashMode {}

    private Context mContext;

    private final Object mCameraLock = new Object();

    // Guarded by mCameraLock
    private Camera mCamera;

    private int mFacing = CAMERA_FACING_BACK;

    /**
     * デバイスの回転、したがってデバイスからキャプチャされた関連プレビューイメージ。
     * See {@link Frame.Metadata#getRotation()}.
     */
    private int mRotation;

    private Size mPreviewSize;

    // これらの値は、呼び出し元によって要求されることがあります。
    // ハードウェアの制限のため、近い値を選択する必要があるかもしれませんが、正確に同じ値を選択する必要はありません。
    private float mRequestedFps = 30.0f;
    private int mRequestedPreviewWidth = 1024;
    private int mRequestedPreviewHeight = 768;


    private String mFocusMode = null;
    private String mFlashMode = null;

    // これらのインスタンスは、それらの基礎となるリソースのGCを避けるために保持する必要があります。
    // これらを作成するメソッドの外部では使用されませんが、依然としてハードリファレンスが維持されている必要があります。
    private SurfaceView mDummySurfaceView;
    private SurfaceTexture mDummySurfaceTexture;

    /**
     * フレームがカメラから利用可能になると、フレームとともに検出器に呼び出すための専用のスレッドおよび関連する実行可能ファイル。
     */
    private Thread mProcessingThread;
    private FrameProcessingRunnable mFrameProcessor;

    /**
     * カメラから受け取ったバイト配列とそれに関連するバイトバッファを変換するマップ。
     * 私たちは内部的にバイトバッファーを使用しています。これは後でネイティブコードを呼び出す方が効率的な方法です（コピーを避けるため）。
     */
    private Map<byte[], ByteBuffer> mBytesToByteBuffer = new HashMap<>();

    //==============================================================================================
    // Builder
    //==============================================================================================

    /**
     * 関連するカメラソースを設定および作成するためのビルダー。
     */
    public static class Builder {
        private final Detector<?> mDetector;
        private CameraSource mCameraSource = new CameraSource();

        /**
         * 指定されたコンテキストと検出器を使用してカメラソースビルダーを作成します。
         * カメラプレビュー画像は、カメラソースを開始すると関連する検出器にストリーミングされます。
         */
        public Builder(Context context, Detector<?> detector) {
            if (context == null) {
                throw new IllegalArgumentException("No context supplied.");
            }
            if (detector == null) {
                throw new IllegalArgumentException("No detector supplied.");
            }

            mDetector = detector;
            mCameraSource.mContext = context;
        }

        /**
         * 要求されたフレームレートを1秒あたりのフレーム数で設定します。
         * 正確に要求された値が利用可能でない場合、最も適合する利用可能な値が選択される。
         * Default: 30.
         */
        public Builder setRequestedFps(float fps) {
            if (fps <= 0) {
                throw new IllegalArgumentException("Invalid fps: " + fps);
            }
            mCameraSource.mRequestedFps = fps;
            return this;
        }

        public Builder setFocusMode(@FocusMode String mode) {
            mCameraSource.mFocusMode = mode;
            return this;
        }

        public Builder setFlashMode(@FlashMode String mode) {
            mCameraSource.mFlashMode = mode;
            return this;
        }

        /**
         * カメラフレームの幅と高さをピクセル単位で設定します。
         * 正確な希望値に使用可能なオプションがない場合は、使用可能な最適なオプションが選択されます。
         * また、関連する全画像サイズのアスペクト比に対応するプレビューサイズを選択しようと試みます（該当する場合）。デフォルト：1024x768
         */
        public Builder setRequestedPreviewSize(int width, int height) {
            // 要求された範囲を可能性の範囲内に制限する。
            // 1000000の選択は少し恣意的です - デバイスがサポートできる解像度をはるかに上回るように意図されています。
            // 私たちは、後でこのコードでintのオーバーフローを避けるためにこれをバインドしました。
            final int MAX = 1000000;
            if ((width <= 0) || (width > MAX) || (height <= 0) || (height > MAX)) {
                throw new IllegalArgumentException("Invalid preview size: " + width + "x" + height);
            }
            mCameraSource.mRequestedPreviewWidth = width;
            mCameraSource.mRequestedPreviewHeight = height;
            return this;
        }

        /**
         * 使用するカメラを設定します（{@link #CAMERA_FACING_BACK}または{@link #CAMERA_FACING_FRONT}）。デフォルト：裏向き。
         */
        public Builder setFacing(int facing) {
            if ((facing != CAMERA_FACING_BACK) && (facing != CAMERA_FACING_FRONT)) {
                throw new IllegalArgumentException("Invalid camera: " + facing);
            }
            mCameraSource.mFacing = facing;
            return this;
        }

        /**
         * カメラソースのインスタンスを作成します。
         */
        public CameraSource build() {
            mCameraSource.mFrameProcessor = mCameraSource.new FrameProcessingRunnable(mDetector);
            return mCameraSource;
        }
    }

    //==============================================================================================
    // Camera1 APIのブリッジ機能
    //==============================================================================================

    /**
     * 実際のイメージキャプチャの瞬間を伝えるために使用されるコールバックインターフェイス。
     */
    public interface ShutterCallback {
        /**
         * センサーから写真がキャプチャされる瞬間に可能な限り近くに呼び出されます。
         * これは、シャッター音を鳴らすか、カメラ操作の他のフィードバックを与える良い機会です。
         * これは、写真がトリガーされてからしばらく時間がかかることがありますが、実際のデータが利用可能になるまでには時間がかかることがあります。
         */
        void onShutter();
    }

    /**
     * 写真キャプチャから画像データを供給するためのコールバックインターフェイス。
     */
    public interface PictureCallback {
        /**
         * 写真撮影後に画像データが利用可能になったときに呼び出されます。
         * データの形式はjpegバイナリです。
         */
        void onPictureTaken(byte[] data);
    }

    /**
     * カメラの自動フォーカスの完了時に通知するために使用されるコールバックインターフェイス。
     */
    public interface AutoFocusCallback {
        /**
         * カメラのオートフォーカスが完了すると呼び出されます。
         * カメラがオートフォーカスをサポートせず、autoFocusが呼び出された場合、onAutoFocusは<code> success </ code>の偽値を<code> true </ code>に設定してすぐに呼び出されます。
         * オートフォーカスルーチンは、オート露出とオートホワイトバランスが完了した後にロックしません。
         * @param success true フォーカスが成功した場合はfalse、そうでない場合はfalse
         */
        void onAutoFocus(boolean success);
    }

    /**
     * オートフォーカスの開始と停止を通知するためのコールバックインターフェイス。
     * <p/>
     * <p>これは連続オートフォーカスモードでのみサポートされています -- {@link
     * Camera.Parameters#FOCUS_MODE_CONTINUOUS_VIDEO} and {@link
     * Camera.Parameters#FOCUS_MODE_CONTINUOUS_PICTURE}.
     * アプリケーションはこれに基づいてオートフォーカスアニメーションを表示できます。</p>
     */
    public interface AutoFocusMoveCallback {
        /**
         * カメラのオートフォーカスの開始または停止時に呼び出されます。
         *
         * @param start true フォーカスが移動を開始した場合はフォーカス、移動が停止した場合はfalse
         */
        void onAutoFocusMoving(boolean start);
    }

    //==============================================================================================
    // Public
    //==============================================================================================

    /**
     * カメラを停止し、カメラとその下にある検出器のリソースを解放します。
     */
    public void release() {
        synchronized (mCameraLock) {
            stop();
            mFrameProcessor.release();
        }
    }

    /**
     * カメラを開き、下にある検出器にプレビューフレームを送信します。
     * プレビューフレームは表示されません。
     *
     * @throws IOException カメラのプレビューテクスチャまたはディスプレイを初期化できなかった場合
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    public CameraSource start() throws IOException {
        synchronized (mCameraLock) {
            if (mCamera != null) {
                return this;
            }

            mCamera = createCamera();

            // SurfaceTextureはHoneycomb（11）に導入されているので、実行していればアンドロイドの古いバージョンです。 SurfaceViewを使用するために後退します。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                mDummySurfaceTexture = new SurfaceTexture(DUMMY_TEXTURE_NAME);
                mCamera.setPreviewTexture(mDummySurfaceTexture);
            } else {
                mDummySurfaceView = new SurfaceView(mContext);
                mCamera.setPreviewDisplay(mDummySurfaceView.getHolder());
            }
            mCamera.startPreview();

            mProcessingThread = new Thread(mFrameProcessor);
            mFrameProcessor.setActive(true);
            mProcessingThread.start();
        }
        return this;
    }

    /**
     * カメラを開き、下にある検出器にプレビューフレームを送信します。
     * 付属のサーフェスホルダーはプレビューに使用され、フレームをユーザーに表示することができます。
     *
     * @param surfaceHolder プレビューフレームに使用するサーフェスホルダ
     * @throws IOException 供給された表面ホルダーがプレビュー表示として使用できなかった場合
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    public CameraSource start(SurfaceHolder surfaceHolder) throws IOException {
        synchronized (mCameraLock) {
            if (mCamera != null) {
                return this;
            }

            mCamera = createCamera();
            mCamera.setPreviewDisplay(surfaceHolder);
            mCamera.startPreview();

            mProcessingThread = new Thread(mFrameProcessor);
            mFrameProcessor.setActive(true);
            mProcessingThread.start();
        }
        return this;
    }

    /**
     * カメラを閉じ、下にあるフレーム検出器へのフレームの送信を停止します。
     * このカメラのソースは、{@link #start（）}または{@link #start（SurfaceHolder）}を呼び出すことで、再起動することができます。
     * 代わりに、このカメラソースを完全にシャットダウンし、その下にある検出器のリソースを解放するためにコールしてください。
     */
    public void stop() {
        synchronized (mCameraLock) {
            mFrameProcessor.setActive(false);
            if (mProcessingThread != null) {
                try {
                    // スレッドが完了するのを待って、複数のスレッドを同時に実行することができないようにします（つまり、停止後にstartをあまりにも早く呼び出すと起こります）。
                    mProcessingThread.join();
                } catch (InterruptedException e) {
                    Log.d(TAG, "Frame processing thread interrupted on release.");
                }
                mProcessingThread = null;
            }

            // oom例外を防ぐためにバッファをクリアする
            mBytesToByteBuffer.clear();

            if (mCamera != null) {
                mCamera.stopPreview();
                mCamera.setPreviewCallbackWithBuffer(null);
                try {
                    // 我々はジンジャーブレッドに戻って互換性を望んでいますが、SurfaceTextureはHoneycombまで紹介されていませんでした。
                    // インターフェイスはSurfaceTextureを使用できないため、開発者がプレビューを表示したい場合は、SurfaceHolderを使用する必要があります。
                    // 開発者がプレビューを表示したくない場合は、少なくともHoneycombを実行している場合はSurfaceTextureを使用します。
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
                        mCamera.setPreviewTexture(null);

                    } else {
                        mCamera.setPreviewDisplay(null);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to clear camera preview: " + e);
                }
                mCamera.release();
                mCamera = null;
            }
        }
    }

    /**
     * 基になるカメラで現在使用されているプレビューサイズを返します。
     */
    public Size getPreviewSize() {
        return mPreviewSize;
    }

    /**
     * 選択したカメラを返します。 {@link #CAMERA_FACING_BACK}または{@link #CAMERA_FACING_FRONT}のいずれかです。
     */
    public int getCameraFacing() {
        return mFacing;
    }

    public int doZoom(float scale) {
        synchronized (mCameraLock) {
            if (mCamera == null) {
                return 0;
            }
            int currentZoom = 0;
            int maxZoom;
            Camera.Parameters parameters = mCamera.getParameters();
            if (!parameters.isZoomSupported()) {
                Log.w(TAG, "Zoom is not supported on this device");
                return currentZoom;
            }
            maxZoom = parameters.getMaxZoom();

            currentZoom = parameters.getZoom() + 1;
            float newZoom;
            if (scale > 1) {
                newZoom = currentZoom + scale * (maxZoom / 10);
            } else {
                newZoom = currentZoom * scale;
            }
            currentZoom = Math.round(newZoom) - 1;
            if (currentZoom < 0) {
                currentZoom = 0;
            } else if (currentZoom > maxZoom) {
                currentZoom = maxZoom;
            }
            parameters.setZoom(currentZoom);
            mCamera.setParameters(parameters);
            return currentZoom;
        }
    }

    /**
     * 写真の撮影を開始します。これは非同期で行われます。
     * カメラのソースは、以前に{@link #start（）}または{@link #start（SurfaceHolder）}でアクティブ化されている必要があります。
     * カメラのプレビューは写真の撮影中に中断されますが、撮影が完了すると再開します。
     * @param shutter イメージキャプチャの瞬間のコールバック、またはnull
     * @param jpeg    JPEGイメージデータのコールバック、またはnull
     */
    public void takePicture(ShutterCallback shutter, PictureCallback jpeg) {
        synchronized (mCameraLock) {
            if (mCamera != null) {
                PictureStartCallback startCallback = new PictureStartCallback();
                startCallback.mDelegate = shutter;
                PictureDoneCallback doneCallback = new PictureDoneCallback();
                doneCallback.mDelegate = jpeg;
                mCamera.takePicture(startCallback, null, null, doneCallback);
            }
        }
    }

    /**
     * 現在のフォーカスモード設定を取得します。
     *
     * @return current focus mode.
     * This value is null if the camera is not yet created.
     * Applications should call {@link #autoFocus(AutoFocusCallback)} to start the focus if focus mode is FOCUS_MODE_AUTO or FOCUS_MODE_MACRO.
     * @see Camera.Parameters#FOCUS_MODE_AUTO
     * @see Camera.Parameters#FOCUS_MODE_INFINITY
     * @see Camera.Parameters#FOCUS_MODE_MACRO
     * @see Camera.Parameters#FOCUS_MODE_FIXED
     * @see Camera.Parameters#FOCUS_MODE_EDOF
     * @see Camera.Parameters#FOCUS_MODE_CONTINUOUS_VIDEO
     * @see Camera.Parameters#FOCUS_MODE_CONTINUOUS_PICTURE
     */
    @Nullable
    @FocusMode
    public String getFocusMode() {
        return mFocusMode;
    }

    /**
     * フォーカスモードを設定します。
     *
     * @param mode the focus mode
     * @return {@code true} if the focus mode is set, {@code false} otherwise
     * @see #getFocusMode()
     */
    public boolean setFocusMode(@FocusMode String mode) {
        synchronized (mCameraLock) {
            if (mCamera != null && mode != null) {
                Camera.Parameters parameters = mCamera.getParameters();
                if (parameters.getSupportedFocusModes().contains(mode)) {
                    parameters.setFocusMode(mode);
                    mCamera.setParameters(parameters);
                    mFocusMode = mode;
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * 現在のフラッシュモード設定を取得します。
     *
     * @return current flash mode. null if flash mode setting is not
     * supported or the camera is not yet created.
     * @see Camera.Parameters#FLASH_MODE_OFF
     * @see Camera.Parameters#FLASH_MODE_AUTO
     * @see Camera.Parameters#FLASH_MODE_ON
     * @see Camera.Parameters#FLASH_MODE_RED_EYE
     * @see Camera.Parameters#FLASH_MODE_TORCH
     */
    @Nullable
    @FlashMode
    public String getFlashMode() {
        return mFlashMode;
    }

    /**
     * フラッシュモードを設定します。
     *
     * @param mode flash mode.
     * @return {@code true} if the flash mode is set, {@code false} otherwise
     * @see #getFlashMode()
     */
    public boolean setFlashMode(@FlashMode String mode) {
        synchronized (mCameraLock) {
            if (mCamera != null && mode != null) {
                Camera.Parameters parameters = mCamera.getParameters();
                if (parameters.getSupportedFlashModes().contains(mode)) {
                    parameters.setFlashMode(mode);
                    mCamera.setParameters(parameters);
                    mFlashMode = mode;
                    return true;
                }
            }

            return false;
        }
    }

    /**
     * カメラの自動フォーカスを開始し、カメラがフォーカスされているときに実行されるコールバック関数を登録します。
     * このメソッドは、プレビューがアクティブな場合（{@link #start（）}または{@link #start（SurfaceHolder）}と{@link #stop（）}または{@link #release（）}の間）にのみ有効です。
     * 呼び出し側は、このメソッドを呼び出す必要があるかどうかを判断するために、getFocusMode（）をチェックする必要があります。カメラが自動焦点をサポートしていない場合は、
     * それはノーオペレーションであり、すぐに呼び出されるのはAutoFocusCallback＃onAutoFocus（boolean）}コールバックです。
     * 現在のフラッシュモードがCamera.Parameters＃FLASH_MODE_OFFでなければ、ドライバとカメラのハードウェアによっては、オートフォーカス中にフラッシュが発光することがあります。
     *
     * @param cb the callback to run
     * @see #cancelAutoFocus()
     */
    public void autoFocus(@Nullable AutoFocusCallback cb) {
        synchronized (mCameraLock) {
            if (mCamera != null) {
                CameraAutoFocusCallback autoFocusCallback = null;
                if (cb != null) {
                    autoFocusCallback = new CameraAutoFocusCallback();
                    autoFocusCallback.mDelegate = cb;
                }
                mCamera.autoFocus(autoFocusCallback);
            }
        }
    }

    /**
     * 進行中のオートフォーカス機能をキャンセルします。
     * オートフォーカスが現在進行中であるかどうかにかかわらず、この機能はフォーカス位置をデフォルトに戻します。
     * カメラがオートフォーカスをサポートしていない場合、これはノーオペレーションです。
     *
     * @see #autoFocus(AutoFocusCallback)
     */
    public void cancelAutoFocus() {
        synchronized (mCameraLock) {
            if (mCamera != null) {
                mCamera.cancelAutoFocus();
            }
        }
    }

    /**
     * カメラの自動フォーカス移動のコールバックを設定します。
     *
     * @param cb the callback to run
     * @return {@code true} if the operation is supported (i.e. from Jelly Bean), {@code false}
     * otherwise
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public boolean setAutoFocusMoveCallback(@Nullable AutoFocusMoveCallback cb) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN) {
            return false;
        }

        synchronized (mCameraLock) {
            if (mCamera != null) {
                CameraAutoFocusMoveCallback autoFocusMoveCallback = null;
                if (cb != null) {
                    autoFocusMoveCallback = new CameraAutoFocusMoveCallback();
                    autoFocusMoveCallback.mDelegate = cb;
                }
                mCamera.setAutoFocusMoveCallback(autoFocusMoveCallback);
            }
        }

        return true;
    }

    //==============================================================================================
    // Private
    //==============================================================================================

    /**
     * Builderクラスによる作成のみを許可します。
     */
    private CameraSource() {
    }

    /**
     * 非推奨のAPIが公開されないように、camera1シャッターコールバックをラップします。
     */
    private class PictureStartCallback implements Camera.ShutterCallback {
        private ShutterCallback mDelegate;

        @Override
        public void onShutter() {
            if (mDelegate != null) {
                mDelegate.onShutter();
            }
        }
    }

    /**
     * 最終的なコールバックをカメラシーケンスでラップします。これにより、画像の撮影後に自動的にカメラプレビューをオンに戻すことができます。
     */
    private class PictureDoneCallback implements Camera.PictureCallback {
        private PictureCallback mDelegate;

        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            if (mDelegate != null) {
                mDelegate.onPictureTaken(data);
            }
            synchronized (mCameraLock) {
                if (mCamera != null) {
                    mCamera.startPreview();
                }
            }
        }
    }

    /**
     * 廃止予定のAPIが公開されないように、カメラ1自動フォーカスコールバックをラップします。
     */
    private class CameraAutoFocusCallback implements Camera.AutoFocusCallback {
        private AutoFocusCallback mDelegate;

        @Override
        public void onAutoFocus(boolean success, Camera camera) {
            if (mDelegate != null) {
                mDelegate.onAutoFocus(success);
            }
        }
    }

    /**
     * 廃止予定のAPIが公開されないように、カメラ1自動フォーカス移動コールバックをラップします。
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private class CameraAutoFocusMoveCallback implements Camera.AutoFocusMoveCallback {
        private AutoFocusMoveCallback mDelegate;

        @Override
        public void onAutoFocusMoving(boolean start, Camera camera) {
            if (mDelegate != null) {
                mDelegate.onAutoFocusMoving(start);
            }
        }
    }

    /**
     * カメラを開き、ユーザー設定を適用します。
     *
     * @throws RuntimeException if the method fails
     */
    @SuppressLint("InlinedApi")
    private Camera createCamera() {
        int requestedCameraId = getIdForRequestedCamera(mFacing);
        if (requestedCameraId == -1) {
            throw new RuntimeException("Could not find requested camera.");
        }
        Camera camera = Camera.open(requestedCameraId);

        SizePair sizePair = selectSizePair(camera, mRequestedPreviewWidth, mRequestedPreviewHeight);
        if (sizePair == null) {
            throw new RuntimeException("Could not find suitable preview size.");
        }
        Size pictureSize = sizePair.pictureSize();
        mPreviewSize = sizePair.previewSize();

        int[] previewFpsRange = selectPreviewFpsRange(camera, mRequestedFps);
        if (previewFpsRange == null) {
            throw new RuntimeException("Could not find suitable preview frames per second range.");
        }

        Camera.Parameters parameters = camera.getParameters();

        if (pictureSize != null) {
            parameters.setPictureSize(pictureSize.getWidth(), pictureSize.getHeight());
        }

        parameters.setPreviewSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        parameters.setPreviewFpsRange(
                previewFpsRange[Camera.Parameters.PREVIEW_FPS_MIN_INDEX],
                previewFpsRange[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]);
        parameters.setPreviewFormat(ImageFormat.NV21);

        setRotation(camera, parameters, requestedCameraId);

        if (mFocusMode != null) {
            if (parameters.getSupportedFocusModes().contains(
                    mFocusMode)) {
                parameters.setFocusMode(mFocusMode);
            } else {
                Log.i(TAG, "Camera focus mode: " + mFocusMode +
                    " is not supported on this device.");
            }
        }

        // setting mFocusMode to the one set in the params
        mFocusMode = parameters.getFocusMode();

        if (mFlashMode != null) {
            if (parameters.getSupportedFlashModes().contains(
                    mFlashMode)) {
                parameters.setFlashMode(mFlashMode);
            } else {
                Log.i(TAG, "Camera flash mode: " + mFlashMode +
                    " is not supported on this device.");
            }
        }

        // setting mFlashMode to the one set in the params
        mFlashMode = parameters.getFlashMode();

        camera.setParameters(parameters);

        // Four frame buffers are needed for working with the camera:
        //
        //   one for the frame that is currently being executed upon in doing detection
        //   one for the next pending frame to process immediately upon completing detection
        //   two for the frames that the camera uses to populate future preview images
        camera.setPreviewCallbackWithBuffer(new CameraPreviewCallback());
        camera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
        camera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
        camera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));
        camera.addCallbackBuffer(createPreviewBuffer(mPreviewSize));

        return camera;
    }

    /**
     * 対面している方向で指定されたカメラのIDを取得します。
     * そのようなカメラが見つからない場合は-1を返します。
     *
     * @param facing the desired camera (front-facing or rear-facing)
     */
    private static int getIdForRequestedCamera(int facing) {
        CameraInfo cameraInfo = new CameraInfo();
        for (int i = 0; i < Camera.getNumberOfCameras(); ++i) {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == facing) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 所望の幅と高さを考慮して、最も適切なプレビューと画像サイズを選択します。
     * プレビューサイズのみが必要な場合もありますが、プレビューサイズとピクチャサイズの両方を同じアスペクト比にする必要があるため、両方を一緒に見つける必要があります。
     * 一部のハードウェアでは、プレビューサイズのみを設定すると、歪んだ画像が表示されます。
     *
     * @param camera        the camera to select a preview size from
     * @param desiredWidth  the desired width of the camera preview frames
     * @param desiredHeight the desired height of the camera preview frames
     * @return the selected preview and picture size pair
     */
    private static SizePair selectSizePair(Camera camera, int desiredWidth, int desiredHeight) {
        List<SizePair> validPreviewSizes = generateValidPreviewSizeList(camera);

        // 最良のサイズを選択する方法は、所望の値と幅と高さの実際の値との間の差の合計を最小にすることである。
        // これは確かに最適なサイズを選択する唯一の方法ではありませんが、最も近いアスペクト比と最も近いピクセル領域を使用する場合との間で適切なトレードオフを提供します。
        SizePair selectedPair = null;
        int minDiff = Integer.MAX_VALUE;
        for (SizePair sizePair : validPreviewSizes) {
            Size size = sizePair.previewSize();
            int diff = Math.abs(size.getWidth() - desiredWidth) +
                    Math.abs(size.getHeight() - desiredHeight);
            if (diff < minDiff) {
                selectedPair = sizePair;
                minDiff = diff;
            }
        }

        return selectedPair;
    }

    /**
     * プレビューサイズとそれに対応する同じアスペクト比の画像サイズを格納します。
     * 一部のデバイスでプレビュー画像が歪んで表示されないようにするには、画像のサイズをプレビューサイズと同じ縦横比のサイズに設定する必要があります。そうしないと、プレビューが歪んでしまうことがあります。
     * ピクチャサイズがnullの場合、プレビューサイズと同じアスペクト比のピクチャサイズはありません。
     */
    private static class SizePair {
        private Size mPreview;
        private Size mPicture;

        public SizePair(android.hardware.Camera.Size previewSize,
                        android.hardware.Camera.Size pictureSize) {
            mPreview = new Size(previewSize.width, previewSize.height);
            if (pictureSize != null) {
                mPicture = new Size(pictureSize.width, pictureSize.height);
            }
        }

        public Size previewSize() {
            return mPreview;
        }

        @SuppressWarnings("unused")
        public Size pictureSize() {
            return mPicture;
        }
    }

    /**
     * 受け入れ可能なプレビューサイズのリストを生成します。
     * プレビューサイズは、同じアスペクト比の対応するピクチャサイズがない場合には許容されない。
     * 同じアスペクト比の対応するピクチャサイズがある場合、ピクチャサイズはプレビューサイズとペアになります。
     * これは、静止画を使用しない場合でも、選択したプレビューサイズと同じアスペクト比のサイズに設定する必要があるためです。
     * そうしないと、一部のデバイスでプレビュー画像が歪むことがあります。
     */
    private static List<SizePair> generateValidPreviewSizeList(Camera camera) {
        Camera.Parameters parameters = camera.getParameters();
        List<android.hardware.Camera.Size> supportedPreviewSizes =
                parameters.getSupportedPreviewSizes();
        List<android.hardware.Camera.Size> supportedPictureSizes =
                parameters.getSupportedPictureSizes();
        List<SizePair> validPreviewSizes = new ArrayList<>();
        for (android.hardware.Camera.Size previewSize : supportedPreviewSizes) {
            float previewAspectRatio = (float) previewSize.width / (float) previewSize.height;

            // ピクチャサイズを順番にループすることで、より高い解像度を優先します。
            // 後で完全な解像度の写真を撮ることをサポートするために最高の解像度を選択します。
            for (android.hardware.Camera.Size pictureSize : supportedPictureSizes) {
                float pictureAspectRatio = (float) pictureSize.width / (float) pictureSize.height;
                if (Math.abs(previewAspectRatio - pictureAspectRatio) < ASPECT_RATIO_TOLERANCE) {
                    validPreviewSizes.add(new SizePair(previewSize, pictureSize));
                    break;
                }
            }
        }

        // プレビューサイズと同じアスペクト比の画像サイズがない場合は、すべてのプレビューサイズを許可して、カメラが対応できることを願ってください。
        // おそらくそうではないかもしれませんが、私たちはまだそれを説明しています。
        if (validPreviewSizes.size() == 0) {
            Log.w(TAG, "No preview sizes have a corresponding same-aspect-ratio picture size");
            for (android.hardware.Camera.Size previewSize : supportedPreviewSizes) {
                // The null picture size will let us know that we shouldn't set a picture size.
                validPreviewSizes.add(new SizePair(previewSize, null));
            }
        }

        return validPreviewSizes;
    }

    /**
     * 1秒あたりの希望フレーム数を考慮して、1秒あたりの範囲に最も適したプレビューフレームを選択します。
     *
     * @param camera カメラは1秒あたりのフレーム数を選択します
     * @param desiredPreviewFps カメラのプレビューフレームの1秒間に必要なフレーム数
     * @return 選択されたプレビューフレーム/秒の範囲
     */
    private int[] selectPreviewFpsRange(Camera camera, float desiredPreviewFps) {
        // カメラAPIは、浮動小数点フレームレートではなく、1000倍にスケールされた整数を使用します。
        int desiredPreviewFpsScaled = (int) (desiredPreviewFps * 1000.0f);

        // 最良の範囲を選択する方法は、所望の値とその範囲の上限と下限との間の差の合計を最小にすることである。
        // これは、所望の値が範囲外である範囲を選択することができるが、これはしばしば好ましい。
        // 例えば、所望のフレームレートが29.97である場合、範囲（30,30）はおそらく範囲（15,30）よりも望ましい。
        int[] selectedFpsRange = null;
        int minDiff = Integer.MAX_VALUE;
        List<int[]> previewFpsRangeList = camera.getParameters().getSupportedPreviewFpsRange();
        for (int[] range : previewFpsRangeList) {
            int deltaMin = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MIN_INDEX];
            int deltaMax = desiredPreviewFpsScaled - range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX];
            int diff = Math.abs(deltaMin) + Math.abs(deltaMax);
            if (diff < minDiff) {
                selectedFpsRange = range;
                minDiff = diff;
            }
        }
        return selectedFpsRange;
    }

    /**
     * 指定されたカメラIDの正しい回転を計算し、パラメータに回転を設定します。
     * また、カメラの表示方向と回転を設定します。
     *
     * @param parameters 回転を設定するカメラパラメータ
     * @param cameraId   カメラIDに基づいて回転を設定する
     */
    private void setRotation(Camera camera, Camera.Parameters parameters, int cameraId) {
        WindowManager windowManager =
                (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        int degrees = 0;
        int rotation = windowManager.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                Log.e(TAG, "Bad rotation value: " + rotation);
        }

        CameraInfo cameraInfo = new CameraInfo();
        Camera.getCameraInfo(cameraId, cameraInfo);

        int angle;
        int displayAngle;
        if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            angle = (cameraInfo.orientation + degrees) % 360;
            displayAngle = (360 - angle); // compensate for it being mirrored
        } else {  // back-facing
            angle = (cameraInfo.orientation - degrees + 360) % 360;
            displayAngle = angle;
        }

        // This corresponds to the rotation constants in {@link Frame}.
        mRotation = angle / 90;

        camera.setDisplayOrientation(displayAngle);
        parameters.setRotation(angle);
    }

    /**
     * カメラプレビューコールバック用のバッファを1つ作成します。
     * バッファのサイズは、カメラのプレビューサイズとカメラ画像のフォーマットに基づいています。
     *
     * @return 現在のカメラ設定に適したサイズの新しいプレビューバッファ
     */
    private byte[] createPreviewBuffer(Size previewSize) {
        int bitsPerPixel = ImageFormat.getBitsPerPixel(ImageFormat.NV21);
        long sizeInBits = previewSize.getHeight() * previewSize.getWidth() * bitsPerPixel;
        int bufferSize = (int) Math.ceil(sizeInBits / 8.0d) + 1;
        // 注意：このコードは、プレイサービスv。8.1以降を使用している場合にのみ機能します。
        // バイト配列をこのように作成し、それをラッピングすることは、.allocate（）を使用するのとは対照的に、動作する配列が存在することを保証するはずです。
        byte[] byteArray = new byte[bufferSize];
        ByteBuffer buffer = ByteBuffer.wrap(byteArray);
        if (!buffer.hasArray() || (buffer.array() != byteArray)) {
            // 私はこれが起こるとは思わない
            // しかし、そうであれば、プレビューコンテンツを後の検出器に後で渡すことはありません。
            throw new IllegalStateException("Failed to create valid buffer for camera source.");
        }

        mBytesToByteBuffer.put(byteArray, buffer);
        return byteArray;
    }

    //==============================================================================================
    // Frame processing
    //==============================================================================================

    /**
     * カメラに新しいプレビューフレームがあるときに呼び出されます。
     */
    private class CameraPreviewCallback implements Camera.PreviewCallback {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            mFrameProcessor.setNextFrame(data, camera);
        }
    }

    /**
     * この実行可能ファイルは、基本となる受信機へのアクセスを制御し、カメラから利用可能なときにフレームを処理するように呼び出します。
     * これは、できるだけ早く（すなわち、不要なコンテキストの切り替えや次のフレームの待機なしに）フレームの検出を実行するように設計されています。
     * 検出はフレーム上で実行されている間、新しいフレームがカメラから受信されることがあります。
     * これらのフレームが入ってくると、最新のフレームが保留中に保持されます。
     * 前のフレームに対して検出およびそれに関連する処理が行われると直ぐに、最近受信したフレームの検出が同じスレッド上で直ちに開始される。
     */
    private class FrameProcessingRunnable implements Runnable {
        private Detector<?> mDetector;
        private long mStartTimeMillis = SystemClock.elapsedRealtime();

        // このロックは、以下のメンバ変数すべてを保護します。
        private final Object mLock = new Object();
        private boolean mActive = true;

        // これらの保留中の変数は、処理を待っている新しいフレームに関連する状態を保持する。
        private long mPendingTimeMillis;
        private int mPendingFrameId = 0;
        private ByteBuffer mPendingFrameData;

        FrameProcessingRunnable(Detector<?> detector) {
            mDetector = detector;
        }

        /**
         * 基本となる受信側を解放します。
         * これは、関連するスレッドが完了した後であれば安全です。これは、上記のカメラソースの解放メソッドで管理されます。
         */
        @SuppressLint("Assert")
        void release() {
            assert (mProcessingThread.getState() == State.TERMINATED);
            mDetector.release();
            mDetector = null;
        }

        /**
         * 実行可能ファイルをアクティブ/非アクティブとしてマークします。ブロックされたスレッドにシグナルを送り続けます。
         */
        void setActive(boolean active) {
            synchronized (mLock) {
                mActive = active;
                mLock.notifyAll();
            }
        }

        /**
         * カメラから受信したフレームデータを設定します。
         * これにより、未使用のフレームバッファ（存在する場合）がカメラに戻され、将来の使用のためにフレームデータへの保留中の参照が保持されます。
         */
        void setNextFrame(byte[] data, Camera camera) {
            synchronized (mLock) {
                if (mPendingFrameData != null) {
                    camera.addCallbackBuffer(mPendingFrameData.array());
                    mPendingFrameData = null;
                }

                if (!mBytesToByteBuffer.containsKey(data)) {
                    Log.d(TAG,
                        "Skipping frame.  Could not find ByteBuffer associated with the image " +
                        "data from the camera.");
                    return;
                }

                // タイムスタンプとフレームIDはここで維持されます。これにより、受信したフレームのタイミングと途中でフレームがドロップされたタイミングを下流のコードに認識させます。
                mPendingTimeMillis = SystemClock.elapsedRealtime() - mStartTimeMillis;
                mPendingFrameId++;
                mPendingFrameData = mBytesToByteBuffer.get(data);

                // プロセッサスレッドが次のフレームで待機している場合は、プロセッサスレッドに通知します（下記参照）。
                mLock.notifyAll();
            }
        }

        /**
         * 処理スレッドがアクティブである限り、これはフレームの検出を連続的に実行します。
         * 次の保留中のフレームは、すぐに使用可能であるか、まだ受信されていないかのいずれかです。
         * 利用可能になると、フレーム情報をローカル変数に転送し、そのフレームで検出を実行します。
         * 一時停止することなく、すぐに次のフレームのためにループバックします。
         * 検出がカメラからの新しいフレームの間の時間よりも長くかかる場合、
         * これは、このループがフレームを待たずに実行され、コンテキスト切り替えやフレーム取得時間の遅延を避けることを意味します。
         * これ以上のCPUを使用している場合は、上記のFPS設定を減らして、フレーム間のアイドル時間を考慮する必要があります。
         */
        @Override
        public void run() {
            Frame outputFrame;
            ByteBuffer data;

            while (true) {
                synchronized (mLock) {
                    while (mActive && (mPendingFrameData == null)) {
                        try {
                            // まだフレームがないので、次のフレームがカメラから受信されるのを待ちます。
                            mLock.wait();
                        } catch (InterruptedException e) {
                            Log.d(TAG, "Frame processing loop terminated.", e);
                            return;
                        }
                    }

                    if (!mActive) {
                        // このカメラソースが停止または解放されると、ループを終了します。
                        // We check this here, immediately after the wait() above, to handle the case where setActive(false) had been called, triggering the termination of this loop.
                        return;
                    }

                    outputFrame = new Frame.Builder()
                            .setImageData(mPendingFrameData, mPreviewSize.getWidth(),
                                    mPreviewSize.getHeight(), ImageFormat.NV21)
                            .setId(mPendingFrameId)
                            .setTimestampMillis(mPendingTimeMillis)
                            .setRotation(mRotation)
                            .build();

                    // フレームデータをローカルに保持することで、これを検出に使用することができます。
                    // mPendingFrameDataをクリアして、このバッファをカメラにリサイクルしないようにしてから、そのデータを使用する必要があります。
                    data = mPendingFrameData;
                    mPendingFrameData = null;
                }

                // 以下のコードは、同期外で実行する必要があります。
                // 現在のフレームで検出を実行している間、カメラがペンディングフレームを追加できるためです。
                try {
                    mDetector.receiveFrame(outputFrame);
                } catch (Throwable t) {
                    Log.e(TAG, "Exception thrown from receiver.", t);
                } finally {
                    mCamera.addCallbackBuffer(data.array());
                }
            }
        }
    }
}
