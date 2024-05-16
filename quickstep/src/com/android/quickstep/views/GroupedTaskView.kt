/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.quickstep.views

import android.app.ActivityTaskManager.INVALID_TASK_ID
import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewStub
import com.android.internal.jank.Cuj
import com.android.launcher3.Flags.enableOverviewIconMenu
import com.android.launcher3.R
import com.android.launcher3.Utilities
import com.android.launcher3.config.FeatureFlags
import com.android.launcher3.util.CancellableTask
import com.android.launcher3.util.RunnableList
import com.android.launcher3.util.SplitConfigurationOptions
import com.android.launcher3.util.TransformingTouchDelegate
import com.android.quickstep.RecentsModel
import com.android.quickstep.util.RecentsOrientedState
import com.android.quickstep.util.SplitScreenUtils.Companion.convertLauncherSplitBoundsToShell
import com.android.quickstep.util.SplitSelectStateController
import com.android.systemui.shared.recents.model.Task
import com.android.systemui.shared.recents.model.ThumbnailData
import com.android.systemui.shared.recents.utilities.PreviewPositionHelper
import com.android.systemui.shared.system.InteractionJankMonitorWrapper
import com.android.wm.shell.common.split.SplitScreenConstants.PersistentSnapPosition
import java.util.function.Consumer

/**
 * TaskView that contains and shows thumbnails for not one, BUT TWO(!!) tasks
 *
 * That's right. If you call within the next 5 minutes we'll go ahead and double your order and send
 * you !! TWO !! Tasks along with their TaskThumbnailViews complimentary. On. The. House. And not
 * only that, we'll even clean up your thumbnail request if you don't like it. All the benefits of
 * one TaskView, except DOUBLED!
 *
 * (Icon loading sold separately, fees may apply. Shipping & Handling for Overlays not included).
 */
class GroupedTaskView @JvmOverloads constructor(context: Context?, attrs: AttributeSet? = null) :
    TaskView(context, attrs) {
    // TODO(b/336612373): Support new TTV for GroupedTaskView
    private val icon2CenterCoords = FloatArray(2)
    private val digitalWellBeingToast2: DigitalWellBeingToast =
        DigitalWellBeingToast(mContainer, this)
    private lateinit var snapshotView2: TaskThumbnailViewDeprecated
    private lateinit var iconView2: TaskViewIcon
    private lateinit var icon2TouchDelegate: TransformingTouchDelegate
    private var thumbnailLoadRequest2: CancellableTask<ThumbnailData>? = null
    private var iconLoadRequest2: CancellableTask<*>? = null
    var splitBoundsConfig: SplitConfigurationOptions.SplitBounds? = null
        private set

    @get:Deprecated("Use {@link #mTaskContainers} instead.")
    private val secondTask: Task
        /** Returns the second task bound to this TaskView. */
        get() {
            assert(mTaskContainers.size > 1) { "GroupedTaskView is not bound" }
            return mTaskContainers[1].task
        }

    @get:PersistentSnapPosition
    val snapPosition: Int
        /** Returns the [PersistentSnapPosition] of this pair of tasks. */
        get() {
            checkNotNull(splitBoundsConfig) { "mSplitBoundsConfig is null" }
            return splitBoundsConfig!!.snapPosition
        }

    override fun getThumbnailBounds(bounds: Rect, relativeToDragLayer: Boolean) {
        splitBoundsConfig ?: return super.getThumbnailBounds(bounds, relativeToDragLayer)
        if (relativeToDragLayer) {
            val firstThumbnailBounds = Rect()
            val secondThumbnailBounds = Rect()
            with(mContainer.dragLayer) {
                getDescendantRectRelativeToSelf(mTaskThumbnailViewDeprecated, firstThumbnailBounds)
                getDescendantRectRelativeToSelf(snapshotView2, secondThumbnailBounds)
            }
            bounds.set(firstThumbnailBounds)
            bounds.union(secondThumbnailBounds)
        } else {
            bounds.set(getSnapshotViewBounds(mTaskThumbnailViewDeprecated))
            bounds.union(getSnapshotViewBounds(snapshotView2))
        }
    }

    private fun getSnapshotViewBounds(snapshotView: View): Rect {
        val snapshotViewX = Math.round(snapshotView.x)
        val snapshotViewY = Math.round(snapshotView.y)
        return Rect(
            snapshotViewX,
            snapshotViewY,
            snapshotViewX + snapshotView.width,
            snapshotViewY + snapshotView.height
        )
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        snapshotView2 = findViewById(R.id.bottomright_snapshot)!!
        val iconViewStub2 =
            findViewById<ViewStub>(R.id.bottomRight_icon)!!.apply {
                layoutResource =
                    if (enableOverviewIconMenu()) R.layout.icon_app_chip_view
                    else R.layout.icon_view
            }
        iconView2 = iconViewStub2.inflate() as TaskViewIcon
        icon2TouchDelegate = TransformingTouchDelegate(iconView2.asView())
    }

    fun bind(
        primaryTask: Task,
        secondaryTask: Task,
        orientedState: RecentsOrientedState,
        splitBoundsConfig: SplitConfigurationOptions.SplitBounds?,
    ) {
        cancelPendingLoadTasks()
        setupTaskContainers(primaryTask)
        mTaskContainers =
            listOf(
                mTaskContainers[0].apply {
                    stagePosition = SplitConfigurationOptions.STAGE_POSITION_TOP_OR_LEFT
                },
                TaskContainer(
                    secondaryTask,
                    findViewById(R.id.bottomright_snapshot)!!,
                    iconView2,
                    SplitConfigurationOptions.STAGE_POSITION_BOTTOM_OR_RIGHT,
                    digitalWellBeingToast2
                )
            )
        snapshotView2.bind(secondaryTask)
        this.splitBoundsConfig = splitBoundsConfig
        this.splitBoundsConfig?.let {
            mTaskThumbnailViewDeprecated.previewPositionHelper.setSplitBounds(
                convertLauncherSplitBoundsToShell(it),
                PreviewPositionHelper.STAGE_POSITION_TOP_OR_LEFT
            )
            snapshotView2.previewPositionHelper.setSplitBounds(
                convertLauncherSplitBoundsToShell(it),
                PreviewPositionHelper.STAGE_POSITION_BOTTOM_OR_RIGHT
            )
        }
        setOrientationState(orientedState)
    }

    /**
     * Sets up an on-click listener and the visibility for show_windows icon on top of each task.
     */
    override fun setUpShowAllInstancesListener() {
        // sets up the listener for the left/top task
        super.setUpShowAllInstancesListener()
        if (mTaskContainers.size < 2) {
            return
        }

        // right/bottom task's base package name
        val taskPackageName = mTaskContainers[1].task.key.packageName

        // icon of the right/bottom task
        val showWindowsView = findViewById<View>(R.id.show_windows_right)!!
        updateFilterCallback(showWindowsView, getFilterUpdateCallback(taskPackageName))
    }

    override fun onTaskListVisibilityChanged(visible: Boolean, changes: Int) {
        super.onTaskListVisibilityChanged(visible, changes)
        val model = RecentsModel.INSTANCE[context]
        if (needsUpdate(changes, FLAG_UPDATE_THUMBNAIL)) {
            if (visible) {
                thumbnailLoadRequest2 =
                    model.thumbnailCache.updateThumbnailInBackground(secondTask) {
                        snapshotView2.setThumbnail(secondTask, it)
                    }
            } else {
                snapshotView2.setThumbnail(null, null)
                // Reset the task thumbnail reference as well (it will be fetched from the cache or
                // reloaded next time we need it)
                secondTask.thumbnail = null
            }
        }
        if (needsUpdate(changes, FLAG_UPDATE_ICON)) {
            if (visible) {
                iconLoadRequest2 =
                    model.iconCache.updateIconInBackground(secondTask) {
                        setIcon(iconView2, it.icon)
                        if (enableOverviewIconMenu()) {
                            setText(iconView2, it.title)
                        }
                        digitalWellBeingToast2.initialize(secondTask)
                        digitalWellBeingToast2.setSplitConfiguration(splitBoundsConfig)
                        mDigitalWellBeingToast.setSplitConfiguration(splitBoundsConfig)
                    }
            } else {
                setIcon(iconView2, null)
                if (enableOverviewIconMenu()) {
                    setText(iconView2, null)
                }
            }
        }
    }

    fun updateSplitBoundsConfig(splitBounds: SplitConfigurationOptions.SplitBounds?) {
        splitBoundsConfig = splitBounds
        invalidate()
    }

    override fun offerTouchToChildren(event: MotionEvent): Boolean {
        computeAndSetIconTouchDelegate(iconView2, icon2CenterCoords, icon2TouchDelegate)
        return if (icon2TouchDelegate.onTouchEvent(event)) {
            true
        } else super.offerTouchToChildren(event)
    }

    override fun cancelPendingLoadTasks() {
        super.cancelPendingLoadTasks()
        thumbnailLoadRequest2?.cancel()
        thumbnailLoadRequest2 = null
        iconLoadRequest2?.cancel()
        iconLoadRequest2 = null
    }

    override fun launchTaskAnimated(): RunnableList? {
        if (mTaskContainers.isEmpty()) {
            Log.d(TAG, "launchTaskAnimated - task is not bound")
            return null
        }
        val recentsView = recentsView ?: return null
        val endCallback = RunnableList()
        // Callbacks run from remote animation when recents animation not currently running
        InteractionJankMonitorWrapper.begin(
            this,
            Cuj.CUJ_SPLIT_SCREEN_ENTER,
            "Enter form GroupedTaskView"
        )
        launchTaskInternal(isQuickSwitch = false, launchingExistingTaskView = true) {
            endCallback.executeAllAndDestroy()
            InteractionJankMonitorWrapper.end(Cuj.CUJ_SPLIT_SCREEN_ENTER)
        }

        // Callbacks get run from recentsView for case when recents animation already running
        recentsView.addSideTaskLaunchCallback(endCallback)
        return endCallback
    }

    override fun launchTask(callback: Consumer<Boolean>, isQuickswitch: Boolean) {
        launchTaskInternal(isQuickswitch, false, callback /*launchingExistingTaskview*/)
    }

    /**
     * @param launchingExistingTaskView [SplitSelectStateController.launchExistingSplitPair] uses
     *   existence of GroupedTaskView as control flow of how to animate in the incoming task. If
     *   we're launching from overview (from overview thumbnails) then pass in `true`, otherwise
     *   pass in `false` for case like quickswitching from home to task
     */
    private fun launchTaskInternal(
        isQuickSwitch: Boolean,
        launchingExistingTaskView: Boolean,
        callback: Consumer<Boolean>
    ) {
        check(mTaskContainers.size >= 2) { "task not bound" }
        recentsView?.let {
            it.splitSelectController.launchExistingSplitPair(
                if (launchingExistingTaskView) this else null,
                mTaskContainers[0].task.key.id,
                mTaskContainers[1].task.key.id,
                SplitConfigurationOptions.STAGE_POSITION_TOP_OR_LEFT,
                callback,
                isQuickSwitch,
                snapPosition
            )
            Log.d(TAG, "launchTaskInternal - launchExistingSplitPair: ${taskIds.contentToString()}")
        }
    }

    public override fun refreshThumbnails(thumbnailDatas: HashMap<Int, ThumbnailData?>?) {
        super.refreshThumbnails(thumbnailDatas)
        thumbnailDatas?.get(secondTask.key.id)?.let { snapshotView2.setThumbnail(secondTask, it) }
            ?: { snapshotView2.refresh() }
    }

    /**
     * Returns taskId that split selection was initiated with, [INVALID_TASK_ID] if no tasks in this
     * TaskView are part of split selection
     */
    private fun getThisTaskCurrentlyInSplitSelection(): Int {
        val initialTaskId = recentsView?.splitSelectController?.initialTaskId
        return if (initialTaskId != null && containsTaskId(initialTaskId)) initialTaskId
        else INVALID_TASK_ID
    }

    override fun getLastSelectedChildTaskIndex(): Int {
        if (recentsView?.splitSelectController?.isDismissingFromSplitPair == true) {
            // return the container index of the task that wasn't initially selected to split
            // with because that is the only remaining app that can be selected. The coordinate
            // checks below aren't reliable since both of those views may be gone/transformed
            val initSplitTaskId = getThisTaskCurrentlyInSplitSelection()
            if (initSplitTaskId != INVALID_TASK_ID) {
                return if (initSplitTaskId == firstTask!!.key.id) 1 else 0
            }
        }

        // Check which of the two apps was selected
        if (
            iconView2.asView().containsPoint(mLastTouchDownPosition) ||
                snapshotView2.containsPoint(mLastTouchDownPosition)
        ) {
            return 1
        }
        return super.getLastSelectedChildTaskIndex()
    }

    private fun View.containsPoint(position: PointF): Boolean {
        val localPos = floatArrayOf(position.x, position.y)
        Utilities.mapCoordInSelfToDescendant(this, this@GroupedTaskView, localPos)
        return Utilities.pointInView(this, localPos[0], localPos[1], 0f /* slop */)
    }

    override fun onRecycle() {
        super.onRecycle()
        snapshotView2.setThumbnail(secondTask, null)
        splitBoundsConfig = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(widthSize, heightSize)
        val splitBoundsConfig = splitBoundsConfig ?: return
        val initSplitTaskId = getThisTaskCurrentlyInSplitSelection()
        if (initSplitTaskId == INVALID_TASK_ID) {
            pagedOrientationHandler.measureGroupedTaskViewThumbnailBounds(
                mTaskThumbnailViewDeprecated,
                snapshotView2,
                widthSize,
                heightSize,
                splitBoundsConfig,
                mContainer.deviceProfile,
                layoutDirection == LAYOUT_DIRECTION_RTL
            )
            // Should we be having a separate translation step apart from the measuring above?
            // The following only applies to large screen for now, but for future reference
            // we'd want to abstract this out in PagedViewHandlers to get the primary/secondary
            // translation directions
            mTaskThumbnailViewDeprecated.applySplitSelectTranslateX(
                mTaskThumbnailViewDeprecated.translationX
            )
            mTaskThumbnailViewDeprecated.applySplitSelectTranslateY(
                mTaskThumbnailViewDeprecated.translationY
            )
            snapshotView2.applySplitSelectTranslateX(snapshotView2.translationX)
            snapshotView2.applySplitSelectTranslateY(snapshotView2.translationY)
        } else {
            // Currently being split with this taskView, let the non-split selected thumbnail
            // take up full thumbnail area
            mTaskContainers
                .firstOrNull { it.task.key.id != initSplitTaskId }
                ?.thumbnailView
                ?.measure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(
                        heightSize - mContainer.deviceProfile.overviewTaskThumbnailTopMarginPx,
                        MeasureSpec.EXACTLY
                    )
                )
        }
        if (!enableOverviewIconMenu()) {
            updateIconPlacement()
        }
    }

    override fun setOverlayEnabled(overlayEnabled: Boolean) {
        if (FeatureFlags.enableAppPairs()) {
            super.setOverlayEnabled(overlayEnabled)
        } else {
            // Intentional no-op to prevent setting smart actions overlay on thumbnails
        }
    }

    override fun setOrientationState(orientationState: RecentsOrientedState) {
        if (enableOverviewIconMenu()) {
            splitBoundsConfig?.let {
                val groupedTaskViewSizes =
                    orientationState.orientationHandler.getGroupedTaskViewSizes(
                        mContainer.deviceProfile,
                        it,
                        layoutParams.width,
                        layoutParams.height
                    )
                val iconViewMarginStart =
                    resources.getDimensionPixelSize(
                        R.dimen.task_thumbnail_icon_menu_expanded_top_start_margin
                    )
                val iconViewBackgroundMarginStart =
                    resources.getDimensionPixelSize(
                        R.dimen.task_thumbnail_icon_menu_background_margin_top_start
                    )
                val iconMargins = (iconViewMarginStart + iconViewBackgroundMarginStart) * 2
                // setMaxWidth() needs to be called before mIconView.setIconOrientation which is
                // called in the super below.
                (mIconView as IconAppChipView).setMaxWidth(
                    groupedTaskViewSizes.first.x - iconMargins
                )
                (iconView2 as IconAppChipView).setMaxWidth(
                    groupedTaskViewSizes.second.x - iconMargins
                )
            }
        }
        super.setOrientationState(orientationState)
        iconView2.setIconOrientation(orientationState, isGridTask())
        updateIconPlacement()
    }

    override fun setThumbnailOrientation(orientationState: RecentsOrientedState?) {
        super.setThumbnailOrientation(orientationState)
        digitalWellBeingToast2.initialize(secondTask)
    }

    private fun updateIconPlacement() {
        val splitBoundsConfig = splitBoundsConfig ?: return
        val taskIconHeight = mContainer.deviceProfile.overviewTaskIconSizePx
        val isRtl = layoutDirection == LAYOUT_DIRECTION_RTL
        if (enableOverviewIconMenu()) {
            val groupedTaskViewSizes =
                pagedOrientationHandler.getGroupedTaskViewSizes(
                    mContainer.deviceProfile,
                    splitBoundsConfig,
                    layoutParams.width,
                    layoutParams.height
                )
            pagedOrientationHandler.setSplitIconParams(
                mIconView.asView(),
                iconView2.asView(),
                taskIconHeight,
                groupedTaskViewSizes.first.x,
                groupedTaskViewSizes.first.y,
                layoutParams.height,
                layoutParams.width,
                isRtl,
                mContainer.deviceProfile,
                splitBoundsConfig
            )
        } else {
            pagedOrientationHandler.setSplitIconParams(
                mIconView.asView(),
                iconView2.asView(),
                taskIconHeight,
                mTaskThumbnailViewDeprecated.measuredWidth,
                mTaskThumbnailViewDeprecated.measuredHeight,
                measuredHeight,
                measuredWidth,
                isRtl,
                mContainer.deviceProfile,
                splitBoundsConfig
            )
        }
    }

    override fun updateSnapshotRadius() {
        super.updateSnapshotRadius()
        snapshotView2.setFullscreenParams(mCurrentFullscreenParams)
    }

    override fun setIconsAndBannersTransitionProgress(progress: Float, invert: Boolean) {
        super.setIconsAndBannersTransitionProgress(progress, invert)
        // Value set by super call
        val scale = mIconView.alpha
        iconView2.setContentAlpha(scale)
        digitalWellBeingToast2.updateBannerOffset(1f - scale)
    }

    override fun setColorTint(amount: Float, tintColor: Int) {
        super.setColorTint(amount, tintColor)
        iconView2.setIconColorTint(tintColor, amount)
        snapshotView2.dimAlpha = amount
        digitalWellBeingToast2.setBannerColorTint(tintColor, amount)
    }

    override fun applyThumbnailSplashAlpha() {
        super.applyThumbnailSplashAlpha()
        snapshotView2.setSplashAlpha(mTaskThumbnailSplashAlpha)
    }

    override fun refreshTaskThumbnailSplash() {
        super.refreshTaskThumbnailSplash()
        snapshotView2.refreshSplashView()
    }

    override fun resetViewTransforms() {
        super.resetViewTransforms()
        snapshotView2.resetViewTransforms()
    }

    /**
     * Sets visibility for thumbnails and associated elements (DWB banners). IconView is unaffected.
     *
     * When setting INVISIBLE, sets the visibility for the last selected child task. When setting
     * VISIBLE (as a reset), sets the visibility for both tasks.
     */
    public override fun setThumbnailVisibility(visibility: Int, taskId: Int) {
        mTaskContainers.forEach {
            if (visibility == VISIBLE || it.task.key.id == taskId) {
                it.thumbnailView.visibility = visibility
                it.digitalWellBeingToast.setBannerVisibility(visibility)
            }
        }
    }

    companion object {
        private const val TAG = "GroupedTaskView"
    }
}