package com.fivehundredpx.android.blur;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

/**
 * A custom view for presenting a dynamically blurred version of another view's content.
 * <p/>
 * Use {@link #setBlurredView(android.view.View)} to set up the reference to the view to be blurred.
 * After that, call {@link #invalidate()} to trigger blurring whenever necessary.
 */
public class BlurringView extends LinearLayout {
    private int mDownsampleFactor;
    private int mOverlayColor;
    private int mRadius;
    private View mBlurredView;
    private int mBlurredViewWidth, mBlurredViewHeight;
    private boolean mDownsampleFactorChanged;
    private Bitmap mBitmapToBlur, mBlurredBitmap;
    private Canvas mBlurringCanvas;
    private RenderScript mRenderScript;
    private ScriptIntrinsicBlur mBlurScript;
    private Allocation mBlurInput, mBlurOutput;

    public BlurringView(Context context) {
        this(context, null);
    }

    public BlurringView(Context context, AttributeSet attrs) {
        super(context, attrs);

        final Resources res = getResources();
        final int defaultCornerRadius = res.getInteger(R.integer.default_corner_radius);
        final int defaultBlurRadius = res.getInteger(R.integer.default_blur_radius);
        final int defaultDownsampleFactor = res.getInteger(R.integer.default_downsample_factor);
        final int defaultOverlayColor = res.getColor(R.color.default_overlay_color);

        final boolean isInEditMode = isInEditMode();
        if (!isInEditMode)
            initializeRenderScript(context);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PxBlurringView);
        if (!isInEditMode) {
            setCornerRadius(a.getColor(R.styleable.PxBlurringView_cornerRadius, defaultCornerRadius));
            setBlurRadius(a.getInt(R.styleable.PxBlurringView_blurRadius, defaultBlurRadius));
        } else
            setBackgroundColor(a.getColor(R.styleable.PxBlurringView_overlayColor, defaultOverlayColor));
        setDownsampleFactor(a.getInt(R.styleable.PxBlurringView_downsampleFactor, defaultDownsampleFactor));
        setOverlayColor(a.getColor(R.styleable.PxBlurringView_overlayColor, defaultOverlayColor));
        a.recycle();

        setWillNotDraw(false);
    }

    public void setBlurredView(View blurredView) {
        mBlurredView = blurredView;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isInEditMode() && mBlurredView != null) {
            if (prepare()) {
                if (mBlurredView.getBackground() != null && mBlurredView.getBackground() instanceof ColorDrawable)
                    mBitmapToBlur.eraseColor(((ColorDrawable) mBlurredView.getBackground()).getColor());
                else
                    mBitmapToBlur.eraseColor(Color.TRANSPARENT);

                mBlurredView.draw(mBlurringCanvas);
                blur();
                canvas.drawBitmap(getRoundedCornerBitmap(getBluredBitmap(mBlurredBitmap), mRadius, getContext()), 0, 0, null);
            }
        }
    }

    public Bitmap getBluredBitmap(Bitmap bitmap) {
        Bitmap output = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        // This position
        int[] pos = new int[2];
        this.getLocationOnScreen(pos);

        // Blurred positon
        int[] pos1 = new int[2];
        mBlurredView.getLocationOnScreen(pos1);

        // Different
        pos[0] -= pos1[0];
        pos[1] -= pos1[1];

        final Rect rect = new Rect(pos[0] / mDownsampleFactor, pos[1] / mDownsampleFactor, (pos[0] + getWidth()) / mDownsampleFactor, (pos[1] + getHeight()) / mDownsampleFactor);
        final Rect rect1 = new Rect(0, 0, getWidth(), getHeight());

        final Paint paint = new Paint();
        paint.setFilterBitmap(true);
        paint.setAntiAlias(true);

        // draw bitmap
        canvas.drawBitmap(bitmap, rect, rect1, paint);

        if (mOverlayColor != Color.TRANSPARENT)
            canvas.drawColor(mOverlayColor);

        return output;
    }

    public Bitmap getRoundedCornerBitmap(Bitmap bitmap, int cornerDips, Context context) {
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        final int cornerSizePx = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, (float) cornerDips,
                context.getResources().getDisplayMetrics());
        final Paint paint = new Paint();
        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        final RectF rectF = new RectF(rect);

        // prepare canvas for transfer
        paint.setAntiAlias(true);
        paint.setColor(0xFFFFFFFF);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawARGB(0, 0, 0, 0);
        canvas.drawRoundRect(rectF, cornerSizePx, cornerSizePx, paint);

        // draw bitmap
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);
        return output;
    }

    public void setDownsampleFactor(int factor) {
        if (factor <= 0)
            throw new IllegalArgumentException("Downsample factor must be greater than 0.");

        if (mDownsampleFactor != factor) {
            mDownsampleFactor = factor;
            mDownsampleFactorChanged = true;
        }
    }

    public void setBlurRadius(int radius) {
        mBlurScript.setRadius(radius);
    }

    public void setOverlayColor(int color) {
        mOverlayColor = color;
    }

    public void setCornerRadius(int radius) {
        mRadius = radius;
    }

    private void initializeRenderScript(Context context) {
        mRenderScript = RenderScript.create(context);
        mBlurScript = ScriptIntrinsicBlur.create(mRenderScript, Element.U8_4(mRenderScript));
    }

    protected boolean prepare() {
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
                if (mBitmapToBlur == null)
                    return false;

                mBlurredBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight,
                        Bitmap.Config.ARGB_8888);
                if (mBlurredBitmap == null)
                    return false;
            }

            mBlurringCanvas = new Canvas(mBitmapToBlur);
            mBlurringCanvas.scale(1f / mDownsampleFactor, 1f / mDownsampleFactor);
            mBlurInput = Allocation.createFromBitmap(mRenderScript, mBitmapToBlur,
                    Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
            mBlurOutput = Allocation.createTyped(mRenderScript, mBlurInput.getType());
        }
        return true;
    }

    protected void blur() {
        mBlurInput.copyFrom(mBitmapToBlur);
        mBlurScript.setInput(mBlurInput);
        mBlurScript.forEach(mBlurOutput);
        mBlurOutput.copyTo(mBlurredBitmap);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mRenderScript != null)
            mRenderScript.destroy();
    }
}
