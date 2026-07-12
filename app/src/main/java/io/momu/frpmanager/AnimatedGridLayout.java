package io.momu.frpmanager;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

public class AnimatedGridLayout extends FrameLayout {
    private final Paint gridPaint = new Paint();
    private final Paint majorGridPaint = new Paint();
    private final Paint scanPaint = new Paint();
    private final float cellSize;
    private final float majorSpan;
    private float animationProgress;
    private ValueAnimator animator;

    public AnimatedGridLayout(@NonNull Context context) {
        this(context, null);
    }

    public AnimatedGridLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AnimatedGridLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        float density = getResources().getDisplayMetrics().density;
        cellSize = 24f * density;
        majorSpan = cellSize * 4f;

        gridPaint.setColor(ContextCompat.getColor(context, R.color.grid_line));
        gridPaint.setStrokeWidth(Math.max(1f, density * 0.5f));
        gridPaint.setAntiAlias(false);
        majorGridPaint.setColor(ContextCompat.getColor(context, R.color.grid_major_line));
        majorGridPaint.setStrokeWidth(Math.max(1f, density));
        majorGridPaint.setAntiAlias(false);
        scanPaint.setColor(ContextCompat.getColor(context, R.color.grid_scan_line));
        scanPaint.setStrokeWidth(Math.max(1f, density));
        scanPaint.setAntiAlias(false);
        setWillNotDraw(false);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        float offset = animationProgress * majorSpan;
        int columns = (int) Math.ceil(getWidth() / cellSize) + 9;
        int rows = (int) Math.ceil(getHeight() / cellSize) + 9;

        for (int i = -8; i < columns; i++) {
            float x = i * cellSize + offset;
            canvas.drawLine(x, 0, x, getHeight(), Math.floorMod(i, 4) == 0 ? majorGridPaint : gridPaint);
        }
        for (int i = -8; i < rows; i++) {
            float y = i * cellSize + offset;
            canvas.drawLine(0, y, getWidth(), y, Math.floorMod(i, 4) == 0 ? majorGridPaint : gridPaint);
        }

        float scanY = animationProgress * (getHeight() + majorSpan) - majorSpan;
        canvas.drawLine(0, scanY, getWidth(), scanY, scanPaint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (ValueAnimator.areAnimatorsEnabled()) startGridAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopGridAnimation();
        super.onDetachedFromWindow();
    }

    private void startGridAnimation() {
        if (animator != null && animator.isRunning()) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(24000L);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(valueAnimator -> {
            animationProgress = (float) valueAnimator.getAnimatedValue();
            postInvalidateOnAnimation();
        });
        animator.start();
    }

    private void stopGridAnimation() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
