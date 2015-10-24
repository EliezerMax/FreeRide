package com.labs.okey.freeride;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.support.annotation.CallSuper;
import android.support.annotation.UiThread;
import android.os.Bundle;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuItem;
import android.view.OrientationEventListener;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import com.afollestad.materialdialogs.MaterialDialog;
import com.crashlytics.android.Crashlytics;
import com.crashlytics.android.answers.Answers;
import com.crashlytics.android.answers.CustomEvent;
import com.labs.okey.freeride.fastcv.FastCVCameraView;
import com.labs.okey.freeride.fastcv.FastCVWrapper;
import com.labs.okey.freeride.utils.IPictureURLUpdater;
import com.microsoft.projectoxford.face.FaceServiceClient;
import com.microsoft.projectoxford.face.contract.Face;

import net.steamcrafted.loadtoast.LoadToast;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class CameraCVActivity extends Activity
        implements CameraBridgeViewBase.CvCameraViewListener2,
                    Camera.PictureCallback,
                    IPictureURLUpdater,
                    Handler.Callback {

    private static final String LOG_TAG = "FR.CV";

    private Handler handle = new Handler(this);
    public Handler getHandler() { return handle; }

    private String mRideCode = "73373"; // TODO: get rid of this!

    private Mat     mGray;
    private Mat     mRgba;
    private Mat     mMatTemplate;

    Scalar mCameraFontColor = new Scalar(255, 255, 255);
    String mCameraDirective;
    String mCameraDirective2;
    TextToSpeech mSpeechCommander;

    FastCVWrapper mCVWrapper;

    private FastCVCameraView mOpenCvCameraView;

    OrientationEventListener mOrientationEventListener;
    private int              mCurrentOrientation;

    private String createCascadeFile(int resourceId, String fileName) {

        try {
            InputStream is = getResources().openRawResource(resourceId);
            File cascadeDir = getDir("cascade", Context.MODE_PRIVATE);
            File cascadeFile = new File(cascadeDir, fileName);

            FileOutputStream os = new FileOutputStream(cascadeFile);
            byte[] buffer = new byte[4096];
            int bytesRead;
            while( (bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }

            os.close();
            is.close();

            return cascadeFile.getAbsolutePath();

        } catch (IOException e) {
            if(Crashlytics.getInstance() != null )
                Crashlytics.logException(e);

            Log.e(LOG_TAG, e.getMessage());

            return "";
        }
    }

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS:
                {
                    Log.i(LOG_TAG, "OpenCV loaded successfully");

                    System.loadLibrary("fastcvUtils");

                    String faceCascadeFilePath = createCascadeFile(R.raw.haarcascade_frontalface_default,
                                                                "haarcascade_frontalface_default.xml");
                    String eyesCascadeFilePath = createCascadeFile(R.raw.haarcascade_eye,
                                                                "haarcascade_eye.xml");

                    mCVWrapper = new FastCVWrapper(faceCascadeFilePath,
                                                   eyesCascadeFilePath);

                    if( mOpenCvCameraView != null) {
                        mOpenCvCameraView.enableView();
                        mSpeechCommander = new TextToSpeech(getApplicationContext(), new TextToSpeech.OnInitListener(){
                            @Override
                            public void onInit(int status) {
                                if(status == TextToSpeech.ERROR) {
                                    Log.e(LOG_TAG, "Can not init Speech Engine");
                                    //t1.setLanguage(Locale.UK);
                                }
                            }
                        });
                    }

                }
                break;

                default:
                {
                    super.onManagerConnected(status);
                } break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_camera_cv);

        mOpenCvCameraView = (FastCVCameraView) findViewById(R.id.java_surface_view);
        if( mOpenCvCameraView != null ) {
            mOpenCvCameraView.setVisibility(SurfaceView.VISIBLE);
            mOpenCvCameraView.setCvCameraViewListener(this);

            PackageManager pm = getPackageManager();
            if( pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT) )
                mOpenCvCameraView.setCameraIndex(CameraBridgeViewBase.CAMERA_ID_FRONT);
            else
                mOpenCvCameraView.setCameraIndex(CameraBridgeViewBase.CAMERA_ID_BACK);

            mCurrentOrientation = OrientationEventListener.ORIENTATION_UNKNOWN;
            mOrientationEventListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
                @Override
                public void onOrientationChanged(int degrees) {
                    if( (degrees >= 45 && degrees <= 135)
                            || (degrees >= 225 && degrees <= 315) )
                        mCurrentOrientation = Configuration.ORIENTATION_LANDSCAPE;
                    else
                        mCurrentOrientation = Configuration.ORIENTATION_PORTRAIT;

                }
            };
            if( mOrientationEventListener.canDetectOrientation() ) {
                mOrientationEventListener.enable();
            }
        }

        mCameraDirective = getString(R.string.camera_directive_1);
        mCameraDirective2 = getString(R.string.camera_directive_2);

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_camera_cv, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    @CallSuper
    public void onStop(){

        if( isCheckerMatchTimerRunning() )
            mCheckMatchResultTimer.shutdown();

        super.onStop();
    }

    @Override
    @CallSuper
    public void onPause() {

        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();

        if( mSpeechCommander != null ) {
            mSpeechCommander.stop();
            mSpeechCommander.shutdown();
        }

        super.onPause();
    }


    @Override
    @CallSuper
    public void onResume() {
        super.onResume();

        if( !OpenCVLoader.initDebug() ) {
            // Roughly, it's an analog of System.loadLibrary('opencv_java3') - meaning .so library
            // In our case it is supposed to always return false, because we are statically linked with opencv_java3.so
            // (in jniLbs/<platform> folder.
            //
            // Such way of linking allowed for running without OpenCV Manager (https://play.google.com/store/apps/details?id=org.opencv.engine&hl=en)
            Log.d(LOG_TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");

            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_0_0,
                                    this,
                                    mLoaderCallback);
        } else {
            Log.d(LOG_TAG, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mOpenCvCameraView != null)
            mOpenCvCameraView.disableView();

        if( mOrientationEventListener != null )
            mOrientationEventListener.disable();
    }

    @Override
    public void onCameraViewStarted(int width, int height) {
        mRgba = new Mat(height, width, CvType.CV_8UC4);
        mGray = new Mat(height, width, CvType.CV_8UC1);
        mMatTemplate = new Mat(height, width, CvType.CV_8UC1);
    }

    @Override
    public void onCameraViewStopped() {
        mRgba.release();
        mGray.release();
        mMatTemplate.release();
    }

    boolean bTemplateFound = false;
    private boolean mbSearchInitialized = false;
    int matchResult = 0; // Possible values: -1 - restart search (e.g. face is out of frame)
                         //                  0 - no match
                         //                  1 - match
                         // Start with 'no match'

    ScheduledExecutorService mCheckMatchResultTimer =
            Executors.newScheduledThreadPool(1);
    private Boolean checkMatchRunning = false;
    boolean isCheckerMatchTimerRunning() {
        return checkMatchRunning;
    }

    private int initialEyesDetectedCounter = 0;
    private int INITIAL_EYES = 3;
    private int missedEyesCounter = 0;
    private int MISSED_EYES = 3;

    private boolean bUploadingFrame = false;

    @Override
    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {

        long executionTime = 0L;
        long start = System.currentTimeMillis();

        // input frame has RGBA format
        mRgba = inputFrame.rgba();
        mGray = inputFrame.gray();

        Core.flip(mRgba, mRgba, 1); // flip around y-axis anyway: found face or not

        try {
            Mat faceMat = new Mat();
            if( mCVWrapper.DetectFace(mRgba.getNativeObjAddr(),
                    mGray.getNativeObjAddr(),
                    faceMat.getNativeObjAddr(),
                    mCurrentOrientation) )
            {

                final Mat eyeMat = new Mat();
                boolean bEyeFound = mCVWrapper.DetectEye(mRgba.getNativeObjAddr(),
                                    faceMat.getNativeObjAddr(),
                                    eyeMat.getNativeObjAddr(),
                                    mCurrentOrientation);

                if( !mbSearchInitialized ) {
                    if( bEyeFound ) {

                        if( ++initialEyesDetectedCounter >= INITIAL_EYES) {
                            mbSearchInitialized = true;

                            matToView(faceMat, R.id.imageViewFace);

                            getHandler().post(new Runnable() {

                                @Override
                                public void run() {

                                    if( mCurrentOrientation == Configuration.ORIENTATION_PORTRAIT) {
                                        Core.transpose(eyeMat, eyeMat);
                                    }

                                    matToView(eyeMat, R.id.imageViewTemplate);

                                }
                            });
                        }

                    }
                } else {
                    if( !bEyeFound ) {

                        if( ++missedEyesCounter >= MISSED_EYES ) {
                            mOpenCvCameraView.stopPreview();

                            if( !bUploadingFrame ) {

                                bUploadingFrame = true;

                                final Bitmap faceBitmap = Bitmap.createBitmap(faceMat.cols(),
                                                                              faceMat.rows(),
                                                                              Bitmap.Config.ARGB_8888);
                                Utils.matToBitmap(faceMat, faceBitmap);

                                getHandler().post(new Runnable() {
                                    @Override
                                    public void run() {
                                        uploadFrame(faceBitmap);
                                    }
                                });
                            }


//                            getHandler().postDelayed(new Runnable() {
//                                @Override
//                                public void run() {
//                                    // Will be continued in onPictureTaken() callback
//                                    mOpenCvCameraView.takePicture(CameraCVActivity.this);
//                                }
//                            }, 2000); // delay for 2 sec. to let to open the eyes
                        }

                    }
                }
          }
        } catch( Exception ex) {
            if(Crashlytics.getInstance() != null )
                Crashlytics.logException(ex);

            Log.e(LOG_TAG, ex.getMessage());
        }


        executionTime += (System.currentTimeMillis() - start);
        String msg = String.format("Executed for %d ms.", executionTime);
        Log.d(LOG_TAG, msg);

        System.gc();
        return mRgba;
    }

    @UiThread
    private void matToView(final Mat mat, final int id){

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = Bitmap.createBitmap(mat.cols(),
                        mat.rows(),
                        Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(mat, bmp);

                ImageView imgView = (ImageView)findViewById(id);
                imgView.setImageBitmap(bmp);
            }
        });

    }

    public void restoreFromSendToDetect(View view){

//        // Restore camera frames processing
//        mOpenCvCameraView.startPreview();
//
//        // Dismiss buttons
//        findViewById(R.id.detection_buttons_bar).setVisibility(View.GONE);
//
//        // Restore status text
//        TextView txtStatus = (TextView)findViewById(R.id.detection_monitor);
//        txtStatus.setText(getString(R.string.detection_freeze));
    }

    @Override
    public void onPictureTaken(byte[] data, Camera camera) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inTempStorage = new byte[16 * 1024];

            Camera.Parameters parameters = camera.getParameters();
            Camera.Size size = parameters.getPictureSize();

            int height = size.height;
            int width = size.width;
            float mb = (width * height) / 1024000;

            if (mb > 4f)
                options.inSampleSize = 4;
            else if (mb > 3f)
                options.inSampleSize = 2;

            final Bitmap _bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {

                    new MaterialDialog.Builder(CameraCVActivity.this)
                            .title(getString(R.string.detection_success))
                            .positiveText(R.string.ok)
                            .callback(new MaterialDialog.ButtonCallback() {
                                @Override
                                public void onPositive(MaterialDialog dialog) {

                                    reportAnswer(1);

                                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                    _bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                                    byte[] b = baos.toByteArray();

                                    Intent intent = new Intent();
                                    intent.putExtra("face", b);
                                    intent.putExtra("faceid", "4444444");

                                    setResult(RESULT_OK, intent);
                                    finish();
                                }
                            })
                            .show();

                }
            });

            uploadFrame(_bitmap);

        } catch(Exception ex) {
            String message = ex.getMessage();
            Log.e(LOG_TAG, message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        }
    }

    private void uploadFrame(final Bitmap sampleBitmap) {

        if( sampleBitmap == null )
            return;

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        sampleBitmap.compress(Bitmap.CompressFormat.JPEG, 100, output);
        ByteArrayInputStream inputStream = new ByteArrayInputStream(output.toByteArray());

        new AsyncTask<InputStream, String, Face[]>(){

            // Toast popped up when communicating with server.
            LoadToast lt;

            InputStream mInputStream;

            private String getTempFileName() {
                String timeStamp = new SimpleDateFormat("yyyyMMdd+HHmmss").format(new Date());
                return "FR_" + timeStamp;
            }

            @Override
            protected void onPreExecute() {

                lt = new LoadToast(CameraCVActivity.this);
                lt.setText(getString(R.string.detection_send));
                Display display = getWindow().getWindowManager().getDefaultDisplay();
                android.graphics.Point size = new android.graphics.Point();
                display.getSize(size);
                lt.setTranslationY(size.y / 2);
                lt.show();
            }

            @Override
            protected void onPostExecute(Face[] result) {

                String strFormat = getString(R.string.detection_save);
                String msg = String.format(strFormat, result.length);
                Log.i(LOG_TAG, msg);

                try {

                    if( mInputStream != null)
                        mInputStream.close();

                    if( result.length < 1) {

                        lt.error();

                        new MaterialDialog.Builder(CameraCVActivity.this)
                                .title(getString(R.string.detection_no_results))
                                .content(getString(R.string.try_again))
                                .positiveText(R.string.ok).callback(new MaterialDialog.ButtonCallback() {
                                    @Override
                                    public void onPositive(MaterialDialog dialog) {
                                        mOpenCvCameraView.startPreview();
                                    }
                                })
                                .show();
                    } else {

                        lt.success();

                        Face _face = result[0];
                        final UUID faceID = _face.faceId;

                        reportAnswer(1);

//                        int bmpSize = getBitmapSize(sampleBitmap);
//                        int bmpWidth = sampleBitmap.getWidth();
//                        int bmpHeight = sampleBitmap.getHeight();

                        Bitmap thumbFace = Bitmap.createScaledBitmap(sampleBitmap, 40, 40, false);
//                        bmpSize = getBitmapSize(thumbFace);
//                        bmpWidth = thumbFace.getWidth();
//                        bmpHeight = thumbFace.getHeight();

                        Intent intent = new Intent();
                        intent.putExtra("face", thumbFace);
                        intent.putExtra("faceid", faceID);

                        setResult(RESULT_OK, intent);
                        finish();

//                        new MaterialDialog.Builder(CameraCVActivity.this)
//                                .title(getString(R.string.detection_success))
//                                .positiveText(R.string.ok)
//                                .callback(new MaterialDialog.ButtonCallback() {
//                                        @Override
//                                        public void onPositive(MaterialDialog dialog) {
//
//                                            reportAnswer(1);
//
//                                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
//                                            sampleBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
//                                            byte[] b = baos.toByteArray();
//
//                                            Intent intent = new Intent();
//                                            intent.putExtra("face", b);
//                                            intent.putExtra("faceid", faceID);
//
//                                            setResult(RESULT_OK, intent);
//                                            finish();
//                                        }
//                                })
//                                .show();

//                        new MaterialDialog.Builder(CameraCVActivity.this)
//                                .title(getString(R.string.detection_results))
//                                .content(msg)
//                                .positiveText(R.string.yes)
//                                .negativeText(R.string.no)
//                                .callback(new MaterialDialog.ButtonCallback() {
//                                    @Override
//                                    public void onPositive(MaterialDialog dialog) {
//
//                                        try {
//                                            File outputDir = getApplicationContext().getCacheDir();
//                                            String photoFileName = getTempFileName();
//
//                                            File photoFile = File.createTempFile(photoFileName, ".jpg", outputDir);
//                                            FileOutputStream fos = new FileOutputStream(photoFile);
//                                            sampleBitmap.compress(Bitmap.CompressFormat.JPEG, 80, fos);
//
//                                            fos.flush();
//                                            fos.close();
//
//                                            MediaStore.Images.Media.insertImage(getContentResolver(),
//                                                    photoFile.getAbsolutePath(),
//                                                    photoFile.getName(),
//                                                    photoFile.getName());
//
//                                            new wamsBlobUpload(CameraCVActivity.this).execute(photoFile);
//
//                                        } catch (IOException ex) {
//                                            Log.e(LOG_TAG, ex.getMessage());
//                                        }
//
//                                    }
//
//                                    @Override
//                                    public void onNegative(MaterialDialog dialog) {
//                                        mOpenCvCameraView.startPreview();
//                                    }
//                                })
//                                .show();
                    }


                } catch(Exception ex) {
                    Log.e(LOG_TAG, ex.getMessage());
                }
            }

            @Override
            protected Face[] doInBackground(InputStream... params) {

                mInputStream = params[0];

                // Get an instance of face service client to detect faces in image.
                FaceServiceClient faceServiceClient = new FaceServiceClient(getString(R.string.oxford_subscription_key));

                // Start detection.
                try {
                    return faceServiceClient.detect(
                            mInputStream,  /* Input stream of image to detect */
                            true,       /* Whether to analyzes facial landmarks */
                            false,       /* Whether to analyzes age */
                            false,       /* Whether to analyzes gender */
                            true);      /* Whether to analyzes head pose */
                } catch (Exception e) {

                    if(Crashlytics.getInstance() != null )
                        Crashlytics.logException(e);

                    Log.e(LOG_TAG, e.getMessage());
                }

                return null;
            }

        }.execute(inputStream);
    }

    private int getBitmapSize(Bitmap bmp) {
        if(Build.VERSION.SDK_INT< Build.VERSION_CODES.KITKAT){
            return bmp.getByteCount();
        } else
            return bmp.getAllocationByteCount();
    }

    private void reportAnswer(int status){

        CustomEvent confirmEvent = new CustomEvent(getString(R.string.passenger_confirmation_answer_name));
        // No user for this Answer
        //confirmEvent.putCustomAttribute("User", getUser().getFullName());
        confirmEvent.putCustomAttribute(getString(R.string.answer_success_attribute), status);
        Answers.getInstance().logCustom(confirmEvent);
    }

    //
    // Implementation of Handler.Callback
    //
    @Override
    public boolean handleMessage(Message msg) {
        return true;
    }

    //
    // Implementation of IPictureURLUpdater
    //
    @Override
    public void update(String url) {
        //new wamsPictureURLUpdater(this).execute(url, mRideCode, mFaceID.toString());
    }

    @Override
    public void finished(boolean success) {
        if( !success )
            restoreFromSendToDetect(null);
        else
            finish();
    }

}
