package com.example.lib.camera;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceHolder.Callback;

import java.io.IOException;
import java.util.concurrent.Exchanger;

public class VideoCaptureAndroid implements PreviewCallback, Callback {
  private final static String TAG = VideoCaptureAndroid.class.getSimpleName();
  
  private static SurfaceHolder localPreview;
  private Camera camera;  // Only non-null while capturing.
  private CameraThread cameraThread;
  private Handler cameraThreadHandler;
  private final int id;
  private final Camera.CameraInfo info;
  private SurfaceTexture cameraSurfaceTexture;
  private int[] cameraGlTextures = null;
  // Arbitrary queue depth.  Higher number means more memory allocated & held,
  // lower number means more sensitivity to processing time in the client (and
  // potentially stalling the capturer if it runs out of buffers to write to).
  private final int numCaptureBuffers = 3;
  private double averageDurationMs;
  private long lastCaptureTimeMs;
  private int frameCount;

  private PreviewFrameCallback mFrameCallback;

  // Requests future capturers to send their frames to |localPreview| directly.
  public static void setLocalPreview(SurfaceHolder localPreview) {
    // It is a gross hack that this is a class-static.  Doing it right would
    // mean plumbing this through the C++ API and using it from
    // webrtc/examples/android/media_demo's MediaEngine class.
    VideoCaptureAndroid.localPreview = localPreview;
  }

  public interface PreviewFrameCallback {

    void onFrame(byte[] data, long len, long tiemMs);
  }

  public VideoCaptureAndroid(int id) {
    this.id = id;
    this.info = new Camera.CameraInfo();
    Camera.getCameraInfo(id, info);
  }

  public void registerFrameCallback(PreviewFrameCallback callback) {
    mFrameCallback = callback;
  }

  private class CameraThread extends Thread {
    private Exchanger<Handler> handlerExchanger;
    public CameraThread(Exchanger<Handler> handlerExchanger) {
      this.handlerExchanger = handlerExchanger;
    }

    @Override public void run() {
      Looper.prepare();
      exchange(handlerExchanger, new Handler());
      Looper.loop();
    }
  }

  // Called by native code.  Returns true if capturer is started.
  //
  // Note that this actually opens the camera, and Camera callbacks run on the
  // thread that calls open(), so this is done on the CameraThread.  Since ViE
  // API needs a synchronous success return value we wait for the result.
  public synchronized boolean startCapture(
      final int width, final int height,
      final int min_mfps, final int max_mfps) {
    Log.d(TAG, "startCapture: " + width + "x" + height + "@" +
        min_mfps + ":" + max_mfps);
    if (cameraThread != null || cameraThreadHandler != null) {
      throw new RuntimeException("Camera thread already started!");
    }
    Exchanger<Handler> handlerExchanger = new Exchanger<Handler>();
    cameraThread = new CameraThread(handlerExchanger);
    cameraThread.start();
    cameraThreadHandler = exchange(handlerExchanger, null);

    final Exchanger<Boolean> result = new Exchanger<Boolean>();
    cameraThreadHandler.post(new Runnable() {
        @Override public void run() {
          startCaptureOnCameraThread(width, height, min_mfps, max_mfps, result);
        }
      });
    boolean startResult = exchange(result, false); // |false| is a dummy value.
    return startResult;
  }

  private void startCaptureOnCameraThread(
      int width, int height, int min_mfps, int max_mfps,
      Exchanger<Boolean> result) {
    Throwable error = null;
    try {
      camera = Camera.open(id);
      if (localPreview != null) {
        localPreview.addCallback(this);
        if (localPreview.getSurface() != null &&
            localPreview.getSurface().isValid()) {
          camera.setPreviewDisplay(localPreview);
        }
      } else {
        // No local renderer (we only care about onPreviewFrame() buffers, not a
        // directly-displayed UI element).  Camera won't capture without
        // setPreview{Texture,Display}, so we create a SurfaceTexture and hand
        // it over to Camera, but never listen for frame-ready callbacks,
        // and never call updateTexImage on it.
        try {
          cameraGlTextures = new int[1];
          // Generate one texture pointer and bind it as an external texture.
          GLES20.glGenTextures(1, cameraGlTextures, 0);
          GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
              cameraGlTextures[0]);
          GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
              GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
          GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
              GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
          GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
              GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
          GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
              GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

          cameraSurfaceTexture = new SurfaceTexture(cameraGlTextures[0]);
          cameraSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                float[] transformMatrix = new float[16];
                surfaceTexture.getTransformMatrix(transformMatrix);
                Log.i(TAG, "matrix " + transformMatrix);
            }
          });
          camera.setPreviewTexture(cameraSurfaceTexture);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }

      Camera.Parameters parameters = camera.getParameters();
      Log.d(TAG, "isVideoStabilizationSupported: " +
          parameters.isVideoStabilizationSupported());
      if (parameters.isVideoStabilizationSupported()) {
        parameters.setVideoStabilization(true);
      }
      parameters.setPreviewSize(width, height);
      parameters.setPreviewFpsRange(min_mfps, max_mfps);
      int format = ImageFormat.NV21;
      parameters.setPreviewFormat(format);
      camera.setParameters(parameters);
      int bufSize = width * height * ImageFormat.getBitsPerPixel(format) / 8;
      for (int i = 0; i < numCaptureBuffers; i++) {
        camera.addCallbackBuffer(new byte[bufSize]);
      }
      camera.setPreviewCallbackWithBuffer(this);
      camera.setDisplayOrientation(90);
      frameCount = 0;
      averageDurationMs = 1000 / max_mfps;
      camera.startPreview();
      exchange(result, true);
      return;
    } catch (IOException e) {
      error = e;
    } catch (RuntimeException e) {
      error = e;
    }
    Log.e(TAG, "startCapture failed", error);
    if (camera != null) {
      Exchanger<Boolean> resultDropper = new Exchanger<Boolean>();
      stopCaptureOnCameraThread(resultDropper);
      exchange(resultDropper, false);
    }
    exchange(result, false);
  }

  // Called by native code.  Returns true when camera is known to be stopped.
  public synchronized boolean stopCapture() {
    Log.d(TAG, "stopCapture");
    final Exchanger<Boolean> result = new Exchanger<Boolean>();
    cameraThreadHandler.post(new Runnable() {
        @Override public void run() {
          stopCaptureOnCameraThread(result);
        }
      });
    boolean status = exchange(result, false);  // |false| is a dummy value here.
    try {
      cameraThread.join();
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    cameraThreadHandler = null;
    cameraThread = null;
    Log.d(TAG, "stopCapture done");
    return status;
  }

  private void stopCaptureOnCameraThread(
      Exchanger<Boolean> result) {
    if (camera == null) {
      throw new RuntimeException("Camera is already stopped!");
    }
    Throwable error = null;
    try {
      camera.stopPreview();
      camera.setPreviewCallbackWithBuffer(null);
      if (localPreview != null) {
        localPreview.removeCallback(this);
        camera.setPreviewDisplay(null);
      } else {
        camera.setPreviewTexture(null);
        cameraSurfaceTexture = null;
        if (cameraGlTextures != null) {
          GLES20.glDeleteTextures(1, cameraGlTextures, 0);
          cameraGlTextures = null;
        }
      }
      camera.release();
      camera = null;
      exchange(result, true);
      Looper.myLooper().quit();
      return;
    } catch (IOException e) {
      error = e;
    } catch (RuntimeException e) {
      error = e;
    }
    Log.e(TAG, "Failed to stop camera", error);
    exchange(result, false);
    Looper.myLooper().quit();
    return;
  }

  // Called on cameraThread so must not "synchronized".
  @Override
  public void onPreviewFrame(byte[] data, Camera callbackCamera) {
    if (Thread.currentThread() != cameraThread) {
      throw new RuntimeException("Camera callback not on camera thread?!?");
    }
    if (camera == null) {
      return;
    }
    if (camera != callbackCamera) {
      throw new RuntimeException("Unexpected camera in callback!");
    }
    frameCount++;
    long captureTimeMs = SystemClock.elapsedRealtime();
    if (frameCount > 1) {
      double durationMs = captureTimeMs - lastCaptureTimeMs;
      averageDurationMs = 0.9 * averageDurationMs + 0.1 * durationMs;
      if ((frameCount % 30) == 0) {
        Log.d(TAG, "Camera TS " + captureTimeMs +
            ". Duration: " + (int)durationMs + " ms. FPS: " +
            (int) (1000 / averageDurationMs + 0.5));
      }
    }
    lastCaptureTimeMs = captureTimeMs;
    if (mFrameCallback != null) {
      mFrameCallback.onFrame(data, data.length, captureTimeMs);
    }
    camera.addCallbackBuffer(data);
  }

  // Sets the rotation of the preview render window.
  // Does not affect the captured video image.
  // Called by native code.
  private synchronized void setPreviewRotation(final int rotation) {
    if (camera == null || cameraThreadHandler == null) {
      return;
    }
    final Exchanger<IOException> result = new Exchanger<IOException>();
    cameraThreadHandler.post(new Runnable() {
        @Override public void run() {
          setPreviewRotationOnCameraThread(rotation, result);
        }
      });
    // Use the exchanger below to block this function until
    // setPreviewRotationOnCameraThread() completes, holding the synchronized
    // lock for the duration.  The exchanged value itself is ignored.
    exchange(result, null);
  }

  private void setPreviewRotationOnCameraThread(
      int rotation, Exchanger<IOException> result) {
    Log.v(TAG, "setPreviewRotation:" + rotation);

    int resultRotation = 0;
    if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
      // This is a front facing camera.  SetDisplayOrientation will flip
      // the image horizontally before doing the rotation.
      resultRotation = ( 360 - rotation ) % 360; // Compensate for the mirror.
    } else {
      // Back-facing camera.
      resultRotation = rotation;
    }
    camera.setDisplayOrientation(resultRotation);
    exchange(result, null);
  }

  @Override
  public synchronized void surfaceChanged(
      SurfaceHolder holder, int format, int width, int height) {
    Log.d(TAG, "VideoCaptureAndroid::surfaceChanged ignored: " +
        format + ": " + width + "x" + height);
  }

  @Override
  public synchronized void surfaceCreated(final SurfaceHolder holder) {
    Log.d(TAG, "VideoCaptureAndroid::surfaceCreated");
    if (camera == null || cameraThreadHandler == null) {
      return;
    }
    final Exchanger<IOException> result = new Exchanger<IOException>();
    cameraThreadHandler.post(new Runnable() {
        @Override public void run() {
          setPreviewDisplayOnCameraThread(holder, result);
        }
      });
    IOException e = exchange(result, null);  // |null| is a dummy value here.
    if (e != null) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public synchronized void surfaceDestroyed(SurfaceHolder holder) {
    Log.d(TAG, "VideoCaptureAndroid::surfaceDestroyed");
    if (camera == null || cameraThreadHandler == null) {
      return;
    }
    final Exchanger<IOException> result = new Exchanger<IOException>();
    cameraThreadHandler.post(new Runnable() {
        @Override public void run() {
          setPreviewDisplayOnCameraThread(null, result);
        }
      });
    IOException e = exchange(result, null);  // |null| is a dummy value here.
    if (e != null) {
      throw new RuntimeException(e);
    }
  }

  private void setPreviewDisplayOnCameraThread(
      SurfaceHolder holder, Exchanger<IOException> result) {
    try {
      camera.setPreviewDisplay(holder);
    } catch (IOException e) {
      exchange(result, e);
      return;
    }
    exchange(result, null);
  }

  // Exchanges |value| with |exchanger|, converting InterruptedExceptions to
  // RuntimeExceptions (since we expect never to see these).
  private static <T> T exchange(Exchanger<T> exchanger, T value) {
    try {
      return exchanger.exchange(value);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }
}
