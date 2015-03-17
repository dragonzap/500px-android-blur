/*
 * Copyright (C) 2015 500px Inc.
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

package com.fivehundredpx.android.blur;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.os.Build;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.AttributeSet;
import android.view.View;

/**
 * A custom view for presenting a dynamically blurred version of another view's content.
 * <p>
 * Use {@link #setBlurredView(android.view.View)} to set up the reference to the view to be blurred.
 * After that, call {@link #invalidate()} to trigger blurring whenever necessary.
 */
public class BlurringView extends View {

    public BlurringView(Context context) {
        this(context, null);
    }

    public BlurringView(Context context, AttributeSet attrs) {
        super(context, attrs);

        final Resources res = getResources();
        final int defaultBlurRadius = res.getInteger(R.integer.default_blur_radius);
        final int defaultDownsampleFactor = res.getInteger(R.integer.default_downsample_factor);
        final int defaultOverlayColor = res.getColor(R.color.default_overlay_color);

        initializeRenderScript(context);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PxBlurringView);
        setBlurRadius(a.getInt(R.styleable.PxBlurringView_blurRadius, defaultBlurRadius));
        setDownsampleFactor(a.getInt(R.styleable.PxBlurringView_downsampleFactor,
                defaultDownsampleFactor));
        setOverlayColor(a.getColor(R.styleable.PxBlurringView_overlayColor, defaultOverlayColor));
        a.recycle();
    }

    public void setBlurredView(View blurredView) {
        mBlurredView = blurredView;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mBlurredView != null) {
            if (prepare()) {
                // Clears the blurring canvas.
                mBlurringCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

                //
                mBlurredView.draw(mBlurringCanvas);
                blur();

                canvas.save();
                canvas.translate(mBlurredView.getX() - getX(), mBlurredView.getY() - getY());
                canvas.scale(mDownsampleFactor, mDownsampleFactor);
                canvas.drawBitmap(mBlurredBitmap, 0, 0, null);
                canvas.restore();
            }
            canvas.drawColor(mOverlayColor, PorterDuff.Mode.OVERLAY);
        }
    }

    public void setBlurRadius(int radius) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mBlurScript.setRadius(radius);
        }
    }

    public void setDownsampleFactor(int factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("Downsample factor must be greater than 0.");
        }

        if (mDownsampleFactor != factor) {
            mDownsampleFactor = factor;
            mDownsampleFactorChanged = true;
        }
    }

    public void setOverlayColor(int color) {
        mOverlayColor = color;
    }

    private void initializeRenderScript(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mRenderScript = RenderScript.create(context);
            mBlurScript = ScriptIntrinsicBlur.create(mRenderScript, Element.U8_4(mRenderScript));
        }
    }

    private boolean prepare() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2) {
            return false;
        } else {
            final int width = mBlurredView.getWidth();
            final int height = mBlurredView.getHeight();

            if (mBlurringCanvas == null || mDownsampleFactorChanged
                    || mBlurredViewWidth != width || mBlurredViewHeight != height) {
                mDownsampleFactorChanged = false;

                mBlurredViewWidth = width;
                mBlurredViewHeight = height;

                int scaledWidth = width / mDownsampleFactor;
                int scaledHeight = height / mDownsampleFactor;

                // The following manipulation is to avoid some RenderScript artifacts at the edge.
                scaledWidth = scaledWidth - scaledWidth % 4 + 4;
                scaledHeight = scaledHeight - scaledHeight % 4 + 4;

                if (mBlurredBitmap == null
                        || mBlurredBitmap.getWidth() != scaledWidth
                        || mBlurredBitmap.getHeight() != scaledHeight) {
                    mBitmapToBlur = Bitmap.createBitmap(scaledWidth, scaledHeight,
                            Bitmap.Config.ARGB_8888);
                    if (mBitmapToBlur == null) {
                        return false;
                    }

                    mBlurredBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight,
                            Bitmap.Config.ARGB_8888);
                    if (mBlurredBitmap == null) {
                        return false;
                    }
                }

                mBlurringCanvas = new Canvas(mBitmapToBlur);
                mBlurringCanvas.scale(1f / mDownsampleFactor, 1f / mDownsampleFactor);
                mBlurInput = Allocation.createFromBitmap(mRenderScript, mBitmapToBlur,
                        Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
                mBlurOutput = Allocation.createTyped(mRenderScript, mBlurInput.getType());
            }

            return true;
        }
    }

    private void blur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mBlurInput.copyFrom(mBitmapToBlur);
            mBlurScript.setInput(mBlurInput);
            mBlurScript.forEach(mBlurOutput);
            mBlurOutput.copyTo(mBlurredBitmap);
        }
    }

    private int mDownsampleFactor;
    private int mOverlayColor;

    private View mBlurredView;
    private int mBlurredViewWidth, mBlurredViewHeight;

    private boolean mDownsampleFactorChanged;
    private Bitmap mBitmapToBlur, mBlurredBitmap;
    private Canvas mBlurringCanvas;
    private RenderScript mRenderScript;
    private ScriptIntrinsicBlur mBlurScript;
    private Allocation mBlurInput, mBlurOutput;

}