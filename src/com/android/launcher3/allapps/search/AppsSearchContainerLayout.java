/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.launcher3.allapps.search;

import static android.view.View.MeasureSpec.EXACTLY;
import static android.view.View.MeasureSpec.getSize;
import static android.view.View.MeasureSpec.makeMeasureSpec;

import static com.android.launcher3.icons.IconNormalizer.ICON_VISIBLE_AREA_FACTOR;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.PaintDrawable;
import android.net.Uri;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.text.method.TextKeyListener;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup.MarginLayoutParams;

import com.android.launcher3.DeviceProfile;
import com.android.launcher3.ExtendedEditText;
import com.android.launcher3.Insettable;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.ActivityAllAppsContainerView;
import com.android.launcher3.allapps.AllAppsStore;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.allapps.PrivateProfileManager;
import com.android.launcher3.allapps.SearchUiManager;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.util.ApiWrapper;
import com.android.launcher3.util.Themes;
import com.android.launcher3.views.ActivityContext;

import java.util.ArrayList;

/**
 * Layout to contain the All-apps search UI.
 */
public class AppsSearchContainerLayout extends ExtendedEditText
        implements SearchUiManager, SearchCallback<AdapterItem>,
        AllAppsStore.OnUpdateListener, Insettable {

    private final ActivityContext mLauncher;
    private final AllAppsSearchBarController mSearchBarController;
    private final SpannableStringBuilder mSearchQueryBuilder;

    private ActivityAllAppsContainerView<?> mAppsView;

    // The amount of pixels to shift down and overlap with the rest of the content.
    private final int mContentOverlap;
    private final int searchSideMargin;

    private Drawable gIcon, gIconThemed, sIcon, lens, lensThemed;

    public AppsSearchContainerLayout(Context context) {
        this(context, null);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AppsSearchContainerLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        mLauncher = ActivityContext.lookupContext(context);
        mSearchBarController = new AllAppsSearchBarController();

        mSearchQueryBuilder = new SpannableStringBuilder();
        Selection.setSelection(mSearchQueryBuilder, 0);

        mContentOverlap =
                getResources().getDimensionPixelSize(R.dimen.all_apps_search_bar_content_overlap);
        searchSideMargin =
                getResources().getDimensionPixelSize(R.dimen.all_apps_search_bar_margin_side);

        gIcon = getContext().getDrawable(R.drawable.ic_super_g_color);
        gIconThemed = getContext().getDrawable(R.drawable.ic_super_g_themed);
        sIcon = getContext().getDrawable(R.drawable.ic_allapps_search);
        lens = getContext().getDrawable(R.drawable.ic_lens_color);
        lensThemed = getContext().getDrawable(R.drawable.ic_lens_themed);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAppsView != null) {
            mAppsView.getAppsStore().addUpdateListener(this);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAppsView != null) {
            mAppsView.getAppsStore().removeUpdateListener(this);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Update the width to match the grid padding
        DeviceProfile dp = mLauncher.getDeviceProfile();
        int myRequestedWidth = getSize(widthMeasureSpec);

        if (mAppsView != null && mAppsView.getActiveRecyclerView() != null) {

            int rowWidth = myRequestedWidth
                    - mAppsView.getActiveRecyclerView().getPaddingLeft()
                    - mAppsView.getActiveRecyclerView().getPaddingRight();

            int cellWidth = DeviceProfile.calculateCellWidth(
                    rowWidth,
                    dp.getWorkspaceIconProfile().getCellLayoutBorderSpacePx().x,
                    dp.numShownHotseatIcons);

            int iconVisibleSize = Math.round(
                    ICON_VISIBLE_AREA_FACTOR *
                            dp.getWorkspaceIconProfile().getIconSizePx());

            int iconPadding = cellWidth - iconVisibleSize;

            int myWidth = rowWidth - iconPadding + getPaddingLeft() + getPaddingRight();
            super.onMeasure(makeMeasureSpec(myWidth, EXACTLY), heightMeasureSpec);

        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        // Shift the widget horizontally so that its centered in the parent (b/63428078)
        View parent = (View) getParent();
        if (parent != null) {
            int availableWidth = parent.getWidth()
                    - parent.getPaddingLeft()
                    - parent.getPaddingRight();

            int myWidth = right - left;
            int expectedLeft =
                    parent.getPaddingLeft() + (availableWidth - myWidth) / 2;

            setTranslationX(expectedLeft - left);
        }

        if (Utilities.showQSB(getContext())
                && !LauncherPrefs.DOCK_THEME.get(getContext())) {

            setCompoundDrawablesRelativeWithIntrinsicBounds(
                    gIcon, null, lens, null);

        } else if (Utilities.showQSB(getContext())
                && LauncherPrefs.DOCK_THEME.get(getContext())) {

            setCompoundDrawablesRelativeWithIntrinsicBounds(
                    gIconThemed, null, lensThemed, null);

        } else {
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                    sIcon, null, lens, null);
        }

        setOnTouchListener((v, event) -> {

            if (event.getAction() == MotionEvent.ACTION_UP) {

                float touchX = event.getX();

                Drawable[] drawables = getCompoundDrawables();
                Drawable rightDrawable = drawables[2];
                Drawable leftDrawable = drawables[0];

                int paddingEnd = getPaddingEnd();
                int paddingLeft = getPaddingLeft();

                if (rightDrawable != null) {

                    int rightDrawableWidth = rightDrawable.getBounds().width();

                    if (touchX >= (getWidth() - rightDrawableWidth - paddingEnd)) {

                        Intent lensIntent = new Intent(Intent.ACTION_VIEW);
                        lensIntent.setComponent(new ComponentName(
                                Utilities.GSA_PACKAGE,
                                Utilities.LENS_ACTIVITY));

                        lensIntent.setData(Uri.parse(Utilities.LENS_URI));
                        lensIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        lensIntent.putExtra("LensHomescreenShortcut", true);

                        if (lensIntent.resolveActivity(
                                getContext().getPackageManager()) != null) {

                            getContext().startActivity(lensIntent);
                        }

                        return true;
                    }
                }

                if (leftDrawable != null) {

                    int leftDrawableWidth = leftDrawable.getBounds().width();

                    if (touchX <= (leftDrawableWidth
                            + paddingLeft + searchSideMargin)) {

                        Intent gIntent = getContext()
                                .getPackageManager()
                                .getLaunchIntentForPackage(
                                        Utilities.GSA_PACKAGE);

                        if (gIntent != null) {
                            gIntent.addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                            getContext().startActivity(gIntent);
                        }

                        return true;
                    }
                }

                int leftBoundary = leftDrawable != null
                        ? (leftDrawable.getBounds().width() + paddingLeft)
                        : paddingLeft;

                int rightBoundary = rightDrawable != null
                        ? (getWidth()
                        - rightDrawable.getBounds().width()
                        - paddingEnd)
                        : (getWidth() - paddingEnd);

                if (touchX > leftBoundary && touchX < rightBoundary) {

                    Intent pixelSearchIntent =
                            getContext().getPackageManager()
                                    .getLaunchIntentForPackage(
                                            "rk.android.app.pixelsearch");

                    if (pixelSearchIntent != null) {

                        pixelSearchIntent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK
                                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);

                        getContext().startActivity(pixelSearchIntent);
                        return true;
                    }
                }
            }
            return false;
        });

        offsetTopAndBottom(mContentOverlap);
        setUpBackground();
    }

    private void setUpBackground() {
        Context context = getContext();
        float cornerRadius = getCornerRadius(context);
        int color = Themes.getAttrColor(context, R.attr.qsbFillColor);

        if (LauncherPrefs.DOCK_THEME.get(context)) {
            color = Themes.getAttrColor(context,
                    R.attr.qsbFillColorThemed);
        }

        PaintDrawable pd = new PaintDrawable(color);
        pd.setCornerRadius(cornerRadius);
        setClipToOutline(cornerRadius > 0);
        setBackground(pd);
    }

    private float getCornerRadius(Context context) {
        Resources res = context.getResources();

        float height =
                res.getDimension(R.dimen.qsb_widget_height);

        float padding =
                res.getDimension(R.dimen.qsb_widget_vertical_padding);

        float innerHeight = height - 2 * padding;

        return (innerHeight / 2f)
                * ((float) LauncherPrefs.SEARCH_RADIUS_SIZE.get(context) / 100f);
    }

    @Override
    public void initializeSearch(ActivityAllAppsContainerView<?> appsView) {
        mAppsView = appsView;
        mSearchBarController.initialize(
                new DefaultAppSearchAlgorithm(getContext(), true),
                this, mLauncher, this);
    }

    @Override
    public void onAppsUpdated() {
        mSearchBarController.refreshSearchResult();
    }

    @Override
    public void resetSearch() {
        mSearchBarController.reset();
    }

    @Override
    public void focusSearchField() {
        mSearchBarController.focusSearchField();
    }

    @Override
    public void preDispatchKeyEvent(KeyEvent event) {
        // Determine if the key event was actual text, if so, focus the search bar and then dispatch
        // the key normally so that it can process this key event
        if (!mSearchBarController.isSearchFieldFocused()
                && event.getAction() == KeyEvent.ACTION_DOWN) {

            int unicodeChar = event.getUnicodeChar();

            boolean valid = unicodeChar > 0 
                && !Character.isWhitespace(unicodeChar)
                && !Character.isSpaceChar(unicodeChar);

            if (valid) {

                boolean gotKey =
                        TextKeyListener.getInstance()
                                .onKeyDown(this,
                                        mSearchQueryBuilder,
                                        event.getKeyCode(),
                                        event);

                if (gotKey && mSearchQueryBuilder.length() > 0) {
                    mSearchBarController.focusSearchField();
                }
            }
        }
    }

    @Override
    public void onSearchResult(String query, ArrayList<AdapterItem> items) {
        if (query.equalsIgnoreCase(getContext().getString(R.string.private_space_label))) {
            privateSpaceQuery();
            return;
        }
        if (items != null) {
            mAppsView.setSearchResults(items);
        }
    }

    @Override
    public void clearSearchResult() {
        // Clear the search query
        mSearchQueryBuilder.clear();
        mSearchQueryBuilder.clearSpans();
        Selection.setSelection(mSearchQueryBuilder, 0);
        mAppsView.onClearSearchResult();
    }

    @Override
    public void setInsets(Rect insets) {
        MarginLayoutParams mlp = (MarginLayoutParams) getLayoutParams();
        mlp.topMargin = insets.top;
        requestLayout();
    }

    @Override
    public ExtendedEditText getEditText() {
        return this;
    }

    private void privateSpaceQuery() {
        PrivateProfileManager ppm =
                mAppsView.getPrivateProfileManager();

        if (ppm.isPrivateSpaceHidden()) {

            ppm.setQuietMode(false);

        } else if (!mAppsView.hasPrivateProfile()) {

            Intent intent =
                    ApiWrapper.INSTANCE.get(getContext())
                            .getPrivateSpaceSettingsIntent();

            if (intent != null) {
                mLauncher.startActivitySafely(
                        mAppsView, intent, null);
            }
        }
    }
}
