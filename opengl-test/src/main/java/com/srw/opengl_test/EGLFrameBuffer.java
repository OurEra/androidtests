package com.srw.opengl_test;

import android.opengl.GLES20;

public class EGLFrameBuffer {

  int mTexture;
  int mFrameBuffer;
  int mWidth;
  int mHeight;

  public EGLFrameBuffer(int width, int height) {

    mWidth = width;
    mHeight = height;

    final int[] framebuffer = new int[1];
    GLES20.glGenFramebuffers(1, framebuffer, 0);
    mFrameBuffer = framebuffer[0];

    mTexture = EGLUtil.generateTexture();

    GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTexture);
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, mWidth, mHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    EGLUtil.checkGlError("Active texture in framebuffer");

    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFrameBuffer);
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, mTexture, 0);
    int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
    if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
      throw new RuntimeException("FrameBuffer not complete " + status);
    }

    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
  }

  public int getTexture() {
    return mTexture;
  }

  public void setTexture(int mTexture) {
    this.mTexture = mTexture;
  }

  public int getFrameBuffer() {
    return mFrameBuffer;
  }

  public void setFrameBuffer(int mFrameBuffer) {
    this.mFrameBuffer = mFrameBuffer;
  }

  public int getWidth() {
    return mWidth;
  }

  public void setWidth(int mWidth) {
    this.mWidth = mWidth;
  }

  public int getHeight() {
    return mHeight;
  }

  public void setHeight(int mHeight) {
    this.mHeight = mHeight;
  }

  public void Release() {
    GLES20.glDeleteTextures(1, new int[] {mTexture}, 0);
    mTexture = 0;
    GLES20.glDeleteFramebuffers(1, new int[] {mFrameBuffer}, 0);
    mFrameBuffer = 0;
    mWidth = mHeight = 0;
  }

}
