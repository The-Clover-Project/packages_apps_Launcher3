/*
 * Copyright (C) 2024 The Clover Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher

import com.android.launcher3.uioverrides.QuickstepLauncher
import com.android.systemui.plugins.shared.LauncherOverlayManager

class CloverQuickstepLauncher : QuickstepLauncher() {

    companion object {
        private const val TAG = "CloverQuickstepLauncher"
    }

    override fun getDefaultOverlay(): LauncherOverlayManager {
        return OverlayCallbackImpl(this)
    }

}