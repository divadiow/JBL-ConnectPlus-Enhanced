package uk.co.divadiow.connectplusx;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/** Minimal dependency-free flow layout so controls wrap on narrow phones and larger accessibility fonts. */
final class FlowLayout extends ViewGroup {
    FlowLayout(Context context) { super(context); }
    FlowLayout(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int maxWidth = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() - getPaddingRight();
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        if (widthMode == MeasureSpec.UNSPECIFIED) maxWidth = Integer.MAX_VALUE;

        int lineWidth = 0;
        int lineHeight = 0;
        int usedWidth = 0;
        int usedHeight = 0;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, usedHeight);
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            int childHeight = child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
            if (lineWidth > 0 && lineWidth + childWidth > maxWidth) {
                usedWidth = Math.max(usedWidth, lineWidth);
                usedHeight += lineHeight;
                lineWidth = 0;
                lineHeight = 0;
            }
            lineWidth += childWidth;
            lineHeight = Math.max(lineHeight, childHeight);
        }
        usedWidth = Math.max(usedWidth, lineWidth);
        usedHeight += lineHeight;
        setMeasuredDimension(
                resolveSize(usedWidth + getPaddingLeft() + getPaddingRight(), widthMeasureSpec),
                resolveSize(usedHeight + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int maxWidth = right - left - getPaddingRight();
        int x = getPaddingLeft();
        int y = getPaddingTop();
        int lineHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            int totalWidth = lp.leftMargin + childWidth + lp.rightMargin;
            if (x > getPaddingLeft() && x + totalWidth > maxWidth) {
                x = getPaddingLeft();
                y += lineHeight;
                lineHeight = 0;
            }
            int childLeft = x + lp.leftMargin;
            int childTop = y + lp.topMargin;
            child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
            x += totalWidth;
            lineHeight = Math.max(lineHeight, lp.topMargin + childHeight + lp.bottomMargin);
        }
    }

    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
    @Override public LayoutParams generateLayoutParams(AttributeSet attrs) {
        return new MarginLayoutParams(getContext(), attrs);
    }
    @Override protected LayoutParams generateLayoutParams(LayoutParams source) {
        return new MarginLayoutParams(source);
    }
    @Override protected boolean checkLayoutParams(LayoutParams params) {
        return params instanceof MarginLayoutParams;
    }
}
