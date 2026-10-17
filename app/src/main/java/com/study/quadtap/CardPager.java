package com.study.quadtap;

import android.content.Context;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * StandBy 式卡片切换容器：一次只显示一张卡片，支持左右拖动切换。
 *
 * 设计要点：
 * 1. 只拦截「明显水平」的手势，纵向滚动完全留给子 View（日志终端、页面 ScrollView）；
 * 2. 起点落在 SeekBar / Switch / 可选中文本上时，整个手势都不抢，避免滑块拖不动、文字选不了；
 * 3. 只有确实存在相邻页时才拦截，到头的方向不拦截（否则会把点击吞掉）；
 * 4. 动画期间忽略新的切换请求，快速连滑不会让页面状态错乱；
 * 5. 没有引入任何第三方依赖，只用到原生 View 动画。
 */
final class CardPager extends ViewGroup {

    interface OnPageSettledListener {
        /** 动画结束后才会回调，index 一定是「已经稳定显示」的那一页。 */
        void onPageSettled(int index);
    }

    /** 单次切换动画时长，落在需求建议的 180～280ms 区间内。 */
    private static final int ANIM_MS = 220;
    /** 拖过整页宽度的这个比例就翻页。 */
    private static final float COMMIT_RATIO = 0.26f;
    /** 水平位移必须是垂直位移的这么多倍，才算「明显水平」。 */
    private static final float HORIZONTAL_DOMINANCE = 1.5f;
    /** 入场卡片的初始缩放与透明度，制造「从后面推上来」的层次感。 */
    private static final float ENTER_SCALE = 0.92f;
    private static final float ENTER_ALPHA = 0.55f;

    private final int touchSlop;
    private final int minFlingVelocity;
    private final DecelerateInterpolator interpolator = new DecelerateInterpolator();

    private VelocityTracker velocityTracker;

    private int currentIndex = 0;
    /** 正在拖拽/动画的目标页，-1 表示当前没有进行中的切换。 */
    private int targetIndex = -1;

    private float downX;
    private float downY;
    private boolean gestureBlocked;
    private boolean dragging;
    private boolean animating;

    private OnPageSettledListener listener;

    CardPager(Context context) {
        super(context);
        ViewConfiguration configuration = ViewConfiguration.get(context);
        touchSlop = configuration.getScaledTouchSlop();
        minFlingVelocity = configuration.getScaledMinimumFlingVelocity();
        setClipChildren(true);
        setClipToPadding(true);
    }

    void setOnPageSettledListener(OnPageSettledListener listener) {
        this.listener = listener;
    }

    int getCurrentIndex() {
        return currentIndex;
    }

    /** 是否有切换/回弹动画正在进行。导航据此避免与手势抢状态。 */
    boolean isAnimating() {
        return animating || dragging;
    }

    /** 不带动画定位到某一页：只在首次搭界面时用。 */
    void setCurrentItemImmediate(int index) {
        int count = getChildCount();
        if (count == 0) return;
        currentIndex = clampIndex(index);
        targetIndex = -1;
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            child.animate().cancel();
            resetView(child);
            child.setVisibility(i == currentIndex ? VISIBLE : INVISIBLE);
        }
        invalidate();
    }

    /** 带动画切到某一页；与当前页相同或正在动画时忽略。 */
    void setCurrentItem(int index, boolean animate) {
        int count = getChildCount();
        if (count == 0 || animating) return;
        int target = clampIndex(index);
        if (target == currentIndex) return;

        View current = getChildAt(currentIndex);
        View incoming = getChildAt(target);
        int direction = target > currentIndex ? 1 : -1;

        incoming.setVisibility(VISIBLE);

        if (!animate) {
            resetView(incoming);
            current.setVisibility(INVISIBLE);
            resetView(current);
            currentIndex = target;
            targetIndex = -1;
            invalidate();
            if (listener != null) listener.onPageSettled(currentIndex);
            return;
        }

        incoming.setTranslationX(direction * width());
        incoming.setAlpha(ENTER_ALPHA);
        incoming.setScaleX(ENTER_SCALE);
        incoming.setScaleY(ENTER_SCALE);
        targetIndex = target;
        animatePair(current, -direction * width(), 0.45f, ENTER_SCALE,
                incoming, 0f, 1f, 1f, target, true);
    }

    // ------------------------------------------------------------------ 测量与布局

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        if (width <= 0) width = getSuggestedMinimumWidth();
        if (height <= 0) height = getSuggestedMinimumHeight();
        setMeasuredDimension(width, height);

        int childWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
        int childHeight = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(childWidth, childHeight);
        }
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left;
        int height = bottom - top;
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).layout(0, 0, width, height);
        }
    }

    // ------------------------------------------------------------------ 触摸处理

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            dragging = false;
            targetIndex = -1;
            // 动画中、或起点落在滑块/开关/可选文本上：本次手势整个不抢
            gestureBlocked = animating || hasHorizontalConsumer(this, downX, downY);
            recycleTracker();
            velocityTracker = VelocityTracker.obtain();
            velocityTracker.addMovement(event);
            return false;
        }

        if (gestureBlocked || animating) return false;

        if (action == MotionEvent.ACTION_MOVE) {
            if (velocityTracker != null) velocityTracker.addMovement(event);
            if (dragging) return true;

            float dx = event.getX() - downX;
            float dy = event.getY() - downY;
            // 必须是明显水平：|dx| 超过 slop，且显著大于 |dy|
            if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * HORIZONTAL_DOMINANCE) {
                // -dx 就是「想去的方向」：向左拖为正，表示想看下一页
                int neighbor = neighborFor(-dx);
                if (neighbor < 0) return false;   // 到头了，不抢手势
                beginDrag(neighbor);
                return true;
            }
        }
        return false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (gestureBlocked) return false;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                return true;

            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return false;
                if (velocityTracker != null) velocityTracker.addMovement(event);
                applyDrag(event.getX() - downX);
                return true;
            }

            case MotionEvent.ACTION_UP: {
                if (!dragging) return false;
                if (velocityTracker != null) {
                    velocityTracker.addMovement(event);
                    velocityTracker.computeCurrentVelocity(1000);
                }
                float velocityX = velocityTracker == null ? 0f : velocityTracker.getXVelocity();
                settle(event.getX() - downX, velocityX);
                recycleTracker();
                return true;
            }

            case MotionEvent.ACTION_CANCEL: {
                if (!dragging) { recycleTracker(); return false; }
                settle(0f, 0f);
                recycleTracker();
                return true;
            }

            default:
                return dragging;
        }
    }

    /** travel > 0 表示向左拖（看下一页），< 0 表示向右拖（看上一页）。 */
    private int neighborFor(float travel) {
        if (travel > 0) {
            return currentIndex + 1 < getChildCount() ? currentIndex + 1 : -1;
        }
        if (travel < 0) {
            return currentIndex - 1 >= 0 ? currentIndex - 1 : -1;
        }
        return -1;
    }

    private void beginDrag(int neighbor) {
        dragging = true;
        targetIndex = neighbor;
        int direction = neighbor > currentIndex ? 1 : -1;
        View incoming = getChildAt(neighbor);
        incoming.setVisibility(VISIBLE);
        incoming.setTranslationX(direction * width());
        incoming.setAlpha(ENTER_ALPHA);
        incoming.setScaleX(ENTER_SCALE);
        incoming.setScaleY(ENTER_SCALE);
    }

    private void applyDrag(float dx) {
        if (targetIndex < 0) return;
        int direction = targetIndex > currentIndex ? 1 : -1;
        float w = width();
        float travel = clamp(-direction * dx, 0f, w);
        float progress = travel / w;

        View current = getChildAt(currentIndex);
        View incoming = getChildAt(targetIndex);

        current.setTranslationX(-direction * travel);
        current.setAlpha(1f - 0.55f * progress);
        current.setScaleX(1f - 0.08f * progress);
        current.setScaleY(1f - 0.08f * progress);

        incoming.setTranslationX(-direction * travel + direction * w);
        incoming.setAlpha(ENTER_ALPHA + (1f - ENTER_ALPHA) * progress);
        incoming.setScaleX(ENTER_SCALE + (1f - ENTER_SCALE) * progress);
        incoming.setScaleY(ENTER_SCALE + (1f - ENTER_SCALE) * progress);
    }

    private void settle(float dx, float velocityX) {
        dragging = false;
        if (targetIndex < 0) return;

        int direction = targetIndex > currentIndex ? 1 : -1;
        float w = width();
        float travel = clamp(-direction * dx, 0f, w);
        // 速度也换算到「前进方向」，甩一下就能翻页
        float velocityAlongTravel = -direction * velocityX;
        boolean commit = travel > w * COMMIT_RATIO || velocityAlongTravel > minFlingVelocity;

        View current = getChildAt(currentIndex);
        View incoming = getChildAt(targetIndex);

        if (commit) {
            animatePair(current, -direction * w, 0.45f, ENTER_SCALE,
                    incoming, 0f, 1f, 1f, targetIndex, true);
        } else {
            // 回弹：邻居退回屏幕外
            animatePair(current, 0f, 1f, 1f,
                    incoming, direction * w, ENTER_ALPHA, ENTER_SCALE, currentIndex, false);
        }
    }

    /**
     * 同时动画两张卡片。commit=true 时结束后把旧页藏起来、索引移到新页并回调；
     * commit=false 时结束后把邻居藏起来、恢复原状。
     */
    private void animatePair(final View current, float currentX, float currentAlpha, float currentScale,
                             final View other, float otherX, float otherAlpha, float otherScale,
                             final int settleIndex, final boolean commit) {
        animating = true;

        current.animate().cancel();
        other.animate().cancel();

        current.animate()
                .translationX(currentX)
                .alpha(currentAlpha)
                .scaleX(currentScale)
                .scaleY(currentScale)
                .setDuration(ANIM_MS)
                .setInterpolator(interpolator)
                .start();

        other.animate()
                .translationX(otherX)
                .alpha(otherAlpha)
                .scaleX(otherScale)
                .scaleY(otherScale)
                .setDuration(ANIM_MS)
                .setInterpolator(interpolator)
                .withEndAction(new Runnable() {
                    @Override public void run() {
                        animating = false;
                        if (commit) {
                            current.setVisibility(INVISIBLE);
                            resetView(current);
                            other.setVisibility(VISIBLE);
                            resetView(other);
                            currentIndex = settleIndex;
                            if (listener != null) listener.onPageSettled(currentIndex);
                        } else {
                            other.setVisibility(INVISIBLE);
                            resetView(other);
                            resetView(current);
                        }
                        targetIndex = -1;
                        invalidate();
                    }
                })
                .start();
    }

    // ------------------------------------------------------------------ 工具

    private static void resetView(View view) {
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setAlpha(1f);
        view.setScaleX(1f);
        view.setScaleY(1f);
    }

    private int clampIndex(int index) {
        int count = getChildCount();
        if (count == 0) return 0;
        if (index < 0) return 0;
        if (index >= count) return count - 1;
        return index;
    }

    private int width() {
        return Math.max(1, getWidth());
    }

    private static float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private void recycleTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    /**
     * 判断这个点是否落在「自己要处理水平拖动」的控件上。
     * 这类控件（滑块、开关、可选中文本）一旦被父容器抢走手势就会失效，
     * 所以从 DOWN 开始就整段放弃拦截。
     */
    private boolean hasHorizontalConsumer(View parent, float x, float y) {
        if (!(parent instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) parent;
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            float cx = x - child.getLeft();
            float cy = y - child.getTop();
            if (cx < 0 || cy < 0 || cx >= child.getWidth() || cy >= child.getHeight()) continue;
            if (child instanceof SeekBar || child instanceof Switch) return true;
            if (child instanceof TextView && ((TextView) child).isTextSelectable()) return true;
            if (child instanceof ViewGroup && hasHorizontalConsumer(child, cx, cy)) return true;
        }
        return false;
    }
}
